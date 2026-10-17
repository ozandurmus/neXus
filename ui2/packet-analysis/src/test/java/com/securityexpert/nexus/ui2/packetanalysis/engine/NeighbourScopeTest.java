package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Neighbour capture scope ({@link NeighbourScope}, an intentional divergence from the reference): one capture point
 * carries flows its neighbour never captured. Those flows must not become loss, capture miss or delay on the hop; a
 * flow the neighbour did capture keeps its genuine loss, and a flow with no proof of crossing the neighbour (a blocked
 * handshake, one-way traffic) stays in scope. Inputs: the frozen {@code healthy} fixture (one TCP flow across
 * p0 -> p4, client 10.0.0.10:50000) with extra flows written into the packets table before the run.
 */
class NeighbourScopeTest {
    @TempDir
    Path dir;

    private JsonNode run(String fixture, String sql) throws Exception {
        return run(fixture, sql, t -> { });
    }

    private JsonNode run(String fixture, String sql, Consumer<ObjectNode> topology) throws Exception {
        JsonNode f = Fixtures.load(fixture);
        Path project = Fixtures.project(f, dir, fixture + System.nanoTime() + ".duckdb");
        if (sql != null) {
            try (var c = Engine.open(project, 1); Statement st = c.createStatement()) {
                for (String s : sql.split(";")) {
                    if (!s.isBlank()) {
                        st.execute(s);
                    }
                }
            }
        }
        ObjectNode t = Fixtures.topology(f);
        topology.accept(t);
        return Fixtures.parse(Fixtures.run(project, t, 1));
    }

    private static String capture(String fixture, String point) {
        for (JsonNode p : Fixtures.load(fixture).get("topology").get("points")) {
            if (p.get("id").asText().equals(point)) {
                return p.get("capture_id").asText();
            }
        }
        throw new AssertionError("no point " + point);
    }

    private static String extraFlow(String where, String directionFilter) {
        return extraFlow(where, directionFilter, 1);
    }

    /** A copy of the healthy flow at the given points as a new flow (client port 50000+n), optionally one direction. */
    private static String extraFlow(String where, String directionFilter, int n) {
        int port = 50000 + n;
        return "INSERT INTO packets SELECT * REPLACE(frame+" + 100000 * n + " AS frame,"
                + " CASE WHEN sport=50000 THEN " + port + " ELSE sport END AS sport,"
                + " CASE WHEN dport=50000 THEN " + port + " ELSE dport END AS dport,"
                + " " + retuple("tuple_key", port) + " AS tuple_key,"
                + " " + retuple("reverse_tuple", port) + " AS reverse_tuple,"
                + " stream+" + 1000 * n + " AS stream, md5(coalesce(frame_hash,'')||'extra" + n + "') AS frame_hash)"
                + " FROM packets WHERE (sport=50000 OR dport=50000) AND " + where + (directionFilter == null ? "" : " AND " + directionFilter) + ";";
    }

    /** The client port inside a tuple key ({@code ["TCP","10.0.0.10",50000,...]} or {@code [...,50000]}). */
    private static String retuple(String column, int port) {
        return "replace(replace(" + column + ",',50000,','," + port + ",'),',50000]','," + port + "]')";
    }

    private static String at(String... points) {
        List<String> ids = new ArrayList<>();
        for (String p : points) {
            ids.add("'" + capture("healthy", p) + "'");
        }
        return "capture_id IN (" + String.join(",", ids) + ")";
    }

    private static JsonNode segment(JsonNode report, String id) {
        for (JsonNode s : report.get("segments")) {
            if (s.get("id").asText().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no segment " + id);
    }

    private static JsonNode scope(JsonNode report, String point) {
        for (JsonNode s : report.get("analysis_scope")) {
            if (s.get("point").asText().equals(point)) {
                return s;
            }
        }
        throw new AssertionError("no analysis_scope " + point);
    }

    private static boolean limited(JsonNode report) {
        for (JsonNode l : report.get("limitations")) {
            if (l.asText().equals(NeighbourScope.LIMITATION)) {
                return true;
            }
        }
        return false;
    }

    /** Segments without the neighbour-scope keys and notes, for comparison with a run that has no extra flow. */
    private static List<JsonNode> hops(JsonNode report) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode s : report.get("segments")) {
            ObjectNode copy = s.deepCopy();
            copy.remove("scope_excluded_flows");
            copy.remove("scope_excluded_packets");
            var notes = Fixtures.JSON.createArrayNode();
            copy.get("quality_notes").forEach(n -> {
                if (!n.asText().startsWith("Flows not observed at ")) {
                    notes.add(n);
                }
            });
            copy.set("quality_notes", notes);
            out.add(copy);
        }
        return out;
    }

    /**
     * Every segment equal to the baseline's, key by key, apart from the neighbour-scope keys and notes. With
     * {@code endpoints == false} the {@code endpoint_quality} key is not compared: it describes each endpoint's
     * capture eligibility over every packet it holds (a flow kept in the index scope counts there), not hop accounting.
     */
    private static void assertSameHops(JsonNode baseline, JsonNode report, boolean endpoints) {
        List<JsonNode> expected = hops(baseline);
        List<JsonNode> actual = hops(report);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            String id = expected.get(i).get("id").asText();
            for (var it = expected.get(i).fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!endpoints && key.equals("endpoint_quality")) {
                    continue;
                }
                assertEquals(expected.get(i).get(key), actual.get(i).get(key), id + "." + key);
            }
            assertEquals(expected.get(i).size(), actual.get(i).size(), id);
        }
    }

    private static long events(JsonNode report) {
        long n = 0;
        for (JsonNode s : report.get("segments")) {
            for (JsonNode c : s.get("classes")) {
                n += c.asLong();
            }
        }
        return n;
    }

    @Test
    void oneWayFlowAtOnePointHasNoProofAndStaysInScope() throws Exception {
        // Negative first: server -> client packets only at the server point, nothing the client sent. Nothing proves
        // the flow crossed p3, so it is assessed as before (and its packets are missing at p3).
        JsonNode report = run("healthy", extraFlow(at("p4"), "src<>'10.0.0.10'"));
        assertEquals(0, scope(report, "p4").get("excluded_single_point_flows").asLong());
        JsonNode hop = segment(report, "reverse:p4:p3");
        assertFalse(hop.has("scope_excluded_flows"));
        assertTrue(hop.get("classes").size() > 0, "the unproven flow's packets are still assessed");
        assertFalse(limited(report));
    }

    @Test
    void blockedHandshakeIsStillReported() throws Exception {
        // det_a_syn_blocked: SYNs reach the firewall and stop; no reply crosses back, so the flow stays in scope.
        JsonNode report = run("det_a_syn_blocked", null);
        boolean blocked = false;
        for (JsonNode f : report.get("findings")) {
            blocked |= f.get("type").asText().equals("handshake_blocked");
        }
        assertTrue(blocked);
        for (JsonNode s : report.get("segments")) {
            assertFalse(s.has("scope_excluded_flows"), s.get("id").asText());
        }
        assertFalse(limited(report));
    }

    @Test
    void unconfirmedNatHopNeverComparesFlowIdentity() throws Exception {
        // nat: the pre-NAT flow is "absent" at the post-NAT point under its own tuple, and its replies at p1 crossed
        // p2. Without a confirmed mapping the identity across the NAT hop is unknown, so nothing is excluded.
        JsonNode report = run("nat", null);
        assertEquals("NAT mapping awaits confirmation", segment(report, "forward:p1:p2").get("reason").asText());
        for (JsonNode s : report.get("segments")) {
            assertFalse(s.has("scope_excluded_flows"), s.get("id").asText());
        }
        for (JsonNode s : report.get("analysis_scope")) {
            assertEquals(0, s.get("excluded_single_point_flows").asLong());
        }
    }

    @Test
    void flowsOnlyAtTheServerPointAreExcludedSinglePointFlowsNotLoss() throws Exception {
        // The real-case shape: the server capture carries a flow its neighbour's capture filter left out. The
        // client's packets at the server crossed every point, so the flow is out of scope, not loss.
        JsonNode baseline = run("healthy", null);
        JsonNode report = run("healthy", extraFlow(at("p4"), null));
        JsonNode p4 = scope(report, "p4");
        assertEquals(1, p4.get("excluded_single_point_flows").asLong());
        assertEquals(p4.get("input_frames").asLong() - p4.get("retained_frames").asLong(),
                p4.get("excluded_single_point_frames").asLong());
        assertTrue(p4.get("excluded_single_point_frames").asLong() > 0);
        for (JsonNode q : report.get("quality")) {
            if (q.get("point").asText().equals("p4")) {
                assertEquals(1, q.get("index_scope").get("excluded_flows").asLong());
            }
        }
        assertSameHops(baseline, report, true);
        assertEquals(baseline.get("findings"), report.get("findings"));
        assertEquals(baseline.get("verdict"), report.get("verdict"));
        assertEquals(baseline.get("flow_count"), report.get("flow_count"));
        assertTrue(limited(report));
    }

    @Test
    void flowMissingOnlyAtTheNeighbourIsOutOfThatHopsScope() throws Exception {
        // Captured at the client and at the server, never at p1 .. p3: a multi-point flow, so the index scope keeps
        // it, but the hops into the points that never saw it must not count it as missing.
        JsonNode baseline = run("healthy", null);
        JsonNode report = run("healthy", extraFlow(at("p0", "p4"), null));
        for (JsonNode s : report.get("analysis_scope")) {
            assertEquals(0, s.get("excluded_single_point_flows").asLong());
        }
        for (String id : List.of("forward:p0:p1", "reverse:p4:p3")) {
            JsonNode hop = segment(report, id);
            assertEquals(1, hop.get("scope_excluded_flows").asLong(), id);
            assertTrue(hop.get("scope_excluded_packets").asLong() > 0, id);
            assertTrue(hop.get("quality_notes").toString().contains("outside its capture scope"), id);
        }
        assertFalse(segment(report, "forward:p1:p2").has("scope_excluded_flows"));
        assertSameHops(baseline, report, false);
        assertEquals(baseline.get("findings"), report.get("findings"));
        assertEquals(baseline.get("verdict"), report.get("verdict"));
        assertTrue(limited(report));
    }

    @Test
    void genuineLossOnTheSharedFlowIsStillDetected() throws Exception {
        // One client data packet of the shared flow never reaches p2 (frame 10 at p2 removed); the extra flows at the
        // server and at client + server change nothing about that loss.
        String drop = "DELETE FROM packets WHERE capture_id='" + capture("healthy", "p2") + "' AND frame=10;";
        JsonNode lossOnly = run("healthy", drop);
        long lossEvents = events(lossOnly);
        assertTrue(lossEvents > 0, "the dropped packet is an event on forward:p1:p2");
        assertTrue(segment(lossOnly, "forward:p1:p2").get("classes").size() > 0);
        JsonNode report = run("healthy", drop + extraFlow(at("p4"), null, 1) + extraFlow(at("p0", "p4"), null, 2));
        assertSameHops(lossOnly, report, false);
        assertEquals(lossOnly.get("findings"), report.get("findings"));
        assertEquals(lossEvents, events(report));
        assertEquals(1, scope(report, "p4").get("excluded_single_point_flows").asLong());
        assertEquals(1, segment(report, "forward:p0:p1").get("scope_excluded_flows").asLong());
    }
}
