package com.securityexpert.nexus.ui2.worker.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.compliance.evaluator.PaloAltoPanOsComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.model.DisplayStatus;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationResult;

class PaloAltoPanOsComplianceEvaluatorTest {

    private static final String DEVICE_ID = "e724dea5-10fa-469a-acd9-bd5362c84e8a";

    @Test
    void testEvaluationWithCompliantPanOsConfig() {
        String compliantXml = """
            <config version="11.1.0" urldb="paloaltonetworks">
              <mgt-config>
                <users>
                  <entry name="admin">
                    <phash>$1$example$hash</phash>
                    <permissions><role-based><superuser>yes</superuser></role-based></permissions>
                  </entry>
                </users>
                <password-complexity>
                  <enabled>yes</enabled>
                  <minimum-length>14</minimum-length>
                  <password-history>24</password-history>
                  <expiration-period>90</expiration-period>
                </password-complexity>
              </mgt-config>
              <shared>
                <log-settings>
                  <syslog>
                    <entry name="SIEM-Syslog">
                      <server>10.10.10.50</server>
                    </entry>
                  </syslog>
                  <system>
                    <entry name="system-forwarding">
                      <send-to-panorama>yes</send-to-panorama>
                    </entry>
                  </system>
                  <config>
                    <entry name="config-forwarding">
                      <send-to-panorama>yes</send-to-panorama>
                    </entry>
                  </config>
                </log-settings>
              </shared>
              <devices>
                <entry name="localhost.localdomain">
                  <deviceconfig>
                    <system>
                      <hostname>FW-TANGO-04</hostname>
                      <login-banner>Authorized Access Only. All activities are logged and monitored.</login-banner>
                      <timezone>Europe/Istanbul</timezone>
                      <ntp-servers>
                        <primary-ntp-server>
                          <ntp-server-address>10.0.0.1</ntp-server-address>
                        </primary-ntp-server>
                      </ntp-servers>
                      <dns-setting>
                        <servers>
                          <primary>10.0.0.10</primary>
                          <secondary>10.0.0.11</secondary>
                        </servers>
                      </dns-setting>
                      <service>
                        <disable-telnet>yes</disable-telnet>
                        <disable-http>yes</disable-http>
                      </service>
                    </system>
                    <setting>
                      <management>
                        <idle-timeout>15</idle-timeout>
                        <failed-attempts>3</failed-attempts>
                        <lockout-time>30</lockout-time>
                        <tls-version-min>1.2</tls-version-min>
                        <permitted-ip>
                          <entry name="10.230.0.0/16"/>
                        </permitted-ip>
                      </management>
                    </setting>
                    <high-availability>
                      <encryption>
                        <enabled>yes</enabled>
                      </encryption>
                    </high-availability>
                  </deviceconfig>
                </entry>
              </devices>
            </config>
            """;

        EvaluationResult eval = PaloAltoPanOsComplianceEvaluator.evaluate(
                DEVICE_ID,
                "palo_alto",
                "pan_os",
                compliantXml,
                null
        );

        assertNotNull(eval);
        assertEquals(DEVICE_ID, eval.deviceId());
        assertEquals("palo_alto", eval.vendor());
        assertEquals(24, eval.totalAssigned());
        // The three structure controls now read the active XML; this fixture has no SNMP, interface or DDNS evidence.
        assertEquals(4, eval.dataUnavailableCount());

        // 20 evaluable controls should all pass with this compliant XML
        assertEquals(20, eval.passCount());
        assertEquals(0, eval.failCount());

        // Check the 4 uncollected controls
        var unavailItems = eval.items().stream()
                .filter(i -> i.displayStatus() == DisplayStatus.DATA_UNAVAILABLE)
                .toList();
        assertEquals(4, unavailItems.size());
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("snmpv3_only")));
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("unused_interfaces_down")));
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("ddns_disabled")));
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("ssh_strong_ciphers")));

        // Verify Tri-Metric formulas:
        // Assured Compliance: PASS / TotalAssigned * 100 = 20 / 24 * 100 = 83.3%
        // Evidence Coverage: (PASS + FAIL) / TotalAssigned * 100 = 20 / 24 * 100 = 83.3%
        // Observed Compliance: PASS / (PASS + FAIL) * 100 = 20 / 20 * 100 = 100.0%
        assertEquals(83.3, eval.assuredCompliance());
        assertEquals(83.3, eval.evidenceCoverage());
        assertEquals(100.0, eval.observedCompliance());

        // Verify sensitive display value masking
        var bannerItem = eval.items().stream()
                .filter(i -> i.controlId().equals("pan_cis_1_2_1_login_banner"))
                .findFirst().orElseThrow();
        assertEquals("configured (legal notice present)", bannerItem.observedValue());

        var permittedIpItem = eval.items().stream()
                .filter(i -> i.controlId().equals("pan_cis_1_3_5_permitted_ip_addresses"))
                .findFirst().orElseThrow();
        assertEquals("configured (bastion access restricted)", permittedIpItem.observedValue());
    }

    @Test
    void testEvaluationWithNonCompliantSettings() {
        String nonCompliantXml = """
            <config version="11.1.0">
              <devices>
                <entry name="localhost.localdomain">
                  <deviceconfig>
                    <system>
                      <service>
                        <disable-telnet>no</disable-telnet>
                        <disable-http>no</disable-http>
                      </service>
                    </system>
                    <setting>
                      <management>
                        <idle-timeout>60</idle-timeout>
                        <failed-attempts>10</failed-attempts>
                        <lockout-time>5</lockout-time>
                      </management>
                    </setting>
                  </deviceconfig>
                </entry>
              </devices>
            </config>
            """;

        EvaluationResult eval = PaloAltoPanOsComplianceEvaluator.evaluate(
                DEVICE_ID,
                "palo_alto",
                "pan_os",
                nonCompliantXml,
                null
        );

        assertNotNull(eval);
        assertEquals(24, eval.totalAssigned());
        assertEquals(4, eval.dataUnavailableCount());

        // Telnet and HTTP are enabled (disable-telnet=no, disable-http=no), timeout=60, attempts=10, lockout=5
        // These should fail
        assertTrue(eval.failCount() > 0);

        var telnetItem = eval.items().stream()
                .filter(i -> i.controlId().equals("pan_cis_1_3_1_telnet_disabled"))
                .findFirst().orElseThrow();
        assertEquals(DisplayStatus.FAIL, telnetItem.displayStatus());

        var timeoutItem = eval.items().stream()
                .filter(i -> i.controlId().equals("pan_cis_1_2_2_idle_timeout"))
                .findFirst().orElseThrow();
        assertEquals(DisplayStatus.FAIL, timeoutItem.displayStatus());
    }

    @Test
    void testEvaluationWithNullOrEmptyConfigReturnsAllDataUnavailable() {
        EvaluationResult eval = PaloAltoPanOsComplianceEvaluator.evaluate(
                DEVICE_ID,
                "palo_alto",
                "pan_os",
                "",
                null
        );

        assertNotNull(eval);
        assertEquals(24, eval.totalAssigned());
        assertEquals(24, eval.dataUnavailableCount());
        assertEquals(0, eval.passCount());
        assertEquals(0, eval.failCount());
        assertEquals(0.0, eval.assuredCompliance());
        assertEquals(0.0, eval.evidenceCoverage());
        assertEquals(0.0, eval.observedCompliance());
    }

    @Test
    void testEvaluationWithAssignedControlsFilter() {
        String compliantXml = """
            <config version="11.1.0">
              <devices>
                <entry name="localhost.localdomain">
                  <deviceconfig>
                    <system>
                      <login-banner>Authorized Access Only.</login-banner>
                    </system>
                  </deviceconfig>
                </entry>
              </devices>
            </config>
            """;

        EvaluationResult eval = PaloAltoPanOsComplianceEvaluator.evaluate(
                DEVICE_ID,
                "palo_alto",
                "pan_os",
                compliantXml,
                Set.of("pan_cis_1_2_1_login_banner")
        );

        assertNotNull(eval);
        assertEquals(1, eval.totalAssigned());
        assertEquals(1, eval.passCount());
        assertEquals(100.0, eval.assuredCompliance());
        assertEquals(100.0, eval.observedCompliance());
    }

    private static String panXml(String deviceconfig, String network) {
        return "<config version=\"11.1.0\"><devices><entry name=\"localhost.localdomain\"><deviceconfig><system><hostname>FW-TANGO-04</hostname>"
                + deviceconfig + "</system></deviceconfig>" + network + "</entry></devices></config>";
    }

    private static com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationItem item(String xml, String id) {
        return PaloAltoPanOsComplianceEvaluator.evaluate(DEVICE_ID, "palo_alto", "pan_os", xml, Set.of(id)).items().get(0);
    }

    private static final String SNMP_ID = "pan_cis_4_1_1_snmpv3_only";
    private static final String IFACE_ID = "pan_cis_4_1_2_unused_interfaces_down";
    private static final String DDNS_ID = "pan_cis_4_1_3_ddns_disabled";

    @Test
    void snmpV3PassesFailsAndStaysUnknown() {
        String v3 = "<snmp-setting><access-setting><version><v3><users><entry name=\"mon\"><authpwd>[REDACTED]</authpwd>"
                + "<privpwd>[REDACTED]</privpwd></entry></users></v3></version></access-setting></snmp-setting>";
        assertEquals(DisplayStatus.PASS, item(panXml(v3, ""), SNMP_ID).displayStatus());
        String v2c = "<snmp-setting><access-setting><version><v2c><snmp-community-string>[REDACTED]</snmp-community-string></v2c></version></access-setting></snmp-setting>";
        assertEquals(DisplayStatus.FAIL, item(panXml(v2c, ""), SNMP_ID).displayStatus());
        String weak = "<snmp-setting><access-setting><version><v3><users><entry name=\"mon\"><authpwd>x</authpwd></entry></users></v3></version></access-setting></snmp-setting>";
        assertEquals(DisplayStatus.FAIL, item(panXml(weak, ""), SNMP_ID).displayStatus());
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, item(panXml("", ""), SNMP_ID).displayStatus());
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, item("<config><unclosed>", SNMP_ID).displayStatus());
    }

    @Test
    void unusedInterfacesNeedARolelessEntryAndLinkStateDown() {
        String down = "<network><interface><ethernet><entry name=\"ethernet1/1\"><layer3><units><entry name=\"ethernet1/1.10\"/></units></layer3></entry>"
                + "<entry name=\"ethernet1/9\"><link-state>down</link-state></entry></ethernet></interface></network>";
        assertEquals(DisplayStatus.PASS, item(panXml("", down), IFACE_ID).displayStatus());
        String up = down.replace("<link-state>down</link-state>", "<comment>spare</comment>");
        assertEquals(DisplayStatus.FAIL, item(panXml("", up), IFACE_ID).displayStatus());
        String none = "<network><interface><ethernet><entry name=\"ethernet1/1\"><layer3/></entry></ethernet></interface></network>";
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, item(panXml("", none), IFACE_ID).displayStatus());
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, item(panXml("", ""), IFACE_ID).displayStatus());
    }

    @Test
    void ddnsFailsWhenEnabledPassesWhenInterfacesShowNone() {
        String on = "<network><interface><ethernet><entry name=\"ethernet1/1\"><layer3><ddns-config><ddns-enabled>yes</ddns-enabled></ddns-config></layer3></entry></ethernet></interface></network>";
        assertEquals(DisplayStatus.FAIL, item(panXml("", on), DDNS_ID).displayStatus());
        String off = on.replace("yes", "no");
        assertEquals(DisplayStatus.PASS, item(panXml("", off), DDNS_ID).displayStatus());
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, item(panXml("", ""), DDNS_ID).displayStatus());
    }
}
