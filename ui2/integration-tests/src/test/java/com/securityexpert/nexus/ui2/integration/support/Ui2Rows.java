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

    /**
     * Every {@code devices} column that is NOT NULL without a default, in
     * the order {@link #DEVICE_REQUIRED_VALUES} supplies them. V21 added
     * {@code role}; V12 added {@code identity_mismatch_state} and
     * {@code peer_follow_outcome} and then dropped their defaults, so a
     * writer must state them -- the values here are exactly the ones
     * {@code JooqDeviceRepository.registerDraft} writes for a new device.
     */
    public static final String DEVICE_REQUIRED_COLUMNS = "role, identity_mismatch_state, peer_follow_outcome";
    public static final String DEVICE_REQUIRED_VALUES = "'gateway', 'NONE', 'NONE'";

    /** A {@code devices} row in {@code enrollmentState}, with its required credential reference. */
    public static String insertDevice(Connection connection, String credentialReferenceId, String enrollmentState)
            throws SQLException {
        String id = opaqueId("dev");
        insertDevice(connection, id, credentialReferenceId, enrollmentState);
        return id;
    }

    /** As {@link #insertDevice(Connection, String, String)}, for a caller that needs a chosen opaque id. */
    public static void insertDevice(Connection connection, String deviceId, String credentialReferenceId,
            String enrollmentState) throws SQLException {
        audited(connection, statement -> statement.execute(insertDeviceSql(deviceId, credentialReferenceId,
                enrollmentState)));
    }

    /**
     * The complete {@code devices} INSERT the seeding helpers use, exposed so a
     * test that must issue the INSERT itself (for example without an audit
     * context, to prove the trigger refuses it) fails only for the reason it
     * names, never on an unrelated NOT NULL column.
     */
    public static String insertDeviceSql(String deviceId, String credentialReferenceId, String enrollmentState) {
        return "INSERT INTO devices(device_id, vendor_hint, registration_source, is_test_target, "
                + "enrollment_state, disabled, credential_reference_id, " + DEVICE_REQUIRED_COLUMNS + ") VALUES ("
                + literal(deviceId) + ", 'harness-vendor', 'manual', true, "
                + literal(enrollmentState) + ", false, " + literal(credentialReferenceId) + ", "
                + DEVICE_REQUIRED_VALUES + ")";
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
                "INSERT INTO jobs(job_id, job_type, target_kind, target_device_id, submitted_by_actor_fingerprint, "
                        + "state, action_class) VALUES ("
                        + literal(id) + ", 'harness.collect', 'device', " + literal(deviceId) + ", " + literal(ACTOR)
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
