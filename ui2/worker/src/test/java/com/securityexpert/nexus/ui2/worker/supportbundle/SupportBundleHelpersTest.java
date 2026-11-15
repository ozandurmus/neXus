package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.TarStream;

class SupportBundleHelpersTest {

    private final SupportBundleTextRedactor redactor = new SupportBundleTextRedactor();

    private String redact(String text) {
        return new String(redactor.redact(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void secretsAreReplacedAndOrdinaryLinesKept() {
        String out = redact("hostname FW-TANGO-04\nset snmp community synthetic-community\npassword synthetic-pass-9\n"
                + "-----BEGIN RSA PRIVATE KEY-----\nMIIBsynthetic\n-----END RSA PRIVATE KEY-----\ndone\n");
        assertTrue(out.contains("hostname FW-TANGO-04"));
        assertTrue(out.contains("done"));
        assertFalse(out.contains("synthetic-community") || out.contains("synthetic-pass-9") || out.contains("MIIBsynthetic"), out);
    }

    @Test
    void anOverlongLineIsReplacedWhole() {
        String out = redact("ok\n" + "x".repeat(SupportBundleTextRedactor.MAX_LINE_BYTES + 1) + "\nafter\n");
        assertTrue(out.contains(SupportBundleTextRedactor.OVERLONG));
        assertFalse(out.contains("xxxxxxxx"));
        assertTrue(out.endsWith("after\n"));
    }

    @Test
    void redactionIsIdempotent() {
        String once = redact("snmp community synthetic-community\nplain\n");
        assertEquals(once, redact(once));
    }

    @Test
    void tarRoundTripWithALongName() throws Exception {
        String longName = "nexus-support-bundle-d8f19c2a/items/cp_hcp_latest/" + "a".repeat(60) + ".tar.gz";
        var out = new ByteArrayOutputStream();
        var writer = new TarStream.Writer(out);
        writer.entry("manifest.json", "{}".getBytes(StandardCharsets.UTF_8), 1);
        byte[] payload = new byte[1300];
        java.util.Arrays.fill(payload, (byte) 7);
        writer.entry(longName, payload, 1);
        writer.finish();
        var entries = TarStream.readAll(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        assertEquals(List.of("manifest.json", longName), entries.stream().map(TarStream.Entry::name).toList());
        assertArrayEquals(payload, entries.get(1).bytes());
        assertEquals(0, out.size() % 512);
    }

    @Test
    void tarRefusesAnArchiveOverTheBoundAndAShortEntry() throws Exception {
        var out = new ByteArrayOutputStream();
        var writer = new TarStream.Writer(out);
        writer.entry("a", new byte[2000], 1);
        writer.finish();
        assertThrows(java.io.IOException.class, () -> TarStream.readAll(new ByteArrayInputStream(out.toByteArray()), 1000));
        var w2 = new TarStream.Writer(new ByteArrayOutputStream());
        w2.beginEntry("b", 10, 1);
        w2.write(new byte[3], 0, 3);
        assertThrows(java.io.IOException.class, w2::endEntry);
    }

    @Test
    void hcpEntryNameKeepsOnlyTheTimestamp() {
        assertEquals("hcp_report_15_11_26_03_00.tar.gz", CpSupportBundleJobExecutor.safeName("hcp_report_fwtango04_15_11_26_03_00.tar.gz"));
        assertEquals("hcp_report.tar.gz", CpSupportBundleJobExecutor.safeName("hcp_last_report.tar.gz"));
    }

    @Test
    void cpuTableParsingIsTolerantAndNeverGuesses() {
        assertEquals(60, CpSupportBundleJobExecutor.maxCorePercent("|CPU#|Idle Time(%)|Usage(%)|\n|1|90|10|\n|2|40|60|\n"));
        assertNull(CpSupportBundleJobExecutor.maxCorePercent("some other shape\n"));
        assertNull(CpSupportBundleJobExecutor.maxCorePercent(""));
    }

    @Test
    void gzipMagicIsReadFromTheFirstTwoBytesAcrossWrites() throws Exception {
        var sink = new ByteArrayOutputStream();
        var magic = new CpSupportBundleJobExecutor.GzipMagic(sink);
        magic.write(new byte[] {0x1f}, 0, 1);
        magic.write(new byte[] {(byte) 0x8b, 1, 2}, 0, 3);
        assertTrue(magic.ok());
        assertEquals(4, sink.size());
        var bad = new CpSupportBundleJobExecutor.GzipMagic(new ByteArrayOutputStream());
        bad.write("<html>".getBytes(StandardCharsets.UTF_8), 0, 6);
        assertFalse(bad.ok());
    }
}
