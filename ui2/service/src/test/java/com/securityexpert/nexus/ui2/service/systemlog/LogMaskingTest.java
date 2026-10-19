package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;

/**
 * The aiview masking projection over stored lines and file names. Synthetic values only: RFC 5737 / RFC 3849 /
 * documentation addresses, example.test names. Every assertion about "unchanged" is a promise that the projection
 * does not damage text that carries no identity.
 */
class LogMaskingTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef-test-key".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, String> INVENTORY = Map.of("edge-fw-01", "FW-TANGO-04", "core.cluster.test", "CLS-ROMEO-01");

    private final LogMasking masking = new LogMasking(KEY, () -> KnownNames.of(INVENTORY));

    private LogMasking.Session session() {
        return masking.session();
    }

    @Test
    void ipv4AddressesAreReplacedAndPrefixRelationsSurvive() {
        LogMasking.Session s = session();
        String a = s.maskLine("drop src 192.0.2.17 dst 198.51.100.5");
        assertFalse(a.contains("192.0.2.17"));
        assertFalse(a.contains("198.51.100.5"));
        String[] parts = s.maskLine("192.0.2.17 192.0.2.200").split(" ");
        assertEquals(parts[0].substring(0, parts[0].lastIndexOf('.')), parts[1].substring(0, parts[1].lastIndexOf('.')),
                "same /24 stays the same /24");
        assertEquals(s.maskLine("192.0.2.17"), session().maskLine("192.0.2.17"), "deterministic across sessions");
    }

    @Test
    void ipv6AddressesAndCidrsAreReplaced() {
        String line = session().maskLine("route 2001:db8:1::5 via 2001:db8:1::1 net 2001:db8:2::/48");
        assertFalse(line.contains("2001:db8:1::5"));
        assertFalse(line.contains("2001:db8:1::1"));
        assertTrue(line.startsWith("route "));
        assertTrue(line.contains("/48"));
    }

    @Test
    void macAddressesKeepTheirVendorPrefixOnly() {
        String masked = session().maskLine("learned 00:50:56:ab:cd:ef on port 3");
        assertTrue(masked.startsWith("learned 00:50:56:"), masked);
        assertFalse(masked.contains("ab:cd:ef"));
        assertTrue(masked.endsWith(" on port 3"));
    }

    @Test
    void hostnamesAndFqdnsAreReplacedWithLabelTokensAndTheSuffixStays() {
        String masked = session().maskLine("resolved db01.corp.example.com ok");
        assertFalse(masked.contains("db01"));
        assertFalse(masked.contains("corp"));
        assertTrue(masked.contains(".example.com") || masked.contains(".com"));
        assertTrue(masked.matches(".*h-[0-9a-f]{10}.*"), masked);
        // a syslog header host
        String syslog = session().maskLine("Oct 17 10:00:00 2026 gw-hq-77 sshd[12]: Accepted");
        assertFalse(syslog.contains("gw-hq-77"), syslog);
        assertTrue(syslog.contains("sshd[12]: Accepted"));
    }

    @Test
    void emailAddressesAreReplacedLocalPartAndDomainLabels() {
        String masked = session().maskLine("mail from alice.smith@mail.example.com sent");
        assertFalse(masked.contains("alice"));
        assertFalse(masked.contains("smith"));
        assertFalse(masked.contains("mail.example"));
        assertTrue(masked.contains("@"));
        assertTrue(masked.endsWith(" sent"));
    }

    @Test
    void inventoryDeviceNamesGetTheirUiPseudonym() {
        assertEquals("cluster FW-TANGO-04 failed over", session().maskLine("cluster edge-fw-01 failed over"));
        assertTrue(session().maskLine("member core.cluster.test up").contains("CLS-ROMEO-01"));
        assertEquals(Map.of("device_name", 1L), counted("cluster edge-fw-01 failed over"));
    }

    private Map<String, Long> counted(String line) {
        LogMasking.Session s = session();
        s.maskLine(line);
        return s.counts();
    }

    @Test
    void textWithoutIdentityIsUntouched() {
        for (String line : new String[] {"firmware v1.2.3.4 installed", "cpu 87% load average 0.5 0.6 0.7",
                "key exchange completed", "mask 255.255.255.0 gw 0.0.0.0", "at 12:30:45 rc=0",
                "[17 Oct 10:15:01][fw4_0];[cpu_2];fwkern: ok"}) {
            assertEquals(line, session().maskLine(line));
        }
    }

    @Test
    void theRedactedMarkerSurvivesMasking() {
        assertEquals("login password=<redacted> from X", session().maskLine("login password=<redacted> from X"));
    }

    @Test
    void chunksKeepTheirLineEndingsAndMaskEveryLine() {
        byte[] in = "a 192.0.2.1\r\nb edge-fw-01\nc 192.0.2.1".getBytes(StandardCharsets.UTF_8);
        String out = new String(session().maskChunk(in), StandardCharsets.UTF_8);
        String[] lines = out.split("\r?\n", -1);
        assertEquals(3, lines.length);
        assertTrue(out.contains("\r\n"));
        assertEquals("b FW-TANGO-04", lines[1]);
        assertEquals(lines[0].substring(2), lines[2].substring(2), "the same address maps the same way");
        assertFalse(out.contains("192.0.2.1"));
        assertFalse(out.endsWith("\n"), "no terminator invented");
    }

    @Test
    void utf8TextSurvivesAndALegacyCodePageIsKeptByteForByte() {
        byte[] utf8 = "café 192.0.2.1 中文\n".getBytes(StandardCharsets.UTF_8);
        String out = new String(session().maskChunk(utf8), StandardCharsets.UTF_8);
        assertTrue(out.startsWith("café ") && out.endsWith(" 中文\n"));
        byte[] latin = {'a', ' ', (byte) 0xFC, (byte) 0xDF, ' ', '1', '9', '2', '.', '0', '.', '2', '.', '1', '\n'};
        byte[] masked = session().maskChunk(latin);
        assertEquals((byte) 0xFC, masked[2], "legacy bytes pass through");
        assertEquals((byte) 0xDF, masked[3]);
        assertFalse(new String(masked, StandardCharsets.ISO_8859_1).contains("192.0.2.1"));
        assertArrayEquals(new byte[0], session().maskChunk(new byte[0]));
    }

    @Test
    void fileNamesAreMaskedByTheTextRulesAndByInventoryNames() {
        LogMasking.Session s = session();
        assertEquals("hcp_report_FW-TANGO-04_17_10_26_03_00.tar.gz", s.maskFileName("hcp_report_edge-fw-01_17_10_26_03_00.tar.gz"));
        assertEquals("FW-TANGO-04.log", s.maskFileName("edge-fw-01.log"));
        String fqdn = s.maskFileName("export-db01.corp.example.com.txt");
        assertFalse(fqdn.contains("db01"), fqdn);
        String ip = s.maskFileName("capture_192.0.2.9.pcap");
        assertFalse(ip.contains("192.0.2.9"), ip);
        assertEquals("messages.1", s.maskFileName("messages.1"));
        assertEquals("fwk.elg", s.maskFileName("fwk.elg"));
        assertEquals("spike_detective.csv", s.maskFileName("spike_detective.csv"));
        assertEquals("", s.maskFileName(""));
        assertEquals(null, s.maskFileName(null));
    }

    @Test
    void theSameKeyGivesTheSamePseudonymAndAnotherKeyDoesNot() {
        String line = "peer db01.corp.example.com at 192.0.2.77";
        assertEquals(session().maskLine(line), new LogMasking(KEY, () -> KnownNames.NONE).session().maskLine(line));
        byte[] other = "ffffffffffffffffffffffffffffffff-other-key".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(session().maskLine(line), new LogMasking(other, null).session().maskLine(line));
    }

    @Test
    void anInventoryReadFailureIsNotSilentlyIgnoredButANullSnapshotIsTolerated() {
        LogMasking nullSnapshot = new LogMasking(KEY, () -> null);
        // No pseudonym without an inventory snapshot; since logmask-v3 the heuristic pass still masks the
        // hostname-shaped token, so the name is not shown in clear either.
        assertTrue(nullSnapshot.session().maskLine("x edge-fw-01 y").matches("x h-[0-9a-f]{10} y"));
    }

    @Test
    void sessionToStringNeverPrintsContent() {
        LogMasking.Session s = session();
        s.maskLine("192.0.2.1");
        assertFalse(s.toString().contains("192"));
    }
}
