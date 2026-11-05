package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Kpi;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;

/**
 * RPT-05 HA and failover readiness (contract "Scope"): clusters ready, not ready or unknown with the readiness age;
 * the reasons for not ready; stale readiness flagged as STALE, never as ready. Read from the stored readiness results;
 * nothing here starts a readiness run.
 */
final class HaReadinessBuilder implements ReportBuilder {

    @Override
    public ReportTemplate template() {
        return ReportTemplate.RPT_05;
    }

    @Override
    public ReportDocument build(Context ctx, ReportData data) {
        HaFacts facts = new HaFacts(data.devices(), data.haUnits(ctx.actor()), ctx.now());
        PlaneAsOf readiness = readinessPlane(facts, ctx.now());
        return ReportBuilder.document(ctx, List.of(readiness), facts.clusters.isEmpty()
                ? List.of("NO EVIDENCE: no enrolled HA cluster; readiness is UNKNOWN.") : List.of(),
                List.of(summary(facts, readiness, ctx.now()), reasons(facts, readiness, ctx.now())));
    }

    static PlaneAsOf readinessPlane(HaFacts facts, Instant now) {
        return Freshness.plane("HA readiness", facts.observedTimes(), (int) facts.supported(), now, "clusters");
    }

    private static Section summary(HaFacts facts, PlaneAsOf readiness, Instant now) {
        List<List<Cell>> rows = new ArrayList<>();
        for (HaFacts.Cluster c : facts.clusters) {
            Instant at = c.base() == null ? null : c.base().observedAt();
            rows.add(List.of(Cell.cluster(c.clusterRef()), Cell.text(ReportBuilder.vendorLabel(c.vendor())),
                    Cell.number(c.members().size()), Cell.status(c.verdict()), Cell.time(at),
                    Cell.text(Freshness.age(at, now))));
        }
        boolean none = facts.clusters.isEmpty();
        return new Section("readiness", "Cluster readiness", List.of(readiness), List.of(
                new Kpi("Ready", Long.toString(facts.count(HaFacts.READY)), none ? "UNKNOWN" : "OK"),
                new Kpi("Not ready", Long.toString(facts.count(HaFacts.NOT_READY)),
                        none ? "UNKNOWN" : facts.count(HaFacts.NOT_READY) > 0 ? "BAD" : "OK"),
                new Kpi("Ready but stale (older than " + Freshness.STALE_AFTER.toHours() + " h)",
                        Long.toString(facts.count(HaFacts.STALE)), facts.count(HaFacts.STALE) > 0 ? "STALE" : "OK"),
                new Kpi("Unknown (no readiness evidence)", Long.toString(facts.count(HaFacts.UNKNOWN)),
                        facts.count(HaFacts.UNKNOWN) > 0 ? "UNKNOWN" : "OK"),
                new Kpi("Not supported", Long.toString(facts.count(HaFacts.UNSUPPORTED)), "INFO")),
                List.of(new Table("clusters", "Clusters (each cluster counts once)",
                        ReportBuilder.columns("cluster", "Cluster", "vendor", "Vendor", "members", "Members", "verdict",
                                "Readiness", "evaluated", "Evaluated", "age", "Age"),
                        rows, "No enrolled HA cluster.")),
                List.of("STALE: the last result was READY but is older than " + Freshness.STALE_AFTER.toHours()
                        + " h; run a readiness check before relying on it. It is never counted as ready."));
    }

    private static Section reasons(HaFacts facts, PlaneAsOf readiness, Instant now) {
        List<List<Cell>> rows = new ArrayList<>();
        for (HaFacts.Cluster c : facts.clusters) {
            for (ReportData.HaUnit u : c.units()) {
                String verdict = HaFacts.verdict(u.outcome(), u.observedAt(), now);
                if (!HaFacts.NOT_READY.equals(verdict) && !HaFacts.STALE.equals(verdict)
                        && !(HaFacts.UNKNOWN.equals(verdict) && u.observedAt() != null)) continue;
                String unit = u.vsxChassis() ? "VSX chassis" : u.vsId() != null ? "Virtual system " + u.vsId() : "Cluster";
                rows.add(List.of(Cell.cluster(c.clusterRef()), Cell.text(unit), Cell.status(verdict),
                        Cell.text(blankToUnknown(u.failedCheck())), Cell.text(blankToUnknown(u.stopCode())),
                        Cell.time(u.observedAt()), Cell.text(Freshness.age(u.observedAt(), now))));
            }
        }
        return new Section("reasons", "Not ready, stale and unknown results", List.of(readiness), List.of(),
                List.of(new Table("reasons", "Reasons per failover unit",
                        ReportBuilder.columns("cluster", "Cluster", "unit", "Unit", "verdict", "Readiness", "failed_check",
                                "Failed check", "stop_code", "Stop code", "evaluated", "Evaluated", "age", "Age"),
                        rows, "Every evaluated unit is ready and current.")),
                List.of("A VSX chassis and each of its virtual systems are evaluated separately."));
    }

    static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
