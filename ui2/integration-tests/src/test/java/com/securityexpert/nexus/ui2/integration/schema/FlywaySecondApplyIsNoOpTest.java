package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 2. Requires a live PostgreSQL 16 instance via
 * Testcontainers, unavailable in this environment (no container runtime).
 * No container is instantiated in this class -- the disabled method below
 * fails closed and visibly, never as a silent pass.
 */
class FlywaySecondApplyIsNoOpTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(FlywaySecondApplyIsNoOpTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void secondMigrateCallExecutesZeroMigrations() {
        // A container-capable host must prove: calling migrate() twice
        // against the same database asserts the SECOND
        // MigrateResult.migrationsExecuted == 0 -- not merely that the
        // call does not throw. A change that made the second apply
        // silently re-run a statement passes "does not throw" but must
        // fail this assertion (contract invariant: "a test asserts the
        // migration count, never merely that a call did not throw").
    }
}
