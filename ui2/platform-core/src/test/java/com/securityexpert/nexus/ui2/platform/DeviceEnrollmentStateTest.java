package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * B1-4b contract §3's transition table and closed vocabulary, plus §8
 * test 1's "DRAFT is never collectible" half that lives purely in this
 * enum's own logic (the admission/claim wiring is B1-4's, adjudication
 * F4/F6; this class proves the vocabulary itself cannot be talked out of
 * that rule).
 */
class DeviceEnrollmentStateTest {

    @Test
    void draftNeverPermitsReadCollection() {
        assertFalse(DeviceEnrollmentState.DRAFT.permitsReadCollection());
    }

    @Test
    void enrolledAndDegradedPermitReadCollection() {
        assertTrue(DeviceEnrollmentState.ENROLLED.permitsReadCollection());
        assertTrue(DeviceEnrollmentState.DEGRADED.permitsReadCollection());
    }

    @Test
    void unreachableDoesNotPermitReadCollection() {
        // Contract §3: UNREACHABLE permits display; submission is
        // "judged on outcome, never inferred from the label" -- read
        // collection is not itself granted by this label.
        assertFalse(DeviceEnrollmentState.UNREACHABLE.permitsReadCollection());
    }

    @Test
    void onlyTheContractsSevenTransitionsAreLegal() {
        assertTrue(DeviceEnrollmentState.DRAFT.canTransitionTo(DeviceEnrollmentState.ENROLLED));
        assertTrue(DeviceEnrollmentState.ENROLLED.canTransitionTo(DeviceEnrollmentState.UNREACHABLE));
        assertTrue(DeviceEnrollmentState.ENROLLED.canTransitionTo(DeviceEnrollmentState.DEGRADED));
        assertTrue(DeviceEnrollmentState.UNREACHABLE.canTransitionTo(DeviceEnrollmentState.ENROLLED));
        assertTrue(DeviceEnrollmentState.DEGRADED.canTransitionTo(DeviceEnrollmentState.UNREACHABLE));
        assertTrue(DeviceEnrollmentState.DEGRADED.canTransitionTo(DeviceEnrollmentState.ENROLLED));

        // No other pair is legal -- exhaustively checked, not sampled.
        int legalCount = 0;
        for (DeviceEnrollmentState from : DeviceEnrollmentState.values()) {
            for (DeviceEnrollmentState to : DeviceEnrollmentState.values()) {
                if (from.canTransitionTo(to)) {
                    legalCount++;
                }
            }
        }
        assertEquals(6, legalCount, "exactly six legal same-column transitions per contract §3's table");
        assertFalse(DeviceEnrollmentState.DRAFT.canTransitionTo(DeviceEnrollmentState.DRAFT));
        assertFalse(DeviceEnrollmentState.DRAFT.canTransitionTo(DeviceEnrollmentState.UNREACHABLE));
        assertFalse(DeviceEnrollmentState.DRAFT.canTransitionTo(DeviceEnrollmentState.DEGRADED));
        assertFalse(DeviceEnrollmentState.UNREACHABLE.canTransitionTo(DeviceEnrollmentState.DEGRADED));
        assertFalse(DeviceEnrollmentState.ENROLLED.canTransitionTo(DeviceEnrollmentState.DRAFT));
    }

    @Test
    void unrecognizedColumnValueFailsClosedNeverDefaultingToEnrolled() {
        assertThrows(IllegalStateException.class, () -> DeviceEnrollmentState.fromColumnValue("SOMETHING_ELSE"));
        assertThrows(IllegalStateException.class, () -> DeviceEnrollmentState.fromColumnValue(null));
    }

    @Test
    void fromColumnValueRoundTripsEveryKnownValue() {
        for (DeviceEnrollmentState state : DeviceEnrollmentState.values()) {
            assertEquals(state, DeviceEnrollmentState.fromColumnValue(state.name()));
        }
    }
}
