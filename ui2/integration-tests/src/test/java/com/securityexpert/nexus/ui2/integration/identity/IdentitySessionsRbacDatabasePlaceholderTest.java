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
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import javax.net.SocketFactory;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.securityexpert.nexus.ui2.identity.ldap.UnboundIdOperatorBindAdapter;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.PrincipalFingerprint;
import com.securityexpert.nexus.ui2.platform.Result;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.LoginController;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.security.RoleBindingAdminService;

import com.securityexpert.nexus.ui2.integration.support.LdapAdapterTestAccess;
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
 * <p><b>Directory-dependent tests.</b> The original reason these stayed
 * disabled ("requires a live directory server ... and a live container
 * runtime") was investigated and found to be wrong for four of the five:
 * the repository already depends on the UnboundID LDAP SDK
 * ({@code ui2/gradle/libs.versions.toml}, consumed by {@code ldap-adapter}),
 * and that SDK ships {@code com.unboundid.ldap.listener.InMemoryDirectoryServer}
 * — a real, in-process LDAP server that needs no container, accepts a real
 * TCP connection, and speaks the real LDAP wire protocol
 * ({@link TestLdapDirectory}). Tests 7, 9, 13 and 14 below drive
 * {@link UnboundIdOperatorBindAdapter} against it over a real socket, through
 * the exact plain-socket seam ({@code forTestDirectory}) the adapter's own
 * unit tests already use — reached here by reflection
 * ({@link LdapAdapterTestAccess}) because that seam is package-private in a
 * different module, not because this movement is stubbing anything (see that
 * class's Javadoc). Test 15 (re-validation) stays {@code @Disabled}: see its
 * own Javadoc for the one genuine blocker found.</p>
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
     * Contract §8 test 7 [directory]. {@code RbacEvaluatorTest.}
     * {@code unmappedIdentityNeverReceivesPermittedForAnyRoleGatedAction}
     * proves the decision logic against an already-resolved, hand-built
     * group set; this proves the end-to-end path: a real
     * {@code SimpleBindRequest} against a synthetic identity that matches
     * no configured group, through {@link UnboundIdOperatorBindAdapter}'s
     * real socket and real LDAP search, persisted as {@code
     * actor_authz_state}, then evaluated by the real {@link RbacEvaluator} —
     * never {@code PERMITTED} for a token with an active binding the actor
     * does not hold, and never a default/fallback token.
     */
    @Test
    void unmappedIdentityReceivesNoRoleAgainstARealDirectoryBind() throws SQLException {
        try (TestLdapDirectory directory = TestLdapDirectory.start();
                Connection app = fixture.appConnection()) {
            String personDn = directory.addPerson("unmapped-user", "test-pw-unmapped-1");
            // A group the unmapped identity is deliberately NOT a member of.
            directory.addGroup("role-operator-group");

            UnboundIdOperatorBindAdapter adapter = plainAdapter(directory);
            Result<LdapOperatorBindPort.OperatorBindOutcome> bindResult =
                    adapter.bind("unmapped-user", "test-pw-unmapped-1".toCharArray());
            assertTrue(bindResult instanceof Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>,
                    "the seeded synthetic identity must bind successfully");
            LdapOperatorBindPort.OperatorBindOutcome outcome =
                    ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) bindResult).value();
            assertEquals(PrincipalFingerprint.of(personDn), outcome.actorFingerprint());
            assertTrue(outcome.groupReferences().isEmpty(), "the unmapped identity must resolve to no groups");

            DSLContext dsl = dslFor(app);
            TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
            ActorAuthzStateRepository actorAuthzStateRepository = new JooqActorAuthzStateRepository(transactionBoundary);
            RoleBindingRepository roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
            GroupReferenceCipher cipher = testCipher();

            Instant now = Instant.now();
            actorAuthzStateRepository.upsert(outcome.actorFingerprint(), outcome.groupReferences(), now,
                    now.plus(15, ChronoUnit.MINUTES));
            // role:operator is bound to a real group -- the unmapped
            // identity's resolved group set does not contain it.
            roleBindingRepository.create(OpaqueId.random().value(), RoleToken.OPERATOR.token(),
                    cipher.encrypt("cn=role-operator-group,ou=groups,dc=example,dc=com"), "test-key",
                    "actor-rbac-setup", "harness.rbac.setup");

            RbacEvaluator evaluator = new RbacEvaluator(roleBindingRepository, actorAuthzStateRepository, cipher);
            RbacEvaluator.Decision decision = evaluator.evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.OPERATOR), now);
            assertEquals(AuthzOutcome.DENIED, decision.outcome(),
                    "a role-gated action must never evaluate PERMITTED for an identity matching no role_bindings row");
            assertEquals(RbacEvaluator.REASON_ACTOR_NOT_IN_REQUIRED_GROUP, decision.reasonCode().orElse(null));

            // A token with zero active bindings at all: role_token_unbound,
            // still never PERMITTED, never a default token.
            RbacEvaluator.Decision unboundDecision = evaluator.evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.SECURITY_ADMIN), now);
            assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED,
                    unboundDecision.outcome());
            assertEquals(RbacEvaluator.REASON_ROLE_TOKEN_UNBOUND, unboundDecision.reasonCode().orElse(null));

            // Un-role-gated actions still proceed, but under NO_APPLICABLE_AUTHORITY, never PERMITTED.
            RbacEvaluator.Decision noTokenDecision = evaluator.evaluate(outcome.actorFingerprint(), Optional.empty(), now);
            assertEquals(AuthzOutcome.NO_APPLICABLE_AUTHORITY,
                    noTokenDecision.outcome());
        }
    }

    /**
     * Contract §8 test 9 [directory]. {@code RbacEvaluatorTest.}
     * {@code boundButNotAMemberIsDeniedWithActorNotInRequiredGroup} proves
     * the decision logic against a hand-built group set; this proves the
     * same outcome end-to-end: a real bind resolves the actor's real (but
     * insufficient) group memberships, and the real {@link RbacEvaluator}
     * refuses {@code DENIED / actor_not_in_required_group} against a token
     * that is genuinely bound to a different, real group.
     */
    @Test
    void boundButNotAMemberRefusalShapeAgainstARealDirectoryBind() throws SQLException {
        try (TestLdapDirectory directory = TestLdapDirectory.start();
                Connection app = fixture.appConnection()) {
            String bobDn = directory.addPerson("bob", "test-pw-bob-1");
            String memberGroupDn = directory.addGroup("group-bob-is-in", bobDn);
            directory.addGroup("group-required-for-backup-admin"); // bob is NOT a member of this one

            UnboundIdOperatorBindAdapter adapter = plainAdapter(directory);
            Result<LdapOperatorBindPort.OperatorBindOutcome> bindResult = adapter.bind("bob", "test-pw-bob-1".toCharArray());
            LdapOperatorBindPort.OperatorBindOutcome outcome =
                    ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) bindResult).value();
            assertEquals(Set.of(memberGroupDn), outcome.groupReferences(), "bob must resolve to exactly the group he is a real member of");

            DSLContext dsl = dslFor(app);
            TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
            ActorAuthzStateRepository actorAuthzStateRepository = new JooqActorAuthzStateRepository(transactionBoundary);
            RoleBindingRepository roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
            GroupReferenceCipher cipher = testCipher();

            Instant now = Instant.now();
            actorAuthzStateRepository.upsert(outcome.actorFingerprint(), outcome.groupReferences(), now,
                    now.plus(15, ChronoUnit.MINUTES));
            roleBindingRepository.create(OpaqueId.random().value(), RoleToken.BACKUP_ADMIN.token(),
                    cipher.encrypt("cn=group-required-for-backup-admin,ou=groups,dc=example,dc=com"), "test-key",
                    "actor-rbac-setup", "harness.rbac.setup");

            RbacEvaluator evaluator = new RbacEvaluator(roleBindingRepository, actorAuthzStateRepository, cipher);
            RbacEvaluator.Decision decision = evaluator.evaluate(outcome.actorFingerprint(),
                    Optional.of(RoleToken.BACKUP_ADMIN), now);
            assertEquals(AuthzOutcome.DENIED, decision.outcome(),
                    "bound-but-not-a-member must refuse DENIED, never PERMITTED");
            assertEquals(RbacEvaluator.REASON_ACTOR_NOT_IN_REQUIRED_GROUP, decision.reasonCode().orElse(null));
            assertTrue(decision.bindingId().isEmpty());
        }
    }

    /**
     * Contract §8 test 13 [directory]. {@code RoleBindingAdminServiceTest.}
     * {@code selfGrantIsRefusedAndNoRowIsCreated} proves the decision logic
     * against a hand-built admin group set; this proves the same outcome
     * end-to-end: a real bind resolves the acting admin's real group
     * membership, and {@link RoleBindingAdminService} refuses the self-grant
     * against that real, persisted {@code actor_authz_state} row, leaving no
     * {@code role_bindings} row behind.
     */
    @Test
    void selfGrantRefusedAgainstARealDirectoryBind() throws SQLException {
        try (TestLdapDirectory directory = TestLdapDirectory.start();
                Connection app = fixture.appConnection()) {
            String adminDn = directory.addPerson("security-admin-1", "test-pw-admin-1");
            String ownGroupDn = directory.addGroup("group-admin-already-holds", adminDn);

            UnboundIdOperatorBindAdapter adapter = plainAdapter(directory);
            Result<LdapOperatorBindPort.OperatorBindOutcome> bindResult =
                    adapter.bind("security-admin-1", "test-pw-admin-1".toCharArray());
            LdapOperatorBindPort.OperatorBindOutcome outcome =
                    ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) bindResult).value();
            assertEquals(Set.of(ownGroupDn), outcome.groupReferences());

            DSLContext dsl = dslFor(app);
            TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
            ActorAuthzStateRepository actorAuthzStateRepository = new JooqActorAuthzStateRepository(transactionBoundary);
            RoleBindingRepository roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
            GroupReferenceCipher cipher = testCipher();

            Instant now = Instant.now();
            actorAuthzStateRepository.upsert(outcome.actorFingerprint(), outcome.groupReferences(), now,
                    now.plus(15, ChronoUnit.MINUTES));

            RoleBindingAdminService service =
                    new RoleBindingAdminService(roleBindingRepository, actorAuthzStateRepository, cipher);
            RoleBindingAdminService.Outcome result = service.create(outcome.actorFingerprint(),
                    RoleToken.SECURITY_ADMIN.token(), "cn=group-admin-already-holds,ou=groups,dc=example,dc=com",
                    "test-key", now);

            assertTrue(result instanceof RoleBindingAdminService.Outcome.SelfGrantRefused,
                    "an admin whose own real, directory-resolved group set already contains the group being "
                            + "bound must be refused SELF_GRANT_REFUSED");
            assertFalse(roleBindingRepository.hasAnyActiveBinding(RoleToken.SECURITY_ADMIN.token()),
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
        UnboundIdOperatorBindAdapter adapter = plainAdapter(directory);

        // Prove the directory really was reachable a moment ago -- this is
        // not a host that was never up.
        Result<LdapOperatorBindPort.OperatorBindOutcome> priorBind = adapter.bind("carol", "test-pw-carol-1".toCharArray());
        assertTrue(priorBind instanceof Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>);

        directory.stopListening();

        // LoginController.login only ever touches loginFlow after a
        // successful bind (see its Javadoc above); a failed-closed bind
        // must never reach it, so no LoginFlow/SessionRepository
        // implementation is needed here at all -- null is deliberate, not
        // a shortcut around the real code path.
        LoginController controller = new LoginController(adapter, null);

        ResponseEntity<java.util.Map<String, Object>> response = controller.login(
                new LoginController.LoginRequest("carol", "test-pw-carol-1".toCharArray()),
                (HttpServletResponse) null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "a directory that goes unreachable between two calls must fail closed 503, never 401");
        assertEquals("DIRECTORY_UNAVAILABLE", response.getBody().get("error"));
    }

    /**
     * Contract §8 test 15 [directory]. {@code UnboundIdRevalidationAdapterTest.}
     * {@code revalidateRefusesWithoutOpeningAConnectionWhileDisabled} proves
     * the disabled-adapter half without a directory.
     *
     * <p><b>Stays disabled — genuine blocker, not a missing carrier.</b>
     * Unlike {@link UnboundIdOperatorBindAdapter}, {@code
     * UnboundIdRevalidationAdapter} has no plain-socket test seam at all: its
     * {@code poolOrOpen()} unconditionally builds a TLS {@code SSLUtil} with
     * {@code TrustStoreTrustManager(caBundlePath.toString())} — the same
     * single-argument constructor {@code UnboundIdOperatorBindAdapter.create}
     * uses, which never supplies a trust-store password. Measured directly
     * against this JDK (21.0.10): {@code KeyStore.load(inputStream, null)}
     * returns <b>zero</b> entries for both a JKS and a PKCS12 trust store
     * built by {@code keytool}, for a self-signed certificate generated for
     * this very purpose — confirmed with a standalone reproduction outside
     * this test file before writing this note. A trust store that requires no
     * password to enumerate its certificates (so that a null-PIN {@code
     * TrustStoreTrustManager} can read it) is not something this harness can
     * fabricate without either changing {@code ldap-adapter} production code
     * (outside this movement's edit scope) or relying on a CA the JVM's
     * platform default trust store already recognizes (defeats the point of
     * a synthetic test identity). This is a real, reportable finding about
     * {@code UnboundIdOperatorBindAdapter}/{@code UnboundIdRevalidationAdapter}'s
     * TLS trust wiring, not an environment gap -- see SESSION CLOSE.
     */
    @Test
    @Disabled("UnboundIdRevalidationAdapter is TLS-only with no test seam, and this JDK's KeyStore.load "
            + "returns zero entries for a null-password trust store (JKS and PKCS12 both measured) -- a "
            + "genuine adapter/JDK trust-store limitation, not a missing test-LDAP carrier; see this "
            + "method's Javadoc and SESSION CLOSE")
    void directoryUnreachableRevalidationLeavesActorAuthzStateStale() {
        // Intentionally left unimplemented -- see the @Disabled reason and Javadoc above.
    }

    // -----------------------------------------------------------------
    // Directory-backed test helpers
    // -----------------------------------------------------------------

    /** The plain-socket adapter under test, reached via the same seam its own unit tests use (see {@link LdapAdapterTestAccess}). */
    private static UnboundIdOperatorBindAdapter plainAdapter(TestLdapDirectory directory) {
        return LdapAdapterTestAccess.forTestDirectory(directory.host(), directory.port(),
                SocketFactory.getDefault(), TestLdapDirectory.BIND_DN_TEMPLATE, TestLdapDirectory.GROUPS_BASE_DN);
    }

    private static DSLContext dslFor(Connection connection) {
        return DSL.using(connection, SQLDialect.POSTGRES);
    }

    /** A fresh, random AES-256 key -- this harness's own synthetic key, never a real secret (C3 §4.2). */
    private static GroupReferenceCipher testCipher() {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(keyBytes));
    }
}
