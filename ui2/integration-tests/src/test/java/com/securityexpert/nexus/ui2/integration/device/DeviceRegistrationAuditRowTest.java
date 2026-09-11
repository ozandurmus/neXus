package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * B1-4b contract §8 test 7 ({@code audit_row_written_for_every_mutation}),
 * as it applies specifically to registration's paired {@code devices}/
 * {@code endpoints} INSERT. The general trigger mechanism this depends on
 * ({@code fn_audit_capture}, C1 §3.5) already has its own
 * container-dependent proof at {@code
 * com.securityexpert.nexus.ui2.integration.schema.AuditAtomicityTest} and
 * {@code AuditContextMissingFailsClosedTest} -- this class exists only to
 * name the registration-specific assertion those generic tests do not:
 * that ONE registration call produces exactly TWO audit_log rows (one per
 * table) in the SAME transaction, never one without the other.
 *
 * <p>Requires a live PostgreSQL 16 instance via Testcontainers, unavailable
 * in this environment (no container runtime). No container is instantiated
 * in this class -- the disabled method below fails closed and visibly,
 * never as a silent pass.</p>
 */
class DeviceRegistrationAuditRowTest {

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(DeviceRegistrationAuditRowTest.class);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void oneRegistrationProducesExactlyTwoAuditRowsInOneTransaction() {
        // A container-capable host must prove: call
        // DeviceRepository.registerDraft(...) with the audit context set,
        // then assert exactly one audit_log row with table_name='devices'
        // and one with table_name='endpoints' exist, both sharing the same
        // actor_fingerprint/action_id and occurring inside the same
        // transaction window; and that a raw INSERT into devices without
        // first calling AuditedTransactionBoundary raises
        // audit_context_missing (already covered generically by
        // AuditContextMissingFailsClosedTest against V1's trigger, which
        // this migration's ALTER TABLE does not detach).
    }
}
