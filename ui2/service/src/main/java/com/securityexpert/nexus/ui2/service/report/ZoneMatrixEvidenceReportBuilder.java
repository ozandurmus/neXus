package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Revision;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedFinding;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceBlocksAppendix;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReportInputs;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.PolicyCoverage;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixSummaryView;

/**
 * ZM-01, the zone matrix evidence report through Reports R1 (docs/design/ZONE_MATRIX_CONTRACT.md 3.4, 7.3, 8.2 and
 * 10.1 step 6; {@code ZM-28}, {@code ZM-31}, {@code ZM-32}, {@code ZM-38}). It composes the pure step 6b-1 parts:
 * {@link ZoneMatrixEvidenceReportInputs} over the stored read models, {@link ZoneMatrixEvidenceReport} for the content
 * and, only when opted in and permitted, {@link ZoneMatrixEvidenceBlocksAppendix}.
 *
 * <ul>
 *   <li><b>View.</b> The content follows the caller's screen view: masked ({@code ZONE-<n>}, {@code PolicyPrivacy},
 *       {@code USER-<n>}) for a MASKED projection and for every replay viewer; names otherwise, with policy names
 *       still masked without {@code policy_read}. The one masking pass of {@link ReportDocumentMasker} then runs over
 *       the result as for every report.</li>
 *   <li><b>Period.</b> The report window {@code [windowStart, windowEnd)} bounds the findings and the one
 *       {@link Source#resolvedInPeriod} read (ZM-32).</li>
 *   <li><b>Blocks appendix.</b> Only for a RAW projection by a caller holding {@code report_raw_view} and
 *       {@code zone_matrix_zones_read} who is not a replay viewer; the block view is on record before it is read.
 *       {@link ReportService} refuses any other request before a build; this builder refuses it again
 *       ({@link ZoneMatrixEvidenceBlocksAppendix#BLOCKS_NOT_PERMITTED}) before reading anything.</li>
 * </ul>
 * Reads stored evidence only: no device, no job, no write except the audited block view.
 */
public final class ZoneMatrixEvidenceReportBuilder implements ReportBuilder {

    /** The "data as of" plane: the stored policy snapshots the evaluation runs read. */
    public static final String PLANE = "zone matrix policy snapshot";

    /** Added when the opt-in appendix is part of the content. */
    public static final String APPENDIX_NOTICE = "The zone blocks appendix is included at the requester's opt-in (RAW "
            + "projection, report_raw_view and zone_matrix_zones_read); the block view is on record.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What ZM-01 reads: the stored zone matrix read models, the caller's name projection and the audited blocks. */
    public interface Source {
        StoredSummary storedSummary();

        /** Findings resolved for any reason within {@code [from, to)} (ZM-32). */
        List<ResolvedFinding> resolvedInPeriod(Instant from, Instant to);

        /** The caller's name projection: masked persona view, or names (policy names masked without policy_read). */
        ZoneMatrixSummaryView.Labels labels(boolean masked, boolean policyRead);

        /** The edit revisions of a version (editors); never the publish row. */
        List<Revision> revisions(String versionId);

        /** The zones and blocks of a version, on record first ({@code BLOCKS_VIEW}). */
        List<ZoneMatrixContent.Zone> auditedBlocks(String actor, String versionId);

        /** Production source over the zone matrix services and the version store. */
        static Source of(ZoneMatrixService matrix, ZoneMatrixRunService runs, ZoneMatrixRepository versions) {
            Objects.requireNonNull(matrix, "matrix");
            Objects.requireNonNull(runs, "runs");
            Objects.requireNonNull(versions, "versions");
            return new Source() {
                @Override
                public StoredSummary storedSummary() {
                    return runs.storedSummary();
                }

                @Override
                public List<ResolvedFinding> resolvedInPeriod(Instant from, Instant to) {
                    return runs.resolvedInPeriod(from, to);
                }

                @Override
                public ZoneMatrixSummaryView.Labels labels(boolean masked, boolean policyRead) {
                    return matrix.summaryLabels(masked ? ZoneMatrixService.View.MASKED : ZoneMatrixService.View.NAMES,
                            policyRead);
                }

                @Override
                public List<Revision> revisions(String versionId) {
                    return versions.revisions(versionId).stream().filter(r -> !"PUBLISH".equals(r.changeKind())).toList();
                }

                @Override
                public List<ZoneMatrixContent.Zone> auditedBlocks(String actor, String versionId) {
                    return matrix.blocksForReport(actor, versionId);
                }
            };
        }
    }

    @Override
    public ReportTemplate template() {
        return ReportTemplate.ZM_01;
    }

    @Override
    public ReportDocument build(Context ctx, ReportData data) {
        Caller caller = ctx.caller();
        ReportParameters p = ctx.parameters();
        boolean masked = caller.maskedView();
        // A masked view never carries blocks: both rights count only for a RAW, non-replay-viewer caller.
        var rights = new ZoneMatrixEvidenceBlocksAppendix.Rights(p.includeBlocksAppendix(), !masked && caller.zonesRead(),
                !masked && caller.rawRight(), caller.replayViewer());
        if (rights.appendixRequested() && !rights.permitted()) throw blocksNotPermitted();

        Source source = Objects.requireNonNull(data.zoneMatrixEvidence(), "zone matrix evidence source");
        ZoneMatrixSummaryView.Labels labels = source.labels(masked, !masked && caller.policyRead());
        StoredSummary summary = source.storedSummary();
        Instant from = p.windowStart();
        Instant to = p.windowEnd();
        // No upper bound: a finding open in the period but resolved after it ends is kept (contract 7.3); inclusion filters.
        List<ResolvedFinding> resolved = source.resolvedInPeriod(from, null);
        Version active = summary.activeVersion();
        List<Revision> revisions = active == null ? null : source.revisions(active.versionId());
        var request = new ZoneMatrixEvidenceReportInputs.Request(productVersion(), ctx.actor(), from, to, p.policyId(),
                p.outcome(), null, revisions);
        var input = ZoneMatrixEvidenceReportInputs.assemble(summary, resolved, labels, MAPPER, request, ctx.now());
        var built = ZoneMatrixEvidenceReport.build(input, ctx.now());

        List<String> notices = new ArrayList<>(built.notices());
        List<Section> sections = new ArrayList<>(built.sections());
        if (rights.appendixRequested()) {
            List<ZoneMatrixContent.Zone> zones = active == null ? List.of()
                    : source.auditedBlocks(ctx.actor(), active.versionId());
            var appendix = ZoneMatrixEvidenceBlocksAppendix.build(zones, rights);
            if (!appendix.isIncluded()) throw blocksNotPermitted();
            sections.addAll(appendix.sections());
            notices.add(APPENDIX_NOTICE);
        }
        ReportTemplate t = ctx.template();
        return new ReportDocument(t.id(), built.title(), t.audience(), t.question(), ctx.now(), ReportDocument.UNPROJECTED,
                p.asMap(), List.of(plane(summary)), notices, sections);
    }

    static ReportService.Refusal blocksNotPermitted() {
        return new ReportService.Refusal(403, ZoneMatrixEvidenceBlocksAppendix.BLOCKS_NOT_PERMITTED,
                "The zone blocks appendix needs a RAW report, report_raw_view and zone_matrix_zones_read, and is never "
                        + "available to a masked (AIView) session");
    }

    /**
     * Data as of: newest and oldest stored snapshot among the policies with a COMPLETE run, how many of all policies
     * (evaluable and not evaluated) have one, and how many of those are STALE by the zone matrix threshold.
     */
    static PlaneAsOf plane(StoredSummary summary) {
        Instant newest = null;
        Instant oldest = null;
        int covered = 0;
        int stale = 0;
        for (PolicyCoverage p : summary.policies()) {
            if (p.latestCompleteRun() == null) continue;
            covered++;
            if (p.staleness().isStale()) stale++;
            Instant at = instant(p.collectedAt());
            if (at == null) continue;
            if (newest == null || at.isAfter(newest)) newest = at;
            if (oldest == null || at.isBefore(oldest)) oldest = at;
        }
        return new PlaneAsOf(PLANE, newest, oldest, covered, summary.policies().size() + summary.notEvaluated().size(),
                stale, "policies");
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The build's implementation version when the jar states one; otherwise the header prints UNKNOWN. */
    private static String productVersion() {
        Package pkg = ZoneMatrixEvidenceReportBuilder.class.getPackage();
        return pkg == null ? null : pkg.getImplementationVersion();
    }
}
