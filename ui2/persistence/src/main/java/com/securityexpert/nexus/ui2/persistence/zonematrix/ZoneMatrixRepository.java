package com.securityexpert.nexus.ui2.persistence.zonematrix;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Storage of zone-matrix versions (V212; docs/design/ZONE_MATRIX_CONTRACT.md sections 3.5 and 8.1, FROZEN 2026-11-13).
 * Content is opaque canonical JSON here; the service owns its meaning and its hash.
 */
public interface ZoneMatrixRepository {
    /** One version row. {@code content} is null in list results. */
    record Version(String versionId, Integer number, String state, long revision, String content, String contentSha256,
            String basedOnVersionId, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
            String publishedBy, Instant publishedAt) {}

    /** One revision row, without its content. */
    record Revision(long revision, String actor, Instant occurredAt, String changeKind, String zoneId, String contentSha256,
            boolean rationaleGiven) {}

    List<Version> list();

    Optional<Version> find(String versionId);

    Optional<Version> active();

    Optional<Version> draft();

    List<Revision> revisions(String versionId);

    /** Records one raw blocks view or address lookup (section 3.4); the trigger writes the audit row. */
    void recordAccess(String actor, String kind, String versionId);

    /**
     * Runs {@code work} in one transaction holding the zone-matrix write lock, so every draft change and every publish is
     * serialized and reads the state it changes.
     */
    <T> T locked(Function<DraftTransaction, T> work);

    /** The operations available under the write lock. */
    interface DraftTransaction {
        Optional<Version> draft();

        Optional<Version> active();

        /** Distinct actors of every revision of a draft (creation, edits, confirmations); never the publish row. */
        Set<String> participants(String versionId);

        int nextNumber();

        void insertDraft(String versionId, String content, String sha256, String basedOn, String actor, Instant now);

        /** Moves the draft to {@code revision} with new content; refuses when the stored revision is not {@code expected}. */
        void updateDraft(String versionId, long expected, long revision, String content, String sha256, String actor, Instant now);

        void appendRevision(String versionId, long revision, String actor, Instant now, String changeKind, String zoneId,
                String sha256, String content, String rationale);

        void retire(String versionId);

        void activate(String versionId, int number, String actor, Instant now);

        void discard(String versionId);
    }

    /** The draft changed between read and write; the caller reports {@code REQUEST_CHANGED}. */
    final class Conflict extends RuntimeException {
        public Conflict() { super("zone matrix draft changed", null, false, false); }
    }
}
