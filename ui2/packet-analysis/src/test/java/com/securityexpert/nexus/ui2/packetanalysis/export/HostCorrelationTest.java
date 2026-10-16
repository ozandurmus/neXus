package com.securityexpert.nexus.ui2.packetanalysis.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.Finding;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.HostColumn;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.HostFile;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Host telemetry in the expert report, on synthetic server files laid over the engine fixture's capture clock. */
class HostCorrelationTest {
    private static final String CASE = "det_a_onset_loss";
    private static final List<String> SHEETS_WITH_HOST = List.of("Özet", "Zaman Çizelgesi", "Hop Analizi", "Örnek Olaylar",
            "Sunucu Korelasyonu", "Yöntem ve Veri");

    @TempDir
    Path tmp;
    private Path db;

    @BeforeEach
    void analyse() throws Exception {
        db = ExpertReportFixtures.analysed(CASE, tmp, "case.duckdb", 1);
    }

    private ExpertReportOptions with(ExpertReportOptions.HostTelemetry... files) {
        return ExpertReportOptions.defaults().withTelemetry(List.of(files));
    }

    private ExpertReportData data(ExpertReportOptions options) throws Exception {
        try (Db read = new Db(Engine.openReadOnly(db, 1))) {
            return ExpertReportBuilder.build(read, options);
        }
    }

    private static ExpertReportOptions.HostTelemetry vmstat() {
        return HostFixtures.file("server-vmstat.txt", HostFixtures.vmstat(HostFixtures.utc(), "UTC"), "UTC", 0, null);
    }

    private static HostColumn column(ExpertReportData d, String key) {
        return d.host.columns.stream().filter(c -> c.key.equals(key)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ the sheet

    @Test
    void theHostSheetSitsBeforeMethodAndHoldsOneRowPerTimelineSecondInEveryColumn() throws Exception {
        ExpertReportOptions options = with(vmstat(),
                HostFixtures.file("srv-sar.txt", HostFixtures.sar(HostFixtures.utc(), "srv-a.example.test"), "UTC", 0, null),
                HostFixtures.file("srv-pidstat.txt", HostFixtures.pidstat(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("srv-snapshots.txt", HostFixtures.snapshots(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("messages", HostFixtures.messages(HostFixtures.utc()), "UTC", 0, null));
        byte[] xlsx = ExpertReport.generate(db, options, 1);
        assertEquals(SHEETS_WITH_HOST, sheetNames(xlsx));
        ExpertReportData d = data(options);
        assertEquals(41, d.timeline.count);
        assertEquals(5, d.host.files.size());
        assertTrue(d.host.files.stream().allMatch(HostFile::placed), d.host.files.toString());
        for (HostColumn c : d.host.columns) {
            assertEquals(d.timeline.count, c.values.length, c.title);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            XSSFSheet sheet = wb.getSheet("Sunucu Korelasyonu");
            assertEquals(1, sheet.getDrawingPatriarch().getCharts().size(), "one line chart on the capture timeline");
            // the grid repeats the timeline sheet's seconds, row for row
            int header = -1;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row != null && row.getCell(0) != null && "Saat (UTC)".equals(row.getCell(0).getStringCellValue())) {
                    header = r;
                }
            }
            assertTrue(header > 0);
            XSSFSheet timeline = wb.getSheet("Zaman Çizelgesi");
            for (int i = 0; i < 41; i++) {
                assertEquals(timeline.getRow(4 + i).getCell(0).getStringCellValue(), sheet.getRow(header + 1 + i).getCell(0).getStringCellValue());
            }
            assertEquals(header + 41, sheet.getLastRowNum());
        }
        String text = sheetText(xlsx, "Sunucu Korelasyonu");
        for (String needed : List.of("A. Saat eşlemesi", "B. vmstat ve sar", "C. sar TCP ve ağ arayüzleri", "D. qdisc / softnet",
                "E. pidstat süreç satırı sayısı", "F. syslog saniyelik kayıt sayısı", "F. syslog: en sık program etiketleri")) {
            assertTrue(text.contains(needed), needed);
        }
    }

    @Test
    void everyNumericCellOfTheWorkbookWithHostTelemetryHasAnExplicitNumberFormatInUtcAndLocalReports() throws Exception {
        ExpertReportOptions options = with(vmstat(),
                HostFixtures.file("srv-sar.txt", HostFixtures.sar(HostFixtures.utc(), "srv-a.example.test"), "UTC", 0, null),
                HostFixtures.file("srv-pidstat.txt", HostFixtures.pidstat(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("srv-snapshots.txt", HostFixtures.snapshots(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("messages", HostFixtures.messages(HostFixtures.utc()), "UTC", 0, null));
        ExpertReportTest.assertNoGeneralNumbers(ExpertReport.generate(db, options, 1), "host, UTC report");
        ExpertReportTest.reportZone(db, "Europe/Istanbul");
        byte[] local = ExpertReport.generate(db, options, 1);
        ExpertReportTest.assertNoGeneralNumbers(local, "host, local report");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(local))) {
            XSSFSheet sheet = wb.getSheet("Sunucu Korelasyonu");
            boolean utcHeader = false;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                utcHeader |= row != null && row.getCell(1) != null && row.getCell(1).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && "UTC".equals(row.getCell(1).getStringCellValue());
            }
            assertTrue(utcHeader, "a local-zone report keeps the UTC column on the host grid too");
        }
    }

    @Test
    void valuesLandOnTheRightSecondForEveryFormat() throws Exception {
        ExpertReportOptions options = with(vmstat(),
                HostFixtures.file("srv-sar.txt", HostFixtures.sar(HostFixtures.utc(), "srv-a.example.test"), "UTC", 0, null),
                HostFixtures.file("srv-pidstat.txt", HostFixtures.pidstat(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("srv-snapshots.txt", HostFixtures.snapshots(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("messages", HostFixtures.messages(HostFixtures.utc()), "UTC", 0, null));
        ExpertReportData d = data(options);
        HostColumn runq = column(d, "vmstat.r");
        assertEquals(2.0, runq.values[0]);
        assertEquals(300.0, runq.values[12]);
        assertEquals(300.0 + 40 * 28, runq.values[40]);
        HostColumn retrans = column(d, "sar.retrans/s");
        assertEquals(0.0, retrans.values[12]);
        assertEquals(518.0, retrans.values[13]);
        HostColumn drops = d.host.columns.stream().filter(c -> c.key.equals("qdisc.dropped|eth1")).findFirst().orElseThrow();
        assertEquals(0.0, drops.values[0], "the increase between the first two snapshots, both inside second 0");
        assertEquals(0.0, drops.values[5]);
        assertTrue(drops.values[13] > 0 && drops.values[12] == 0.0, "the first increase is in second 13");
        HostColumn squeeze = column(d, "softnet.time_squeeze");
        assertEquals(3.0, squeeze.values[20]);
        HostColumn rows = column(d, "pidstat.rows");
        assertEquals(40.0, rows.values[5]);
        assertEquals(40.0 + 6 * 28, rows.values[40]);
        HostColumn delta = column(d, "pidstat.rows.delta");
        assertEquals(0.0, delta.values[5]);
        assertEquals(6.0, delta.values[13]);
        HostColumn syslog = column(d, "syslog.count");
        assertEquals(1.0, syslog.values[5]);
        assertEquals(5.0, syslog.values[12]);
        assertFalse(d.host.columns.stream().anyMatch(c -> c.key.contains("lo") && c.key.startsWith("qdisc") && c.values[13] != null && c.values[13] > 0),
                "lo never drops in the fixture");
    }

    @Test
    void aLocalTimeFileWithItsZoneConfirmedLandsOnTheSameSecondsAsTheUtcFile() throws Exception {
        ExpertReportData utc = data(with(vmstat()));
        ExpertReportOptions.HostTelemetry local = HostFixtures.file("server-vmstat.txt",
                HostFixtures.vmstat(HostFixtures.istanbul(), "+03"), "Europe/Istanbul", 0, null);
        ExpertReportData istanbul = data(with(local));
        assertArrayEquals(column(utc, "vmstat.r").values, column(istanbul, "vmstat.r").values);
        assertEquals("Europe/Istanbul (kullanıcı teyit etti)", istanbul.host.files.get(0).zoneText());
        assertEquals("+03", istanbul.host.files.get(0).fileZoneLabel());
        assertTrue(istanbul.host.files.get(0).notes().stream().noneMatch(n -> n.contains("çelişiyor")));
        // an extra clock offset moves the samples
        ExpertReportData shifted = data(with(HostFixtures.file("server-vmstat.txt", HostFixtures.vmstat(HostFixtures.utc(), "UTC"),
                "UTC", 2, null)));
        assertEquals(300.0, column(shifted, "vmstat.r").values[14], "the file's second 12 now sits at second 14");
    }

    // ------------------------------------------------------------------------------------------ assumptions and refusals

    @Test
    void aFileWithoutAConfirmedZoneIsReadAsUtcAndTheReportSaysSo() throws Exception {
        ExpertReportOptions.HostTelemetry unconfirmed = HostFixtures.file("server-vmstat.txt",
                HostFixtures.vmstat(HostFixtures.utc(), "UTC"), null, 0, null);
        ExpertReportData d = data(with(unconfirmed));
        assertFalse(d.host.files.get(0).zoneConfirmed());
        assertTrue(d.host.files.get(0).zoneText().contains("varsayım"), d.host.files.get(0).zoneText());
        assertTrue(d.host.limits.stream().anyMatch(l -> l.contains("UTC varsayıldı")), d.host.limits.toString());
        byte[] xlsx = ExpertReport.generate(db, with(unconfirmed), 1);
        assertTrue(sheetText(xlsx, "Yöntem ve Veri").contains("saatler UTC varsayıldı"));
        assertTrue(sheetText(xlsx, "Sunucu Korelasyonu").contains("varsayım"));
        String alignment = d.findings.stream().filter(f -> f.id().equals("host-align")).findFirst().orElseThrow().text();
        assertTrue(alignment.contains("varsayım"), alignment);
        // confirmed: no such limit
        ExpertReportData confirmed = data(with(HostFixtures.file("server-vmstat.txt", HostFixtures.vmstat(HostFixtures.utc(), "UTC"),
                "UTC", 0, null)));
        assertTrue(confirmed.host.limits.stream().noneMatch(l -> l.contains("UTC varsayıldı")));
    }

    @Test
    void aFileLabelThatContradictsTheZoneUsedIsReported() throws Exception {
        // vmstat prints '+03' but the user left the zone unconfirmed (UTC assumed): the contradiction is a note on the file
        ExpertReportData d = data(with(HostFixtures.file("server-vmstat.txt", HostFixtures.vmstat(HostFixtures.utc(), "+03"), null, 0, null)));
        assertTrue(d.host.files.get(0).notes().stream().anyMatch(n -> n.contains("'+03'") && n.contains("çelişiyor")),
                d.host.files.get(0).notes().toString());
    }

    @Test
    void unrecognisedUnalignableAndNonOverlappingFilesAreListedWithAReasonNotDropped() throws Exception {
        ExpertReportOptions options = with(HostFixtures.file("a-table.csv", HostFixtures.unknown(), "UTC", 0, null),
                HostFixtures.file("b-plain-vmstat.txt", "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----\n"
                        + " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st\n"
                        + " 2  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n"
                        + " 3  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n", "UTC", 0, null),
                HostFixtures.file("c-other-day.txt", HostFixtures.vmstat(HostFixtures.utc(), "UTC"), "UTC", 86_400, null));
        ExpertReportData d = data(options);
        assertEquals(3, d.host.files.size());
        HostFile csv = d.host.files.get(0);
        assertEquals("UNRECOGNIZED", csv.status());
        assertTrue(csv.statusNote().contains("Biçim tanınmadı"), csv.statusNote());
        HostFile plain = d.host.files.get(1);
        assertEquals("NOT_ALIGNABLE", plain.status());
        assertTrue(plain.statusNote().contains("başlangıç"), plain.statusNote());
        HostFile other = d.host.files.get(2);
        assertEquals("NO_OVERLAP", other.status());
        assertTrue(other.statusNote().contains("örtüşmüyor"));
        assertTrue(d.host.columns.isEmpty());
        assertNotNull(d.host.gridNote);
        byte[] xlsx = ExpertReport.generate(db, options, 1);
        assertEquals(SHEETS_WITH_HOST, sheetNames(xlsx));
        String text = sheetText(xlsx, "Sunucu Korelasyonu");
        assertTrue(text.contains("a-table.csv") && text.contains("Biçim tanınmadı") && text.contains("kullanılamadı"), text);
        List<String> ids = d.findings.stream().map(Finding::id).toList();
        assertTrue(ids.contains("host-align"));
        assertFalse(ids.contains("host-runq"));
    }

    @Test
    void aPlainVmstatFileIsPlacedFromTheStartTheUserGave() throws Exception {
        String plain = "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----\n"
                + " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st\n"
                + " 2  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n"
                + " 11  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n"
                + " 12  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n";
        ExpertReportData d = data(with(HostFixtures.file("plain.txt", plain, "UTC", 0, "2023-11-14 22:13:25")));
        HostColumn r = column(d, "vmstat.r");
        assertEquals(11.0, r.values[6], "the second printed row is one interval after the start 22:13:25 (second 5)");
        assertEquals(12.0, r.values[7]);
        assertNull(r.values[5], "the first printed row is the start itself and not a sample");
        assertTrue(d.host.files.get(0).notes().stream().anyMatch(n -> n.contains("1 sn aralıklı")));
    }

    @Test
    void malformedLinesAreCountedAndOnlyThoseLinesAreLost() throws Exception {
        String text = HostFixtures.vmstat(HostFixtures.utc(), "UTC") + " 5  0  broken row\n 7  0      0 8037584  79200 5978804    0    0     7    23 40 64  1 zz 99  0  0 2023-11-14 22:13:30\n";
        ExpertReportData d = data(with(HostFixtures.file("server-vmstat.txt", text, "UTC", 0, null)));
        HostFile f = d.host.files.get(0);
        assertEquals(2, f.linesSkipped());
        assertTrue(f.skipText().contains("sütun sayısı uyumsuz: 1") && f.skipText().contains("sayı olmayan değer: 1"), f.skipText());
        assertEquals(f.linesTotal(), f.linesUsed() + f.linesIgnored() + f.linesSkipped());
        assertEquals(300.0, column(d, "vmstat.r").values[12]);
    }

    // ------------------------------------------------------------------------------------------ findings

    @Test
    void findingsStateFactsWithTheirNumbersAndTimesAndNoVerdict() throws Exception {
        ExpertReportOptions options = with(vmstat(),
                HostFixtures.file("srv-sar.txt", HostFixtures.sar(HostFixtures.utc(), "srv-a.example.test"), "UTC", 0, null),
                HostFixtures.file("srv-pidstat.txt", HostFixtures.pidstat(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("srv-snapshots.txt", HostFixtures.snapshots(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("messages", HostFixtures.messages(HostFixtures.utc()), "UTC", 0, null));
        ExpertReportData d = data(options);
        Map<String, String> byId = new LinkedHashMap<>();
        for (Finding f : d.findings) {
            if (f.id().startsWith("host-")) {
                byId.putIfAbsent(f.id(), f.text());
            }
        }
        assertEquals(List.of("host-align", "host-runq", "host-cpu", "host-retrans", "host-qdisc", "host-softnet", "host-pidstat", "host-syslog"),
                new ArrayList<>(byId.keySet()));
        String runq = d.findings.stream().filter(f -> f.id().equals("host-runq") && f.text().contains("vmstat")).findFirst().orElseThrow().text();
        assertTrue(runq.contains("önce → sonra"), runq);
        assertTrue(runq.contains("→"), runq);
        assertTrue(runq.contains("zirve 1.420, saat 22:14:00 (UTC)"), runq);
        String retrans = byId.get("host-retrans");
        assertTrue(retrans.contains("zirve 14.504, saat 22:14:00 (UTC)"), retrans);
        assertTrue(byId.get("host-qdisc").contains("ilk artış saat 22:13:33 (UTC)"), byId.get("host-qdisc"));
        assertTrue(byId.get("host-pidstat").contains("önceki örneğe göre en büyük artış +6, saat 22:13:33"), byId.get("host-pidstat"));
        assertTrue(byId.get("host-syslog").contains("saniyede en çok 5, saat 22:13:32 (UTC)"), byId.get("host-syslog"));
        assertTrue(byId.get("host-syslog").contains("en sık program etiketleri"), byId.get("host-syslog"));
        String everything = String.join("\n", byId.values()).toLowerCase(new Locale("tr"));
        for (String forbidden : List.of("temiz", "sorunlu", "kök neden", "neden olu", "suçlu", "yüksek", "düşük", "anormal", "kritik")) {
            assertFalse(everything.contains(forbidden), forbidden);
        }
    }

    @Test
    void qdiscCountersThatNeverRoseAreStatedOnceNotPerDevice() throws Exception {
        StringBuilder flat = new StringBuilder();
        for (int s = 0; s < 6; s++) {
            flat.append("2023-11-14 22:13:").append(String.format("%02d", 20 + s)).append(".100\n");
            flat.append("qdisc mq 0: dev eth1 root\n Sent 1 bytes 1 pkt (dropped 500, overlimits 0 requeues 0)\n");
            flat.append("qdisc mq 0: dev eth2 root\n Sent 1 bytes 1 pkt (dropped 9, overlimits 0 requeues 0)\n");
        }
        ExpertReportData d = data(with(HostFixtures.file("flat.txt", flat.toString(), "UTC", 0, null)));
        List<Finding> qdisc = d.findings.stream().filter(f -> f.id().equals("host-qdisc")).toList();
        assertEquals(1, qdisc.size());
        assertTrue(qdisc.get(0).text().contains("artış yok") && qdisc.get(0).text().contains("eth1"), qdisc.get(0).text());
        assertEquals(0.0, column(d, "qdisc.dropped|eth1").values[3]);
    }

    @Test
    void withoutTelemetryTheMethodNoteAboutHostLogsStaysAndWithTelemetryItIsGone() throws Exception {
        byte[] without = ExpertReport.generate(db, ExpertReportOptions.defaults(), 1);
        assertTrue(sheetText(without, "Yöntem ve Veri").contains("Sunucu (host) tarafı günlükleri"));
        assertFalse(sheetNames(without).contains("Sunucu Korelasyonu"));
        byte[] with = ExpertReport.generate(db, with(vmstat()), 1);
        String method = sheetText(with, "Yöntem ve Veri");
        assertFalse(method.contains("dahil edilmemiştir"), "the old 'host logs were not part of the analysis' line is gone");
        assertTrue(method.contains("Sunucu verisi kullanıcının yüklediği metin dosyalarından okunur"));
        assertTrue(method.contains("server-vmstat.txt"), "the file is listed with the other sources");
    }

    // ------------------------------------------------------------------------------------------ privacy

    private static ReportMasker pseudonyms() {
        Map<String, String> assigned = new LinkedHashMap<>();
        return new ReportMasker() {
            @Override
            public String name(String raw) {
                return assigned.computeIfAbsent(raw, k -> "MASK-" + (assigned.size() + 1));
            }

            @Override
            public String text(String raw) {
                return raw;
            }

            @Override
            public boolean masking() {
                return true;
            }
        };
    }

    @Test
    void aMaskedReportHoldsNoHostInterfaceProgramOrFileNameAndNoMessageTextAtAll() throws Exception {
        ExpertReportOptions raw = with(vmstat(),
                HostFixtures.file("srv-sar.txt", HostFixtures.sar(HostFixtures.utc(), "srv-a.example.test"), "UTC", 0, null),
                HostFixtures.file("srv-snapshots.txt", HostFixtures.snapshots(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("messages-customer-x", HostFixtures.messages(HostFixtures.utc()), "UTC", 0, null));
        byte[] unmasked = ExpertReport.generate(db, raw, 1);
        byte[] masked = ExpertReport.generate(db, raw.withMasker(pseudonyms()), 1);
        String rawParts = allParts(unmasked);
        String maskedParts = allParts(masked);
        for (String identity : List.of("srv-a.example.test", "eth1", "srv-sar.txt", "messages-customer-x", "kernel", "systemd")) {
            assertTrue(rawParts.contains(identity), "raw view shows " + identity);
            assertFalse(maskedParts.contains(identity), "masked view must not show " + identity);
        }
        for (String text : List.of("batch-user-secret", "nf_conntrack", "192.0.2.77", "table full")) {
            assertFalse(rawParts.contains(text), "message text is not in any projection: " + text);
            assertFalse(maskedParts.contains(text), text);
        }
        assertFalse(maskedParts.contains("cd".repeat(32)), "digests are withheld from masked readers");
        assertTrue(rawParts.contains("cd".repeat(32)));
        assertTrue(maskedParts.contains("MASK-"));
        // the figures do not change with the projection
        try (XSSFWorkbook a = new XSSFWorkbook(new ByteArrayInputStream(unmasked)); XSSFWorkbook b = new XSSFWorkbook(new ByteArrayInputStream(masked))) {
            assertEquals(a.getSheet("Sunucu Korelasyonu").getLastRowNum(), b.getSheet("Sunucu Korelasyonu").getLastRowNum());
        }
    }

    @Test
    void theLocalCliTakesTelemetryFilesWithTheirAlignment() throws Exception {
        Path vm = tmp.resolve("vm.txt");
        java.nio.file.Files.writeString(vm, HostFixtures.vmstat(HostFixtures.istanbul(), "+03"));
        Path out = tmp.resolve("cli.xlsx");
        ExpertReportCli.run(new java.io.PrintStream(new java.io.ByteArrayOutputStream()), "--project", db.toString(), "--output",
                out.toString(), "--threads", "1", "--telemetry", vm.toString(), "--telemetry-zone", "Europe/Istanbul",
                "--telemetry-offset", "0");
        byte[] xlsx = java.nio.file.Files.readAllBytes(out);
        assertEquals(SHEETS_WITH_HOST, sheetNames(xlsx));
        assertTrue(sheetText(xlsx, "Sunucu Korelasyonu").contains("Europe/Istanbul (kullanıcı teyit etti)"));
        assertFalse(sheetText(xlsx, "Sunucu Korelasyonu").contains("varsayım:"));
        assertThrows(IllegalArgumentException.class, () -> ExpertReportCli.run(new java.io.PrintStream(new java.io.ByteArrayOutputStream()),
                "--project", db.toString(), "--output", tmp.resolve("x.xlsx").toString(), "--telemetry-zone", "UTC"));
    }

    // ------------------------------------------------------------------------------------------ determinism

    @Test
    void sameInputGivesByteIdenticalWorkbooksAcrossRunsThreadsTimeZonesAndLocales() throws Exception {
        ExpertReportOptions options = with(vmstat(),
                HostFixtures.file("srv-sar.txt", HostFixtures.sar(HostFixtures.istanbul(), "srv-a.example.test"), "Europe/Istanbul", 0, null),
                HostFixtures.file("srv-pidstat.txt", HostFixtures.pidstat(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("srv-snapshots.txt", HostFixtures.snapshots(HostFixtures.utc()), "UTC", 0, null),
                HostFixtures.file("messages", HostFixtures.messages(HostFixtures.utc()), null, 0, null));
        byte[] a = ExpertReport.generate(db, options, 1);
        assertArrayEquals(a, ExpertReport.generate(db, options, 1), "two runs");
        assertArrayEquals(a, ExpertReport.generate(db, options, 4), "DuckDB threads 1 and 4");
        // the order the files are attached in does not matter
        List<ExpertReportOptions.HostTelemetry> reversed = new ArrayList<>(options.telemetry());
        java.util.Collections.reverse(reversed);
        assertArrayEquals(a, ExpertReport.generate(db, options.withTelemetry(reversed), 1), "attachment order");
        TimeZone zone = TimeZone.getDefault();
        Locale locale = Locale.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            Locale.setDefault(new Locale("tr", "TR"));
            assertArrayEquals(a, ExpertReport.generate(db, options, 1), "another JVM time zone and locale");
        } finally {
            TimeZone.setDefault(zone);
            Locale.setDefault(locale);
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

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
