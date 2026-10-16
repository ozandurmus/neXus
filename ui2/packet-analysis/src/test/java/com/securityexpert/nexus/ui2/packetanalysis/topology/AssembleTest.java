package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Assembly end to end on small inline packet tables (the reference case-setup tests, built here as rows instead of
 * pcaps): negative cases first — equal TTLs on unaligned clocks, no SYN with two ephemeral ports, a mostly excluded
 * or empty capture, a single capture — then a two-hop chain.
 */
class AssembleTest {

    @TempDir
    Path dir;

    /** A project.duckdb with the columns the topology SQL reads. */
    private final class Case {
        private final Map<String, List<Object[]>> packets = new LinkedHashMap<>();
        private final Map<String, String> names = new LinkedHashMap<>();

        String capture(String name) {
            String id = String.format("%032x", names.size() + 1);
            names.put(id, name);
            packets.put(id, new ArrayList<>());
            return id;
        }

        void tcp(String cid, double ts, String src, String dst, int sport, int dport, int flags, int ttl, String sig) {
            tcp(cid, ts, src, dst, sport, dport, flags, ttl, sig, null);
        }

        void tcp(String cid, double ts, String src, String dst, int sport, int dport, int flags, int ttl, String sig,
                String unsupported) {
            String key = "TCP " + src + ":" + sport + ">" + dst + ":" + dport;
            String rev = "TCP " + dst + ":" + dport + ">" + src + ":" + sport;
            packets.get(cid).add(new Object[] {ts, src, dst, sport, dport, flags, ttl, sig, key, rev, unsupported});
        }

        /** Client 198.51.100.10:42000 to 203.0.113.20:443, four rounds, SYN first when {@code syn}. */
        void flow(String cid, double offset, double revOffset, int fwdTtl, int revTtl, boolean syn, int sport, int dport) {
            for (int i = 0; i < 4; i++) {
                double t = 1700000000.0 + i * 0.01;
                tcp(cid, t + offset, "198.51.100.10", "203.0.113.20", sport, dport, syn && i == 0 ? 2 : 24, fwdTtl,
                        "f" + i);
                tcp(cid, t + revOffset, "203.0.113.20", "198.51.100.10", dport, sport, syn && i == 0 ? 18 : 24, revTtl,
                        "r" + i);
            }
        }

        ProjectDb build() throws SQLException {
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + dir.resolve("project.duckdb"));
                    Statement st = c.createStatement()) {
                st.execute("CREATE TABLE settings (key VARCHAR PRIMARY KEY, value VARCHAR)");
                st.execute("CREATE TABLE captures (id VARCHAR PRIMARY KEY, path VARCHAR, name VARCHAR, identity VARCHAR,"
                        + " state VARCHAR, checkpoint BIGINT, inventory VARCHAR, error VARCHAR)");
                st.execute("CREATE TABLE packets (capture_id VARCHAR, frame BIGINT, ts DOUBLE, src VARCHAR,"
                        + " dst VARCHAR, sport INTEGER, dport INTEGER, proto VARCHAR, flags INTEGER, length INTEGER,"
                        + " wirelen INTEGER, caplen INTEGER, ttl INTEGER, signature VARCHAR, tuple_key VARCHAR,"
                        + " reverse_tuple VARCHAR, unsupported VARCHAR, PRIMARY KEY(capture_id, frame))");
                try (PreparedStatement cap = c.prepareStatement("INSERT INTO captures VALUES (?,?,?,?,?,?,?,?)");
                        PreparedStatement pkt = c.prepareStatement(
                                "INSERT INTO packets VALUES (?,?,?,?,?,?,?,'TCP',?,100,100,100,?,?,?,?,?)")) {
                    for (Map.Entry<String, String> e : names.entrySet()) {
                        List<Object[]> rows = packets.get(e.getKey());
                        cap.setString(1, e.getKey());
                        cap.setString(2, "/cases/inline/" + e.getValue());
                        cap.setString(3, e.getValue());
                        cap.setString(4, "identity");
                        cap.setString(5, "ready");
                        cap.setLong(6, 0);
                        cap.setString(7, "{\"packet_count\": " + rows.size() + ", \"observed_max_caplen\": 100}");
                        cap.setString(8, null);
                        cap.execute();
                        long frame = 1;
                        for (Object[] r : rows) {
                            pkt.setString(1, e.getKey());
                            pkt.setLong(2, frame++);
                            pkt.setDouble(3, (Double) r[0]);
                            pkt.setString(4, (String) r[1]);
                            pkt.setString(5, (String) r[2]);
                            pkt.setInt(6, (Integer) r[3]);
                            pkt.setInt(7, (Integer) r[4]);
                            pkt.setInt(8, (Integer) r[5]);
                            pkt.setInt(9, (Integer) r[6]);
                            pkt.setString(10, (String) r[7]);
                            pkt.setString(11, (String) r[8]);
                            pkt.setString(12, (String) r[9]);
                            pkt.setString(13, (String) r[10]);
                            pkt.execute();
                        }
                    }
                }
            }
            return new ProjectDb(dir);
        }
    }

    @Test
    void equalTtlOnUnalignedClocksIsAmbiguousNotGuessed() throws SQLException {
        Case k = new Case();
        String a = k.capture("tap_1.pcap");
        String b = k.capture("tap_2.pcap");
        k.flow(a, 0, 0.001, 60, 60, true, 42000, 443);
        k.flow(b, 2.5, 2.501, 60, 60, true, 42000, 443);
        Map<String, Object> res = Assemble.assembleTopology(k.build(), null);
        assertEquals("ambiguous", res.get("status"));
        assertEquals("Ambiguous path order between ['tap_1.pcap'] and ['tap_2.pcap']: equal TTLs and no shared-clock "
                + "microsecond separation.", res.get("reason"));
        Map<?, ?> topo = (Map<?, ?>) res.get("topology");
        assertEquals(List.of(), topo.get("forward"));
        assertEquals(List.of("198.51.100.10/32"), topo.get("client_cidrs"), "inferred CIDRs stay on the base");
        assertEquals("client_cidrs", ((Map<?, ?>) ((List<?>) res.get("inferences")).get(0)).get("kind"));
    }

    @Test
    void noSynAndTwoEphemeralPortsIsAmbiguousDirection() throws SQLException {
        Case k = new Case();
        String a = k.capture("hop_1.pcap");
        String b = k.capture("hop_2.pcap");
        k.flow(a, 0, 0.001, 64, 62, false, 45000, 45001);
        k.flow(b, 0.00002, 0.00098, 63, 63, false, 45000, 45001);
        Map<String, Object> res = Assemble.assembleTopology(k.build(), null);
        assertEquals("Ambiguous client direction: no TCP SYN/SYN-ACK and ports (45000, 45001) do not separate service "
                + "from ephemeral.", res.get("reason"));
        assertEquals(List.of("10.0.0.0/8"), ((Map<?, ?>) res.get("topology")).get("client_cidrs"));
    }

    @Test
    void serverPortHeuristicNamesTheServicePort() throws SQLException {
        Case k = new Case();
        String a = k.capture("hop_1.pcap");
        String b = k.capture("hop_2.pcap");
        k.flow(a, 0, 0.001, 64, 62, false, 45000, 443);
        k.flow(b, 0.00002, 0.00098, 63, 63, false, 45000, 443);
        Map<String, Object> res = Assemble.assembleTopology(k.build(), null);
        Map<?, ?> cidrs = (Map<?, ?>) ((List<?>) res.get("inferences")).get(0);
        assertEquals("server_port_heuristic", cidrs.get("method"));
        assertEquals("Client CIDR(s) 198.51.100.10/32 inferred from server-port heuristic (service port(s) [443]).",
                cidrs.get("reason"));
    }

    private Case excludedCase() {
        Case k = new Case();
        String a = k.capture("hop_1.pcap");
        String b = k.capture("hop_2.pcap");
        String x = k.capture("hop_mostly_excluded.pcap");
        k.flow(a, 0, 0.001, 64, 62, true, 43000, 443);
        k.flow(b, 0.00002, 0.00098, 63, 63, true, 43000, 443);
        k.tcp(a, 1700000000.5, "192.0.2.55", "198.51.100.99", 51000, 80, 16, 60, "noise");
        k.tcp(x, 1700000000.5, "192.0.2.55", "198.51.100.99", 51000, 80, 16, 60, "noise");
        for (int i = 0; i < 9; i++) {
            k.tcp(x, 1700000001.0 + i * 0.001, "198.51.100.10", "203.0.113.20", 43000, 443, 2, 64, "tun" + i,
                    "ERSPAN tunnel");
        }
        return k;
    }

    @Test
    void mostlyExcludedCaptureIsNamedAsTheReason() throws SQLException {
        Map<String, Object> res = Assemble.assembleTopology(excludedCase().build(), null);
        assertEquals("Capture(s) hop_mostly_excluded.pcap is mostly excluded (9 of 10 frames excluded; 1 usable TCP "
                + "frame(s) remain); path order is ambiguous.", res.get("reason"));
    }

    @Test
    void emptyCaptureStopsBeforeAnyInference() throws SQLException {
        ProjectDb project = excludedCase().build();
        try (ProjectDb.Db db = project.connect()) {
            db.execute("DELETE FROM packets WHERE capture_id=(SELECT id FROM captures WHERE name=?)",
                    List.of("hop_mostly_excluded.pcap"));
        }
        Map<String, Object> res = Assemble.assembleTopology(project, null);
        assertEquals("Capture(s) hop_mostly_excluded.pcap is empty (0 usable frames); path order is ambiguous.",
                res.get("reason"));
        assertEquals(List.of(), res.get("inferences"));
    }

    @Test
    void oneCaptureCannotFormAPath() throws SQLException {
        Case k = new Case();
        k.flow(k.capture("only.pcap"), 0, 0.001, 64, 62, true, 42000, 443);
        assertEquals("At least two ready captures are required to assemble a path.",
                Assemble.assembleTopology(k.build(), null).get("reason"));
    }

    @Test
    void twoHopChainIsOrderedByTtlWithASharedClock() throws SQLException {
        Case k = new Case();
        String a = k.capture("hop_1.pcap");
        String b = k.capture("hop_2.pcap");
        k.flow(a, 0, 0.001, 64, 62, true, 43000, 443);
        k.flow(b, 0.00002, 0.00098, 63, 63, true, 43000, 443);
        ProjectDb project = k.build();
        Map<String, Object> suggest = Suggest.suggestTopology(project);
        List<?> items = (List<?>) suggest.get("items");
        assertEquals(1, items.size(), "a shared clock, but no stage pair (TTL steps are +1/-1)");
        assertEquals("shared_clock", ((Map<?, ?>) items.get(0)).get("kind"));

        Map<String, Object> res = Assemble.assembleTopology(project, null);
        assertEquals("ready", res.get("status"));
        assertEquals(Assemble.BADGE_READY, res.get("badge"));
        Map<?, ?> topo = (Map<?, ?>) res.get("topology");
        assertEquals(List.of("hop_1", "hop_2"), topo.get("forward"));
        assertEquals(List.of("hop_2", "hop_1"), topo.get("reverse"));
        List<?> points = (List<?>) topo.get("points");
        Map<?, ?> second = (Map<?, ?>) points.get(1);
        assertEquals("appliance", second.get("clock_domain"));
        assertEquals(Py.i(220), second.get("x"), "assigned coordinates stay ints");
        List<?> inferences = (List<?>) res.get("inferences");
        Map<?, ?> path = (Map<?, ?>) inferences.get(inferences.size() - 1);
        assertEquals("path_order", path.get("kind"));
        assertEquals(Py.dict("hop_1", Py.f(64), "hop_2", Py.f(63)), ((Map<?, ?>) path.get("metrics")).get("forward_ttls"));

        // assemble_and_analyze + confirm write the reference settings keys
        Map<String, Object> out = Assemble.assembleAndAnalyze(project, null, (p, t) -> new LinkedHashMap<>());
        assertFalse(Py.truthy(((Map<?, ?>) out.get("suggested_topology")).get("confirmed")));
        Map<String, Object> confirmed = Assemble.confirmSuggestedTopology(project, null);
        assertTrue(Py.truthy(confirmed.get("confirmed")));
        try (ProjectDb.Db db = project.connect()) {
            assertNull(db.get("unconfirmed_topology", "absent"));
            assertTrue(Py.truthy(((Map<?, ?>) ((Map<?, ?>) db.get("report", null)).get("suggested_topology"))
                    .get("confirmed")));
        }
    }

    @Test
    void confirmWithoutAPendingSuggestionFails() throws SQLException {
        Case k = new Case();
        k.capture("a.pcap");
        ProjectDb project = k.build();
        assertEquals("No assembled suggested topology is pending confirmation",
                assertThrows(IllegalArgumentException.class, () -> Assemble.confirmSuggestedTopology(project, null))
                        .getMessage());
    }
}
