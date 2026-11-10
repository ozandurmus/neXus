package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;

/** Contract section 6, "Fortinet rule": parsing of the RSS feed and of CSAF 2.0 files. */
class FortinetAdvisoryParserTest {

    private final FortinetAdvisoryParser parser = new FortinetAdvisoryParser();

    private static byte[] resource(String name) {
        return CsafTestResources.bytes(name);
    }

    private ParsedAdvisory one(String json) {
        ParseResult result = parser.parse("https://example.invalid/x.json", json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.advisories().size(), json);
        return result.advisories().getFirst();
    }

    private static String doc(String tree, String vulns) {
        return "{\"document\":{\"title\":\"T\",\"tracking\":{\"id\":\"FG-IR-26-1\",\"initial_release_date\":\"2026-01-02T00:00:00\","
                + "\"current_release_date\":\"2026-01-03T00:00:00\"}},\"product_tree\":" + tree + ",\"vulnerabilities\":" + vulns + "}";
    }

    private static String tree(String productName, String... leaves) {
        StringBuilder sb = new StringBuilder("{\"branches\":[{\"category\":\"vendor\",\"name\":\"V\",\"branches\":[{\"category\":\"product\",\"name\":\""
                + productName + "\",\"branches\":[");
        for (int i = 0; i < leaves.length; i += 2) {
            sb.append(i == 0 ? "" : ",").append("{\"category\":\"product_version_range\",\"name\":\"").append(leaves[i])
                    .append("\",\"product\":{\"product_id\":\"").append(leaves[i + 1]).append("\",\"name\":\"")
                    .append(productName).append("\"}}");
        }
        return sb.append("]}]}]}").toString();
    }

    private static String vuln(String affected, String notAffected) {
        return "[{\"cve\":\"CVE-2026-0001\",\"scores\":[{\"cvss_v3\":{\"baseScore\":7.5}}],\"product_status\":{\"known_affected\":["
                + affected + "],\"known_not_affected\":[" + notAffected + "]}}]";
    }

    @Test
    void rssFeedYieldsNothing() {
        ParseResult result = parser.parse("https://example.invalid/rss", resource("fortinet/rss_small.xml"));
        assertEquals(ParseResult.empty(), result);
    }

    @Test
    void realShapedCsafGivesRangesWholeTrainsAndExactVersions() {
        ParsedAdvisory a = one(new String(resource("fortinet/csaf_ranges.json"), StandardCharsets.UTF_8));
        assertEquals("fortinet:FG-IR-26-150", a.advisoryId());
        assertEquals("fortinet", a.source());
        assertEquals("SSL-VPN Reflected XSS", a.title());
        assertEquals(ParseState.PARSED, a.parseState());
        assertNull(a.parseNote());
        assertEquals(Severity.MEDIUM, a.severity());
        assertEquals(0, a.cvssScore().compareTo(new java.math.BigDecimal("5.5")));
        assertEquals(List.of("CVE-2026-23573"), a.cveIds());
        assertEquals("2026-07-14", a.publishedOn().toString());
        assertEquals("https://fortiguard.fortinet.com/psirt/FG-IR-26-150", a.sourceUrl());
        // only FortiOS is kept: trains 7.6 (range + exact unaffected fix), 7.4, 7.2, 8.0
        assertEquals(4, a.rules().size());
        assertTrue(a.rules().stream().allMatch(r -> r.productKey().equals(AdvisoryModel.PRODUCT_FORTIOS)));
        ProductRule t76 = rule(a, "7.6");
        assertEquals(List.of(new Range(Status.AFFECTED, "7.6.0", "7.6.6", true, List.of()),
                new ExactVersions(Status.UNAFFECTED, Set.of("7.6.7"))), t76.rules());
        assertEquals("Upgrade to 7.6.7 or above", t76.fixedText());
        assertEquals(List.of(new WholeTrain(Status.AFFECTED)), rule(a, "7.4").rules());
        assertEquals("Migrate to a fixed release", rule(a, "7.4").fixedText());
        assertEquals(List.of(new WholeTrain(Status.UNAFFECTED)), rule(a, "8.0").rules());
        assertNull(rule(a, "8.0").fixedText());
    }

    private static ProductRule rule(ParsedAdvisory a, String train) {
        return a.rules().stream().filter(r -> r.train().equals(train)).findFirst().orElseThrow();
    }

    @Test
    void emptyProductTreeNeedsReview() {
        ParsedAdvisory a = one(new String(resource("fortinet/csaf_empty_tree.json"), StandardCharsets.UTF_8));
        assertEquals("fortinet:FG-IR-26-187", a.advisoryId());
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("EMPTY_PRODUCT_TREE", a.parseNote());
        assertEquals(Severity.UNKNOWN, a.severity());
        assertNull(a.cvssScore());
    }

    @Test
    void otherProductsOnlyGivesParsedAdvisoryWithoutRules() {
        ParsedAdvisory a = one(doc(tree("FortiPAM", "FortiPAM/1.8.0", "FortiPAM 1.8.0"), vuln("\"FortiPAM 1.8.0\"", "")));
        assertEquals(ParseState.PARSED, a.parseState());
        assertTrue(a.rules().isEmpty());
    }

    @Test
    void fortiManagerSingleVersionAndRange() {
        ParsedAdvisory a = one(doc(tree("FortiManager", "FortiManager/>=7.4.0|<=7.4.2", "FortiManager >=7.4.0|<=7.4.2",
                "FortiManager/7.2.1", "FortiManager 7.2.1"),
                vuln("\"FortiManager >=7.4.0|<=7.4.2\",\"FortiManager 7.2.1\"", "")));
        assertEquals(ParseState.PARSED, a.parseState());
        assertEquals(AdvisoryModel.PRODUCT_FORTIMANAGER, a.rules().getFirst().productKey());
        assertEquals(List.of(new Range(Status.AFFECTED, "7.4.0", "7.4.2", true, List.of())), rule(a, "7.4").rules());
        assertEquals(List.of(new ExactVersions(Status.AFFECTED, Set.of("7.2.1"))), rule(a, "7.2").rules());
    }

    @Test
    void statusIdMatchesBranchNameAndIdWithSlashOrSpace() {
        // the status id spelled like the branch name (slash) while the product id uses a space
        ParsedAdvisory a = one(doc(tree("FortiOS", "FortiOS/ 7.0 all versions", "FortiOS 7.0 all versions"),
                vuln("\"FortiOS/ 7.0 all versions\"", "")));
        assertEquals(List.of(new WholeTrain(Status.AFFECTED)), rule(a, "7.0").rules());
    }

    @Test
    void rangeAcrossTrainsNeedsReviewAndNamesTheProduct() {
        ParsedAdvisory a = one(doc(tree("FortiOS", "FortiOS/>=7.2.0|<=7.4.1", "FortiOS >=7.2.0|<=7.4.1"),
                vuln("\"FortiOS >=7.2.0|<=7.4.1\"", "")));
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("RANGE_TRAIN_MISMATCH", a.parseNote());
        assertEquals(List.of(new ProductRule(AdvisoryModel.PRODUCT_FORTIOS, "*", List.of(), null)), a.rules());
    }

    @Test
    void unknownRangeFormNeedsReview() {
        ParsedAdvisory a = one(doc(tree("FortiOS", "FortiOS/<=7.2.4", "FortiOS <=7.2.4"), vuln("\"FortiOS <=7.2.4\"", "")));
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("RANGE_UNREADABLE", a.parseNote());
    }

    @Test
    void unresolvableStatusIdNeedsReview() {
        ParsedAdvisory a = one(doc(tree("FortiOS", "FortiOS/7.0 all versions", "FortiOS 7.0 all versions"),
                vuln("\"FortiOS 7.0 all versions\",\"FortiOS 7.2.x\"", "")));
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("STATUS_ID_UNRESOLVED", a.parseNote());
    }

    @Test
    void ourProductWithoutAnyStatusNeedsReview() {
        ParsedAdvisory a = one(doc(tree("FortiOS", "FortiOS/7.0 all versions", "FortiOS 7.0 all versions"), vuln("", "")));
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("NO_STATUS_FOR_RANGES", a.parseNote());
    }

    @Test
    void sameRangeBothAffectedAndUnaffectedNeedsReview() {
        ParsedAdvisory a = one(doc(tree("FortiOS", "FortiOS/7.0 all versions", "FortiOS 7.0 all versions"),
                vuln("\"FortiOS 7.0 all versions\"", "\"FortiOS 7.0 all versions\"")));
        assertEquals("CONFLICTING_STATUS", a.parseNote());
    }

    @Test
    void highestScoreWinsAndFixTextIsCut() {
        String longFix = "x".repeat(400);
        String json = "{\"document\":{\"title\":\"" + "t".repeat(500) + "\",\"tracking\":{\"id\":\"FG-IR-26-2\"}},"
                + "\"product_tree\":" + tree("FortiOS", "FortiOS/7.0 all versions", "FortiOS 7.0 all versions")
                + ",\"vulnerabilities\":[{\"cve\":\"CVE-2026-1111\",\"scores\":[{\"cvss_v3\":{\"baseScore\":4.1}},{\"cvss_v3\":{\"baseScore\":9.8}}],"
                + "\"product_status\":{\"known_affected\":[\"FortiOS 7.0 all versions\"]},"
                + "\"remediations\":[{\"category\":\"vendor_fix\",\"details\":\"FortiOS 7.0: " + longFix + "\"}]},"
                + "{\"cve\":\"not-a-cve\"},{\"cve\":\"CVE-2026-1111\"}]}";
        ParsedAdvisory a = one(json);
        assertEquals(Severity.CRITICAL, a.severity());
        assertEquals(List.of("CVE-2026-1111"), a.cveIds());
        assertEquals(300, a.title().length());
        assertEquals(200, rule(a, "7.0").fixedText().length());
        assertNull(a.publishedOn());
    }

    @Test
    void hostileOrUnusableInputNeverThrows() {
        for (byte[] bad : new byte[][] {null, new byte[0], "not json".getBytes(StandardCharsets.UTF_8),
                "[1,2]".getBytes(StandardCharsets.UTF_8), "{\"document\":{}}".getBytes(StandardCharsets.UTF_8),
                ("{\"document\":{\"tracking\":{\"id\":\"../../x y\"}}}").getBytes(StandardCharsets.UTF_8)}) {
            ParseResult result = parser.parse("u", bad);
            assertTrue(result.advisories().isEmpty());
            assertEquals(1, result.rejected());
        }
        String deep = "[".repeat(100_000);
        ParseResult result = parser.parse("u", ("{\"a\":" + deep).getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.rejected());
        String nested = "{\"document\":{\"tracking\":{\"id\":\"FG-IR-1\"}},\"product_tree\":{\"branches\":" + "[{\"branches\":".repeat(900)
                + "[]" + "}]".repeat(900) + "}}";
        assertDoesNotThrow(() -> parser.parse("u", nested.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sourceKey() {
        assertEquals("fortinet", parser.source());
    }
}
