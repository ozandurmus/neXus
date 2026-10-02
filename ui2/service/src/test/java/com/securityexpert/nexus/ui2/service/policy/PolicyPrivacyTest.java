package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;

class PolicyPrivacyTest {
    @Test void hitProjectionPreservesCountersDatesProvenanceAndMasksMemberContext() {
        var body = Map.of("hitCounts", Map.of("hits", 12, "source", "device", "lastHit", "2026-07-01T00:00:00Z", "level", "high",
                "firewalls", List.of(Map.of("deviceId", "device-1", "context", "Synthetic context", "createdAt", "2026-01-01T00:00:00Z"))));
        var masked = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(PolicyPrivacy.mask(body, "", names, ips)).path("hitCounts");
        assertEquals(12, masked.path("hits").asInt()); assertEquals("device", masked.path("source").asText());
        assertEquals("2026-07-01T00:00:00Z", masked.path("lastHit").asText());
        assertEquals("device-1", masked.path("firewalls").get(0).path("deviceId").asText());
        assertFalse(masked.toString().contains("Synthetic context"));
    }
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);

    @Test void typedNamesAreStableCaseSensitiveAndUniqueBeyondTheTopologyDictionary() {
        for (String type : List.of("policy", "rule", "address", "group", "service", "application", "profile-setting/group", "tag")) {
            String label = names.maskPolicyName(type, "Synthetic label");
            assertTrue(label.matches("(?:POL|RULE|ADDR|GRP|SVC|APP|PROF|TAG)-[A-Z]+-[0-9]{2,4}"));
            assertEquals(label, names.maskPolicyName(type, "Synthetic label"));
            assertNotEquals(label, names.maskPolicyName(type, "synthetic label"));
        }
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < 10000; i++) assertTrue(labels.add(names.maskPolicyName("rule", "Synthetic rule " + i)));
    }

    @Test void policyRuleObjectsAndHistoryShareTheirTypedAliasesWithoutChangingKeys() {
        var policy = Map.of("id", "policy-1", "sourceId", "source-1", "name", "Synthetic policy");
        var rule = Map.of("id", "rule-1", "number", 1, "name", "Synthetic rule");
        var object = Map.of("id", "object-1", "type", "address", "name", "Synthetic address");
        var body = Map.of("metadata", policy, "rules", List.of(rule), "object", object,
                "changes", List.of(Map.of("field", "name", "before", "Synthetic rule", "after", "Synthetic renamed rule")));
        var masked = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(PolicyPrivacy.mask(body, "", names, ips));
        assertEquals(names.maskPolicyName("policy", "Synthetic policy"), masked.path("metadata").path("name").asText());
        assertEquals(names.maskPolicyName("address", "Synthetic address"), masked.path("object").path("name").asText());
        assertEquals(masked.path("rules").get(0).path("name"), masked.path("changes").get(0).path("before"));
        assertEquals("object-1", masked.path("object").path("id").asText());
        assertFalse(masked.toString().contains("Synthetic"));
        assertFalse(masked.toString().matches(".*[a-f0-9]{32}.*"));
    }

    @Test void eachSourceJobRetainsItsOwnStateAndCollectionTime() {
        var sources = List.of(
            Map.of("sourceId", "source-1", "collection", Map.of("state", "COMPLETED", "collectedAt", "2026-10-02T06:06:00Z")),
            Map.of("sourceId", "source-2", "collection", Map.of("state", "EXECUTING", "step", 3)));
        var masked = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(PolicyPrivacy.mask(Map.of("sources", sources), "", names, ips));
        assertEquals("COMPLETED", masked.path("sources").get(0).path("collection").path("state").asText());
        assertEquals("EXECUTING", masked.path("sources").get(1).path("collection").path("state").asText());
        assertEquals("2026-10-02T06:06:00Z", masked.path("sources").get(0).path("collection").path("collectedAt").asText());
    }
}
