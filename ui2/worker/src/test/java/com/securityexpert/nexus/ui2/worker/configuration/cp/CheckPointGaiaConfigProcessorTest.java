package com.securityexpert.nexus.ui2.worker.configuration.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/** AC-1: canonical hash over set lines only, CG-3 secret withholding, section index. */
class CheckPointGaiaConfigProcessorTest {

    // Shaped like CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md: a "#" header whose
    // timestamp changes between reads, then a mix of secret-bearing and non-secret set lines.
    private static final String FIXTURE_HEADER_1 = "#\n# 2026-09-14 10:00:00\n#\n";
    private static final String FIXTURE_HEADER_2 = "#\n# 2026-09-14 10:05:00\n#\n";
    private static final String FIXTURE_BODY = String.join("\n",
            "set interface eth0 state on",
            "set interface eth0 ipv4-address 198.51.100.5 mask-length 24",
            "set ssh server ciphers aes256-ctr",
            "set snmp traps community my-secret-community enable",
            "set user admin password-hash $6$abc",
            "set aaa radius-servers priority 1 server-name radius1 shared-secret topsecret",
            "set ntp server primary ip-address 198.51.100.10",
            "") + "\n";

    @Test
    void canonicalHashIgnoresTheHeaderTimestampButChangesOnBodyDifference() {
        var first = CheckPointGaiaConfigProcessor.process(FIXTURE_HEADER_1 + FIXTURE_BODY);
        var second = CheckPointGaiaConfigProcessor.process(FIXTURE_HEADER_2 + FIXTURE_BODY);
        assertEquals(first.canonicalHash(), second.canonicalHash(),
                "same set lines, different header timestamp -- canonical hash must match");

        var changedBody = CheckPointGaiaConfigProcessor.process(FIXTURE_HEADER_1 + FIXTURE_BODY + "set web port 4433\n");
        assertFalse(first.canonicalHash().equals(changedBody.canonicalHash()),
                "an added set line must change the canonical hash");
    }

    @Test
    void withholdsEverySecretBearingLineAndCountsThem() {
        var processed = CheckPointGaiaConfigProcessor.process(FIXTURE_HEADER_1 + FIXTURE_BODY);

        // 3 secret-bearing lines: snmp traps community, user password-hash, aaa radius shared-secret.
        assertEquals(3, processed.withheldLineCount());
        assertFalse(processed.sanitizedText().contains("my-secret-community"));
        assertFalse(processed.sanitizedText().contains("$6$abc"));
        assertFalse(processed.sanitizedText().contains("topsecret"));
        assertTrue(processed.sanitizedText().contains("set interface eth0 state on"));
    }

    @Test
    void sectionIndexGroupsByFirstOneOrTwoTokensAfterSet() {
        var processed = CheckPointGaiaConfigProcessor.process(FIXTURE_HEADER_1 + FIXTURE_BODY);
        var bySection = processed.index().stream()
                .collect(java.util.stream.Collectors.toMap(ConfigurationIndexEntry::section, e -> e.entryCount()));

        assertEquals(2, bySection.get("interface"));
        assertEquals(1, bySection.get("ssh server"));
        assertEquals(1, bySection.get("snmp traps"));
        assertEquals(1, bySection.get("user"));
        assertEquals(1, bySection.get("aaa radius-servers"));
        assertEquals(1, bySection.get("ntp server"));
        // Structural counts only -- every context is physical (14G CG-1: host-level, no per-VS repetition).
        assertTrue(processed.index().stream().allMatch(e -> "physical".equals(e.context())));
    }
}
