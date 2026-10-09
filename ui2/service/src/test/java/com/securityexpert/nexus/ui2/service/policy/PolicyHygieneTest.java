package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

class PolicyHygieneTest {
    static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    static Cell cell(String id) { return new Cell(List.of(id), false); }
    static PolicyObject object(String id, String type, String... values) { return new PolicyObject(id, "OBJ-TEST-" + id, type, List.of(), List.of(values), "RESOLVED"); }
    static Map<String, PolicyObject> objects() {
        return new HashMap<>(Map.of("any", object("any", "any"), "host", object("host", "address", "ipv4-address: 192.0.2.8"),
            "net", object("net", "address", "ip-netmask: 192.0.2.0/24"), "port", object("port", "service", "protocol: tcp", "port: 443"),
            "ports", object("ports", "service", "protocol/tcp/port: 400-500"),
            "group", new PolicyObject("group", "OBJ-GROUP-01", "group", List.of("net"), List.of(), "RESOLVED")));
    }
    static Rule rule(String id, String source, String service, String action, Boolean enabled) {
        return new Rule(id, "uuid-" + id, id.equals("r1") ? 1 : 2, "OBJ-RULE-" + id, enabled, cell(source), cell("any"), cell(service), cell("any"), action, "Log", "", Map.of("from", List.of("any"), "to", List.of("any")));
    }
    static PolicySnapshot snapshot(List<Rule> rules, Map<String, PolicyObject> objects) {
        return new PolicySnapshot(new Metadata("p1", "s1", "MGR-BRAVO-01", "PAN", "c1", "DOM-TANGO-01", "POL-ALPHA-01", NOW.toString(), "a1", List.of()),
            List.of(new Section("s1", "Pre rules", "Shared", null, rules)), objects);
    }
    static PolicyHygiene.Result analyze(List<Rule> rules, Map<String, PolicyObject> objects) { return PolicyHygiene.analyze(snapshot(rules, objects), 90, NOW, 250000); }
    static Rule edit(Rule r, Cell source, Map<String, List<String>> extras) {
        var merged = new HashMap<>(r.extras()); merged.putAll(extras);
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), source, r.destination(), r.service(), r.application(), r.action(), r.log(), r.comment(), merged, r.hitCounts());
    }
    @Test void exactGroupAndPortSupersetsAndConflictingActions() {
        for (String source : List.of("host", "net", "group")) for (String service : List.of("port", "ports")) {
            var result = analyze(List.of(rule("r1", source, service, "allow", true), rule("r2", "host", "port", "allow", true)), objects()).rules().get("r2");
            assertEquals("REDUNDANT", result.shadowStatus()); assertEquals("r1", result.coveringRuleId());
            assertTrue(result.findings().stream().anyMatch(f -> f.findingClass().equals("shadowed") && f.severity().equals("MEDIUM")));
        }
        var conflict = analyze(List.of(rule("r1", "net", "ports", "deny", true), rule("r2", "host", "port", "allow", true)), objects()).rules().get("r2");
        assertEquals("CONFLICT", conflict.shadowStatus());
        assertTrue(conflict.findings().stream().anyMatch(f -> f.findingClass().equals("conflict") && f.severity().equals("HIGH")));
    }
    @Test void unresolvedNegationCyclesDynamicAndIdentityConstraintsStayUnknown() {
        var objects = objects(); objects.put("cycle", new PolicyObject("cycle", "OBJ-GROUP-02", "group", List.of("cycle"), List.of(), "RESOLVED"));
        objects.put("dynamic", object("dynamic", "dynamic-object"));
        var first = rule("r1", "any", "any", "allow", true); var next = rule("r2", "host", "port", "allow", true);
        for (Rule unknown : List.of(edit(next, new Cell(List.of("host"), true), Map.of()), edit(next, cell("missing"), Map.of()),
                edit(next, cell("cycle"), Map.of()), edit(next, cell("dynamic"), Map.of()), edit(next, cell("host"), Map.of("source-user", List.of("OBJ-USER-01"))))) {
            var result = analyze(List.of(first, unknown), objects).rules().get("r2");
            assertEquals("UNKNOWN", result.shadowStatus()); assertNull(result.coveringRuleId());
        }
    }
    @Test void disabledEarlierRuleDoesNotShadowAndPartialOverlapDoesNotCover() {
        var next = rule("r2", "net", "ports", "allow", true);
        var disabled = rule("r1", "any", "any", "allow", false);
        assertEquals("NOT_SHADOWED", analyze(List.of(disabled, next), objects()).rules().get("r2").shadowStatus());
        assertTrue(analyze(List.of(disabled), objects()).rules().get("r1").findings().stream().anyMatch(f -> f.findingClass().equals("disabled")));
        assertEquals("NOT_SHADOWED", analyze(List.of(rule("r1", "host", "port", "allow", true), next), objects()).rules().get("r2").shadowStatus());
    }
    @Test void contiguousRangesMergeAndProtocolsRemainSeparate() {
        var objects = objects();
        objects.put("range", object("range", "address", "ip-address-first: 192.0.2.0", "ip-address-last: 192.0.2.127"));
        objects.put("range2", object("range2", "address", "ip-range: 192.0.2.128-192.0.2.255"));
        objects.put("joined", new PolicyObject("joined", "OBJ-GROUP-03", "group", List.of("range", "range2"), List.of(), "RESOLVED"));
        var first = rule("r1", "joined", "ports", "allow", true);
        var second = rule("r2", "net", "port", "allow", true);
        assertEquals("REDUNDANT", analyze(List.of(first, second), objects).rules().get("r2").shadowStatus());
        objects.put("ports", object("ports", "service", "protocol: udp", "port: 400-500"));
        assertEquals("NOT_SHADOWED", analyze(List.of(first, second), objects).rules().get("r2").shadowStatus());
    }
    @Test void unsupportedEarlierRulesAndIncompatibleZonesCannotProveClean() {
        var first = rule("r1", "missing", "port", "allow", true); var second = rule("r2", "host", "port", "allow", true);
        assertEquals("UNKNOWN", analyze(List.of(first, second), objects()).rules().get("r2").shadowStatus());
        first = edit(rule("r1", "host", "port", "allow", true), cell("host"), Map.of("from", List.of("ZONE-ALPHA-01")));
        second = edit(second, cell("host"), Map.of("from", List.of("ZONE-BRAVO-01")));
        assertEquals("UNKNOWN", analyze(List.of(first, second), objects()).rules().get("r2").shadowStatus());
        first = edit(first, cell("host"), Map.of("from", List.of("any")));
        assertEquals("REDUNDANT", analyze(List.of(first, second), objects()).rules().get("r2").shadowStatus());
    }
    @Test void sectionsLayersInlineAndPrePostNeverCross() {
        var first = rule("r1", "any", "any", "allow", true); var second = rule("r2", "host", "port", "allow", true);
        for (String parent : List.of("", "r1")) {
            var base = snapshot(List.of(), objects());
            var s = new PolicySnapshot(base.metadata(), List.of(new Section("pre", "Pre rules", "Shared", null, List.of(first)),
                new Section("post", "Post rules", "Shared", parent.isEmpty() ? null : parent, List.of(second))), base.objects());
            assertEquals("NOT_SHADOWED", PolicyHygiene.analyze(s, 90, NOW, 250000).rules().get("r2").shadowStatus());
        }
    }
    @Test void checkPointEmptyApplicationIsNotAnUnresolvedSelectorButMissingTimeIs() {
        var first = rule("r1", "any", "any", "Accept", true); var second = rule("r2", "host", "port", "Accept", true);
        var rules = List.of(first, second).stream().map(r -> new Rule(r.id(), r.uuid(), r.number(), r.name(), true, r.source(), r.destination(), r.service(),
            new Cell(List.of(), false), r.action(), r.log(), "", Map.of("time", List.of("any"), "install-on", List.of("any")))).toList();
        var base = snapshot(rules, objects()); var m = base.metadata();
        var cp = new PolicySnapshot(new Metadata(m.id(), m.sourceId(), m.sourceName(), "CP", m.containerId(), m.containerName(), m.name(), m.collectedAt(), m.artefactRef(), List.of()), base.sections(), base.objects());
        assertEquals("REDUNDANT", PolicyHygiene.analyze(cp, 90, NOW, 250000).rules().get("r2").shadowStatus());
        var r = rules.get(1);
        var noTime = new Rule(r.id(), r.uuid(), r.number(), r.name(), true, r.source(), r.destination(), r.service(), r.application(),
            r.action(), r.log(), "", Map.of("install-on", List.of("any"), "time-uncollected", List.of("true")));
        var missing = new PolicySnapshot(cp.metadata(), List.of(new Section("s", "OBJ-LAYER-01", "CP access layer", null, List.of(noTime))), cp.objects());
        assertEquals("UNKNOWN", PolicyHygiene.analyze(missing, 90, NOW, 250000).rules().get("r2").shadowStatus());
    }
    @Test void unusedUsesCollectionTimeAndKeepsCounterWindowUncertainty() {
        Rule base = rule("r1", "host", "port", "allow", true);
        assertTrue(analyze(List.of(base), objects()).rules().get("r1").findings().stream().anyMatch(f -> f.evidence().contains("Hit counts were not collected")));
        var zero = base.withHitCounts(new HitCounts(0L, null, null, "mds", NOW.toString(), "zero", List.of()));
        assertTrue(unused(zero));
        var old = base.withHitCounts(new HitCounts(4L, null, "2026-01-01T00:00:00Z", "mds", NOW.toString(), null, List.of()));
        assertTrue(unused(old));
        var shortWindow = zero.withHitCounts(new HitCounts(0L, null, null, "device", NOW.toString(), "zero", List.of(
            new FirewallHits("d1", "ctx1", 0L, null, null, NOW.minusSeconds(86400).toString(), null, NOW.toString()))));
        assertFalse(unused(shortWindow));
        assertTrue(analyze(List.of(shortWindow), objects()).rules().get("r1").findings().stream().anyMatch(f -> f.evidence().contains("shorter than")));
        var recentAtCollection = base.withHitCounts(new HitCounts(4L, null, "2026-01-01T00:00:00Z", "mds", "2026-01-02T00:00:00Z", null, List.of()));
        assertFalse(unused(recentAtCollection));
        assertTrue(analyze(List.of(zero), objects()).rules().get("r1").counterWindow().contains("unknown"));
    }
    private boolean unused(Rule r) { return analyze(List.of(r), objects()).rules().get("r1").findings().stream().anyMatch(f -> f.findingClass().equals("unused")); }
    @Test void missingTargetCountersAndInvalidTimesStayUnknown() {
        var rule = rule("r1", "host", "port", "allow", true).withHitCounts(new HitCounts(0L, null, null, "device", NOW.toString(), null, List.of(new FirewallHits("d1", "ctx1", null, null, null, null, null, NOW.toString()))));
        assertFalse(unused(rule));
        assertFalse(unused(rule.withHitCounts(new HitCounts(0L, null, null, "mds", "invalid", null, List.of()))));
    }
    @Test void incompleteSnapshotsAndMissingZoneEvidenceCannotProveClean() {
        var r = rule("r1", "host", "port", "allow", true);
        var base = snapshot(List.of(r), objects());
        var incomplete = new PolicySnapshot(base.metadata(), base.sections(), base.objects(), List.of(new CollectionFailure("s1", "TIMEOUT")));
        assertEquals("UNKNOWN", PolicyHygiene.analyze(incomplete, 90, NOW, 250000).rules().get("r1").shadowStatus());
        var missing = new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(), r.service(), r.application(), r.action(), r.log(), r.comment(), Map.of());
        assertEquals("zone evidence not collected", analyze(List.of(missing), objects()).rules().get("r1").shadowReason());
    }
    @Test void budgetIsDeterministicAndRemainderIsUnknown() {
        var s = snapshot(List.of(rule("r1", "any", "any", "allow", true), rule("r2", "host", "port", "allow", true)), objects());
        var result = PolicyHygiene.analyze(s, 90, NOW, 0);
        assertTrue(result.budgetReached());
        result.rules().values().forEach(a -> { assertEquals("UNKNOWN", a.shadowStatus()); assertEquals("analysis budget", a.shadowReason()); });
        assertEquals(result, PolicyHygiene.analyze(s, 90, NOW, 0));
    }
    @Test void expiredAndAnyAcceptHaveDocumentedSeverityAndScore() {
        var any = rule("r1", "any", "any", "allow", true);
        var result = analyze(List.of(any), objects()).rules().get("r1");
        assertEquals(8, result.permissiveness().get("score"));
        assertTrue(result.findings().stream().anyMatch(f -> f.findingClass().equals("broad") && f.severity().equals("HIGH")));
        var objects = objects(); objects.put("time", new PolicyObject("time", "OBJ-TIME-01", "schedule", List.of(), List.of(), "RESOLVED",
            new PolicySchedule("one-time", null, "2026-01-01T00:00", List.of(), "UTC", true)));
        result = analyze(List.of(edit(any, any.source(), Map.of("schedule", List.of("time")))), objects).rules().get("r1");
        assertEquals("expired", result.timeStatus()); assertEquals("UNKNOWN", result.shadowStatus());
        assertTrue(result.findings().stream().anyMatch(f -> f.findingClass().equals("expired") && f.severity().equals("LOW")));
    }
}
