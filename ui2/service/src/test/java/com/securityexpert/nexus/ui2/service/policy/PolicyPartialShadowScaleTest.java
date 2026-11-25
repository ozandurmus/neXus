package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Design section 9, phase P2 gate: the I4 pass ({@link PolicyPartialShadow}) over the same fully resolvable 10,000-rule
 * Check Point policy the shadow engine's and the I3 scale tests pin. It must finish within seconds and report how often
 * the budget was hit (decision record Q8); the bound is a blow-up guard sized for CI runners.
 */
class PolicyPartialShadowScaleTest {
    @Test void tenThousandCheckPointRulesFinishWithinSeconds() {
        var snapshot = SyntheticPolicies.large("CP", 10_000, 7);
        var hygiene = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        for (int warm = 0; warm < 2; warm++) PolicyPartialShadow.analyze(snapshot, hygiene, union);
        long started = System.nanoTime();
        var result = PolicyPartialShadow.analyze(snapshot, hygiene, union);
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("SCALE I4 CP 10000 rules: " + millis + " ms, evaluated " + result.evaluated() + ", partial " + result.partial()
            + ", different action " + result.differentAction() + ", not proven " + result.notProven() + ", not evaluable " + result.notEvaluable());
        assertTrue(result.evaluated() > 9_000, "subjects evaluated: " + result.evaluated());
        assertEquals(0, result.notProven(), "no subject of this policy exhausts the budget");
        assertTrue(millis < 60_000, "took " + millis + " ms");
    }
}
