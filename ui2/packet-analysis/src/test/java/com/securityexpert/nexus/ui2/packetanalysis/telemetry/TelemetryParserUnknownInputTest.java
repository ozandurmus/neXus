package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import static com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pins how the telemetry parsers treat empty, header-only, malformed and unrecognised input: the outcome is UNKNOWN, a
 * reason code, or a skipped-line count. A bad value is never read as zero and never throws. Inputs are derived from
 * {@link TelemetryFixtures} only.
 */
class TelemetryParserUnknownInputTest {
    private static final long T0 = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli();

    private static ParsedTelemetry parse(String text) throws IOException {
        return TelemetryParser.parse(new ByteArrayInputStream(bytes(text)), new TelemetryParser.Context(T0));
    }

    private static String[] lines(String text) {
        return text.split("\n", -1);
    }

    private static void assertNoSamplesAndNoZero(ParsedTelemetry p) {
        assertFalse(p.hasSamples(), "no sample may be produced from this input");
        for (Series s : p.series().values()) {
            assertEquals(0, s.size(), "no series may hold a value for " + s.key());
        }
    }

    // ---- empty input ----------------------------------------------------------------------------------------

    @Test
    void emptyInputIsUnknownWithReasonEmptyAndNoSamples() throws IOException {
        for (String text : new String[] {"", "\n", "\n\n  \n\t\n"}) {
            ParsedTelemetry p = parse(text);
            assertFalse(p.recognised(), "empty input is not a format");
            assertEquals(TelemetryFormat.UNKNOWN, p.format());
            assertEquals("EMPTY", p.reason());
            assertNoSamplesAndNoZero(p);
            assertEquals(0L, p.linesUsed());
            assertTrue(AlignedTelemetry.align(p, Alignment.assumedUtc()).unusableReason() != null);
        }
    }

    // ---- header-only input ----------------------------------------------------------------------------------

    @Test
    void sarHeaderOnlyIsRecognisedButYieldsNoSampleAndNoZero() throws IOException {
        String[] sar = lines(TelemetryFixtures.sar24h());
        // banner, blank line, then the CPU block's header with no rows under it
        String headerOnly = sar[0] + "\n\n" + sar[2] + "\n";
        ParsedTelemetry p = parse(headerOnly);
        assertEquals(TelemetryFormat.SAR, p.format());
        assertNoSamplesAndNoZero(p);
        assertEquals("NO_SAMPLES", p.reason());
        assertNull(p.series().get("sar.cpu.sys"), "no series is created for a header without rows");
    }

    @Test
    void vmstatHeaderOnlyIsUnknownSamplesAndNoZero() throws IOException {
        String[] vm = lines(TelemetryFixtures.vmstatPlain());
        ParsedTelemetry p = parse(vm[0] + "\n" + vm[1] + "\n");
        assertEquals(TelemetryFormat.VMSTAT, p.format());
        assertNoSamplesAndNoZero(p);
        assertEquals("NO_SAMPLES", p.reason());
        assertNull(p.series().get("vmstat.r"));
    }

    @Test
    void vmstatHeaderPlusOnlyTheSinceBootRowHasNoSampleAndNoZero() throws IOException {
        String[] vm = lines(TelemetryFixtures.vmstatPlain());
        ParsedTelemetry p = parse(vm[0] + "\n" + vm[1] + "\n" + vm[2] + "\n");
        assertNoSamplesAndNoZero(p);
        assertTrue(p.notes().containsKey("FIRST_ROW_SKIPPED"));
        assertEquals("NO_SAMPLES", p.reason());
    }

    @Test
    void pidstatHeaderOnlyYieldsNoSampleAndNoZero() throws IOException {
        String[] pid = lines(TelemetryFixtures.pidstat());
        ParsedTelemetry p = parse(pid[0] + "\n\n" + pid[2] + "\n");
        assertEquals(TelemetryFormat.PIDSTAT, p.format());
        assertNoSamplesAndNoZero(p);
        assertEquals("NO_SAMPLES", p.reason());
    }

    @Test
    void syslogLinesWithoutAnyTimestampAreSkippedNotCountedAsZero() throws IOException {
        String[] sl = lines(TelemetryFixtures.syslogClassic());
        // only the continuation line (no time stamp) and a bare host line: nothing placeable
        ParsedTelemetry p = parse(sl[4] + "\n" + sl[4] + "\n");
        assertFalse(p.recognised(), "two lines do not make a syslog file");
        assertNoSamplesAndNoZero(p);
    }

    // ---- missing field --------------------------------------------------------------------------------------

    @Test
    void vmstatRowWithAMissingFieldIsSkippedWholeAndCounted() throws IOException {
        String text = TelemetryFixtures.vmstatPlain().replace("1403540 1396109 13 18 69  0  0", "1403540 1396109 13 18 69  0");
        ParsedTelemetry p = parse(text);
        assertEquals(TelemetryFormat.VMSTAT, p.format());
        assertEquals(1L, p.skipReasons().get("BAD_COLUMN_COUNT"));
        assertEquals(1, p.series().get("vmstat.r").size(), "the intact row still counts, the broken one does not");
        assertEquals(555.0, p.series().get("vmstat.r").value(0));
        assertEquals(1, p.series().get("vmstat.in").size(), "the broken row adds no 'in' value");
    }

    @Test
    void sarRowWithAMissingFieldIsSkippedWholeAndCounted() throws IOException {
        String text = TelemetryFixtures.sar24h().replace("10:00:03        all      6.00", "10:00:03        all");
        ParsedTelemetry p = parse(text);
        assertEquals(1L, p.skipReasons().get("BAD_COLUMN_COUNT"));
        assertEquals(3, p.series().get("sar.cpu.sys").size(), "the broken all-CPU row is not a sample");
        assertEquals(1L, p.linesSkipped());
    }

    // ---- non-numeric field ----------------------------------------------------------------------------------

    @Test
    void vmstatNonNumericMetricIsSkippedAndNeverReadAsZero() throws IOException {
        String text = TelemetryFixtures.vmstatPlain().replace("1403540 1396109", "14035x0 1396109");
        ParsedTelemetry p = parse(text);
        assertEquals(1L, p.skipReasons().get("BAD_NUMBER"));
        Series r = p.series().get("vmstat.r");
        assertEquals(1, r.size());
        assertEquals(555.0, r.value(0));
        assertEquals(1, p.series().get("vmstat.in").size(), "the bad 'in' value is not stored, not even as zero");
        assertEquals(1130827.0, p.series().get("vmstat.in").value(0));
    }

    /** Pins that an {@code all}-CPU row with a non-numeric value is skipped whole as {@code BAD_NUMBER}, never a header or zero. */
    @Test
    void sarNonNumericValueSkipsTheWholeRowAndNeverBecomesZero() throws IOException {
        String fixture = TelemetryFixtures.sar24h();
        String text = fixture.replaceFirst("(10:00:01\\s+all\\s+)7\\.00", "$17.0x");
        assertNotEquals(fixture, text, "the fixture row was found and changed");
        ParsedTelemetry p = parse(text);
        assertEquals(1L, p.skipReasons().get("BAD_NUMBER"));
        Series sys = p.series().get("sar.cpu.sys");
        assertEquals(3, sys.size(), "the broken row is absent, not 0");
        assertEquals(1L, p.linesSkipped(), "exactly the one broken line is skipped");
    }

    @Test
    void sarNonNumericValueInNonAllCpuRowIsIgnoredAndLaterAllRowsStillCount() throws IOException {
        String fixture = TelemetryFixtures.sar24h();
        String oneBad = fixture.replaceFirst("(10:00:01\\s+0\\s+)99\\.00", "$199.0x");
        assertNotEquals(fixture, oneBad);
        ParsedTelemetry p1 = parse(oneBad);
        assertEquals(0L, p1.linesSkipped(), "non-all CPU rows are ignored before value parsing");
        assertEquals(4, p1.series().get("sar.cpu.sys").size());

        String twoBad = fixture.replaceFirst("(10:00:01\\s+0\\s+)99\\.00(\\s+0\\.00\\s+)1\\.00", "$199.0x$21.0x");
        assertNotEquals(fixture, twoBad);
        ParsedTelemetry p2 = parse(twoBad);
        assertEquals(0L, p2.linesSkipped(), "two non-numeric values in a non-all CPU row still do not make a header");
        assertEquals(4, p2.series().get("sar.cpu.usr").size());
        assertEquals(4, p2.series().get("sar.cpu.sys").size());
        assertEquals(47.0, p2.series().get("sar.cpu.sys").value(2));
        assertEquals(47.0, p2.series().get("sar.cpu.sys").value(3));
    }

    @Test
    void sarNonNumericValueInInterfaceRowIsBadNumberAndLaterInterfaceRowsStillRead() throws IOException {
        String fixture = TelemetryFixtures.sar24h();
        String text = fixture.replaceFirst("(10:00:01\\s+eth1\\s+)370141\\.00", "$1370141.0x");
        assertNotEquals(fixture, text);
        ParsedTelemetry p = parse(text);
        assertEquals(1L, p.skipReasons().get("BAD_NUMBER"));
        assertEquals(1L, p.linesSkipped());
        Series eth1Rx = p.series().get("sar.rxpck/s|eth1");
        Series eth1Tx = p.series().get("sar.txpck/s|eth1");
        Series loRx = p.series().get("sar.rxpck/s|lo");
        assertEquals(3, eth1Rx.size(), "later eth1 rows in the DEV block still read");
        assertEquals(3, eth1Tx.size(), "the broken eth1 row stores nothing in sibling columns");
        assertEquals(390141.0, eth1Rx.value(0));
        assertEquals(350141.0, eth1Rx.value(1));
        assertEquals(330141.0, eth1Rx.value(2));
        assertEquals(4, loRx.size(), "same-timestamp and later lo rows in the DEV block still read");
    }

    @Test
    void sarUnknownBlockHeaderBetweenKnownBlocksSwitchesToOtherAndNextKnownHeaderResumes() throws IOException {
        String banner = lines(TelemetryFixtures.sar24h())[0];
        String text = banner + "\n\n"
                + "09:59:59     active/s passive/s    iseg/s    oseg/s\n"
                + "10:00:00         1.00      2.00    100.00    200.00\n"
                + "10:00:01        66.00    141.00 165084.00 390141.00\n\n"
                + "09:59:59    kbmemfree   kbavail kbmemused kbbuffers\n"
                + "10:00:00      8000000   7500000    500000    100000\n"
                + "10:00:01      8000000   7500000    500000    100000\n\n"
                + "09:59:59       call/s retrans/s    read/s   write/s  access/s  getatt/s\n"
                + "10:00:00        12.00     77.00      1.00      2.00      3.00      4.00\n\n"
                + "09:59:59     atmptf/s  estres/s retrans/s isegerr/s   orsts/s\n"
                + "10:00:02         0.00      0.00   1978.00      0.00      0.00\n"
                + "10:00:03         0.00      1.00   3316.00      0.00      0.00\n";
        ParsedTelemetry p = parse(text);
        assertEquals(TelemetryFormat.SAR, p.format());
        assertEquals(0L, p.linesSkipped(), "unknown block headers and their rows are ignored, never skipped");
        assertEquals(2, p.series().get("sar.active/s").size(), "unknown block with 4 columns is not mis-read as TCP");
        assertEquals(2, p.series().get("sar.retrans/s").size(), "known block after unknown blocks resumes cleanly");
        assertEquals(1978.0, p.series().get("sar.retrans/s").value(0));
        assertEquals(3316.0, p.series().get("sar.retrans/s").value(1));
    }

    @Test
    void sarHeaderLookingLineWithFewerThanTwoNonNumericTokensIsNotAHeader() throws IOException {
        assertFalse(SarParser.isKnownHeader("10:00:00 runq-sz 1 2 3 4 5"));
        assertFalse(SarParser.isKnownHeader("10:00:00 %user"));
        assertNull(SarParser.classify(new String[] {"runq-sz", "1", "2", "3", "4", "5"}));
        assertNull(SarParser.classify(new String[] {"%user"}));

        String banner = lines(TelemetryFixtures.sar24h())[0];
        ParsedTelemetry unrecognised = parse(banner + "\n\n10:00:00 runq-sz 1 2 3 4 5\n10:00:01 10 20 30 40 50 60\n");
        assertFalse(unrecognised.recognised());
        assertEquals("NO_KNOWN_BLOCK", unrecognised.reason());
        assertNoSamplesAndNoZero(unrecognised);

        ParsedTelemetry inBlock = parse(banner + "\n\n"
                + "09:59:59      runq-sz  plist-sz   ldavg-1   ldavg-5  ldavg-15   blocked\n"
                + "10:00:00          317      2955     12.50     10.20      9.80         0\n"
                + "10:00:01      runq-sz      2955     12.50     10.20      9.80         0\n"
                + "10:00:02        %user\n"
                + "10:00:03          900      2955     12.50     10.20      9.80         0\n");
        assertEquals(1L, inBlock.skipReasons().get("BAD_NUMBER"));
        assertEquals(1L, inBlock.skipReasons().get("BAD_COLUMN_COUNT"));
        assertEquals(2, inBlock.series().get("sar.runq").size());
        assertEquals(317.0, inBlock.series().get("sar.runq").value(0));
        assertEquals(900.0, inBlock.series().get("sar.runq").value(1));
    }

    @Test
    void sarRowsAfterBadRowInSameBlockAreStillSamples() throws IOException {
        String fixture = TelemetryFixtures.sar24h();
        String text = fixture.replaceFirst("(10:00:00\\s+all\\s+)13\\.00", "$113.0x");
        assertNotEquals(fixture, text);
        ParsedTelemetry p = parse(text);
        assertEquals(1L, p.skipReasons().get("BAD_NUMBER"));
        assertEquals(1L, p.linesSkipped());
        assertEquals(3, p.series().get("sar.cpu.usr").size());
        assertEquals(3, p.series().get("sar.cpu.sys").size());
        assertEquals(3, p.series().get("sar.cpu.iowait").size());
        assertEquals(3, p.series().get("sar.cpu.idle").size());
        assertEquals(7.0, p.series().get("sar.cpu.usr").value(0));
        assertEquals(6.0, p.series().get("sar.cpu.usr").value(1));
        assertEquals(6.0, p.series().get("sar.cpu.usr").value(2));
        assertEquals(36.0, p.series().get("sar.cpu.sys").value(0));
        assertEquals(47.0, p.series().get("sar.cpu.sys").value(1));
        assertEquals(47.0, p.series().get("sar.cpu.sys").value(2));
        assertEquals(4, p.series().get("sar.runq").size());
        assertEquals(4, p.series().get("sar.proc/s").size());
        assertEquals(4, p.series().get("sar.rxpck/s|eth1").size());
        assertEquals(4, p.series().get("sar.active/s").size());
        assertEquals(4, p.series().get("sar.retrans/s").size());
    }

    // ---- unrecognised format --------------------------------------------------------------------------------

    @Test
    void unrecognisedTextIsUnknownWithReasonAndNoSamples() throws IOException {
        ParsedTelemetry p = parse("just some prose\nwith two lines\nand a third\n");
        assertFalse(p.recognised());
        assertEquals(TelemetryFormat.UNKNOWN, p.format());
        assertEquals("NO_KNOWN_FORMAT", p.reason());
        assertNoSamplesAndNoZero(p);
        assertNotNull(AlignedTelemetry.align(p, Alignment.assumedUtc()).unusableReason());
    }

    @Test
    void aBannerWithNoKnownBlockIsUnknownNotAFormat() throws IOException {
        String banner = TelemetryFixtures.sar24h().split("\n", -1)[0] + "\n";
        ParsedTelemetry p = parse(banner + "\n10:00:00 kbmemfree kbavail kbmemused\n10:00:01 1 2 3\n");
        assertFalse(p.recognised());
        assertEquals("NO_KNOWN_BLOCK", p.reason());
        assertNoSamplesAndNoZero(p);
    }
}
