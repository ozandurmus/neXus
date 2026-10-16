package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.ReportModel;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestOptions;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Port of reference {@code tests/test_udp_transactions.py}, {@code tests/test_rtp.py}, {@code tests/test_sip.py} and
 * {@code tests/test_media_mutants.py} (negative/unknown cases first, naive-implementation mutants, full fault/IP-ID/IPv6
 * matrices, and threads=1 vs threads=4 determinism).
 */
class UdpRtpSipTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String tshark;
    private static Path sharedRoot;
    private static JsonNode wireBundle;
    private static final Map<String, Scenario> CACHE = new HashMap<>();

    @TempDir
    Path dir;

    record Scenario(Path pcapPath, Path dbPath, JsonNode truth, ObjectNode topology, Map<String, Object> rawReport,
            JsonNode report) {
    }

    @BeforeAll
    static void init() throws Exception {
        try {
            tshark = TsharkRunner.find(System.getenv("TSHARK"));
        } catch (IllegalArgumentException e) {
            tshark = null;
        }
        sharedRoot = Files.createTempDirectory("udp-rtp-sip-shared-");
        try (InputStream in = UdpRtpSipTest.class.getResourceAsStream(
                "/packetanalysis/engine/udp_wire_fixtures.json.gz")) {
            assertNotNull(in, "Missing udp_wire_fixtures.json.gz");
            wireBundle = JSON.readTree(new GZIPInputStream(in));
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (sharedRoot != null && Files.exists(sharedRoot)) {
            try (Stream<Path> walk = Files.walk(sharedRoot)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static Scenario scenario(String faultLocation, String ipId, boolean ipv6, String mediaMode)
            throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        String key = faultLocation + "_" + ipId + "_" + (ipv6 ? "True" : "False") + "_" + mediaMode;
        Scenario existing = CACHE.get(key);
        if (existing != null) {
            return existing;
        }
        JsonNode entry = wireBundle.get(key);
        assertNotNull(entry, "Missing scenario entry " + key);
        Path caseDir = sharedRoot.resolve(key);
        Files.createDirectories(caseDir);
        Path pcap = caseDir.resolve("points.pcapng");
        Files.write(pcap, Base64.getDecoder().decode(entry.get("pcap_b64").asText()));

        ProjectStore store = ProjectStore.open(caseDir.resolve("project"), new ProjectStore.Settings(null, 4 * GIB, 1));
        String cid = CaptureIngest.ingest(store, pcap, IngestOptions.defaults().withTshark(tshark), null, null);
        ObjectNode topo = entry.get("topology").deepCopy();
        for (JsonNode pt : topo.get("points")) {
            ((ObjectNode) pt).put("capture_id", cid);
        }
        Path dbPath = caseDir.resolve("project/project.duckdb");
        Map<String, Object> raw;
        JsonNode parsed;
        try (Db db = new Db(Engine.open(dbPath, 1))) {
            raw = Engine.analyze(db, Topology.parse(topo), null);
            parsed = JSON.readTree(ReportModel.toJson(raw));
        }
        Scenario built = new Scenario(pcap, dbPath, entry.get("truth"), topo, raw, parsed);
        CACHE.put(key, built);
        return built;
    }

    private Path cloneDb(Scenario shared) throws IOException {
        Path copy = dir.resolve("clone-" + System.nanoTime() + ".duckdb");
        Files.copy(shared.dbPath(), copy);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> segments(Scenario s) {
        return (List<Map<String, Object>>) s.rawReport().get("segments");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> clocks(Scenario s) {
        Map<String, Map<String, Object>> orig = (Map<String, Map<String, Object>>) s.rawReport().get("clocks");
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : orig.entrySet()) {
            copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rtpLossFindings(Scenario s) {
        List<Map<String, Object>> all = (List<Map<String, Object>>) s.rawReport().get("findings");
        return all.stream().filter(f -> "rtp_loss".equals(f.get("type"))).toList();
    }

    static Stream<Arguments> faultAndModeMatrix() {
        List<Arguments> out = new ArrayList<>();
        for (String fault : List.of("FW1", "FW2", "link")) {
            out.add(Arguments.of(fault, "zero", false));
            out.add(Arguments.of(fault, "constant", false));
            out.add(Arguments.of(fault, "zero", true));
        }
        return out.stream();
    }

    // ------------------------------------------------------------------ Negative / unknown cases first

    @Test
    @SuppressWarnings("unchecked")
    void udpIncompleteResponseDropInventoryAndSelectedWindowAreUnknown() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path clone = cloneDb(shared);
        try (Db db = new Db(Engine.open(clone, 1))) {
            db.exec("UPDATE obs SET caplen=wirelen-1 WHERE point='client' AND sport=123 AND dport=41000");
            Map<String, Object> udp = UdpTransactions.build(db, Topology.parse(shared.topology()), segments(shared));
            List<Db.Row> items = (List<Db.Row>) udp.get("items");
            Db.Row ntp = items.stream()
                    .filter(i -> "client".equals(i.get("point")) && "NTP".equals(i.get("protocol")))
                    .findFirst().orElseThrow();
            assertEquals("unknown", ntp.get("status"));

            Map<String, Object> inv = (Map<String, Object>) PyJson.loads(
                    (String) db.scalar("SELECT inventory FROM captures LIMIT 1"));
            inv.put("ifdrop", 5L);
            db.exec("UPDATE captures SET inventory=?", PyJson.dumps(inv));
            udp = UdpTransactions.build(db, Topology.parse(shared.topology()), segments(shared));
            items = (List<Db.Row>) udp.get("items");
            assertFalse(items.stream().anyMatch(i -> "unanswered".equals(i.get("status"))));

            ObjectNode narrowed = shared.topology().deepCopy();
            narrowed.put("start", 1700000010.0);
            narrowed.put("end", 1700000012.0);
            UdpTransactions.build(db, Topology.parse(narrowed), segments(shared));
            Object[] range = db.one("SELECT min(corrected),max(corrected) FROM udp_transactions");
            assertEquals(1700000010.0, ((Number) range[0]).doubleValue(), 0.01);
            assertEquals(1700000012.0, ((Number) range[1]).doubleValue(), 0.01);
        }
    }

    @Test
    void snmpContextMismatchCannotAnswerAnotherCommunity() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path clone = cloneDb(shared);
        try (Db db = new Db(Engine.open(clone, 1))) {
            db.exec("UPDATE udp_decoded SET \"snmp.community\"='other' WHERE point='client' AND \"snmp.data\"='2'");
            UdpTransactions.build(db, Topology.parse(shared.topology()), segments(shared));
            String status = (String) db.scalar(
                    "SELECT status FROM udp_transactions WHERE point='client' AND protocol='SNMP'");
            assertNotEquals("answered", status);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rtpSelectedWindowAndUncertainClockDoNotBlameReceiver() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path clone = cloneDb(shared);
        Map<String, Map<String, Object>> clocks = clocks(shared);
        clocks.values().forEach(c -> c.put("uncertainty", null));
        ObjectNode narrowed = shared.topology().deepCopy();
        narrowed.put("start", 1700000020.2);
        narrowed.put("end", 1700000020.4);
        try (Db db = new Db(Engine.open(clone, 1))) {
            Object[] built = Rtp.build(db, Topology.parse(narrowed), segments(shared), clocks);
            Map<String, Object> rtp = (Map<String, Object>) built[0];
            List<Map<String, Object>> findings = (List<Map<String, Object>>) built[1];
            assertTrue(findings.isEmpty());
            assertFalse(((List<?>) rtp.get("streams")).isEmpty());
            assertEquals(Boolean.TRUE,
                    db.scalar("SELECT min(corrected)>=1700000020.2 AND max(corrected)<=1700000020.4 FROM rtp_input"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingEndpointAndUncertainClocksNeverClaimMediaFailure() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path clone = cloneDb(shared);
        try (Db db = new Db(Engine.open(clone, 1))) {
            db.exec("DELETE FROM sip_messages WHERE point='client'");
            db.exec("DELETE FROM udp_decoded WHERE point='client' AND \"sip.Call-ID\" IS NOT NULL");
            Object[] built = Sip.build(db, Topology.parse(shared.topology()), segments(shared), clocks(shared),
                    List.of());
            Map<String, Object> sip = (Map<String, Object>) built[0];
            List<Map<String, Object>> findings = (List<Map<String, Object>>) built[1];
            assertFalse(findings.stream()
                    .anyMatch(f -> "sdp_media_mismatch".equals(f.get("type")) && "forward".equals(f.get("direction"))));
            assertTrue(((List<?>) sip.get("one_way")).isEmpty());
        }
        Map<String, Map<String, Object>> unclocked = clocks(shared);
        unclocked.values().forEach(c -> c.put("uncertainty", null));
        try (Db db = new Db(Engine.open(shared.dbPath(), 1))) {
            Object[] built = Sip.build(db, Topology.parse(shared.topology()), segments(shared), unclocked,
                    rtpLossFindings(shared));
            List<Map<String, Object>> findings = (List<Map<String, Object>>) built[1];
            assertTrue(findings.isEmpty());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendonlyIsLegitimateNotOneWayFailure() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path clone = cloneDb(shared);
        try (Db db = new Db(Engine.open(clone, 1))) {
            db.exec("UPDATE udp_decoded SET \"sdp.media_attr\"='sendonly' WHERE \"sip.Call-ID\" IS NOT NULL");
            Object[] built = Sip.build(db, Topology.parse(shared.topology()), segments(shared), clocks(shared),
                    rtpLossFindings(shared));
            Map<String, Object> sip = (Map<String, Object>) built[0];
            List<Map<String, Object>> findings = (List<Map<String, Object>>) built[1];
            assertTrue(((List<?>) sip.get("one_way")).isEmpty());
            assertFalse(findings.stream().anyMatch(f -> "one_way_audio".equals(f.get("type"))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"healthy", "capture_miss"})
    void healthyRewritingAndCaptureMissesAreNotAudioFailure(String mediaMode) throws Exception {
        Scenario s = scenario("FW2", "zero", false, mediaMode);
        Set<String> mediaTypes = Set.of("rtp_loss", "rtp_jitter", "sdp_media_mismatch", "one_way_audio",
                "sip_rewritten");
        for (JsonNode f : s.report().get("findings")) {
            if (mediaTypes.contains(f.get("type").asText())) {
                assertNotEquals("supported", f.get("confidence").asText());
            }
        }
        assertEquals(0, s.report().get("sip").get("one_way").size());
        assertTrue(s.report().get("rtp").get("streams").size() > 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void videoSdpDoesNotBecomeOneWayAudio() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path clone = cloneDb(shared);
        try (Db db = new Db(Engine.open(clone, 1))) {
            db.exec("UPDATE udp_decoded SET \"sdp.media.media\"='video' WHERE \"sip.Call-ID\" IS NOT NULL");
            Object[] built = Sip.build(db, Topology.parse(shared.topology()), segments(shared), clocks(shared),
                    rtpLossFindings(shared));
            List<Map<String, Object>> findings = (List<Map<String, Object>>) built[1];
            assertFalse(findings.stream().anyMatch(f -> "one_way_audio".equals(f.get("type"))));
        }
    }

    // ------------------------------------------------------------------ Naive-implementation mutant check

    @Test
    void nearestUdpSsrcOnlyReceiverBlameAndAnySdpChangeMutants() throws Exception {
        Scenario s = scenario("FW2", "zero", false, "faults");
        List<Object[]> nearest;
        long naiveSsrc;
        long actualStreams;
        Set<String> receiverBlame;
        try (Db db = new Db(Engine.open(s.dbPath(), 1))) {
            nearest = db.tuples("""
                    SELECT q.frame,arg_min(r.frame,abs(r.ts-q.ts)) FROM udp_protocol q JOIN udp_protocol r
                    ON q.point=r.point AND q.tuple_key=r.reverse_tuple AND r.ts BETWEEN q.ts AND q.ts+2
                    WHERE q.point='client' AND q.sport=41005 AND q.role='request' AND r.role='response' GROUP BY q.frame""");
            naiveSsrc = db.count("SELECT count(DISTINCT ssrc) FROM rtp_streams WHERE point='client' AND direction='forward'");
            actualStreams = db.count("SELECT count(*) FROM rtp_streams WHERE point='client' AND direction='forward'");
            receiverBlame = db.count("SELECT count(*) FROM rtp_streams WHERE point='server' AND jitter_max_ms>2") > 0
                    ? Set.of("server")
                    : Set.of();
        }
        // Ambiguous pair has a closest candidate in nearest-time mutant, but must remain unknown in production.
        assertFalse(nearest.isEmpty());
        // SSRC-only mutant collapses colliding streams.
        assertNotEquals(naiveSsrc, actualStreams);

        Set<String> supportedJitter = new LinkedHashSet<>();
        for (JsonNode f : s.report().get("findings")) {
            if ("rtp_jitter".equals(f.get("type").asText()) && "supported".equals(f.get("confidence").asText())) {
                supportedJitter.add(f.get("device").asText());
            }
        }
        assertNotEquals(receiverBlame, supportedJitter);

        Set<String> anyRewriteBug = new LinkedHashSet<>();
        for (JsonNode c : s.report().get("sip").get("comparisons")) {
            if (c.get("changes").size() > 0) {
                anyRewriteBug.add(c.get("call_id").asText());
            }
        }
        assertNotEquals(Set.of(s.truth().get("bad_call").asText()), anyRewriteBug);

        List<String> mismatchDevices = new ArrayList<>();
        for (JsonNode f : s.report().get("findings")) {
            if ("sdp_media_mismatch".equals(f.get("type").asText())) {
                mismatchDevices.add(f.get("device").asText(null));
            }
        }
        assertFalse(mismatchDevices.isEmpty());
        assertFalse(mismatchDevices.stream().allMatch("FW1"::equals));
    }

    // ------------------------------------------------------------------ Positive / matrix tests

    @ParameterizedTest
    @MethodSource("faultAndModeMatrix")
    void udpIdentityAmbiguityCaptureMissAndOnewayFirst(String faultLocation, String ipId, boolean ipv6)
            throws Exception {
        Scenario s = scenario(faultLocation, ipId, ipv6, "faults");
        List<Object[]> rows;
        try (Db db = new Db(Engine.open(s.dbPath(), 1))) {
            rows = db.tuples(
                    "SELECT protocol,status,latency_ms,hop FROM udp_transactions WHERE point='client' ORDER BY ts");
        }
        assertEquals(List.of("answered", "answered", "answered", "no_response_expected", "answered", "unknown",
                "unknown"), rows.subList(0, 7).stream().map(r -> (String) r[1]).toList());
        assertEquals(List.of("NTP", "SNMP", "RADIUS", "SYSLOG"),
                rows.subList(0, 4).stream().map(r -> (String) r[0]).toList());
        assertEquals(47.1, ((Number) rows.get(0)[2]).doubleValue(), 0.01);
        assertEquals(73.1, ((Number) rows.get(1)[2]).doubleValue(), 0.01);
        assertEquals("answered", rows.get(7)[1]);
        assertNull(rows.get(7)[3]); // intermediate capture miss, not network loss
        assertEquals("unanswered", rows.get(8)[1]);
        assertEquals(s.truth().get("expected_response_hop").asText(), rows.get(8)[3]);
        assertEquals("answered", rows.get(9)[1]);
        assertNull(rows.get(9)[3]);

        JsonNode items = s.report().get("udp").get("items");
        assertTrue(items.size() > 0);
        for (JsonNode item : items) {
            assertTrue(item.get("evidence").size() > 0);
            assertFalse(item.get("tooltip").asText().isEmpty());
        }
    }

    @Test
    void batchedUdpHeadersEqualIsolatedTsharkFields() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path clone = cloneDb(shared);
        List<List<String>> calls = new ArrayList<>();
        UdpIndex.Runner defaultRunner = UdpIndex::runProcess;
        UdpIndex.runner = (cmd, stdout, errors) -> {
            calls.add(cmd);
            return defaultRunner.run(cmd, stdout, errors);
        };
        try (Db db = new Db(Engine.open(clone, 1))) {
            String fields = String.join(",", UdpIndex.FIELDS.stream().filter(f -> !"frame.number".equals(f))
                    .map(f -> "\"" + f + "\"").toList());
            db.exec("DELETE FROM udp_decode_cache");
            List<UdpIndex.Status> statuses = UdpIndex.index(db, Topology.parse(shared.topology()), true, tshark);
            assertTrue(statuses.stream().allMatch(st -> st.reason() == null));
            List<Db.Row> batch = db.rows("SELECT point,frame," + fields + " FROM udp_decoded ORDER BY point,frame");
            assertEquals(1, calls.size());

            calls.clear();
            statuses = UdpIndex.index(db, Topology.parse(shared.topology()), false, tshark);
            assertTrue(statuses.stream().allMatch(st -> st.reason() == null));
            List<Db.Row> isolated = db.rows("SELECT point,frame," + fields + " FROM udp_decoded ORDER BY point,frame");
            assertEquals(16, calls.size());
            assertEquals(batch, isolated);

            db.exec("UPDATE obs SET path_fields=json_merge_patch(path_fields, '{\"sip_probe\":\"1\"}') WHERE point='server' AND proto='TCP'");
            UdpIndex.runner = (cmd, stdout, errors) -> {
                assertTrue(cmd.contains("-w"));
                throw new IllegalStateException("TCP SIP isolation requested");
            };
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> UdpIndex.index(db, Topology.parse(shared.topology()), true, tshark));
            assertEquals("TCP SIP isolation requested", ex.getMessage());
        } finally {
            UdpIndex.runner = defaultRunner;
        }
    }

    @ParameterizedTest
    @MethodSource("faultAndModeMatrix")
    void rtpCaptureMissCollisionAndExactLossJitter(String faultLocation, String ipId, boolean ipv6) throws Exception {
        Scenario s = scenario(faultLocation, ipId, ipv6, "faults");
        Set<String> lossHops = new LinkedHashSet<>();
        String expectedDevice = "FW1".equals(faultLocation) ? "FW1" : "FW2".equals(faultLocation) ? "FW2" : null;
        for (JsonNode f : s.report().get("findings")) {
            if ("rtp_loss".equals(f.get("type").asText()) && "supported".equals(f.get("confidence").asText())) {
                lossHops.add(f.get("hop").asText());
                String actualDevice = f.get("device") == null || f.get("device").isNull() ? null
                        : f.get("device").asText();
                assertEquals(expectedDevice, actualDevice);
            }
        }
        assertEquals(Set.of(s.truth().get("rtp_loss_hop").asText(), s.truth().get("expected_response_hop").asText()),
                lossHops);

        Set<String> jitterDevices = new LinkedHashSet<>();
        for (JsonNode f : s.report().get("findings")) {
            if ("rtp_jitter".equals(f.get("type").asText()) && "supported".equals(f.get("confidence").asText())) {
                jitterDevices.add(f.get("device").asText());
            }
        }
        assertEquals(Set.of("LB"), jitterDevices);

        String healthyFlow;
        try (Db db = new Db(Engine.open(s.dbPath(), 1))) {
            healthyFlow = (String) db.scalar(
                    "SELECT flow FROM obs WHERE point='client' AND proto='UDP' AND sport=40000 AND src=? LIMIT 1",
                    ipv6 ? "2001:db8:1::2" : ("10." + "1.0.2"));
            List<Object[]> healthyGaps = db.tuples(
                    "SELECT gaps FROM rtp_streams WHERE point='client' AND direction='forward' ORDER BY flow");
            assertEquals(2, healthyGaps.size());
            assertEquals(0L, ((Number) healthyGaps.get(0)[0]).longValue());
            assertEquals(0L, ((Number) healthyGaps.get(1)[0]).longValue());
            assertEquals(2L, db.count(
                    "SELECT count(DISTINCT flow) FROM rtp_streams WHERE point='client' AND ssrc=286331153 AND direction='forward'"));
        }
        for (JsonNode f : s.report().get("findings")) {
            assertFalse("rtp_loss".equals(f.get("type").asText())
                    && "forward".equals(f.get("direction").asText())
                    && healthyFlow.equals(f.get("metrics").get("flow").asText()));
        }
        JsonNode streams = s.report().get("rtp").get("streams");
        assertTrue(streams.size() > 0);
        for (JsonNode st : streams) {
            assertTrue(st.get("evidence").size() > 0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void jitterMatchesHeaderOracleAndMissingDynamicClockIsUnknown() throws Exception {
        Scenario s = scenario("FW2", "zero", false, "faults");
        try (Db db = new Db(Engine.open(s.dbPath(), 1))) {
            List<Object[]> packets = db.tuples(
                    "SELECT ts,stamp,jitter_ms FROM rtp_packets WHERE point='client' AND direction='forward' AND ssrc=286331153 ORDER BY flow,rn");
            assertFalse(packets.isEmpty());
            List<Object[]> flowPackets = db.tuples(
                    "SELECT ts,stamp,jitter_ms FROM rtp_packets WHERE point='client' AND direction='forward' AND flow=(SELECT min(flow) FROM rtp_packets WHERE point='client' AND direction='forward') ORDER BY rn");
            double jitter = 0.0;
            for (int i = 0; i < flowPackets.size(); i++) {
                double arrival = ((Number) flowPackets.get(i)[0]).doubleValue();
                long stamp = ((Number) flowPackets.get(i)[1]).longValue();
                double measured = ((Number) flowPackets.get(i)[2]).doubleValue();
                if (i > 0) {
                    double prevArrival = ((Number) flowPackets.get(i - 1)[0]).doubleValue();
                    long prevStamp = ((Number) flowPackets.get(i - 1)[1]).longValue();
                    long delta = Math.floorMod(stamp - prevStamp + (1L << 31), 1L << 32) - (1L << 31);
                    jitter += (Math.abs((arrival - prevArrival) * 8000.0 - delta) - jitter) / 16.0;
                }
                assertEquals(jitter / 8.0, measured, 1e-9);
            }
        }
        Path clone = cloneDb(s);
        try (Db db = new Db(Engine.open(clone, 1))) {
            db.exec("UPDATE udp_decoded SET \"rtp.p_type\"='96',\"sdp.media_attr\"=NULL");
            Object[] built = Rtp.build(db, Topology.parse(s.topology()), segments(s), clocks(s));
            Map<String, Object> rtp = (Map<String, Object>) built[0];
            List<Db.Row> streams = (List<Db.Row>) rtp.get("streams");
            assertFalse(streams.isEmpty());
            for (Db.Row st : streams) {
                assertNull(st.get("jitter_p95_ms"));
                assertTrue(((String) st.get("reason")).contains("clock rate unknown"));
            }
        }
    }

    @Test
    void mediaContentFiltersAreTsharkDecodable() throws Exception {
        Scenario s = scenario("FW2", "zero", false, "faults");
        List<JsonNode> refs = new ArrayList<>();
        for (String kind : List.of("rtp_loss", "sdp_media_mismatch")) {
            JsonNode finding = null;
            for (JsonNode f : s.report().get("findings")) {
                if (kind.equals(f.get("type").asText())) {
                    finding = f;
                    break;
                }
            }
            assertNotNull(finding, kind);
            JsonNode ref = finding.get("evidence").get(0);
            assertNotNull(ref.get("protocol_identity"));
            assertFalse(ref.get("flow_filter").asText().isEmpty());
            assertFalse(ref.get("display_filter").asText().isEmpty());
            refs.add(ref);
        }
        String expr = String.join(" || ", refs.stream().map(r -> "(" + r.get("content_filter").asText() + ")").toList());
        List<String> cmd = new ArrayList<>(
                List.of(tshark, "-n", "-r", s.pcapPath().toString(), "-Y", expr, "-T", "fields", "-e", "frame.number"));
        for (JsonNode r : s.topology().get("rtp_port_ranges")) {
            cmd.addAll(List.of("-d", "udp.port==" + r.get(0).asInt() + "-" + r.get(1).asInt() + ",rtp"));
        }
        Process p = new ProcessBuilder(cmd).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor());
        Set<Integer> frames = new LinkedHashSet<>();
        for (String tok : out.trim().split("\\s+")) {
            if (!tok.isEmpty()) {
                frames.add(Integer.parseInt(tok));
            }
        }
        for (JsonNode r : refs) {
            assertTrue(frames.contains(r.get("frame").asInt()));
        }
    }

    @Test
    void sdpPortsEnableDecodeWithoutExplicitRange() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        ObjectNode topo = shared.topology().deepCopy();
        ArrayNode firstPoint = JSON.createArrayNode();
        firstPoint.add(topo.get("points").get(0));
        topo.set("points", firstPoint);
        ArrayNode fwd = JSON.createArrayNode();
        fwd.add("client");
        topo.set("forward", fwd);
        topo.set("reverse", JSON.createArrayNode());
        topo.set("nat_mappings", JSON.createArrayNode());
        topo.set("rtp_port_ranges", JSON.createArrayNode());

        Path clone = cloneDb(shared);
        try (Db db = new Db(Engine.open(clone, 1))) {
            List<UdpIndex.Status> statuses = UdpIndex.index(db, Topology.parse(topo), true, tshark);
            assertFalse(statuses.isEmpty());
            assertTrue(statuses.stream().allMatch(st -> st.reason() == null));
            assertTrue(db.count("SELECT count(*) FROM udp_decoded WHERE point='client' AND \"rtp.version\"='2'") > 0);
        }
    }

    @ParameterizedTest
    @MethodSource("faultAndModeMatrix")
    void expectedRewriteIsNotAlgBugAndWrongSdpIsExact(String faultLocation, String ipId, boolean ipv6)
            throws Exception {
        Scenario s = scenario(faultLocation, ipId, ipv6, "faults");
        String expectedDevice = !"link".equals(faultLocation) ? faultLocation : null;
        List<JsonNode> changes = new ArrayList<>();
        for (JsonNode c : s.report().get("sip").get("comparisons")) {
            if (c.get("changes").size() > 0) {
                changes.add(c);
                String actualDevice = c.get("device") == null || c.get("device").isNull() ? null
                        : c.get("device").asText();
                assertEquals(expectedDevice, actualDevice);
            }
        }
        assertFalse(changes.isEmpty());

        List<JsonNode> mismatch = new ArrayList<>();
        List<JsonNode> oneWay = new ArrayList<>();
        String badCall = s.truth().get("bad_call").asText();
        String healthyCall = s.truth().get("healthy_call").asText();
        for (JsonNode f : s.report().get("findings")) {
            if (!"supported".equals(f.get("confidence").asText())) {
                continue;
            }
            if ("sdp_media_mismatch".equals(f.get("type").asText())) {
                mismatch.add(f);
                String actualDevice = f.get("device") == null || f.get("device").isNull() ? null
                        : f.get("device").asText();
                assertEquals(expectedDevice, actualDevice);
                assertEquals(badCall, f.get("metrics").get("call_id").asText());
                assertNotEquals(healthyCall, f.get("metrics").get("call_id").asText());
            } else if ("one_way_audio".equals(f.get("type").asText())) {
                oneWay.add(f);
            }
        }
        assertFalse(mismatch.isEmpty());
        Set<String> oneWayCalls = new LinkedHashSet<>();
        oneWay.forEach(f -> oneWayCalls.add(f.get("metrics").get("call_id").asText()));
        assertEquals(Set.of(badCall, healthyCall), oneWayCalls);
        for (JsonNode f : Stream.concat(mismatch.stream(), oneWay.stream()).toList()) {
            assertTrue(f.get("evidence").size() > 0);
            assertFalse(f.get("tooltip").asText().isEmpty());
        }
    }

    @Test
    void undeclaredRewriteNamesActualFwNotOtherFw() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        ObjectNode topo = shared.topology().deepCopy();
        for (JsonNode pt : topo.get("points")) {
            if ("FW2".equals(pt.get("device").asText())) {
                ((ObjectNode) pt).put("payload_transform", "none");
            }
        }
        Path clone = cloneDb(shared);
        JsonNode report;
        try (Db db = new Db(Engine.open(clone, 1))) {
            report = JSON.readTree(ReportModel.toJson(Engine.analyze(db, Topology.parse(topo), null)));
        }
        List<JsonNode> rewritten = new ArrayList<>();
        for (JsonNode f : report.get("findings")) {
            if ("sip_rewritten".equals(f.get("type").asText()) && "supported".equals(f.get("confidence").asText())) {
                rewritten.add(f);
                assertEquals("FW2", f.get("device").asText());
                assertEquals("SIP rewritten by FW2 (not declared)", f.get("summary").asText());
            }
        }
        assertFalse(rewritten.isEmpty());
    }

    @Test
    void udpRtpSipSameInputSameOutputAcrossThreadCounts() throws Exception {
        Scenario shared = scenario("FW2", "zero", false, "faults");
        Path db1Path = cloneDb(shared);
        Path db4Path = cloneDb(shared);
        Topology topology = Topology.parse(shared.topology());
        String json1;
        String json4;
        try (Db db1 = new Db(Engine.open(db1Path, 1)); Db db4 = new Db(Engine.open(db4Path, 4))) {
            Map<String, Object> r1 = Engine.analyze(db1, topology, null);
            Map<String, Object> r4 = Engine.analyze(db4, topology, null);
            r1.put("generated_at", "<masked>");
            r4.put("generated_at", "<masked>");
            json1 = ReportModel.toJson(r1);
            json4 = ReportModel.toJson(r4);
            assertEquals(json1, json4);
            for (String table : List.of("udp_decoded", "udp_protocol", "udp_transactions", "rtp_input", "rtp_packets",
                    "rtp_streams", "sip_messages", "rtp_call_packets")) {
                assertEquals(db1.rows("SELECT * FROM " + table + " ORDER BY ALL"),
                        db4.rows("SELECT * FROM " + table + " ORDER BY ALL"), table);
            }
        }
    }
}
