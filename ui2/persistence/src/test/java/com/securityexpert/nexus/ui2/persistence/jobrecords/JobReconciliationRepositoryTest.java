package com.securityexpert.nexus.ui2.persistence.jobrecords;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class JobReconciliationRepositoryTest {
    private static JobReconciliationRepository repo(List<String> sql, String state, boolean failover) {
        var db = DSL.using(SQLDialect.POSTGRES);
        return new JobReconciliationRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            String s = context.sql();
            if (s.startsWith("select state")) return new MockResult[] { new MockResult(state == null ? 0 : 1,
                state == null ? db.fetchFromStringData(new String[] {"state"})
                    : db.fetchFromStringData(new String[] {"state"}, new String[] {state})) };
            if (s.startsWith("select 1")) return new MockResult[] { new MockResult(failover ? 1 : 0,
                failover ? db.fetchFromStringData(new String[] {"x"}, new String[] {"1"})
                    : db.fetchFromStringData(new String[] {"x"})) };
            return new MockResult[] { new MockResult(1, null) };
        }), SQLDialect.POSTGRES)));
    }

    @Test void recordsReconciliationAndMovesTheJobInOneAuditedTransaction() {
        List<String> sql = new ArrayList<>();
        assertEquals("RECONCILED", repo(sql, "OUTCOME_UNKNOWN", false).record("job-1", "RECONCILED_FAILURE", "looked", "actor"));
        assertEquals("SET LOCAL app.action_id = 'job_reconcile_record'", sql.get(1));
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("insert into job_reconciliation")));
        assertTrue(sql.stream().anyMatch(s -> s.contains("set state = 'RECONCILED', reconciliation_ref") && s.contains("state = 'OUTCOME_UNKNOWN'")));
    }

    @Test void refusesMissingWrongStateAndFailoverJobsWithoutWriting() {
        for (var c : new Object[][] {{null, false, "JOB_NOT_FOUND"}, {"COMPLETED", false, "JOB_NOT_OUTCOME_UNKNOWN"},
                {"RECONCILED", false, "JOB_NOT_OUTCOME_UNKNOWN"}, {"OUTCOME_UNKNOWN", true, "JOB_BELONGS_TO_FAILOVER_RUN"}}) {
            List<String> sql = new ArrayList<>();
            assertEquals(c[2], repo(sql, (String) c[0], (Boolean) c[1]).record("job-1", "RECONCILED_SUCCESS", "e", "actor"));
            assertTrue(sql.stream().noneMatch(s -> s.startsWith("insert") || s.startsWith("update")));
        }
    }

    @Test void rejectsInvalidInput() {
        var r = repo(new ArrayList<>(), "OUTCOME_UNKNOWN", false);
        assertThrows(IllegalArgumentException.class, () -> r.record("j", "BOGUS", "e", "a"));
        assertThrows(IllegalArgumentException.class, () -> r.record("j", "RECONCILED_SUCCESS", " ", "a"));
        assertThrows(IllegalArgumentException.class, () -> r.record("j", "RECONCILED_SUCCESS", "x".repeat(2001), "a"));
        assertThrows(IllegalArgumentException.class, () -> r.record("j", "RECONCILED_SUCCESS", "e", " "));
    }
}
