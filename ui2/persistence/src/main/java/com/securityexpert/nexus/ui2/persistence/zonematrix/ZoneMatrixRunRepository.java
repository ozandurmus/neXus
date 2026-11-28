package com.securityexpert.nexus.ui2.persistence.zonematrix;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Durable zone-matrix evaluation runs and their findings (ZONE_MATRIX_CONTRACT.md sections 5.1, 5.9 and 8.1, FROZEN
 * 2026-11-13; the {@code zone_matrix_run} / {@code zone_matrix_finding} tables of V267).
 *
 * <p>A run is {@code RUNNING} until {@link #complete} writes every finding and flips it to {@code COMPLETE} in one
 * transaction, or {@link #fail} ends it {@code FAILED}. The read methods return {@code COMPLETE} runs only, so partial
 * findings never become visible. Identifiers are opaque text compared exactly (identity law). Nothing here deletes.</p>
 */
public interface ZoneMatrixRunRepository {

    String STATE_RUNNING = "RUNNING";
    String STATE_COMPLETE = "COMPLETE";
    String STATE_FAILED = "FAILED";

    /** Fixed failure codes; never free text (section 8.2). */
    enum FailureReason { TIME_LIMIT, HEAP_LIMIT, INTERRUPTED_AT_STARTUP, ENGINE_ERROR, SNAPSHOT_MISSING, POOL_SATURATED, STORE_ERROR }

    /** What a new {@code RUNNING} row records about the version and snapshot it evaluates against. */
    record NewRun(String runId, String versionId, int versionNumber, String contentSha256, String policySourceId,
            String engineVersion, Instant startedAt, Instant snapshotCollectedAt) {
        public NewRun {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(contentSha256, "contentSha256");
            Objects.requireNonNull(policySourceId, "policySourceId");
            Objects.requireNonNull(engineVersion, "engineVersion");
            Objects.requireNonNull(startedAt, "startedAt");
        }

        public NewRun(String runId, String versionId, int versionNumber, String contentSha256, String policySourceId,
                String engineVersion, Instant startedAt) {
            this(runId, versionId, versionNumber, contentSha256, policySourceId, engineVersion, startedAt, null);
        }
    }

    /** The count of every outcome of one run, plus how many findings were stored. */
    record Counts(int violation, int compliant, int notEvaluable, int unknown, int notApplicable, int findings) {}

    /** What the run evaluated and how complete it was (section 5.9 coverage; item codes only, never their text). */
    record Coverage(String coverage, List<String> itemCodes, int collectionFailures, boolean evaluated, String vendor,
            Instant snapshotCollectedAt, Integer snapshotRuleCount) {
        public Coverage {
            Objects.requireNonNull(coverage, "coverage");
            itemCodes = List.copyOf(itemCodes);
        }
    }

    /** One violated cell as the engine returned it (section 5.7 step 5). */
    record Cell(String sourceZone, String destinationZone, String statement, String severity,
            List<String> notAllowedServices, String natView, Witness witness) {
        public Cell { notAllowedServices = List.copyOf(notAllowedServices); }
    }

    /** One witness tuple; readers project it (masked views never show it, section 8.2). */
    record Witness(String sourceRange, String destinationRange, String service, long sampleSourceIp,
            long sampleDestinationIp) {}

    /** One stored finding (a rule whose outcome is VIOLATION, UNKNOWN or NOT_EVALUABLE). */
    record Finding(
            String policySourceId,
            String ruleUid,
            List<String> inlineParentPath,
            List<String> violatedPairs,
            String ruleId,
            Integer ruleNumber,
            String ruleName,
            String sectionId,
            String sectionName,
            List<String> targetDeviceIds,
            String outcome,
            String reason,
            String highestSeverity,
            List<String> flags,
            String coveringRuleId,
            List<Cell> violatedCells,
            List<String> undecidedParts,
            String natView,
            Instant firstObservedAt,
            Instant firstObservedAnyVersion,
            Instant lastObservedAt,
            Instant resolvedAt,
            String resolutionReason) {
        public Finding {
            Objects.requireNonNull(policySourceId, "policySourceId");
            Objects.requireNonNull(ruleUid, "ruleUid");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            Objects.requireNonNull(lastObservedAt, "lastObservedAt");
            inlineParentPath = List.copyOf(inlineParentPath);
            violatedPairs = List.copyOf(violatedPairs);
            targetDeviceIds = List.copyOf(targetDeviceIds);
            flags = List.copyOf(flags);
            violatedCells = List.copyOf(violatedCells);
            undecidedParts = List.copyOf(undecidedParts);
        }
    }

    /** A stored finding from an earlier {@code COMPLETE} run of the policy source, paired with its run context. */
    record PriorFinding(long findingId, String runId, String versionId, int versionNumber, Instant runFinishedAt,
            boolean runCompleteScope, Finding finding) {
        public PriorFinding {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(finding, "finding");
        }
    }

    /** Chronological {@code COMPLETE}-run finding history of one policy source, plus its newest {@code COMPLETE} run id. */
    record PriorHistory(String latestCompleteRunId, List<PriorFinding> findings) {
        public PriorHistory { findings = List.copyOf(findings); }
    }

    /** Marks prior open finding row(s) of an exact identity resolved within the completing transaction. */
    record ResolutionUpdate(String ruleUid, List<String> inlineParentPath, List<String> violatedPairs,
            Instant resolvedAt, String resolutionReason) {
        public ResolutionUpdate {
            Objects.requireNonNull(ruleUid, "ruleUid");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
            Objects.requireNonNull(resolutionReason, "resolutionReason");
            inlineParentPath = List.copyOf(inlineParentPath);
            violatedPairs = List.copyOf(violatedPairs);
        }
    }

    /** One rule whose addresses touch unassigned private RFC 1918 / IPv6 ULA space (section 3.3, V276). */
    record ZoneDefinitionGap(
            String ruleId,
            Integer ruleNumber,
            String ruleName,
            String sectionName,
            List<String> unassignedPrivateRanges) {
        public ZoneDefinitionGap {
            unassignedPrivateRanges = unassignedPrivateRanges == null ? List.of() : List.copyOf(unassignedPrivateRanges);
        }
    }

    /** Lifecycle transition output applied atomically when completing a run. */
    record CompletionPlan(Counts counts, List<Finding> findingsToInsert, List<ResolutionUpdate> resolutions,
            List<ZoneDefinitionGap> zoneDefinitionGaps) {
        public CompletionPlan {
            Objects.requireNonNull(counts, "counts");
            findingsToInsert = List.copyOf(findingsToInsert);
            resolutions = List.copyOf(resolutions);
            zoneDefinitionGaps = zoneDefinitionGaps == null ? null : List.copyOf(zoneDefinitionGaps);
        }

        public CompletionPlan(Counts counts, List<Finding> findingsToInsert, List<ResolutionUpdate> resolutions) {
            this(counts, findingsToInsert, resolutions, null);
        }
    }

    /** A finding resolved with {@code MATRIX_VERSION_CHANGED}, citing both the opening and resolving versions (ZM-36). */
    record ResolvedByVersionFinding(String resolvedFromVersionId, int resolvedFromVersionNumber,
            String resolvedInVersionId, Integer resolvedInVersionNumber, Instant resolvedAt, Finding finding) {
        public ResolvedByVersionFinding {
            Objects.requireNonNull(resolvedFromVersionId, "resolvedFromVersionId");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
            Objects.requireNonNull(finding, "finding");
        }
    }

    /**
     * A finding resolved for any reason ({@code resolution_reason}, carried by {@code finding}) within a period, with the
     * version of the run it was found against (ZM-32). The resolving version is not read: only a version-change
     * resolution has one, and {@link ResolvedByVersionFinding} carries it.
     */
    record ResolvedFinding(String foundVersionId, int foundVersionNumber, Instant resolvedAt, Finding finding) {
        public ResolvedFinding {
            Objects.requireNonNull(foundVersionId, "foundVersionId");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
            Objects.requireNonNull(finding, "finding");
        }
    }

    /**
     * A run row: ids, state, counts, coverage and stored zone-definition gaps (V276; readers project rule metadata and
     * unassigned private ranges through fail-closed masking, sections 3.4 and 8.2). Null gaps mean not recorded;
     * an empty list means the evaluator recorded no gaps.
     */
    record Run(String runId, String versionId, int versionNumber, String contentSha256, String policySourceId,
            String engineVersion, String state, String failureReason, Instant startedAt, Instant finishedAt,
            Instant snapshotCollectedAt, Integer snapshotRuleCount, String vendor, Boolean evaluated, String coverage,
            List<String> coverageItems, Integer collectionFailures, Counts counts, Long durationMillis,
            List<ZoneDefinitionGap> zoneDefinitionGaps) {
        public Run {
            coverageItems = coverageItems == null ? List.of() : List.copyOf(coverageItems);
            zoneDefinitionGaps = zoneDefinitionGaps == null ? null : List.copyOf(zoneDefinitionGaps);
        }

        public Run(String runId, String versionId, int versionNumber, String contentSha256, String policySourceId,
                String engineVersion, String state, String failureReason, Instant startedAt, Instant finishedAt,
                Instant snapshotCollectedAt, Integer snapshotRuleCount, String vendor, Boolean evaluated, String coverage,
                List<String> coverageItems, Integer collectionFailures, Counts counts, Long durationMillis) {
            this(runId, versionId, versionNumber, contentSha256, policySourceId, engineVersion, state, failureReason,
                    startedAt, finishedAt, snapshotCollectedAt, snapshotRuleCount, vendor, evaluated, coverage,
                    coverageItems, collectionFailures, counts, durationMillis, null);
        }
    }

    /** Thrown by {@link #start} when the policy source already has a {@code RUNNING} run (partial unique index). */
    final class RunningRunExists extends RuntimeException {
        private final String existingRunId;
        public RunningRunExists(String existingRunId) {
            super("the policy source already has a running zone matrix run", null, false, false);
            this.existingRunId = existingRunId;
        }
        /** The run that holds the slot, or {@code null} when it finished between the refusal and the lookup. */
        public String existingRunId() { return existingRunId; }
    }

    /** Inserts a {@code RUNNING} row; refuses a second {@code RUNNING} run for the same policy source. */
    void start(NewRun run);

    /** The {@code RUNNING} run of a policy source, if any. */
    Optional<String> runningRunOf(String policySourceId);

    /**
     * Writes every finding and flips the run to {@code COMPLETE} in one transaction.
     *
     * @return {@code false} (and nothing written) when the run is no longer {@code RUNNING}, e.g. failed by the time
     *         limit or by startup recovery; a database error rolls the whole transaction back and propagates
     */
    boolean complete(String runId, Instant finishedAt, long durationMillis, Counts counts, Coverage coverage,
            List<Finding> findings);

    /**
     * Writes every finding, persists {@code zoneDefinitionGaps} (V276), and flips the run to {@code COMPLETE} in one
     * transaction.
     */
    default boolean complete(String runId, Instant finishedAt, long durationMillis, Counts counts, Coverage coverage,
            List<Finding> findings, List<ZoneDefinitionGap> zoneDefinitionGaps) {
        return complete(runId, finishedAt, durationMillis, counts, coverage, findings);
    }

    /**
     * Loads prior {@code COMPLETE}-run finding history for the run's policy source inside the {@code FOR UPDATE}
     * transaction, computes the {@link CompletionPlan}, updates resolved prior open rows (lifecycle columns only),
     * inserts this run's findings, persists {@code zone_definition_gaps}, and flips the run to {@code COMPLETE}
     * atomically.
     */
    default boolean complete(String runId, Instant finishedAt, long durationMillis, Coverage coverage,
            Function<PriorHistory, CompletionPlan> planner) {
        CompletionPlan plan = planner.apply(new PriorHistory(null, List.of()));
        return complete(runId, finishedAt, durationMillis, plan.counts(), coverage, plan.findingsToInsert(),
                plan.zoneDefinitionGaps());
    }

    /** Ends a {@code RUNNING} run {@code FAILED}; {@code false} when it was already finished. */
    boolean fail(String runId, FailureReason reason, Instant finishedAt);

    /** Ends a {@code RUNNING} run {@code FAILED}, recording the evaluated snapshot's {@code collected_at} when known. */
    default boolean fail(String runId, FailureReason reason, Instant finishedAt, Instant snapshotCollectedAt) {
        return fail(runId, reason, finishedAt);
    }

    /** Sets every {@code RUNNING} run {@code FAILED} with {@link FailureReason#INTERRUPTED_AT_STARTUP}; returns the count. */
    int failInterrupted(Instant finishedAt);

    /** The newest {@code COMPLETE} run of a policy source. */
    Optional<Run> latestComplete(String policySourceId);

    /** The newest run of a policy source in any state ({@code RUNNING}, {@code COMPLETE} or {@code FAILED}). */
    default Optional<Run> latestRun(String policySourceId) {
        return runs(500).stream().filter(r -> r.policySourceId().equals(policySourceId)).findFirst();
    }

    /** The newest finished ({@code COMPLETE} or {@code FAILED}) run of a policy source against {@code versionId}. */
    default Optional<Run> latestFinishedForVersion(String policySourceId, String versionId) {
        return runs(500).stream()
                .filter(r -> r.policySourceId().equals(policySourceId)
                        && r.versionId().equals(versionId)
                        && (STATE_COMPLETE.equals(r.state()) || STATE_FAILED.equals(r.state())))
                .findFirst();
    }

    /** The findings of a {@code COMPLETE} run in insertion order; empty for a run in any other state. */
    List<Finding> findingsOf(String completeRunId);

    /**
     * Findings resolved with {@code resolution_reason = 'MATRIX_VERSION_CHANGED'} (at or after {@code since} when
     * non-null), newest resolution first.
     */
    default List<ResolvedByVersionFinding> resolvedByVersionChange(Instant since) {
        return List.of();
    }

    /**
     * Findings of {@code COMPLETE} runs resolved within the half-open period {@code [from, to)} for any resolution reason
     * ({@code null} bound: unbounded), newest resolution first. Never capped: the period is the bound. One entry per
     * finding identity and resolution instant, carrying the earliest stored row's version.
     */
    default List<ResolvedFinding> resolvedInPeriod(Instant from, Instant to) {
        return List.of();
    }

    /** The newest runs in any state, newest first: state and counts only (at most {@code limit}, capped at 500). */
    List<Run> runs(int limit);

    /** One stored finding with its opaque database id ({@code zone_matrix_finding.finding_id}); the id is never parsed by readers. */
    record IdentifiedFinding(Long findingId, Finding finding) {
        public IdentifiedFinding { Objects.requireNonNull(finding, "finding"); }
    }

    /**
     * {@link #findingsOf} with each finding's opaque id, in the same order. A store that keeps no id answers {@code null} ids,
     * and a reader then offers no per-finding witness read.
     */
    default List<IdentifiedFinding> identifiedFindingsOf(String completeRunId) {
        return findingsOf(completeRunId).stream().map(f -> new IdentifiedFinding(null, f)).toList();
    }

    /**
     * The stored witness tuples and NAT views of one finding (section 8.1), with the run and version it was found against.
     * Readers project it: a masked view never shows it (section 8.2) and every read is on record (section 3.4).
     */
    record StoredFindingWitness(long findingId, String runId, String versionId, int versionNumber, String natView,
            List<Cell> violatedCells) {
        public StoredFindingWitness {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(versionId, "versionId");
            violatedCells = List.copyOf(violatedCells);
        }
    }

    /**
     * The stored witness tuples of the finding with this opaque id, when it belongs to a {@code COMPLETE} run; empty for an
     * unknown id or a finding of a run in any other state. Reads only; writes no audit row (the caller does, first).
     */
    default Optional<StoredFindingWitness> findingWitness(long findingId) {
        return Optional.empty();
    }
}
