package com.securityexpert.nexus.ui2.integration.persistence;

import static org.junit.jupiter.api.Assertions.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobReconciliationRepository;

/** C2 section 3.5 against real PostgreSQL: the human step OUTCOME_UNKNOWN -> RECONCILED. Synthetic rows only. */
class JobReconciliationStepTest {
    private static String state(Ui2PostgresFixture f, String job) throws Exception {
        try (var c = f.migrateConnection(); var s = c.createStatement();
             var r = s.executeQuery("select state||'/'||coalesce(reconciliation_ref,'-') from jobs where job_id='" + job + "'")) {
            r.next(); return r.getString(1);
        }
    }

    private static void setState(Ui2PostgresFixture f, String job, String state) throws Exception {
        try (var c = f.migrateConnection()) {
            c.setAutoCommit(false);
            Ui2Rows.setAuditContext(c, Ui2Rows.ACTOR, "synthetic_state");
            try (var s = c.createStatement()) { s.execute("update jobs set state='" + state + "' where job_id='" + job + "'"); }
            c.commit();
        }
    }

    @Test void reconcilesAnOutcomeUnknownJobOnceAndAuditsIt() throws Exception {
        try (var f = Ui2PostgresFixture.createAndMigrate("job_reconcile_step")) {
            String job, other;
            try (var c = f.appConnection()) {
                String device = Ui2Rows.insertDevice(c, Ui2Rows.insertCredentialReference(c), "ENROLLED");
                job = Ui2Rows.insertJob(c, device);
                other = Ui2Rows.insertJob(c, device);
            }
            var repo = new JobReconciliationRepository(new JooqTransactionBoundary(DSL.using(f.appDataSource(), SQLDialect.POSTGRES)));
            assertEquals("JOB_NOT_OUTCOME_UNKNOWN", repo.record(job, "RECONCILED_SUCCESS", "e", Ui2Rows.ACTOR));
            assertEquals("JOB_NOT_FOUND", repo.record("nope", "RECONCILED_SUCCESS", "e", Ui2Rows.ACTOR));
            setState(f, job, "OUTCOME_UNKNOWN");
            assertEquals("RECONCILED", repo.record(job, "RECONCILED_STILL_UNKNOWN", "looked, cannot tell", Ui2Rows.ACTOR));
            assertEquals("RECONCILED/" + job, state(f, job));
            assertEquals("JOB_NOT_OUTCOME_UNKNOWN", repo.record(job, "RECONCILED_FAILURE", "again", Ui2Rows.ACTOR));
            assertEquals("REQUESTED/-", state(f, other), "no other job is touched");
            try (var c = f.migrateConnection()) {
                assertEquals(1, Ui2Rows.count(c, "select count(*) from job_reconciliation where job_id='" + job
                    + "' and recorded_by='" + Ui2Rows.ACTOR + "' and reconciled_outcome='RECONCILED_STILL_UNKNOWN'"));
                assertEquals(1, Ui2Rows.count(c, "select count(*) from audit_log where table_name='job_reconciliation' and row_pk='"
                    + job + "' and action_id='job_reconcile_record'"));
                assertEquals(1, Ui2Rows.count(c, "select count(*) from audit_log where table_name='jobs' and row_pk='"
                    + job + "' and action_id='job_reconcile_record'"));
            }
        }
    }
}
