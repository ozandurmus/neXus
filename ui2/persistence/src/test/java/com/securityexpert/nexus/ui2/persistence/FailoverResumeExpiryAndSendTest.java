package com.securityexpert.nexus.ui2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * Argon review 2026-10-21 of the resumed return step (docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md):
 * P1-1, a due request awaiting its second approval is expired (state EXPIRED, outcome RESUME_REQUEST_EXPIRED) on the
 * read path before the verdict, and reads RESUME_REQUEST_EXPIRED, not RESUME_ALREADY_USED; P2-2, a refusal found in
 * the send transaction runs the full §2.1 recheck and records the intent NOT_SENT without invoking the transport.
 * Statement order and bindings against a mock connection; V170 itself is exercised on PostgreSQL by
 * FailoverResumeReturnTest.
 */
class FailoverResumeExpiryAndSendTest {
    private static final String RUN = "run-a", JOB = "job-r", A = "member-a", B = "member-b", UNIT = "unit-a";

    private static final class Db implements TransactionBoundary {
        final List<String> sql = new ArrayList<>();
        final List<Object[]> bindings = new ArrayList<>();
        boolean due, incidentOpen = true;
        String earlierResume, intentMember = A;
        final DSLContext dsl;
        Db() {
            dsl = DSL.using(new MockConnection(ctx -> {
                String q = ctx.sql();
                sql.add(q);
                bindings.add(ctx.bindings());
                Map<String, Object> row = null;
                boolean empty = false;
                if (q.startsWith("select pg_try_advisory_xact_lock")) row = Map.of("acquired", true);
                else if (q.startsWith("select j.job_id from jobs j")) row = Map.of("job_id", JOB);
                else if (q.startsWith("select 1 from failover_resume where state='PENDING_APPROVAL'")) {
                    if (due) row = Map.of("one", 1); else empty = true;
                } else if (q.startsWith("select * from failover_dispatch_intent")) row = intent();
                else if (q.startsWith("select s.*")) row = resume();
                else if (q.startsWith("select run_kind")) row = run();
                else if (q.startsWith("select member_ref from failover_dispatch_intent")) row = Map.of("member_ref", A);
                else if (q.startsWith("select count(*) from failover_dispatch_intent")) row = Map.of("count", 1);
                else if (q.startsWith("select count(*) as total")) row = Map.of("total", 0, "sent", 0);
                else if (q.startsWith("select state from failover_resume")) {
                    if (earlierResume != null) row = Map.of("state", earlierResume); else empty = true;
                } else if (q.startsWith("select 1 from failover_quarantine where execution_id")) {
                    if (incidentOpen) row = Map.of("one", 1); else empty = true;
                } else if (q.startsWith("select 1") || q.startsWith("select ")) empty = !q.startsWith("select pg_advisory_xact_lock");
                if (row != null) return new MockResult[] {new MockResult(1, result(row))};
                if (empty) return new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES)
                    .newResult(DSL.field("one", Integer.class)))};
                return new MockResult[] {new MockResult(1)};
            }), SQLDialect.POSTGRES);
        }
        private Map<String, Object> intent() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nonce", "nonce-1"); row.put("attempt_id", "attempt-1"); row.put("owner_instance", "worker-1");
            row.put("owner_generation", 7L); row.put("member_ref", intentMember); row.put("gate_id", "cp_failover_up");
            return row;
        }
        private static Map<String, Object> resume() {
            OffsetDateTime at = OffsetDateTime.of(2026, 10, 21, 0, 0, 0, 0, ZoneOffset.UTC);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resume_id", "resume-1"); row.put("run_id", RUN); row.put("cluster_ref", UNIT);
            row.put("former_active_ref", A); row.put("peer_ref", B); row.put("m0", A); row.put("m1", B);
            row.put("member_set_revision", JooqCpFailoverRepository.memberSetRevision(java.util.Set.of(A, B)));
            row.put("expected_incident_id", RUN); row.put("policy", JooqCpFailoverRepository.ADMIN_SINGLE);
            row.put("initiated_by", "synthetic-admin"); row.put("requested_at", at); row.put("expires_at", at.plusMinutes(30));
            row.put("requested_in_business_hours", false); row.put("approved_by", "synthetic-admin"); row.put("job_id", JOB);
            row.put("state", "RETURNING"); row.put("step", "RETURNING"); row.put("mutation_possible", true);
            for (String absent : List.of("business_hours_ack_at", "outcome", "failed_check", "message", "finished_at"))
                row.put(absent, null);
            return row;
        }
        private static Map<String, Object> run() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run_kind", "FAILOVER"); row.put("vendor", "check_point"); row.put("state", "STOPPED");
            row.put("cluster_ref", UNIT); row.put("bound", true); row.put("m0", A); row.put("m1", B);
            row.put("vs_id", null);
            return row;
        }
        private static Result<Record> result(Map<String, Object> row) {
            var create = DSL.using(SQLDialect.POSTGRES);
            List<Field<?>> fields = new ArrayList<>();
            row.forEach((name, value) -> fields.add(DSL.field(name, value == null ? String.class : value.getClass())));
            Result<Record> result = create.newResult(fields);
            Record record = create.newRecord(fields);
            for (Field<?> field : fields) set(record, field, row.get(field.getName()));
            result.add(record);
            return result;
        }
        @SuppressWarnings("unchecked")
        private static <T> void set(Record record, Field<T> field, Object value) { record.set(field, (T) value); }
        @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
        JooqCpFailoverRepository repository() { return new JooqCpFailoverRepository(this, true, JobWindowPolicy.SYSTEM); }
        int index(String prefix) {
            for (int i = 0; i < sql.size(); i++) if (sql.get(i).startsWith(prefix)) return i;
            return -1;
        }
        List<Object> bound(String prefix) { return Arrays.asList(bindings.get(index(prefix))); }
    }

    @Test void aDueRequestIsExpiredOnTheReadPathAndReadsAsExpiredNotAsUsed() {
        var db = new Db(); db.due = true; db.earlierResume = JooqCpFailoverRepository.RESUME_EXPIRED;
        assertEquals("RESUME_REQUEST_EXPIRED", db.repository().resumeEligibility(RUN));
        int expire = db.index("update failover_resume set state='EXPIRED'");
        int lock = db.index("select pg_advisory_xact_lock(136, 1)");
        int verdict = db.index("select run_kind");
        assertTrue(lock >= 0 && lock < expire && expire < verdict, "lock 136, then the expiry, then the §2.1 verdict");
        assertTrue(db.sql.get(expire).contains("state='PENDING_APPROVAL' and expires_at<=clock_timestamp()"));
        assertTrue(db.bound("update failover_resume set state='EXPIRED'").containsAll(List.of(RUN, "RESUME_REQUEST_EXPIRED",
            JooqCpFailoverRepository.RESUME_EXPIRY_BATCH)), "this run's request plus a bounded batch");
        assertTrue(db.index("SET LOCAL app.action_id = 'failover_resume_expiry'") >= 0, "an audited system transition");
        // Any other earlier resume still reads as used.
        var used = new Db(); used.earlierResume = "STOPPED";
        assertEquals("RESUME_ALREADY_USED", used.repository().resumeEligibility(RUN));
        assertEquals(-1, used.index("update failover_resume set state='EXPIRED'"), "nothing due: no write");
        assertEquals(-1, used.index("select pg_advisory_xact_lock(136, 1)"), "nothing due: no admission lock on a read");
    }

    @Test void theSweepExpiresDueRequestsInABoundedAuditedTransaction() {
        var db = new Db(); db.due = true;
        assertEquals(1, db.repository().expireResumeRequests());
        List<Object> bound = db.bound("update failover_resume set state='EXPIRED'");
        assertTrue(bound.contains(null), "no run in particular");
        assertTrue(bound.contains(JooqCpFailoverRepository.RESUME_EXPIRY_BATCH));
        var none = new Db();
        assertEquals(0, none.repository().expireResumeRequests());
        assertEquals(-1, none.index("update failover_resume"));
    }

    @Test void aRefusalInTheSendTransactionRecordsNotSentAndNeverInvokesTheTransport() {
        var db = new Db(); db.incidentOpen = false;
        var intent = new JooqCpFailoverRepository.Dispatch("nonce-1", RUN, JOB, 3L);
        var refused = assertThrows(JooqCpFailoverRepository.NotSent.class,
            () -> db.repository().dispatchResume(intent, () -> { fail("the transport is never invoked"); return true; }));
        assertEquals("INCIDENT_NOT_OPEN", refused.code());
        int verdict = db.index("select run_kind");
        int notSent = db.index("update failover_dispatch_intent set delivery='NOT_SENT'");
        assertTrue(verdict >= 0 && notSent > verdict, "the full §2.1 recheck runs before the NOT_SENT record");
        assertTrue(db.bound("update job_step_attempt set outcome='NOT_SENT'").containsAll(List.of("attempt-1", "INCIDENT_NOT_OPEN")));
        assertTrue(db.bound("update failover_resume set state='STOPPED'").containsAll(List.of(JOB, "INCIDENT_NOT_OPEN")));
        assertTrue(db.bound("update jobs set state='FAILED'").containsAll(List.of(JOB, "INCIDENT_NOT_OPEN", 3L)));
        assertEquals(-1, db.index("update failover_dispatch_intent set delivery=?"), "no reply is recorded");
        assertFalse(db.sql.stream().anyMatch(q -> q.contains("observation='OUTCOME_UNKNOWN'")), "no uncertainty is recorded");
    }

    @Test void anIntentForAnotherMemberIsRefusedAsNotBoundAndNotSent() {
        var db = new Db(); db.intentMember = B;
        var intent = new JooqCpFailoverRepository.Dispatch("nonce-1", RUN, JOB, 3L);
        var refused = assertThrows(JooqCpFailoverRepository.NotSent.class,
            () -> db.repository().dispatchResume(intent, () -> { fail("the transport is never invoked"); return true; }));
        assertEquals("RESUME_WRITE_NOT_BOUND", refused.code());
        assertTrue(db.index("update failover_dispatch_intent set delivery='NOT_SENT'") >= 0);
    }
}
