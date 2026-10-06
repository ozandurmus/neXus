package com.securityexpert.nexus.ui2.integration.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/** Executes each repository SQL statement, including guarded write branches, on migrated PostgreSQL.
 * Only synthetic database rows are created; no executor or device transport is constructed.
 */
class CpFailoverPlainSqlTest {
    private static JooqCpFailoverRepository.RequestApproval approve(JooqCpFailoverRepository repo,String unit,String vs,
            java.util.Set<String> members,String actor) {
        return repo.createRequestApproval(java.util.UUID.randomUUID().toString(),unit,vs,unit,members,
            Instant.now().minusSeconds(60),Instant.now().plusSeconds(3600),"Synthetic approval",actor,"check_point",
            JooqCpFailoverRepository.ADMIN_SINGLE);
    }
    private static JooqCpFailoverRepository.Decision start(JooqCpFailoverRepository repo,
            JooqCpFailoverRepository.RequestApproval approval,java.util.Set<String> members,String actor,boolean immediate) {
        return repo.requestBound(approval.approval().clusterRef(),approval.approval().vsId(),Instant.now(),actor,
            members.iterator().next(),immediate,"check_point",members,approval.approval().id(),approval.revision(),
            approval.executionNonce(),true,approval.policy());
    }
    @Test
    void everyPlainSqlStatementExecutesAgainstTheMigratedSchema() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_sql")) {
            String device,peer;
            try (var connection = fixture.appConnection()) {
                device = Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
                Ui2Rows.insertEndpoint(connection, device);
                peer=Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
                Ui2Rows.insertEndpoint(connection, peer);
                new com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary(
                    new JooqTransactionBoundary(DSL.using(connection,SQLDialect.POSTGRES)))
                    .inTransaction(Ui2Rows.ACTOR,"synthetic_enrollment",dsl -> dsl.execute(
                        "update devices set vendor_hint='check_point',role='gateway',cluster_member_ref='CLS-TEST-01'"));
            }
            var repository = new JooqCpFailoverRepository(new JooqTransactionBoundary(
                    DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)),true);
            String cluster = "CLS-TEST-01";
            String actor = Ui2Rows.ACTOR;
            Instant now = Instant.now();
            assertFalse(repository.summaryMembers().isEmpty());
            assertEquals("REQUEST_BINDING_REQUIRED", repository.request(cluster,null,now,actor,device,true).code());
            var members=java.util.Set.of(device,peer);
            var approval = approve(repository,cluster,null,members,actor);
            assertEquals(1, repository.approvals(cluster, null).size());
            JooqCpFailoverRepository.Decision immediate;
            try (var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first=pool.submit(() -> start(repository,approval,members,actor,true));
                var second=pool.submit(() -> start(repository,approval,members,actor,true));
                immediate=first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(immediate,second.get(15,java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals("ADMITTED", immediate.code());
            assertEquals(immediate,start(repository,approval,members,actor,true));
            assertEquals("RUN_ALREADY_ACTIVE", repository.requestReadiness(cluster, null, actor, device, "check_point").code());
            assertTrue(repository.windowValid(immediate.runId()));
            assertFalse(repository.finished(immediate.runId()));
            repository.command(immediate.runId(), "synthetic-ledger-only");
            repository.check(immediate.runId(), "pre", device, null, 1, "PASS", "{}");
            var detail = repository.detail(immediate.runId()).orElseThrow();
            assertEquals(1, detail.checks().size());
            assertTrue(repository.runByJob(detail.run().jobId()).isPresent());
            assertEquals(1, repository.runs(cluster, null).size());
            assertEquals(1, repository.summaryStatuses().size());
            repository.state(immediate.runId(), "DONE", "DONE", "SYNTHETIC", null, null);
            assertTrue(repository.finished(immediate.runId()));

            var scheduled = start(repository,approve(repository,cluster,null,members,actor),members,actor,false);
            assertTrue(repository.due().stream().anyMatch(run -> run.id().equals(scheduled.runId())));
            assertEquals("ADMITTED", repository.startDue(scheduled.runId(), device));
            assertEquals("ALREADY_CLAIMED", repository.startDue(scheduled.runId(), device));
            repository.state(scheduled.runId(), "DONE", "DONE", "SYNTHETIC", null, null);
            var revocable=approve(repository,cluster,null,members,actor);
            var expired = start(repository,revocable,members,actor,false);
            assertTrue(repository.revoke(revocable.approval().id(), actor));
            assertEquals("WINDOW_EXPIRED", repository.startDue(expired.runId(), device));
            assertFalse(repository.windowValid(expired.runId()));

            var cancelled = start(repository,approve(repository,cluster,"0001",members,actor),members,actor,false);
            repository.stopPlanned(cancelled.runId(), "SYNTHETIC_CANCELLED");
            assertTrue(repository.finished(cancelled.runId()));
            var readiness = repository.requestReadiness(cluster, null, actor, device, "check_point");
            assertEquals("ADMITTED", readiness.code());
            repository.check(readiness.runId(), "pre", device, null, 1, "PASS", "{}");
            repository.state(readiness.runId(), "DONE", "DONE", "SYNTHETIC", null, null);
            assertEquals(1, repository.readinessStatuses().size());
            assertEquals(2, repository.summaryStatuses().size());

            var two=repository.createRequestApproval(java.util.UUID.randomUUID().toString(),cluster,null,cluster,members,
                now.minusSeconds(60),now.plusSeconds(3600),"Synthetic dual approval",actor,"check_point",
                JooqCpFailoverRepository.TWO_PERSON);
            assertEquals("SECOND_APPROVAL_REQUIRED",start(repository,two,members,actor,true).code());
            assertEquals("SELF_APPROVAL",repository.approveRequest(two.approval().id(),1,actor,"check_point",members));
            assertEquals("APPROVED",repository.approveRequest(two.approval().id(),1,"synthetic-second-principal","check_point",members));
            assertEquals("NONCE_MISMATCH",repository.requestBound(cluster,null,Instant.now(),actor,device,true,
                "check_point",members,two.approval().id(),1,approval.executionNonce(),true,two.policy()).code());
            var dualRun=start(repository,two,members,actor,true);
            assertEquals("ADMITTED",dualRun.code());
            var audited=new com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary(
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(),SQLDialect.POSTGRES)));
            assertThrows(org.jooq.exception.DataAccessException.class,() -> audited.inTransaction(actor,"synthetic_test",dsl ->
                dsl.execute("update failover_approval set vs_id='changed' where approval_id={0}",two.approval().id())));
            assertThrows(org.jooq.exception.DataAccessException.class,() -> audited.inTransaction(actor,"synthetic_test",dsl ->
                dsl.execute("update failover_approval set policy_version=2 where approval_id={0}",two.approval().id())));
            repository.revoke(two.approval().id(),actor);
            assertEquals("APPROVAL_REVOKED",repository.mutationAdmission(dualRun.runId(),cluster,null,"check_point",members,
                true,repository.detail(dualRun.runId()).orElseThrow().run().jobId(),1));

        }
    }
}
