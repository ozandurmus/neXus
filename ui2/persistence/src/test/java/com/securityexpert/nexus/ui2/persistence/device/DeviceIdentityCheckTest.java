package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceIdentityCheck.State;

/** The three header states of "Identity verified" (PO decision 2026-10-18). Synthetic ids only. */
class DeviceIdentityCheckTest {

    private static DeviceIdentityCheck check(String verdict, boolean recordedConflict) {
        return new DeviceIdentityCheck("device-fixture", verdict == null ? Optional.empty() : Optional.of("SSH_HOST_KEY"),
                Optional.ofNullable(verdict), verdict == null ? Optional.empty() : Optional.of(Instant.parse("2026-10-18T06:00:00Z")),
                recordedConflict);
    }

    @Test
    void aMatchedLastComparisonIsVerified() {
        assertEquals(State.VERIFIED, check("MATCH", false).state());
    }

    @Test
    void aFailedLastComparisonIsAMismatch() {
        assertEquals(State.MISMATCH, check("MISMATCH", false).state());
        assertEquals("SSH_HOST_KEY", check("MISMATCH", false).basis());
    }

    @Test
    void anOpenFirstContactIdentityConflictIsAMismatchEvenWhenTheKeyMatched() {
        DeviceIdentityCheck c = check("MATCH", true);
        assertEquals(State.MISMATCH, c.state());
        assertEquals("RECORDED_IDENTITY", c.basis());
    }

    @Test
    void neverComparedAndFirstUseAreNotVerified() {
        assertEquals(State.NOT_VERIFIED, DeviceIdentityCheck.none("device-fixture").state());
        assertEquals("NONE", DeviceIdentityCheck.none("device-fixture").basis());
        assertEquals(State.NOT_VERIFIED, check("FIRST_USE", false).state());
    }
}
