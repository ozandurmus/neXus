package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Design section 9, phase P2 gate: the I7 pass ({@link PolicyReorder}) over the same fully resolvable 10,000-rule Check
 * Point policy the shadow engine's, the I3 and the I4 scale tests pin. It must finish within seconds and report how often
 * the budget was hit (decision record Q8); the bound is a blow-up guard sized for CI runners.
 */
class PolicyReorderScaleTest {
    @Test void tenThousandCheckPointRulesFinishWithinSeconds() {
        var snapshot = SyntheticPolicies.large("CP", 10_000, 7);
        var hygiene = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        var partial = PolicyPartialShadow.analyze(snapshot, hygiene, union);
        for (int warm = 0; warm < 2; warm++) PolicyReorder.analyze(snapshot, partial);
        long started = System.nanoTime();
        var result = PolicyReorder.analyze(snapshot, partial);
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("SCALE I7 CP 10000 rules: " + millis + " ms, I4 rules " + partial.partial() + ", moves evaluated " + result.evaluated()
            + ", safe " + result.safe() + ", safe inspection changes " + result.safeInspectionChanges() + ", unsafe " + result.unsafeDifferentEffect()
            + ", anchor blocked " + result.anchorBlocked() + ", crosses section " + result.crossesSection() + ", not proven " + result.notProven()
            + ", not evaluable " + result.notEvaluable());
        assertEquals(partial.partial(), result.evaluated(), "one move per I4 rule");
        assertEquals(0, result.notProven(), "no move of this policy exhausts the budget");
        assertTrue(millis < 60_000, "took " + millis + " ms");
    }
}
