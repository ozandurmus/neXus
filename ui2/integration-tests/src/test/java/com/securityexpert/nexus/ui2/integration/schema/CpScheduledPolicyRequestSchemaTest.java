package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;

/**
 * PO 2026-10-18 changed-only trial: a policy request is "scheduled" only when it is automatic and its job targets a
 * discovery run started by the scheduled refresh. No schema change: the flag is derived from existing rows.
 */
class CpScheduledPolicyRequestSchemaTest {
    @Test void scheduledFlagFollowsTheDiscoveryRunRequesterAndOnlyItBypassesTheSixHourAllowance() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cp_scheduled_policy_request")) {
            fixture.runFlyway();
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            String credential;
            try (var db = fixture.appConnection()) {
                credential = Ui2Rows.insertCredentialReference(db);
                Ui2Rows.insertDevice(db, "source-1", credential, "ENROLLED");
            }
            var jobs = new JooqJobRecordDao(tx, JobWindowTestPolicy.PERMISSIVE);
            String[][] cases = {
                {"job-scheduled", "run-scheduled", PolicyCollectionRepository.SCHEDULED_REFRESH_ACTOR, "true"},
                {"job-operator", "run-operator", "synthetic-operator", "true"},
                {"job-manual", "run-manual", PolicyCollectionRepository.SCHEDULED_REFRESH_ACTOR, "false"}};
            for (String[] c : cases) {
                new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", "discovery_run_start", db -> {
                    db.execute("insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                        + "requested_by_actor_fingerprint, state, started_at, finished_at) values ({0}, 'check_point', '192.0.2.10', {1}, {2}, "
                        + "'FINISHED', now(), now())", c[1], credential, c[2]);
                    return null;
                });
                assertTrue(jobs.insertRequestedIfAbsentForRun(c[0], c[0], "cp_policy_collect", c[1], "read",
                    "cp_policy_collect", "synthetic-actor", "policy_collect").isPresent());
                new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", "policy_collect", db -> {
                    db.execute("insert into policy_collection_request(job_id, source_id, domain_ref, automatic, mode) "
                        + "values ({0}, 'source-1', '', {1}, 'CHANGED_ONLY')", c[0], Boolean.parseBoolean(c[3]));
                    return null;
                });
            }
            var repository = new PolicyCollectionRepository(tx);
            var scheduled = repository.request("job-scheduled").orElseThrow();
            assertTrue(scheduled.scheduled()); assertFalse(scheduled.throttledAutomatic());
            var operator = repository.request("job-operator").orElseThrow();
            assertFalse(operator.scheduled()); assertTrue(operator.throttledAutomatic());
            var manual = repository.request("job-manual").orElseThrow();
            assertFalse(manual.scheduled(), "a manual request is never scheduled"); assertFalse(manual.throttledAutomatic());
            assertTrue(repository.request("job-missing").isEmpty());
            // The scheduled run is admitted twice inside six hours (back-to-back windows); another automatic run is not.
            assertTrue(repository.beginDomain(scheduled, "domain-1"));
            assertTrue(repository.beginDomain(scheduled.withLease(2), "domain-1"));
            assertFalse(repository.beginDomain(operator, "domain-1"));
        }
    }
}
