package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §8 (B1-3) tests that need a real PostgreSQL 16 server, or a real
 * directory server, or both.
 *
 * <p>The two database-only scenarios are implemented here against
 * {@link Ui2PostgresFixture}. The directory-dependent ones still have no
 * carrier in this environment (no test LDAP) and stay {@code @Disabled} with
 * a precise reason. Contract §8 test 3 is implemented here per Correction
 * C-1, which resolved the contradiction between its original "exactly one
 * audit row" wording and {@code V2}'s per-row trigger.</p>
 *
 * <p>The pure decision-logic half of each scenario is proved without a
 * database by {@code service}'s own unit tests ({@code LoginFlowTest},
 * {@code RbacEvaluatorTest}, {@code RoleBindingAdminServiceTest}), named in
 * each Javadoc below.</p>
 */
class IdentitySessionsRbacDatabasePlaceholderTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("identity_sessions");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(IdentitySessionsRbacDatabasePlaceholderTest.class);
    }

    /**
     * Contract §8 test 1 / C3 §8 criterion 1: a raw INSERT that bypasses the
     * Java service entirely cannot create a second {@code ACTIVE} session for
     * an actor that already has one — {@code ux_sessions_one_active_per_actor}
     * refuses it. {@code LoginFlowTest} exercises the application-layer logic
     * against an in-memory repository that enforces the invariant itself,
     * which is not proof that the database enforces it against a bypassed
     * application layer.
     */
    @Test
    void singleActiveSessionStructurallyEnforcedBySqlBypass() throws SQLException {
        String actor = "actor-single-active";
        try (Connection app = fixture.appConnection()) {
            insertSession(app, actor, "ACTIVE");

            SQLException rejected = assertThrows(SQLException.class, () -> insertSession(app, actor, "ACTIVE"));
            assertEquals("23505", rejected.getSQLState(), "expected unique_violation");
            assertEquals(true, rejected.getMessage().contains("ux_sessions_one_active_per_actor"),
                    "the refusal must come from the partial unique index specifically");

            // The index is partial, so a non-ACTIVE second row for the same
            // actor must still be accepted -- otherwise this would be proving
            // "one session per actor", a different and wrong rule.
            insertSession(app, actor, "SUPERSEDED");
            insertSession(app, actor, "EXPIRED");
            assertEquals(1L, Ui2Rows.count(app, "SELECT count(*) FROM sessions WHERE actor_fingerprint = '"
                    + actor + "' AND state = 'ACTIVE'"));
            assertEquals(3L, Ui2Rows.count(app, "SELECT count(*) FROM sessions WHERE actor_fingerprint = '"
                    + actor + "'"));
        }
    }

    /**
     * Contract §8 test 6 / C3 §8 criterion 5: a
     * {@code last_seen_at}/{@code idle_deadline_at}-only UPDATE produces zero
     * new {@code audit_log} rows ({@code trg_audit_sessions} is scoped to
     * {@code AFTER INSERT OR UPDATE OF state}, and PostgreSQL's
     * {@code UPDATE OF column_list} trigger form does not fire when that
     * column is absent from the SET list); a state UPDATE on the same row
     * produces exactly one.
     */
    @Test
    void heartbeatGeneratesNoAuditNoiseStateChangeGeneratesExactlyOne() throws SQLException {
        String actor = "actor-heartbeat";
        try (Connection app = fixture.appConnection()) {
            String sessionId = insertSession(app, actor, "ACTIVE");
            assertEquals(1L, Ui2Rows.countAuditRows(app, "sessions", sessionId),
                    "the session INSERT itself is audited exactly once");

            // Heartbeat: last_seen_at/idle_deadline_at only.
            for (int i = 0; i < 3; i++) {
                audited(app, "UPDATE sessions SET last_seen_at = now(), idle_deadline_at = now() + interval '15 "
                        + "minutes' WHERE session_id = '" + sessionId + "'");
            }
            assertEquals(1L, Ui2Rows.countAuditRows(app, "sessions", sessionId),
                    "three heartbeat UPDATEs must add zero audit_log rows");

            // A state change on the same row.
            audited(app, "UPDATE sessions SET state = 'EXPIRED', end_reason = 'idle_timeout' "
                    + "WHERE session_id = '" + sessionId + "'");
            assertEquals(2L, Ui2Rows.countAuditRows(app, "sessions", sessionId),
                    "a state-column UPDATE must add exactly one audit_log row");
        }
    }

    /**
     * Contract §8 test 3, as replaced by Correction C-1 (2026-09-12): a
     * takeover writes <strong>exactly two</strong> audit rows — one for
     * {@code S1}'s state transition, one for {@code S2}'s creation — both
     * carrying the same actor, action and correlation id, proving one logical
     * event; and a rollback of the same takeover leaves neither session
     * change nor audit row.
     *
     * <p>The original clause required exactly one row. That was a defect in
     * the contract, not in the schema: {@code trg_audit_sessions} is a
     * per-row trigger, and a single row would have recorded the new session
     * while hiding the prior session's forced termination. C-1 records the
     * adjudication.</p>
     *
     * <p>{@code LoginFlowTest.takeoverTransitionsPriorToSupersededAndCreatesANewActiveSessionInOneCall}
     * proves the state-transition logic against an in-memory repository;
     * this test proves the database half.</p>
     */
    @Test
    void takeoverWritesTwoCorrelatedAuditRowsInOneTransaction() throws SQLException {
        String actor = "actor-takeover";
        try (Connection app = fixture.appConnection()) {
            String s1 = insertSession(app, actor, "ACTIVE");
            long auditBefore = Ui2Rows.countAuditRows(app, "sessions", s1);

            // The takeover: supersede S1 and create S2 in one transaction.
            String s2 = Ui2Rows.opaqueId("sess");
            Instant now = Instant.now();
            app.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.takeover");
                try (Statement statement = app.createStatement()) {
                    // The statement order is forced by two structural constraints:
                    // superseded_by_session_id is an FK, so S2 must exist before
                    // the link can be set; and ux_sessions_one_active_per_actor
                    // is checked per statement, so S2 cannot be inserted ACTIVE
                    // while S1 is still ACTIVE. Hence: supersede, insert, link.
                    statement.execute("UPDATE sessions SET state = 'SUPERSEDED', end_reason = 'login_elsewhere', "
                            + "ended_by_actor_fingerprint = '" + Ui2Rows.ACTOR + "' "
                            + "WHERE session_id = '" + s1 + "'");
                    statement.execute("INSERT INTO sessions(session_id, actor_fingerprint, csrf_secret, state, "
                            + "idle_deadline_at, absolute_expires_at) VALUES ('" + s2 + "', '" + actor
                            + "', 'opaque-test-token', 'ACTIVE', '"
                            + now.plus(15, ChronoUnit.MINUTES) + "', '" + now.plus(8, ChronoUnit.HOURS) + "')");
                    statement.execute("UPDATE sessions SET superseded_by_session_id = '" + s2 + "' "
                            + "WHERE session_id = '" + s1 + "'");
                }
                app.commit();
            } catch (SQLException e) {
                app.rollback();
                throw e;
            } finally {
                app.setAutoCommit(true);
            }

            assertEquals("SUPERSEDED", stateOf(app, s1), "S1 must be SUPERSEDED after the takeover");
            assertEquals("ACTIVE", stateOf(app, s2), "S2 must be ACTIVE after the takeover");
            assertEquals(s2, supersededBy(app, s1), "S1 must record S2 as its successor");

            // Exactly two audit rows, one per mutated session row.
            assertEquals(auditBefore + 1L, Ui2Rows.countAuditRows(app, "sessions", s1),
                    "S1's state transition must be audited exactly once, and the "
                            + "follow-up superseded_by_session_id UPDATE must add no further row "
                            + "because trg_audit_sessions is scoped to UPDATE OF state");
            assertEquals(1L, Ui2Rows.countAuditRows(app, "sessions", s2),
                    "S2's creation must be audited exactly once");

            // Both halves attributable to one actor and one action.
            try (Statement statement = app.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT DISTINCT actor_fingerprint, action_id FROM audit_log "
                                 + "WHERE table_name = 'sessions' AND action_id = 'harness.takeover'")) {
                assertTrue(rs.next(), "the takeover must produce audit rows under its own action_id");
                assertEquals(Ui2Rows.ACTOR, rs.getString("actor_fingerprint"));
                assertFalse(rs.next(),
                        "both audit rows must share one actor_fingerprint and action_id, "
                                + "proving one logical event rather than two unrelated mutations");
            }

            // A rolled-back takeover leaves neither session change nor audit row.
            String s3 = Ui2Rows.opaqueId("sess");
            long auditBeforeRollback = Ui2Rows.countAuditRows(app, "sessions", s2);
            app.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.takeover.rollback");
                try (Statement statement = app.createStatement()) {
                    statement.execute("UPDATE sessions SET state = 'SUPERSEDED', end_reason = 'login_elsewhere' "
                            + "WHERE session_id = '" + s2 + "'");
                    statement.execute("INSERT INTO sessions(session_id, actor_fingerprint, csrf_secret, state, "
                            + "idle_deadline_at, absolute_expires_at) VALUES ('" + s3 + "', '" + actor
                            + "', 'opaque-test-token', 'ACTIVE', '"
                            + now.plus(15, ChronoUnit.MINUTES) + "', '" + now.plus(8, ChronoUnit.HOURS) + "')");
                }
                app.rollback();
            } finally {
                app.setAutoCommit(true);
            }

            assertEquals("ACTIVE", stateOf(app, s2), "a rolled-back takeover must leave S2 untouched");
            assertNull(stateOf(app, s3), "a rolled-back takeover must leave no new session row");
            assertEquals(auditBeforeRollback, Ui2Rows.countAuditRows(app, "sessions", s2),
                    "a rolled-back takeover must leave no audit row behind");
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** One {@code sessions} row. {@code csrf_secret} is an opaque test token, never a real secret. */
    private static String insertSession(Connection app, String actor, String state) throws SQLException {
        String sessionId = Ui2Rows.opaqueId("sess");
        Instant now = Instant.now();
        audited(app, "INSERT INTO sessions(session_id, actor_fingerprint, csrf_secret, state, "
                + "idle_deadline_at, absolute_expires_at) VALUES ('" + sessionId + "', '" + actor
                + "', 'opaque-test-token', '" + state + "', '"
                + now.plus(15, ChronoUnit.MINUTES) + "', '" + now.plus(8, ChronoUnit.HOURS) + "')");
        return sessionId;
    }

    /** The {@code state} of one session, or {@code null} when the row does not exist. */
    private static String stateOf(Connection app, String sessionId) throws SQLException {
        try (Statement statement = app.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT state FROM sessions WHERE session_id = '" + sessionId + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** The successor recorded on one session, or {@code null}. */
    private static String supersededBy(Connection app, String sessionId) throws SQLException {
        try (Statement statement = app.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT superseded_by_session_id FROM sessions WHERE session_id = '" + sessionId + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void audited(Connection app, String sql) throws SQLException {
        boolean previousAutoCommit = app.getAutoCommit();
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.session");
            try (Statement statement = app.createStatement()) {
                statement.execute(sql);
            }
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(previousAutoCommit);
        }
    }

    @Test
    @Disabled("requires a live directory server (test LDAP, e.g. OpenLDAP/ApacheDS Testcontainer) and a live container runtime, neither available in this environment")
    void unmappedIdentityReceivesNoRoleAgainstARealDirectoryBind() {
        // Contract §8 test 7 [directory]. RbacEvaluatorTest.
        // unmappedIdentityNeverReceivesPermittedForAnyRoleGatedAction
        // proves the decision logic against an already-resolved group set;
        // a directory-capable host must additionally prove the end-to-end
        // path: a real bind against a synthetic identity matching no
        // configured group, through UnboundIdOperatorBindAdapter, through
        // login, never evaluates PERMITTED for any role-gated action.
    }

    @Test
    @Disabled("requires a live directory server (test LDAP) and a live container runtime, neither available in this environment")
    void boundButNotAMemberRefusalShapeAgainstARealDirectoryBind() {
        // Contract §8 test 9 [directory]. RbacEvaluatorTest.
        // boundButNotAMemberIsDeniedWithActorNotInRequiredGroup proves the
        // decision logic; a directory-capable host must prove the same
        // outcome end-to-end through a real bind and a real
        // authz_decisions row.
    }

    @Test
    @Disabled("requires a live directory server (test LDAP) and a live container runtime, neither available in this environment")
    void selfGrantRefusedAgainstARealDirectoryBind() {
        // Contract §8 test 13 [directory]. RoleBindingAdminServiceTest.
        // selfGrantIsRefusedAndNoRowIsCreated proves the decision logic; a
        // directory-capable host must prove the same outcome end-to-end,
        // including that no role_bindings row is visible in the database
        // afterward.
    }

    @Test
    @Disabled("requires a live directory server (test LDAP) and a live container runtime, neither available in this environment")
    void directoryUnreachableLoginFailsClosedWith503() {
        // Contract §8 test 14 [directory]. A directory-capable host must
        // stop the test LDAP directory before a login bind and prove
        // exactly 503 DIRECTORY_UNAVAILABLE, never 401, never a
        // cached-credential retry (there is no cache to retry against).
        // UnboundIdOperatorBindAdapterTest proves the adapter's own
        // connectivity-failure classification without a directory; this
        // is the end-to-end proof that classification reaches the HTTP
        // response unchanged.
    }

    @Test
    @Disabled("requires a live directory server (test LDAP) and a live container runtime, neither available in this environment")
    void directoryUnreachableRevalidationLeavesActorAuthzStateStale() {
        // Contract §8 test 15 [directory]. UnboundIdRevalidationAdapterTest.
        // revalidateRefusesWithoutOpeningAConnectionWhileDisabled proves
        // the disabled-adapter half; a directory-capable host with
        // directory_posture_enabled=true (test-scope only, C3 §4.4.3) must
        // additionally stop the test directory mid-cycle and prove the
        // actor's actor_authz_state row is not refreshed, and once
        // valid_until elapses, E4 returns AUTHZ_NOT_EVALUATED, never
        // DENIED, never a stale PERMITTED.
    }
}
