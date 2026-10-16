package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestOptions;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of reference {@code tests/test_dns.py} (negative/unknown outcomes first, then positive outcomes, native DNS
 * tuple scope latency and {@link Dns#page}, and multi-hop/multi-thread determinism).
 */
class DnsTest {

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

    private static String status(Map<String, Object> changes) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("complete", true);
        row.put("unique_response", false);
        row.put("candidates", 0);
        row.put("ambiguous", false);
        row.put("covered", true);
        row.put("query_hop", null);
        row.put("response_hop", null);
        row.put("far_responses", 0);
        row.put("resolver_seen", true);
        row.put("resolver_covered", true);
        row.putAll(changes);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            List<String> cols = new ArrayList<>();
            List<String> marks = new ArrayList<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                String type = v instanceof Boolean ? "BOOLEAN" : k.endsWith("hop") ? "VARCHAR" : "INTEGER";
                cols.add(k + " " + type);
                marks.add("?");
            }
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE d(" + String.join(",", cols) + ")");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO d VALUES(" + String.join(",", marks) + ")")) {
                int i = 1;
                for (Object v : row.values()) {
                    ps.setObject(i++, v);
                }
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT " + Dns.outcome() + " FROM d")) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
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

    private static byte[] udpDatagram(String src, String dst, int sport, int dport, byte[] payload, int ipid, int ttl)
            throws IOException {
        byte[] a = InetAddress.getByName(src).getAddress();
        byte[] b = InetAddress.getByName(dst).getAddress();
        ByteBuffer udp = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        udp.putShort((short) sport).putShort((short) dport).putShort((short) (8 + payload.length)).putShort((short) 0);
        udp.put(payload);
        byte[] udpBytes = udp.array();

        ByteBuffer ip = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        ip.put((byte) 0x45).put((byte) 0).putShort((short) (20 + udpBytes.length)).putShort((short) (ipid & 0xFFFF))
                .putShort((short) 0x4000).put((byte) ttl).put((byte) 17).putShort((short) 0).put(a).put(b);
        byte[] ipBytes = ip.array();
        int sum = checksum(ipBytes);
        ipBytes[10] = (byte) (sum >>> 8);
        ipBytes[11] = (byte) sum;

        ByteBuffer frame = ByteBuffer.allocate(14 + ipBytes.length + udpBytes.length);
        frame.put(new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA,
                (byte) 0xBB, 0x08, 0x00});
        frame.put(ipBytes).put(udpBytes);
        return frame.array();
    }

    private static byte[] dnsPayload(int id, int flags, byte[] qname, int qtype) {
        ByteBuffer b = ByteBuffer.allocate(12 + qname.length + 4).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) id).putShort((short) flags).putShort((short) 1).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0);
        b.put(qname);
        b.putShort((short) qtype).putShort((short) 1);
        return b.array();
    }

    record TimedFrame(double ts, byte[] frame) {
    }

    private static void writePcap(Path path, List<TimedFrame> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xA1B2C3D4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        out.writeBytes(h.array());
        List<TimedFrame> sorted = new ArrayList<>(frames);
        sorted.sort(java.util.Comparator.comparingDouble(TimedFrame::ts));
        for (TimedFrame tf : sorted) {
            long sec = (long) tf.ts();
            long usec = Math.round((tf.ts() - sec) * 1e6);
            if (usec == 1_000_000L) {
                sec += 1;
                usec = 0;
            }
            ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            r.putInt((int) sec).putInt((int) usec).putInt(tf.frame().length).putInt(tf.frame().length);
            out.writeBytes(r.array());
            out.writeBytes(tf.frame());
        }
        Files.write(path, out.toByteArray());
    }

    @Test
    void dnsUnknownBeforePositiveAttribution() throws Exception {
        assertEquals("unknown", status(Map.of("covered", false)));
        assertEquals("unknown", status(Map.of("resolver_seen", false)));
        assertEquals("unknown", status(Map.of("complete", false)));
        assertEquals("unknown", status(Map.of("ambiguous", true, "candidates", 2)));
        assertEquals("unknown", status(Map.of("far_responses", 1)));
        assertEquals("unknown", status(Map.of("far_responses", 2, "response_hop", "reverse:lb_out:lb_in")));
    }

    @Test
    void dnsResolverAndNetworkAreDifferent() throws Exception {
        assertEquals("answered", status(Map.of("unique_response", true, "candidates", 1)));
        assertEquals("resolver_timeout", status(Map.of()));
        assertEquals("query_disappeared", status(Map.of("query_hop", "forward:fw_in:fw_out")));
        assertEquals("response_disappeared",
                status(Map.of("far_responses", 1, "response_hop", "reverse:fw_out:fw_in")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeDnsTupleScopeLatencyAndPage() throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        byte[] name = "\u0004same\u0007example\u0004test\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] queryBody = dnsPayload(77, 0x0100, name, 1);
        byte[] respBody = dnsPayload(77, 0x8180, name, 1);

        List<TimedFrame> frames = List.of(
                new TimedFrame(1700000000.1, udpDatagram("192.0.2.1", "203.0.113.53", 40000, 53, queryBody, 0, 64)),
                new TimedFrame(1700000000.12, udpDatagram("192.0.2.2", "203.0.113.53", 40000, 53, queryBody, 0, 64)),
                new TimedFrame(1700000000.15, udpDatagram("203.0.113.53", "192.0.2.1", 53, 40000, respBody, 0, 64)),
                new TimedFrame(1700000004.0, udpDatagram("192.0.2.1", "203.0.113.53", 45000, 45001,
                        "noop".getBytes(StandardCharsets.UTF_8), 0, 64)));

        Path p0 = dir.resolve("point0.pcap");
        Path p1 = dir.resolve("point1.pcap");
        writePcap(p0, frames);
        writePcap(p1, frames);

        ProjectStore project = ProjectStore.open(dir.resolve("project"), new ProjectStore.Settings(null, 4 * GIB, 1));
        String cid0 = CaptureIngest.ingest(project, p0, IngestOptions.defaults().withTshark(tshark), null, null);
        String cid1 = CaptureIngest.ingest(project, p1, IngestOptions.defaults().withTshark(tshark), null, null);

        Map<String, Object> topologyMap = Map.of(
                "points", List.of(
                        Map.of("id", "p0", "label", "point0", "device", "endpoint0", "kind", "Client", "capture_id", cid0),
                        Map.of("id", "p1", "label", "point1", "device", "endpoint1", "kind", "Server", "capture_id", cid1)),
                "forward", List.of("p0", "p1"));
        Topology topology = Topology.parse(JSON.readTree(PyJson.dumps(topologyMap)));

        Path dbPath = dir.resolve("project/project.duckdb");
        try (Db db = new Db(Engine.open(dbPath, 1))) {
            Map<String, Object> report = Engine.analyze(db, topology, null);
            Map<String, Object> result = Dns.page(db, topology, report, "p0", null, null, null, 0, 200);
            assertEquals(2L, ((Number) result.get("total")).longValue());
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
            Map<String, Object> first = items.stream().filter(q -> "192.0.2.1".equals(q.get("src"))).findFirst()
                    .orElseThrow();
            Map<String, Object> second = items.stream().filter(q -> "192.0.2.2".equals(q.get("src"))).findFirst()
                    .orElseThrow();
            assertEquals("answered", first.get("status"));
            assertEquals(50.0, ((Number) first.get("latency_ms")).doubleValue(), 0.001);
            assertNull(second.get("latency_ms"));
            assertNotEquals("answered", second.get("status"));
            for (Map<String, Object> q : items) {
                List<?> ev = (List<?>) q.get("evidence");
                assertNotNull(ev);
                assertFalse(ev.isEmpty());
            }
            Map<String, Object> all = Dns.page(db, topology, report, null, null, null, null, 0, 1000);
            assertEquals(4L, ((Number) all.get("total")).longValue());
            assertTrue( ((String) result.get("tooltip")).contains("query-to-response latency"));
        }

        // Determinism: threads=1 vs threads=4 on the same DNS input produces identical DNS tables and page JSON.
        Path dbFour = dir.resolve("four.duckdb");
        Files.copy(dbPath, dbFour);
        String pageOne;
        String pageFour;
        try (Db db1 = new Db(Engine.open(dbPath, 1)); Db db4 = new Db(Engine.open(dbFour, 4))) {
            db1.exec("DELETE FROM settings WHERE key='dns_generation'");
            db4.exec("DELETE FROM settings WHERE key='dns_generation'");
            Map<String, Object> rep1 = Engine.analyze(db1, topology, null);
            Map<String, Object> rep4 = Engine.analyze(db4, topology, null);
            rep1.put("generated_at", "<masked>");
            rep4.put("generated_at", "<masked>");
            pageOne = PyJsonWriter.dumps(Dns.page(db1, topology, rep1, null, null, null, null, 0, 200), true);
            pageFour = PyJsonWriter.dumps(Dns.page(db4, topology, rep4, null, null, null, null, 0, 200), true);
            assertEquals(pageOne, pageFour);
            for (String table : List.of("dns_observations", "dns_queries", "dns_local_pairs", "dns_signals",
                    "dns_transactions")) {
                assertEquals(db1.rows("SELECT * FROM " + table + " ORDER BY ALL"),
                        db4.rows("SELECT * FROM " + table + " ORDER BY ALL"), table);
            }
        }
    }
}
