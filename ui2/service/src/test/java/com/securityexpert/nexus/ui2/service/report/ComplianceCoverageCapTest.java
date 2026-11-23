package com.securityexpert.nexus.ui2.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.ComplianceRow;
import org.junit.jupiter.api.Test;

/**
 * RPT-02 severity ordering and the row caps of {@link ComplianceCoverageBuilder}: pins the behaviour as it exists.
 * Synthetic control ids and device labels only.
 */
class ComplianceCoverageCapTest {

    @Test
    void severityRankOrdersCriticalAboveHighAboveMediumAboveLow() {
        assertEquals(4, ComplianceCoverageBuilder.severityRank("CRITICAL"));
        assertEquals(3, ComplianceCoverageBuilder.severityRank("HIGH"));
        assertEquals(2, ComplianceCoverageBuilder.severityRank("MEDIUM"));
        assertEquals(1, ComplianceCoverageBuilder.severityRank("LOW"));
        assertTrue(ComplianceCoverageBuilder.severityRank("CRITICAL") > ComplianceCoverageBuilder.severityRank("HIGH"));
        assertTrue(ComplianceCoverageBuilder.severityRank("HIGH") > ComplianceCoverageBuilder.severityRank("MEDIUM"));
        assertTrue(ComplianceCoverageBuilder.severityRank("MEDIUM") > ComplianceCoverageBuilder.severityRank("LOW"));
    }

    @Test
    void severityRankIsZeroForUnknownNullAndEmptySeverities() {
        // Pinned as written: an unrecognised or missing severity ranks below LOW, and the match is case-sensitive.
        assertEquals(0, ComplianceCoverageBuilder.severityRank("UNKNOWN"));
        assertEquals(0, ComplianceCoverageBuilder.severityRank(null));
        assertEquals(0, ComplianceCoverageBuilder.severityRank(""));
        assertEquals(0, ComplianceCoverageBuilder.severityRank("INFO"));
        assertEquals(0, ComplianceCoverageBuilder.severityRank("critical"));
        assertTrue(ComplianceCoverageBuilder.severityRank("UNKNOWN") < ComplianceCoverageBuilder.severityRank("LOW"));
    }

    @Test
    void theTopFailingControlsTableNeverExceedsTopControls() {
        var data = new ReportFixtures.Data();
        data.devices.clear();
        data.compliance.clear();
        data.devices.add(ReportFixtures.device("dev-z", "FW-TANGO-04", "check_point", "gateway", null, true, null));
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= ComplianceCoverageBuilder.TOP_CONTROLS + 2; i++) {
            items.add(ReportFixtures.item(String.format("CT-%02d", i), "Synthetic control " + i, "LOW", "FAIL", "CIS", "1." + i));
        }
        data.compliance.add(new ComplianceRow("dev-z", ReportFixtures.FRESH, ReportFixtures.evaluation(items.toArray(String[]::new))));

        var doc = new ComplianceCoverageBuilder().build(ReportFixtures.context(ReportTemplate.RPT_02), data);
        List<List<String>> top = ReportFixtures.rows(doc, "top_failing_controls");
        assertEquals(ComplianceCoverageBuilder.TOP_CONTROLS, top.size());
        // Ties on severity and count fall back to the control id, so the first ten ids are the ones shown.
        assertEquals("CT-01", top.get(0).get(0));
        assertEquals("CT-10", top.get(9).get(0));
        assertEquals(ComplianceCoverageBuilder.TOP_CONTROLS, ReportFixtures.rows(doc, "affected_firewalls").size());
    }

    @Test
    void theAffectedFirewallsTableNeverExceedsMaxAffectedRowsAndSaysSo() {
        int firewalls = ComplianceCoverageBuilder.MAX_AFFECTED_ROWS + 100;
        var data = new ReportFixtures.Data();
        data.devices.clear();
        data.compliance.clear();
        for (int i = 0; i < firewalls; i++) {
            String id = String.format("dev-%04d", i);
            data.devices.add(ReportFixtures.device(id, String.format("FW-TANGO-%04d", i), "check_point", "gateway", null,
                    true, null));
            data.compliance.add(new ComplianceRow(id, ReportFixtures.FRESH, ReportFixtures.evaluation(
                    ReportFixtures.item("CT-CAP", "Synthetic capped control", "HIGH", "FAIL", "CIS", "9.9"))));
        }

        var doc = new ComplianceCoverageBuilder().build(ReportFixtures.context(ReportTemplate.RPT_02), data);
        assertEquals(ComplianceCoverageBuilder.MAX_AFFECTED_ROWS, ReportFixtures.rows(doc, "affected_firewalls").size());
        List<String> summary = ReportFixtures.rows(doc, "top_failing_controls").get(0);
        assertEquals(List.of("CT-CAP", "600", "0", "0"), List.of(summary.get(0), summary.get(3), summary.get(4), summary.get(5)),
                "the control summary still counts every failing firewall, not only the rows shown");
        assertTrue(doc.sections().stream().filter(s -> s.id().equals("top_failing")).findFirst().orElseThrow().notes()
                .contains("Showing " + ComplianceCoverageBuilder.MAX_AFFECTED_ROWS + " of " + firewalls
                        + " failing control/firewall pairs."));
    }
}
