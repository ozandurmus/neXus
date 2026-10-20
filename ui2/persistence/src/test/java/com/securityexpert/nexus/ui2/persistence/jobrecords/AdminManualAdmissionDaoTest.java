package com.securityexpert.nexus.ui2.persistence.jobrecords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;

/**
 * PO decision 2026-10-20 at the durable admission points: an administrator's manual job is admitted outside the
 * windows, recorded on its job row (role, business hours, acknowledgement) in the same audited transaction, and
 * claimable outside the windows; without the acknowledgement inside business hours nothing reaches the database.
 */
class AdminManualAdmissionDaoTest {
    private static final String ADMIN = "synthetic-admin";
    private static final String OPERATOR = "synthetic-operator";

    private static JobWindowPolicy at(String local) {
        return new JobWindowPolicy(Clock.fixed(LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant(),
                JobWindowPolicy.ZONE_ID), 60, new BusinessHours(BusinessHours.DEFAULT_START, BusinessHours.DEFAULT_END));
    }

    private static final TransactionBoundary NO_DATABASE = new TransactionBoundary() {
        @Override public <T> T inTransaction(Function<DSLContext, T> work) {
            throw new AssertionError("a refused admission must not access the database");
        }
    };

    /** Records every statement; answers inserts with one job_id row and every read with the configured rows. */
    private static final class Db {
        final List<String> sql = new ArrayList<>();
        final List<Object[]> bindings = new ArrayList<>();
        final DSLContext dsl;
        Db(Function<String, String[][]> rowsFor) {
            var plain = DSL.using(SQLDialect.POSTGRES);
            dsl = DSL.using(new MockConnection(query -> {
                sql.add(query.sql());
                bindings.add(query.bindings());
                String[][] rows = rowsFor.apply(query.sql());
                if (rows == null && query.sql().toLowerCase(java.util.Locale.ROOT).startsWith("select")
                        && !query.sql().startsWith("select pg_advisory")) rows = new String[][] {{"none"}};
                return new MockResult[] { rows == null ? new MockResult(1)
                        : new MockResult(rows.length - 1, plain.fetchFromStringData(rows)) };
            }), SQLDialect.POSTGRES);
        }
        TransactionBoundary boundary() {
            return new TransactionBoundary() {
                @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
            };
        }
        Optional<Object[]> recorded() {
            for (int i = 0; i < sql.size(); i++)
                if (sql.get(i).startsWith("update jobs set window_bypass_role")) return Optional.of(bindings.get(i));
            return Optional.empty();
        }
    }

    private static String[][] insertReturnsJob(String sql) {
        if (sql.startsWith("insert into jobs")) return new String[][] {{"job_id"}, {"job-1"}};
        return null;
    }

    private static <T> T as(String role, boolean ack, Supplier<T> work) {
        return ManualAdmission.under(new ManualAdmission.Grant(ADMIN, role, ack), work);
    }

    /** Collect now (inventory), configuration, a manual backup and the policy job (discovery-run target). */
    @ParameterizedTest(name = "{0} {1} at {2} ack={3}")
    @CsvSource({
        "cp_inventory_collect,security_admin,2026-10-08T13:00:00,true,true",
        "cp_inventory_collect,operation_admin,2026-10-08T03:30:00,false,false",
        "cp_configuration_collect,security_admin,2026-10-08T21:00:00,true,false",
        "pan_device_state_backup,operation_admin,2026-10-08T15:00:00,true,true",
        "cp_policy_collect,security_admin,2026-10-08T13:00:00,true,true",
        "cp_policy_collect,operation_admin,2026-10-08T23:30:00,false,false",
    })
    void adminManualJobOutsideTheWindowIsAdmittedAndRecorded(String capability, String role, String local, boolean ack,
            boolean insideBusinessHours) {
        var policy = at(local);
        assertFalse(policy.isOpen(), "every row is outside the job window");
        var db = new Db(AdminManualAdmissionDaoTest::insertReturnsJob);
        var jobs = new JooqJobRecordDao(db.boundary(), policy);
        Optional<String> created = as(role, ack, () -> "cp_policy_collect".equals(capability)
                ? jobs.insertRequestedIfAbsentForRun("job-1", "key", capability, "run-1", "read", capability, ADMIN, "policy_collect")
                : jobs.insertRequestedIfAbsent("job-1", "key", capability, "device-1", "read", capability, ADMIN, "collect"));
        assertEquals(Optional.of("job-1"), created);
        Object[] record = db.recorded().orElseThrow();
        assertEquals("job-1", record[4]); // job_id binds last: {0} stands in the WHERE after the SET list
        assertTrue(Arrays.asList(record).contains(role));
        assertTrue(Arrays.asList(record).contains(insideBusinessHours));
        assertEquals(insideBusinessHours && ack, Arrays.asList(record).contains(ADMIN),
                "the acknowledging actor is recorded only inside business hours");
        // The record is written in the admission's own audited transaction, after the insert.
        assertTrue(db.sql.indexOf(db.sql.stream().filter(s -> s.startsWith("insert into jobs")).findFirst().orElseThrow())
                < db.sql.indexOf(db.sql.stream().filter(s -> s.startsWith("update jobs set window_bypass_role")).findFirst().orElseThrow()));
        assertTrue(db.sql.stream().anyMatch(s -> s.startsWith("SET LOCAL app.actor_fingerprint")));
    }

    @Test void insideBusinessHoursWithoutTheAcknowledgementNothingReachesTheDatabase() {
        var outsideWindowInHours = at("2026-10-08T13:00:00");
        var insideWindowInHours = at("2026-10-08T12:10:00");
        for (var policy : List.of(outsideWindowInHours, insideWindowInHours)) {
            var jobs = new JooqJobRecordDao(NO_DATABASE, policy);
            for (String role : List.of(ManualAdmission.SECURITY_ADMIN, ManualAdmission.OPERATION_ADMIN)) {
                assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> as(role, false, () -> jobs.insertRequestedIfAbsent(
                        "job-1", "key", "cp_inventory_collect", "device-1", "read", "cp_inventory_collect", ADMIN, "collect")));
                assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> as(role, false, () -> jobs.insertRequestedIfAbsentForRun(
                        "job-1", "key", "cp_policy_collect", "run-1", "read", "cp_policy_collect", ADMIN, "policy_collect")));
                assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class,
                        () -> as(role, false, () -> jobs.insertDiagnosticRead("job-1", "key", "device-1", "gate", "synthetic-read", ADMIN)));
            }
        }
    }

    @Test void operatorAndAiviewKeepTheWindowRefusalAndNoRecord() {
        var outside = at("2026-10-08T13:00:00");
        var jobs = new JooqJobRecordDao(NO_DATABASE, outside);
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> jobs.insertRequestedIfAbsent(
                "job-1", "key", "cp_inventory_collect", "device-1", "read", "cp_inventory_collect", OPERATOR, "collect"));
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> jobs.insertRequestedIfAbsentForRun(
                "job-1", "key", "cp_policy_collect", "run-1", "read", "cp_policy_collect", OPERATOR, "policy_collect"));
        // Inside the window an operator's job is admitted and nothing marks it: it stays window-bound at claim time.
        var db = new Db(AdminManualAdmissionDaoTest::insertReturnsJob);
        assertEquals(Optional.of("job-1"), new JooqJobRecordDao(db.boundary(), at("2026-10-08T12:10:00")).insertRequestedIfAbsent(
                "job-1", "key", "cp_inventory_collect", "device-1", "read", "cp_inventory_collect", OPERATOR, "collect"));
        assertTrue(db.recorded().isEmpty());
    }

    @Test void businessHoursBeginningBetweenCheckAndRecordRefusesInsteadOfRecordingAnUnacknowledgedJob() {
        var crossing = new java.util.concurrent.atomic.AtomicReference<>(
                LocalDateTime.parse("2026-10-08T07:59:59").atZone(JobWindowPolicy.ZONE_ID).toInstant());
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return JobWindowPolicy.ZONE_ID; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return crossing.get(); }
        };
        var policy = new JobWindowPolicy(clock, 60, new BusinessHours(BusinessHours.DEFAULT_START, BusinessHours.DEFAULT_END));
        var db = new Db(sql -> {
            if (sql.startsWith("insert into jobs")) {
                crossing.set(crossing.get().plusSeconds(2));
                return new String[][] {{"job_id"}, {"job-1"}};
            }
            return null;
        });
        assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> as(ManualAdmission.SECURITY_ADMIN, false,
                () -> new JooqJobRecordDao(db.boundary(), policy).insertRequestedIfAbsent(
                        "job-1", "key", "cp_inventory_collect", "device-1", "read", "cp_inventory_collect", ADMIN, "collect")));
        assertTrue(db.recorded().isEmpty());
    }

    @Test void policyCollectionEnqueueUnderAnAcknowledgedAdminGrantIsAdmittedAtAnyHourAndRecorded() {
        var db = new Db(sql -> {
            if (sql.startsWith("select d.device_id, r.run_id, d.vendor_hint"))
                return new String[][] {{"device_id", "run_id", "vendor_hint"}, {"source-1", "run-1", "check_point"}};
            if (sql.startsWith("select j.job_id from jobs j join policy_collection_request")) return new String[][] {{"job_id"}};
            return insertReturnsJob(sql);
        });
        var queued = as(ManualAdmission.SECURITY_ADMIN, true,
                () -> new PolicyCollectionRepository(db.boundary()).enqueue("source-1", "", false, ADMIN));
        assertTrue(queued.isPresent());
        assertTrue(Arrays.asList(db.recorded().orElseThrow()).contains(ManualAdmission.SECURITY_ADMIN));
        assertTrue(db.sql.stream().anyMatch(s -> s.startsWith("insert into policy_collection_request")));
    }

    @Test void failoverReadinessAndStartUnderTheGrantPassTheWindowAndReadinessIsRecordedOnItsJob() {
        var outside = at("2026-10-08T13:00:00");
        // Readiness: the admit inserts the job and records the grant in the same transaction.
        var db = new Db(sql -> sql.startsWith("select run_id from failover_run") ? new String[][] {{"run_id"}} : null);
        var failover = new JooqCpFailoverRepository(db.boundary(), true, outside);
        for (String role : List.of(ManualAdmission.SECURITY_ADMIN, ManualAdmission.OPERATION_ADMIN)) {
            db.sql.clear(); db.bindings.clear();
            var decision = as(role, true, () -> failover.requestReadiness("cluster", null, ADMIN, "device-1", "check_point"));
            assertEquals("ADMITTED", decision.code());
            assertTrue(Arrays.asList(db.recorded().orElseThrow()).contains(role));
        }
        // Start: the window gate passes; the (empty) approval lookup is what refuses here, not the window.
        var start = as(ManualAdmission.OPERATION_ADMIN, true, () -> failover.requestBound("cluster", null, outside.now(), ADMIN,
                "device-1", true, "palo_alto", Set.of("member-a", "member-b"), "request", 1, "nonce", true,
                JooqCpFailoverRepository.TWO_PERSON));
        assertEquals("REQUEST_CHANGED", start.code());
        // Without the acknowledgement inside business hours, neither reaches the database.
        var refused = new JooqCpFailoverRepository(NO_DATABASE, true, outside);
        assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> as(ManualAdmission.SECURITY_ADMIN, false,
                () -> refused.requestReadiness("cluster", null, ADMIN, "device-1", "check_point")));
        assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> as(ManualAdmission.SECURITY_ADMIN, false,
                () -> refused.requestBound("cluster", null, outside.now(), ADMIN, "device-1", true, "check_point",
                        Set.of("member-a", "member-b"), "request", 1, "nonce", true, JooqCpFailoverRepository.ADMIN_SINGLE)));
        // A scheduled start keeps the window at its scheduled time, grant or not; the scheduler's start keeps it too.
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> as(ManualAdmission.SECURITY_ADMIN, true,
                () -> refused.requestBound("cluster", null, outside.now().plusSeconds(600), ADMIN, "device-1", false,
                        "check_point", Set.of("member-a", "member-b"), "request", 1, "nonce", true,
                        JooqCpFailoverRepository.ADMIN_SINGLE)));
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> as(ManualAdmission.SECURITY_ADMIN, true,
                () -> refused.startDue("run", "device-1")));
    }

    @Test void outsideTheWindowAQueuedBypassJobIsClaimedWithTheBypassBranch() {
        var outside = at("2026-10-08T13:00:00");
        var db = new Db(sql -> {
            if (sql.startsWith("select 1 as queued")) return new String[][] {{"queued"}, {"1"}};
            if (sql.startsWith("WITH inventory_claim_lock")) return new String[][] {{"job_id", "lease_epoch"}, {"job-1", "1"}};
            return null;
        });
        var claimed = new JooqJobLeaseDao(db.boundary(), outside).claimNext("general-synthetic",
                List.of("cp_inventory_collect", "cp_cluster_failover"), Duration.ofMinutes(5));
        assertEquals("job-1", claimed.orElseThrow().jobId());
        String claim = db.sql.stream().filter(s -> s.startsWith("WITH inventory_claim_lock")).findFirst().orElseThrow();
        assertTrue(claim.contains("WHEN window_bypass_role IS NOT NULL THEN true"));
        assertTrue(JooqJobLeaseDao.CLAIM_SQL.indexOf("WHEN window_bypass_role IS NOT NULL THEN true")
                < JooqJobLeaseDao.CLAIM_SQL.indexOf("ELSE clock_timestamp() < CAST({3} AS timestamptz) END"),
                "the regular branch (window end) still governs every other job");
    }
}
