package com.securityexpert.nexus.ui2.sanitizer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.TestVault;

/**
 * The text rules on adversarial strings, negative cases first (DATA_SANITIZER_CONTRACT.md §4 "detectors on
 * adversarial strings"). Synthetic values only: RFC 5737 / RFC 3849 ranges, example.com and .test names.
 */
class TextRulesTest {

    private final TestVault vault = new TestVault();
    private final Mappers mappers = new Mappers(SanitizerKey.of(TestVault.key()),
            KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04", "core.cluster.test", "CLS-ROMEO-01")));
    private final TextSanitizer rules = new TextSanitizer(mappers, vault, new Counts());

    private String s(String text) {
        return rules.sanitize(text);
    }

    // ---- what must stay ------------------------------------------------------------------------------------

    @Test
    void versionStringsOidsAndDottedNumbersAreNotAddresses() {
        for (String text : List.of("firmware v1.2.3.4 installed", "Version 10.2.0.1", "ver: 7.0.1.2", "build=1.2.3.4",
                "oid 1.3.6.1.4.1.9.9.1", "seq 1.2.3.4.5", "x1.2.3.4", "1.2.3.4x", "256.1.1.1", "01.2.3.4", "1.2.3.04",
                "1.2.3", "rev 192.0.2.1")) {
            assertEquals(text, s(text), "unchanged");
        }
    }

    @Test
    void timestampsRatiosAndCodeAreNotIpv6OrMacs() {
        for (String text : List.of("2026-10-16T12:30:45.123Z", "at 12:30:45", "10:20:30.5", "std::vector<int>",
                "Foo::bar()", "00:11:22:33:44", "00-11-22-33-44-55-66", "0011.2233.445", "ratio 3:4:5")) {
            assertEquals(text, s(text));
        }
    }

    @Test
    void protocolScopeAddressesNetmasksAndDefaultRoutesStay() {
        for (String text : List.of("0.0.0.0/0", "0.0.0.0", "127.0.0.1", "255.255.255.255", "255.255.255.0",
                "mask 255.255.240.0", "wildcard 0.0.0.255", "224.0.0.5", "239.255.255.250", "::", "::1", "ff02::1",
                "ff05::1:3", "::/0", "ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:fb", "00:00:00:00:00:00", "128.0.0.0",
                "192.0.0.0")) {
            assertEquals(text, s(text));
        }
    }

    @Test
    void fileNamesAndDottedIdentifiersAreNotHostNames() {
        for (String text : List.of("run setup.py", "see notes.md", "java.lang.String", "System.out.println", "e.g. this",
                "archive.zip", "a.b", "config.yaml", "1.5.2", "co.uk")) {
            assertEquals(text, s(text));
        }
    }

    // ---- what must change ----------------------------------------------------------------------------------

    @Test
    void ipv4WithPortAndCidrKeepsItsShape() {
        String out = s("connect 192.0.2.1:8080 via 198.51.100.0/24 and 203.0.113.9/32");
        assertFalse(out.contains("192.0.2.1"));
        assertFalse(out.contains("198.51.100.0"));
        assertFalse(out.contains("203.0.113.9"));
        assertTrue(Pattern.compile("connect \\d+\\.\\d+\\.\\d+\\.\\d+:8080 via \\d+\\.\\d+\\.\\d+\\.0/24 and \\d+\\.\\d+\\.\\d+\\.\\d+/32")
                .matcher(out).matches(), out);
    }

    @Test
    void ipv6FormsZonesPortsAndMappedAddresses() {
        String out = s("a [2001:db8::1]:443 b fe80::1%eth0 c 2001:db8::/32 d ::ffff:192.0.2.1 e 2001:DB8:0:0:0:0:0:2");
        assertFalse(out.contains("2001:db8::1]"));
        assertTrue(out.matches("a \\[[0-9a-f:]+\\]:443 b fe[89ab][0-9a-f]:[0-9a-f:]+%eth0 c [0-9a-f:]+::/32 d ::ffff:\\d+\\.\\d+\\.\\d+\\.\\d+ e [0-9A-F:]+"),
                out);
        assertFalse(out.contains("192.0.2.1"));
    }

    @Test
    void macsKeepTheirOuiFormatAndCase() {
        String out = s("00:1A:2B:3C:4D:5E 00-1a-2b-3c-4d-5f 001a.2b3c.4d60");
        String[] parts = out.split(" ");
        assertTrue(parts[0].startsWith("00:1A:2B:") && !parts[0].equals("00:1A:2B:3C:4D:5E"), out);
        assertTrue(parts[0].matches("[0-9A-F:]{17}"));
        assertTrue(parts[1].startsWith("00-1a-2b-") && parts[1].matches("[0-9a-f-]{17}"));
        assertTrue(parts[2].startsWith("001a.2b") && parts[2].matches("[0-9a-f.]{14}") && !parts[2].equals("001a.2b3c.4d60"));
    }

    @Test
    void hostNamesFqdnsIdnAndPunycodeKeepThePublicSuffix() {
        String out = s("www.example.com mail.example.co.uk srv.site.test");
        assertTrue(out.matches("h-[0-9a-f]{10}\\.h-[0-9a-f]{10}\\.com h-[0-9a-f]{10}\\.h-[0-9a-f]{10}\\.co\\.uk h-[0-9a-f]{10}\\.h-[0-9a-f]{10}\\.test"), out);
        assertEquals(s("www.example.com").split("\\.")[1], s("mail.example.com").split("\\.")[1], "same label, same token");
        String unicode = s("host bücher.example.com");
        String puny = s("host xn--bcher-kva.example.com");
        assertEquals(puny, unicode, "an IDN label and its punycode form are the same DNS label");
        assertFalse(unicode.contains("bücher"));
    }

    @Test
    void labelCaseIsCarriedByTheTokenForAnExactRevert() {
        String lower = s("www.example.com");
        String upper = s("WWW.EXAMPLE.COM");
        assertTrue(upper.matches("H-[0-9A-F]{10}\\.H-[0-9A-F]{10}\\.COM"), upper);
        assertEquals(lower.substring(2, 12).toUpperCase(), upper.substring(2, 12), "same digest, visibly the same name");
    }

    @Test
    void emailsUrlsAndUserinfo() {
        String out = s("mail john.doe@example.com or https://admin:secret@www.example.com:8443/path?q=1 now");
        assertTrue(out.matches("mail h-[0-9a-f]{10}@h-[0-9a-f]{10}\\.com or https://h-[0-9a-f]{10}:h-[0-9a-f]{10}@h-[0-9a-f]{10}\\.h-[0-9a-f]{10}\\.com:8443/path\\?q=1 now"), out);
        assertTrue(s("http://192.0.2.7/x").matches("http://\\d+\\.\\d+\\.\\d+\\.\\d+/x"));
        assertTrue(s("http://intranet/x").matches("http://h-[0-9a-f]{10}/x"), "a single-label URL host is a host");
    }

    @Test
    void keywordsSyslogHeadersAndShellPrompts() {
        assertTrue(s("hostname corefw").matches("hostname h-[0-9a-f]{10}"));
        assertTrue(s("devname=\"branchfw\" level=info").matches("devname=\"h-[0-9a-f]{10}\" level=info"));
        assertTrue(s("Oct 16 12:00:01 edgebox sshd[42]: ok").matches("Oct 16 12:00:01 h-[0-9a-f]{10} sshd\\[42\\]: ok"));
        assertTrue(s("<34>1 2026-10-16T12:00:01Z gwbox app - - msg").matches("<34>1 2026-10-16T12:00:01Z h-[0-9a-f]{10} app - - msg"));
        assertTrue(s("admin@corefw:~$ ls").matches("h-[0-9a-f]{10}@h-[0-9a-f]{10}:~\\$ ls"));
        assertEquals("host is down", s("host is down"), "free text after 'host' is not a name without = or :");
    }

    @Test
    void knownDeviceNamesUseTheInventoryPseudonym() {
        assertTrue(s("edge-fw-01 restarted, edge-fw-01.example.com").matches("FW-TANGO-04 restarted, FW-TANGO-04\\.h-[0-9a-f]{10}\\.com"));
        assertEquals("cluster CLS-ROMEO-01 ok", s("cluster core.cluster.test ok"));
        assertEquals("hostname FW-TANGO-04", s("hostname edge-fw-01"));
    }

    @Test
    void reverseDnsNamesMapTheAddressTheySpell() {
        String out = s("PTR 1.2.0.192.in-addr.arpa");
        assertTrue(out.matches("PTR \\d+\\.\\d+\\.\\d+\\.\\d+\\.in-addr\\.arpa"), out);
        assertNotEquals("PTR 1.2.0.192.in-addr.arpa", out);
        String mapped = s("192.0.2.1");
        String[] o = mapped.split("\\.");
        assertEquals("PTR " + o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa", out);
    }

    @Test
    void bareValuesAreHostsOnlyUnderAHostHeader() {
        assertEquals("corefw", rules.sanitize("corefw", false));
        assertTrue(rules.sanitize("corefw", true).matches("h-[0-9a-f]{10}"));
        assertEquals("unknown", rules.sanitize("unknown", true));
        assertEquals("12345", rules.sanitize("12345", true));
    }

    // ---- determinism and the vault -------------------------------------------------------------------------

    @Test
    void sameKeySameOutputAnotherKeyAnotherOutput() {
        String text = "192.0.2.1 2001:db8::5 00:1a:2b:3c:4d:5e www.example.com a@example.com";
        TextSanitizer again = new TextSanitizer(new Mappers(SanitizerKey.of(TestVault.key()), KnownNames.NONE),
                TokenSink.DISCARD, new Counts());
        assertEquals(s(text), again.sanitize(text));
        byte[] other = TestVault.key();
        other[0] ^= 1;
        TextSanitizer otherKey = new TextSanitizer(new Mappers(SanitizerKey.of(other), KnownNames.NONE), TokenSink.DISCARD, new Counts());
        for (String token : text.split(" ")) assertNotEquals(s(token), otherKey.sanitize(token), "token differs under another key");
    }

    @Test
    void countsReportKindsNeverValues() {
        Counts counts = new Counts();
        new TextSanitizer(mappers, TokenSink.DISCARD, counts).sanitize("192.0.2.1 2001:db8::1 198.51.100.0/24 www.example.com a@example.com 00:1a:2b:3c:4d:5e");
        assertEquals(Map.of("cidr", 1L, "email", 1L, "fqdn", 1L, "ipv4", 1L, "ipv6", 1L, "mac", 1L), counts.asMap());
    }

    @Test
    void revertRestoresOnlyVaultTokensInsideNewText() {
        String original = "src=192.0.2.10 dst=[2001:db8::7]:443 host www.example.com by john@example.com mac 00:1A:2B:3C:4D:5E";
        String sanitized = s(original);
        TextReverter reverter = new TextReverter(vault, new Counts());
        assertEquals(original, reverter.revert(sanitized));
        // An external tool wrote new text around the tokens, and invented an address and a token of its own.
        String token = sanitized.split(" ")[0].substring(4);
        String edited = "Summary: " + token + " talks to 198.51.100.200 and h-0000000000; " + sanitized.split(" ")[3];
        String reverted = reverter.revert(edited);
        assertEquals("Summary: 192.0.2.10 talks to 198.51.100.200 and h-0000000000; www.example.com", reverted);
    }

    @Test
    void ccTldTwoLabelDomainsAndHyphenatedDomainsAreSanitized() {
        // F-10: real ccTLDs (.ai, .rs, .pl, .cc, .ms) and hyphenated domains must not be suppressed by FILE_LIKE_TLDS
        String out = s("DNS query for acme-security.ai and portal.pl and banka.rs and corp.cc and acme-build.sh");
        assertFalse(out.contains("acme-security"));
        assertFalse(out.contains("portal.pl"));
        assertFalse(out.contains("banka.rs"));
        assertFalse(out.contains("corp.cc"));
        assertFalse(out.contains("acme-build.sh"));
    }

    @Test
    void digitLeadingHostnamesAndLearnedContextHostsAreSanitized() {
        // F-11: RFC 1123 digit-leading hostnames in syslog, user@host, and keyword context; context hosts are learned
        String line = "<134>Oct 16 10:00:00 10g-sw01 sshd[100]: user admin@2fa-gw from hostname=3com-fw01; later 3com-fw01 and 10g-sw01 and 2fa-gw reset";
        String out = s(line);
        assertFalse(out.contains("10g-sw01"), out);
        assertFalse(out.contains("2fa-gw"), out);
        assertFalse(out.contains("3com-fw01"), out);
    }

    @Test
    void bracketedNonIpv6UrlsSanitizeSingleLabelHost() {
        // F-12: [https://fw-tango-04] and [http://fw-tango-04:8443] must not include closing ']' in hostPort
        String out = s("Portal link: [https://intranet-gw] and [http://intranet-gw:8443]");
        assertFalse(out.contains("intranet-gw"), out);
        assertTrue(out.matches("Portal link: \\[https://h-[0-9a-f]{10}\\] and \\[http://h-[0-9a-f]{10}:8443\\]"), out);
    }

    @Test
    void bsdSingleHexDigitMacOctetsAreSanitizedAndReverted() {
        // F-15: BSD/macOS arp -a omits leading zero on single-nibble MAC octets
        String original = "? (192.0.2.10) at 0:1a:2b:3c:4d:5 on en0 ifscope [ethernet]";
        String sanitized = s(original);
        assertFalse(sanitized.contains("0:1a:2b:3c:4d:5"), sanitized);
        assertFalse(sanitized.contains("3c:4d"), sanitized);
        TextReverter reverter = new TextReverter(vault, new Counts());
        assertEquals(original, reverter.revert(sanitized));
    }

    @Test
    void ipv6WithZoneAndCidrPrefixRoundTrips() {
        // F-16: IPv6 network and interface addresses with both %zone and /prefix must revert cleanly
        String original = "route 2001:db8:1234::%eth0/48 via fe80::1234%eth0/64";
        String sanitized = s(original);
        assertFalse(sanitized.contains("2001:db8:1234"), sanitized);
        assertFalse(sanitized.contains("fe80::1234"), sanitized);
        TextReverter reverter = new TextReverter(vault, new Counts());
        assertEquals(original, reverter.revert(sanitized));
    }

    @Test
    void revertDoesNotPartiallyResolveSyntheticCompoundTokens() {
        // F-17: A synthetic ::ffff:<v4token> or <invented-label>.<vault-fqdn> must not resolve interior sub-tokens
        TestVault localVault = new TestVault();
        TextSanitizer localSanitizer = new TextSanitizer(mappers, localVault, new Counts());
        String v4Token = localSanitizer.sanitize("192.0.2.10");
        String fqdnToken = localSanitizer.sanitize("www.example.com"); // h-xxx.h-yyy.com
        String domainLabelToken = fqdnToken.substring(fqdnToken.indexOf('.') + 1); // h-yyy.com

        TextReverter reverter = new TextReverter(localVault, new Counts());
        String syntheticV6 = "::ffff:" + v4Token;
        assertEquals(syntheticV6, reverter.revert(syntheticV6), "un-vaulted ::ffff:<v4token> must stay as-is");

        String syntheticFqdn = "h-0000000000." + domainLabelToken;
        assertEquals(syntheticFqdn, reverter.revert(syntheticFqdn), "compound FQDN with unknown label must stay as-is");
    }
}
