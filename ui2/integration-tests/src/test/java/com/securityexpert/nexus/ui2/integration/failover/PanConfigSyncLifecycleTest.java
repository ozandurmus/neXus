package com.securityexpert.nexus.ui2.integration.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;

/**
 * V178 against PostgreSQL (PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md; Argon review 2026-10-24 P0-1, P0-2, P2-3 item 6): the
 * open-record index, the append-only guard, the job-end trigger keeping the recorded reply, a cancel after the send that
 * changes nothing, the readiness re-run, and the settled release of a quarantined CONFIGURATION_WRITE endpoint lease
 * while its owner is alive. Synthetic identities only.
 */
class PanConfigSyncLifecycleTest {
    private static final String ADMIN = "synthetic-security-admin", CLUSTER = "CLS-" + "SYNC-" + 1, WORKER = "general-synthetic";

    @Test void oneSyncEndsWithItsReplyDespiteACancelReRunsReadinessAndReleasesItsLease() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("pan_config_sync_lifecycle")) {
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var failover = new JooqCpFailoverRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE);
            var syncs = new JooqPanConfigSyncRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE);
            List<String> members = pair(fixture);
            String active = members.get(0), passive = members.get(1);
            evidence(failover, active, passive);

            var first = asAdmin(() -> syncs.request(CLUSTER, active, passive, ADMIN));
            assertEquals("ADMITTED", first.code());
            assertEquals("CONFIG_SYNC_ALREADY_ACTIVE", asAdmin(() -> syncs.request(CLUSTER, active, passive, ADMIN)).code());
            assertEquals("RUN_ALREADY_ACTIVE", failover.requestReadiness(CLUSTER, null, ADMIN, active, "palo_alto").code(),
                "no readiness battery alongside an open sync");
            var record = syncs.find(first.runId()).orElseThrow();
            assertEquals("QUEUED", record.state());
            var job = mutate(fixture, dsl -> dsl.fetchOne("select job_type,action_class,window_bypass_role,target_device_id "
                + "from jobs where job_id={0}", record.jobId()));
            assertEquals("pan_failover_config_sync", job.get("job_type", String.class));
            assertEquals("configuration-write", job.get("action_class", String.class));
            assertEquals("security_admin", job.get("window_bypass_role", String.class));
            assertEquals(active, job.get("target_device_id", String.class));
            // The open-record index: a second open record on the cluster cannot exist, even written directly.
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> {
                dsl.execute("insert into jobs(job_id,job_type,capability_id,target_device_id,target_kind,"
                    + "submitted_by_actor_fingerprint,idempotency_key,action_class,state) values('synthetic-job-2',"
                    + "'pan_failover_config_sync','pan_failover_config_sync',{0},'device',{1},'synthetic-key-2','configuration-write',"
                    + "'REQUESTED')", active, ADMIN);
                return dsl.execute("insert into pan_config_sync(sync_id,cluster_ref,source_member_ref,target_member_ref,"
                    + "member_set_revision,readiness_run_id,initiated_by,initiator_role,requested_in_business_hours,job_id,state) "
                    + "select 'synthetic-sync-2',cluster_ref,source_member_ref,target_member_ref,member_set_revision,"
                    + "readiness_run_id,initiated_by,initiator_role,false,'synthetic-job-2','QUEUED' from pan_config_sync "
                    + "where sync_id={0}", record.id());
            }));
            // Append-only: the binding cannot be rewritten and the history cannot be deleted.
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> dsl.execute(
                "update pan_config_sync set target_member_ref=source_member_ref where sync_id={0}", record.id())));
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> dsl.execute(
                "delete from pan_config_sync where sync_id={0}", record.id())));

            // The worker: claim, admission, the write's preparation.
            mutate(fixture, dsl -> dsl.execute("update module_runtime_control set owner_instance={0},owner_heartbeat_at=now() "
                + "where module='general'", WORKER));
            var leases = new JooqJobLeaseDao(boundary, JobWindowTestPolicy.PERMISSIVE);
            var claimed = leases.claimNext(WORKER, List.of(JooqPanConfigSyncRepository.JOB_TYPE), Duration.ofMinutes(10)).orElseThrow();
            assertEquals(record.jobId(), claimed.jobId());
            assertTrue(leases.transitionState(claimed.jobId(), claimed.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
            long epoch = claimed.leaseEpoch();
            assertEquals("ADMITTED", syncs.admission(record.id(), record.jobId(), epoch));
            var prepared = syncs.prepareSend(record.id(), record.jobId(), epoch, 2, active, "pan_failover_config_sync", "configuration-write");
            assertEquals("ADMITTED", prepared.code());
            // A cancel requested while the write is in flight (Argon P0-2) changes nothing about its outcome.
            mutate(fixture, dsl -> dsl.execute("update jobs set cancel_requested=true where job_id={0}", record.jobId()));
            assertTrue(syncs.recordReply(record.id(), record.jobId(), epoch, prepared.attemptId(), "success", "MATCHED"));
            assertTrue(syncs.finish(record.id(), record.jobId(), epoch, JooqPanConfigSyncRepository.REQUESTED));
            var ended = syncs.find(record.id()).orElseThrow();
            assertEquals("DONE", ended.state());
            assertEquals(JooqPanConfigSyncRepository.REQUESTED, ended.outcome());
            assertEquals("success", ended.deviceStatus());
            assertEquals("COMPLETED", mutate(fixture, dsl -> dsl.fetchOne("select state from jobs where job_id={0}",
                record.jobId()).get(0, String.class)));
            // The readiness re-run, in the initiator's name, after the record ended.
            String rerun = syncs.followUp(record.id());
            assertNotNull(rerun);
            assertEquals(ADMIN, mutate(fixture, dsl -> dsl.fetchOne("select requested_by from failover_run where run_id={0} "
                + "and run_kind='READINESS'", rerun).get(0, String.class)));
            assertEquals(rerun, syncs.find(record.id()).orElseThrow().followupReadinessRunId());
            assertNull(syncs.followUp(record.id()), "one re-run per sync");
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl -> dsl.execute(
                "update pan_config_sync set outcome='OUTCOME_UNKNOWN' where sync_id={0}", record.id())), "terminal is final");

            // P0-1: a quarantined CONFIGURATION_WRITE endpoint lease of the ended job is released, audited, while its
            // owner is alive (a module drain is never held by it).
            new AuditedTransactionBoundary(boundary).inTransaction("system:worker", "synthetic-fixture", db -> db.execute(
                "insert into endpoint_admission(request_id,endpoint_ref,purpose_class,job_id,job_epoch,session_ref,owner_role,"
                + "owner_instance,owner_generation,lease_token,state,heartbeat_at,expires_at) values('synthetic-ticket',"
                + "'192.0.2.11:443','CONFIGURATION_WRITE',{0},{1},'synthetic-session','general',{2},1,'synthetic-permit',"
                + "'QUARANTINED',now(),now()-interval '1 second')", record.jobId(), epoch, WORKER));
            new AuditedTransactionBoundary(boundary).inTransaction("system:worker", "synthetic-sweep",
                db -> db.fetch("select ui2_release_orphan_quarantines()"));
            assertEquals(0L, count(fixture, "endpoint_admission where request_id='synthetic-ticket'"));
            assertEquals(1L, count(fixture, "audit_log where table_name='endpoint_admission' "
                + "and action_id='QUARANTINE_RELEASED_SYNC_SETTLED'"));
            assertEquals(0L, count(fixture, "runtime_task_lease where task_key='fleet.failover.execution'"));
        }
    }

    @Test void aJobThatEndsElsewhereClosesItsRecordFromTheRecordedReply() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("pan_config_sync_job_end")) {
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var failover = new JooqCpFailoverRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE);
            var syncs = new JooqPanConfigSyncRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE);
            List<String> members = pair(fixture);
            evidence(failover, members.get(0), members.get(1));
            var decision = asAdmin(() -> syncs.request(CLUSTER, members.get(0), members.get(1), ADMIN));
            var record = syncs.find(decision.runId()).orElseThrow();
            mutate(fixture, dsl -> dsl.execute("update module_runtime_control set owner_instance={0},owner_heartbeat_at=now() "
                + "where module='general'", WORKER));
            var leases = new JooqJobLeaseDao(boundary, JobWindowTestPolicy.PERMISSIVE);
            var claimed = leases.claimNext(WORKER, List.of(JooqPanConfigSyncRepository.JOB_TYPE), Duration.ofMinutes(10)).orElseThrow();
            assertTrue(leases.transitionState(claimed.jobId(), claimed.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
            assertEquals("ADMITTED", syncs.admission(record.id(), record.jobId(), claimed.leaseEpoch()));
            var prepared = syncs.prepareSend(record.id(), record.jobId(), claimed.leaseEpoch(), 2, members.get(0),
                "pan_failover_config_sync", "configuration-write");
            assertTrue(syncs.recordReply(record.id(), record.jobId(), claimed.leaseEpoch(), prepared.attemptId(), "success", "MATCHED"));
            // The job is cancelled before the worker could end the record: the recorded success is kept, not rewritten.
            assertTrue(leases.transitionState(claimed.jobId(), claimed.leaseEpoch(), "EXECUTING", "CANCELLED", Ui2Rows.ACTOR, "synthetic_cancel"));
            var ended = syncs.find(record.id()).orElseThrow();
            assertEquals("DONE", ended.state());
            assertEquals(JooqPanConfigSyncRepository.REQUESTED, ended.outcome());
            assertFalse(syncs.finish(record.id(), record.jobId(), claimed.leaseEpoch(), "PRECHECK_UNAVAILABLE"),
                "an ended record is never rewritten");
        }
    }

    private static <T> T asAdmin(java.util.function.Supplier<T> work) {
        return ManualAdmission.under(new ManualAdmission.Grant(ADMIN, ManualAdmission.SECURITY_ADMIN, true), work);
    }

    /** The unit's newest readiness: check 1 PASS with the roles, check 4 FAIL on both members. */
    private static void evidence(JooqCpFailoverRepository failover, String active, String passive) {
        var readiness = failover.requestReadiness(CLUSTER, null, ADMIN, active, "palo_alto");
        assertEquals("ADMITTED", readiness.code());
        String run = readiness.runId();
        failover.check(run, "pre", active, null, 1, "PASS", "{\"role\":\"active\"}");
        failover.check(run, "pre", passive, null, 1, "PASS", "{\"role\":\"passive\"}");
        failover.check(run, "pre", active, null, 4, "FAIL", "{}");
        failover.check(run, "pre", passive, null, 4, "FAIL", "{}");
        failover.state(run, "DONE", "DONE", "NOT_READY", "Configuration sync", "CHECK_NOT_READY");
    }

    private static long count(Ui2PostgresFixture fixture, String tableAndFilter) {
        return mutate(fixture, dsl -> dsl.fetchOne("select count(*) from " + tableAndFilter).get(0, Long.class));
    }
    private static <T> T mutate(Ui2PostgresFixture fixture, Function<DSLContext, T> work) {
        return new AuditedTransactionBoundary(new JooqTransactionBoundary(
            DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES))).inTransaction(Ui2Rows.ACTOR, "synthetic_config_sync_test", work);
    }
    /** Two enrolled Palo Alto gateways of one cluster, sorted by id. */
    private static List<String> pair(Ui2PostgresFixture fixture) throws Exception {
        try (var connection = fixture.appConnection()) {
            String credential = Ui2Rows.insertCredentialReference(connection);
            String a = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            String b = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            Ui2Rows.insertEndpoint(connection, a); Ui2Rows.insertEndpoint(connection, b);
            mutate(fixture, dsl -> dsl.execute("update devices set vendor_hint='palo_alto',role='gateway',"
                + "cluster_member_ref={0} where device_id in ({1},{2})", CLUSTER, a, b));
            return java.util.stream.Stream.of(a, b).sorted().toList();
        }
    }
}
