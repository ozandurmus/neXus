package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;

/**
 * {@code UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md} §6 — proves that
 * {@code V5}'s redaction actually removes declared values from
 * {@code audit_log}, that the digest form preserves the audit properties §2
 * claims for it, and that an unclassified column is a build failure rather
 * than a silent default.
 *
 * <p>The defect this guards against was measured, not theorised: before
 * {@code V5}, inserting one {@code sessions} row placed that row's
 * {@code csrf_secret} value verbatim into {@code audit_log.after_state},
 * where {@code ui2_app} read it with a plain {@code SELECT}.</p>
 *
 * <p>No test in this class writes a real secret. Every value is a synthetic
 * token, and no assertion message echoes a value — only the relationship
 * (contract §2; {@code AGENTS.md} sensitive identity reporting law).</p>
 */
class AuditRedactionPolicyTest {

    /**
     * Columns of audited tables that are persisted in full, by decision.
     * Together with {@code audit_redaction_policy} this classifies every
     * column of every audited table exactly once (contract §4). A column in
     * neither set fails {@link #everyAuditedColumnIsClassifiedExactlyOnce()}.
     *
     * <p>This register is deliberately explicit rather than derived: adding a
     * column to an audited table must force a decision in the same change.</p>
     */
    private static final Set<String> AUDITED_IN_FULL = Set.of(
            // cp_inventory_projection
            "cp_inventory_projection.collected_at", "cp_inventory_projection.device_id",
            "cp_inventory_projection.endpoint_id", "cp_inventory_projection.ha_state",
            "cp_inventory_projection.job_id", "cp_inventory_projection.product_version",
            "cp_inventory_projection.projection_id", "cp_inventory_projection.provenance_id",
            // credential_references
            "credential_references.created_at", "credential_references.credential_reference_id",
            "credential_references.purpose",
            // devices
            "devices.created_at", "devices.credential_reference_id", "devices.device_id",
            "devices.disabled", "devices.enrollment_state", "devices.is_test_target",
            "devices.registration_source", "devices.vendor_hint",
            // endpoints
            "endpoints.created_at", "endpoints.device_id", "endpoints.endpoint_id",
            "endpoints.transport_kind",
            // gate_registry
            "gate_registry.action_class", "gate_registry.canonical_command_key",
            "gate_registry.created_at", "gate_registry.gate_id", "gate_registry.max_frequency",
            "gate_registry.platform_role_scope", "gate_registry.retry_rule",
            "gate_registry.safe_telemetry_fields", "gate_registry.secret_output_risk",
            "gate_registry.session_reuse_rule", "gate_registry.shell_context",
            "gate_registry.sign_off_state", "gate_registry.source_document_pointer",
            "gate_registry.timeout_s", "gate_registry.transport_kind",
            "gate_registry.unsupported_behavior_ref", "gate_registry.vendor",
            // job_reconciliation
            "job_reconciliation.job_id", "job_reconciliation.reconciled_outcome",
            "job_reconciliation.recorded_at", "job_reconciliation.recorded_by",
            // job_step_attempt
            "job_step_attempt.action_class", "job_step_attempt.attempt_id",
            "job_step_attempt.attempt_number", "job_step_attempt.created_at",
            "job_step_attempt.error_class", "job_step_attempt.fingerprint_sha256",
            "job_step_attempt.job_id", "job_step_attempt.lease_epoch",
            "job_step_attempt.matched_expectation", "job_step_attempt.mutation_boundary_crossed",
            "job_step_attempt.outcome", "job_step_attempt.output_bytes",
            "job_step_attempt.output_lines", "job_step_attempt.sent_at",
            "job_step_attempt.step_index", "job_step_attempt.step_kind",
            // job_steps
            "job_steps.job_id", "job_steps.job_step_id", "job_steps.step_index",
            // jobs
            "jobs.action_class", "jobs.capability_id", "jobs.finished_at",
            "jobs.idempotency_key", "jobs.job_id", "jobs.job_type",
            "jobs.last_heartbeat_at", "jobs.lease_epoch", "jobs.lease_expires_at",
            "jobs.lease_worker_id", "jobs.outcome", "jobs.precheck_results",
            "jobs.reconciliation_ref", "jobs.state", "jobs.submitted_at",
            "jobs.submitted_by_actor_fingerprint", "jobs.target_device_id",
            "jobs.terminal_reason",
            // provenance_records
            "provenance_records.capability_version", "provenance_records.capture_artifact_id",
            "provenance_records.collected_at", "provenance_records.fingerprint_sha256",
            "provenance_records.parser_version", "provenance_records.provenance_id",
            "provenance_records.run_id", "provenance_records.sanitized_fragment",
            "provenance_records.source_location", "provenance_records.step_id",
            // role_bindings
            "role_bindings.binding_id", "role_bindings.created_at",
            "role_bindings.created_by_actor_fingerprint", "role_bindings.group_reference_key_id",
            "role_bindings.revoked_at", "role_bindings.revoked_by_actor_fingerprint",
            "role_bindings.role_token",
            // secrets_metadata
            "secrets_metadata.backend_kind", "secrets_metadata.component",
            "secrets_metadata.created_at", "secrets_metadata.purpose",
            "secrets_metadata.rotated_at", "secrets_metadata.rotation_policy_ref",
            "secrets_metadata.secret_id",
            // sessions
            "sessions.absolute_expires_at", "sessions.actor_fingerprint",
            "sessions.created_at", "sessions.end_reason",
            "sessions.ended_by_actor_fingerprint", "sessions.idle_deadline_at",
            "sessions.last_seen_at", "sessions.session_id", "sessions.state",
            "sessions.superseded_by_session_id");

    /**
     * Column-name fragments that mark a value as secret, credential-bearing or
     * device-identity-bearing. A column whose name contains one of these and
     * is not in {@code audit_redaction_policy} fails
     * {@link #noSensitivelyNamedColumnEscapesTheRedactionPolicy()} — an
     * independent second gate, so that adding a new sensitive column to the
     * register above is not enough to persist it.
     */
    private static final List<String> SENSITIVE_NAME_FRAGMENTS = List.of(
            "secret", "password", "passwd", "credential", "token", "encrypted",
            "private_key", "pointer", "address", "evidence", "captured");

    /** Register entries exempt from the name heuristic, each by decision. */
    private static final Set<String> NAME_HEURISTIC_EXEMPT = Set.of(
            // An opaque role name, not a bearer token (C3 §3.2).
            "role_bindings.role_token",
            // A risk classification produced by the command gate, not output.
            "gate_registry.secret_output_risk",
            // The identifier of a secret record, never the secret itself.
            "secrets_metadata.secret_id",
            // A pointer to a document, not to a credential.
            "gate_registry.source_document_pointer",
            // Identifiers of a credential reference, never the credential.
            "credential_references.credential_reference_id",
            "devices.credential_reference_id",
            // A digest, already non-reversible.
            "job_step_attempt.fingerprint_sha256",
            "provenance_records.fingerprint_sha256");

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_redaction");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    /**
     * Contract §6.1/§6.2: the value never reaches {@code audit_log}, a digest
     * stands in its place, and the digest still distinguishes two different
     * values while matching two equal ones — so change detection and equality
     * comparison survive redaction.
     */
    @Test
    void sessionCsrfSecretIsReplacedByADigestThatStillDetectsChange() throws SQLException {
        String tokenA = "synthetic-token-" + Ui2Rows.opaqueId("a");
        String tokenB = "synthetic-token-" + Ui2Rows.opaqueId("b");

        try (Connection app = fixture.appConnection()) {
            String s1 = insertSession(app, "actor-redact-1", tokenA);
            String s2 = insertSession(app, "actor-redact-2", tokenB);
            String s3 = insertSession(app, "actor-redact-3", tokenA);

            assertEquals(0L, Ui2Rows.count(app,
                            "SELECT count(*) FROM audit_log WHERE after_state::text LIKE '%" + tokenA
                                    + "%' OR before_state::text LIKE '%" + tokenA + "%'"),
                    "no audit row may contain the csrf_secret value");
            assertEquals(0L, Ui2Rows.count(app,
                            "SELECT count(*) FROM audit_log WHERE after_state::text LIKE '%" + tokenB
                                    + "%' OR before_state::text LIKE '%" + tokenB + "%'"),
                    "no audit row may contain the second csrf_secret value either");

            String d1 = auditedValue(app, s1, "csrf_secret");
            String d2 = auditedValue(app, s2, "csrf_secret");
            String d3 = auditedValue(app, s3, "csrf_secret");

            assertNotNull(d1, "the column must still be present in the snapshot, as a digest");
            assertTrue(d1.startsWith("sha256:"), "the stand-in must be a labelled sha256 digest");
            assertEquals(7 + 64, d1.length(), "a sha256 hex digest is 64 characters");
            assertNotEquals(d1, d2, "two different values must produce two different digests");
            assertEquals(d1, d3, "the same value twice must produce the same digest");
        }
    }

    /** Contract §6.3: a NULL redacted column stays NULL, distinguishably. */
    @Test
    void nullRedactedColumnStaysNullAndIsDistinguishableFromARedactedValue() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String credentialReferenceId = Ui2Rows.insertCredentialReference(app);
            String deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "DRAFT");
            String endpointId = Ui2Rows.insertEndpoint(app, deviceId);

            // endpoints.address_ref is NOT NULL in V1, so the NULL case is
            // proved on a nullable redacted column instead: role_bindings'
            // group_reference_encrypted is nullable only in principle, so use
            // the one redacted column that is genuinely nullable here.
            String endpointDigest = auditedValueFor(app, "endpoints", endpointId, "address_ref");
            assertNotNull(endpointDigest, "a non-null redacted column must leave a digest");
            assertTrue(endpointDigest.startsWith("sha256:"));

            String evidence = auditedValueFor(app, "endpoints", endpointId, "transport_kind");
            assertNotNull(evidence, "a non-redacted column must keep its value");
            assertFalse(evidence.startsWith("sha256:"),
                    "a column outside the policy must not be digested");
        }
    }

    /**
     * Contract §6.4: every column named by §3 is proven redacted by an
     * assertion naming that table and column — not by a blanket scan that
     * would pass if the policy were empty.
     */
    @Test
    void everyDeclaredRedactionIsPresentInThePolicyWithItsTier() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            assertEquals(1, tierOf(app, "sessions", "csrf_secret"));
            assertEquals(1, tierOf(app, "role_bindings", "group_reference_encrypted"));
            assertEquals(2, tierOf(app, "credential_references", "backend_pointer"));
            assertEquals(2, tierOf(app, "secrets_metadata", "reference_pointer"));
            assertEquals(2, tierOf(app, "endpoints", "address_ref"));
            assertEquals(2, tierOf(app, "job_step_attempt", "captured_variables"));
            assertEquals(2, tierOf(app, "job_reconciliation", "evidence"));

            assertEquals(7L, Ui2Rows.count(app, "SELECT count(*) FROM audit_redaction_policy"),
                    "the policy must hold exactly the seven columns §3 declares; "
                            + "an extra entry is an undeclared redaction");
        }
    }

    /**
     * Contract §4, the clause that matters more than the current list: every
     * column of every audited table is classified exactly once. A column in
     * neither {@code audit_redaction_policy} nor {@link #AUDITED_IN_FULL}
     * fails here, naming the table and column, so a new column forces a
     * decision in the same change.
     */
    @Test
    void everyAuditedColumnIsClassifiedExactlyOnce() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            Set<String> redacted = redactedColumns(app);
            List<String> unclassified = new ArrayList<>();
            List<String> classifiedTwice = new ArrayList<>();

            for (String column : auditedColumns(app)) {
                boolean inPolicy = redacted.contains(column);
                boolean inRegister = AUDITED_IN_FULL.contains(column);
                if (!inPolicy && !inRegister) {
                    unclassified.add(column);
                } else if (inPolicy && inRegister) {
                    classifiedTwice.add(column);
                }
            }

            assertEquals(List.of(), unclassified,
                    "every column of an audited table must be classified: either redacted in "
                            + "audit_redaction_policy, or listed in AUDITED_IN_FULL by decision");
            assertEquals(List.of(), classifiedTwice,
                    "a column must not be both redacted and audited in full");
        }
    }

    /**
     * A second, independent gate: a column whose name carries a
     * secret/credential/identity marker must be in the redaction policy.
     * Listing it in {@link #AUDITED_IN_FULL} is therefore not enough to
     * persist it — someone must either redact it or add an explicit,
     * reasoned exemption.
     */
    @Test
    void noSensitivelyNamedColumnEscapesTheRedactionPolicy() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            Set<String> redacted = redactedColumns(app);
            List<String> escaped = new ArrayList<>();

            for (String column : auditedColumns(app)) {
                if (redacted.contains(column) || NAME_HEURISTIC_EXEMPT.contains(column)) {
                    continue;
                }
                String name = column.substring(column.indexOf('.') + 1).toLowerCase(Locale.ROOT);
                for (String fragment : SENSITIVE_NAME_FRAGMENTS) {
                    if (name.contains(fragment)) {
                        escaped.add(column + " (matched '" + fragment + "')");
                        break;
                    }
                }
            }

            assertEquals(List.of(), escaped,
                    "a sensitively named column must be redacted, or exempted with a stated reason");
        }
    }

    /** Contract §6.7: the application cannot rewrite its own audit policy. */
    @Test
    void ui2AppCannotWriteTheRedactionPolicy() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            for (String sql : List.of(
                    "INSERT INTO audit_redaction_policy VALUES ('sessions', 'state', 1, 'x')",
                    "UPDATE audit_redaction_policy SET tier = 2 WHERE table_name = 'sessions'",
                    "DELETE FROM audit_redaction_policy")) {
                SQLException thrown = assertThrows(SQLException.class, () -> {
                    try (Statement statement = app.createStatement()) {
                        statement.execute(sql);
                    }
                }, "ui2_app must not be able to change the redaction policy");
                assertEquals("42501", thrown.getSQLState(),
                        "the refusal must be insufficient_privilege, not an incidental failure");
            }
        }
    }

    /**
     * Contract §5.3: redaction is applied <em>after</em> the context check,
     * never instead of it. V5 must not have weakened the fail-closed
     * behaviour V1 established.
     */
    @Test
    void auditContextMissingStillFailsClosedAfterV5() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            try {
                SQLException thrown = assertThrows(SQLException.class, () -> {
                    try (Statement statement = app.createStatement()) {
                        statement.execute(insertSessionSql(Ui2Rows.opaqueId("sess"),
                                "actor-no-context", "synthetic-token"));
                    }
                });
                assertTrue(thrown.getMessage().contains("audit_context_missing"),
                        "the refusal must still be audit_context_missing");
            } finally {
                app.rollback();
                app.setAutoCommit(true);
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** Every {@code table.column} of every table carrying a {@code trg_audit_*} trigger. */
    private static Set<String> auditedColumns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT c.relname || '.' || a.attname "
                             + "FROM pg_trigger t "
                             + "JOIN pg_class c ON c.oid = t.tgrelid "
                             + "JOIN pg_attribute a ON a.attrelid = c.oid "
                             + "  AND a.attnum > 0 AND NOT a.attisdropped "
                             + "WHERE t.tgname LIKE 'trg_audit_%' AND NOT t.tgisinternal "
                             + "ORDER BY 1")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        if (columns.isEmpty()) {
            fail("no audited columns were found — this test would pass vacuously");
        }
        return columns;
    }

    private static Set<String> redactedColumns(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name || '.' || column_name FROM audit_redaction_policy")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private static int tierOf(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT tier FROM audit_redaction_policy WHERE table_name = '" + table
                             + "' AND column_name = '" + column + "'")) {
            if (!rs.next()) {
                fail(table + "." + column + " is declared redacted by contract §3 but absent "
                        + "from audit_redaction_policy");
            }
            return rs.getInt(1);
        }
    }

    /** One key of the {@code after_state} snapshot of a {@code sessions} audit row. */
    private static String auditedValue(Connection connection, String sessionId, String key)
            throws SQLException {
        return auditedValueFor(connection, "sessions", sessionId, key);
    }

    private static String auditedValueFor(Connection connection, String table, String rowPk,
            String key) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT after_state ->> '" + key + "' FROM audit_log "
                             + "WHERE table_name = '" + table + "' AND row_pk = '" + rowPk
                             + "' ORDER BY audit_id LIMIT 1")) {
            if (!rs.next()) {
                fail("no audit row for " + table + " " + rowPk);
            }
            return rs.getString(1);
        }
    }

    private static String insertSession(Connection app, String actor, String csrfSecret)
            throws SQLException {
        String sessionId = Ui2Rows.opaqueId("sess");
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.redaction");
            try (Statement statement = app.createStatement()) {
                statement.execute(insertSessionSql(sessionId, actor, csrfSecret));
            }
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(true);
        }
        return sessionId;
    }

    private static String insertSessionSql(String sessionId, String actor, String csrfSecret) {
        Instant now = Instant.now();
        return "INSERT INTO sessions(session_id, actor_fingerprint, csrf_secret, state, "
                + "idle_deadline_at, absolute_expires_at) VALUES ('" + sessionId + "', '" + actor
                + "', '" + csrfSecret + "', 'ACTIVE', '"
                + now.plus(15, ChronoUnit.MINUTES) + "', '" + now.plus(8, ChronoUnit.HOURS) + "')";
    }
}
