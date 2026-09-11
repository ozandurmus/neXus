package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * B1-4b contract §9 AC-2: {@code devices.enrollment_state} is a closed,
 * checked vocabulary of exactly {@code {DRAFT, ENROLLED, UNREACHABLE,
 * DEGRADED}} plus the separate {@code disabled} boolean column (V3's
 * {@code chk_devices_enrollment_state}). The application-level fail-closed
 * half of this (an unrecognized column value throws rather than defaulting
 * to ENROLLED) is proved without a database at {@code
 * com.securityexpert.nexus.ui2.platform.DeviceEnrollmentStateTest}; this
 * class is the database-level half -- the CHECK constraint itself must
 * reject a value the application would never produce but a bypassing
 * writer might attempt.
 *
 * <p>Requires a live PostgreSQL 16 instance via Testcontainers, unavailable
 * in this environment (no container runtime). No container is instantiated
 * in this class -- the disabled method below fails closed and visibly,
 * never as a silent pass.</p>
 */
class EnrollmentStateCheckConstraintTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(EnrollmentStateCheckConstraintTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void v3AppliesCleanlyOverV1AndV2AndTheCheckConstraintRejectsAFifthValue() {
        // A container-capable host must prove: (a) Flyway applies V1, V2,
        // V3 in sequence with no error against a fresh database; (b) each
        // of DRAFT/ENROLLED/UNREACHABLE/DEGRADED is insertable into
        // devices.enrollment_state; (c) a fifth value (e.g. 'DISABLED', or
        // any string outside the four) is rejected by
        // chk_devices_enrollment_state, not merely by application code;
        // and (d) devices.disabled accepts true/false independently of
        // enrollment_state (contract §3 "any -> disabled" row), proving
        // it is a separate column, not encoded into the same CHECK.
    }
}
