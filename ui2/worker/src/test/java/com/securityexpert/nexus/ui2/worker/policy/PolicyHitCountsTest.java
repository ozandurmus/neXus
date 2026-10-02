package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.policy.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class PolicyHitCountsTest {
    private final String collected = "2026-10-02T12:00:00Z";
    private final Target first = new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN");
    private final Target second = new Target("device-2", "FW-BRAVO-02", "vsys1", "UNKNOWN");
    private Rule rule() {
        var cell = new Cell(List.of(), false);
        return new Rule("rule-1", "uuid-1", 1, "OBJ-RULE-01", true, cell, cell, cell, cell, "allow", "Log", "", Map.of());
    }
    private PolicySnapshot snapshot(List<Rule> rules) {
        return new PolicySnapshot(new Metadata("policy-1", "manager-1", "MGR-BRAVO-01", "PAN", "group-1", "DOM-TANGO-01",
                "OBJ-POLICY-01", collected, "", List.of(first, second)), List.of(new Section("section-1", "Pre rules", "Shared", null, rules)), Map.of());
    }
    private Map<String, FirewallHits> pan(Target member, String count, String last) {
        return PolicyHitCounts.pan(PolicyXml.parse("<response status='success'><result><rule-hit-count><vsys><entry name='vsys1'>"
                + "<rule-base><entry name='security'><rules><entry name='OBJ-RULE-01'><hit-count>" + count + "</hit-count>"
                + "<last-hit-timestamp>" + last + "</last-hit-timestamp><rule-creation-timestamp>1</rule-creation-timestamp>"
                + "<rule-modification-timestamp>2</rule-modification-timestamp></entry></rules></entry></rule-base>"
                + "</entry></vsys></rule-hit-count></result></response>").getDocumentElement(), member, collected);
    }
    @Test void cpProjectsZeroDatesLevelAndMissingCountersWithoutInventingZero() throws Exception {
        var json = new ObjectMapper();
        assertNull(PolicyHitCounts.checkPoint(json.readTree("null"), collected));
        var hits = PolicyHitCounts.checkPoint(json.readTree("{\"value\":0,\"last-date\":0,\"level\":\"zero\"}"), collected);
        assertEquals(0L, hits.hits()); assertNull(hits.lastHit()); assertEquals("mds", hits.source());
        hits = PolicyHitCounts.checkPoint(json.readTree("{\"value\":12,\"last-date\":{\"iso-8601\":\"2026-07-01T12:00:00Z\"},\"first-date\":\"2026-06-01T12:00:00Z\",\"level\":\"high\"}"), collected);
        assertEquals(12L, hits.hits()); assertEquals("2026-07-01T12:00:00Z", hits.lastHit());
        assertEquals("2026-06-01T12:00:00Z", hits.firstHit());
        assertNull(PolicyHitCounts.checkPoint(json.readTree("{\"value\":-1}"), collected).hits());
    }
    @Test void panAggregatesMembersAndPreservesUnknownPartialAndAmbiguousNames() {
        var a = pan(first, "2", "100");
        var b = pan(second, "3", "200");
        var counts = PolicyHitCounts.aggregate(snapshot(List.of(rule())), List.of(a, b)).sections().get(0).rules().get(0).hitCounts();
        assertEquals(5L, counts.hits()); assertEquals("1970-01-01T00:03:20Z", counts.lastHit());
        assertEquals(2, counts.firewalls().size()); assertEquals("device", counts.source());
        assertEquals("1970-01-01T00:00:01Z", counts.firewalls().get(0).createdAt());
        var partial = PolicyHitCounts.aggregate(snapshot(List.of(rule())), List.of(a)).sections().get(0).rules().get(0).hitCounts();
        assertNull(partial.hits()); assertNull(partial.lastHit()); assertEquals(1, partial.firewalls().size());
        assertNull(PolicyHitCounts.aggregate(snapshot(List.of(rule(), rule())), List.of(a, b)).sections().get(0).rules().get(0).hitCounts());
        assertNull(PolicyHitCounts.aggregate(snapshot(List.of(rule())), List.of(a, pan(second, "3", "invalid")))
                .sections().get(0).rules().get(0).hitCounts().lastHit());
        assertNull(PolicyHitCounts.aggregate(snapshot(List.of(rule())), List.of(pan(first, Long.toString(Long.MAX_VALUE), "100"), b))
                .sections().get(0).rules().get(0).hitCounts().hits());
        assertThrows(IllegalArgumentException.class, () -> PolicyHitCounts.pan(PolicyXml.parse("<response status='success'><result/></response>").getDocumentElement(), first, collected));
    }
    @Test void unsignedFixtureCannotEnableEitherVariantAndCommandEscapesContext() {
        GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"))
                .stream().filter(row -> row.key().equals(key)).toList();
        CpPolicyGates.requireAll(gates); PanPolicyGates.requireAll(gates);
        for (boolean cp : List.of(true, false)) {
            assertFalse(PolicyHitGates.enabled(gates, cp));
            assertThrows(IllegalStateException.class, () -> PolicyHitGates.require(gates, cp));
        }
        String context = "vsys'&<>\"";
        var spec = PanoramaPolicyCollector.hitRequest(context, new char[0]);
        var xml = PolicyXml.parse(spec.formParams().get("cmd"));
        assertEquals(context, PolicyXml.selectRelative(xml.getDocumentElement(), "rule-hit-count/vsys/vsys-name/entry").get(0).getAttribute("name"));
        assertEquals("no_target", spec.targetScope());
        assertFalse(spec.formParams().containsKey("target"));
    }
}
