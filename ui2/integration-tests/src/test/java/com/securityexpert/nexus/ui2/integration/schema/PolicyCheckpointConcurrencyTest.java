package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.ExecuteContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;

class PolicyCheckpointConcurrencyTest {
    @Test @Timeout(30)
    void heartbeatAndEpochReclaimProceedWhileCheckpointInsertWaitsOnSnapshotLock() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_checkpoint_lock")) {
            fixture.runFlyway();
            var source = fixture.appDataSource();
            var tx = new JooqTransactionBoundary(DSL.using(source, SQLDialect.POSTGRES));
            new JooqJobRecordDao(tx).insertRequestedIfAbsentForRun("job-1", "job-1", "cp_policy_collect",
                "synthetic-run", "read", "policy_collect", "synthetic-actor", "policy_collect").orElseThrow();
            var leases = new JooqJobLeaseDao(tx);
            long epoch = leases.claimNext("synthetic-worker", List.of("cp_policy_collect"), Duration.ofMinutes(10)).orElseThrow().leaseEpoch();
            assertTrue(leases.transitionState("job-1", epoch, "CLAIMED", "EXECUTING", "synthetic-actor", "policy_collect"));
            new PolicySnapshotRepository(tx).save(new PolicySnapshotRepository.Stored("policy-1", "2026-10-01T00:00:00Z",
                "{}", "{\"failures\":[{}]}"), "synthetic-actor", "policy_collect_checkpoint");
            var started = new CountDownLatch(1);
            var dsl = DSL.using(source, SQLDialect.POSTGRES);
            dsl.configuration().set(new DefaultExecuteListener() {
                @Override public void executeStart(ExecuteContext context) {
                    if (context.sql().startsWith("insert into policy_snapshot")) started.countDown();
                }
            });
            var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(dsl));
            var snapshot = new PolicySnapshotRepository.Stored("policy-1", "2026-10-02T00:00:00Z", "{}", "{\"failures\":[{}]}");
            var pool = Executors.newFixedThreadPool(2);
            try (var blocker = fixture.appConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate("update policy_snapshot set metadata='{}'::jsonb where policy_id='policy-1'");
                }
                Future<Boolean> checkpoint = pool.submit(() -> repository.checkpoint("job-1", epoch, snapshot, "synthetic-actor"));
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertFalse(checkpoint.isDone(), "Snapshot row lock must keep the insert in progress");
                    Future<Boolean> heartbeat = pool.submit(() -> leases.heartbeat("job-1", epoch, Duration.ofMinutes(10)));
                    assertTrue(heartbeat.get(2, TimeUnit.SECONDS), "Heartbeat must not wait for the snapshot transaction");
                    new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", "job_reclaim", db ->
                        db.execute("update jobs set lease_epoch=lease_epoch+1 where job_id={0}", "job-1"));
                } finally {
                    blocker.rollback(); // Always release the latch-controlled long write, even on assertion failure.
                }
                assertTrue(checkpoint.get(5, TimeUnit.SECONDS));
                assertFalse(repository.publish("job-1", epoch, List.of(snapshot), "synthetic-actor"));
                assertFalse(repository.publishWithWarnings("job-1", epoch, List.of(snapshot), "synthetic-actor", "PARTIAL_SNAPSHOT"));
                assertFalse(repository.checkpoint("job-1", epoch, snapshot, "synthetic-actor"));
                assertEquals("EXECUTING", tx.inTransaction(db -> db.fetchOne("select state from jobs where job_id={0}", "job-1").get(0, String.class)));
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
    @Test void chunkedSnapshotIsAtomicUnderAppRoleAndStagingDisappearsAfterCommitAndRollback() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_chunked_write")) {
            fixture.runFlyway();
            try (var connection = fixture.appConnection()) {
                var db = DSL.using(connection, SQLDialect.POSTGRES);
                var tx = new JooqTransactionBoundary(db);
                var repository = new PolicySnapshotRepository(tx);
                String padding = "synthetic-é😀".repeat(20000);
                String snapshot = "{\"sections\":[],\"objects\":{},\"failures\":[],\"padding\":\"" + padding + "\"}";
                repository.save(new PolicySnapshotRepository.Stored("policy-1", "2026-10-05T00:00:00Z", "{}", snapshot),
                    "synthetic-actor", "policy_collect_checkpoint");
                assertEquals(padding, db.fetchOne("select snapshot->>'padding' from policy_snapshot where policy_id='policy-1'").get(0, String.class));
                assertEquals(1, db.fetchOne("select count(*)::int from policy_rule_history_baseline where policy_id='policy-1'").get(0, Integer.class));
                assertEquals(0, db.fetchOne("select count(*)::int from pg_class where relnamespace=pg_my_temp_schema() and relname like 'policy_json_%'").get(0, Integer.class));
                assertThrows(PolicyDatabaseFailure.class, () -> repository.save(new PolicySnapshotRepository.Stored(
                    "policy-1", "2026-10-05T00:01:00Z", "{}", snapshot.substring(0, snapshot.length() - 1)),
                    "synthetic-actor", "policy_collect_checkpoint"));
                assertEquals(padding, db.fetchOne("select snapshot->>'padding' from policy_snapshot where policy_id='policy-1'").get(0, String.class));
                assertEquals(0, db.fetchOne("select count(*)::int from pg_class where relnamespace=pg_my_temp_schema() and relname like 'policy_json_%'").get(0, Integer.class));
            }
        }
    }

}
