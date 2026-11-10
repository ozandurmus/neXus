package com.securityexpert.nexus.ui2.service.advisorystore;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdvisorySourcesTest {
    @Test void documentAddressesMustBeHttpsOnTheSourceHostAndPath() {
        assertTrue(AdvisorySources.documentUrlAllowed("paloalto", "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories"));
        assertTrue(AdvisorySources.documentUrlAllowed("fortinet", "https://filestore.fortinet.com/fortiguard/rss/ir.xml"));
        assertTrue(AdvisorySources.documentUrlAllowed("fortinet", "https://filestore.fortinet.com/fortiguard/psirt/FG-IR-26-150.json"));
        assertTrue(AdvisorySources.documentUrlAllowed("cisco", "https://www.cisco.com/.well-known/csaf/changes.csv"));
        assertTrue(AdvisorySources.documentUrlAllowed("cisa_kev", "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"));
        for (String bad : new String[] {
                "http://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories",
                "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories?x=1",
                "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories/extra",
                "https://security.paloaltonetworks.com.example.test/api/v1/products/PAN-OS/advisories",
                "https://user@security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories",
                "https://security.paloaltonetworks.com:8443/api/v1/products/PAN-OS/advisories",
                "https://www.cisco.com/.well-known/csaf/../secret",
                "https://www.cisco.com/.well-known/csaf/%2e%2e/secret",
                "https://www.cisco.com/.well-known/csaf/",
                "https://www.cisco.com/other", "ftp://www.cisco.com/.well-known/csaf/x", "", "not a url"}) {
            assertFalse(AdvisorySources.documentUrlAllowed(paloOrCisco(bad), bad), bad);
        }
        assertFalse(AdvisorySources.documentUrlAllowed("fortinet", "https://www.cisco.com/.well-known/csaf/x"), "another source's host");
        assertFalse(AdvisorySources.documentUrlAllowed(null, "https://www.cisco.com/.well-known/csaf/x"));
        assertFalse(AdvisorySources.documentUrlAllowed("cisco", null));
    }

    private static String paloOrCisco(String url) { return url.contains("cisco") ? "cisco" : "paloalto"; }

    @Test void linksAreShownOnlyOnTheSourcesLinkHosts() {
        assertTrue(AdvisorySources.linkAllowed("paloalto", "https://security.paloaltonetworks.com/CVE-2026-0001"));
        assertTrue(AdvisorySources.linkAllowed("fortinet", "https://www.fortiguard.com/psirt/FG-IR-26-150"));
        assertFalse(AdvisorySources.linkAllowed("paloalto", "https://evil.example.test/CVE-2026-0001"));
        assertFalse(AdvisorySources.linkAllowed("paloalto", "http://security.paloaltonetworks.com/x"));
        assertFalse(AdvisorySources.linkAllowed("paloalto", "javascript:alert(1)"));
        assertFalse(AdvisorySources.linkAllowed("paloalto", null));
    }

    @Test void theFourSourceKeysAreKnown() {
        assertEquals(4, AdvisorySources.KEYS.size());
        AdvisorySources.KEYS.forEach(k -> assertTrue(AdvisorySources.known(k)));
        assertFalse(AdvisorySources.known("checkpoint"));
    }
}
