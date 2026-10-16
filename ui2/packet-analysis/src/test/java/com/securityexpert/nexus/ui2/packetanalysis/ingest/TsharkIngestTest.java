package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.Db;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end through a real tshark on a hand-built synthetic pcap. Skipped (never failed) when tshark is not
 * installed; the cross-engine row parity lives in tools/packet_analysis/parity/l1_compare.py.
 */
class TsharkIngestTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static String tshark;

    @TempDir
    Path dir;

    @BeforeAll
    static void findTshark() {
        try {
            tshark = TsharkRunner.find(System.getenv("TSHARK"));
        } catch (IllegalArgumentException e) {
            tshark = null;
        }
    }

    private static byte[] tcpFrame(int seq, byte[] payload, int flags) {
        ByteBuffer b = ByteBuffer.allocate(14 + 20 + 20 + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[] {2, 0, 0, 0, 0, 2, 2, 0, 0, 0, 0, 1}).putShort((short) 0x0800);
        b.put((byte) 0x45).put((byte) 0).putShort((short) (40 + payload.length)).putShort((short) (100 + seq))
                .putShort((short) 0x4000).put((byte) 64).put((byte) 6).putShort((short) 0);
        b.put(new byte[] {(byte) 198, 51, 100, 1}).put(new byte[] {(byte) 192, 0, 2, 9});
        b.putShort((short) 40000).putShort((short) 443).putInt(1000 + seq).putInt(0).put((byte) 0x50)
                .put((byte) flags).putShort((short) 65535).putShort((short) 0).putShort((short) 0);
        b.put(payload);
        return b.array();
    }

    private static byte[] udpFrame(byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(14 + 20 + 8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[] {2, 0, 0, 0, 0, 2, 2, 0, 0, 0, 0, 1}).putShort((short) 0x0800);
        b.put((byte) 0x45).put((byte) 0).putShort((short) (28 + payload.length)).putShort((short) 7)
                .putShort((short) 0).put((byte) 63).put((byte) 17).putShort((short) 0);
        b.put(new byte[] {(byte) 198, 51, 100, 2}).put(new byte[] {(byte) 198, 51, 100, 3});
        b.putShort((short) 5000).putShort((short) 6000).putShort((short) (8 + payload.length)).putShort((short) 0);
        b.put(payload);
        return b.array();
    }

    private static byte[] pcap(List<byte[]> frames, long[] seconds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        out.writeBytes(h.array());
        for (int i = 0; i < frames.size(); i++) {
            ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            r.putInt((int) seconds[i]).putInt(250_000 * (i % 4)).putInt(frames.get(i).length)
                    .putInt(frames.get(i).length);
            out.writeBytes(r.array());
            out.writeBytes(frames.get(i));
        }
        return out.toByteArray();
    }

    private ProjectStore project() {
        return ProjectStore.open(dir.resolve("project"), new ProjectStore.Settings(null, 4 * GIB, 2));
    }

    @Test
    void ingestsRowsMetadataAndExcludedFrames() throws IOException {
        assumeTrue(tshark != null, "tshark not installed");
        List<byte[]> frames = new ArrayList<>();
        frames.add(tcpFrame(0, new byte[0], 0x02));
        frames.add(tcpFrame(1, "hello".getBytes(), 0x18));
        frames.add(udpFrame(new byte[] {0, 0, 0, 0}));
        frames.add(udpFrame(new byte[] {1, 2, 3}));
        frames.add(tcpFrame(2, new byte[0], 0x10));
        Path capture = dir.resolve("small.pcap");
        Files.write(capture, pcap(frames, new long[] {1_700_000_000L, 1_700_000_001L, 1_700_000_002L, 5L,
            1_700_000_003L}));
        ProjectStore store = project();
        List<Map<String, Object>> events = new ArrayList<>();
        String cid = CaptureIngest.ingest(store, capture, IngestOptions.defaults().withTshark(tshark).withBatchSize(2),
                null, events::add);
        assertEquals(32, cid.length());
        assertEquals("ready", events.get(events.size() - 1).get("state"));
        try (Db db = store.connect()) {
            Object[] cap = db.query("SELECT state, checkpoint, error, inventory, name FROM captures").get(0);
            assertEquals("ready", cap[0]);
            assertEquals(5L, cap[1]);
            assertNull(cap[2]);
            assertEquals("small.pcap", cap[4]);
            Map<?, ?> inv = (Map<?, ?>) PyJson.loads((String) cap[3]);
            assertEquals(4L, inv.get("packet_count"));
            assertEquals(5L, inv.get("records_read"));
            assertEquals(Map.of("timestamp_before_2000", 1L), inv.get("timestamp_excluded_counts"));
            List<Object[]> rows = db.query("SELECT frame, proto, length, prefix, payload_hash IS NULL, unsupported,"
                    + " src FROM packets ORDER BY frame");
            assertEquals(4, rows.size());
            assertEquals("TCP", rows.get(0)[1]);
            assertEquals(5L, rows.get(1)[2]);
            assertEquals("68656c6c6f", rows.get(1)[3]);
            assertEquals("UDP payload unavailable or all-zero; identity is insufficient", rows.get(2)[5]);
            assertEquals(true, rows.get(0)[4]);
            assertNull(rows.get(0)[5]);
            assertEquals("198.51.100.1", rows.get(0)[6]);
            assertEquals(List.of(4L, "timestamp_before_2000", 5.75), List.of(db.query(
                    "SELECT frame, reason, observed_ts FROM excluded_frames").get(0)));
        }
        // Same identity again: cached, no new tshark run.
        List<Map<String, Object>> again = new ArrayList<>();
        assertEquals(cid, CaptureIngest.ingest(store, capture, IngestOptions.defaults().withTshark(tshark), null,
                again::add));
        assertEquals("cached", again.get(0).get("state"));
        // A different prefix size is a different identity: re-ingested under the same capture id.
        assertEquals(cid, CaptureIngest.ingest(store, capture,
                new IngestOptions(tshark, 8, 50000, false, null, null, null, null, null, null), null, null));
    }

    @Test
    void cancellationMarksTheCaptureCancelled() throws IOException {
        assumeTrue(tshark != null, "tshark not installed");
        List<byte[]> frames = new ArrayList<>();
        long[] seconds = new long[200];
        for (int i = 0; i < 200; i++) {
            frames.add(tcpFrame(i, new byte[0], 0x10));
            seconds[i] = 1_700_000_000L + i;
        }
        Path capture = dir.resolve("cancel.pcap");
        Files.write(capture, pcap(frames, seconds));
        ProjectStore store = project();
        CancelFlag flag = new CancelFlag();
        assertThrows(IngestCancelledException.class, () -> CaptureIngest.ingest(store, capture,
                IngestOptions.defaults().withTshark(tshark).withBatchSize(50), flag, state -> {
                    if ("ingesting".equals(state.get("state"))) {
                        flag.set();
                    }
                }));
        try (Db db = store.connect()) {
            Object[] cap = db.query("SELECT state, checkpoint, error FROM captures").get(0);
            assertEquals("cancelled", cap[0]);
            assertEquals(50L, cap[1]);
            assertTrue(((String) cap[2]).startsWith("Ingest cancelled"));
            assertEquals(50L, db.query("SELECT count(*) FROM packets").get(0)[0]);
        }
    }

    @Test
    void tsharkFailureIsRecorded() throws IOException {
        assumeTrue(tshark != null, "tshark not installed");
        Path capture = dir.resolve("bad.pcap");
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        Files.write(capture, h.array());
        ProjectStore store = project();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CaptureIngest.ingest(store,
                capture, IngestOptions.defaults().withTshark(tshark), null, null));
        assertEquals("Capture contains zero usable frames", e.getMessage());
        try (Db db = store.connect()) {
            assertEquals("error", db.query("SELECT state FROM captures").get(0)[0]);
        }
    }
}
