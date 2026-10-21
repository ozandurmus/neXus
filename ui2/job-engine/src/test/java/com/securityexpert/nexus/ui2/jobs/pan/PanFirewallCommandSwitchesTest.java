package com.securityexpert.nexus.ui2.jobs.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** PO 2026-10-21 scope update: one switch per switchable Palo Alto firewall read, default on. */
class PanFirewallCommandSwitchesTest {

    @Test
    void everyReadStaysOnUntilItsSwitchSaysRetired() {
        assertEquals(PanFirewallCommandSwitches.ALL_ENABLED, PanFirewallCommandSwitches.from(Map.of()));
        assertEquals(PanFirewallCommandSwitches.ALL_ENABLED, PanFirewallCommandSwitches.from(Map.of(
                PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE, "true",
                PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE, "anything-else")));
    }

    @Test
    void retiredFalseOrOffTurnsExactlyThatReadOff() {
        for (String off : new String[] {"retired", "RETIRED", " false ", "off"}) {
            var interfaceOff = PanFirewallCommandSwitches.from(Map.of(PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE, off));
            assertFalse(interfaceOff.showInterfaceAll(), off);
            assertTrue(interfaceOff.showSessionInfo(), off);
            var sessionOff = PanFirewallCommandSwitches.from(Map.of(PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE, off));
            assertTrue(sessionOff.showInterfaceAll(), off);
            assertFalse(sessionOff.showSessionInfo(), off);
        }
    }

    @Test
    void theSwitchNamesAndTheReasonAreStable() {
        assertEquals("NEXUS_PAN_FW_SHOW_INTERFACE_ALL", PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE);
        assertEquals("NEXUS_PAN_FW_SHOW_SESSION_INFO", PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE);
        assertEquals("Not collected (command retired by PO 2026-10-21)", PanFirewallCommandSwitches.REASON);
    }
}
