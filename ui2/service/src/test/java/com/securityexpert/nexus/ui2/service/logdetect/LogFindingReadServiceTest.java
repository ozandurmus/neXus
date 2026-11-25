package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;

/** The chip, coverage, budget and acknowledgement answers: counts say what they count; no device is green by default. */
class LogFindingReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private final InMemoryLogDetectionRepository repo = new InMemoryLogDetectionRepository();
    private final LogFindingReadService service = new LogFindingReadService(repo, Clock.fixed(NOW, ZoneOffset.UTC));

    private FindingRow finding(String id, String device, String marker, String severity, String state) {
        return finding(id, device, marker, severity, state, DAY);
    }

    private FindingRow finding(String id, String device, String marker, String severity, String state, LocalDate day) {
        return new FindingRow(id, device, "cp_messages", "layer-a-severity", "p1-1", "MARKER", marker, "", day, day.atTime(1, 0), day.atTime(2, 0),
                severity, "WEIGHT_AT_LEAST_0_5", 0.8, "PROVISIONAL", 45, 10, 1, 0, 0, 0, 0, false, false, List.of(), "0123456789abcdef",
                "NOT_EVALUABLE", List.of(new Pointer("c1", 1, 2)), "logmask-v8", "markers-p1-1", "p1-config-1", state, null, null, NOW, NOW);
    }

    private void windows(String device, String... states) {
        int i = 0;
        for (String s : states) repo.coverage.put(device + i, new CoverageRow(device, "cp_messages", "job-" + device + i++, DAY, s, "R", 30));
    }

    @Test
    void theChipCountsOnlyAttentionAndAboveAndNamesTheWorst() {
        repo.findings.put("a".repeat(32), finding("a".repeat(32), "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN"));
        repo.findings.put("b".repeat(32), finding("b".repeat(32), "FW-TANGO-04", "hw.mce", "CRITICAL", "OPEN"));
        repo.findings.put("c".repeat(32), finding("c".repeat(32), "FW-TANGO-04", "kern.oops", "INFO", "OPEN"));
        windows("FW-TANGO-04", "EVALUATED");
        @SuppressWarnings("unchecked")
        var chips = (List<Map<String, Object>>) service.chips().body().get("chips");
        Map<String, Object> chip = chips.get(0);
        assertEquals("FINDINGS", chip.get("state"));
        assertEquals("CRITICAL", chip.get("severity"));
        assertEquals(2, chip.get("count"), "INFO is listed, not counted");
    }

    @Test
    void aDeviceWithoutFindingsIsGreenOnlyWhenEveryRecordedWindowWasEvaluated() {
        windows("FW-SIERRA-06", "EVALUATED", "EVALUATED", "EVALUATED", "EVALUATED");
        windows("FW-BRAVO-02", "EVALUATED", "GAP", "UNSUPPORTED");
        windows("FW-JULIET-06", "NOT_EVALUABLE");
        @SuppressWarnings("unchecked")
        var chips = (List<Map<String, Object>>) service.chips().body().get("chips");
        Map<String, Map<String, Object>> byDevice = new java.util.TreeMap<>();
        for (var c : chips) byDevice.put((String) c.get("device_id"), c);
        assertEquals("NO_FINDINGS", byDevice.get("FW-SIERRA-06").get("state"));
        assertEquals("No findings; 4 of 4 recorded windows evaluated", byDevice.get("FW-SIERRA-06").get("text"));
        assertEquals("UNKNOWN", byDevice.get("FW-BRAVO-02").get("state"));
        assertTrue(byDevice.get("FW-BRAVO-02").get("text").toString().contains("2 of 3 windows UNKNOWN"));
        assertEquals("NO_FINDINGS", byDevice.get("FW-JULIET-06").get("state"), "NOT_EVALUABLE counts as evaluated by layer A, with its reason in the coverage strip");
    }

    @Test
    void coverageListsEveryWindowAndTheStateCounts() {
        windows("FW-TANGO-04", "EVALUATED", "UNSUPPORTED");
        var body = service.coverage("FW-TANGO-04").body();
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) body.get("summary");
        assertEquals(2, summary.get("windows_total"));
        assertEquals(1, summary.get("windows_evaluated"));
        assertEquals(2, ((List<?>) body.get("windows")).size());
        assertEquals(400, service.coverage("../x").refusal().status());
    }

    @Test
    void budgetCountsAttentionAndAboveAgainstTheProvisionalNumbers() {
        windows("FW-TANGO-04", "EVALUATED");
        windows("FW-SIERRA-06", "EVALUATED");
        repo.findings.put("a".repeat(32), finding("a".repeat(32), "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN"));
        repo.findings.put("b".repeat(32), finding("b".repeat(32), "FW-TANGO-04", "hw.mce", "ATTENTION", "OPEN"));
        var body = service.budget().body();
        assertEquals(2, body.get("devices_with_coverage"));
        assertEquals(2, body.get("max_fleet_day"));
        assertEquals(true, body.get("evaluable"));
        assertEquals(true, body.get("within_budget"), "median 0, p95 2 <= 5, fleet day 2 <= 20");
    }

    @Test
    void budgetCountsTodayInTheLastWeekOfTheWindow() {
        windows("FW-TANGO-04", "EVALUATED");
        for (int i = 0; i < 6; i++) {
            String id = String.format("%032x", i + 1);
            repo.findings.put(id, finding(id, "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN")); // all on today (DAY)
        }
        var body = service.budget().body();
        assertEquals(6, body.get("p95_device_week"), "today's findings are in the device-week counts, not left out of the window");
        assertEquals(6, body.get("max_fleet_day"));
        assertEquals(false, body.get("within_budget"), "p95 6 > 5");
    }

    @Test
    void budgetWindowIsExactlyFourWeeksEndingToday() {
        windows("FW-TANGO-04", "EVALUATED");
        // 27 days ago is the first day of week 0; 28 days ago is outside the window.
        String inside = "a".repeat(32);
        String outside = "b".repeat(32);
        repo.findings.put(inside, finding(inside, "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN", DAY.minusDays(27)));
        repo.findings.put(outside, finding(outside, "FW-TANGO-04", "hw.mce", "ATTENTION", "OPEN", DAY.minusDays(28)));
        var body = service.budget().body();
        assertEquals(1, body.get("max_fleet_day"));
        assertEquals(1, body.get("p95_device_week"), "only the finding inside the four weeks is counted, in week 0");
    }

    @Test
    void chipsPageThroughEveryOpenFindingSoACountIsNeverSilentlyCut() {
        windows("FW-TANGO-04", "EVALUATED");
        for (int i = 0; i < 2500; i++) {
            String id = String.format("%032x", i + 1);
            repo.findings.put(id, finding(id, "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN"));
        }
        var body = service.chips().body();
        @SuppressWarnings("unchecked")
        var chips = (List<Map<String, Object>>) body.get("chips");
        assertEquals(2500, chips.get(0).get("count"), "all pages are read");
        assertEquals(false, body.get("truncated"));
    }

    @Test
    void chipsSayWhenTheReadBoundIsReached() {
        windows("FW-TANGO-04", "EVALUATED");
        int bound = LogFindingReadService.CHIP_MAX_PAGES * LogFindingReadService.CHIP_PAGE;
        for (int i = 0; i < bound + 1; i++) {
            String id = String.format("%032x", i + 1);
            repo.findings.put(id, finding(id, "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN"));
        }
        var body = service.chips().body();
        assertEquals(true, body.get("truncated"));
        assertTrue(body.get("truncated_note").toString().contains("partial"));
    }

    @Test
    void budgetWithoutCoverageIsNotEvaluableNeverWithinBudget() {
        var body = service.budget().body();
        assertEquals(false, body.get("evaluable"));
        assertEquals(false, body.get("within_budget"));
    }

    @Test
    void acknowledgeRecordsAndRefusesAnUnknownFindingOrAMissingActor() {
        String id = "a".repeat(32);
        repo.findings.put(id, finding(id, "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN"));
        var ok = service.acknowledge("actor", id, "known");
        assertTrue(ok.isOk());
        @SuppressWarnings("unchecked")
        var f = (Map<String, Object>) ok.body().get("finding");
        assertEquals("ACKNOWLEDGED", f.get("review_state"));
        assertEquals(404, service.acknowledge("actor", "f".repeat(32), null).refusal().status());
        assertEquals(403, service.acknowledge(null, id, null).refusal().status());
        assertEquals(400, service.acknowledge("actor", "nope", null).refusal().status());
        assertEquals(400, service.acknowledge("actor", id, "x".repeat(501)).refusal().status());
    }

    @Test
    void theFindingShapeCarriesNoLineTextAndSaysThatRarityAndChangeArriveLater() {
        String id = "a".repeat(32);
        repo.findings.put(id, finding(id, "FW-TANGO-04", "hw.edac.ce", "ATTENTION", "OPEN"));
        @SuppressWarnings("unchecked")
        var f = (Map<String, Object>) service.finding(id).body().get("finding");
        @SuppressWarnings("unchecked")
        var score = (Map<String, Object>) f.get("score");
        assertEquals(0.8, score.get("s"));
        assertNull(score.get("r"));
        assertNull(score.get("score"));
        assertEquals("DEVICE_LOCAL", f.get("time_basis"));
        assertFalse(f.toString().contains("memory read error"));
        assertEquals(1, ((List<?>) f.get("evidence_pointers")).size());
    }

    @Test
    void theSwitchValidatesItsBodyAndStartsTheShadowPeriodNow() {
        assertEquals(400, service.updateSettings("actor", Map.of()).refusal().status());
        assertEquals(400, service.updateSettings("actor", Map.of("enabled", "yes")).refusal().status());
        assertEquals(400, service.updateSettings("actor", Map.of("enabled", true, "shadow_days", 91)).refusal().status());
        assertEquals(400, service.updateSettings("actor", Map.of("enabled", true, "other", 1)).refusal().status());
        var ok = service.updateSettings("actor", Map.of("enabled", true, "shadow_days", 14));
        assertTrue(ok.isOk());
        assertEquals(true, ok.body().get("shadow"));
        assertEquals(NOW.plusSeconds(14L * 86400).toString(), ok.body().get("shadow_until"));
        assertEquals(403, service.updateSettings(null, Map.of("enabled", true)).refusal().status());
    }
}
