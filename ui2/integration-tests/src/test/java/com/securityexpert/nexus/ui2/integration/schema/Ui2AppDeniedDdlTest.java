package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 3. Requires a live PostgreSQL 16 instance via
 * Testcontainers, unavailable in this environment (no container runtime).
 * No container is instantiated in this class -- the disabled method below
 * fails closed and visibly, never as a silent pass.
 */
class Ui2AppDeniedDdlTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(Ui2AppDeniedDdlTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void ui2AppCreateTableFailsWithSqlState42501() {
        // A container-capable host must prove: open the ui2_app connection
        // OUTSIDE the assertion block (so a connection failure -- wrong
        // credential, unreachable host -- is never mistaken for the DDL
        // denial this test exists to prove), then attempt CREATE TABLE and
        // assert the thrown SQLException.getSQLState() equals "42501"
        // (insufficient_privilege).
    }
}
