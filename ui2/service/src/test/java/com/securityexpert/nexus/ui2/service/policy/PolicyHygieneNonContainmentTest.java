package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneApplicationDefaultTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.time.Instant;
import java.util.*;

/**
 * X1 (POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md, ratified in PO_DECISION_RECORD_2026_11_05_EVENING_BATCH.md): a partial
 * prior is also passed over when one of its resolved dimensions provably does not contain the later rule's, mirroring
 * where PolicyNormalization#holds answers FALSE. It changes only the question "may some partial prior be the single rule
 * that covers this rule" (Earlier#partialOverlap); a prior with no partial form (Earlier#unknown) still blocks everything.
 * The unit cases pin each fail-closed edge; the differential test compares X1 with the analysis with X1 switched off over
 * seeded snapshots and confirms every rule only X1 releases by brute force ({@link PartialPriorOracle#assertNoCover}).
 * Synthetic fixtures: documentation addresses and masked names only.
 */
@Execution(ExecutionMode.CONCURRENT)
class PolicyHygieneNonContainmentTest {
    static final Instant NOW = PolicyHygieneTest.NOW;
    static final Map<String, List<String>> USER = Map.of("source-user", List.of("OBJ-USER-01"));

    /** A Palo Alto rule with a destination and applications (any when none). */
    static Rule pr(String id, String source, String destination, String service, String action, String... apps) {
        var r = PolicyHygienePartialPriorTest.pan(id, source, destination, service, action);
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), true, r.source(), r.destination(), r.service(),
            new Cell(apps.length == 0 ? List.of("any") : List.of(apps), false), action, r.log(), "", r.extras());
    }
    static Rule partial(Rule rule) { return PolicyHygienePartialPriorTest.with(rule, USER); }
    static PolicyHygiene.Assessment x1(PolicySnapshot s, String id) { return PolicyHygieneTest.assessment(s, id); }
    static PolicyHygiene.Assessment off(PolicySnapshot s, String id) { return PolicyHygieneTest.assessmentWithoutX1(s, id); }
    /** Blocked with X1 off and with it on, by the same blocker. */
    static void stillBlocked(PolicySnapshot s, String id, String blocker) {
        for (var a : List.of(x1(s, id), off(s, id))) {
            assertEquals("UNKNOWN", a.shadowStatus(), id + " " + a); assertEquals(UNSUPPORTED, a.shadowReason());
            assertEquals(blocker, a.blockingRuleId());
        }
    }
    /** Blocked with X1 off, released with it on, and brute-force confirmed to be covered by no earlier rule. */
    static void released(PolicySnapshot s, String id) {
        var before = off(s, id);
        assertEquals("UNKNOWN", before.shadowStatus(), before.toString()); assertEquals(UNSUPPORTED, before.shadowReason());
        PolicyHygienePartialPriorTest.assertClean(s, id);
        assertNull(x1(s, id).blockingRuleId());
        PartialPriorOracle.assertNoCover(new PartialPriorOracle.Universe(s), id, id);
    }

    @Test void aNarrowResolvedSourceIsPassedOverForAWiderLaterSource() {
        // r1 (a user constraint, so a partial prior) matches one host at most; r2 matches a whole network. r1 cannot cover r2.
        released(pan(partial(pr("r1", "host", "any", "port", "allow")), pr("r2", "net", "any", "port", "allow")), "r2");
        // Same on the destination, and for a later rule of any.
        released(pan(partial(pr("r1", "any", "host", "port", "allow")), pr("r2", "any", "net", "port", "allow")), "r2");
        released(pan(partial(pr("r1", "host", "any", "port", "allow")), pr("r2", "any", "any", "port", "allow")), "r2");
    }
    @Test void aPriorWhoseSelectorContainsTheLaterOneStaysBlocked() {
        stillBlocked(pan(partial(pr("r1", "net", "any", "port", "allow")), pr("r2", "host", "any", "port", "allow")), "r2", "r1");
        stillBlocked(pan(partial(pr("r1", "net", "any", "port", "allow")), pr("r2", "net", "any", "port", "allow")), "r2", "r1");
        stillBlocked(pan(partial(pr("r1", "any", "any", "port", "allow")), pr("r2", "host", "net", "port", "allow")), "r2", "r1");
        // Every earlier partial prior must be passed over: the second one contains the rule.
        var second = pan(partial(pr("r1", "host", "any", "port", "allow")), partial(pr("r2", "net", "any", "port", "allow")), pr("r3", "net", "any", "port", "allow"));
        assertEquals("r1", off(second, "r3").blockingRuleId());
        assertEquals("UNKNOWN", x1(second, "r3").shadowStatus()); assertEquals("r2", x1(second, "r3").blockingRuleId());
    }
    @Test void anExplicitServiceNotContainedByThePriorsIsPassedOver() {
        released(pan(partial(pr("r1", "host", "any", "port", "allow")), pr("r2", "host", "any", "ports", "allow")), "r2");
        // A prior with service any contains every service.
        stillBlocked(pan(partial(pr("r1", "host", "any", "any", "allow")), pr("r2", "host", "any", "ports", "allow")), "r2", "r1");
    }
    @Test void applicationDefaultOnEitherSideGivesNoServiceVerdict() {
        // Application-default against explicit ports (either way round) and against itself: whether the default ports lie
        // inside the other service is unknown (holds answers null), so the service dimension passes nothing over.
        stillBlocked(pan(partial(pr("r1", "host", "any", APPDEF, "allow", A)), pr("r2", "host", "any", "port", "allow", A)), "r2", "r1");
        stillBlocked(pan(partial(pr("r1", "host", "any", "dns", "allow", A)), pr("r2", "host", "any", APPDEF, "allow", A)), "r2", "r1");
        stillBlocked(pan(partial(pr("r1", "host", "any", APPDEF, "allow", A)), pr("r2", "host", "any", APPDEF, "allow", B)), "r2", "r1");
        // The addresses still decide with application-default in play.
        released(pan(partial(pr("r1", "host", "any", APPDEF, "allow", A)), pr("r2", "net", "any", APPDEF, "allow", A)), "r2");
    }
    @Test void opaqueApplicationIdentitiesGiveNoApplicationVerdict() {
        // Same addresses and services, applications that may or may not overlap: no verdict on that dimension.
        stillBlocked(pan(partial(pr("r1", "host", "any", "port", "allow", A)), pr("r2", "host", "any", "port", "allow", B)), "r2", "r1");
        stillBlocked(pan(partial(pr("r1", "host", "any", "port", "allow", A)), pr("r2", "host", "any", "port", "allow")), "r2", "r1");
        stillBlocked(pan(partial(pr("r1", "host", "any", "port", "allow")), pr("r2", "host", "any", "port", "allow", A)), "r2", "r1");
    }
    @Test void anUnresolvedDimensionOfThePriorGivesNoVerdictOnIt() {
        // r1's destination is an unresolved object (a name missing from the snapshot): the destinations differ, but only
        // that unresolved dimension differs, so nothing may be passed over.
        stillBlocked(pan(pr("r1", "host", "missing-object", "port", "allow"), pr("r2", "host", "net", "port", "allow")), "r2", "r1");
        // An unresolved service of the prior leaves the service and application dimensions out of it as well.
        stillBlocked(pan(pr("r1", "host", "any", "missing-object", "allow", A), pr("r2", "host", "any", "port", "allow", B)), "r2", "r1");
        // The resolved source of the same prior still decides.
        released(pan(pr("r1", "host", "missing-object", "port", "allow"), pr("r2", "net", "net", "port", "allow")), "r2");
    }
    @Test void aPriorWithNoPartialFormStillBlocksEveryLaterRule() {
        // A field of unknown meaning: no partial prior at all, so r1 blocks r2 although its source is a single host.
        var unknown = PolicyHygienePartialPriorTest.with(pr("r1", "host", "any", "port", "allow"), Map.of("unexpected-field", List.of("yes")));
        stillBlocked(pan(unknown, pr("r2", "net", "any", "port", "allow")), "r2", "r1");
        // Behind a releasable partial prior it is still the blocker.
        stillBlocked(pan(partial(pr("r0", "host", "any", "port", "allow")), unknown, pr("r2", "net", "any", "port", "allow")), "r2", "r1");
        var ordered = pan(unknown, partial(pr("r1b", "host", "any", "port", "allow")), pr("r2", "net", "any", "port", "allow"));
        assertEquals("r1", x1(ordered, "r2").blockingRuleId());
    }
    @Test void anAccessRoleBoundIsAnUpperBoundForCannotCoverButNeverACoverCandidate() {
        // role-net matches at most 192.0.2.0/24. A later source of any is not inside it: no single role rule covers it.
        released(PolicyHygieneAccessRoleTest.cp(PolicyHygieneAccessRoleTest.cp("r1", 1, "Accept", "role-net", "any", "port"),
            PolicyHygieneAccessRoleTest.cp("r2", 2, "Accept", "any", "any", "port")), "r2");
        // A later source inside the bound may be covered by the role's rule: still blocked, and the bound covers nothing itself.
        var inside = PolicyHygieneAccessRoleTest.cp(PolicyHygieneAccessRoleTest.cp("r1", 1, "Accept", "role-net", "any", "any"),
            PolicyHygieneAccessRoleTest.cp("r2", 2, "Accept", "host", "any", "port"));
        stillBlocked(inside, "r2", "r1");
        assertNull(x1(inside, "r2").coveringRuleId());
        // A role without a bound (networks any) gives no verdict.
        stillBlocked(PolicyHygieneAccessRoleTest.cp(PolicyHygieneAccessRoleTest.cp("r1", 1, "Accept", "role-any", "any", "port"),
            PolicyHygieneAccessRoleTest.cp("r2", 2, "Accept", "any", "any", "port")), "r2", "r1");
    }
    @Test void aPassedOverPriorStillCannotCoverAndNothingElseChanges() {
        // r2 is covered by the full rule r1 (REDUNDANT) whatever the partial rule r0 before it says; X1 changes nothing there.
        var covered = pan(partial(pr("r0", "host", "any", "port", "allow")), pr("r1", "any", "any", "any", "allow"), pr("r2", "net", "any", "port", "allow"));
        assertEquals(off(covered, "r2"), x1(covered, "r2"));
        assertEquals("REDUNDANT", x1(covered, "r2").shadowStatus());
        // The partial prior's own verdict and every rule before the release are unchanged as well.
        var s = pan(partial(pr("r1", "host", "any", "port", "allow")), pr("r2", "net", "any", "port", "allow"));
        assertEquals(PartialPriorOracle.withoutBlocker(off(s, "r1")), PartialPriorOracle.withoutBlocker(x1(s, "r1")));
    }

    /**
     * Over seeded snapshots of every generator: a rule X1 does not change keeps the identical assessment (the blocker of a
     * rule that stays blocked may move later, never earlier); a rule X1 changes was "earlier rule has unsupported
     * constraints" and is exactly released, and no earlier rule of its scope, resolved or not, covers it by brute force in
     * the open universe or the pair's smallest world, under every access-role identity selection and default-port world.
     */
    @Test void x1ChangesOnlyBlockedRulesAndEveryReleaseIsNoCoverByBruteForce() {
        Map<String, Integer> counters = new TreeMap<>(); int rules = 0, snapshots = 0;
        for (long seed = 0; seed < 3500; seed++) {
            for (var snapshot : variants(seed)) {
                snapshots++;
                var reference = PolicyHygieneTest.withoutX1(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
                var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
                assertFalse(reference.budgetReached() || actual.budgetReached(), "seed " + seed);
                assertEquals(reference.rules().keySet(), actual.rules().keySet());
                var universe = new PartialPriorOracle.Universe(snapshot);
                boolean incomplete = !snapshot.failures().isEmpty(); String vendor = snapshot.metadata().vendor();
                for (var entry : reference.rules().entrySet()) {
                    String id = entry.getKey(), context = "seed " + seed + " " + snapshot.metadata().id() + " rule " + id;
                    var expected = entry.getValue(); var got = actual.rules().get(id);
                    var verdict = PartialPriorOracle.verdictOnly(universe, got, id, context);
                    rules++;
                    if (!PartialPriorOracle.UNSUPPORTED.equals(expected.shadowReason())) { assertEquals(expected, got, context); continue; }
                    if (PartialPriorOracle.UNSUPPORTED.equals(verdict.shadowReason())) {
                        assertEquals(PartialPriorOracle.withoutBlocker(expected), verdict, context);
                        assertTrue(universe.order.get(got.blockingRuleId()) >= universe.order.get(expected.blockingRuleId()), context + ": the blocker moved earlier");
                        counters.merge(vendor + " kept", 1, Integer::sum);
                        continue;
                    }
                    assertEquals(PartialPriorOracle.released(PartialPriorOracle.withoutBlocker(expected), incomplete), verdict, context);
                    assertNull(got.blockingRuleId(), context);
                    PartialPriorOracle.assertNoCover(universe, id, context);
                    counters.merge(vendor + " released", 1, Integer::sum);
                    if (!universe.unresolvedPriors(id).stream().allMatch(p -> universe.sharedPacket(p, universe.rules.get(id)) == null))
                        counters.merge(vendor + " released (shares a packet with a partial prior)", 1, Integer::sum);
                }
            }
        }
        for (String vendor : List.of("CP", "PAN")) for (String outcome : List.of(" released", " kept"))
            assertTrue(counters.getOrDefault(vendor + outcome, 0) >= 100, vendor + outcome + " in " + counters);
        assertTrue(counters.entrySet().stream().anyMatch(e -> e.getKey().contains("shares a packet with a partial prior") && e.getValue() > 0),
            "X1 must release rules the disjointness criterion kept: " + counters);
        System.out.println("X1 DIFFERENTIAL " + snapshots + " snapshots, " + rules + " rules, formerly-blocked outcomes per vendor: " + counters);
    }
    /** The plain seeded snapshot and its application-default / content-application, access-role and Panorama-target variants. */
    private static List<PolicySnapshot> variants(long seed) {
        var base = SyntheticPolicies.legacyComparable(SyntheticPolicies.random(seed + 700_000, 1 + (int) (seed % 60)));
        List<PolicySnapshot> out = new ArrayList<>(List.of(base));
        if (seed % 2 == 0) {
            if (base.metadata().vendor().equals("PAN")) {
                out.add(SyntheticPolicies.contentApplications(SyntheticPolicies.applicationDefault(base, seed), seed));
                out.add(SyntheticPolicies.panTargets(base, seed));
            } else out.add(SyntheticPolicies.accessRoles(base, seed));
        }
        if (seed % 5 == 0) out.add(SyntheticPolicies.inline(seed + 800_000, 5 + (int) (seed % 40)));
        return out;
    }
}
