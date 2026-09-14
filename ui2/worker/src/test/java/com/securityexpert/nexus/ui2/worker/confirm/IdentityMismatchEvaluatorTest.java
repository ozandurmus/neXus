package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.confirm.IdentityMismatchEvaluator.Decision;

/** AC-4: mismatch warns and continues by default; refuses only with the posture switch on. */
class IdentityMismatchEvaluatorTest {

    private static final PresentedIdentity BASELINE = new PresentedIdentity("fingerprint-a", Optional.of("fw-a"));
    private static final PresentedIdentity SAME = new PresentedIdentity("fingerprint-a", Optional.of("fw-a"));
    private static final PresentedIdentity DIFFERENT = new PresentedIdentity("fingerprint-z", Optional.of("fw-z"));

    @Test
    void firstContactHasNoBaselineToCompareAgainst() {
        assertEquals(Decision.NO_BASELINE, IdentityMismatchEvaluator.evaluate(Optional.empty(), BASELINE, false));
        assertEquals(Decision.NO_BASELINE, IdentityMismatchEvaluator.evaluate(Optional.empty(), BASELINE, true));
    }

    @Test
    void matchingIdentityIsNeitherAWarningNorARefusal() {
        assertEquals(Decision.MATCH, IdentityMismatchEvaluator.evaluate(Optional.of(BASELINE), SAME, false));
        assertEquals(Decision.MATCH, IdentityMismatchEvaluator.evaluate(Optional.of(BASELINE), SAME, true));
    }

    @Test
    void mismatchWarnsAndContinuesByDefault() {
        assertEquals(Decision.WARN_AND_CONTINUE,
                IdentityMismatchEvaluator.evaluate(Optional.of(BASELINE), DIFFERENT, false));
    }

    @Test
    void mismatchRefusesOnlyWhenTheStrictPostureSwitchIsOn() {
        assertEquals(Decision.REFUSE, IdentityMismatchEvaluator.evaluate(Optional.of(BASELINE), DIFFERENT, true));
    }
}
