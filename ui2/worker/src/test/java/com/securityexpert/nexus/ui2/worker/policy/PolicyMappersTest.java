package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.worker.discovery.pan.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class PolicyMappersTest {
    private Metadata metadata(String vendor) { return new Metadata("policy-1", "manager-1", "Synthetic manager", vendor,
            "container-1", "Synthetic container", "Synthetic policy", "2026-10-01T12:00:00Z", "artifact-1",
            List.of(new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN"), new Target("device-1", "FW-TANGO-04", "vsys2", "UNKNOWN"))); }
    private String fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/policy/" + name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void checkpointMapsNestedSectionsReferencesInlineLayersAndUnknowns() throws Exception {
        var json = new ObjectMapper().readTree(fixture("check-point.json"));
        var snapshot = new CheckPointPolicyMapper().map(metadata("CP"), List.of(json.get(0), json.get(1)));
        var rules = snapshot.sections().stream().flatMap(s -> s.rules().stream()).toList();
        assertEquals(3, rules.size());
        assertEquals("rule-001", rules.get(0).uuid());
        assertEquals(false, rules.get(0).enabled());
        assertTrue(rules.get(0).source().negated());
        assertEquals("Accept", rules.get(0).action()); assertEquals("Log", rules.get(0).log());
        assertEquals("any", snapshot.objects().get(rules.get(0).destination().refs().get(0)).type());
        assertEquals(List.of(ref("policy-1", "object", "target-01")), rules.get(0).extras().get("install-on"));
        assertNull(rules.get(2).enabled()); assertEquals("UNKNOWN", rules.get(2).action());
        assertEquals(List.of("rule-001", "rule-003", "rule-002"), rules.stream().map(Rule::uuid).toList());
        assertTrue(snapshot.sections().stream().anyMatch(s -> rules.get(0).id().equals(s.parentRuleId())));
        assertTrue(snapshot.objects().values().stream().anyMatch(o -> o.status().equals("UNRESOLVED")));
        var group = snapshot.objects().get(ref("policy-1", "object", "group-01"));
        assertEquals(2, group.members().size());
        assertNotEquals(ref("x", "01"), ref("x", "1"));
        assertNotEquals(ref("a:b", "c"), ref("a", "b:c"));
    }
    @Test void checkpointRejectsMissingPagesAndRetainsRepeatedInlineOccurrences() throws Exception {
        var json = new ObjectMapper().readTree(fixture("check-point.json"));
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) json.get(0);
        var parser = new CheckPointPolicyMapper();
        first.put("total", 3);
        assertThrows(IllegalArgumentException.class, () -> parser.map(metadata("CP"), List.of(first)));
        first.put("total", 2);
        var second = (com.fasterxml.jackson.databind.node.ObjectNode) first.path("rulebase").get(0).path("rulebase").get(1);
        second.put("inline-layer", "layer-02");
        var snapshot = parser.map(metadata("CP"), List.of(first, json.get(1)));
        var occurrences = snapshot.sections().stream().filter(s -> s.parentRuleId() != null).toList();
        assertEquals(2, occurrences.size());
        assertNotEquals(occurrences.get(0).parentRuleId(), occurrences.get(1).parentRuleId());
        assertNotEquals(occurrences.get(0).rules().get(0).id(), occurrences.get(1).rules().get(0).id());
        assertEquals(occurrences.get(0).rules().get(0).uuid(), occurrences.get(1).rules().get(0).uuid());
    }
    @Test void panoramaPreservesManagementOrderInheritanceGroupsAndMultiVsysTargets() throws Exception {
        var parser = new PanoramaPolicyMapper();
        var snapshot = parser.map(metadata("PAN"), fixture("panorama.xml"), "Synthetic child", Map.of("Synthetic child", "Synthetic parent"), false);
        var rules = snapshot.sections().stream().flatMap(s -> s.rules().stream()).toList();
        assertEquals(List.of("uuid-01", "uuid-02", "uuid-03", "uuid-04", "uuid-05", "uuid-06"), rules.stream().map(Rule::uuid).toList());
        assertEquals(2, snapshot.metadata().targets().size());
        assertTrue(rules.get(0).source().negated()); assertEquals(false, rules.get(0).enabled());
        assertTrue(snapshot.objects().values().stream().anyMatch(o -> o.status().equals("DYNAMIC")));
        assertTrue(snapshot.objects().values().stream().anyMatch(o -> o.type().equals("application-group")));
        assertTrue(snapshot.objects().values().stream().anyMatch(o -> o.type().equals("tag")));
        assertEquals(List.of("ip-netmask: 198.51.100.8/32"), snapshot.objects().get(ref("policy-1", "address", "Synthetic address")).values());
        var ancestor = parser.map(metadata("PAN"), fixture("panorama.xml"), "Synthetic child", Map.of("Synthetic child", "Synthetic parent"), true);
        assertEquals(List.of("ip-netmask: 192.0.2.8/32"), ancestor.objects().get(ref("policy-1", "address", "Synthetic address")).values());
        var shared = parser.map(metadata("PAN"), fixture("panorama.xml"), "", Map.of(), false);
        assertEquals(2, shared.sections().stream().mapToInt(s -> s.rules().size()).sum());
    }
    @Test void panoramaRejectsDtdMissingParentsAndHierarchyCycles() throws Exception {
        var parser = new PanoramaPolicyMapper(); String xml = fixture("panorama.xml");
        assertThrows(RuntimeException.class, () -> parser.map(metadata("PAN"), "<!DOCTYPE config [<!ENTITY x SYSTEM 'file:///never-read'>]><config>&x;</config>", "", Map.of(), false));
        assertThrows(IllegalArgumentException.class, () -> parser.map(metadata("PAN"), xml, "Synthetic child", Map.of("Synthetic child", "missing"), false));
        assertThrows(IllegalArgumentException.class, () -> parser.map(metadata("PAN"), xml, "Synthetic child", Map.of("Synthetic child", "Synthetic child"), false));
    }
    @Test void panoramaScheduleReferencesResolveInSharedAndDeviceGroupScopes() {
        String xml = "<config><shared><schedule><entry name='OBJ-TIME-01'><schedule-type><recurring><daily><member>08:00-18:00</member></daily></recurring></schedule-type></entry></schedule>"
                + "<pre-rulebase><security><rules><entry name='OBJ-RULE-01' uuid='uuid-1'><schedule>OBJ-TIME-01</schedule></entry></rules></security></pre-rulebase></shared>"
                + "<devices><entry><device-group><entry name='DOM-TANGO-01'><schedule><entry name='OBJ-TIME-02'><schedule-type><non-recurring><member>2026/10/01@00:00-2026/10/31@23:59</member></non-recurring></schedule-type></entry></schedule>"
                + "<post-rulebase><security><rules><entry name='OBJ-RULE-02' uuid='uuid-2'><schedule>OBJ-TIME-02</schedule></entry></rules></security></post-rulebase></entry></device-group></entry></devices></config>";
        var snapshot = new PanoramaPolicyMapper().map(metadata("PAN"), xml, "DOM-TANGO-01", Map.of(), false);
        var rules = snapshot.sections().stream().flatMap(s -> s.rules().stream()).toList();
        assertEquals(2, rules.size());
        for (var rule : rules) assertNotNull(snapshot.objects().get(rule.extras().get("schedule").get(0)).schedule());
        assertEquals("recurring", snapshot.objects().get(rules.get(0).extras().get("schedule").get(0)).schedule().kind());
        assertEquals("one-time", snapshot.objects().get(rules.get(1).extras().get("schedule").get(0)).schedule().kind());
    }

}
