package com.securityexpert.nexus.ui2.service.audit;

import java.time.Instant;
import java.util.Optional;

/** Contract §3.1's list/detail-shared metadata -- never {@code before_state}/{@code after_state}. */
public record AuditEntrySummary(
        long auditId,
        String tableName,
        String rowPk,
        String operation,
        String actorFingerprint,
        String actionId,
        Instant occurredAt,
        Optional<String> correlationRunId) {
}
