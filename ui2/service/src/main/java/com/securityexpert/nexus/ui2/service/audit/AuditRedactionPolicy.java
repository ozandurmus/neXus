package com.securityexpert.nexus.ui2.service.audit;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The in-memory form of {@code audit_redaction_policy} (V5), read fresh from
 * the database. {@code UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md} §3.2: a
 * {@code REDACTED} field's {@code tier} and {@code reason} are read from
 * this table, never invented in code.
 *
 * <p>{@code ui2_app} holds {@code SELECT} only on this table (B1-2a §5.2),
 * so this class only ever reads it. It never mutates {@code CLASS 2} data
 * and holds no digest, no secret, and no row value — only the policy
 * itself, which is repository-committed and {@code CLASS 0}.</p>
 */
public final class AuditRedactionPolicy {

    private final Map<AuditColumnKey, AuditRedactionPolicyEntry> entries;

    private AuditRedactionPolicy(Map<AuditColumnKey, AuditRedactionPolicyEntry> entries) {
        this.entries = entries;
    }

    /** Loads the full policy from a live connection. Never caches across calls. */
    public static AuditRedactionPolicy load(Connection connection) throws SQLException {
        Map<AuditColumnKey, AuditRedactionPolicyEntry> loaded = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_name, column_name, tier, reason FROM audit_redaction_policy")) {
            while (rows.next()) {
                String table = rows.getString("table_name");
                String column = rows.getString("column_name");
                int tier = rows.getInt("tier");
                String reason = rows.getString("reason");
                loaded.put(new AuditColumnKey(table, column),
                        new AuditRedactionPolicyEntry(table, column, tier, reason));
            }
        }
        return new AuditRedactionPolicy(Map.copyOf(loaded));
    }

    /** Test/unit-level construction from already-loaded entries. */
    public static AuditRedactionPolicy of(Iterable<AuditRedactionPolicyEntry> entries) {
        Map<AuditColumnKey, AuditRedactionPolicyEntry> map = new LinkedHashMap<>();
        for (AuditRedactionPolicyEntry entry : entries) {
            map.put(new AuditColumnKey(entry.tableName(), entry.columnName()), entry);
        }
        return new AuditRedactionPolicy(Map.copyOf(map));
    }

    public boolean isRedacted(String tableName, String columnName) {
        return entries.containsKey(new AuditColumnKey(tableName, columnName));
    }

    public Optional<AuditRedactionPolicyEntry> entryFor(String tableName, String columnName) {
        return Optional.ofNullable(entries.get(new AuditColumnKey(tableName, columnName)));
    }

    /** Every column of one table declared redacted by this policy. */
    public Set<String> columnsFor(String tableName) {
        Set<String> columns = new TreeSet<>();
        for (AuditColumnKey key : entries.keySet()) {
            if (key.tableName().equals(tableName)) {
                columns.add(key.columnName());
            }
        }
        return columns;
    }
}
