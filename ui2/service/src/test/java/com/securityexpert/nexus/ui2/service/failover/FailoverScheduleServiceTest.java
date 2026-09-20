package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.checks.ClockHealthCheck;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FailoverScheduleServiceTest {

    private FailoverScheduleService scheduleService;
    private FailoverScheduleLedger scheduleLedger;
    private StubPreflightService preflightService;
    private FailoverPilotAllowlist pilotAllowlist;

    private static final String CLUSTER_REF = "cls-uuid-cp";
    private static final String DEV_ACTIVE = "dev-cp-1";
    private static final String DEV_STANDBY = "dev-cp-2";

    private static final byte[] TEST_MASTER_SECRET = "test-master-secret-at-least-32-bytes-long".getBytes();

    @BeforeEach
    void setUp() {
        preflightService = new StubPreflightService();
        scheduleLedger = new FailoverScheduleLedger();
        FailoverBookingAdmissionControl admissionControl = new FailoverBookingAdmissionControl();
        FailoverAuthorizationService authzService = new FailoverAuthorizationService(preflightService);

        pilotAllowlist = new FailoverPilotAllowlist();

        DurableQuarantineStore quarantineStore = new DurableQuarantineStore();
        FailoverExecutionService executionService = new FailoverExecutionService(
            authzService, preflightService, pilotAllowlist, quarantineStore
        );

        FailoverKeyManagementService keyManagementService =
            FailoverKeyManagementService.withFixedSecretForTesting(TEST_MASTER_SECRET);

        scheduleService = new FailoverScheduleService(
            preflightService, authzService, executionService, scheduleLedger, admissionControl,
            keyManagementService, null
        );
    }

    @Test
    @DisplayName("Scheduling enforces 4-eyes dual control, reason length, and lead time caps")
    void schedulingValidationsTest() {
        Instant now = Instant.now();
        Instant windowStart = now.plus(Duration.ofHours(2));
        Instant windowEnd = windowStart.plus(Duration.ofHours(1));

        // 1. Same requester and approver rejected
        assertThrows(IllegalArgumentException.class, () -> {
            scheduleService.scheduleMaintenanceWindow(new FailoverScheduleService.ScheduleWindowRequest(
                CLUSTER_REF, FailoverActionKind.CONTROLLED_FAILOVER, windowStart, windowEnd,
                15, "alice", "alice", "Scheduled maintenance window", "nonce-1"
            ));
        });

        // 2. Short reason rejected
        assertThrows(IllegalArgumentException.class, () -> {
            scheduleService.scheduleMaintenanceWindow(new FailoverScheduleService.ScheduleWindowRequest(
                CLUSTER_REF, FailoverActionKind.CONTROLLED_FAILOVER, windowStart, windowEnd,
                15, "alice", "bob", "short", "nonce-1"
            ));
        });

        // 3. Lead time > 7 days rejected
        Instant past7Days = now.plus(Duration.ofDays(8));
        assertThrows(IllegalArgumentException.class, () -> {
            scheduleService.scheduleMaintenanceWindow(new FailoverScheduleService.ScheduleWindowRequest(
                CLUSTER_REF, FailoverActionKind.CONTROLLED_FAILOVER, past7Days, past7Days.plus(Duration.ofHours(1)),
                15, "alice", "bob", "Valid change reason for maintenance", "nonce-1"
            ));
        });

        // 4. Valid schedule succeeds and records transition in ledger
        FailoverScheduleRecord record = scheduleService.scheduleMaintenanceWindow(new FailoverScheduleService.ScheduleWindowRequest(
            CLUSTER_REF, FailoverActionKind.CONTROLLED_FAILOVER, windowStart, windowEnd,
            15, "alice", "bob", "Valid change reason for maintenance", "nonce-1"
        ));

        assertNotNull(record);
        assertEquals(FailoverScheduleStatus.SCHEDULED, record.status());
        assertEquals(CLUSTER_REF, record.clusterRef());
        assertEquals(DEV_ACTIVE, record.signedMutationTarget());
        assertNotNull(record.envelopeSignature());

        // Claude CF-P0.2: the recorded baseline digest must be independently re-derivable,
        // never a random token.
        assertEquals(record.baselineSummary().computeCanonicalDigest(), record.baselineSummary().assessmentDigest());

        // Claude CF-P0.3: executionDeadline must equal the deterministic function of the window.
        assertEquals(
            FailoverScheduleRecord.computeExecutionDeadline(record.windowStart(), record.windowEnd(), record.maxStartDelayMinutes()),
            record.executionDeadline()
        );

        // Verify ledger entry
        List<FailoverScheduleLedger.TransitionEntry> entries = scheduleLedger.getTransitionsForSchedule(record.scheduleId());
        assertEquals(1, entries.size());
        assertEquals(FailoverScheduleStatus.SCHEDULED, entries.get(0).toStatus());
        assertTrue(scheduleLedger.verifyChainIntegrity());
    }

    @Test
    @DisplayName("Cancellation transitions schedule to CANCELLED and writes to hash-chained ledger")
    void cancelScheduleTest() {
        Instant windowStart = Instant.now().plus(Duration.ofHours(2));
        Instant windowEnd = windowStart.plus(Duration.ofHours(1));

        FailoverScheduleRecord record = scheduleService.scheduleMaintenanceWindow(new FailoverScheduleService.ScheduleWindowRequest(
            CLUSTER_REF, FailoverActionKind.CONTROLLED_FAILOVER, windowStart, windowEnd,
            15, "alice", "bob", "Valid change reason for maintenance", "nonce-1"
        ));

        FailoverScheduleRecord cancelled = scheduleService.cancelSchedule(record.scheduleId(), "admin-charlie", "Change window cancelled by customer");
        assertEquals(FailoverScheduleStatus.CANCELLED, cancelled.status());
        assertEquals("admin-charlie", cancelled.cancelledBy());
        assertNotNull(cancelled.cancelledAt());

        List<FailoverScheduleLedger.TransitionEntry> entries = scheduleLedger.getTransitionsForSchedule(record.scheduleId());
        assertEquals(2, entries.size());
        assertEquals(FailoverScheduleStatus.CANCELLED, entries.get(1).toStatus());
        assertTrue(scheduleLedger.verifyChainIntegrity());
    }

    @Test
    @DisplayName("Dispatch aborts with ABORTED_WINDOW_EXPIRED when execution window has passed")
    void expiredWindowAbortTest() {
        // Schedule in the future then dispatch after deadline passed
        Instant start = Instant.now().plus(Duration.ofMillis(10));
        Instant end = start.plus(Duration.ofMillis(50));

        FailoverScheduleRecord record = scheduleService.scheduleMaintenanceWindow(new FailoverScheduleService.ScheduleWindowRequest(
            CLUSTER_REF, FailoverActionKind.CONTROLLED_FAILOVER, start, end,
            1, "alice", "bob", "Short test maintenance window", "nonce-1"
        ));

        // Sleep past window
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {}

        FailoverScheduleRecord res = scheduleService.dispatchScheduledExecution(record.scheduleId(), "CRON_RUNNER");
        assertEquals(FailoverScheduleStatus.ABORTED_WINDOW_EXPIRED, res.status());
        assertEquals("WINDOW_EXPIRED", res.abortReasonCode());
    }

    // Helper StubPreflightService
    private static class StubPreflightService extends PreflightService {
        private PreflightReport report;
        private ClusterEvidenceSnapshot snapshot;

        public StubPreflightService() {
            super(null, null, null);
            Instant now = Instant.now();
            ClusterMemberEvidence active = new ClusterMemberEvidence(
                DEV_ACTIVE, "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
                "SYNCHRONIZED", 0L, true, 0, true, List.of(),
                true, 0, "R81.20", "hash-1", 10, 20, 100L, 1000L, false, 0, 0, false, true, now
            );
            ClusterMemberEvidence standby = new ClusterMemberEvidence(
                DEV_STANDBY, "FW-TANGO-02", "STANDBY", "ACTIVE", "ClusterXL",
                "SYNCHRONIZED", 0L, true, 0, true, List.of(),
                true, 0, "R81.20", "hash-1", 10, 20, 100L, 1000L, false, 0, 0, false, true, now
            );
            this.snapshot = new ClusterEvidenceSnapshot(
                CLUSTER_REF, "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null, active, standby, now
            );
            this.report = PreflightReport.fromResults(
                CLUSTER_REF, "CLS-TANGO-01", "CHECK_POINT", "ClusterXL",
                List.of(CheckResult.pass("chk-1", "Link", "Net", EnforcementPolicy.BLOCKING, "OK"))
            );
        }

        @Override
        public PreflightReport getLatestReport(String clusterRef) {
            return report;
        }

        @Override
        public ClusterEvidenceSnapshot buildSnapshotForCluster(String clusterRef) {
            return snapshot;
        }
    }
}
