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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;


import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.securityexpert.nexus.ui2.identity.ldap.UnboundIdOperatorBindAdapter;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.DirectoryObservation;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.PrincipalFingerprint;
import com.securityexpert.nexus.ui2.platform.Result;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.identity.ldap.LdapMechanism;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.Clock;
import com.securityexpert.nexus.ui2.service.api.LoginController;
import com.securityexpert.nexus.ui2.service.security.LocalMechanism;
import com.securityexpert.nexus.ui2.service.security.MechanismRegistry;
import com.securityexpert.nexus.ui2.service.security.DirectoryAuthorizationRefresh;
import com.securityexpert.nexus.ui2.service.security.DirectoryTargetPort;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.security.RoleBindingAdminService;

import com.securityexpert.nexus.ui2.integration.support.TestLdapDirectory;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §8 (B1-3) tests that need a real PostgreSQL 16 server, a real
 * directory server, or both.
 *
 * <p>The two originally-database-only scenarios are implemented here
 * against {@link Ui2PostgresFixture}. Contract §8 test 3 is implemented
 * here per Correction C-1, which resolved the contradiction between its
 * original "exactly one audit row" wording and {@code V2}'s per-row
 * trigger.</p>
 *
 * <p><b>Directory-dependent tests.</b> Tests 7, 9, 13, 14 and 15 run
 * against {@link TestLdapDirectory}: UnboundID's in-process
 * {@code InMemoryDirectoryServer}, served over LDAPS with a synthetic CA, so
 * the production {@link UnboundIdOperatorBindAdapter} (and, for test 15, the
 * re-validation adapter) bind over the same verified TLS transport and
 * server-owned {@code DirectoryProfile} a deployment uses. No container and
 * no plain-socket seam are involved.</p>
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

    /**
     * Contract §8 test 7 [directory]. {@code RbacEvaluatorTest} proves the
     * decision logic against a hand-built group set; this proves the
     * end-to-end path: a real LDAPS bind of a synthetic identity that holds
     * no role group (only the product's access group), through
     * {@link UnboundIdOperatorBindAdapter}'s verified TLS transport and real
     * LDAP searches, persisted as a directory-proven {@code actor_authz_state}
     * row, then evaluated by the real {@link RbacEvaluator} -- never
     * {@code PERMITTED} for a token with an active binding the actor does not
     * hold, and never a default/fallback token.
     *
     * <p>Since 7ca608a5 (C3C) an operator bind admits only a member of the
     * profile's access group, and the evaluator reads typed directory
     * bindings against a directory-proven actor state; the refusal reason is
     * {@code actor_not_in_required_binding}.</p>
     */
    @Test
    void unmappedIdentityReceivesNoRoleAgainstARealDirectoryBind() {
        try (TestLdapDirectory directory = TestLdapDirectory.start()) {
            String personDn = directory.addPerson("unmapped-user", "test-pw-unmapped-1");
            // A role group the unmapped identity is deliberately NOT a member of.
            String operatorGroupDn = directory.addGroup("role-operator-group");

            LdapOperatorBindPort.OperatorBindOutcome outcome = bind(directory, "unmapped-user", "test-pw-unmapped-1");
            assertEquals(PrincipalFingerprint.of(personDn), outcome.actorFingerprint());
            assertEquals(Set.of(TestLdapDirectory.ACCESS_GROUP_DN), outcome.groupReferences(),
                    "the unmapped identity must resolve to the access group only, no role group");

            Directory db = new Directory(fixture);
            Instant now = Instant.now();
            db.persistProof(outcome, directory);
            db.bindGroup(RoleToken.OPERATOR, operatorGroupDn);

            RbacEvaluator.Decision decision = db.evaluator().evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.OPERATOR), now);
            assertEquals(AuthzOutcome.DENIED, decision.outcome(),
                    "a role-gated action must never evaluate PERMITTED for an identity matching no role_bindings row");
            assertEquals(RbacEvaluator.REASON_ACTOR_NOT_IN_REQUIRED_BINDING, decision.reasonCode().orElse(null));

            // A token with zero active bindings at all: role_token_unbound,
            // still never PERMITTED, never a default token.
            RbacEvaluator.Decision unboundDecision = db.evaluator().evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.COMPLIANCE_ADMIN), now);
            assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, unboundDecision.outcome());
            assertEquals(RbacEvaluator.REASON_ROLE_TOKEN_UNBOUND, unboundDecision.reasonCode().orElse(null));

            // Un-role-gated actions still proceed, but under NO_APPLICABLE_AUTHORITY, never PERMITTED.
            RbacEvaluator.Decision noTokenDecision = db.evaluator().evaluate(outcome.actorFingerprint(),
                    Optional.empty(), now);
            assertEquals(AuthzOutcome.NO_APPLICABLE_AUTHORITY, noTokenDecision.outcome());
        }
    }

    /**
     * Contract §8 test 9 [directory]. {@code RbacEvaluatorTest} proves the
     * decision logic against a hand-built group set; this proves the same
     * outcome end-to-end: a real bind resolves the actor's real (but
     * insufficient) group memberships, and the real {@link RbacEvaluator}
     * refuses {@code DENIED / actor_not_in_required_binding} against a token
     * that is genuinely bound to a different, real group -- while the group
     * the actor does hold is honoured.
     */
    @Test
    void boundButNotAMemberRefusalShapeAgainstARealDirectoryBind() {
        try (TestLdapDirectory directory = TestLdapDirectory.start()) {
            String bobDn = directory.addPerson("bob", "test-pw-bob-1");
            String memberGroupDn = directory.addGroup("group-bob-is-in", bobDn);
            String requiredGroupDn = directory.addGroup("group-required-for-backup-admin"); // bob is NOT a member

            LdapOperatorBindPort.OperatorBindOutcome outcome = bind(directory, "bob", "test-pw-bob-1");
            assertEquals(Set.of(TestLdapDirectory.ACCESS_GROUP_DN, memberGroupDn), outcome.groupReferences(),
                    "bob must resolve to exactly the groups he is a real member of");

            Directory db = new Directory(fixture);
            Instant now = Instant.now();
            db.persistProof(outcome, directory);
            db.bindGroup(RoleToken.BACKUP_ADMIN, requiredGroupDn);

            RbacEvaluator.Decision decision = db.evaluator().evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.BACKUP_ADMIN), now);
            assertEquals(AuthzOutcome.DENIED, decision.outcome(),
                    "bound-but-not-a-member must refuse DENIED, never PERMITTED");
            assertEquals(RbacEvaluator.REASON_ACTOR_NOT_IN_REQUIRED_BINDING, decision.reasonCode().orElse(null));
            assertTrue(decision.bindingId().isEmpty());

            // Non-vacuity: the same evaluator permits the group bob really holds.
            String held = db.bindGroup(RoleToken.BACKUP_ADMIN, memberGroupDn);
            RbacEvaluator.Decision permitted = db.evaluator().evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.BACKUP_ADMIN), now);
            assertEquals(AuthzOutcome.PERMITTED, permitted.outcome());
            assertEquals(Optional.of(held), permitted.bindingId());
        }
    }

    /**
     * Contract §8 test 13 [directory]. {@code RoleBindingAdminServiceTest}
     * proves the decision logic against a hand-built admin group set; this
     * proves the same outcome end-to-end: a real bind resolves the acting
     * security admin's real group membership, and
     * {@link RoleBindingAdminService} refuses a binding of a group the admin
     * already holds against that real, persisted, directory-proven
     * {@code actor_authz_state} row, leaving no {@code role_bindings} row
     * behind. Since 7ca608a5 directory bindings are created only through the
     * typed selection path ({@code resolveSelections} then
     * {@code createDirectory}); the target is offered through
     * {@link DirectoryTargetPort} (no production adapter exists yet --
     * corporate composition stays disabled), carrying the bind's own
     * publication.
     */
    @Test
    void selfGrantRefusedAgainstARealDirectoryBind() {
        try (TestLdapDirectory directory = TestLdapDirectory.start();
                Ui2PostgresFixture own = Ui2PostgresFixture.createAndMigrate("identity_self_grant")) {
            String adminDn = directory.addPerson("security-admin-1", "test-pw-admin-1");
            String securityAdminsDn = directory.addGroup("security-admins", adminDn);
            String ownGroupDn = directory.addGroup("group-admin-already-holds", adminDn);

            LdapOperatorBindPort.OperatorBindOutcome outcome = bind(directory, "security-admin-1", "test-pw-admin-1");
            assertEquals(Set.of(TestLdapDirectory.ACCESS_GROUP_DN, securityAdminsDn, ownGroupDn),
                    outcome.groupReferences());

            Directory db = new Directory(own);
            Instant now = Instant.now();
            String sessionId = db.openSession(outcome.actorFingerprint(), now);
            db.persistProof(outcome, directory);
            db.bindGroup(RoleToken.SECURITY_ADMIN, securityAdminsDn);

            DirectoryTargetPort.Target target = new DirectoryTargetPort.Target(TestLdapDirectory.PROFILE_ID,
                    DirectoryBindingKind.DIRECTORY_GROUP, ownGroupDn, now.plus(1, ChronoUnit.MINUTES),
                    outcome.observation().publication());
            DirectoryTargetPort targets = new DirectoryTargetPort() {
                @Override public java.util.List<Target> list(String profileId, DirectoryBindingKind kind, Instant at) {
                    return java.util.List.of(target);
                }
                @Override public Optional<Target> resolve(Target selected, Instant at) {
                    return Optional.of(target);
                }
            };
            RoleBindingAdminService service = new RoleBindingAdminService(db.bindings, db.actors, db.cipher,
                    new com.securityexpert.nexus.ui2.persistence.identity.SecurityAdminLockoutGuard(
                            db.bindings, db.locals, db.cipher),
                    null, true, db.sessions, new LocalIdentityResolver(db.locals), targets);

            var selections = service.resolveSelections(outcome.actorFingerprint(), sessionId,
                    TestLdapDirectory.PROFILE_ID, DirectoryBindingKind.DIRECTORY_GROUP, now);
            assertEquals(1, selections.size(), "the authorized security admin must be offered the target");
            RoleBindingAdminService.Outcome result = service.createDirectory(outcome.actorFingerprint(), sessionId,
                    RoleToken.BACKUP_ADMIN, selections.get(0).handle(), TestLdapDirectory.PROFILE_ID,
                    DirectoryBindingKind.DIRECTORY_GROUP, now);

            assertTrue(result instanceof RoleBindingAdminService.Outcome.SelfGrantRefused,
                    "an admin whose own real, directory-resolved group set already contains the group being "
                            + "bound must be refused SELF_GRANT_REFUSED");
            assertFalse(db.bindings.hasAnyActiveBinding(RoleToken.BACKUP_ADMIN),
                    "no role_bindings row may exist after a refused self-grant");
        }
    }

    /**
     * Contract §8 test 14 [directory]. {@code UnboundIdOperatorBindAdapterTest}
     * proves the adapter's own connectivity-failure classification without a
     * directory (an unreachable host); this test stops a directory that was
     * genuinely reachable a moment before, and proves the classification
     * reaches {@link LoginController}'s HTTP response unchanged: exactly
     * {@code 503 DIRECTORY_UNAVAILABLE}, never {@code 401}, never a
     * cached-credential retry (none exists). {@code LoginController.login}
     * never touches its {@code HttpServletResponse} parameter on this path
     * (only on a successful bind, to set the session cookie), so a real
     * instance of the production controller is exercised here directly,
     * with no servlet container and no mock response needed.
     */
    @Test
    void directoryUnreachableLoginFailsClosedWith503() {
        TestLdapDirectory directory = TestLdapDirectory.start();
        directory.addPerson("carol", "test-pw-carol-1");
        UnboundIdOperatorBindAdapter adapter = directory.operatorBindAdapter();

        // Prove the directory really was reachable a moment ago -- this is
        // not a host that was never up.
        Result<LdapOperatorBindPort.OperatorBindOutcome> priorBind = adapter.bind("carol", "test-pw-carol-1".toCharArray());
        assertTrue(priorBind instanceof Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>);

        directory.stopListening();

        // LoginController.login only ever touches loginFlow after a
        // successful bind (see its Javadoc above); a failed-closed bind
        // must never reach it, so no LoginFlow/SessionRepository
        // implementation is needed here at all -- null is deliberate, not
        // a shortcut around the real code path. LdapMechanism wraps the
        // real adapter behind the C3A §2.1 Mechanism interface, exactly as
        // the production registry would; the LocalMechanism passed to
        // MechanismRegistry is never exercised on this "ldap"-selected
        // request (its repository throws if it ever is, which would itself
        // fail this test).
        MechanismRegistry mechanismRegistry = new MechanismRegistry(
                new LocalMechanism(new NeverInvokedLocalCredentialsRepository(), Clock.system()),
                java.util.List.of(new LdapMechanism(adapter)));
        LoginController controller = new LoginController(mechanismRegistry, null);

        ResponseEntity<java.util.Map<String, Object>> response = controller.login(
                new LoginController.LoginRequest("carol", "test-pw-carol-1".toCharArray(), "ldap"),
                (HttpServletResponse) null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "a directory that goes unreachable between two calls must fail closed 503, never 401");
        assertEquals("DIRECTORY_UNAVAILABLE", response.getBody().get("error"));
    }

    /** A {@link LocalCredentialsRepository} that fails the test if the "ldap"-selected request above ever reaches it. */
    private static final class NeverInvokedLocalCredentialsRepository implements LocalCredentialsRepository {
        @Override
        public java.util.Optional<LocalCredentialRecord> findByName(String localIdentityName) {
            throw new AssertionError("the local mechanism must never be invoked for an ldap-selected login attempt");
        }

        @Override
        public java.util.Optional<LocalCredentialRecord> findById(String localIdentityId) {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
                String createdByActorFingerprint) {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public boolean anyExist() {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public java.util.List<LocalCredentialRecord> findAll() {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public void markMustChangePassword(String localIdentityId, String actorFingerprint) {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public void recordFailedAttempt(String localIdentityId, java.time.Instant now, int lockoutThreshold,
                java.time.Duration lockoutDuration) {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public void recordSuccessfulLogin(String localIdentityId, java.time.Instant now, String actorFingerprint) {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String changedByActorFingerprint) {
            throw new AssertionError("not exercised by this test");
        }


        @Override
        public void adminSetPassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String settingAdminActorFingerprint) {
            throw new AssertionError("not exercised by this test");
        }

        @Override
        public void setEnabled(String localIdentityId, boolean enabled, String actingAdminActorFingerprint) {
            throw new AssertionError("not exercised by this test");
        }
    }

    /**
     * Contract §8 test 15 [directory]. {@code UnboundIdRevalidationAdapterTest.}
     * {@code revalidateRefusesWithoutOpeningAConnectionWhileDisabled} proves
     * the disabled-adapter half without a directory. This is the enabled half:
     * a directory that was reachable at login goes away, the production
     * {@link DirectoryAuthorizationRefresh} (service-account re-validation
     * over the same verified LDAPS profile) refreshes nothing, the persisted
     * {@code actor_authz_state} row keeps its login-time resolution, and once
     * that resolution expires the evaluator answers
     * {@code AUTHZ_NOT_EVALUATED / actor_group_set_stale} -- never a stale
     * {@code PERMITTED}.
     *
     * <p>Previously disabled because the re-validation adapter had no way to
     * trust a synthetic CA; since 7ca608a5 it reads a server-owned
     * {@code DirectoryProfile} whose PEM trust material this harness
     * generates.</p>
     */
    @Test
    void directoryUnreachableRevalidationLeavesActorAuthzStateStale() throws SQLException {
        try (TestLdapDirectory directory = TestLdapDirectory.start();
                Ui2PostgresFixture own = Ui2PostgresFixture.createAndMigrate("identity_revalidation")) {
            String daveDn = directory.addPerson("dave", "test-pw-dave-1");
            String operatorsDn = directory.addGroup("operators", daveDn);
            LdapOperatorBindPort.OperatorBindOutcome outcome = bind(directory, "dave", "test-pw-dave-1");

            Directory db = new Directory(own);
            Instant now = Instant.now();
            db.openSession(outcome.actorFingerprint(), now);
            db.persistProof(outcome, directory);
            db.bindGroup(RoleToken.OPERATOR, operatorsDn);
            assertEquals(AuthzOutcome.PERMITTED, db.evaluator().evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.OPERATOR), now).outcome(), "sanity: the fresh login-time proof permits");
            Timestamp resolvedAtLogin = resolvedAt(own, outcome.actorFingerprint());

            DirectoryAuthorizationRefresh refresh = new DirectoryAuthorizationRefresh(directory.revalidationAdapter(),
                    db.bindings, db.cipher);
            directory.stopListening();
            assertFalse(refresh.refresh(outcome.actorFingerprint(), Instant.now()),
                    "an unreachable directory must refresh nothing");
            assertEquals(resolvedAtLogin, resolvedAt(own, outcome.actorFingerprint()),
                    "the persisted resolution must be left exactly as it was -- not refreshed, not deleted");

            Instant afterExpiry = outcome.observation().validUntil().plusSeconds(1);
            RbacEvaluator.Decision stale = db.evaluator().evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.OPERATOR), afterExpiry);
            assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, stale.outcome(),
                    "an expired resolution must never evaluate PERMITTED");
            assertEquals(RbacEvaluator.REASON_ACTOR_GROUP_SET_STALE, stale.reasonCode().orElse(null));
        }
    }

    // -----------------------------------------------------------------
    // Directory-backed test helpers
    // -----------------------------------------------------------------

    /** A real LDAPS bind through the production adapter; the synthetic identity must be admitted. */
    private static LdapOperatorBindPort.OperatorBindOutcome bind(TestLdapDirectory directory, String user,
            String password) {
        Result<LdapOperatorBindPort.OperatorBindOutcome> result =
                directory.operatorBindAdapter().bind(user, password.toCharArray());
        assertTrue(result instanceof Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>,
                "the seeded synthetic identity must bind successfully");
        return ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) result).value();
    }

    private static Timestamp resolvedAt(Ui2PostgresFixture database, String actor) throws SQLException {
        try (Connection app = database.appConnection();
                java.sql.PreparedStatement statement = app.prepareStatement(
                        "SELECT resolved_at FROM actor_authz_state WHERE actor_fingerprint = ?")) {
            statement.setString(1, actor);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "the actor's directory proof row must exist");
                return rows.getTimestamp(1);
            }
        }
    }

    /** The production repositories over one database, with a synthetic typed-directory cipher. */
    private static final class Directory {
        final RoleBindingRepository bindings;
        final ActorAuthzStateRepository actors;
        final SessionRepository sessions;
        final LocalCredentialsRepository locals;
        final GroupReferenceCipher cipher = testCipher();

        Directory(Ui2PostgresFixture database) {
            TransactionBoundary boundary = new JooqTransactionBoundary(
                    DSL.using(database.appDataSource(), SQLDialect.POSTGRES));
            bindings = new JooqRoleBindingRepository(boundary);
            actors = new JooqActorAuthzStateRepository(boundary);
            sessions = new JooqSessionRepository(boundary);
            locals = new com.securityexpert.nexus.ui2.persistence.identity.JooqLocalCredentialsRepository(boundary);
        }

        RbacEvaluator evaluator() {
            return new RbacEvaluator(bindings, actors, cipher);
        }

        String openSession(String actor, Instant now) {
            return sessions.createActive(OpaqueId.random().value(), actor, "harness-csrf", now,
                    java.time.Duration.ofHours(1), java.time.Duration.ofDays(1), "harness.session").sessionId();
        }

        /** Persists the bind's observation exactly as DirectoryAuthorizationRefresh/LoginFlow do. */
        void persistProof(LdapOperatorBindPort.OperatorBindOutcome outcome, TestLdapDirectory directory) {
            DirectoryObservation observation = outcome.observation();
            assertEquals(TestLdapDirectory.PROFILE_ID, observation.profileId());
            assertTrue(observation.publication().ifCurrent(() -> actors.upsertDirectory(new ActorAuthzStateRecord(
                    outcome.actorFingerprint(), observation.groupReferences(), observation.resolvedAt(),
                    observation.validUntil(), observation.profileId(),
                    cipher.encryptDirectory(observation.principalReference(), observation.profileId(),
                            DirectoryBindingKind.DIRECTORY_PRINCIPAL), cipher.keyId()))),
                    "the bind's trust snapshot must still be current");
        }

        /** A typed DIRECTORY_GROUP binding of {@code token} to {@code groupDn}; returns its id. */
        String bindGroup(String token, String groupDn) {
            String id = OpaqueId.random().value();
            bindings.createDirectory(new RoleBindingRecord(id, token,
                    cipher.encryptDirectory(groupDn, TestLdapDirectory.PROFILE_ID, DirectoryBindingKind.DIRECTORY_GROUP),
                    cipher.keyId(), "actor-rbac-setup", Instant.now(), Optional.empty(), Optional.empty(),
                    DirectoryBindingKind.DIRECTORY_GROUP, TestLdapDirectory.PROFILE_ID), "harness.rbac.setup");
            return id;
        }
    }

    /** A fresh, random AES-256 key with a key id -- this harness's own synthetic key, never a real secret. */
    private static GroupReferenceCipher testCipher() {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(keyBytes), "test-key");
    }
}
