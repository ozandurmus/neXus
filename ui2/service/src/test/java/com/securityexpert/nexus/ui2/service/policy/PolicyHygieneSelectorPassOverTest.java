package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygienePartialPriorTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneZoneSupersetTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Selector pass-over (PolicyHygiene.Earlier#undecidedMayCover): an earlier rule whose placement relation to a later rule
 * cannot be decided (a lone any against zone names, unequal install-on) used to make the later rule "zone or install-on
 * coverage is unproven" whatever its selectors. Under single-rule cover such a prior matters only if its four resolved
 * selectors contain the later rule's; one that fails on a dimension can never cover it, in any placement, and is
 * passed over. Every changed verdict is checked against the former analysis through the brute-force oracle, including
 * the pair's smallest world (any is exactly the zones the two rules name). Synthetic fixtures only.
 */
class PolicyHygieneSelectorPassOverTest {
    static final List<String> ANY = List.of("any");
    static PolicyHygiene.Assessment legacy(PolicySnapshot snapshot, String id) {
        return LegacyPolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules().get(id);
    }
    static Rule zones(Rule rule, List<String> from, List<String> to) { return with(rule, Map.of("from", from, "to", to)); }

    @Test void namedZonePriorWhoseSourceCannotHoldTheRuleIsPassedOver() {
        // r1 names zones (undecided against any) but its source is one host of r2's network.
        var snapshot = pan(zoned("r1", "host", "deny", List.of(A), List.of(B)), zoned("r2", "net", "allow", ANY, ANY));
        assertEquals(UNPROVEN, legacy(snapshot, "r2").shadowReason());
        assertClean(snapshot, "r2");
        assertOracle(snapshot);
    }
    @Test void namedZonePriorWhoseSelectorsHoldTheRuleStaysUnproven() {
        // r1 holds every selector of r2: it covers r2 exactly when A and B are all the zones, which is not collected.
        assertReason(pan(zoned("r1", "net", "deny", List.of(A), List.of(B)), zoned("r2", "host", "allow", ANY, ANY)), "r2", UNPROVEN);
        assertReason(pan(zoned("r1", "host", "deny", List.of(A), List.of(B)), zoned("r2", "host", "allow", ANY, ANY)), "r2", UNPROVEN);
        // A mixed zone set is undecided against names too; the pass-over reads the selectors the same way.
        assertReason(pan(zoned("r1", "net", "deny", List.of("any", A), List.of(B)), zoned("r2", "host", "allow", List.of(C), List.of(B))), "r2", UNPROVEN);
        assertCleanAndOracle(pan(zoned("r1", "host", "deny", List.of("any", A), List.of(B)), zoned("r2", "net", "allow", List.of(C), List.of(B))), "r2");
    }
    @Test void everyDimensionCanFailContainment() {
        // Destination.
        assertCleanAndOracle(pan(zones(pan("r1", "any", "host", "port", "deny"), List.of(A), List.of(B)), zones(pan("r2", "any", "net", "port", "allow"), ANY, ANY)), "r2");
        // Service: UDP 53 holds no TCP 443; TCP 400-500 holds 443, so that prior stays undecided.
        assertCleanAndOracle(pan(zones(pan("r1", "any", "any", "dns", "deny"), List.of(A), List.of(B)), zones(pan("r2", "any", "any", "port", "allow"), ANY, ANY)), "r2");
        assertReason(pan(zones(pan("r1", "any", "any", "ports", "deny"), List.of(A), List.of(B)), zones(pan("r2", "any", "any", "port", "allow"), ANY, ANY)), "r2", UNPROVEN);
        // A range that only partly overlaps is no containment either.
        assertCleanAndOracle(pan(zones(pan("r1", "any", "any", "port", "deny"), List.of(A), List.of(B)), zones(pan("r2", "any", "any", "ports", "allow"), ANY, ANY)), "r2");
    }
    @Test void oneContainingUndecidedPriorAmongManyKeepsTheRuleUnproven() {
        var r1 = zoned("r1", "host", "deny", List.of(A), List.of(B));
        var r2 = zoned("r2", "host", "deny", List.of(C), List.of(D));
        var r3 = zoned("r3", "other-net", "deny", List.of(A, B), List.of(C));
        var r4 = zoned("r4", "other-host", "allow", ANY, ANY);
        assertReason(pan(r1, r2, r3, r4), "r4", UNPROVEN);
        assertCleanAndOracle(pan(r1, r2, r4), "r4");
        // Two priors in one placement bucket: the second one holds the rule.
        var r5 = zoned("r5", "other-net", "deny", List.of(A), List.of(B));
        assertReason(pan(r1, r5, r4), "r4", UNPROVEN);
    }
    @Test void passedOverRuleStillMeetsPartialPriorsAndEarlierUnknowns() {
        // After the pass-over the rule is decided as if the undecided prior were not there: an overlapping partial prior
        // (a user constraint) still blocks it, and names itself as the blocker.
        var partial = with(zoned("r2", "net", "allow", ANY, ANY), Map.of("source-user", List.of("OBJ-USER-01")));
        var snapshot = pan(zoned("r1", "host", "deny", List.of(A), List.of(B)), partial, zoned("r3", "net", "allow", ANY, ANY));
        assertEquals(UNPROVEN, legacy(snapshot, "r3").shadowReason());
        var result = at(snapshot, "r3");
        assertEquals("UNKNOWN", result.shadowStatus()); assertEquals(PolicyHygienePartialPriorTest.UNSUPPORTED, result.shadowReason());
        assertEquals("r2", result.blockingRuleId());
        assertOracle(snapshot);
        // An earlier rule nothing of which resolves (a target negation) keeps the scope's earlier-unsupported reason.
        var unknown = with(zoned("r2", "net", "allow", ANY, ANY), Map.of("target/negate", List.of("yes")));
        snapshot = pan(zoned("r1", "host", "deny", List.of(A), List.of(B)), unknown, zoned("r3", "net", "allow", ANY, ANY));
        result = at(snapshot, "r3");
        assertEquals(PolicyHygienePartialPriorTest.UNSUPPORTED, result.shadowReason()); assertEquals("r2", result.blockingRuleId());
        assertOracle(snapshot);
    }
    @Test void checkPointUnequalInstallOnIsPassedOverTheSameWay() {
        // Install-on "any" against one gateway is undecided; a prior whose source cannot hold the rule's is passed over.
        var snapshot = cp(cp("r1", 1, "Accept", "host", "any", "port"), with(cp("r2", 2, "Accept", "net", "any", "port"), Map.of("install-on", List.of("gw-a"))));
        assertEquals(UNPROVEN, legacy(snapshot, "r2").shadowReason());
        assertCleanAndOracle(snapshot, "r2");
        // The reverse holds the rule: unproven, as before.
        assertReason(cp(cp("r1", 1, "Accept", "net", "any", "port"), with(cp("r2", 2, "Accept", "host", "any", "port"), Map.of("install-on", List.of("gw-a")))), "r2", UNPROVEN);
    }
    @Test void coveringAndDecidedPriorsAreUntouched() {
        // A compatible prior that covers still wins; a superset prior still covers; the pass-over is never consulted.
        assertCovered(pan(zoned("r1", "host", "deny", List.of(A), List.of(B)), zoned("r2", "net", "allow", ANY, ANY), zoned("r3", "host", "allow", ANY, ANY)), "r3", "r2", "REDUNDANT");
        assertCovered(pan(zoned("r1", "net", "deny", List.of(A, B), List.of(C)), zoned("r2", "host", "allow", List.of(A), List.of(C))), "r2", "r1", "CONFLICT");
    }
    @Test void runningOutOfPassOverWorkLeavesTheFormerVerdict() {
        // Many undecided priors (one placement key each), none holding the rule: the pass-over reads every one of them,
        // the former "unproven" scan stops at the first. Some per-rule windows fit the scan but not the pass-over.
        List<Rule> rules = new ArrayList<>();
        var zoneNames = List.of(A, B, C, D, E);
        for (int mask = 1; mask < 32; mask++) {
            List<String> from = new ArrayList<>();
            for (int z = 0; z < 5; z++) if ((mask & (1 << z)) != 0) from.add(zoneNames.get(z));
            rules.add(zoned("p" + mask, "host", "deny", from, List.of(A)));
        }
        rules.add(zoned("r9", "net", "allow", ANY, ANY));
        var snapshot = pan(rules.toArray(Rule[]::new));
        assertEquals(UNPROVEN, legacy(snapshot, "r9").shadowReason());
        assertClean(snapshot, "r9");
        Set<String> seen = new TreeSet<>();
        for (int limit = 1; limit < 400; limit++) {
            var got = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK, limit, snapshot.objects()).rules().get("r9");
            assertNull(got.coveringRuleId());
            String verdict = got.shadowStatus() + " / " + got.shadowReason();
            assertTrue(Set.of("UNKNOWN / analysis budget", "UNKNOWN / " + UNPROVEN, "NOT_SHADOWED / null").contains(verdict), limit + ": " + verdict);
            seen.add(verdict);
        }
        assertEquals(Set.of("UNKNOWN / analysis budget", "UNKNOWN / " + UNPROVEN, "NOT_SHADOWED / null"), seen);
    }
    static void assertCleanAndOracle(PolicySnapshot snapshot, String id) {
        assertClean(snapshot, id);
        assertOracle(snapshot);
    }
}
