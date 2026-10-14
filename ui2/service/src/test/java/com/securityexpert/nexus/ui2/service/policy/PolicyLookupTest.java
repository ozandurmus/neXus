package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class PolicyLookupTest {
    static PolicyLookup.Query query(String source) { return new PolicyLookup.Query(source, "198.51.100.8", "tcp/443", null, null, null, null); }
    static Map<String, Object> run(PolicySnapshot snapshot, PolicyLookup.Query query) { return PolicyLookup.evaluate(snapshot, query, 250000).body(); }
    static PolicySnapshot pan(Rule... rules) { return sections("PAN", panSection("Shared", "pre-rulebase", rules)); }
    static String verdict(PolicySnapshot snapshot) { return (String) run(snapshot, query("192.0.2.8")).get("verdict"); }
    @Test void allowDenyNoMatchAndDisabled() {
        assertEquals("ALLOWED", verdict(pan(rule("r1", "host", "port", "allow", true))));
        assertEquals("DENIED", verdict(pan(rule("r1", "host", "port", "deny", true))));
        var implicit = run(pan(rule("r1", "host", "port", "allow", true)), query("203.0.113.8"));
        assertEquals("UNKNOWN", implicit.get("verdict")); assertEquals("zone or target required", implicit.get("lookupReason")); assertNull(implicit.get("decidingRule"));
        assertEquals("ALLOWED", verdict(pan(rule("r1", "any", "any", "deny", false), rule("r2", "host", "port", "allow", true))));
    }
    @Test void noMatchUsesOnlyCollectedEvidenceIncludingInlineLayers() {
        var noMatch = edit(cpRule("r2", "Accept"), cell("host"), Map.of());
        for (var cp : List.of(sections("CP", cpSection("s1", null, noMatch)),
                sections("CP", cpSection("s1", null, cpRule("r1", "Apply Layer")), cpSection("child", "r1", noMatch)),
                sections("CP", cpSection("s1", null, cpRule("r1", "Apply Layer")), cpSection("empty-child", "r1")),
                sections("CP", cpSection("empty", null)))) {
            var result = run(cp, query("203.0.113.8"));
            assertEquals("UNKNOWN", result.get("verdict"));
            assertEquals("implicit cleanup action not collected", result.get("lookupReason"));
            assertNull(result.get("decidingRule"));
        }
        for (String destinationZone : List.of("ZONE-ALPHA", "ZONE-BRAVO")) {
            var result = run(pan(rule("r1", "host", "port", "allow", true)),
                new PolicyLookup.Query("203.0.113.8", "198.51.100.8", "tcp/443", "ZONE-ALPHA", destinationZone, null, null));
            assertEquals("UNKNOWN", result.get("verdict")); assertNull(result.get("decidingRule"));
            assertEquals(destinationZone.equals("ZONE-ALPHA")
                ? "no rule matched; intrazone-default applies (allow by default, override not collected)"
                : "no rule matched; interzone-default applies (deny by default, override not collected)", result.get("lookupReason"));
        }
    }
    @Test void orderedLayersRequireEveryAllowAndRetainFirstProvenDeny() {
        for (String action : List.of("Accept", "Drop", "NO_MATCH")) {
            Rule second = cpLayerRule("r2", 1, "LAYER-BRAVO", action.equals("NO_MATCH") ? "Accept" : action);
            if (action.equals("NO_MATCH")) second = edit(second, cell("host"), Map.of());
            var snapshot = sections("CP", cpSection("s1", null, cpLayerRule("r1", 1, "LAYER-ALPHA", "Accept")),
                cpSection("s2", null, second));
            var result = run(snapshot, query("203.0.113.8"));
            assertEquals(action.equals("Accept") ? "ALLOWED" : action.equals("Drop") ? "DENIED" : "UNKNOWN", result.get("verdict"));
            var layers = (List<?>) result.get("layers"); assertEquals(2, layers.size());
            assertEquals("ALLOWED", ((Map<?, ?>) layers.get(0)).get("verdict"));
            if (action.equals("Drop")) assertEquals("r2", ((Map<?, ?>) result.get("decidingRule")).get("ruleId"));
            else assertNull(result.get("decidingRule"));
            if (action.equals("NO_MATCH")) {
                assertEquals("ordered layer results", result.get("lookupReason"));
                assertEquals("implicit cleanup action not collected", ((Map<?, ?>) layers.get(1)).get("lookupReason"));
                assertNull(((Map<?, ?>) layers.get(1)).get("decidingRule"));
            }
        }
        var snapshot = sections("CP", cpSection("s1", null, edit(cpLayerRule("r1", 1, "LAYER-ALPHA", "Accept"), cell("host"), Map.of())),
            cpSection("s2", null, cpLayerRule("r2", 1, "LAYER-BRAVO", "Drop")),
            cpSection("s3", null, cpLayerRule("r3", 1, "LAYER-CHARLIE", "Drop")));
        var result = run(snapshot, query("203.0.113.8"));
        assertEquals("DENIED", result.get("verdict"));
        assertEquals("r2", ((Map<?, ?>) result.get("decidingRule")).get("ruleId"));
    }
    @Test void inlineAcceptDoesNotSkipTheNextOrderedLayer() {
        var snapshot = sections("CP", cpSection("s1", null, cpLayerRule("r1", 1, "LAYER-ALPHA", "Apply Layer")),
            cpSection("child", "r1", cpLayerRule("child-rule", 1, "LAYER-INLINE", "Accept")),
            cpSection("s2", null, cpLayerRule("r2", 1, "LAYER-BRAVO", "Drop")));
        var result = run(snapshot, query("192.0.2.8"));
        assertEquals("DENIED", result.get("verdict"));
        assertEquals("r2", ((Map<?, ?>) result.get("decidingRule")).get("ruleId"));
    }
    @Test void ambiguousLayerBoundaryFailsClosedAndLabelsNeverRejoinRuns() {
        for (var snapshot : List.of(
                sections("CP", cpSection("s1", null, cpLayerRule("r1", 1, "LAYER-ALPHA", "Accept")),
                    cpSection("s2", null, cpLayerRule("r2", 1, "LAYER-ALPHA", "Drop"))),
                sections("CP", cpSection("s1", null, cpRule("r1", "Accept")), cpSection("empty", null)),
                sections("CP", cpSection("s1", null, cpRule("r1", "Accept")),
                    cpSection("s2", null, edit(cpRule("r2", "Drop"), cell("any"), Map.of("layer-name", List.of())))))) {
            var result = run(snapshot, query("192.0.2.8"));
            assertEquals("UNKNOWN", result.get("verdict"));
            assertEquals("multiple ordered layers not evaluated", result.get("lookupReason"));
            assertNull(result.get("decidingRule"));
        }
        var repeated = sections("CP", cpSection("s1", null, cpLayerRule("r1", 1, "LAYER-ALPHA", "Accept")),
            cpSection("s2", null, cpLayerRule("r2", 1, "LAYER-BRAVO", "Accept")),
            cpSection("s3", null, cpLayerRule("r3", 1, "LAYER-ALPHA", "Drop")));
        assertEquals("DENIED", verdict(repeated));
    }
    @Test void explicitFallbackStillProducesPartialCoverage() {
        var result = run(pan(rule("r1", "host", "port", "allow", true), rule("r2", "any", "any", "deny", true)), query("192.0.2.0/24"));
        assertEquals("PARTIAL", result.get("verdict")); assertEquals(3, ((List<?>) result.get("parts")).size());
    }
    @Test void cidrPartialListsExactDecidedRanges() {
        var result = run(pan(rule("r1", "host", "port", "allow", true)), query("192.0.2.0/24"));
        assertEquals("UNKNOWN", result.get("verdict")); var parts = (List<?>) result.get("parts"); assertEquals(3, parts.size());
        assertEquals("192.0.2.8", ((Map<?, ?>) parts.get(1)).get("querySource"));
        assertEquals("ALLOWED", ((Map<?, ?>) parts.get(1)).get("verdict"));
        assertEquals("UNKNOWN", ((Map<?, ?>) parts.get(0)).get("verdict")); assertNull(result.get("decidingRule"));
    }
    @Test void groupsAndPortRangesCoverEntireQuery() {
        assertEquals("ALLOWED", run(pan(rule("r1", "group", "ports", "allow", true)), query("192.0.2.0/24")).get("verdict"));
        assertEquals("UNKNOWN", run(pan(rule("r1", "group", "ports", "allow", true)),
            new PolicyLookup.Query("192.0.2.8", "198.51.100.8", "udp/53", "", "", "", "")).get("verdict"));
    }
    @Test void unknownEarlierRuleBlocksAndRetainsFirstProvenMatch() {
        var next = rule("r2", "any", "any", "allow", true);
        for (Rule uncertain : List.of(rule("r1", "missing", "port", "deny", true),
                edit(rule("r1", "host", "port", "deny", true), new Cell(List.of("host"), true), Map.of()),
                edit(rule("r1", "host", "port", "deny", true), cell("host"), Map.of("source-user", List.of("USER-ALPHA-01"))),
                edit(rule("r1", "host", "port", "deny", true), cell("host"), Map.of("schedule", List.of("missing"))))) {
            var result = run(pan(uncertain, next), query("192.0.2.8"));
            assertEquals("UNKNOWN", result.get("verdict")); assertEquals("r2", ((Map<?, ?>) result.get("decidingRule")).get("ruleId"));
            assertEquals(1, ((List<?>) result.get("undecidableRules")).size());
        }
    }
    @Test void cpSectionsShareOrderAndInlineIsEnteredOnlyWhenParentMatches() {
        var first = edit(cpRule("r1", "Drop"), cell("host"), Map.of());
        var cp = sections("CP", cpSection("s1", null, first), cpSection("s2", null, cpRule("r2", "Accept")));
        assertEquals("ALLOWED", run(cp, query("203.0.113.8")).get("verdict"));
        var inline = sections("CP", cpSection("s1", null, cpRule("r1", "Apply Layer")), cpSection("child", "r1", cpRule("child-rule", "Accept")), cpSection("s2", null, cpRule("r2", "Drop")));
        var result = run(inline, query("192.0.2.8")); assertEquals("ALLOWED", result.get("verdict"));
        assertEquals("child-rule", ((Map<?, ?>) result.get("decidingRule")).get("ruleId"));
        var missing = sections("CP", cpSection("s1", null, cpRule("r1", "Apply Layer")), cpSection("s2", null, cpRule("r2", "Drop")));
        var unknown = run(missing, query("192.0.2.8")); assertEquals("UNKNOWN", unknown.get("verdict"));
        assertEquals("r2", ((Map<?, ?>) unknown.get("decidingRule")).get("ruleId"));
        assertEquals("inline layer evaluation unsupported", ((Map<?, ?>) ((List<?>) unknown.get("undecidableRules")).get(0)).get("lookupReason"));
        assertEquals("UNKNOWN", verdict(sections("CP", cpSection("s1", null, cpRule("r2", "Accept")), cpSection("s2", null, cpRule("r1", "Drop")))));
    }
    @Test void panPreLocalPostAndZones() {
        var pre = rule("r1", "host", "port", "deny", true); var local = rule("r2", "any", "any", "allow", true); var post = rule("r3", "any", "any", "deny", true);
        var snapshot = sections("PAN", panSection("Shared", "pre-rulebase", pre), panSection("DG-ALPHA", "rulebase", local), panSection("DG-ALPHA", "post-rulebase", post));
        assertEquals("DENIED", verdict(snapshot)); assertEquals("ALLOWED", run(snapshot, query("203.0.113.8")).get("verdict"));
        var zoned = pan(edit(pre, cell("any"), Map.of("from", List.of("ZONE-ALPHA"), "to", List.of("ZONE-BRAVO"))), local);
        assertEquals("UNKNOWN", verdict(zoned));
        assertEquals("DENIED", run(zoned, new PolicyLookup.Query("192.0.2.8", "198.51.100.8", "tcp/443", "ZONE-ALPHA", "ZONE-BRAVO", null, null)).get("verdict"));
        assertEquals("ALLOWED", run(zoned, new PolicyLookup.Query("192.0.2.8", "198.51.100.8", "tcp/443", "ZONE-OTHER", "ZONE-BRAVO", null, null)).get("verdict"));
        assertEquals("UNKNOWN", verdict(sections("PAN", panSection("Shared", "post-rulebase", post), panSection("Shared", "pre-rulebase", pre))));
    }
    @Test void budgetIncompleteAndAnyServiceRemainFailClosed() {
        var snapshot = pan(rule("r1", "any", "any", "allow", true));
        assertEquals("analysis budget", PolicyLookup.evaluate(snapshot, query("192.0.2.8"), 0).body().get("lookupReason"));
        assertEquals("UNKNOWN", verdict(new PolicySnapshot(snapshot.metadata(), snapshot.sections(), snapshot.objects(), List.of(new CollectionFailure("s1", "COLLECTION_PENDING")))));
        assertEquals("ALLOWED", run(snapshot, new PolicyLookup.Query("192.0.2.8", "198.51.100.8", "any", null, null, null, null)).get("verdict"));
        assertEquals("UNKNOWN", run(pan(rule("r1", "any", "port", "allow", true)), new PolicyLookup.Query("192.0.2.8", "198.51.100.8", "any", null, null, null, null)).get("verdict"));
    }
    @Test void fragmentBudgetAndNonmatchingInlineParentAreBounded() {
        var objects = objects(); var members = new ArrayList<String>();
        for (int i = 0; i < 65; i++) {
            String id = "host-" + i; members.add(id); objects.put(id, object(id, "address", "ipv4-address: 192.0.2." + (i * 2 + 1)));
        }
        objects.put("many", new PolicyObject("many", "GROUP-SYNTHETIC", "group", members, List.of(), "RESOLVED"));
        var base = pan(rule("r1", "many", "any", "allow", true));
        var result = run(new PolicySnapshot(base.metadata(), base.sections(), objects), query("192.0.2.0/24"));
        assertEquals("UNKNOWN", result.get("verdict")); assertEquals("analysis budget", result.get("lookupReason"));
        var inline = sections("CP", cpSection("s1", null, edit(cpRule("r1", "Apply Layer"), cell("host"), Map.of())),
            cpSection("child", "r1", cpRule("child-rule", "Drop")), cpSection("s2", null, cpRule("r2", "Accept")));
        assertEquals("ALLOWED", run(inline, query("203.0.113.8")).get("verdict"));
    }
    @Test void validatesInputsStrictly() {
        for (String invalid : List.of("", "::1", "192.0.2.999", "192.0.2.1/33", "0192.0.2.1", "192.0.2.1-192.0.2.8", "192.0.2.1/01")) assertThrows(IllegalArgumentException.class, () -> query(invalid));
        for (String service : List.of("TCP/443", "tcp/65536", "tcp/-1", "icmp", "udp/01", "tcp/1-9")) assertThrows(IllegalArgumentException.class, () -> new PolicyLookup.Query("192.0.2.8", "198.51.100.8", service, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PolicyLookup.Query("192.0.2.8", "198.51.100.8", "any", "x".repeat(201), null, null, null));
    }
}
