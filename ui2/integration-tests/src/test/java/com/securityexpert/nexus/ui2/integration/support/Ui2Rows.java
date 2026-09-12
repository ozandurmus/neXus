package com.securityexpert.nexus.ui2.integration.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Minimal row seeding for the integration tests, expressed in raw SQL
 * against the real schema so a test proves the constraint it names rather
 * than a repository's opinion of it.
 *
 * <p>Every seeding method opens its own transaction, sets the audit context
 * ({@code app.actor_fingerprint} / {@code app.action_id}) before the
 * mutation and commits — C1 §3.5's fail-closed trigger refuses any other
 * shape. Identifiers are opaque, minted here, and carry no meaning
 * (AGENTS.md "Identity law"); no value written here resembles a real
 * device identity, address or credential.</p>
 */
public final class Ui2Rows {

    public static final String ACTOR = "harness-actor-fingerprint";
    public static final String ACTION = "harness.seed";

    private Ui2Rows() {
    }

    public static String opaqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** {@code SET LOCAL} both audit-context variables in the current transaction. */
    public static void setAuditContext(Connection connection, String actor, String action) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL app.actor_fingerprint = " + literal(actor));
            statement.execute("SET LOCAL app.action_id = " + literal(action));
        }
    }

    public static String insertCredentialReference(Connection connection) throws SQLException {
        String id = opaqueId("cred");
        audited(connection, statement -> statement.execute(
                "INSERT INTO credential_references(credential_reference_id, purpose, backend_pointer) VALUES ("
                        + literal(id) + ", 'harness', 'vault://harness/opaque')"));
        return id;
    }

    /** A {@code devices} row in {@code enrollmentState}, with its required credential reference. */
    public static String insertDevice(Connection connection, String credentialReferenceId, String enrollmentState)
            throws SQLException {
        String id = opaqueId("dev");
        audited(connection, statement -> statement.execute(
                "INSERT INTO devices(device_id, vendor_hint, registration_source, is_test_target, "
                        + "enrollment_state, disabled, credential_reference_id) VALUES ("
                        + literal(id) + ", 'harness-vendor', 'manual', true, "
                        + literal(enrollmentState) + ", false, " + literal(credentialReferenceId) + ")"));
        return id;
    }

    public static String insertEndpoint(Connection connection, String deviceId) throws SQLException {
        String id = opaqueId("ep");
        audited(connection, statement -> statement.execute(
                "INSERT INTO endpoints(endpoint_id, device_id, transport_kind, address_ref) VALUES ("
                        + literal(id) + ", " + literal(deviceId) + ", 'ssh_exec', 'opaque-address-ref')"));
        return id;
    }

    public static String insertJob(Connection connection, String deviceId) throws SQLException {
        String id = opaqueId("job");
        audited(connection, statement -> statement.execute(
                "INSERT INTO jobs(job_id, job_type, target_device_id, submitted_by_actor_fingerprint, "
                        + "state, action_class) VALUES ("
                        + literal(id) + ", 'harness.collect', " + literal(deviceId) + ", " + literal(ACTOR)
                        + ", 'REQUESTED', 'read')"));
        return id;
    }

    public static String insertProvenanceRecord(Connection connection) throws SQLException {
        String id = opaqueId("prov");
        audited(connection, statement -> statement.execute(
                "INSERT INTO provenance_records(provenance_id, run_id, step_id, parser_version, "
                        + "capability_version, source_location, fingerprint_sha256, collected_at) VALUES ("
                        + literal(id) + ", 'run-1', 'step-1', 'p1', 'c1', 'harness', "
                        + "'0000000000000000000000000000000000000000000000000000000000000000', now())"));
        return id;
    }

    public static long countAuditRows(Connection connection, String tableName, String rowPk) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM audit_log WHERE table_name = " + literal(tableName)
                                + " AND row_pk = " + literal(rowPk))) {
            rows.next();
            return rows.getLong(1);
        }
    }

    public static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void audited(Connection connection, SqlWork work) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            setAuditContext(connection, ACTOR, ACTION);
            try (Statement statement = connection.createStatement()) {
                work.run(statement);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Statement statement) throws SQLException;
    }
}
