package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Column;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;

/** One R1 report builder: stored evidence in, one unprojected {@link ReportDocument} out. Pure: no write, no I/O. */
public interface ReportBuilder {

    /**
     * What a build knows about its run: the template, its parameters, the clock, the requesting actor and the caller's
     * authorised view. Builders whose content does not depend on the view ignore {@code caller}; the one masking pass
     * still projects their document.
     */
    record Context(ReportTemplate template, ReportParameters parameters, Instant now, String actor, Caller caller) {
        public Context {
            caller = caller == null ? Caller.NONE : caller;
        }

        public Context(ReportTemplate template, ReportParameters parameters, Instant now, String actor) {
            this(template, parameters, now, actor, Caller.NONE);
        }
    }

    /**
     * The caller's view as {@link ReportService} authorised it before the build (ZONE_MATRIX_CONTRACT.md 3.4, 7.3;
     * ZM-31, ZM-38): the projection, whether the session is a replay viewer, and the rights a view-dependent builder
     * needs ({@code policy_read}, {@code zone_matrix_zones_read}, {@code report_raw_view}). {@link #NONE} holds no right
     * and is masked.
     */
    record Caller(ProjectionMode projection, boolean replayViewer, boolean policyRead, boolean zonesRead,
            boolean rawRight) {
        public static final Caller NONE = new Caller(ProjectionMode.MASKED, false, false, false, false);

        public Caller {
            projection = projection == null ? ProjectionMode.MASKED : projection;
        }

        /** The masked screen view: a MASKED projection, or any replay viewer whatever it asked for. */
        public boolean maskedView() {
            return projection != ProjectionMode.RAW || replayViewer;
        }
    }

    ReportTemplate template();

    ReportDocument build(Context context, ReportData data);

    static ReportBuilder forTemplate(ReportTemplate template) {
        return switch (template) {
            case RPT_01 -> new ExecutiveSummaryBuilder();
            case RPT_02 -> new ComplianceCoverageBuilder();
            case RPT_03 -> new BackupCoverageBuilder();
            case RPT_05 -> new HaReadinessBuilder();
            case RPT_17 -> new LifecycleLicensingBuilder();
            case ZM_01 -> new ZoneMatrixEvidenceReportBuilder();
        };
    }

    // ---- shared helpers -------------------------------------------------------------------------------------

    static ReportDocument document(Context ctx, List<PlaneAsOf> dataAsOf, List<String> notices,
            List<ReportDocument.Section> sections) {
        List<String> all = new ArrayList<>(notices);
        for (PlaneAsOf plane : dataAsOf) {
            if (plane.anyStale()) {
                all.add("STALE: " + plane.stale() + " of " + plane.covered() + " " + plane.unit() + " with " + plane.plane()
                        + " evidence have evidence older than " + Freshness.STALE_AFTER.toHours() + " h.");
            }
            if (plane.covered() < plane.total()) {
                all.add("NO EVIDENCE: " + (plane.total() - plane.covered()) + " of " + plane.total() + " " + plane.unit()
                        + " have no " + plane.plane() + " evidence; they are shown as UNKNOWN, never as pass or fail.");
            }
        }
        return new ReportDocument(ctx.template().id(), ctx.template().title(), ctx.template().audience(),
                ctx.template().question(), ctx.now(), ReportDocument.UNPROJECTED, ctx.parameters().asMap(), dataAsOf,
                all, sections);
    }

    static List<Column> columns(String... keyLabelPairs) {
        List<Column> out = new ArrayList<>();
        for (int i = 0; i < keyLabelPairs.length; i += 2) out.add(new Column(keyLabelPairs[i], keyLabelPairs[i + 1]));
        return out;
    }

    static Cell device(DeviceSummaryRecord d, String deviceIdFallback) {
        if (d == null) return Cell.device(null, deviceIdFallback, null);
        return Cell.device(d.observedHostname().orElse(null), d.deviceId(), d.clusterMemberRef().orElse(null));
    }

    static String vendorLabel(String vendor) {
        return Map.of("check_point", "Check Point", "palo_alto", "Palo Alto", "fortinet", "Fortinet",
                "cisco_asa", "Cisco ASA", "infoblox", "Infoblox", "radware", "Radware", "pulse_secure", "Pulse Secure",
                "bluecoat", "Symantec ProxySG").getOrDefault(vendor == null ? "" : vendor, vendor == null ? "UNKNOWN" : vendor);
    }
}
