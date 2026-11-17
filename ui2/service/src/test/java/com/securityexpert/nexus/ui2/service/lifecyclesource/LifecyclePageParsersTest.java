package com.securityexpert.nexus.ui2.service.lifecyclesource;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The three vendor page parsers on synthetic pages shaped like the vendors' own (structure measured on the public pages on
 * 2026-11-13: table headers, caption rows, sub-header rows, date forms, footnote marks). The rows are invented; only the
 * page structure follows the vendor's.
 */
class LifecyclePageParsersTest {

    private static byte[] html(String body) {
        return ("<!DOCTYPE html><html><head><title>%s</title><script>var x = '<table><tr><td>not a table</td></tr></table>';</script>"
                + "</head><body>%s</body></html>").formatted("Page", body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] page(String title, String body) {
        return ("<html><head><title>" + title + "</title></head><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, LifecyclePage.Entry> byKey(LifecyclePage.Result result) {
        return result.entries().stream().collect(Collectors.toMap(e -> e.kind() + " " + e.productKey(), e -> e));
    }

    // ------------------------------------------------------------------------------------------------ Cisco

    private static final String MILESTONES_HW = """
            <table><tr><th>Milestone</th><th>Definition</th><th>Date</th></tr>
            <tr><td>End-of-Life Announcement Date</td><td>The date the document is released.</td><td>July 8, 2025</td></tr>
            <tr><td>End-of-Sale Date: HW,License</td><td>The last date to order.</td><td>January 6, 2026</td></tr>
            <tr><td>End of SW Maintenance Releases Date: HW</td><td>Last maintenance release.</td><td>January 6, 2027</td></tr>
            <tr><td>End of Vulnerability/Security Support: HW</td><td>Last security fix.</td><td>January 31, 2031</td></tr>
            <tr><td>Last Date of Support: HW</td><td>The last date to receive support.</td><td>January 31, 2031</td></tr>
            </table>""";

    @Test
    void ciscoHardwareBulletinGivesOneEntryPerApplianceModelFromThePartNumbers() {
        String parts = """
                <table><tr><th>End-of-Sale Product Part Number</th><th>Product Description</th><th>Replacement Product Part Number</th></tr>
                <tr><td>FPR-4100-FIPS-KIT</td><td>Firepower 4000 FIPS Kit</td><td>-</td></tr>
                <tr><td>FPR-4115-K9</td><td>Firepower 4115 Appliance, 1U</td><td>-</td></tr>
                <tr><td>FPR-4115-K9=</td><td>Firepower 4115 Appliance spare</td><td>-</td></tr>
                <tr><td>FPR4125-ASA-K9</td><td>Firepower 4125 ASA appliance</td><td>-</td></tr>
                <tr><td>FPR-MSP-SSD</td><td>Malware storage pack</td><td>-</td></tr>
                <tr><td>L-FPR4115T-TMC-5Y</td><td>Subscription</td><td>-</td></tr>
                </table>""";
        var result = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for the Cisco Firepower 4100 Series Security Appliances - Cisco",
                "<h2>Overview</h2><p>EOL15698 - Amended</p>" + MILESTONES_HW + parts), "fallback");
        assertTrue(result.recognised());
        assertEquals(List.of("EOL15698"), result.documentRefs());
        var entries = byKey(result);
        assertEquals(java.util.Set.of("HARDWARE FPR4115", "HARDWARE FPR4125"), entries.keySet());
        var e = entries.get("HARDWARE FPR4115");
        assertEquals(LifecyclePage.PARSED, e.parseState());
        assertEquals(LocalDate.of(2026, 1, 6), e.date("end_of_sale"));
        assertEquals(LocalDate.of(2027, 1, 6), e.date("end_of_engineering"));
        assertEquals(LocalDate.of(2031, 1, 31), e.date("end_of_support"));
        assertEquals(LocalDate.of(2031, 1, 31), e.date("end_of_security_support"));
        assertEquals(List.of(), e.monthOnlyFields());
    }

    @Test
    void ciscoSoftwareBulletinTakesTheAsaTrainsOfTheTitleAndTheSwRows() {
        String milestones = """
                <table><tr><th>Milestone</th><th>Definition</th><th>Date</th></tr>
                <tr><td>End-of-Sale Date: App SW</td><td>x</td><td>November 18, 2025</td></tr>
                <tr><td>End of SW Maintenance Releases Date: App SW</td><td>x</td><td>November 18, 2026</td></tr>
                <tr><td>Last Date of Support: App SW</td><td>x</td><td>November 30, 2027</td></tr>
                <tr><td>Last Date of Support: HW</td><td>x</td><td>December 31, 2029</td></tr>
                </table>""";
        var result = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for the  Cisco Adaptive Security Appliance (ASA) "
                + "Release 9.18(x), Adaptive Security Virtual Appliance (ASAv) Release 9.18(x) and Adaptive Security Device Manager (ASDM) "
                + "Release 7.18(x) - Cisco", "<p>EOL15626</p>" + milestones), "fallback");
        var entries = byKey(result);
        assertEquals(java.util.Set.of("SOFTWARE ASA 9.18"), entries.keySet(), "ASAv and ASDM trains are not ASA trains");
        assertEquals(LocalDate.of(2027, 11, 30), entries.get("SOFTWARE ASA 9.18").date("end_of_support"));
    }

    @Test
    void ciscoTitleFormsOfTrainsAreRead() {
        assertEquals(java.util.Set.of("9.17"), CiscoEolBulletinParser.trains("... Firepower Management Center (FMC) 7.1.(x), Adaptive Security Appliance(ASA) 9.17.(x) and ..."));
        assertEquals(java.util.Set.of("9.0", "9.3"), CiscoEolBulletinParser.trains("Announcement for the Cisco ASA 9.0 and 9.3"));
        assertEquals(java.util.Set.of("9.23"), CiscoEolBulletinParser.trains("Cisco ASA Release 9.23(x), ASAv 9.23(x), ASDM 7.23(x)"));
        assertEquals(java.util.Set.of(), CiscoEolBulletinParser.trains("Cisco ASA 5500 Series Adaptive Security Appliances"));
        assertEquals(java.util.Set.of(), CiscoEolBulletinParser.trains("Cisco Firepower Threat Defense (FTD) 7.2(x)"));
    }

    @Test
    void ciscoConflictingOrMissingMilestonesAreReviewedNeverGuessed() {
        String conflicting = """
                <table><tr><th>Milestone</th><th>Definition</th><th>Date</th></tr>
                <tr><td>Last Date of Support: HW</td><td>x</td><td>January 31, 2031</td></tr>
                <tr><td>Last Date of Support: HW</td><td>x</td><td>January 31, 2032</td></tr>
                </table>
                <table><tr><th>End-of-Sale Product Part Number</th><th>Product Description</th></tr><tr><td>FPR-2130-K9</td><td>x</td></tr></table>""";
        var result = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for the Cisco Firepower 2100", conflicting), "fpr-2100-eol");
        var e = result.entries().getFirst();
        assertEquals("FPR2130", e.productKey());
        assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState());
        assertEquals("MILESTONE_CONFLICT", e.parseNote());
        assertNull(e.date("end_of_support"));
        assertEquals(List.of("fpr-2100-eol"), result.documentRefs(), "no bulletin id: the page path names the publication");

        String unreadable = MILESTONES_HW.replace("January 31, 2031</td></tr>\n</table>", "31/01/2031</td></tr>\n</table>");
        var review = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for X", "EOL1 EOL12345" + unreadable
                + "<table><tr><th>End-of-Sale Product Part Number</th></tr><tr><td>ASA5516-FPWR-K9</td></tr></table>"), "x");
        assertEquals("ASA5516", review.entries().getFirst().productKey());
        assertEquals("DATE_UNREADABLE", review.entries().getFirst().parseNote());

        var noSupport = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for X",
                "<table><tr><th>Milestone</th><th>Date</th></tr><tr><td>End-of-Sale Date: HW</td><td>May 1, 2026</td></tr></table>"
                        + "<table><tr><th>End-of-Sale Product Part Number</th></tr><tr><td>FPR-1010-K9</td></tr></table>"), "x");
        assertEquals("MILESTONE_MISSING", noSupport.entries().getFirst().parseNote());
    }

    @Test
    void ciscoPagesThatAreNotBulletinsOrNameNoCoveredProductChangeNothing() {
        assertFalse(CiscoEolBulletinParser.parse(page("Cisco Security Products", MILESTONES_HW), "x").recognised());
        var subscriptions = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for the Cisco Firepower 4100 5 YR Subscriptions",
                MILESTONES_HW + "<table><tr><th>End-of-Sale Product Part Number</th></tr><tr><td>L-FPR4115T-TMC-5Y</td></tr></table>"), "x");
        assertFalse(subscriptions.recognised());
        assertFalse(CiscoEolBulletinParser.parse(new byte[0], "x").recognised());
        assertFalse(CiscoEolBulletinParser.parse("<html><title>End-of-Sale and End-of-Life Announcement".getBytes(StandardCharsets.UTF_8), "x").recognised());
    }

    // ------------------------------------------------------------------------------------------------ Palo Alto

    @Test
    void paloAltoSoftwareTableGivesTrainsWithStandardAndExtendedSupport() {
        String table = """
                <table id="pan-os-panorama"><thead><tr><th colspan="4">&nbsp;</th></tr>
                <tr><th colspan="5"><p><b>PAN-OS &amp; Panorama</b></p></th></tr>
                <tr><th><b>Version</b></th><th><b>Release Date</b></th><th colspan="3"><b>End-of-Life Date</b></th></tr>
                <tr><th>&nbsp;</th><th>&nbsp;</th><th><a href="/x">Standard Support</a></th><th>&nbsp;</th><th>Extended Support<sup>+</sup></th></tr>
                </thead><tbody>
                <tr><td>12.1</td><td>August 28, 2025</td><td>August 28, 2028</td><td></td><td>August 28, 2029</td></tr>
                <tr><td>11.1</td><td>November 3, 2023</td><td>May 3, 2027</td><td></td><td>August 31, 2027</td></tr>
                <tr><td>10.2</td><td>February 27, 2022</td><td>August 27, 2025</td><td></td><td>March 31, 2027^</td></tr>
                <tr><td>9.0-XFR (VM-Series only)</td><td>September 19, 2019</td><td>September 19, 2020</td><td></td><td></td></tr>
                <tr><td>12.2</td><td>July 30th, 2026</td><td>TBD</td><td></td><td></td></tr>
                </tbody></table>""";
        var result = PaloAltoEolParser.software(html("<table><tr><td>Prisma</td></tr><tr><th>Version</th><th>Release Date</th><th>End-of-Life Date</th></tr>"
                + "<tr><td>1.0</td><td>May 1, 2020</td><td>May 1, 2021</td></tr></table>" + table));
        assertTrue(result.recognised());
        assertEquals(1, result.rowsSkipped());
        var entries = byKey(result);
        assertEquals(java.util.Set.of("SOFTWARE PAN-OS 12.1", "SOFTWARE PAN-OS 11.1", "SOFTWARE PAN-OS 10.2", "SOFTWARE PAN-OS 12.2"), entries.keySet());
        assertEquals(LocalDate.of(2027, 5, 3), entries.get("SOFTWARE PAN-OS 11.1").date("end_of_support"));
        assertEquals(LocalDate.of(2027, 8, 31), entries.get("SOFTWARE PAN-OS 11.1").date("end_of_extended_support"));
        assertEquals(LocalDate.of(2027, 3, 31), entries.get("SOFTWARE PAN-OS 10.2").date("end_of_extended_support"), "footnote mark removed");
        assertEquals("MILESTONE_MISSING", entries.get("SOFTWARE PAN-OS 12.2").parseNote(), "TBD is no date and no guess");
    }

    @Test
    void paloAltoHardwareRowsNameModelsNotSeries() {
        String table = """
                <table><tbody><tr><th>End-of-Sale Product</th><th>End-of-Sale Date</th><th>End-of-Life Date</th><th>Resources</th><th>Last Supported OS ^</th></tr>
                <tr><td>PAN-PA-410<br>PAN-PA-415</td><td>Mar 22, 2027</td><td>Mar 21, 2032</td><td></td><td>PAN-OS 12.1</td></tr>
                <tr><td>PA-800 Series<br>(PA-820, PA-850)<br>(PA-820-ZTP, PA-850-ZTP)</td><td>August 31, 2024</td><td>August 31, 2029</td><td></td><td>PAN-OS 11.1</td></tr>
                <tr><td>VM-Series legacy bundle</td><td>June 15th, 2024</td><td>February 1st, 2029</td><td></td><td></td></tr>
                <tr><td>PA-5200 Series (PA-5220, PA-5250, PA-5260, PA-5280)</td><td>January 31, 2024</td><td>January 31, 2029</td><td></td><td>PAN-OS 11.1</td></tr>
                <tr><td>PA-5450 Series PAN-PA-5450-AC-SYS PAN-PA-5400-DPC-A PAN-PA-5400-NC-A</td><td>Nov 22, 2026</td><td>Nov 21, 2031</td><td></td><td></td></tr>
                <tr><td>PA-7050-SMC PA-7080-SMC PA-7000-LPC</td><td>February 28, 2021</td><td>February 28, 2026 (EOL)</td><td></td><td></td></tr>
                <tr><td>M-200 and M-600</td><td>November 15, 2023</td><td>November 15, 2028</td><td></td><td></td></tr>
                <tr><td>M-500</td><td>February 29, 2020</td><td>February 28, 2025 (EOL)</td><td></td><td></td></tr>
                <tr><td>LightCyber Magna Master (M-100)</td><td>December 31, 2017</td><td>December 31, 2020 (EOL)</td><td></td><td></td></tr>
                </tbody></table>""";
        var result = PaloAltoEolParser.hardware(html("<h2>Hardware End-of-Life Dates</h2>" + table));
        var entries = byKey(result);
        assertEquals(java.util.Set.of("HARDWARE PA-410", "HARDWARE PA-415", "HARDWARE PA-820", "HARDWARE PA-850",
                "HARDWARE PA-5220", "HARDWARE PA-5250", "HARDWARE PA-5260", "HARDWARE PA-5280", "HARDWARE PA-5450",
                "HARDWARE M-200", "HARDWARE M-500", "HARDWARE M-600"), entries.keySet(),
                "card and module part numbers and other product lines are not models");
        assertEquals(3, result.rowsSkipped());
        assertEquals(LocalDate.of(2025, 2, 28), entries.get("HARDWARE M-500").date("end_of_support"), "the (EOL) marker is removed");
        assertEquals(LocalDate.of(2024, 8, 31), entries.get("HARDWARE PA-820").date("end_of_sale"));
        assertEquals(LocalDate.of(2029, 8, 31), entries.get("HARDWARE PA-820").date("end_of_support"));
        assertFalse(PaloAltoEolParser.hardware(html("<table><tr><th>Product</th></tr></table>")).recognised());
        assertFalse(PaloAltoEolParser.software(html("<p>nothing</p>")).recognised());
    }

    // ------------------------------------------------------------------------------------------------ Check Point

    @Test
    void checkPointPageGivesVersionsWithMonthPrecisionAndAppliances() {
        String body = """
                <h3>Latest Announcements</h3>
                <table><tr><th>Product</th><th>End of Sale</th><th>End of Support</th></tr><tr><td>910 Appliance</td><td>Aug 2021</td><td>Aug 2026</td></tr></table>
                <h3>Security Gateway &amp; Management</h3>
                <table><tr><th>Major Version</th><th>General Availability</th><th>Affected Versions</th><th>Support Until</th></tr>
                <tr><td>Check Point R82</td><td>October 2024</td><td>R82</td><td>April 2029</td></tr>
                <tr><td>Check Point R81.20</td><td>November 2022</td><td>R81.20</td><td>November 2027</td></tr>
                <tr><td>Check Point R81</td><td>October 2020</td><td>R81, R81.10</td><td>July 2025</td></tr></table>
                <h3>Check Point Spark Firewall Security Gateways</h3>
                <table><tr><th>Major Version</th><th>General Availability</th><th>Affected Versions</th><th>Support Until</th></tr>
                <tr><td>Check Point R81.10.xx for 15&#215;5</td><td>Jul-22</td><td>R81.10.xx</td><td>Dec-26</td></tr></table>
                <h3>Endpoint Security</h3>
                <table><tr><th>Major Version</th><th>General Availability</th><th>Affected Versions</th><th>Support Until</th></tr>
                <tr><td>E86.00</td><td>Nov 2021</td><td>E86.00</td><td>December 2023</td></tr></table>
                <h3>Enterprise Appliances</h3>
                <table><tr><th>Appliance Product/Model</th><th>Status</th><th>General Availability</th><th>End of Sale</th><th>Successor Model</th>
                <th>Successor Product Availability</th><th>End of Engineering Support</th><th>End of Support</th><th>Supported Software Versions</th></tr>
                <tr><td>3600 Appliance</td><td>Active</td><td>Jan-2020</td><td>N/A</td><td>3920</td><td>May-2025</td><td>Feb 2030</td><td>Feb 2032</td><td>R81.20</td></tr>
                <tr><td>21600 Appliance</td><td>Active</td><td>Oct-2012</td><td>31-Oct-2014</td><td>21700</td><td>Feb-2013</td><td>31-Oct-2017</td><td>31-Oct-2019</td><td>R80.30</td></tr>
                <tr><td>Open Server (any)</td><td>Active</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr></table>
                <h3>SMART-1 Appliances</h3>
                <table><tr><th>Appliance Product/Model</th><th>Status</th><th>General Availability</th><th>End of Sale</th><th>Successor Model</th>
                <th>Successor Product Availability</th><th>End of Engineering Support</th><th>End of Support</th><th>Supported Software Versions</th></tr>
                <tr><td>SMART-1 700-S</td><td>Active</td><td>May-2025</td><td>N/A</td><td></td><td></td><td></td><td></td><td>R82</td></tr></table>
                """;
        var result = CheckPointLifecycleParser.parse(html(body));
        assertEquals(List.of(CheckPointLifecycleParser.SOFTWARE_REF, CheckPointLifecycleParser.HARDWARE_REF), result.documentRefs());
        var entries = byKey(result);
        assertEquals(java.util.Set.of("SOFTWARE R82", "SOFTWARE R81.20", "SOFTWARE R81", "SOFTWARE R81.10", "SOFTWARE R81.10.XX",
                "HARDWARE 3600", "HARDWARE 21600", "HARDWARE SMART-1 700-S"), entries.keySet(), "no endpoint versions, no announcement summary");
        var r8120 = entries.get("SOFTWARE R81.20");
        assertEquals(LocalDate.of(2027, 11, 1), r8120.date("end_of_support"));
        assertEquals(List.of("end_of_support"), r8120.monthOnlyFields());
        assertEquals("DATE_UNREADABLE", entries.get("SOFTWARE R81.10.XX").parseNote(), "a two-digit year is not guessed");
        var a3600 = entries.get("HARDWARE 3600");
        assertNull(a3600.date("end_of_sale"));
        assertEquals(LocalDate.of(2032, 2, 1), a3600.date("end_of_support"));
        assertEquals(LocalDate.of(2019, 10, 31), entries.get("HARDWARE 21600").date("end_of_support"));
        assertEquals(List.of(), entries.get("HARDWARE 21600").monthOnlyFields());
        assertEquals(LifecyclePage.PARSED, entries.get("HARDWARE SMART-1 700-S").parseState(), "an active appliance has no dates yet");
        assertEquals(1, result.rowsSkipped());
    }

    @Test
    void aModelListedTwiceWithDifferentDatesIsReviewed() {
        String header = "<tr><th>Appliance Product/Model</th><th>End of Sale</th><th>End of Engineering Support</th><th>End of Support</th></tr>";
        var result = CheckPointLifecycleParser.parse(html("<table>" + header + "<tr><td>6600 Appliance</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2031</td></tr></table>"
                + "<table>" + header + "<tr><td>6600</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2032</td></tr></table>"));
        assertEquals(1, result.entries().size());
        assertEquals("CONFLICTING_ROWS", result.entries().getFirst().parseNote());
        var same = CheckPointLifecycleParser.parse(html("<table>" + header + "<tr><td>6600 Appliance</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2031</td></tr>"
                + "<tr><td>6600</td><td>Oct-2026</td><td>Oct-2029</td><td>Oct-2031</td></tr></table>"));
        assertEquals(LifecyclePage.PARSED, same.entries().getFirst().parseState());
    }

    // ------------------------------------------------------------------------------------------------ dates and tables

    @Test
    void everyListedDateFormIsReadAndEverythingElseIsUnreadable() {
        assertEquals(new VendorDate.Read(VendorDate.Kind.DAY, LocalDate.of(2025, 11, 18)), VendorDate.read("November 18, 2025"));
        assertEquals(LocalDate.of(2027, 3, 22), VendorDate.read("Mar 22, 2027").date());
        assertEquals(LocalDate.of(2026, 7, 30), VendorDate.read("July 30th, 2026").date());
        assertEquals(LocalDate.of(2025, 7, 31), VendorDate.read("July 31 2025").date());
        assertEquals(LocalDate.of(2014, 10, 31), VendorDate.read("31-Oct-2014").date());
        assertEquals(LocalDate.of(2013, 12, 31), VendorDate.read("31 December 2013").date());
        assertEquals(LocalDate.of(2026, 11, 13), VendorDate.read("2026-11-13").date());
        assertEquals(LocalDate.of(2027, 3, 31), VendorDate.read("March 31, 2027^").date());
        assertEquals(new VendorDate.Read(VendorDate.Kind.MONTH, LocalDate.of(2026, 10, 1)), VendorDate.read("Oct-2026"));
        assertEquals(VendorDate.Kind.MONTH, VendorDate.read("September 2030").kind());
        assertEquals(VendorDate.Kind.MONTH, VendorDate.read("Feb 2030").kind());
        for (String none : List.of("", " ", "N/A", "NA", "-", "–", "TBD", "Latest")) assertEquals(VendorDate.Kind.NONE, VendorDate.read(none).kind(), none);
        for (String bad : List.of("Oct-28", "31-Dec-18", "06/30/2028", "2/12/2019", "2012", "Q3 2026", "February 30, 2026", "Smarch 1, 2026",
                "31-Dec-2020 or later")) {
            assertEquals(VendorDate.Kind.UNREADABLE, VendorDate.read(bad).kind(), bad);
        }
    }

    @Test
    void tablesAreReadAsTextWithoutScriptsAndWithCappedInput() {
        var doc = HtmlTables.read(html("<h2>A &amp; B</h2><table id=\"t1\"><tr><th>x</th><td>a<br/>b &#8211; c&nbsp;d</td></tr></table>"));
        assertEquals(1, doc.tables().size(), "the table inside the script is not read");
        assertEquals("A & B", doc.tables().getFirst().heading());
        assertEquals("t1", doc.tables().getFirst().id());
        assertEquals(List.of("x", "a b – c d"), doc.tables().getFirst().rows().getFirst());
        assertTrue(HtmlTables.read(new byte[HtmlTables.MAX_DOCUMENT_CHARS + 1]).tables().isEmpty());
        assertTrue(HtmlTables.read(null).tables().isEmpty());
    }
}
