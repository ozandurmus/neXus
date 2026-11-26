package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.support.CpuTimeGuard;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

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
 *
 * Timing: the analysed section is timed with this thread's CPU time (analyze runs on the calling thread; a loaded host
 * stretches wall time, not CPU time), the minimum of three samples after a warm-up. Besides the absolute bound each test
 * checks a growth ratio that does not depend on machine speed: the same analysis at 2,500 and at 10,000 rules. The
 * candidate comparisons are themselves close to quadratic in the rule count (every rule against the earlier rules of its
 * key), so this is not a linearity check: a 4x input costs about 16x in the model and 10-17x measured. Fifteen runs on
 * a laptop JVM, 2026-11-26, 4x ratio: CP 7.8-13.3, PAN 5.0-9.5, PAN many zone keys 10.3-17.3; the limit of 35 is over
 * twice the largest. It fails when growth turns clearly worse than that (a scratch copy with a per-rule scan of order n^2/40
 * ahead of the comparison gave 49 for CP and 36 in the partial-prior test; PAN many zone keys gave 34, just under its limit). A constant-factor
 * regression (the former scan, 25x slower per pair) is caught by the budget assertions, which count work units and
 * therefore do not depend on the host either.
 */
class PolicyHygieneScaleTest {
    static final int SAMPLES = 3;
    static final double MAX_RATIO_FOR_4X_RULES = 35.0;
    @Test void tenThousandRulePoliciesFinishWithinSecondsAndWithoutBudget() {
        for (String vendor : List.of("CP", "PAN")) {
            var snapshot = SyntheticPolicies.large(vendor, 10_000, 7);
            var small = SyntheticPolicies.large(vendor, 2_500, 7);
            PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
            PolicyHygiene.analyze(small, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
            var last = new AtomicReference<PolicyHygiene.Result>();
            long nanos = CpuTimeGuard.minNanos(SAMPLES, () -> last.set(PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK)));
            long smallNanos = CpuTimeGuard.minNanos(SAMPLES, () -> PolicyHygiene.analyze(small, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK));
            var result = last.get();
            long millis = CpuTimeGuard.millis(nanos);
            System.out.println("SCALE " + vendor + " 10000 rules: " + millis + " ms " + (CpuTimeGuard.cpuTimed() ? "thread CPU" : "wall clock")
                + ", 2500 rules " + CpuTimeGuard.millis(smallNanos) + " ms, ratio " + String.format("%.2f", ratio(nanos, smallNanos)));
            assertFalse(result.budgetReached(), vendor);
            assertEquals(10_000, result.rules().size());
            assertTrue(result.rules().values().stream().noneMatch(a -> "analysis budget".equals(a.shadowReason())), vendor);
            assertTrue(millis < 30_000, vendor + " took " + millis + " ms"); // blow-up guard, thread CPU time
            assertRatio(vendor, nanos, smallNanos);
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
        var snapshot = manyZoneKeys(10_000); var small = manyZoneKeys(2_500);
        PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        PolicyHygiene.analyze(small, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        var last = new AtomicReference<PolicyHygiene.Result>();
        long nanos = CpuTimeGuard.minNanos(SAMPLES, () -> last.set(PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK)));
        long smallNanos = CpuTimeGuard.minNanos(SAMPLES, () -> PolicyHygiene.analyze(small, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK));
        var result = last.get();
        long millis = CpuTimeGuard.millis(nanos);
        Map<String, Long> verdicts = new TreeMap<>();
        for (var a : result.rules().values()) verdicts.merge(a.shadowStatus() + (a.shadowReason() == null ? "" : " / " + a.shadowReason()), 1L, Long::sum);
        System.out.println("SCALE PAN 10000 rules, many zone keys: " + millis + " ms " + (CpuTimeGuard.cpuTimed() ? "thread CPU" : "wall clock")
            + ", 2500 rules " + CpuTimeGuard.millis(smallNanos) + " ms, ratio " + String.format("%.2f", ratio(nanos, smallNanos)) + " " + verdicts);
        assertFalse(result.budgetReached());
        assertTrue(result.rules().values().stream().noneMatch(a -> "analysis budget".equals(a.shadowReason())));
        assertTrue(millis < 90_000, "took " + millis + " ms"); // pathological blow-up guard only, thread CPU time; the budget assertions above are the real check
        assertRatio("PAN many zone keys", nanos, smallNanos);
    }
    private static PolicySnapshot manyZoneKeys(int size) {
        var base = SyntheticPolicies.large("PAN", size, 7); var random = new Random(13);
        List<PolicySnapshot.Rule> rules = new ArrayList<>();
        for (var rule : base.sections().get(0).rules())
            rules.add(SyntheticPolicies.withExtras(rule, rule.action(), Map.of("from", zones(random), "to", zones(random))));
        var section = base.sections().get(0);
        return new PolicySnapshot(base.metadata(), List.of(new PolicySnapshot.Section(section.id(), section.name(), section.source(), null, rules)), base.objects());
    }
    private static double ratio(long big, long small) { return (double) big / Math.max(1L, small); }
    /** Machine-speed independent growth guard: 4x the rules costs about 16x for quadratic comparison work; see the class comment. */
    private static void assertRatio(String what, long bigNanos, long smallNanos) {
        assertTrue(ratio(bigNanos, smallNanos) < MAX_RATIO_FOR_4X_RULES,
            what + ": 4x the rules must not cost " + MAX_RATIO_FOR_4X_RULES + "x: " + CpuTimeGuard.millis(smallNanos) + " ms vs " + CpuTimeGuard.millis(bigNanos) + " ms");
    }
    private static List<String> zones(Random random) {
        if (random.nextInt(10) == 0) return List.of("any");
        var out = new TreeSet<String>();
        for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) out.add(String.format("ZONE-%02d", random.nextInt(30)));
        return List.copyOf(out);
    }
}
