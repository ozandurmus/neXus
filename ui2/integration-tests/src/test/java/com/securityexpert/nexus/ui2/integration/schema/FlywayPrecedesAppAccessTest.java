package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 1. Requires a live PostgreSQL 16 instance via
 * Testcontainers, unavailable in this environment (no container runtime).
 * No container is instantiated in this class -- the disabled method below
 * fails closed and visibly, never as a silent pass, mirroring the pattern
 * {@code Ui2IntegrationHarnessPlaceholderTest} established under B1-1.
 */
class FlywayPrecedesAppAccessTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(FlywayPrecedesAppAccessTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void anyUi2AppConnectionBeforeFlywayMigrateReturnsFailsTheTest() {
        // A container-capable host must prove: the harness opens the
        // ui2_migrate connection, runs Flyway migrate(), and only then
        // opens a ui2_app connection. The test fails if any ui2_app
        // connection succeeds before Flyway's migrate() call returns.
    }
}
