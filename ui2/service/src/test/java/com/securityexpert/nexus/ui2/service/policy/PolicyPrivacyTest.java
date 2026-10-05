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
    @Test void inventoryUsesRuleObjectAliasesMasksIpsAndRetainsOpaqueJoinsAndGaps() {
        var item = Map.of("id", "object-1", "uid", "uid-001", "name", "Synthetic address", "type", "host",
            "members", List.of("uid-002"), "values", List.of("ipv4-address: 192.0.2.8", "port: 443"),
            "policyInstallations", List.of(Map.of("policyName", "Synthetic policy", "installed", false)));
        var body = Map.of("objects", List.of(item), "types", List.of(Map.of("type", "hosts", "status", "COLLECTION_FAILED",
            "reason", "policy: TIMEOUT", "pages", 2, "seconds", 1.25)));
        var masked = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(PolicyPrivacy.mask(body, "", names, ips));
        var object = masked.path("objects").get(0);
        assertEquals(names.maskPolicyName("address", "Synthetic address"), object.path("name").asText());
        assertEquals("uid-001", object.path("uid").asText()); assertEquals("uid-002", object.path("members").get(0).asText());
        assertEquals("ipv4-address: " + ips.mask("192.0.2.8"), object.path("values").get(0).asText());
        assertEquals("port: 443", object.path("values").get(1).asText());
        assertEquals(names.maskPolicyName("policy", "Synthetic policy"), object.path("policyInstallations").get(0).path("policyName").asText());
        assertEquals("COLLECTION_FAILED", masked.path("types").get(0).path("status").asText());
        assertEquals("hosts", masked.path("types").get(0).path("type").asText());
        assertFalse(masked.toString().contains("Synthetic")); assertFalse(masked.toString().contains("192.0.2.8"));
    }
    @Test void domainUsageDuplicatesAndInstallationMaskNestedNamesButKeepSafeFlagsAndJoinKeys() {
        var object = Map.of("uid", "uid-001", "name", "Synthetic address", "type", "host", "duplicateId", "duplicate-1", "emptyGroup", false,
            "values", List.of("ipv4-address: 192.0.2.8"));
        var body = Map.of("object", object, "duplicates", List.of(Map.of("id", "duplicate-1", "objects", List.of(object))),
            "rules", List.of(Map.of("policyId", "policy-1", "policyName", "Synthetic package", "layerName", "Synthetic layer", "number", 7)),
            "groups", List.of(Map.of("id", "group-1", "name", "Synthetic group", "type", "group")),
            "installations", List.of(Map.of("deviceId", "device-1", "name", "Synthetic gateway", "allTargets", true, "installed", false)));
        var masked = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(PolicyPrivacy.mask(body, "", names, ips));
        assertFalse(masked.toString().contains("Synthetic")); assertFalse(masked.toString().contains("192.0.2.8"));
        assertEquals("duplicate-1", masked.path("object").path("duplicateId").asText());
        assertEquals(masked.path("object").path("name"), masked.path("duplicates").get(0).path("objects").get(0).path("name"));
        assertEquals("uid-001", masked.path("object").path("uid").asText());
        assertEquals(7, masked.path("rules").get(0).path("number").asInt());
        assertEquals(names.maskDeviceName("Synthetic gateway", null), masked.path("installations").get(0).path("name").asText());
        assertTrue(masked.path("installations").get(0).path("allTargets").asBoolean());
        assertFalse(masked.path("installations").get(0).path("installed").asBoolean());
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
