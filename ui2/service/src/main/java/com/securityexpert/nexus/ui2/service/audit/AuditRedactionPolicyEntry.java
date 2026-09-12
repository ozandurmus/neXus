package com.securityexpert.nexus.ui2.service.audit;

/**
 * One row of {@code audit_redaction_policy} (V5), read at request time —
 * never hardcoded. {@code tier} and {@code reason} are exactly what a
 * {@code REDACTED} field projection carries (contract §3.2).
 */
public record AuditRedactionPolicyEntry(String tableName, String columnName, int tier, String reason) {

    public AuditRedactionPolicyEntry {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
