package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The container walker on small synthetic byte arrays. */
class CaptureMetadataTest {

    @TempDir
    Path dir;

    private Map<String, Object> walk(byte[] data) throws IOException {
        Path f = dir.resolve("c" + System.nanoTime());
        Files.write(f, data);
        return CaptureMetadata.read(f, Cancellation.NONE);
    }

    // ---------------------------------------------------------------- builders

    private static final class Bytes {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteOrder order;

        Bytes(ByteOrder order) {
            this.order = order;
        }

        Bytes u16(int v) {
            return put(ByteBuffer.allocate(2).order(order).putShort((short) v).array());
        }

        Bytes u32(long v) {
            return put(ByteBuffer.allocate(4).order(order).putInt((int) v).array());
        }

        Bytes u64(long v) {
            return put(ByteBuffer.allocate(8).order(order).putLong(v).array());
        }

        Bytes put(byte[] b) {
            out.writeBytes(b);
            return this;
        }

        Bytes zeros(int n) {
            return put(new byte[n]);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static byte[] pcap(ByteOrder order, long magic, long snaplen, long link, int... caplens) {
        Bytes b = new Bytes(order).u32(magic).u16(2).u16(4).u32(0).u32(0).u32(snaplen).u32(link);
        for (int caplen : caplens) {
            b.u32(1_700_000_000L).u32(0).u32(caplen).u32(caplen).zeros(caplen);
        }
        return b.bytes();
    }

    private static byte[] block(ByteOrder order, int type, byte[] body) {
        int len = 12 + body.length;
        return new Bytes(order).u32(type).u32(len).put(body).u32(len).bytes();
    }

    private static byte[] shb(ByteOrder order) {
        return block(order, 0x0a0d0d0a, new Bytes(order).u32(0x1a2b3c4d).u16(1).u16(0).u64(-1).bytes());
    }

    private static byte[] idb(ByteOrder order, int link, long snaplen, String name) {
        Bytes b = new Bytes(order).u16(link).u16(0).u32(snaplen);
        if (name != null) {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            b.u16(2).u16(n.length).put(n).zeros((4 - n.length % 4) % 4);
        }
        b.u16(0).u16(0);
        return block(order, 1, b.bytes());
    }

    private static byte[] epb(ByteOrder order, int iface, int caplen) {
        Bytes b = new Bytes(order).u32(iface).u32(0).u32(0).u32(caplen).u32(caplen).zeros((caplen + 3) & ~3);
        return block(order, 6, b.bytes());
    }

    private static byte[] isb(ByteOrder order, int iface, long ifdrop, long osdrop) {
        Bytes b = new Bytes(order).u32(iface).u32(0).u32(0)
                .u16(5).u16(8).u64(ifdrop).u16(7).u16(8).u64(osdrop).u16(0).u16(0);
        return block(order, 5, b.bytes());
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Arrays.stream(parts).forEach(out::writeBytes);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- pcap

    @Test
    void littleEndianPcapKeepsTheReferenceKeyOrder() throws IOException {
        Map<String, Object> m = walk(pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4L, 65535, 1, 60, 70));
        assertEquals("{\"format\": \"pcap\", \"interfaces\": [{\"id\": 0, \"section\": 0, \"snaplen\": 65535,"
                + " \"link_type\": 1, \"name\": null}], \"ifdrop\": null, \"osdrop\": null, \"complete_records\": 2}",
                PyJson.dumps(m));
    }

    @Test
    void bigEndianAndNanosecondMagicsAndLinkTypeMask() throws IOException {
        Map<String, Object> be = walk(pcap(ByteOrder.BIG_ENDIAN, 0xa1b2c3d4L, 90, 0x00ab0001L, 10));
        assertEquals(1L, ((Map<?, ?>) ((List<?>) be.get("interfaces")).get(0)).get("link_type"));
        assertEquals(1L, be.get("complete_records"));
        Map<String, Object> ns = walk(pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b23c4dL, 90, 1, 10, 10, 10));
        assertEquals(3L, ns.get("complete_records"));
        assertEquals(90L, ((Map<?, ?>) ((List<?>) ns.get("interfaces")).get(0)).get("snaplen"));
    }

    @Test
    void recordCutShortIsADamagedTail() throws IOException {
        byte[] full = pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4L, 65535, 1, 60, 70);
        Map<String, Object> m = walk(Arrays.copyOf(full, full.length - 5));
        assertEquals(1L, m.get("complete_records"));
        assertEquals(true, m.get("damaged_tail"));
        assertNull(m.get("frame_limit"));
        Map<String, Object> header = walk(Arrays.copyOf(full, 24 + 16 + 60 + 9));
        assertEquals(1L, header.get("complete_records"));
        assertEquals(true, header.get("damaged_tail"));
    }

    @Test
    void zeroTailSetsFrameLimitAndFlagsAPartialRecord() throws IOException {
        byte[] base = pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4L, 65535, 1, 60, 70);
        Map<String, Object> whole = walk(concat(base, new byte[32]));
        assertEquals(32L, whole.get("zero_tail_bytes"));
        assertEquals(2L, whole.get("zero_tail_records"));
        assertEquals(16L, whole.get("zero_tail_record_size"));
        assertEquals(2L, whole.get("frame_limit"));
        assertNull(whole.get("damaged_tail"));
        assertEquals(List.of("format", "interfaces", "ifdrop", "osdrop", "complete_records", "zero_tail_bytes",
                "zero_tail_records", "zero_tail_record_size", "frame_limit"), List.copyOf(whole.keySet()));
        Map<String, Object> partial = walk(concat(base, new byte[40]));
        assertEquals(2L, partial.get("zero_tail_records"));
        assertEquals(true, partial.get("damaged_tail"));
    }

    @Test
    void zeroPacketBodiesEndingTheFileAreNotATail() throws IOException {
        // The zero run starts inside the last record, after its header: complete records are never a tail.
        Map<String, Object> m = walk(pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4L, 65535, 1, 60, 70));
        assertEquals(2L, m.get("complete_records"));
        assertNull(m.get("frame_limit"));
        assertNull(m.get("zero_tail_bytes"));
        assertNull(m.get("damaged_tail"));
    }

    @Test
    void unsupportedAndTruncatedHeaders() {
        assertThrows(IllegalArgumentException.class, () -> walk(new byte[] {1, 2, 3, 4, 5}));
        assertThrows(IllegalArgumentException.class,
                () -> walk(Arrays.copyOf(pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4L, 1, 1), 20)));
    }

    // ---------------------------------------------------------------- pcapng

    @Test
    void pcapngInterfacesNamesAndDropCounters() throws IOException {
        ByteOrder le = ByteOrder.LITTLE_ENDIAN;
        byte[] data = concat(shb(le), idb(le, 1, 262144, "eth0"), idb(le, 113, 96, "anyé"), epb(le, 0, 60),
                epb(le, 1, 61), isb(le, 0, 3, 4), isb(le, 0, 5, 1), isb(le, 1, 7, 0), new byte[] {9, 9, 9, 9});
        // A trailing non-block makes the last 4 bytes a truncated header: damaged tail, nothing else counted.
        Map<String, Object> m = walk(data);
        assertEquals("pcapng", m.get("format"));
        assertEquals(2L, m.get("complete_records"));
        assertEquals(12L, m.get("ifdrop"));
        assertEquals(4L, m.get("osdrop"));
        assertEquals(true, m.get("damaged_tail"));
        assertEquals(false, m.get("multiple_sections"));
        List<?> ifaces = (List<?>) m.get("interfaces");
        assertEquals("[{\"id\": 0, \"section\": 0, \"snaplen\": 262144, \"link_type\": 1, \"name\": \"eth0\"},"
                + " {\"id\": 1, \"section\": 0, \"snaplen\": 96, \"link_type\": 113, \"name\": \"any\\u00e9\"}]",
                PyJson.dumps(ifaces));
    }

    @Test
    void secondSectionRestartsInterfaceIdsAndMayChangeByteOrder() throws IOException {
        ByteOrder le = ByteOrder.LITTLE_ENDIAN;
        ByteOrder be = ByteOrder.BIG_ENDIAN;
        byte[] data = concat(shb(le), idb(le, 1, 100, null), epb(le, 0, 10), shb(be), idb(be, 105, 200, null),
                epb(be, 0, 10), epb(be, 0, 12));
        Map<String, Object> m = walk(data);
        assertEquals(3L, m.get("complete_records"));
        assertEquals(true, m.get("multiple_sections"));
        assertNull(m.get("ifdrop"));
        assertNull(m.get("damaged_tail"));
        assertEquals("[{\"id\": 0, \"section\": 0, \"snaplen\": 100, \"link_type\": 1, \"name\": null},"
                + " {\"id\": 0, \"section\": 1, \"snaplen\": 200, \"link_type\": 105, \"name\": null}]",
                PyJson.dumps(m.get("interfaces")));
    }

    @Test
    void pcapngTrailerAndLengthRules() throws IOException {
        ByteOrder le = ByteOrder.LITTLE_ENDIAN;
        byte[] good = concat(shb(le), idb(le, 1, 100, null), epb(le, 0, 10));
        // Bad trailer on the final block: damaged tail.
        byte[] lastBad = good.clone();
        lastBad[lastBad.length - 1] ^= 1;
        Map<String, Object> m = walk(lastBad);
        assertEquals(true, m.get("damaged_tail"));
        assertEquals(0L, m.get("complete_records"));
        // Bad trailer before the end: malformed.
        byte[] midBad = concat(lastBad, epb(le, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> walk(midBad));
        // Block cut inside: damaged tail.
        Map<String, Object> cut = walk(Arrays.copyOf(good, good.length - 3));
        assertEquals(true, cut.get("damaged_tail"));
        // Length not a multiple of 4: malformed.
        byte[] odd = good.clone();
        ByteBuffer.wrap(odd).order(le).putInt(4, 27);
        assertThrows(IllegalArgumentException.class, () -> walk(odd));
        // Bad byte-order magic: malformed.
        byte[] bom = good.clone();
        bom[8] = 0;
        assertThrows(IllegalArgumentException.class, () -> walk(bom));
        // Zero-filled tail after complete blocks.
        Map<String, Object> zero = walk(concat(good, new byte[30]));
        assertEquals(1L, zero.get("frame_limit"));
        assertEquals(2L, zero.get("zero_tail_records"));
        assertEquals(true, zero.get("damaged_tail"));
    }

    @Test
    void malformedOptionRaises() {
        ByteOrder le = ByteOrder.LITTLE_ENDIAN;
        byte[] body = new Bytes(le).u16(1).u16(0).u32(100).u16(2).u16(40).put("ab".getBytes()).zeros(2).bytes();
        byte[] data = concat(shb(le), block(le, 1, body));
        assertThrows(IllegalArgumentException.class, () -> walk(data));
    }

    // ---------------------------------------------------------------- snoop

    private static byte[] snoopRecord(int included, long drops) {
        int length = 24 + ((included + 3) & ~3);
        return new Bytes(ByteOrder.BIG_ENDIAN).u32(included).u32(included).u32(length).u32(drops)
                .u32(1_700_000_000L).u32(0).zeros(length - 24).bytes();
    }

    @Test
    void snoopRecordsAndDrops() throws IOException {
        byte[] header = new Bytes(ByteOrder.BIG_ENDIAN).put("snoop".getBytes(StandardCharsets.US_ASCII))
                .zeros(3).u32(2).u32(4).bytes();
        byte[] r1 = snoopRecord(10, 5);
        r1[24] = 1;
        byte[] r2 = snoopRecord(11, 2);
        r2[24] = 1;
        Map<String, Object> m = walk(concat(header, r1, r2, Arrays.copyOf(r1, 20)));
        assertEquals("snoop", m.get("format"));
        assertEquals("checkpoint", m.get("adapter"));
        assertEquals(2L, m.get("complete_records"));
        assertEquals(5L, m.get("ifdrop"));
        assertEquals(true, m.get("damaged_tail"));
        assertTrue(PyJson.dumps(m).contains("\"snaplen\": null, \"link_type\": 4, \"name\": \"snoop network type\""));
        byte[] badVersion = header.clone();
        badVersion[11] = 3;
        assertThrows(IllegalArgumentException.class, () -> walk(concat(badVersion, r1)));
    }

    @Test
    void cancellationStopsTheWalk() throws IOException {
        Path f = dir.resolve("x.pcap");
        Files.write(f, pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4L, 65535, 1, 60));
        assertThrows(IngestCancelledException.class, () -> CaptureMetadata.read(f, () -> true));
    }
}
