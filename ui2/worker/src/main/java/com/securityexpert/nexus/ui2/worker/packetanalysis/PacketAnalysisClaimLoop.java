package com.securityexpert.nexus.ui2.worker.packetanalysis;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisJob;

/**
 * The analysis worker's claim loop: it claims {@code packet_analysis_run} and nothing else (the 2026-10-05
 * ownership incident: a module claims exactly its own job types), runs one analysis at a time to completion
 * (decision record §4), and renews the lease while the engine works.
 */
public final class PacketAnalysisClaimLoop {

    /** The complete claim set of the packet-analysis module. */
    public static final List<String> CLAIMS = List.of(PacketAnalysisJob.CAPABILITY);

    private static final System.Logger LOG = System.getLogger(PacketAnalysisClaimLoop.class.getName());
    private static final java.util.concurrent.ScheduledExecutorService HEARTBEATS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "packet-analysis-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final JobLeaseRepository leases;
    private final JobRecordDao jobs;
    private final PacketAnalysisJobExecutor executor;
    private final String workerId;
    private final Duration leaseDuration;
    private volatile boolean stopRequested;

    public PacketAnalysisClaimLoop(JobLeaseRepository leases, JobRecordDao jobs, PacketAnalysisJobExecutor executor,
            String workerId, Duration leaseDuration) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        if (!workerId.startsWith(PacketAnalysisJob.MODULE + "-")) throw new IllegalArgumentException("WORKER_ID_NOT_PACKET_ANALYSIS");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    }

    public void requestStop() {
        stopRequested = true;
    }

    public void runUntilInterrupted(Duration pollInterval) {
        while (!Thread.currentThread().isInterrupted() && !stopRequested) {
            boolean claimed;
            try {
                claimed = claimAndExecuteOnce();
            } catch (RuntimeException unexpected) {
                LOG.log(System.Logger.Level.ERROR, "[PACKET_ANALYSIS] claim loop error: " + unexpected.getClass().getSimpleName());
                claimed = false;
            }
            if (!claimed) {
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** @return {@code true} when a job was claimed and run to some outcome. */
    public boolean claimAndExecuteOnce() {
        Optional<ClaimedJob> claim = leases.claimNext(workerId, CLAIMS, leaseDuration);
        if (claim.isEmpty()) return false;
        ClaimedJob claimed = claim.get();
        long period = Math.max(5, leaseDuration.toSeconds() / 3);
        var heartbeat = HEARTBEATS.scheduleAtFixedRate(() -> {
            try {
                if (!leases.heartbeat(claimed.jobId(), claimed.leaseEpoch(), leaseDuration))
                    LOG.log(System.Logger.Level.WARNING, "[PACKET_ANALYSIS] lease lost for job " + claimed.jobId());
            } catch (RuntimeException unavailable) {
                LOG.log(System.Logger.Level.WARNING, "[PACKET_ANALYSIS] lease renewal failed");
            }
        }, period, period, java.util.concurrent.TimeUnit.SECONDS);
        try {
            Optional<JobRow> job = jobs.find(claimed.jobId());
            if (job.isEmpty()) return true;
            if (!PacketAnalysisJob.CAPABILITY.equals(job.get().capabilityId())
                    || !PacketAnalysisJob.TARGET_KIND.equals(job.get().targetKind())) {
                leases.transitionState(claimed.jobId(), claimed.leaseEpoch(), JobState.CLAIMED, JobState.REJECTED,
                        PacketAnalysisJobExecutor.ACTOR, "packet_analysis_rejected", "NOT_A_PACKET_ANALYSIS_JOB");
                return true;
            }
            JobState outcome = executor.execute(claimed.jobId(), claimed.leaseEpoch(), job.get().targetRef());
            LOG.log(System.Logger.Level.INFO, "[PACKET_ANALYSIS] job " + claimed.jobId() + " -> "
                    + (outcome == null ? "LEASE_LOST" : outcome.name()));
            return true;
        } finally {
            heartbeat.cancel(false);
        }
    }
}
