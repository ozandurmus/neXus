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
    @Test
    void everyPlainSqlStatementExecutesAgainstTheMigratedSchema() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_sql")) {
            String device;
            try (var connection = fixture.appConnection()) {
                device = Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
                Ui2Rows.insertEndpoint(connection, device);
            }
            var repository = new JooqCpFailoverRepository(new JooqTransactionBoundary(
                    DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String cluster = "CLS-TEST-01";
            String actor = Ui2Rows.ACTOR;
            Instant now = Instant.now();
            assertFalse(repository.summaryMembers().isEmpty());
            assertEquals("NO_PRE_APPROVAL", repository.request(cluster, null, now, actor, device, true).code());
            var approval = repository.createApproval(cluster, null, now.minusSeconds(60), now.plusSeconds(3600), "Synthetic test", actor);
            assertEquals(1, repository.approvals(cluster, null).size());
            var immediate = repository.request(cluster, null, now, actor, device, true);
            assertEquals("ADMITTED", immediate.code());
            assertEquals("RUN_ALREADY_ACTIVE", repository.request(cluster, null, now, actor, device, true).code());
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

            var scheduled = repository.request(cluster, null, now, actor, device, false);
            assertTrue(repository.due().stream().anyMatch(run -> run.id().equals(scheduled.runId())));
            assertEquals("ADMITTED", repository.startDue(scheduled.runId(), device));
            assertEquals("ALREADY_CLAIMED", repository.startDue(scheduled.runId(), device));
            repository.state(scheduled.runId(), "DONE", "DONE", "SYNTHETIC", null, null);
            var expired = repository.request(cluster, null, now, actor, device, false);
            assertTrue(repository.revoke(approval.id(), actor));
            assertEquals("WINDOW_EXPIRED", repository.startDue(expired.runId(), device));
            assertFalse(repository.windowValid(expired.runId()));

            repository.createApproval(cluster, "0001", now.minusSeconds(60), now.plusSeconds(3600), "Synthetic test", actor);
            var cancelled = repository.request(cluster, "0001", now, actor, device, false);
            repository.stopPlanned(cancelled.runId(), "SYNTHETIC_CANCELLED");
            assertTrue(repository.finished(cancelled.runId()));
            var readiness = repository.requestReadiness(cluster, null, actor, device, "check_point");
            assertEquals("ADMITTED", readiness.code());
            repository.check(readiness.runId(), "pre", device, null, 1, "PASS", "{}");
            repository.state(readiness.runId(), "DONE", "DONE", "SYNTHETIC", null, null);
            assertEquals(1, repository.readinessStatuses().size());
            assertEquals(2, repository.summaryStatuses().size());
        }
    }
}
