package com.securityexpert.nexus.ui2.worker.systemlog;

import static com.securityexpert.nexus.ui2.worker.systemlog.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Synthetic lines in the measured shapes (contract section 4.7); no real host names or addresses. */
class LineTimesTest {
    /** 2025-10-17 11:20:00 UTC: the "device clock" of these tests. */
    private static final long CLOCK = NOW;

    private static LineTimes.ChunkTimes times(SourceFamily f, String text) {
        return LineTimes.extract(f, bytes(text), CLOCK, CFG);
    }

    private static LineTime at(int y, int mo, int d, int h, int mi, int s) {
        return LineTime.of(LocalDateTime.of(y, mo, d, h, mi, s));
    }

    @Test
    void theClockOfTheTestsIsWhatTheTestsAssume() {
        assertEquals(LocalDateTime.of(2025, 10, 17, 11, 20, 0), LocalDateTime.ofInstant(Instant.ofEpochSecond(CLOCK), ZoneOffset.UTC));
    }

    @Test
    void messagesLinesCarryTheYear() {
        LineTimes.ChunkTimes t = times(SourceFamily.CP_MESSAGES,
                "Oct 17 06:05:01 2026 fw-synth clish[1234]: user logged in\n"
                + "Oct 17 06:05:09 2026 fw-synth expert[77]: command\n"
                + "Oct 17 07:30:00 2026 fw-synth kernel: note\n");
        assertEquals(at(2026, 10, 17, 6, 5, 1), t.first());
        assertEquals(at(2026, 10, 17, 7, 30, 0), t.last());
        assertEquals("2026-10-17 06:05:01", t.first().text());
    }

    @Test
    void messagesAcceptsASpacePaddedDayAndAProgramWithoutPid() {
        LineTimes.ChunkTimes t = times(SourceFamily.CP_MESSAGES, "Mar  5 00:00:07 2026 fw-synth cpd: started\n");
        assertEquals(at(2026, 3, 5, 0, 0, 7), t.first());
        assertEquals(t.first(), t.last());
    }

    @Test
    void anUnrecognisedMonthTokenMeansTheTimeIsUnknown() {
        LineTimes.ChunkTimes t = times(SourceFamily.CP_MESSAGES, "Xyz 17 06:05:01 2026 fw-synth cpd: started\n");
        assertEquals(LineTimes.ChunkTimes.UNKNOWN, t);
        assertFalse(t.first().known());
        assertNull(t.first().text());
    }

    @Test
    void impossibleDatesAndTimesAreUnknownNotAnException() {
        assertEquals(LineTimes.ChunkTimes.UNKNOWN, times(SourceFamily.CP_MESSAGES, "Feb 30 06:05:01 2026 h p: x\n"));
        assertEquals(LineTimes.ChunkTimes.UNKNOWN, times(SourceFamily.CP_MESSAGES, "Oct 17 25:61:61 2026 h p: x\n"));
        assertEquals(LineTimes.ChunkTimes.UNKNOWN, times(SourceFamily.CP_FWD_ELG, "[fwd 1 2]@h[32 Oct 01:02:03] x\n"));
    }

    @Test
    void elgLinesTakeTheYearFromTheDeviceClock() {
        LineTimes.ChunkTimes t = times(SourceFamily.CP_CPD_ELG,
                "[cpd 4242 140234]@fw-synth[17 Oct 09:59:59] connection ok\n"
                + "[cpd 4242 140234]@fw-synth[17 Oct 10:30:00] second\n");
        assertEquals(at(2025, 10, 17, 9, 59, 59), t.first());
        assertEquals(at(2025, 10, 17, 10, 30, 0), t.last());
    }

    @Test
    void aYearlessDateAfterTheClockBelongsToTheYearBefore() {
        assertEquals(at(2024, 12, 31, 23, 59, 59),
                times(SourceFamily.CP_CPWD_ELG, "[cpwd 1 2]@h[31 Dec 23:59:59] restart\n").first());
        assertEquals(at(2024, 10, 19, 1, 2, 3),
                times(SourceFamily.CP_CPWD_ELG, "[cpwd 1 2]@h[19 Oct 01:02:03] restart\n").first());
    }

    @Test
    void theSlackAcceptsTomorrowInTheDevicesOwnZone() {
        // 18 Oct is one day after the UTC date of the clock: a device ahead of UTC may already be there
        assertEquals(at(2025, 10, 18, 0, 5, 0),
                times(SourceFamily.CP_CPWD_ELG, "[cpwd 1 2]@h[18 Oct 00:05:00] restart\n").first());
        PlannerConfig noSlack = new PlannerConfig(CFG.readCapBytes(), CFG.sourceBudgetBytes(), CFG.deviceBudgetBytes(),
                CFG.backfillBytes(), CFG.anchorBytes(), CFG.siblingSuffixRegex(), ".gz", 9, 0);
        assertEquals(at(2024, 10, 18, 0, 5, 0), LineTimes.extract(SourceFamily.CP_CPWD_ELG,
                bytes("[cpwd 1 2]@h[18 Oct 00:05:00] restart\n"), CLOCK, noSlack).first());
    }

    @Test
    void the29thOfFebruaryUsesTheLatestLeapYear() {
        assertEquals(at(2024, 2, 29, 12, 0, 0),
                times(SourceFamily.CP_FWD_ELG, "[fwd 1 2]@h[29 Feb 12:00:00] x\n").first());
    }

    @Test
    void continuationLinesNeverGiveATime() {
        LineTimes.ChunkTimes t = times(SourceFamily.CP_CPD_ELG,
                "    continuation of a multi-line record\n"
                + "[cpd 4242 140234]@fw-synth[17 Oct 09:59:59] first\n"
                + "    more detail with [17 Oct 11:11:11] inside the text\n"
                + "[cpd 4242 140234]@fw-synth[17 Oct 10:10:10] last\n"
                + "    trailing detail\n");
        assertEquals(at(2025, 10, 17, 9, 59, 59), t.first());
        assertEquals(at(2025, 10, 17, 10, 10, 10), t.last());
    }

    @Test
    void fwdLinesMostlyHaveNoTimestampAndAChunkWithoutOneHasBothTimesUnknown() {
        assertEquals(LineTimes.ChunkTimes.UNKNOWN,
                times(SourceFamily.CP_FWD_ELG, "plain text\nanother plain text\nmore\n"));
        LineTimes.ChunkTimes t = times(SourceFamily.CP_FWD_ELG,
                "plain\n[fwd 99 98]@fw-synth[17 Oct 08:00:00] header line\nplain\n");
        assertEquals(at(2025, 10, 17, 8, 0, 0), t.first());
        assertEquals(t.first(), t.last());
    }

    @Test
    void fwkConnectionLinesCarryTheTimestampInTheLeadingBrackets() {
        LineTimes.ChunkTimes t = times(SourceFamily.CP_FWK_ELG,
                "[17 Oct 05:00:01][fw_0];[cpu_3];[192.0.2.1:1234 -> 192.0.2.2:80] [INFO]: text\n"
                + "free-form line without a time\n"
                + "[17 Oct 05:00:09][fw_1];[cpu_0];[192.0.2.3:5 -> 192.0.2.4:443] [WARN]: text\n");
        assertEquals(at(2025, 10, 17, 5, 0, 1), t.first());
        assertEquals(at(2025, 10, 17, 5, 0, 9), t.last());
    }

    @Test
    void firstAndLastFollowTheLinePositionNotTheSmallestOrLargestTime() {
        LineTimes.ChunkTimes t = times(SourceFamily.CP_CPWD_ELG,
                "[cpwd 1 2]@h[17 Oct 09:00:00] a\n[cpwd 1 2]@h[16 Oct 23:00:00] b\n");
        assertEquals(at(2025, 10, 17, 9, 0, 0), t.first());
        assertEquals(at(2025, 10, 16, 23, 0, 0), t.last());
    }

    @Test
    void notMeasuredSourcesAreUnknown() {
        for (SourceFamily f : new SourceFamily[] {SourceFamily.CP_MQ_MNG_ELG, SourceFamily.CP_SPIKE_LOG,
                SourceFamily.CP_SPIKE_CSV}) {
            assertEquals(LineTimes.ChunkTimes.UNKNOWN,
                    times(f, "[mq 1 2]@h[17 Oct 09:00:00] x\nOct 17 09:00:00 2026 h p: x\n"), f.sourceKey());
        }
    }

    @Test
    void aLineOfTheWrongFormForItsSourceIsNotATime() {
        assertEquals(LineTimes.ChunkTimes.UNKNOWN,
                times(SourceFamily.CP_MESSAGES, "[cpd 1 2]@h[17 Oct 09:00:00] x\n"));
        assertEquals(LineTimes.ChunkTimes.UNKNOWN,
                times(SourceFamily.CP_CPD_ELG, "Oct 17 09:00:00 2026 h p: x\n"));
    }

    @Test
    void crlfEmptyChunkAndMissingFinalNewline() {
        assertEquals(at(2026, 10, 17, 6, 5, 1),
                times(SourceFamily.CP_MESSAGES, "Oct 17 06:05:01 2026 h p: x\r\nOct 17 06:05:01 2026 h p: y").last());
        assertEquals(LineTimes.ChunkTimes.UNKNOWN, times(SourceFamily.CP_MESSAGES, ""));
        assertEquals(LineTimes.ChunkTimes.UNKNOWN, LineTimes.extract(SourceFamily.CP_MESSAGES, null, CLOCK, CFG));
        assertEquals(LineTimes.ChunkTimes.UNKNOWN, times(SourceFamily.CP_MESSAGES, "\n\n\n"));
    }

    @Test
    void aVeryLongLineIsOnlyInspectedAtItsHead() {
        String longTail = "x".repeat(2_000_000);
        LineTimes.ChunkTimes t = times(SourceFamily.CP_MESSAGES,
                "Oct 17 06:05:01 2026 h p: " + longTail + "\n" + longTail + "\nOct 17 06:05:02 2026 h p: " + longTail + "\n");
        assertEquals(at(2026, 10, 17, 6, 5, 1), t.first());
        assertEquals(at(2026, 10, 17, 6, 5, 2), t.last());
    }

    @Test
    void binaryGarbageNeverThrows() {
        byte[] noise = new byte[4096];
        new java.util.Random(7).nextBytes(noise);
        for (SourceFamily f : SourceFamily.values()) {
            assertDoesNotThrow(() -> LineTimes.extract(f, noise, CLOCK, CFG));
        }
    }

    @Test
    void parseLineHelperAndPanReceiveTime() {
        assertEquals(at(2026, 1, 2, 3, 4, 5),
                LineTimes.parseLine(SourceFamily.CP_MESSAGES, "Jan  2 03:04:05 2026 h p: x", CLOCK, CFG));
        assertEquals("2026/10/17 06:05:01", LineTimes.panReceiveTime("2026/10/17 06:05:01"));
        assertNull(LineTimes.panReceiveTime("2026-10-17 06:05:01"));
        assertNull(LineTimes.panReceiveTime("2026/10/17 06:05:01 "));
        assertNull(LineTimes.panReceiveTime(null));
    }
}
