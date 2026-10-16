package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import static com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TelemetryParserTest {
    private static final long T0 = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli();

    private static ParsedTelemetry parse(String text) throws IOException {
        return TelemetryParser.parse(new ByteArrayInputStream(bytes(text)), new TelemetryParser.Context(T0));
    }

    private static double at(ParsedTelemetry p, String key, int i) {
        return p.series().get(key).value(i);
    }

    // ---- sar ------------------------------------------------------------------------------------------------

    @Test
    void sarBlocksAreToldApartByTheirColumnsAndNfsRetransIsNotTcpRetrans() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.sar24h());
        assertEquals(TelemetryFormat.SAR, p.format());
        assertEquals("srv-a.example.test", p.hostName());
        assertEquals(4, p.series().get("sar.cpu.sys").size(), "only the 'all' CPU row");
        assertEquals(47.0, at(p, "sar.cpu.sys", 2));
        assertEquals(886.0, at(p, "sar.runq", 2));
        assertEquals(2955.0, at(p, "sar.plist", 0));
        assertEquals(160.0, at(p, "sar.proc/s", 1));
        assertEquals(3316.0, at(p, "sar.retrans/s", 3));
        assertEquals(4, p.series().get("sar.retrans/s").size(), "the NFS block's retrans/s (77) is a different series, never this one");
        assertTrue(p.series().containsKey("sar.rxpck/s|eth1") && p.series().containsKey("sar.txpck/s|lo"));
        assertEquals(165084.0, at(p, "sar.iseg/s", 1));
        assertFalse(p.series().containsKey("sar.cpu.nice"));
        assertEquals(ParsedTelemetry.TimeBase.ABSOLUTE, p.base());
        assertEquals(0, p.linesSkipped());
        assertEquals(28, p.linesUsed());
        assertTrue(p.linesIgnored() > 10);
        assertEquals(p.linesTotal(), p.linesUsed() + p.linesIgnored() + p.linesSkipped(), "every line is accounted for");
        assertTrue(p.notes().containsKey("DATE_ASSUMED_MONTH_FIRST"), "10/08/2026 is ambiguous and read month first, loudly");
    }

    @Test
    void sarDateFormatsAndTwelveHourClocks() throws IOException {
        String text = "Linux 6.1.0 (h) \t08.10.2026 \t_x86_64_\t(8 CPU)\n\n"
                + "10:00:00 AM  runq-sz  plist-sz   ldavg-1   ldavg-5  ldavg-15   blocked\n"
                + "11:59:59 AM        5       100      0.10      0.10      0.10         0\n"
                + "12:00:01 PM        6       100      0.10      0.10      0.10         0\n"
                + "01:00:00 PM        7       100      0.10      0.10      0.10         0\n";
        ParsedTelemetry p = parse(text);
        assertFalse(p.notes().containsKey("DATE_ASSUMED_MONTH_FIRST"), "dotted dates are day first");
        Series runq = p.series().get("sar.runq");
        assertEquals(3, runq.size());
        long day = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli();
        assertEquals(day + 11 * 3_600_000L + 59 * 60_000L + 59_000L, runq.wall(0));
        assertEquals(day + 12 * 3_600_000L + 1_000L, runq.wall(1), "12 PM is noon");
        assertEquals(day + 13 * 3_600_000L, runq.wall(2));
    }

    @Test
    void sarSkipsMalformedLinesWholeAndCountsWhy() throws IOException {
        String text = "Linux 5.14.0 (h) \t2026-10-08 \t_x86_64_\t(8 CPU)\n"
                + "10:00:00     active/s passive/s    iseg/s    oseg/s\n"
                + "10:00:01         1.00      2.00      3.00      4.00\n"
                + "10:00:02         1.00      abc       3.00      4.00\n"
                + "10:00:03         1.00      2.00\n"
                + "25:00:04         1.00      2.00      3.00      4.00\n"
                + "10:00:05         1.00      2.00      3.00      4.00\n";
        ParsedTelemetry p = parse(text);
        assertEquals(2, p.series().get("sar.active/s").size());
        assertEquals(2, p.series().get("sar.passive/s").size(), "a bad value skips the whole line: no half-applied rows");
        assertEquals(1L, p.skipReasons().get("BAD_NUMBER"));
        assertEquals(1L, p.skipReasons().get("BAD_COLUMN_COUNT"));
        assertEquals(1L, p.skipReasons().get("BAD_TIME"), "an impossible clock reading is a skipped line, not a sample");
        assertEquals(3, p.linesSkipped());
    }

    @Test
    void sarMidnightRolloverAdvancesTheDayAndADatelessFileNeedsAnAnchor() throws IOException {
        String banner = "Linux 5.14.0 (h) \t2026-10-08 \t_x86_64_\t(8 CPU)\n";
        String rows = "23:59:58     active/s passive/s    iseg/s    oseg/s\n"
                + "23:59:59         1.00      2.00      3.00      4.00\n"
                + "00:00:01         1.00      2.00      3.00      4.00\n";
        ParsedTelemetry p = parse(banner + rows);
        Series s = p.series().get("sar.active/s");
        long day = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli();
        assertEquals(day + 86_399_000L, s.wall(0));
        assertEquals(day + 86_400_000L + 1000L, s.wall(1));
        assertEquals(1L, p.notes().get("MIDNIGHT_ROLLOVER"));

        ParsedTelemetry dateless = parse(rows);
        assertEquals(ParsedTelemetry.TimeBase.DATE_RELATIVE, dateless.base());
        assertEquals("NEEDS_ANCHOR_DATE", AlignedTelemetry.align(dateless, Alignment.assumedUtc()).unusableReason());
        AlignedTelemetry ok = AlignedTelemetry.align(dateless, Alignment.of("UTC", 0, "2026-10-08 23:59:00"));
        assertNull(ok.unusableReason());
        assertEquals(day / 1000.0 + 86_399, ok.firstEpoch());
    }

    // ---- vmstat ---------------------------------------------------------------------------------------------

    @Test
    void vmstatWithTimestampsKeepsTheFilesZoneLabelAndSkipsTheSinceBootRow() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.vmstatStamped());
        assertEquals(TelemetryFormat.VMSTAT, p.format());
        assertEquals("UTC", p.fileZoneLabel());
        Series r = p.series().get("vmstat.r");
        assertEquals(3, r.size(), "the first row (average since boot) and the malformed row are not samples");
        assertEquals(3.0, r.value(0));
        assertEquals(886.0, r.value(2));
        assertEquals(36.0, at(p, "vmstat.sy", 1));
        assertEquals(1130827.0, at(p, "vmstat.in", 1));
        assertEquals(1L, p.skipReasons().get("BAD_NUMBER"));
        assertTrue(p.notes().containsKey("FIRST_ROW_SKIPPED"));
        assertEquals(ParsedTelemetry.TimeBase.ABSOLUTE, p.base());
    }

    @Test
    void vmstatWithoutTimestampsIsPlacedFromTheStartTimeTheUserGives() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.vmstatPlain());
        assertEquals(ParsedTelemetry.TimeBase.START_RELATIVE, p.base());
        assertEquals(2, p.series().get("vmstat.r").size());
        assertTrue(p.notes().containsKey("INTERVAL_ASSUMED_1S"));
        assertEquals("NEEDS_ANCHOR_TIME", AlignedTelemetry.align(p, Alignment.assumedUtc()).unusableReason(),
                "no start time, no placement: never guessed");
        AlignedTelemetry aligned = AlignedTelemetry.align(p, Alignment.of("UTC", 0, "2026-10-08 09:59:59"));
        assertEquals(T0 / 1000.0, aligned.firstEpoch(), "the second printed row is one interval after the start");
        assertEquals(T0 / 1000.0 + 1, aligned.lastEpoch());
    }

    // ---- pidstat --------------------------------------------------------------------------------------------

    @Test
    void pidstatCountsProcessRowsPerIntervalAndTheirChange() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.pidstat());
        assertEquals(TelemetryFormat.PIDSTAT, p.format());
        Series rows = p.series().get("pidstat.rows");
        assertEquals(4, rows.size());
        assertEquals(3.0, rows.value(0));
        assertEquals(6.0, rows.value(2));
        Series delta = p.series().get("pidstat.rows.delta");
        assertEquals(3, delta.size());
        assertEquals(0.0, delta.value(0));
        assertEquals(3.0, delta.value(1));
        assertEquals(-2.0, delta.value(2));
        assertFalse(p.series().keySet().stream().anyMatch(k -> k.contains("worker")), "process names are not kept");
    }

    // ---- qdisc and softnet ----------------------------------------------------------------------------------

    @Test
    void qdiscDropsUseTheRootQdiscPerDeviceAndSoftnetRowsAreSummedOverCpus() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.snapshots());
        assertEquals(TelemetryFormat.SNAPSHOTS, p.format());
        Series eth1 = p.series().get("qdisc.dropped|eth1");
        assertEquals(5, eth1.size());
        assertEquals(39588.0, eth1.value(0), "the root qdisc, not the 999999 of its child");
        assertEquals(35918.0, p.series().get("qdisc.dropped|eth2").value(4));
        assertEquals(0.0, p.series().get("qdisc.dropped|lo").value(0));
        assertEquals(Series.Kind.COUNTER, eth1.kind());
        assertEquals(1000.0 + 2000.0, at(p, "softnet.processed", 0));
        assertEquals(231634.0, at(p, "softnet.time_squeeze", 0));
        assertEquals(231635.0, at(p, "softnet.time_squeeze", 4));
        assertEquals(0, p.linesSkipped());
    }

    @Test
    void snapshotCountersBecomeIncreasesPlacedInTheBucketOfTheLaterSample() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.snapshots());
        AlignedTelemetry a = AlignedTelemetry.align(p, Alignment.assumedUtc());
        Double[] grid = a.place(p.series().get("qdisc.dropped|eth1"), T0 / 1000.0, 1.0, 4);
        assertNull(grid[0], "the first sample has no earlier one to subtract");
        assertEquals(40.0 + 46.0, grid[1], "10:00:01.591 and 10:00:01.978 both fall in second 1");
        assertEquals(76.0 + 226.0, grid[2]);
        assertNull(grid[3]);
    }

    @Test
    void aCounterThatGoesDownIsAResetAndCountsAsNoIncrease() throws IOException {
        String text = "2026-10-08 10:00:00\nqdisc mq 0: dev eth1 root\n Sent 1 bytes 1 pkt (dropped 100, overlimits 0 requeues 0)\n"
                + "2026-10-08 10:00:01\nqdisc mq 0: dev eth1 root\n Sent 1 bytes 1 pkt (dropped 5, overlimits 0 requeues 0)\n"
                + "2026-10-08 10:00:02\nqdisc mq 0: dev eth1 root\n Sent 1 bytes 1 pkt (dropped 9, overlimits 0 requeues 0)\n";
        ParsedTelemetry p = parse(text);
        AlignedTelemetry a = AlignedTelemetry.align(p, Alignment.assumedUtc());
        Double[] grid = a.place(p.series().get("qdisc.dropped|eth1"), T0 / 1000.0, 1.0, 3);
        assertEquals(0.0, grid[1]);
        assertEquals(4.0, grid[2]);
        assertEquals(1L, a.resets().get("qdisc.dropped|eth1"));
    }

    @Test
    void snapshotsAcceptDateCommandAndEpochStampsAndSkipContentWithoutAStamp() throws IOException {
        String text = "qdisc mq 0: dev eth1 root\n Sent 1 bytes 1 pkt (dropped 1, overlimits 0 requeues 0)\n"
                + "Thu Oct  8 10:00:00 UTC 2026\nqdisc mq 0: dev eth1 root\n Sent 1 bytes 1 pkt (dropped 10, overlimits 0 requeues 0)\n"
                + "1791453601\nqdisc mq 0: dev eth1 root\n Sent 1 bytes 1 pkt (dropped 12, overlimits 0 requeues 0)\n";
        ParsedTelemetry p = parse(text);
        assertEquals(1L, p.skipReasons().get("NO_TIMESTAMP"));
        Series s = p.series().get("qdisc.dropped|eth1");
        assertEquals(2, s.size());
        AlignedTelemetry a = AlignedTelemetry.align(p, Alignment.of("Europe/Istanbul", 0, null));
        assertEquals(T0 / 1000.0, a.firstEpoch(), "a stamp that names UTC is not moved by the zone the user chose");
        assertEquals(T0 / 1000.0 + 1, a.lastEpoch());
        assertTrue(p.notes().containsKey("EXPLICIT_OFFSETS"));
    }

    // ---- syslog ---------------------------------------------------------------------------------------------

    @Test
    void syslogKeepsCountsPerSecondAndProgramNamesButNeverTheMessageText() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.syslogClassic());
        assertEquals(TelemetryFormat.SYSLOG, p.format());
        assertEquals("srv-a", p.hostName());
        Series count = p.series().get("syslog.count");
        assertEquals(3, count.size());
        assertEquals(1.0, count.value(0));
        assertEquals(3.0, count.value(1));
        assertEquals(2.0, count.value(2));
        assertEquals(3L, p.programs().get("kernel"));
        assertEquals(1L, p.programs().get("sshd"));
        assertEquals(1L, p.skipReasons().get("NO_TIMESTAMP"));
        assertTrue(p.notes().containsKey("YEAR_FROM_CAPTURE"));
        String everything = p.series().keySet() + "" + p.programs().keySet() + p.hostName();
        assertFalse(everything.contains("secret-user") || everything.contains("192.0.2.77") || everything.contains("conntrack"));
    }

    @Test
    void syslogYearComesFromTheYearNearestTheReferenceTime() throws IOException {
        String text = "Dec 31 23:59:59 h a: x\nJan  1 00:00:01 h a: y\nJan  1 00:00:02 h a: z\n";
        long newYear = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli();
        ParsedTelemetry p = TelemetryParser.parse(new ByteArrayInputStream(bytes(text)), new TelemetryParser.Context(newYear));
        Series s = p.series().get("syslog.count");
        assertEquals(Instant.parse("2026-12-31T23:59:59Z").toEpochMilli(), s.wall(0));
        assertEquals(Instant.parse("2027-01-01T00:00:01Z").toEpochMilli(), s.wall(1));
    }

    @Test
    void syslogWithIsoStampsCarriesItsOwnOffset() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.syslogIso());
        AlignedTelemetry a = AlignedTelemetry.align(p, Alignment.assumedUtc());
        assertEquals(Instant.parse("2026-10-08T07:00:00Z").toEpochMilli() / 1000.0, a.firstEpoch());
        Double[] grid = a.place(p.series().get("syslog.count"), Instant.parse("2026-10-08T07:00:00Z").toEpochMilli() / 1000.0, 1.0, 3);
        assertEquals(2.0, grid[0]);
        assertEquals(2.0, grid[1]);
        assertNull(grid[2]);
    }

    // ---- recognition ----------------------------------------------------------------------------------------

    @Test
    void theFormatComesFromTheContentAndUnknownFilesGetAReason() throws IOException {
        assertEquals(TelemetryFormat.VMSTAT, TelemetryParser.detect(new ByteArrayInputStream(bytes(TelemetryFixtures.vmstatPlain()))).format());
        TelemetryParser.Detection csv = TelemetryParser.detect(new ByteArrayInputStream(bytes("a,b,c\n1,2,3\n")));
        assertEquals(TelemetryFormat.UNKNOWN, csv.format());
        assertEquals("NO_KNOWN_FORMAT", csv.reason());
        assertEquals("EMPTY", TelemetryParser.detect(new ByteArrayInputStream(bytes("\n\n  \n"))).reason());
        assertEquals("NO_KNOWN_BLOCK", TelemetryParser.detect(new ByteArrayInputStream(bytes(
                "Linux 5.14.0 (h) \t2026-10-08 \t_x86_64_\t(8 CPU)\n\n10:00:00 kbmemfree kbavail kbmemused\n"))).reason());
        ParsedTelemetry unknown = parse("just some prose\nwith two lines\n");
        assertFalse(unknown.recognised());
        assertEquals("NO_KNOWN_FORMAT", unknown.reason());
        assertEquals(2, unknown.linesTotal());
        assertNotNull(unknown.reason());
        assertTrue(AlignedTelemetry.align(unknown, Alignment.assumedUtc()).unusableReason() != null);
    }

    @Test
    void aFewSyslogLikeLinesInProseAreNotEnoughButMostlySyslogIsRecognised() throws IOException {
        String prose = "Oct  8 10:00:00 h a: x\nsome text\nmore text\nmore text\nmore text\nmore text\n";
        assertEquals(TelemetryFormat.UNKNOWN, TelemetryParser.detect(new ByteArrayInputStream(bytes(prose))).format());
        assertEquals(TelemetryFormat.SYSLOG, TelemetryParser.detect(new ByteArrayInputStream(bytes(TelemetryFixtures.syslogClassic()))).format());
    }

    @Test
    void hugeLinesAreCutAndCountedNotBuffered() throws IOException {
        String longLine = "x".repeat(40_000);
        String text = "Oct  8 10:00:00 h a: x\nOct  8 10:00:01 h a: y\nOct  8 10:00:02 h a: z\n" + longLine + "\nOct  8 10:00:03 h a: w\n";
        ParsedTelemetry p = parse(text);
        assertEquals(1L, p.skipReasons().get("LINE_TOO_LONG"));
        assertEquals(4, p.series().get("syslog.count").size());
    }

    @Test
    void crlfBomAndNonUtf8BytesAreTolerated() throws IOException {
        byte[] body = (TelemetryFixtures.vmstatPlain().replace("\n", "\r\n")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        ParsedTelemetry p = TelemetryParser.parse(new ByteArrayInputStream(withBom), TelemetryParser.Context.none());
        assertEquals(TelemetryFormat.VMSTAT, p.format());
        assertEquals(2, p.series().get("vmstat.r").size());
        byte[] latin1 = ("Oct  8 10:00:00 h café: x\nOct  8 10:00:01 h b: y\nOct  8 10:00:02 h b: z\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(TelemetryFormat.SYSLOG, TelemetryParser.parse(new ByteArrayInputStream(latin1), TelemetryParser.Context.none()).format());
    }

    @Test
    void aFileIsCutOffAtTheSampleCapAndTheRestIsCountedNotKept() throws IOException {
        StringBuilder rows = new StringBuilder("procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----\n"
                + " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st\n");
        for (int i = 0; i < 40; i++) {
            rows.append(" 5  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n");
        }
        ParsedTelemetry p = TelemetryParser.parse(new ByteArrayInputStream(bytes(rows.toString())), new TelemetryParser.Context(T0), 100);
        assertTrue(p.series().get("vmstat.r").size() < 40, "the cap stopped the reading");
        assertTrue(p.series().get("vmstat.r").size() >= 10);
        assertTrue(p.notes().containsKey("SAMPLE_CAP"));
        assertTrue(p.skipReasons().get("SAMPLE_CAP") > 0);
        assertEquals(p.linesTotal(), p.linesUsed() + p.linesIgnored() + p.linesSkipped());
        ParsedTelemetry whole = parse(rows.toString());
        assertEquals(39, whole.series().get("vmstat.r").size());
        assertFalse(whole.notes().containsKey("SAMPLE_CAP"));
    }

    // ---- alignment ------------------------------------------------------------------------------------------

    @Test
    void theZoneAndTheClockOffsetMoveTheSamplesAndAssumptionsAreVisible() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.vmstatStamped());
        Alignment assumed = Alignment.assumedUtc();
        assertFalse(assumed.zoneConfirmed());
        assertEquals("UTC", assumed.zoneLabel());
        double utc = AlignedTelemetry.align(p, assumed).firstEpoch();
        Alignment istanbul = Alignment.of("Europe/Istanbul", 0, null);
        assertTrue(istanbul.zoneConfirmed());
        assertEquals(utc - 3 * 3600, AlignedTelemetry.align(p, istanbul).firstEpoch(), "10:00 written in +03 is 07:00 UTC");
        assertEquals(utc - 3 * 3600 + 5, AlignedTelemetry.align(p, Alignment.of("Europe/Istanbul", 5, null)).firstEpoch());
        assertEquals(utc - 3 * 3600, AlignedTelemetry.align(p, Alignment.of("+03:00", 0, null)).firstEpoch());
    }

    @Test
    void invalidAlignmentInputIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Alignment.of("Mars/Olympus", 0, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Alignment.of("../etc", 0, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Alignment.of("UTC", 90_000, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Alignment.of("UTC", 0, "yesterday"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Alignment.of("UTC", 0, "2026-13-40 10:00:00"));
        assertEquals("UTC", Alignment.of("  ", 0, " ").zoneLabel());
        assertFalse(Alignment.of(null, 0, null).zoneConfirmed());
    }

    @Test
    void gaugesShowTheLastSampleOfABucketAndTheGridIgnoresSamplesOutsideIt() throws IOException {
        ParsedTelemetry p = parse(TelemetryFixtures.vmstatStamped());
        AlignedTelemetry a = AlignedTelemetry.align(p, Alignment.assumedUtc());
        Double[] grid = a.place(p.series().get("vmstat.r"), T0 / 1000.0, 2.0, 2);
        assertEquals(555.0, grid[0], "10:00:00 and 10:00:01 share a 2 s bucket: the later sample");
        assertEquals(886.0, grid[1]);
        Double[] shifted = a.place(p.series().get("vmstat.r"), T0 / 1000.0 + 100, 1.0, 5);
        for (Double v : shifted) {
            assertNull(v);
        }
    }
}
