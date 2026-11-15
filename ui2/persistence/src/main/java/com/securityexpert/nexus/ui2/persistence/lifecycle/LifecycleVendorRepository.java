package com.securityexpert.nexus.ui2.persistence.lifecycle;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The vendor side of the lifecycle catalog (V219, docs/design/LIFECYCLE_CATALOG_SOURCES_CONTRACT.md): entries read from a
 * vendor publication, the operator page uploads that brought some of them, and the online fetch runs that brought the
 * rest. The operator catalog of V144 stays in {@link LifecycleCatalogRepository}.
 */
public interface LifecycleVendorRepository {

    /** One product read from a vendor publication. Dates are null when the vendor gave none or the entry needs review. */
    record VendorEntry(String entryId, String origin, String source, String uploadId, String vendor, String kind,
            String productKey, String productLabel, LocalDate endOfSale, LocalDate endOfEngineering, LocalDate endOfSupport,
            LocalDate endOfSecuritySupport, LocalDate endOfExtendedSupport, List<String> monthOnlyFields, String parseState,
            String parseNote, String documentRef, String sourceUrl, String contentSha256, Instant confirmedAt) {
        public VendorEntry {
            monthOnlyFields = monthOnlyFields == null ? List.of() : List.copyOf(monthOnlyFields);
        }
    }

    /** One operator page upload (audited). */
    record Upload(String uploadId, String pageForm, String vendor, String contentSha256, int entriesParsed,
            int entriesNeedingReview, int rowsSkipped, String uploadedBy, Instant uploadedAt) { }

    /** One online fetch run of one source. */
    record FeedRun(String runId, String source, Instant startedAt, Instant endedAt, String outcome, int documentsReceived,
            int documentsIgnored, int rejected, int entriesStored, int entriesNeedingReview, Integer documentsFetched,
            Integer documentsFailed, String reason) { }

    List<VendorEntry> entries();

    /**
     * Replaces the FEED entries of one publication ({@code source}, {@code documentRef}) with {@code entries}, in one
     * transaction. An empty list removes the publication's entries.
     */
    void replaceFeedDocument(String source, String documentRef, List<VendorEntry> entries);

    /**
     * Stores an operator page upload and replaces the OPERATOR_PAGE entries of the same source and publications with its
     * entries, inside one audited transaction ({@code lifecycle_catalog_write}).
     */
    void storeUpload(Upload upload, String source, List<String> documentRefs, List<VendorEntry> entries, String actor);

    List<Upload> latestUploads(int limit);

    void openRun(String runId, String source);

    void addToRun(String runId, String source, int documentsReceived, int documentsIgnored, int rejected, int entriesStored,
            int entriesNeedingReview);

    void closeRun(String runId, String source, String outcome, Integer documentsFetched, Integer documentsFailed, String reason);

    Optional<FeedRun> run(String runId, String source);

    /** The latest run of each source, finished or not. */
    List<FeedRun> latestRuns();

    /** The latest OK or PARTIAL run of each source. */
    List<FeedRun> lastGoodRuns();
}
