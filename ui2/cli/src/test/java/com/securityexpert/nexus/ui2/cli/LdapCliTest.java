package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** CLI parity for Administration › LDAP: the password is merged in at send time, never read from the file. */
class LdapCliTest {
    @Test
    void thePromptedPasswordReplacesAnyPasswordInTheFile() {
        String merged = NexusSessionCli.withBindPassword(
                "{ \"servers\": [\"dc1.example.local\"], \"bindPassword\": \"from-file\", \"port\": 636 }", "pw\"x");
        assertTrue(merged.startsWith("{\"bindPassword\":\"pw\\\"x\","), merged);
        assertFalse(merged.contains("from-file"), merged);
        assertTrue(merged.contains("\"port\": 636"), merged);
    }

    @Test
    void aBlankPasswordIsSentAsBlankSoTheServerKeepsTheStoredOne() {
        assertEquals("{\"bindPassword\":\"\"}", NexusSessionCli.withBindPassword("{}", ""));
        assertThrows(IllegalArgumentException.class, () -> NexusSessionCli.withBindPassword("[1]", "x"));
    }
}
