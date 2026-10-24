package com.securityexpert.nexus.ui2.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;

/** V178 request, end and readiness re-run against a scripted database (PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §3-§5). */
class JooqPanConfigSyncRepositoryTest {
    private static final String CLUSTER = "CLS-" + "TEST-" + 1, SOURCE = "member-" + "a", TARGET = "member-" + "b";
    private static final String ACTOR = "synthetic-" + "admin";
    /** 09:00 UTC is 12:00 Europe/Istanbul: inside 08:00-20:00. */
    private static final Instant INSIDE = Instant.parse("2026-10-24T09:00:00Z"), OUTSIDE = Instant.parse("2026-10-24T20:00:00Z");

    private static JobWindowPolicy windows(Instant now) {
        return new JobWindowPolicy(Clock.fixed(now, ZoneOffset.UTC), 60, new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)));
    }

    private static final class Database implements TransactionBoundary {
        final List<String> statements = new ArrayList<>();
        final List<Object[]> bindings = new ArrayList<>();
        Map<String, Object> evidence = evidence(true, true, "active", "passive");
        Map<String, Object> sync = syncRow(null, false);
        boolean touched;
        static Map<String, Object> evidence(boolean fresh, boolean failed, String source, String target) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("run_id", "run-" + "evidence"); e.put("fresh", fresh); e.put("sync_failed", failed);
            e.put("source_role", source); e.put("target_role", target);
            return e;
        }
        static Map<String, Object> syncRow(OffsetDateTime ackAt, boolean inside) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sync_id", "sync-1"); r.put("cluster_ref", CLUSTER); r.put("source_member_ref", SOURCE);
            r.put("target_member_ref", TARGET); r.put("readiness_run_id", "run-evidence"); r.put("initiated_by", ACTOR);
            r.put("initiator_role", "operation_admin"); r.put("requested_at", OffsetDateTime.now(ZoneOffset.UTC));
            r.put("requested_in_business_hours", inside); r.put("business_hours_ack_at", ackAt); r.put("job_id", "job-1");
            r.put("state", "SYNCING"); r.put("outcome", null); r.put("device_status", "success"); r.put("mutation_possible", true);
            r.put("followup_readiness_run_id", null); r.put("followup_refusal", null); r.put("started_at", null); r.put("finished_at", null);
            return r;
        }
        private static MockResult[] row(Map<String, Object> values) {
            var dsl = DSL.using(SQLDialect.POSTGRES);
            Field<?>[] fields = values.entrySet().stream().map(e -> DSL.field(e.getKey(),
                e.getValue() == null ? String.class : e.getValue().getClass())).toArray(Field[]::new);
            var rows = dsl.newResult(fields);
            var record = dsl.newRecord(fields); record.fromArray(values.values().toArray()); rows.add(record);
            return new MockResult[]{new MockResult(1, rows)};
        }
        private static MockResult[] none() {
            return new MockResult[]{new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("one", Integer.class)))};
        }
        final DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql();
            if (sql.startsWith("SET LOCAL") || sql.startsWith("select pg_advisory")) return new MockResult[]{new MockResult(0)};
            statements.add(sql); bindings.add(ctx.bindings());
            if (sql.startsWith("select resolved.device_id")) {
                var d = DSL.using(SQLDialect.POSTGRES);
                var id = DSL.field("device_id", String.class); var vendor = DSL.field("vendor_hint", String.class);
                var role = DSL.field("role", String.class); var disabled = DSL.field("disabled", Boolean.class);
                var state = DSL.field("enrollment_state", String.class); var rows = d.newResult(id, vendor, role, disabled, state);
                for (String m : List.of(SOURCE, TARGET)) rows.add(d.newRecord(id, vendor, role, disabled, state)
                    .values(m, "palo_alto", "gateway", false, "ENROLLED"));
                return new MockResult[]{new MockResult(rows.size(), rows)};
            }
            if (sql.startsWith("select 1 from") || sql.startsWith("select run_id from failover_run")) return none();
            if (sql.startsWith("with latest as")) return evidence == null ? none() : row(evidence);
            if (sql.startsWith("select coalesce(submitted_in_business_hours")) {
                Map<String, Object> job = new LinkedHashMap<>(); job.put("inside", false); job.put("business_hours_ack_at", null);
                return row(job);
            }
            if (sql.startsWith("select * from pan_config_sync")) return row(sync);
            if (sql.startsWith("select job_id from jobs")) return row(Map.of("job_id", "job-1"));
            return new MockResult[]{new MockResult(1)};
        }), SQLDialect.POSTGRES);
        @Override public <T> T inTransaction(Function<DSLContext, T> work) { touched = true; return work.apply(dsl); }
        boolean ran(String prefix) { return statements.stream().anyMatch(s -> s.startsWith(prefix)); }
    }

    private static <T> T asAdmin(boolean acknowledged, java.util.function.Supplier<T> work) {
        return ManualAdmission.under(new ManualAdmission.Grant(ACTOR, ManualAdmission.OPERATION_ADMIN, acknowledged), work);
    }

    @Test void theMutationSwitchAndAMissingGrantRefuseBeforeTheDatabase() {
        var db = new Database();
        var off = new JooqPanConfigSyncRepository(db, false, windows(OUTSIDE));
        assertEquals("FAILOVER_MUTATION_DISABLED", asAdmin(true, () -> off.request(CLUSTER, SOURCE, TARGET, ACTOR)).code());
        assertEquals("FAILOVER_MUTATION_DISABLED", off.admission("sync-1", "job-1", 1));
        assertEquals("FAILOVER_MUTATION_DISABLED", off.prepareSend("sync-1", "job-1", 1, 2, SOURCE, "pan_failover_config_sync",
            "configuration-write").code());
        // No administrator's grant on this thread (operator, aiview, any worker): nothing is created.
        var on = new JooqPanConfigSyncRepository(db, true, windows(OUTSIDE));
        assertEquals("WRONG_ROLE", on.request(CLUSTER, SOURCE, TARGET, ACTOR).code());
        assertFalse(db.touched);
    }

    @Test void aFreshFailedReadinessWithTheBoundRolesAdmitsOneRecordAndItsJob() {
        var db = new Database();
        var repository = new JooqPanConfigSyncRepository(db, true, windows(OUTSIDE));
        var decision = asAdmin(false, () -> repository.request(CLUSTER, SOURCE, TARGET, ACTOR));
        assertEquals("ADMITTED", decision.code());
        assertNotNull(decision.runId());
        int job = indexOf(db, "insert into jobs");
        // jOOQ binds in the order the placeholders appear: job id, type, capability, target, submitter, idempotency key.
        assertArrayEquals(new Object[]{db.bindings.get(job)[0], "pan_failover_config_sync", "pan_failover_config_sync", SOURCE, ACTOR,
            "pan_failover_config_sync:" + decision.runId()}, db.bindings.get(job), "the job targets the bound active member");
        assertTrue(db.statements.get(job).contains("'configuration-write'"));
        assertTrue(db.ran("update jobs set window_bypass_role"), "the administrator's grant is recorded on the job");
        assertTrue(db.ran("insert into pan_config_sync"));
        String evidence = db.statements.get(indexOf(db, "with latest as"));
        assertTrue(evidence.contains("run_kind='READINESS'") && evidence.contains("c.check_no=4") && evidence.contains("c.status='FAIL'"));
        assertTrue(evidence.contains("c.check_no=1"));
    }

    @Test void staleMissingPassingOrRoleChangedEvidenceIsRefusedWithoutAnInsert() {
        Object[][] cases = {
            {null, "READINESS_REQUIRED"},
            {Database.evidence(false, true, "active", "passive"), "READINESS_NOT_FRESH"},
            {Database.evidence(true, false, "active", "passive"), "CONFIG_SYNC_NOT_FAILED"},
            {Database.evidence(true, true, "passive", "active"), "ROLES_CHANGED"},
            {Database.evidence(true, true, "active", "active"), "ROLES_CHANGED"},
        };
        for (Object[] c : cases) {
            var db = new Database();
            @SuppressWarnings("unchecked") var e = (Map<String, Object>) c[0];
            db.evidence = e;
            var repository = new JooqPanConfigSyncRepository(db, true, windows(OUTSIDE));
            assertEquals(c[1], asAdmin(false, () -> repository.request(CLUSTER, SOURCE, TARGET, ACTOR)).code());
            assertFalse(db.ran("insert"), (String) c[1]);
        }
    }

    @Test void theEndBindsToTheJobsEpochOnlyNeverItsCancelFlag() {
        var db = new Database();
        var repository = new JooqPanConfigSyncRepository(db, true, windows(OUTSIDE));
        assertTrue(repository.recordReply("sync-1", "job-1", 1, "attempt-write", "success", "MATCHED"));
        assertTrue(repository.finish("sync-1", "job-1", 1, JooqPanConfigSyncRepository.REQUESTED));
        // Argon P0-2: after the send, neither a cancel request nor the lease clock decides whether the outcome is kept.
        for (String sql : db.statements) assertFalse(sql.contains("cancel_requested"), sql);
        assertTrue(db.statements.stream().anyMatch(sql -> sql.startsWith("select job_id from jobs")
            && sql.contains("state='EXECUTING'") && !sql.contains("lease_expires_at")));
        int ended = indexOf(db, "update pan_config_sync set state");
        assertArrayEquals(new Object[]{"DONE", JooqPanConfigSyncRepository.REQUESTED, false, "sync-1"}, db.bindings.get(ended));
        assertEquals("COMPLETED", db.bindings.get(indexOf(db, "update jobs set state"))[0]);
        // The end and the readiness re-run are separate transactions: no readiness admission inside the end.
        assertFalse(db.ran("insert into failover_run"));
    }

    @Test void aWriteThatMayHaveBeenSentEndsAsItsRecordedReplyOrUnknownAndANotSentOneClearsTheFlag() {
        var unconfirmed = new Database();
        unconfirmed.sync.put("device_status", null);
        assertTrue(new JooqPanConfigSyncRepository(unconfirmed, true, windows(OUTSIDE)).finish("sync-1", "job-1", 1,
            JooqPanConfigSyncRepository.REQUESTED));
        assertArrayEquals(new Object[]{"STOPPED", "OUTCOME_UNKNOWN", false, "sync-1"},
            unconfirmed.bindings.get(indexOf(unconfirmed, "update pan_config_sync set state")));
        assertEquals("OUTCOME_UNKNOWN", unconfirmed.bindings.get(indexOf(unconfirmed, "update jobs set state"))[0]);
        // A pre-send code can never land on a write that may have been sent.
        var sent = new Database();
        sent.sync.put("device_status", null);
        new JooqPanConfigSyncRepository(sent, true, windows(OUTSIDE)).finish("sync-1", "job-1", 1, "PRECHECK_UNAVAILABLE");
        assertEquals("OUTCOME_UNKNOWN", sent.bindings.get(indexOf(sent, "update pan_config_sync set state"))[1]);
        var error = new Database();
        error.sync.put("device_status", "error");
        new JooqPanConfigSyncRepository(error, true, windows(OUTSIDE)).finish("sync-1", "job-1", 1,
            JooqPanConfigSyncRepository.DEVICE_REPORTED_ERROR);
        assertArrayEquals(new Object[]{"STOPPED", "DEVICE_REPORTED_ERROR", false, "sync-1"},
            error.bindings.get(indexOf(error, "update pan_config_sync set state")));
        assertEquals("FAILED", error.bindings.get(indexOf(error, "update jobs set state"))[0]);
        // Proven not sent: the flag falls back with the terminal outcome (the V178 guard allows exactly this).
        var notSent = new Database();
        notSent.sync.put("device_status", null);
        new JooqPanConfigSyncRepository(notSent, true, windows(OUTSIDE)).finish("sync-1", "job-1", 1,
            JooqPanConfigSyncRepository.NOT_SENT_BEFORE_CONTACT);
        assertArrayEquals(new Object[]{"STOPPED", JooqPanConfigSyncRepository.NOT_SENT_BEFORE_CONTACT, true, "sync-1"},
            notSent.bindings.get(indexOf(notSent, "update pan_config_sync set state")));
    }

    @Test void theReRunIsTheInitiatorsAndIsNeverRefusedForBusinessHours() {
        // Inside business hours, a request made just before 08:00 without an acknowledgement: the re-run is admitted
        // and its acknowledgement is the follow-up's own (Argon P0-3).
        var db = new Database();
        db.sync = Database.syncRow(null, false);
        db.sync.put("state", "DONE");
        var repository = new JooqPanConfigSyncRepository(db, true, windows(INSIDE));
        assertNotNull(repository.followUp("sync-1"));
        int run = indexOf(db, "insert into failover_run");
        assertTrue(db.statements.get(run).contains("'READINESS'"));
        assertEquals(ACTOR, db.bindings.get(run)[3], "the re-run is the initiator's");
        assertEquals("pan_failover_readiness", db.bindings.get(indexOf(db, "insert into jobs"))[1]);
        assertEquals(ManualAdmission.OPERATION_ADMIN, db.bindings.get(indexOf(db, "update jobs set window_bypass_role"))[0]);
        int ack = indexOf(db, "update jobs set business_hours_ack_by");
        assertEquals(JooqPanConfigSyncRepository.FOLLOW_UP_ACTOR, db.bindings.get(ack)[0]);
        int followUp = indexOf(db, "update pan_config_sync set followup_readiness_run_id");
        assertEquals(db.bindings.get(run)[0], db.bindings.get(followUp)[0]);
        assertNull(db.bindings.get(followUp)[1]);
        assertTrue(ManualAdmission.current().isEmpty(), "the grant does not outlive the re-run");
        // With the initiator's acknowledgement the re-run keeps it (no follow-up actor).
        var acked = new Database();
        acked.sync = Database.syncRow(OffsetDateTime.now(ZoneOffset.UTC), true);
        acked.sync.put("state", "STOPPED");
        assertNotNull(new JooqPanConfigSyncRepository(acked, true, windows(INSIDE)).followUp("sync-1"));
        assertFalse(acked.ran("update jobs set business_hours_ack_by"));
        // An open record, or one already followed up, gets no re-run.
        var open = new Database();
        assertNull(new JooqPanConfigSyncRepository(open, true, windows(OUTSIDE)).followUp("sync-1"));
        assertFalse(open.ran("insert into failover_run"));
    }

    @Test void anAcknowledgementGivenJustBeforeEightIsKeptAndTheWriteIsNotRefusedAtEight() {
        var db = new Database();
        var repository = new JooqPanConfigSyncRepository(db, true, windows(OUTSIDE));
        asAdmin(true, () -> repository.request(CLUSTER, SOURCE, TARGET, ACTOR));
        Object[] insert = db.bindings.get(indexOf(db, "insert into pan_config_sync"));
        assertNotNull(insert[9], "business_hours_ack_at comes from the grant (Argon P1-4)");
        // At 08:00 the record's own acknowledgement admits the write; without one it is refused.
        var acked = new Database();
        acked.sync = Database.syncRow(OffsetDateTime.now(ZoneOffset.UTC), false);
        acked.sync.put("state", "READING_STATE"); acked.sync.put("device_status", null); acked.sync.put("mutation_possible", false);
        assertEquals("ADMITTED", new JooqPanConfigSyncRepository(acked, true, windows(INSIDE)).prepareSend("sync-1", "job-1", 1, 2,
            SOURCE, "pan_failover_config_sync", "configuration-write").code());
        var unacked = new Database();
        unacked.sync.put("state", "READING_STATE"); unacked.sync.put("device_status", null); unacked.sync.put("mutation_possible", false);
        assertEquals(ManualAdmission.CONFIRM_CODE, new JooqPanConfigSyncRepository(unacked, true, windows(INSIDE)).prepareSend(
            "sync-1", "job-1", 1, 2, SOURCE, "pan_failover_config_sync", "configuration-write").code());
        assertFalse(unacked.ran("insert into job_step_attempt"));
    }

    @Test void theUnitFenceCoversRunsResumesUnresolvedWritesAndPassedRolesOnly() {
        var db = new Database();
        var repository = new JooqPanConfigSyncRepository(db, true, windows(OUTSIDE));
        asAdmin(false, () -> repository.request(CLUSTER, SOURCE, TARGET, ACTOR));
        assertTrue(db.ran("select 1 from failover_resume where cluster_ref"));
        String dispatch = db.statements.get(indexOf(db, "select 1 from failover_dispatch_intent"));
        assertTrue(dispatch.contains("i.delivery<>'NOT_SENT'") && dispatch.contains("i.observation='NOT_OBSERVED'"));
        String evidence = db.statements.get(indexOf(db, "with latest as"));
        assertEquals(2, evidence.split("c.check_no=1 and c.status='PASS'", -1).length - 1, "both roles from PASSED check 1 rows");
    }

    private static int indexOf(Database db, String prefix) {
        for (int i = 0; i < db.statements.size(); i++) if (db.statements.get(i).startsWith(prefix)) return i;
        throw new AssertionError("no statement " + prefix + " in " + db.statements);
    }
}
