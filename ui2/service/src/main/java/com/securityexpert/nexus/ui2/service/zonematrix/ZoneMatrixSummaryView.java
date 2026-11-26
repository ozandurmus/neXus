package com.securityexpert.nexus.ui2.service.zonematrix;

import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedByVersionFinding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.NotEvaluatedPolicy;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.PolicyCoverage;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredFinding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredHeadline;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.StalenessThreshold;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Zone matrix delivery step 4b-2b, part 1: the read projection of {@link StoredSummary} behind
 * {@code GET /api/v2/zone-matrix/summary} (docs/design/ZONE_MATRIX_CONTRACT.md sections 3.4, 7.1, 7.2, 10.1 step 4,
 * {@code ZM-26}, {@code ZM-33}, {@code ZM-34}, {@code ZM-35} and the 2026-11-23 amendment).
 *
 * <ul>
 *   <li><b>Masking</b> goes through {@link Labels}, which {@link ZoneMatrixService#summaryLabels} builds from the same
 *       {@code PolicyPrivacy} / {@link ZoneMatrixService.View#MASKED} projection the evaluate route uses. Policy, source,
 *       container, rule and section names pass through it; zone names are pseudonymised for the masked persona.</li>
 *   <li><b>Never</b> blocks, addresses, witness tuples or NAT views: the projection copies named fields only and never
 *       reads {@link ZoneMatrixRunRepository.Cell#witness()} or {@link ZoneMatrixRunRepository.Cell#natView()}.</li>
 *   <li><b>UNKNOWN stays UNKNOWN</b>: an unknown snapshot age, a policy with no {@code COMPLETE} run (collection failures
 *       unknown) or an unreadable zone name is {@code null} / {@code "UNKNOWN"}, never {@code 0} or {@code "OK"}.</li>
 *   <li><b>Findings</b> are sorted deterministically (severity, outcome, policy, rule number, then identity), filtered,
 *       then capped; {@code total_findings}, {@code returned}, {@code more} and {@code capped} say what was left out.</li>
 * </ul>
 */
public final class ZoneMatrixSummaryView {
    private ZoneMatrixSummaryView() {}

    /** Default number of findings returned. */
    public static final int DEFAULT_LIMIT = 200;
    /** Largest {@code limit} accepted; a larger value is refused, never clamped. */
    public static final int MAX_LIMIT = 1000;
    /** Query parameters the route accepts; anything else is refused. */
    public static final Set<String> PARAMETERS = Set.of("policy_id", "outcome", "limit");
    /** The stored outcomes ({@code ZoneMatrixRunService} stores only these three). */
    public static final List<String> OUTCOMES = List.of(ZoneMatrixEvaluator.OUTCOME_VIOLATION,
            ZoneMatrixEvaluator.OUTCOME_UNKNOWN, ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE);
    /** Shown with the threshold while it is the interim value (amendment 2026-11-23). */
    public static final String INTERIM_THRESHOLD_LABEL =
            "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";

    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /** Name projection of the caller's view; production: {@link ZoneMatrixService#summaryLabels}. */
    public interface Labels {
        /** {@code true} for the masked persona (replay_viewer). */
        boolean masked();
        /** {@code true} when policy, source, container, rule and section names pass through the masked projection. */
        boolean policyNamesMasked();
        String policyName(String raw);
        String sourceName(String raw, String sourceId);
        String containerName(String raw);
        String ruleName(String raw, Integer ruleNumber);
        /** The zone's name in {@code versionId}'s content as the caller may see it; {@code "UNKNOWN"} when unreadable. */
        String zoneName(String versionId, String zoneId);
    }

    /** Validated query of the route. {@code policyId} and {@code outcome} are {@code null} when not filtered. */
    public record Query(String policyId, String outcome, int limit) {
        public Query {
            if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit out of range");
        }

        public static Query defaults() { return new Query(null, null, DEFAULT_LIMIT); }

        /**
         * Parses the raw parameters. A blank {@code policy_id}, an outcome outside {@link #OUTCOMES}, or a {@code limit}
         * that is not an integer in 1..{@value #MAX_LIMIT} is refused with 400; nothing is silently clamped.
         */
        public static Query parse(String policyId, String outcome, String limit) {
            String policy = null;
            if (policyId != null) {
                if (policyId.isBlank()) throw bad("INVALID_POLICY_ID", "policy_id must not be blank");
                policy = policyId;
            }
            String out = null;
            if (outcome != null) {
                if (!OUTCOMES.contains(outcome)) {
                    throw bad("INVALID_OUTCOME", "outcome must be one of " + String.join(", ", OUTCOMES));
                }
                out = outcome;
            }
            int n = DEFAULT_LIMIT;
            if (limit != null) {
                if (!limit.matches("[0-9]{1,7}")) throw bad("INVALID_LIMIT", "limit must be an integer from 1 to " + MAX_LIMIT);
                n = Integer.parseInt(limit);
                if (n < 1 || n > MAX_LIMIT) throw bad("INVALID_LIMIT", "limit must be an integer from 1 to " + MAX_LIMIT);
            }
            return new Query(policy, out, n);
        }

        /** Refuses a parameter the route does not know, or one given more than once. */
        public static void checkParameterNames(Map<String, String[]> parameters) {
            if (parameters == null) return;
            for (var entry : parameters.entrySet()) {
                if (!PARAMETERS.contains(entry.getKey())) {
                    throw bad("UNKNOWN_PARAMETER", "unknown query parameter; accepted: policy_id, outcome, limit");
                }
                if (entry.getValue() != null && entry.getValue().length > 1) {
                    throw bad("REPEATED_PARAMETER", "a query parameter was given more than once");
                }
            }
        }
    }

    /** Builds the response body. A {@code policy_id} that names no policy in the summary is refused with 400. */
    public static Map<String, Object> project(StoredSummary summary, Labels labels, Query query) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(query, "query");

        Map<String, String> policyNames = policyNames(summary, labels);
        if (query.policyId() != null && !policyNames.containsKey(query.policyId())
                && summary.notEvaluated().stream().noneMatch(p -> p.id().equals(query.policyId()))) {
            throw bad("UNKNOWN_POLICY", "policy_id names no policy in the zone matrix coverage");
        }

        Version active = summary.activeVersion();
        boolean hasActive = active != null && active.number() != null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluated_at", text(summary.evaluatedAt()));
        out.put("state", state(summary, hasActive));
        out.put("state_text", stateText(state(summary, hasActive)));
        out.put("masked", labels.masked());
        out.put("policy_names_masked", labels.policyNamesMasked());
        out.put("witnesses_included", false);
        out.put("staleness_threshold", threshold(summary.stalenessThreshold()));
        out.put("active_version", hasActive ? activeVersion(active) : null);
        out.put("headline", summary.headline() == null ? null : headline(summary.headline(),
                summary.resolvedByVersionChangeLast7Days().size()));
        out.put("headline_absent_reason", summary.headline() == null ? "no active version: no headline row" : null);

        List<Map<String, Object>> policies = new ArrayList<>(summary.policies().size());
        for (PolicyCoverage p : summary.policies()) policies.add(policy(p, labels));
        out.put("policies", policies);
        List<Map<String, Object>> notEvaluated = new ArrayList<>(summary.notEvaluated().size());
        for (NotEvaluatedPolicy p : summary.notEvaluated()) notEvaluated.add(notEvaluated(p, labels));
        out.put("not_evaluated", notEvaluated);

        String activeId = hasActive ? active.versionId() : null;
        List<StoredFinding> matching = summary.findings().stream()
                .filter(f -> query.policyId() == null || query.policyId().equals(f.finding().policySourceId()))
                .filter(f -> query.outcome() == null || query.outcome().equals(f.finding().outcome()))
                .sorted(FINDING_ORDER)
                .toList();
        List<Map<String, Object>> findings = new ArrayList<>(Math.min(matching.size(), query.limit()));
        for (StoredFinding f : matching.subList(0, Math.min(matching.size(), query.limit()))) {
            findings.add(storedFinding(f, activeId, policyNames, labels));
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("policy_id", query.policyId());
        filters.put("outcome", query.outcome());
        filters.put("limit", query.limit());
        out.put("filters", filters);
        out.put("unfiltered_findings", summary.findings().size());
        out.put("total_findings", matching.size());
        out.put("returned", findings.size());
        out.put("more", matching.size() - findings.size());
        out.put("capped", matching.size() > findings.size());
        out.put("findings", findings);

        putCellCounts(out, summary, hasActive ? active.versionId() : null, labels, query.limit());
        out.put("resolved_by_version_change", resolvedByVersion(summary, query.limit(), policyNames, labels));
        return out;
    }

    // ---------------------------------------------------------------- parts

    private static String state(StoredSummary s, boolean hasActive) {
        if (!hasActive) return "NO_ACTIVE_VERSION";
        if (s.policies().isEmpty()) return "NO_EVALUABLE_POLICY";
        if (s.policies().stream().allMatch(p -> p.latestCompleteRun() == null)) return "NO_COMPLETE_RUN";
        return "READY";
    }

    private static String stateText(String state) {
        return switch (state) {
            case "NO_ACTIVE_VERSION" -> "no active zone matrix version: nothing is evaluated yet";
            case "NO_EVALUABLE_POLICY" -> "no stored Check Point or Palo Alto policy to evaluate";
            case "NO_COMPLETE_RUN" -> "no policy has a complete evaluation run yet";
            default -> "findings of the latest complete run of each policy";
        };
    }

    private static Map<String, Object> threshold(StalenessThreshold t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seconds", t.value().toSeconds());
        out.put("hours", t.value().toSeconds() % 3600 == 0 ? t.value().toHours() : null);
        out.put("interim", interim(t));
        out.put("source", t.source());
        out.put("label", thresholdLabel(t));
        return out;
    }

    /** {@code true} while the threshold is the interim value of the 2026-11-23 amendment. */
    static boolean interim(StalenessThreshold t) {
        return ZoneMatrixStaleness.INTERIM_PO_2026_11_23.equals(t);
    }

    /** The label shown with the threshold: {@link #INTERIM_THRESHOLD_LABEL} while interim, else its stated source. */
    static String thresholdLabel(StalenessThreshold t) {
        return interim(t) ? INTERIM_THRESHOLD_LABEL : "source: " + t.source();
    }

    /** Policy id to its name as the caller may see it; findings of a policy outside the coverage list show UNKNOWN. */
    static Map<String, String> policyNames(StoredSummary summary, Labels labels) {
        Map<String, String> out = new HashMap<>();
        for (PolicyCoverage p : summary.policies()) out.put(p.policyId(), labels.policyName(p.policyName()));
        return out;
    }

    private static Map<String, Object> activeVersion(Version v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version_id", v.versionId());
        out.put("version_number", v.number());
        out.put("content_sha256", v.contentSha256());
        out.put("published_at", text(v.publishedAt()));
        return out;
    }

    private static Map<String, Object> headline(StoredHeadline h, int resolvedLast7Days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version_id", h.versionId());
        out.put("version_number", h.versionNumber());
        out.put("distinct_violating_rules", h.distinctViolatingRules());
        out.put("violated_zone_pairs", h.violatedZonePairs());
        out.put("critical_rules", h.criticalRules());
        out.put("covered_by_earlier_rule", h.coveredByEarlierRule());
        out.put("conditional_rules", h.conditionalRules());
        out.put("outside_headline_ordered_layer", h.outsideHeadlineOrderedLayer());
        out.put("not_evaluable_findings", h.notEvaluableFindings());
        out.put("unknown_findings", h.unknownFindings());
        out.put("became_undecided_findings", h.becameUndecidedFindings());
        out.put("stale_policies", h.stalePolicies());
        out.put("incomplete_policies", h.incompletePolicies());
        out.put("older_version_policies", h.olderVersionPolicies());
        out.put("not_evaluated_policies", h.notEvaluatedPolicies());
        out.put("resolved_by_version_change_last_7_days", resolvedLast7Days);
        out.put("exact", h.exact());
        out.put("count_text", h.countText());
        out.put("text", h.text());
        out.put("outside_headline_text", h.outsideHeadlineText());
        Map<String, Object> staleness = new LinkedHashMap<>();
        staleness.put("count", h.stalenessHeadline().count());
        staleness.put("exact", h.stalenessHeadline().exact());
        staleness.put("text", h.stalenessHeadline().text());
        out.put("staleness_headline", staleness);
        return out;
    }

    private static Map<String, Object> policy(PolicyCoverage p, Labels labels) {
        var complete = p.latestCompleteRun();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy_id", p.policyId());
        out.put("policy_name", labels.policyName(p.policyName()));
        out.put("vendor", p.vendor());
        out.put("source_id", p.sourceId());
        out.put("source_name", labels.sourceName(p.sourceName(), p.sourceId()));
        out.put("container_id", p.containerId());
        out.put("container_name", labels.containerName(p.containerName()));
        out.put("collected_at", p.collectedAt());
        out.put("rule_count", p.ruleCount());
        Map<String, Object> staleness = new LinkedHashMap<>();
        staleness.put("verdict", p.staleness().verdict().name());
        staleness.put("reasons", p.staleness().reasons().stream().map(Enum::name).toList());
        staleness.put("snapshot_age_seconds", seconds(p.staleness().snapshotAge()));
        staleness.put("snapshot_age_known", p.staleness().snapshotAge() != null);
        out.put("staleness", staleness);
        out.put("coverage", p.coverage());
        out.put("coverage_items", p.coverageItems());
        // Without a COMPLETE run the number of collection failures is not known: null, never 0.
        out.put("collection_failures", complete == null ? null : p.collectionFailures());
        out.put("has_complete_run", complete != null);
        out.put("latest_complete_run", complete == null ? null : run(complete));
        out.put("latest_run", p.latestRun() == null ? null : run(p.latestRun()));
        return out;
    }

    private static Map<String, Object> run(ZoneMatrixRunRepository.Run r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", r.runId());
        out.put("state", r.state());
        out.put("failure_reason", r.failureReason());
        out.put("version_id", r.versionId());
        out.put("version_number", r.versionNumber());
        out.put("content_sha256", r.contentSha256());
        out.put("started_at", text(r.startedAt()));
        out.put("finished_at", text(r.finishedAt()));
        out.put("snapshot_collected_at", text(r.snapshotCollectedAt()));
        out.put("coverage", r.coverage());
        if (r.counts() == null) {
            out.put("counts", null);
        } else {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("violation", r.counts().violation());
            counts.put("compliant", r.counts().compliant());
            counts.put("not_evaluable", r.counts().notEvaluable());
            counts.put("unknown", r.counts().unknown());
            counts.put("not_applicable", r.counts().notApplicable());
            counts.put("findings", r.counts().findings());
            out.put("counts", counts);
        }
        return out;
    }

    private static Map<String, Object> notEvaluated(NotEvaluatedPolicy p, Labels labels) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("name", labels.policyName(p.name()));
        out.put("vendor", p.vendor());
        out.put("source_id", p.sourceId());
        out.put("source_name", labels.sourceName(p.sourceName(), p.sourceId()));
        out.put("reason", p.reason());
        return out;
    }

    private static Map<String, Object> storedFinding(StoredFinding s, String activeVersionId, Map<String, String> policyNames,
            Labels labels) {
        Map<String, Object> out = finding(s.finding(), s.versionId(), policyNames, labels);
        // The opaque key of the audited witness read (GET /api/v2/zone-matrix/findings/{findingId}/witness); never the witness.
        out.put("finding_id", s.findingId() == null ? null : String.valueOf(s.findingId()));
        out.put("run_id", s.runId());
        out.put("version_id", s.versionId());
        out.put("version_number", s.versionNumber());
        out.put("content_sha256", s.contentSha256());
        out.put("on_active_version", activeVersionId != null && activeVersionId.equals(s.versionId()));
        out.put("policy_staleness", s.policyStaleness().name());
        out.put("snapshot_age_seconds", seconds(s.snapshotAge()));
        return out;
    }

    /** The named fields of one stored finding; zone names come from {@code versionId}'s content. */
    private static Map<String, Object> finding(ZoneMatrixRunRepository.Finding f, String versionId,
            Map<String, String> policyNames, Labels labels) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy_source_id", f.policySourceId());
        out.put("policy_name", policyNames.getOrDefault(f.policySourceId(), "UNKNOWN"));
        out.put("rule_uid", f.ruleUid());
        out.put("inline_parent_path", f.inlineParentPath());
        out.put("rule_id", f.ruleId());
        out.put("rule_number", f.ruleNumber());
        out.put("rule_name", labels.ruleName(f.ruleName(), f.ruleNumber()));
        out.put("target_device_ids", f.targetDeviceIds());
        out.put("outcome", f.outcome());
        out.put("reason", f.reason());
        out.put("highest_severity", f.highestSeverity());
        out.put("flags", f.flags());
        out.put("covering_rule_id", f.coveringRuleId());
        out.put("violated_pairs", pairs(f, versionId, labels));
        out.put("undecided_parts", f.undecidedParts());
        out.put("first_observed_at", text(f.firstObservedAt()));
        out.put("first_observed_any_version", text(f.firstObservedAnyVersion()));
        out.put("last_observed_at", text(f.lastObservedAt()));
        return out;
    }

    /** One row per violated cell (statement, severity, not-allowed services); no witness, no NAT view. */
    private static List<Map<String, Object>> pairs(ZoneMatrixRunRepository.Finding f, String versionId, Labels labels) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!f.violatedCells().isEmpty()) {
            for (ZoneMatrixRunRepository.Cell c : f.violatedCells()) {
                Map<String, Object> row = pair(c.sourceZone(), c.destinationZone(), versionId, labels);
                row.put("statement", c.statement());
                row.put("severity", c.severity());
                row.put("not_allowed_services", c.notAllowedServices());
                out.add(row);
            }
            return out;
        }
        for (String p : f.violatedPairs()) {
            int arrow = p.indexOf("->");
            Map<String, Object> row = arrow < 0
                    ? pair(p, null, versionId, labels)
                    : pair(p.substring(0, arrow), p.substring(arrow + 2), versionId, labels);
            row.put("statement", null);
            row.put("severity", null);
            row.put("not_allowed_services", List.of());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> pair(String source, String destination, String versionId, Labels labels) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_zone_id", source);
        row.put("destination_zone_id", destination);
        row.put("source_name", zone(source, versionId, labels));
        row.put("destination_name", zone(destination, versionId, labels));
        return row;
    }

    /** A zone id as the caller may see it in {@code versionId}'s content; UNASSIGNED stays, a missing id is UNKNOWN. */
    static String zone(String zoneId, String versionId, Labels labels) {
        if (zoneId == null) return "UNKNOWN";
        if (ZoneMatrixContent.UNASSIGNED.equals(zoneId)) return ZoneMatrixContent.UNASSIGNED;
        return labels.zoneName(versionId, zoneId);
    }

    /**
     * Per-cell open-violation counts (section 7.1 Matrix tab, 7.2): always the fleet headline's population, never filtered by
     * {@code policy_id} / {@code outcome}, capped by {@code limit} with the cap said. A count is a lower bound unless
     * {@code cell_counts_exact} (the headline's {@code exact}). No active version: every key is {@code null}, never 0.
     */
    private static void putCellCounts(Map<String, Object> out, StoredSummary summary, String activeVersionId, Labels labels,
            int limit) {
        List<ZoneMatrixRunService.CellCount> counts = summary.cellCounts();
        if (counts == null || activeVersionId == null) {
            out.put("cell_counts", null);
            out.put("cell_counts_exact", null);
            out.put("cell_counts_total", null);
            out.put("cell_counts_returned", null);
            out.put("cell_counts_more", null);
            out.put("cell_counts_capped", null);
            return;
        }
        List<ZoneMatrixRunService.CellCount> sorted = counts.stream().sorted(CELL_ORDER).toList();
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(sorted.size(), limit));
        for (ZoneMatrixRunService.CellCount c : sorted.subList(0, Math.min(sorted.size(), limit))) {
            Map<String, Object> row = pair(c.sourceZoneId(), c.destinationZoneId(), activeVersionId, labels);
            row.put("headline_rules", c.headlineRules());
            row.put("outside_headline_rules", c.outsideHeadlineRules());
            row.put("highest_severity", c.highestSeverity());
            rows.add(row);
        }
        out.put("cell_counts", rows);
        out.put("cell_counts_exact", summary.headline() == null ? null : summary.headline().exact());
        out.put("cell_counts_total", sorted.size());
        out.put("cell_counts_returned", rows.size());
        out.put("cell_counts_more", sorted.size() - rows.size());
        out.put("cell_counts_capped", sorted.size() > rows.size());
    }

    private static Map<String, Object> resolvedByVersion(StoredSummary summary, int limit, Map<String, String> policyNames,
            Labels labels) {
        List<ResolvedByVersionFinding> recent = summary.resolvedByVersionChangeLast7Days().stream()
                .sorted(Comparator.comparing(ResolvedByVersionFinding::resolvedAt).reversed()
                        .thenComparing(r -> r.finding(), FINDING_IDENTITY))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResolvedByVersionFinding r : recent.subList(0, Math.min(recent.size(), limit))) {
            Map<String, Object> row = finding(r.finding(), r.resolvedFromVersionId(), policyNames, labels);
            row.put("resolved_from_version_id", r.resolvedFromVersionId());
            row.put("resolved_from_version_number", r.resolvedFromVersionNumber());
            row.put("resolved_in_version_id", r.resolvedInVersionId());
            row.put("resolved_in_version_number", r.resolvedInVersionNumber());
            row.put("resolved_at", text(r.resolvedAt()));
            rows.add(row);
        }
        List<Map<String, Object>> byVersion = new ArrayList<>();
        summary.resolvedByVersionChangeByVersion().forEach((versionId, list) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version_id", versionId);
            row.put("version_number", list.isEmpty() ? null : versionNumber(list));
            row.put("count", list.size());
            byVersion.add(row);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", ZoneMatrixRunService.RESOLVED_BY_VERSION_OVERVIEW_WINDOW.toDays());
        out.put("total_last_7_days", recent.size());
        out.put("returned", rows.size());
        out.put("more", recent.size() - rows.size());
        out.put("capped", recent.size() > rows.size());
        out.put("last_7_days", rows);
        out.put("by_version", byVersion);
        return out;
    }

    private static Integer versionNumber(Collection<ResolvedByVersionFinding> list) {
        ResolvedByVersionFinding first = list.iterator().next();
        return first.resolvedInVersionNumber() != null ? first.resolvedInVersionNumber() : first.resolvedFromVersionNumber();
    }

    // ---------------------------------------------------------------- order and helpers

    private static int severityRank(String severity) {
        int i = severity == null ? -1 : SEVERITY_ORDER.indexOf(severity);
        return i < 0 ? SEVERITY_ORDER.size() : i;
    }

    private static int outcomeRank(String outcome) {
        int i = OUTCOMES.indexOf(outcome);
        return i < 0 ? OUTCOMES.size() : i;
    }

    private static final Comparator<ZoneMatrixRunRepository.Finding> FINDING_IDENTITY = Comparator
            .comparing((ZoneMatrixRunRepository.Finding f) -> f.policySourceId())
            .thenComparing(f -> f.ruleNumber(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ZoneMatrixRunRepository.Finding::ruleUid)
            .thenComparing(f -> String.join("/", f.inlineParentPath()))
            .thenComparing(f -> String.join(",", f.violatedPairs()));

    /** Severity (CRITICAL first, none last), outcome (VIOLATION, UNKNOWN, NOT_EVALUABLE), policy, rule number, identity. */
    static final Comparator<StoredFinding> FINDING_ORDER = Comparator
            .comparingInt((StoredFinding s) -> severityRank(s.finding().highestSeverity()))
            .thenComparingInt(s -> outcomeRank(s.finding().outcome()))
            .thenComparing(StoredFinding::finding, FINDING_IDENTITY)
            .thenComparing(StoredFinding::runId);

    /** Highest severity first (none last), then source and destination zone ids. */
    private static final Comparator<ZoneMatrixRunService.CellCount> CELL_ORDER = Comparator
            .comparingInt((ZoneMatrixRunService.CellCount c) -> severityRank(c.highestSeverity()))
            .thenComparing(ZoneMatrixRunService.CellCount::sourceZoneId)
            .thenComparing(ZoneMatrixRunService.CellCount::destinationZoneId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static Long seconds(Duration d) { return d == null ? null : d.toSeconds(); }

    private static String text(Instant instant) { return instant == null ? null : instant.toString(); }

    private static Refused bad(String code, String reason) { return new Refused(400, code, reason); }
}
