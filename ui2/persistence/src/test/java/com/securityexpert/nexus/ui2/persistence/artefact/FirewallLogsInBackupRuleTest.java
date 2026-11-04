package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** PO 2026-11-04: Check Point firewall logs inside a backup. Synthetic paths and sizes only. */
class FirewallLogsInBackupRuleTest {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    @Test
    void thePatternMatchesTheFirewallLogDirectoryUnderAnyCpsuiteVersion() {
        assertTrue(FirewallLogsInBackupRule.isLogPath("opt/CPsuite-R81.20/fw1/log/2026-11-03_000000.log"));
        assertTrue(FirewallLogsInBackupRule.isLogPath("opt/CPsuite-R80.40/fw1/log/blob/x.logptr"));
        assertTrue(FirewallLogsInBackupRule.isLogPath("./opt/CPsuite-R81.20/fw1/log/fw.log"));
        assertTrue(FirewallLogsInBackupRule.isLogPath("/opt/CPsuite-R81.20/fw1/log/fw.log"));
    }

    @Test
    void otherPathsAreNotFirewallLogs() {
        assertFalse(FirewallLogsInBackupRule.isLogPath("opt/CPsuite-R81.20/fw1/conf/objects_5_0.C"));
        assertFalse(FirewallLogsInBackupRule.isLogPath("opt/CPsuite-R81.20/fw1/logs-archive/x.log"));
        assertFalse(FirewallLogsInBackupRule.isLogPath("opt/CPsuite-R81.20/fw1/log"), "the directory entry itself");
        assertFalse(FirewallLogsInBackupRule.isLogPath("var/log/messages"));
        assertFalse(FirewallLogsInBackupRule.isLogPath("home/admin/opt/CPsuite-R81.20/fw1/log2/x"));
        assertFalse(FirewallLogsInBackupRule.isLogPath("xopt/CPsuite-R81.20/fw1/log/x"), "anchored on a path segment");
        assertFalse(FirewallLogsInBackupRule.isLogPath(null));
    }

    @Test
    void theThresholdIsOneHundredMebibytesInclusive() {
        assertTrue(FirewallLogsInBackupRule.evaluate(100 * MIB, GIB).isPresent());
        assertFalse(FirewallLogsInBackupRule.evaluate(100 * MIB - 1, GIB).isPresent());
        assertFalse(FirewallLogsInBackupRule.evaluate(0, GIB).isPresent());
    }

    @Test
    void theSummaryNamesGibibytesAndPercentOnly() {
        var verdict = FirewallLogsInBackupRule.evaluate(270 * GIB, 605 * GIB).orElseThrow();
        assertEquals(44.6, verdict.percent(), 0.05);
        assertEquals("Backup contains 270.0 GiB of firewall logs (44.6% of the archive contents)", verdict.summary());
    }

    @Test
    void thePercentIsCappedAndAnEmptyTotalCannotDivideByZero() {
        assertEquals(100.0, FirewallLogsInBackupRule.evaluate(GIB, GIB).orElseThrow().percent(), 0.0);
        assertEquals(100.0, FirewallLogsInBackupRule.evaluate(GIB, 0).orElseThrow().percent(), 0.0);
    }
}
