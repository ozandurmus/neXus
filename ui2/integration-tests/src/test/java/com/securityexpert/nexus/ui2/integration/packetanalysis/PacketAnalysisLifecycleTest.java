package com.securityexpert.nexus.ui2.integration.packetanalysis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaptureRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.Outcome;
import com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisJob;

/**
 * V154 against PostgreSQL: case, capture, run and ledger rows; the packet_analysis module claims its own job type
 * while the general worker cannot; every fenced progress write; retention pruning with its tombstone.
 */
class PacketAnalysisLifecycleTest {

    @Test void caseUploadAnalysisAndPruneRunEndToEndWithOnlyThePacketAnalysisOwnerClaiming() {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("packet_analysis_lifecycle")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var repository = new PacketAnalysisRepository(tx);
            String operator = "synthetic-operator";
            String caseId = UUID.randomUUID().toString();
            var created = repository.createCase(caseId, "Synthetic case", operator, false);
            assertEquals(Duration.ofDays(7), Duration.between(created.createdAt(), created.expiresAt()));
            assertEquals("OPEN", created.state());
            assertEquals(Outcome.NO_CAPTURES, repository.requestAnalysis(caseId, UUID.randomUUID().toString(), operator));

            String captureId = UUID.randomUUID().toString();
            assertEquals(Outcome.DONE, repository.addCapture(new CaptureRow(captureId, caseId, "synthetic.pcap", "pcap", 124,
                    "b".repeat(64), captureId + ".pcap", operator, Instant.now()), operator));
            assertEquals(124, repository.findCase(caseId).orElseThrow().totalBytes());

            String jobId = UUID.randomUUID().toString();
            assertEquals(Outcome.DONE, repository.requestAnalysis(caseId, jobId, operator));
            assertEquals(Outcome.BUSY, repository.requestAnalysis(caseId, UUID.randomUUID().toString(), operator));
            assertEquals("QUEUED", repository.findCase(caseId).orElseThrow().state());
            assertEquals(Outcome.BUSY, repository.deleteCase(caseId, operator, operator, () -> fail("files must stay")));

            var leases = new JooqJobLeaseDao(tx, JobWindowTestPolicy.PERMISSIVE);
            var runtime = new ModuleRuntimeRepository(tx);
            assertTrue(runtime.heartbeat("general", "general-synthetic"));
            assertTrue(leases.claimNext("general-synthetic", List.of(PacketAnalysisJob.CAPABILITY), Duration.ofMinutes(10)).isEmpty(),
                    "the general worker never claims packet analysis");
            String instance = "packet_analysis-" + UUID.randomUUID();
            assertTrue(runtime.heartbeat(PacketAnalysisJob.MODULE, instance));
            var claim = leases.claimNext(instance, List.of(PacketAnalysisJob.CAPABILITY), Duration.ofMinutes(10)).orElseThrow();
            assertEquals(jobId, claim.jobId());
            assertTrue(leases.transitionState(jobId, claim.leaseEpoch(), JobState.CLAIMED.name(), JobState.EXECUTING.name(),
                    "system:worker", "packet_analysis_running"));
            assertFalse(repository.markRunning(jobId, claim.leaseEpoch() + 1, "system:worker"), "a stale epoch is fenced");
            assertTrue(repository.markRunning(jobId, claim.leaseEpoch(), "system:worker"));
            assertEquals("ANALYZING", repository.findCase(caseId).orElseThrow().state());
            assertTrue(repository.complete(jobId, claim.leaseEpoch(), "stub-0",
                    "{\"verdict\":\"unknown\",\"reason\":\"engine not yet ported\"}", "system:worker"));
            assertTrue(leases.transitionState(jobId, claim.leaseEpoch(), JobState.EXECUTING.name(), JobState.COMPLETED.name(),
                    "system:worker", "packet_analysis_completed"));
            var run = repository.findRun(jobId).orElseThrow();
            assertEquals("COMPLETED", run.state());
            assertTrue(run.maskedSummaryJson().contains("engine not yet ported"));
            assertEquals("ANALYZED", repository.findCase(caseId).orElseThrow().state());

            repository.recordAccess(caseId, captureId, null, "CAPTURE_DOWNLOADED", "synthetic-secadmin", 124L);
            new AuditedTransactionBoundary(tx).inTransaction("synthetic-fixture", "synthetic_expire", db -> db.execute(
                    "update packet_analysis_case set created_at = now() - interval '8 days', expires_at = now() - interval '1 day' "
                            + "where case_id = {0}", caseId));
            assertTrue(repository.findCase(caseId).isEmpty(), "an expired case is gone for readers before it is pruned");
            assertEquals(List.of(caseId), repository.expiredCases(10));
            boolean[] filesRemoved = {false};
            assertTrue(repository.prune(caseId, "system:worker", () -> filesRemoved[0] = true));
            assertTrue(filesRemoved[0]);
            assertTrue(repository.expiredCases(10).isEmpty());
            assertEquals(List.of("CASE_CREATED", "CAPTURE_UPLOADED", "ANALYSIS_REQUESTED", "CAPTURE_DOWNLOADED", "CASE_PRUNED"),
                    tx.inTransaction(db -> db.fetch("select event from packet_analysis_ledger where case_id = {0} "
                            + "order by occurred_at, ledger_id", caseId).map(r -> r.get("event", String.class))));
            assertEquals(0, tx.<Integer>inTransaction(db -> db.fetchOne("select count(*)::int from packet_analysis_capture "
                    + "where case_id = {0}", caseId).get(0, Integer.class)));
            assertEquals(0, tx.<Integer>inTransaction(db -> db.fetchOne("select count(*)::int from audit_log where "
                    + "table_name = 'packet_analysis_capture' and after_state::text like '%synthetic.pcap%'").get(0, Integer.class)),
                    "the uploaded file name reaches the audit log as a digest only");
        }
    }

    @Test void theMigrationAddsExactlyOneSelfOwnedModuleRow() {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("packet_analysis_module")) {
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            assertEquals("packet_analysis|packet_analysis|false", tx.inTransaction(db -> db.fetchOne(
                    "select module || '|' || effective_owner || '|' || fallback_enabled from module_runtime_control "
                            + "where module = 'packet_analysis'").get(0, String.class)));
            assertEquals("packet_analysis", tx.inTransaction(db -> db.fetchOne("select ui2_job_module('packet_analysis_run')")
                    .get(0, String.class)));
            assertEquals("policy", tx.inTransaction(db -> db.fetchOne("select ui2_job_module('cp_policy_collect')").get(0, String.class)));
            // V154 replaces ui2_job_module; V151's diagnostics mapping must survive it.
            assertEquals("diagnostics", tx.inTransaction(db -> db.fetchOne("select ui2_job_module('pan_syslog_measure')")
                    .get(0, String.class)));
        }
    }
}
