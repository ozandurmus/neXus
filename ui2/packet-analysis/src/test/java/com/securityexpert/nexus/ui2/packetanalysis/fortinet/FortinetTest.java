package com.securityexpert.nexus.ui2.packetanalysis.fortinet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestOptions;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.PyCsv;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of reference {@code tests/test_fortinet.py} (negative and unknown/low-confidence cases first, then exact
 * round-trip bytes, cooked variants 1/113/276, and CLI entry point).
 */
class FortinetTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
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

    record PcapRecord(long ns, byte[] frame) {
        @Override
        public boolean equals(Object o) {
            return o instanceof PcapRecord other && ns == other.ns && Arrays.equals(frame, other.frame);
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(ns) + Arrays.hashCode(frame);
        }
    }

    static List<PcapRecord> records(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 24;
        List<PcapRecord> out = new ArrayList<>();
        while (pos < data.length) {
            long sec = Integer.toUnsignedLong(b.getInt(pos));
            long ns = Integer.toUnsignedLong(b.getInt(pos + 4));
            int size = b.getInt(pos + 8);
            byte[] frame = Arrays.copyOfRange(data, pos + 16, pos + 16 + size);
            out.add(new PcapRecord(sec * 1_000_000_000L + ns, frame));
            pos += 16 + size;
        }
        return out;
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i += 2) {
            int word = ((data[i] & 0xFF) << 8) | (i + 1 < data.length ? (data[i + 1] & 0xFF) : 0);
            sum += word;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (~sum) & 0xFFFF;
    }

    static byte[] tcpPacket(String src, String dst, int sport, int dport, int seq, int ack, int flags, byte[] payload,
            int ipid) throws IOException {
        byte[] a = InetAddress.getByName(src).getAddress();
        byte[] b = InetAddress.getByName(dst).getAddress();
        ByteBuffer tcp = ByteBuffer.allocate(20 + payload.length).order(ByteOrder.BIG_ENDIAN);
        tcp.putShort((short) sport).putShort((short) dport).putInt(seq).putInt(ack).put((byte) (5 << 4))
                .put((byte) flags).putShort((short) 65535).putShort((short) 0).putShort((short) 0);
        tcp.put(payload);
        byte[] tcpBytes = tcp.array();
        ByteBuffer pseudo = ByteBuffer.allocate(12 + tcpBytes.length).order(ByteOrder.BIG_ENDIAN);
        pseudo.put(a).put(b).put((byte) 0).put((byte) 6).putShort((short) tcpBytes.length).put(tcpBytes);
        int tcpSum = checksum(pseudo.array());
        tcpBytes[16] = (byte) (tcpSum >>> 8);
        tcpBytes[17] = (byte) tcpSum;

        ByteBuffer ip = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        ip.put((byte) 0x45).put((byte) 0).putShort((short) (20 + tcpBytes.length)).putShort((short) (ipid & 0xFFFF))
                .putShort((short) 0x4000).put((byte) 64).put((byte) 6).putShort((short) 0).put(a).put(b);
        byte[] ipBytes = ip.array();
        int ipSum = checksum(ipBytes);
        ipBytes[10] = (byte) (ipSum >>> 8);
        ipBytes[11] = (byte) ipSum;

        ByteBuffer frame = ByteBuffer.allocate(14 + ipBytes.length + tcpBytes.length);
        frame.put(new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA,
                (byte) 0xBB, 0x08, 0x00});
        frame.put(ipBytes).put(tcpBytes);
        return frame.array();
    }

    private static String hexPairs(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        HexFormat hf = HexFormat.of();
        for (int i = 0; i < bytes.length; i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(hf.toHexDigits(bytes[i]));
            if (i + 1 < bytes.length) {
                sb.append(hf.toHexDigits(bytes[i + 1]));
            }
        }
        return sb.toString();
    }

    private Path[] pcapAndText(Path work, boolean relative) throws IOException {
        Files.createDirectories(work);
        Path original = work.resolve("source.pcap");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(0xA1B23C4D).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        data.writeBytes(hdr.array());
        byte[] payload = new byte[32];
        for (int j = 0; j < 32; j++) {
            payload[j] = (byte) j;
        }
        for (int i = 0; i < 4; i++) {
            int seq = i / 2;
            byte[] frame = tcpPacket("10.0.0.1", "203.0.113.1", 50000, 80, 1000 + seq * 32, 9000, 24, payload, seq);
            int sec = 1700000001 + seq * 3;
            int nano = 123 + (i % 2) * 1_000_000;
            ByteBuffer rec = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            rec.putInt(sec).putInt(nano).putInt(frame.length).putInt(frame.length);
            data.writeBytes(rec.array());
            data.writeBytes(frame);
        }
        Files.write(original, data.toByteArray());

        List<String> lines = new ArrayList<>(List.of(
                "FGT # diagnose sniffer packet any none 6 0 a",
                "interfaces=[any]",
                "filters=[none]"));
        List<PcapRecord> recs = records(original);
        for (int i = 0; i < recs.size(); i++) {
            PcapRecord r = recs.get(i);
            long sec = r.ns() / 1_000_000_000L;
            long nano = r.ns() % 1_000_000_000L;
            String stamp = relative
                    ? PyFormat.format((r.ns() - 1700000000000000123L) / 1e9, ".9f")
                    : STAMP_FMT.format(Instant.ofEpochSecond(sec)) + String.format(".%09d", nano);
            lines.add(stamp + " port" + (i % 2 + 1) + " in synthetic packet");
            byte[] frame = r.frame();
            for (int offset = 0; offset < frame.length; offset += 16) {
                byte[] chunk = Arrays.copyOfRange(frame, offset, Math.min(frame.length, offset + 16));
                if (chunk.length > 8) {
                    lines.add(String.format("0x%04x  %s", offset, hexPairs(Arrays.copyOfRange(chunk, 0, 8))));
                    lines.add("        " + hexPairs(Arrays.copyOfRange(chunk, 8, chunk.length)) + "    ........");
                } else {
                    lines.add(String.format("0x%04x  %s    ....", offset, hexPairs(chunk)));
                }
            }
        }
        Path text = work.resolve("sniffer.txt");
        Files.write(text, (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8));
        return new Path[] {original, text};
    }

    static List<Map<String, String>> tsharkFields(String bin, Path path, List<String> fields, List<String> extraOptions)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add(bin);
        args.add("-n");
        args.add("-r");
        args.add(path.toString());
        args.addAll(extraOptions);
        args.addAll(List.of("-T", "fields", "-E", "separator=/t", "-E", "quote=d", "-E", "occurrence=a"));
        for (String f : fields) {
            args.add("-e");
            args.add(f);
        }
        Process p = new ProcessBuilder(args).redirectErrorStream(false).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor());
        List<Map<String, String>> rows = new ArrayList<>();
        PyCsv.CsvReader reader = new PyCsv.CsvReader(new StringReader(out), '\t');
        List<String> row;
        while ((row = reader.next()) != null) {
            if (row.isEmpty()) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                map.put(fields.get(i), i < row.size() ? row.get(i) : "");
            }
            rows.add(map);
        }
        return rows;
    }

    @Test
    @SuppressWarnings("unchecked")
    void relativeRequiresAnchorAndRemainsLowConfidenceAfterCache() throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        Path[] pair = pcapAndText(dir.resolve("rel"), true);
        Path original = pair[0];
        Path text = pair[1];

        IllegalArgumentException noStart = assertThrows(IllegalArgumentException.class,
                () -> Fortinet.convertText(text, dir.resolve("rejected"), null, null, null, tshark));
        assertTrue(noStart.getMessage().contains("start time"));
        assertFalse(Files.exists(dir.resolve("rejected")));

        IllegalArgumentException noZone = assertThrows(IllegalArgumentException.class,
                () -> Fortinet.convertText(text, dir.resolve("no-zone"), "2023-11-14T22:13:20", null, null, tshark));
        assertTrue(noZone.getMessage().contains("timezone"));

        ProjectStore project = ProjectStore.open(dir.resolve("project"), new ProjectStore.Settings(null, 4 * GIB, 1));
        Map<String, Object> result = Fortinet.importText(project, text, "2023-11-14T22:13:20.000000123Z", tshark, null,
                null);
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        List<PcapRecord> origRecords = records(original);
        for (int i = 0; i < files.size(); i++) {
            Path p = Path.of((String) files.get(i).get("path"));
            assertEquals(List.of(origRecords.get(i), origRecords.get(i + 2)), records(p));
            // Reattach preserves conversion provenance.
            CaptureIngest.ingest(project, p, IngestOptions.defaults().withTshark(tshark), null, null);
        }
        Map<String, Object> topology;
        try (var storeDb = project.connect()) {
            for (Object[] row : storeDb.query("SELECT inventory FROM captures")) {
                Map<String, Object> inv = (Map<String, Object>) PyJson.loads((String) row[0]);
                Map<String, Object> meta = (Map<String, Object>) inv.get("source_metadata");
                assertEquals("low", meta.get("clock_confidence"));
            }
            topology = (Map<String, Object>) ProjectStore.get(storeDb, "topology", null);
        }
        List<Map<String, Object>> points = (List<Map<String, Object>>) topology.get("points");
        Set<String> labels = new LinkedHashSet<>();
        List<String> forward = new ArrayList<>();
        for (Map<String, Object> pt : points) {
            labels.add((String) pt.get("label"));
            forward.add((String) pt.get("id"));
        }
        assertEquals(Set.of("port1", "port2"), labels);
        topology.put("forward", forward);

        try (Db engineDb = new Db(Engine.open(dir.resolve("project/project.duckdb"), 1))) {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(PyJson.dumps(topology));
            Map<String, Object> report = Engine.analyze(engineDb, Topology.parse(json), null);
            Map<String, Map<String, Object>> clocks = (Map<String, Map<String, Object>>) report.get("clocks");
            assertFalse(clocks.isEmpty());
            for (Map<String, Object> model : clocks.values()) {
                assertEquals("low", model.get("confidence"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncatedHexConsoleNoiseAndCookedFramesAreReported() throws Exception {
        Path[] pair = pcapAndText(dir.resolve("tol"), false);
        Path text = pair[1];
        String extra = """
                2023-11-14 22:13:30.000001 port1 -- incomplete
                0x0000  0011 2233 4455 6677 8899 aabb 0800 45
                0x0010  a
                console prompt
                2023-11-14 22:13:31.000001 port1 in cooked
                linux cooked capture, packet type: 0x1
                0x0000  0011 2233 4455 6677 8899 aabb 0800 4500
                """;
        Files.writeString(text, Files.readString(text) + extra);
        Map<String, Object> result = Fortinet.convertText(text, dir.resolve("tolerant"), null, null, null, tshark);
        assertEquals(4L, ((Number) result.get("packets")).longValue());
        assertEquals(2L, ((Number) result.get("skipped_packets")).longValue());
        Map<String, Object> reasons = (Map<String, Object>) result.get("skipped_reasons");
        assertEquals(1L, ((Number) reasons.get("invalid_or_truncated_hex")).longValue());
        assertEquals(1L, ((Number) reasons.get("unsupported_cooked_link_header")).longValue());
        assertTrue(((Number) result.get("skipped_lines")).longValue() >= 6L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fortinetRoundTripExactBytesAndTsharkDecode() throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        Path[] pair = pcapAndText(dir.resolve("rt"), false);
        Path original = pair[0];
        Path text = pair[1];
        Map<String, Object> result = Fortinet.convertText(text, dir.resolve("converted"), null, null, null, tshark);
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        Set<String> ifaces = new LinkedHashSet<>();
        for (Map<String, Object> f : files) {
            ifaces.add((String) f.get("interface"));
        }
        assertEquals(Set.of("port1", "port2"), ifaces);
        assertEquals(3L, ((Number) result.get("skipped_lines")).longValue());
        assertEquals(0L, ((Number) result.get("skipped_packets")).longValue());

        List<String> fields = List.of("frame.time_epoch", "frame.cap_len", "ip.src", "ip.dst", "tcp.seq_raw");
        List<Map<String, String>> expected = tsharkFields(tshark, original, fields, List.of());
        List<PcapRecord> origRecords = records(original);
        for (int i = 0; i < files.size(); i++) {
            Path p = Path.of((String) files.get(i).get("path"));
            assertEquals(List.of(origRecords.get(i), origRecords.get(i + 2)), records(p));
            assertEquals(List.of(expected.get(i), expected.get(i + 2)), tsharkFields(tshark, p, fields, List.of()));
        }

        // Also exercise FortinetCli entry point on the same text.
        Path cliResult = dir.resolve("cli_result.json");
        FortinetCli.main(new String[] {
            "--project", dir.resolve("cli_project").toString(),
            "--text", text.toString(),
            "--result", cliResult.toString()
        });
        Map<String, Object> parsedCli = (Map<String, Object>) PyJson.loads(Files.readString(cliResult));
        assertEquals(4L, ((Number) parsedCli.get("packets")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cookedVariantsUseTsharkLinkDecodeWithoutChangingBytes() throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        byte[] ethernet = tcpPacket("10.0.0.1", "203.0.113.1", 50000, 80, 1000, 9000, 24,
                "synthetic".getBytes(StandardCharsets.UTF_8), 1);
        byte[] mac = new byte[] {0x02, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00};
        byte[] ipAndTcp = Arrays.copyOfRange(ethernet, 14, ethernet.length);

        ByteBuffer sll1 = ByteBuffer.allocate(16 + ipAndTcp.length).order(ByteOrder.BIG_ENDIAN);
        sll1.putShort((short) 1).putShort((short) 1).putShort((short) 6).put(mac).putShort((short) 0x0800).put(ipAndTcp);

        ByteBuffer sll2 = ByteBuffer.allocate(20 + ipAndTcp.length).order(ByteOrder.BIG_ENDIAN);
        sll2.putShort((short) 0x0800).putShort((short) 0).putInt(1).putShort((short) 1).put((byte) 1).put((byte) 6)
                .put(mac).put(ipAndTcp);

        Map<Integer, byte[]> layouts = new LinkedHashMap<>();
        layouts.put(1, ethernet);
        layouts.put(113, sll1.array());
        layouts.put(276, sll2.array());

        List<String> fields = List.of("frame.time_epoch", "frame.cap_len", "ip.src", "ip.dst", "tcp.seq_raw");
        for (Map.Entry<Integer, byte[]> e : layouts.entrySet()) {
            int link = e.getKey();
            byte[] frame = e.getValue();
            Path original = dir.resolve("original-" + link + ".pcap");
            ByteBuffer pcap = ByteBuffer.allocate(24 + 16 + frame.length).order(ByteOrder.LITTLE_ENDIAN);
            pcap.putInt(0xA1B23C4D).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535)
                    .putInt(link);
            pcap.putInt(1700000001).putInt(123).putInt(frame.length).putInt(frame.length);
            pcap.put(frame);
            Files.write(original, pcap.array());

            Path text = dir.resolve("cooked-" + link + ".txt");
            List<String> lines = new ArrayList<>();
            lines.add("2023-11-14 22:13:21.000000123 port1 in synthetic");
            lines.add("linux cooked capture, packet type: 0x1, link layer address type: 0x1, protocol type: 0x800");
            for (int offset = 0; offset < frame.length; offset += 16) {
                byte[] chunk = Arrays.copyOfRange(frame, offset, Math.min(frame.length, offset + 16));
                lines.add(String.format("0x%04x  %s    ........", offset, hexPairs(chunk)));
            }
            Files.writeString(text, String.join("\n", lines) + "\n");

            Map<String, Object> result = Fortinet.convertText(text, dir.resolve("converted-" + link), null, null, null,
                    tshark);
            List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
            Map<String, Object> out = files.get(0);
            assertEquals((long) link, ((Number) out.get("link_type")).longValue());
            assertEquals(Boolean.TRUE, out.get("cooked_metadata"));
            Path outPath = Path.of((String) out.get("path"));
            assertEquals(records(original), records(outPath));
            assertEquals(tsharkFields(tshark, original, fields, List.of()),
                    tsharkFields(tshark, outPath, fields, List.of()));
        }
    }
}
