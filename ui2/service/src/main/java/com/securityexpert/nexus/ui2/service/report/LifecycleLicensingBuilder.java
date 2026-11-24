package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Kpi;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;

/**
 * RPT-17 Lifecycle and licensing ({@code docs/design/REPORTS_RPT17_LIFECYCLE_ADDENDUM_2026_11_24.md}; PO decision
 * 2026-11-24): stored licences, subscriptions and support contracts expired or expiring within 30 / 90 days; devices
 * matching actively exploited (CISA KEV) or critical/high advisories; stored Live Patch and hotfix currency; and
 * lifecycle read coverage with the age of the newest read. No device is contacted.
 */
final class LifecycleLicensingBuilder implements ReportBuilder {

    @Override
    public ReportTemplate template() {
        return ReportTemplate.RPT_17;
    }

    @Override
    public ReportDocument build(Context ctx, ReportData data) {
        Instant now = ctx.now();
        LocalDate today = now.atZone(com.securityexpert.nexus.ui2.platform.JobWindowPolicy.ZONE_ID).toLocalDate();
        var devices = data.devices();
        LifecycleFacts facts = new LifecycleFacts(devices, data.lifecycleSnapshot(today, now), data.newestInventory(), now);
        PlaneAsOf lifecycle = lifecyclePlane(facts, now);
        PlaneAsOf inventory = inventoryPlane(facts, now);
        List<PlaneAsOf> planes = List.of(lifecycle, inventory);
        List<String> notices = devices.isEmpty()
                ? List.of("NO EVIDENCE: no device is registered, so lifecycle and licensing posture is UNKNOWN.")
                : List.of();
        return ReportBuilder.document(ctx, planes, notices, List.of(
                licensingSection(facts, lifecycle),
                advisoriesSection(facts, inventory),
                livePatchHotfixSection(facts, lifecycle, inventory),
                coverageSection(facts, lifecycle, now)));
    }

    static PlaneAsOf lifecyclePlane(LifecycleFacts facts, Instant now) {
        return Freshness.plane("lifecycle", facts.lifecycleEvidenceTimes, facts.sortedDevices.size(), now, "devices");
    }

    static PlaneAsOf inventoryPlane(LifecycleFacts facts, Instant now) {
        return Freshness.plane("inventory", facts.inventoryEvidenceTimes, facts.sortedDevices.size(), now, "devices");
    }

    static String planeNote(PlaneAsOf p) {
        return "Data age and coverage (" + p.plane() + "): " + p.covered() + " of " + p.total() + " " + p.unit()
                + " with evidence; newest " + (p.newest() == null ? "UNKNOWN" : p.newest().toString())
                + "; oldest " + (p.oldest() == null ? "UNKNOWN" : p.oldest().toString())
                + "; stale (older than " + Freshness.STALE_AFTER.toHours() + " h) " + p.stale() + ".";
    }

    private static Section licensingSection(LifecycleFacts facts, PlaneAsOf lifecycle) {
        int total = facts.sortedDevices.size();
        int covered = facts.licenseCoveredDevices;
        int unknownDevices = total - covered;
        boolean partial = covered < total || total == 0;
        List<List<Cell>> rows = new ArrayList<>();
        for (LifecycleFacts.LicenseFinding f : facts.licenseFindings) {
            rows.add(List.of(
                    ReportBuilder.device(f.device(), f.device().deviceId()),
                    Cell.text(ReportBuilder.vendorLabel(f.device().vendorHint())),
                    Cell.text(f.category()),
                    Cell.text(f.item()),
                    Cell.status(f.state()),
                    Cell.text(f.expiry() == null ? "UNKNOWN" : f.expiry()),
                    f.daysRemaining() == null ? Cell.text("UNKNOWN") : Cell.number(f.daysRemaining()),
                    Cell.time(f.observedAt())));
        }
        return new Section("licensing", "Licences, subscriptions and support contracts", List.of(lifecycle), List.of(
                new Kpi("Devices with license evidence", covered + " of " + total,
                        total == 0 || covered < total ? "UNKNOWN" : "OK"),
                new Kpi("Expired", Integer.toString(facts.expiredItems),
                        partial && facts.expiredItems == 0 ? "UNKNOWN" : facts.expiredItems > 0 ? "BAD" : "OK"),
                new Kpi("Expiring within 30 days", Integer.toString(facts.expiringWithin30Days),
                        partial && facts.expiringWithin30Days == 0 ? "UNKNOWN" : facts.expiringWithin30Days > 0 ? "WARN" : "OK"),
                new Kpi("Expiring within 90 days", Integer.toString(facts.expiringWithin90Days),
                        partial && facts.expiringWithin90Days == 0 ? "UNKNOWN" : facts.expiringWithin90Days > 0 ? "WARN" : "OK"),
                new Kpi("Unknown or not collected (devices)", Integer.toString(unknownDevices),
                        total == 0 || unknownDevices > 0 ? "UNKNOWN" : "OK")),
                List.of(new Table("licensing_items", "Expired and expiring items",
                        ReportBuilder.columns("device", "Device", "vendor", "Vendor", "category", "Category",
                                "item", "Item", "state", "State", "expires", "Expires",
                                "days_remaining", "Days remaining", "observed", "Observed"),
                        rows, "No expired, expiring (within 90 days) or unknown item in stored evidence.")),
                List.of(planeNote(lifecycle),
                        "Expiring within 90 days (0 to 90 days) includes items expiring within 30 days (0 to 30 days); "
                                + "in the table, 0 to 30 days is WITHIN 30 DAYS and 31 to 90 days is WITHIN 90 DAYS. "
                                + "Earlier expired support contracts on a device that holds a current contract are historical "
                                + "and omitted; devices with no collected licence read are shown as UNKNOWN, never as valid."));
    }

    private static Section advisoriesSection(LifecycleFacts facts, PlaneAsOf inventory) {
        int total = facts.sortedDevices.size();
        int evaluated = facts.evaluatedDevices;
        int unknownDevices = facts.advisoryUnknown.size();
        Long ageDays = facts.oldestSourceAgeDays;
        String sourceAgeValue = ageDays == null ? "UNKNOWN" : ageDays + " d";
        String sourceAgeTone = ageDays == null ? "UNKNOWN" : ageDays > 30 ? "BAD" : ageDays > 7 ? "WARN" : "INFO";

        List<List<Cell>> affectedRows = new ArrayList<>();
        for (LifecycleFacts.AdvisoryAffectedRow r : facts.advisoryAffected) {
            affectedRows.add(List.of(
                    ReportBuilder.device(r.device(), r.device().deviceId()),
                    Cell.text(ReportBuilder.vendorLabel(r.device().vendorHint())),
                    Cell.text(LifecycleFacts.blankToUnknown(r.comparedVersion())),
                    Cell.status(r.state()),
                    Cell.number(r.exploited()),
                    Cell.number(r.critical()),
                    Cell.number(r.high()),
                    Cell.number(r.criticalHigh()),
                    Cell.time(r.sourceLoadedAt())));
        }

        List<List<Cell>> unknownRows = new ArrayList<>();
        for (LifecycleFacts.AdvisoryUnknownRow r : facts.advisoryUnknown) {
            unknownRows.add(List.of(
                    ReportBuilder.device(r.device(), r.device().deviceId()),
                    Cell.text(ReportBuilder.vendorLabel(r.device().vendorHint())),
                    Cell.text(LifecycleFacts.blankToUnknown(r.storedVersion())),
                    Cell.status(r.state()),
                    Cell.text(LifecycleFacts.blankToUnknown(r.reason()))));
        }

        String sourceLoadNote = "Advisory sources: newest load "
                + (facts.newestSourceLoadedAt == null ? "UNKNOWN" : facts.newestSourceLoadedAt.toString())
                + "; oldest load "
                + (facts.oldestSourceLoadedAt == null ? "UNKNOWN" : facts.oldestSourceLoadedAt.toString()) + ".";

        return new Section("advisories", "Advisory exposure (CISA KEV and critical/high)", List.of(inventory), List.of(
                new Kpi("Devices evaluated", evaluated + " of " + total,
                        total == 0 || evaluated < total ? "UNKNOWN" : "OK"),
                new Kpi("Affected by CISA KEV (exploited)", Integer.toString(facts.exploitedDevices),
                        evaluated < total && facts.exploitedDevices == 0 || evaluated == 0 ? "UNKNOWN" : facts.exploitedDevices > 0 ? "BAD" : "OK"),
                new Kpi("Affected by critical/high advisories", Integer.toString(facts.criticalHighDevices),
                        evaluated < total && facts.criticalHighDevices == 0 || evaluated == 0 ? "UNKNOWN" : facts.criticalHighDevices > 0 ? "WARN" : "OK"),
                new Kpi("Unknown or not evaluated (devices)", Integer.toString(unknownDevices),
                        total == 0 || unknownDevices > 0 ? "UNKNOWN" : "OK"),
                new Kpi("Oldest advisory source age", sourceAgeValue, sourceAgeTone)),
                List.of(new Table("advisory_affected", "Devices matching advisories",
                                ReportBuilder.columns("device", "Device", "vendor", "Vendor", "version", "Compared version",
                                        "state", "Status", "exploited", "CISA KEV", "critical", "Critical", "high", "High",
                                        "critical_high", "Critical + High", "source_loaded", "Source loaded"),
                                affectedRows, "No evaluated device matches an actively exploited (CISA KEV) or critical/high advisory."),
                        new Table("advisory_unknown", "Devices not evaluated",
                                ReportBuilder.columns("device", "Device", "vendor", "Vendor", "version", "Stored version",
                                        "state", "State", "reason", "Reason"),
                                unknownRows, "Every registered device was evaluated against a current advisory source.")),
                List.of(planeNote(inventory), sourceLoadNote,
                        "Affected by version; exposure depends on configuration. Exploited means the advisory's CVE is in "
                                + "the CISA Known Exploited Vulnerabilities (KEV) catalog. Devices in NOT_EVALUATED, "
                                + "VERSION_UNKNOWN or NOT_COVERED are listed apart and never counted as unaffected."));
    }

    private static Section livePatchHotfixSection(LifecycleFacts facts, PlaneAsOf lifecycle, PlaneAsOf inventory) {
        int total = facts.sortedDevices.size();
        int withoutStored = total - facts.storedHotfixOrPatch;
        String notActiveTone = facts.cpTotal == 0 ? "INFO"
                : facts.cpCollected < facts.cpTotal && facts.livePatchNotActive == 0 ? "UNKNOWN"
                : facts.livePatchNotActive > 0 ? "WARN" : "OK";
        String missingTone = facts.cpTotal == 0 ? "INFO"
                : facts.cpCollected < facts.cpTotal && facts.livePatchMissing == 0 ? "UNKNOWN"
                : facts.livePatchMissing > 0 ? "BAD" : "OK";

        List<List<Cell>> rows = new ArrayList<>();
        for (LifecycleFacts.HotfixRow r : facts.hotfixRows) {
            rows.add(List.of(
                    ReportBuilder.device(r.device(), r.device().deviceId()),
                    Cell.text(ReportBuilder.vendorLabel(r.device().vendorHint())),
                    Cell.text(LifecycleFacts.blankToUnknown(r.softwareVersion())),
                    Cell.text(LifecycleFacts.blankToUnknown(r.hotfix())),
                    Cell.text(r.livePatchSummary()),
                    Cell.status(r.state()),
                    Cell.time(r.observedAt())));
        }

        return new Section("live_patch_hotfix", "Live Patch and hotfix currency", List.of(lifecycle, inventory), List.of(
                new Kpi("Devices with stored hotfix or Live Patch", facts.storedHotfixOrPatch + " of " + total,
                        total == 0 ? "UNKNOWN" : "INFO"),
                new Kpi("Check Point Live Patch not active", Integer.toString(facts.livePatchNotActive), notActiveTone),
                new Kpi("Check Point Live Patch package missing", Integer.toString(facts.livePatchMissing), missingTone),
                new Kpi("Without stored hotfix or Live Patch", Integer.toString(withoutStored),
                        total == 0 || facts.cpCollected < facts.cpTotal ? "UNKNOWN" : "INFO")),
                List.of(new Table("live_patch_hotfix", "Live Patch and hotfix levels",
                        ReportBuilder.columns("device", "Device", "vendor", "Vendor", "version", "Software version",
                                "hotfix", "Hotfix / Jumbo", "live_patch", "Live Patch", "state", "State",
                                "observed", "Observed"),
                        rows, "No Check Point device or stored hotfix fact in the estate.")),
                List.of(planeNote(lifecycle), planeNote(inventory),
                        "Check Point gateways report Jumbo Hotfix takes and Live Patch state (a ready patch whose target "
                                + "process is not running is idle, not pending). Other vendors appear when a separate "
                                + "hotfix_level fact is stored; maintenance levels carried inside software_version are "
                                + "not duplicated as hotfix rows."));
    }

    private static Section coverageSection(LifecycleFacts facts, PlaneAsOf lifecycle, Instant now) {
        int total = facts.sortedDevices.size();
        String newestAge = Freshness.age(lifecycle.newest(), now);
        String newestTone = lifecycle.newest() == null ? "UNKNOWN"
                : Freshness.stale(lifecycle.newest(), now) ? "STALE" : "OK";

        List<List<Cell>> missingRows = new ArrayList<>();
        for (LifecycleFacts.MissingReadRow r : facts.missingReads) {
            missingRows.add(List.of(
                    ReportBuilder.device(r.device(), r.device().deviceId()),
                    Cell.text(ReportBuilder.vendorLabel(r.device().vendorHint())),
                    Cell.text(LifecycleFacts.blankToUnknown(r.device().role())),
                    Cell.status(r.state()),
                    Cell.text(r.detail()),
                    Cell.time(r.lastAttemptAt()),
                    Cell.text(Freshness.age(r.lastAttemptAt(), now))));
        }

        List<List<Cell>> coveredRows = new ArrayList<>();
        for (LifecycleFacts.CoveredReadRow r : facts.coveredReads) {
            coveredRows.add(List.of(
                    ReportBuilder.device(r.device(), r.device().deviceId()),
                    Cell.text(ReportBuilder.vendorLabel(r.device().vendorHint())),
                    Cell.status(r.state()),
                    Cell.text(r.kinds()),
                    Cell.time(r.newestReadAt()),
                    Cell.text(Freshness.age(r.newestReadAt(), now))));
        }

        return new Section("coverage", "Lifecycle read coverage and freshness", List.of(lifecycle), List.of(
                new Kpi("Devices with collected lifecycle read", lifecycle.covered() + " of " + lifecycle.total(),
                        total == 0 || lifecycle.covered() < total || lifecycle.anyStale() ? "UNKNOWN" : "OK"),
                new Kpi("Devices with no lifecycle read", Integer.toString(facts.noReadDevices),
                        total == 0 ? "UNKNOWN" : facts.noReadDevices > 0 ? "BAD" : "OK"),
                new Kpi("Devices with failed or unparsed read", Integer.toString(facts.failedReadDevices),
                        total == 0 ? "UNKNOWN" : facts.failedReadDevices > 0 ? "UNKNOWN" : "OK"),
                new Kpi("Newest lifecycle read age", newestAge, newestTone),
                new Kpi("Stale lifecycle reads (older than " + Freshness.STALE_AFTER.toHours() + " h)",
                        Integer.toString(lifecycle.stale()),
                        total == 0 ? "UNKNOWN" : lifecycle.anyStale() ? "STALE" : "OK")),
                List.of(new Table("no_lifecycle_read", "Devices with no lifecycle read",
                                ReportBuilder.columns("device", "Device", "vendor", "Vendor", "role", "Role",
                                        "state", "State", "detail", "Read outcome", "last_attempt", "Last attempt",
                                        "age", "Age"),
                                missingRows, "Every registered device has at least one collected lifecycle read."),
                        new Table("lifecycle_read_freshness", "Newest lifecycle reads",
                                ReportBuilder.columns("device", "Device", "vendor", "Vendor", "state", "Freshness",
                                        "kinds", "Collected reads", "newest_read", "Newest read", "age", "Age"),
                                coveredRows, "No device has a collected lifecycle read.")),
                List.of(planeNote(lifecycle),
                        "NO READ marks devices with no recorded lifecycle read attempt; UNKNOWN marks devices whose "
                                + "recorded lifecycle reads failed, were unparsed or returned no collected items."));
    }
}
