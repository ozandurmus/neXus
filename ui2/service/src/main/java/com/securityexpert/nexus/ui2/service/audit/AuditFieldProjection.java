package com.securityexpert.nexus.ui2.service.audit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One projected snapshot key, contract §3.2's payload shapes:
 *
 * <ul>
 *   <li>{@code PRESENT} — {@code {state, value}}</li>
 *   <li>{@code NULL} — {@code {state}}</li>
 *   <li>{@code REDACTED} — {@code {state, tier, reason}}</li>
 *   <li>{@code ABSENT} — {@code {state}}</li>
 *   <li>{@code UNCLASSIFIED} — {@code {state, table_name, column_name}}</li>
 * </ul>
 *
 * <p>{@link #value()} is populated only for {@code PRESENT}; every other
 * state carries no value field. In particular {@code REDACTED} never carries
 * a digest string here — the caller (the migration's {@code
 * fn_audit_redact}) already stripped the original value before this class
 * ever sees the row, and this class holds no digest field to leak even by
 * accident.</p>
 */
public record AuditFieldProjection(
        AuditFieldState state,
        JsonNode value,
        Integer tier,
        String reason,
        String tableName,
        String columnName) {

    public static AuditFieldProjection present(JsonNode value) {
        return new AuditFieldProjection(AuditFieldState.PRESENT, value, null, null, null, null);
    }

    public static AuditFieldProjection ofNull() {
        return new AuditFieldProjection(AuditFieldState.NULL, null, null, null, null, null);
    }

    public static AuditFieldProjection redacted(int tier, String reason) {
        return new AuditFieldProjection(AuditFieldState.REDACTED, null, tier, reason, null, null);
    }

    public static AuditFieldProjection absent() {
        return new AuditFieldProjection(AuditFieldState.ABSENT, null, null, null, null, null);
    }

    public static AuditFieldProjection unclassified(String tableName, String columnName) {
        return new AuditFieldProjection(AuditFieldState.UNCLASSIFIED, null, null, null, tableName, columnName);
    }
}
