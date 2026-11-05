package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.assertUnknown;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Partial priors: an earlier rule that cannot be fully resolved (a Check Point access-role source, a user, time or
 * category constraint, an unsupported service) keeps the dimensions that did resolve. A later rule is NOT_SHADOWED only
 * when every such prior has a resolved dimension disjoint from it; a partial prior never covers anything. Synthetic
 * fixtures: documentation addresses and masked names only.
 */
class PolicyHygienePartialPriorTest {
    static final String UNSUPPORTED = "earlier rule has unsupported constraints";
    static Map<String, PolicyObject> partialObjects() {
        var objects = objects();
        // How CheckPointPolicyMapper stores an access role: an identity object no selector resolution understands.
        objects.put("role", new PolicyObject("role", "OBJ-ROLE-01", "access-role", List.of(), List.of(), "RESOLVED"));
        objects.put("role-unsupported", new PolicyObject("role-unsupported", "OBJ-ROLE-02", "access-role", List.of(), List.of(), "UNSUPPORTED"));
        objects.put("other-net", object("other-net", "network", "ip-netmask: 198.51.100.0/24"));
        objects.put("other-host", object("other-host", "host", "ipv4-address: 198.51.100.7"));
        objects.put("dns", object("dns", "service-udp", "protocol: udp", "port: 53"));
        objects.put("icmp", object("icmp", "service", "protocol: icmp", "port: 8"));
        objects.put("office-hours", object("office-hours", "time"));
        objects.put("gw-a", object("gw-a", "simple-gateway")); objects.put("gw-b", object("gw-b", "simple-gateway"));
        return objects;
    }
    /** A Check Point access rule: source, destination, service, install-on "any" unless given. */
    static Rule cp(String id, int number, String action, String source, String destination, String service) {
        var r = cpLayerRule(id, number, "LAYER-ALPHA", action);
        return new Rule(r.id(), r.uuid(), number, r.name(), true, cell(source), cell(destination), cell(service), new Cell(List.of(), false),
            action, r.log(), r.comment(), r.extras());
    }
    static Rule with(Rule rule, Map<String, List<String>> extras) { return SyntheticPolicies.withExtras(rule, rule.action(), extras); }
    static PolicySnapshot cp(Rule... rules) {
        var base = sections("CP", cpSection("s1", null, rules));
        return new PolicySnapshot(base.metadata(), base.sections(), partialObjects());
    }
    static PolicySnapshot pan(Rule... rules) { return snapshot(List.of(rules), partialObjects()); }
    static Rule pan(String id, String source, String destination, String service, String action) {
        var r = rule(id, source, service, action, true);
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), true, r.source(), cell(destination), r.service(), r.application(), action, r.log(), "", r.extras());
    }
    static void assertClean(PolicySnapshot snapshot, String id) {
        var result = assessment(snapshot, id);
        assertEquals("NOT_SHADOWED", result.shadowStatus(), id + " " + result);
        assertNull(result.shadowReason()); assertNull(result.coveringRuleId());
        assertTrue(result.findings().stream().noneMatch(f -> f.evidence().startsWith("Shadowing:")), result.findings().toString());
    }

    @Test void accessRolePriorWithDisjointDestinationReleasesTheLaterRule() {
        for (String role : List.of("role", "role-unsupported")) {
            var snapshot = cp(cp("r1", 1, "Accept", role, "net", "port"), cp("r2", 2, "Accept", "any", "other-host", "port"));
            assertUnknown(snapshot, "r1", role.equals("role") ? "dynamic or unsupported object type" : "unresolved object");
            assertClean(snapshot, "r2");
            // A disjoint service (TCP 443 against UDP 53) proves it as well.
            assertClean(cp(cp("r1", 1, "Accept", role, "any", "port"), cp("r2", 2, "Accept", "any", "any", "dns")), "r2");
        }
    }
    @Test void accessRolePriorWithOverlappingDestinationKeepsTheLaterRuleUnknown() {
        // 192.0.2.8 lies inside 192.0.2.0/24; the source is the unresolved access role, so nothing is disjoint. The prior
        // also contains the later destination and service, so X1 (non-containment) passes nothing over either.
        for (String destination : List.of("host", "net", "group")) {
            var snapshot = cp(cp("r1", 1, "Accept", "role", "net", "port"), cp("r2", 2, "Accept", "other-net", destination, "port"));
            assertUnknown(snapshot, "r2", UNSUPPORTED);
        }
        // A later destination or service the prior does not contain is released by X1 only; without X1 it stays unknown.
        for (var later : List.of(cp("r2", 2, "Accept", "other-net", "any", "port"), cp("r2", 2, "Accept", "other-net", "net", "ports"))) {
            var snapshot = cp(cp("r1", 1, "Accept", "role", "net", "port"), later);
            assertEquals(UNSUPPORTED, assessmentWithoutX1(snapshot, "r2").shadowReason());
            assertClean(snapshot, "r2");
        }
        // Every partial prior must be disjoint: one overlapping prior among disjoint ones keeps the rule unknown.
        var mixed = cp(cp("r1", 1, "Accept", "role", "other-net", "port"), cp("r2", 2, "Drop", "role", "net", "port"),
            cp("r3", 3, "Accept", "any", "net", "port"));
        assertUnknown(mixed, "r3", UNSUPPORTED);
    }
    @Test void aPartialPriorNeverCovers() {
        // Were r1's source resolvable as "any", r1 would cover r2 (REDUNDANT) and r3 (CONFLICT). As a partial prior it covers nothing.
        for (String action : List.of("Accept", "Drop")) {
            var snapshot = cp(cp("r1", 1, "Accept", "role", "any", "any"), cp("r2", 2, action, "host", "host", "port"));
            assertUnknown(snapshot, "r2", UNSUPPORTED);
            assertTrue(assessment(snapshot, "r2").findings().stream().noneMatch(f -> Set.of("shadowed", "conflict").contains(f.findingClass())));
        }
        // A later full cover is still found behind a partial prior; the partial prior does not take its place.
        var behind = cp(cp("r1", 1, "Accept", "role", "any", "any"), cp("r2", 2, "Drop", "any", "net", "any"), cp("r3", 3, "Accept", "host", "host", "port"));
        assertEquals("CONFLICT", assessment(behind, "r3").shadowStatus());
        assertEquals("r2", assessment(behind, "r3").coveringRuleId());
    }
    @Test void narrowingConstraintsArePartialPriors() {
        // A time constraint only restricts when the rule matches; a user constraint only whom it matches.
        var timed = with(cp("r1", 1, "Accept", "any", "net", "port"), Map.of("time", List.of("office-hours")));
        var snapshot = cp(timed, cp("r2", 2, "Accept", "any", "other-net", "port"));
        assertUnknown(snapshot, "r1", "time or content constraint");
        assertClean(snapshot, "r2");
        assertUnknown(cp(timed, cp("r2", 2, "Accept", "any", "host", "port")), "r2", UNSUPPORTED);
        var user = with(pan("r1", "any", "net", "port", "allow"), Map.of("source-user", List.of("OBJ-USER-01")));
        var users = pan(user, pan("r2", "any", "other-net", "port", "allow"));
        assertUnknown(users, "r1", "user or category constraint");
        assertClean(users, "r2");
        // An unsupported service: the other resolved dimensions still count.
        var icmp = pan(pan("r1", "any", "net", "icmp", "allow"), pan("r2", "any", "other-host", "port", "allow"));
        assertUnknown(icmp, "r1", "unsupported service constraint");
        assertClean(icmp, "r2");
    }
    @Test void constraintsOfUnknownMeaningAreNoPartialPriors() {
        // A field the analysis does not understand (a Panorama target shape nobody parsed, a negate value other than yes or
        // no, an unknown field) might widen the match: no partial prior. A documented target only narrows
        // (PolicyNormalization#targetNarrows, PolicyHygienePanTargetTest).
        for (var field : List.of(Map.entry("target-unrecognized", "unexpected"), Map.entry("target/negate", "maybe"), Map.entry("unexpected-field", "yes"))) {
            var unknown = with(pan("r1", "any", "net", "port", "allow"), Map.of(field.getKey(), List.of(field.getValue())));
            var snapshot = pan(unknown, pan("r2", "any", "other-net", "port", "allow"));
            assertUnknown(snapshot, "r1", "unsupported rule constraint");
            assertUnknown(snapshot, "r2", UNSUPPORTED);
        }
        // The same rule with a documented target (negate yes) is a partial prior: its destination is disjoint from r2's.
        assertClean(pan(with(pan("r1", "any", "net", "port", "allow"), Map.of("target/negate", List.of("yes"))), pan("r2", "any", "other-net", "port", "allow")), "r2");
        // A Check Point rule record without its time column is not relied on either.
        var r1 = cp("r1", 1, "Accept", "role", "net", "port");
        var extras = new HashMap<String, List<String>>(); extras.put("time", null);
        var untimed = cp(SyntheticPolicies.withExtras(r1, "Accept", extras), cp("r2", 2, "Accept", "any", "other-net", "port"));
        assertUnknown(untimed, "r2", UNSUPPORTED);
    }
    @Test void negationAndUnresolvedDimensionsNeverProveDisjointness() {
        // r1 really matches only destinations outside 192.0.2.0/24, but a negated cell is not resolved and proves nothing.
        var negated = cp("r1", 1, "Accept", "role", "net", "port");
        negated = new Rule(negated.id(), negated.uuid(), 1, negated.name(), true, negated.source(), new Cell(List.of("net"), true), negated.service(),
            negated.application(), "Accept", negated.log(), negated.comment(), negated.extras());
        assertUnknown(cp(negated, cp("r2", 2, "Accept", "any", "host", "port")), "r2", UNSUPPORTED);
        // A source and destination that both fail to resolve leave only the service, which overlaps.
        assertUnknown(cp(cp("r1", 1, "Accept", "role", "missing", "port"), cp("r2", 2, "Accept", "other-net", "other-net", "port")), "r2", UNSUPPORTED);
    }
    @Test void placementsCountAsDimensionsUnderTheExistingDisjointnessRules() {
        // Two resolved, atomic install-on targets are disjoint; an opaque target or "any" never is.
        var a = with(cp("r1", 1, "Accept", "role", "any", "any"), Map.of("install-on", List.of("gw-a")));
        assertClean(cp(a, with(cp("r2", 2, "Accept", "any", "any", "any"), Map.of("install-on", List.of("gw-b")))), "r2");
        for (String target : List.of("any", "targets")) {
            var base = cp(a, with(cp("r2", 2, "Accept", "any", "any", "any"), Map.of("install-on", List.of(target))));
            var snapshot = new PolicySnapshot(base.metadata(), base.sections(), with(base.objects(), PolicyHygieneCheckPointScopeTest.policyTargets("targets")));
            assertUnknown(snapshot, "r2", UNSUPPORTED);
        }
        // Palo Alto zones: disjoint zones prove it for a universal rule, never for an intrazone or interzone rule.
        var zoned = with(pan("r1", "any", "any", "icmp", "allow"), Map.of("from", List.of("ZONE-ALPHA-01")));
        var later = with(pan("r2", "any", "any", "port", "allow"), Map.of("from", List.of("ZONE-BRAVO-02")));
        assertClean(pan(zoned, later), "r2");
        assertUnknown(pan(with(zoned, Map.of("rule-type", List.of("intrazone"))), later), "r2", UNSUPPORTED);
        // An intrazone prior is still a partial prior through its resolved selectors.
        var intrazone = with(pan("r1", "any", "net", "port", "allow"), Map.of("rule-type", List.of("intrazone")));
        assertClean(pan(intrazone, pan("r2", "any", "other-net", "port", "allow")), "r2");
    }
    @Test void unknownEnabledStateAndNonTerminalActionsArePartialPriors() {
        var maybe = pan("r1", "any", "net", "port", "allow");
        maybe = new Rule(maybe.id(), maybe.uuid(), maybe.number(), maybe.name(), null, maybe.source(), maybe.destination(), maybe.service(),
            maybe.application(), maybe.action(), maybe.log(), maybe.comment(), maybe.extras());
        assertClean(pan(maybe, pan("r2", "any", "other-net", "port", "allow")), "r2");
        assertUnknown(pan(maybe, pan("r2", "any", "host", "port", "allow")), "r2", UNSUPPORTED);
        assertClean(pan(pan("r1", "any", "net", "port", "ask"), pan("r2", "any", "other-net", "port", "allow")), "r2");
    }
    @Test void partialPriorWorkIsBoundedAndRunningOutKeepsTheFormerVerdict() {
        // 3,000 access-role rules on distinct destinations, then 3,000 rules on a disjoint network.
        var objects = partialObjects(); List<Rule> rules = new ArrayList<>(); int n = 3000;
        for (int i = 0; i < n; i++) {
            String id = "d" + i; objects.put(id, object(id, "host", "ipv4-address: 192.0.2." + (i % 250)));
            objects.put("p" + i, object("p" + i, "service-tcp", "protocol: tcp", "port: " + (1000 + i)));
            rules.add(cp("a" + i, i + 1, "Accept", "role", id, "p" + i));
        }
        for (int i = 0; i < n; i++) rules.add(cp("b" + i, n + i + 1, "Accept", "any", "other-host", "p" + i));
        var base = sections("CP", new Section("s1", "OBJ-SECTION-01", "CP access layer", null, rules));
        var snapshot = new PolicySnapshot(base.metadata(), base.sections(), objects);
        long started = System.nanoTime();
        var result = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertFalse(result.budgetReached());
        for (int i = 0; i < n; i++) assertEquals("NOT_SHADOWED", result.rules().get("b" + i).shadowStatus(), "b" + i);
        assertTrue(millis < 20_000, millis + " ms");
        // A per-rule window too small for 3,000 partial-prior checks: the later rule keeps the former verdict, and the
        // snapshot-wide budget is untouched by the partial work (no budget verdict anywhere).
        var one = sections("CP", new Section("s1", "OBJ-SECTION-01", "CP access layer", null, rules.subList(0, n + 1)));
        var small = new PolicySnapshot(one.metadata(), one.sections(), objects);
        var tight = PolicyHygiene.analyze(small, 90, NOW, PolicyHygiene.TOTAL_WORK, 1500, small.objects());
        assertFalse(tight.budgetReached());
        assertEquals(UNSUPPORTED, tight.rules().get("b0").shadowReason());
        for (int i = 0; i < n; i++) assertEquals("dynamic or unsupported object type", tight.rules().get("a" + i).shadowReason(), "a" + i);
        assertClean(small, "b0");
        System.out.println("PARTIAL PRIOR SCALE " + n + " partial priors x " + n + " later rules: " + millis + " ms");
    }
    private static Map<String, PolicyObject> with(Map<String, PolicyObject> objects, PolicyObject extra) {
        var out = new HashMap<>(objects); out.put(extra.id(), extra); return out;
    }
}
