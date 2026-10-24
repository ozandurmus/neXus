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

    @Test void theEndReRunsReadinessUnderTheInitiatorsGrantAfterTheRecordEnded() {
        var db = new Database();
        var repository = new JooqPanConfigSyncRepository(db, true, windows(OUTSIDE));
        assertTrue(repository.finish("sync-1", "job-1", 1, JooqPanConfigSyncRepository.REQUESTED, true));
        int ended = indexOf(db, "update pan_config_sync set state");
        assertArrayEquals(new Object[]{"DONE", JooqPanConfigSyncRepository.REQUESTED, "sync-1"}, db.bindings.get(ended));
        int job = indexOf(db, "update jobs set state");
        assertEquals("COMPLETED", db.bindings.get(job)[0]);
        int run = indexOf(db, "insert into failover_run");
        assertTrue(run > ended && run > job, "readiness is admitted after the record has ended");
        assertTrue(db.statements.get(run).contains("'READINESS'"));
        assertEquals(ACTOR, db.bindings.get(run)[3], "the re-run is the initiator's");
        int readinessJob = indexOf(db, "insert into jobs");
        assertEquals("pan_failover_readiness", db.bindings.get(readinessJob)[1]);
        assertEquals(ManualAdmission.OPERATION_ADMIN, db.bindings.get(indexOf(db, "update jobs set window_bypass_role"))[0]);
        int followUp = indexOf(db, "update pan_config_sync set followup_readiness_run_id");
        assertEquals(db.bindings.get(run)[0], db.bindings.get(followUp)[0]);
        assertNull(db.bindings.get(followUp)[1]);
        assertTrue(ManualAdmission.current().isEmpty(), "the grant does not outlive the re-run");
    }

    @Test void insideBusinessHoursAnUnacknowledgedRequestGetsNoReRunAndAnUncertainWriteEndsUnknown() {
        var db = new Database();
        var repository = new JooqPanConfigSyncRepository(db, true, windows(INSIDE));
        db.sync = Database.syncRow(null, false);
        db.sync.put("device_status", null);
        assertTrue(repository.finish("sync-1", "job-1", 1, JooqPanConfigSyncRepository.OUTCOME_UNKNOWN, true));
        assertEquals("STOPPED", db.bindings.get(indexOf(db, "update pan_config_sync set state"))[0]);
        assertEquals("OUTCOME_UNKNOWN", db.bindings.get(indexOf(db, "update jobs set state"))[0]);
        assertFalse(db.ran("insert into failover_run"));
        assertEquals(ManualAdmission.CONFIRM_CODE, db.bindings.get(indexOf(db, "update pan_config_sync set followup"))[1]);
        // Acknowledged inside business hours: the re-run is admitted with that acknowledgement.
        var acked = new Database();
        acked.sync = Database.syncRow(OffsetDateTime.now(ZoneOffset.UTC), true);
        assertTrue(new JooqPanConfigSyncRepository(acked, true, windows(INSIDE)).finish("sync-1", "job-1", 1,
            JooqPanConfigSyncRepository.REQUESTED, true));
        assertTrue(acked.ran("insert into failover_run"));
        // A claimed success without the recorded device status is never DONE.
        var unconfirmed = new Database();
        unconfirmed.sync.put("device_status", null);
        assertTrue(new JooqPanConfigSyncRepository(unconfirmed, true, windows(OUTSIDE)).finish("sync-1", "job-1", 1,
            JooqPanConfigSyncRepository.REQUESTED, false));
        assertArrayEquals(new Object[]{"STOPPED", "OUTCOME_UNKNOWN", "sync-1"},
            unconfirmed.bindings.get(indexOf(unconfirmed, "update pan_config_sync set state")));
        assertFalse(unconfirmed.ran("insert into failover_run"));
    }

    private static int indexOf(Database db, String prefix) {
        for (int i = 0; i < db.statements.size(); i++) if (db.statements.get(i).startsWith(prefix)) return i;
        throw new AssertionError("no statement " + prefix + " in " + db.statements);
    }
}
