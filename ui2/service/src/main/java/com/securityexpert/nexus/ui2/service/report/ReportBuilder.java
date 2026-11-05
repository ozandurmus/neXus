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

    /** What a build knows about its run: the template, its parameters, the clock and the requesting actor. */
    record Context(ReportTemplate template, ReportParameters parameters, Instant now, String actor) {
    }

    ReportTemplate template();

    ReportDocument build(Context context, ReportData data);

    static ReportBuilder forTemplate(ReportTemplate template) {
        return switch (template) {
            case RPT_01 -> new ExecutiveSummaryBuilder();
            case RPT_02 -> new ComplianceCoverageBuilder();
            case RPT_03 -> new BackupCoverageBuilder();
            case RPT_05 -> new HaReadinessBuilder();
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
