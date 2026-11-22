package com.securityexpert.nexus.ui2.service.lifecyclesource;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Cisco End-of-Sale / End-of-Life bulletin parser on invented pages. The titles name fictional products; only the
 * table structure follows the vendor's bulletin. Complements the reference cases in {@link LifecyclePageParsersTest}:
 * here the full date set, the unreadable and placeholder milestones, and the unrecognised input are pinned.
 */
class CiscoEolBulletinParserTest {

    private static final String SOFTWARE_TITLE =
            "End-of-Sale and End-of-Life Announcement for the Fictional Gateway Software ASA Release 8.44(x) - Cisco";

    private static byte[] page(String title, String body) {
        return ("<html><head><title>" + title + "</title></head><body>" + body + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String milestones(String eos, String eoe, String eoSec, String eos2) {
        return "<table><tr><th>Milestone</th><th>Definition</th><th>Date</th></tr>"
                + "<tr><td>End-of-Sale Date: App SW</td><td>x</td><td>" + eos + "</td></tr>"
                + "<tr><td>End of SW Maintenance Releases Date: App SW</td><td>x</td><td>" + eoe + "</td></tr>"
                + "<tr><td>End of Vulnerability/Security Support: App SW</td><td>x</td><td>" + eoSec + "</td></tr>"
                + "<tr><td>Last Date of Support: App SW</td><td>x</td><td>" + eos2 + "</td></tr>"
                + "</table>";
    }

    private static final String FPR_TABLE = "<table><tr><th>End-of-Sale Product Part Number</th><th>Product Description</th></tr>"
            + "<tr><td>FPR-4115-K9</td><td>x</td></tr></table>";

    @Test
    void wellFormedSoftwareBulletinGivesTheExpectedDatesAndProductKey() {
        var result = CiscoEolBulletinParser.parse(page(SOFTWARE_TITLE,
                "<p>EOL90001</p>" + milestones("March 1, 2026", "March 1, 2027", "March 1, 2028", "March 1, 2029")), "path-ref");

        assertTrue(result.recognised());
        assertEquals(List.of("EOL90001"), result.documentRefs(), "the bulletin id in the page text names the publication");
        assertEquals(1, result.entries().size());
        var e = result.entries().getFirst();
        assertEquals("SOFTWARE", e.kind());
        assertEquals("ASA 8.44", e.productKey());
        assertEquals(LifecyclePage.PARSED, e.parseState());
        assertNull(e.parseNote());
        assertEquals(LocalDate.of(2026, 3, 1), e.date("end_of_sale"));
        assertEquals(LocalDate.of(2027, 3, 1), e.date("end_of_engineering"));
        assertEquals(LocalDate.of(2028, 3, 1), e.date("end_of_security_support"));
        assertEquals(LocalDate.of(2029, 3, 1), e.date("end_of_support"));
    }

    @Test
    void aBulletinWithoutAnIdUsesThePathReference() {
        var result = CiscoEolBulletinParser.parse(page(SOFTWARE_TITLE,
                milestones("March 1, 2026", "March 1, 2027", "March 1, 2028", "March 1, 2029")), "fictional-path-ref");
        assertEquals(List.of("fictional-path-ref"), result.documentRefs());
        assertEquals("ASA 8.44 (fictional-path-ref)", result.entries().getFirst().productLabel());
    }

    @Test
    void anUnreadableEndOfSaleDateMakesTheEntryNeedsReviewAndNoDateIsGuessed() {
        String body = "<p>EOL90002</p>" + milestones("sometime in spring 2026", "March 1, 2027", "March 1, 2028", "March 1, 2029")
                + FPR_TABLE;
        var result = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for the Fictional Firepower 4100 Series", body), "x");
        var e = result.entries().getFirst();
        assertEquals("FPR4115", e.productKey());
        assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState(), "an unreadable milestone is not a PARSED entry");
        assertEquals("DATE_UNREADABLE", e.parseNote());
        assertNull(e.date("end_of_sale"));
        assertNull(e.date("end_of_support"), "no date is read from an entry that needs review");
    }

    @Test
    void aPlaceholderEndOfSaleDateIsNoDateAndTheEntryStaysParsedWithoutIt() {
        String body = milestones("TBD", "March 1, 2027", "March 1, 2028", "March 1, 2029") + FPR_TABLE;
        var result = CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for the Fictional Firepower 4100 Series", body), "x");
        var e = result.entries().getFirst();
        assertEquals(LifecyclePage.PARSED, e.parseState());
        assertNull(e.date("end_of_sale"), "TBD is the vendor publishing no date yet, never a guessed date");
        assertEquals(LocalDate.of(2029, 3, 1), e.date("end_of_support"));
    }

    @Test
    void aBulletinWithoutAMilestoneTableIsReviewedNotSupported() {
        var result = CiscoEolBulletinParser.parse(page(SOFTWARE_TITLE, "<p>EOL90003</p>"), "x");
        assertTrue(result.recognised(), "the title and the ASA train are a bulletin");
        assertEquals(1, result.entries().size());
        var e = result.entries().getFirst();
        assertEquals(LifecyclePage.NEEDS_REVIEW, e.parseState());
        assertEquals("MILESTONE_TABLE_MISSING", e.parseNote());
        assertNull(e.date("end_of_support"));
    }

    @Test
    void emptyOrUnrecognisedInputChangesNothing() {
        assertFalse(CiscoEolBulletinParser.parse(new byte[0], "x").recognised());
        assertFalse(CiscoEolBulletinParser.parse(new byte[]{0x00, (byte) 0xFF, 0x3C, (byte) 0xFE}, "x").recognised());
        assertFalse(CiscoEolBulletinParser.parse(page("Fictional Vendor Product Catalogue",
                milestones("March 1, 2026", "March 1, 2027", "March 1, 2028", "March 1, 2029") + FPR_TABLE), "x").recognised(),
                "a page that is not an end-of-life announcement is never a lifecycle claim");
        assertFalse(CiscoEolBulletinParser.parse(page("End-of-Sale and End-of-Life Announcement for the Fictional Licence Bundle",
                milestones("March 1, 2026", "March 1, 2027", "March 1, 2028", "March 1, 2029")), "x").recognised(),
                "a bulletin that names no ASA train and no appliance model is not recognised");
    }
}
