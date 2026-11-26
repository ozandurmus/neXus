package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.CompletionPlan;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.FailureReason;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.PriorFinding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.PriorHistory;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolutionUpdate;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedByVersionFinding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedFinding;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.FindingIdentity;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.RuleEvaluation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.KnownFinding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Status;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.CountHeadline;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.CoveredPolicy;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyStaleness;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.RunState;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.StalenessThreshold;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Zone matrix delivery steps 4b-1 and 4b-2a: durable evaluation runs on the engine's own bounded pool, atomic finding
 * lifecycle transitions, publish and completed-collection triggers, and deterministic staleness / coverage / findings
 * projections (ZONE_MATRIX_CONTRACT.md sections 5.1, 5.9, 7.1, 7.2, 8.1-8.3, 9, and Amendment 2026-11-23).
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
 *   <li><b>One transaction with finding lifecycle</b> ({@code ZM-25}, {@code ZM-34}, {@code ZM-36}, {@code ZM-39},
 *       {@code ZM-42}). Prior {@code COMPLETE}-run findings of the policy source are read under the {@code FOR UPDATE}
 *       lock, passed through {@link ZoneMatrixFindingLifecycle#next}, resolved prior rows update only V267's granted
 *       lifecycle columns, and this run's findings and {@code COMPLETE} state commit together.</li>
 *   <li><b>Triggers</b> ({@code ZM-8}, section 5.1). Publishing an active version submits runs for every evaluable
 *       ({@code CP}, {@code PAN}) policy; {@link #submitCompletedCollections()} polls {@code PolicyCatalog} (which
 *       reflects completed {@code policy_snapshot} writes only, never {@code policy_collection_checkpoint}) and submits
 *       once per new {@code collected_at} against the active version without retrying a {@code FAILED} run on the same
 *       {@code (version_id, snapshot_collected_at)}.</li>
 *   <li><b>Logging</b> ({@code ZM-10}): run id, policy id, version id / number, content sha256, state, failure or
 *       refusal code, counts and duration only; never zone names, blocks, addresses or rule names, and never an
 *       exception message or stack trace.</li>
 * </ul>
 */
public final class ZoneMatrixRunService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ZoneMatrixRunService.class);

    /** Section 9: at most two runs in total on the own pool. */
    public static final int THREADS = 2;
    /** Bounded queue of runs waiting for a pool thread. */
    public static final int QUEUE_CAPACITY = 64;
    /** Section 9 hard ceiling for the largest policy. */
    public static final Duration DEFAULT_TIME_LIMIT = Duration.ofSeconds(30);
    /** Section 3.5 & 7.2: findings resolved by a version change are shown on the Overview for 7 days. */
    public static final Duration RESOLVED_BY_VERSION_OVERVIEW_WINDOW = Duration.ofDays(7);
    /** Section 5.8: fixed reason for non-evaluated ASA / FGT entries. */
    public static final String NOT_EVALUATED_REASON = "not evaluated: policy not collected";

    private static final Set<String> STORED_OUTCOMES = Set.of(ZoneMatrixEvaluator.OUTCOME_VIOLATION,
            ZoneMatrixEvaluator.OUTCOME_UNKNOWN, ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE);

    /** What {@link #submit} did: started a new run, or returned the policy's run that is still {@code RUNNING}. */
    public record Submission(String runId, boolean alreadyRunning, String state) {}

    /** Coverage and staleness assessment of one evaluable ({@code CP}, {@code PAN}) policy (sections 5.9, 7.1). */
    public record PolicyCoverage(
            String policyId,
            String policyName,
            String vendor,
            String sourceId,
            String sourceName,
            String containerId,
            String containerName,
            String collectedAt,
            int ruleCount,
            PolicyStaleness staleness,
            String coverage,
            List<String> coverageItems,
            int collectionFailures,
            ZoneMatrixRunRepository.Run latestCompleteRun,
            ZoneMatrixRunRepository.Run latestRun) {
        public PolicyCoverage {
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(staleness, "staleness");
            Objects.requireNonNull(coverage, "coverage");
            coverageItems = List.copyOf(coverageItems);
        }
    }

    /** A policy or device in {@link PolicyCatalog} whose vendor has no policy collection (section 5.8). */
    public record NotEvaluatedPolicy(
            String id,
            String name,
            String vendor,
            String sourceId,
            String sourceName,
            String reason) {
        public NotEvaluatedPolicy {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** One open or undecided finding from a policy's latest {@code COMPLETE} run, with its version and staleness. */
    public record StoredFinding(
            String runId,
            String versionId,
            int versionNumber,
            String contentSha256,
            ZoneMatrixStaleness.Verdict policyStaleness,
            Duration snapshotAge,
            ZoneMatrixRunRepository.Finding finding,
            Long findingId) {
        public StoredFinding {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(contentSha256, "contentSha256");
            Objects.requireNonNull(policyStaleness, "policyStaleness");
            Objects.requireNonNull(finding, "finding");
        }

        /** A stored finding without its opaque id (a store that keeps none): no per-finding witness read is offered. */
        public StoredFinding(String runId, String versionId, int versionNumber, String contentSha256,
                ZoneMatrixStaleness.Verdict policyStaleness, Duration snapshotAge, ZoneMatrixRunRepository.Finding finding) {
            this(runId, versionId, versionNumber, contentSha256, policyStaleness, snapshotAge, finding, null);
        }
    }

    /** Stored fleet headline against the active version (section 7.2, ZM-26, ZM-35). */
    public record StoredHeadline(
            String versionId,
            int versionNumber,
            int distinctViolatingRules,
            int violatedZonePairs,
            int criticalRules,
            int coveredByEarlierRule,
            int conditionalRules,
            int outsideHeadlineOrderedLayer,
            int notEvaluableFindings,
            int unknownFindings,
            int becameUndecidedFindings,
            int stalePolicies,
            int incompletePolicies,
            int olderVersionPolicies,
            int notEvaluatedPolicies,
            CountHeadline stalenessHeadline,
            boolean exact,
            String countText,
            String text,
            String outsideHeadlineText) {
        public StoredHeadline {
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(stalenessHeadline, "stalenessHeadline");
            Objects.requireNonNull(countText, "countText");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Open violations of one matrix cell on the active version (section 7.1 Matrix tab, 7.2 per-cell counts).
     * {@code headlineRules} = distinct open (policy source, rule uid) in the headline set that violate the cell;
     * {@code outsideHeadlineRules} = the same for rules that appear only in the outside-headline set;
     * {@code highestSeverity} is the highest severity among the counted violating cells, {@code null} when unknown.
     * A destination of {@code null} is a stored pair with no readable destination (shown as UNKNOWN).
     */
    public record CellCount(
            String sourceZoneId,
            String destinationZoneId,
            int headlineRules,
            int outsideHeadlineRules,
            String highestSeverity) {
        public CellCount {
            Objects.requireNonNull(sourceZoneId, "sourceZoneId");
        }
    }

    /** Complete deterministic read projection across stored runs, findings, staleness and version-change resolutions. */
    public record StoredSummary(
            Instant evaluatedAt,
            StalenessThreshold stalenessThreshold,
            Version activeVersion,
            List<PolicyCoverage> policies,
            List<NotEvaluatedPolicy> notEvaluated,
            List<StoredFinding> findings,
            StoredHeadline headline,
            List<ResolvedByVersionFinding> resolvedByVersionChangeLast7Days,
            Map<String, List<ResolvedByVersionFinding>> resolvedByVersionChangeByVersion,
            List<CellCount> cellCounts) {
        public StoredSummary {
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            Objects.requireNonNull(stalenessThreshold, "stalenessThreshold");
            policies = List.copyOf(policies);
            notEvaluated = List.copyOf(notEvaluated);
            findings = List.copyOf(findings);
            resolvedByVersionChangeLast7Days = List.copyOf(resolvedByVersionChangeLast7Days);
            Map<String, List<ResolvedByVersionFinding>> copy = new LinkedHashMap<>();
            resolvedByVersionChangeByVersion.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            resolvedByVersionChangeByVersion = Collections.unmodifiableMap(copy);
            // null = no active version, so no cell counts; an empty list = an active version with no open violation.
            cellCounts = cellCounts == null ? null : List.copyOf(cellCounts);
        }
    }

    private record HeadlineRuleKey(String policySourceId, String ruleUid) {}

    private record CellKey(String sourceZone, String destinationZone) {}

    private final ZoneMatrixRunRepository runs;
    private final ZoneMatrixRepository versions;
    private final PolicyCatalog catalog;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeLimit;
    private final StalenessThreshold stalenessThreshold;
    private final ExecutorService pool;
    private final ScheduledExecutorService watchdog;
    private final Map<String, String> inFlight = new ConcurrentHashMap<>();
    private final Object admission = new Object();

    public ZoneMatrixRunService(ZoneMatrixRunRepository runs, ZoneMatrixRepository versions, PolicyCatalog catalog,
            ObjectMapper mapper, Clock clock, Duration timeLimit) {
        this(runs, versions, catalog, mapper, clock, timeLimit, ZoneMatrixStaleness.INTERIM_PO_2026_11_23,
                newPool(), newWatchdog());
    }

    public ZoneMatrixRunService(ZoneMatrixRunRepository runs, ZoneMatrixRepository versions, PolicyCatalog catalog,
            ObjectMapper mapper, Clock clock, Duration timeLimit, StalenessThreshold stalenessThreshold) {
        this(runs, versions, catalog, mapper, clock, timeLimit, stalenessThreshold, newPool(), newWatchdog());
    }

    ZoneMatrixRunService(ZoneMatrixRunRepository runs, ZoneMatrixRepository versions, PolicyCatalog catalog,
            ObjectMapper mapper, Clock clock, Duration timeLimit, ExecutorService pool,
            ScheduledExecutorService watchdog) {
        this(runs, versions, catalog, mapper, clock, timeLimit, ZoneMatrixStaleness.INTERIM_PO_2026_11_23,
                pool, watchdog);
    }

    ZoneMatrixRunService(ZoneMatrixRunRepository runs, ZoneMatrixRepository versions, PolicyCatalog catalog,
            ObjectMapper mapper, Clock clock, Duration timeLimit, StalenessThreshold stalenessThreshold,
            ExecutorService pool, ScheduledExecutorService watchdog) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.catalog = catalog;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeLimit == null || timeLimit.isNegative() || timeLimit.isZero()) {
            throw new IllegalArgumentException("the zone matrix run time limit must be positive");
        }
        this.timeLimit = timeLimit;
        this.stalenessThreshold = stalenessThreshold == null
                ? ZoneMatrixStaleness.INTERIM_PO_2026_11_23
                : stalenessThreshold;
        this.pool = Objects.requireNonNull(pool, "pool");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
    }

    /** The configured staleness threshold (defaults to {@link ZoneMatrixStaleness#INTERIM_PO_2026_11_23}). */
    public StalenessThreshold stalenessThreshold() {
        return stalenessThreshold;
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
        return submitInternal(policy, versionId, catalogCollectedAt(policy));
    }

    private Submission submitInternal(String policy, String versionId, Instant knownSnapshotCollectedAt) {
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
                        version.contentSha256(), policy, ZoneMatrixEvaluator.ENGINE_VERSION, clock.instant(),
                        knownSnapshotCollectedAt));
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
            pool.execute(new RunTask(runId, policy, version, knownSnapshotCollectedAt));
        } catch (RejectedExecutionException e) {
            inFlight.remove(policy, runId);
            failAndLog(runId, policy, version, FailureReason.POOL_SATURATED, 0L, knownSnapshotCollectedAt, null);
            return new Submission(runId, false, ZoneMatrixRunRepository.STATE_FAILED);
        }
        return new Submission(runId, false, ZoneMatrixRunRepository.STATE_RUNNING);
    }

    /**
     * Post-publish trigger (section 5.1, ZM-8): submits durable runs against the newly published version for every
     * evaluable ({@code CP}, {@code PAN}) policy in {@link PolicyCatalog#catalogViews()}. Best-effort: saturation or
     * refusal on one policy is logged with safe tokens only (ZM-10) and never rolls back the published version.
     */
    public List<Submission> submitPublishedVersion(String versionId) {
        if (catalog == null || versionId == null || versionId.isBlank()) return List.of();
        List<Map<String, Object>> views;
        try {
            views = catalog.catalogViews();
        } catch (RuntimeException e) {
            LOG.warn("zone_matrix_publish_catalog_failed version_id={} error_class={}",
                    versionId, e.getClass().getSimpleName());
            return List.of();
        }
        List<Submission> out = new ArrayList<>();
        for (Map<String, Object> item : views) {
            String vendor = str(item.get("vendor"));
            if (!isEvaluableVendor(vendor)) continue;
            String id = str(item.get("id"));
            if (id == null || id.isBlank()) continue;
            String policy = id.strip();
            Instant snapCollectedAt = collectedAt(str(item.get("collectedAt")));
            try {
                out.add(submitInternal(policy, versionId, snapCollectedAt));
            } catch (Refused r) {
                LOG.warn("zone_matrix_publish_submit_refused policy_id={} version_id={} refusal_code={}",
                        policy, versionId, r.code);
            } catch (RuntimeException e) {
                LOG.warn("zone_matrix_publish_submit_failed policy_id={} version_id={} error_class={}",
                        policy, versionId, e.getClass().getSimpleName());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Completed-collection trigger (section 5.1, ZM-8): {@code policy_snapshot} changes only when a {@code CP} or
     * {@code PAN} policy collection finishes completely (checkpoints stay in {@code policy_collection_checkpoint}).
     * Submits a run against the active version when a policy has a parseable {@code collectedAt}, no {@code RUNNING}
     * run, and no finished ({@code COMPLETE} or {@code FAILED}) run for {@code (policyId, activeVersionId)} that
     * already evaluated that exact {@code snapshot_collected_at}.
     */
    public List<Submission> submitCompletedCollections() {
        if (catalog == null) return List.of();
        Optional<Version> activeOpt = versions.active();
        if (activeOpt.isEmpty()) return List.of();
        Version active = activeOpt.get();
        List<Map<String, Object>> views;
        try {
            views = catalog.catalogViews();
        } catch (RuntimeException e) {
            LOG.warn("zone_matrix_collection_catalog_failed version_id={} error_class={}",
                    active.versionId(), e.getClass().getSimpleName());
            return List.of();
        }
        List<Submission> out = new ArrayList<>();
        for (Map<String, Object> item : views) {
            String vendor = str(item.get("vendor"));
            if (!isEvaluableVendor(vendor)) continue;
            String id = str(item.get("id"));
            if (id == null || id.isBlank()) continue;
            String policy = id.strip();
            Instant snapCollectedAt = collectedAt(str(item.get("collectedAt")));
            if (snapCollectedAt == null) continue;
            if (inFlight.containsKey(policy) || runs.runningRunOf(policy).isPresent()) continue;
            Optional<ZoneMatrixRunRepository.Run> latestForVersion =
                    runs.latestFinishedForVersion(policy, active.versionId());
            if (latestForVersion.isPresent()
                    && snapCollectedAt.equals(latestForVersion.get().snapshotCollectedAt())) {
                continue;
            }
            try {
                Submission sub = submitInternal(policy, active.versionId(), snapCollectedAt);
                if (!sub.alreadyRunning()) out.add(sub);
            } catch (Refused r) {
                LOG.warn("zone_matrix_collection_submit_refused policy_id={} version_id={} refusal_code={}",
                        policy, active.versionId(), r.code);
            } catch (RuntimeException e) {
                LOG.warn("zone_matrix_collection_submit_failed policy_id={} version_id={} error_class={}",
                        policy, active.versionId(), e.getClass().getSimpleName());
            }
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- stored projections (step 4b-2a)

    /**
     * The stored witness tuples of one finding of a {@code COMPLETE} run (section 8.1), by its opaque id. Reads only: the
     * caller checks the right and writes the audit row before anything is returned ({@link ZoneMatrixService#findingWitness}).
     */
    public Optional<ZoneMatrixRunRepository.StoredFindingWitness> storedWitness(long findingId) {
        return runs.findingWitness(findingId);
    }

    /** Assembles stored coverage, staleness, findings, headline and version-change resolutions using the default threshold. */
    public StoredSummary storedSummary() {
        return storedSummary(stalenessThreshold);
    }

    /** Assembles stored coverage, staleness, findings, headline and version-change resolutions using {@code threshold}. */
    public StoredSummary storedSummary(StalenessThreshold threshold) {
        Objects.requireNonNull(threshold, "threshold");
        Instant now = clock.instant();
        Version active = versions.active().orElse(null);
        String activeVersionId = active == null ? null : active.versionId();

        List<Map<String, Object>> catalogItems = catalog == null ? List.of() : catalog.catalogViews();
        List<Map<String, Object>> evaluableItems = new ArrayList<>();
        List<NotEvaluatedPolicy> notEvaluated = new ArrayList<>();

        for (Map<String, Object> item : catalogItems) {
            String id = str(item.get("id"));
            if (id == null || id.isBlank()) continue;
            String vendor = str(item.get("vendor"));
            if (isEvaluableVendor(vendor)) {
                evaluableItems.add(item);
            } else {
                notEvaluated.add(new NotEvaluatedPolicy(
                        id.strip(),
                        str(item.get("name")),
                        vendor,
                        str(item.get("sourceId")),
                        str(item.get("sourceName")),
                        NOT_EVALUATED_REASON));
            }
        }

        evaluableItems.sort(Comparator
                .comparing((Map<String, Object> m) -> strOrEmpty(m.get("vendor")))
                .thenComparing(m -> strOrEmpty(m.get("id")).strip()));
        notEvaluated.sort(Comparator
                .comparing((NotEvaluatedPolicy p) -> strOrEmpty(p.vendor()))
                .thenComparing(NotEvaluatedPolicy::id));

        List<PolicyCoverage> policies = new ArrayList<>(evaluableItems.size());
        List<CoveredPolicy> coveredForHeadline = new ArrayList<>(evaluableItems.size());
        List<StoredFinding> storedFindings = new ArrayList<>();

        int stalePolicies = 0;
        int incompletePolicies = 0;
        int olderVersionPolicies = 0;
        int notEvaluableFindings = 0;
        int unknownFindings = 0;
        int becameUndecidedFindings = 0;

        Map<HeadlineRuleKey, List<ZoneMatrixRunRepository.Finding>> headlineByRule = new LinkedHashMap<>();
        Set<HeadlineRuleKey> outsideHeadlineRules = new LinkedHashSet<>();
        Set<String> headlineZonePairs = new LinkedHashSet<>();
        Map<CellKey, Set<HeadlineRuleKey>> headlineRulesByCell = new LinkedHashMap<>();
        Map<CellKey, Set<HeadlineRuleKey>> outsideRulesByCell = new LinkedHashMap<>();
        Map<CellKey, Map<HeadlineRuleKey, String>> severityByCell = new LinkedHashMap<>();

        for (Map<String, Object> item : evaluableItems) {
            String policyId = str(item.get("id")).strip();
            String collectedAt = str(item.get("collectedAt"));
            int ruleCount = item.get("ruleCount") instanceof Number n ? n.intValue() : 0;

            ZoneMatrixRunRepository.Run latestRun = runs.latestRun(policyId).orElse(null);
            ZoneMatrixRunRepository.Run latestComplete = runs.latestComplete(policyId).orElse(null);

            RunState lastRunState = latestRun == null ? null : toRunState(latestRun.state());
            String citedVersionId = latestComplete == null ? null : latestComplete.versionId();

            PolicyStaleness staleness = ZoneMatrixStaleness.assess(
                    new PolicyInput(policyId, collectedAt, lastRunState, citedVersionId, activeVersionId),
                    now,
                    threshold);

            String coverage = latestComplete != null && "COMPLETE".equals(latestComplete.coverage())
                    && (latestComplete.collectionFailures() == null || latestComplete.collectionFailures() == 0)
                    && Boolean.TRUE.equals(latestComplete.evaluated())
                    ? "COMPLETE"
                    : "INCOMPLETE";
            List<String> coverageItems = latestComplete == null ? List.of() : latestComplete.coverageItems();
            int failures = latestComplete == null || latestComplete.collectionFailures() == null
                    ? 0
                    : latestComplete.collectionFailures();

            if (staleness.isStale()) stalePolicies++;
            if (!"COMPLETE".equals(coverage)) incompletePolicies++;
            if (latestComplete != null && activeVersionId != null && !activeVersionId.equals(latestComplete.versionId())) {
                olderVersionPolicies++;
            }

            policies.add(new PolicyCoverage(
                    policyId,
                    str(item.get("name")),
                    str(item.get("vendor")),
                    str(item.get("sourceId")),
                    str(item.get("sourceName")),
                    str(item.get("containerId")),
                    str(item.get("containerName")),
                    collectedAt,
                    ruleCount,
                    staleness,
                    coverage,
                    coverageItems,
                    failures,
                    latestComplete,
                    latestRun));
            coveredForHeadline.add(new CoveredPolicy(staleness, coverage));

            if (latestComplete != null) {
                boolean onActiveVersion = activeVersionId != null && activeVersionId.equals(latestComplete.versionId());
                for (ZoneMatrixRunRepository.IdentifiedFinding identified : runs.identifiedFindingsOf(latestComplete.runId())) {
                    ZoneMatrixRunRepository.Finding f = identified.finding();
                    if (f.resolvedAt() != null) continue;
                    storedFindings.add(new StoredFinding(
                            latestComplete.runId(),
                            latestComplete.versionId(),
                            latestComplete.versionNumber(),
                            latestComplete.contentSha256(),
                            staleness.verdict(),
                            staleness.snapshotAge(),
                            f,
                            identified.findingId()));
                    if (ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE.equals(f.outcome())) notEvaluableFindings++;
                    else if (ZoneMatrixEvaluator.OUTCOME_UNKNOWN.equals(f.outcome())) unknownFindings++;
                    if (f.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED)) {
                        becameUndecidedFindings++;
                    }
                    if (onActiveVersion && ZoneMatrixEvaluator.OUTCOME_VIOLATION.equals(f.outcome())) {
                        HeadlineRuleKey rk = new HeadlineRuleKey(f.policySourceId(), f.ruleUid());
                        boolean outsideHeadline = f.flags().contains("DENIED_BY_OTHER_ORDERED_LAYER")
                                || f.flags().contains("OTHER_ORDERED_LAYERS_UNKNOWN");
                        tallyCells(f, rk, outsideHeadline ? outsideRulesByCell : headlineRulesByCell, severityByCell);
                        if (outsideHeadline) {
                            outsideHeadlineRules.add(rk);
                        } else {
                            headlineByRule.computeIfAbsent(rk, k -> new ArrayList<>()).add(f);
                            for (ZoneMatrixRunRepository.Cell c : f.violatedCells()) {
                                headlineZonePairs.add(c.sourceZone() + "->" + c.destinationZone());
                            }
                            if (f.violatedCells().isEmpty()) {
                                headlineZonePairs.addAll(f.violatedPairs());
                            }
                        }
                    }
                }
            }
        }

        outsideHeadlineRules.removeAll(headlineByRule.keySet());

        // Per-cell counts follow the headline: a rule in the headline set counts only as a headline rule, an
        // outside-headline rule counts only when it is not in the headline set. No active version: no cell counts (null).
        List<CellCount> cellCounts = null;
        if (active != null && active.number() != null) {
            Set<CellKey> cellKeys = new LinkedHashSet<>(headlineRulesByCell.keySet());
            cellKeys.addAll(outsideRulesByCell.keySet());
            cellCounts = new ArrayList<>();
            for (CellKey key : cellKeys) {
                Set<HeadlineRuleKey> inHeadline = headlineRulesByCell.getOrDefault(key, Set.of());
                Set<HeadlineRuleKey> outsideOnly = new LinkedHashSet<>(outsideRulesByCell.getOrDefault(key, Set.of()));
                outsideOnly.removeAll(headlineByRule.keySet());
                if (inHeadline.isEmpty() && outsideOnly.isEmpty()) continue;
                String highest = null;
                Map<HeadlineRuleKey, String> severities = severityByCell.getOrDefault(key, Map.of());
                for (HeadlineRuleKey rk : inHeadline) highest = higherSeverity(highest, severities.get(rk));
                for (HeadlineRuleKey rk : outsideOnly) highest = higherSeverity(highest, severities.get(rk));
                cellCounts.add(new CellCount(key.sourceZone(), key.destinationZone(), inHeadline.size(),
                        outsideOnly.size(), highest));
            }
        }

        StoredHeadline headline = null;
        if (active != null && active.number() != null) {
            int n = headlineByRule.size();
            int p = headlineZonePairs.size();
            int critical = 0;
            int covered = 0;
            int conditional = 0;
            for (List<ZoneMatrixRunRepository.Finding> group : headlineByRule.values()) {
                if (group.stream().anyMatch(f -> "CRITICAL".equals(f.highestSeverity()))) critical++;
                if (group.stream().anyMatch(f -> f.flags().contains("COVERED_BY_EARLIER_DENY")
                        || f.flags().contains("INLINE_LAYER_COVER"))) covered++;
                if (group.stream().anyMatch(f -> f.flags().contains("IDENTITY_IGNORED")
                        || f.flags().contains("TIME_IGNORED")
                        || f.flags().contains("CONDITION_IGNORED"))) conditional++;
            }
            int outside = outsideHeadlineRules.size();
            // One rule for both: exact only when staleness/coverage allow it and nothing is undecided (ZM-26).
            CountHeadline stalenessHeadline = ZoneMatrixStaleness.withUndecided(
                    ZoneMatrixStaleness.headline(n, coveredForHeadline),
                    notEvaluableFindings + unknownFindings + becameUndecidedFindings);
            boolean exact = stalenessHeadline.exact();
            String countText = exact ? Integer.toString(n) : "at least " + n;
            int versionNumber = active.number();
            String text;
            if (n == 0) {
                text = exact
                        ? "0 rules violate the zone matrix (v" + versionNumber + ")"
                        : "no proven violations in evaluated rules";
            } else {
                String ruleWord = n == 1 ? "rule violates" : "rules violate";
                String pairWord = p == 1 ? "zone pair" : "zone pairs";
                text = countText + " " + ruleWord + " the zone matrix (v" + versionNumber + ") across "
                        + p + " " + pairWord + "; " + critical + " critical, of which " + covered
                        + " covered by an earlier rule and " + conditional + " conditional (identity / time)";
            }
            String outsideText = outside > 0
                    ? "+" + outside + " outside the headline (other ordered layer denies or unknown)"
                    : null;
            headline = new StoredHeadline(
                    active.versionId(),
                    versionNumber,
                    n,
                    p,
                    critical,
                    covered,
                    conditional,
                    outside,
                    notEvaluableFindings,
                    unknownFindings,
                    becameUndecidedFindings,
                    stalePolicies,
                    incompletePolicies,
                    olderVersionPolicies,
                    notEvaluated.size(),
                    stalenessHeadline,
                    exact,
                    countText,
                    text,
                    outsideText);
        }

        List<ResolvedByVersionFinding> allResolvedByVersion = runs.resolvedByVersionChange(null);
        Instant cutoff = now.minus(RESOLVED_BY_VERSION_OVERVIEW_WINDOW);
        List<ResolvedByVersionFinding> recentResolved = allResolvedByVersion.stream()
                .filter(r -> !r.resolvedAt().isBefore(cutoff) && !r.resolvedAt().isAfter(now))
                .toList();
        Map<String, List<ResolvedByVersionFinding>> byVersion = groupByResolvingVersion(allResolvedByVersion);

        return new StoredSummary(
                now,
                threshold,
                active,
                policies,
                notEvaluated,
                storedFindings,
                headline,
                recentResolved,
                byVersion,
                cellCounts);
    }

    /**
     * Adds {@code rule} to the tally of each cell {@code f} violates: its {@code violatedCells()}, else its
     * {@code violatedPairs()} "src->dst" strings (a pair with no arrow keeps a {@code null} destination), the same
     * fallback the headline's zone-pair set uses. Remembers the highest severity per (cell, rule).
     */
    private static void tallyCells(ZoneMatrixRunRepository.Finding f, HeadlineRuleKey rule,
            Map<CellKey, Set<HeadlineRuleKey>> rulesByCell, Map<CellKey, Map<HeadlineRuleKey, String>> severityByCell) {
        if (!f.violatedCells().isEmpty()) {
            for (ZoneMatrixRunRepository.Cell c : f.violatedCells()) {
                tallyCell(new CellKey(c.sourceZone(), c.destinationZone()), c.severity(), rule, rulesByCell, severityByCell);
            }
            return;
        }
        for (String pair : f.violatedPairs()) {
            int arrow = pair.indexOf("->");
            CellKey key = arrow < 0
                    ? new CellKey(pair, null)
                    : new CellKey(pair.substring(0, arrow), pair.substring(arrow + 2));
            tallyCell(key, null, rule, rulesByCell, severityByCell);
        }
    }

    private static void tallyCell(CellKey key, String severity, HeadlineRuleKey rule,
            Map<CellKey, Set<HeadlineRuleKey>> rulesByCell, Map<CellKey, Map<HeadlineRuleKey, String>> severityByCell) {
        if (key.sourceZone() == null) return;
        rulesByCell.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(rule);
        severityByCell.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(rule, severity == null ? "" : severity,
                ZoneMatrixRunService::higherSeverityOrEmpty);
    }

    private static final List<String> SEVERITY_RANK = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private static int severityRank(String severity) {
        int i = severity == null ? -1 : SEVERITY_RANK.indexOf(severity);
        return i < 0 ? SEVERITY_RANK.size() : i;
    }

    /** The higher of two severities; {@code null} / unknown values lose against a known one, both unknown stays {@code null}. */
    private static String higherSeverity(String a, String b) {
        String x = a != null && SEVERITY_RANK.contains(a) ? a : null;
        String y = b != null && SEVERITY_RANK.contains(b) ? b : null;
        if (x == null) return y;
        if (y == null) return x;
        return severityRank(y) < severityRank(x) ? y : x;
    }

    private static String higherSeverityOrEmpty(String a, String b) {
        String h = higherSeverity(a, b);
        return h == null ? "" : h;
    }

    /** Findings resolved with {@code MATRIX_VERSION_CHANGED} at or after {@code since} (all when {@code since == null}). */
    public List<ResolvedByVersionFinding> resolvedByVersionChange(Instant since) {
        return runs.resolvedByVersionChange(since);
    }

    /**
     * Findings resolved for any reason within the half-open period {@code [from, to)} (ZM-32), newest resolution first.
     * Read once per report assembly; the period bounds the read and nothing is capped.
     */
    public List<ResolvedFinding> resolvedInPeriod(Instant from, Instant to) {
        return runs.resolvedInPeriod(from, to);
    }

    /** Findings resolved with {@code MATRIX_VERSION_CHANGED}, grouped by the version id of the resolving run (ZM-36). */
    public Map<String, List<ResolvedByVersionFinding>> resolvedByVersionChangeByVersion() {
        return groupByResolvingVersion(runs.resolvedByVersionChange(null));
    }

    private static Map<String, List<ResolvedByVersionFinding>> groupByResolvingVersion(
            List<ResolvedByVersionFinding> list) {
        Map<String, List<ResolvedByVersionFinding>> grouped = new LinkedHashMap<>();
        for (ResolvedByVersionFinding item : list) {
            String key = item.resolvedInVersionId() != null
                    ? item.resolvedInVersionId()
                    : item.resolvedFromVersionId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        grouped.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(grouped);
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

    private Instant catalogCollectedAt(String policyId) {
        if (catalog == null) return null;
        try {
            for (Map<String, Object> item : catalog.catalogViews()) {
                String id = str(item.get("id"));
                if (id != null && policyId.equals(id.strip())) {
                    return collectedAt(str(item.get("collectedAt")));
                }
            }
        } catch (RuntimeException ignored) {
            // Snapshot loading inside RunTask handles unreadable catalog state.
        }
        return null;
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
        private volatile Instant snapshotCollectedAt;
        private Thread thread;

        RunTask(String runId, String policy, Version version, Instant initialSnapshotCollectedAt) {
            this.runId = runId;
            this.policy = policy;
            this.version = version;
            this.snapshotCollectedAt = initialSnapshotCollectedAt;
        }

        @Override public void run() {
            long t0 = System.nanoTime();
            synchronized (this) { thread = Thread.currentThread(); }
            ScheduledFuture<?> deadline = scheduleDeadline(t0);
            try {
                execute(t0);
            } catch (Throwable t) {
                // Anything the evaluation did not handle (an Error included) still ends the run, never leaves it RUNNING.
                failAndLog(runId, policy, version, FailureReason.ENGINE_ERROR, millisSince(t0), snapshotCollectedAt, t);
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
                    if (failAndLog(runId, policy, version, FailureReason.TIME_LIMIT, millisSince(t0),
                            snapshotCollectedAt, null)) {
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
                    failAndLog(runId, policy, version, FailureReason.SNAPSHOT_MISSING, millisSince(t0),
                            snapshotCollectedAt, null);
                    return;
                }
                if (snapshot.metadata() != null) {
                    Instant parsed = collectedAt(snapshot.metadata().collectedAt());
                    if (parsed != null) snapshotCollectedAt = parsed;
                }
                ZoneMatrixEvaluator.CompiledVersion compiled = ZoneMatrixEvaluator.compile(
                        version.versionId(), version.number(), content(version));
                result = ZoneMatrixEvaluator.evaluate(snapshot, compiled, clock.instant());
            } catch (RuntimeException e) {
                if (overTime(t0)) {
                    failAndLog(runId, policy, version, FailureReason.TIME_LIMIT, millisSince(t0),
                            snapshotCollectedAt, null);
                } else {
                    failAndLog(runId, policy, version, FailureReason.ENGINE_ERROR, millisSince(t0),
                            snapshotCollectedAt, e);
                }
                return;
            }
            if (overTime(t0)) {
                failAndLog(runId, policy, version, FailureReason.TIME_LIMIT, millisSince(t0),
                        snapshotCollectedAt, null);
                return;
            }
            Instant finishedAt = clock.instant();
            long duration = millisSince(t0);
            ZoneMatrixRunRepository.Coverage runCoverage = coverage(snapshot, result);
            ZoneMatrixRunRepository.Counts[] completedCounts = new ZoneMatrixRunRepository.Counts[1];
            boolean written;
            try {
                written = runs.complete(runId, finishedAt, duration, runCoverage, history -> {
                    CompletionPlan plan = planCompletion(policy, result, history, finishedAt);
                    completedCounts[0] = plan.counts();
                    return plan;
                });
            } catch (RuntimeException e) {
                failAndLog(runId, policy, version, FailureReason.STORE_ERROR, duration, snapshotCollectedAt, e);
                return;
            }
            if (written) {
                ZoneMatrixRunRepository.Counts counts = completedCounts[0];
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

    /** Ends the run FAILED when it is still RUNNING; logs ids, the failure code and the exception class only. */
    private boolean failAndLog(String runId, String policy, Version version, FailureReason reason, long durationMillis,
            Instant snapshotCollectedAt, Throwable cause) {
        boolean failed;
        try {
            failed = runs.fail(runId, reason, clock.instant(), snapshotCollectedAt);
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

    // ---------------------------------------------------------------- lifecycle & mapping

    private record RuleKey(String policyId, String ruleUid, List<String> parentPath) {}

    private record FullKey(String policyId, String ruleUid, List<String> parentPath, Set<String> pairs) {}

    private record Provenance(String versionId, Instant runFinishedAt) {}

    private static RuleKey ruleKey(FindingIdentity id) {
        return new RuleKey(id.policyId(), id.ruleUid(), id.inlineParentPath());
    }

    private static FullKey fullKey(FindingIdentity id) {
        return new FullKey(id.policyId(), id.ruleUid(), id.inlineParentPath(), Set.copyOf(id.violatedPairs()));
    }

    static CompletionPlan planCompletion(String policy, ZoneMatrixEvaluator.EvaluationResult rawResult,
            PriorHistory history, Instant finishedAt) {
        ZoneMatrixEvaluator.EvaluationResult result = policy.equals(rawResult.policyId())
                ? rawResult
                : rekeyPolicy(rawResult, policy);

        String latestRunId = history.latestCompleteRunId();
        List<KnownFinding> known = new ArrayList<>();
        Map<FullKey, ZoneMatrixRunRepository.Finding> openStoredByFullKey = new LinkedHashMap<>();
        Map<FullKey, Provenance> provenByKey = new HashMap<>();

        for (PriorFinding pf : history.findings()) {
            ZoneMatrixRunRepository.Finding f = pf.finding();
            if (f.violatedPairs().isEmpty()) continue;

            FindingIdentity id = new FindingIdentity(policy, f.ruleUid(), f.inlineParentPath(), f.violatedPairs());
            FullKey fk = fullKey(id);

            if (ZoneMatrixEvaluator.OUTCOME_VIOLATION.equals(f.outcome())
                    && !f.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED)) {
                Provenance prev = provenByKey.get(fk);
                if (prev == null || pf.runCompleteScope()
                        || (pf.runFinishedAt() != null
                                && f.lastObservedAt().equals(pf.runFinishedAt())
                                && !pf.runFinishedAt().equals(prev.runFinishedAt()))) {
                    provenByKey.put(fk, new Provenance(pf.versionId(), pf.runFinishedAt()));
                }
            }

            Provenance proven = provenByKey.get(fk);
            String citedVersionId = proven != null ? proven.versionId() : pf.versionId();
            if (f.resolvedAt() != null) {
                provenByKey.remove(fk);
            }

            Instant anyVersion = f.firstObservedAnyVersion() != null
                    && !f.firstObservedAnyVersion().isAfter(f.firstObservedAt())
                    ? f.firstObservedAnyVersion()
                    : f.firstObservedAt();

            boolean isOpen = pf.runId().equals(latestRunId) && f.resolvedAt() == null;
            if (isOpen && !openStoredByFullKey.containsKey(fk)) {
                openStoredByFullKey.put(fk, f);
                known.add(new KnownFinding(id, Status.OPEN, f.firstObservedAt(), anyVersion, f.lastObservedAt(),
                        f.outcome(), Set.copyOf(f.flags()), citedVersionId));
            } else {
                known.add(new KnownFinding(id, Status.RESOLVED, f.firstObservedAt(), anyVersion, f.lastObservedAt(),
                        f.outcome(), Set.copyOf(f.flags()), citedVersionId));
            }
        }

        ZoneMatrixFindingLifecycle.Result lifecycle = ZoneMatrixFindingLifecycle.next(known, result, finishedAt);

        Map<FullKey, RuleEvaluation> violationsByFullKey = new LinkedHashMap<>();
        Map<RuleKey, RuleEvaluation> undecidedByRuleKey = new LinkedHashMap<>();
        for (RuleEvaluation ev : result.rules()) {
            if (ZoneMatrixEvaluator.OUTCOME_VIOLATION.equals(ev.outcome())) {
                violationsByFullKey.putIfAbsent(fullKey(ev.identity()), ev);
            } else if (ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE.equals(ev.outcome())
                    || ZoneMatrixEvaluator.OUTCOME_UNKNOWN.equals(ev.outcome())) {
                undecidedByRuleKey.putIfAbsent(ruleKey(ev.identity()), ev);
            }
        }

        List<ZoneMatrixRunRepository.Finding> toInsert = new ArrayList<>();
        List<ResolutionUpdate> resolutions = new ArrayList<>();
        Set<RuleKey> consumedUndecidedRuleKeys = new LinkedHashSet<>();

        for (ZoneMatrixFindingLifecycle.Finding lf : lifecycle.findings()) {
            FullKey fk = fullKey(lf.identity());
            switch (lf.transition()) {
                case RESOLVED -> resolutions.add(new ResolutionUpdate(
                        lf.identity().ruleUid(),
                        lf.identity().inlineParentPath(),
                        lf.identity().violatedPairs(),
                        lf.resolvedAt(),
                        lf.resolutionReason().name()));
                case NEW, REPRODUCED -> {
                    RuleEvaluation ev = violationsByFullKey.get(fk);
                    if (ev != null) toInsert.add(toStoredViolation(policy, ev, lf));
                }
                case BECAME_UNDECIDED -> {
                    ZoneMatrixRunRepository.Finding prior = openStoredByFullKey.get(fk);
                    RuleKey rk = ruleKey(lf.identity());
                    consumedUndecidedRuleKeys.add(rk);
                    RuleEvaluation ev = undecidedByRuleKey.get(rk);
                    if (prior != null) toInsert.add(toBecameUndecided(policy, prior, ev, lf));
                }
                case STILL_OPEN_NOT_COLLECTED -> {
                    ZoneMatrixRunRepository.Finding prior = openStoredByFullKey.get(fk);
                    if (prior != null) toInsert.add(toCarriedOver(prior, lf));
                }
            }
        }

        for (RuleEvaluation ev : result.rules()) {
            if (!ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE.equals(ev.outcome())
                    && !ZoneMatrixEvaluator.OUTCOME_UNKNOWN.equals(ev.outcome())) {
                continue;
            }
            if (consumedUndecidedRuleKeys.contains(ruleKey(ev.identity()))) continue;
            toInsert.add(toStandaloneUndecided(policy, ev, finishedAt));
        }

        ZoneMatrixRunRepository.Counts counts = counts(result, toInsert.size());
        return new CompletionPlan(counts, toInsert, resolutions);
    }

    private static ZoneMatrixEvaluator.EvaluationResult rekeyPolicy(
            ZoneMatrixEvaluator.EvaluationResult r, String policy) {
        List<RuleEvaluation> rules = new ArrayList<>(r.rules().size());
        for (RuleEvaluation ev : r.rules()) {
            FindingIdentity oldId = ev.identity();
            FindingIdentity newId = new FindingIdentity(
                    policy,
                    oldId != null && oldId.ruleUid() != null ? oldId.ruleUid() : ev.ruleUid(),
                    oldId != null ? oldId.inlineParentPath() : ev.inlineParentPath(),
                    oldId != null ? oldId.violatedPairs() : List.of());
            rules.add(new RuleEvaluation(newId, ev.ruleId(), ev.ruleUid(), ev.ruleNumber(), ev.ruleName(),
                    ev.sectionId(), ev.sectionName(), ev.inlineParentPath(), ev.targetDeviceIds(),
                    ev.vendorFromZones(), ev.vendorToZones(), ev.outcome(), ev.reason(), ev.highestSeverity(),
                    ev.flags(), ev.coveringRuleId(), ev.touchedPairs(), ev.violatedCells(), ev.undecidedParts(),
                    ev.natViewSummary()));
        }
        return new ZoneMatrixEvaluator.EvaluationResult(r.engineVersion(), r.version(), policy, r.policyName(),
                r.sourceId(), r.sourceName(), r.containerId(), r.containerName(), r.vendor(), r.collectedAt(),
                r.targets(), r.evaluated(), r.notEvaluatedReason(), r.coverage(), r.coverageItems(), r.failureCount(),
                r.outcomeCounts(), r.notApplicable(), r.headline(), rules, r.zoneDefinitionGaps(),
                r.hygieneBudgetReached(), r.durationMillis(), r.hygieneMillis());
    }

    private static List<ZoneMatrixRunRepository.Cell> mapCells(List<ZoneMatrixEvaluator.CellViolation> cellViolations) {
        List<ZoneMatrixRunRepository.Cell> cells = new ArrayList<>(cellViolations.size());
        for (ZoneMatrixEvaluator.CellViolation c : cellViolations) {
            ZoneMatrixEvaluator.Witness w = c.witness();
            cells.add(new ZoneMatrixRunRepository.Cell(c.sourceZone(), c.destinationZone(), c.statement(),
                    c.severity(), c.notAllowedServices(), c.natView(),
                    w == null ? null : new ZoneMatrixRunRepository.Witness(w.sourceRange(), w.destinationRange(),
                            w.service(), w.sampleSourceIp(), w.sampleDestinationIp())));
        }
        return cells;
    }

    private static ZoneMatrixRunRepository.Finding toStoredViolation(
            String policy, RuleEvaluation ev, ZoneMatrixFindingLifecycle.Finding lf) {
        return new ZoneMatrixRunRepository.Finding(
                policy,
                lf.identity().ruleUid(),
                lf.identity().inlineParentPath(),
                lf.identity().violatedPairs(),
                ev.ruleId(),
                ev.ruleNumber(),
                ev.ruleName(),
                ev.sectionId(),
                ev.sectionName(),
                ev.targetDeviceIds(),
                lf.outcome(),
                lf.reason() != null ? lf.reason() : ev.reason(),
                ev.highestSeverity(),
                List.copyOf(lf.flags()),
                ev.coveringRuleId(),
                mapCells(ev.violatedCells()),
                ev.undecidedParts(),
                ev.natViewSummary(),
                lf.firstObservedAt(),
                lf.firstObservedAnyVersion(),
                lf.lastObservedAt(),
                null,
                null);
    }

    private static ZoneMatrixRunRepository.Finding toBecameUndecided(
            String policy,
            ZoneMatrixRunRepository.Finding prior,
            RuleEvaluation ev,
            ZoneMatrixFindingLifecycle.Finding lf) {
        return new ZoneMatrixRunRepository.Finding(
                policy,
                lf.identity().ruleUid(),
                lf.identity().inlineParentPath(),
                prior.violatedPairs(),
                ev != null ? ev.ruleId() : prior.ruleId(),
                ev != null ? ev.ruleNumber() : prior.ruleNumber(),
                ev != null ? ev.ruleName() : prior.ruleName(),
                ev != null ? ev.sectionId() : prior.sectionId(),
                ev != null ? ev.sectionName() : prior.sectionName(),
                ev != null ? ev.targetDeviceIds() : prior.targetDeviceIds(),
                lf.outcome(),
                lf.reason(),
                prior.highestSeverity(),
                List.copyOf(lf.flags()),
                prior.coveringRuleId(),
                prior.violatedCells(),
                ev != null && !ev.undecidedParts().isEmpty() ? ev.undecidedParts() : prior.undecidedParts(),
                prior.natView(),
                prior.firstObservedAt(),
                prior.firstObservedAnyVersion() != null
                        ? prior.firstObservedAnyVersion()
                        : lf.firstObservedAnyVersion(),
                prior.lastObservedAt(),
                null,
                null);
    }

    private static ZoneMatrixRunRepository.Finding toCarriedOver(
            ZoneMatrixRunRepository.Finding prior, ZoneMatrixFindingLifecycle.Finding lf) {
        return new ZoneMatrixRunRepository.Finding(
                prior.policySourceId(),
                prior.ruleUid(),
                prior.inlineParentPath(),
                prior.violatedPairs(),
                prior.ruleId(),
                prior.ruleNumber(),
                prior.ruleName(),
                prior.sectionId(),
                prior.sectionName(),
                prior.targetDeviceIds(),
                prior.outcome(),
                prior.reason(),
                prior.highestSeverity(),
                prior.flags(),
                prior.coveringRuleId(),
                prior.violatedCells(),
                prior.undecidedParts(),
                prior.natView(),
                prior.firstObservedAt(),
                prior.firstObservedAnyVersion() != null
                        ? prior.firstObservedAnyVersion()
                        : lf.firstObservedAnyVersion(),
                prior.lastObservedAt(),
                null,
                null);
    }

    private static ZoneMatrixRunRepository.Finding toStandaloneUndecided(
            String policy, RuleEvaluation ev, Instant observedAt) {
        FindingIdentity id = ev.identity();
        return new ZoneMatrixRunRepository.Finding(
                policy,
                id != null && id.ruleUid() != null ? id.ruleUid() : ev.ruleUid(),
                id != null ? id.inlineParentPath() : ev.inlineParentPath(),
                List.of(),
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
                mapCells(ev.violatedCells()),
                ev.undecidedParts(),
                ev.natViewSummary(),
                observedAt,
                null,
                observedAt,
                null,
                null);
    }

    static List<ZoneMatrixRunRepository.Finding> findings(String policy, ZoneMatrixEvaluator.EvaluationResult result,
            Instant observedAt) {
        return planCompletion(policy, result, new PriorHistory(null, List.of()), observedAt).findingsToInsert();
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
        String text = value.strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // Try offset form next.
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isEvaluableVendor(String vendor) {
        return "CP".equals(vendor) || "PAN".equals(vendor);
    }

    private static RunState toRunState(String state) {
        if (ZoneMatrixRunRepository.STATE_RUNNING.equals(state)) return RunState.RUNNING;
        if (ZoneMatrixRunRepository.STATE_COMPLETE.equals(state)) return RunState.COMPLETE;
        if (ZoneMatrixRunRepository.STATE_FAILED.equals(state)) return RunState.FAILED;
        return null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String strOrEmpty(Object o) { return o == null ? "" : String.valueOf(o); }
}
