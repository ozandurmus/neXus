package com.securityexpert.nexus.ui2.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * This slice (Slice A) carries the integration-tests source set and its
 * Testcontainers dependency, but this environment has no container
 * runtime — Testcontainers cannot start a PostgreSQL container here.
 *
 * <p>No test in this class requires a container. The disabled methods
 * below name, precisely, what Slice B (a host with Podman/Docker) must
 * prove once it adds the real Testcontainers-backed bodies, per contract
 * §4 and AC-9:</p>
 *
 * <ol>
 *   <li>Flyway runs against the {@code ui2_migrate} DSN and completes
 *       before any connection is opened with the {@code ui2_app} DSN.</li>
 *   <li>A second {@code flyway.migrate()} invocation against the same
 *       database applies zero migrations and leaves the schema-history
 *       checksum unchanged.</li>
 *   <li>{@code ui2_app} is denied DDL: an attempt to run
 *       {@code CREATE TABLE} as {@code ui2_app} fails with SQLState
 *       {@code 42501} (insufficient_privilege).</li>
 * </ol>
 *
 * <p>This class fails closed rather than silently skipping: each
 * placeholder is a real, named, {@code @Disabled} test method (visible in
 * every test report as disabled, never as passing) so the gap cannot be
 * mistaken for proof.</p>
 */
class Ui2IntegrationHarnessPlaceholderTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        // Proves the module's source set and dependency wiring are real
        // and exercised by `integrationTest`, without touching a
        // container runtime.
        assertNotNull(Ui2IntegrationHarnessPlaceholderTest.class);
    }

    @Test
    @Disabled("Slice B: requires a live container runtime (Podman/Docker), not available in this environment")
    void flywayRunsBeforeAnyApplicationConnection() {
        // See class Javadoc item 1.
    }

    @Test
    @Disabled("Slice B: requires a live container runtime (Podman/Docker), not available in this environment")
    void secondMigrateIsANoOp() {
        // See class Javadoc item 2.
    }

    @Test
    @Disabled("Slice B: requires a live container runtime (Podman/Docker), not available in this environment")
    void uiAppRoleIsDeniedDdlWithSqlState42501() {
        // See class Javadoc item 3.
    }
}
