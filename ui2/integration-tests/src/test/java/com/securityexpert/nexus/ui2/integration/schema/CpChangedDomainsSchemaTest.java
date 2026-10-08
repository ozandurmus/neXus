package com.securityexpert.nexus.ui2.integration.schema;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;

class CpChangedDomainsSchemaTest {
    @Test void migrationSignsOnlyApprovedCommandAndAppRoleCanPersistAndReplaceDomainRuns() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cp_changed_domains")) {
            fixture.runFlyway();
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            try (var db = fixture.appConnection(); var query = db.prepareStatement(
                    "select canonical_command_key, sign_off_state, source_document_pointer from gate_registry where gate_id = 'cp_policy_last_published_session'")) {
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(CpPolicyGates.COMMANDS.get(CpPolicyGates.LAST_PUBLISHED_SESSION), rows.getString(1));
                    assertEquals("SIGNED_OFF", rows.getString(2)); assertTrue(rows.getString(3).contains("PO 2026-10-05"));
                    assertFalse(rows.next());
                }
            }
            var jobs = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
            assertTrue(jobs.insertRequestedIfAbsentForRun("job-1", "key-1", "cp_policy_collect", "run-1", "read",
                "cp_policy_collect", "synthetic-actor", "policy_collect").isPresent());
            new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", "policy_collect", db -> {
                db.execute("update jobs set state = 'EXECUTING', lease_epoch = 1, lease_expires_at = now() + interval '10 minutes' where job_id = 'job-1'");
                db.execute("insert into policy_collection_request(job_id, source_id, domain_ref, automatic) values ('job-1','source-1','domain-1',false)");
                return null;
            });
            var repository = new PolicyCollectionRepository(tx);
            var request = repository.request("job-1").orElseThrow().withLease(1);
            assertEquals(PolicyCollectionRepository.Mode.CHANGED_ONLY, request.mode());
            assertTrue(repository.saveDomain(request, "domain-1", "COLLECTED", true,
                "{\"uid\":\"published-001\",\"posix\":1791158400000,\"iso8601\":\"2026-10-05T00:00Z\",\"publishTime\":\"2026-10-05T00:00:00Z\"}",
                "[]", 7, "2026-10-04T00:00:00Z", "synthetic-actor"));
            assertTrue(repository.previousDomain("source-1", "domain-1").orElseThrow().complete());
            var progress = repository.domainProgress("job-1").get(0);
            assertEquals("COLLECTED", progress.get("status")); assertEquals(7, progress.get("rules"));
            assertEquals("2026-10-04T00:00:00Z", progress.get("hitsCollectedAt"));
            assertTrue(repository.saveDomain(request, "domain-1", "COLLECTING", false, null, "[]", 0, null, "synthetic-actor"));
            assertFalse(repository.previousDomain("source-1", "domain-1").orElseThrow().complete());
            assertFalse(repository.saveDomain(request.withLease(2), "domain-1", "REUSED", true, null, "[]", 0, null, "synthetic-actor"));
            try (var db = fixture.appConnection(); var statement = db.createStatement()) {
                assertEquals(1, statement.executeUpdate("delete from cp_policy_domain_run where job_id='job-1'"));
            }
        }
    }
}
