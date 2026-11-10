package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ConfigurationHostnameParseTest {

    @Test
    void oneTokenIsTheHostnameAnythingElseIsNot() {
        assertEquals(Optional.of("FW-TANGO-04"), ConfigurationCapabilityExecutor.parseHostname("FW-TANGO-04\n"));
        assertEquals(Optional.empty(), ConfigurationCapabilityExecutor.parseHostname("CLINFR0329  Invalid command:'show hostname'."));
        assertEquals(Optional.empty(), ConfigurationCapabilityExecutor.parseHostname(""));
    }

    /** 2026-11-11: a Gaia CLI message line (CLINFR code) before or after the name must not hide it; nothing else changes. */
    @Test
    void cliMessageLinesAreSkippedButNeverAnArbitraryToken() {
        assertEquals(Optional.of("synthetic-host-01"),
                ConfigurationCapabilityExecutor.parseHostname("CLINFR0123  Synthetic warning text.\nsynthetic-host-01\n"));
        assertEquals(Optional.of("synthetic-host-01"),
                ConfigurationCapabilityExecutor.parseHostname("\nsynthetic-host-01\nCLINFR0123  trailing note\n"));
        assertEquals(Optional.empty(), ConfigurationCapabilityExecutor.parseHostname("CLINFR0123  only a warning\n"));
        // a free-text first line is still no hostname, and a later token is not picked from behind it
        assertEquals(Optional.empty(), ConfigurationCapabilityExecutor.parseHostname("two words here\nsynthetic-host-01\n"));
        assertEquals(Optional.empty(), ConfigurationCapabilityExecutor.parseHostname("Warning: something\nsynthetic-host-01\n"));
    }
}
