package com.securityexpert.nexus.ui2.persistence.audit;

/**
 * One raw {@code audit_redaction_policy} (V5) row. Kept as a persistence-only
 * DTO -- not {@code service.audit.AuditRedactionPolicyEntry} -- because
 * {@code persistence} may not depend on {@code service} (module layering);
 * the service layer converts this into its own domain type.
 */
public record AuditRedactionPolicyRow(String tableName, String columnName, int tier, String reason) {
}
