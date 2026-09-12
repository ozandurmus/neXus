package com.securityexpert.nexus.ui2.jobs;

import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * A job instance (C2 §2). job-engine is allowed to reference
 * capability-registry types (contract §2 row) but never a worker,
 * scheduler, or adapter implementation type.
 *
 * <p>Fields are C2 §2.1's set, narrowed to what this movement's own scope
 * exercises (contract §1: schedules/Run Now/approvers/owner are deferred to
 * B1-3/C2 §7). {@code capabilityId} is a plain reference (never the
 * {@code Capability} object itself, C2 §2.2: "the worker's execution plan
 * is fully determined by the job row alone").</p>
 */
public record Job(
        OpaqueId id,
        String capabilityId,
        String targetDeviceId,
        ActionClass actionClass,
        String idempotencyKey,
        JobState state,
        String leaseWorkerId,
        long leaseEpoch,
        Instant leaseExpiresAt,
        Instant lastHeartbeatAt,
        String outcome,
        String terminalReason) {

    public static Job requested(OpaqueId id, String capabilityId, String targetDeviceId, ActionClass actionClass,
            String idempotencyKey) {
        return new Job(id, capabilityId, targetDeviceId, actionClass, idempotencyKey, JobState.REQUESTED,
                null, 0L, null, null, null, null);
    }

    /**
     * C2 §3.2's graph, enforced in-memory too (not only at the persistence
     * layer): an illegal transition throws rather than silently producing
     * an inconsistent in-memory {@link Job}. The persistence layer's own
     * {@code WHERE state = <expected>} guard is the durable enforcement
     * point (contract §4); this is the same rule applied to the value
     * object itself.
     */
    public Job transitionTo(JobState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("illegal job state transition: " + state + " -> " + next);
        }
        return new Job(id, capabilityId, targetDeviceId, actionClass, idempotencyKey, next,
                leaseWorkerId, leaseEpoch, leaseExpiresAt, lastHeartbeatAt, outcome, terminalReason);
    }

    public Optional<String> outcomeOptional() {
        return Optional.ofNullable(outcome);
    }
}
