package com.securityexpert.nexus.ui2.persistence.audit;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to {@code audit_log} and {@code audit_redaction_policy}
 * (`UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §1: "this screen reads this
 * product's own PostgreSQL database and nothing else"). {@code ui2_app} holds
 * {@code SELECT} only on both tables (B1-2 §5, B1-2a §5.2) -- no method here
 * ever issues {@code INSERT}/{@code UPDATE}/{@code DELETE} against either
 * (§7, AC-8).
 */
public interface AuditLogRepository {

    /**
     * Keyset page ordered {@code audit_id DESC} (§5.3). {@code cursorExclusive},
     * when present, restricts to {@code audit_id < cursor}. {@code limit} is
     * already capped by the caller (§4.1: capped, not refused).
     */
    AuditPage findPage(AuditListFilters filters, Optional<Long> cursorExclusive, int limit);

    /**
     * One row by id, restricted to {@code scopeActorFingerprint} when present
     * (the {@code read_own} scope) -- so an out-of-scope id is indistinguishable
     * from a missing one at this layer, which is what makes the controller's
     * {@code 404} (§4.2) correct rather than a leak.
     */
    Optional<AuditLogRow> findById(long auditId, Optional<String> scopeActorFingerprint);

    /** The full {@code audit_redaction_policy} table, read fresh every call (never cached). */
    List<AuditRedactionPolicyRow> findRedactionPolicy();
}
