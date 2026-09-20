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
                      <hostname>MigroFw-02</hostname>
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
}
