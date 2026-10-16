package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ports of the reference unit tests for the path detectors (tests/test_integrity.py, test_proxy_pairing.py,
 * test_proxy_resets.py, test_proxy_integration.py dwell, test_fragments.py status), negative cases first.
 */
class PathDetectorsTest {

    private static Db memory() throws Exception {
        return new Db(DriverManager.getConnection("jdbc:duckdb:"));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    // ------------------------------------------------------------------ integrity (test_integrity.py)

    @Test
    void originPatternsAreNotAuthentication() {
        Map<String, Object> packet = map("src", "10.0.0.1", "ttl", 64L, "ipid", 0L);
        assertEquals("unknown", Integrity.endpointPattern(List.of(packet, packet), packet).get("status"));
        Map<String, Object> result = Integrity.endpointPattern(List.of(packet, packet, packet), packet);
        assertEquals("constant", result.get("ip_id_pattern"));
        assertTrue(((String) result.get("ip_id_note")).contains("cannot authenticate"));
        Map<String, Object> v6 = new LinkedHashMap<>(packet);
        v6.put("src", "2001:db8::1");
        assertNull(Integrity.endpointPattern(List.of(packet, packet, packet), v6).get("candidate_ip_id"));
    }

    @Test
    void longRepeatStreamUsesAdjacentEvidence() throws Exception {
        try (Db db = memory()) {
            db.exec("""
                    CREATE TABLE obs AS SELECT 'p' AS point,i AS frame,'flow' AS canon,
                    'signature' AS signature,'payload' AS payload_hash,'bytes'||(i//10000)::VARCHAR AS frame_hash,
                    1700000000.0+i*.0002 AS ts,64-(i//10000)::INTEGER AS ttl,true AS eligible,
                    'TCP' AS proto,80 AS length,134 AS caplen,134 AS wirelen FROM range(1,20001) t(i)""");
            db.exec("CREATE VIEW integrity_obs AS SELECT * FROM obs");
            assertTrue(Integrity.duplicatePairs(db, "p", 500, "identical").isEmpty());
            Row identical = Integrity.duplicatePairs(db, "p", 20, "identical").get(0);
            assertEquals(List.of(1L, 2L), frames(identical));
            Row loop = Integrity.duplicatePairs(db, "p", 20, "loop").get(0);
            assertEquals(List.of(9999L, 10000L), frames(loop));
            assertThrows(IllegalArgumentException.class, () -> Integrity.duplicatePairs(db, "p", 20, "other"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Long> frames(Row pair) {
        return List.of((Long) ((Map<String, Object>) pair.get("first_packet")).get("frame"),
                (Long) ((Map<String, Object>) pair.get("repeat_packet")).get("frame"));
    }

    private static Topology fourHops() {
        return Topology.parse(Fixtures.parse("""
                {"points":[{"id":"p1","label":"Hop 1","device":"D1","capture_id":"c1"},
                {"id":"p2","label":"Hop 2","device":"D2","capture_id":"c2"},
                {"id":"p3","label":"Hop 3","device":"D3","capture_id":"c3"},
                {"id":"p4","label":"Hop 4","device":"D4","capture_id":"c4"}],
                "forward":["p1","p2","p3","p4"],"reverse":["p4","p3","p2","p1"]}"""));
    }

    private static Map<String, Object> finding(String id, String type, String hop, double t0, double t1, long count, String point,
            long frame) {
        Map<String, Object> ref = map("point", point, "frame", frame);
        return map("id", id, "type", type, "hop", hop, "direction", "forward", "time_range", new ArrayList<>(List.of(t0, t1)),
                "metrics", map("count", count), "summary", type + " summary", "headline", type + " headline",
                "evidence", new ArrayList<>(List.of(ref)));
    }

    @Test
    void downstreamPacketsUpstreamOfOrBeforeACaptureMissStaySeparate() {
        List<Map<String, Object>> findings = new ArrayList<>(List.of(
                finding("forward:p2:p3:capture_miss", "capture_miss", "forward:p2:p3", 20.0, 25.0, 2, "p2", 10),
                finding("integrity:forward:p1:p2:downstream_packet:5:0", "downstream_packet", "forward:p1:p2", 21.0, 21.0, 1, "p2", 5),
                finding("integrity:forward:p3:p4:downstream_packet:6:1", "downstream_packet", "forward:p3:p4", 10.0, 10.0, 1, "p4", 6)));
        Integrity.groupDownstreamFindings(findings, fourHops());
        assertEquals(2, findings.stream().filter(f -> f.get("type").equals("downstream_packet")).count());
        assertFalse(findings.get(0).containsKey("downstream_summary"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void downstreamPacketsFollowingAnUpstreamCaptureMissAreGroupedUnderIt() {
        List<Map<String, Object>> findings = new ArrayList<>(List.of(
                finding("forward:p1:p2:capture_miss", "capture_miss", "forward:p1:p2", 10.0, 15.0, 3, "p1", 1),
                finding("integrity:forward:p2:p3:downstream_packet:11:0", "downstream_packet", "forward:p2:p3", 11.0, 11.0, 1, "p3", 11),
                finding("integrity:forward:p3:p4:downstream_packet:12:1", "downstream_packet", "forward:p3:p4", 12.0, 12.0, 1, "p4", 12)));
        Integrity.groupDownstreamFindings(findings, fourHops());
        assertTrue(findings.stream().noneMatch(f -> f.get("type").equals("downstream_packet")));
        Map<String, Object> miss = findings.get(0);
        assertEquals(2L, miss.get("downstream_count"));
        assertEquals(2L, ((Map<String, Object>) miss.get("metrics")).get("downstream_packet_count"));
        assertTrue(((String) miss.get("downstream_summary"))
                .contains("2 downstream-only packet observation(s) at Hop 3, Hop 4 follow this capture miss"));
        assertTrue(((String) miss.get("summary")).contains((String) miss.get("downstream_summary")));
        assertEquals(3, ((List<Object>) miss.get("evidence")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void qualityLimitedDownstreamPacketsMergeIntoOneQualityLine() {
        Map<String, Object> a = finding("integrity:forward:p1:p2:downstream_packet:5:0", "downstream_packet", "forward:p1:p2", 21.0, 21.0, 1,
                "p2", 5);
        Map<String, Object> b = finding("integrity:forward:p1:p2:downstream_packet:6:1", "downstream_packet", "forward:p1:p2", 19.0, 19.0, 1,
                "p2", 6);
        Map<String, Object> clean = finding("integrity:forward:p2:p3:downstream_packet:7:2", "downstream_packet", "forward:p2:p3", 22.0, 22.0,
                1, "p3", 7);
        for (Map<String, Object> f : List.of(a, b)) {
            ((Map<String, Object>) f.get("metrics")).put("quality_reason", "Coverage does not bracket the complete matching window");
        }
        List<Map<String, Object>> findings = new ArrayList<>(List.of(a, b, clean));
        Integrity.groupDownstreamFindings(findings, fourHops());
        assertEquals(2, findings.size());
        Map<String, Object> carrier = findings.get(0);
        assertEquals("quality", carrier.get("severity"));
        assertEquals(2L, ((Map<String, Object>) carrier.get("metrics")).get("count"));
        assertEquals(List.of(19.0, 21.0), carrier.get("time_range"));
        assertTrue(((String) carrier.get("id")).endsWith(":merged"));
        assertTrue(((String) carrier.get("summary")).contains("not evidence of injection"));
        assertEquals(clean, findings.get(1));
    }

    // ------------------------------------------------------------------ proxy pairing (test_proxy_pairing.py)

    private static List<List<Map<String, Object>>> messages(String location) {
        java.util.function.BiFunction<Object[], List<Object>, Map<String, Object>> row = (v, xff) -> map("id", location + ":" + v[0],
                "flow", v[1], "origin_ip", v[2], "line", "GET /shared HTTP/1.1", "host", "same.example", "xff", new ArrayList<>(xff),
                "sni", "same.example", "start", v[3], "complete", (Double) v[3] + 0.003, "ready", true, "kind", "http");
        List<Map<String, Object>> clients = new ArrayList<>(List.of(row.apply(new Object[] {"a", "client-a", "10.1.0.1", 1.137}, List.of()),
                row.apply(new Object[] {"b", "client-b", "10.1.0.2", 1.151}, List.of())));
        List<Map<String, Object>> servers = new ArrayList<>(List.of(
                row.apply(new Object[] {"sb", "pooled", "172.16.0.1", 1.170}, List.of("10.1.0.2")),
                row.apply(new Object[] {"sa", "pooled", "172.16.0.1", 1.240}, List.of("10.1.0.1"))));
        return List.of(clients, servers);
    }

    interface Engine2 {
        List<Map<String, Object>> pair(List<Map<String, Object>> c, List<Map<String, Object>> s, double window, PyNum u, boolean declared)
                throws Exception;
    }

    private static void contract(Engine2 engine, String location) throws Exception {
        List<List<Map<String, Object>>> m = messages(location);
        List<Map<String, Object>> found = engine.pair(m.get(0), m.get(1), 2, new PyInt(0), !location.equals("link"));
        List<Map<String, Object>> matched = found.stream().filter(p -> "matched".equals(p.get("status"))).toList();
        if (location.equals("link")) {
            assertTrue(matched.isEmpty());
            assertTrue(found.stream().allMatch(p -> Evidence.truthy(p.get("reason"))));
            return;
        }
        Set<List<Object>> pairs = new HashSet<>();
        for (Map<String, Object> p : matched) {
            pairs.add(List.of(p.get("client_id"), p.get("server_id")));
        }
        assertEquals(Set.of(List.of(location + ":a", location + ":sa"), List.of(location + ":b", location + ":sb")), pairs);
    }

    @ParameterizedTest
    @CsvSource({"capture_miss,Proxy A", "missing_point,Proxy A", "clock,Proxy A", "same_leg,Proxy A", "contradictory_identity,Proxy A",
        "ambiguous,Proxy A", "streaming,Proxy A", "capture_miss,link", "missing_point,link", "clock,link", "same_leg,link",
        "contradictory_identity,link", "ambiguous,link", "streaming,link", "ambiguous,Proxy B", "same_leg,Proxy B"})
    void negativePairingFirst(String negative, String location) throws Exception {
        List<List<Map<String, Object>>> m = messages(location);
        List<Map<String, Object>> clients = m.get(0);
        List<Map<String, Object>> servers = m.get(1);
        PyNum uncertainty = new PyInt(0);
        switch (negative) {
            case "capture_miss" -> clients = List.of();
            case "missing_point" -> servers = List.of();
            case "clock" -> uncertainty = null;
            case "same_leg" -> {
                for (Map<String, Object> s : servers) {
                    s.put("flow", clients.get(0).get("flow"));
                }
                clients = clients.subList(0, 1);
            }
            case "contradictory_identity" -> servers.forEach(s -> s.put("line", "GET /different HTTP/1.1"));
            case "ambiguous" -> servers.forEach(s -> {
                s.put("start", 1.2);
                s.put("complete", 1.203);
                s.put("xff", new ArrayList<>());
            });
            case "streaming" -> clients.forEach(c -> {
                c.put("ready", false);
                c.put("reason", "Request byte boundary unavailable");
            });
            default -> throw new IllegalArgumentException(negative);
        }
        try (Db db = memory()) {
            List<Map<String, Object>> found = Proxies.pairRequests(clients, servers, 2, uncertainty, !location.equals("link"), db);
            assertFalse(found.isEmpty());
            assertTrue(found.stream().allMatch(p -> "unknown".equals(p.get("status")) && Evidence.truthy(p.get("reason"))), found::toString);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Proxy A", "Proxy B", "link"})
    void requestPoolingAndXffBeatNearestTime(String location) throws Exception {
        try (Db db = memory()) {
            contract((c, s, w, u, d) -> Proxies.pairRequests(c, s, w, u, d, db), location);
        }
    }

    /** Naive-implementation check: pairing each client with the nearest-in-time server fails the contract. */
    @ParameterizedTest
    @ValueSource(strings = {"Proxy A", "Proxy B", "link"})
    void naiveNearestOnlyIsRejected(String location) {
        Engine2 nearest = (clients, servers, w, u, d) -> {
            List<Map<String, Object>> available = new ArrayList<>(servers);
            List<Map<String, Object>> found = new ArrayList<>();
            for (Map<String, Object> c : clients) {
                Map<String, Object> best = available.stream().min((x, y) -> Double.compare(
                        Math.abs((Double) x.get("start") - (Double) c.get("start")),
                        Math.abs((Double) y.get("start") - (Double) c.get("start")))).orElseThrow();
                available.remove(best);
                found.add(map("status", "matched", "client_id", c.get("id"), "server_id", best.get("id")));
            }
            return found;
        };
        assertThrows(AssertionError.class, () -> contract(nearest, location));
    }

    @Test
    void sniOnlyIsExplicitTlsSetupNotAnApplicationRequest() throws Exception {
        List<List<Map<String, Object>>> m = messages("Proxy B");
        for (List<Map<String, Object>> side : m) {
            for (Map<String, Object> r : side) {
                r.put("line", null);
                r.put("host", null);
                r.put("xff", new ArrayList<>());
                r.put("kind", "tls_setup");
            }
        }
        try (Db db = memory()) {
            Map<String, Object> found = Proxies.pairRequests(m.get(0).subList(0, 1), m.get(1).subList(0, 1), 2, new PyInt(0), true, db).get(0);
            assertEquals("matched", found.get("status"));
            assertEquals("TLS SNI", found.get("evidence_rank"));
        }
    }

    // ------------------------------------------------------------------ proxy resets and dwell

    @ParameterizedTest
    @ValueSource(strings = {"clock", "coverage", "uncaptured_trigger", "ambiguity", "multiple_triggers"})
    void negativeResetFirst(String negative) {
        Map<String, Object> output = map("corrected", 10.3);
        Map<String, Object> trigger = map("corrected", 10.2);
        String[] r = Proxies.classify(output, negative.equals("multiple_triggers") ? List.of(trigger, trigger) : List.of(),
                negative.equals("clock") ? null : new PyInt(0), !negative.equals("coverage"), negative.equals("uncaptured_trigger"),
                negative.equals("ambiguity"));
        assertEquals("unknown", r[0]);
        assertNotNull(r[1]);
    }

    @Test
    void resetPositiveClassification() {
        String[] propagated = Proxies.classify(map("corrected", 10.3), List.of(map("corrected", 10.2)), new PyInt(0), true, false, false);
        assertEquals("propagated", propagated[0]);
        assertNull(propagated[1]);
        assertEquals("originated", Proxies.classify(map("corrected", 10.3), List.of(), new PyInt(0), true, false, false)[0]);
    }

    @Test
    void dwellNeverInventsMissingClockOrStreamingDuration() {
        Object[][] cases = {{1.0, 2.0, null}, {null, 2.0, new PyInt(0)}, {2.0, 1.0, new PyInt(0)}};
        for (Object[] c : cases) {
            Map<String, Object> v = Proxies.interval(c[0], c[1], (PyNum) c[2]);
            assertNull(v.get("duration_ms"));
            assertNotNull(v.get("reason"));
        }
        assertEquals(1000.0, Proxies.interval(1.0, 2.0, new PyInt(0)).get("duration_ms"));
    }

    // ------------------------------------------------------------------ fragments and application index helpers

    @Test
    void fragmentQualityPrecedesMatching() throws Exception {
        Object[][] cases = {{1, 1, 1, 0, 1, "unknown"}, {0, 0, 1, 0, 0, "unknown"}, {0, 1, 1, 1, 0, "incomplete"},
            {0, 1, 1, 0, 1, "complete"}};
        try (Db db = memory()) {
            db.exec("CREATE TABLE g(invalid INTEGER,starts INTEGER,tails INTEGER,gaps INTEGER,decoded INTEGER)");
            for (Object[] c : cases) {
                db.exec("DELETE FROM g");
                db.exec("INSERT INTO g VALUES (?,?,?,?,?)", c[0], c[1], c[2], c[3], c[4]);
                assertEquals(c[5], db.scalar("SELECT " + Sql.get("fragments", "FRAGMENT_STATUS") + " FROM g"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"192.0.2.1|192.0.2.1", "2001:DB8:0:0:0:0:0:1|2001:db8::1", "1:0:0:2:0:0:0:3|1:0:0:2::3",
        "::|::", "0:0:0:0:0:0:0:1|::1", "fe80::1%eth0|fe80::1%eth0", "::ffff:1.2.3.4|::ffff:102:304"})
    void forwardedForAddressesNormalizeAsTheReference(String text, String expected) {
        assertEquals(expected, ApplicationIndex.Addresses.normalize(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"010.0.2.1", "256.1.1.1", "1.2.3", "unknown", "1::2::3", "12345::1", ""})
    void invalidForwardedForTokensAreDropped(String text) {
        assertNull(ApplicationIndex.Addresses.normalize(text));
        assertEquals(List.of(), ApplicationIndex.ipList(text));
    }

    @Test
    void tsharkIntegersFollowTheReferenceRules() {
        assertEquals(17L, ApplicationIndex.integer("0x11"));
        assertEquals(-1L, ApplicationIndex.integer(null, -1L));
        assertEquals(0L, ApplicationIndex.integer("1.5"));
        assertNull(ApplicationIndex.integer("abc", null));
        assertEquals(1700000000.0, ApplicationIndex.observedTime("2023-11-14T22:13:20.000000000Z"));
        assertEquals(1700000000.123456, ApplicationIndex.observedTime("2023-11-14T22:13:20.123456789Z"));
    }
}
