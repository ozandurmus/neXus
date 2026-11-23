package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.FailureReason;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zone matrix delivery step 4b-1: durable evaluation runs on the engine's own bounded pool (ZONE_MATRIX_CONTRACT.md
 * sections 5.1, 8.1, 8.2 and 9, FROZEN 2026-11-13).
 *
 * <ul>
 *   <li><b>Own pool.</b> {@value #THREADS} threads and a bounded queue of {@value #QUEUE_CAPACITY}, never the interactive
 *       {@code PolicyBulkhead}. At most one run per policy source: a second {@link #submit} for a policy whose run is
 *       still {@code RUNNING} returns that run's id, never a parallel run (in process by the in-flight map, across
 *       pods by the database's one-{@code RUNNING}-per-policy index). A full queue ends the new run {@code FAILED}
 *       {@code POOL_SATURATED}.</li>
 *   <li><b>Hard time limit</b> ({@code ui2.zone-matrix.run-time-limit-seconds}, default 30 s per section 9), counted
 *       from the moment the run starts executing: on expiry a watchdog ends the run {@code FAILED} {@code TIME_LIMIT}
 *       and interrupts its thread; an evaluation that still finishes afterwards finds the run no longer
 *       {@code RUNNING} and writes nothing.</li>
 *   <li><b>One transaction.</b> Every finding and the {@code COMPLETE} state are written together
 *       ({@link ZoneMatrixRunRepository#complete}); a failure while writing rolls back and the run ends {@code FAILED}
 *       {@code STORE_ERROR}, so partial findings never become visible ({@code ZM-39}).</li>
 *   <li><b>Startup recovery.</b> {@link #recoverInterrupted()} sets every run left {@code RUNNING} by a pod kill
 *       {@code FAILED} {@code INTERRUPTED_AT_STARTUP}. It runs on application ready; with more than one service pod it
 *       would also end a run another live pod is executing, which then writes nothing (fail closed, never partial).</li>
 *   <li><b>What is stored.</b> Every rule whose outcome is {@code VIOLATION}, {@code UNKNOWN} or {@code NOT_EVALUABLE}
 *       becomes a finding; the count of every outcome goes on the run row. Witness tuples and the NAT view are stored
 *       as the engine returns them; projecting them for readers (masking, section 8.2) is the reading step's job.</li>
 *   <li><b>Logging</b> ({@code ZM-10}): run id, policy id, version id / number, content sha256, state, failure code,
 *       counts and duration only; never zone names, blocks, addresses or rule names, and never an exception
 *       message.</li>
 * </ul>
 *
 * <p>Not in this step: the measured heap ceiling of section 5.1 is <b>not enforced</b> here; it needs the in-pod
 * measurement of section 9 first. The finding lifecycle of section 5.9 (resolution, {@code BECAME_UNDECIDED},
 * {@code firstObservedAnyVersion}) is not applied either: each finding is stored with
 * {@code first_observed_at = last_observed_at =} the run's finish time and the other lifecycle columns empty; the
 * lifecycle step (4b-2) replaces this, and also adds the triggers, routes and tabs.</p>
 */
public final class ZoneMatrixRunService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ZoneMatrixRunService.class);

    /** Section 9: at most two runs in total on the own pool. */
    public static final int THREADS = 2;
    /** Bounded queue of runs waiting for a pool thread. */
    public static final int QUEUE_CAPACITY = 64;
    /** Section 9 hard ceiling for the largest policy. */
    public static final Duration DEFAULT_TIME_LIMIT = Duration.ofSeconds(30);

    private static final Set<String> STORED_OUTCOMES = Set.of(ZoneMatrixEvaluator.OUTCOME_VIOLATION,
            ZoneMatrixEvaluator.OUTCOME_UNKNOWN, ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE);

    /** What {@link #submit} did: started a new run, or returned the policy's run that is still {@code RUNNING}. */
    public record Submission(String runId, boolean alreadyRunning, String state) {}

    private final ZoneMatrixRunRepository runs;
    private final ZoneMatrixRepository versions;
    private final PolicyCatalog catalog;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeLimit;
    private final ExecutorService pool;
    private final ScheduledExecutorService watchdog;
    private final Map<String, String> inFlight = new ConcurrentHashMap<>();
    private final Object admission = new Object();

    public ZoneMatrixRunService(ZoneMatrixRunRepository runs, ZoneMatrixRepository versions, PolicyCatalog catalog,
            ObjectMapper mapper, Clock clock, Duration timeLimit) {
        this(runs, versions, catalog, mapper, clock, timeLimit, newPool(), newWatchdog());
    }

    ZoneMatrixRunService(ZoneMatrixRunRepository runs, ZoneMatrixRepository versions, PolicyCatalog catalog,
            ObjectMapper mapper, Clock clock, Duration timeLimit, ExecutorService pool,
            ScheduledExecutorService watchdog) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.catalog = catalog;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeLimit == null || timeLimit.isNegative() || timeLimit.isZero()) {
            throw new IllegalArgumentException("the zone matrix run time limit must be positive");
        }
        this.timeLimit = timeLimit;
        this.pool = Objects.requireNonNull(pool, "pool");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
    }

    /** The own bounded pool: {@value #THREADS} daemon threads, a bounded queue, refusal when full. */
    static ThreadPoolExecutor newPool() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
                    Thread t = new Thread(r, "zone-matrix-run-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    static ScheduledExecutorService newWatchdog() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zone-matrix-run-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /** The production policy catalog, read exactly as {@link ZoneMatrixService#evaluate} reads it. */
    public static PolicyCatalog catalogOf(PolicyQueryService policies) {
        if (policies == null) return null;
        return new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return policies.catalogViews(); }
            @Override public Optional<PolicySnapshot> find(String policyId) {
                return policies.find(policyId).map(s -> ZoneMatrixPolicyBridge.withDomainObjects(policies, s));
            }
        };
    }

    /**
     * Starts a durable evaluation of one stored policy against the active version, or against the named published
     * version. Refuses (as {@link ZoneMatrixService#evaluate} does) a blank policy id, no active version, an unknown
     * version and a draft. Returns the existing run id when the policy already has a {@code RUNNING} run.
     */
    public Submission submit(String policyId, String versionId) {
        if (policyId == null || policyId.isBlank()) {
            throw new Refused(400, "INVALID_POLICY", "policy_id is required");
        }
        String policy = policyId.strip();
        Version version = resolve(versionId);
        String runId;
        synchronized (admission) {
            String inProcess = inFlight.get(policy);
            if (inProcess != null) return new Submission(inProcess, true, ZoneMatrixRunRepository.STATE_RUNNING);
            Optional<String> stored = runs.runningRunOf(policy);
            if (stored.isPresent()) return new Submission(stored.get(), true, ZoneMatrixRunRepository.STATE_RUNNING);
            runId = UUID.randomUUID().toString();
            try {
                runs.start(new ZoneMatrixRunRepository.NewRun(runId, version.versionId(), version.number(),
                        version.contentSha256(), policy, ZoneMatrixEvaluator.ENGINE_VERSION, clock.instant()));
            } catch (ZoneMatrixRunRepository.RunningRunExists e) {
                if (e.existingRunId() != null) {
                    return new Submission(e.existingRunId(), true, ZoneMatrixRunRepository.STATE_RUNNING);
                }
                throw new Refused(409, "RUN_IN_PROGRESS", "an evaluation of this policy is finishing; try again");
            }
            inFlight.put(policy, runId);
        }
        LOG.info("zone_matrix_run_started run_id={} policy_id={} version_id={} version_number={} content_sha256={} state={}",
                runId, policy, version.versionId(), version.number(), version.contentSha256(),
                ZoneMatrixRunRepository.STATE_RUNNING);
        try {
            pool.execute(new RunTask(runId, policy, version));
        } catch (RejectedExecutionException e) {
            inFlight.remove(policy, runId);
            failAndLog(runId, policy, version, FailureReason.POOL_SATURATED, 0L);
            return new Submission(runId, false, ZoneMatrixRunRepository.STATE_FAILED);
        }
        return new Submission(runId, false, ZoneMatrixRunRepository.STATE_RUNNING);
    }

    /** Startup recovery (section 5.1): every run left {@code RUNNING} ends {@code FAILED INTERRUPTED_AT_STARTUP}. */
    public int recoverInterrupted() {
        int failed = runs.failInterrupted(clock.instant());
        LOG.info("zone_matrix_runs_interrupted_at_startup count={}", failed);
        return failed;
    }

    /**
     * Orderly shutdown: no new run is accepted, queued and running runs get a short grace period (the watchdog keeps
     * enforcing the time limit meanwhile), then the pool and the watchdog stop. A run cut off here stays RUNNING and is
     * set FAILED by the next startup's recovery.
     */
    @Override public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            watchdog.shutdownNow();
        }
    }

    private Version resolve(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return versions.active().orElseThrow(() -> new Refused(
                    409, "NO_ACTIVE_VERSION", "publish a zone matrix version before evaluating policies"));
        }
        Version version = versions.find(versionId.strip())
                .orElseThrow(() -> new Refused(404, "NOT_FOUND", "no such version"));
        if (!version.state().equals("ACTIVE") && !version.state().equals("RETIRED")) {
            throw new Refused(409, "DRAFT_NOT_EVALUATED", "drafts are not evaluated; select a published version");
        }
        return version;
    }

    private ZoneMatrixContent content(Version version) {
        String json = version.content() != null ? version.content()
                : versions.find(version.versionId()).map(Version::content).orElseThrow();
        return ZoneMatrixContent.parse(json, mapper);
    }

    /** One run on a pool thread; the watchdog may interrupt it only while it is executing this run. */
    private final class RunTask implements Runnable {
        private final String runId;
        private final String policy;
        private final Version version;
        private Thread thread;

        RunTask(String runId, String policy, Version version) {
            this.runId = runId;
            this.policy = policy;
            this.version = version;
        }

        @Override public void run() {
            long t0 = System.nanoTime();
            synchronized (this) { thread = Thread.currentThread(); }
            ScheduledFuture<?> deadline = scheduleDeadline(t0);
            try {
                execute(t0);
            } catch (Throwable t) {
                // Anything the evaluation did not handle (an Error included) still ends the run, never leaves it RUNNING.
                failAndLog(runId, policy, version, FailureReason.ENGINE_ERROR, millisSince(t0), t);
            } finally {
                if (deadline != null) deadline.cancel(false);
                synchronized (this) {
                    thread = null;
                    Thread.interrupted();
                }
                inFlight.remove(policy, runId);
            }
        }

        private ScheduledFuture<?> scheduleDeadline(long t0) {
            try {
                return watchdog.schedule(() -> {
                    if (failAndLog(runId, policy, version, FailureReason.TIME_LIMIT, millisSince(t0))) {
                        synchronized (this) { if (thread != null) thread.interrupt(); }
                    }
                }, timeLimit.toNanos(), TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException e) {
                return null; // shutting down: the in-thread deadline check below still applies
            }
        }

        private void execute(long t0) {
            ZoneMatrixEvaluator.EvaluationResult result;
            PolicySnapshot snapshot;
            try {
                snapshot = catalog == null ? null : catalog.find(policy).orElse(null);
                if (snapshot == null) {
                    failAndLog(runId, policy, version, FailureReason.SNAPSHOT_MISSING, millisSince(t0));
                    return;
                }
                ZoneMatrixEvaluator.CompiledVersion compiled = ZoneMatrixEvaluator.compile(
                        version.versionId(), version.number(), content(version));
                result = ZoneMatrixEvaluator.evaluate(snapshot, compiled, clock.instant());
            } catch (RuntimeException e) {
                if (overTime(t0)) failAndLog(runId, policy, version, FailureReason.TIME_LIMIT, millisSince(t0));
                else failAndLog(runId, policy, version, FailureReason.ENGINE_ERROR, millisSince(t0), e);
                return;
            }
            if (overTime(t0)) {
                failAndLog(runId, policy, version, FailureReason.TIME_LIMIT, millisSince(t0));
                return;
            }
            Instant finishedAt = clock.instant();
            long duration = millisSince(t0);
            List<ZoneMatrixRunRepository.Finding> findings = findings(policy, result, finishedAt);
            ZoneMatrixRunRepository.Counts counts = counts(result, findings.size());
            boolean written;
            try {
                written = runs.complete(runId, finishedAt, duration, counts, coverage(snapshot, result), findings);
            } catch (RuntimeException e) {
                failAndLog(runId, policy, version, FailureReason.STORE_ERROR, duration, e);
                return;
            }
            if (written) {
                LOG.info("zone_matrix_run_finished run_id={} policy_id={} version_id={} version_number={} content_sha256={} state={} violation={} compliant={} not_evaluable={} unknown={} not_applicable={} findings={} duration_ms={}",
                        runId, policy, version.versionId(), version.number(), version.contentSha256(),
                        ZoneMatrixRunRepository.STATE_COMPLETE, counts.violation(), counts.compliant(),
                        counts.notEvaluable(), counts.unknown(), counts.notApplicable(), counts.findings(), duration);
            } else {
                LOG.info("zone_matrix_run_discarded run_id={} policy_id={} version_id={} version_number={} content_sha256={} state={} findings=0 duration_ms={}",
                        runId, policy, version.versionId(), version.number(), version.contentSha256(),
                        ZoneMatrixRunRepository.STATE_FAILED, duration);
            }
        }

        private boolean overTime(long t0) {
            return Thread.currentThread().isInterrupted() || System.nanoTime() - t0 > timeLimit.toNanos();
        }
    }

    private boolean failAndLog(String runId, String policy, Version version, FailureReason reason, long durationMillis) {
        return failAndLog(runId, policy, version, reason, durationMillis, null);
    }

    /** Ends the run FAILED when it is still RUNNING; logs ids, the failure code and the exception class only. */
    private boolean failAndLog(String runId, String policy, Version version, FailureReason reason, long durationMillis,
            Throwable cause) {
        boolean failed;
        try {
            failed = runs.fail(runId, reason, clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("zone_matrix_run_fail_unrecorded run_id={} policy_id={} failure_reason={} error_class={}",
                    runId, policy, reason, e.getClass().getSimpleName());
            return false;
        }
        if (failed) {
            LOG.warn("zone_matrix_run_failed run_id={} policy_id={} version_id={} version_number={} content_sha256={} state={} failure_reason={} findings=0 duration_ms={} error_class={}",
                    runId, policy, version.versionId(), version.number(), version.contentSha256(),
                    ZoneMatrixRunRepository.STATE_FAILED, reason, durationMillis,
                    cause == null ? "none" : cause.getClass().getSimpleName());
        }
        return failed;
    }

    private static long millisSince(long t0) { return (System.nanoTime() - t0) / 1_000_000L; }

    // ---------------------------------------------------------------- mapping

    static List<ZoneMatrixRunRepository.Finding> findings(String policy, ZoneMatrixEvaluator.EvaluationResult result,
            Instant observedAt) {
        List<ZoneMatrixRunRepository.Finding> out = new ArrayList<>();
        for (ZoneMatrixEvaluator.RuleEvaluation ev : result.rules()) {
            if (!STORED_OUTCOMES.contains(ev.outcome())) continue;
            List<ZoneMatrixRunRepository.Cell> cells = new ArrayList<>(ev.violatedCells().size());
            for (ZoneMatrixEvaluator.CellViolation c : ev.violatedCells()) {
                ZoneMatrixEvaluator.Witness w = c.witness();
                cells.add(new ZoneMatrixRunRepository.Cell(c.sourceZone(), c.destinationZone(), c.statement(),
                        c.severity(), c.notAllowedServices(), c.natView(),
                        w == null ? null : new ZoneMatrixRunRepository.Witness(w.sourceRange(), w.destinationRange(),
                                w.service(), w.sampleSourceIp(), w.sampleDestinationIp())));
            }
            ZoneMatrixEvaluator.FindingIdentity id = ev.identity();
            out.add(new ZoneMatrixRunRepository.Finding(
                    policy,
                    id != null && id.ruleUid() != null ? id.ruleUid() : ev.ruleUid(),
                    id != null ? id.inlineParentPath() : ev.inlineParentPath(),
                    id != null ? id.violatedPairs() : List.of(),
                    ev.ruleId(),
                    ev.ruleNumber(),
                    ev.ruleName(),
                    ev.sectionId(),
                    ev.sectionName(),
                    ev.targetDeviceIds(),
                    ev.outcome(),
                    ev.reason(),
                    ev.highestSeverity(),
                    List.copyOf(ev.flags()),
                    ev.coveringRuleId(),
                    cells,
                    ev.undecidedParts(),
                    ev.natViewSummary(),
                    observedAt,
                    null,
                    observedAt,
                    null,
                    null));
        }
        return out;
    }

    static ZoneMatrixRunRepository.Counts counts(ZoneMatrixEvaluator.EvaluationResult result, int findings) {
        Map<String, Integer> c = result.outcomeCounts();
        return new ZoneMatrixRunRepository.Counts(
                c.getOrDefault(ZoneMatrixEvaluator.OUTCOME_VIOLATION, 0),
                c.getOrDefault(ZoneMatrixEvaluator.OUTCOME_COMPLIANT, 0),
                c.getOrDefault(ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE, 0),
                c.getOrDefault(ZoneMatrixEvaluator.OUTCOME_UNKNOWN, 0),
                result.notApplicable() == null ? 0 : result.notApplicable().total(),
                findings);
    }

    static ZoneMatrixRunRepository.Coverage coverage(PolicySnapshot snapshot, ZoneMatrixEvaluator.EvaluationResult result) {
        List<String> codes = new ArrayList<>(result.coverageItems().size());
        for (ZoneMatrixEvaluator.CoverageItem item : result.coverageItems()) codes.add(item.code());
        return new ZoneMatrixRunRepository.Coverage(
                "COMPLETE".equals(result.coverage()) ? "COMPLETE" : "INCOMPLETE",
                codes,
                result.failureCount(),
                result.evaluated(),
                result.vendor() == null || result.vendor().isEmpty() ? null : result.vendor(),
                collectedAt(result.collectedAt()),
                snapshot.ruleCount());
    }

    /** The snapshot's collected_at as an instant; an unreadable value stays unknown (null), never guessed. */
    static Instant collectedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
