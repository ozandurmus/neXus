package com.securityexpert.nexus.ui2.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** PO decision 2026-11-13: Financial baseline identifiers, and how evaluations stored before it still read. */
class FinancialBaselineFamilyTest {

    @Test
    void aNexusIdentifierBelongsToItsAreaFamily() {
        assertEquals("FB-ACCESS", ComplianceFacts.family("FINANCIAL_BASELINE", "FB-ACCESS-01"));
        assertEquals("FB-AUDIT", ComplianceFacts.family("FINANCIAL_BASELINE", "FB-AUDIT-01"));
    }

    @Test
    void aRetiredClauseLikeReferenceReadsUnderTheFamilyOfItsReplacement() {
        assertEquals("FB-ACCESS", ComplianceFacts.family("FINANCIAL_BASELINE", "Access Control §3.1"));
        assertEquals("FB-LOCKOUT", ComplianceFacts.family("FINANCIAL_BASELINE", "Brute Force Protection §2.4"));
        assertEquals("Unmapped", ComplianceFacts.family("FINANCIAL_BASELINE", "Something Else §1.1"));
    }

    @Test
    void theNoticeStatesThereIsNoRegulationMapping() {
        assertTrue(ComplianceFacts.FINANCIAL_BASELINE_NOTICE.contains("not a mapping to any regulation"));
        assertTrue(ComplianceFacts.FINANCIAL_BASELINE_NOTICE.contains("Regulatory mapping is the bank compliance team's input."));
    }

    @Test
    void theFrameworkReportCarriesTheNoticeWhereTheBaselineIsListed() {
        ReportDocument doc = new ComplianceCoverageBuilder().build(ReportFixtures.context(ReportTemplate.RPT_02),
                new ReportFixtures.Data());
        long carrying = doc.sections().stream()
                .filter(s -> s.notes().contains(ComplianceFacts.FINANCIAL_BASELINE_NOTICE)).count();
        assertEquals(2, carrying, "the framework summary and the Financial baseline family section");
    }
}
