package com.securityexpert.nexus.ui2.service.packetanalysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PacketCaptureStoreTest {
    @TempDir Path root;
    private final String caseId = UUID.randomUUID().toString();

    static byte[] pcap(int payloadBytes) {
        byte[] bytes = new byte[24 + payloadBytes];
        bytes[0] = (byte) 0xd4; bytes[1] = (byte) 0xc3; bytes[2] = (byte) 0xb2; bytes[3] = (byte) 0xa1;
        for (int i = 24; i < bytes.length; i++) bytes[i] = (byte) i;
        return bytes;
    }

    static byte[] pcapng(boolean validByteOrder) {
        byte[] bytes = new byte[64];
        bytes[0] = 0x0a; bytes[1] = 0x0d; bytes[2] = 0x0d; bytes[3] = 0x0a;
        if (validByteOrder) { bytes[8] = 0x4d; bytes[9] = 0x3c; bytes[10] = 0x2b; bytes[11] = 0x1a; }
        return bytes;
    }

    @Test void acceptsOnlyPcapAndPcapngByTheirOwnMagicNumbers() {
        assertEquals("pcap", PacketCaptureStore.formatOf(pcap(0)));
        byte[] big = pcap(0); big[0] = (byte) 0xa1; big[1] = (byte) 0xb2; big[2] = (byte) 0xc3; big[3] = (byte) 0xd4;
        assertEquals("pcap", PacketCaptureStore.formatOf(big));
        byte[] nano = pcap(0); nano[0] = (byte) 0x4d; nano[1] = (byte) 0x3c; nano[2] = (byte) 0xb2; nano[3] = (byte) 0xa1;
        assertEquals("pcap", PacketCaptureStore.formatOf(nano));
        assertEquals("pcapng", PacketCaptureStore.formatOf(pcapng(true)));
        assertNull(PacketCaptureStore.formatOf(pcapng(false)), "a pcapng magic without the byte-order magic is refused");
        assertNull(PacketCaptureStore.formatOf("synthetic,csv,text,that,is,long,enough".getBytes()));
        assertNull(PacketCaptureStore.formatOf(new byte[] {(byte) 0xd4, (byte) 0xc3, (byte) 0xb2, (byte) 0xa1}),
                "a header shorter than the 24-byte pcap global header is refused");
    }

    @Test void streamsToTheCaseDirectoryWithItsDigestAndNoPartialFile() throws Exception {
        var store = new PacketCaptureStore(root, 1 << 20, 10 << 20);
        byte[] data = pcap(5000);
        var stored = store.write(caseId, new ByteArrayInputStream(data), data.length + 300);
        assertEquals("pcap", stored.format());
        assertEquals(data.length, stored.sizeBytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)), stored.sha256());
        assertEquals(stored.captureId() + ".pcap", stored.storageKey());
        Path file = root.resolve("cases").resolve(caseId).resolve("captures").resolve(stored.storageKey());
        assertArrayEquals(data, Files.readAllBytes(file));
        assertEquals(data.length, store.usedBytes());
        assertNoPartialFiles();
    }

    @Test void refusesACaptureOverThePerFileLimitWhileStreamingWithoutBufferingIt() throws Exception {
        long limit = 1 << 20;
        var store = new PacketCaptureStore(root, limit, 100L << 20);
        // An endless capture: the store must stop at the limit, so the whole body is never held anywhere.
        InputStream endless = new InputStream() {
            long served;
            @Override public int read() { return served++ < 24 ? pcap(0)[(int) served - 1] & 0xff : 7; }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                if (served < 24) return super.read(b, off, len);
                java.util.Arrays.fill(b, off, off + len, (byte) 7);
                served += len;
                return len;
            }
        };
        var refusal = assertThrows(PacketCaptureStore.Refusal.class, () -> store.write(caseId, endless, -1));
        assertEquals(PacketCaptureStore.TOO_LARGE, refusal.code());
        assertNoPartialFiles();
        assertEquals(0, store.usedBytes());
        assertEquals(PacketCaptureStore.TOO_LARGE, assertThrows(PacketCaptureStore.Refusal.class,
                () -> store.write(caseId, new ByteArrayInputStream(pcap(10)), limit + (2 << 20))).code(),
                "a declared length far over the limit is refused before any byte is read");
    }

    @Test void refusesAnUploadThatWouldPassTheStoreCap() throws Exception {
        var store = new PacketCaptureStore(root, 4096, 6000);
        store.write(caseId, new ByteArrayInputStream(pcap(3000)), 3024);
        var refusal = assertThrows(PacketCaptureStore.Refusal.class,
                () -> store.write(caseId, new ByteArrayInputStream(pcap(3000)), 3024));
        assertEquals(PacketCaptureStore.STORE_FULL, refusal.code());
        assertNoPartialFiles();
    }

    @Test void refusesNonCaptureContentWhateverItsNameAndKeepsNothing() throws Exception {
        var store = new PacketCaptureStore(root, 1 << 20, 10 << 20);
        var refusal = assertThrows(PacketCaptureStore.Refusal.class, () -> store.write(caseId,
                new ByteArrayInputStream("PK\u0003\u0004synthetic-archive-renamed-to.pcap-content".getBytes()), 60));
        assertEquals(PacketCaptureStore.NOT_A_CAPTURE, refusal.code());
        assertEquals(0, store.usedBytes());
    }

    @Test void deletingACaseRemovesItsWholeDirectoryAndPathsComeFromIdsOnly() throws Exception {
        var store = new PacketCaptureStore(root, 1 << 20, 10 << 20);
        store.write(caseId, new ByteArrayInputStream(pcapng(true)), 64);
        store.deleteCase(caseId);
        assertFalse(Files.exists(root.resolve("cases").resolve(caseId)));
        store.deleteCase(caseId); // idempotent
        assertThrows(IllegalArgumentException.class, () -> store.deleteCase("../outside"));
        assertThrows(IllegalArgumentException.class, () -> store.open(caseId, "../../etc.pcap"));
    }

    private void assertNoPartialFiles() throws Exception {
        if (!Files.exists(root.resolve("cases"))) return;
        try (Stream<Path> files = Files.walk(root.resolve("cases"))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".partial-")));
        }
    }

    // ---- host telemetry -------------------------------------------------------------------------------------

    private static final String VMSTAT = "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----\n"
            + " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st\n"
            + " 2  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n";

    @Test void streamsATelemetryFileIntoTheCaseTelemetryDirectoryAndSniffsItsContentNotItsName() throws Exception {
        var store = new PacketCaptureStore(root, 1 << 20, 10 << 20, 1 << 16);
        byte[] data = VMSTAT.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var stored = store.writeTelemetry(caseId, new ByteArrayInputStream(data), data.length + 200);
        assertEquals("vmstat", stored.detectedFormat());
        assertNull(stored.recognitionNote());
        assertEquals(stored.telemetryId() + ".txt", stored.storageKey());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)), stored.sha256());
        Path file = root.resolve("cases").resolve(caseId).resolve("telemetry").resolve(stored.storageKey());
        assertArrayEquals(data, Files.readAllBytes(file));
        assertEquals(data.length, store.usedBytes(), "telemetry counts against the same store cap");
        try (InputStream in = store.openTelemetry(caseId, stored.storageKey())) {
            assertArrayEquals(data, in.readAllBytes());
        }
        var other = store.writeTelemetry(caseId, new ByteArrayInputStream("a,b\n1,2\n".getBytes()), -1);
        assertEquals("unknown", other.detectedFormat());
        assertEquals("NO_KNOWN_FORMAT", other.recognitionNote());
        store.deleteTelemetry(caseId, stored.storageKey());
        assertFalse(Files.exists(file));
        assertNoPartialFiles();
    }

    @Test void refusesBinaryEmptyOversizedAndStoreBreakingTelemetryAndLeavesNothingBehind() throws Exception {
        var store = new PacketCaptureStore(root, 1 << 20, 10_000, 4096);
        assertEquals(PacketCaptureStore.NOT_A_TEXT_FILE, assertThrows(PacketCaptureStore.Refusal.class,
                () -> store.writeTelemetry(caseId, new ByteArrayInputStream(pcap(100)), -1)).code());
        assertEquals(PacketCaptureStore.NOT_A_TEXT_FILE, assertThrows(PacketCaptureStore.Refusal.class,
                () -> store.writeTelemetry(caseId, new ByteArrayInputStream(new byte[0]), -1)).code());
        assertEquals(PacketCaptureStore.TOO_LARGE, assertThrows(PacketCaptureStore.Refusal.class,
                () -> store.writeTelemetry(caseId, new ByteArrayInputStream("x".repeat(5000).getBytes()), -1)).code());
        assertEquals(PacketCaptureStore.TOO_LARGE, assertThrows(PacketCaptureStore.Refusal.class,
                () -> store.writeTelemetry(caseId, new ByteArrayInputStream(new byte[10]), 4096L + (2L << 20))).code(),
                "a declared length far over the limit is refused before reading");
        store.writeTelemetry(caseId, new ByteArrayInputStream("y".repeat(4000).getBytes()), -1);
        store.writeTelemetry(caseId, new ByteArrayInputStream("y".repeat(4000).getBytes()), -1);
        assertEquals(PacketCaptureStore.STORE_FULL, assertThrows(PacketCaptureStore.Refusal.class,
                () -> store.writeTelemetry(caseId, new ByteArrayInputStream("z".repeat(4000).getBytes()), -1)).code());
        assertEquals(8000, store.usedBytes());
        assertNoPartialFiles();
        assertEquals(java.nio.file.Files.exists(root.resolve("cases").resolve(caseId).resolve("captures")), false);
    }

    @Test void theTelemetryLimitDefaultsToTwoHundredMegabytesButNeverAboveTheCaptureLimit() {
        assertEquals(200L * 1024 * 1024, new PacketCaptureStore(root, 2L << 30, 100L << 30).maxTelemetryBytes());
        assertEquals(4096, new PacketCaptureStore(root, 4096, 64 * 1024).maxTelemetryBytes());
    }
}
