package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;
import static org.junit.jupiter.api.Assertions.*;

class JobWindowAdmissionTest {
    private final JobWindowPolicy outside = new JobWindowPolicy(
            Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC), 60);
    private final TransactionBoundary noDatabase = new TransactionBoundary() {
        @Override public <T> T inTransaction(Function<DSLContext, T> work) {
            throw new AssertionError("Outside-window admission must not access the database");
        }
    };
    @Test void everyDurableDeviceAdmissionRefusesBeforeDatabaseAccess() {
        var jobs = new JooqJobRecordDao(noDatabase, outside);
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> jobs.insertRequestedIfAbsent(
                "job", "key", "cp_inventory_collect", "device", "read", "cp_inventory_collect", "actor", "collect"));
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> jobs.insertRequestedIfAbsentForRun(
                "job", "key", "cp_discovery_enumerate", "run", "read", "cp_discovery_enumerate", "actor", "collect"));
        assertThrows(JobWindowPolicy.OutsideWindow.class,
                () -> jobs.insertDiagnostic("job", "key", "device", "port", "actor", "diagnostic"));
        assertThrows(JobWindowPolicy.OutsideWindow.class,
                () -> jobs.insertDiagnosticRead("job", "key", "device", "gate", "synthetic-read", "actor"));
    }
    @Test void cpAndPanFailoverRequestsReadinessAndStartsRefuse() {
        var failover = new JooqCpFailoverRepository(noDatabase, true, outside);
        for (String vendor : List.of("check_point", "palo_alto")) {
            assertThrows(JobWindowPolicy.OutsideWindow.class, () -> failover.requestBound("cluster", null,
                    outside.now(), "actor", "device", true, vendor, Set.of("member-a", "member-b"),
                    "request", 1, "nonce", true, JooqCpFailoverRepository.ADMIN_SINGLE));
            assertThrows(JobWindowPolicy.OutsideWindow.class,
                    () -> failover.requestReadiness("cluster", null, "actor", "device", vendor));
        }
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> failover.startDue("run", "device"));
    }
    @Test void claimAcquiresTransactionLockBeforeCandidateSnapshot() {
        assertClaimLockOrdering(false, false);
    }
    @Test void windowClosingWhileWaitingForLockLeavesJobsUnclaimed() {
        assertClaimLockOrdering(true, false);
    }
    @Test void scheduledBackupClaimAcquiresLockOutsidePollingWindow() {
        assertClaimLockOrdering(false, true);
    }
    @Test void backupWindowClosingWhileWaitingForLockLeavesJobsUnclaimed() {
        assertClaimLockOrdering(true, true);
    }
    private void assertClaimLockOrdering(boolean closeWindowAtLock, boolean backupWindow) {
        var instant = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse(
                backupWindow ? "2026-10-08T23:30:00Z" : "2026-10-08T21:30:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return instant.get(); }
        };
        List<String> queries = new ArrayList<>();
        var sql = DSL.using(new MockConnection(query -> {
            queries.add(query.sql());
            if (closeWindowAtLock && query.sql().equals("SELECT pg_advisory_xact_lock(294611)"))
                instant.set(Instant.parse(backupWindow ? "2026-10-09T00:00:00Z" : "2026-10-08T22:00:00Z"));
            return new MockResult[] { new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(
                    DSL.field("job_id", String.class), DSL.field("lease_epoch", Long.class))) };
        }), SQLDialect.POSTGRES);
        TransactionBoundary database = new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(sql); }
        };
        assertTrue(new JooqJobLeaseDao(database, new JobWindowPolicy(clock, 60))
                .claimNext("general-synthetic", List.of(backupWindow ? "pan_device_state_backup" : "pan_inventory_collect"),
                        Duration.ofMinutes(10)).isEmpty());
        int isolation = queries.indexOf("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
        int lock = queries.indexOf("SELECT pg_advisory_xact_lock(294611)");
        assertTrue(isolation >= 0 && lock > isolation);
        var claims = queries.stream().filter(q -> q.contains("UPDATE jobs")).toList();
        assertEquals(closeWindowAtLock ? 0 : 1, claims.size());
        if (!closeWindowAtLock) assertTrue(queries.indexOf(claims.getFirst()) > lock);
    }
    @Test void queuedJobsWaitButClaimedAndExecutingJobsKeepTheirHeartbeat() {
        List<String> reads = new ArrayList<>();
        var empty = DSL.using(new MockConnection(query -> {
            reads.add(query.sql());
            return new MockResult[] { new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("queued", Integer.class))) };
        }), SQLDialect.POSTGRES);
        var leases = new JooqJobLeaseDao(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(empty); }
        }, outside);
        assertTrue(leases.claimNext("worker", List.of("diagnostic_read"), Duration.ofMinutes(5)).isEmpty());
        // Outside the window: only the bypass-queued read, never the claim statement (PO 2026-10-20).
        assertEquals(1, reads.size());
        assertTrue(reads.getFirst().startsWith("select 1 as queued from jobs"));
        List<String> queries = new ArrayList<>();
        var sql = DSL.using(new MockConnection(query -> {
            queries.add(query.sql());
            return new MockResult[] { new MockResult(1) };
        }), SQLDialect.POSTGRES);
        TransactionBoundary database = new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(sql); }
        };
        assertTrue(new JooqJobLeaseDao(database, outside).heartbeat("job", 1, Duration.ofMinutes(5)));
        assertTrue(queries.stream().anyMatch(q -> q.contains("state in ('CLAIMED', 'EXECUTING')")));
        assertTrue(queries.stream().noneMatch(q -> q.contains("CANCEL") || q.contains("STOPPED")));
    }

    @Test void atTwoManualBackupAndPollingAdmissionsStillRefuseBeforeDatabaseAccess() {
        var atTwo = new JobWindowPolicy(Clock.fixed(Instant.parse("2026-10-08T23:00:00Z"), ZoneOffset.UTC), 60);
        var jobs = new JooqJobRecordDao(noDatabase, atTwo);
        for (var type : ScheduledBackupWindowPolicy.FLEET_JOB_TYPES)
            assertThrows(JobWindowPolicy.OutsideWindow.class, () -> jobs.insertRequestedIfAbsent(
                    "job", "key", type, "device", "read", type, "synthetic-operator", "collect"));
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> jobs.insertRequestedIfAbsent(
                "job", "key", "cp_inventory_collect", "device", "read", "cp_inventory_collect",
                ScheduledBackupWindowPolicy.ACTOR, "collect"));
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> jobs.insertRequestedIfAbsent(
                "job", "key", "cp_gateway_backup", "device", "read", "cp_inventory_collect",
                ScheduledBackupWindowPolicy.ACTOR, "collect"));
    }

    @Test void scheduledBackupAdmissionAndClaimCarryTheExplicitWindowAndSameDayFence() {
        var atTwo = new JobWindowPolicy(Clock.fixed(Instant.parse("2026-10-08T23:30:00Z"), ZoneOffset.UTC), 60);
        List<String> queries = new ArrayList<>();
        List<Object> bindings = new ArrayList<>();
        var db = DSL.using(SQLDialect.POSTGRES);
        var sql = DSL.using(new MockConnection(query -> {
            queries.add(query.sql());
            bindings.addAll(Arrays.asList(query.bindings()));
            return new MockResult[] {new MockResult(0,
                    query.sql().startsWith("insert into jobs") || query.sql().startsWith("WITH inventory_claim_lock")
                            ? db.fetchFromStringData(new String[] {"job_id", "lease_epoch"}) : null)};
        }), SQLDialect.POSTGRES);
        TransactionBoundary database = new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(sql); }
        };
        var jobs = new JooqJobRecordDao(database, atTwo);
        for (var type : ScheduledBackupWindowPolicy.FLEET_JOB_TYPES)
            assertDoesNotThrow(() -> jobs.insertRequestedIfAbsent("job", "key", type, "device", "read",
                    type, ScheduledBackupWindowPolicy.ACTOR, "collect"));
        assertDoesNotThrow(() -> jobs.insertRequestedIfAbsent("job", "key", "cp_mds_export", "device", "read",
                "cp_mds_export", ScheduledBackupWindowPolicy.MDS_ACTOR, "collect"));
        queries.clear();
        bindings.clear();
        assertTrue(new JooqJobLeaseDao(database, atTwo).claimNext("worker", List.of("cp_gateway_backup",
                "cp_inventory_collect"), Duration.ofMinutes(5)).isEmpty());
        assertTrue(queries.stream().anyMatch(q -> q.contains("AND CASE WHEN")
                && q.contains("submitted_at >= CAST(") && q.contains("submitted_at < CAST(")));
        // jOOQ's PostgreSQL binding converts OffsetDateTime to a JDBC timestamp string.
        assertEquals(2, Collections.frequency(bindings, "2026-10-09 02:00:00+03:00"));
        assertEquals(2, Collections.frequency(bindings, "2026-10-09 03:00:00+03:00"));
        assertTrue(bindings.contains(ScheduledBackupWindowPolicy.ACTOR));
        assertTrue(bindings.contains(ScheduledBackupWindowPolicy.MDS_ACTOR));
    }
}
