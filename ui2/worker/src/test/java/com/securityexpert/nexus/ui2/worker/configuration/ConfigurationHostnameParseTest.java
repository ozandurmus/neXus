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
}
