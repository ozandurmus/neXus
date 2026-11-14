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

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;

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

            race(admin -> {
                bindings.revoke("binding-shared", admin, "accept.binding_revoke");
                return admin;
            });

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
