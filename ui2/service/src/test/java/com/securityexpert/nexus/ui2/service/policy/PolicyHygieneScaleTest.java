package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.util.*;

/**
 * Micro-benchmark pinned as a test: a fully resolvable 10,000-rule policy (one placement key for Check Point, four zone
 * pairs for Palo Alto, nearly no rule covered, so every rule is compared with every earlier rule of its key) finishes
 * shadowing without any "analysis budget" rule.
 *
 * Measured on a laptop JVM, 2026-11-03 (SyntheticPolicies.large, seed 7):
 * <pre>
 *                      former scan, 250k budget        former scan, unlimited     indexed, per-rule budget
 *   CP  10,000 rules   9,599 rules "analysis budget"   11.9 s                     0.46 s, none
 *   PAN 10,000 rules   9,442 rules "analysis budget"    4.4 s                     0.19 s, none
 *   CP  20,000 rules   -                               -                          1.6 s, last 21 rules hit TOTAL_WORK
 *   PAN 20,000 rules   -                               -                          0.6 s, none
 * </pre>
 * The bound below is generous for shared CI runners; it fails on a return to quadratic set and stream work per pair.
 */
class PolicyHygieneScaleTest {
    @Test void tenThousandRulePoliciesFinishWithinSecondsAndWithoutBudget() {
        for (String vendor : List.of("CP", "PAN")) {
            var snapshot = SyntheticPolicies.large(vendor, 10_000, 7);
            PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
            long started = System.nanoTime();
            var result = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
            long millis = (System.nanoTime() - started) / 1_000_000;
            System.out.println("SCALE " + vendor + " 10000 rules: " + millis + " ms");
            assertFalse(result.budgetReached(), vendor);
            assertEquals(10_000, result.rules().size());
            assertTrue(result.rules().values().stream().noneMatch(a -> "analysis budget".equals(a.shadowReason())), vendor);
            assertTrue(millis < 8_000, vendor + " took " + millis + " ms");
        }
    }
    /**
     * Palo Alto zone supersets with many distinct zone keys: the same 10,000 rules over one to three of 30 zones per side
     * (one rule in ten any), so most rules have hundreds of earlier keys and many superset buckets to search.
     * Measured on a laptop JVM, 2026-11-03 (seed 7/13): exact-key index 0.29 s (9,978 rules unproven, 22 not shadowed);
     * with zone supersets, where a prior whose zones provably do not hold the rule's is no candidate, 0.7 s (1,937
     * unproven, 8,063 not shadowed), no "analysis budget". The bound is the same as above.
     */
    @Test void tenThousandPanRulesOverManyZoneKeysFinishWithoutBudget() {
        var base = SyntheticPolicies.large("PAN", 10_000, 7); var random = new Random(13);
        List<PolicySnapshot.Rule> rules = new ArrayList<>();
        for (var rule : base.sections().get(0).rules())
            rules.add(SyntheticPolicies.withExtras(rule, rule.action(), Map.of("from", zones(random), "to", zones(random))));
        var section = base.sections().get(0);
        var snapshot = new PolicySnapshot(base.metadata(), List.of(new PolicySnapshot.Section(section.id(), section.name(), section.source(), null, rules)), base.objects());
        PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        long started = System.nanoTime();
        var result = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        long millis = (System.nanoTime() - started) / 1_000_000;
        Map<String, Long> verdicts = new TreeMap<>();
        for (var a : result.rules().values()) verdicts.merge(a.shadowStatus() + (a.shadowReason() == null ? "" : " / " + a.shadowReason()), 1L, Long::sum);
        System.out.println("SCALE PAN 10000 rules, many zone keys: " + millis + " ms " + verdicts);
        assertFalse(result.budgetReached());
        assertTrue(result.rules().values().stream().noneMatch(a -> "analysis budget".equals(a.shadowReason())));
        assertTrue(millis < 8_000, "took " + millis + " ms");
    }
    private static List<String> zones(Random random) {
        if (random.nextInt(10) == 0) return List.of("any");
        var out = new TreeSet<String>();
        for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) out.add(String.format("ZONE-%02d", random.nextInt(30)));
        return List.copyOf(out);
    }
}
