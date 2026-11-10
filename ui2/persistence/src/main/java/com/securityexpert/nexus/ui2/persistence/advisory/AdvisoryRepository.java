package com.securityexpert.nexus.ui2.persistence.advisory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The advisory store (V204, docs/design/ADVISORY_MATCHING_CONTRACT.md section 5): public vendor advisories with their
 * normalised version rules, the known-exploited catalog and the record of each fetch run. Written only by the ingest
 * endpoint, read by the Lifecycle read interface. The rules are kept as the JSON text the caller's codec wrote; this
 * layer does not interpret them.
 */
public interface AdvisoryRepository {

    /** One product and train rule of an advisory; {@code ruleJson} is stored and returned unchanged. */
    record StoredRule(String productKey, String train, String ruleJson, String fixedText) { }

    /** One advisory with its rules ({@code parseState}: PARSED or NEEDS_REVIEW; {@code severity}: the contract's six values). */
    record StoredAdvisory(String advisoryId, String source, String title, String severity, BigDecimal cvssScore,
            List<String> cveIds, LocalDate publishedOn, LocalDate updatedOn, String sourceUrl, String parseState,
            String parseNote, String contentSha256, Instant loadedAt, List<StoredRule> rules) {
        public StoredAdvisory {
            cveIds = cveIds == null ? List.of() : List.copyOf(cveIds);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    record StoredKnownExploited(String cveId, LocalDate dateAdded, boolean ransomwareUse) { }

    /**
     * One fetch run of one source. {@code outcome} and {@code endedAt} are null while the run is open. {@code rejected}
     * counts entries the parser could not read plus documents that yielded nothing.
     */
    record FeedRun(String runId, String source, Instant startedAt, Instant endedAt, String outcome, int documentsReceived,
            int rejected, int advisoriesStored, int advisoriesNeedingReview, Integer documentsFetched,
            Integer documentsFailed, String reason) { }

    /** What one source holds now: advisory count, how many need review, oldest and newest {@code published_on}. */
    record SourceSummary(String source, int advisories, int needsReview, LocalDate oldest, LocalDate newest) { }

    /**
     * Stores the advisories in one transaction. An advisory with the same id is replaced, and so are its rules (the rules
     * of the previous version of the same advisory are removed first).
     */
    void upsertAdvisories(List<StoredAdvisory> advisories);

    /** Replaces the whole known-exploited set in one transaction. */
    void replaceKnownExploited(List<StoredKnownExploited> entries);

    /** Opens the run if it does not exist yet (the first document of a run opens it); an existing run is left as it is. */
    void openRun(String runId, String source);

    /** Adds to the counters of an open or closed run. */
    void addToRun(String runId, String source, int documentsReceived, int rejected, int advisoriesStored, int advisoriesNeedingReview);

    /** Closes the run (creating it first when the fetcher closes a run that received nothing). */
    void closeRun(String runId, String source, String outcome, Integer documentsFetched, Integer documentsFailed, String reason);

    Optional<FeedRun> run(String runId, String source);

    /** Every advisory with its rules, optionally only those of one source. */
    List<StoredAdvisory> advisories(Optional<String> source);

    List<StoredKnownExploited> knownExploited();

    /** The latest run (by start time) of each source that has one. */
    List<FeedRun> latestRuns();

    /** The latest run of each source that ended OK or PARTIAL: the last good load. */
    List<FeedRun> lastGoodRuns();

    List<SourceSummary> sourceSummaries();
}
