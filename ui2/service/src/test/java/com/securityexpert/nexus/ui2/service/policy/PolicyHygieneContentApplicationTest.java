package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneApplicationDefaultTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Palo Alto content-provided applications in shadowing: on a rule whose service is explicit ports or any, an application
 * the configuration does not define (stored UNRESOLVED under its own application reference, e.g. a predefined App-ID
 * application) is an opaque application identity (PolicyNormalization.Resolver#contentApplication). Covered on the
 * application dimension by an earlier any, or by an earlier set containing every later identity; any other pair that
 * holds every other dimension is UNKNOWN "application overlap is unknown" (applications may overlap); never disjoint.
 * Groups, filters, custom applications and unresolved addresses or services keep their former handling. Every verdict
 * is checked against the former "unresolved object" treatment through the brute-force oracle, for every overlap of the
 * named applications. Synthetic fixtures: documentation addresses and masked names only.
 */
class PolicyHygieneContentApplicationTest {
    static final String OVERLAP = PolicyHygiene.APPLICATION_OVERLAP_UNKNOWN, UNRESOLVED = "unresolved object";
    static PolicyHygiene.Assessment formerly(PolicySnapshot snapshot, String id) {
        return PolicyHygieneTest.assessment(SyntheticPolicies.unrecognisedApplications(snapshot), id);
    }
    /** Every rule against the former treatment, each change confirmed for every overlap and every default-port world. */
    static void assertOracle(PolicySnapshot snapshot) {
        var former = PolicyHygiene.analyze(SyntheticPolicies.unrecognisedApplications(snapshot), 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        var actual = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        var universe = new PartialPriorOracle.Universe(snapshot);
        for (var entry : former.entrySet())
            PartialPriorOracle.assertContentApplicationChangeIsSafe(universe, entry.getValue(), actual.get(entry.getKey()), entry.getKey(), entry.getKey());
    }
    static void covered(PolicySnapshot snapshot, String id, String covering, String status) {
        var result = at(snapshot, id);
        assertEquals(status, result.shadowStatus(), id + " " + result);
        assertEquals(covering, result.coveringRuleId()); assertNull(result.shadowReason());
        assertOracle(snapshot);
    }
    static void unknown(PolicySnapshot snapshot, String id, String reason) {
        var result = at(snapshot, id);
        assertEquals("UNKNOWN", result.shadowStatus(), id + " " + result);
        assertEquals(reason, result.shadowReason()); assertNull(result.coveringRuleId());
        assertOracle(snapshot);
    }
    static void clean(PolicySnapshot snapshot, String id) {
        PolicyHygienePartialPriorTest.assertClean(snapshot, id);
        assertOracle(snapshot);
        PartialPriorOracle.assertNoCover(new PartialPriorOracle.Universe(snapshot), id, id);
    }

    @Test void aContentApplicationOnAnExplicitServiceRuleIsAnOpaqueIdentity() {
        for (String service : List.of("port", "any")) {
            var snapshot = pan(rule("r1", "host", service, "allow", A, B));
            assertEquals(UNRESOLVED, formerly(snapshot, "r1").shadowReason());
            clean(snapshot, "r1");
            var section = snapshot.sections().get(0); var r1 = section.rules().get(0);
            var match = PolicyNormalization.normalize(snapshot, section, r1, PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(10_000)));
            assertNull(match.problem());
            assertEquals(Set.of(A, B), match.selectors().get(3).tokens());
            assertFalse(match.selectors().get(3).any());
            // Shadowing only: lookup, offline and path analysis keep the plain resolver, for which it stays unresolved.
            var plain = new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(10_000));
            assertEquals(UNRESOLVED, PolicyNormalization.normalize(snapshot, section, r1, plain).problem());
            assertEquals(UNRESOLVED, PolicyNormalization.normalize(snapshot, section, r1, plain, true).problem());
        }
        // Mixed with any it is any.
        var mixed = pan(rule("r1", "host", "port", "allow", A, "any"));
        var section = mixed.sections().get(0);
        var match = PolicyNormalization.normalize(mixed, section, section.rules().get(0), PolicyNormalization.Resolver.shadowing(mixed, new PolicyNormalization.Budget(10_000)));
        assertTrue(match.selectors().get(3).any()); assertEquals(Set.of(), match.selectors().get(3).tokens());
    }
    @Test void earlierAnyOrASupersetOfIdentitiesCovers() {
        // Earlier application any holds every application on the ports it holds.
        covered(pan(rule("r1", "net", "any", "deny"), rule("r2", "host", "port", "allow", A)), "r2", "r1", "CONFLICT");
        covered(pan(rule("r1", "net", "port", "allow"), rule("r2", "host", "port", "allow", A, B)), "r2", "r1", "REDUNDANT");
        // The same identities, or more of them earlier.
        covered(pan(rule("r1", "net", "port", "allow", A), rule("r2", "host", "port", "allow", A)), "r2", "r1", "REDUNDANT");
        covered(pan(rule("r1", "net", "any", "deny", A, B), rule("r2", "host", "port", "allow", B)), "r2", "r1", "CONFLICT");
        covered(pan(rule("r1", "net", "ports", "allow", B, A), rule("r2", "net", "port", "allow", A, B)), "r2", "r1", "REDUNDANT");
        // An earlier service any with the same identities holds them on their default ports too.
        covered(pan(rule("r1", "net", "any", "deny", A, B), rule("r2", "host", APPDEF, "allow", A)), "r2", "r1", "CONFLICT");
    }
    @Test void otherApplicationsLeaveTheCoverToTheOverlap() {
        // Two names are never proven to be different traffic: B may carry only traffic that A matches.
        unknown(pan(rule("r1", "net", "any", "deny", A), rule("r2", "host", "port", "allow", B)), "r2", OVERLAP);
        // More identities later, or a later any: the earlier application may well be all of that traffic.
        unknown(pan(rule("r1", "net", "any", "deny", A), rule("r2", "host", "port", "allow", A, B)), "r2", OVERLAP);
        unknown(pan(rule("r1", "net", "any", "deny", A), rule("r2", "host", "port", "allow")), "r2", OVERLAP);
        // Never a blocker, never "earlier rule has unsupported constraints".
        assertNull(at(pan(rule("r1", "net", "any", "deny", A), rule("r2", "host", "port", "allow", B)), "r2").blockingRuleId());
        // A later proven cover still wins.
        covered(pan(rule("r1", "net", "any", "deny", A), rule("r2", "net", "any", "allow"), rule("r3", "host", "port", "allow", B)), "r3", "r2", "REDUNDANT");
        // With application-default on one side the undecided pair is the default ports' (unchanged reason).
        unknown(pan(rule("r1", "net", APPDEF, "deny", A), rule("r2", "host", "port", "allow", A)), "r2", DEFAULT_PORTS);
        unknown(pan(rule("r1", "net", "port", "deny", A), rule("r2", "host", APPDEF, "allow", A)), "r2", DEFAULT_PORTS);
    }
    @Test void addressesAndPortsStillDecide() {
        // r1's source (one host) does not hold r2's network, or its port not r2's: no overlap of applications puts it back.
        clean(pan(rule("r1", "host", "any", "deny", A), rule("r2", "net", "port", "allow", A)), "r2");
        clean(pan(rule("r1", "net", "dns", "deny", A), rule("r2", "host", "port", "allow", B)), "r2");
        clean(pan(rule("r1", "host", "port", "deny", A), rule("r2", "net", "any", "allow")), "r2");
    }
    @Test void aRuleFormerlyBlockedByAContentApplicationRuleIsDecided() {
        // r1's source (one host) does not hold r2's network: r1 cannot cover r2 whatever its application is.
        var snapshot = pan(rule("r1", "host", "port", "deny", A), rule("r2", "net", "port", "allow"));
        var before = formerly(snapshot, "r2");
        assertEquals(UNSUPPORTED, before.shadowReason()); assertEquals("r1", before.blockingRuleId());
        assertEquals(UNRESOLVED, formerly(snapshot, "r1").shadowReason());
        clean(snapshot, "r2");
        // r1 holds r2 on every other dimension: formerly blocked by r1, now the overlap is the reason (no blocker).
        var undecided = pan(rule("r1", "net", "port", "deny", A), rule("r2", "host", "port", "allow"));
        assertEquals(UNSUPPORTED, formerly(undecided, "r2").shadowReason());
        unknown(undecided, "r2", OVERLAP);
        assertNull(at(undecided, "r2").blockingRuleId());
        // A formerly unresolved rule is now covered by identity.
        var coveredNow = pan(rule("r1", "net", "port", "deny", A, B), rule("r2", "host", "port", "allow", B));
        assertEquals(UNRESOLVED, formerly(coveredNow, "r2").shadowReason());
        covered(coveredNow, "r2", "r1", "CONFLICT");
    }
    @Test void contentApplicationsAreNeverDisjoint() {
        // A partial prior (a user constraint) naming A: other applications never prove disjointness, other addresses do.
        var prior = PolicyHygienePartialPriorTest.with(rule("r1", "host", "port", "deny", A), Map.of("source-user", List.of("OBJ-USER-01")));
        for (String[] apps : List.of(new String[]{B}, new String[]{A}, new String[0])) {
            var overlapping = pan(prior, rule("r2", "net", "port", "allow", apps));
            assertEquals("user or category constraint", at(overlapping, "r1").shadowReason());
            unknown(overlapping, "r2", UNSUPPORTED);
            assertEquals("r1", at(overlapping, "r2").blockingRuleId());
            clean(pan(prior, rule("r2", "other-net", "port", "allow", apps)), "r2");
        }
    }
    @Test void groupsFiltersCustomApplicationsAndOtherObjectsKeepTheirHandling() {
        // On an explicit-service rule an application group still resolves its members as before (unresolved here),
        // a filter is dynamic, a custom application unsupported; a reference minted under another snapshot id is no
        // content-provided application of this one; an unresolved address or service stays unresolved.
        assertEquals(UNRESOLVED, at(pan(rule("r1", "host", "port", "allow", GROUP)), "r1").shadowReason());
        assertEquals(UNRESOLVED, at(pan(rule("r1", "host", "port", "allow", FILTER)), "r1").shadowReason());
        assertEquals(UNRESOLVED, at(pan(rule("r1", "host", "port", "allow", A, FILTER)), "r1").shadowReason());
        assertEquals("dynamic or unsupported object type", at(pan(rule("r1", "host", "port", "allow", "custom")), "r1").shadowReason());
        var objects = appObjects();
        String foreign = ref("p2", "application", "APP-ALPHA-01");
        objects.put(foreign, new PolicyObject(foreign, "APP-ALPHA-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        objects.put("unres-address", new PolicyObject("unres-address", "OBJ-UNRESOLVED", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        assertEquals(UNRESOLVED, at(PolicyHygieneTest.snapshot(List.of(rule("r1", "host", "port", "allow", foreign)), objects), "r1").shadowReason());
        assertEquals(UNRESOLVED, at(PolicyHygieneTest.snapshot(List.of(rule("r1", "unres-address", "port", "allow", A)), objects), "r1").shadowReason());
        assertEquals(UNRESOLVED, at(PolicyHygieneTest.snapshot(List.of(rule("r1", "host", "unres-address", "allow", A)), objects), "r1").shadowReason());
        // A negated application cell stays unsupported.
        var negated = rule("r1", "host", "port", "allow", A);
        negated = new Rule(negated.id(), negated.uuid(), negated.number(), negated.name(), true, negated.source(), negated.destination(), negated.service(),
            new Cell(List.of(A), true), negated.action(), negated.log(), "", negated.extras());
        assertEquals("negated selector", at(pan(negated), "r1").shadowReason());
    }
    @Test void placementUndecidedPriorsArePassedOverOnlyWhenTheyCannotCover() {
        // r1 names zones (undecided against any); it holds r2's addresses and port, the applications decide nothing: unproven.
        unknown(pan(zoned(rule("r1", "net", "any", "deny", A), List.of(Z1), List.of(Z2)), rule("r2", "host", "port", "allow")), "r2", UNPROVEN);
        // Its source does not hold r2's: passed over in every placement and every overlap.
        clean(pan(zoned(rule("r1", "host", "any", "deny", A), List.of(Z1), List.of(Z2)), rule("r2", "net", "port", "allow", B)), "r2");
    }
    @Test void zoneSupersetsCoverAndLeaveTheOverlap() {
        covered(pan(zoned(rule("r1", "net", "any", "allow", A, B), List.of(Z1, Z2), List.of(Z3)),
            zoned(rule("r2", "host", "port", "allow", A), List.of(Z1), List.of(Z3))), "r2", "r1", "REDUNDANT");
        unknown(pan(zoned(rule("r1", "net", "any", "deny", A), List.of(Z1, Z2), List.of(Z3)),
            zoned(rule("r2", "host", "port", "allow", B), List.of(Z1), List.of(Z3))), "r2", OVERLAP);
    }
    @Test void theNewReasonIsAProjectedShadowReason() {
        assertTrue(PolicyHygiene.REASONS.contains(OVERLAP));
        assertEquals(OVERLAP, PartialPriorOracle.OVERLAP);
    }
}
