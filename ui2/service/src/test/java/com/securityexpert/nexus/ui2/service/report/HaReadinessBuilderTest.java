package com.securityexpert.nexus.ui2.service.report;

import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.FRESH;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.NOW;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.STALE;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.context;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.device;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.kpis;
import static com.securityexpert.nexus.ui2.service.report.ReportFixtures.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.service.report.ReportData.HaUnit;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;

/**
 * UNKNOWN handling of the RPT-05 HA readiness builder. Synthetic estate only: FW-TANGO-style names, documentation
 * addresses. Covers {@code blankToUnknown}, the readiness plane when evidence is absent, and the agreement of the
 * summary and reasons counts with the facts they count.
 */
final class HaReadinessBuilderTest {

    private static final String CLS_TANGO = "CLS-TANGO-01";
    private static final String CLS_ROMEO = "CLS-ROMEO-02";
    private static final String CLS_ECHO = "CLS-ECHO-03";
    private static final String CLS_BRAVO = "CLS-BRAVO-04";
    private static final String CLS_DELTA = "CLS-DELTA-05";
    private static final String CLS_KILO = "CLS-KILO-06";

    // ---- blankToUnknown -----------------------------------------------------------------------------------------

    @Test
    void blankToUnknownReturnsUnknownForNullEmptyAndWhitespaceOnly() {
        assertEquals("UNKNOWN", HaReadinessBuilder.blankToUnknown(null));
        assertEquals("UNKNOWN", HaReadinessBuilder.blankToUnknown(""));
        assertEquals("UNKNOWN", HaReadinessBuilder.blankToUnknown("   "));
        assertEquals("UNKNOWN", HaReadinessBuilder.blankToUnknown("\t\n "));
    }

    @Test
    void blankToUnknownPassesRealValuesThroughUnchanged() {
        assertEquals("CP-04", HaReadinessBuilder.blankToUnknown("CP-04"));
        assertEquals("SYNC_NOT_OK", HaReadinessBuilder.blankToUnknown("SYNC_NOT_OK"));
        // Not trimmed: the value is shown as the evidence recorded it.
        assertEquals(" SYNC_NOT_OK ", HaReadinessBuilder.blankToUnknown(" SYNC_NOT_OK "));
    }

    // ---- readiness plane without evidence -----------------------------------------------------------------------

    @Test
    void clusterWithoutAnyReadinessRecordIsNotReportedReady() {
        var data = estate(List.of(
                device("dev-t1", "FW-TANGO-01", "check_point", "gateway", CLS_TANGO, true, "192.0.2.10"),
                device("dev-t2", "FW-TANGO-02", "check_point", "gateway", CLS_TANGO, true, "192.0.2.11")));
        data.ha.clear();
        var doc = build(data);

        assertEquals("0", kpis(doc, "readiness").get("Ready"));
        assertEquals("1", kpis(doc, "readiness").get("Unknown (no readiness evidence)"));
        assertEquals("UNKNOWN", rows(doc, "clusters").get(0).get(3));
        PlaneAsOf plane = plane(doc);
        assertEquals(0, plane.covered(), "no cluster has readiness evidence");
        assertEquals(1, plane.total());
        assertNull(plane.newest());
        assertFalse(plane.anyStale());
    }

    @Test
    void readyOutcomeWithoutObservationTimeIsUnknownNotReady() {
        var data = estate(List.of(
                device("dev-t1", "FW-TANGO-01", "check_point", "gateway", CLS_TANGO, true, "192.0.2.10")));
        data.ha.clear();
        data.ha.add(new HaUnit(CLS_TANGO, "check_point", null, false, "READY", null, null, null));
        var doc = build(data);

        assertEquals("0", kpis(doc, "readiness").get("Ready"));
        assertEquals("1", kpis(doc, "readiness").get("Unknown (no readiness evidence)"));
        assertEquals("UNKNOWN", rows(doc, "clusters").get(0).get(3));
        assertEquals(0, plane(doc).covered(), "a result without a time is not evidence");
    }

    @Test
    void clusterWithOnlyVirtualSystemEvidenceHasNoBaseVerdictAndIsNotReady() {
        var data = estate(List.of(
                device("dev-t1", "FW-TANGO-01", "check_point", "gateway", CLS_TANGO, true, "192.0.2.10")));
        data.ha.clear();
        data.ha.add(new HaUnit(CLS_TANGO, "check_point", "2", false, "READY", FRESH, null, null));
        var doc = build(data);

        assertEquals("0", kpis(doc, "readiness").get("Ready"));
        assertEquals("1", kpis(doc, "readiness").get("Unknown (no readiness evidence)"));
        assertEquals("UNKNOWN", rows(doc, "clusters").get(0).get(3));
        assertEquals(0, plane(doc).covered(), "the base unit carries the cluster verdict; a VS alone does not");
    }

    @Test
    void noEnrolledClusterStatesUnknownInNoticeAndSummary() {
        var data = estate(List.of());
        data.ha.clear();
        var doc = build(data);

        assertTrue(doc.notices().stream().anyMatch(n -> n.startsWith("NO EVIDENCE:")));
        assertEquals("0", kpis(doc, "readiness").get("Ready"));
        assertEquals("UNKNOWN", doc.sections().get(0).kpis().get(0).tone(), "no cluster: Ready is UNKNOWN, not OK");
        assertEquals(0, plane(doc).total());
        assertEquals(List.of(), rows(doc, "clusters"));
        assertEquals(List.of(), rows(doc, "reasons"));
    }

    // ---- summary and reasons agree with the facts ---------------------------------------------------------------

    @Test
    void summaryAndReasonsCountTheSameFactsPerCluster() {
        var data = estate(List.of(
                device("dev-t1", "FW-TANGO-01", "check_point", "gateway", CLS_TANGO, true, "192.0.2.10"),
                device("dev-t2", "FW-TANGO-02", "check_point", "gateway", CLS_TANGO, true, "192.0.2.11"),
                device("dev-r1", "FW-ROMEO-01", "check_point", "gateway", CLS_ROMEO, true, "192.0.2.20"),
                device("dev-e1", "FW-ECHO-01", "check_point", "gateway", CLS_ECHO, true, "192.0.2.30"),
                device("dev-b1", "FW-BRAVO-01", "check_point", "gateway", CLS_BRAVO, true, "192.0.2.40"),
                device("dev-d1", "FW-DELTA-01", "fortinet", "firewall", CLS_DELTA, true, "192.0.2.50"),
                device("dev-k1", "FW-KILO-01", "check_point", "gateway", CLS_KILO, true, "192.0.2.60")));
        data.ha.clear();
        data.ha.add(new HaUnit(CLS_TANGO, "check_point", null, false, "READY", FRESH, null, null));
        data.ha.add(new HaUnit(CLS_ROMEO, "check_point", null, false, "NOT_READY", FRESH, "CP-04", "SYNC_NOT_OK"));
        data.ha.add(new HaUnit(CLS_ROMEO, "check_point", "2", false, "READY", FRESH, null, null));
        data.ha.add(new HaUnit(CLS_ECHO, "check_point", null, false, "READY", STALE, null, null));
        data.ha.add(new HaUnit(CLS_KILO, "check_point", null, false, "UNKNOWN", FRESH, null, null));
        var doc = build(data);

        var k = kpis(doc, "readiness");
        assertEquals("1", k.get("Ready"));
        assertEquals("1", k.get("Not ready"));
        assertEquals("1", k.get("Ready but stale (older than 24 h)"));
        assertEquals("2", k.get("Unknown (no readiness evidence)"), "CLS-BRAVO-04 (no record) and CLS-KILO-06 (UNKNOWN)");
        assertEquals("1", k.get("Not supported"));
        assertEquals(6, rows(doc, "clusters").size(), "each cluster is one row, and the KPIs sum to the cluster count");
        assertEquals(6L, Long.parseLong(k.get("Ready")) + Long.parseLong(k.get("Not ready"))
                + Long.parseLong(k.get("Ready but stale (older than 24 h)"))
                + Long.parseLong(k.get("Unknown (no readiness evidence)")) + Long.parseLong(k.get("Not supported")));

        // Clusters are listed in reference order (TreeMap); one row each.
        assertEquals(List.of(
                List.of(CLS_BRAVO, "Check Point", "1", "UNKNOWN", "UNKNOWN", "UNKNOWN"),
                List.of(CLS_DELTA, "Fortinet", "1", "NOT_SUPPORTED", "UNKNOWN", "UNKNOWN"),
                List.of(CLS_ECHO, "Check Point", "1", "STALE", STALE.toString(), "30 h"),
                List.of(CLS_KILO, "Check Point", "1", "UNKNOWN", FRESH.toString(), "3 h"),
                List.of(CLS_ROMEO, "Check Point", "1", "NOT_READY", FRESH.toString(), "3 h"),
                List.of(CLS_TANGO, "Check Point", "2", "READY", FRESH.toString(), "3 h")),
                rows(doc, "clusters"));

        // Reasons: one row per NOT_READY, STALE, or UNKNOWN-with-time unit. The READY VS of CLS-ROMEO-02 and the
        // READY base of CLS-TANGO-01 are not reasons; CLS-BRAVO-04 has no unit and so no row.
        List<List<String>> reasons = rows(doc, "reasons");
        assertEquals(List.of(
                List.of(CLS_ECHO, "Cluster", "STALE", "UNKNOWN", "UNKNOWN", STALE.toString(), "30 h"),
                List.of(CLS_KILO, "Cluster", "UNKNOWN", "UNKNOWN", "UNKNOWN", FRESH.toString(), "3 h"),
                List.of(CLS_ROMEO, "Cluster", "NOT_READY", "CP-04", "SYNC_NOT_OK", FRESH.toString(), "3 h")),
                reasons);
    }

    @Test
    void unitWithBlankFailureFieldsShowsUnknownNotBlank() {
        var data = estate(List.of(
                device("dev-r1", "FW-ROMEO-01", "check_point", "gateway", CLS_ROMEO, true, "192.0.2.20")));
        data.ha.clear();
        data.ha.add(new HaUnit(CLS_ROMEO, "check_point", null, false, "NOT_READY", FRESH, "   ", ""));
        var doc = build(data);

        assertEquals(List.of(CLS_ROMEO, "Cluster", "NOT_READY", "UNKNOWN", "UNKNOWN", FRESH.toString(), "3 h"),
                rows(doc, "reasons").get(0));
    }

    @Test
    void reasonsListOnlyCountedVerdictsWhenEveryUnitIsReadyAndCurrent() {
        var data = estate(List.of(
                device("dev-t1", "FW-TANGO-01", "check_point", "gateway", CLS_TANGO, true, "192.0.2.10")));
        data.ha.clear();
        data.ha.add(new HaUnit(CLS_TANGO, "check_point", null, true, "READY", FRESH, null, null));
        data.ha.add(new HaUnit(CLS_TANGO, "check_point", "2", false, "READY", FRESH, null, null));
        var doc = build(data);

        assertEquals("1", kpis(doc, "readiness").get("Ready"));
        assertEquals(List.of(), rows(doc, "reasons"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Synthetic estate: the given devices and no HA units yet (each test adds its own). */
    private static ReportFixtures.Data estate(List<DeviceSummaryRecord> devices) {
        var data = new ReportFixtures.Data();
        data.devices = new ArrayList<>(devices);
        return data;
    }

    private static ReportDocument build(ReportFixtures.Data data) {
        return new HaReadinessBuilder().build(context(ReportTemplate.RPT_05), data);
    }

    private static PlaneAsOf plane(ReportDocument doc) {
        return doc.sections().get(0).dataAsOf().get(0);
    }
}
