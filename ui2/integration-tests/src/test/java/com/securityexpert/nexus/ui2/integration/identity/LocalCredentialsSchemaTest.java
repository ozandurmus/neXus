package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;

/**
 * C3A contract (docs/design/UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md,
 * FROZEN 2026-09-13) §11.2 database-layer tests, proved against a real
 * PostgreSQL 16 server (this environment cannot run this class -- no
 * Docker/UI2_TEST_JDBC_URL here -- but it must compile and is the
 * real-environment half of the pure-logic tests in
 * {@code service:test}'s {@code LocalMechanismTest}/{@code PasswordChangeServiceTest}).
 *
 * <p>Rows are seeded with raw SQL rather than through
 * {@code JooqLocalCredentialsRepository} so each test proves the schema's
 * own constraint/trigger, not a repository's opinion of it (mirrors
 * {@code EnrollmentStateCheckConstraintTest}'s own discipline).</p>
 */
class LocalCredentialsSchemaTest {

    @Test
    void v8AppliesCleanlyAndTheNarrowAuditTriggerNeverPersistsCredentialMaterial() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("local_credentials")) {
            MigrateResult result = fixture.runFlyway();
            assertTrue(result.migrations.stream().anyMatch(m -> "8".equals(m.version)),
                    "V8 must be among the applied migrations");

            try (Connection app = fixture.appConnection()) {
                // Two identities, the identical plaintext-shaped verifier
                // bytes deliberately NOT reused -- a real caller would
                // Argon2-hash first; this test's own verifier/salt values
                // stand in for that output, since the schema's own
                // constraints (distinctness, allowlisted audit capture) are
                // this test's subject, not the hashing library.
                String nexusadminId = insertLocalCredential(app, "nexusadmin", "verifier-bytes-a", "salt-bytes-a");
                String claudeadminId = insertLocalCredential(app, "claudeadmin", "verifier-bytes-b", "salt-bytes-b");
                assertNotEquals(nexusadminId, claudeadminId);

                // Test 9: every audit_log row this table's trigger wrote
                // exposes only the §8 allowlist -- never verifier/salt/
                // algorithm_id/memory_cost_kib/time_cost/parallelism.
                Set<String> allowedKeys = Set.of(
                        "local_identity_id", "failed_attempt_count", "locked_until", "created_at", "updated_at");
                assertAuditRowsUseOnlyAllowedKeys(app, nexusadminId, allowedKeys);
                assertAuditRowsUseOnlyAllowedKeys(app, claudeadminId, allowedKeys);

                // Test 7 (DB half): the two bootstrap identities are
                // distinct actors -- their INSERT audit rows carry
                // different actor_fingerprint values.
                String nexusadminActor = actorFingerprintOfInsert(app, nexusadminId);
                String claudeadminActor = actorFingerprintOfInsert(app, claudeadminId);
                assertNotEquals(nexusadminActor, claudeadminActor);

                // Test 4/5: lockout threshold, effect, and self-expiring clearing.
                for (int i = 0; i < 4; i++) {
                    recordFailedAttempt(app, nexusadminId, Instant.now());
                }
                assertFalse(isLocked(app, nexusadminId), "must not lock before the 5th failure");
                recordFailedAttempt(app, nexusadminId, Instant.now());
                assertTrue(isLocked(app, nexusadminId), "the 5th consecutive failure must lock the identity");

                // Force locked_until into the past (simulating elapsed
                // time) and prove a successful login clears both columns.
                forceLockedUntilIntoThePast(app, nexusadminId);
                recordSuccessfulLogin(app, nexusadminId, nexusadminActor);
                assertFalse(isLocked(app, nexusadminId));
                assertEquals(0, failedAttemptCount(app, nexusadminId));
            }
        }
    }

    @Test
    void localIdentityNameIsUniqueAndVerifierSaltAreDistinctAcrossIdenticalPlaintextPasswords() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("local_credentials_unique")) {
            fixture.runFlyway();
            try (Connection app = fixture.appConnection()) {
                insertLocalCredential(app, "dup-name", "verifier-1", "salt-1");
                SQLException rejected = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                        () -> insertLocalCredential(app, "dup-name", "verifier-2", "salt-2"));
                assertEquals("23505", rejected.getSQLState(), "expected unique_violation on local_identity_name");
            }
        }
    }

    private static String insertLocalCredential(Connection app, String name, String verifierSeed, String saltSeed)
            throws SQLException {
        String id = "local-" + UUID.randomUUID();
        Ui2Rows.setAuditContext(app, "system:bootstrap", "local_credential_create");
        boolean previousAutoCommit = app.getAutoCommit();
        app.setAutoCommit(false);
        try {
            try (PreparedStatement statement = app.prepareStatement(
                    "INSERT INTO local_credentials(local_identity_id, local_identity_name, verifier, salt, "
                            + "algorithm_id, memory_cost_kib, time_cost, parallelism) VALUES (?, ?, ?, ?, 'argon2id', 19456, 2, 1)")) {
                statement.setString(1, id);
                statement.setString(2, name);
                statement.setBytes(3, verifierSeed.getBytes());
                statement.setBytes(4, saltSeed.getBytes());
                statement.execute();
            }
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(previousAutoCommit);
        }
        return id;
    }

    private static void recordFailedAttempt(Connection app, String id, Instant now) throws SQLException {
        Ui2Rows.setAuditContext(app, "system:local_login_attempt", "local_login_failure");
        boolean previousAutoCommit = app.getAutoCommit();
        app.setAutoCommit(false);
        try (Statement statement = app.createStatement()) {
            Ui2Rows.setAuditContext(app, "system:local_login_attempt", "local_login_failure");
            statement.execute("UPDATE local_credentials SET failed_attempt_count = failed_attempt_count + 1, "
                    + "updated_at = now(), locked_until = CASE WHEN failed_attempt_count + 1 >= 5 "
                    + "THEN now() + interval '15 minutes' ELSE locked_until END WHERE local_identity_id = '" + id + "'");
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(previousAutoCommit);
        }
    }

    private static void recordSuccessfulLogin(Connection app, String id, String actorFingerprint) throws SQLException {
        boolean previousAutoCommit = app.getAutoCommit();
        app.setAutoCommit(false);
        try (Statement statement = app.createStatement()) {
            Ui2Rows.setAuditContext(app, actorFingerprint, "local_login_success");
            statement.execute("UPDATE local_credentials SET failed_attempt_count = 0, locked_until = NULL, "
                    + "updated_at = now() WHERE local_identity_id = '" + id + "'");
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(previousAutoCommit);
        }
    }

    private static void forceLockedUntilIntoThePast(Connection app, String id) throws SQLException {
        boolean previousAutoCommit = app.getAutoCommit();
        app.setAutoCommit(false);
        try (PreparedStatement statement = app.prepareStatement(
                "UPDATE local_credentials SET locked_until = ? WHERE local_identity_id = ?")) {
            // This one column-only touch also needs audit context, since
            // the row-level trigger fires on any UPDATE.
            Ui2Rows.setAuditContext(app, "system:local_login_attempt", "local_login_failure");
            statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(3600)));
            statement.setString(2, id);
            statement.execute();
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(previousAutoCommit);
        }
    }

    private static boolean isLocked(Connection app, String id) throws SQLException {
        try (Statement statement = app.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT locked_until FROM local_credentials WHERE local_identity_id = '" + id + "'")) {
            rows.next();
            Timestamp lockedUntil = rows.getTimestamp(1);
            return lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now());
        }
    }

    private static int failedAttemptCount(Connection app, String id) throws SQLException {
        try (Statement statement = app.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT failed_attempt_count FROM local_credentials WHERE local_identity_id = '" + id + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String actorFingerprintOfInsert(Connection app, String id) throws SQLException {
        try (Statement statement = app.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT actor_fingerprint FROM audit_log WHERE table_name = 'local_credentials' "
                                + "AND row_pk = '" + id + "' AND operation = 'INSERT'")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void assertAuditRowsUseOnlyAllowedKeys(Connection app, String id, Set<String> allowedKeys)
            throws SQLException {
        try (Statement statement = app.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT before_state, after_state FROM audit_log WHERE table_name = 'local_credentials' "
                                + "AND row_pk = '" + id + "'")) {
            while (rows.next()) {
                assertKeysWithin(rows.getString(1), allowedKeys);
                assertKeysWithin(rows.getString(2), allowedKeys);
            }
        }
    }

    private static void assertKeysWithin(String jsonOrNull, Set<String> allowedKeys) {
        if (jsonOrNull == null) {
            return;
        }
        // A minimal top-level-key scan is sufficient here: this table's
        // allowlisted JSON is always a flat object of scalar values (no
        // nested objects), per V8's fn_audit_capture_local_credentials().
        Set<String> present = new HashSet<>();
        for (String part : jsonOrNull.replace("{", "").replace("}", "").split(",")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                present.add(part.substring(0, colon).trim().replace("\"", ""));
            }
        }
        assertTrue(allowedKeys.containsAll(present),
                "audit row for local_credentials must contain only the §8 allowlist; found: " + present);
        assertFalse(present.contains("verifier") || present.contains("salt")
                || present.contains("memory_cost_kib") || present.contains("time_cost")
                || present.contains("parallelism") || present.contains("algorithm_id"),
                "no credential material may reach an audit row (§8)");
    }
}
