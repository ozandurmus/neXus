package com.securityexpert.nexus.ui2.worker.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.compliance.evaluator.CheckPointGaiaComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.model.DisplayStatus;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigParser;

class CheckPointGaiaComplianceEvaluatorTest {

    private final CheckPointGaiaConfigParser parser = new CheckPointGaiaConfigParser();

    @Test
    void testComplianceEvaluationWithMissingDataPreservation() {
        String sampleConfig = String.join("\n",
                "set hostname FW-JULIET-06",
                "set password-controls min-password-length 14",
                "set password-controls complexity on",
                "set password-controls history-check on",
                "set password-controls lockout-threshold 3",
                "set password-controls lockout-duration 30",
                "set clienv inactivity-timeout 10",
                "set sshd protocol 2",
                "set web ssl-port 443",
                "set ntp server primary 10.0.0.1",
                "set ntp server secondary 10.0.0.2",
                "set syslog server 10.10.10.10",
                "set message banner on msgvalue Legal Notice",
                "set core-dump total 1000",
                "set format date-format YYYY-MM-DD",
                "set arp table cache-size 2048",
                "set ip-conflicts-monitor state off",
                "set clienv debug 0",
                "set installer policy check-for-updates-period 7"
        );

        ConfigParseContext ctx = new ConfigParseContext(
                "c154432c-1e28-4a20-aa26-ab4a05c0d9af",
                ConfigVendor.CHECK_POINT,
                ConfigFormat.GAIA_CLISH,
                "physical",
                Optional.empty()
        );

        ConfigParseResult parseResult = parser.parse(ctx,
                new ByteArrayInputStream(sampleConfig.getBytes(StandardCharsets.UTF_8)));

        EvaluationResult eval = CheckPointGaiaComplianceEvaluator.evaluate(
                "c154432c-1e28-4a20-aa26-ab4a05c0d9af",
                "check_point",
                "gaia",
                parseResult.sections(),
                sampleConfig,
                null
        );

        assertNotNull(eval);
        assertEquals(24, eval.totalAssigned());
        // Exactly 4 controls must be marked DATA_UNAVAILABLE
        assertEquals(4, eval.dataUnavailableCount());

        // Verify that the 4 uncollected controls are DATA_UNAVAILABLE
        var unavailItems = eval.items().stream()
                .filter(i -> i.displayStatus() == DisplayStatus.DATA_UNAVAILABLE)
                .toList();
        assertEquals(4, unavailItems.size());
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("ssh_strong_ciphers")));
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("snmp_v3_only")));
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("unused_interfaces_disabled")));
        assertTrue(unavailItems.stream().anyMatch(i -> i.controlId().contains("mgmt_trusted_subnets")));

        // Verify Tri-Metric formulas:
        // Assured Compliance: PASS / TotalAssigned * 100
        // Evidence Coverage: (PASS + FAIL) / TotalAssigned * 100
        // Observed Compliance: PASS / (PASS + FAIL) * 100
        assertEquals(Math.round(((eval.passCount() + eval.failCount()) * 100.0 / 24.0) * 10.0) / 10.0, eval.evidenceCoverage());
        assertEquals(Math.round((eval.passCount() * 100.0 / 24.0) * 10.0) / 10.0, eval.assuredCompliance());
    }

    private EvaluationResult evaluateSanitized(String raw) {
        ConfigParseContext ctx = new ConfigParseContext("c154432c-1e28-4a20-aa26-ab4a05c0d9af",
                ConfigVendor.CHECK_POINT, ConfigFormat.GAIA_CLISH, "physical", Optional.empty());
        // The stored text is the sanitized text: evaluate exactly what the collector would have stored.
        String sanitized = parser.parse(ctx, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))).sanitizedText();
        ConfigParseResult res = parser.parse(ctx, new ByteArrayInputStream(sanitized.getBytes(StandardCharsets.UTF_8)));
        return CheckPointGaiaComplianceEvaluator.evaluate("c154432c-1e28-4a20-aa26-ab4a05c0d9af", "check_point", "gaia",
                res.sections(), sanitized, null);
    }

    private static com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationItem item(EvaluationResult r, String id) {
        return r.items().stream().filter(i -> i.controlId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void mgmtClientsPassWhenOnlyNamedClientsAndNeverEchoAddresses() {
        EvaluationResult r = evaluateSanitized(String.join("\n",
                "set hostname FW-TANGO-04",
                "add allowed-client host ipv4-address 192.0.2.10",
                "add allowed-client network ipv4-address 192.0.2.64 mask-length 26"));
        var i = item(r, "gaia_cis_2_5_1_mgmt_trusted_subnets");
        assertEquals(DisplayStatus.PASS, i.displayStatus());
        assertTrue(!String.valueOf(i.observedValue()).contains("192.0.2"));
        assertTrue(!i.message().contains("192.0.2"));
    }

    @Test
    void mgmtClientsFailWhenAnyHostOrDefaultRoute() {
        assertEquals(DisplayStatus.FAIL, item(evaluateSanitized(
                "add allowed-client host any-host\nadd allowed-client host ipv4-address 192.0.2.10"),
                "gaia_cis_2_5_1_mgmt_trusted_subnets").displayStatus());
        assertEquals(DisplayStatus.FAIL, item(evaluateSanitized(
                "add allowed-client network ipv4-address 0.0.0.0 mask-length 0"),
                "gaia_cis_2_5_1_mgmt_trusted_subnets").displayStatus());
    }

    @Test
    void mgmtClientsStayUnknownWhenNoEntryIsCollected() {
        var i = item(evaluateSanitized("set hostname FW-TANGO-04"), "gaia_cis_2_5_1_mgmt_trusted_subnets");
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, i.displayStatus());
    }

    @Test
    void snmpV3PassNeverKeepsPassPhrasesInStoredText() {
        String raw = String.join("\n",
                "set snmp agent-version v3-Only",
                "add snmp usm user FW-TANGO-04-mon security-level authPriv auth-pass-phrase-hashed 0123abcdSECRETHASH"
                        + " privacy-pass-phrase-hashed 4567efabSECRETHASH2 authentication-protocol SHA1 privacy-protocol AES");
        ConfigParseContext ctx = new ConfigParseContext("d", ConfigVendor.CHECK_POINT, ConfigFormat.GAIA_CLISH, "physical", Optional.empty());
        String sanitized = parser.parse(ctx, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))).sanitizedText();
        assertTrue(!sanitized.contains("SECRETHASH"));
        assertTrue(sanitized.contains("add snmp usm user FW-TANGO-04-mon security-level authPriv"));
        var i = item(evaluateSanitized(raw), "gaia_cis_2_6_1_snmp_v3_only");
        assertEquals(DisplayStatus.PASS, i.displayStatus());
    }

    @Test
    void snmpV3FailsOnOlderAgentOrWeakUserAndStaysUnknownWithoutEvidence() {
        assertEquals(DisplayStatus.FAIL, item(evaluateSanitized("set snmp agent-version any"),
                "gaia_cis_2_6_1_snmp_v3_only").displayStatus());
        assertEquals(DisplayStatus.FAIL, item(evaluateSanitized(
                "set snmp agent-version v3-Only\nadd snmp usm user u1 security-level authNoPriv auth-pass-phrase-hashed AAAA"),
                "gaia_cis_2_6_1_snmp_v3_only").displayStatus());
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, item(evaluateSanitized("set hostname FW-TANGO-04"),
                "gaia_cis_2_6_1_snmp_v3_only").displayStatus());
        assertEquals(DisplayStatus.DATA_UNAVAILABLE, item(evaluateSanitized("set snmp agent-version v3-Only"),
                "gaia_cis_2_6_1_snmp_v3_only").displayStatus());
        assertEquals(DisplayStatus.PASS, item(evaluateSanitized("set snmp agent off"),
                "gaia_cis_2_6_1_snmp_v3_only").displayStatus());
    }

    @Test
    void otherAddLinesAreStillDropped() {
        ConfigParseContext ctx = new ConfigParseContext("d", ConfigVendor.CHECK_POINT, ConfigFormat.GAIA_CLISH, "physical", Optional.empty());
        String sanitized = parser.parse(ctx, new ByteArrayInputStream(
                "add user admin2 uid 0\nadd allowed-client host any-host\nset hostname FW-TANGO-04".getBytes(StandardCharsets.UTF_8))).sanitizedText();
        assertTrue(!sanitized.contains("add user"));
        assertTrue(sanitized.contains("add allowed-client host any-host"));
    }
}
