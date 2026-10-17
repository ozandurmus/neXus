package com.securityexpert.nexus.ui2.worker.systemlog;

import static com.securityexpert.nexus.ui2.worker.systemlog.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatParserTest {

    @Test
    void parsesClockAndRecordsByShape() {
        StatParseResult r = StatParser.parse("1760700000\n/var/log/messages 10 4096 1760699900\n"
                + "/var/log/messages.1 9 2048 1760600000\n");
        assertEquals(1_760_700_000L, r.deviceEpoch());
        assertEquals(List.of(new FileStat("/var/log/messages", 10, 4096, 1_760_699_900L),
                new FileStat("/var/log/messages.1", 9, 2048, 1_760_600_000L)), r.files());
        assertEquals(0, r.leadingNonDataLines());
        assertEquals(0, r.malformedLines());
        assertTrue(r.usable());
    }

    @Test
    void toleratesTheLeadingNonDataLineOf25Gateways() {
        StatParseResult r = StatParser.parse("Welcome banner for FW-TANGO-04 from 192.0.2.77\n1760700000\n"
                + "/var/log/messages 10 4096 1760699900\n");
        assertEquals(1_760_700_000L, r.deviceEpoch());
        assertEquals(1, r.files().size());
        assertEquals(1, r.leadingNonDataLines());
        assertEquals(0, r.malformedLines());
        assertEquals(0, r.ignoredLines());
    }

    @Test
    void parsesByShapeNeverByPosition() {
        // banner lines before and after the clock, a blank line, CRLF endings, a record before the clock
        StatParseResult r = StatParser.parse("banner one\r\n\r\n/var/log/messages 10 5 6\r\n1760700000\r\n"
                + "trailing note\r\n/var/log/messages.1 9 7 8\r\n");
        assertEquals(1_760_700_000L, r.deviceEpoch());
        assertEquals(2, r.files().size());
        assertEquals(1, r.leadingNonDataLines());
        assertEquals(1, r.ignoredLines());
    }

    @Test
    void clockIsTheFirstLineOfOnlyNineToElevenDigits() {
        StatParseResult r = StatParser.parse("12345678\n17607000001\n1760700000\n/var/log/messages 1 2 3\n");
        // 8 digits is not a clock; 11 digits is; a second digits-only line is just ignored
        assertEquals(17_607_000_001L, r.deviceEpoch());
        assertEquals(1, r.leadingNonDataLines());
        assertEquals(1, r.ignoredLines());
    }

    @Test
    void noClockLineMeansUnreadable() {
        StatParseResult r = StatParser.parse("/var/log/messages 10 4096 1760699900\n");
        assertNull(r.deviceEpoch());
        assertFalse(r.usable());
        assertFalse(StatParser.parse(null).usable());
        assertFalse(StatParser.parse("").usable());
    }

    @Test
    void clockOnlyIsUnreadableNotEightAbsentSources() {
        assertFalse(StatParser.parse("1760700000\n").usable());
    }

    @Test
    void malformedLinesAreCountedAttributedAndNeverHalfApplied() {
        StatParseResult r = StatParser.parse("1760700000\n"
                + "/var/log/messages 10 4096 1760699900\n"
                + "/var/log/messages.1 nine 2048 1760600000\n"       // non-numeric inode
                + "/opt/synth/fw/log/fwd.elg 11 -5 1760600000\n"     // negative size
                + "/var/log/spike_detective/spike_detective.log 1 2\n" // three fields
                + "/opt/synth/fw/log/cpd.elg 12 99999999999999999999 5\n"); // beyond 63 bits
        assertEquals(1, r.files().size());
        assertEquals("/var/log/messages", r.files().get(0).path());
        assertEquals(4, r.malformedLines());
        assertEquals(java.util.Set.of(SourceFamily.CP_MESSAGES, SourceFamily.CP_FWD_ELG, SourceFamily.CP_SPIKE_LOG,
                SourceFamily.CP_CPD_ELG), r.malformedFamilies());
    }

    @Test
    void aDuplicatedPathIsAmbiguousAndNeitherOccurrenceIsApplied() {
        StatParseResult r = StatParser.parse("1760700000\n/var/log/messages 10 100 5\n/var/log/messages 11 200 6\n"
                + "/var/log/messages 12 300 7\n/var/log/messages.1 9 50 4\n");
        assertEquals(List.of("/var/log/messages.1"), r.files().stream().map(FileStat::path).toList());
        assertEquals(3, r.malformedLines());
        assertTrue(r.malformedFamilies().contains(SourceFamily.CP_MESSAGES));
    }

    @Test
    void errorLinesAreNotMalformedAndAreClassifiedByReason() {
        StatParseResult r = StatParser.parse("1760700000\n"
                + "stat: cannot statx '/var/log/messages*': No such file or directory\n"
                + "stat: cannot statx '/log/fwk.elg*': No such file or directory\n"
                + "stat: cannot statx '/var/log/spike_detective/spike_detective.csv*': Permission denied\n"
                + "/var/log/spike_detective/spike_detective.log 4 5 6\n");
        assertEquals(3, r.errorLines());
        assertEquals(0, r.malformedLines());
        assertEquals(java.util.Set.of(SourceFamily.CP_MESSAGES, SourceFamily.CP_FWK_ELG), r.absentFamilies());
        assertEquals(java.util.Set.of(SourceFamily.CP_SPIKE_CSV), r.errorFamilies());
        assertTrue(r.usable());
    }

    @Test
    void membersAreGroupedByTheFamilyGlob() {
        StatParseResult r = StatParser.parse(render(NOW, List.of(msg("messages", 1, 1), msg("messages.1", 2, 1),
                file(FWD_DIR + "fwd.elg", 3, 1), file(FWD_DIR + "fwd.elg.1", 4, 1),
                file(FWD_DIR + "cpd.elg", 5, 1))));
        assertEquals(2, r.membersOf(SourceFamily.CP_MESSAGES).size());
        assertEquals(2, r.membersOf(SourceFamily.CP_FWD_ELG).size());
        assertEquals(1, r.membersOf(SourceFamily.CP_CPD_ELG).size());
        assertEquals(0, r.membersOf(SourceFamily.CP_CPWD_ELG).size());
    }

    @Test
    void noTextOfAnyLineSurvivesInTheResult() {
        StatParseResult r = StatParser.parse("secret-banner-FW-TANGO-04\n1760700000\n/var/log/messages 10 4096 5\n"
                + "/var/log/messages.1 bad 1 2\n");
        assertFalse(r.toString().contains("secret-banner"));
        assertFalse(r.toString().contains("FW-TANGO-04"));
    }
}
