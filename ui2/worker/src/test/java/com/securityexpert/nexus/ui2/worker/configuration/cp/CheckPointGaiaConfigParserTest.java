package com.securityexpert.nexus.ui2.worker.configuration.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigHighlight;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigSection;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;

/**
 * 1:1 characterization test suite for {@link CheckPointGaiaConfigParser},
 * validating parity with Python Check Point configuration collector.
 */
class CheckPointGaiaConfigParserTest {

    private final CheckPointGaiaConfigParser parser = new CheckPointGaiaConfigParser();
    private final ConfigParseContext context = new ConfigParseContext(
            "dev-1",
            ConfigVendor.CHECK_POINT,
            ConfigFormat.GAIA_CLISH,
            "physical",
            Optional.empty()
    );

    @Test
    void emptyConfigReturnsEmptyCanonicalHashAndZeroCounts() {
        var result = parser.parse(context, new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        assertTrue(result.canonicalHash().isEmpty());
        assertEquals(0, result.withheldLineCount());
        assertEquals(0, result.totalSettingsCount());
        assertTrue(result.index().isEmpty());
        assertTrue(result.sections().isEmpty());
        assertTrue(result.highlights().isEmpty());
        assertTrue(result.sanitizedText().contains("# raw-canonical-sha256=unavailable"));
        assertTrue(result.sanitizedText().contains("# secret-bearing-lines-withheld=0"));
    }

    @Test
    void canonicalHashIgnoresHeaderTimestampsAndControlFlow() {
        String raw1 = "#\n# 2026-09-14 10:00:00\n#\n"
                + "set virtual-system 1\n"
                + "set hostname FW-ROMEO-07\n"
                + "set timezone Europe/Istanbul\n";

        String raw2 = "#\n# 2026-09-14 10:05:00\n#\n"
                + "set hostname FW-ROMEO-07\n"
                + "set timezone Europe/Istanbul\n";

        var res1 = parser.parse(context, new ByteArrayInputStream(raw1.getBytes(StandardCharsets.UTF_8)));
        var res2 = parser.parse(context, new ByteArrayInputStream(raw2.getBytes(StandardCharsets.UTF_8)));

        assertTrue(res1.canonicalHash().isPresent());
        assertEquals(res1.canonicalHash(), res2.canonicalHash());
    }

    @Test
    void passwordPolicySafeKnobsArePreservedAndNotWithheld() {
        String raw = String.join("\n",
                "set password-controls min-password-length 12",
                "set password-controls complexity on",
                "set password-controls palindrome-check on",
                "set password-controls history-check on",
                "set password-controls password-expiration 90",
                "set user admin password-hash $6$xyz123",
                "set snmp traps community secret-trap enable"
        );

        var res = parser.parse(context, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));

        // Only user password-hash and snmp traps community are withheld (2 lines withheld)
        assertEquals(2, res.withheldLineCount());
        assertFalse(res.sanitizedText().contains("$6$xyz123"));
        assertFalse(res.sanitizedText().contains("secret-trap"));

        // Safe knobs are preserved in sanitized text
        assertTrue(res.sanitizedText().contains("set password-controls min-password-length 12"));
        assertTrue(res.sanitizedText().contains("set password-controls complexity on"));
        assertTrue(res.sanitizedText().contains("set password-controls palindrome-check on"));

        // Password policy section exists
        Map<String, ConfigSection> sections = res.sections().stream()
                .collect(Collectors.toMap(ConfigSection::id, s -> s));
        assertTrue(sections.containsKey("password_policy"));
        assertEquals(5, sections.get("password_policy").count());
    }

    @Test
    void bannerMotdBodyIsRedactedPreservingPresence() {
        String raw = "set message motd on msgvalue Welcome to Corporate Gateway Authorized Personnel Only\n"
                + "set message banner off msgvalue Do not login\n";

        var res = parser.parse(context, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, res.withheldLineCount()); // Banner masking is redaction, not line withholding

        assertTrue(res.sanitizedText().contains("set message motd on msgvalue [SECURITYEXPERT BANNER BODY WITHHELD]"));
        assertTrue(res.sanitizedText().contains("set message banner off msgvalue [SECURITYEXPERT BANNER BODY WITHHELD]"));
        assertFalse(res.sanitizedText().contains("Welcome to Corporate Gateway"));

        Map<String, ConfigSection> sections = res.sections().stream()
                .collect(Collectors.toMap(ConfigSection::id, s -> s));
        assertTrue(sections.containsKey("banner"));
    }

    @Test
    void governedSectionsAndHighlightsAreExtractedCorrectly() {
        String raw = String.join("\n",
                "set hostname FW-TEST-01",
                "set domainname example.corp",
                "set timezone UTC",
                "set dns primary 192.0.2.53",
                "set dns secondary 192.0.2.54",
                "set ntp server primary 192.0.2.123",
                "set interface eth0 ipv4-address 198.51.100.1 mask-length 24",
                "set static-route 10.0.0.0/8 nexthop gateway address 198.51.100.254 on",
                "set syslog server 192.0.2.514",
                "set snmp location Datacenter-A",
                "set snmptrap host 192.0.2.162"
        );

        var res = parser.parse(context, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, res.withheldLineCount());

        Map<String, ConfigSection> sections = res.sections().stream()
                .collect(Collectors.toMap(ConfigSection::id, s -> s));

        assertTrue(sections.containsKey("system"));
        assertTrue(sections.containsKey("dns"));
        assertTrue(sections.containsKey("ntp"));
        assertTrue(sections.containsKey("interfaces"));
        assertTrue(sections.containsKey("routing"));
        assertTrue(sections.containsKey("snmp"));
        assertTrue(sections.containsKey("logging"));

        // Verify highlights
        Map<String, String> highlights = res.highlights().stream()
                .collect(Collectors.toMap(ConfigHighlight::label, ConfigHighlight::value));

        assertEquals("FW-TEST-01", highlights.get("Hostname"));
        assertEquals("example.corp", highlights.get("Domain"));
        assertEquals("UTC", highlights.get("Timezone"));
        assertEquals("192.0.2.53", highlights.get("Primary DNS"));
        assertEquals("192.0.2.54", highlights.get("Secondary DNS"));
        assertEquals("192.0.2.123", highlights.get("Primary NTP Server"));
    }
}
