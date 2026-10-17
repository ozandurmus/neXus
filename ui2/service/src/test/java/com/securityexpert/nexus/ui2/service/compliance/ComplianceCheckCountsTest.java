package com.securityexpert.nexus.ui2.service.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The fleet compliance figures are counted over control checks the same way on every card (UI review 2026-10-17,
 * P0-2): a framework's total is the sum of its pass, fail and unavailable parts, NOT_APPLICABLE is not a check,
 * data gaps are the unavailable checks, and the percentages use the Overview screen's rounding. Synthetic fixtures.
 */
class ComplianceCheckCountsTest {

    private static Map<String, Object> item(String status, String severity, String... frameworks) {
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (String f : frameworks) {
            mappings.add(Map.of("framework", f));
        }
        return Map.of("controlId", "c-" + status + severity + frameworks.length, "displayStatus", status,
                "severity", severity, "frameworks", mappings);
    }

    private static Map<String, Object> eval(Map<?, ?>... items) {
        return Map.of("items", List.of(items));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> card(Map<String, Object> agg, String name) {
        return ((List<Map<String, Object>>) agg.get("frameworks")).stream()
                .filter(c -> name.equals(c.get("framework"))).findFirst().orElseThrow();
    }

    private static long n(Object o) {
        return ((Number) o).longValue();
    }

    @Test
    void notApplicableIsNotACheckAndEveryFrameworkTotalIsTheSumOfItsParts() {
        // the live shape behind "CIS 2523 control checks, 749 + 1266 + 500": a FortiGate HA control is
        // NOT_APPLICABLE on standalone units and maps to CIS, PCI and NIST but not to the financial baseline
        Map<String, Object> agg = ComplianceService.aggregateChecks(List.of(
                eval(item("PASS", "HIGH", "CIS", "PCI_DSS", "NIST_800_53", "FINANCIAL_BASELINE"),
                        item("FAIL", "CRITICAL", "CIS", "NIST_800_53"),
                        item("DATA_UNAVAILABLE", "MEDIUM", "CIS", "PCI_DSS", "NIST_800_53"),
                        item("NOT_APPLICABLE", "HIGH", "CIS", "PCI_DSS", "NIST_800_53")),
                eval(item("NOT_APPLICABLE", "HIGH", "CIS", "PCI_DSS", "NIST_800_53"),
                        item("PASS", "CRITICAL", "CIS", "PCI_DSS"))));

        for (String fw : List.of("CIS Benchmark", "PCI-DSS v4.0.1", "NIST SP 800-53", "Financial Baseline")) {
            Map<String, Object> c = card(agg, fw);
            assertThat(n(c.get("total_controls")))
                    .as(fw).isEqualTo(n(c.get("pass_count")) + n(c.get("fail_count")) + n(c.get("data_unavailable_count")));
        }
        Map<String, Object> cis = card(agg, "CIS Benchmark");
        assertThat(n(cis.get("total_controls"))).isEqualTo(4);
        assertThat(n(cis.get("pass_count"))).isEqualTo(2);
        assertThat(n(cis.get("fail_count"))).isEqualTo(1);
        assertThat(n(cis.get("data_unavailable_count"))).isEqualTo(1);
        assertThat(n(card(agg, "PCI-DSS v4.0.1").get("total_controls"))).isEqualTo(3);
        assertThat(n(card(agg, "NIST SP 800-53").get("total_controls"))).isEqualTo(3);
        assertThat(n(card(agg, "Financial Baseline").get("total_controls"))).isEqualTo(1);

        // every check maps to CIS, so the fleet check count, the data gaps and the CIS card agree
        assertThat(n(agg.get("control_checks"))).isEqualTo(n(cis.get("total_controls")));
        assertThat(n(agg.get("data_gaps"))).isEqualTo(n(cis.get("data_unavailable_count")));
        assertThat(n(agg.get("critical_deficiencies"))).isEqualTo(1);
    }

    @Test
    void scoresUseTheOverviewRoundingOverTheSameTotal() {
        // 749 / 2515 = 29.78 % -> 29.8 on both screens (it read 29.7 on Compliance: 749 / 2523, N/A in the total)
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 749; i++) items.add(item("PASS", "HIGH", "CIS"));
        for (int i = 0; i < 1266; i++) items.add(item("FAIL", "HIGH", "CIS"));
        for (int i = 0; i < 500; i++) items.add(item("DATA_UNAVAILABLE", "HIGH", "CIS"));
        for (int i = 0; i < 8; i++) items.add(item("NOT_APPLICABLE", "HIGH", "CIS"));
        Map<String, Object> agg = ComplianceService.aggregateChecks(List.of(Map.of("items", items)));
        Map<String, Object> cis = card(agg, "CIS Benchmark");

        assertThat(n(cis.get("total_controls"))).isEqualTo(2515);
        assertThat(cis.get("score_pct")).isEqualTo(29.8);
        // what the Overview computes from the parts: Math.round(1000 * pass / (pass + fail + unavailable)) / 10
        assertThat(cis.get("score_pct")).isEqualTo(Math.round(1000.0 * 749 / (749 + 1266 + 500)) / 10.0);
        // pooled over checks, as the cards say ("checks with collected evidence"), equal to the Overview's "assessed"
        assertThat(agg.get("assured_compliance_pct")).isEqualTo(29.8);
        assertThat(agg.get("evidence_coverage_pct")).isEqualTo(80.1);
        assertThat(agg.get("observed_compliance_pct")).isEqualTo(37.2);
        assertThat(n(agg.get("data_gaps"))).isEqualTo(500);
    }

    @Test
    void anItemCountsOncePerFrameworkAndAnUnknownFrameworkIsOnNoCard() {
        Map<String, Object> agg = ComplianceService.aggregateChecks(List.of(
                eval(item("PASS", "HIGH", "CIS", "CIS", "NIST_800_53"), item("FAIL", "HIGH", "COBIT"))));
        assertThat(n(card(agg, "CIS Benchmark").get("total_controls"))).isEqualTo(1);
        assertThat(n(card(agg, "Financial Baseline").get("total_controls"))).isZero();
        assertThat(n(agg.get("control_checks"))).isEqualTo(2);
    }

    @Test
    void aStatusThatIsNeitherPassNorFailIsNotEvidence() {
        Map<String, Object> agg = ComplianceService.aggregateChecks(List.of(eval(item("ERROR", "HIGH", "CIS"))));
        assertThat(n(card(agg, "CIS Benchmark").get("data_unavailable_count"))).isEqualTo(1);
        assertThat(n(agg.get("data_gaps"))).isEqualTo(1);
        assertThat(ComplianceService.checkOutcome("NOT_APPLICABLE")).isNull();
        assertThat(ComplianceService.checkOutcome(null)).isEqualTo(ComplianceService.CheckOutcome.UNAVAILABLE);
    }

    @Test
    void noEvaluationMeansNoChecksNotInventedOnes() {
        Map<String, Object> agg = ComplianceService.aggregateChecks(List.of());
        assertThat(n(agg.get("control_checks"))).isZero();
        assertThat(n(card(agg, "CIS Benchmark").get("total_controls"))).isZero();
        assertThat(agg.get("evidence_coverage_pct")).isEqualTo(0.0);
    }

    @Test
    void deviceCriticalsCountEvidenceOfAnySeverity() {
        // a Cisco ASA today: every check unavailable, none critical -> nothing evidenced
        ComplianceService.DeviceCriticals asa = ComplianceService.deviceCriticals(
                eval(item("DATA_UNAVAILABLE", "HIGH", "CIS"), item("DATA_UNAVAILABLE", "MEDIUM", "CIS")));
        assertThat(asa).isEqualTo(new ComplianceService.DeviceCriticals(0, 0, 0, 0));
        // a FortiGate: evidence, but no critical control in its catalog
        ComplianceService.DeviceCriticals forti = ComplianceService.deviceCriticals(
                eval(item("PASS", "HIGH", "CIS"), item("FAIL", "HIGH", "CIS"), item("NOT_APPLICABLE", "HIGH", "CIS")));
        assertThat(forti).isEqualTo(new ComplianceService.DeviceCriticals(0, 0, 0, 2));
        ComplianceService.DeviceCriticals cp = ComplianceService.deviceCriticals(
                eval(item("PASS", "CRITICAL", "CIS"), item("DATA_UNAVAILABLE", "CRITICAL", "CIS"), item("FAIL", "CRITICAL", "CIS")));
        assertThat(cp).isEqualTo(new ComplianceService.DeviceCriticals(1, 1, 1, 2));
    }
}
