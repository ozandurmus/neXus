package com.securityexpert.nexus.ui2.service.zonematrix;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-policy staleness and the "at least N" headline rule (docs/design/ZONE_MATRIX_CONTRACT.md section 5.9;
 * {@code ZM-35}).
 *
 * <p>Pure and deterministic: no Spring, no database, no clock ({@code now} is a parameter). The caller is the step 4
 * storage layer, which supplies the snapshot time, the last run state and the cited and active version ids. Nothing
 * here is wired into a service or controller.</p>
 *
 * <p>A stale policy keeps its findings listed with their age ({@link PolicyStaleness#snapshotAge()}); staleness never
 * resolves a finding and never turns a count into an exact one.</p>
 */
public final class ZoneMatrixStaleness {
    private ZoneMatrixStaleness() {}

    /** Last run states of the durable run table (contract 5.1). */
    public enum RunState { RUNNING, COMPLETE, FAILED }

    public enum Verdict { CURRENT, STALE }

    public enum Reason {
        SNAPSHOT_OLDER_THAN_THRESHOLD,
        LAST_RUN_FAILED,
        VERSION_NOT_ACTIVE,
        /**
         * Not in the contract's list: the snapshot time is missing, unparseable or in the future, so its age cannot be
         * stated. Never {@code CURRENT} (fail closed); reported under its own name instead of a false "older than".
         */
        SNAPSHOT_TIME_UNKNOWN
    }

    /**
     * Staleness threshold. The contract (6.2 item 9) makes it a bank input and forbids the product from filling a bank
     * field itself, so the value always travels with the statement of where it came from.
     */
    public record StalenessThreshold(Duration value, String source) {
        public StalenessThreshold {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("threshold must be positive");
            }
            if (source.isBlank()) {
                throw new IllegalArgumentException("threshold source must be stated");
            }
        }
    }

    /**
     * Interim value set by the Product Owner on 2026-11-23 until the bank states its own. It is a labelled interim
     * default, not the bank's value.
     */
    public static final StalenessThreshold INTERIM_PO_2026_11_23 =
            new StalenessThreshold(Duration.ofHours(24), "interim (PO 2026-11-23) until the bank states it");

    /**
     * Inputs for one policy. {@code collectedAt} is the stored snapshot time (ISO-8601 instant text, as the snapshot
     * metadata carries it). {@code lastRunState} is {@code null} when the policy never had a run.
     * {@code citedVersionId} is the version of the last {@code COMPLETE} run ({@code null} when none);
     * {@code activeVersionId} is the active matrix version ({@code null} when none).
     */
    public record PolicyInput(
            String policyId,
            String collectedAt,
            RunState lastRunState,
            String citedVersionId,
            String activeVersionId) {}

    /** Assessment of one policy. {@code snapshotAge} is {@code null} when the snapshot time is unknown. */
    public record PolicyStaleness(
            String policyId,
            Verdict verdict,
            List<Reason> reasons,
            Duration snapshotAge,
            StalenessThreshold threshold) {
        public PolicyStaleness {
            reasons = List.copyOf(reasons);
        }

        public boolean isStale() {
            return verdict == Verdict.STALE;
        }
    }

    /**
     * Assesses one policy. A snapshot is older than the threshold only when its age is strictly greater (exactly the
     * threshold is still {@code CURRENT}). A version is active only when both ids are present and equal ({@code equals},
     * identity law); a missing cited or active version cannot be shown to be active and gives
     * {@link Reason#VERSION_NOT_ACTIVE}.
     */
    public static PolicyStaleness assess(PolicyInput in, Instant now, StalenessThreshold threshold) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(threshold, "threshold");
        List<Reason> reasons = new ArrayList<>();
        Instant collected = parse(in.collectedAt());
        Duration age = null;
        if (collected == null || collected.isAfter(now)) {
            reasons.add(Reason.SNAPSHOT_TIME_UNKNOWN);
        } else {
            age = Duration.between(collected, now);
            if (age.compareTo(threshold.value()) > 0) {
                reasons.add(Reason.SNAPSHOT_OLDER_THAN_THRESHOLD);
            }
        }
        if (in.lastRunState() == RunState.FAILED) {
            reasons.add(Reason.LAST_RUN_FAILED);
        }
        if (in.citedVersionId() == null || in.activeVersionId() == null
                || !in.citedVersionId().equals(in.activeVersionId())) {
            reasons.add(Reason.VERSION_NOT_ACTIVE);
        }
        return new PolicyStaleness(in.policyId(), reasons.isEmpty() ? Verdict.CURRENT : Verdict.STALE, reasons, age,
                threshold);
    }

    /** One covered policy for the headline: its staleness and its evaluation coverage ({@code COMPLETE}/{@code INCOMPLETE}). */
    public record CoveredPolicy(PolicyStaleness staleness, String coverage) {
        public CoveredPolicy {
            Objects.requireNonNull(staleness, "staleness");
        }

        boolean currentAndComplete() {
            return staleness.verdict() == Verdict.CURRENT && "COMPLETE".equals(coverage);
        }
    }

    /** Headline count: {@code exact} only when every covered policy is {@code CURRENT} and {@code COMPLETE}. */
    public record CountHeadline(int count, boolean exact, String text) {}

    /**
     * Builds the count text of the Overview ({@code ZM-35}): the number alone when every covered policy is
     * {@code CURRENT} and {@code COMPLETE}, otherwise {@code "at least N"}. With no covered policy nothing was
     * checked, so the count is never exact.
     */
    public static CountHeadline headline(int count, List<CoveredPolicy> covered) {
        Objects.requireNonNull(covered, "covered");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        boolean exact = !covered.isEmpty() && covered.stream().allMatch(CoveredPolicy::currentAndComplete);
        return new CountHeadline(count, exact, exact ? Integer.toString(count) : "at least " + count);
    }

    private static Instant parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through to an offset form
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
