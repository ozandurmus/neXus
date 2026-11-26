package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.service.logdetect.LayerBEvaluator.Baseline;
import com.securityexpert.nexus.ui2.service.logdetect.LayerBEvaluator.DayInput;
import com.securityexpert.nexus.ui2.service.logdetect.LayerBEvaluator.Prevalence;
import com.securityexpert.nexus.ui2.service.logdetect.LayerBEvaluator.TemplateDay;

/** Layer-B kinds, gates and severity (design 4.3, 7.1, 7.4) as a pure function. Synthetic template ids only. */
class LayerBEvaluatorTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final String COMMON = "t" + "1".repeat(31);
    private static final String RARE = "t" + "2".repeat(31);
    private static final String PANIC = "t" + "3".repeat(31);
    private static final String PERIODIC = "t" + "4".repeat(31);

    private static TemplateDay day(String id, double s, String marker, long lines, int peak) {
        LocalDateTime t = DAY.atTime(2, 0);
        return new TemplateDay(id, false, "kernel: <W> state", s, marker, lines, peak, t, t.plusHours(4), List.of());
    }

    private static DayInput input(int history, boolean fullyEvaluated, List<TemplateDay> templates, Set<String> known,
            Map<String, Baseline> baselines) {
        Map<String, Prevalence> prevalence = Map.of(COMMON, new Prevalence(57, 40), RARE, new Prevalence(57, 1),
                PANIC, new Prevalence(57, 0), PERIODIC, new Prevalence(57, 50));
        return new DayInput("dev-tango", "cp_messages", DAY, "cat-1", history, templates, known, 20, false, baselines, prevalence,
                false, Map.of(), Map.of(), fullyEvaluated, fullyEvaluated ? "EVALUATED" : "GAP", DAY.atTime(0, 0), DAY.atTime(23, 0),
                false);
    }

    private static FindingRow only(List<FindingRow> rows, String id) {
        return rows.stream().filter(f -> f.layerB().templateId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test
    void aStatisticalFindingIsNeverCriticalEvenForAnSOneTemplate() {
        // a new, fleet-rare, spiking kern.panic-weight template: every kind holds, S = 1.0
        var rows = LayerBEvaluator.evaluate(input(30, true, List.of(day(PANIC, 1.0, "kern.panic", 400, 300)), Set.of(),
                Map.of()));
        FindingRow f = only(rows, PANIC);
        assertEquals("ATTENTION", f.severity());
        assertEquals(List.of("FLEET_RARE", "RATE_SPIKE", "NEW_ON_DEVICE"), f.layerB().kinds());
        assertEquals("FLEET_RARE", f.kind());
        assertEquals("B", f.layerB() == null ? "A" : "B");
        assertEquals(100.0, f.layerB().score(), 1e-9);
        String[] floored = LayerBEvaluator.floor(new String[] {"CRITICAL", "ANY"});
        assertEquals("ATTENTION", floored[0]);
        assertEquals("B_STATISTICAL_FLOOR", floored[1]);
    }

    @Test
    void aWindowWithAGapGivesNoRateOrSilenceFindingButNoveltyStillCounts() {
        Baseline periodic = new Baseline(PERIODIC, 1, 0, 1.0, 20, "crond: job <NUM> started", 0.3, null);
        Baseline common = new Baseline(COMMON, 1, 0, 1.0, 20, "x", 0.3, null);
        List<TemplateDay> templates = List.of(day(COMMON, 0.5, null, 500, 400), day(RARE, 0.8, "hw.edac.ce", 45, 12));
        var gap = LayerBEvaluator.evaluate(input(30, false, templates, Set.of(COMMON, PERIODIC), Map.of(COMMON, common, PERIODIC, periodic)));
        assertTrue(gap.stream().noneMatch(f -> f.layerB().kinds().contains("RATE_SPIKE") || f.layerB().kinds().contains("SILENCE")));
        assertEquals("GAP", only(gap, RARE).coverageState());
        assertTrue(only(gap, RARE).layerB().kinds().contains("NEW_ON_DEVICE"));
        assertTrue(only(gap, RARE).layerB().z() == null, "no rate judgement without full coverage");
        var full = LayerBEvaluator.evaluate(input(30, true, templates, Set.of(COMMON, PERIODIC), Map.of(COMMON, common, PERIODIC, periodic)));
        assertTrue(only(full, COMMON).layerB().kinds().contains("RATE_SPIKE"));
        assertEquals("ATTENTION", only(full, COMMON).severity());
        FindingRow silence = only(full, PERIODIC);
        assertEquals("SILENCE", silence.kind());
        assertEquals("INFO", silence.severity(), "not classed as periodic health output in catalog v1");
        assertEquals(0, silence.eventCount());
        assertTrue(silence.labels().contains("HA_ROLE_UNKNOWN"));
    }

    @Test
    void underSevenDaysNothingIsRaisedAndBelowFourteenOnlyNoveltyAndRarity() {
        List<TemplateDay> templates = List.of(day(COMMON, 0.5, null, 500, 400), day(RARE, 0.8, "hw.edac.ce", 45, 12));
        Map<String, Baseline> base = Map.of(COMMON, new Baseline(COMMON, 1, 0, 1.0, 5, "x", 0.5, null));
        assertTrue(LayerBEvaluator.evaluate(input(6, true, templates, Set.of(COMMON), base)).isEmpty());
        var learning = LayerBEvaluator.evaluate(input(10, true, templates, Set.of(COMMON), base));
        assertEquals(1, learning.size(), "the known common template's spike waits for the baseline");
        assertEquals(RARE, learning.get(0).layerB().templateId());
        assertTrue(learning.get(0).labels().contains("BASELINE_LEARNING"));
        assertEquals(3, LayerBEvaluator.evaluate(input(14, true, templates, Set.of(COMMON), base)).size() + 1,
                "from 14 days: the spike and the novelty (no periodic template here)");
    }

    @Test
    void novelFleetCommonTemplatesAreInfoAndLowWeightIsInfo() {
        var rows = LayerBEvaluator.evaluate(input(30, true, List.of(day(COMMON, 0.5, null, 3, 1), day(RARE, 0.15, null, 1, 1)),
                Set.of(), Map.of()));
        assertEquals("INFO", only(rows, COMMON).severity());
        assertEquals("B_NEW_ON_DEVICE_FLEET_COMMON", only(rows, COMMON).severityRule());
        assertEquals("INFO", only(rows, RARE).severity());
        assertEquals("B_WEIGHT_BELOW_0_3", only(rows, RARE).severityRule());
    }

    @Test
    void theSameInputGivesTheSameFindingsInTheSameOrder() {
        List<TemplateDay> templates = List.of(day(RARE, 0.8, "hw.edac.ce", 45, 12), day(COMMON, 0.5, null, 3, 1));
        var a = LayerBEvaluator.evaluate(input(30, true, templates, Set.of(), Map.of()));
        var b = LayerBEvaluator.evaluate(input(30, true, List.of(templates.get(1), templates.get(0)), Set.of(), Map.of()));
        assertEquals(a, b);
        assertEquals(RARE, a.get(0).layerB().templateId(), "highest score first");
        assertNotEquals(a.get(0).findingId(), a.get(1).findingId());
        assertTrue(a.get(0).findingId().matches("[0-9a-f]{32}"));
        assertEquals("tpl.cp_messages", a.get(0).markerId());
        assertEquals(RARE, a.get(0).keyToken());
    }

    @Test
    void markersOffWeighsMarkerTemplatesAtTheDefault() {
        assertEquals(0.3, LayerBEvaluator.weight(0.8, "hw.edac.ce", true), 1e-9);
        assertEquals(0.8, LayerBEvaluator.weight(0.8, "hw.edac.ce", false), 1e-9);
        assertEquals(0.5, LayerBEvaluator.weight(0.5, null, true), 1e-9);
        assertFalse(LayerBEvaluator.weight(0.0, null, false) < 0.05);
    }
}
