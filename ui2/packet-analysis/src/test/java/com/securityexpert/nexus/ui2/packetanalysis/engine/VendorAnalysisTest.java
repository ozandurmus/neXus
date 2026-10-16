package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.ReportModel;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestOptions;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.PyCsv;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkFields;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Port of reference {@code tests/test_f5.py} and vendor device events / selector rules from
 * {@code tests/test_paloalto.py} and {@code vendors.py} (negative/unknown cases first).
 */
class VendorAnalysisTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
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

    private static byte[] tcpPacket(String src, String dst, int sport, int dport, int seq, int ack, int flags,
            byte[] payload, int ipid) throws IOException {
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

    private static byte[] v4Mapped(String ip) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(16);
        b.position(10);
        b.put((byte) 0xFF).put((byte) 0xFF);
        b.put(InetAddress.getByName(ip).getAddress());
        return b.array();
    }

    private static byte[] f5Trailer(long flow, long peer, int incoming, String reason) throws IOException {
        byte[] vip = "/Common/synthetic".getBytes(StandardCharsets.UTF_8);
        ByteBuffer low = ByteBuffer.allocate(7 + vip.length);
        low.put((byte) 1).put((byte) (5 + vip.length)).put((byte) 1).put((byte) incoming).put((byte) 0).put((byte) 2)
                .put((byte) vip.length).put(vip);

        byte[] cause = new byte[0];
        if (!reason.isEmpty()) {
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            ByteBuffer cb = ByteBuffer.allocate(1 + 8 + r.length).order(ByteOrder.BIG_ENDIAN);
            cb.put((byte) 0).putLong(0x12340002L).put(r);
            cause = cb.array();
        }
        ByteBuffer medium = ByteBuffer.allocate(3 + 26 + 1 + cause.length).order(ByteOrder.BIG_ENDIAN);
        medium.put((byte) 2).put((byte) (28 + cause.length)).put((byte) 1);
        medium.putLong(flow).putLong(peer).putInt(0).put((byte) 0).put((byte) 0).putInt(0);
        medium.put((byte) cause.length).put(cause);

        ByteBuffer high = ByteBuffer.allocate(42).order(ByteOrder.BIG_ENDIAN);
        high.put((byte) 3).put((byte) 40).put((byte) 0).put((byte) 6).putShort((short) 100);
        high.put(v4Mapped(flow != 200 ? "203.0.113.2" : peer == 100 ? "192.0.2.1" : "192.0.2.2"));
        high.put(v4Mapped(flow != 200 ? "198.51.100.2" : "198.51.100.1"));
        high.putShort((short) (flow != 200 ? 80 : peer == 100 ? 50000 : 50001));
        high.putShort((short) (flow != 200 ? 41000 : 80));

        ByteBuffer all = ByteBuffer.allocate(low.capacity() + medium.capacity() + high.capacity());
        all.put(low.array()).put(medium.array()).put(high.array());
        return all.array();
    }

    record TimedFrame(double ts, byte[] frame) {
    }

    private static Path f5Capture(Path path, boolean peerReuse) throws IOException {
        List<TimedFrame> frames = new ArrayList<>();
        class Emitter {
            void emit(double t, String leg, boolean reverse, int seq, int ack, int flags, byte[] payload, String reason)
                    throws IOException {
                String a;
                String b;
                int port;
                if ("client_b".equals(leg)) {
                    a = "192.0.2.2";
                    b = "198.51.100.1";
                    port = 50001;
                } else if ("client".equals(leg)) {
                    a = "192.0.2.1";
                    b = "198.51.100.1";
                    port = 50000;
                } else {
                    a = "198.51.100.2";
                    b = "203.0.113.2";
                    port = 41000;
                }
                byte[] pkt = tcpPacket(reverse ? b : a, reverse ? a : b, reverse ? 80 : port, reverse ? port : 80, seq,
                        ack, flags, payload, frames.size());
                long flow;
                long peer;
                if ("client_b".equals(leg)) {
                    flow = 300;
                    peer = 200;
                } else if ("server_b".equals(leg)) {
                    flow = 200;
                    peer = 300;
                } else if ("client".equals(leg)) {
                    flow = 100;
                    peer = 200;
                } else {
                    flow = 200;
                    peer = 100;
                }
                byte[] tr = f5Trailer(flow, peer, (leg.startsWith("client") != reverse) ? 1 : 0, reason);
                ByteBuffer combined = ByteBuffer.allocate(pkt.length + tr.length);
                combined.put(pkt).put(tr);
                frames.add(new TimedFrame(1700000000.0 + t, combined.array()));
            }
        }
        Emitter e = new Emitter();
        e.emit(0, "client", false, 1000, 0, 2, new byte[0], "");
        e.emit(0.01, "client", true, 9000, 1001, 18, new byte[0], "");
        e.emit(0.02, "client", false, 1001, 9001, 16, new byte[0], "");
        e.emit(0, "server", false, 8000, 0, 2, new byte[0], "");
        e.emit(0.01, "server", true, 9000, 8001, 18, new byte[0], "");
        e.emit(0.02, "server", false, 8001, 9001, 16, new byte[0], "");
        if (peerReuse) {
            e.emit(2.8, "client_b", false, 1000, 0, 2, new byte[0], "");
            e.emit(2.81, "client_b", true, 9000, 1001, 18, new byte[0], "");
            e.emit(2.82, "client_b", false, 1001, 9001, 16, new byte[0], "");
        }
        double[][] schedule = new double[][] {{1.0, 0.24}, {3.0, 0.1}};
        byte[] okResp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < schedule.length; i++) {
            double at = schedule[i][0];
            double delay = schedule[i][1];
            byte[] request = ("GET /synthetic/" + i + " HTTP/1.1\r\nHost: test\r\nContent-Length: 0\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            String cLeg = (peerReuse && i == 1) ? "client_b" : "client";
            int cSeq = (peerReuse && i == 1) ? 1001 : 1001 + i * request.length;
            e.emit(at, cLeg, false, cSeq, 9001, 24, request, "");
            e.emit(at + delay + 0.02, cLeg, true, "client_b".equals(cLeg) ? 9001 : 9001 + i * 38,
                    cSeq + request.length, 24, okResp, "");

            String sLeg = (peerReuse && i == 1) ? "server_b" : "server";
            int sSeq = 8001 + i * request.length;
            double sWhen = at + delay;
            e.emit(sWhen, sLeg, false, sSeq, 9001, 24, request, "");
            e.emit(sWhen + 0.01, sLeg, true, 9001 + i * 38, sSeq + request.length, 24, okResp, "");
        }
        e.emit(4.0, "client", false, 1200, 9100, 20, new byte[0], "Synthetic policy reset");
        frames.sort(Comparator.comparingDouble(TimedFrame::ts));

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xA1B2C3D4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        data.writeBytes(h.array());
        for (TimedFrame tf : frames) {
            long sec = (long) tf.ts();
            long usec = Math.round((tf.ts() - sec) * 1e6);
            ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            r.putInt((int) sec).putInt((int) usec).putInt(tf.frame().length).putInt(tf.frame().length);
            data.writeBytes(r.array());
            data.writeBytes(tf.frame());
        }
        Files.write(path, data.toByteArray());
        return path;
    }

    private static List<Map<String, String>> tsharkFields(String bin, Path path, List<String> fields,
            List<String> options) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add(bin);
        args.add("-n");
        args.add("-r");
        args.add(path.toString());
        args.addAll(options);
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
    void f5UnknownClientCidrAndEmptyDeviceProofsFailClosed() throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        Path path = f5Capture(dir.resolve("f5_unk.pcap"), false);
        ProjectStore project = ProjectStore.open(dir.resolve("p_unk"), new ProjectStore.Settings(null, 4 * GIB, 1));
        String cid = CaptureIngest.ingest(project, path,
                new IngestOptions(tshark, 64, 50000, false, null, true, null, null, null, null), null, null);
        List<Map<String, Object>> points = List.of(
                Map.of("id", "client", "device", "BIG-IP", "label", "client", "capture_id", cid, "vendor", "f5",
                        "vendor_stage", "client"),
                Map.of("id", "server", "device", "BIG-IP", "label", "server", "capture_id", cid, "vendor", "f5",
                        "vendor_stage", "server"));
        Map<String, Object> unknownTopo = Map.of("points", points, "forward", List.of("client", "server"),
                "client_cidrs", List.of("0.0.0.0/0"));
        try (Db db = new Db(Engine.open(dir.resolve("p_unk/project.duckdb"), 1))) {
            JsonNode report = JSON.readTree(
                    ReportModel.toJson(Engine.analyze(db, Topology.parse(JSON.readTree(PyJson.dumps(unknownTopo))), null)));
            assertEquals("Inconclusive", report.get("verdict").asText());
            assertTrue(report.get("f5").get("pairs").size() > 0);
            for (JsonNode pair : report.get("f5").get("pairs")) {
                assertEquals("unknown", pair.get("role").asText());
                assertFalse(pair.get("reason").asText().isEmpty());
            }
            assertEquals(0, report.get("f5").get("requests").size());
            assertEquals(0, report.get("vendor_device_event_count").asInt());
            assertTrue(report.get("vendor_device_events").isArray());
            assertEquals(0, report.get("vendor_device_events").size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void f5DecodePairRequestDwellAndReset(boolean peerReuse) throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        Path sub = dir.resolve("f5_" + peerReuse);
        Files.createDirectories(sub);
        Path path = f5Capture(sub.resolve("f5.pcap"), peerReuse);
        List<String> fields = new ArrayList<>();
        fields.addAll(TsharkFields.F5_FIELDS);
        fields.addAll(TsharkFields.HTTP_FIELDS);
        List<String> queryFields = new ArrayList<>(List.of("frame.number", "frame.time_epoch", "tcp.stream"));
        queryFields.addAll(fields);
        List<Map<String, String>> expected = tsharkFields(tshark, path, queryFields,
                List.of("--enable-protocol", "f5ethtrailer"));
        assertTrue(expected.stream().anyMatch(e -> !e.get("f5ethtrailer.flowid").isEmpty()));
        assertTrue(expected.stream().anyMatch(e -> !e.get("http.request.method").isEmpty()));

        ProjectStore project = ProjectStore.open(sub.resolve("project"), new ProjectStore.Settings(null, 4 * GIB, 1));
        String cid = CaptureIngest.ingest(project, path,
                new IngestOptions(tshark, 64, 50000, false, null, true, null, null, null, null), null, null);
        try (var storeDb = project.connect()) {
            List<Object[]> vendorRows = storeDb.query("SELECT vendor FROM packets ORDER BY frame");
            assertEquals(expected.size(), vendorRows.size());
            for (int i = 0; i < expected.size(); i++) {
                Map<String, Object> actual = (Map<String, Object>) PyJson.loads((String) vendorRows.get(i)[0]);
                for (String f : fields) {
                    assertEquals(expected.get(i).get(f), String.valueOf(actual.getOrDefault(f, "")));
                }
            }
        }

        List<Map<String, Object>> points = List.of(
                Map.of("id", "client", "device", "BIG-IP", "label", "client", "capture_id", cid, "vendor", "f5",
                        "vendor_stage", "client"),
                Map.of("id", "server", "device", "BIG-IP", "label", "server", "capture_id", cid, "vendor", "f5",
                        "vendor_stage", "server"));
        Map<String, Object> topoMap = Map.of("points", points, "forward", List.of("client", "server"), "client_cidrs",
                List.of("192.0.2.0/24"));
        Topology topology = Topology.parse(JSON.readTree(PyJson.dumps(topoMap)));

        Path dbPath = sub.resolve("project/project.duckdb");
        Path dbFour = sub.resolve("four.duckdb");
        Files.copy(dbPath, dbFour);

        JsonNode report;
        String jsonOne;
        try (Db db = new Db(Engine.open(dbPath, 1))) {
            Map<String, Object> raw = Engine.analyze(db, topology, null);
            raw.put("generated_at", "<masked>");
            jsonOne = ReportModel.toJson(raw);
            report = JSON.readTree(jsonOne);
        }
        Set<String> distinctStreams = new LinkedHashSet<>();
        expected.forEach(e -> distinctStreams.add(e.get("tcp.stream")));
        assertEquals(distinctStreams.size(), report.get("flow_count").asInt());

        Set<String> roles = new LinkedHashSet<>();
        report.get("f5").get("pairs").forEach(p -> roles.add(p.get("role").asText()));
        assertEquals(Set.of("client", "server"), roles);

        JsonNode requests = report.get("f5").get("requests");
        assertEquals(2, requests.size());
        List<Map<String, String>> decoded = expected.stream().filter(e -> !e.get("http.request.method").isEmpty())
                .toList();
        for (JsonNode r : requests) {
            assertNotEquals(r.get("client_flow").asText(), r.get("server_flow").asText());
            int frameA = r.get("evidence").get(0).get("frame").asInt();
            int frameB = r.get("evidence").get(1).get("frame").asInt();
            Map<String, String> first = decoded.stream()
                    .filter(e -> Integer.parseInt(e.get("frame.number")) == frameA).findFirst().orElseThrow();
            Map<String, String> second = decoded.stream()
                    .filter(e -> Integer.parseInt(e.get("frame.number")) == frameB).findFirst().orElseThrow();
            double expectedDwell = (Double.parseDouble(second.get("frame.time_epoch"))
                    - Double.parseDouble(first.get("frame.time_epoch"))) * 1000.0;
            assertEquals(expectedDwell, r.get("request_dwell_ms").asDouble(), 1e-6);
        }
        String expectedReset = expected.stream().map(e -> e.get("f5ethtrailer.rstcausetxt"))
                .filter(s -> !s.isEmpty()).findFirst().orElseThrow();
        assertEquals(expectedReset, report.get("f5").get("resets").get(0).get("reason").asText());
        for (JsonNode s : report.get("segments")) {
            assertEquals("Unsupported translation boundary", s.get("reason").asText());
        }

        // Determinism: threads=1 vs threads=4 produces identical report text and f5_connections rows.
        try (Db db4 = new Db(Engine.open(dbFour, 4))) {
            Map<String, Object> raw4 = Engine.analyze(db4, topology, null);
            raw4.put("generated_at", "<masked>");
            assertEquals(jsonOne, ReportModel.toJson(raw4));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void vendorDeviceEventsAndPointSelectorsMatchReference() throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        Path sub = dir.resolve("pa");
        Files.createDirectories(sub);
        ProjectStore project = ProjectStore.open(sub.resolve("project"), new ProjectStore.Settings(null, 4 * GIB, 1));
        Path dropPath = sub.resolve("drop.pcap");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xA1B2C3D4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        data.writeBytes(h.array());
        byte[] frame = tcpPacket("192.0.2.1", "203.0.113.1", 50000, 443, 1040, 9000, 24,
                "xxxxxxxxxxxxxxxxxxxx".getBytes(StandardCharsets.UTF_8), 2);
        ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        r.putInt(1700000006).putInt(0).putInt(frame.length).putInt(frame.length);
        data.writeBytes(r.array());
        data.writeBytes(frame);
        Files.write(dropPath, data.toByteArray());

        String cid = CaptureIngest.ingest(project, dropPath, IngestOptions.defaults().withTshark(tshark), null, null);
        Map<String, Object> topoMap = Map.of(
                "points", List.of(Map.of("id", "drop", "label", "drop", "device", "PA", "capture_id", cid, "vendor",
                        "paloalto", "vendor_stage", "drop")),
                "forward", List.of(),
                "client_cidrs", List.of("192.0.2.0/24"));
        Topology topology = Topology.parse(JSON.readTree(PyJson.dumps(topoMap)));
        try (Db db = new Db(Engine.open(sub.resolve("project/project.duckdb"), 1))) {
            // prepare_proofs extracts the Palo Alto drop-stage proof during analysis; device_report reads it back.
            Engine.analyze(db, topology, null);
            EngineContext ctx = new EngineContext(db, topology, null);
            DeviceDrops.deviceReport(ctx);
            Map<String, Object> deviceReport = TimeEvents.of(ctx).deviceReport;
            assertEquals(1L, ((Number) deviceReport.get("vendor_device_event_count")).longValue());
            List<Db.Row> events = (List<Db.Row>) deviceReport.get("vendor_device_events");
            assertEquals(1, events.size());
            assertEquals("confirmed_device_drop", events.get(0).get("status"));
            assertEquals("PA", events.get(0).get("device"));
            List<Map<String, Object>> evidence = (List<Map<String, Object>>) events.get(0).get("evidence");
            assertNotNull(evidence);
            assertEquals(1, evidence.size());
            Map<String, Object> ref = evidence.get(0);
            assertEquals("drop.pcap", ref.get("file"));
            assertEquals(1L, ((Number) ref.get("frame")).longValue());
            assertTrue(((String) ref.get("content_filter")).contains("tcp.seq_raw == 1040"));
        }

        // Check Point pointSelector SQL and parameters.
        Topology cpTopo = Topology.parse(JSON.readTree(PyJson.dumps(Map.of(
                "points", List.of(Map.of("id", "p0", "label", "o", "device", "FW", "capture_id", cid, "vendor",
                        "checkpoint", "vendor_stage", "o", "vendor_interface", "eth0")),
                "forward", List.of("p0")))));
        Object[] sel = Preparation.pointSelector(cpTopo.points.get(0));
        assertTrue(((String) sel[0]).contains("$.stage"));
        assertTrue(((String) sel[0]).contains("fw1.interface"));
        assertEquals(List.of("o", "eth0"), sel[1]);
    }
}
