package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Settings;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TrainingChunk;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.logdetect.LogDetectionP2Service.PassSummary;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * Golden case G1 for layer B (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 4.5 and 6.2): a synthetic, masked
 * replica of the trigger case on the device-day of {@code FW-TANGO-04} -- 45 EDAC corrected-error lines on one slot
 * label within 4.5 hours, a machine-check line after each of two boots -- next to the fleet background and the strongest
 * plausible competitor (a rate spike of a one-device template). The whole path runs: the offline build reads the
 * stored, encrypted training chunks through the masked read path, a security_admin freezes the catalog, the P2 pass
 * indexes the day's chunk and evaluates it. Acceptance: the EDAC and machine-check templates rank in the top 3 of the
 * device-day from layers A and B alone (no DIMM card exists), neither is CRITICAL, and the "marker list off" rank is
 * recorded. Device ids are opaque test ids; the only host name is masked to {@code FW-TANGO-04}.
 */
class LayerBG1GoldenTest {
    static final LocalDate TRAIN_FROM = LocalDate.of(2026, 10, 9);
    static final int TRAIN_DAYS = 14;
    static final int DEVICES = 20;
    static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    static final String ACTOR = "actor-synthetic-security-admin";

    static final class Rig {
        final SystemLogReadFixture fixture;
        final InMemoryLogDetectionRepository p1 = new InMemoryLogDetectionRepository();
        final InMemoryLogTemplateRepository repo = new InMemoryLogTemplateRepository(p1);
        final HardwareMarkers markers = new HardwareMarkers(new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY));
        final LogTemplateNormaliser normaliser = new LogTemplateNormaliser();
        final LogTemplateIds ids = new LogTemplateIds(SystemLogReadFixture.PRIVACY_KEY);
        final LogKeyToken keyed = new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY);

        Rig(Path dir) throws Exception {
            fixture = new SystemLogReadFixture(dir);
            fixture.masking = new LogMasking(SystemLogReadFixture.PRIVACY_KEY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04")));
            p1.settings = new Settings(true, null, null, null, true);
        }

        LogCatalogService catalogs() {
            return new LogCatalogService(repo, fixture.service(), markers, normaliser, ids, keyed);
        }

        LogDetectionP2Service p2() {
            return new LogDetectionP2Service(p1, repo, fixture.service(), markers, normaliser, ids, keyed);
        }

        void storeTraining() throws Exception {
            LayerBTestPool pool = new LayerBTestPool(TRAIN_FROM, TRAIN_DAYS, DEVICES);
            for (int d = 0; d < TRAIN_DAYS; d++) {
                LocalDate day = TRAIN_FROM.plusDays(d);
                for (String device : pool.deviceIds()) {
                    String chunkId = "tr-" + device + "-" + day;
                    fixture.chunk(chunkId, SystemLogReadFixture.bytes(trainingText(device, day, d)));
                    repo.training.add(new TrainingChunk(chunkId, device, "cp_messages", day));
                }
            }
        }

        void storeDay(String chunkId, String device, String text, LocalDate day) throws Exception {
            fixture.chunk(chunkId, SystemLogReadFixture.bytes(text));
            repo.pending.add(new PendingChunk(chunkId, device, "cp_messages", "job-" + chunkId, day));
            p1.coverage.put(device + "|cp_messages|job-" + chunkId,
                    new CoverageRow(device, "cp_messages", "job-" + chunkId, day, "EVALUATED", "ALL_LAYERS", 40));
        }
    }

    static String trainingText(String device, LocalDate day, int d) {
        StringBuilder sb = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            LocalDateTime t = day.atTime(h, 5);
            line(sb, t, LayerBTestPool.SSHD);
            line(sb, t.plusMinutes(20), LayerBTestPool.CROND);
            if (device.equals(LayerBTestPool.TANGO)) line(sb, t.plusMinutes(30), LayerBTestPool.ROUTED);
        }
        int index = device.equals(LayerBTestPool.TANGO) ? 0 : Integer.parseInt(device.substring(4));
        if (d == 2 && index >= 1 && index <= 10) line(sb, day.atTime(3, 0), LayerBTestPool.BOOT);
        if (d == 3 && index == 5) line(sb, day.atTime(4, 0), LayerBTestPool.EDAC);
        return sb.toString();
    }

    /** The trigger day of FW-TANGO-04: background, two boots each followed by a machine check, 45 CE lines in 4.5 h, a competitor spike. */
    static String g1DayText() {
        List<String[]> lines = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            LocalDateTime t = DAY.atTime(h, 5);
            lines.add(new String[] {t.toString(), LayerBTestPool.SSHD});
            lines.add(new String[] {t.plusMinutes(20).toString(), LayerBTestPool.CROND});
            int routed = h == 15 ? 200 : 1;
            for (int i = 0; i < routed; i++) lines.add(new String[] {t.plusMinutes(30).plusSeconds(i % 60).toString(), LayerBTestPool.ROUTED});
        }
        for (LocalDateTime boot : List.of(DAY.atTime(2, 0), DAY.atTime(6, 30))) {
            lines.add(new String[] {boot.toString(), LayerBTestPool.BOOT});
            lines.add(new String[] {boot.plusMinutes(1).toString(), LayerBTestPool.MCE});
        }
        for (int i = 0; i < 45; i++) {
            lines.add(new String[] {DAY.atTime(2, 10).plusMinutes(6L * i).toString(), LayerBTestPool.EDAC});
        }
        lines.sort(Comparator.comparing(a -> a[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] l : lines) line(sb, LocalDateTime.parse(l[0]), l[1]);
        return sb.toString();
    }

    private static void line(StringBuilder sb, LocalDateTime t, String text) {
        sb.append(LayerBTestPool.raw(t, text)).append('\n');
    }

    private static Rig frozenRig(Path dir) throws Exception {
        Rig rig = new Rig(dir);
        rig.storeTraining();
        var built = rig.catalogs().build(ACTOR, TRAIN_FROM, TRAIN_FROM.plusDays(TRAIN_DAYS - 1L), LogCatalogBuilder.Params.defaults());
        assertTrue(built.stored(), built.reason());
        assertEquals("cat-1", built.catalogVersion());
        var frozen = rig.catalogs().freeze(ACTOR, "cat-1");
        assertTrue(frozen.frozen(), frozen.reason());
        return rig;
    }

    private static String titleOf(FindingRow f) {
        return f.layerB().templateText() == null ? f.layerB().templateId() : f.layerB().templateText();
    }

    @Test
    void theEdacAndMachineCheckTemplatesRankInTheTopThreeOfTheDeviceDayFromLayersAAndBAlone(@TempDir Path dir) throws Exception {
        Rig rig = frozenRig(dir);
        rig.storeDay("g1-day", LayerBTestPool.TANGO, g1DayText(), DAY);
        PassSummary pass = rig.p2().runOnce(10, Duration.ofMinutes(5));
        assertTrue(pass.ran(), pass.reason());
        assertEquals(1, pass.chunksIndexed());
        assertEquals("cat-1", pass.catalogVersion());

        List<FindingRow> day = rig.p1.findings.values().stream()
                .filter(f -> f.layerB() != null && f.deviceId().equals(LayerBTestPool.TANGO) && f.windowDay().equals(DAY))
                .sorted(Comparator.comparingDouble((FindingRow f) -> -f.layerB().score())).toList();
        String ranking = day.stream().map(f -> titleOf(f) + "=" + f.layerB().score()).collect(Collectors.joining("; "));
        assertTrue(day.size() >= 3, ranking);
        List<FindingRow> top3 = day.subList(0, 3);
        FindingRow edac = top3.stream().filter(f -> titleOf(f).contains("EDAC")).findFirst().orElseThrow(() -> new AssertionError(ranking));
        FindingRow mce = top3.stream().filter(f -> titleOf(f).contains("Hardware Error")).findFirst()
                .orElseThrow(() -> new AssertionError(ranking));

        // EDAC: a catalog template seen on one other gateway in the training window (n = 1 of N = 21): rare and new here
        assertEquals("ATTENTION", edac.severity());
        assertEquals(45, edac.eventCount());
        assertTrue(edac.layerB().kinds().containsAll(List.of("FLEET_RARE", "NEW_ON_DEVICE")), edac.layerB().kinds().toString());
        assertEquals(0.8, edac.severityWeight(), 1e-9);
        assertTrue(edac.layerB().score() >= 58, ranking);
        assertTrue(edac.layerB().templateId().startsWith("t"));
        assertEquals(1, edac.prevalenceDevices());
        assertEquals(21, edac.cohortDevices());
        assertTrue(edac.labels().contains("CATALOG_PREVALENCE"), "the rolling cohort is not yet large enough: the catalog's prevalence");
        // MCE: never seen in the training window: a provisional template, R = 1
        assertEquals("ATTENTION", mce.severity());
        assertEquals(2, mce.eventCount());
        assertTrue(mce.layerB().templateId().startsWith("p"));
        assertTrue(mce.labels().contains("PROVISIONAL_TEMPLATE"));
        assertEquals(1.0, mce.layerB().rarity(), 1e-9);
        assertTrue(mce.layerB().score() >= 58, ranking);

        // no statistical finding is CRITICAL; the background raised nothing; the competitor spike ranks below both
        assertTrue(day.stream().noneMatch(f -> "CRITICAL".equals(f.severity())), ranking);
        assertTrue(day.stream().noneMatch(f -> titleOf(f).contains("Accepted") || titleOf(f).contains("crond")), ranking);
        FindingRow competitor = day.stream().filter(f -> titleOf(f).contains("neighbor")).findFirst().orElseThrow(() -> new AssertionError(ranking));
        assertTrue(competitor.layerB().kinds().contains("RATE_SPIKE"), competitor.layerB().kinds().toString());
        assertTrue(competitor.labels().contains("CATALOG_BASELINE"));
        assertTrue(competitor.layerB().score() < Math.min(edac.layerB().score(), mce.layerB().score()), ranking);
        // the boot banner is fleet-common: new on this device, INFO, near the bottom
        FindingRow boot = day.stream().filter(f -> titleOf(f).contains("Linux version")).findFirst().orElseThrow(() -> new AssertionError(ranking));
        assertEquals("INFO", boot.severity());
        assertTrue(boot.layerB().score() <= 15, ranking);

        // the "marker list off" information run (design 4.5): recorded, not stored
        int before = rig.p1.findings.size();
        List<FindingRow> off = rig.p2().markersOffRun(LayerBTestPool.TANGO, "cp_messages", DAY);
        assertEquals(before, rig.p1.findings.size(), "the information run stores nothing");
        int edacRankOff = rank(off, "EDAC");
        int mceRankOff = rank(off, "Hardware Error");
        assertTrue(edacRankOff >= 1 && mceRankOff >= 1, "ranks recorded: EDAC " + edacRankOff + ", MCE " + mceRankOff);
        System.out.println("G1 layer B: rank with markers EDAC " + (rank(day, "EDAC")) + ", MCE " + rank(day, "Hardware Error")
                + "; marker list off EDAC " + edacRankOff + ", MCE " + mceRankOff);
    }

    private static int rank(List<FindingRow> rows, String fragment) {
        List<FindingRow> sorted = rows.stream().sorted(Comparator.comparingDouble((FindingRow f) -> -f.layerB().score())
                .thenComparing(f -> f.layerB().templateId())).toList();
        for (int i = 0; i < sorted.size(); i++) if (titleOf(sorted.get(i)).contains(fragment)) return i + 1;
        return -1;
    }

    @Test
    void reIndexingTheSameChunkUnderTheSameVersionsGivesTheSameCountsAndFindings(@TempDir Path dir) throws Exception {
        Rig rig = frozenRig(dir);
        rig.storeDay("g1-day", LayerBTestPool.TANGO, g1DayText(), DAY);
        rig.p2().runOnce(10, Duration.ofMinutes(5));
        var counts = List.copyOf(rig.repo.counts);
        var findings = Map.copyOf(rig.p1.findings);
        rig.repo.states.clear();
        PassSummary again = rig.p2().runOnce(10, Duration.ofMinutes(5));
        assertEquals(1, again.chunksIndexed());
        assertEquals(counts.stream().sorted(Comparator.comparing(c -> c.countId())).toList(),
                rig.repo.counts.stream().sorted(Comparator.comparing(c -> c.countId())).toList());
        assertEquals(findings, rig.p1.findings);
    }

    @Test
    void storedP2RowsHoldNoLineAndNoMaskedValue(@TempDir Path dir) throws Exception {
        Rig rig = frozenRig(dir);
        rig.storeDay("g1-day", LayerBTestPool.TANGO, g1DayText(), DAY);
        rig.p2().runOnce(10, Duration.ofMinutes(5));
        String stored = rig.repo.counts + " " + rig.repo.provisional + " " + rig.p1.findings + " " + rig.repo.templates;
        for (String value : List.of("edge-fw-01", "FW-TANGO-04", "192.0.2", "USR-0A1B2C3D4E", "0x4a1f2", "publickey")) {
            assertFalse(stored.contains(value), value);
        }
        assertNotNull(rig.repo.catalogs.get("cat-1").diffJson());
        assertTrue(rig.repo.catalogs.get("cat-1").diffJson().contains("\"added\""));
    }
}
