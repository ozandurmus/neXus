package com.securityexpert.nexus.ui2.jobs.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.Decision;
import com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.Mode;

/** PO 2026-10-21 scope update: one switch per switchable Palo Alto firewall read, default always sent. */
class PanFirewallCommandSwitchesTest {

    @Test
    void everyReadStaysOnUntilItsSwitchSaysOtherwise() {
        assertEquals(PanFirewallCommandSwitches.ALL_ENABLED, PanFirewallCommandSwitches.from(Map.of()));
        assertEquals(PanFirewallCommandSwitches.ALL_ENABLED, PanFirewallCommandSwitches.from(Map.of(
                PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE, "true",
                PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE, "anything-else")));
    }

    @Test
    void retiredFalseOrOffTurnsExactlyThatReadOff() {
        for (String off : new String[] {"retired", "RETIRED", " false ", "off"}) {
            var interfaceOff = PanFirewallCommandSwitches.from(Map.of(PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE, off));
            assertEquals(Mode.NEVER, interfaceOff.showInterfaceAll(), off);
            assertEquals(Mode.ALWAYS, interfaceOff.showSessionInfo(), off);
            var sessionOff = PanFirewallCommandSwitches.from(Map.of(PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE, off));
            assertEquals(Mode.ALWAYS, sessionOff.showInterfaceAll(), off);
            assertEquals(Mode.NEVER, sessionOff.showSessionInfo(), off);
        }
    }

    @Test
    void below12AndPre12LimitExactlyThatReadToOlderPanOs() {
        for (String v : new String[] {"below_12", " BELOW_12 ", "pre12", "Pre12"}) {
            var s = PanFirewallCommandSwitches.from(Map.of(PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE, v));
            assertEquals(Mode.BELOW_12, s.showSessionInfo(), v);
            assertEquals(Mode.ALWAYS, s.showInterfaceAll(), v);
        }
        assertTrue(PanFirewallCommandSwitches.mayRead(Mode.BELOW_12));
        assertTrue(PanFirewallCommandSwitches.mayRead(Mode.ALWAYS));
        assertFalse(PanFirewallCommandSwitches.mayRead(Mode.NEVER));
    }

    @Test
    void decisionDependsOnTheVersionObservedInTheSameJobAndUnknownIsNeverSent() {
        assertEquals(Decision.SEND, PanFirewallCommandSwitches.decide(Mode.BELOW_12, "11.1.4-h7"));
        assertEquals(Decision.SEND, PanFirewallCommandSwitches.decide(Mode.BELOW_12, "10.2.9"));
        assertEquals(Decision.SKIP_PAN_OS_12, PanFirewallCommandSwitches.decide(Mode.BELOW_12, "12.1.2"));
        assertEquals(Decision.SKIP_PAN_OS_12, PanFirewallCommandSwitches.decide(Mode.BELOW_12, "13.0.0"));
        for (String unknown : new String[] {null, "", "  ", "abc", "x.1.2"}) {
            assertEquals(Decision.SKIP_VERSION_UNKNOWN, PanFirewallCommandSwitches.decide(Mode.BELOW_12, unknown), String.valueOf(unknown));
        }
        assertEquals(Decision.SEND, PanFirewallCommandSwitches.decide(Mode.ALWAYS, null));
        assertEquals(Decision.SKIP_RETIRED, PanFirewallCommandSwitches.decide(Mode.NEVER, "10.2.9"));
    }

    /** PO 2026-10-24: replace_12 sends the read below 12 and its replacement on 12 or later; unknown is neither. */
    @Test
    void replace12SendsBelowTwelveAndReplacesOnTwelveOrLater() {
        for (String v : new String[] {"replace_12", " REPLACE_12 ", "replace12"}) {
            var s = PanFirewallCommandSwitches.from(Map.of(PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE, v,
                    PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE, v));
            assertEquals(Mode.REPLACE_12, s.showInterfaceAll(), v);
            assertEquals(Mode.REPLACE_12, s.showSessionInfo(), v);
        }
        assertTrue(PanFirewallCommandSwitches.mayRead(Mode.REPLACE_12));
        assertEquals(Decision.SEND, PanFirewallCommandSwitches.decide(Mode.REPLACE_12, "11.1.4-h7"));
        assertEquals(Decision.SEND, PanFirewallCommandSwitches.decide(Mode.REPLACE_12, "10.2.9"));
        for (String twelve : new String[] {"12.1.2", "12.0.0", "13.0.0"}) {
            Decision d = PanFirewallCommandSwitches.decide(Mode.REPLACE_12, twelve);
            assertEquals(Decision.REPLACE, d, twelve);
            assertTrue(d.replace());
            assertFalse(d.send(), "the original read is not sent");
        }
        for (String unknown : new String[] {null, "", "abc"}) {
            Decision d = PanFirewallCommandSwitches.decide(Mode.REPLACE_12, unknown);
            assertEquals(Decision.SKIP_VERSION_UNKNOWN, d, String.valueOf(unknown));
            assertFalse(d.replace());
        }
        // below_12 is unchanged: 12 or later is still not collected, never replaced.
        assertEquals(Decision.SKIP_PAN_OS_12, PanFirewallCommandSwitches.decide(Mode.BELOW_12, "12.1.2"));
        assertEquals("pan_os_12_or_later", Decision.REPLACE.token());
        assertEquals("replace_12", PanFirewallCommandSwitches.REPLACE_12);
    }

    @Test
    void reasonsAreStable() {
        assertEquals("Not collected: PAN-OS 12 or later (PO 2026-10-21)", Decision.SKIP_PAN_OS_12.reason());
        assertEquals("Not collected: PAN-OS version unknown", Decision.SKIP_VERSION_UNKNOWN.reason());
        assertEquals(PanFirewallCommandSwitches.REASON, Decision.SKIP_RETIRED.reason());
        assertNull(Decision.SEND.reason());
        assertEquals("pan_os_12_or_later", Decision.SKIP_PAN_OS_12.token());
        assertEquals("pan_os_version_unknown", Decision.SKIP_VERSION_UNKNOWN.token());
        assertNull(Decision.SKIP_RETIRED.token());
    }

    @Test
    void theSwitchNamesAndTheReasonAreStable() {
        assertEquals("NEXUS_PAN_FW_SHOW_INTERFACE_ALL", PanFirewallCommandSwitches.SHOW_INTERFACE_ALL_VARIABLE);
        assertEquals("NEXUS_PAN_FW_SHOW_SESSION_INFO", PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE);
        assertEquals("Not collected (command retired by PO 2026-10-21)", PanFirewallCommandSwitches.REASON);
    }
}
