package com.securityexpert.nexus.ui2.jobs.lease;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.JobState;

/**
 * Leasing/fencing (C2 §4; contract §2 module-placement table: "leasing/
 * fencing... job-engine (.jobs)"). The only implementation lives in {@code
 * persistence} (raw SQL/jOOQ); since {@code persistence} never depends on
 * {@code job-engine}, that implementation is a thin, jOOQ-free adapter here
 * ({@code PersistenceJobLeaseRepository}) delegating to a low-level,
 * plain-typed DAO declared in {@code persistence} -- the same pattern
 * {@code jobs.device.PersistenceDeviceEnrollmentReadPort} already
 * establishes over {@code persistence.device.DeviceRepository} (DIR-7:
 * this interface, and its adapter, never import {@code org.jooq}).
 *
 * <p>{@code job-engine}'s executor consumes this port directly.</p>
 *
 * <p>{@link #claimNext} is the <b>only</b> way a job moves {@code
 * REQUESTED -> CLAIMED} -- contract §4: "no read-then-write claim path
 * exists anywhere (test-enforced, §8-1)." Every other method that mutates a
 * claimed job's state or lease carries {@code lease_epoch} as an explicit
 * parameter and is fencing-token-guarded (C2 §4.2): a zero-row result is
 * the caller's sole signal to stop, never inspected via {@code
 * lease_expires_at} directly.</p>
 */
public interface JobLeaseRepository {

    /**
     * C2 §4.1's single atomic {@code UPDATE ... WHERE ... RETURNING}
     * ({@link ClaimStatementText#SQL}, verbatim).
     */
    Optional<ClaimedJob> claimNext(String workerId, List<String> eligibleCapabilityIds, Duration leaseDuration);

    /**
     * C2 §4.3: extends the lease while {@code CLAIMED}/{@code EXECUTING}.
     * A missed heartbeat never itself changes job state (only lease expiry
     * does) -- this method never transitions {@code state}.
     */
    boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration);

    /**
     * Every state-affecting write carries {@code WHERE state = <expected>}
     * (C2 §9-1) in addition to the fencing check -- an illegal transition
     * or a stale epoch both surface identically as "zero rows affected,"
     * which is exactly what makes a zombie writer's next write fail its
     * row-count check and stop (C2 §4.2) whether the cause was fencing or
     * an illegal transition.
     */
    boolean transitionState(String jobId, long leaseEpoch, JobState expectedFrom, JobState to,
            String actorFingerprint, String actionId);

    /** C2 §4.4-1: a lease-expired {@code CLAIMED} job with no attempt row requeues. Carries the epoch to requeue at (fenced). */
    List<ClaimedJob> findExpiredWithNoAttempt();

    /** C2 §4.4-2: a lease-expired {@code EXECUTING} job whose every attempt boundary is {@code NO} requeues. */
    List<ClaimedJob> findExpiredAllBoundaryNo();

    /** C2 §4.4-3: a lease-expired {@code EXECUTING} job with one unconfirmed {@code YES} boundary reaches {@code OUTCOME_UNKNOWN}. */
    List<ClaimedJob> findExpiredUnconfirmedYes();
}
