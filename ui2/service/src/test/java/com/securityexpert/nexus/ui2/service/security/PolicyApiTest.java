package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.securityexpert.nexus.ui2.service.api.PolicyController;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;

class PolicyApiTest {
    @Test void policyRoutesKeepReadsSeparateFromAdminCollection() {
        var action = new ActionRegistry().find(ActionRegistry.POLICY_READ).orElseThrow();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN, RoleToken.REPLAY_VIEWER), action.requiredRoleTokens());
        for (String route : List.of("GET /api/v2/policy/devices", "GET /api/v2/policy/devices/*", "GET /api/v2/policy/policies/*", "GET /api/v2/policy/objects/*"))
            assertEquals(ActionRegistry.POLICY_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route));
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN),
                new ActionRegistry().find(ActionRegistry.POLICY_COLLECT).orElseThrow().requiredRoleTokens());
        assertEquals(ActionRegistry.POLICY_COLLECT, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/policy/sources/*/collect"));
    }
    @Test void mvcEnforcesSessionRbacMaskingAndNoStoreForEachPersona() throws Exception {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var audit = mock(AuthzDecisionRepository.class);
        var query = mock(PolicyQueryService.class);
        var collections = mock(PolicyCollectionService.class);
        when(collections.collect(eq("pan-1"), eq(""), anyString())).thenReturn(Optional.of("job-1"));
        var meta = new PolicySnapshot.Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "domain-1", "Synthetic domain",
                "Synthetic policy", "2026-10-01T12:00:00Z", "artifact-1", List.of());
        var snapshot = new PolicySnapshot(meta, List.of(), Map.of());
        when(query.find("policy-1")).thenReturn(Optional.of(snapshot));
        var body = new LinkedHashMap<String, Object>();
        body.put("id", "policy-1"); body.put("name", "Synthetic policy"); body.put("uuid", "native-synthetic-uuid");
        body.put("comment", "Unregistered synthetic hostname"); body.put("values", List.of("192.0.2.8", "host.example.invalid"));
        body.put("extras", Map.of("unknown-secret-field", List.of("synthetic-withheld-value")));
        when(query.page(eq(snapshot), eq(0), eq(""), anyBoolean())).thenReturn(new PolicyResponse(body));
        var names = new TopologyNamePseudonymizer(new byte[32]);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), names);
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, audit);
        var mvc = MockMvcBuilders.standaloneSetup(new PolicyController(query), new com.securityexpert.nexus.ui2.service.api.PolicyCollectionController(collections, rbac)).setControllerAdvice(advice)
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        mvc.perform(get("/api/v2/policy/policies/policy-1").servletPath("/api/v2/policy/policies/policy-1")).andExpect(status().isUnauthorized());
        for (String role : List.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN, RoleToken.REPLAY_VIEWER, RoleToken.VIEWER)) {
            String cookie = "synthetic-session-" + role;
            String actor = "synthetic-actor-" + role;
            Instant now = Instant.now();
            var session = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE,
                    now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty());
            when(sessions.findBySessionId(eq(session.sessionId()), any(Instant.class))).thenReturn(Optional.of(session));
            boolean allowed = !role.equals(RoleToken.VIEWER);
            var decision = new RbacEvaluator.Decision(allowed ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                    Optional.of("test"), Optional.empty(), Optional.empty());
            when(rbac.evaluateAny(eq(actor), eq(new ActionRegistry().find(ActionRegistry.POLICY_READ).orElseThrow().requiredRoleTokens()), any())).thenReturn(decision);
            when(rbac.evaluate(eq(actor), eq(Optional.of(RoleToken.REPLAY_VIEWER)), any())).thenReturn(
                    new RbacEvaluator.Decision(role.equals(RoleToken.REPLAY_VIEWER) ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty()));
            boolean admin = role.equals(RoleToken.SECURITY_ADMIN) || role.equals(RoleToken.ONBOARDING_ADMIN);
            when(rbac.evaluateAny(eq(actor), eq(new ActionRegistry().find(ActionRegistry.POLICY_COLLECT).orElseThrow().requiredRoleTokens()), any())).thenReturn(
                    new RbacEvaluator.Decision(admin ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty()));
            String collectPath = "/api/v2/policy/sources/pan-1/collect";
            mvc.perform(post(collectPath).servletPath(collectPath).cookie(new Cookie("ui2_session", cookie))
                    .contentType("application/json").content("{\"domainRef\":\"\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(collectPath).servletPath(collectPath).cookie(new Cookie("ui2_session", cookie))
                    .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid").contentType("application/json").content("{\"domainRef\":\"\"}"))
                    .andExpect(status().is(admin ? 202 : 403));
            // Use the repository's session-cookie name, the same boundary used by every controller.
            var response = mvc.perform(get("/api/v2/policy/policies/policy-1").servletPath("/api/v2/policy/policies/policy-1").cookie(new Cookie("ui2_session", cookie)));
            if (!allowed) { response.andExpect(status().isForbidden()); continue; }
            String text = response.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
            if (role.equals(RoleToken.REPLAY_VIEWER)) {
                response.andExpect(header().string("X-Nexus-Masked", "true"));
                for (String raw : List.of("Synthetic policy", "native-synthetic-uuid", "Unregistered synthetic hostname", "192.0.2.8", "host.example.invalid", "synthetic-withheld-value")) assertFalse(text.contains(raw));
                assertTrue(text.contains(names.maskPolicyName("name", "Synthetic policy")));
            } else assertTrue(text.contains("Synthetic policy"));
        }
    }
    @Test void controllerBoundsPagingAndScopesObjectsToPolicyAssignments() {
        var query = mock(PolicyQueryService.class); var controller = new PolicyController(query);
        assertEquals(400, controller.page("policy-1", -1, "", new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        assertEquals(400, controller.page("policy-1", 0, "x".repeat(201), new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        assertEquals(400, controller.object("object-1", "", "").getStatusCode().value());
        when(query.catalog()).thenReturn(List.of());
        assertEquals(200, controller.device("missing", "", 0, "", new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        assertEquals(404, controller.object("object-1", "missing", "policy-1").getStatusCode().value());
    }
}
