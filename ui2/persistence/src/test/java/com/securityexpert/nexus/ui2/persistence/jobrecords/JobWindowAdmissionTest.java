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
    @Test void queuedJobsWaitButClaimedAndExecutingJobsKeepTheirHeartbeat() {
        var leases = new JooqJobLeaseDao(noDatabase, outside);
        assertTrue(leases.claimNext("worker", List.of("diagnostic_read"), Duration.ofMinutes(5)).isEmpty());
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
}
