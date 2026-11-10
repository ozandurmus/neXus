package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;

/** Contract section 6, "Cisco rule": CSAF 2.0 files that name the ASA software family. */
class CiscoAdvisoryParserTest {

    private final CiscoAdvisoryParser parser = new CiscoAdvisoryParser();

    private ParsedAdvisory one(String json) {
        ParseResult result = parser.parse("https://example.invalid/x.json", json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.advisories().size());
        return result.advisories().getFirst();
    }

    private static final String FAMILY = "Cisco Secure Firewall Adaptive Security Appliance (ASA) Software";

    /** A small document; {@code family} is the raw JSON of the family's branches, {@code affected} the known_affected ids. */
    private static String doc(String familyName, String familyBranches, String relationships, String affected) {
        return "{\"document\":{\"title\":\"Title\",\"tracking\":{\"id\":\"cisco-sa-Test-AbC123\",\"initial_release_date\":\"2026-03-01T10:00:00+00:00\","
                + "\"current_release_date\":\"2026-04-01T10:00:00+00:00\"}},\"product_tree\":{\"branches\":[{\"category\":\"vendor\",\"name\":\"Cisco\","
                + "\"branches\":[{\"category\":\"product_family\",\"name\":\"" + familyName + "\",\"branches\":" + familyBranches + "}]}],"
                + "\"relationships\":" + relationships + "},\"vulnerabilities\":[{\"cve\":\"CVE-2026-20001\","
                + "\"scores\":[{\"cvss_v3\":{\"baseScore\":8.6}}],\"product_status\":{\"known_affected\":[" + affected + "]}}]}";
    }

    private static String release(String version, String id) {
        return "{\"category\":\"service_pack\",\"name\":\"" + version + "\",\"product\":{\"product_id\":\"" + id + "\",\"name\":\"ASA " + version + "\"}}";
    }

    private static String train(String name, String... releases) {
        return "{\"category\":\"product_version\",\"name\":\"" + name + "\",\"branches\":[" + String.join(",", releases) + "]}";
    }

    private static String rel(String releaseId, String platform) {
        return "{\"category\":\"installed_on\",\"full_product_name\":{\"name\":\"n\",\"product_id\":\"" + releaseId + ":" + platform
                + "\"},\"product_reference\":\"" + releaseId + "\",\"relates_to_product_reference\":\"" + platform + "\"}";
    }

    @Test
    void changeListYieldsNothing() {
        assertEquals(ParseResult.empty(), parser.parse("u", CsafTestResources.bytes("cisco/changes_small.csv")));
    }

    @Test
    void realShapedFileGivesExactReleasesPerTrain() {
        ParsedAdvisory a = one(new String(CsafTestResources.bytes("cisco/asa_small.json"), StandardCharsets.UTF_8));
        assertEquals("cisco:cisco-sa-asaftd-vpn-dos-dzv4mqff", a.advisoryId());
        assertEquals("cisco", a.source());
        assertEquals(ParseState.PARSED, a.parseState());
        assertEquals(Severity.HIGH, a.severity());
        assertEquals(List.of("CVE-2026-20349"), a.cveIds());
        assertEquals("2026-08-11", a.publishedOn().toString());
        assertEquals("2026-09-16", a.updatedOn().toString());
        assertTrue(a.sourceUrl().startsWith("https://example.invalid/") && a.sourceUrl().endsWith("cisco-sa-asaftd-vpn-dos-dzv4mQFF"));
        // 9.16.4 and the FTD release are not affected; 9.20.1 is affected through its own product id
        assertEquals(3, a.rules().size());
        assertTrue(a.rules().stream().allMatch(r -> r.productKey().equals(AdvisoryModel.PRODUCT_CISCO_ASA)));
        assertEquals(List.of(new ExactVersions(Status.AFFECTED, Set.of("9.16.1", "9.16.2.14"))), rule(a, "9.16").rules());
        assertEquals(List.of(new ExactVersions(Status.AFFECTED, Set.of("9.18.4", "9.18.4.50"))), rule(a, "9.18").rules());
        assertEquals(List.of(new ExactVersions(Status.AFFECTED, Set.of("9.20.1"))), rule(a, "9.20").rules());
    }

    private static ProductRule rule(ParsedAdvisory a, String train) {
        return a.rules().stream().filter(r -> r.train().equals(train)).findFirst().orElseThrow();
    }

    @Test
    void fileWithoutTheAsaFamilyHasNoRules() {
        ParsedAdvisory a = one(doc("Cisco Secure Firewall Threat Defense (FTD) Software", "[" + train("7.0", release("7.0.0", "P1")) + "]",
                "[" + rel("P1", "Q1") + "]", "\"P1:Q1\""));
        assertEquals(ParseState.PARSED, a.parseState());
        assertTrue(a.rules().isEmpty());
        assertEquals("https://sec.cloudapps.cisco.com/security/center/content/CiscoSecurityAdvisory/cisco-sa-Test-AbC123", a.sourceUrl());
    }

    @Test
    void olderFamilyNameAndReleasesWithoutTrainLevel() {
        ParsedAdvisory a = one(doc("Cisco Adaptive Security Appliance (ASA) Software",
                "[" + release("9.8.4.12", "P1") + "," + release("9.8.4", "P2") + "," + release("9.12.1", "P3") + "]",
                "[" + rel("P1", "Q1") + "," + rel("P3", "Q1") + "]", "\"P1:Q1\",\"P2\",\"P3:Q1\""));
        assertEquals(ParseState.PARSED, a.parseState());
        assertEquals(Set.of("9.8.4.12", "9.8.4"), ((ExactVersions) rule(a, "9.8").rules().getFirst()).versions());
        assertEquals(Set.of("9.12.1"), ((ExactVersions) rule(a, "9.12").rules().getFirst()).versions());
    }

    @Test
    void releaseNotInKnownAffectedIsNotListed() {
        ParsedAdvisory a = one(doc(FAMILY, "[" + train("9.16", release("9.16.1", "P1"), release("9.16.2", "P2")) + "]",
                "[" + rel("P1", "Q1") + "," + rel("P2", "Q1") + "]", "\"P1:Q1\""));
        assertEquals(Set.of("9.16.1"), ((ExactVersions) rule(a, "9.16").rules().getFirst()).versions());
    }

    @Test
    void affectedReleaseWithUnreadableVersionNeedsReview() {
        ParsedAdvisory a = one(doc(FAMILY, "[" + train("9.16", release("9.16", "P1"), release("9.16.2", "P2")) + "]",
                "[" + rel("P1", "Q1") + "]", "\"P1:Q1\""));
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("ASA_AFFECTED_VERSION_UNREADABLE", a.parseNote());
        assertEquals(List.of(new ProductRule(AdvisoryModel.PRODUCT_CISCO_ASA, "*", List.of(), null)), a.rules());
    }

    @Test
    void familyWithoutAnyReadableReleaseNeedsReview() {
        ParsedAdvisory a = one(doc(FAMILY, "[]", "[]", ""));
        assertEquals(ParseState.NEEDS_REVIEW, a.parseState());
        assertEquals("ASA_NO_RELEASE_READABLE", a.parseNote());
        ParsedAdvisory b = one(doc(FAMILY, "[" + train("9.16", release("nine", "P1")) + "]", "[]", ""));
        assertEquals("ASA_NO_RELEASE_READABLE", b.parseNote());
    }

    @Test
    void hostileOrUnusableInputNeverThrows() {
        for (byte[] bad : new byte[][] {null, new byte[0], "garbage".getBytes(StandardCharsets.UTF_8),
                "{\"document\":{\"tracking\":{\"id\":\"has space\"}}}".getBytes(StandardCharsets.UTF_8)}) {
            ParseResult result = parser.parse("u", bad);
            assertTrue(result.advisories().isEmpty());
            assertEquals(1, result.rejected());
        }
        assertEquals(1, parser.parse("u", ("{\"a\":" + "[".repeat(100_000)).getBytes(StandardCharsets.UTF_8)).rejected());
    }

    @Test
    void sourceKey() {
        assertEquals("cisco", parser.source());
    }
}
