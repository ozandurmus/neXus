package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.DeviceStatRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TemplateRow;
import com.securityexpert.nexus.ui2.platform.logtemplate.TemplateMiner;

/** The offline catalog build (design 4.1 steps 3 and 4, 4.2, 6.3, 6.5): deterministic, bounded, masked, vocabulary form only. */
class LogCatalogBuilderTest {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 9);

    private static TemplateRow template(List<TemplateRow> rows, String fragment) {
        return rows.stream().filter(t -> t.templateText().contains(fragment)).findFirst().orElseThrow(() -> new AssertionError(fragment));
    }

    @Test
    void theSameLinesGiveTheSameCatalogIdsStatisticsAndDigest() {
        LayerBTestPool pool = new LayerBTestPool(FROM, 8, 12);
        var a = pool.build("cat-1");
        var b = pool.build("cat-1");
        assertEquals(a.catalog().digest(), b.catalog().digest());
        assertEquals(a.catalog().manifestJson(), b.catalog().manifestJson());
        assertEquals(a.templates(), b.templates());
        assertEquals(a.stats(), b.stats());
        assertEquals(a.windows(), b.windows());
        assertTrue(a.catalog().digest().matches("[0-9a-f]{64}"));
    }

    @Test
    void prevalenceCohortClassAndBaselineFollowSection42() {
        LayerBTestPool pool = new LayerBTestPool(FROM, 8, 12);
        var draft = pool.build("cat-1");
        TemplateRow sshd = template(draft.templates(), "Accepted");
        TemplateRow edac = template(draft.templates(), "EDAC");
        TemplateRow boot = template(draft.templates(), "Linux version");
        TemplateRow routed = template(draft.templates(), "neighbor");
        assertEquals(13, sshd.cohortDevices(), "12 devices and the device under test, each with 8 usable days");
        assertEquals(13, sshd.prevalenceDevices());
        assertEquals(1, edac.prevalenceDevices());
        assertEquals(10, boot.prevalenceDevices());
        assertEquals(1, routed.prevalenceDevices());
        assertEquals("MARKER", edac.templateClass());
        assertEquals("hw.edac.ce", edac.markerId());
        assertEquals(0.8, edac.severityWeight(), 1e-9);
        assertEquals("kern.boot", boot.markerId());
        assertEquals("UNCLASSED", sshd.templateClass());
        assertEquals(LogSeverity.DEFAULT_WEIGHT, sshd.severityWeight(), 1e-9);
        assertEquals(FROM.plusDays(3), edac.fleetFirstSeen());
        assertTrue(sshd.templateId().matches("t[0-9a-f]{31}"));
        DeviceStatRow tangoRouted = draft.stats().stream()
                .filter(s -> s.deviceId().equals(LayerBTestPool.TANGO) && s.templateId().equals(routed.templateId())).findFirst().orElseThrow();
        assertEquals(1.0, tangoRouted.baselineMedian(), 1e-9, "one line every hour");
        assertEquals(0.0, tangoRouted.baselineMad(), 1e-9);
        assertEquals(1.0, tangoRouted.periodicity(), 1e-9);
        assertEquals(8, tangoRouted.usableDays());
        assertEquals(8 * 24, tangoRouted.bucketsUsed());
    }

    @Test
    void storedFormsHoldNoMaskedValueAndNoWordOutsideTheVocabulary() {
        var draft = new LayerBTestPool(FROM, 8, 12).build("cat-1");
        for (TemplateRow t : draft.templates()) {
            for (String form : List.of(t.templateText(), t.matchForm())) {
                assertTrue(LogTemplateNormaliser.SAFE_CHARS.matcher(form).matches(), form);
                for (String value : List.of("TANGO", "FW-", "USR-", "192.0.2", "edge-fw", "0x4a1f2")) {
                    assertFalse(form.contains(value), value + " in " + form);
                }
            }
        }
        assertFalse(draft.catalog().manifestJson().contains("dev-"), "the manifest holds no device id");
    }

    @Test
    void gapDaysAreNotUsableBaselineDays() {
        LayerBTestPool pool = new LayerBTestPool(FROM, 8, 12);
        var b = new LogCatalogBuilder(new LogTemplateNormaliser(), new HardwareMarkers(new LogKeyToken(new byte[32])),
                new LogTemplateIds(new byte[32]), new LogKeyToken(new byte[32]), LogCatalogBuilder.Params.defaults(), FROM,
                FROM.plusDays(7), Map.of(LayerBTestPool.TANGO + "|cp_messages", java.util.Set.of(FROM, FROM.plusDays(1))));
        pool.trainingLines().forEach(b::accept);
        var tango = b.finish("cat-1").windows().stream().filter(w -> w.deviceId().equals(LayerBTestPool.TANGO)).findFirst().orElseThrow();
        assertEquals(6, tango.usableDays());
    }

    @Test
    void theBuildIsBoundedAndSaysSoInTheManifest() {
        LayerBTestPool pool = new LayerBTestPool(FROM, 8, 12);
        var b = pool.builder(new LogCatalogBuilder.Params(4, 0.4, 100, 20_000, 64, 100, 5_000_000));
        int accepted = 0;
        for (var l : pool.trainingLines()) if (b.accept(l)) accepted++;
        assertEquals(100, accepted);
        assertTrue(b.full());
        String manifest = b.finish("cat-1").catalog().manifestJson();
        assertTrue(manifest.contains("\"truncated\":true") && manifest.contains("MAX_LINES"), manifest);
    }

    @Test
    void aDifferentKeyGivesDifferentIdsForTheSameTemplates() {
        var ids1 = new LogTemplateIds(new byte[32]);
        var ids2 = new LogTemplateIds(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        List<String> tokens = List.of("kernel:", "EDAC", "MC<NUM>:");
        assertNotEquals(ids1.catalogId("cp_messages", tokens), ids2.catalogId("cp_messages", tokens));
        assertNotEquals(ids1.catalogId("cp_messages", tokens), ids1.catalogId("pan_system", tokens));
        assertTrue(ids1.provisionalId("cp_messages", tokens).startsWith("p"));
    }

    @Test
    void theDiffListsAddedRemovedAndTheIdMapping() {
        var first = new LayerBTestPool(FROM, 8, 12).build("cat-1");
        var second = new LayerBTestPool(FROM.plusDays(8), 8, 12, false).build("cat-2");
        Map<String, Object> self = LogCatalogDiff.diff(first.catalog(), first.templates(), first.catalog(), first.templates(),
                LogCatalogBuilder.Params.defaults().miner());
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) self.get("counts");
        assertEquals(0, counts.get("added"));
        assertEquals(0, counts.get("removed"));
        // the second window has no boot and no EDAC line: those two templates are removed, nothing is added
        Map<String, Object> d = LogCatalogDiff.diff(first.catalog(), first.templates(), second.catalog(), second.templates(),
                LogCatalogBuilder.Params.defaults().miner());
        @SuppressWarnings("unchecked")
        Map<String, Object> c2 = (Map<String, Object>) d.get("counts");
        assertEquals(2, c2.get("removed"), d.toString());
        assertEquals(0, c2.get("added"), d.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) d.get("mapping");
        assertEquals(first.templates().size() - 2, mapping.size());
        String json = LogCatalogDiff.json(null, List.of(), first.catalog(), first.templates(), TemplateMiner.Params.defaults());
        assertTrue(json.contains("\"added\""));
        assertFalse(json.contains("Accepted"), "the diff holds ids and counts, never template words");
    }
}
