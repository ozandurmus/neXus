package com.securityexpert.nexus.ui2.service.lifecyclesource;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * UNKNOWN and malformed-page handling of the Check Point lifecycle parser, on synthetic pages. Every test asserts the same
 * invariant: a date that the page does not state is never produced, and a page that cannot be read yields no entries and no
 * exception. The happy-path forms are pinned in {@link LifecyclePageParsersTest}; this class does not repeat them.
 */
class CheckPointLifecycleParserUnknownTest {

    private static final String SW_HEADER = "<tr><th>Major Version</th><th>General Availability</th><th>Affected Versions</th>"
            + "<th>Support Until</th></tr>";
    private static final String HW_HEADER = "<tr><th>Appliance Product/Model</th><th>End of Sale</th>"
            + "<th>End of Engineering Support</th><th>End of Support</th></tr>";

    private static byte[] page(String body) {
        return ("<html><head><title>Support Life Cycle Policy</title></head><body>" + body + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String software(String rows) {
        return "<h3>Security Gateway &amp; Management</h3><table>" + SW_HEADER + rows + "</table>";
    }

    private static String appliances(String rows) {
        return "<h3>Enterprise Appliances</h3><table>" + HW_HEADER + rows + "</table>";
    }

    private static LifecyclePage.Entry only(LifecyclePage.Result result) {
        assertEquals(1, result.entries().size(), "exactly one entry");
        return result.entries().getFirst();
    }

    @Test
    void aSoftwareRowWithoutASupportDateIsReviewedWithNoDates() {
        var result = CheckPointLifecycleParser.parse(page(software(
                "<tr><td>Check Point R82</td><td>October 2024</td><td>R82</td><td>N/A</td></tr>"
                        + "<tr><td>Check Point R81.20</td><td>November 2022</td><td>R81.20</td><td></td></tr>"
                        + "<tr><td>Check Point R81</td><td>October 2020</td><td>R81</td><td>TBD</td></tr>")));
        assertTrue(result.recognised());
        assertEquals(3, result.entries().size());
        for (LifecyclePage.Entry e : result.entries()) {
            assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState(), e.productKey());
            assertEquals("MILESTONE_MISSING", e.parseNote(), e.productKey());
            assertTrue(e.dates().isEmpty(), "no date is carried for " + e.productKey());
            assertNull(e.date("end_of_support"), e.productKey());
        }
    }

    @Test
    void anApplianceRowWithoutAnEndOfSupportDateGivesNoInventedDate() {
        // Current behaviour: the row stays PARSED (no unreadable date) and the end of support reads as NONE, so no date is
        // produced. It is not reviewed the way a software row is; see the open issue in the change report.
        var entry = only(CheckPointLifecycleParser.parse(page(appliances(
                "<tr><td>5400 Appliance</td><td>Aug-2021</td><td>Aug-2026</td><td>TBD</td></tr>"))));
        assertEquals("HARDWARE", entry.kind());
        assertEquals(LifecyclePage.PARSED, entry.parseState());
        assertEquals(VendorDate.Kind.NONE, entry.dates().get("end_of_support").kind());
        assertNull(entry.date("end_of_support"));
        assertEquals(java.time.LocalDate.of(2021, 8, 1), entry.date("end_of_sale"), "the stated sale month is still read");
    }

    @Test
    void aMalformedDateMakesTheEntryNeedsReviewAndSetsNoDate() {
        var software = only(CheckPointLifecycleParser.parse(page(software(
                "<tr><td>Check Point R82</td><td>October 2024</td><td>R82</td><td>31st of January 2031</td></tr>"))));
        assertEquals(LifecyclePage.NEEDS_REVIEW, software.parseState());
        assertEquals("DATE_UNREADABLE", software.parseNote());
        assertNull(software.date("end_of_support"));

        var appliance = only(CheckPointLifecycleParser.parse(page(appliances(
                "<tr><td>6600 Appliance</td><td>Oct-2026</td><td>Oct-2029</td><td>31/01/2031</td></tr>"))));
        assertEquals(LifecyclePage.NEEDS_REVIEW, appliance.parseState());
        assertEquals("DATE_UNREADABLE", appliance.parseNote());
        assertNull(appliance.date("end_of_support"), "the numeric day/month order is not read");
        assertNull(appliance.date("end_of_sale"), "one unreadable date holds back the readable ones too");
    }

    @Test
    void aMissingProductRowYieldsNoEntriesAndNoRecognition() {
        // A page whose lifecycle tables have a header but no product row at all.
        assertFalse(CheckPointLifecycleParser.parse(page(software("") + appliances(""))).recognised());
        // A product table whose only row names no product the parser keys (an open server, an endpoint client).
        assertFalse(CheckPointLifecycleParser.parse(page(appliances(
                "<tr><td>Open Server (any)</td><td></td><td></td><td></td></tr>"))).recognised());
        assertFalse(CheckPointLifecycleParser.parse(page(software(
                "<tr><td>Check Point Endpoint E86.00</td><td>Nov 2021</td><td>Endpoint client</td><td>December 2023</td></tr>"))).recognised());
    }

    @Test
    void truncatedRowsAreSkippedAndCountedNotGuessed() {
        var result = CheckPointLifecycleParser.parse(page(
                software("<tr><td>Check Point R82</td><td>October 2024</td><td>R82</td><td>April 2029</td></tr>"
                        + "<tr><td>Check Point R81</td><td>October 2020</td><td>R81</td></tr>")
                        + appliances("<tr><td>6600 Appliance</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2031</td></tr>"
                                + "<tr><td>5400 Appliance</td><td>Aug-2021</td><td>Aug-2026</td></tr>")));
        assertEquals(2, result.rowsSkipped(), "the R81 row and the 5400 row have no support cell");
        assertEquals(List.of("SOFTWARE R82", "HARDWARE 6600"),
                result.entries().stream().map(e -> e.kind() + " " + e.productKey()).toList());
        assertNull(result.entries().stream().filter(e -> e.productKey().equals("R81")).findAny().orElse(null));
    }

    @Test
    void anEmptyPageIsNotRecognisedAndDoesNotThrow() {
        assertFalse(CheckPointLifecycleParser.parse(new byte[0]).recognised());
        assertFalse(CheckPointLifecycleParser.parse(null).recognised());
        assertFalse(CheckPointLifecycleParser.parse(page("")).recognised());
    }

    @Test
    void hostileOrNonHtmlBytesDoNotEscapeAsExceptions() {
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xC3, 0x28, 0x7F};
        assertFalse(CheckPointLifecycleParser.parse(binary).recognised());
        assertFalse(CheckPointLifecycleParser.parse("plain text, no markup at all".getBytes(StandardCharsets.UTF_8)).recognised());
        // Unclosed table, rows and cells under a valid-looking header: the parser must return, not throw.
        byte[] unclosed = page("<table>" + SW_HEADER + "<tr><td>Check Point R82<td>x<td>R82<td>April 2029");
        assertDoesNotThrow(() -> CheckPointLifecycleParser.parse(unclosed));
    }
}
