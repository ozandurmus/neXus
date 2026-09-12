package com.securityexpert.nexus.ui2.integration.schema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.service.audit.AuditRedactionPolicy;

/**
 * Shared plumbing for the {@code AuditFieldProjector} tests in this
 * package: loading {@code audit_redaction_policy} and fetching one
 * {@code audit_log} row's raw {@code before_state}/{@code after_state} as a
 * {@link JsonNode}, exactly as the projector under test receives it.
 *
 * <p>This class never returns a redacted value to a caller outside the test
 * process's own trust boundary and is never used by production code — it
 * exists only so these tests can hand the projector a real snapshot read
 * from a real PostgreSQL 16 server.</p>
 */
final class AuditProjectionTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditProjectionTestSupport() {
    }

    static AuditRedactionPolicy loadPolicy(Connection connection) throws SQLException {
        return AuditRedactionPolicy.load(connection);
    }

    /** The first audit row for {@code tableName}/{@code rowPk}'s {@code after_state}. */
    static JsonNode afterStateOf(Connection connection, String tableName, String rowPk) throws SQLException {
        return snapshotColumn(connection, tableName, rowPk, "after_state", 1);
    }

    static JsonNode beforeStateOf(Connection connection, String tableName, String rowPk, int rowNumber)
            throws SQLException {
        return snapshotColumn(connection, tableName, rowPk, "before_state", rowNumber);
    }

    static JsonNode afterStateOf(Connection connection, String tableName, String rowPk, int rowNumber)
            throws SQLException {
        return snapshotColumn(connection, tableName, rowPk, "after_state", rowNumber);
    }

    static long auditIdOf(Connection connection, String tableName, String rowPk, int rowNumber) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT audit_id FROM audit_log WHERE table_name = " + literal(tableName)
                                + " AND row_pk = " + literal(rowPk) + " ORDER BY audit_id LIMIT " + rowNumber)) {
            long id = -1;
            for (int i = 0; i < rowNumber && rows.next(); i++) {
                id = rows.getLong(1);
            }
            if (id < 0) {
                throw new IllegalStateException("no audit row #" + rowNumber + " for " + tableName + " " + rowPk);
            }
            return id;
        }
    }

    private static JsonNode snapshotColumn(Connection connection, String tableName, String rowPk, String column,
            int rowNumber) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT " + column + " FROM audit_log WHERE table_name = " + literal(tableName)
                                + " AND row_pk = " + literal(rowPk) + " ORDER BY audit_id LIMIT " + rowNumber)) {
            String json = null;
            for (int i = 0; i < rowNumber && rows.next(); i++) {
                json = rows.getString(1);
            }
            if (json == null) {
                return null;
            }
            try {
                return MAPPER.readTree(json);
            } catch (Exception e) {
                throw new IllegalStateException("could not parse " + column + " as JSON", e);
            }
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
