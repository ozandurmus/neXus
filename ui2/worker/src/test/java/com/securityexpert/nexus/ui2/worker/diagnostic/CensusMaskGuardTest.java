package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The output guard and the survival check. Documentation addresses only. */
class CensusMaskGuardTest {

    @Test
    void aSurvivingAddressOrNameIsALeakAndAReplacedOneIsNot() {
        assertEquals("ipv4", CensusMaskGuard.leak("drop src 192.0.2.17 dst x", "drop src 192.0.2.17 dst x"));
        assertNull(CensusMaskGuard.leak("drop src 192.0.2.17 dst x", "drop src 198.51.100.40 dst x"));
        assertNull(CensusMaskGuard.leak("drop src 192.0.2.17", "drop src 192.0.2.170")); // a longer, different address
        assertEquals("ipv6", CensusMaskGuard.leak("route 2001:db8:1::5 up", "route 2001:db8:1::5 up"));
        assertNull(CensusMaskGuard.leak("route 2001:db8:1::5 up", "route 2001:db8:77::9 up"));
        assertEquals("mac", CensusMaskGuard.leak("learned 00:50:56:ab:cd:ef", "learned 00:50:56:ab:cd:ef"));
        assertNull(CensusMaskGuard.leak("learned 00:50:56:ab:cd:ef", "learned 00:50:56:11:22:33"));
        assertEquals("email", CensusMaskGuard.leak("mail alice@mail.example.com", "mail alice@mail.example.com"));
        assertEquals("fqdn", CensusMaskGuard.leak("resolved db01.corp.example.com", "resolved db01.corp.example.com"));
        assertNull(CensusMaskGuard.leak("resolved db01.corp.example.com", "resolved h-0123456789.h-abcdef0123.example.com"));
    }

    @Test
    void rangesThePermutationKeepsAreNotLeaks() {
        assertNull(CensusMaskGuard.leak("bind 127.0.0.1 mask 255.255.255.0 any 0.0.0.0", "bind 127.0.0.1 mask 255.255.255.0 any 0.0.0.0"));
        assertNull(CensusMaskGuard.leak("join 224.0.0.18 and ff02::5 on ::1", "join 224.0.0.18 and ff02::5 on ::1"));
    }

    @Test
    void timesVersionsAndFileNamesAreNotIdentityShaped() {
        String line = "at 13:31:44 kernel 3.10.0.1160 loaded libfoo.so and run.sh ratio 1:2:3";
        assertNull(CensusMaskGuard.leak(line, line));
        assertTrue(CensusMaskGuard.findings(line).isEmpty(), CensusMaskGuard.findings(line).toString());
    }

    @Test
    void theFinishedReportMustCarryNoIdentityShapedText() {
        CensusMaskGuard.assertClean("{\"template\":\"sshd: Accepted publickey for <*> from <*> port <*>\",\"first_day\":\"2026-11-24\"}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CensusMaskGuard.assertClean("{\"template\":\"peer 192.0.2.17 down\"}"));
        assertFalse(e.getMessage().contains("192.0.2.17"), "the failure must not carry the value");
        assertThrows(IllegalStateException.class, () -> CensusMaskGuard.assertClean("{\"t\":\"host db01.corp.example.com\"}"));
        assertThrows(IllegalStateException.class, () -> CensusMaskGuard.assertClean("{\"t\":\"nbr 2001:db8::1\"}"));
        assertThrows(IllegalStateException.class, () -> CensusMaskGuard.assertClean("{\"t\":\"mac 00:50:56:ab:cd:ef\"}"));
        assertThrows(IllegalStateException.class, () -> CensusMaskGuard.assertClean("{\"t\":\"a@b.example.com\"}"));
    }
}
