package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;

/**
 * Claim loop for the dedicated {@code ui2-script-runner} pod ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.2, 5).
 * Claims {@link ScriptExecutionJob#CAPABILITY} ({@code script_run}) only, heartbeats the job lease while the script
 * executes, kills the run when the lease cannot be renewed, and drains cleanly on rollout ({@link #requestStop()} or
 * module drain).
 */
public final class ScriptRunnerClaimLoop {

    private static final Logger LOG = Logger.getLogger(ScriptRunnerClaimLoop.class.getName());

    public static final List<String> CAPABILITIES = List.of(ScriptExecutionJob.CAPABILITY);

    private final String workerId;
    private final JobLeaseRepository leases;
    private final ScriptRunJobExecutor executor;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final Duration idlePollInterval;
    private final BooleanSupplier drainCheck;
    private final Clock clock;
    private volatile boolean running = true;

    public ScriptRunnerClaimLoop(
            String workerId,
            JobLeaseRepository leases,
            ScriptRunJobExecutor executor,
            Duration leaseDuration,
            Duration idlePollInterval) {
        this(
                workerId,
                leases,
                executor,
                leaseDuration,
                defaultHeartbeatInterval(leaseDuration),
                idlePollInterval,
                () -> false,
                Clock.systemUTC());
    }

    public ScriptRunnerClaimLoop(
            String workerId,
            JobLeaseRepository leases,
            ScriptRunJobExecutor executor,
            Duration leaseDuration,
            Duration heartbeatInterval,
            Duration idlePollInterval,
            BooleanSupplier drainCheck) {
        this(workerId, leases, executor, leaseDuration, heartbeatInterval, idlePollInterval, drainCheck,
                Clock.systemUTC());
    }

    public ScriptRunnerClaimLoop(
            String workerId,
            JobLeaseRepository leases,
            ScriptRunJobExecutor executor,
            Duration leaseDuration,
            Duration heartbeatInterval,
            Duration idlePollInterval,
            BooleanSupplier drainCheck,
            Clock clock) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.idlePollInterval = Objects.requireNonNull(idlePollInterval, "idlePollInterval");
        this.drainCheck = Objects.requireNonNull(drainCheck, "drainCheck");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static Duration defaultHeartbeatInterval(Duration leaseDuration) {
        long millis = Math.max(200L, leaseDuration.toMillis() / 3L);
        return Duration.ofMillis(millis);
    }

    public boolean runSingleIteration() {
        if (!running || drainCheck.getAsBoolean()) {
            return false;
        }
        Optional<ClaimedJob> claimedOpt = leases.claimNext(workerId, CAPABILITIES, leaseDuration);
        if (claimedOpt.isEmpty()) {
            return false;
        }
        ClaimedJob claimed = claimedOpt.get();
        // A lease the broker can no longer renew kills the run (review finding 9): the executor polls this flag.
        java.util.concurrent.atomic.AtomicBoolean leaseLost = new java.util.concurrent.atomic.AtomicBoolean(false);
        AtomicReference<Instant> leaseValidUntil = new AtomicReference<>(clock.instant().plus(leaseDuration));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "script-run-lease-heartbeat-" + claimed.jobId());
            t.setDaemon(true);
            return t;
        });
        long periodMillis = Math.max(10L, heartbeatInterval.toMillis());
        ScheduledFuture<?> heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean renewed = leases.heartbeat(claimed.jobId(), claimed.leaseEpoch(), leaseDuration);
                if (renewed) {
                    leaseValidUntil.set(clock.instant().plus(leaseDuration));
                } else {
                    leaseLost.set(true);
                    LOG.log(Level.WARNING, "script_run_lease_lost: job_id={0} epoch={1}",
                            new Object[] {claimed.jobId(), claimed.leaseEpoch()});
                }
            } catch (RuntimeException e) {
                if (!clock.instant().isBefore(leaseValidUntil.get())) {
                    leaseLost.set(true);
                }
                LOG.log(Level.WARNING, "script_run_lease_heartbeat_error: job_id=" + claimed.jobId(), e);
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        try {
            executor.execute(claimed, () -> leaseLost.get() || !clock.instant().isBefore(leaseValidUntil.get()));
        } finally {
            heartbeatTask.cancel(false);
            scheduler.shutdownNow();
        }
        return true;
    }

    public void runLoop() {
        while (running) {
            try {
                boolean claimed = runSingleIteration();
                if (!claimed && running) {
                    Thread.sleep(idlePollInterval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "script_runner_claim_iteration_failed", e);
                try {
                    Thread.sleep(idlePollInterval.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void requestStop() {
        this.running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
