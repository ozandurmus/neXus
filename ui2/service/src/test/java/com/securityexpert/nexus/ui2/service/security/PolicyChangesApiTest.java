package com.securityexpert.nexus.ui2.service.security;

import com.securityexpert.nexus.ui2.service.api.PolicyChangesController;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.platform.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;

class PolicyChangesApiTest {
    @Test void bothRoutesRequirePolicyReadAndMaskReplayViewers() throws Exception {
        var sessions = mock(SessionRepository.class); var rbac = mock(RbacEvaluator.class);
        var service = mock(PolicyChangesService.class);
        var names = new TopologyNamePseudonymizer(new byte[32]);
        when(service.changes(any(), anyBoolean())).thenReturn(new PolicyResponse(Map.of("rows", List.of(Map.of(
            "policyName", "Synthetic policy", "name", "Synthetic rule", "number", 1, "changedBy", "Synthetic actor", "values", List.of("192.0.2.8"))))));
        doAnswer(call -> { ((java.io.OutputStream) call.getArgument(2)).write("Time,Actor\r\n2026-10-09,ACTOR-ALPHA-01".getBytes(java.nio.charset.StandardCharsets.UTF_8)); return null; })
            .when(service).csv(any(), anyBoolean(), any());
        var mvc = MockMvcBuilders.standaloneSetup(new PolicyChangesController(service))
            .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), names))
            .addInterceptors(new GateChainInterceptor(new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class)), SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        for (String path : List.of("/api/v2/policy/changes", "/api/v2/policy/changes.csv")) {
            mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        }
        for (String role : List.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER, RoleToken.VIEWER)) {
            String cookie = "synthetic-session-" + role, actor = "synthetic-actor-" + role;
            Instant now = Instant.now();
            var session = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE,
                now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty());
            when(sessions.findBySessionId(eq(session.sessionId()), any(Instant.class))).thenReturn(Optional.of(session));
            boolean allowed = !role.equals(RoleToken.VIEWER), masked = role.equals(RoleToken.REPLAY_VIEWER);
            when(rbac.evaluateAny(eq(actor), eq(new ActionRegistry().find(ActionRegistry.POLICY_READ).orElseThrow().requiredRoleTokens()), any()))
                .thenReturn(new RbacEvaluator.Decision(allowed ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty()));
            when(rbac.evaluate(eq(actor), eq(Optional.of(RoleToken.REPLAY_VIEWER)), any()))
                .thenReturn(new RbacEvaluator.Decision(masked ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty()));
            for (String path : List.of("/api/v2/policy/changes", "/api/v2/policy/changes.csv")) {
                var result = mvc.perform(get(path).servletPath(path).cookie(new Cookie("ui2_session", cookie)))
                    .andExpect(status().is(allowed ? 200 : 403)).andReturn();
                if (!allowed) continue;
                if (path.endsWith(".csv")) {
                    assertTrue(result.getRequest().isAsyncStarted());
                    result.getAsyncResult(5000);
                    result = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
                    assertEquals("text/csv;charset=UTF-8", result.getResponse().getContentType());
                    assertEquals("attachment; filename=policy-changes.csv", result.getResponse().getHeader("Content-Disposition"));
                    assertEquals(String.valueOf(masked), result.getResponse().getHeader(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER));
                    assertEquals("Time,Actor\r\n2026-10-09,ACTOR-ALPHA-01", result.getResponse().getContentAsString());
                    verify(service, atLeastOnce()).csv(any(), eq(masked), any());
                } else if (masked) {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("Synthetic")); assertFalse(body.contains("192.0.2.8"));
                    assertTrue(body.contains(names.maskPolicyName("last-modifier", "Synthetic actor")));
                }
                assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
            }
        }
    }
    @Test void invalidFiltersReturnBadRequestWithoutExport() throws Exception {
        var service = mock(PolicyChangesService.class);
        doThrow(new IllegalArgumentException()).when(service).validate(any());
        when(service.changes(any(), anyBoolean())).thenThrow(new IllegalArgumentException());
        var mvc = MockMvcBuilders.standaloneSetup(new PolicyChangesController(service)).build();
        for (String path : List.of("/api/v2/policy/changes", "/api/v2/policy/changes.csv"))
            mvc.perform(get(path).param("limit", "201")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("INVALID_CHANGE_FILTERS"));
        verify(service, never()).csv(any(), anyBoolean(), any());
    }
}
