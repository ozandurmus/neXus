package com.securityexpert.nexus.ui2.service.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ComplianceControlDetailTest {
    @Test
    void reportProjectionWithholdsSecretAndUnknownEvidenceWithoutChangingStoredItems() {
        var guidance = Map.of("summary", "Review access", "steps", List.of("Review the approved policy"));
        Map<String, Object> item = Map.of("controlId", "c1", "observedValue", "synthetic-sensitive-value",
                "message", "synthetic-sensitive-value", "guidance", guidance);
        Map<String, Object> evaluation = Map.of("items", List.of(item));
        Map<String, Object> secret = Map.of("id", "c1", "bindings", List.of(Map.of(
                "evidenceRequirement", Map.of("settingKey", "gaia.expert.hash"))));
        for (var catalog : List.of(List.of(secret), List.<Map<String, Object>>of())) {
            var result = ComplianceService.publicItems(evaluation, catalog).get(0);
            assertThat(result.get("observedValue")).isNull();
            assertThat(result.get("message")).isEqualTo("Evidence withheld");
            assertThat(result.get("guidance")).isEqualTo(guidance);
        }
        assertThat(item.get("observedValue")).isEqualTo("synthetic-sensitive-value");
        var safe = ComplianceService.publicItems(evaluation, List.of(Map.of("id", "c1", "bindings", List.of())));
        assertThat(safe.get(0)).isEqualTo(item);
    }

    @Test
    void assertionWordsCoverEveryCatalogOperator() {
        assertThat(ComplianceService.assertionText(Map.of("op", "gte", "targetNumber", 5))).isEqualTo("at least 5");
        assertThat(ComplianceService.assertionText(Map.of("op", "count_gte", "targetNumber", 5))).isEqualTo("at least 5");
        assertThat(ComplianceService.assertionText(Map.of("op", "lte", "targetNumber", 600))).isEqualTo("at most 600");
        assertThat(ComplianceService.assertionText(Map.of("op", "count_lte", "targetNumber", 600))).isEqualTo("at most 600");
        assertThat(ComplianceService.assertionText(Map.of("op", "equals", "targetString", "X"))).isEqualTo("set to X");
        assertThat(ComplianceService.assertionText(Map.of("op", "not_equals", "targetString", "X"))).isEqualTo("not set to X");
        assertThat(ComplianceService.assertionText(Map.of("op", "present"))).isEqualTo("configured");
        assertThat(ComplianceService.assertionText(Map.of("op", "absent"))).isEqualTo("not configured");
        assertThat(ComplianceService.assertionText(Map.of("op", "in", "targetList", List.of("A", "B")))).isEqualTo("one of: A, B");
        assertThat(ComplianceService.assertionText(Map.of("op", "not_in", "targetList", List.of("A")))).isEqualTo("not one of: A");
        assertThat(ComplianceService.assertionText(Map.of("op", "matches"))).isEqualTo("matches the required pattern");
        assertThat(ComplianceService.assertionText(Map.of("op", "any_match"))).isEqualTo("matches the required pattern");
        assertThat(ComplianceService.assertionText(Map.of("op", "not_match"))).isEqualTo("does not match the prohibited pattern");
        assertThat(ComplianceService.assertionText(Map.of("op", "none_match"))).isEqualTo("does not match the prohibited pattern");
        assertThat(ComplianceService.assertionText(Map.of("op", "unknown"))).isEqualTo("see benchmark");
    }
}
