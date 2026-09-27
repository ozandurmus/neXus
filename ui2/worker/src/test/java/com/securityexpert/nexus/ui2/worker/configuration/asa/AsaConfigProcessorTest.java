package com.securityexpert.nexus.ui2.worker.configuration.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
            snmp-server host inside 192.0.2.50 community host-community-value version 2c
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
    void indexesOnlyAllowedBlocksAndWithholdsTheirSecrets() {
        var p = AsaConfigProcessor.process(CONFIG);
        assertEquals("single", p.index().getFirst().context());
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Interfaces") && e.entryCount() == 2));
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Routing") && e.entryCount() == 1));
        assertEquals(4, p.withheldLineCount());
        for (String value : new String[] {"enable-value", "login-value", "user-value", "snmp-value", "psk-value",
                "psk2-value", "auth-value", "key-value", "other-value", "host-community-value", "deadbeef", "0123456789abcdef", "INVENTED"}) {
            assertFalse(p.sanitizedText().contains(value), value);
        }
        assertTrue(p.sanitizedText().contains("nameif outside"));
        for (String dropped : List.of("object network", "access-list", "tunnel-group", "crypto", "passwd", "quit")) {
            assertFalse(p.sanitizedText().contains(dropped), dropped);
        }
        assertEquals(p.canonicalHash(), AsaConfigProcessor.process(CONFIG.replace("12:00", "13:00")
                .replace("abcdef\nASA Version", "123456\nASA Version")).canonicalHash());
    }

    @Test
    void coversEveryAllowedFamilyAndDropsWholePolicyBlocks() {
        String[] allowed = {"ASA Version 9.22", "hostname FW-TANGO-04", "domain-name example.test", "clock timezone UTC 0",
                "boot system disk0:/image", "firewall transparent", "mode single", "console timeout 0",
                "dns domain-lookup outside", "name-server 192.0.2.53", "ntp server 192.0.2.54",
                "ssh 192.0.2.0 255.255.255.0 outside", "http server enable", "telnet timeout 5",
                "management-access inside", "banner motd invented", "password-policy minimum-length 8",
                "username invented password synthetic", "enable password synthetic", "aaa authentication ssh console LOCAL",
                "aaa-server invented protocol radius", "ssl server-version tlsv1.2", "snmp-server community synthetic",
                "logging enable", "failover", "monitor-interface inside", "prompt hostname",
                "interface GigabitEthernet0/0", "mtu outside 1500", "route outside 0.0.0.0 0.0.0.0 192.0.2.254",
                "ipv6 route outside ::/0 2001:db8::1", "router ospf 1", "no http server enable",
                "no logging timestamp", "no failover lan interface"};
        String[] dropped = {"object network INVENTED", "object-group network INVENTED", "access-list INVENTED permit ip any any",
                "nat (inside,outside) source static INVENTED INVENTED", "crypto ca certificate chain INVENTED",
                "tunnel-group INVENTED type remote-access", "webvpn"};
        StringBuilder raw = new StringBuilder();
        for (String line : allowed) raw.append(line).append("\n child [withheld]\n");
        for (String line : dropped) raw.append(line).append("\n secret [withheld]\n");
        var p = AsaConfigProcessor.process(raw.toString());
        assertEquals(List.of("System", "DNS", "NTP", "Management access", "SNMP", "Logging", "Failover",
                "Interfaces", "Routing"), p.index().stream().map(e -> e.section()).toList());
        assertEquals(allowed.length * 2, p.sanitizedText().lines().count());
        assertEquals(allowed.length + 4, p.withheldLineCount());
        for (String line : dropped) assertFalse(p.sanitizedText().contains(line), line);
        assertTrue(p.sanitizedText().contains("no http server enable"));
    }

    @org.junit.jupiter.api.Test
    void snmpV3UserKeysAreWithheld() {
        String text = "hostname invented\nsnmp-server user INVENTED grp v3 engineID 80000009fe encrypted auth sha "
                + "7c:b0:03 priv aes 128 8d:26:50\nsnmp-server enable traps\n: end\n";
        String out = AsaConfigProcessor.process(text).sanitizedText();
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("7c:b0:03"));
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("8d:26:50"));
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("snmp-server [withheld]"));
    }
}
