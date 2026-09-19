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
}
