package com.securityexpert.nexus.ui2.worker.systemlog;

import static com.securityexpert.nexus.ui2.worker.systemlog.Fixtures.bytes;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LineBoundaryTest {

    private static LineBoundary.Trim trailing(String s, boolean fullCap, boolean finalFile) {
        byte[] b = bytes(s);
        return LineBoundary.trailing(b, 0, b.length, fullCap, finalFile);
    }

    @Test
    void theChunkEndsAtTheLastNewlineAndThePartialLineIsCarried() {
        LineBoundary.Trim t = trailing("one\ntwo\npart", false, false);
        assertEquals(8, t.storedBytes());
        assertEquals(4, t.carriedBytes());
        assertFalse(t.lineSplit());
    }

    @Test
    void aPayloadEndingInANewlineCarriesNothing() {
        LineBoundary.Trim t = trailing("one\ntwo\n", false, false);
        assertEquals(8, t.storedBytes());
        assertEquals(0, t.carriedBytes());
    }

    @Test
    void aReadWithNoNewlineStoresNothingAndCarriesEverythingBelowTheCap() {
        LineBoundary.Trim t = trailing("no newline yet", false, false);
        assertEquals(0, t.storedBytes());
        assertEquals(14, t.carriedBytes());
        assertFalse(t.lineSplit());
    }

    @Test
    void aFullCapReadWithoutANewlineIsStoredAsIsAndFlaggedLineSplit() {
        LineBoundary.Trim t = trailing("x".repeat(100), true, false);
        assertEquals(100, t.storedBytes());
        assertEquals(0, t.carriedBytes());
        assertTrue(t.lineSplit());
    }

    @Test
    void aFullCapReadWithANewlineIsTrimmedNormally() {
        LineBoundary.Trim t = trailing("aaa\nbbbbbbb", true, false);
        assertEquals(4, t.storedBytes());
        assertEquals(7, t.carriedBytes());
        assertFalse(t.lineSplit());
    }

    @Test
    void aRotatedFileReadToItsEndKeepsALastLineWithoutANewline() {
        LineBoundary.Trim t = trailing("one\ntwo", false, true);
        assertEquals(7, t.storedBytes());
        assertEquals(0, t.carriedBytes());
        assertEquals(3, trailing("abc", false, true).storedBytes());
    }

    @Test
    void emptyAndOffsetRanges() {
        assertEquals(new LineBoundary.Trim(0, 0, false), trailing("", true, false));
        byte[] buffer = bytes("LEADIN--one\ntwo");
        LineBoundary.Trim t = LineBoundary.trailing(buffer, 8, buffer.length - 8, false, false);
        assertEquals(4, t.storedBytes());
        assertEquals(3, t.carriedBytes());
    }

    @Test
    void aBadRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LineBoundary.trailing(new byte[3], 2, 5, false, false));
        assertThrows(IllegalArgumentException.class, () -> LineBoundary.trailing(null, 0, 0, false, false));
    }

    @Test
    void aFirstRunReadThatStartsOnALineStartSkipsNothing() {
        byte[] b = bytes("prev line\nfirst\nsecond\n");
        // lead-in "prev line\n" (10 bytes) ends with a newline: the payload starts a line
        LineBoundary.Leading l = LineBoundary.leading(b, 10, b.length - 10, false);
        assertEquals(0, l.skippedBytes());
        assertEquals(0, LineBoundary.leading(bytes("first\n"), 0, 6, false).skippedBytes());
    }

    @Test
    void aFirstRunReadThatStartsMidLineSkipsThePartialFirstLine() {
        byte[] b = bytes("...prev line tail\nfirst\n");
        int leadIn = 10; // "...prev li" ends inside a line
        LineBoundary.Leading l = LineBoundary.leading(b, leadIn, b.length - leadIn, false);
        assertEquals("ne tail\n".length(), l.skippedBytes());
        assertFalse(l.lineSplit());
    }

    @Test
    void aFirstRunPayloadWithoutAnyNewlineIsFlaggedWhenFullCapAndSkippedOtherwise() {
        byte[] b = bytes("lead|" + "y".repeat(50));
        LineBoundary.Leading cap = LineBoundary.leading(b, 5, 50, true);
        assertEquals(0, cap.skippedBytes());
        assertTrue(cap.lineSplit());
        LineBoundary.Leading shorter = LineBoundary.leading(b, 5, 50, false);
        assertEquals(50, shorter.skippedBytes());
        assertFalse(shorter.lineSplit());
    }
}
