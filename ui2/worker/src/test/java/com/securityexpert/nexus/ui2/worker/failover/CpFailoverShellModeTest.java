package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class CpFailoverShellModeTest {
    private static final Duration LEGACY = Duration.ofSeconds(2);

    @Test
    void onlyTheExactValueEnabledTurnsTheShellOn() {
        assertTrue(CpFailoverShellMode.fromValue("enabled").oneShell());
        assertTrue(CpFailoverShellMode.fromValue(" enabled ").oneShell());
        for (String value : new String[]{null, "", "true", "ENABLED", "on", "1", "disabled"})
            assertFalse(CpFailoverShellMode.fromValue(value).oneShell(), String.valueOf(value));
        assertFalse(CpFailoverShellMode.OFF.oneShell());
    }

    @Test
    void pauseDefaultsKeepTheExecPathUnchanged() {
        assertEquals(LEGACY, CpFailoverShellMode.OFF.commandPause(null, LEGACY));
        assertEquals(Duration.ofMillis(1000), new CpFailoverShellMode(true).commandPause(null, LEGACY));
    }

    @Test
    void explicitPauseAppliesToBothPathsAndInvalidValuesFallBack() {
        assertEquals(Duration.ofMillis(250), new CpFailoverShellMode(true).commandPause("250", LEGACY));
        assertEquals(Duration.ZERO, new CpFailoverShellMode(true).commandPause("0", LEGACY));
        assertEquals(Duration.ofMillis(1500), CpFailoverShellMode.OFF.commandPause("1500", LEGACY));
        for (String invalid : new String[]{"-1", "abc", "1.5", "60001", "999999", " "}) {
            assertEquals(Duration.ofMillis(1000), new CpFailoverShellMode(true).commandPause(invalid, LEGACY), invalid);
            assertEquals(LEGACY, CpFailoverShellMode.OFF.commandPause(invalid, LEGACY), invalid);
        }
    }
}
