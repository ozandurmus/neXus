package com.securityexpert.nexus.ui2.persistence.audit;

import java.time.Instant;
import java.util.Optional;

/**
 * The bounded, closed filter set of contract §4.1. {@code scopeActorFingerprint}
 * is the server-resolved {@code read_own} restriction (empty for
 * {@code read_all}) -- never the caller-supplied {@code actorFingerprint}
 * parameter, which is a separate field and is refused outright for
 * {@code read_own} before a filter object is even built (§5.2).
 */
public record AuditListFilters(
        Optional<String> scopeActorFingerprint,
        Optional<String> tableName,
        Optional<String> rowPk,
        Optional<String> operation,
        Optional<String> actorFingerprint,
        Optional<String> actionId,
        Optional<String> correlationRunId,
        Optional<Instant> occurredFrom,
        Optional<Instant> occurredTo) {

    public static AuditListFilters none(Optional<String> scopeActorFingerprint) {
        return new AuditListFilters(scopeActorFingerprint, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
