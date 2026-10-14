package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.securityexpert.nexus.ui2.service.api.OfflineAnalysisController;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;

class OfflineAnalysisApiTest {
    @Test void registeredRoutesAreSingleWildcardAndWritesRequireAdmin() {
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN), registry.find(ActionRegistry.POLICY_OFFLINE_WRITE).orElseThrow().requiredRoleTokens());
        for (String route : List.of("POST /api/v2/policy/policies/*/offline-analyses", "DELETE /api/v2/policy/offline-analyses/*"))
            assertEquals(ActionRegistry.POLICY_OFFLINE_WRITE, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route));
        for (String route : List.of("GET /api/v2/policy/policies/*/offline-analyses", "GET /api/v2/policy/offline-analyses/*"))
            assertEquals(ActionRegistry.POLICY_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route));
        SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet().stream().filter(r -> r.contains("offline-analyses"))
            .forEach(r -> assertEquals(1, r.chars().filter(c -> c == '*').count()));
    }
    @Test void uploadGateNeverInvokesContainerMultipartParsing() throws Exception {
        var chain = mock(GateChain.class);
        when(chain.machineWriteRefusal(any(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(chain.evaluate(any(), any())).thenReturn(new GateOutcome.Refused("RBAC", 403, Map.of("error", "DENIED")));
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v2/policy/policies/p1/offline-analyses") {
            @Override public String getParameter(String name) { fail("Container multipart parsing must not run"); return null; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { fail("Upload must not be read before authorization"); return null; }
        };
        request.setServletPath("/api/v2/policy/policies/p1/offline-analyses");
        request.setContentType("multipart/form-data; boundary=SyntheticBoundary");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(new GateChainInterceptor(chain, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE).preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }
    @Test void realGateRefusesViewerUploadDeleteAndMasksStoredReads() throws Exception {
        var sessions = mock(SessionRepository.class); var rbac = mock(RbacEvaluator.class); var audit = mock(AuthzDecisionRepository.class);
        var analyses = mock(OfflineAnalysisService.class); var policies = mock(PolicyQueryService.class);
        when(analyses.find("analysis-1")).thenReturn(Optional.of(Map.of("id", "analysis-1", "fileName", "synthetic-private.csv",
            "observedSource", List.of("192.0.2.8"), "flowCount", 12)));
        var controller = new OfflineAnalysisController(policies, analyses, rbac);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(
            new SubnetPreservingIpMasker(new byte[32]), new TopologyNamePseudonymizer(new byte[32])))
            .addInterceptors(new GateChainInterceptor(new GateChain(sessions, new ActionRegistry(), rbac, audit), SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        String cookie = "synthetic-offline-viewer", actor = "synthetic-viewer"; Instant now = Instant.now();
        var session = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE, now, now,
            now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty(), false);
        when(sessions.findBySessionId(eq(session.sessionId()), any())).thenReturn(Optional.of(session));
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        when(rbac.evaluateAny(eq(actor), anySet(), any())).thenAnswer(invocation -> ((Set<?>) invocation.getArgument(1)).contains(RoleToken.REPLAY_VIEWER) ? permitted : denied);
        when(rbac.evaluate(eq(actor), any(), any())).thenReturn(permitted);
        String read = "/api/v2/policy/offline-analyses/analysis-1", upload = "/api/v2/policy/policies/p1/offline-analyses";
        mvc.perform(get(read).servletPath(read)).andExpect(status().isUnauthorized());
        String masked = mvc.perform(get(read).servletPath(read).cookie(new Cookie("ui2_session", cookie)))
            .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "true"))
            .andExpect(jsonPath("$.flowCount").value(12)).andReturn().getResponse().getContentAsString();
        assertFalse(masked.contains("synthetic-private.csv")); assertFalse(masked.contains("192.0.2.8"));
        mvc.perform(post(upload).servletPath(upload).cookie(new Cookie("ui2_session", cookie)).header("X-CSRF-Token", "synthetic-csrf")
            .header("Origin", "https://example.invalid").contentType("multipart/form-data; boundary=SyntheticBoundary")).andExpect(status().isForbidden());
        mvc.perform(delete(read).servletPath(read).cookie(new Cookie("ui2_session", cookie)).header("X-CSRF-Token", "synthetic-csrf")
            .header("Origin", "https://example.invalid")).andExpect(status().isForbidden());
        verify(analyses, never()).delete(anyString(), anyString()); verify(analyses, never()).save(anyString(), anyString(), anyString(), anyMap());
    }
}
