package com.securityexpert.nexus.ui2.worker.compliance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.compliance.catalog.CheckPointComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.catalog.FortinetAsaComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.catalog.PaloAltoComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceFramework;
import com.securityexpert.nexus.ui2.worker.compliance.model.FrameworkMapping;

/**
 * PO decision 2026-11-13: the Financial baseline is a neXus baseline, not a regulation. Its references are neXus
 * identifiers (FB-AREA-NN); no clause-like text, no regulator name.
 */
class FinancialBaselineReferencesTest {

    private static final Pattern NEXUS_ID = Pattern.compile("^FB-[A-Z]+-\\d{2}$");

    private static List<ComplianceControl> allControls() {
        List<ComplianceControl> all = new ArrayList<>();
        all.addAll(CheckPointComplianceCatalog.getControls());
        all.addAll(PaloAltoComplianceCatalog.getControls());
        all.addAll(FortinetAsaComplianceCatalog.getControls("fortinet"));
        all.addAll(FortinetAsaComplianceCatalog.getControls("cisco"));
        return all;
    }

    @Test
    void everyFinancialBaselineReferenceIsANexusIdentifier() {
        int seen = 0;
        for (ComplianceControl control : allControls()) {
            for (FrameworkMapping mapping : control.frameworks()) {
                if (mapping.framework() != ComplianceFramework.FINANCIAL_BASELINE) continue;
                seen++;
                assertTrue(NEXUS_ID.matcher(mapping.reference()).matches(),
                        control.id() + " carries a non-neXus reference: " + mapping.reference());
                assertFalse(mapping.version().matches("^\\d{4}$"), control.id() + " carries a year as a regulation edition");
            }
        }
        assertTrue(seen > 0);
    }

    @Test
    void theFrameworkIsDescribedAsNotARegulationMappingAndNamesNoRegulator() {
        String description = ComplianceFramework.FINANCIAL_BASELINE.description();
        assertTrue(description.contains("not a mapping to any regulation"));
        assertTrue(description.contains("Regulatory mapping is the bank compliance team's input."));
        assertFalse((ComplianceFramework.FINANCIAL_BASELINE.displayName() + description).toUpperCase().contains("BDDK"));
    }
}
