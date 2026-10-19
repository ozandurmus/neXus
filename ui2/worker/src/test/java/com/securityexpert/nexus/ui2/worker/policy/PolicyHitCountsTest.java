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
    /**
     * The shape Check Point returns for hits.first-date / hits.last-date: the API time object {posix (epoch ms), iso-8601
     * (minute precision, offset without a colon)}, as also recorded for meta-info and publish-time in this repository.
     * Before the fix every such date was dropped because Instant.parse rejects the ISO text.
     */
    @Test void cpHitDatesUseTheVendorTimeObjectWithMinutePrecisionIso() throws Exception {
        var json = new ObjectMapper();
        var hits = PolicyHitCounts.checkPoint(json.readTree("{\"percentage\":\"22%\",\"level\":\"medium\",\"value\":3809,"
                + "\"first-date\":{\"posix\":1781085600000,\"iso-8601\":\"2026-06-10T13:00+0300\"},"
                + "\"last-date\":{\"posix\":1792303200000,\"iso-8601\":\"2026-10-18T09:00+0300\"}}"), collected);
        assertEquals(3809L, hits.hits());
        assertEquals("2026-06-10T10:00:00Z", hits.firstHit());
        assertEquals("2026-10-18T06:00:00Z", hits.lastHit());
        assertEquals("medium", hits.level());
        // ISO alone (minute precision, with or without the offset colon) is enough.
        assertEquals("2026-10-18T06:00:00Z", PolicyHitCounts.cpDate(json.readTree("{\"iso-8601\":\"2026-10-18T09:00+0300\"}")));
        assertEquals("2026-10-18T06:00:00Z", PolicyHitCounts.cpDate(json.readTree("{\"iso-8601\":\"2026-10-18T09:00+03:00\"}")));
        assertEquals("2026-10-18T06:00:00Z", PolicyHitCounts.cpDate(json.readTree("{\"iso-8601\":\"2026-10-18T06:00Z\"}")));
        // posix and ISO that disagree by a minute or more, a zero posix, or garbage stay UNKNOWN.
        assertNull(PolicyHitCounts.cpDate(json.readTree("{\"posix\":1792303200000,\"iso-8601\":\"2026-10-18T10:00+0300\"}")));
        assertNull(PolicyHitCounts.cpDate(json.readTree("{\"posix\":0,\"iso-8601\":\"\"}")));
        assertNull(PolicyHitCounts.cpDate(json.readTree("{\"iso-8601\":\"yesterday\"}")));
        // A zero-hit rule carries no dates.
        var never = PolicyHitCounts.checkPoint(json.readTree("{\"percentage\":\"0%\",\"level\":\"zero\",\"value\":0}"), collected);
        assertEquals(0L, never.hits()); assertNull(never.firstHit()); assertNull(never.lastHit());
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
    @Test void panNatAndDefaultSectionsNeitherReceiveSecurityCountersNorMakeSecurityNamesAmbiguous() {
        var meta = snapshot(List.of()).metadata();
        var natRule = new Rule("nat-1", "uuid-n", 1, "OBJ-RULE-01", true, rule().source(), rule().destination(), rule().service(), rule().application(), "source-nat", "", "", Map.of());
        var sections = List.of(new Section("section-1", "Pre rules", "Shared", null, List.of(rule())),
            new Section("section-2", PAN_PRE_NAT, "Shared", null, List.of(natRule)),
            new Section("section-3", PAN_POST_DEFAULT, "Shared", null, List.of(natRule)));
        var result = PolicyHitCounts.aggregate(new PolicySnapshot(meta, sections, Map.of()), List.of(pan(first, "2", "100"), pan(second, "3", "200")));
        assertEquals(5L, result.sections().get(0).rules().get(0).hitCounts().hits(), "same-named NAT rule is not an ambiguity");
        assertNull(result.sections().get(1).rules().get(0).hitCounts());
        assertNull(result.sections().get(2).rules().get(0).hitCounts());
        // A CP section is never classified by its (user-controlled) name.
        var cpMeta = new Metadata("p", "m", "n", "CP", "c", "c", "p", collected, "", List.of());
        assertFalse(PolicySnapshot.panNonSecurity(cpMeta, new Section("s", PAN_PRE_NAT, "CP access layer", null, List.of())));
    }
    @Test void signedCpAndPanFixtureRespectApprovalAndCommandEscapesContext() {
        GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"))
                .stream().filter(row -> row.key().equals(key)).toList();
        CpPolicyGates.requireAll(gates); PanPolicyGates.requireAll(gates);
        assertTrue(PolicyHitGates.enabled(gates, true));
        assertDoesNotThrow(() -> PolicyHitGates.require(gates, true));
        // V155 signs the Palo Alto hit-count read off; the fixture mirrors it.
        assertTrue(PolicyHitGates.enabled(gates, false));
        assertDoesNotThrow(() -> PolicyHitGates.require(gates, false));
        // An unsigned (DRAFTED) Palo Alto row still disables and refuses the read.
        GateRegistryPort unsignedPan = key -> gates.findByCanonicalKey(key).stream()
                .map(r -> r.gateId().equals("pan_policy_rule_hit_count")
                        ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(),
                                r.canonicalCommandKey(), r.actionClass(), SignOffState.DRAFTED, r.timeoutS(), r.retryRule(),
                                r.maxFrequency(), r.sessionReuseRule(), r.unsupportedBehaviorRef(), r.secretOutputRisk(),
                                r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        assertFalse(PolicyHitGates.enabled(unsignedPan, false));
        assertThrows(IllegalStateException.class, () -> PolicyHitGates.require(unsignedPan, false));
        String context = "vsys'&<>\"";
        var spec = PanoramaPolicyCollector.hitRequest(context, new char[0]);
        var xml = PolicyXml.parse(spec.formParams().get("cmd"));
        assertEquals(context, PolicyXml.selectRelative(xml.getDocumentElement(), "rule-hit-count/vsys/vsys-name/entry").get(0).getAttribute("name"));
        assertEquals("no_target", spec.targetScope());
        assertFalse(spec.formParams().containsKey("target"));
    }
}
