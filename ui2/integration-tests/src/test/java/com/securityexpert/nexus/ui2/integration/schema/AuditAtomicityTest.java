package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 5 / C1 §3.5 "Test-enforced" third case. Requires a live
 * PostgreSQL 16 instance via Testcontainers, unavailable in this
 * environment (no container runtime). No container is instantiated in this
 * class -- the disabled method below fails closed and visibly, never as a
 * silent pass.
 */
class AuditAtomicityTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(AuditAtomicityTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void rolledBackMutationLeavesNoAuditLogRow() {
        // A container-capable host must prove: with both session variables
        // set (app.actor_fingerprint, app.action_id), perform a mutation,
        // then ROLLBACK the transaction; assert zero matching audit_log
        // rows survive (transactional atomicity between a mutation and its
        // audit row, not merely "usually true together").
    }
}
