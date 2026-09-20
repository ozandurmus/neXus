package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.BaselineSnapshotSummary;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleRecord;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CF-P1.14: the booking admission control claimed a 24-hour volume window and a symmetric
 * cooldown gap, but the volume window was actually 48 hours and the cooldown gap ignored
 * direction. These tests reproduce the review's own concrete counterexamples.
 */
class FailoverBookingAdmissionControlTest {

    private static final String CLUSTER_REF = "cls-cp-01";

    private final FailoverBookingAdmissionControl admissionControl = new FailoverBookingAdmissionControl();

    @Test
    @DisplayName("CF-P1.14 finding 3: a new window ending 30 minutes before an existing window's start violates the 2-hour cooldown")
    void directionalCooldownCatchesANewWindowBeforeAnExistingOne() {
        Instant existingStart = Instant.now().plus(Duration.ofDays(2)).plus(Duration.ofHours(10));
        Instant existingEnd = existingStart.plus(Duration.ofHours(4));
        FailoverScheduleRecord existing = buildRecord(existingStart, existingEnd, FailoverScheduleStatus.SCHEDULED);

        Instant newStart = existingStart.minus(Duration.ofHours(1));
        Instant newEnd = newStart.plus(Duration.ofMinutes(30));

        // The true gap (existing.windowStart - new.windowEnd) is 30 minutes -- below the 2-hour
        // floor. The old abs(existing.windowEnd - new.windowStart) computation would have measured
        // |14:00 - 09:00| = 5h and wrongly admitted this booking.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            admissionControl.validateBookingAdmission(newStart, newEnd, 15, CLUSTER_REF, List.of(existing)));
        assertTrue(ex.getMessage().contains("cooldown"));
    }

    @Test
    @DisplayName("A new window starting more than 2 hours after an existing window ends is admitted")
    void directionalCooldownAdmitsASufficientlySpacedLaterWindow() {
        Instant existingStart = Instant.now().plus(Duration.ofDays(2)).plus(Duration.ofHours(10));
        Instant existingEnd = existingStart.plus(Duration.ofHours(4));
        FailoverScheduleRecord existing = buildRecord(existingStart, existingEnd, FailoverScheduleStatus.SCHEDULED);

        Instant newStart = existingEnd.plus(Duration.ofHours(3));
        Instant newEnd = newStart.plus(Duration.ofMinutes(30));

        assertDoesNotThrow(() ->
            admissionControl.validateBookingAdmission(newStart, newEnd, 15, CLUSTER_REF, List.of(existing)));
    }

    @Test
    @DisplayName("CF-P1.14 finding 2: volume cap counts only the exact rolling 24-hour window, not 48 hours")
    void volumeCapUsesExactRolling24HourWindow() {
        Instant newStart = Instant.now().plus(Duration.ofDays(3));
        Instant newEnd = newStart.plus(Duration.ofMinutes(30));

        // Nine existing schedules exactly 23 hours before the new window's start, on nine
        // different (non-cooling-down) clusters -- all inside [newStart - 24h, newStart].
        List<FailoverScheduleRecord> nineInWindow = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Instant windowStart = newStart.minus(Duration.ofHours(23));
            nineInWindow.add(buildRecordForCluster("cls-other-" + i, windowStart, windowStart.plusSeconds(600), FailoverScheduleStatus.COMPLETED));
        }
        // A tenth schedule 25 hours before -- outside the 24-hour window (would have been inside
        // the old, buggy +/-1-day window).
        Instant outsideWindowStart = newStart.minus(Duration.ofHours(25));
        nineInWindow.add(buildRecordForCluster("cls-other-outside", outsideWindowStart, outsideWindowStart.plusSeconds(600), FailoverScheduleStatus.COMPLETED));

        // 9 in-window + this admission would be the 10th -- exactly at the cap, so it must still
        // be admitted (cap triggers only once 10 are already in the window).
        assertDoesNotThrow(() ->
            admissionControl.validateBookingAdmission(newStart, newEnd, 15, CLUSTER_REF, nineInWindow));
    }

    @Test
    @DisplayName("Volume cap rejects the 11th schedule inside the exact rolling 24-hour window")
    void volumeCapRejectsWhenTenAlreadyInWindow() {
        Instant newStart = Instant.now().plus(Duration.ofDays(3));
        Instant newEnd = newStart.plus(Duration.ofMinutes(30));

        List<FailoverScheduleRecord> tenInWindow = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant windowStart = newStart.minus(Duration.ofHours(1));
            tenInWindow.add(buildRecordForCluster("cls-other-" + i, windowStart, windowStart.plusSeconds(600), FailoverScheduleStatus.COMPLETED));
        }

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            admissionControl.validateBookingAdmission(newStart, newEnd, 15, CLUSTER_REF, tenInWindow));
        assertTrue(ex.getMessage().contains("volume"));
    }

    private static FailoverScheduleRecord buildRecord(Instant windowStart, Instant windowEnd, FailoverScheduleStatus status) {
        return buildRecordForCluster(CLUSTER_REF, windowStart, windowEnd, status);
    }

    private static FailoverScheduleRecord buildRecordForCluster(String clusterRef, Instant windowStart, Instant windowEnd, FailoverScheduleStatus status) {
        BaselineSnapshotSummary baseline = BaselineSnapshotSummary.of(
            clusterRef, "CHECK_POINT", "ClusterXL", "dev-1", "dev-2", "R81.20", "hash-1", 0L, "digest", windowStart);
        return new FailoverScheduleRecord(
            "sched-" + clusterRef + "-" + windowStart, clusterRef, "CLS-MASKED", "CHECK_POINT", "CP_CLUSTERXL_MUTATION_GATE",
            FailoverActionKind.CONTROLLED_FAILOVER, "dev-1", windowStart, windowEnd, 15,
            FailoverScheduleRecord.computeExecutionDeadline(windowStart, windowEnd, 15),
            "alice", "bob", "grant-" + clusterRef + "-" + windowStart, baseline, "sig", status, "nonce",
            windowStart.minusSeconds(60), null, null, null, null, null, null, null
        );
    }
}
