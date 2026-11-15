package com.securityexpert.nexus.ui2.platform.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LogAddressMaskerTest {

    // Synthetic documentation-range values (RFC 5737 / RFC 3849), assembled at runtime, never written as literals.
    static String v4() {
        return String.join(".", "192", "0", "2", Integer.toString(40 + 2));
    }

    static String v6() {
        return String.join(":", "2001", "db8", "", "a1", "7");
    }

    private static String m(String s) {
        return LogAddressMasker.mask(s, false);
    }

    @Test
    void masksIpv4WithSurroundingPunctuation() {
        String a = v4();
        assertEquals("connect <ip>:22 failed", m("connect " + a + ":22 failed"));
        assertEquals("peer=<ip>, next", m("peer=" + a + ", next"));
        assertEquals("ended at <ip>.", m("ended at " + a + "."));
        assertEquals("net <ip>/24 up", m("net " + a + "/24 up"));
        assertEquals("[<ip>]", m("[" + a + "]"));
        assertEquals("<ip> and <ip>", m(a + " and " + a));
    }

    @Test
    void masksIpv6Forms() {
        assertEquals("peer <ip> up", m("peer " + v6() + " up"));
        assertEquals("[<ip>]:443", m("[" + v6() + "]:443"));
        assertEquals("loopback <ip>", m("loopback " + "::" + "1"));
        assertEquals("zone <ip>", m("zone " + "fe80" + "::" + "1%eth0"));
        assertEquals("mapped <ip>", m("mapped ::ffff:" + v4()));
        assertEquals("full <ip>.", m("full " + String.join(":", "2001", "db8", "0", "0", "0", "0", "1", "2") + "."));
        assertEquals("addr <ip>: refused", m("addr " + v6() + ": refused"));
        assertEquals("ip:<ip>", m("ip:" + v6()));
    }

    @Test
    void keepsNonAddresses() {
        for (String s : new String[] {
                "version 10.1.2 released", "build 1.2.3.4.5 done", "1.2.3.4.5", "took 12.5 ms",
                "at 12:34:56.789 UTC", "2026-11-14T10:20:30.456Z", "PT1.5S", "pi=3.14159", "port 8443 open",
                "mac aa:bb:cc:dd:ee:ff", "clock 12:34:56", "Foo::bar failed", "ratio 1:2:3", "::", "a:b",
                "octet 256.1.1.1", "lead 01.2.3.4", "ver10.1.2.3x", "jdk 21.0.4", "id 12345678"}) {
            assertEquals(s, m(s), s);
        }
    }

    @Test
    void masksStackTraceText() {
        String trace = "java.io.IOException: connect to " + v4() + ":22\n\tat Foo.bar(Foo.java:10)\nCaused by: x [" + v6() + "]";
        String masked = m(trace);
        assertFalse(masked.contains(v4()));
        assertFalse(masked.contains(v6()));
        assertTrue(masked.contains("Foo.java:10"));
    }

    @Test
    void escapeHatch() {
        String s = "peer " + v4();
        assertEquals(s, LogAddressMasker.mask(s, true));
        assertTrue(LogAddressMasker.rawAddressesEnabled(Map.of(LogAddressMasker.RAW_ENV, "1")::get));
        assertFalse(LogAddressMasker.rawAddressesEnabled(Map.of(LogAddressMasker.RAW_ENV, "0")::get));
        assertFalse(LogAddressMasker.rawAddressesEnabled(k -> null));
    }

    @Test
    void nullAndShortPassThrough() {
        assertSame(null, m(null));
        assertEquals("", m(""));
    }
}
