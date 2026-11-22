package com.securityexpert.nexus.ui2.service.report;

import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.NOW;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.context;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.kpis;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.BackupRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.DeviationRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;

/**
 * RPT-03 backup coverage (contract "Tests"): synthetic devices only. UNKNOWN must never read as a clean pass, a stale
 * archive keeps its own evidence time, and the size-alert counts match the facts they count.
 */
class BackupCoverageBuilderTest {

    private static final String ALPHA = "FW-ALPHA-01";
    private static final String BRAVO = "FW-BRAVO-02";
    private static final String CHARLIE = "FW-CHARLIE-03";
    private static final Instant FRESH_AT = NOW.minus(Duration.ofHours(3));
    private static final Instant STALE_AT = NOW.minus(Duration.ofHours(30));

    private static DeviceSummaryRecord device(String id, String host, boolean backupTarget) {
        return new DeviceSummaryRecord(id, "firewall", "check_point", DeviceEnrollmentState.ENROLLED,
                Optional.of(host), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("192.0.2.10"), backupTarget);
    }

    private static BackupRow backup(String id, Instant at) {
        return new BackupRow(id, "art-" + id, at, 50L * 1024 * 1024, "check_point", 2);
    }

    private static ReportFixtures.Data data(List<DeviceSummaryRecord> devices, List<BackupRow> backups,
            List<DeviationRow> deviations) {
        ReportFixtures.Data d = new ReportFixtures.Data();
        d.devices = new ArrayList<>(devices);
        d.backups = new ArrayList<>(backups);
        d.deviations = new ArrayList<>(deviations);
        return d;
    }

    private static ReportDocument build(ReportFixtures.Data d) {
        return new BackupCoverageBuilder().build(context(ReportTemplate.RPT_03), d);
    }

    private static PlaneAsOf plane(ReportDocument doc) {
        return doc.dataAsOf().stream().filter(p -> p.plane().equals("backup")).findFirst().orElseThrow();
    }

    private static String tone(ReportDocument doc, String sectionId, String label) {
        return doc.sections().stream().filter(s -> s.id().equals(sectionId)).findFirst().orElseThrow()
                .kpis().stream().filter(k -> k.label().equals(label)).findFirst().orElseThrow().tone();
    }

    private static DeviationRow alert(String id, String deviceId, String deviationClass, Instant resolvedAt, String details) {
        return new DeviationRow(id, deviceId, "art-" + deviceId, deviationClass, NOW.minus(Duration.ofDays(2)),
                resolvedAt, details);
    }

    // ---- backupPlane: UNKNOWN is never a clean pass ---------------------------------------------------------------

    @Test
    void planeWithoutAnyBackupTargetIsCoveredZeroOfZeroWithNoEvidenceTime() {
        PlaneAsOf p = BackupCoverageBuilder.backupPlane(new BackupFacts(List.of(), List.of(), List.of()), NOW);
        assertEquals("backup", p.plane());
        assertEquals(0, p.covered());
        assertEquals(0, p.total());
        assertNull(p.newest());
        assertNull(p.oldest());
        assertEquals(0, p.stale());
    }

    @Test
    void targetWithoutArchiveContributesNoEvidenceTimeAndIsNotCovered() {
        List<DeviceSummaryRecord> devices = List.of(device("dev-a", ALPHA, true), device("dev-c", CHARLIE, true));
        BackupFacts facts = new BackupFacts(devices, List.of(backup("dev-a", FRESH_AT)), List.of());
        PlaneAsOf p = BackupCoverageBuilder.backupPlane(facts, NOW);
        assertEquals(1, p.covered());
        assertEquals(2, p.total());
        assertEquals(FRESH_AT, p.newest());
        assertEquals(0, p.stale());
    }

    @Test
    void staleArchiveCarriesItsOwnEvidenceTime() {
        List<DeviceSummaryRecord> devices = List.of(device("dev-a", ALPHA, true), device("dev-b", BRAVO, true));
        BackupFacts facts = new BackupFacts(devices,
                List.of(backup("dev-a", FRESH_AT), backup("dev-b", STALE_AT)), List.of());
        PlaneAsOf p = BackupCoverageBuilder.backupPlane(facts, NOW);
        assertEquals(1, p.stale());
        assertEquals(FRESH_AT, p.newest());
        assertEquals(STALE_AT, p.oldest());
    }

    @Test
    void buildWithNoBackupTargetShowsUnknownNotOkAndAddsNoEvidenceNotice() {
        ReportDocument doc = build(data(List.of(device("dev-e", ALPHA, false)), List.of(), List.of()));
        assertTrue(doc.notices().stream().anyMatch(n -> n.startsWith("NO EVIDENCE")));
        assertEquals("UNKNOWN", tone(doc, "coverage", "Backup targets"));
        assertEquals("UNKNOWN", tone(doc, "coverage", "With an archive"));
        assertEquals("UNKNOWN", tone(doc, "coverage", "Without an archive"));
        assertEquals("UNKNOWN", tone(doc, "coverage", "Last archive older than 24 h"));
        assertEquals("0", kpis(doc, "coverage").get("Backup targets"));
    }

    // ---- coverage: counts match the facts they count ---------------------------------------------------------------

    @Test
    void coverageCountsTargetsArchivesMissingAndStaleAndLabelsEachRow() {
        List<DeviceSummaryRecord> devices = List.of(device("dev-a", ALPHA, true), device("dev-b", BRAVO, true),
                device("dev-c", CHARLIE, true), device("dev-e", "FW-DELTA-04", false));
        ReportDocument doc = build(data(devices,
                List.of(backup("dev-a", FRESH_AT), backup("dev-b", STALE_AT)), List.of()));

        assertEquals("3", kpis(doc, "coverage").get("Backup targets"));
        assertEquals("2", kpis(doc, "coverage").get("With an archive"));
        assertEquals("1", kpis(doc, "coverage").get("Without an archive"));
        assertEquals("1", kpis(doc, "coverage").get("Last archive older than 24 h"));
        assertEquals("BAD", tone(doc, "coverage", "Without an archive"));
        assertEquals("STALE", tone(doc, "coverage", "Last archive older than 24 h"));

        // Rows sort by observed hostname; the non-target device is not listed.
        List<List<String>> rows = rows(doc, "targets");
        assertEquals(3, rows.size());
        assertEquals(List.of("CURRENT", "3 h"), List.of(rows.get(0).get(2), rows.get(0).get(4)));
        assertEquals(List.of("STALE", "30 h"), List.of(rows.get(1).get(2), rows.get(1).get(4)));
        assertEquals(List.of("NO ARCHIVE", "UNKNOWN"), List.of(rows.get(2).get(2), rows.get(2).get(4)));
    }

    @Test
    void coverageWithAllTargetsCoveredAndFreshReportsOkAndZeroStale() {
        ReportDocument doc = build(data(List.of(device("dev-a", ALPHA, true)),
                List.of(backup("dev-a", FRESH_AT)), List.of()));
        assertEquals("OK", tone(doc, "coverage", "Without an archive"));
        assertEquals("OK", tone(doc, "coverage", "Last archive older than 24 h"));
        assertEquals("0", kpis(doc, "coverage").get("Without an archive"));
        assertEquals("0", kpis(doc, "coverage").get("Last archive older than 24 h"));
    }

    // ---- alerts: counts and labels --------------------------------------------------------------------------------

    @Test
    void alertsCountEachClassAndOnlyUnresolvedSizeAnomaliesAsOpen() {
        List<DeviationRow> deviations = List.of(
                alert("dv-1", "dev-b", "size_anomaly", null,
                        "{\"direction\":\"growth\",\"plaintext_bytes\":3221225472,\"median_bytes\":52428800,\"ratio\":61.4}"),
                alert("dv-2", "dev-b", "size_anomaly", NOW.minus(Duration.ofDays(1)),
                        "{\"direction\":\"shrink\",\"plaintext_bytes\":1048576,\"median_bytes\":52428800,\"ratio\":0.02}"),
                alert("dv-3", "dev-b", "fleet_large", null,
                        "{\"plaintext_bytes\":3221225472,\"median_bytes\":52428800,\"ratio\":61.4}"),
                alert("dv-4", "dev-b", "firewall_logs", null,
                        "{\"log_bytes\":2147483648,\"total_bytes\":3221225472,\"percent\":66.7}"),
                alert("dv-5", "dev-b", "unknown_future_class", null, "{}"));
        ReportDocument doc = build(data(List.of(device("dev-b", BRAVO, true)),
                List.of(backup("dev-b", FRESH_AT)), deviations));

        var k = kpis(doc, "anomalies");
        assertEquals("2", k.get("Own-history size changes"));
        assertEquals("1", k.get("Unresolved size anomalies"));
        assertEquals("1", k.get("Fleet-large backups"));
        assertEquals("1", k.get("Backups carrying firewall logs"));
        assertEquals("WARN", tone(doc, "anomalies", "Unresolved size anomalies"));

        // The unknown class is not an alert row; the resolved anomaly is listed as RESOLVED.
        List<List<String>> rows = rows(doc, "size_alerts");
        assertEquals(4, rows.size());
        assertEquals(List.of("OWN HISTORY", "OPEN"), List.of(rows.get(0).get(0), rows.get(0).get(3)));
        assertEquals(List.of("OWN HISTORY", "RESOLVED"), List.of(rows.get(1).get(0), rows.get(1).get(3)));
        assertEquals("FLEET LARGE", rows.get(2).get(0));
        assertEquals("FIREWALL LOGS", rows.get(3).get(0));
    }

    @Test
    void noOpenSizeAnomalyIsOkNotWarn() {
        ReportDocument doc = build(data(List.of(device("dev-b", BRAVO, true)),
                List.of(backup("dev-b", FRESH_AT)),
                List.of(alert("dv-1", "dev-b", "size_anomaly", NOW.minus(Duration.ofDays(1)), "{}"))));
        assertEquals("OK", tone(doc, "anomalies", "Unresolved size anomalies"));
        assertEquals("0", kpis(doc, "anomalies").get("Unresolved size anomalies"));
    }

    // ---- label -------------------------------------------------------------------------------------------------

    @Test
    void labelMapsKnownDeviationClassesAndFallsBackToUnknownForAnyOtherClass() {
        assertEquals("OWN HISTORY", BackupCoverageBuilder.label("size_anomaly"));
        assertEquals("FLEET LARGE", BackupCoverageBuilder.label("fleet_large"));
        assertEquals("FIREWALL LOGS", BackupCoverageBuilder.label("firewall_logs"));
        assertEquals("UNKNOWN", BackupCoverageBuilder.label("unknown_future_class"));
        assertEquals("UNKNOWN", BackupCoverageBuilder.label(""));
    }

    // ---- detail: sizes only, safe fallback --------------------------------------------------------------------

    @Test
    void detailRendersSizesAndRatiosAndFallsBackToUnknownWithoutReadableJson() {
        DeviationRow sizeRow = alert("dv-1", "dev-b", "size_anomaly", null,
                "{\"direction\":\"growth\",\"plaintext_bytes\":3221225472,\"median_bytes\":52428800,\"ratio\":61.4}");
        assertEquals("growth: 3.0 GiB against a median of 50.0 MiB (ratio 61.4)", BackupFacts.detail(sizeRow));
        assertEquals("UNKNOWN", BackupFacts.detail(alert("dv-2", "dev-b", "size_anomaly", null, null)));
        assertEquals("UNKNOWN", BackupFacts.detail(alert("dv-3", "dev-b", "size_anomaly", null, "not-json")));
    }
}
