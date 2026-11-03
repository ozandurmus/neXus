package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;

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
}
