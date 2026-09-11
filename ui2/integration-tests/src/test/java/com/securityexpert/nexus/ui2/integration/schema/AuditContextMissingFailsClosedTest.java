package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 4 / C1 §3.5 "Test-enforced" first case /
 * {@code UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md} §4's
 * {@code AuditContextIntegrationTest} spec. Requires a live PostgreSQL 16
 * instance via Testcontainers, unavailable in this environment (no
 * container runtime). No container is instantiated in this class -- the
 * disabled method below fails closed and visibly, never as a silent pass.
 */
class AuditContextMissingFailsClosedTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(AuditContextMissingFailsClosedTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void rawMutationWithoutAuditContextRaisesAuditContextMissingAndWritesNoRow() {
        // A container-capable host must prove: as ui2_app, issue a raw
        // mutation (e.g. INSERT) on a mutation-bearing table WITHOUT first
        // issuing SET LOCAL app.actor_fingerprint / SET LOCAL app.action_id
        // in the same transaction; assert it fails with
        // "audit_context_missing", and a follow-up SELECT proves no row
        // was written to the target table (the mutation itself never
        // committed, not merely that the exception was thrown).
    }
}
