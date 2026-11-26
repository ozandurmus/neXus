package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Revision;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedByVersionFinding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedFinding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.CoverageItem;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.FindingIdentity;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.FindingRow;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Input;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.MatrixCell;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.MatrixVersion;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.NotEvaluated;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.PolicyScope;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Regulation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Summary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.ResolutionReason;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Transition;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.NotEvaluatedPolicy;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.PolicyCoverage;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredFinding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredHeadline;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyStaleness;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.RunState;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.StalenessThreshold;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixSummaryView.Labels;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Zone matrix delivery step 6b-1: assembles the {@link ZoneMatrixEvidenceReport.Input} of the evidence report from the
 * stored read models the step 4b-2b summary route reads, projected for the caller's screen view
 * (docs/design/ZONE_MATRIX_CONTRACT.md 3.4, 7.3, 8.2; {@code ZM-11}, {@code ZM-31}, {@code ZM-32}; amendment
 * 2026-11-23).
 *
 * <p>Pure and deterministic: no Spring, no database, no clock. {@code now} and {@code generatedBy} are parameters and
 * every stored read model is passed in. Nothing here is registered in the Reports catalogue, adds a route, an action
 * or a right; rights, the stored-file audit row and the raw-only blocks appendix ({@code ZM-38}) are step 6b-2.</p>
 *
 * <h2>Screen view</h2>
 * <ul>
 *   <li>Policy, source, container, rule and zone names go through {@link Labels} exactly as
 *       {@link ZoneMatrixSummaryView} maps them (production source: {@link ZoneMatrixService#summaryLabels}); the zone
 *       name of a finding comes from the finding's own version, the zone name of a cell from the active version.
 *       Section names are not part of the report content (the builder has no section column).</li>
 *   <li>The other fields {@code ZoneMatrixService}'s screen view masks for the masked persona are masked the same way:
 *       actor names ({@code generatedBy}, editors, publisher, confirmed by) become {@code USER-<n>} tokens in
 *       first-seen order; service set names become {@code SET-<n>} by set name order; a non-empty regulation
 *       reference is withheld behind {@link #WITHHELD_REFERENCE} (the screen shows none), which keeps the
 *       "N of M cells confirmed" count and the BDDK title the same in both views. Otherwise reference fields are
 *       passed exactly as entered (the R1 CSV renderer owns the formula guard).</li>
 *   <li>Never blocks, addresses, witness tuples or NAT views: only {@code sourceZone()} / {@code destinationZone()} of
 *       a stored violated cell are read, never {@code witness()} or {@code natView()}, and the finding's
 *       {@code natView()} is never read either.</li>
 * </ul>
 *
 * <h2>Fail-closed rules</h2>
 * A count that cannot be given is {@code null} (printed UNKNOWN), never {@code 0}: the whole-scope count when an
 * evaluable policy has no {@code COMPLETE} run, the filtered count when the filtered scope contains one, and both counts
 * for a period report, because the stored summary carries only open findings and the findings resolved by a matrix
 * version change; the findings resolved for any reason within the period come from a separate period read (ZM-32), and
 * without it other resolutions are not read here. Findings are never capped or dropped: every stored VIOLATION,
 * NOT_EVALUABLE and UNKNOWN row is passed to the builder, which owns period membership. An undecided rule stored
 * without a finding lifecycle (a standalone NOT_EVALUABLE or UNKNOWN row) gets no lifecycle, so its dates print
 * UNKNOWN. Unreadable active-version content refuses the input instead of printing an empty matrix.
 */
public final class ZoneMatrixEvidenceReportInputs {
    private ZoneMatrixEvidenceReportInputs() {}

    /** Printed instead of a non-empty regulation reference in the masked view (the screen shows none, 8.2). */
    public static final String WITHHELD_REFERENCE = "WITHHELD (masked view)";

    /** Stored coverage items carry their code only (section 5.9); the description is not invented here. */
    static final String COVERAGE_DESCRIPTION_NOT_STORED = "description not stored with the run (code only)";

    private static final String UNKNOWN = "UNKNOWN";

    /**
     * What the caller asked for. {@code policyId} and {@code outcome} filter the findings as the summary route does
     * ({@code null}: not filtered; validated by {@link ZoneMatrixSummaryView.Query#parse}); the report never caps.
     * {@code periodFrom} is inclusive, {@code periodTo} exclusive, {@code null} is no bound. {@code regulation} is the
     * bank's named regulation (6.2 item 1), {@code null} when not named. {@code revisions} are the active version's
     * revision rows (editors); {@code null} when not read, which prints the editors as UNKNOWN.
     */
    public record Request(String productVersion, String generatedBy, Instant periodFrom, Instant periodTo,
            String policyId, String outcome, Regulation regulation, List<Revision> revisions) {
        public Request {
            revisions = revisions == null ? null : List.copyOf(revisions);
        }
    }

    /**
     * Builds the report input from the stored summary for the caller's view.
     *
     * @param summary the stored summary ({@link ZoneMatrixRunService#storedSummary()}); its active version is cited
     * @param labels  the caller's name projection ({@link ZoneMatrixService#summaryLabels})
     * @param mapper  reads the active version's stored content
     * @param request what the caller asked for
     * @param now     the generation time
     */
    public static Input assemble(StoredSummary summary, Labels labels, ObjectMapper mapper, Request request, Instant now) {
        return assemble(summary, null, labels, mapper, request, now);
    }

    /**
     * {@link #assemble(StoredSummary, Labels, ObjectMapper, Request, Instant)} with the findings resolved within the
     * period for any reason ({@link ZoneMatrixRunService#resolvedInPeriod}, read once by the caller; ZM-32). A finding the
     * summary already lists (open, or resolved by a matrix version change) appears once. {@code null} means the read
     * was not made: only the version-change resolutions are then listed and the filter description says so.
     */
    public static Input assemble(StoredSummary summary, List<ResolvedFinding> resolvedInPeriod, Labels labels,
            ObjectMapper mapper, Request request, Instant now) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");

        ZoneMatrixSummaryView.Query filter = ZoneMatrixSummaryView.Query.parse(request.policyId(), request.outcome(), null);
        if (filter.policyId() != null && summary.policies().stream().noneMatch(p -> p.policyId().equals(filter.policyId()))
                && summary.notEvaluated().stream().noneMatch(p -> p.id().equals(filter.policyId()))) {
            throw new Refused(400, "UNKNOWN_POLICY", "policy_id names no policy in the zone matrix coverage");
        }

        Actors actors = new Actors(labels.masked());
        String generatedBy = request.generatedBy() == null ? null : actors.of(request.generatedBy());
        Map<String, String> policyNames = ZoneMatrixSummaryView.policyNames(summary, labels);
        Version active = summary.activeVersion();
        boolean period = request.periodFrom() != null || request.periodTo() != null;

        MatrixVersion version = null;
        List<MatrixCell> cells = List.of();
        if (active != null) {
            ZoneMatrixContent content = ZoneMatrixContent.parse(active.content(), mapper);
            version = version(active, content, request.revisions(), actors);
            cells = cells(active.versionId(), content, labels, actors);
        }

        List<PolicyScope> policies = new ArrayList<>(summary.policies().size());
        List<NotEvaluated> notEvaluated = new ArrayList<>();
        String activeId = active == null ? null : active.versionId();
        for (PolicyCoverage p : summary.policies()) {
            policies.add(scope(p, labels, activeId, summary.stalenessThreshold(), now));
            if (p.latestCompleteRun() == null) {
                notEvaluated.add(new NotEvaluated(subject(labels.policyName(p.policyName()), p.sourceName(),
                        p.sourceId(), labels), kind(p.vendor()), "no COMPLETE evaluation run (latest run state: "
                        + (p.latestRun() == null ? "none" : orUnknown(p.latestRun().state())) + ")"));
            }
        }
        for (NotEvaluatedPolicy p : summary.notEvaluated()) {
            notEvaluated.add(new NotEvaluated(subject(labels.policyName(p.name()), p.sourceName(), p.sourceId(), labels),
                    kind(p.vendor()), p.reason()));
        }

        List<FindingRow> rows = new ArrayList<>();
        int whole = 0;
        int filtered = 0;
        Set<List<Object>> listed = new LinkedHashSet<>();
        for (StoredFinding s : summary.findings()) {
            whole++;
            if (!matches(filter, s.finding())) continue;
            filtered++;
            rows.add(row(s.finding(), s.versionId(), policyNames, labels));
            if (s.finding().resolvedAt() != null) listed.add(resolvedKey(s.finding()));
        }
        if (period) {
            for (ResolvedByVersionFinding r : resolvedByVersionChange(summary)) {
                if (!matches(filter, r.finding())) continue;
                if (!listed.add(resolvedKey(r.finding()))) continue;
                rows.add(resolvedRow(r, policyNames, labels));
            }
            if (resolvedInPeriod != null) {
                for (ResolvedFinding r : resolvedInPeriod) {
                    if (!matches(filter, r.finding())) continue;
                    if (!listed.add(resolvedKey(r.finding()))) continue;
                    rows.add(resolvedAnyReasonRow(r, policyNames, labels));
                }
            }
        }

        boolean wholeKnown = !period && wholeScopeKnown(summary);
        boolean filteredKnown = !period && filteredScopeKnown(summary, filter.policyId());

        return new Input(
                request.productVersion(),
                generatedBy,
                viewLabel(labels),
                request.periodFrom(),
                request.periodTo(),
                filterDescription(filter, labels, summary, period, resolvedInPeriod != null),
                filteredKnown ? filtered : null,
                wholeKnown ? whole : null,
                request.regulation(),
                version,
                cells,
                displayThreshold(summary.stalenessThreshold()),
                policies,
                notEvaluated,
                summary(summary.headline(), summary.resolvedByVersionChangeLast7Days().size()),
                rows);
    }

    // ------------------------------------------------------------------ header

    private static String viewLabel(Labels labels) {
        if (labels.masked()) {
            return "MASKED: zone names as ZONE-<n>, policy, source, container and rule names through PolicyPrivacy, "
                    + "actors as USER-<n>; no blocks, no witness addresses";
        }
        if (labels.policyNamesMasked()) {
            return "NAMES: zone names as stored; policy, source, container and rule names through PolicyPrivacy "
                    + "(no policy_read); no blocks, no witness addresses";
        }
        return "NAMES: zone, policy, source, container and rule names as stored; no blocks, no witness addresses";
    }

    private static String filterDescription(ZoneMatrixSummaryView.Query filter, Labels labels, StoredSummary summary,
            boolean period, boolean allResolutionsRead) {
        List<String> parts = new ArrayList<>();
        if (filter.policyId() != null) {
            String name = summary.policies().stream().filter(p -> p.policyId().equals(filter.policyId()))
                    .map(p -> labels.policyName(p.policyName())).findFirst()
                    .orElseGet(() -> summary.notEvaluated().stream().filter(p -> p.id().equals(filter.policyId()))
                            .map(p -> labels.policyName(p.name())).findFirst().orElse(null));
            parts.add("policy " + orUnknown(name));
        }
        if (filter.outcome() != null) {
            parts.add("outcome " + filter.outcome());
        }
        if (period && allResolutionsRead) {
            parts.add("resolved findings listed are those resolved within the period for any reason (matrix version "
                    + "change, rule changed, rule removed or disabled); the finding counts are UNKNOWN");
        } else if (period) {
            parts.add("resolved findings listed are only those resolved by a matrix version change; other "
                    + "resolutions are not read for this report, so the finding counts are UNKNOWN");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * A policy's findings are a complete count only when its latest run is {@code COMPLETE} <em>and</em> that run's
     * stored coverage is {@code COMPLETE} (ZM-26): a finished run over a truncated rulebase or with collection
     * failures still leaves findings uncounted, so its count is a lower bound, never an exact figure.
     */
    private static boolean countable(PolicyCoverage p) {
        return p.latestCompleteRun() != null && "COMPLETE".equals(p.coverage());
    }

    /** The whole scope is known only when there is at least one evaluable policy and every one of them is countable. */
    private static boolean wholeScopeKnown(StoredSummary summary) {
        return !summary.policies().isEmpty() && summary.policies().stream().allMatch(ZoneMatrixEvidenceReportInputs::countable);
    }

    /** The filtered scope is known only when every evaluable policy in it is countable (and there is one). */
    private static boolean filteredScopeKnown(StoredSummary summary, String policyId) {
        if (policyId == null) {
            return wholeScopeKnown(summary);
        }
        return summary.policies().stream().anyMatch(p -> p.policyId().equals(policyId) && countable(p));
    }

    /** The threshold as printed: its label states the interim value (amendment 2026-11-23) or its source. */
    private static StalenessThreshold displayThreshold(StalenessThreshold t) {
        return new StalenessThreshold(t.value(), ZoneMatrixSummaryView.thresholdLabel(t));
    }

    // ------------------------------------------------------------------ matrix version

    private static MatrixVersion version(Version v, ZoneMatrixContent content, List<Revision> revisions, Actors actors) {
        String publisher = v.publishedBy() == null ? null : actors.of(v.publishedBy());
        List<String> editors = new ArrayList<>();
        if (revisions != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (Revision r : revisions) {
                if (r.actor() != null && seen.add(r.actor())) editors.add(actors.of(r.actor()));
            }
        }
        var origin = content.matrix().templateOrigin();
        String templateOrigin = origin == null ? null : origin.name() + " (revision " + origin.revision() + ")";
        return new MatrixVersion(v.number(), v.contentSha256(), v.publishedAt(), editors, publisher, templateOrigin,
                content.matrix().defaultStatement(), content.matrix().defaultSeverity());
    }

    private static List<MatrixCell> cells(String versionId, ZoneMatrixContent content, Labels labels, Actors actors) {
        Map<String, String> setNames = new HashMap<>();
        List<ZoneMatrixContent.ServiceSet> sets = content.matrix().serviceSets();
        for (int i = 0; i < sets.size(); i++) {
            setNames.put(sets.get(i).name(), labels.masked() ? "SET-" + (i + 1) : sets.get(i).name());
        }
        List<MatrixCell> out = new ArrayList<>(content.matrix().cells().size());
        for (ZoneMatrixContent.Cell c : content.matrix().cells()) {
            List<String> services = new ArrayList<>(c.services().size());
            for (String entry : c.services()) {
                services.add(serviceEntry(entry, setNames, labels.masked()));
            }
            String reference = labels.masked() && !c.regulationReference().isBlank()
                    ? WITHHELD_REFERENCE : c.regulationReference();
            out.add(new MatrixCell(
                    ZoneMatrixSummaryView.zone(c.source(), versionId, labels),
                    ZoneMatrixSummaryView.zone(c.destination(), versionId, labels),
                    c.statement(), services, c.severity(), reference, c.referenceStatus(),
                    c.confirmedBy() == null ? null : actors.of(c.confirmedBy()), c.confirmedAt()));
        }
        return out;
    }

    /** A service entry as the screen shows it: a set reference by its projected set name, anything else as stored. */
    private static String serviceEntry(String entry, Map<String, String> setNames, boolean masked) {
        ServiceEntry parsed;
        try {
            parsed = ServiceEntry.parse(entry);
        } catch (IllegalArgumentException e) {
            return masked && entry != null && entry.startsWith("set:") ? "set:SET-?" : entry;
        }
        return parsed.kind() == ServiceEntry.Kind.SET ? "set:" + setNames.getOrDefault(parsed.token(), "SET-?") : entry;
    }

    // ------------------------------------------------------------------ scope

    private static PolicyScope scope(PolicyCoverage p, Labels labels, String activeVersionId,
            StalenessThreshold threshold, Instant now) {
        ZoneMatrixRunRepository.Run complete = p.latestCompleteRun();
        RunState lastRunState = p.latestRun() == null ? null : runState(p.latestRun().state());
        PolicyStaleness staleness = ZoneMatrixStaleness.assess(new PolicyInput(p.policyId(), p.collectedAt(),
                lastRunState, complete == null ? null : complete.versionId(), activeVersionId), now, threshold);
        String container = labels.containerName(p.containerName());
        String policy = labels.policyName(p.policyName());
        if (container != null && !container.isBlank()) {
            policy = orUnknown(policy) + " (container " + container + ")";
        }
        String source = labels.sourceName(p.sourceName(), p.sourceId());
        List<CoverageItem> items = new ArrayList<>(p.coverageItems().size());
        for (String code : p.coverageItems()) {
            items.add(new CoverageItem(code, COVERAGE_DESCRIPTION_NOT_STORED));
        }
        return new PolicyScope(policy, p.vendor(), source == null || source.isBlank() ? List.of() : List.of(source),
                ZoneMatrixStaleness.parse(p.collectedAt()), complete == null ? null : complete.finishedAt(), staleness,
                complete == null ? null : complete.versionNumber(), p.coverage(), items);
    }

    /** Run state text to {@link RunState}, exact names only; anything else is unknown ({@code null}). */
    private static RunState runState(String state) {
        if (ZoneMatrixRunRepository.STATE_RUNNING.equals(state)) return RunState.RUNNING;
        if (ZoneMatrixRunRepository.STATE_COMPLETE.equals(state)) return RunState.COMPLETE;
        if (ZoneMatrixRunRepository.STATE_FAILED.equals(state)) return RunState.FAILED;
        return null;
    }

    private static String subject(String policyName, String sourceName, String sourceId, Labels labels) {
        String source = labels.sourceName(sourceName, sourceId);
        return orUnknown(policyName) + (source == null || source.isBlank() ? "" : " on " + source);
    }

    private static String kind(String vendor) {
        return "policy (vendor " + orUnknown(vendor) + ")";
    }

    // ------------------------------------------------------------------ summary

    private static Summary summary(StoredHeadline h, int resolvedLast7Days) {
        if (h == null) {
            return new Summary(null, null);
        }
        String text = "Headline (v" + h.versionNumber() + ", fleet-wide, not filtered): " + h.text() + ". "
                + ZoneMatrixStaleness.withUndecided(h.stalenessHeadline(),
                        h.notEvaluableFindings() + h.unknownFindings() + h.becameUndecidedFindings()).text()
                + ". Splits: " + h.criticalRules() + " critical rules; "
                + h.coveredByEarlierRule() + " covered by an earlier rule; " + h.conditionalRules()
                + " conditional (identity / time); " + h.outsideHeadlineOrderedLayer()
                + " rules outside the headline (other ordered layer); " + h.notEvaluableFindings()
                + " NOT_EVALUABLE findings; " + h.unknownFindings() + " UNKNOWN findings; "
                + h.becameUndecidedFindings() + " findings became undecided; " + h.stalePolicies() + " stale policies; "
                + h.incompletePolicies() + " incomplete policies; " + h.olderVersionPolicies()
                + " policies evaluated against an older version; " + h.notEvaluatedPolicies()
                + " policies not evaluated; " + resolvedLast7Days
                + " findings resolved by a matrix version change in the last 7 days. The count is "
                + (h.exact() ? "exact" : "a lower bound") + ".";
        return new Summary(text, h.outsideHeadlineText());
    }

    // ------------------------------------------------------------------ findings

    private static boolean matches(ZoneMatrixSummaryView.Query filter, ZoneMatrixRunRepository.Finding f) {
        return (filter.policyId() == null || filter.policyId().equals(f.policySourceId()))
                && (filter.outcome() == null || filter.outcome().equals(f.outcome()));
    }

    private static FindingRow row(ZoneMatrixRunRepository.Finding f, String versionId, Map<String, String> policyNames,
            Labels labels) {
        boolean stored = ZoneMatrixEvaluator.OUTCOME_VIOLATION.equals(f.outcome())
                || f.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED);
        if (!stored) {
            return findingRow(f, versionId, policyNames, labels, null);
        }
        Transition transition = f.resolvedAt() != null ? Transition.RESOLVED
                : f.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED) ? Transition.BECAME_UNDECIDED
                : Transition.REPRODUCED;
        return findingRow(f, versionId, policyNames, labels, lifecycle(f, versionId, transition,
                resolutionReason(f.resolutionReason()), f.resolvedAt(), null, null));
    }

    private static FindingRow resolvedRow(ResolvedByVersionFinding r, Map<String, String> policyNames, Labels labels) {
        return findingRow(r.finding(), r.resolvedFromVersionId(), policyNames, labels, lifecycle(r.finding(),
                r.resolvedFromVersionId(), Transition.RESOLVED, ResolutionReason.MATRIX_VERSION_CHANGED,
                r.resolvedAt(), r.resolvedFromVersionId(), r.resolvedInVersionId()));
    }

    /**
     * A finding resolved within the period for any reason. A stored reason the enum does not know leaves the lifecycle
     * reason {@code null}, which the builder prints as UNKNOWN for a resolved finding; the row is never dropped.
     */
    private static FindingRow resolvedAnyReasonRow(ResolvedFinding r, Map<String, String> policyNames, Labels labels) {
        return findingRow(r.finding(), r.foundVersionId(), policyNames, labels, lifecycle(r.finding(),
                r.foundVersionId(), Transition.RESOLVED, resolutionReason(r.finding().resolutionReason()),
                r.resolvedAt(), null, null));
    }

    /** One resolved finding as the stores list it: identity and resolution instant (the version is not identity). */
    private static List<Object> resolvedKey(ZoneMatrixRunRepository.Finding f) {
        return List.of(f.policySourceId(), f.ruleUid(), f.inlineParentPath(), Set.copyOf(f.violatedPairs()),
                f.resolvedAt());
    }

    private static FindingRow findingRow(ZoneMatrixRunRepository.Finding f, String versionId,
            Map<String, String> policyNames, Labels labels, ZoneMatrixFindingLifecycle.Finding lifecycle) {
        String rule = labels.ruleName(f.ruleName(), f.ruleNumber());
        if (f.ruleNumber() != null) {
            rule = (rule == null || rule.isBlank() ? UNKNOWN : rule) + " (rule #" + f.ruleNumber() + ")";
        }
        String detail = f.undecidedParts().isEmpty() ? null : "undecided parts: " + String.join("; ", f.undecidedParts());
        return new FindingRow(policyNames.getOrDefault(f.policySourceId(), UNKNOWN), rule,
                pairNames(f, versionId, labels), f.highestSeverity(), f.outcome(), f.reason(), f.flags(), detail,
                lifecycle);
    }

    /** One {@code "from -> to"} per violated cell; reads the cell's zone ids only, never its witness or NAT view. */
    private static List<String> pairNames(ZoneMatrixRunRepository.Finding f, String versionId, Labels labels) {
        List<String> out = new ArrayList<>();
        if (!f.violatedCells().isEmpty()) {
            for (ZoneMatrixRunRepository.Cell c : f.violatedCells()) {
                out.add(ZoneMatrixSummaryView.zone(c.sourceZone(), versionId, labels) + " -> "
                        + ZoneMatrixSummaryView.zone(c.destinationZone(), versionId, labels));
            }
            return out;
        }
        for (String p : f.violatedPairs()) {
            int arrow = p.indexOf("->");
            out.add(arrow < 0
                    ? ZoneMatrixSummaryView.zone(p, versionId, labels) + " -> " + UNKNOWN
                    : ZoneMatrixSummaryView.zone(p.substring(0, arrow), versionId, labels) + " -> "
                            + ZoneMatrixSummaryView.zone(p.substring(arrow + 2), versionId, labels));
        }
        return out;
    }

    /**
     * The stored lifecycle dates of a finding. The transition of the run that stored it is not stored; the builder
     * reads only the status (open or resolved), the dates and the resolution reason.
     */
    private static ZoneMatrixFindingLifecycle.Finding lifecycle(ZoneMatrixRunRepository.Finding f, String versionId,
            Transition transition, ResolutionReason reason, Instant resolvedAt, String resolvedFrom, String resolvedIn) {
        return new ZoneMatrixFindingLifecycle.Finding(
                new FindingIdentity(f.policySourceId(), f.ruleUid(), f.inlineParentPath(), f.violatedPairs()),
                transition, f.outcome(), f.reason(), new LinkedHashSet<>(f.flags()), f.firstObservedAt(),
                f.firstObservedAnyVersion(), Map.of(), f.lastObservedAt(), versionId, reason, resolvedAt, resolvedFrom,
                resolvedIn, null);
    }

    private static ResolutionReason resolutionReason(String stored) {
        if (stored == null) return null;
        for (ResolutionReason r : ResolutionReason.values()) {
            if (r.name().equals(stored)) return r;
        }
        return null;
    }

    /** Every finding resolved by a matrix version change the summary carries, once each, in a stable order. */
    private static List<ResolvedByVersionFinding> resolvedByVersionChange(StoredSummary summary) {
        Map<List<Object>, ResolvedByVersionFinding> unique = new LinkedHashMap<>();
        List<ResolvedByVersionFinding> all = new ArrayList<>(summary.resolvedByVersionChangeLast7Days());
        summary.resolvedByVersionChangeByVersion().values().forEach(all::addAll);
        for (ResolvedByVersionFinding r : all) {
            ZoneMatrixRunRepository.Finding f = r.finding();
            unique.putIfAbsent(List.of(f.policySourceId(), f.ruleUid(), f.inlineParentPath(), f.violatedPairs(),
                    r.resolvedFromVersionId(), r.resolvedAt()), r);
        }
        return List.copyOf(unique.values());
    }

    // ------------------------------------------------------------------ helpers

    /** Actor names as {@code ZoneMatrixService} shows them: as stored, or {@code USER-<n>} in the masked view. */
    private static final class Actors {
        private final boolean masked;
        private final Map<String, String> tokens = new LinkedHashMap<>();

        Actors(boolean masked) { this.masked = masked; }

        String of(String actor) {
            if (!masked) return actor;
            return tokens.computeIfAbsent(actor, a -> "USER-" + (tokens.size() + 1));
        }
    }

    private static String orUnknown(String v) {
        return v == null || v.isBlank() ? UNKNOWN : v;
    }
}
