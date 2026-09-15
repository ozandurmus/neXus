package com.securityexpert.nexus.ui2.persistence.audit;

import java.time.Instant;
import java.util.Optional;

/**
 * One raw {@code audit_log} row (`UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md`
 * §3.1), exactly as stored -- {@code before_state}/{@code after_state} are
 * carried as raw JSON text, never parsed here. This module holds no JSON
 * library dependency by design (mirrors {@code discovery.OutcomeSummaryJson}'s
 * own rationale), so parsing into the presentation projection is the
 * {@code service} module's job ({@code AuditFieldProjector}).
 */
public record AuditLogRow(
        long auditId,
        String tableName,
        String rowPk,
        String operation,
        String actorFingerprint,
        String actionId,
        Instant occurredAt,
        Optional<String> correlationRunId,
        Optional<String> beforeStateJson,
        Optional<String> afterStateJson) {
}
