package com.securityexpert.nexus.ui2.integration.schema;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.*;
import com.securityexpert.nexus.ui2.persistence.runtime.*;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Owner;

class QuarantineReleaseTest {
    private static void quarantine(AuditedTransactionBoundary audited) {
        audited.inTransaction("system:worker", "synthetic-fixture", db -> {
            db.execute("insert into runtime_task_lease(task_key,owner_role,owner_instance,owner_generation,token,epoch,state,heartbeat_at,expires_at) "
                + "values('synthetic-task','general','general-old',1,'synthetic-token',1,'QUARANTINED',now()-interval '121 seconds',now()-interval '60 seconds')");
            db.execute("insert into endpoint_admission(request_id,endpoint_ref,purpose_class,operation_ref,operation_epoch,session_ref,"
                + "owner_role,owner_instance,owner_generation,lease_token,state,heartbeat_at,expires_at) "
                + "values('synthetic-ticket','192.0.2.10:22','INVENTORY','synthetic-task',1,'synthetic-session','general','general-old',1,"
                + "'synthetic-permit','QUARANTINED',now()-interval '121 seconds',now()-interval '60 seconds')");
            return null;
        });
    }

    @Test void orphanReleaseIsAtomicAuditedAndIdempotentAndUnblocksAcquire() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("quarantine_acquire")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var audited = new AuditedTransactionBoundary(tx);
            quarantine(audited);
            audited.inTransaction("system:worker", "synthetic-expired-leases", db -> {
                db.execute("update endpoint_admission set state='LEASED'");
                db.execute("update runtime_task_lease set state='LEASED'");
                return null;
            });
            var jobs = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
            jobs.insertRequestedIfAbsentForRun("synthetic-job", "synthetic-key", "cp_inventory_collect", "synthetic-run", "read",
                "cp_inventory_collect", "synthetic-actor", "fixture").orElseThrow();
            long epoch = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE).claimNext("general-new", List.of("cp_inventory_collect"), Duration.ofMinutes(10))
                .orElseThrow().leaseEpoch();
            var admissions = new EndpointAdmissionRepository(tx);
            assertTrue(admissions.acquire("new-ticket", "192.0.2.10:22", "new-session",
                new Owner("synthetic-job", epoch, null, 0, "general", "general-new", 1, "INVENTORY", false)).isPresent());
            assertEquals(0, tx.inTransaction(db -> db.fetchOne("select count(*)::int from runtime_task_lease").get(0, Integer.class)));
            assertEquals(2, tx.inTransaction(db -> db.fetchOne("select count(*)::int from audit_log "
                + "where action_id='QUARANTINE_RELEASED_OWNER_GONE' and actor_fingerprint='system:worker' "
                + "and operation='DELETE' and before_state->>'reason'='QUARANTINE_RELEASED_OWNER_GONE' "
                + "and not before_state ? 'owner_instance' and not before_state ? 'endpoint_ref'").get(0, Integer.class)));
            assertEquals(0, audited.inTransaction("system:release", "synthetic-drain", db ->
                db.fetchOne("select ui2_release_orphan_quarantines()").get(0, Integer.class)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "current-stale", "other-module", "endpoint-heartbeat", "task-heartbeat", "job-heartbeat", "job-lease"})
    void anyInstanceLivenessOrCurrentOwnershipRetainsBothQuarantines(String evidence) throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("quarantine_live")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var audited = new AuditedTransactionBoundary(tx);
            quarantine(audited);
            audited.inTransaction("system:worker", "synthetic-liveness", db -> {
                switch (evidence) {
                    case "current" -> db.execute("update module_runtime_control set owner_instance='general-old',owner_heartbeat_at=now() where module='general'");
                    case "current-stale" -> db.execute("update module_runtime_control set owner_instance='general-old',owner_heartbeat_at=now()-interval '121 seconds' where module='general'");
                    case "other-module" -> db.execute("update module_runtime_control set owner_instance='general-old',owner_heartbeat_at=now() where module='scheduler'");
                    case "endpoint-heartbeat" -> db.execute("update endpoint_admission set heartbeat_at=now() where request_id='synthetic-ticket'");
                    case "task-heartbeat" -> db.execute("update runtime_task_lease set heartbeat_at=now() where task_key='synthetic-task'");
                    default -> { /* Job liveness is installed through the existing job API below. */ }
                }
                return null;
            });
            if (evidence.startsWith("job-")) {
                var jobs = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
                jobs.insertRequestedIfAbsentForRun("live-job", "live-key", "cp_inventory_collect", "synthetic-run", "read",
                    "cp_inventory_collect", "synthetic-actor", "fixture").orElseThrow();
                new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE).claimNext("general-old", List.of("cp_inventory_collect"), Duration.ofMinutes(10)).orElseThrow();
                audited.inTransaction("system:worker", "synthetic-job-liveness", db -> db.execute(evidence.equals("job-heartbeat")
                    ? "update jobs set state='COMPLETED',lease_expires_at=now()-interval '1 second' where job_id='live-job'"
                    : "update jobs set last_heartbeat_at=now()-interval '121 seconds' where job_id='live-job'"));
            }
            assertEquals(0, audited.inTransaction("system:release", "synthetic-drain", db ->
                db.fetchOne("select ui2_release_orphan_quarantines()").get(0, Integer.class)));
            assertEquals(1, tx.inTransaction(db -> db.fetchOne("select count(*)::int from endpoint_admission").get(0, Integer.class)));
            assertEquals(1, tx.inTransaction(db -> db.fetchOne("select count(*)::int from runtime_task_lease").get(0, Integer.class)));
            assertEquals(0, tx.inTransaction(db -> db.fetchOne("select count(*)::int from audit_log where action_id='QUARANTINE_RELEASED_OWNER_GONE'").get(0, Integer.class)));
        }
    }

    @Test void moduleHeartbeatReleasesPreviousOwnerButKeepsItsOwnQuarantine() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("quarantine_heartbeat")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            quarantine(new AuditedTransactionBoundary(tx));
            assertTrue(new ModuleRuntimeRepository(tx).heartbeat("general", "general-new"));
            assertEquals(0, tx.inTransaction(db -> db.fetchOne("select count(*)::int from endpoint_admission").get(0, Integer.class)));
            quarantine(new AuditedTransactionBoundary(tx));
            new AuditedTransactionBoundary(tx).inTransaction("system:worker", "synthetic-owner", db -> db.execute(
                "update module_runtime_control set owner_instance='general-old',owner_heartbeat_at=now() where module='general'"));
            assertTrue(new ModuleRuntimeRepository(tx).heartbeat("general", "general-old"));
            assertEquals(1, tx.inTransaction(db -> db.fetchOne("select count(*)::int from endpoint_admission").get(0, Integer.class)));
        }
    }

    @Test void previousOwnersHeartbeatSurvivesReplacementAndProtectsFor120Seconds() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("quarantine_previous_heartbeat")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var audited = new AuditedTransactionBoundary(tx);
            quarantine(audited);
            audited.inTransaction("system:worker", "synthetic-replacement", db -> {
                db.execute("update module_runtime_control set owner_instance='general-old',owner_heartbeat_at=now() where module='general'");
                db.execute("update module_runtime_control set owner_instance='general-new',owner_heartbeat_at=now() where module='general'");
                return null;
            });
            assertEquals(0, audited.inTransaction("system:release", "synthetic-drain", db ->
                db.fetchOne("select ui2_release_orphan_quarantines()").get(0, Integer.class)));
            audited.inTransaction("system:worker", "synthetic-expiry", db -> db.execute(
                "update runtime_instance_heartbeat set heartbeat_at=now()-interval '121 seconds' where owner_instance='general-old'"));
            assertEquals(2, audited.inTransaction("system:release", "synthetic-drain", db ->
                db.fetchOne("select ui2_release_orphan_quarantines()").get(0, Integer.class)));
        }
    }

    @Test void fencedButAliveProcessHeartbeatPreventsRelease() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("quarantine_fenced_alive")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var audited = new AuditedTransactionBoundary(tx);
            quarantine(audited);
            audited.inTransaction("system:worker", "synthetic-replacement", db -> db.execute(
                "update module_runtime_control set owner_instance='general-new',owner_heartbeat_at=now() where module='general'"));
            assertFalse(new ModuleRuntimeRepository(tx).heartbeat("general", "general-old"));
            assertEquals(0, audited.inTransaction("system:release", "synthetic-drain", db ->
                db.fetchOne("select ui2_release_orphan_quarantines()").get(0, Integer.class)));
            assertEquals(1, tx.inTransaction(db -> db.fetchOne("select count(*)::int from endpoint_admission").get(0, Integer.class)));
        }
    }

    @Test void missingAuditActorRollsBackBothReleases() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("quarantine_audit_failure")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            quarantine(new AuditedTransactionBoundary(tx));
            assertThrows(org.jooq.exception.DataAccessException.class, () -> tx.inTransaction(db -> {
                db.execute("select set_config('app.actor_fingerprint','',true)");
                return db.fetch("select ui2_release_orphan_quarantines()");
            }));
            assertEquals(1, tx.inTransaction(db -> db.fetchOne("select count(*)::int from endpoint_admission").get(0, Integer.class)));
            assertEquals(1, tx.inTransaction(db -> db.fetchOne("select count(*)::int from runtime_task_lease").get(0, Integer.class)));
        }
    }
}
