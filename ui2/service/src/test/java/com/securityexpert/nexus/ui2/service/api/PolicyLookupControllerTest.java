package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;

class PolicyLookupControllerTest {
    private final PolicyQueryService policies = mock(PolicyQueryService.class);
    private final AuthzDecisionRepository audit = mock(AuthzDecisionRepository.class);
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    private final PolicyLookupController controller = new PolicyLookupController(policies, audit, names, ips);
    private final Map<String, Object> input = Map.of("source", "192.0.2.8", "destination", "198.51.100.8", "service", "tcp/443");
    private MockHttpServletRequest request(String actor) {
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actor);
        request.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "synthetic-session"); return request;
    }
    private void snapshot() {
        var meta = new Metadata("p1", "s1", "MGR-ALPHA", "PAN", "c1", "DOM-ALPHA", "POL-ALPHA", "2026-10-09T12:00:00Z", "a1", List.of());
        when(policies.find("p1")).thenReturn(Optional.of(new PolicySnapshot(meta,
            List.of(new Section(ref("p1", "Shared", "pre-rulebase"), "Pre rules", "Shared", null, List.of())), Map.of())));
    }
    @Test void strictInputValidationMissingPolicyAndNoSession() {
        assertEquals(401, controller.lookup("p1", input, new MockHttpServletRequest()).getStatusCode().value());
        for (Map<String, Object> bad : List.of(Map.<String, Object>of(), Map.<String, Object>of("source", 42),
                Map.<String, Object>of("source", "192.0.2.8", "destination", "198.51.100.8", "service", "tcp/65536"),
                Map.<String, Object>of("source", "192.0.2.8", "destination", "198.51.100.8", "service", "any", "unexpected", "value")))
            assertEquals(400, controller.lookup("p1", bad, request("actor-a")).getStatusCode().value());
        assertEquals(404, controller.lookup("missing", input, request("actor-a")).getStatusCode().value());
        verifyNoInteractions(audit);
    }
    @Test void perUserRateLimitMasksAuditAndRefusesUnauditedResult() {
        snapshot();
        for (int i = 0; i < 30; i++) assertEquals(200, controller.lookup("p1", input, request("actor-a")).getStatusCode().value());
        assertEquals(429, controller.lookup("p1", input, request("actor-a")).getStatusCode().value());
        assertEquals(200, controller.lookup("p1", input, request("actor-b")).getStatusCode().value());
        verify(audit, times(30)).insert(eq("synthetic-session"), eq("actor-a"), eq(ActionRegistry.POLICY_READ), eq(Optional.of("p1")),
            eq(AuthzOutcome.PERMITTED), eq(Optional.of("policy_lookup")), argThat(reason -> reason.isPresent()
                && reason.get().contains("UNKNOWN") && !reason.get().contains("192.0.2.8") && !reason.get().contains("198.51.100.8")), eq(Optional.empty()));
        doThrow(new IllegalStateException()).when(audit).insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
        assertEquals(503, controller.lookup("p1", input, request("actor-c")).getStatusCode().value());
    }
    @Test void aiviewMatchesDisplayedAddressesAndZonesWithoutChangingScopeIdentity() {
        var any = new Cell(List.of("any"), false);
        var rule = new Rule("r1", "synthetic-rule", 1, "RULE-SYNTHETIC", true, new Cell(List.of("host"), false), any, any, any,
            "allow", "Log", "", Map.of("from", List.of("ZONE-SYNTHETIC"), "to", List.of("any")));
        var meta = new Metadata("p1", "s1", "MGR-ALPHA", "PAN", "c1", "DOM-ALPHA", "POL-ALPHA", "2026-10-09T12:00:00Z", "a1", List.of());
        when(policies.find("p1")).thenReturn(Optional.of(new PolicySnapshot(meta,
            List.of(new Section(ref("p1", "GROUP-SYNTHETIC", "pre-rulebase"), "Pre rules", "GROUP-SYNTHETIC", null, List.of(rule))),
            Map.of("host", new PolicyObject("host", "HOST-SYNTHETIC", "address", List.of(), List.of("ipv4-address: 192.0.2.8"), "RESOLVED"),
                "any", new PolicyObject("any", "Any", "any", List.of(), List.of(), "RESOLVED")))));
        var request = request("actor-a"); request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var response = controller.lookup("p1", Map.of("source", ips.mask("192.0.2.8"), "destination", "198.51.100.8", "service", "tcp/443",
            "sourceZone", names.maskPolicyName("from", "ZONE-SYNTHETIC")), request);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("ALLOWED", ((PolicyResponse) response.getBody()).body().get("verdict"));
        String masked = PolicyPrivacy.mask(((PolicyResponse) response.getBody()).body(), "", names, ips).toString();
        assertFalse(masked.contains("RULE-SYNTHETIC")); assertFalse(masked.contains("192.0.2.8"));
    }
    @Test void lookupReasonsAndPerLayerResultsSurviveAiviewProjection() {
        for (String reason : PolicyLookup.REASONS) {
            var layer = Map.<String, Object>of("number", 1, "verdict", "UNKNOWN", "lookupReason", reason);
            var raw = Map.<String, Object>of("verdict", "UNKNOWN", "lookupReason", reason, "layers", List.of(layer));
            assertEquals(raw, PolicyPrivacy.mask(raw, "", names, ips));
        }
    }
    @Test void responseUsesPolicyMaskingForQueryAndRuleIdentity() {
        Map<String, Object> raw = Map.of("query", Map.of("querySource", "192.0.2.8", "queryDestination", "198.51.100.8", "queryService", "tcp/443"),
            "verdict", "UNKNOWN", "lookupReason", "negated selector", "limitations", PolicyLookup.LIMITATIONS,
            "decidingRule", Map.of("ruleId", "r1", "number", 1, "name", "RULE-SYNTHETIC", "sectionName", "SECTION-SYNTHETIC", "action", "allow"));
        String masked = PolicyPrivacy.mask(raw, "", names, ips).toString();
        for (String value : List.of("RULE-SYNTHETIC", "SECTION-SYNTHETIC")) assertFalse(masked.contains(value));
        for (String value : List.of("tcp/443", "UNKNOWN", "negated selector", PolicyLookup.LIMITATIONS, "r1")) assertTrue(masked.contains(value));
    }
}
