package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.Commit;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.Edit;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.Link;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.Pending;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.RuleIdentity;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * Joins the policy-history rows of one Panorama to the config-log evidence the reader stored, so the policy-changes screen
 * can name the administrator of a Palo Alto rule change (PO decision record 2026-11-12; design
 * {@code PAN_CHANGE_ACTOR_FROM_PANORAMA_CONFIG_LOG_2026_11_11.md} section 4.2). No device is called: it reads stored rows
 * only, in the policy job after the config-log reader finished.
 *
 * <p><b>Why commits anchor the window.</b> The collector reads the running configuration, so a rule changes in a snapshot at
 * the <i>commit</i>; the administrator's edit entry was written earlier, against the candidate configuration. For a history
 * row with collection time C and previous collection P the resolver takes the successful commits in (P, C], the commit
 * before them (B) and the last of them (E), and the candidates are the change entries that name the same rule in (B, E].</p>
 *
 * <p><b>Outcomes, never a guess.</b> No candidate: UNKNOWN. All candidates by one administrator: MATCHED. Two or more
 * different administrators: AMBIGUOUS. A window that cannot be shown to be covered by stored evidence (no commit seen, no
 * earlier commit, longer than {@value #LOOKBACK_DAYS} days, no previous collection, a rule that cannot be referenced):
 * UNKNOWN with a fixed reason code. Every UNKNOWN of the design's section 8 (path form, commit {@code cmd}, device clock) can
 * only produce an UNKNOWN here, because the evidence rows it reads exist only when those shapes matched.</p>
 */
public final class PolicyChangeActorResolver {
    /** The device clock is read in the product's display zone, as the change feed does (PanConfigEvents.ORDERING_ZONE). */
    static final ZoneId DEVICE_ZONE = ZoneId.of("Europe/Istanbul");
    static final int LOOKBACK_DAYS = 30;
    static final Duration MARGIN = Duration.ofMinutes(5);
    /** History rows older than this are no longer resolved; they stay UNKNOWN as before. */
    static final Duration PENDING_AGE = Duration.ofDays(14);
    static final int BATCH = 2000;
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<String> SEQNO_ORDER = (a, b) -> {
        try { return new BigInteger(a).compareTo(new BigInteger(b)); }
        catch (RuntimeException nonNumeric) { return String.valueOf(a).compareTo(String.valueOf(b)); }
    };

    /**
     * One commit job after pairing a {@code Submitted} entry with its later {@code Succeeded} or {@code Failed} completion
     * entry (by job reference when both carry one, else by the same administrator in log order). An existing
     * {@code Succeeded}-only entry without a preceding {@code Submitted} entry is already complete.
     */
    record CommitJob(String submittedTime, String completedTime, String adminRef, String outcome) {
        boolean succeeded() { return "Succeeded".equals(outcome); }
        boolean failed() { return "Failed".equals(outcome); }
        boolean inWindow(String from, String to) {
            return (submittedTime.compareTo(from) > 0 && submittedTime.compareTo(to) <= 0)
                    || (completedTime.compareTo(from) > 0 && completedTime.compareTo(to) <= 0);
        }
    }

    /** The documented commands that can produce each change type (design section 4.2 step 3). */
    private static final Map<String, Set<String>> COMPATIBLE = Map.of(
            "added", Set.of("set", "add", "clone", "edit"),
            "removed", Set.of("delete"),
            "modified", Set.of("edit", "set", "move", "rename"));

    /**
     * Reasons that describe missing <i>evidence</i>, not the row: the log has not (yet) been read far enough, or the commit
     * that would anchor the window is not in it. Such a row is never given a link, so it stays pending and the next run tries
     * again, until it is older than {@link #PENDING_AGE}. A link is the final answer for a row (measured 2026-11-13: 2000
     * rows were linked UNKNOWN for a commit anchor that did not match this Panorama's commit entries).
     */
    static final Set<String> EVIDENCE_GAP_REASONS = Set.of("NO_COMMIT_SEEN", "WINDOW_NOT_COVERED", "PREVIOUS_COLLECTION_UNKNOWN");

    /** Counts only (safe telemetry); reasons are fixed codes. {@code deferred} rows were left pending, {@code reasons} counts both kinds. */
    public record Result(int examined, int matched, int ambiguous, int unknown, int deferred, Map<String, Integer> reasons, int namesCleared) {
        public String note() {
            return "PAN_ACTOR_LINK examined=" + examined + " matched=" + matched + " ambiguous=" + ambiguous + " unknown=" + unknown
                    + " deferred=" + deferred + (reasons.isEmpty() ? "" : " reasons=" + reasons);
        }
    }

    private final PolicyChangeActorRepository repository;
    private final HostnameFingerprint fingerprint;

    public PolicyChangeActorResolver(PolicyChangeActorRepository repository, HostnameFingerprint fingerprint) {
        this.repository = repository; this.fingerprint = fingerprint;
    }

    /** Resolves the pending history rows of one source. Idempotent: a final linked row is never looked at again. */
    public Result resolve(String sourceId, String jobId, Instant now) {
        List<Pending> pending = repository.pending(sourceId, now.minus(PENDING_AGE), BATCH);
        Map<String, Map<String, RuleIdentity>> identities = new HashMap<>();
        Map<String, Optional<Instant>> previous = new HashMap<>();
        Map<String, List<String>> commitCache = new HashMap<>();
        Map<String, Optional<String>> beforeCache = new HashMap<>();
        Map<String, List<Edit>> editCache = new HashMap<>();
        @SuppressWarnings("unchecked") List<CommitJob>[] resolvedCommits = new List[] {null};
        Map<String, List<String>> keysByPolicy = new LinkedHashMap<>();
        for (Pending row : pending)
            if (!"removed".equals(row.changeType())) keysByPolicy.computeIfAbsent(row.policyId(), p -> new ArrayList<>()).add(row.ruleKey());
        keysByPolicy.forEach((policy, keys) -> identities.put(policy, new HashMap<>(repository.identities(policy, keys))));
        List<Link> links = new ArrayList<>();
        List<Long> deferredIds = new ArrayList<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int matched = 0, ambiguous = 0, unknown = 0, deferred = 0;
        for (Pending row : pending) {
            Link link = link(sourceId, row, identities, previous, resolvedCommits, commitCache, beforeCache, editCache);
            switch (link.state()) {
                case PolicyChangeActorRepository.MATCHED -> { matched++; links.add(link); }
                case PolicyChangeActorRepository.AMBIGUOUS -> { ambiguous++; links.add(link); }
                default -> {
                    reasons.merge(link.reason(), 1, Integer::sum);
                    if (EVIDENCE_GAP_REASONS.contains(link.reason())) { deferred++; deferredIds.add(row.revisionId()); }
                    else { unknown++; links.add(link); }
                }
            }
        }
        repository.saveLinks(jobId, links);
        if (!deferredIds.isEmpty() && resolvedCommits[0] != null && !resolvedCommits[0].isEmpty())
            repository.deletePendingOutcomeLinks(deferredIds);
        int cleared = repository.clearExpiredNames(sourceId, LocalDateTime.ofInstant(now, DEVICE_ZONE).minusDays(LOOKBACK_DAYS).format(SQL_TIME));
        return new Result(pending.size(), matched, ambiguous, unknown, deferred, reasons, cleared);
    }

    private Link link(String sourceId, Pending row, Map<String, Map<String, RuleIdentity>> identities,
            Map<String, Optional<Instant>> previous, List<CommitJob>[] resolvedCommits, Map<String, List<String>> commitCache,
            Map<String, Optional<String>> beforeCache, Map<String, List<Edit>> editCache) {
        Optional<String> ref = ruleRef(row, identities);
        if (ref.isEmpty()) return unknown(row, "RULE_REF_UNAVAILABLE");
        if (!COMPATIBLE.containsKey(row.changeType())) return unknown(row, "CHANGE_TYPE_UNKNOWN");
        if (row.containerRef() == null) return unknown(row, "PREVIOUS_COLLECTION_UNKNOWN");
        Optional<Instant> prior = previous.computeIfAbsent(row.containerRef() + "|" + row.collectedAt(),
                k -> repository.previousObservation(sourceId, row.containerRef(), row.collectedAt()));
        if (prior.isEmpty()) return unknown(row, "PREVIOUS_COLLECTION_UNKNOWN");
        String from = device(prior.get().minus(MARGIN));
        String to = device(row.collectedAt().plus(MARGIN));
        if (resolvedCommits[0] == null) resolvedCommits[0] = resolveCommits(repository.commits(sourceId));
        String first;
        String last;
        Optional<String> boundary;
        List<CommitJob> windowJobs = List.of();
        if (!resolvedCommits[0].isEmpty()) {
            List<CommitJob> active = resolvedCommits[0].stream().filter(j -> !j.failed()).toList();
            windowJobs = active.stream().filter(j -> j.inWindow(from, to)).toList();
            if (windowJobs.isEmpty()) return unknown(row, "NO_COMMIT_SEEN");
            first = windowJobs.get(0).submittedTime();
            last = windowJobs.stream().map(CommitJob::completedTime).max(String::compareTo)
                    .orElse(windowJobs.get(windowJobs.size() - 1).completedTime());
            final String firstSubmitted = first;
            boundary = active.stream().filter(j -> j.submittedTime().compareTo(firstSubmitted) < 0)
                    .map(j -> j.completedTime().compareTo(firstSubmitted) < 0 ? j.completedTime() : j.submittedTime())
                    .max(String::compareTo);
            if (boundary.isEmpty()) boundary = beforeCache.computeIfAbsent(first, k -> repository.commitBefore(sourceId, k));
        } else {
            List<String> commits = commitCache.computeIfAbsent(from + "|" + to, k -> repository.commitTimes(sourceId, from, to));
            if (commits.isEmpty()) return unknown(row, "NO_COMMIT_SEEN");
            first = commits.get(0);
            last = commits.get(commits.size() - 1);
            boundary = beforeCache.computeIfAbsent(first, k -> repository.commitBefore(sourceId, k));
        }
        if (boundary.isEmpty()) return unknown(row, "WINDOW_NOT_COVERED");
        String windowStart = boundary.get();
        String windowEnd = last;
        LocalDateTime start = LocalDateTime.parse(windowStart, SQL_TIME);
        if (Duration.between(start, LocalDateTime.parse(windowEnd, SQL_TIME)).compareTo(Duration.ofDays(LOOKBACK_DAYS)) > 0)
            return unknown(row, "WINDOW_NOT_COVERED");
        List<Edit> edits = editCache.computeIfAbsent(windowStart + "|" + windowEnd, k -> repository.edits(sourceId, windowStart, windowEnd));
        Set<String> compatible = COMPATIBLE.get(row.changeType());
        Map<String, String> byAdmin = new LinkedHashMap<>();
        String latestEditTime = windowStart;
        for (Edit e : edits) {
            if (ref.get().equals(e.ruleRefHmac()) && compatible.contains(e.commandClass())
                    && e.adminName() != null && !e.adminName().isBlank()) {
                byAdmin.putIfAbsent(e.adminRef(), e.adminName());
                if (e.time() != null && e.time().compareTo(latestEditTime) > 0) latestEditTime = e.time();
            }
        }
        if (byAdmin.isEmpty()) return unknown(row, "NO_ENTRY");
        // Two administrators whose names collide (same text, different keyed reference) are still two administrators: the
        // count is the number of references and every name is listed.
        if (byAdmin.size() > 1) return new Link(row.revisionId(), PolicyChangeActorRepository.AMBIGUOUS, null, new ArrayList<>(byAdmin.values()));
        String admin = byAdmin.values().iterator().next();
        final String editTime = latestEditTime;
        boolean outcomeKnown = windowJobs.isEmpty() || windowJobs.stream().anyMatch(j -> j.succeeded() && j.completedTime().compareTo(editTime) >= 0);
        return new Link(row.revisionId(), PolicyChangeActorRepository.MATCHED, null,
                List.of(outcomeKnown ? admin : PolicyChangeActorRepository.pendingOutcomeActor(admin)));
    }

    static List<CommitJob> resolveCommits(List<Commit> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        List<Commit> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Commit::time).thenComparing(Commit::entryRef, SEQNO_ORDER));
        boolean[] consumed = new boolean[sorted.size()];
        List<CommitJob> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (consumed[i]) continue;
            Commit current = sorted.get(i);
            if ("Submitted".equals(current.resultClass())) {
                int match = -1;
                for (int j = i + 1; j < sorted.size(); j++) {
                    if (consumed[j]) continue;
                    Commit later = sorted.get(j);
                    if ("Submitted".equals(later.resultClass())) {
                        if (current.jobRef() == null && later.jobRef() == null && sameAdmin(current, later)) break;
                        if (current.jobRef() != null && current.jobRef().equals(later.jobRef())) break;
                        continue;
                    }
                    if (("Succeeded".equals(later.resultClass()) || "Failed".equals(later.resultClass()))
                            && sameCommitJob(current, later)) {
                        match = j;
                        break;
                    }
                }
                if (match >= 0) {
                    consumed[match] = true;
                    Commit completion = sorted.get(match);
                    out.add(new CommitJob(current.time(), completion.time(), current.adminRef(), completion.resultClass()));
                } else {
                    out.add(new CommitJob(current.time(), current.time(), current.adminRef(), "Pending"));
                }
            } else if ("Succeeded".equals(current.resultClass())) {
                out.add(new CommitJob(current.time(), current.time(), current.adminRef(), "Succeeded"));
            } else if ("Failed".equals(current.resultClass())) {
                out.add(new CommitJob(current.time(), current.time(), current.adminRef(), "Failed"));
            }
        }
        return out;
    }

    private static boolean sameCommitJob(Commit submitted, Commit completion) {
        if (submitted.jobRef() != null && completion.jobRef() != null) return submitted.jobRef().equals(completion.jobRef());
        return sameAdmin(submitted, completion);
    }

    private static boolean sameAdmin(Commit a, Commit b) {
        return a.adminRef() != null && !a.adminRef().isEmpty() && !"UNKNOWN".equals(a.adminRef()) && a.adminRef().equals(b.adminRef());
    }

    private Optional<String> ruleRef(Pending row, Map<String, Map<String, RuleIdentity>> identities) {
        if (fingerprint == null) return Optional.empty();
        RuleIdentity identity = identities.getOrDefault(row.policyId(), Map.of()).get(row.ruleKey());
        if (identity == null && "removed".equals(row.changeType())) identity = fromRemovedChanges(row.removedChangesJson());
        if (identity == null) return Optional.empty();
        return PanRuleRef.fromSnapshot(identity.scope(), identity.section(), identity.name()).map(parts -> PanRuleRef.hmac(fingerprint, parts));
    }

    /** A removed rule is gone from the current snapshot; its scope, section and name are the {@code before} values of its diff. */
    static RuleIdentity fromRemovedChanges(String changesJson) {
        if (changesJson == null) return null;
        try {
            Map<String, String> values = new HashMap<>();
            JsonNode changes = JSON.readTree(changesJson);
            if (changes == null || !changes.isArray()) return null;
            for (JsonNode change : changes) {
                String field = change.path("field").asText("");
                if (field.equals("container") || field.equals("section") || field.equals("name")) {
                    JsonNode before = change.path("before");
                    if (before.isTextual()) values.put(field, before.asText());
                }
            }
            return values.size() == 3 ? new RuleIdentity(values.get("container"), values.get("section"), values.get("name")) : null;
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    private static Link unknown(Pending row, String reason) {
        return new Link(row.revisionId(), PolicyChangeActorRepository.UNKNOWN, reason, List.of());
    }

    private static String device(Instant instant) { return LocalDateTime.ofInstant(instant, DEVICE_ZONE).format(SQL_TIME); }
}
