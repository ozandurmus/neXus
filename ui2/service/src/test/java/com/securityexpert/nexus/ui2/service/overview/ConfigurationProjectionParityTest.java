package com.securityexpert.nexus.ui2.service.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Parity with ui2/frontend/tests/configurationProjection.test.ts: the same fixtures, the same expected DIFF
 * counts. If either implementation changes its rules, one of the two suites fails.
 */
class ConfigurationProjectionParityTest {

    static final String CP_A = """
            # SecurityExpert Check Point Gaia configuration evidence (redacted)
            # secret-bearing-lines-withheld=2
            set hostname FW-TANGO-01
            set domainname example.test
            set timezone Europe / Istanbul
            set dns primary 192.0.2.53
            set dns secondary 192.0.2.54
            # [SECURITYEXPERT SECRET-BEARING CONFIGURATION LINE WITHHELD]
            set ntp active on
            set ntp server primary 192.0.2.10 version 4
            set ntp server secondary 192.0.2.11 version 4
            set inactivity-timeout 720
            set management interface Mgmt
            set password-controls min-password-length 14
            set message banner on
            set syslog filename /var/log/messages
            set cluster member mvc off
            set interface eth1-01 state on
            set interface eth1-01 ipv4-address 192.0.2.1 mask-length 28
            set static-route default nexthop gateway address 192.0.2.254 on
            set snmp agent on
            set aaa tacacs-servers state off
            set user admin shell /bin/bash
            set lcd screensaver mode model
            """;

    static final String CP_B = CP_A.replace("FW-TANGO-01", "FW-TANGO-02").replace("192.0.2.1 mask-length 28", "192.0.2.2 mask-length 28")
            .replace("set ntp server secondary 192.0.2.11 version 4", "set ntp server secondary 192.0.2.99 version 4");

    @Test
    void checkPointSettingsAndMemberSpecificRulesMatchTheBrowser() {
        List<ConfigurationProjection.Row> rows = ConfigurationProjection.checkPoint(CP_A);
        assertEquals(21, rows.size(), "settingCount in configurationProjection.test.ts");
        assertTrue(rows.stream().anyMatch(r -> r.setting().equals("Interface eth1-01 · IPv4 Address") && r.memberSpecific()));
        assertTrue(rows.stream().anyMatch(r -> r.setting().equals("Hostname") && r.memberSpecific()));
        assertTrue(rows.stream().anyMatch(r -> r.setting().equals("NTP · Active") && r.value().equals("on")));
    }

    @Test
    void checkPointClusterCountsOneRealDifference() {
        Map<String, List<ConfigurationProjection.Row>> members = new LinkedHashMap<>();
        members.put("m1", ConfigurationProjection.checkPoint(CP_A));
        members.put("m2", ConfigurationProjection.checkPoint(CP_B));
        ConfigurationProjection.ClusterDiff diff = ConfigurationProjection.cluster(members);
        assertEquals(1, diff.diffCount());
        assertEquals(List.of("NTP"), diff.diffSections());
    }

    private static String panMember(String host, String ip, String ha1, String ntp) {
        return "<response status=\"success\"><result><config>"
                + "<devices><entry name=\"localhost.localdomain\">"
                + "<deviceconfig><system><hostname>" + host + "</hostname><ip-address>" + ip + "</ip-address>"
                + "<ntp-servers><primary-ntp-server><ntp-server-address>" + ntp + "</ntp-server-address></primary-ntp-server></ntp-servers></system>"
                + "<high-availability><enabled>yes</enabled><interface><ha1><ip-address>" + ha1 + "</ip-address><port>ha1-a</port></ha1></interface></high-availability>"
                + "</deviceconfig></entry></devices></config></result></response>";
    }

    @Test
    void paloAltoClusterExemptsHostnameManagementAndHaLinkAddresses() {
        Map<String, List<ConfigurationProjection.Row>> members = new LinkedHashMap<>();
        members.put("m1", ConfigurationProjection.paloAlto(panMember("FW-ZULU-05-M1", "192.0.2.10", "192.0.2.101", "192.0.2.53")));
        members.put("m2", ConfigurationProjection.paloAlto(panMember("FW-ZULU-05-M2", "192.0.2.11", "192.0.2.102", "192.0.2.54")));
        ConfigurationProjection.ClusterDiff diff = ConfigurationProjection.cluster(members);
        assertEquals(1, diff.diffCount());
        assertEquals(List.of("NTP"), diff.diffSections());
    }

    @Test
    void paloAltoRedactedLeavesAreNotRowsAndDoctypesAreRefused() {
        String xml = "<config><mgt-config><users><entry name=\"admin\"><phash>[REDACTED]</phash></entry></users></mgt-config></config>";
        assertTrue(ConfigurationProjection.paloAlto(xml).stream().noneMatch(r -> r.value().equals("[REDACTED]")));
        assertEquals(List.of(), ConfigurationProjection.paloAlto("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"));
    }
}
