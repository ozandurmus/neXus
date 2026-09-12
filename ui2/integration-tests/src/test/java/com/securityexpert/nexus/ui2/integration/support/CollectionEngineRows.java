package com.securityexpert.nexus.ui2.integration.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Row-seeding helpers specific to the collection-engine lease/reconciler
 * tests (contract §8 tests 2-6, {@code CollectionEngineDatabasePlaceholderTest}).
 * {@link Ui2Rows#insertJob} deliberately leaves {@code capability_id} NULL
 * for its own callers; the claim statement's own eligibility filter
 * ({@code capability_id = ANY(string_to_array(...))}) needs one set, so a
 * dedicated helper lives here rather than changing {@code Ui2Rows}, which
 * other tests already depend on unchanged.
 *
 * <p>Follows {@link Ui2Rows}'s own pattern: raw SQL against the real
 * schema, one audited transaction per insert, opaque identifiers only.</p>
 */
public final class CollectionEngineRows {

    private CollectionEngineRows() {
    }

    public static String insertJobWithCapability(Connection connection, String deviceId, String capabilityId)
            throws SQLException {
        String id = Ui2Rows.opaqueId("job");
        audited(connection, statement -> statement.execute(
                "INSERT INTO jobs(job_id, job_type, capability_id, target_device_id, "
                        + "submitted_by_actor_fingerprint, state, action_class) VALUES ("
                        + literal(id) + ", 'harness.collect', " + literal(capabilityId) + ", "
                        + literal(deviceId) + ", " + literal(Ui2Rows.ACTOR) + ", 'REQUESTED', 'read')"));
        return id;
    }

    /**
     * A {@code job_step_attempt} row shaped exactly as {@code
     * JooqJobLeaseDao}'s three {@code findExpired*} predicates distinguish
     * them: no row at all (call this zero times), a boundary-{@code false}
     * row (pre-contact), or a boundary-{@code true} row with {@code
     * outcome} left {@code NULL} (the unconfirmed-write case the crash
     * matrix depends on).
     *
     * <p><b>Testability finding, reported, not fixed here (out of file
     * scope: {@code V5__audit_redaction_policy.sql}).</b> {@code
     * job_step_attempt.captured_variables} is a {@code V5}-declared
     * tier-2 redacted column. {@code fn_audit_redact} only rewrites a
     * declared column when its value is present and non-{@code NULL}
     * (correctly -- {@code NULL} already carries no secret to hide); but
     * {@code fn_audit_capture}'s own fail-closed check then compares the
     * redacted snapshot to the raw one and raises {@code
     * audit_redaction_not_applied} whenever they are equal -- which they
     * always are when the only declared-redacted column is {@code NULL}.
     * The real production write path, {@code
     * JooqJobStepAttemptDao#insertPreContact}, never sets {@code
     * captured_variables} (C2 §5.1: it is unknown until a later
     * capture), so <b>every real pre-contact {@code job_step_attempt}
     * insert against a real, migrated V5 database currently raises this
     * exception and rolls back</b> -- reproduced directly against real
     * PostgreSQL while writing this class (not merely inferred from
     * reading the trigger). This helper works around it, for seeding
     * purposes only, by giving {@code captured_variables} a non-NULL
     * placeholder value so the redaction actually has something to
     * rewrite; production code is not touched or reimplemented.</p>
     */
    public static String insertJobStepAttempt(Connection connection, String jobId, long leaseEpoch,
            boolean mutationBoundaryCrossed, boolean outcomeRecorded) throws SQLException {
        String id = Ui2Rows.opaqueId("attempt");
        String outcomeClause = outcomeRecorded ? "'MATCHED'" : "NULL";
        audited(connection, statement -> statement.execute(
                "INSERT INTO job_step_attempt(attempt_id, job_id, lease_epoch, step_index, step_kind, "
                        + "attempt_number, action_class, mutation_boundary_crossed, sent_at, outcome, "
                        + "captured_variables) VALUES ("
                        + literal(id) + ", " + literal(jobId) + ", " + leaseEpoch + ", 0, 'exec', 1, 'read', "
                        + mutationBoundaryCrossed + ", now(), " + outcomeClause + ", '{}'::jsonb)"));
        return id;
    }

    public static String jobState(Connection connection, String jobId) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT state FROM jobs WHERE job_id = " + literal(jobId))) {
            rows.next();
            return rows.getString(1);
        }
    }

    public static long jobLeaseEpoch(Connection connection, String jobId) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT lease_epoch FROM jobs WHERE job_id = " + literal(jobId))) {
            rows.next();
            return rows.getLong(1);
        }
    }

    public static long attemptCount(Connection connection, String jobId) throws SQLException {
        return Ui2Rows.count(connection, "SELECT count(*) FROM job_step_attempt WHERE job_id = '" + jobId + "'");
    }

    private static void audited(Connection connection, SqlWork work) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(connection, Ui2Rows.ACTOR, Ui2Rows.ACTION);
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
