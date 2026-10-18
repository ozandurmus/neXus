package com.securityexpert.nexus.ui2.packetanalysis.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.Finding;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.Stat;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.WindowMode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The expert report on synthetic cases only: the frozen engine fixtures run through the engine, and the end-to-end
 * fixtures' stored reports. Negative and degenerate cases first.
 */
class ExpertReportTest {
    private static final String ONSET_FIXTURE = "det_a_onset_loss";
    private static final List<String> SHEETS = List.of("Özet", "Zaman Çizelgesi", "Hop Analizi", "Örnek Olaylar", "Yöntem ve Veri");

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------------------------------ refusals

    @Test
    void aCaseWithoutAnalysisIsRefused() throws Exception {
        Path db = tmp.resolve("empty.duckdb");
        try (Db empty = new Db(Engine.open(db, 1))) {
            empty.exec("CREATE TABLE settings(key VARCHAR PRIMARY KEY, value VARCHAR)");
        }
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ExpertReport.generate(db, ExpertReportOptions.defaults(), 1));
        assertTrue(ex.getMessage().contains("Run analysis"));
    }

    @Test
    void optionsRefuseAnInvertedWindowAnEndWithoutStartAndAnOutOfRangeTopCount() {
        assertThrows(IllegalArgumentException.class, () -> new ExpertReportOptions(10.0, 5.0, 25, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExpertReportOptions(null, 5.0, 25, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExpertReportOptions(Double.NaN, null, 25, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExpertReportOptions(null, null, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExpertReportOptions(null, null, 101, null, null));
    }

    @Test
    void anExplicitWindowOutsideTheAnalysedRangeIsRefused() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "w.duckdb", 1);
        assertThrows(IllegalArgumentException.class, () -> ExpertReport.generate(db,
                ExpertReportOptions.defaults().withWindow(1800000000.0, null), 1));
        assertThrows(IllegalArgumentException.class, () -> ExpertReport.generate(db,
                ExpertReportOptions.defaults().withWindow(1600000000.0, 1600000100.0), 1));
    }

    @Test
    void aReportWithoutTheDetailTablesDegradesToOneLinePerMissingSection() throws Exception {
        // the chain_loss end-to-end fixture's stored report in an otherwise empty database: no obs, cycle or segment tables
        JsonNode stored = gz("/packetanalysis/e2e/chain_loss/expected-report.json.gz");
        Path db = tmp.resolve("report-only.duckdb");
        Map<String, Object> topology = new LinkedHashMap<>();
        List<Object> points = new ArrayList<>();
        List<Object> forward = new ArrayList<>();
        for (JsonNode p : stored.get("auto_order_suggestion").get("points")) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", p.asText());
            point.put("label", "Nokta " + p.asText());
            point.put("device", "Cihaz " + p.asText());
            point.put("capture_id", "<capture:" + p.asText() + ">");
            points.add(point);
            forward.add(p.asText());
        }
        topology.put("points", points);
        topology.put("forward", forward);
        topology.put("reverse", List.of());
        try (Db plain = new Db(Engine.open(db, 1))) {
            plain.exec("CREATE TABLE settings(key VARCHAR PRIMARY KEY, value VARCHAR)");
            plain.exec("INSERT INTO settings VALUES ('report', ?)", stored.toString());
            plain.exec("INSERT INTO settings VALUES ('topology', ?)", PyJsonWriter.dumps(topology, false));
        }
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        assertEquals(SHEETS, sheetNames(xlsx));
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertEquals(WindowMode.NONE, data.window.mode());
            assertFalse(data.absent.isEmpty(), "missing sections say so");
            assertTrue(data.findings.stream().anyMatch(f -> f.id().equals("window")));
            assertTrue(data.findings.stream().noneMatch(f -> f.id().startsWith("hop") || f.id().equals("rtt")));
        }
    }

    // ------------------------------------------------------------------------------------------ window selection

    @Test
    void theDefaultWindowSplitsAtTheEnginesFirstOnsetAndEveryHopCountMatchesTheEngine() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "o.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertEquals(WindowMode.ONSET, data.window.mode());
            assertEquals(1700000012.0, data.window.split());
            assertEquals(2, data.window.periods().size());
            assertEquals("Önce", data.window.periods().get(0).label());
            assertEquals("Sonra", data.window.periods().get(1).label());
            // the matched packets before and after add up to what the engine stored for the hop
            Map<String, Object> report = report(read);
            for (Object s : (List<?>) report.get("segments")) {
                Map<?, ?> segment = (Map<?, ?>) s;
                Stat[] stats = data.hopTransit.get((String) segment.get("id"));
                assertNotNull(stats);
                assertEquals(((Number) segment.get("matched")).longValue(), stats[0].n() + stats[1].n(), segment.get("id").toString());
            }
            // the engine's loss events fall into the period after the onset
            assertEquals(0L, data.lossByKind.get("recovered_loss")[0]);
            assertEquals(94L, data.lossByKind.get("recovered_loss")[1]);
            assertTrue(data.findings.stream().anyMatch(f -> f.id().equals("window") && f.text().contains("ilk olay başlangıcını")));
        }
    }

    @Test
    void aCaseWithoutAnOnsetStatesThatNoEventWindowWasFoundAndReportsTheWholeCapture() throws Exception {
        Path db = ExpertReportFixtures.analysed("healthy", tmp, "h.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertEquals(WindowMode.NONE, data.window.mode());
            assertEquals(1, data.window.periods().size());
            assertEquals("Tüm yakalama", data.window.periods().get(0).label());
            Finding window = data.findings.get(0);
            assertEquals("window", window.id());
            assertTrue(window.text().contains("bir olay penceresi bulamadı"), window.text());
            assertTrue(window.text().contains("tüm yakalama"), window.text());
            Stat[] firstHop = data.hopTransit.get(data.segments.get(0).id());
            assertTrue(firstHop[0].has(), "whole-capture figures are still computed");
        }
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        String summary = sheetText(xlsx, "Özet");
        assertTrue(summary.contains("Tüm yakalama"));
        assertFalse(summary.contains("Önce ("), "no before/after table without a window");
    }

    @Test
    void anExplicitWindowReplacesTheOnsetAndSplitsAtItsStart() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "e.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read,
                    ExpertReportOptions.defaults().withWindow(1700000020.0, 1700000030.0));
            assertEquals(WindowMode.EXPLICIT, data.window.mode());
            assertEquals(1700000020.0, data.window.split());
            assertEquals(1700000030.0, data.window.periods().get(1).end());
            // the engine's events are counted by the same boundaries: before 20 s, and within [20 s, 30 s)
            long before = read.count("SELECT count(*) FROM events WHERE kind='recovered_loss' AND ts>=? AND ts<?",
                    data.window.periods().get(0).start(), 1700000020.0);
            long within = read.count("SELECT count(*) FROM events WHERE kind='recovered_loss' AND ts>=1700000020 AND ts<1700000030");
            assertEquals(before, data.lossByKind.get("recovered_loss")[0]);
            assertEquals(within, data.lossByKind.get("recovered_loss")[1]);
            assertTrue(before > 0 && within > 0 && before + within < 94, "events after the window belong to neither period");
            // the 10 s window holds fewer first-point packets than the 29 s the onset gave
            ExpertReportData byOnset = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertTrue(data.pointPackets.get(0).packets()[1] < byOnset.pointPackets.get(0).packets()[1]);
            assertTrue(data.findings.get(0).text().contains("verilen pencereye göre"));
        }
    }

    // ------------------------------------------------------------------------------------------ templates

    @Test
    void findingsAreFixedTemplatesEmittedOnlyWhenTheirDataExists() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "f.duckdb", 1);
        List<String> full;
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            full = ids(ExpertReportBuilder.build(read, ExpertReportOptions.defaults()));
        }
        assertEquals(List.of("window", "hop-delta", "hop-mark", "hop-forward", "hop-reverse", "rtt", "rtt-first", "cycles", "match",
                "loss", "retransmission", "rate", "clock", "longest", "ladder"), full);
        // remove the cycle tables and the flow summary: the templates that need them disappear, the others stay
        try (Db write = new Db(Engine.open(db, 1))) {
            write.exec("DROP VIEW cycle_details");
            write.exec("DROP VIEW cycle_flow_detail");
            for (String table : List.of("cycle_points", "cycle_pairs", "cycle_top_points", "flow_summary")) {
                write.exec("DROP TABLE " + table);
            }
        }
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            List<String> reduced = ids(data);
            assertFalse(reduced.contains("rtt"));
            assertFalse(reduced.contains("rtt-first"));
            assertFalse(reduced.contains("hop-delta"), "no cycle tables, no hop component figures");
            assertFalse(reduced.contains("cycles"));
            assertFalse(reduced.contains("longest"));
            assertFalse(reduced.contains("ladder"));
            assertTrue(reduced.containsAll(List.of("window", "hop-forward", "match", "loss", "retransmission")));
            assertTrue(data.absent.containsKey("top"));
            assertTrue(data.absent.containsKey("ladder"));
            String sample = sheetText(ExpertReport.generate(db, ExpertReportOptions.defaults(), 1), "Örnek Olaylar");
            assertTrue(sample.contains("Bu analizde istek/cevap döngüsü hesaplanmadı."), sample);
            assertTrue(sample.contains("Retransmission merdiveni için akış verisi yok."), sample);
        }
    }

    @Test
    void findingTextCarriesComputedValuesInTurkishNumberFormat() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "t.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            String all = String.join("\n", data.findings.stream().map(Finding::text).toList());
            assertTrue(all.contains("toplam 0 → 94"), all);
            assertTrue(all.contains("%") && all.contains(","), "Turkish decimal comma");
            assertFalse(all.matches("(?s).*\\d\\.\\d{3}\\s*ms.*"), "no decimal point in finding numbers");
        }
    }

    @Test
    void theOnlyWordsOnAHopAreTheApprovedMarkersAndTheColourScalesKeepNoFixedThreshold() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "v.duckdb", 1);
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        String text = String.join("\n", sheetNames(xlsx).stream().map(n -> sheetText(xlsx, n)).toList()).toLowerCase(new Locale("tr"));
        for (String forbidden : List.of("▲", "etken değil", "sorunlu", "suspect", "rapor bir hüküm veya sınıflandırma içermez")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        assertTrue(text.contains("hop işaretleri (temiz / gecikme burada) yalnız yöntem sayfasındaki sabit eşiklerle üretilir; kök neden, "
                + "kayıp veya yeniden iletim hükmü değildir."), "the reworded limitation");
        // the decision column holds the four approved words only
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            XSSFSheet hop = wb.getSheet("Hop Analizi");
            int header = rowOf(hop, "Hop", "Tür", "Karar");
            List<String> words = new ArrayList<>();
            for (int r = header + 1; hop.getRow(r) != null && hop.getRow(r).getCell(2) != null
                    && hop.getRow(r).getCell(2).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING; r++) {
                words.add(hop.getRow(r).getCell(2).getStringCellValue());
            }
            assertEquals(List.of("temiz", "gecikme burada", "belirsiz", "belirsiz"), words);
        }
        // colour scales use the data's own minimum and maximum only
        for (String sheet : List.of("xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml")) {
            String xml = part(xlsx, sheet);
            assertFalse(xml.contains("cfvo type=\"num\""), "no fixed numeric thresholds in " + sheet);
        }
    }

    // ------------------------------------------------------------------------------------------ hop markers

    private static List<HopVerdict.Mark> marks(ExpertReportData data) {
        return data.hopMarks.stream().map(ExpertReportData.HopMark::mark).toList();
    }

    @Test
    void eachHopOfTheOnsetFixtureGetsTheMarkerOfTheApprovedRule() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "mk.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            // P1 -> P2 recovers the injected loss by retransmission: its request RX component grows by 13 ms for a third
            // of the cycles (a delay at the hop, not a root cause); the hops behind it have 5 and 7 ms clock uncertainty
            assertEquals(List.of(HopVerdict.Mark.CLEAN, HopVerdict.Mark.DELAY, HopVerdict.Mark.UNDECIDED, HopVerdict.Mark.UNDECIDED),
                    marks(data));
            ExpertReportData.HopMark delayed = data.hopMarks.get(1);
            assertEquals("p1", delayed.pointA());
            assertEquals("p2", delayed.pointB());
            assertTrue(delayed.reason().startsWith("gecikme burada (istek RX bileşeni): ortalama 1,00 → 14,38 ms (+13,38 ms, 14×); "
                    + "önceki ortancadan 5 ms fazla geç kalan paketler önce %0,0, sonra %33,5 (94 paket); örnek sayısı 119 / 281; "
                    + "hop saat belirsizliği ±3,00 ms."), delayed.reason());
            assertTrue(data.hopMarks.get(0).reason().startsWith("temiz (ileri ve geri yön geçiş süresi, istek RX ve cevap TX bileşeni)"),
                    data.hopMarks.get(0).reason());
            assertTrue(data.hopMarks.get(2).reason().startsWith("belirsiz: saat belirsizliği ±5,0 ms; 2× = 10,0 ms"),
                    data.hopMarks.get(2).reason());
            String finding = data.findings.stream().filter(f -> f.id().equals("hop-mark")).findFirst().orElseThrow().text();
            assertTrue(finding.contains("önce 12,0 sn / sonra 28,5 sn): gecikme burada: Point 1 → Point 2; temiz: Point 0 → Point 1; "
                    + "işaretsiz (belirsiz veya ölçüm yok): Point 2 → Point 3, Point 3 → Point 4."), finding);
        }
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            // the strip: the word under the arrow between the two devices, nothing under a belirsiz hop
            XSSFSheet summary = wb.getSheet("Özet");
            int boxes = rowOf(summary, "Zincir ve kayıt noktaları") + 1;
            org.apache.poi.ss.usermodel.Row markers = summary.getRow(boxes + 2);
            assertEquals("temiz", markers.getCell(2).getStringCellValue());
            assertEquals("gecikme burada", markers.getCell(4).getStringCellValue());
            assertTrue(markers.getCell(6) == null || markers.getCell(6).getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK);
            assertTrue(summary.getRow(boxes + 3).getCell(1).getStringCellValue()
                    .startsWith("Hop işaretleri: Pencere: önce 12,0 sn / sonra 28,5 sn."));
            // the hop sheet: Karar, Gerekçe and the window on every row
            XSSFSheet hop = wb.getSheet("Hop Analizi");
            int header = rowOf(hop, "Hop", "Tür", "Karar");
            assertEquals("Gerekçe", hop.getRow(header).getCell(3).getStringCellValue());
            assertEquals("Pencere", hop.getRow(header).getCell(11).getStringCellValue());
            assertEquals("Point 1 → Point 2", hop.getRow(header + 2).getCell(0).getStringCellValue());
            assertTrue(hop.getRow(header + 2).getCell(3).getStringCellValue().startsWith("gecikme burada (istek RX bileşeni)"));
            assertEquals("Pencere: önce 12,0 sn / sonra 28,5 sn", hop.getRow(header + 2).getCell(11).getStringCellValue());
            // the method sheet: the six values and the rule sentence
            String method = sheetText(xlsx, "Yöntem ve Veri");
            assertTrue(method.contains("C. Hop işaretleri: sabit eşikler (PO kararı 2026-10-18)"), method);
            for (String value : List.of("5 ms", "2×", "10", "1 puan", "1 ms")) {
                assertTrue(method.contains(value), value);
            }
            assertTrue(method.contains("\"gecikme burada\" = ortalama geçiş süresi en az 5 ms ve 2 kat arttı"), method);
        }
    }

    /**
     * Makes forward packets that left point {@code a} at or after {@code from} arrive {@code seconds} later at every point
     * behind it (the engine's own {@code obs} rows), so only the hop {@code a -> next} sees the change; {@code limit}
     * packets at most, the earliest first.
     */
    private static void delayBehind(Path db, String a, List<String> behind, double from, double seconds, int limit) throws Exception {
        String marks = String.join(",", java.util.Collections.nCopies(behind.size(), "?"));
        List<Object> params = new ArrayList<>(List.of(seconds));
        params.addAll(behind);
        params.addAll(List.of(a, from, (long) limit));
        try (Db write = new Db(Engine.open(db, 1))) {
            write.exec("UPDATE obs SET corrected=corrected+? WHERE direction='forward' AND point IN (" + marks + ") AND packet_key IN ("
                    + "SELECT packet_key FROM obs WHERE point=? AND direction='forward' AND corrected>=? ORDER BY corrected,frame LIMIT ?)",
                    params.toArray());
        }
    }

    @Test
    void aStepDelayOnOneHopIsMarkedThereWhileAFewHugeSpikesOnAnotherAreNot() throws Exception {
        Path db = ExpertReportFixtures.analysed("det_a_security", tmp, "step.duckdb", 1);
        double split;
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            split = data.window.split();
            assertEquals(List.of(HopVerdict.Mark.CLEAN, HopVerdict.Mark.CLEAN, HopVerdict.Mark.CLEAN, HopVerdict.Mark.CLEAN,
                    HopVerdict.Mark.UNDECIDED), marks(data), "before the change");
        }
        // every later packet 20 ms slower between FW1 and FW2; three later packets 500 ms late between the client and FW1
        delayBehind(db, "fw1_out", List.of("fw2_in", "fw2_out", "server"), split, 0.020, 1_000_000);
        delayBehind(db, "client", List.of("fw1_in", "fw1_out", "fw2_in", "fw2_out", "server"), split, 0.500, 3);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertEquals(List.of(HopVerdict.Mark.UNDECIDED, HopVerdict.Mark.CLEAN, HopVerdict.Mark.DELAY, HopVerdict.Mark.CLEAN,
                    HopVerdict.Mark.UNDECIDED), marks(data));
            assertTrue(data.hopMarks.get(2).reason().startsWith("gecikme burada (ileri yön geçiş süresi): ortalama 0,30 → 20,30 ms"),
                    data.hopMarks.get(2).reason());
            String spikes = data.hopMarks.get(0).reason();
            assertTrue(spikes.startsWith("belirsiz: ortalama +6,00 ms arttı ama yalnız 3 paket"), spikes);
            assertTrue(spikes.contains("en az 10 paket ve payın 1 puan artması gerekir."), spikes);
        }
        byte[] a = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        assertArrayEquals(a, ExpertReport.generate(db, ExpertReportOptions.defaults(), 4), "medians and late counts at threads 1 and 4");
    }

    @Test
    void withoutABeforeAfterSplitNoHopGetsAWord() throws Exception {
        Path db = ExpertReportFixtures.analysed("healthy", tmp, "nw.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertEquals(4, data.hopMarks.size());
            for (ExpertReportData.HopMark m : data.hopMarks) {
                assertEquals(HopVerdict.Mark.UNDECIDED, m.mark());
                assertEquals("belirsiz: önce/sonra karşılaştırması yok (pencere verilmedi, motor olay başlangıcı bulamadı).", m.reason());
            }
            assertFalse(ids(data).contains("hop-mark"));
        }
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        String summary = sheetText(xlsx, "Özet");
        assertFalse(summary.contains("temiz") || summary.contains("gecikme burada") || summary.contains("Hop işaretleri:"), summary);
        // an explicit window gives the same hops a comparison
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults().withWindow(1700000010.0, null));
            assertTrue(data.hopMarks.stream().noneMatch(m -> m.reason().startsWith("belirsiz: önce/sonra karşılaştırması yok")));
        }
    }

    // ------------------------------------------------------------------------------------------ workbook

    @Test
    void theWorkbookHasTheFiveSheetsInOrderWithNativeChartsAndNoFormulas() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "x.duckdb", 1);
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        assertEquals(SHEETS, sheetNames(xlsx));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (int i = 0; i < SHEETS.size(); i++) {
                assertFalse(wb.isSheetHidden(i));
            }
            assertEquals(5, wb.getSheet("Özet").getDrawingPatriarch().getCharts().size(), "two hop charts and three aligned timelines");
            assertEquals(2, wb.getSheet("Hop Analizi").getDrawingPatriarch().getCharts().size());
            assertEquals(2, wb.getSheet("Örnek Olaylar").getDrawingPatriarch().getCharts().size());
            assertEquals(0, wb.getSheet("Yöntem ve Veri").getDrawingPatriarch() == null ? 0
                    : wb.getSheet("Yöntem ve Veri").getDrawingPatriarch().getCharts().size());
            XSSFSheet timeline = wb.getSheet("Zaman Çizelgesi");
            assertEquals("Saat (UTC)", timeline.getRow(3).getCell(0).getStringCellValue());
            assertEquals("Dönem", timeline.getRow(3).getCell(1).getStringCellValue(), "a UTC report has no second, identical UTC column");
            assertEquals(41, timeline.getLastRowNum() - 3, "one row per second bucket");
            assertEquals("Önce", timeline.getRow(5).getCell(1).getStringCellValue());
        }
        for (String sheet : List.of("sheet1", "sheet2", "sheet3", "sheet4", "sheet5")) {
            assertFalse(part(xlsx, "xl/worksheets/" + sheet + ".xml").contains("<f>"), "values are constants, never formulas");
        }
        // the three timeline charts share one plot-area layout so their time axes line up
        String first = part(xlsx, "xl/charts/chart7.xml");
        assertTrue(first.contains("<c:manualLayout>") && first.contains("<c:x val=\"0.07\"/>"));
        // the reference palette
        assertTrue(part(xlsx, "xl/styles.xml").contains("Arial"));
        assertTrue(part(xlsx, "xl/charts/chart1.xml").contains("2A78D6") || part(xlsx, "xl/charts/chart5.xml").contains("2A78D6"));
    }

    @Test
    void theChainStripListsTheDevicesAndPointsInPathOrder() throws Exception {
        Path db = ExpertReportFixtures.analysed("healthy", tmp, "c.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertEquals(List.of("Client", "FW", "LB ingress", "Server"), data.chain.stream().map(ExpertReportData.Box::device).toList());
            assertEquals(List.of("P2 giriş", "P3 çıkış"), data.chain.get(1).captions(), "two points of one device share one box");
        }
    }

    @Test
    void aHopBetweenTwoPointsOfOneDeviceIsMarkedAsResidenceInsideTheDevice() throws Exception {
        Path db = ExpertReportFixtures.analysed("det_a_security", tmp, "s.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            assertEquals(List.of("Client", "FW1", "FW2", "Server"), data.chain.stream().map(ExpertReportData.Box::device).toList());
            ExpertReportData.Seg inside = data.segments.stream().filter(s -> s.id().equals("forward:fw1_in:fw1_out")).findFirst().orElseThrow();
            assertTrue(inside.deviceHop());
            assertFalse(data.segments.stream().filter(s -> s.id().equals("forward:client:fw1_in")).findFirst().orElseThrow().deviceHop());
            String hop = data.findings.stream().filter(f -> f.id().equals("hop-forward")).findFirst().orElseThrow().text();
            assertTrue(hop.contains("(cihaz içi)"), hop);
        }
    }

    @Test
    void everyFixtureTopologyProducesAWorkbookWithTheFiveSheets() throws Exception {
        for (String fixture : List.of("detb_proxy_zero_ipv4", "offload", "nat", "duplicate", "det_a_syn_blocked")) {
            Path db = ExpertReportFixtures.analysed(fixture, tmp, fixture + ".duckdb", 1);
            byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
            assertEquals(SHEETS, sheetNames(xlsx), fixture);
            assertTrue(sheetText(xlsx, "Özet").contains("Bulgular"), fixture);
        }
    }

    // ------------------------------------------------------------------------------------------ number formats

    /** Every numeric cell of every sheet carries an explicit number format (never General), in the workbook of every shape. */
    @Test
    void everyNumericCellOfTheWorkbookHasAnExplicitNumberFormat() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "nf.duckdb", 1);
        assertNoGeneralNumbers(ExpertReport.generate(db, ExpertReportOptions.defaults(), 1), "onset");
        assertNoGeneralNumbers(ExpertReport.generate(db, ExpertReportOptions.defaults().withWindow(1700000020.0, 1700000030.0), 1),
                "explicit window");
        assertNoGeneralNumbers(ExpertReport.generate(db, ExpertReportOptions.defaults().withMasker(pseudonyms()), 1), "masked");
        Path healthy = ExpertReportFixtures.analysed("healthy", tmp, "nfh.duckdb", 1);
        assertNoGeneralNumbers(ExpertReport.generate(healthy, ExpertReportOptions.defaults(), 1), "no window");
    }

    static void assertNoGeneralNumbers(byte[] xlsx, String what) throws Exception {
        List<String> offenders = new ArrayList<>();
        int numeric = 0;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (org.apache.poi.ss.usermodel.Sheet sheet : wb) {
                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                            continue;
                        }
                        numeric++;
                        String format = cell.getCellStyle().getDataFormatString();
                        if (format == null || format.equalsIgnoreCase("General")) {
                            offenders.add(sheet.getSheetName() + "!" + cell.getAddress().formatAsString() + "=" + cell.getNumericCellValue());
                        }
                    }
                }
            }
        }
        assertTrue(numeric > 0, what + ": the walk saw numeric cells");
        assertTrue(offenders.isEmpty(), what + ": numeric cells in General format: "
                + offenders.subList(0, Math.min(15, offenders.size())) + " (" + offenders.size() + " in all)");
    }

    @Test
    void numberFormatsFollowTheKindOfValueAndTheMillisecondColumnsDropDecimalsOnlyWhenEveryValueIsAtLeastOneHundred() throws Exception {
        assertEquals("0.000", XlsxStyle.ms(java.util.Arrays.asList(3.5, 150.0)));
        assertEquals("0.0", XlsxStyle.ms(java.util.Arrays.asList(150.0, null, -300.0)));
        assertEquals("0.000", XlsxStyle.ms(java.util.Arrays.asList(null, null)), "an empty column keeps the millisecond default");
        assertEquals("+0.000;-0.000;0.000", XlsxStyle.signed("0.000"));
        assertEquals("+#,##0;-#,##0;0", XlsxStyle.signed("#,##0"));
        assertEquals("+0.0%;-0.0%;0.0%", XlsxStyle.signed("0.0%"));
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "kind.duckdb", 1);
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            XSSFSheet hop = wb.getSheet("Hop Analizi");
            // A: matched packets are counts, means are milliseconds, the before-after difference is signed milliseconds
            int header = rowOf(hop, "Hop", "Yön", "Tür");
            org.apache.poi.ss.usermodel.Row first = hop.getRow(header + 1);
            assertEquals("#,##0", first.getCell(3).getCellStyle().getDataFormatString());
            assertEquals("0.000", first.getCell(5).getCellStyle().getDataFormatString());
            int diffCol = columnOf(hop.getRow(header), "Değişim: sonra − önce, ort. ms");
            assertEquals("+0.000;-0.000;0.000", first.getCell(diffCol).getCellStyle().getDataFormatString());
            // B: the ratio of two means carries a multiplication sign and two decimals
            int headerB = rowOf(hop, "Kayıt noktası");
            int ratioCol = columnOf(hop.getRow(headerB), "Sonra / önce (ort.)");
            assertEquals("0.00\"×\"", hop.getRow(headerB + 1).getCell(ratioCol).getCellStyle().getDataFormatString());
            // D: shares are percents with one decimal
            int headerD = rowOf(hop, "Hop", "Yön", "Dönem");
            int shareCol = columnOf(hop.getRow(headerD), "Eşleşmeyen %");
            assertEquals("0.0%", hop.getRow(headerD + 1).getCell(shareCol).getCellStyle().getDataFormatString());
            // per-second rates on the timeline are counts when the buckets are whole seconds
            XSSFSheet timeline = wb.getSheet("Zaman Çizelgesi");
            assertEquals("#,##0", timeline.getRow(5).getCell(2).getCellStyle().getDataFormatString());
            // a ladder's IP ID is an identifier, not a quantity
            XSSFSheet samples = wb.getSheet("Örnek Olaylar");
            int ladder = rowOf(samples, "Kopya", "IP ID");
            assertEquals("0", samples.getRow(ladder + 1).getCell(1).getCellStyle().getDataFormatString());
        }
    }

    // ------------------------------------------------------------------------------------------ summary

    @Test
    void theTitleNamesTheCaseItsPointCountAndTheCaptureDate() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "title.duckdb", 1);
        int points;
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            points = ExpertReportBuilder.build(read, ExpertReportOptions.defaults()).points.size();
        }
        byte[] named = ExpertReport.generate(db, ExpertReportOptions.defaults().withCaseTitle("  OBJ-LIMA-12 "), 1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(named))) {
            assertEquals("OBJ-LIMA-12: " + points + " kayıt noktası, 14 Kasım 2023",
                    wb.getSheet("Özet").getRow(0).getCell(0).getStringCellValue());
            assertTrue(wb.getSheet("Özet").getRow(1).getCell(0).getStringCellValue().contains("Analiz penceresi"),
                    "the subtitle keeps the window line");
        }
        byte[] anonymous = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(anonymous))) {
            assertEquals("Paket analizi: " + points + " kayıt noktası, 14 Kasım 2023",
                    wb.getSheet("Özet").getRow(0).getCell(0).getStringCellValue());
        }
        // the title is a projection supplied by the caller: a blank one is no title
        assertArrayEquals(anonymous, ExpertReport.generate(db, ExpertReportOptions.defaults().withCaseTitle("   "), 1));
    }

    @Test
    void theSummaryStatesAtMostFiveFindingsInAFixedOrderAndKeepsTheRestBelowTheIndicatorTable() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "five.duckdb", 1);
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        int findings;
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            findings = ExpertReportBuilder.build(read, ExpertReportOptions.defaults()).findings.size();
        }
        assertTrue(findings > 5);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            XSSFSheet sheet = wb.getSheet("Özet");
            List<String> lines = new ArrayList<>();
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && cell.getColumnIndex() <= 1) {
                        lines.add(cell.getStringCellValue());
                    }
                }
            }
            List<String> numbered = lines.stream().filter(l -> l.matches("\\d+\\. .*")).toList();
            assertEquals(5, numbered.size(), numbered.toString());
            assertTrue(numbered.get(0).startsWith("1. Motor ilk olay başlangıcını"), numbered.get(0));
            assertTrue(numbered.get(1).startsWith("2. Önce→sonra en büyük artış "), numbered.get(1));
            assertTrue(numbered.get(2).startsWith("3. Motorun desteklediği kayıp olayları"), numbered.get(2));
            assertTrue(numbered.get(3).contains("TCP retransmission işareti"), numbered.get(3));
            assertTrue(numbered.get(4).startsWith("5. İlk kayıt noktasında (") && numbered.get(4).contains("istek→cevap süresi (RTT)"),
                    numbered.get(4));
            List<String> others = lines.stream().filter(l -> l.startsWith("• ")).toList();
            assertEquals(findings - 5, others.size());
            int kpi = lines.indexOf("Önce / Sonra göstergeleri");
            int blockAt = lines.indexOf("Diğer gözlemler");
            assertTrue(kpi > 0 && blockAt > kpi, "the other observations sit below the indicator table");
            assertTrue(lines.indexOf(others.get(0)) > blockAt);
        }
    }

    @Test
    void theHopDeltaFindingNamesTheComponentWhoseMeanGrewMostAndTheLargestChangeElsewhere() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "delta.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            ExpertReportData data = ExpertReportBuilder.build(read, ExpertReportOptions.defaults());
            String text = data.findings.stream().filter(f -> f.id().equals("hop-delta")).findFirst().orElseThrow().text();
            ExpertReportData.CycleHop best = null;
            double bestDelta = Double.NEGATIVE_INFINITY;
            for (ExpertReportData.CycleHop ch : data.cycleHops) {
                Double a = ch.byPeriod()[0].meanMs();
                Double b = ch.byPeriod()[1].meanMs();
                if (a != null && b != null && b - a > bestDelta) {
                    best = ch;
                    bestDelta = b - a;
                }
            }
            assertNotNull(best);
            assertTrue(text.startsWith("Önce→sonra en büyük artış " + best.label() + " " + best.component() + " bileşeninde: "), text);
            assertTrue(text.contains(TrFormat.number(Math.abs(bestDelta), 3) + " ms"), text);
            assertTrue(bestDelta <= 0 || text.contains("+" + TrFormat.number(bestDelta, 3)), text);
            assertTrue(text.contains("diğer hop'larda en büyük değişim"), text);
            for (String forbidden : List.of("yüksek", "sorun", "neden", "temiz", "gecikme burada")) {
                assertFalse(text.toLowerCase(new Locale("tr")).contains(forbidden), forbidden);
            }
        }
        // no before/after split, no sentence
        Path healthy = ExpertReportFixtures.analysed("healthy", tmp, "delta-h.duckdb", 1);
        try (Db read = new Db(Engine.openReadOnly(healthy, 1))) {
            assertFalse(ids(ExpertReportBuilder.build(read, ExpertReportOptions.defaults())).contains("hop-delta"));
        }
    }

    @Test
    void theIndicatorTableSplitsTheChangeIntoADifferenceAndARatioThatIsEmptyWhereTheEarlierValueIsZero() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "kpi.duckdb", 1);
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            XSSFSheet sheet = wb.getSheet("Özet");
            int header = -1;
            for (int r = 0; r <= sheet.getLastRowNum() && header < 0; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row != null && row.getCell(1) != null && "Gösterge".equals(row.getCell(1).getStringCellValue())) {
                    header = r;
                }
            }
            assertTrue(header > 0);
            org.apache.poi.ss.usermodel.Row head = sheet.getRow(header);
            assertEquals("Fark (sonra − önce)", head.getCell(8).getStringCellValue());
            assertEquals("Oran (sonra/önce)", head.getCell(10).getStringCellValue());
            assertFalse(sheetText(xlsx, "Özet").contains("Değişim"), "no column mixes a difference and a ratio");
            int seen = 0;
            int emptyRatio = 0;
            for (int r = header + 1; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null || row.getCell(1) == null || row.getCell(1).getCellType() != org.apache.poi.ss.usermodel.CellType.STRING
                        || row.getCell(4) == null || row.getCell(4).getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC
                        || row.getCell(6) == null || row.getCell(6).getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                    continue;
                }
                double before = row.getCell(4).getNumericCellValue();
                double after = row.getCell(6).getNumericCellValue();
                seen++;
                assertEquals(after - before, row.getCell(8).getNumericCellValue(), 1e-9, row.getCell(1).getStringCellValue());
                assertTrue(row.getCell(8).getCellStyle().getDataFormatString().startsWith("+"),
                        row.getCell(8).getCellStyle().getDataFormatString());
                if (before == 0) {
                    emptyRatio++;
                    assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, row.getCell(10).getCellType(),
                            row.getCell(1).getStringCellValue());
                } else {
                    assertEquals(after / before, row.getCell(10).getNumericCellValue(), 1e-9, row.getCell(1).getStringCellValue());
                    assertEquals("0.00\"×\"", row.getCell(10).getCellStyle().getDataFormatString());
                }
            }
            assertTrue(seen >= 8, "the table has its indicator rows: " + seen);
            assertTrue(emptyRatio >= 1, "the loss count rises from zero: a difference, no ratio");
        }
    }

    // ------------------------------------------------------------------------------------------ timeline and samples

    /** Sets the case's report time zone (the stored topology) before the report is generated. */
    @SuppressWarnings("unchecked")
    static void reportZone(Path db, String zone) throws Exception {
        try (Db write = new Db(Engine.open(db, 1))) {
            Map<String, Object> topology = new LinkedHashMap<>((Map<String, Object>) com.securityexpert.nexus.ui2.packetanalysis.engine
                    .CaseQueries.settingGet(write, "topology"));
            topology.put("report_timezone", zone);
            write.exec("INSERT OR REPLACE INTO settings VALUES ('topology', ?)", PyJsonWriter.dumps(topology, false));
        }
    }

    @Test
    void theUtcColumnOfTheTimelineAppearsOnlyWhenTheReportZoneIsNotUtc() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "zone.duckdb", 1);
        reportZone(db, "Europe/Istanbul");
        byte[] local = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(local))) {
            org.apache.poi.ss.usermodel.Row head = wb.getSheet("Zaman Çizelgesi").getRow(3);
            assertEquals("Saat (Europe/Istanbul)", head.getCell(0).getStringCellValue());
            assertEquals("UTC", head.getCell(1).getStringCellValue());
            assertEquals("Dönem", head.getCell(2).getStringCellValue());
            assertEquals("01:13:20", wb.getSheet("Zaman Çizelgesi").getRow(4).getCell(0).getStringCellValue(), "the local clock");
            assertEquals("22:13:20", wb.getSheet("Zaman Çizelgesi").getRow(4).getCell(1).getStringCellValue(), "the UTC clock");
        }
        assertNoGeneralNumbers(local, "Istanbul");
        reportZone(db, "UTC");
        byte[] utc = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(utc))) {
            org.apache.poi.ss.usermodel.Row head = wb.getSheet("Zaman Çizelgesi").getRow(3);
            for (org.apache.poi.ss.usermodel.Cell cell : head) {
                assertFalse("UTC".equals(cell.getStringCellValue()), "no duplicate clock column");
            }
        }
    }

    @Test
    void theStackedBarChartOfTheLongestCyclesUsesThePerHopColumnsAndNotTheTotalRtt() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "stack.duckdb", 1);
        byte[] xlsx = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        int points;
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            points = ExpertReportBuilder.build(read, ExpertReportOptions.defaults()).points.size();
        }
        String chart = null;
        for (int i = 1; i <= 12 && chart == null; i++) {
            try {
                String xml = part(xlsx, "xl/charts/chart" + i + ".xml");
                if (xml.contains("RTT kırılımı")) {
                    chart = xml;
                }
            } catch (IllegalArgumentException missing) {
                break;
            }
        }
        assertNotNull(chart, "the stacked chart exists");
        assertEquals(points, chart.split("<c:ser>", -1).length - 1, "one series per hop component and the last point's own part");
        java.util.regex.Matcher titles = java.util.regex.Pattern.compile("<c:tx><c:strRef><c:f>([^<]*)</c:f>").matcher(chart);
        int titleCount = 0;
        while (titles.find()) {
            titleCount++;
            assertFalse(titles.group(1).matches(".*\\$D\\$\\d+"), "column D holds the total RTT: " + titles.group(1));
        }
        assertEquals(points, titleCount);
        assertTrue(chart.contains("<c:grouping val=\"stacked\"/>"));
    }

    // ------------------------------------------------------------------------------------------ determinism

    @Test
    void sameInputGivesByteIdenticalOutputAcrossRunsThreadsTimeZonesAndLocales() throws Exception {
        Path one = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "d1.duckdb", 1);
        byte[] a = ExpertReport.generate(one, ExpertReportOptions.defaults(), 1);
        assertArrayEquals(a, ExpertReport.generate(one, ExpertReportOptions.defaults(), 1), "two runs");
        assertArrayEquals(a, ExpertReport.generate(one, ExpertReportOptions.defaults(), 4), "DuckDB threads 1 and 4 on one database");
        Path four = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "d4.duckdb", 4);
        assertArrayEquals(a, ExpertReport.generate(four, ExpertReportOptions.defaults(), 4), "analysis and report at threads 4");
        TimeZone zone = TimeZone.getDefault();
        Locale locale = Locale.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            Locale.setDefault(new Locale("tr", "TR"));
            assertArrayEquals(a, ExpertReport.generate(one, ExpertReportOptions.defaults(), 1), "another JVM time zone and locale");
        } finally {
            TimeZone.setDefault(zone);
            Locale.setDefault(locale);
        }
        // the package itself is canonical: fixed entry times and order, fixed document properties
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(a))) {
            List<String> names = new ArrayList<>();
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
                assertEquals(LocalDateTime.of(1980, 1, 2, 0, 0, 0), e.getTimeLocal(), e.getName());
                assertTrue(e.getExtra() == null || e.getExtra().length == 0, e.getName());
            }
            assertEquals("[Content_Types].xml", names.get(0));
            assertEquals("_rels/.rels", names.get(1));
            List<String> rest = new ArrayList<>(names.subList(2, names.size()));
            List<String> sorted = new ArrayList<>(rest);
            java.util.Collections.sort(sorted);
            assertEquals(sorted, rest);
        }
        String core = part(a, "docProps/core.xml");
        assertTrue(core.contains("2000-01-01T00:00:00Z"), core);
    }

    @Test
    void aDifferentExplicitWindowGivesADifferentWorkbook() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "dw.duckdb", 1);
        byte[] onset = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        byte[] explicit = ExpertReport.generate(db, ExpertReportOptions.defaults().withWindow(1700000020.0, null), 1);
        assertFalse(java.util.Arrays.equals(onset, explicit));
        assertArrayEquals(explicit, ExpertReport.generate(db, ExpertReportOptions.defaults().withWindow(1700000020.0, null), 4));
    }

    // ------------------------------------------------------------------------------------------ privacy projection

    /** A masker like the service's: names become pseudonyms, text is scrubbed of the raw names. */
    private static ReportMasker pseudonyms() {
        Map<String, String> assigned = new LinkedHashMap<>();
        return new ReportMasker() {
            @Override
            public String name(String raw) {
                return assigned.computeIfAbsent(raw, k -> "MASK-" + (assigned.size() + 1));
            }

            @Override
            public String text(String raw) {
                String out = raw;
                for (Map.Entry<String, String> e : assigned.entrySet()) {
                    out = out.replace(e.getKey(), e.getValue());
                }
                return out;
            }

            @Override
            public boolean masking() {
                return true;
            }
        };
    }

    @Test
    void theMaskedProjectionNeverHoldsARawNameAddressFlowIdentifierOrDigest() throws Exception {
        Path db = ExpertReportFixtures.analysed(ONSET_FIXTURE, tmp, "m.duckdb", 1);
        String digest = "ab".repeat(32);
        List<ExpertReportOptions.SourceCapture> sources = List.of(
                new ExpertReportOptions.SourceCapture("point-0.pcap", 164354, digest));
        byte[] raw = ExpertReport.generate(db, ExpertReportOptions.defaults().withSources(sources), 1);
        byte[] masked = ExpertReport.generate(db,
                ExpertReportOptions.defaults().withSources(sources).withMasker(pseudonyms()), 1);
        String rawXml = allParts(raw);
        String maskedXml = allParts(masked);
        for (String identity : List.of("Point 0", "Device 0", "point-0.pcap", "7d3d2286", digest)) {
            assertTrue(rawXml.contains(identity), "raw projection shows " + identity);
            assertFalse(maskedXml.contains(identity), "masked projection must not show " + identity);
        }
        for (String address : List.of("10.0.0.10", "203.0.113.20")) {
            assertFalse(rawXml.contains(address), "the report never lists an address: " + address);
            assertFalse(maskedXml.contains(address), address);
        }
        assertTrue(maskedXml.contains("MASK-"), "names are replaced by the masker's pseudonyms");
        assertTrue(maskedXml.contains("Akış 1"), "flows are shown by ordinal");
        assertTrue(maskedXml.contains("Bu görünümde SHA-256 değerleri gösterilmez"));
        assertTrue(rawXml.contains(digest));
        // the figures themselves do not change with the projection
        try (XSSFWorkbook a = new XSSFWorkbook(new ByteArrayInputStream(raw));
                XSSFWorkbook b = new XSSFWorkbook(new ByteArrayInputStream(masked))) {
            assertEquals(a.getSheet("Zaman Çizelgesi").getLastRowNum(), b.getSheet("Zaman Çizelgesi").getLastRowNum());
            int rowA = rowOf(a.getSheet("Hop Analizi"), "Hop", "Yön", "Tür") + 1;
            assertEquals(rowA, rowOf(b.getSheet("Hop Analizi"), "Hop", "Yön", "Tür") + 1);
            assertEquals(a.getSheet("Hop Analizi").getRow(rowA).getCell(5).getNumericCellValue(),
                    b.getSheet("Hop Analizi").getRow(rowA).getCell(5).getNumericCellValue());
            int marks = rowOf(a.getSheet("Hop Analizi"), "Hop", "Tür", "Karar") + 1;
            assertEquals(a.getSheet("Hop Analizi").getRow(marks).getCell(2).getStringCellValue(),
                    b.getSheet("Hop Analizi").getRow(marks).getCell(2).getStringCellValue(), "the marker does not depend on the projection");
        }
    }

    // ------------------------------------------------------------------------------------------ units

    @Test
    void integerSumsGiveTheSameMeanWhateverTheOrderTheyWereAddedIn() {
        long[] ns = {1_000_000L, 1_000_414L, 999_927L, 1_000_166L, 1_000_023L};
        long forward = 0;
        for (long v : ns) {
            forward += v;
        }
        long backward = 0;
        for (int i = ns.length - 1; i >= 0; i--) {
            backward += ns[i];
        }
        assertEquals(new Stat(5, forward, 1_000_414L, 1e6).meanMs(), new Stat(5, backward, 1_000_414L, 1e6).meanMs());
        assertEquals(null, Stat.EMPTY.meanMs());
        assertEquals("1.234,57", TrFormat.number(1234.5678, 2));
        assertEquals("0,000", TrFormat.number(-0.0001, 3));
        assertEquals("16 Ekim 2026", TrFormat.dateOf(1792108800.0, java.time.ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** First row whose leading cells carry these texts in order. */
    private static int rowOf(XSSFSheet sheet, String... leading) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            boolean all = row != null;
            for (int i = 0; all && i < leading.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(i);
                all = cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && cell.getStringCellValue().equals(leading[i]);
            }
            if (all) {
                return r;
            }
        }
        throw new IllegalArgumentException("no row starting with " + List.of(leading));
    }

    private static int columnOf(org.apache.poi.ss.usermodel.Row header, String text) {
        for (org.apache.poi.ss.usermodel.Cell cell : header) {
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && cell.getStringCellValue().equals(text)) {
                return cell.getColumnIndex();
            }
        }
        throw new IllegalArgumentException("no column " + text);
    }

    private static List<String> ids(ExpertReportData data) {
        return data.findings.stream().map(Finding::id).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> report(Db db) {
        return (Map<String, Object>) com.securityexpert.nexus.ui2.packetanalysis.engine.CaseQueries.settingGet(db, "report");
    }

    private static JsonNode gz(String resource) throws Exception {
        try (InputStream in = ExpertReportTest.class.getResourceAsStream(resource)) {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(new GZIPInputStream(in));
        }
    }

    private static List<String> sheetNames(byte[] xlsx) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                names.add(wb.getSheetName(i));
            }
            return names;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String sheetText(byte[] xlsx, String name) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            StringBuilder out = new StringBuilder();
            for (org.apache.poi.ss.usermodel.Row row : wb.getSheet(name)) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        out.append(cell.getStringCellValue()).append('\n');
                    }
                }
            }
            return out.toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String part(byte[] xlsx, String name) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        throw new IllegalArgumentException("no part " + name);
    }

    private static String allParts(byte[] xlsx) {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                out.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toString();
    }
}
