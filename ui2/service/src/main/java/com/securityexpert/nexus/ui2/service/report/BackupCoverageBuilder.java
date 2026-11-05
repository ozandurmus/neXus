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
 * RPT-03 Backup coverage and anomalies (contract "Scope"): backup targets with and without an archive, the last
 * archive's age (stale labelled stale), and the size alerts of the window -- own history ({@code size_anomaly}),
 * fleet-large ({@code fleet_large}) and firewall logs inside the backup ({@code firewall_logs}).
 */
final class BackupCoverageBuilder implements ReportBuilder {

    @Override
    public ReportTemplate template() {
        return ReportTemplate.RPT_03;
    }

    @Override
    public ReportDocument build(Context ctx, ReportData data) {
        Instant from = ctx.parameters().windowStart();
        Instant to = ctx.parameters().windowEnd();
        var devices = data.devices();
        BackupFacts facts = new BackupFacts(devices, data.latestBackups(from, to), data.sizeDeviations(from, to));
        PlaneAsOf backup = backupPlane(facts, ctx.now());
        return ReportBuilder.document(ctx, List.of(backup), facts.targets.isEmpty()
                ? List.of("NO EVIDENCE: no device is a backup target, so backup coverage is UNKNOWN.") : List.of(),
                List.of(coverage(facts, backup, ctx.now()), alerts(facts, backup)));
    }

    static PlaneAsOf backupPlane(BackupFacts facts, Instant now) {
        return Freshness.plane("backup", facts.targets.stream().map(t -> t.hasArchive() ? t.latest().createdAt() : null).toList(),
                facts.targets.size(), now, "backup targets");
    }

    private static Section coverage(BackupFacts facts, PlaneAsOf backup, Instant now) {
        int targets = facts.targets.size();
        int without = facts.withoutArchive();
        int stale = facts.staleArchives(now);
        List<List<Cell>> rows = new ArrayList<>();
        for (BackupFacts.Target t : facts.targets) {
            boolean has = t.hasArchive();
            String state = !has ? "NO ARCHIVE" : Freshness.stale(t.latest().createdAt(), now) ? "STALE" : "CURRENT";
            rows.add(List.of(ReportBuilder.device(t.device(), t.device().deviceId()),
                    Cell.text(ReportBuilder.vendorLabel(t.device().vendorHint())), Cell.status(state),
                    has ? Cell.time(t.latest().createdAt()) : Cell.text("NONE"),
                    Cell.text(has ? Freshness.age(t.latest().createdAt(), now) : "UNKNOWN"),
                    has ? Cell.text(BackupFacts.bytes(t.latest().plaintextBytes())) : Cell.text("UNKNOWN"),
                    Cell.number(has ? t.latest().backupsInWindow() : 0)));
        }
        return new Section("coverage", "Backup coverage", List.of(backup), List.of(
                new Kpi("Backup targets", Integer.toString(targets), targets == 0 ? "UNKNOWN" : "INFO"),
                new Kpi("With an archive", Integer.toString(targets - without), targets == 0 ? "UNKNOWN" : "OK"),
                new Kpi("Without an archive", Integer.toString(without), targets == 0 ? "UNKNOWN" : without > 0 ? "BAD" : "OK"),
                new Kpi("Last archive older than " + Freshness.STALE_AFTER.toHours() + " h", Integer.toString(stale),
                        targets == 0 ? "UNKNOWN" : stale > 0 ? "STALE" : "OK")),
                List.of(new Table("targets", "Backup targets",
                        ReportBuilder.columns("device", "Device", "vendor", "Vendor", "state", "State", "last_archive",
                                "Last archive", "age", "Age", "size", "Size", "in_window", "Archives in range"),
                        rows, "No device is a backup target.")),
                List.of("A target without an archive and a stale archive are listed as such; neither counts as covered."));
    }

    private static Section alerts(BackupFacts facts, PlaneAsOf backup) {
        List<List<Cell>> rows = new ArrayList<>();
        for (BackupFacts.Alert a : facts.alerts) {
            rows.add(List.of(Cell.status(label(a.row().deviationClass())), ReportBuilder.device(a.device(), a.row().deviceId()),
                    Cell.time(a.row().detectedAt()), Cell.status(a.open() ? "OPEN" : "RESOLVED"), Cell.text(a.detail())));
        }
        return new Section("anomalies", "Backup size anomalies", List.of(backup), List.of(
                new Kpi("Own-history size changes", Long.toString(facts.count("size_anomaly")), "INFO"),
                new Kpi("Unresolved size anomalies", Integer.toString(facts.openSizeAnomalies()),
                        facts.openSizeAnomalies() > 0 ? "WARN" : "OK"),
                new Kpi("Fleet-large backups", Long.toString(facts.count("fleet_large")), "INFO"),
                new Kpi("Backups carrying firewall logs", Long.toString(facts.count("firewall_logs")), "INFO")),
                List.of(new Table("size_alerts", "Size alerts in range (and size anomalies still unresolved)",
                        ReportBuilder.columns("class", "Kind", "device", "Device", "detected", "Detected", "state", "State",
                                "detail", "Detail"),
                        rows, "No size alert in range.")),
                List.of("Own history: grew at least 5x or shrank at least 50% against the device's own recent backups. "
                        + "Fleet-large: at least 10x the vendor's fleet median and at least 1 GiB. Firewall logs: at least "
                        + "100 MiB of Check Point firewall log files inside the backup (sizes only, never file names)."));
    }

    static String label(String deviationClass) {
        return switch (deviationClass) {
            case "size_anomaly" -> "OWN HISTORY";
            case "fleet_large" -> "FLEET LARGE";
            case "firewall_logs" -> "FIREWALL LOGS";
            default -> "UNKNOWN";
        };
    }
}
