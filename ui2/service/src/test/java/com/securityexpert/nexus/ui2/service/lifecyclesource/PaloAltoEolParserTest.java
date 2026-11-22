package com.securityexpert.nexus.ui2.service.lifecyclesource;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Palo Alto software and hardware end-of-life parsers on invented pages. The table structure follows the vendor's
 * pages; the rows are fictional. Complements the reference cases in {@link LifecyclePageParsersTest}: the full date set of
 * a well-formed row, unreadable and placeholder dates, and unrecognised input.
 */
class PaloAltoEolParserTest {

    private static byte[] html(String body) {
        return ("<!DOCTYPE html><html><head><title>Fictional page</title></head><body>" + body + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, LifecyclePage.Entry> byKey(LifecyclePage.Result result) {
        return result.entries().stream().collect(Collectors.toMap(e -> e.kind() + " " + e.productKey(), e -> e));
    }

    private static String softwareTable(String row) {
        return "<table id=\"pan-os-panorama\"><thead>"
                + "<tr><th colspan=\"5\"><p><b>PAN-OS &amp; Panorama</b></p></th></tr>"
                + "<tr><th><b>Version</b></th><th><b>Release Date</b></th><th colspan=\"3\"><b>End-of-Life Date</b></th></tr>"
                + "<tr><th>&nbsp;</th><th>&nbsp;</th><th>Standard Support</th><th>&nbsp;</th><th>Extended Support</th></tr>"
                + "</thead><tbody>" + row + "</tbody></table>";
    }

    private static String hardwareTable(String row) {
        return "<table><tbody><tr><th>End-of-Sale Product</th><th>End-of-Sale Date</th><th>End-of-Life Date</th>"
                + "<th>Resources</th><th>Last Supported OS</th></tr>" + row + "</tbody></table>";
    }

    @Test
    void wellFormedSoftwareRowGivesTheTrainAndItsExpectedDates() {
        var result = PaloAltoEolParser.software(html(softwareTable(
                "<tr><td>13.1</td><td>May 4, 2030</td><td>May 4, 2033</td><td></td><td>May 4, 2034</td></tr>")));

        assertTrue(result.recognised());
        assertEquals(0, result.rowsSkipped());
        assertEquals(1, result.entries().size());
        var e = result.entries().getFirst();
        assertEquals("SOFTWARE", e.kind());
        assertEquals("PAN-OS 13.1", e.productKey());
        assertEquals(LifecyclePage.PARSED, e.parseState());
        assertEquals(LocalDate.of(2033, 5, 4), e.date("end_of_support"));
        assertEquals(LocalDate.of(2034, 5, 4), e.date("end_of_extended_support"));
    }

    @Test
    void anUnreadableSoftwareEndOfLifeDateIsNeedsReviewNeverACurrentDate() {
        var result = PaloAltoEolParser.software(html(softwareTable(
                "<tr><td>13.2</td><td>May 4, 2030</td><td>2033-02-30</td><td></td><td></td></tr>")));
        var e = byKey(result).get("SOFTWARE PAN-OS 13.2");
        assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState());
        assertEquals("DATE_UNREADABLE", e.parseNote(), "an impossible calendar day is unreadable, not corrected");
        assertNull(e.date("end_of_support"));
    }

    @Test
    void aPlaceholderSoftwareEndOfLifeDateIsMilestoneMissingAndNoDateIsRead() {
        var result = PaloAltoEolParser.software(html(softwareTable(
                "<tr><td>13.3</td><td>May 4, 2030</td><td>TBA</td><td></td><td></td></tr>")));
        var e = byKey(result).get("SOFTWARE PAN-OS 13.3");
        assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState());
        assertEquals("MILESTONE_MISSING", e.parseNote());
        assertNull(e.date("end_of_support"));
    }

    @Test
    void softwareTableWithoutTheCaptionOrHeaderOrRowsIsNotRecognised() {
        assertFalse(PaloAltoEolParser.software(html("")).recognised(), "an empty page");
        assertFalse(PaloAltoEolParser.software(html("<table><tr><th>Version</th><th>Release Date</th><th>End-of-Life Date</th></tr>"
                + "<tr><td>13.1</td><td>May 4, 2030</td><td>May 4, 2033</td></tr></table>")).recognised(),
                "a table without the PAN-OS caption is not the software table");
        assertFalse(PaloAltoEolParser.software(html("<table id=\"pan-os-panorama\"><tr><th>Name</th><th>Date</th></tr>"
                + "<tr><td>13.1</td><td>May 4, 2033</td></tr></table>")).recognised(), "a table with no version header");
        var qualifiedOnly = PaloAltoEolParser.software(html(softwareTable(
                "<tr><td>9.0-XFR (VM-Series only)</td><td>May 4, 2019</td><td>May 4, 2020</td><td></td><td></td></tr>")));
        assertFalse(qualifiedOnly.recognised(), "a table whose only row is a qualified train yields no plain train claim");    }

    @Test
    void wellFormedHardwareRowGivesEachModelTheExpectedDates() {
        var result = PaloAltoEolParser.hardware(html(hardwareTable(
                "<tr><td>PAN-PA-440<br>PAN-PA-445</td><td>Mar 22, 2030</td><td>Mar 21, 2035</td><td></td><td>PAN-OS 13.1</td></tr>")));
        assertTrue(result.recognised());
        var entries = byKey(result);
        assertEquals(java.util.Set.of("HARDWARE PA-440", "HARDWARE PA-445"), entries.keySet());
        var e = entries.get("HARDWARE PA-445");
        assertEquals(LifecyclePage.PARSED, e.parseState());
        assertEquals(LocalDate.of(2030, 3, 22), e.date("end_of_sale"));
        assertEquals(LocalDate.of(2035, 3, 21), e.date("end_of_support"));
    }

    @Test
    void anUnreadableHardwareEndOfSaleDateMakesEachModelNeedsReviewWithNoDate() {
        var result = PaloAltoEolParser.hardware(html(hardwareTable(
                "<tr><td>PA-3300</td><td>around spring 2030</td><td>Mar 21, 2035</td><td></td><td></td></tr>")));
        var e = byKey(result).get("HARDWARE PA-3300");
        assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState());
        assertEquals("DATE_UNREADABLE", e.parseNote());
        assertNull(e.date("end_of_sale"));
        assertNull(e.date("end_of_support"));
    }

    @Test
    void aPlaceholderHardwareEndOfLifeDateIsMilestoneMissing() {
        var result = PaloAltoEolParser.hardware(html(hardwareTable(
                "<tr><td>PA-3300</td><td>Mar 22, 2030</td><td>TBD</td><td></td><td></td></tr>")));
        var e = byKey(result).get("HARDWARE PA-3300");
        assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState());
        assertEquals("MILESTONE_MISSING", e.parseNote());
        assertNull(e.date("end_of_support"));
    }

    @Test
    void emptyOrUnrelatedInputIsNotRecognisedByEitherParser() {
        assertFalse(PaloAltoEolParser.hardware(new byte[0]).recognised());
        assertFalse(PaloAltoEolParser.software(new byte[0]).recognised());
        assertFalse(PaloAltoEolParser.hardware(html("<p>Fictional product notes, no table</p>")).recognised());
        assertFalse(PaloAltoEolParser.hardware(html("<table><tr><th>Product</th><th>Date</th></tr>"
                + "<tr><td>PA-3300</td><td>Mar 22, 2030</td></tr></table>")).recognised(),
                "a table without the three hardware headers is not the hardware table");
    }
}
