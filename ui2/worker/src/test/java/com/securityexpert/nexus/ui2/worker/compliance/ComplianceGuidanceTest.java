package com.securityexpert.nexus.ui2.worker.compliance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.worker.compliance.catalog.CheckPointComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.catalog.PaloAltoComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.catalog.FortinetAsaComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.evaluator.CheckPointGaiaComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.evaluator.PaloAltoPanOsComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.evaluator.FortinetAsaComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceGuidance;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationItem;

class ComplianceGuidanceTest {
    @Test
    void everyVendorBindingHasGuidanceAndEverySnippetHasAReference() {
        List<ComplianceControl> controls = new ArrayList<>(CheckPointComplianceCatalog.getControls());
        controls.addAll(PaloAltoComplianceCatalog.getControls());
        controls.addAll(FortinetAsaComplianceCatalog.getControls("fortinet"));
        controls.addAll(FortinetAsaComplianceCatalog.getControls("cisco_asa"));
        assertEquals(64, controls.size());
        for (var control : controls) for (var binding : control.bindings()) {
            var guidance = binding.guidance();
            assertNotNull(guidance, control.id());
            assertFalse(guidance.summary().isBlank());
            assertFalse(guidance.steps().isEmpty());
            guidance.steps().forEach(step -> assertFalse(step.isBlank()));
            if (guidance.cli() != null && !guidance.cli().isBlank()) assertFalse(guidance.references().isEmpty());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ComplianceGuidance("s", List.of("step"), "synthetic snippet", List.of(), null));
    }

    @Test
    void evaluatorsCarryGuidanceAndRationaleEvenWhenEvidenceIsMissing() throws Exception {
        var results = List.of(
                CheckPointGaiaComplianceEvaluator.evaluate("synthetic", "check_point", "gaia", List.of(), null),
                PaloAltoPanOsComplianceEvaluator.evaluate("synthetic", "palo_alto", "pan_os", null, null),
                FortinetAsaComplianceEvaluator.evaluate("synthetic", "fortinet", null),
                FortinetAsaComplianceEvaluator.evaluate("synthetic", "cisco_asa", null));
        ObjectMapper mapper = new ObjectMapper();
        for (var result : results) for (var item : result.items()) {
            assertNotNull(item.guidance());
            assertFalse(item.rationale().isBlank());
            assertNull(item.observedValue());
            String json = mapper.writeValueAsString(item);
            assertTrue(json.contains("\"guidance\"") && json.contains("\"steps\"") && json.contains("\"references\""));
            assertFalse(json.contains("\"remediation\""));
            assertEquals(item, mapper.readValue(json, EvaluationItem.class));
        }
        EvaluationItem legacy = mapper.readValue("{\"controlId\":\"legacy\"}", EvaluationItem.class);
        assertNull(legacy.guidance());
    }

    @Test
    void failingEvidenceIsPreservedAlongsideGuidance() {
        var result = CheckPointGaiaComplianceEvaluator.evaluate("synthetic", "check_point", "gaia", List.of(),
                "set password-controls min-password-length 8", java.util.Set.of("gaia_cis_2_1_1_password_min_length"));
        assertEquals(1, result.items().size());
        var item = result.items().getFirst();
        assertEquals("8", item.observedValue());
        assertEquals("FAIL", item.displayStatus().name());
        assertTrue(item.guidance().steps().stream().anyMatch(step -> step.contains("12")));
    }
}
