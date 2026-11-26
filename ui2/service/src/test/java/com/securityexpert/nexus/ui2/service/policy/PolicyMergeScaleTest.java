package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.service.support.CpuTimeGuard;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Design section 9, phase P2 gate: the I5 pass ({@link PolicyMerge}) over the same fully resolvable 10,000-rule Check
 * Point policy the shadow engine's, I3, I4 and I7 scale tests pin. Counts are asserted exactly, and runtime is bounded
 * with thread CPU time ({@link CpuTimeGuard}) plus a 4x growth ratio guard against the 2,500-rule policy rather than a
 * wall-clock constant.
 */
class PolicyMergeScaleTest {
    private static final int SAMPLES = 3;
    /** PolicyMerge is linear in rule count; 4x the rules costs ~4x CPU time, so 16x catches quadratic blow-ups with headroom for timer granularity. */
    private static final double MAX_RATIO_FOR_4X_RULES = 16.0;

    @Test void tenThousandCheckPointRulesFinishLinearlyWithExactCounts() {
        var snapshot = SyntheticPolicies.large("CP", 10_000, 7);
        var small = SyntheticPolicies.large("CP", 2_500, 7);
        for (int warm = 0; warm < 2; warm++) {
            PolicyMerge.analyze(snapshot);
            PolicyMerge.analyze(small);
        }
        var last = new AtomicReference<PolicyMerge.Result>();
        long nanos = CpuTimeGuard.minNanos(SAMPLES, () -> last.set(PolicyMerge.analyze(snapshot)));
        long smallNanos = CpuTimeGuard.minNanos(SAMPLES, () -> PolicyMerge.analyze(small));
        var result = last.get();
        long millis = CpuTimeGuard.millis(nanos);
        double ratio = (double) nanos / Math.max(1_000_000L, smallNanos);
        System.out.println("SCALE I5 CP 10000 rules: " + millis + " ms " + (CpuTimeGuard.cpuTimed() ? "thread CPU" : "wall clock")
            + ", 2500 rules " + CpuTimeGuard.millis(smallNanos) + " ms, ratio " + String.format("%.2f", ratio)
            + ", evaluated " + result.evaluated() + ", candidates " + result.candidates()
            + ", alreadyShadowed " + result.alreadyShadowed() + ", differsInSeveralCells " + result.differsInSeveralCells()
            + ", differentEffect " + result.differentEffect() + ", differentInstallOn " + result.differentInstallOn()
            + ", anchorBlocked " + result.anchorBlocked() + ", notProven " + result.notProven()
            + ", notEvaluable " + result.notEvaluable());
        PolicyMergeTest.assertPartsSumToEvaluated(result);
        assertEquals(9_999, result.evaluated(), "9,999 adjacent pairs in the single 10,000-rule section");
        assertEquals(0, result.candidates());
        assertEquals(0, result.alreadyShadowed());
        assertEquals(6_000, result.differsInSeveralCells());
        assertEquals(3_999, result.differentEffect(), "every 5th rule is Drop, giving 3,999 action transitions");
        assertEquals(0, result.differentInstallOn());
        assertEquals(0, result.anchorBlocked());
        assertEquals(0, result.notProven(), "no pair of this policy exhausts the budget");
        assertEquals(Map.of(), result.notEvaluable());
        assertTrue(millis < 15_000, "took " + millis + " ms");
        assertTrue(ratio < MAX_RATIO_FOR_4X_RULES,
            "4x rules must not cost " + MAX_RATIO_FOR_4X_RULES + "x: " + smallNanos + " ns vs " + nanos + " ns");
    }
}
