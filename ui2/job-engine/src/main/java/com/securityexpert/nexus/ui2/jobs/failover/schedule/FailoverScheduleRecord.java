package com.securityexpert.nexus.ui2.jobs.failover.schedule;

import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain record representing an authorized scheduled maintenance window failover.
 */
public record FailoverScheduleRecord(
    String scheduleId,
    String clusterRef,
    String maskedClusterName,
    String vendor,
    String commandFamilyId,
    FailoverActionKind actionKind,
    String signedMutationTarget,
    Instant windowStart,
    Instant windowEnd,
    int maxStartDelayMinutes,
    Instant executionDeadline,
    String requesterId,
    String approverId,
    String grantId,
    BaselineSnapshotSummary baselineSummary,
    String envelopeSignature,
    FailoverScheduleStatus status,
    String clientNonce,
    Instant scheduledAt,
    Instant claimedAt,
    Instant executedAt,
    String executionResultId,
    String abortReasonCode,
    String abortReason,
    String cancelledBy,
    Instant cancelledAt
) {
    public FailoverScheduleRecord {
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(commandFamilyId, "commandFamilyId must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(signedMutationTarget, "signedMutationTarget must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        Objects.requireNonNull(executionDeadline, "executionDeadline must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(approverId, "approverId must not be null");
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(baselineSummary, "baselineSummary must not be null");
        Objects.requireNonNull(envelopeSignature, "envelopeSignature must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(scheduledAt, "scheduledAt must not be null");

        Instant expectedDeadline = computeExecutionDeadline(windowStart, windowEnd, maxStartDelayMinutes);
        if (!executionDeadline.equals(expectedDeadline)) {
            throw new IllegalArgumentException(
                "executionDeadline must equal computeExecutionDeadline(windowStart, windowEnd, maxStartDelayMinutes) " +
                    "(expected: " + expectedDeadline + ", got: " + executionDeadline + ")"
            );
        }
    }

    public static Instant computeExecutionDeadline(Instant windowStart, Instant windowEnd, int maxStartDelayMinutes) {
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be strictly after windowStart");
        }
        if (maxStartDelayMinutes <= 0) {
            throw new IllegalArgumentException("maxStartDelayMinutes must be positive");
        }
        Instant delayedDeadline = windowStart.plus(Duration.ofMinutes(maxStartDelayMinutes));
        return delayedDeadline.isBefore(windowEnd) ? delayedDeadline : windowEnd;
    }

    public FailoverScheduleRecord withStatus(FailoverScheduleStatus newStatus) {
        return new FailoverScheduleRecord(
            scheduleId, clusterRef, maskedClusterName, vendor, commandFamilyId, actionKind,
            signedMutationTarget, windowStart, windowEnd, maxStartDelayMinutes, executionDeadline,
            requesterId, approverId, grantId, baselineSummary, envelopeSignature, newStatus,
            clientNonce, scheduledAt, claimedAt, executedAt, executionResultId, abortReasonCode,
            abortReason, cancelledBy, cancelledAt
        );
    }

    public FailoverScheduleRecord withClaim(Instant claimedAtTime) {
        return new FailoverScheduleRecord(
            scheduleId, clusterRef, maskedClusterName, vendor, commandFamilyId, actionKind,
            signedMutationTarget, windowStart, windowEnd, maxStartDelayMinutes, executionDeadline,
            requesterId, approverId, grantId, baselineSummary, envelopeSignature,
            FailoverScheduleStatus.CLAIMED_VERIFYING, clientNonce, scheduledAt, claimedAtTime,
            executedAt, executionResultId, abortReasonCode, abortReason, cancelledBy, cancelledAt
        );
    }

    public FailoverScheduleRecord withAbort(FailoverScheduleStatus abortStatus, String reasonCode, String message) {
        return new FailoverScheduleRecord(
            scheduleId, clusterRef, maskedClusterName, vendor, commandFamilyId, actionKind,
            signedMutationTarget, windowStart, windowEnd, maxStartDelayMinutes, executionDeadline,
            requesterId, approverId, grantId, baselineSummary, envelopeSignature, abortStatus,
            clientNonce, scheduledAt, claimedAt, executedAt, executionResultId, reasonCode,
            message, cancelledBy, cancelledAt
        );
    }

    public FailoverScheduleRecord withCancellation(String operatorId, Instant when) {
        return new FailoverScheduleRecord(
            scheduleId, clusterRef, maskedClusterName, vendor, commandFamilyId, actionKind,
            signedMutationTarget, windowStart, windowEnd, maxStartDelayMinutes, executionDeadline,
            requesterId, approverId, grantId, baselineSummary, envelopeSignature,
            FailoverScheduleStatus.CANCELLED, clientNonce, scheduledAt, claimedAt, executedAt,
            executionResultId, abortReasonCode, abortReason, operatorId, when
        );
    }

    public FailoverScheduleRecord withCompletion(FailoverScheduleStatus finalStatus, String resultId, Instant executedAtTime) {
        return new FailoverScheduleRecord(
            scheduleId, clusterRef, maskedClusterName, vendor, commandFamilyId, actionKind,
            signedMutationTarget, windowStart, windowEnd, maxStartDelayMinutes, executionDeadline,
            requesterId, approverId, grantId, baselineSummary, envelopeSignature, finalStatus,
            clientNonce, scheduledAt, claimedAt, executedAtTime, resultId, abortReasonCode,
            abortReason, cancelledBy, cancelledAt
        );
    }
}
