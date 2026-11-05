package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Kpi;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;

/**
 * RPT-01 Executive weekly summary (contract "Scope"): an estate verdict (HEALTHY / AT RISK / UNKNOWN) with data
 * freshness; four pillars (security posture, backup coverage, HA readiness, evidence freshness); up to five attention
 * items; the CIS and NIST headline as the passing share of evidenced controls plus the share with no evidence.
 *
 * <p>Verdict rules (fixed, no weighting): a pillar is AT RISK on a known bad fact, UNKNOWN when its evidence is missing
 * or stale, otherwise HEALTHY. The estate is AT RISK when any pillar is; otherwise UNKNOWN when any pillar is;
 * otherwise HEALTHY. Missing or stale evidence therefore never produces HEALTHY.</p>
 */
final class ExecutiveSummaryBuilder implements ReportBuilder {

    static final String HEALTHY = "HEALTHY";
    static final String AT_RISK = "AT RISK";
    static final String UNKNOWN = "UNKNOWN";
    static final int MAX_ATTENTION = 5;

    record Pillar(String name, String verdict, String reason) {
    }

    @Override
    public ReportTemplate template() {
        return ReportTemplate.RPT_01;
    }

    @Override
    public ReportDocument build(Context ctx, ReportData data) {
        Instant now = ctx.now();
        List<DeviceSummaryRecord> devices = data.devices();
        ComplianceFacts compliance = new ComplianceFacts(devices, data.complianceEvaluations());
        BackupFacts backups = new BackupFacts(devices, data.latestBackups(ctx.parameters().windowStart(),
                ctx.parameters().windowEnd()), data.sizeDeviations(ctx.parameters().windowStart(), ctx.parameters().windowEnd()));
        HaFacts ha = new HaFacts(devices, data.haUnits(ctx.actor()), now);
        Map<String, Instant> inventory = data.newestInventory();

        PlaneAsOf compliancePlane = ComplianceCoverageBuilder.compliancePlane(compliance, data.newestConfiguration(), now);
        PlaneAsOf backupPlane = BackupCoverageBuilder.backupPlane(backups, now);
        PlaneAsOf haPlane = HaReadinessBuilder.readinessPlane(ha, now);
        PlaneAsOf inventoryPlane = Freshness.plane("inventory", devices.stream().map(d -> inventory.get(d.deviceId())).toList(),
                devices.size(), now, "devices");
        List<PlaneAsOf> planes = List.of(inventoryPlane, compliancePlane, backupPlane, haPlane);

        List<Pillar> pillars = List.of(
                security(compliance, compliancePlane),
                backup(backups, backupPlane, now),
                haPillar(ha, haPlane),
                evidence(inventoryPlane));
        String verdict = estate(pillars);

        List<List<Cell>> pillarRows = new ArrayList<>();
        for (Pillar p : pillars) pillarRows.add(List.of(Cell.text(p.name()), Cell.status(p.verdict()), Cell.text(p.reason())));
        Section verdictSection = new Section("verdict", "Estate verdict", planes,
                List.of(new Kpi("Estate", verdict, tone(verdict)),
                        new Kpi("Devices with inventory evidence", inventoryPlane.covered() + " of " + inventoryPlane.total(),
                                inventoryPlane.total() == 0 ? "UNKNOWN" : "INFO"),
                        new Kpi("Newest evidence", inventoryPlane.newest() == null ? "UNKNOWN" : inventoryPlane.newest().toString(),
                                inventoryPlane.newest() == null ? "UNKNOWN" : "INFO")),
                List.of(new Table("pillars", "Pillars", ReportBuilder.columns("pillar", "Pillar", "verdict", "Verdict",
                        "reason", "Why"), pillarRows, "No pillar.")),
                List.of("The estate is AT RISK when any pillar is, otherwise UNKNOWN when any pillar is; missing or stale "
                        + "evidence never reads as healthy."));

        Section attention = attention(compliance, backups, ha, inventoryPlane, devices, inventory, now);

        List<List<Cell>> headline = new ArrayList<>();
        for (String key : List.of("CIS", "NIST_800_53")) {
            ComplianceFacts.Counts c = compliance.framework(key);
            headline.add(List.of(Cell.text(ComplianceFacts.FRAMEWORK_NAMES.get(key)), Cell.percent(c.evidencedPassPct()),
                    Cell.percent(c.noEvidencePct()), Cell.number(c.pass()), Cell.number(c.fail()), Cell.number(c.noEvidence())));
        }
        Section complianceSection = new Section("compliance", "Compliance headline", List.of(compliancePlane), List.of(),
                List.of(new Table("compliance_headline", "CIS and NIST",
                        ReportBuilder.columns("framework", "Framework", "evidenced_pass", "Passing share of evidenced controls",
                                "no_evidence_share", "Share with no evidence", "pass", "Pass", "fail", "Fail", "no_evidence",
                                "No evidence"),
                        headline, "No stored compliance evaluation.")),
                List.of("Passing share of evidenced = pass / (pass + fail); checks without evidence are counted apart."));

        return ReportBuilder.document(ctx, planes, List.of(), List.of(verdictSection, attention, complianceSection));
    }

    static String estate(List<Pillar> pillars) {
        if (pillars.stream().anyMatch(p -> AT_RISK.equals(p.verdict()))) return AT_RISK;
        if (pillars.stream().anyMatch(p -> UNKNOWN.equals(p.verdict()))) return UNKNOWN;
        return HEALTHY;
    }

    static String tone(String verdict) {
        return HEALTHY.equals(verdict) ? "OK" : AT_RISK.equals(verdict) ? "BAD" : "UNKNOWN";
    }

    static Pillar security(ComplianceFacts c, PlaneAsOf plane) {
        String name = "Security posture";
        if (c.checks.isEmpty()) return new Pillar(name, UNKNOWN, "No stored compliance evaluation.");
        if (c.criticalFails() > 0) return new Pillar(name, AT_RISK, c.criticalFails() + " critical control check(s) fail.");
        if (plane.anyStale() || plane.covered() < plane.total()) {
            return new Pillar(name, UNKNOWN, (plane.total() - plane.covered()) + " firewall(s) without evaluation, "
                    + plane.stale() + " evaluated on stale configuration evidence.");
        }
        return new Pillar(name, HEALTHY, "No critical control fails on the evaluated firewalls.");
    }

    static Pillar backup(BackupFacts b, PlaneAsOf plane, Instant now) {
        String name = "Backup coverage";
        if (b.targets.isEmpty()) return new Pillar(name, UNKNOWN, "No device is a backup target.");
        int without = b.withoutArchive();
        int stale = b.staleArchives(now);
        int open = b.openSizeAnomalies();
        if (without > 0 || stale > 0 || open > 0) {
            return new Pillar(name, AT_RISK, without + " target(s) without an archive, " + stale + " with a stale archive, "
                    + open + " unresolved size anomaly(ies).");
        }
        return new Pillar(name, HEALTHY, "Every backup target has a current archive.");
    }

    static Pillar haPillar(HaFacts h, PlaneAsOf plane) {
        String name = "HA readiness";
        if (h.supported() == 0) return new Pillar(name, UNKNOWN, "No supported HA cluster is enrolled.");
        long notReady = h.count(HaFacts.NOT_READY);
        if (notReady > 0) return new Pillar(name, AT_RISK, notReady + " cluster(s) not ready.");
        long unknown = h.count(HaFacts.UNKNOWN) + h.count(HaFacts.STALE);
        if (unknown > 0) {
            return new Pillar(name, UNKNOWN, h.count(HaFacts.STALE) + " stale and " + h.count(HaFacts.UNKNOWN)
                    + " unknown readiness result(s).");
        }
        return new Pillar(name, HEALTHY, "Every supported cluster is ready on current evidence.");
    }

    static Pillar evidence(PlaneAsOf inventory) {
        String name = "Evidence freshness";
        if (inventory.total() == 0) return new Pillar(name, UNKNOWN, "No device is registered.");
        int never = inventory.total() - inventory.covered();
        if (never > 0 || inventory.anyStale()) {
            return new Pillar(name, UNKNOWN, never + " device(s) never inventoried, " + inventory.stale()
                    + " with inventory older than " + Freshness.STALE_AFTER.toHours() + " h.");
        }
        return new Pillar(name, HEALTHY, "Every device has inventory evidence from the last "
                + Freshness.STALE_AFTER.toHours() + " h.");
    }

    private static Section attention(ComplianceFacts compliance, BackupFacts backups, HaFacts ha, PlaneAsOf inventoryPlane,
            List<DeviceSummaryRecord> devices, Map<String, Instant> inventory, Instant now) {
        List<List<Cell>> rows = new ArrayList<>();
        for (HaFacts.Cluster c : ha.clusters) {
            if (HaFacts.NOT_READY.equals(c.verdict())) {
                rows.add(List.of(Cell.text("HA readiness"), Cell.cluster(c.clusterRef()), Cell.text("Not ready: "
                        + HaReadinessBuilder.blankToUnknown(c.base() == null ? null : c.base().failedCheck()))));
            }
        }
        for (BackupFacts.Target t : backups.targets) {
            if (!t.hasArchive()) rows.add(List.of(Cell.text("Backup"), ReportBuilder.device(t.device(), t.device().deviceId()),
                    Cell.text("Backup target without an archive")));
        }
        Map<String, Integer> criticalByDevice = new LinkedHashMap<>();
        for (ComplianceFacts.Check check : compliance.checks) {
            if (check.outcome() == ComplianceFacts.Outcome.FAIL && "CRITICAL".equals(check.severity())) {
                criticalByDevice.merge(check.deviceId(), 1, Integer::sum);
            }
        }
        criticalByDevice.forEach((id, n) -> rows.add(List.of(Cell.text("Security posture"),
                ReportBuilder.device(compliance.firewallById.get(id), id), Cell.text(n + " critical control check(s) fail"))));
        for (BackupFacts.Alert a : backups.alerts) {
            if ("size_anomaly".equals(a.row().deviationClass()) && a.open()) {
                rows.add(List.of(Cell.text("Backup"), ReportBuilder.device(a.device(), a.row().deviceId()),
                        Cell.text("Unresolved backup size anomaly")));
            }
        }
        for (HaFacts.Cluster c : ha.clusters) {
            if (HaFacts.STALE.equals(c.verdict())) {
                rows.add(List.of(Cell.text("HA readiness"), Cell.cluster(c.clusterRef()),
                        Cell.text("Ready result is stale (" + Freshness.age(c.base().observedAt(), now) + ")")));
            }
        }
        for (DeviceSummaryRecord d : devices) {
            Instant at = inventory.get(d.deviceId());
            if (at == null) rows.add(List.of(Cell.text("Evidence"), ReportBuilder.device(d, d.deviceId()),
                    Cell.text("Never inventoried")));
        }
        int total = rows.size();
        List<List<Cell>> top = rows.subList(0, Math.min(MAX_ATTENTION, total));
        return new Section("attention", "Attention items", List.of(inventoryPlane), List.of(),
                List.of(new Table("attention", "Up to five items, most severe first",
                        ReportBuilder.columns("area", "Area", "item", "Item", "why", "Why"), top, "Nothing needs attention.")),
                total > MAX_ATTENTION ? List.of(total + " items in all; the first " + MAX_ATTENTION + " are shown.") : List.of());
    }
}
