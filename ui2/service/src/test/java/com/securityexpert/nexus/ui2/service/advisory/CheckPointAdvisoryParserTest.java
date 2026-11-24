package com.securityexpert.nexus.ui2.service.advisory;

import static com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryTest.cveLink;
import static com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryTest.entry;
import static com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryTest.page;
import static com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryTest.record;
import static com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryTest.row;
import static com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryTest.section;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseResult;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParsedAdvisory;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.Severity;

/**
 * Unknown and malformed-input handling of the Check Point parser: a missing identifier, severity or date never reads as a
 * clear or zero result, and a malformed or truncated document yields no advisory rather than a partial one. All ids are
 * synthetic (CVE-2099-*).
 */
class CheckPointAdvisoryParserTest {

    static final String RECORD = "https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2099/00xxx/CVE-2099-0001.json";
    static final String CVE = "CVE-2099-0001";
    static final String AFFECTED = "[" + entry("Security Gateway", "R81.20") + "]";

    private final CheckPointAdvisoryParser parser = new CheckPointAdvisoryParser();

    private ParseResult parse(byte[] document) {
        return parser.parse(RECORD, document, cve -> List.of());
    }

    private static byte[] baseRecord() {
        return record(CVE, "checkpoint", AFFECTED);
    }

    private static byte[] replaced(byte[] document, String from, String to) {
        String text = new String(document, StandardCharsets.UTF_8);
        assertTrue(text.contains(from), "fixture must contain the fragment being removed");
        return text.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theUnchangedSyntheticRecordIsOneAdvisoryWithItsSeverityAndScore() {
        ParseResult result = parse(baseRecord());
        assertEquals(0, result.rejected());
        ParsedAdvisory advisory = result.advisories().getFirst();
        assertEquals(Severity.CRITICAL, advisory.severity());
        assertEquals(0, new BigDecimal("9.8").compareTo(advisory.cvssScore()));
    }

    @Test
    void aMissingCveIdIsRejectedNotReportedAsAnAdvisory() {
        ParseResult result = parse(record("", "checkpoint", AFFECTED));
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
    }

    @Test
    void aMissingCveMetadataObjectIsRejectedNotReportedAsAnAdvisory() {
        byte[] noMetadata = replaced(baseRecord(), "\"cveMetadata\":{", "\"noMetadata\":{");
        ParseResult result = parse(noMetadata);
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
    }

    @Test
    void aMissingSeverityStaysUnknownAndKeepsTheScoreTheRecordGave() {
        byte[] noSeverity = replaced(baseRecord(), ",\"baseSeverity\":\"CRITICAL\"", "");
        ParsedAdvisory advisory = parse(noSeverity).advisories().getFirst();
        assertEquals(Severity.UNKNOWN, advisory.severity());
        assertEquals(0, new BigDecimal("9.8").compareTo(advisory.cvssScore()));
    }

    @Test
    void aSeverityTextThatIsNotAKnownLevelStaysUnknown() {
        byte[] oddSeverity = replaced(baseRecord(), "\"baseSeverity\":\"CRITICAL\"", "\"baseSeverity\":\"SUPER-BAD\"");
        assertEquals(Severity.UNKNOWN, parse(oddSeverity).advisories().getFirst().severity());
    }

    @Test
    void aRecordWithNoMetricsHasNoScoreAndAnUnknownSeverityNeverNone() {
        byte[] noMetrics = replaced(baseRecord(),
                ",\"metrics\":[{\"format\":\"CVSS\",\"cvssV3_1\":{\"version\":\"3.1\",\"baseScore\":9.8,\"baseSeverity\":\"CRITICAL\"}}]", "");
        ParsedAdvisory advisory = parse(noMetrics).advisories().getFirst();
        assertEquals(Severity.UNKNOWN, advisory.severity());
        assertNull(advisory.cvssScore());
    }

    @Test
    void aMissingPublicationDateIsNullNotInvented() {
        byte[] noDates = replaced(baseRecord(),
                ",\"datePublished\":\"2026-09-09T13:00:00.000Z\",\"dateUpdated\":\"2026-09-10T03:00:00.000Z\"", "");
        ParsedAdvisory advisory = parse(noDates).advisories().getFirst();
        assertNull(advisory.publishedOn());
        assertNull(advisory.updatedOn());
    }

    @Test
    void aMalformedDateIsNullNotAPartialDate() {
        byte[] shortDate = replaced(baseRecord(), "\"datePublished\":\"2026-09-09T13:00:00.000Z\"", "\"datePublished\":\"2099-1\"");
        ParsedAdvisory advisory = parse(shortDate).advisories().getFirst();
        assertNull(advisory.publishedOn());
    }

    @Test
    void anAffectedListThatIsMissingNeedsReviewAndNeverReadsAsClear() {
        byte[] noAffected = replaced(baseRecord(), "\"affected\":" + AFFECTED + ",", "");
        ParsedAdvisory advisory = parse(noAffected).advisories().getFirst();
        assertEquals(ParseState.NEEDS_REVIEW, advisory.parseState());
        assertTrue(advisory.rules().stream().allMatch(r -> r.fixedText() == null && r.rules().isEmpty()),
                "an open question carries no affected or fixed claim");
    }

    @Test
    void anAffectedEntryWithoutAProductNeedsReview() {
        byte[] noProduct = replaced(baseRecord(), "\"product\":\"Security Gateway\",", "");
        ParsedAdvisory advisory = parse(noProduct).advisories().getFirst();
        assertEquals(ParseState.NEEDS_REVIEW, advisory.parseState());
    }

    @Test
    void aTruncatedRecordYieldsNoAdvisoryNotAPartialOne() {
        byte[] full = baseRecord();
        ParseResult result = parse(Arrays.copyOf(full, full.length / 2));
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
    }

    @Test
    void aRecordMissingItsClosingBraceYieldsNoAdvisory() {
        byte[] full = baseRecord();
        ParseResult result = parse(Arrays.copyOf(full, full.length - 1));
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
    }

    @Test
    void jsonLikeGarbageYieldsNoAdvisory() {
        ParseResult result = parse("{\"cveMetadata\":{\"cveId\":".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
        assertEquals(1, parse("[]".getBytes(StandardCharsets.UTF_8)).rejected());
    }

    @Test
    void aResolvedIssuesRowWithTooFewCellsIsRejectedAndStoresNoStatement() {
        String shortRow = "<tr class=\"- topic/row\"><td><p>" + cveLink("CVE-2099-0002") + "</p></td>"
                + "<td><p>Security Gateway</p></td></tr>";
        byte[] doc = page(section(120, shortRow));
        var result = new CheckPointAdvisoryParser().parse("https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html",
                doc);
        assertTrue(result.jumboStatements().isEmpty());
        assertEquals(1, result.rejected());
    }

    @Test
    void aPageWithoutAnyTakeSectionIsRejectedWholesale() {
        byte[] doc = ("<html><body><table><tr>" + row(cveLink("CVE-2099-0003"), "Security Gateway", "UPDATE: synthetic.")
                + "</tr></table></body></html>").getBytes(StandardCharsets.UTF_8);
        var result = new CheckPointAdvisoryParser().parse("https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html",
                doc);
        assertTrue(result.jumboStatements().isEmpty());
        assertEquals(1, result.rejected());
    }

    @Test
    void nullDocumentOrUrlIsRejected() {
        assertEquals(1, parser.parse(RECORD, null, cve -> List.of()).rejected());
        assertEquals(1, parser.parse(null, baseRecord(), cve -> List.of()).rejected());
    }

    @Test
    void anUnknownHostIsRejectedBeforeAnyRead() {
        ParseResult result = parser.parse("https://example.invalid/CVE-2099-0001.json", baseRecord(), cve -> List.of());
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
    }
}
