package com.securityexpert.nexus.ui2.service.audit;

/** An opaque {@code table_name.column_name} pair used as a map key only. */
public record AuditColumnKey(String tableName, String columnName) {

    public AuditColumnKey {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
    }

    @Override
    public String toString() {
        return tableName + "." + columnName;
    }
}
