package com.securityexpert.nexus.ui2.integration.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChunkGarbageCollector;

/** V262 / PO 2026-11-23: unreferenced chunk generations go after the safety age; referenced ones never do. */
class PolicyChunkGarbageCollectorTest {

    private static Ui2PostgresFixture fixture;
    private static TransactionBoundary tx;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.create("policy_chunk_gc");
        fixture.runFlyway();
        tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE).insertRequestedIfAbsentForRun("job-gc", "key-gc",
                "cp_policy_collect", "run-gc", "read", "cp_policy_collect", "synthetic-actor", "policy_collect").orElseThrow();
    }

    @AfterAll
    static void close() {
        if (fixture != null) fixture.close();
    }

    @BeforeEach
    void empty() {
        sql("delete from policy_unit_checkpoint where job_id = 'job-gc'");
        sql("delete from cp_policy_domain_run where job_id = 'job-gc'");
        sql("delete from cp_policy_object_inventory where source_id = 'src-gc'");
        sql("delete from cp_policy_json_chunk where source_id = 'src-gc'");
        sql("update jobs set state = 'REQUESTED' where job_id = 'job-gc'");
    }

    private static PolicyChunkGarbageCollector collector(int batchRows, int maxBatches) {
        return new PolicyChunkGarbageCollector(tx, new PolicyChunkGarbageCollector.Settings(
                PolicyChunkGarbageCollector.MIN_SAFETY_AGE, batchRows, maxBatches, Duration.ZERO, Duration.ofSeconds(30)));
    }

    @Test
    void referencedGenerationsAreKeptUnreferencedOldOnesDeletedYoungOnesKept() {
        chunks("g-inventory", 3);
        chunks("g-domain", 3);
        chunks("g-checkpoint", 3);
        chunks("g-old", 4);
        sql("insert into cp_policy_object_inventory(source_id, domain_ref, object_type, collected_at, snapshot, chunk_generation, chunk_count) "
                + "values ('src-gc','dom-gc','hosts',now(),'{\"objects\":[]}','g-inventory',3)");
        sql("insert into cp_policy_domain_run(job_id, source_id, domain_ref, status, complete, signal, chunk_generation, chunk_count) "
                + "values ('job-gc','src-gc','dom-gc','COLLECTED',true,'{}','g-domain',3)");
        sql("insert into policy_unit_checkpoint(job_id, unit_ref, source_version, snapshot, complete, chunk_generation, chunk_count) "
                + "values ('job-gc','unit-gc','v1','{}',true,'g-checkpoint',3)");

        var first = collector(5_000, 10).runOnce();
        assertFalse(first.skipped());
        assertEquals(1, first.stamped(), "only the unreferenced generation is stamped, on one chunk");
        assertEquals(0, first.deleted(), "a generation stamped just now is younger than the safety age");
        assertEquals(List.of(0), stampedOrdinals("g-old"));

        backdate("g-old", 25);
        // A young unreferenced generation (an in-flight write whose manifest is not yet published) appears now.
        chunks("g-young", 2);
        var second = collector(5_000, 10).runOnce();
        assertEquals(4, second.deleted());
        assertEquals(0, count("g-old"));
        assertEquals(2, count("g-young"));
        assertEquals(List.of(0), stampedOrdinals("g-young"));
        for (String kept : List.of("g-inventory", "g-domain", "g-checkpoint")) assertEquals(3, count(kept), kept);
    }

    @Test
    void aReferencedGenerationIsNeverDeletedEvenWithAnOldStamp() {
        chunks("g-published-late", 3);
        sql("update cp_policy_json_chunk set unreferenced_since = now() - interval '48 hours' "
                + "where generation = 'g-published-late' and ordinal = 0");
        sql("insert into cp_policy_object_inventory(source_id, domain_ref, object_type, collected_at, snapshot, chunk_generation, chunk_count) "
                + "values ('src-gc','dom-gc','hosts',now(),'{\"objects\":[]}','g-published-late',3)");
        // The DELETE statement on its own (no clearing pass first) re-checks every manifest table.
        int deleted = new AuditedTransactionBoundary(tx).inTransaction("system:worker", "policy_chunk_gc_delete",
                db -> db.execute(PolicyChunkGarbageCollector.DELETE, 86_400L, 5_000));
        assertEquals(0, deleted);
        assertEquals(3, count("g-published-late"));
        var run = collector(5_000, 10).runOnce();
        assertEquals(1, run.cleared(), "the stale stamp of a generation a manifest names again is cleared");
        assertEquals(0, run.deleted());
        assertEquals(List.of(), stampedOrdinals("g-published-late"));
    }

    @Test
    void oneRunDeletesAtMostBatchRowsTimesMaxBatchesHighestOrdinalFirst() {
        chunks("g-big", 12);
        collector(5, 1).runOnce();
        backdate("g-big", 25);
        var run = collector(5, 1).runOnce();
        assertEquals(1, run.batches());
        assertEquals(5, run.deleted());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), ordinals("g-big"), "highest ordinals go first; the stamped chunk stays last");
        var next = collector(5, 2).runOnce();
        assertEquals(2, next.batches());
        assertEquals(7, next.deleted(), "a short batch ends the run");
        assertEquals(0, count("g-big"));
    }

    @Test
    void aRunningPolicyCollectionSkipsTheRun() {
        chunks("g-skip", 2);
        sql("update jobs set state = 'EXECUTING', lease_epoch = 1, lease_expires_at = now() + interval '1 hour' where job_id = 'job-gc'");
        var run = collector(5_000, 10).runOnce();
        assertTrue(run.skipped());
        assertEquals(List.of(), stampedOrdinals("g-skip"));
    }

    // ------------------------------------------------------------------ helpers

    private static void sql(String statement) {
        new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", "policy_collect", db -> db.execute(statement));
    }

    private static void chunks(String generation, int count) {
        for (int ordinal = 0; ordinal < count; ordinal++) {
            int page = ordinal;
            new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", "policy_collect_chunk", db -> db.execute(
                    "insert into cp_policy_json_chunk(generation, source_id, domain_ref, object_type, kind, ordinal, payload, plain_bytes) "
                            + "values ({0},'src-gc','dom-gc','hosts','INVENTORY',{1},decode('00','hex'),1)", generation, page));
        }
    }

    private static void backdate(String generation, int hours) {
        sql("update cp_policy_json_chunk set unreferenced_since = now() - interval '" + hours + " hours' "
                + "where generation = '" + generation + "' and unreferenced_since is not null");
    }

    private static int count(String generation) {
        return tx.inTransaction(db -> db.fetchOne("select count(*) from cp_policy_json_chunk where generation = {0}", generation)
                .get(0, Integer.class));
    }

    private static List<Integer> ordinals(String generation) {
        return new ArrayList<>(tx.inTransaction(db -> db.fetch(
                "select ordinal from cp_policy_json_chunk where generation = {0} order by ordinal", generation).getValues(0, Integer.class)));
    }

    private static List<Integer> stampedOrdinals(String generation) {
        return new ArrayList<>(tx.inTransaction(db -> db.fetch("select ordinal from cp_policy_json_chunk where generation = {0} "
                + "and unreferenced_since is not null order by ordinal", generation).getValues(0, Integer.class)));
    }
}
