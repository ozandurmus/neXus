package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.securityexpert.nexus.ui2.service.api.PolicyController;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.Mode;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;

class PolicyApiTest {
    @Test void policyRoutesAllowMaskedCollectionWithoutOtherWrites() throws Exception {
        var action = new ActionRegistry().find(ActionRegistry.POLICY_READ).orElseThrow();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN, RoleToken.REPLAY_VIEWER), action.requiredRoleTokens());
        for (String route : List.of("GET /api/v2/policy/tree", "GET /api/v2/policy/collections/*", "GET /api/v2/policy/devices", "GET /api/v2/policy/devices/*", "GET /api/v2/policy/policies/*", "GET /api/v2/policy/policies/*/history", "GET /api/v2/policy/objects/*", "GET /api/v2/policy/domains/*/objects", "GET /api/v2/policy/domains/*/unused", "GET /api/v2/policy/domains/*/gateways", "GET /api/v2/policy/domains/*/hits", "GET /api/v2/policy/domains/*/object-usage", "GET /api/v2/policy/domains/*/objects/duplicates", "GET /api/v2/policy/domains/*/installation"))
            assertEquals(ActionRegistry.POLICY_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route));
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN, RoleToken.REPLAY_VIEWER),
                new ActionRegistry().find(ActionRegistry.POLICY_COLLECT).orElseThrow().requiredRoleTokens());
        assertEquals(ActionRegistry.POLICY_COLLECT, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/policy/sources/*/collect"));
        // The request cannot carry a command, endpoint, credential or arbitrary transport parameters.
        assertEquals(List.of("domainRef", "mode"), Arrays.stream(
                com.securityexpert.nexus.ui2.service.api.PolicyCollectionController.CollectRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        var collections = mock(PolicyCollectionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new com.securityexpert.nexus.ui2.service.api.PolicyCollectionController(collections, mock(RbacEvaluator.class))).build();
        String path = "/api/v2/policy/sources/source-1/collect";
        for (String body : List.of("{\"domainRef\":\"domain-1\"}",
                "{\"domainRef\":\"domain-1\",\"mode\":\"CHANGED_ONLY\"}",
                "{\"domainRef\":\"domain-1\",\"mode\":\"FULL\"}")) {
            Mode mode = body.contains("FULL") ? Mode.FULL : Mode.CHANGED_ONLY;
            when(collections.collect("source-1", "domain-1", "synthetic-actor", mode)).thenReturn(Optional.of("job-1"));
            mvc.perform(post(path).contentType("application/json").content(body)
                    .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("job-1"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verify(collections, times(2)).collect("source-1", "domain-1", "synthetic-actor", Mode.CHANGED_ONLY);
        verify(collections).collect("source-1", "domain-1", "synthetic-actor", Mode.FULL);
        clearInvocations(collections);
        mvc.perform(post(path).contentType("application/json")
                .content("{\"domainRef\":\"domain-1\",\"mode\":\"INVALID\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_COLLECTION_MODE"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(collections);
    }
    @Test void mvcEnforcesSessionRbacMaskingAndNoStoreForEachPersona() throws Exception {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var audit = mock(AuthzDecisionRepository.class);
        var query = mock(PolicyQueryService.class);
        var collections = mock(PolicyCollectionService.class);
        when(collections.collect(eq("pan-1"), eq(""), anyString(), eq(Mode.CHANGED_ONLY))).thenReturn(Optional.of("job-1"));
        var meta = new PolicySnapshot.Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "domain-1", "Synthetic domain",
                "Synthetic policy", "2026-10-01T12:00:00Z", "artifact-1", List.of());
        var snapshot = new PolicySnapshot(meta, List.of(), Map.of());
        when(query.find("policy-1")).thenReturn(Optional.of(snapshot));
        when(query.history("policy-1", "", 0)).thenReturn(new PolicyResponse(Map.of("revisions", List.of(), "page", 0)));
        var body = new LinkedHashMap<String, Object>();
        body.put("id", "policy-1"); body.put("sourceId", "manager-1"); body.put("name", "Synthetic policy"); body.put("uuid", "native-synthetic-uuid");
        body.put("comment", "Unregistered synthetic hostname"); body.put("values", List.of("192.0.2.8", "host.example.invalid"));
        body.put("extras", Map.of("unknown-secret-field", List.of("synthetic-withheld-value")));
        when(query.page(eq(snapshot), eq(0), eq(""), anyBoolean())).thenReturn(new PolicyResponse(body));
        when(query.tree("", "", "")).thenReturn(new PolicyResponse(Map.of("sources", List.of(Map.of("sourceId", "manager-1", "sourceName", "Synthetic manager", "vendor", "PAN")))));
        when(collections.status("job-1")).thenReturn(Optional.of(Map.of("jobId", "job-1", "state", "FAILED", "step", 2, "total", 6,
            "reason", "show devicegroups target=manager-1: HTTP_403")));
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
            when(rbac.evaluateAny(eq(actor), eq(new ActionRegistry().find(ActionRegistry.POLICY_COLLECT).orElseThrow().requiredRoleTokens()), any())).thenReturn(
                    new RbacEvaluator.Decision(allowed ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty()));
            for (String view : List.of("objects", "unused", "gateways", "hits", "object-usage", "objects/duplicates", "installation")) {
                when(query.domainInventory("manager-1", "domain-1", view, 0)).thenReturn(Optional.of(new PolicyResponse(body)));
                when(query.domainHits("manager-1", "domain-1", 0)).thenReturn(Optional.of(new PolicyResponse(body)));
                when(query.domainObjects(eq("manager-1"), eq("domain-1"), eq(0), eq(""), eq(""), eq(""), anyBoolean())).thenReturn(Optional.of(new PolicyResponse(body)));
                when(query.objectUsage("manager-1", "domain-1", "uid-1", 0)).thenReturn(Optional.of(new PolicyResponse(body)));
                when(query.objectDuplicates(eq("manager-1"), eq("domain-1"), eq(0), eq(""), anyBoolean())).thenReturn(Optional.of(new PolicyResponse(body)));
                when(query.installations(eq("manager-1"), eq("domain-1"), eq(0), eq(""), eq(""), anyBoolean())).thenReturn(Optional.of(new PolicyResponse(body)));
                String path = "/api/v2/policy/domains/domain-1/" + view;
                mvc.perform(get(path).servletPath(path).param("source", "manager-1").param("uid", "uid-1")).andExpect(status().isUnauthorized());
                var result = mvc.perform(get(path).servletPath(path).param("source", "manager-1").param("uid", "uid-1")
                    .cookie(new Cookie("ui2_session", cookie))).andExpect(status().is(allowed ? 200 : 403)).andReturn();
                if (allowed) {
                    assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
                    if (role.equals(RoleToken.REPLAY_VIEWER)) {
                        assertFalse(result.getResponse().getContentAsString().contains("Synthetic policy"));
                        assertFalse(result.getResponse().getContentAsString().contains("192.0.2.8"));
                    }
                }
            }
            String collectPath = "/api/v2/policy/sources/pan-1/collect";
            mvc.perform(post(collectPath).servletPath(collectPath).cookie(new Cookie("ui2_session", cookie))
                    .contentType("application/json").content("{\"domainRef\":\"\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(collectPath).servletPath(collectPath).cookie(new Cookie("ui2_session", cookie))
                    .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid").contentType("application/json").content("{\"domainRef\":\"\"}"))
                    .andExpect(status().is(allowed ? 202 : 403));
            // Use the repository's session-cookie name, the same boundary used by every controller.
            var response = mvc.perform(get("/api/v2/policy/policies/policy-1").servletPath("/api/v2/policy/policies/policy-1").cookie(new Cookie("ui2_session", cookie)));
            for (String path : List.of("/api/v2/policy/tree", "/api/v2/policy/collections/job-1", "/api/v2/policy/policies/policy-1/history")) {
                var read = mvc.perform(get(path).servletPath(path).cookie(new Cookie("ui2_session", cookie)));
                read.andExpect(status().is(allowed ? 200 : 403));
                if (allowed) read.andExpect(header().string("Cache-Control", "no-store"));
                if (role.equals(RoleToken.REPLAY_VIEWER)) {
                    String safe = read.andExpect(header().string("X-Nexus-Masked", "true")).andReturn().getResponse().getContentAsString();
                    assertFalse(safe.contains("Synthetic manager"));
                    if (path.contains("collections")) assertTrue(safe.contains("show devicegroups target=manager-1: HTTP_403"));
                }
            }
            if (!allowed) { response.andExpect(status().isForbidden()); continue; }
            String text = response.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
            if (role.equals(RoleToken.REPLAY_VIEWER)) {
                response.andExpect(header().string("X-Nexus-Masked", "true"));
                for (String raw : List.of("Synthetic policy", "native-synthetic-uuid", "Unregistered synthetic hostname", "192.0.2.8", "host.example.invalid", "synthetic-withheld-value")) assertFalse(text.contains(raw));
                assertTrue(text.contains(names.maskPolicyName("policy", "Synthetic policy")));
            } else assertTrue(text.contains("Synthetic policy"));
        }
    }
    @Test void replayAndMachineCollectionRequireCsrfOriginAndPreserveWriteDenials() throws Exception {
        for (boolean machine : List.of(false, true)) {
            var sessions = mock(SessionRepository.class);
            var rbac = mock(RbacEvaluator.class);
            var collections = mock(PolicyCollectionService.class);
            String cookie = "synthetic-policy-session", actor = "synthetic-policy-actor";
            Instant now = Instant.now();
            var session = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE,
                    now, now, now.plusSeconds(3600), now.plusSeconds(7200),
                    Optional.empty(), Optional.empty(), Optional.empty(), machine);
            when(sessions.findBySessionId(eq(session.sessionId()), any(Instant.class))).thenReturn(Optional.of(session));
            when(rbac.evaluateAny(eq(actor), anySet(), any(Instant.class))).thenAnswer(call -> {
                Set<String> roles = call.getArgument(1);
                return new RbacEvaluator.Decision(roles.contains(RoleToken.REPLAY_VIEWER)
                        ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                        Optional.of("test"), Optional.empty(), Optional.empty());
            });
            when(rbac.evaluate(eq(actor), any(), any(Instant.class))).thenAnswer(call -> {
                Optional<String> role = call.getArgument(1);
                return new RbacEvaluator.Decision(role.filter(RoleToken.REPLAY_VIEWER::equals).isPresent()
                        ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                        Optional.of("test"), Optional.empty(), Optional.empty());
            });
            when(collections.sources()).thenReturn(List.of());
            when(collections.collect("source-1", "", actor, Mode.CHANGED_ONLY)).thenReturn(Optional.of("job-1"));
            var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
            var mvc = MockMvcBuilders.standaloneSetup(
                    new com.securityexpert.nexus.ui2.service.api.PolicyCollectionController(collections, rbac),
                    new PolicyWriteProbe()).setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(
                        new SubnetPreservingIpMasker(new byte[32]), new TopologyNamePseudonymizer(new byte[32])))
                    .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
            String sources = "/api/v2/policy/sources", collect = sources + "/source-1/collect";
            mvc.perform(get(sources).servletPath(sources).cookie(new Cookie("ui2_session", cookie)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.canCollect").value(true))
                    .andExpect(jsonPath("$.canCancel").value(false))
                    .andExpect(header().string("X-Nexus-Masked", "true"));
            for (String csrf : List.of("", "wrong")) {
                mvc.perform(post(collect).servletPath(collect).cookie(new Cookie("ui2_session", cookie))
                        .header("X-CSRF-Token", csrf).header("Origin", "https://example.invalid")
                        .contentType("application/json").content("{\"domainRef\":\"\"}"))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(post(collect).servletPath(collect).cookie(new Cookie("ui2_session", cookie))
                    .header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content("{\"domainRef\":\"\"}"))
                    .andExpect(status().isUnauthorized());
            verify(collections, never()).collect(anyString(), anyString(), anyString(), any(Mode.class));
            mvc.perform(post(collect).servletPath(collect).cookie(new Cookie("ui2_session", cookie))
                    .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid")
                    .contentType("application/json").content("{\"domainRef\":\"\"}"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("job-1"));
            verify(collections).collect("source-1", "", actor, Mode.CHANGED_ONLY);
            when(collections.collect("source-1", "", actor, Mode.CHANGED_ONLY)).thenReturn(Optional.empty());
            mvc.perform(post(collect).servletPath(collect).cookie(new Cookie("ui2_session", cookie))
                    .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid")
                    .contentType("application/json").content("{\"domainRef\":\"\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("POLICY_SOURCE_BUSY_OR_INELIGIBLE"));
            for (String path : List.of(sources, sources + "/source-1", sources + "/source-1/cancel",
                    "/api/v2/policy/collections/job-1/cancel", "/api/v2/jobs/job-1/cancel")) {
                for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                    mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path).servletPath(path)
                            .cookie(new Cookie("ui2_session", cookie)).header("X-CSRF-Token", "synthetic-csrf")
                            .header("Origin", "https://example.invalid")).andExpect(status().isForbidden());
                }
            }
        }
    }
    @org.springframework.web.bind.annotation.RestController
    static class PolicyWriteProbe {
        @org.springframework.web.bind.annotation.RequestMapping(method = {org.springframework.web.bind.annotation.RequestMethod.POST,
                org.springframework.web.bind.annotation.RequestMethod.PUT, org.springframework.web.bind.annotation.RequestMethod.PATCH,
                org.springframework.web.bind.annotation.RequestMethod.DELETE}, value = {"/api/v2/policy/sources", "/api/v2/policy/sources/{id}",
                "/api/v2/policy/sources/{id}/cancel", "/api/v2/policy/collections/{id}/cancel", "/api/v2/jobs/{id}/cancel"})
        public void write() { fail("Policy writes must never reach the controller"); }
    }
    @Test void controllerBoundsPagingAndScopesObjectsToPolicyAssignments() {
        var query = mock(PolicyQueryService.class); var controller = new PolicyController(query);
        assertEquals(400, controller.page("policy-1", -1, "", new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        assertEquals(400, controller.page("policy-1", 0, "x".repeat(1001), new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        assertEquals(400, controller.object("object-1", "", "").getStatusCode().value());
        assertEquals(400, controller.objects("domain-1", "", 0, "", "", "", new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        assertEquals(400, controller.hits("domain-1", "source-1", -1).getStatusCode().value());
        when(query.domainObjects(eq("source-1"), eq("domain-1"), eq(0), eq(""), eq(""), eq(""), anyBoolean())).thenReturn(Optional.empty());
        assertEquals(404, controller.objects("domain-1", "source-1", 0, "", "", "", new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        when(query.domainInventory("source-1", "domain-1", "unused", 0)).thenReturn(Optional.of(new PolicyResponse(Map.of("objects", List.of(), "total", 0))));
        assertEquals(200, controller.unused("domain-1", "source-1", 0).getStatusCode().value());
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        assertEquals(400, controller.objects("domain-1", "source-1", 0, "", "unknown-type", "", request).getStatusCode().value());
        assertEquals(400, controller.objects("domain-1", "source-1", 0, "", "", "bad-filter", request).getStatusCode().value());
        assertEquals(400, controller.usage("domain-1", "uid-1", "source-1", -1).getStatusCode().value());
        assertEquals(400, controller.duplicates("domain-1", "source-1", 0, "x".repeat(1001), request).getStatusCode().value());
        assertEquals(400, controller.installation("domain-1", "", 0, "", "", request).getStatusCode().value());
        assertEquals(400, controller.filteredPage("policy-1", 0, "", "bad-filter", 90, request).getStatusCode().value());
        assertEquals(400, controller.filteredPage("policy-1", 0, "", "inactive", 0, request).getStatusCode().value());
        when(query.catalog()).thenReturn(List.of());
        assertEquals(200, controller.device("missing", "", 0, "", new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value());
        assertEquals(404, controller.object("object-1", "missing", "policy-1").getStatusCode().value());
    }
}
