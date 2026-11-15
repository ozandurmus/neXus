package com.securityexpert.nexus.ui2.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.time.Instant;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;

/**
 * Acceptance scenario B1-10 (workflow section 5): three admins act on the same object at the same moment, against a
 * real PostgreSQL. Each scenario releases all three from one start gate so the writes truly overlap, then proves
 * from SQL: no lost update, a clear answer for the loser(s), and one audit row per effective write attributed to
 * the actor that made it. Actors and ids are synthetic.
 */
class ConcurrentAdminsAcceptanceTest {

    private static final List<String> ADMINS = List.of("synthetic-admin-one", "synthetic-admin-two", "synthetic-admin-three");

    @Test
    @Timeout(60)
    void threeAdminsSubmitTheSameRunAtOnce_oneJobAnswersTheOthersClearly_andOnlyTheWinnerIsAudited() throws Exception {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("accept_same_run")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var jobs = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
            String key = "synthetic-run-now-key";

            List<Optional<String>> answers = race(admin -> jobs.insertRequestedIfAbsentForRun(
                    "job-" + admin, key, "cp_policy_collect", "synthetic-run", "read", "cp_policy_collect",
                    admin, "accept.run_now"));

            List<Optional<String>> winners = answers.stream().filter(Optional::isPresent).toList();
            assertEquals(1, winners.size(), "exactly one admin creates the job; the others get an empty (already exists) answer");
            String jobId = winners.get(0).orElseThrow();
            assertEquals(Optional.of(jobId), jobs.findJobIdByIdempotencyKey(key),
                    "the losers can resolve the existing job from the same key");
            String winner = jobId.substring("job-".length());

            try (Connection app = fixture.appConnection()) {
                assertEquals(1L, count(app, "select count(*) from jobs where idempotency_key = '" + key + "'"));
                assertEquals(winner, one(app, "select submitted_by_actor_fingerprint from jobs where job_id = '" + jobId + "'"));
                Set<String> auditedActors = new HashSet<>(column(app,
                        "select distinct actor_fingerprint from audit_log where action_id = 'accept.run_now'"));
                assertEquals(Set.of(winner), auditedActors,
                        "only the effective write is audited, under the actor that made it; no phantom rows for losers");
                assertTrue(count(app, "select count(*) from audit_log where action_id = 'accept.run_now' and table_name = 'jobs'") == 1L);
            }
        }
    }

    @Test
    @Timeout(60)
    void threeAdminsRevokeTheSameBindingAtOnce_itIsRevokedOnceByOneNamedActor_withOneAuditRow() throws Exception {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("accept_same_revoke")) {
            var bindings = new JooqRoleBindingRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            bindings.create("binding-shared", "role:security_admin", new byte[] {1, 2, 3}, "key-1",
                    "synthetic-creator", "accept.binding_create");

            List<Boolean> changed = race(admin -> bindings.revoke("binding-shared", admin, "accept.binding_revoke"));
            assertEquals(1, changed.stream().filter(c -> c).count(),
                    "exactly one revoker changed the row; the two losers are told false (ALREADY_REVOKED upstream)");

            var after = bindings.find("binding-shared").orElseThrow();
            assertTrue(after.revokedAt().isPresent(), "binding must end revoked");
            String revoker = after.revokedByActorFingerprint().orElseThrow();
            assertTrue(ADMINS.contains(revoker));
            try (Connection app = fixture.appConnection()) {
                assertEquals(1L, count(app, "select count(*) from role_bindings where binding_id = 'binding-shared'"));
                List<String> revokeAuditActors = column(app,
                        "select actor_fingerprint from audit_log where action_id = 'accept.binding_revoke'");
                assertEquals(List.of(revoker), revokeAuditActors,
                        "exactly one revoke audit row, and its actor is the one recorded on the binding (no lost or doubled update)");
            }
        }
    }

    @Test
    @Timeout(60)
    void threeAdminsCreateBindingsAtOnce_everyWriteLandsAndEachAuditRowNamesItsOwnActor() throws Exception {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("accept_parallel_create")) {
            var bindings = new JooqRoleBindingRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));

            race(admin -> bindings.create("binding-" + admin, "role:security_admin", new byte[] {9}, "key-1",
                    admin, "accept.binding_create_parallel"));

            try (Connection app = fixture.appConnection()) {
                assertEquals(3L, count(app, "select count(*) from role_bindings where binding_id like 'binding-synthetic-admin-%'"));
                for (String admin : ADMINS) {
                    assertEquals(List.of("role_bindings/binding-" + admin), column(app,
                            "select table_name || '/' || row_pk from audit_log where action_id = 'accept.binding_create_parallel'"
                                    + " and actor_fingerprint = '" + admin + "'"),
                            "each admin has exactly one audit row, for the row that admin created");
                }
            }
        }
    }

    @Test
    @Timeout(90)
    void threeAdminsContendForOneIdentitySession_oneTakeoverWins_loserGetsRefused_noiseStaysOutOfTheCorrelation() throws Exception {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("accept_takeover")) {
            var sessions = new JooqSessionRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            var flow = new LoginFlow(sessions, Duration.ofMinutes(30), Duration.ofHours(8));
            String shared = "synthetic-shared-identity";
            String bystander = "synthetic-bystander-identity";
            Instant now = Instant.now();

            // Step 1: admin one logs in as the shared identity; the bystander admin holds an unrelated session.
            var s1 = (LoginFlow.LoginResult.NewSession) flow.login(shared, now);
            var noiseSession = (LoginFlow.LoginResult.NewSession) flow.login(bystander, now);

            // Step 2: admins two and three both try to log in as the shared identity -> both get a conflict token.
            List<LoginFlow.LoginResult> conflicts = race(admin -> admin.endsWith("one")
                    ? flow.login(bystander, Instant.now())   // bystander noise overlaps the conflicts
                    : flow.login(shared, Instant.now()));
            var tokens = conflicts.stream().filter(r -> r instanceof LoginFlow.LoginResult.Conflict c
                            && c.priorSession().sessionId().equals(s1.session().sessionId()))
                    .map(r -> ((LoginFlow.LoginResult.Conflict) r).conflictToken()).toList();
            assertEquals(2, tokens.size(), "both contenders see a 409-style conflict naming the prior session");

            // Step 3/4: both resolve with takeover at the same moment, while the bystander keeps writing.
            ExecutorService noise = Executors.newSingleThreadExecutor();
            AtomicBoolean stop = new AtomicBoolean();
            Future<?> noiseWork = noise.submit(() -> {
                while (!stop.get()) sessions.heartbeat(noiseSession.session().sessionId(), Instant.now(), Duration.ofMinutes(30));
            });
            List<LoginFlow.ResolveResult> results = new ArrayList<>();
            try {
                CountDownLatch go = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                List<Future<LoginFlow.ResolveResult>> futures = new ArrayList<>();
                for (String token : tokens) {
                    futures.add(pool.submit(() -> { go.await(); return flow.resolve(token, "takeover", Instant.now()); }));
                }
                go.countDown();
                for (var f : futures) results.add(f.get(30, TimeUnit.SECONDS));
                pool.shutdownNow();
            } finally {
                stop.set(true);
                noiseWork.get(10, TimeUnit.SECONDS);
                noise.shutdownNow();
            }
            var won = results.stream().filter(r -> r instanceof LoginFlow.ResolveResult.TakenOver).toList();
            var lost = results.stream().filter(r -> r instanceof LoginFlow.ResolveResult.Refused).toList();
            assertEquals(1, won.size(), "exactly one takeover wins");
            assertEquals(1, lost.size(), "the other gets a clear refusal, not an exception");
            var s2 = ((LoginFlow.ResolveResult.TakenOver) won.get(0)).session();

            try (Connection app = fixture.appConnection()) {
                assertEquals(1L, count(app, "select count(*) from sessions where actor_fingerprint = '" + shared + "' and state = 'ACTIVE'"));
                assertEquals("SUPERSEDED", one(app, "select state from sessions where session_id = '" + s1.session().sessionId() + "'"));
                assertEquals(s2.sessionId(), one(app, "select superseded_by_session_id from sessions where session_id = '" + s1.session().sessionId() + "'"));
                // Audit: every takeover row belongs to S1/S2 and to the shared identity; the bystander never appears in them.
                List<String> rows = column(app, "select row_pk || '|' || actor_fingerprint from audit_log where action_id = '"
                        + LoginFlow.ACTION_SESSION_LOGIN_TAKEOVER + "'");
                assertTrue(!rows.isEmpty());
                for (String row : rows) {
                    assertTrue(row.equals(s1.session().sessionId() + "|" + shared) || row.equals(s2.sessionId() + "|" + shared), row);
                }
                assertEquals(0L, count(app, "select count(*) from audit_log where actor_fingerprint = '" + bystander
                        + "' and action_id = '" + LoginFlow.ACTION_SESSION_LOGIN_TAKEOVER + "'"));
                // One transaction -> a single correlation value across the takeover rows (null counts as one value).
                assertEquals(1L, count(app, "select count(distinct coalesce(correlation_run_id, '')) from audit_log where action_id = '"
                        + LoginFlow.ACTION_SESSION_LOGIN_TAKEOVER + "'"));
            }

            // Step 5: the superseded session is no longer usable.
            assertTrue(!sessions.findBySessionId(s1.session().sessionId()).orElseThrow().isActive(Instant.now()));

            // Step 6/7: admin one tries again, is shown the conflict, refuses; the winner's session is untouched.
            var conflict = (LoginFlow.LoginResult.Conflict) flow.login(shared, Instant.now());
            assertEquals(s2.sessionId(), conflict.priorSession().sessionId());
            var refused = (LoginFlow.ResolveResult.Refused) flow.resolve(conflict.conflictToken(), "refuse", Instant.now());
            assertEquals("login_refused_active_session", refused.reasonCode());
            var after = sessions.findBySessionId(s2.sessionId()).orElseThrow();
            assertTrue(after.isActive(Instant.now()));
            assertEquals(s2.sessionId(), sessions.findActiveByActor(shared, Instant.now()).orElseThrow().sessionId());
        }
    }

    /** Runs the action once per admin, all released together; returns the answers in admin order. */
    private static <T> List<T> race(AdminAction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(ADMINS.size());
        try {
            CountDownLatch ready = new CountDownLatch(ADMINS.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (String admin : ADMINS) {
                Callable<T> call = () -> {
                    ready.countDown();
                    go.await();
                    return action.run(admin);
                };
                futures.add(pool.submit(call));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                answers.add(future.get(30, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface AdminAction<T> {
        T run(String admin) throws Exception;
    }

    private static long count(Connection c, String sql) throws SQLException {
        return Long.parseLong(one(c, sql));
    }

    private static String one(Connection c, String sql) throws SQLException {
        List<String> rows = column(c, sql);
        assertEquals(1, rows.size(), sql);
        return rows.get(0);
    }

    private static List<String> column(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) rows.add(r.getString(1));
        }
        return rows;
    }
}
