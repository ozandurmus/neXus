package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The raw, plain-typed leasing/fencing DAO (C2 §4). Declared here, not in
 * {@code job-engine}, because it touches jOOQ directly and {@code
 * persistence} never depends on {@code job-engine} (DIR-3). {@code
 * job-engine.jobs.lease.PersistenceJobLeaseRepository} adapts this into
 * {@code job-engine}'s own {@code JobLeaseRepository} port, translating
 * {@code JobState} to/from the plain {@code String} state values used
 * here -- the same pattern {@code DeviceRepository}/{@code
 * PersistenceDeviceEnrollmentReadPort} already establishes.
 */
public interface JobLeaseDao {

    /** C2 §4.1's single atomic claim statement, verbatim (see {@code ClaimStatementText} in job-engine). */
    Optional<ClaimedJobRow> claimNext(String workerId, List<String> eligibleCapabilityIds, Duration leaseDuration);

    boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration);

    /** {@code expectedFromState}/{@code toState} are the plain {@code JobState} enum names. */
    boolean transitionState(String jobId, long leaseEpoch, String expectedFromState, String toState,
            String actorFingerprint, String actionId);

    List<ClaimedJobRow> findExpiredWithNoAttempt();

    List<ClaimedJobRow> findExpiredAllBoundaryNo();

    List<ClaimedJobRow> findExpiredUnconfirmedYes();
}
