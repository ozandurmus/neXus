package com.securityexpert.nexus.ui2.persistence.zonematrix;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    enum FailureReason { TIME_LIMIT, INTERRUPTED_AT_STARTUP, ENGINE_ERROR, SNAPSHOT_MISSING, POOL_SATURATED, STORE_ERROR }

    /** What a new {@code RUNNING} row records about the version it evaluates against. */
    record NewRun(String runId, String versionId, int versionNumber, String contentSha256, String policySourceId,
            String engineVersion, Instant startedAt) {
        public NewRun {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(contentSha256, "contentSha256");
            Objects.requireNonNull(policySourceId, "policySourceId");
            Objects.requireNonNull(engineVersion, "engineVersion");
            Objects.requireNonNull(startedAt, "startedAt");
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

    /** A run row: ids, state, counts and coverage; never zone names, blocks, addresses or rule names. */
    record Run(String runId, String versionId, int versionNumber, String contentSha256, String policySourceId,
            String engineVersion, String state, String failureReason, Instant startedAt, Instant finishedAt,
            Instant snapshotCollectedAt, Integer snapshotRuleCount, String vendor, Boolean evaluated, String coverage,
            List<String> coverageItems, Integer collectionFailures, Counts counts, Long durationMillis) {
        public Run { coverageItems = coverageItems == null ? List.of() : List.copyOf(coverageItems); }
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

    /** Ends a {@code RUNNING} run {@code FAILED}; {@code false} when it was already finished. */
    boolean fail(String runId, FailureReason reason, Instant finishedAt);

    /** Sets every {@code RUNNING} run {@code FAILED} with {@link FailureReason#INTERRUPTED_AT_STARTUP}; returns the count. */
    int failInterrupted(Instant finishedAt);

    /** The newest {@code COMPLETE} run of a policy source. */
    Optional<Run> latestComplete(String policySourceId);

    /** The findings of a {@code COMPLETE} run in insertion order; empty for a run in any other state. */
    List<Finding> findingsOf(String completeRunId);

    /** The newest runs in any state, newest first: state and counts only (at most {@code limit}, capped at 500). */
    List<Run> runs(int limit);
}
