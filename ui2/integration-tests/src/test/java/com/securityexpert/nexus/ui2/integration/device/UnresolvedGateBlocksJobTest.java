package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * B1-4b contract §8 test 5 ({@code unresolved_gate_blocks_job}). This test
 * is {@code C4}'s (capability registry / gate resolution) and {@code
 * B1-4}'s (job admission, device execution) subject matter, not this
 * movement's own -- neither {@code capability_registry} nor {@code
 * DeviceTransport}/job admission exists yet (both are {@code B1-4}'s V4,
 * sequenced after this movement per the adjudication's implementation
 * order, {@code UI2_0_B1_ADJUDICATION_2026_09_12.md} §3). This movement's
 * own contribution -- {@code devices.enrollment_state} being readable by
 * both admission and claim-time checks -- is proved without a database at
 * {@code com.securityexpert.nexus.ui2.jobs.device.PersistenceDeviceEnrollmentReadPortTest}.
 *
 * <p>Left as a disabled, precisely-named placeholder rather than silently
 * omitted, so the gap this movement does NOT close is visible rather than
 * assumed away. Requires a live PostgreSQL 16 instance via Testcontainers
 * (unavailable here) AND B1-4's gate-registry/job-admission code (not yet
 * implemented) -- two blockers, not one.</p>
 */
class UnresolvedGateBlocksJobTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(UnresolvedGateBlocksJobTest.class);
    }

    @Test
    @Disabled("requires B1-4's gate-registry/job-admission code (not yet implemented) "
            + "AND a live container runtime (Podman/Docker), neither available in this movement/environment")
    void aStepWithUnresolvedGateNeverReachesDeviceExecutionAgainstAnEnrolledDevice() {
        // A container-capable host, once B1-4 lands, must prove: an
        // ENROLLED device with a job step whose gate resolution is UNKNOWN
        // (C4 §6) is refused before DeviceTransport.connect is ever
        // called -- exactly as an unresolved-gate refusal, independent of
        // and never substituting for the DRAFT-enrollment refusal this
        // movement's own devices.enrollment_state column enables (test 1).
    }
}
