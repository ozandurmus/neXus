package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class CpFailoverShellModeTest {
    private static final String PILOT = "CLS-TEST-01";

    @Test
    void offUnlessExactlyEnabled() {
        for (String value : new String[]{null, "", "true", "ENABLED", "on", "1", "disabled"})
            assertFalse(CpFailoverShellMode.fromValues(value, "*", null).usesShell(PILOT), String.valueOf(value));
        assertTrue(CpFailoverShellMode.fromValues(" enabled ", "*", null).usesShell(PILOT));
        assertFalse(CpFailoverShellMode.OFF.usesShell(PILOT));
    }

    @Test
    void enabledRunsOnlyListedClusters() {
        assertFalse(CpFailoverShellMode.fromValues("enabled", null, null).usesShell(PILOT), "unset list = none");
        assertFalse(CpFailoverShellMode.fromValues("enabled", " , ", null).usesShell(PILOT), "empty list = none");
        assertFalse(CpFailoverShellMode.fromValues("enabled", "CLS-TEST-02", null).usesShell(PILOT));
        assertFalse(CpFailoverShellMode.fromValues("enabled", "cls-test-01", null).usesShell(PILOT), "refs are opaque");
        assertTrue(CpFailoverShellMode.fromValues("enabled", "CLS-TEST-02, CLS-TEST-01", null).usesShell(PILOT));
        assertFalse(CpFailoverShellMode.fromValues("enabled", PILOT, null).usesShell(null));
        var all = CpFailoverShellMode.fromValues("enabled", "*", null);
        assertTrue(all.usesShell(PILOT) && all.usesShell("CLS-TEST-02"));
    }

    @Test
    void shellPauseDefaultsBoundsAndInvalidValues() {
        assertEquals(Duration.ofMillis(1000), CpFailoverShellMode.fromValues("enabled", "*", null).shellPause());
        assertEquals(Duration.ofMillis(250), CpFailoverShellMode.fromValues("enabled", "*", "250").shellPause());
        assertEquals(Duration.ZERO, CpFailoverShellMode.fromValues("enabled", "*", "0").shellPause());
        assertEquals(Duration.ofMillis(10_000), CpFailoverShellMode.fromValues("enabled", "*", "10000").shellPause());
        for (String invalid : new String[]{"-1", "abc", "1.5", "10001", "60000", "999999", " "})
            assertEquals(Duration.ofMillis(1000), CpFailoverShellMode.fromValues("enabled", "*", invalid).shellPause(), invalid);
    }

    @Test
    void logFormNeverNamesClusters() {
        String text = CpFailoverShellMode.fromValues("enabled", "CLS-TEST-02,CLS-TEST-01", null).toString();
        assertFalse(text.contains("CLS-TEST"), text);
        assertTrue(text.contains("listedClusters=2"), text);
    }
}
