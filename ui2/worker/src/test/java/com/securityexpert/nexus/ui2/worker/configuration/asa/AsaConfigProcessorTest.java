package com.securityexpert.nexus.ui2.worker.configuration.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AsaConfigProcessorTest {

    private static final String CONFIG = """
            : Saved at 12:00
            : Written by test
            : Serial Number: invented
            : Hardware: invented
            Cryptochecksum: abcdef
            ASA Version 9.22
            interface GigabitEthernet0/0
             nameif outside
             ip address 192.0.2.1 255.255.255.0
            object network WEB
             host 192.0.2.10
            access-list OUTSIDE permit ip any any
            route outside 0.0.0.0 0.0.0.0 192.0.2.254
            enable password enable-value
            passwd login-value
            username invented password user-value
            snmp-server community snmp-value
            tunnel-group VPN ipsec-attributes
             ikev1 pre-shared-key psk-value
             ikev2 remote-authentication pre-shared-key psk2-value
             authentication key auth-value
             key-string key-value
             secret other-value
            crypto ca certificate chain INVENTED
             certificate deadbeef
              0123456789abcdef
            quit
            """;

    @Test
    void indexesBlocksAndWithholdsEverySecretIncludingCertificateBody() {
        var p = AsaConfigProcessor.process(CONFIG);
        assertEquals("single", p.index().getFirst().context());
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Interface") && e.entryCount() == 2));
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Object Network") && e.entryCount() == 1));
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Access List") && e.entryCount() == 1));
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Route") && e.entryCount() == 1));
        assertEquals(12, p.withheldLineCount());
        for (String value : new String[] {"enable-value", "login-value", "user-value", "snmp-value", "psk-value",
                "psk2-value", "auth-value", "key-value", "other-value", "deadbeef", "0123456789abcdef", "INVENTED"}) {
            assertFalse(p.sanitizedText().contains(value), value);
        }
        assertTrue(p.sanitizedText().contains("nameif outside"));
        assertTrue(p.sanitizedText().contains("crypto ca certificate chain [withheld]"));
        assertEquals(p.canonicalHash(), AsaConfigProcessor.process(CONFIG.replace("12:00", "13:00")
                .replace("abcdef\nASA Version", "123456\nASA Version")).canonicalHash());
    }
}
