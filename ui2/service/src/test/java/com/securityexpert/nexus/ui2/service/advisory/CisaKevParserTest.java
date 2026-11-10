package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.KnownExploited;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseResult;

/** Contract section 6, "Known-exploited flag": the CISA catalog yields flags only. */
class CisaKevParserTest {

    private final CisaKevParser parser = new CisaKevParser();

    @Test
    void catalogGivesFlagsAndNoAdvisories() {
        ParseResult result = parser.parse("u", CsafTestResources.bytes("cisa/kev_small.json"));
        assertTrue(result.advisories().isEmpty());
        assertEquals(0, result.rejected());
        assertEquals(3, result.knownExploited().size());
        assertEquals(new KnownExploited("CVE-2024-3400", java.time.LocalDate.parse("2024-04-12"), true), result.knownExploited().get(0));
        assertFalse(result.knownExploited().get(1).ransomwareUse());
        assertEquals("CVE-2021-1000123", result.knownExploited().get(2).cveId());
    }

    @Test
    void badEntriesAreCountedAndTheRestIsKept() {
        String json = "{\"vulnerabilities\":[{\"cveID\":\"CVE-2024-1\",\"dateAdded\":\"2024-01-01\"},"
                + "{\"cveID\":\"CVE-2024-12345\",\"dateAdded\":\"01/02/2024\"},{\"cveID\":\"CVE-2024-12345\"},{\"dateAdded\":\"2024-01-01\"},"
                + "\"text\",{\"cveID\":\"CVE-2024-12345\",\"dateAdded\":\"2024-02-30\"},"
                + "{\"cveID\":\"CVE-2025-0001\",\"dateAdded\":\"2025-05-05\",\"knownRansomwareCampaignUse\":\"Known\"}]}";
        ParseResult result = parser.parse("u", json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.knownExploited().size());
        assertEquals(6, result.rejected());
        assertTrue(result.knownExploited().getFirst().ransomwareUse());
    }

    @Test
    void notTheCatalogIsRejectedWithoutThrowing() {
        for (byte[] bad : new byte[][] {null, new byte[0], "<HTML>Access Denied</HTML>".getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_8), "{\"vulnerabilities\":{}}".getBytes(StandardCharsets.UTF_8),
                ("{\"a\":" + "[".repeat(100_000)).getBytes(StandardCharsets.UTF_8)}) {
            ParseResult result = parser.parse("u", bad);
            assertTrue(result.knownExploited().isEmpty());
            assertEquals(1, result.rejected());
        }
    }

    @Test
    void sourceKey() {
        assertEquals("cisa_kev", parser.source());
    }
}
