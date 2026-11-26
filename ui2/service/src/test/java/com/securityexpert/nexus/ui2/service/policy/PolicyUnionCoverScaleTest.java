package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * Design section 9, phase P2 gate: the I3 pass ({@link PolicyUnionCover}) over the same fully resolvable 10,000-rule
 * Check Point policy the shadow engine's scale test pins (one placement key, nearly no rule covered, so every subject
 * is compared with every earlier rule of its layer). It must finish within seconds and report how often the budget was
 * hit (decision record Q8 asks for that number); the bound is a blow-up guard sized for CI runners.
 */
class PolicyUnionCoverScaleTest {
    @Test void tenThousandCheckPointRulesFinishWithinSeconds() {
        // PolicyUnionCover.analyze runs synchronously on the calling thread; time thread CPU time (wall clock is only
        // the fallback if the JVM cannot report it) so host CPU contention does not stretch the bound.
        LongSupplier clock = threadCpuClock();

        // Machine-speed-independent ratio guard at n = 1,000 vs 2n = 2,000 (min of 3 CPU-time samples). Because
        // Earlier.candidates scans packed long[] spans across the single placement bucket, 5k -> 10k already scales at
        // 3.55x-4.22x (601-676 ms at 10k vs 149-183 ms at 5k across 10 runs). At n = 1k -> 2k, O(n) normalization
        // amortizes the span scan: across 10 runs min-of-3 measured 8-11 ms at 1k and 24-27 ms at 2k, max ratio 3.12x
        // (2.37x-3.12x). Threshold 6.5x gives >2x margin over 3.12x and catches super-quadratic regressions; per-pair
        // allocation/stream regressions blow up the 30s CPU-time bound below.
        var s1k = SyntheticPolicies.large("CP", 1_000, 7);
        var h1k = PolicyHygiene.analyze(s1k, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        var s2k = SyntheticPolicies.large("CP", 2_000, 7);
        var h2k = PolicyHygiene.analyze(s2k, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        for (int warm = 0; warm < 2; warm++) {
            PolicyUnionCover.analyze(s1k, h1k);
            PolicyUnionCover.analyze(s2k, h2k);
        }
        long min1k = Long.MAX_VALUE;
        long min2k = Long.MAX_VALUE;
        for (int s = 0; s < 3; s++) {
            long t = clock.getAsLong();
            PolicyUnionCover.analyze(s1k, h1k);
            min1k = Math.min(min1k, clock.getAsLong() - t);
            t = clock.getAsLong();
            PolicyUnionCover.analyze(s2k, h2k);
            min2k = Math.min(min2k, clock.getAsLong() - t);
        }
        assertTrue(2 * min2k < 13 * Math.max(1L, min1k),
            "2n rules must not cost 6.5x n rules: " + min1k + " ns vs " + min2k + " ns");

        var snapshot = SyntheticPolicies.large("CP", 10_000, 7);
        var hygiene = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        for (int warm = 0; warm < 2; warm++) PolicyUnionCover.analyze(snapshot, hygiene);

        long minNanos = Long.MAX_VALUE;
        PolicyUnionCover.Result result = null;
        for (int s = 0; s < 3; s++) {
            long started = clock.getAsLong();
            result = PolicyUnionCover.analyze(snapshot, hygiene);
            minNanos = Math.min(minNanos, clock.getAsLong() - started);
        }
        long millis = minNanos / 1_000_000;
        System.out.println("SCALE I3 CP 10000 rules: " + millis + " ms, evaluated " + result.evaluated() + ", covered " + result.covered()
            + ", not proven " + result.notProven() + ", not evaluable " + result.notEvaluable());
        assertTrue(result.evaluated() > 9_000, "subjects evaluated: " + result.evaluated());
        assertEquals(0, result.notProven(), "no subject of this policy exhausts the budget");
        assertTrue(millis < 30_000, "took " + millis + " ms");
    }

    private static LongSupplier threadCpuClock() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean supported = bean.isCurrentThreadCpuTimeSupported();
        if (supported && !bean.isThreadCpuTimeEnabled()) {
            bean.setThreadCpuTimeEnabled(true);
        }
        return supported ? bean::getCurrentThreadCpuTime : System::nanoTime;
    }
}
