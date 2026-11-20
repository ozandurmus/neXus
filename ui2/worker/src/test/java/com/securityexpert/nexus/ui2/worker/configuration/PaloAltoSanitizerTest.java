package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaloAltoSanitizerTest {

    @Test
    void snmpSecretsAreRedactedButStructureStays() {
        String xml = "<snmp-setting><access-setting><version><v3><users><entry name=\"mon\"><authpwd>-AQ==SYNTHETICAUTH</authpwd>"
                + "<privpwd>-AQ==SYNTHETICPRIV</privpwd></entry></users></v3><v2c><snmp-community-string>SYNTHETICCOMMUNITY</snmp-community-string></v2c>"
                + "</version></access-setting></snmp-setting>";
        String out = ConfigurationCapabilityExecutor.sanitizePaloAltoXml(xml);
        assertFalse(out.contains("SYNTHETIC"));
        assertTrue(out.contains("<authpwd>[REDACTED]</authpwd>"));
        assertTrue(out.contains("<privpwd>[REDACTED]</privpwd>"));
        assertTrue(out.contains("<snmp-community-string>[REDACTED]</snmp-community-string>"));
    }
}
