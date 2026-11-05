package com.securityexpert.nexus.ui2.service.report;

import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.CLUSTER_1;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.CLUSTER_2;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.FRESH;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.HOST_A;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.HOST_B;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.HOST_C;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.STALE;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.context;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.kpis;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.ComplianceRow;
import com.securityexpert.nexus.ui2.service.report.ReportData.HaUnit;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;

/** Reports R1 contract "Tests": each builder against synthetic data, including UNKNOWN and stale cases. */
class ReportBuildersTest {

    private static PlaneAsOf plane(ReportDocument doc, String name) {
        return doc.dataAsOf().stream().filter(p -> p.plane().equals(name)).findFirst().orElseThrow();
    }

    // ---- RPT-02 -------------------------------------------------------------------------------------------------

    @Test
    void rpt02CountsPassFailAndNoEvidenceApartAndSkipsNotApplicableAndManagementServers() {
        ReportDocument doc = new ComplianceCoverageBuilder().build(context(ReportTemplate.RPT_02), new ReportFixtures.Data());
        // CIS: CP-1 FAIL on A, PASS on B; CP-4 is NOT_APPLICABLE; the management server's CP-9 is not counted.
        // NIST: CP-2 PASS on A, FAIL on B; CP-3 no evidence on A.
        assertEquals(List.of(
                List.of("CIS Benchmark", "2", "1", "1", "0", "50.0%", "0.0%"),
                List.of("NIST SP 800-53", "3", "1", "1", "1", "50.0%", "33.3%"),
                List.of("PCI-DSS v4.0.1", "0", "0", "0", "0", "UNKNOWN", "UNKNOWN"),
                List.of("Financial Baseline", "0", "0", "0", "0", "UNKNOWN", "UNKNOWN")), rows(doc, "framework_summary"));
        assertEquals(List.of(List.of("AC", "1", "1", "0", "50.0%"), List.of("AU", "0", "0", "1", "UNKNOWN")),
                rows(doc, "families_nist_800_53"));
        assertEquals(List.of(List.of("1", "1", "1", "0", "50.0%")), rows(doc, "families_cis"));
    }

    @Test
    void rpt02ListsTopFailingControlsBySeverityWithTheFirewallsAffected() {
        ReportDocument doc = new ComplianceCoverageBuilder().build(context(ReportTemplate.RPT_02), new ReportFixtures.Data());
        List<List<String>> top = rows(doc, "top_failing_controls");
        assertEquals("CP-1", top.get(0).get(0));
        assertEquals("CRITICAL", top.get(0).get(2));
        assertEquals("CP-2", top.get(1).get(0));
        assertEquals(List.of(List.of("CP-1", HOST_A, "Check Point", "CRITICAL"), List.of("CP-2", HOST_B, "Check Point", "LOW")),
                rows(doc, "affected_firewalls"));
    }

    @Test
    void rpt02CoverageCountsFirewallsOnlyAndFlagsStaleAndMissingEvidence() {
        ReportDocument doc = new ComplianceCoverageBuilder().build(context(ReportTemplate.RPT_02), new ReportFixtures.Data());
        PlaneAsOf p = plane(doc, "configuration (compliance)");
        assertEquals(4, p.total(), "four firewalls; the management server is not a compliance target");
        assertEquals(2, p.covered());
        assertEquals(1, p.stale(), "dev-b's configuration evidence is 30 h old");
        assertEquals(FRESH, p.newest());
        assertEquals(STALE, p.oldest());
        assertTrue(doc.notices().stream().anyMatch(n -> n.startsWith("STALE:")));
        assertTrue(doc.notices().stream().anyMatch(n -> n.startsWith("NO EVIDENCE: 2 of 4 firewalls")));
    }

    @Test
    void rpt02FrameworkParameterNarrowsToOneFramework() {
        var ctx = new ReportBuilder.Context(ReportTemplate.RPT_02,
                ReportParameters.parse(ReportTemplate.RPT_02, null, null, "NIST_800_53", ReportFixtures.NOW), ReportFixtures.NOW, "a");
        ReportDocument doc = new ComplianceCoverageBuilder().build(ctx, new ReportFixtures.Data());
        assertEquals(1, rows(doc, "framework_summary").size());
        assertEquals(List.of(List.of("CP-2", "SSH timeout", "LOW", "1", "1", "0")), rows(doc, "top_failing_controls"));
    }

    @Test
    void rpt02WithoutEvaluationsIsUnknownNeverZeroOrHundredPercent() {
        var data = new ReportFixtures.Data();
        data.compliance.clear();
        data.compliance.add(new ComplianceRow("dev-c", FRESH, "not json"));
        ReportDocument doc = new ComplianceCoverageBuilder().build(context(ReportTemplate.RPT_02), data);
        assertTrue(rows(doc, "framework_summary").stream().allMatch(r -> r.get(5).equals("UNKNOWN")));
        assertEquals("UNKNOWN", doc.sections().get(0).kpis().stream()
                .filter(k -> k.label().equals("Critical controls failing")).findFirst().orElseThrow().tone(),
                "no evaluation means unknown, not zero failures");
        assertTrue(doc.notices().stream().anyMatch(n -> n.contains("could not be read")));
        assertEquals(0, plane(doc, "configuration (compliance)").covered());
    }

    // ---- RPT-03 -------------------------------------------------------------------------------------------------

    @Test
    void rpt03SeparatesTargetsWithAndWithoutArchiveAndLabelsStaleArchives() {
        ReportDocument doc = new BackupCoverageBuilder().build(context(ReportTemplate.RPT_03), new ReportFixtures.Data());
        var k = kpis(doc, "coverage");
        assertEquals("3", k.get("Backup targets"));
        assertEquals("2", k.get("With an archive"));
        assertEquals("1", k.get("Without an archive"));
        assertEquals("1", k.get("Last archive older than 24 h"));
        List<List<String>> targets = rows(doc, "targets");
        // sorted by name: synthetic-raw-edge-bravo, then the two synthetic-raw-gw-alpha members
        assertEquals(List.of(HOST_C, "Palo Alto", "NO ARCHIVE", "NONE", "UNKNOWN", "UNKNOWN", "0"), targets.get(0));
        assertEquals(List.of(HOST_A, "Check Point", "CURRENT", FRESH.toString(), "3 h", "50.0 MiB", "7"), targets.get(1));
        assertEquals(List.of(HOST_B, "Check Point", "STALE", STALE.toString(), "30 h", "3.0 GiB", "2"), targets.get(2));
        PlaneAsOf p = plane(doc, "backup");
        assertEquals(3, p.total());
        assertEquals(2, p.covered());
        assertEquals(1, p.stale());
    }

    @Test
    void rpt03ListsTheThreeSizeAlertClassesWithSizesOnly() {
        ReportDocument doc = new BackupCoverageBuilder().build(context(ReportTemplate.RPT_03), new ReportFixtures.Data());
        List<List<String>> alerts = rows(doc, "size_alerts");
        assertEquals(List.of("OWN HISTORY", "FLEET LARGE", "FIREWALL LOGS"), alerts.stream().map(r -> r.get(0)).toList());
        assertEquals("growth: 3.0 GiB against a median of 50.0 MiB (ratio 61.4)", alerts.get(0).get(4));
        assertEquals("2.0 GiB of firewall logs in 3.0 GiB (66.7%)", alerts.get(2).get(4));
        assertEquals("1", kpis(doc, "anomalies").get("Unresolved size anomalies"));
    }

    @Test
    void rpt03WithoutTargetsIsUnknown() {
        var data = new ReportFixtures.Data();
        data.devices.replaceAll(d -> ReportFixtures.device(d.deviceId(), d.observedHostname().orElse(null), d.vendorHint(),
                d.role(), d.clusterMemberRef().orElse(null), false, null));
        ReportDocument doc = new BackupCoverageBuilder().build(context(ReportTemplate.RPT_03), data);
        assertEquals("0", kpis(doc, "coverage").get("Backup targets"));
        assertTrue(doc.notices().stream().anyMatch(n -> n.contains("backup coverage is UNKNOWN")));
        assertNull(plane(doc, "backup").newest());
    }

    // ---- RPT-05 -------------------------------------------------------------------------------------------------

    @Test
    void rpt05CountsReadyNotReadyUnknownAndNotSupportedPerCluster() {
        ReportDocument doc = new HaReadinessBuilder().build(context(ReportTemplate.RPT_05), new ReportFixtures.Data());
        var k = kpis(doc, "readiness");
        assertEquals("1", k.get("Ready"));
        assertEquals("0", k.get("Not ready"));
        assertEquals("0", k.get("Unknown (no readiness evidence)"));
        assertEquals("1", k.get("Not supported"), "the FortiGate cluster");
        assertEquals(List.of(List.of(CLUSTER_1, "Check Point", "2", "READY", FRESH.toString(), "3 h"),
                List.of(CLUSTER_2, "Fortinet", "1", "NOT_SUPPORTED", "UNKNOWN", "UNKNOWN")), rows(doc, "clusters"));
    }

    @Test
    void rpt05FlagsStaleReadinessAsStaleNeverReady() {
        var data = new ReportFixtures.Data();
        data.ha.clear();
        data.ha.add(new HaUnit(CLUSTER_1, "check_point", null, false, "READY", STALE, null, null));
        ReportDocument doc = new HaReadinessBuilder().build(context(ReportTemplate.RPT_05), data);
        var k = kpis(doc, "readiness");
        assertEquals("0", k.get("Ready"));
        assertEquals("1", k.get("Ready but stale (older than 24 h)"));
        assertEquals("STALE", rows(doc, "clusters").get(0).get(3));
        assertEquals("STALE", rows(doc, "reasons").get(0).get(2));
        assertTrue(plane(doc, "HA readiness").anyStale());
    }

    @Test
    void rpt05ListsReasonsForNotReadyPerUnitAndUnknownWithoutEvidence() {
        var data = new ReportFixtures.Data();
        data.ha.clear();
        data.ha.add(new HaUnit(CLUSTER_1, "check_point", null, true, "READY", FRESH, null, null));
        data.ha.add(new HaUnit(CLUSTER_1, "check_point", "2", false, "NOT_READY", FRESH, "CP-04", "SYNC_NOT_OK"));
        ReportDocument doc = new HaReadinessBuilder().build(context(ReportTemplate.RPT_05), data);
        assertEquals(List.of(List.of(CLUSTER_1, "Virtual system 2", "NOT_READY", "CP-04", "SYNC_NOT_OK", FRESH.toString(), "3 h")),
                rows(doc, "reasons"));
        data.ha.clear();
        ReportDocument none = new HaReadinessBuilder().build(context(ReportTemplate.RPT_05), data);
        assertEquals("UNKNOWN", rows(none, "clusters").get(0).get(3));
        assertEquals(0, plane(none, "HA readiness").covered());
    }

    @Test
    void readinessVerdictNeverTurnsOldReadyIntoReady() {
        assertEquals("READY", HaFacts.verdict("READY", ReportFixtures.NOW.minus(Duration.ofHours(23)), ReportFixtures.NOW));
        assertEquals("STALE", HaFacts.verdict("READY", ReportFixtures.NOW.minus(Duration.ofHours(25)), ReportFixtures.NOW));
        assertEquals("NOT_READY", HaFacts.verdict("NOT_READY", STALE, ReportFixtures.NOW));
        assertEquals("UNKNOWN", HaFacts.verdict(null, null, ReportFixtures.NOW));
        assertEquals("UNKNOWN", HaFacts.verdict("UNKNOWN", FRESH, ReportFixtures.NOW));
    }

    // ---- RPT-01 -------------------------------------------------------------------------------------------------

    @Test
    void rpt01IsAtRiskWhenAnyPillarIsAndShowsUpToFiveAttentionItems() {
        ReportDocument doc = new ExecutiveSummaryBuilder().build(context(ReportTemplate.RPT_01), new ReportFixtures.Data());
        assertEquals("AT RISK", kpis(doc, "verdict").get("Estate"));
        List<List<String>> pillars = rows(doc, "pillars");
        assertEquals(List.of("Security posture", "Backup coverage", "HA readiness", "Evidence freshness"),
                pillars.stream().map(r -> r.get(0)).toList());
        assertEquals("AT RISK", pillars.get(0).get(1), "a critical control fails");
        assertEquals("AT RISK", pillars.get(1).get(1), "a target has no archive");
        assertEquals("HEALTHY", pillars.get(2).get(1));
        assertEquals("UNKNOWN", pillars.get(3).get(1), "one device never inventoried, one stale");
        List<List<String>> attention = rows(doc, "attention");
        assertTrue(attention.size() <= 5);
        assertEquals(List.of("Backup", HOST_C, "Backup target without an archive"), attention.get(0));
        assertEquals(4, doc.dataAsOf().size());
    }

    @Test
    void rpt01HeadlinesCisAndNistAsEvidencedPassShareAndNoEvidenceShare() {
        ReportDocument doc = new ExecutiveSummaryBuilder().build(context(ReportTemplate.RPT_01), new ReportFixtures.Data());
        assertEquals(List.of(List.of("CIS Benchmark", "50.0%", "0.0%", "1", "1", "0"),
                List.of("NIST SP 800-53", "50.0%", "33.3%", "1", "1", "1")), rows(doc, "compliance_headline"));
    }

    @Test
    void rpt01IsUnknownNotHealthyWhenEvidenceIsMissingOrStale() {
        var data = new ReportFixtures.Data();
        data.compliance.clear();
        data.backups.clear();
        data.deviations.clear();
        data.devices.replaceAll(d -> ReportFixtures.device(d.deviceId(), d.observedHostname().orElse(null), d.vendorHint(),
                d.role(), d.clusterMemberRef().orElse(null), false, null));
        data.ha.clear();
        data.ha.add(new HaUnit(CLUSTER_1, "check_point", null, false, "READY", STALE, null, null));
        ReportDocument doc = new ExecutiveSummaryBuilder().build(context(ReportTemplate.RPT_01), data);
        assertEquals("UNKNOWN", kpis(doc, "verdict").get("Estate"));
        assertTrue(rows(doc, "pillars").stream().noneMatch(r -> r.get(1).equals("HEALTHY")));
        assertTrue(rows(doc, "compliance_headline").stream().allMatch(r -> r.get(1).equals("UNKNOWN")));
    }

    @Test
    void rpt01IsHealthyOnlyWhenEveryPillarIsKnownGood() {
        var data = new ReportFixtures.Data();
        data.devices.clear();
        data.devices.add(ReportFixtures.device("dev-a", HOST_A, "check_point", "gateway", CLUSTER_1, true, null));
        data.devices.add(ReportFixtures.device("dev-b", HOST_B, "check_point", "gateway", CLUSTER_1, true, null));
        data.compliance.clear();
        data.compliance.add(new ComplianceRow("dev-a", FRESH, ReportFixtures.evaluation(
                ReportFixtures.item("X", "x", "LOW", "PASS", "CIS", "1.1"))));
        data.compliance.add(new ComplianceRow("dev-b", FRESH, ReportFixtures.evaluation(
                ReportFixtures.item("X", "x", "LOW", "PASS", "CIS", "1.1"))));
        data.configuration.put("dev-b", FRESH);
        data.backups.replaceAll(b -> new com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.BackupRow(
                b.deviceId(), b.artefactId(), FRESH, b.plaintextBytes(), b.vendor(), 1));
        data.deviations.clear();
        data.inventory.put("dev-b", FRESH);
        ReportDocument doc = new ExecutiveSummaryBuilder().build(context(ReportTemplate.RPT_01), data);
        assertEquals("HEALTHY", kpis(doc, "verdict").get("Estate"));
        assertEquals(List.of(), rows(doc, "attention"));
        assertFalse(doc.notices().stream().anyMatch(n -> n.startsWith("STALE")));
    }

    @Test
    void parametersDefaultToSevenDaysAndRejectBadRanges() {
        var p = ReportParameters.parse(ReportTemplate.RPT_03, null, null, null, ReportFixtures.NOW);
        assertEquals("2026-10-30", p.from().toString());
        assertEquals("2026-11-05", p.to().toString());
        assertNull(p.framework());
        assertEquals("ALL", ReportParameters.parse(ReportTemplate.RPT_02, null, null, "", ReportFixtures.NOW).framework());
        for (String[] bad : new String[][] {{"2026-11-06", null, null}, {"2026-11-05", "2026-11-01", null},
                {"2026-01-01", "2026-11-05", null}, {"yesterday", null, null}}) {
            ReportParameters.Invalid e = org.junit.jupiter.api.Assertions.assertThrows(ReportParameters.Invalid.class,
                    () -> ReportParameters.parse(ReportTemplate.RPT_03, bad[0], bad[1], bad[2],
                            ReportFixtures.NOW));
            assertEquals("INVALID_DATE_RANGE", e.code());
        }
        assertEquals("INVALID_FRAMEWORK", org.junit.jupiter.api.Assertions.assertThrows(ReportParameters.Invalid.class,
                () -> ReportParameters.parse(ReportTemplate.RPT_03, null, null, "CIS", ReportFixtures.NOW)).code());
        assertEquals("INVALID_FRAMEWORK", org.junit.jupiter.api.Assertions.assertThrows(ReportParameters.Invalid.class,
                () -> ReportParameters.parse(ReportTemplate.RPT_02, null, null, "COBIT", ReportFixtures.NOW)).code());
    }
}
