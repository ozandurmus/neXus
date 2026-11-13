package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.JumboStatement;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParsedAdvisory;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ProductRule;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.Verdict;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.VerdictKind;

/**
 * Check Point advisories (ADVISORY_MATCHING_CONTRACT.md amendment A-2026-11-13). Every document here is synthetic: CVE ids,
 * sk numbers, takes and texts are made up in the shapes the public pages and CVE records had on 2026-11-13.
 */
class CheckPointAdvisoryTest {

    static final String PAGE_R8120 = "https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html";
    static final String RECORD = "https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/90xxx/CVE-2026-90001.json";
    static final String GW = AdvisoryModel.PRODUCT_CP_GATEWAY;
    static final String MGMT = AdvisoryModel.PRODUCT_CP_MANAGEMENT;

    private final CheckPointAdvisoryParser parser = new CheckPointAdvisoryParser();
    private final DefaultAdvisoryEvaluator evaluator = new DefaultAdvisoryEvaluator(List.of(new CheckPointGaiaComparator(GW),
            new CheckPointGaiaComparator(MGMT)));

    // ---- synthetic documents --------------------------------------------------------------------------------------

    /** One table row of a resolved-issues page, in the page's own markup. */
    static String row(String ids, String product, String descriptionHtml) {
        return "<tr class=\"- topic/row\">\n<td class=\"- topic/entry entry colsep-0 rowsep-0\"><p class=\"- topic/p p\">" + ids
                + "</p></td>\n<td class=\"- topic/entry entry colsep-0 rowsep-0\" title=\"x | · Product ·\"><p class=\"- topic/p p\">"
                + product + "</p></td>\n<td class=\"- topic/entry entry colsep-0 rowsep-0\" title=\"x | · Description ·\"><p class=\"- topic/p p\">"
                + descriptionHtml + "</p></td>\n</tr>\n";
    }

    /** One take section with its header row, group row and the given rows. */
    static String section(int take, String... rows) {
        return "<section class=\"- topic/section section\" id=\"All_Resolved_Issues__Take_" + take + "\" data-ofbid=\"x\">"
                + "<h2 class=\"- topic/title title sectiontitle\">Take " + take + "</h2><p><strong>Released on 01 January 2026</strong></p>"
                + "<div class=\"table-container\"><table><thead><tr class=\"- topic/row\"><th><p>ID</p></th><th><p>Product</p></th>"
                + "<th><p>Description</p></th></tr></thead><tbody><tr class=\"- topic/row\"><td colspan=\"3\"><p>Take " + take
                + " - Improvements and Resolved Issues</p></td></tr>" + String.join("", rows) + "</tbody></table></div></section>";
    }

    static byte[] page(String... sections) {
        return ("<html><body><h1>All Resolved Issues</h1>" + String.join("", sections) + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    static String cveLink(String id) {
        return "<a class=\"- topic/xref xref\" href=\"https://www.cve.org/CVERecord?id=" + id + "\">" + id + "</a>";
    }

    /** A CVE JSON 5 record; {@code affectedJson} is the raw {@code affected} array. */
    static byte[] record(String cveId, String assigner, String affectedJson) {
        return ("{\"dataType\":\"CVE_RECORD\",\"cveMetadata\":{\"cveId\":\"" + cveId + "\",\"assignerShortName\":\"" + assigner
                + "\",\"state\":\"PUBLISHED\",\"datePublished\":\"2026-09-09T13:00:00.000Z\",\"dateUpdated\":\"2026-09-10T03:00:00.000Z\"},"
                + "\"containers\":{\"cna\":{\"title\":\"Synthetic heap overflow in a gateway daemon\","
                + "\"descriptions\":[{\"lang\":\"en\",\"value\":\"A synthetic description.\"}],\"affected\":" + affectedJson + ","
                + "\"metrics\":[{\"format\":\"CVSS\",\"cvssV3_1\":{\"version\":\"3.1\",\"baseScore\":9.8,\"baseSeverity\":\"CRITICAL\"}}],"
                + "\"references\":[{\"url\":\"https://support.checkpoint.com/results/sk/sk9000001\"}]}}}").getBytes(StandardCharsets.UTF_8);
    }

    static String entry(String product, String... versions) {
        return "{\"vendor\":\"checkpoint\",\"product\":\"" + product + "\",\"versions\":["
                + java.util.Arrays.stream(versions).map(v -> "{\"status\":\"affected\",\"version\":\"" + v + "\"}").collect(Collectors.joining(","))
                + "]}";
    }

    static JumboStatement fix(String train, int take, String cve) {
        return new JumboStatement(train, take, cve, "FIX", "Security Gateway", "sk9000001", "UPDATE: Resolved " + cve + " - synthetic. Refer to sk9000001.");
    }

    static JumboStatement mention(String train, int take, String cve) {
        return new JumboStatement(train, take, cve, "MENTION", "Gaia OS", "sk9000002", "UPDATE: Check Point response to " + cve + ". Refer to sk9000002.");
    }

    private ParsedAdvisory parseRecord(byte[] record, JumboStatement... statements) {
        var result = parser.parse(RECORD, record, cve -> List.of(statements).stream().filter(s -> s.cveId().equals(cve)).toList());
        assertEquals(1, result.advisories().size(), "one advisory per record");
        return result.advisories().getFirst();
    }

    private Verdict verdict(ParsedAdvisory a, String product, String version) {
        List<ProductRule> rules = a.rules().stream().filter(r -> r.productKey().equals(product)).toList();
        return evaluator.evaluate(product, version, a.parseState(), rules);
    }

    // ---- resolved-issues page -------------------------------------------------------------------------------------

    @Test
    void aPageYieldsOneStatementPerTakeAndCveWithItsKind() {
        byte[] html = page(
                section(166, row("PRJ-1,<br/>PMTR-2", "VPN", "<span class=\"UPDATE_label\">UPDATE</span>: Resolved " + cveLink("CVE-2026-90001")
                        + " - synthetic overflow. Refer to <a href=\"https://support.checkpoint.com/results/sk/sk9000001\">sk9000001</a>.")),
                section(120, row("PRJ-3", "Gaia OS", "UPDATE: Check Point response to CVE-2019-90002, CVE-2019-90003. Refer to sk9000002."),
                        row("PRJ-4", "Security Gateway", "UPDATE: Something unrelated was improved.")),
                section(90, row("PRJ-5", "Gaia OS", "UPDATE: A component was updated from 2.4.55 to 2.4.61 to fix: CVE-2023-90004, CVE-2023-90005.")),
                section(65, row("PRHF-6", "Security Gateway", "CVE-2024-90006 - Synthetic information disclosure. Refer to sk9000006.")),
                section(14, row("PRJ-7", "Gaia OS", "UPDATE: Added a defense against an injection in the portal (CVE-2023-90007). Refer to sk9000007.")));
        var result = parser.parse(PAGE_R8120, html);
        assertEquals("R81.20", result.jumboTrain());
        assertEquals(List.of(), result.advisories(), "a page yields statements, not advisories");
        assertEquals(0, result.rejected());
        Map<String, JumboStatement> byCve = result.jumboStatements().stream().collect(Collectors.toMap(JumboStatement::cveId, s -> s));
        assertEquals(7, byCve.size());
        assertEquals(new JumboStatement("R81.20", 166, "CVE-2026-90001", "FIX", "VPN", "sk9000001",
                "UPDATE: Resolved CVE-2026-90001 - synthetic overflow. Refer to sk9000001."), byCve.get("CVE-2026-90001"));
        assertEquals("MENTION", byCve.get("CVE-2019-90002").kind(), "a response is not a resolution");
        assertEquals("MENTION", byCve.get("CVE-2019-90003").kind());
        assertEquals(120, byCve.get("CVE-2019-90003").take());
        assertEquals("FIX", byCve.get("CVE-2023-90004").kind(), "'to fix:' is a resolution");
        assertEquals("FIX", byCve.get("CVE-2023-90005").kind());
        assertEquals("FIX", byCve.get("CVE-2024-90006").kind(), "a line that starts with the CVE id is its resolution");
        assertEquals("MENTION", byCve.get("CVE-2023-90007").kind(), "a defense in other words is not read as a fix");
        assertNull(byCve.get("CVE-2023-90004").skRef());
    }

    @Test
    void aPageOfAnotherFormOrTrainChangesNothing() {
        var noSections = parser.parse(PAGE_R8120, "<html><table><tr><td>a</td><td>b</td><td>Resolved CVE-2026-90001</td></tr></table></html>"
                .getBytes(StandardCharsets.UTF_8));
        assertNull(noSections.jumboTrain(), "no take section: not this page form, nothing is replaced");
        assertEquals(1, noSections.rejected());
        var otherTrain = parser.parse("https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.10/All_Resolved_Issues.html",
                page(section(1, row("a", "b", "Resolved CVE-2026-90001"))));
        assertNull(otherTrain.jumboTrain());
        var badRow = parser.parse(PAGE_R8120, page(section(5, "<tr class=\"- topic/row\"><td>only</td><td>Resolved CVE-2026-90001</td></tr>")));
        assertEquals("R81.20", badRow.jumboTrain());
        assertEquals(1, badRow.rejected(), "a CVE row of another shape is counted, not guessed");
        assertEquals(List.of(), badRow.jumboStatements());
        assertEquals(1, parser.parse("https://example.org/x", new byte[] {1}).rejected());
    }

    // ---- CVE records --------------------------------------------------------------------------------------------

    @Test
    void aTakeStatementOfCheckPointDecidesAndTheResolvedTakeAgrees() {
        ParsedAdvisory a = parseRecord(record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateway",
                "R82 with Jumbo Hotfix Take 125 or below", "R81.20 with Jumbo Hotfix Take 165 or below") + ","
                + entry("Quantum Security Management", "R81.20 with Jumbo Hotfix Take 165 or below") + "]"),
                fix("R81.20", 166, "CVE-2026-90001"), fix("R82", 126, "CVE-2026-90001"));
        assertEquals(ParseState.PARSED, a.parseState());
        assertNull(a.parseNote());
        assertEquals("checkpoint:CVE-2026-90001", a.advisoryId());
        assertEquals(AdvisoryModel.Severity.CRITICAL, a.severity());
        assertEquals("https://support.checkpoint.com/results/sk/sk9000001", a.sourceUrl());
        assertEquals(new Verdict(VerdictKind.AFFECTED, "IN_RANGE", "R81.20 Jumbo Take 166: UPDATE: Resolved CVE-2026-90001 - synthetic. Refer to sk9000001."),
                verdict(a, GW, "R81.20 Jumbo Take 165"));
        assertEquals(VerdictKind.FIXED, verdict(a, GW, "R81.20 Jumbo Take 166").kind());
        assertEquals(VerdictKind.AFFECTED, verdict(a, GW, "R82 Jumbo Take 1").kind());
        assertEquals(VerdictKind.FIXED, verdict(a, GW, "R82 Jumbo Take 130").kind());
        assertEquals(VerdictKind.AFFECTED, verdict(a, MGMT, "R81.20 Jumbo Take 100").kind());
        assertEquals(new Verdict(VerdictKind.NOT_LISTED, "TRAIN_NOT_NAMED", null), verdict(a, MGMT, "R82 Jumbo Take 1"),
                "the record names R82 for gateways only");
        assertEquals(VerdictKind.NOT_EVALUABLE, verdict(a, GW, "R81.20").kind(), "no take: never judged");
    }

    @Test
    void aResolvedTakeAtOrBelowTheLastAffectedTakeContradictsTheRecord() {
        ParsedAdvisory a = parseRecord(record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateway",
                "R81.20 with Jumbo Hotfix Take 165 or below") + "]"), fix("R81.20", 160, "CVE-2026-90001"));
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("SELF_CHECK_FAILED", a.parseNote());
        assertEquals(new Verdict(VerdictKind.NOT_EVALUABLE, "ADVISORY_NEEDS_REVIEW", null), verdict(a, GW, "R81.20 Jumbo Take 170"));
    }

    @Test
    void aTrainNamedWithoutATakeGetsTheResolvedTakeAsAProposalNeverAnAffectedRange() {
        String sentence = "Check Point Quantum Gateway and CloudGuard Network versions R81.20, R81.10, R81 and Check Point Spark versions R81.10, R80.20.";
        ParsedAdvisory a = parseRecord(record("CVE-2026-90001", "checkpoint", "[" + entry("Check Point Quantum Gateway, Spark Gateway and CloudGuard Network",
                sentence) + "]"), fix("R81.20", 65, "CVE-2026-90001"));
        assertEquals(ParseState.PARSED, a.parseState());
        Verdict below = verdict(a, GW, "R81.20 Jumbo Take 64");
        assertEquals(VerdictKind.NOT_EVALUABLE, below.kind());
        assertEquals("FIX_RULE_UNCONFIRMED", below.basis(), "pending: the vendor never said take 64 is affected");
        assertTrue(AdvisoryModel.PENDING_BASES.contains(below.basis()));
        assertEquals("R81.20 Jumbo Take 65: UPDATE: Resolved CVE-2026-90001 - synthetic. Refer to sk9000001.", below.fixedText());
        assertEquals(new Verdict(VerdictKind.FIXED, "JUMBO_FIX_INCLUDED", below.fixedText()), verdict(a, GW, "R81.20 Jumbo Take 65"));
        assertEquals(VerdictKind.NOT_LISTED, verdict(a, MGMT, "R81.20 Jumbo Take 1").kind(), "management is not named");
        List<String> trains = a.rules().stream().map(ProductRule::train).toList();
        assertEquals(List.of("R81.20", "R81.10", "R81"), trains, "Spark's own trains are not Gaia gateway trains");
        assertEquals(new Verdict(VerdictKind.NOT_EVALUABLE, "NO_FIX_TAKE_PUBLISHED", null), evaluator.evaluate(GW, "R81.20 Jumbo Take 1",
                ParseState.PARSED, parseRecord(record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateways", "R81.20") + "]"))
                        .rules()), "named without any resolved take: open, not affected");
    }

    @Test
    void productsTheEntryNamesButTheSentenceDoesNotGetItsTrainsOnlyAsProposals() {
        ParsedAdvisory a = parseRecord(record("CVE-2026-90001", "checkpoint", "[" + entry("ClusterXL, Multi-Domain Security Management, "
                + "Quantum Maestro, Quantum Security Gateways, Quantum Security Management", "Check Point Quantum Gateways versions R81, R81.20") + "]"),
                fix("R81.20", 79, "CVE-2026-90001"));
        assertEquals(ParseState.PARSED, a.parseState());
        assertEquals("FIX_RULE_UNCONFIRMED", verdict(a, MGMT, "R81.20 Jumbo Take 78").basis(), "management: never affected, pending");
        assertEquals(VerdictKind.FIXED, verdict(a, MGMT, "R81.20 Jumbo Take 79").kind());
        assertEquals("FIX_RULE_UNCONFIRMED", verdict(a, GW, "R81.20 Jumbo Take 78").basis());
        ParsedAdvisory twoGroups = parseRecord(record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Management, Quantum Gateway",
                "Quantum Gateway versions R81.20 and Spark versions R81.10") + "]"));
        assertEquals("RECORD_CONFLICT", twoGroups.parseNote(), "several groups: which trains the other products have is not said");
    }

    @Test
    void aThirdPartyCveIsAProposalForBothProductsOnTheTrainsThatResolvedIt() {
        ParsedAdvisory a = parseRecord(record("CVE-2026-90001", "someoneelse", "[{\"vendor\":\"x\",\"product\":\"libsynthetic\","
                + "\"versions\":[{\"status\":\"affected\",\"version\":\"1.0\"}]}]"), fix("R82", 40, "CVE-2026-90001"));
        assertEquals(ParseState.PARSED, a.parseState());
        assertEquals("THIRD_PARTY_CVE", a.parseNote());
        assertEquals(VerdictKind.FIXED, verdict(a, MGMT, "R82 Jumbo Take 40").kind());
        assertEquals("FIX_RULE_UNCONFIRMED", verdict(a, GW, "R82 Jumbo Take 39").basis());
        assertEquals(VerdictKind.NOT_LISTED, verdict(a, GW, "R81.20 Jumbo Take 39").kind(), "R81.20's page does not name it");
    }

    @Test
    void severalResolvedTakesOrOnlyAMentionStayPending() {
        ParsedAdvisory several = parseRecord(record("CVE-2026-90001", "someoneelse", "[]"),
                fix("R81.20", 111, "CVE-2026-90001"), fix("R81.20", 118, "CVE-2026-90001"));
        assertEquals("MULTIPLE_FIX_TAKES", verdict(several, GW, "R81.20 Jumbo Take 115").basis());
        assertEquals(VerdictKind.FIXED, verdict(several, GW, "R81.20 Jumbo Take 118").kind(), "at or above every resolved take");
        ParsedAdvisory mentioned = parseRecord(record("CVE-2026-90001", "someoneelse", "[]"), mention("R81.20", 120, "CVE-2026-90001"));
        assertEquals(new Verdict(VerdictKind.NOT_EVALUABLE, "FIX_STATEMENT_UNCLEAR",
                "R81.20 Jumbo Take 120: UPDATE: Check Point response to CVE-2026-90001. Refer to sk9000002."), verdict(mentioned, GW, "R81.20 Jumbo Take 999"));
    }

    @Test
    void aProductSubsetIsNeverJudgedAffectedButIsClearedAboveTheLastAffectedTake() {
        ParsedAdvisory a = parseRecord(record("CVE-2026-90001", "checkpoint", "[" + entry("Scalable Platforms (ElasticXL, Maestro, Chassis)",
                "R82 with Jumbo Hotfix Take 130 or below") + "]"));
        assertEquals(ParseState.PARSED, a.parseState());
        assertEquals("PRODUCT_SUBSET_UNKNOWN", verdict(a, GW, "R82 Jumbo Take 130").basis());
        assertEquals(new Verdict(VerdictKind.FIXED, "ABOVE_AFFECTED_TAKE", "Affected up to R82 Jumbo Take 130 (vendor CVE record)"),
                verdict(a, GW, "R82 Jumbo Take 131"));
        assertEquals(VerdictKind.NOT_LISTED, verdict(a, MGMT, "R82 Jumbo Take 1").kind());
    }

    @Test
    void anUnknownProductVersionFormOrStatusMakesTheAdvisoryAnOpenQuestion() {
        for (var c : List.of(
                Map.entry(entry("Quantum Firewall Thing", "R81.20 with Jumbo Hotfix Take 1 or below"), "UNKNOWN_PRODUCT"),
                Map.entry(entry("Quantum Security Gateway", "R81.20 Jumbo Take 165 and earlier"), "UNKNOWN_VERSION_FORM"),
                Map.entry(entry("Quantum Security Gateway", "R81.20 with Jumbo Hotfix Take 165 or below", "R81.20 with Jumbo Hotfix Take 160 or below"), "RECORD_CONFLICT"),
                Map.entry("{\"vendor\":\"checkpoint\",\"product\":\"Quantum Security Gateway\",\"versions\":[{\"status\":\"unknown\",\"version\":\"R81.20\"}]}", "UNKNOWN_STATUS"),
                Map.entry("{\"vendor\":\"checkpoint\",\"product\":\"Quantum Security Gateway\",\"versions\":[{\"status\":\"affected\",\"version\":\"R81.20\",\"lessThan\":\"R82\"}]}", "UNKNOWN_VERSION_FORM"))) {
            ParsedAdvisory a = parseRecord(record("CVE-2026-90001", "checkpoint", "[" + c.getKey() + "]"), fix("R81.20", 200, "CVE-2026-90001"));
            assertEquals(ParseState.NEEDS_REVIEW, a.parseState(), c.getValue());
            assertEquals(c.getValue(), a.parseNote());
            assertEquals(VerdictKind.NOT_EVALUABLE, verdict(a, GW, "R81.20 Jumbo Take 300").kind(), c.getValue());
            assertEquals(VerdictKind.NOT_EVALUABLE, verdict(a, MGMT, "R81.20 Jumbo Take 300").kind(), "both products: " + c.getValue());
        }
        ParsedAdvisory empty = parseRecord(record("CVE-2026-90001", "checkpoint", "[]"));
        assertEquals("NO_AFFECTED", empty.parseNote());
        assertEquals("*", empty.rules().getFirst().train(), "no train known: the whole product");
    }

    @Test
    void aRecordThatIsNotPublishedOrNotJsonIsRejected() {
        String rejected = new String(record("CVE-2026-90001", "checkpoint", "[]"), StandardCharsets.UTF_8).replace("PUBLISHED", "REJECTED");
        assertEquals(1, parser.parse(RECORD, rejected.getBytes(StandardCharsets.UTF_8), cve -> List.of()).rejected());
        assertEquals(1, parser.parse(RECORD, "not json".getBytes(StandardCharsets.UTF_8), cve -> List.of()).rejected());
    }

    // ---- comparator, codec, core ----------------------------------------------------------------------------------

    @Test
    void theGaiaComparatorReadsTheStoredJumboTakeAndNothingElse() {
        assertEquals("R81.20 Jumbo Take 119", CheckPointGaiaComparator.compose("R81.20", "R81.20 Jumbo Take 119"));
        assertEquals("R81.20", CheckPointGaiaComparator.compose(" R81.20 ", null));
        assertEquals("R82 (hotfix level of R81.20)", CheckPointGaiaComparator.compose("R82", "R81.20 Jumbo Take 119"));
        assertNull(CheckPointGaiaComparator.compose(null, "R81.20 Jumbo Take 119"));
        var c = new CheckPointGaiaComparator(GW);
        assertEquals(Optional.of("R82 Jumbo Take 127"), c.canonical("R82 Jumbo Take 127"));
        for (String bad : List.of("R81.20", "R81.20 Jumbo Take 0", "R81.20 Jumbo Take 007", "R81.20 jumbo take 5", "R81.20 Jumbo Take 5 ", "Take 5")) {
            assertEquals(Optional.empty(), c.canonical(bad), bad);
        }
        assertEquals(Optional.of("R82.10"), c.train("R82.10 Jumbo Take 3"));
        assertTrue(c.compare("R81.20 Jumbo Take 99", "R81.20 Jumbo Take 100") < 0, "takes compare as numbers");
        assertThrows(IllegalArgumentException.class, () -> c.compare("R81.20 Jumbo Take 1", "R82 Jumbo Take 1"));
        assertEquals("JUMBO_TAKE_MISSING", c.unparsedReason("R81.20"));
        assertEquals("JUMBO_TRAIN_MISMATCH", c.unparsedReason("R82 (hotfix level of R81.20)"));
        assertEquals("VERSION_UNPARSED", c.unparsedReason("something"));
        assertEquals(Optional.of("SPARK_NOT_COVERED"), c.notCoveredReason("R81.10.15"));
        assertEquals(Optional.of("TRAIN_NOT_COVERED"), c.notCoveredReason("R81.10"));
        assertEquals(Optional.of("TRAIN_NOT_COVERED"), c.notCoveredReason("R80.40 Jumbo Take 200"));
        assertEquals(Optional.empty(), c.notCoveredReason("R81.20 Jumbo Take 1"));
        assertEquals(Optional.empty(), c.notCoveredReason("R82"), "covered; judged as JUMBO_TAKE_MISSING instead");
    }

    @Test
    void theJumboRuleSurvivesTheStoredForm() {
        List<AdvisoryModel.VersionRule> rules = List.of(new AdvisoryModel.JumboFix("R81.20 Jumbo Take 65", false, "FIX_RULE_UNCONFIRMED"),
                new AdvisoryModel.JumboFix(null, true, "NO_FIX_TAKE_PUBLISHED"));
        assertEquals(rules, AdvisoryRuleCodec.fromJson(AdvisoryRuleCodec.toJson(rules)));
        assertEquals(List.of(), AdvisoryRuleCodec.fromJson("{\"v\":1,\"rules\":[{\"k\":\"jumbofix\",\"status\":\"UNAFFECTED\",\"from\":null,"
                + "\"exclusive\":false,\"basis\":\"not a code word\"}]}"));
        assertEquals(List.of(), AdvisoryRuleCodec.fromJson("{\"v\":1,\"rules\":[{\"k\":\"jumbofix\",\"status\":\"AFFECTED\",\"from\":null,"
                + "\"exclusive\":false,\"basis\":\"X\"}]}"), "a Jumbo rule never says affected");
    }

    @Test
    void theRoleDecidesTheCheckPointProduct() {
        var core = new DefaultAdvisoryCore(new ArrayList<>(List.of(parser)));
        assertEquals(Optional.of(GW), core.productKey("check_point", "gateway", null));
        assertEquals(Optional.of(MGMT), core.productKey("check_point", "management_server", "Smart-1"));
        assertEquals(Optional.empty(), core.productKey("check_point", null, null));
        assertTrue(core.comparator(GW).isPresent());
        assertTrue(core.parser("checkpoint").isPresent());
    }
}
