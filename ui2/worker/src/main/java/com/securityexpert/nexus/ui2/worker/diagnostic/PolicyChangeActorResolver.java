package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository;
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

    /** The documented commands that can produce each change type (design section 4.2 step 3). */
    private static final Map<String, Set<String>> COMPATIBLE = Map.of(
            "added", Set.of("set", "add", "clone", "edit"),
            "removed", Set.of("delete"),
            "modified", Set.of("edit", "set", "move", "rename"));

    /** Counts only (safe telemetry); reasons are fixed codes. */
    public record Result(int examined, int matched, int ambiguous, int unknown, Map<String, Integer> reasons, int namesCleared) {
        public String note() {
            return "PAN_ACTOR_LINK examined=" + examined + " matched=" + matched + " ambiguous=" + ambiguous + " unknown=" + unknown
                    + (reasons.isEmpty() ? "" : " reasons=" + reasons);
        }
    }

    private final PolicyChangeActorRepository repository;
    private final HostnameFingerprint fingerprint;

    public PolicyChangeActorResolver(PolicyChangeActorRepository repository, HostnameFingerprint fingerprint) {
        this.repository = repository; this.fingerprint = fingerprint;
    }

    /** Resolves the pending history rows of one source. Idempotent: a linked row is never looked at again. */
    public Result resolve(String sourceId, String jobId, Instant now) {
        List<Pending> pending = repository.pending(sourceId, now.minus(PENDING_AGE), BATCH);
        Map<String, Map<String, RuleIdentity>> identities = new HashMap<>();
        Map<String, Optional<Instant>> previous = new HashMap<>();
        Map<String, List<String>> commitCache = new HashMap<>();
        Map<String, Optional<String>> beforeCache = new HashMap<>();
        Map<String, List<Edit>> editCache = new HashMap<>();
        Map<String, List<String>> keysByPolicy = new LinkedHashMap<>();
        for (Pending row : pending)
            if (!"removed".equals(row.changeType())) keysByPolicy.computeIfAbsent(row.policyId(), p -> new ArrayList<>()).add(row.ruleKey());
        keysByPolicy.forEach((policy, keys) -> identities.put(policy, new HashMap<>(repository.identities(policy, keys))));
        List<Link> links = new ArrayList<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int matched = 0, ambiguous = 0, unknown = 0;
        for (Pending row : pending) {
            Link link = link(sourceId, row, identities, previous, commitCache, beforeCache, editCache);
            links.add(link);
            switch (link.state()) {
                case PolicyChangeActorRepository.MATCHED -> matched++;
                case PolicyChangeActorRepository.AMBIGUOUS -> ambiguous++;
                default -> { unknown++; reasons.merge(link.reason(), 1, Integer::sum); }
            }
        }
        repository.saveLinks(jobId, links);
        int cleared = repository.clearExpiredNames(sourceId, LocalDateTime.ofInstant(now, DEVICE_ZONE).minusDays(LOOKBACK_DAYS).format(SQL_TIME));
        return new Result(pending.size(), matched, ambiguous, unknown, reasons, cleared);
    }

    private Link link(String sourceId, Pending row, Map<String, Map<String, RuleIdentity>> identities,
            Map<String, Optional<Instant>> previous, Map<String, List<String>> commitCache,
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
        List<String> commits = commitCache.computeIfAbsent(from + "|" + to, k -> repository.commitTimes(sourceId, from, to));
        if (commits.isEmpty()) return unknown(row, "NO_COMMIT_SEEN");
        String first = commits.get(0);
        String last = commits.get(commits.size() - 1);
        Optional<String> boundary = beforeCache.computeIfAbsent(first, k -> repository.commitBefore(sourceId, k));
        if (boundary.isEmpty()) return unknown(row, "WINDOW_NOT_COVERED");
        LocalDateTime start = LocalDateTime.parse(boundary.get(), SQL_TIME);
        if (Duration.between(start, LocalDateTime.parse(last, SQL_TIME)).compareTo(Duration.ofDays(LOOKBACK_DAYS)) > 0)
            return unknown(row, "WINDOW_NOT_COVERED");
        List<Edit> edits = editCache.computeIfAbsent(boundary.get() + "|" + last, k -> repository.edits(sourceId, boundary.get(), last));
        Set<String> compatible = COMPATIBLE.get(row.changeType());
        Map<String, String> byAdmin = new LinkedHashMap<>();
        for (Edit e : edits)
            if (ref.get().equals(e.ruleRefHmac()) && compatible.contains(e.commandClass())) byAdmin.putIfAbsent(e.adminRef(), e.adminName());
        if (byAdmin.isEmpty()) return unknown(row, "NO_ENTRY");
        // Two administrators whose names collide (same text, different keyed reference) are still two administrators: the
        // count is the number of references and every name is listed.
        List<String> names = new ArrayList<>(byAdmin.values());
        return new Link(row.revisionId(), byAdmin.size() == 1 ? PolicyChangeActorRepository.MATCHED : PolicyChangeActorRepository.AMBIGUOUS,
                null, names);
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
