package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Palo Alto application-default in shadowing: the mapper's application-default service is an opaque token whose meaning
 * depends on the rule's applications (PATH_ANALYSIS_VENDOR_SEMANTICS.md section 6: each application on its own standard
 * ports, which are not collected; application any with application-default is UNKNOWN). Covered by an earlier service
 * any, or by an earlier application-default rule whose applications contain the later rule's by identity; never by
 * explicit ports; never disjoint from a service. Every verdict is checked against the former "missing service values"
 * treatment through the brute-force oracle over every default-port world. Synthetic fixtures: documentation addresses
 * and masked names only.
 */
class PolicyHygieneApplicationDefaultTest {
    static final String DEFAULT_PORTS = PolicyHygiene.DEFAULT_PORTS_UNKNOWN, UNSUPPORTED = PartialPriorOracle.UNSUPPORTED,
        UNPROVEN = PartialPriorOracle.UNPROVEN, MISSING = "missing service values";
    /** The snapshot id of {@link PolicyHygieneTest#snapshot}; the mapper's references are minted under it. */
    static final String SNAPSHOT = "p1";
    static final String APPDEF = ref(SNAPSHOT, "service", "application-default"), A = ref(SNAPSHOT, "application", "APP-ALPHA-01"),
        B = ref(SNAPSHOT, "application", "APP-BRAVO-02"), GROUP = ref(SNAPSHOT, "application", "APP-GROUP-01"),
        FILTER = ref(SNAPSHOT, "application", "APP-FILTER-01");
    static final String Z1 = "ZONE-ALPHA-01", Z2 = "ZONE-BRAVO-02", Z3 = "ZONE-CHARLIE-03";

    static Map<String, PolicyObject> appObjects() {
        var objects = PolicyHygienePartialPriorTest.partialObjects();
        // As PanoramaPolicyMapper stores them: application-default as a RESOLVED service without values, predefined
        // applications without a definition as UNRESOLVED under their application reference.
        objects.put(APPDEF, new PolicyObject(APPDEF, "application-default", "service", List.of(), List.of(), "RESOLVED"));
        objects.put(A, new PolicyObject(A, "APP-ALPHA-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        objects.put(B, new PolicyObject(B, "APP-BRAVO-02", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        objects.put(GROUP, new PolicyObject(GROUP, "APP-GROUP-01", "application-group", List.of(A, B), List.of(), "RESOLVED"));
        objects.put(FILTER, new PolicyObject(FILTER, "APP-FILTER-01", "application-filter", List.of(), List.of("category: networking"), "DYNAMIC"));
        objects.put("custom", object("custom", "application", "default/port: tcp/8443"));
        return objects;
    }
    /** A Palo Alto rule, zones any to any, destination any, with the given applications (any when none). */
    static Rule rule(String id, String source, String service, String action, String... apps) {
        var r = PolicyHygienePartialPriorTest.pan(id, source, "any", service, action);
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), true, r.source(), r.destination(), r.service(),
            new Cell(apps.length == 0 ? List.of("any") : List.of(apps), false), action, r.log(), "", r.extras());
    }
    static Rule zoned(Rule rule, List<String> from, List<String> to) { return PolicyHygienePartialPriorTest.with(rule, Map.of("from", from, "to", to)); }
    static PolicySnapshot pan(Rule... rules) { return PolicyHygieneTest.snapshot(List.of(rules), appObjects()); }
    static PolicyHygiene.Assessment at(PolicySnapshot snapshot, String id) { return PolicyHygieneTest.assessment(snapshot, id); }
    static PolicyHygiene.Assessment former(PolicySnapshot snapshot, String id) {
        return PolicyHygieneTest.assessment(SyntheticPolicies.unrecognisedDefault(snapshot), id);
    }
    /** Every rule against the former treatment, each change confirmed in every default-port world. */
    static void assertOracle(PolicySnapshot snapshot) {
        var former = PolicyHygiene.analyze(SyntheticPolicies.unrecognisedDefault(snapshot), 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        var actual = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        var universe = new PartialPriorOracle.Universe(snapshot);
        for (var entry : former.entrySet())
            PartialPriorOracle.assertDefaultPortsChangeIsSafe(universe, entry.getValue(), actual.get(entry.getKey()), entry.getKey(), entry.getKey());
    }
    static void assertCovered(PolicySnapshot snapshot, String id, String covering, String status) {
        var result = at(snapshot, id);
        assertEquals(status, result.shadowStatus(), id + " " + result);
        assertEquals(covering, result.coveringRuleId()); assertNull(result.shadowReason());
        assertOracle(snapshot);
    }
    static void assertReason(PolicySnapshot snapshot, String id, String reason) {
        var result = at(snapshot, id);
        assertEquals("UNKNOWN", result.shadowStatus(), id + " " + result);
        assertEquals(reason, result.shadowReason()); assertNull(result.coveringRuleId());
        assertOracle(snapshot);
    }
    static void assertClean(PolicySnapshot snapshot, String id) {
        PolicyHygienePartialPriorTest.assertClean(snapshot, id);
        assertOracle(snapshot);
        PartialPriorOracle.assertNoCover(new PartialPriorOracle.Universe(snapshot), id, id);
    }

    @Test void anApplicationDefaultRuleIsFullyResolvable() {
        var snapshot = pan(rule("r1", "host", APPDEF, "allow", A));
        assertEquals(MISSING, former(snapshot, "r1").shadowReason());
        assertClean(snapshot, "r1");
        // Shadowing only: lookup, offline and path analysis keep the plain resolver, for which it stays missing service values.
        var section = snapshot.sections().get(0); var r1 = section.rules().get(0);
        var plain = new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(10_000));
        assertEquals(MISSING, PolicyNormalization.normalize(snapshot, section, r1, plain).problem());
        assertEquals(MISSING, PolicyNormalization.normalize(snapshot, section, r1, plain, true).problem());
        var match = PolicyNormalization.normalize(snapshot, section, r1, PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(10_000)));
        assertNull(match.problem());
        assertTrue(match.selectors().get(2).applicationDefault());
        assertEquals(Set.of(A), match.selectors().get(3).tokens());
    }
    @Test void earlierServiceAnyCoversApplicationDefault() {
        // (a) an earlier service any (application any) holds every port of every application.
        assertCovered(pan(rule("r1", "net", "any", "deny"), rule("r2", "host", APPDEF, "allow", A)), "r2", "r1", "CONFLICT");
        assertCovered(pan(rule("r1", "net", "any", "allow"), rule("r2", "host", APPDEF, "allow", A, B)), "r2", "r1", "REDUNDANT");
        assertCovered(pan(rule("r1", "any", "any", "allow"), rule("r2", "host", APPDEF, "allow")), "r2", "r1", "REDUNDANT");
        // The source must still hold: host does not hold net, so r1 cannot cover r2 in any world.
        assertClean(pan(rule("r1", "host", "any", "deny"), rule("r2", "net", APPDEF, "allow", A)), "r2");
    }
    @Test void earlierApplicationDefaultCoversTheSameOrFewerApplications() {
        // (b) both application-default, the earlier applications contain the later ones by identity.
        assertCovered(pan(rule("r1", "net", APPDEF, "allow", A, B), rule("r2", "host", APPDEF, "allow", A)), "r2", "r1", "REDUNDANT");
        assertCovered(pan(rule("r1", "net", APPDEF, "deny", A, B), rule("r2", "net", APPDEF, "allow", B, A)), "r2", "r1", "CONFLICT");
        // An application group is its members' references.
        assertCovered(pan(rule("r1", "net", APPDEF, "allow", GROUP), rule("r2", "host", APPDEF, "allow", B)), "r2", "r1", "REDUNDANT");
        assertCovered(pan(rule("r1", "net", APPDEF, "allow", A, B), rule("r2", "host", APPDEF, "allow", GROUP)), "r2", "r1", "REDUNDANT");
        // A stored custom application is one reference too.
        assertCovered(pan(rule("r1", "net", APPDEF, "allow", "custom"), rule("r2", "host", APPDEF, "allow", "custom")), "r2", "r1", "REDUNDANT");
        // Application any against application any: the same fixed (unknown) match.
        assertCovered(pan(rule("r1", "net", APPDEF, "allow"), rule("r2", "host", APPDEF, "allow")), "r2", "r1", "REDUNDANT");
    }
    @Test void earlierAnyApplicationOrOtherApplicationsLeaveTheCoverToDefaultPorts() {
        // Application any with application-default is not read as every application's default ports (section 6 UNKNOWN).
        assertReason(pan(rule("r1", "net", APPDEF, "deny"), rule("r2", "host", APPDEF, "allow", A)), "r2", DEFAULT_PORTS);
        assertReason(pan(rule("r1", "net", APPDEF, "deny", A), rule("r2", "host", APPDEF, "allow")), "r2", DEFAULT_PORTS);
        // More applications later, or other ones: two references are never proven to be disjoint traffic.
        assertReason(pan(rule("r1", "net", APPDEF, "deny", A), rule("r2", "host", APPDEF, "allow", A, B)), "r2", DEFAULT_PORTS);
        assertReason(pan(rule("r1", "net", APPDEF, "deny", A), rule("r2", "host", APPDEF, "allow", GROUP)), "r2", DEFAULT_PORTS);
        assertReason(pan(rule("r1", "net", APPDEF, "deny", A), rule("r2", "host", APPDEF, "allow", B)), "r2", DEFAULT_PORTS);
        // None of them is ever named "earlier rule has unsupported constraints" or a blocker.
        assertNull(at(pan(rule("r1", "net", APPDEF, "deny", A), rule("r2", "host", APPDEF, "allow", B)), "r2").blockingRuleId());
    }
    @Test void explicitPortsNeverCoverApplicationDefaultNorTheReverse() {
        for (String service : List.of("port", "ports", "dns")) {
            assertReason(pan(rule("r1", "net", service, "deny"), rule("r2", "host", APPDEF, "allow", A)), "r2", DEFAULT_PORTS);
            assertReason(pan(rule("r1", "net", APPDEF, "deny", A), rule("r2", "host", service, "allow")), "r2", DEFAULT_PORTS);
        }
        assertReason(pan(rule("r1", "net", APPDEF, "deny"), rule("r2", "host", "any", "allow")), "r2", DEFAULT_PORTS);
        // A later proven cover still wins: the any-service rule r2 covers r3 whatever r1's default ports are.
        assertCovered(pan(rule("r1", "net", "port", "deny"), rule("r2", "net", "any", "allow"), rule("r3", "host", APPDEF, "allow", A)), "r3", "r2", "REDUNDANT");
    }
    @Test void aRuleFormerlyBlockedByAnApplicationDefaultRuleIsDecided() {
        // r1's source (one host) holds no other address of r2's network: r1 cannot cover r2 whatever its default ports.
        var snapshot = pan(rule("r1", "host", APPDEF, "deny", A), rule("r2", "net", "port", "allow"));
        var before = former(snapshot, "r2");
        assertEquals(UNSUPPORTED, before.shadowReason()); assertEquals("r1", before.blockingRuleId());
        assertClean(snapshot, "r2");
        assertClean(pan(rule("r1", "host", APPDEF, "deny", A), rule("r2", "net", APPDEF, "allow", A)), "r2");
        assertClean(pan(rule("r1", "host", APPDEF, "deny"), rule("r2", "net", APPDEF, "allow", B)), "r2");
    }
    @Test void applicationDefaultIsNeverDisjointFromAService() {
        // A partial prior (a user constraint) whose service is application-default: only another dimension proves disjointness.
        var user = Map.of("source-user", List.of("OBJ-USER-01"));
        var prior = PolicyHygienePartialPriorTest.with(rule("r1", "host", APPDEF, "deny", A), user);
        for (String service : List.of("port", "dns", "any", APPDEF)) {
            var overlapping = pan(prior, rule("r2", "net", service, "allow", service.equals(APPDEF) ? new String[]{B} : new String[0]));
            assertEquals("user or category constraint", at(overlapping, "r1").shadowReason());
            assertReason(overlapping, "r2", UNSUPPORTED);
            assertEquals("r1", at(overlapping, "r2").blockingRuleId());
            assertClean(pan(prior, rule("r2", "other-net", service, "allow", service.equals(APPDEF) ? new String[]{B} : new String[0])), "r2");
        }
        // Unresolvable applications (a dynamic filter) leave the service token and the addresses of the partial prior.
        var filter = pan(rule("r1", "host", APPDEF, "deny", FILTER), rule("r2", "net", "port", "allow"));
        assertEquals("unresolved object", at(filter, "r1").shadowReason());
        assertReason(filter, "r2", UNSUPPORTED);
        assertClean(pan(rule("r1", "host", APPDEF, "deny", FILTER), rule("r2", "other-net", "port", "allow")), "r2");
    }
    @Test void onlyTheMappersApplicationDefaultIsRecognised() {
        var objects = appObjects();
        // The same shape under another reference (not the mapper's application-default), and application-default mixed
        // with another service: both stay missing service values.
        objects.put("lookalike", new PolicyObject("lookalike", "application-default", "service", List.of(), List.of(), "RESOLVED"));
        var lookalike = PolicyHygieneTest.snapshot(List.of(rule("r1", "host", "lookalike", "allow", A)), objects);
        assertEquals(MISSING, at(lookalike, "r1").shadowReason());
        var mixed = PolicyHygieneTest.snapshot(List.of(new Rule("r1", "uuid-r1", 1, "OBJ-RULE-r1", true, new Cell(List.of("host"), false), new Cell(List.of("any"), false),
            new Cell(List.of(APPDEF, "port"), false), new Cell(List.of(A), false), "allow", "Log", "", Map.of("from", List.of("any"), "to", List.of("any")))), objects);
        assertEquals(MISSING, at(mixed, "r1").shadowReason());
        // An application reference minted under another snapshot id is no predefined application of this one.
        String foreign = ref("p2", "application", "APP-ALPHA-01");
        objects.put(foreign, new PolicyObject(foreign, "APP-ALPHA-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        var other = PolicyHygieneTest.snapshot(List.of(rule("r1", "host", APPDEF, "allow", foreign)), objects);
        assertEquals("unresolved object", at(other, "r1").shadowReason());
        // A dynamic application filter stays unresolvable.
        assertEquals("unresolved object", at(pan(rule("r1", "host", APPDEF, "allow", FILTER)), "r1").shadowReason());
    }
    @Test void placementUndecidedApplicationDefaultPriorsArePassedOverOnlyWhenTheyCannotCover() {
        // r1 names zones (undecided against any); its source holds r2's, and its cover depends on default ports: unproven.
        assertReason(pan(zoned(rule("r1", "net", APPDEF, "deny", A), List.of(Z1), List.of(Z2)), rule("r2", "host", "port", "allow")), "r2", UNPROVEN);
        // Its source does not hold r2's: passed over in every placement and every world.
        assertClean(pan(zoned(rule("r1", "host", APPDEF, "deny", A), List.of(Z1), List.of(Z2)), rule("r2", "net", "port", "allow")), "r2");
    }
    @Test void zoneSupersetsCoverApplicationDefaultRules() {
        assertCovered(pan(zoned(rule("r1", "net", APPDEF, "allow", A, B), List.of(Z1, Z2), List.of(Z3)),
            zoned(rule("r2", "host", APPDEF, "allow", A), List.of(Z1), List.of(Z3))), "r2", "r1", "REDUNDANT");
        // Through a superset placement the cover still depends on default ports where the applications do not contain.
        assertReason(pan(zoned(rule("r1", "net", APPDEF, "deny", A), List.of(Z1, Z2), List.of(Z3)),
            zoned(rule("r2", "host", APPDEF, "allow", B), List.of(Z1), List.of(Z3))), "r2", DEFAULT_PORTS);
    }
    @Test void theNewReasonIsAProjectedShadowReason() {
        assertTrue(PolicyHygiene.REASONS.contains(DEFAULT_PORTS));
        assertEquals(DEFAULT_PORTS, PartialPriorOracle.DEFAULT_PORTS);
    }
}
