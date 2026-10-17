package com.securityexpert.nexus.ui2.worker.policy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository.Latest;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyRevisionRepository.Revision;

/**
 * Writes one revision row per collection per container (V163 {@code policy_revision}), stating CHANGED, UNCHANGED or
 * UNKNOWN against the previous marker (plan section 9). The rules are fail-closed: a missing or unreadable marker is
 * UNKNOWN, never UNCHANGED, and the first observation of a container (no previous marker) is UNKNOWN
 * ({@code NO_PREVIOUS_MARKER}) so it cannot be mistaken for "nothing changed". A revision number rises only on CHANGED.
 * A NULL count is UNKNOWN, never zero.
 */
public final class PolicyRevisionRecorder {
    static final String CP_MARKER = "CP_PUBLISHED_SESSION", PAN_MARKER = "PAN_CONTENT_VERSION";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The verdict of one container. */
    record Verdict(String state, String reason, int revisionNo) {}

    private final PolicyRevisionRepository repository;

    public PolicyRevisionRecorder(PolicyRevisionRepository repository) { this.repository = repository; }

    /**
     * @param latest the container's newest earlier revision, if any
     * @param kind CP_PUBLISHED_SESSION or PAN_CONTENT_VERSION
     * @param markerJson this run's marker, or null when it could not be read
     * @param fallbackPreviousJson an earlier marker the collector itself held, used only when there is no earlier revision
     * @param failureReason a fixed code explaining a null marker
     */
    static Verdict verdict(Optional<Latest> latest, String kind, String markerJson, String fallbackPreviousJson, String failureReason) {
        int base = latest.map(Latest::revisionNo).orElse(1);
        if (markerJson == null) return new Verdict("UNKNOWN", failureReason == null ? "MARKER_UNAVAILABLE" : failureReason, base);
        String previous = latest.map(Latest::markerJson).filter(m -> latest.get().markerKind() != null && !m.equals("{}")).orElse(fallbackPreviousJson);
        if (latest.isPresent() && latest.get().markerKind() != null && !kind.equals(latest.get().markerKind()))
            return new Verdict("UNKNOWN", "MARKER_KIND_CHANGED", base);
        if (previous == null) return new Verdict("UNKNOWN", "NO_PREVIOUS_MARKER", base);
        try {
            JsonNode before = JSON.readTree(previous), now = JSON.readTree(markerJson);
            if (before == null || !before.isObject() || before.isEmpty()) return new Verdict("UNKNOWN", "NO_PREVIOUS_MARKER", base);
            return before.equals(now) ? new Verdict("UNCHANGED", null, base) : new Verdict("CHANGED", null, base + 1);
        } catch (java.io.IOException unreadable) {
            return new Verdict("UNKNOWN", "MARKER_UNREADABLE", base);
        }
    }

    /** The marker this domain is compared against: the earlier revision's, else the collector's own earlier one. */
    Optional<String> baseline(String sourceId, String container, String jobId, String fallbackPreviousJson) {
        var latest = repository.latest(sourceId, container, jobId);
        if (latest.isPresent() && latest.get().markerKind() != null && !"{}".equals(latest.get().markerJson()))
            return Optional.of(latest.get().markerJson());
        return Optional.ofNullable(fallbackPreviousJson);
    }

    /**
     * Writes the rows of one run and links the evidence the readers stored in the same run.
     *
     * @param since the job's start: rule revisions recorded after it belong to this collection
     * @param diffs the Check Point show-changes outcome per domain container, when a trial read ran
     * @return the number of rows written
     */
    int record(String sourceId, String jobId, Instant since, PolicyObservations observations,
            Map<String, CpShowChangesReader.Result> diffs) {
        int written = 0;
        for (PolicyObservations.Cp o : observations.cp()) {
            var latest = repository.latest(sourceId, o.container(), jobId);
            Verdict v = verdict(latest, CP_MARKER, o.markerJson(), o.previousMarkerJson(), "SIGNAL_UNAVAILABLE");
            var rules = o.complete() ? repository.ruleChanges(sourceId, o.container(), since) : null;
            var diff = diffs.get(o.container());
            boolean measured = diff != null && diff.measured();
            if (repository.record(new Revision(sourceId, "check_point", "domain", o.container(), jobId, v.revisionNo(), v.state(),
                    v.reason(), o.markerJson() == null ? null : CP_MARKER, o.markerJson() == null ? "{}" : o.markerJson(), o.complete(),
                    o.complete() ? o.rules() : null, null, rules == null ? null : rules.added(), rules == null ? null : rules.modified(),
                    rules == null ? null : rules.removed(), measured ? diff.added() : null, measured ? diff.modified() : null,
                    measured ? diff.deleted() : null))) written++;
        }
        for (PolicyObservations.Pan o : observations.pan()) {
            var latest = repository.latest(sourceId, o.container(), jobId);
            String marker = o.contentVersion() == null ? null : "{\"content_version\":\"" + o.contentVersion() + "\"}";
            Verdict v = verdict(latest, PAN_MARKER, marker, null, o.failureReason() == null ? "CONTENT_VERSION_UNAVAILABLE" : o.failureReason());
            var rules = o.complete() ? repository.ruleChanges(sourceId, o.container(), since) : null;
            if (repository.record(new Revision(sourceId, "palo_alto", "device_group", o.container(), jobId, v.revisionNo(), v.state(),
                    v.reason(), marker == null ? null : PAN_MARKER, marker == null ? "{}" : marker, o.complete(),
                    o.complete() ? o.rules() : null, null, rules == null ? null : rules.added(), rules == null ? null : rules.modified(),
                    rules == null ? null : rules.removed(), null, null, null))) written++;
        }
        if (written > 0) repository.refreshEvidenceCounts(jobId);
        return written;
    }
}
