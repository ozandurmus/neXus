package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParsedAdvisory;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.VerdictKind;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PaloAltoAdvisoryParserTest {

    private static final String URL = "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories";
    private static Map<String, ParsedAdvisory> byId;
    private final AdvisoryEvaluator ev = new DefaultAdvisoryCore(List.of()).evaluator();

    @BeforeAll
    static void load() throws Exception {
        byte[] doc;
        try (InputStream in = PaloAltoAdvisoryParserTest.class.getResourceAsStream("/advisory/pan_os_advisories_sample.json")) {
            doc = in.readAllBytes();
        }
        AdvisoryModel.ParseResult r = new PaloAltoAdvisoryParser().parse(URL, doc);
        assertEquals(0, r.rejected());
        byId = new LinkedHashMap<>();
        r.advisories().forEach(a -> byId.put(a.advisoryId(), a));
        assertEquals(16, byId.size());
    }

    private AdvisoryModel.Verdict verdict(String id, String version) {
        ParsedAdvisory a = byId.get("paloalto:" + id);
        List<AdvisoryModel.ProductRule> rules = a.rules();
        return ev.evaluate(AdvisoryModel.PRODUCT_PAN_OS, version, a.parseState(), rules);
    }

    @Test
    void headerFieldsComeFromTheRecord() {
        ParsedAdvisory a = byId.get("paloalto:CVE-2025-4619");
        assertEquals("paloalto", a.source());
        assertEquals("https://security.paloaltonetworks.com/CVE-2025-4619", a.sourceUrl());
        assertEquals(List.of("CVE-2025-4619"), a.cveIds());
        assertEquals(ParseState.PARSED, a.parseState());
        assertNull(a.parseNote());
        assertNotNull(a.publishedOn());
        assertTrue(a.title().length() <= 300);
        assertNotNull(a.cvssScore());
        assertTrue(a.severity() != AdvisoryModel.Severity.UNKNOWN);
        assertTrue(byId.get("paloalto:PAN-SA-2025-0005").cveIds().isEmpty());
    }

    @Test
    void changePointsInOneEntry() {
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2025-4619", "11.1.4-h12").kind());
        assertEquals(VerdictKind.FIXED, verdict("CVE-2025-4619", "11.1.4-h13").kind());
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2025-4619", "11.1.5").kind());
        assertEquals(VerdictKind.FIXED, verdict("CVE-2025-4619", "11.1.7").kind());
        ParsedAdvisory a = byId.get("paloalto:CVE-2025-4619");
        AdvisoryModel.ProductRule train = a.rules().stream().filter(r -> r.train().equals("11.1")).findFirst().orElseThrow();
        assertEquals("11.1.2-h18, 11.1.4-h13, 11.1.6-h1, 11.1.7", train.fixedText());
    }

    @Test
    void onlyPanOsEntriesBecomeRules() {
        ParsedAdvisory a = byId.get("paloalto:CVE-2026-111497");
        assertTrue(a.rules().stream().allMatch(r -> r.productKey().equals(AdvisoryModel.PRODUCT_PAN_OS)));
        assertEquals(ParseState.PARSED, a.parseState());
    }

    @Test
    void wholeTrainForms() {
        assertEquals(VerdictKind.NOT_AFFECTED, verdict("CVE-2024-9471", "11.1.2").kind());
        assertEquals("TRAIN_UNAFFECTED", verdict("CVE-2024-9471", "11.1.2").basis());
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2024-9471", "9.1.5").kind());
        assertEquals(VerdictKind.NOT_AFFECTED, verdict("CVE-2022-0023", "10.2.4").kind());
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2022-0011", "9.0.3").kind());
        assertEquals(VerdictKind.AFFECTED, verdict("PAN-SA-2025-0005", "11.1.2").kind(), "product-wide All");
    }

    @Test
    void versionAloneAndEndOfTrainBound() {
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2026-0282", "10.2.14").kind());
        assertEquals(VerdictKind.NOT_AFFECTED, verdict("CVE-2022-0024", "10.2.3").kind());
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2022-0024", "10.1.3").kind());
        assertEquals(VerdictKind.FIXED, verdict("CVE-2022-0024", "10.1.5").kind());
        assertEquals(VerdictKind.NOT_LISTED, verdict("CVE-2022-0024", "11.0.1").kind());
    }

    @Test
    void inclusiveBoundWithHotfixPoint() {
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2019-1581", "7.1.24").kind());
        assertEquals(VerdictKind.FIXED, verdict("CVE-2019-1581", "7.1.24-h1").kind());
    }

    @Test
    void blankPaddingOfChangePointsIsRemoved() {
        assertEquals(ParseState.PARSED, byId.get("paloalto:CVE-2024-0012").parseState());
        assertEquals(VerdictKind.FIXED, verdict("CVE-2024-0012", "11.0.1-h5").kind());
        assertEquals(VerdictKind.AFFECTED, verdict("CVE-2024-0012", "11.0.1-h4").kind());
    }

    @Test
    void unknownFormsNeedReviewAndKeepAPlaceholderRule() {
        ParsedAdvisory text = byId.get("paloalto:PAN-SA-2023-0004");
        assertEquals(ParseState.NEEDS_REVIEW, text.parseState());
        assertEquals("UNKNOWN_VERSION_FORM", text.parseNote());
        assertEquals(VerdictKind.NOT_EVALUABLE, verdict("PAN-SA-2023-0004", "11.1.2").kind());
        ParsedAdvisory unspecified = byId.get("paloalto:CVE-2019-1559");
        assertEquals("UNKNOWN_STATUS", unspecified.parseNote());
        assertEquals(VerdictKind.NOT_EVALUABLE, verdict("CVE-2019-1559", "7.0.1").kind());
        assertEquals("CHANGE_POINT_FORM", byId.get("paloalto:CVE-2017-15944").parseNote());
    }

    @Test
    void selfCheckCatchesVendorListsThatDisagree() {
        ParsedAdvisory a = byId.get("paloalto:CVE-2025-4614");
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("SELF_CHECK_FAILED", a.parseNote());
        ParsedAdvisory b = byId.get("paloalto:CVE-2024-3393");
        assertEquals("SELF_CHECK_FAILED", b.parseNote());
    }

    @Test
    void untrustedDocumentsNeverThrow() {
        PaloAltoAdvisoryParser p = new PaloAltoAdvisoryParser();
        for (String s : new String[] {"", "not json", "[]", "{}", "{\"data\":5}", "{\"data\":[1,\"x\",null,{}]}",
            "{\"data\":[{\"cveMetadata\":{\"cveId\":\"CVE-2026-1\"},\"containers\":{\"cna\":{\"affected\":[{\"product\":\"PAN-OS\",\"versions\":[{\"version\":5}]}]}}}]}"}) {
            AdvisoryModel.ParseResult r = p.parse(URL, s.getBytes(StandardCharsets.UTF_8));
            assertTrue(r.advisories().size() <= 1, s);
        }
        assertEquals(0, p.parse(URL, null).advisories().size());
        assertEquals(0, p.parse(URL, ("{\"data\":" + "[".repeat(5000)).getBytes(StandardCharsets.UTF_8)).advisories().size());
    }
}
