package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Settings;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.logdetect.LogDetectionP2Service.PassSummary;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * The P2 switch and the masking boundary (design sections 4.1 step 1, 10): while the switch is off nothing of P2 runs,
 * without a frozen catalog nothing is matched, a masking refusal processes nothing, and the switch is written by the same
 * action and audit path as P1 and cannot start P2 before the P1 shadow period has ended.
 */
class LogDetectionP2ServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private static LogMasking unavailableMasking() {
        return new LogMasking(SystemLogReadFixture.PRIVACY_KEY, () -> KnownNames.NONE, () -> {
            throw new IllegalStateException("database down");
        }, Duration.ofMinutes(1));
    }

    @Test
    void whileTheP2SwitchIsOffNothingOfP2Runs(@TempDir Path dir) throws Exception {
        for (Settings off : List.of(new Settings(true, null, null, null, false), new Settings(false, null, null, null, true),
                new Settings(false, null, null, null, false))) {
            LayerBG1GoldenTest.Rig rig = new LayerBG1GoldenTest.Rig(dir.resolve("r" + System.nanoTime()));
            rig.storeDay("g1-day", LayerBTestPool.TANGO, LayerBG1GoldenTest.g1DayText(), LayerBG1GoldenTest.DAY);
            rig.p1.settings = off;
            PassSummary pass = rig.p2().runOnce(10, Duration.ofMinutes(1));
            assertFalse(pass.ran());
            assertEquals("DISABLED", pass.reason());
            assertEquals(0, rig.repo.onlineCalls, "not even the pending list is read");
            assertTrue(rig.repo.counts.isEmpty());
            assertTrue(rig.p1.findings.isEmpty());
        }
    }

    @Test
    void withoutAFrozenCatalogNothingIsMatched(@TempDir Path dir) throws Exception {
        LayerBG1GoldenTest.Rig rig = new LayerBG1GoldenTest.Rig(dir);
        rig.storeTraining();
        var built = rig.catalogs().build("actor", LayerBG1GoldenTest.TRAIN_FROM, LayerBG1GoldenTest.TRAIN_FROM.plusDays(13),
                LogCatalogBuilder.Params.defaults());
        assertTrue(built.stored());
        rig.storeDay("g1-day", LayerBTestPool.TANGO, LayerBG1GoldenTest.g1DayText(), LayerBG1GoldenTest.DAY);
        PassSummary pass = rig.p2().runOnce(10, Duration.ofMinutes(1));
        assertEquals("NO_FROZEN_CATALOG", pass.reason(), "a DRAFT is never matched online");
        assertTrue(rig.repo.counts.isEmpty());
        assertTrue(rig.p2().markersOffRun(LayerBTestPool.TANGO, "cp_messages", LayerBG1GoldenTest.DAY).isEmpty());
        // a frozen version cannot be frozen again and stays the one matched
        assertTrue(rig.catalogs().freeze("actor", "cat-1").frozen());
        assertEquals("ALREADY_FROZEN", rig.catalogs().freeze("actor", "cat-1").reason());
        assertEquals("CATALOG_NOT_FOUND", rig.catalogs().freeze("actor", "cat-9").reason());
    }

    @Test
    void whenMaskingIsUnavailableNoChunkIsIndexedAndNoCatalogIsBuilt(@TempDir Path dir) throws Exception {
        LayerBG1GoldenTest.Rig rig = new LayerBG1GoldenTest.Rig(dir);
        rig.storeTraining();
        rig.fixture.masking = unavailableMasking();
        var built = rig.catalogs().build("actor", LayerBG1GoldenTest.TRAIN_FROM, LayerBG1GoldenTest.TRAIN_FROM.plusDays(13),
                LogCatalogBuilder.Params.defaults());
        assertFalse(built.stored());
        assertEquals("MASKING_UNAVAILABLE", built.reason());
        assertTrue(rig.repo.catalogs.isEmpty(), "never built from an unmasked or partial pool");
    }

    @Test
    void aMaskingRefusalOnlineRecordsTheChunkAndProcessesNothing(@TempDir Path dir) throws Exception {
        LayerBG1GoldenTest.Rig rig = new LayerBG1GoldenTest.Rig(dir);
        rig.storeTraining();
        rig.catalogs().build("actor", LayerBG1GoldenTest.TRAIN_FROM, LayerBG1GoldenTest.TRAIN_FROM.plusDays(13),
                LogCatalogBuilder.Params.defaults());
        rig.catalogs().freeze("actor", "cat-1");
        rig.storeDay("g1-day", LayerBTestPool.TANGO, LayerBG1GoldenTest.g1DayText(), LayerBG1GoldenTest.DAY);
        rig.fixture.masking = unavailableMasking();
        PassSummary pass = rig.p2().runOnce(10, Duration.ofMinutes(1));
        assertEquals(1, pass.chunksRefused());
        assertEquals("MASKING_UNAVAILABLE", rig.repo.states.get("g1-day|cat-1").status());
        assertTrue(rig.repo.counts.isEmpty());
        assertTrue(rig.p1.findings.isEmpty());
    }

    @Test
    void theP2SwitchUsesTheSameRouteAndCannotStartBeforeTheP1ShadowEnds() {
        InMemoryLogDetectionRepository repo = new InMemoryLogDetectionRepository();
        LogFindingReadService service = new LogFindingReadService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
        repo.settings = new Settings(false, null, null, null, false);
        assertEquals(false, service.settings().get("p2_enabled"));
        var needsP1 = service.updateSettings("actor", Map.of("p2_enabled", true));
        assertEquals(409, needsP1.refusal().status());
        assertEquals("LOG_DETECTION_P2_NEEDS_P1", needsP1.refusal().code());
        repo.settings = new Settings(true, NOW.plusSeconds(13L * 86400), null, null, false);
        var shadow = service.updateSettings("actor", Map.of("p2_enabled", true));
        assertEquals("LOG_DETECTION_P2_BEFORE_P1_EXIT", shadow.refusal().code());
        assertFalse(repo.settings.p2Enabled());
        assertEquals(400, service.updateSettings("actor", Map.of("p2_enabled", "yes")).refusal().status());
        // after the shadow period: the same write, one row
        repo.settings = new Settings(true, NOW.minusSeconds(60), null, null, false);
        var on = service.updateSettings("actor", Map.of("p2_enabled", true));
        assertTrue(on.isOk());
        assertEquals(true, on.body().get("p2_enabled"));
        assertEquals(true, on.body().get("p2_runs"));
        assertTrue(repo.settings.enabled(), "the P1 switch keeps its value");
        // a P1-only write keeps the P2 switch; switching P2 off is always allowed
        assertTrue(service.updateSettings("actor", Map.of("enabled", true)).isOk());
        assertTrue(repo.settings.p2Enabled());
        assertTrue(service.updateSettings("actor", Map.of("p2_enabled", false)).isOk());
        assertFalse(repo.settings.p2Enabled());
        assertEquals("P1", service.findings(null, null, null, 7, 10).body().get("phase"));
    }

    @Test
    void aLayerBFindingIsShownWithItsScoreFactorsAndVersions(@TempDir Path dir) throws Exception {
        LayerBG1GoldenTest.Rig rig = new LayerBG1GoldenTest.Rig(dir);
        rig.storeTraining();
        rig.catalogs().build("actor", LayerBG1GoldenTest.TRAIN_FROM, LayerBG1GoldenTest.TRAIN_FROM.plusDays(13),
                LogCatalogBuilder.Params.defaults());
        rig.catalogs().freeze("actor", "cat-1");
        rig.storeDay("g1-day", LayerBTestPool.TANGO, LayerBG1GoldenTest.g1DayText(), LayerBG1GoldenTest.DAY);
        rig.p2().runOnce(10, Duration.ofMinutes(1));
        FindingRow any = rig.p1.findings.values().stream().filter(f -> f.layerB() != null).findFirst().orElseThrow();
        Map<String, Object> shown = LogFindingReadService.finding(any);
        assertEquals("B", shown.get("layer"));
        assertEquals("P2", shown.get("phase"));
        @SuppressWarnings("unchecked")
        Map<String, Object> score = (Map<String, Object>) shown.get("score");
        assertTrue(score.get("score") instanceof Double && score.get("r") instanceof Double && score.get("c") instanceof Double);
        @SuppressWarnings("unchecked")
        Map<String, Object> versions = (Map<String, Object>) shown.get("versions");
        assertEquals("cat-1", versions.get("catalog"));
        assertEquals(LogTemplateNormaliser.VERSION, versions.get("normaliser"));
        // a layer-A row keeps its P1 shape
        FindingRow a = new FindingRow("0".repeat(32), "dev-1", "cp_messages", "layer-a-severity", "p1-1", "MARKER", "hw.mce", "",
                LayerBG1GoldenTest.DAY, LayerBG1GoldenTest.DAY.atTime(1, 0), LayerBG1GoldenTest.DAY.atTime(1, 0), "ATTENTION", "R",
                0.8, "PROVISIONAL", 1, 1, 30, 20, 0, 0, 0, false, false, List.of(), "0".repeat(16), "EVALUATED", List.of(),
                "logmask-v8", "markers-p1-1", "p1-config-1", "OPEN", null, null, null, null);
        Map<String, Object> shownA = LogFindingReadService.finding(a);
        assertEquals("A", shownA.get("layer"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scoreA = (Map<String, Object>) shownA.get("score");
        assertNull(scoreA.get("score"));
        assertFalse(shownA.containsKey("layer_b"));
    }

    @Test
    void p2IndexesNewestDaysFirstWithholdsVerdictWhileHistoryIsPendingAndReevaluatesOnceBackfilled(@TempDir Path dir)
            throws Exception {
        LayerBG1GoldenTest.Rig rig = new LayerBG1GoldenTest.Rig(dir);
        rig.storeTraining();
        rig.catalogs().build("actor", LayerBG1GoldenTest.TRAIN_FROM, LayerBG1GoldenTest.TRAIN_FROM.plusDays(13),
                LogCatalogBuilder.Params.defaults());
        rig.catalogs().freeze("actor", "cat-1");

        var priorDay = LayerBG1GoldenTest.DAY.minusDays(1);
        String novelLine = "ntpd[410]: clock step offset stabilized\n";
        String priorText = LayerBTestPool.raw(priorDay.atTime(3, 15), novelLine.strip());
        String currentText = LayerBG1GoldenTest.g1DayText()
                + LayerBTestPool.raw(LayerBG1GoldenTest.DAY.atTime(22, 15), novelLine.strip()) + "\n";

        // Insert older day first, then newer day: pendingChunks must offer DAY before DAY - 1.
        rig.storeDay("g1-prior", LayerBTestPool.TANGO, priorText + "\n", priorDay);
        rig.storeDay("g1-day", LayerBTestPool.TANGO, currentText, LayerBG1GoldenTest.DAY);

        LogDetectionP2Service p2 = rig.p2();
        PassSummary first = p2.runOnce(1, Duration.ofMinutes(1));
        assertEquals(1, first.chunksIndexed());
        assertTrue(rig.repo.states.containsKey("g1-day|cat-1"), "newest log day is indexed first");
        assertFalse(rig.repo.states.containsKey("g1-prior|cat-1"));
        assertTrue(rig.p1.findings.isEmpty(),
                "statistical findings on DAY wait until unprocessed history in [DAY - 28, DAY) is indexed");

        PassSummary second = p2.runOnce(1, Duration.ofMinutes(1));
        assertEquals(1, second.chunksIndexed());
        assertTrue(rig.repo.states.containsKey("g1-prior|cat-1"));

        List<FindingRow> priorFindings = rig.p1.findings.values().stream()
                .filter(f -> f.layerB() != null && f.windowDay().equals(priorDay)).toList();
        List<FindingRow> dayFindings = rig.p1.findings.values().stream()
                .filter(f -> f.layerB() != null && f.windowDay().equals(LayerBG1GoldenTest.DAY)).toList();
        assertTrue(priorFindings.stream().anyMatch(f -> f.layerB().kinds().contains("NEW_ON_DEVICE")
                && f.layerB().templateText().contains("ntpd")), "the novel template is NEW_ON_DEVICE on DAY - 1");
        assertTrue(dayFindings.stream().noneMatch(f -> f.layerB().templateText().contains("ntpd")),
                "after DAY - 1 is indexed, DAY is re-evaluated and does not duplicate NEW_ON_DEVICE for an unchanged line");
        assertTrue(dayFindings.stream().anyMatch(f -> f.layerB().templateText().contains("EDAC")),
                "DAY is re-evaluated once history completes and raises its EDAC finding");
    }
}
