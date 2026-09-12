package com.securityexpert.nexus.ui2.jobs.lease;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.persistence.jobrecords.ClaimedJobRow;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobLeaseDao;

/**
 * {@link JobLeaseRepository} adapter over {@code persistence}'s {@link
 * JobLeaseDao}. Lives in {@code job-engine} (the interface it implements is
 * declared here); introduces no new dependency edge ({@code job-engine} is
 * already allowed to depend on {@code persistence}) and never imports
 * {@code org.jooq} itself (DIR-7) -- every jOOQ call is behind {@link
 * JobLeaseDao}.
 */
public final class PersistenceJobLeaseRepository implements JobLeaseRepository {

    private final JobLeaseDao dao;

    public PersistenceJobLeaseRepository(JobLeaseDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public Optional<ClaimedJob> claimNext(String workerId, List<String> eligibleCapabilityIds,
            Duration leaseDuration) {
        return dao.claimNext(workerId, eligibleCapabilityIds, leaseDuration)
                .map(row -> new ClaimedJob(row.jobId(), row.leaseEpoch()));
    }

    @Override
    public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
        return dao.heartbeat(jobId, leaseEpoch, leaseDuration);
    }

    @Override
    public boolean transitionState(String jobId, long leaseEpoch, JobState expectedFrom, JobState to,
            String actorFingerprint, String actionId) {
        return dao.transitionState(jobId, leaseEpoch, expectedFrom.name(), to.name(), actorFingerprint, actionId);
    }

    @Override
    public List<ClaimedJob> findExpiredWithNoAttempt() {
        return dao.findExpiredWithNoAttempt().stream().map(r -> new ClaimedJob(r.jobId(), r.leaseEpoch())).toList();
    }

    @Override
    public List<ClaimedJob> findExpiredAllBoundaryNo() {
        return dao.findExpiredAllBoundaryNo().stream().map(r -> new ClaimedJob(r.jobId(), r.leaseEpoch())).toList();
    }

    @Override
    public List<ClaimedJob> findExpiredUnconfirmedYes() {
        return dao.findExpiredUnconfirmedYes().stream().map(r -> new ClaimedJob(r.jobId(), r.leaseEpoch())).toList();
    }
}
