package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Design section 9, phase P2 gate: the I3 pass ({@link PolicyUnionCover}) over the same fully resolvable 10,000-rule
 * Check Point policy the shadow engine's scale test pins (one placement key, nearly no rule covered, so every subject
 * is compared with every earlier rule of its layer). It must finish within seconds and report how often the budget was
 * hit (decision record Q8 asks for that number); the bound is a blow-up guard sized for CI runners.
 */
class PolicyUnionCoverScaleTest {
    @Test void tenThousandCheckPointRulesFinishWithinSeconds() {
        var snapshot = SyntheticPolicies.large("CP", 10_000, 7);
        var hygiene = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        for (int warm = 0; warm < 2; warm++) PolicyUnionCover.analyze(snapshot, hygiene);
        long started = System.nanoTime();
        var result = PolicyUnionCover.analyze(snapshot, hygiene);
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("SCALE I3 CP 10000 rules: " + millis + " ms, evaluated " + result.evaluated() + ", covered " + result.covered()
            + ", not proven " + result.notProven() + ", not evaluable " + result.notEvaluable());
        assertTrue(result.evaluated() > 9_000, "subjects evaluated: " + result.evaluated());
        assertEquals(0, result.notProven(), "no subject of this policy exhausts the budget");
        assertTrue(millis < 30_000, "took " + millis + " ms");
    }
}
