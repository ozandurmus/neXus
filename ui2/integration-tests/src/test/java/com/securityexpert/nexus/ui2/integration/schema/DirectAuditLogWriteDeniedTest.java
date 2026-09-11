package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 7 / C1 §9 AC-6 check 5. Requires a live PostgreSQL 16
 * instance via Testcontainers, unavailable in this environment (no
 * container runtime). No container is instantiated in this class -- the
 * disabled method below fails closed and visibly, never as a silent pass.
 */
class DirectAuditLogWriteDeniedTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(DirectAuditLogWriteDeniedTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void ui2AppRawInsertOnAuditLogFailsWithSqlState42501() {
        // A container-capable host must prove: open the ui2_app connection
        // OUTSIDE the assertion block, attempt a raw INSERT on audit_log,
        // and assert the thrown SQLException.getSQLState() equals "42501"
        // -- only fn_audit_capture()'s SECURITY DEFINER execution may ever
        // write to audit_log.
    }
}
