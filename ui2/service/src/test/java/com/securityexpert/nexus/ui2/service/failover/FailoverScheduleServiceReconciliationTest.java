package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.BaselineSnapshotSummary;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleRecord;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CF-P0.20: proves {@link FailoverScheduleService#reconcileOrphanedAttemptsOnStartup()} forces
 * every schedule a prior process left mid-dispatch into {@code OUTCOME_UNKNOWN} and engages sticky
 * quarantine on its cluster, rather than silently resuming or leaving the ambiguity unresolved.
 * A real Postgres round trip is a Testcontainers-dependent test this environment cannot run; the
 * {@link JdbcTemplate} collaborator is mocked instead, which is sufficient to prove this method's
 * own logic (the SQL text/param shape, not the database's behavior).
 */
class FailoverScheduleServiceReconciliationTest {

    private static final byte[] TEST_SECRET = "reconciliation-test-secret-32-bytes!!".getBytes();

    @Test
    @DisplayName("A schedule left in CLAIMED_VERIFYING by a prior process is forced to OUTCOME_UNKNOWN and its cluster quarantined")
    void reconciliationForcesOrphanedClaimedScheduleToOutcomeUnknown() {
        PreflightService preflightService = mock(PreflightService.class);
        FailoverAuthorizationService authzService = mock(FailoverAuthorizationService.class);
        FailoverExecutionService executionService = mock(FailoverExecutionService.class);
        FailoverScheduleLedger scheduleLedger = mock(FailoverScheduleLedger.class);
        FailoverBookingAdmissionControl admissionControl = mock(FailoverBookingAdmissionControl.class);
        FailoverKeyManagementService keyManagementService = FailoverKeyManagementService.withFixedSecretForTesting(TEST_SECRET);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        FailoverScheduleRecord orphaned = buildClaimedRecord();

        @SuppressWarnings("unchecked")
        List<FailoverScheduleRecord> orphanedRows = List.of(orphaned);
        when(jdbcTemplate.query(
            anyString(), any(RowMapper.class),
            eq(FailoverScheduleStatus.CLAIMED_VERIFYING.name()), eq(FailoverScheduleStatus.DISPATCHING.name())
        )).thenReturn(orphanedRows);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        FailoverScheduleService service = new FailoverScheduleService(
            preflightService, authzService, executionService, scheduleLedger, admissionControl,
            keyManagementService, jdbcTemplate
        );

        service.reconcileOrphanedAttemptsOnStartup();

        // 1. The durable record is CAS-updated from CLAIMED_VERIFYING to OUTCOME_UNKNOWN.
        verify(jdbcTemplate).update(
            contains("UPDATE failover_schedules"),
            any(Object[].class)
        );

        // 2. The ledger records the reconciliation transition, attributed to the system actor.
        verify(scheduleLedger).recordTransition(
            eq(orphaned.scheduleId()),
            eq(FailoverScheduleStatus.CLAIMED_VERIFYING),
            eq(FailoverScheduleStatus.OUTCOME_UNKNOWN),
            anyString(),
            eq("SYSTEM_STARTUP_RECONCILIATION"),
            anyString()
        );

        // 3. The cluster is placed under sticky quarantine covering both members -- never silently
        // resumed and never left unresolved.
        verify(executionService).engageQuarantine(
            eq(orphaned.clusterRef()),
            anyString(),
            anyString(),
            eq(Set.of(orphaned.baselineSummary().activeMemberId(), orphaned.baselineSummary().standbyMemberId()))
        );
    }

    @Test
    @DisplayName("No schedules in CLAIMED_VERIFYING/DISPATCHING means no quarantine and no spurious ledger writes")
    void reconciliationIsANoOpWhenNothingIsOrphaned() {
        PreflightService preflightService = mock(PreflightService.class);
        FailoverAuthorizationService authzService = mock(FailoverAuthorizationService.class);
        FailoverExecutionService executionService = mock(FailoverExecutionService.class);
        FailoverScheduleLedger scheduleLedger = mock(FailoverScheduleLedger.class);
        FailoverBookingAdmissionControl admissionControl = mock(FailoverBookingAdmissionControl.class);
        FailoverKeyManagementService keyManagementService = FailoverKeyManagementService.withFixedSecretForTesting(TEST_SECRET);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyString()))
            .thenReturn(List.of());

        FailoverScheduleService service = new FailoverScheduleService(
            preflightService, authzService, executionService, scheduleLedger, admissionControl,
            keyManagementService, jdbcTemplate
        );

        service.reconcileOrphanedAttemptsOnStartup();

        verifyNoInteractions(scheduleLedger, executionService);
    }

    @Test
    @DisplayName("Lightweight (non-durable) instances skip reconciliation entirely -- there is nothing durable to reconcile")
    void nonDurableInstanceSkipsReconciliation() {
        PreflightService preflightService = mock(PreflightService.class);
        FailoverAuthorizationService authzService = mock(FailoverAuthorizationService.class);
        FailoverExecutionService executionService = mock(FailoverExecutionService.class);
        FailoverScheduleLedger scheduleLedger = mock(FailoverScheduleLedger.class);
        FailoverBookingAdmissionControl admissionControl = mock(FailoverBookingAdmissionControl.class);
        FailoverKeyManagementService keyManagementService = FailoverKeyManagementService.withFixedSecretForTesting(TEST_SECRET);

        FailoverScheduleService service = new FailoverScheduleService(
            preflightService, authzService, executionService, scheduleLedger, admissionControl,
            keyManagementService, null
        );

        assertDoesNotThrow(service::reconcileOrphanedAttemptsOnStartup);
        verifyNoInteractions(scheduleLedger, executionService);
    }

    private static FailoverScheduleRecord buildClaimedRecord() {
        Instant windowStart = Instant.now().plus(Duration.ofHours(1));
        Instant windowEnd = windowStart.plus(Duration.ofHours(1));
        BaselineSnapshotSummary baseline = BaselineSnapshotSummary.of(
            "cls-cp-01", "CHECK_POINT", "ClusterXL", "dev-active-1", "dev-standby-1",
            "R81.20", "hash-1", 0L, "digest-abc", windowStart);
        FailoverScheduleRecord scheduled = new FailoverScheduleRecord(
            "sched-orphan-1", "cls-cp-01", "CLS-MASKED", "CHECK_POINT", "CP_CLUSTERXL_MUTATION_GATE",
            FailoverActionKind.CONTROLLED_FAILOVER, "dev-active-1", windowStart, windowEnd, 15,
            FailoverScheduleRecord.computeExecutionDeadline(windowStart, windowEnd, 15),
            "alice", "bob", "grant-orphan-1", baseline, "sig", FailoverScheduleStatus.SCHEDULED, "nonce",
            windowStart.minusSeconds(60), null, null, null, null, null, null, null
        );
        return scheduled.withClaim(Instant.now());
    }
}
