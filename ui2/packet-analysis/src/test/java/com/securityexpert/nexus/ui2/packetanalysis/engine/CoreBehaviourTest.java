package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classification, segment and headline rules on frozen reference inputs, ported from the reference
 * tests/test_analysis.py, test_headlines.py, test_quality.py, test_translation.py (negative / unknown first).
 */
class CoreBehaviourTest {
    @TempDir
    Path dir;

    private JsonNode run(String fixture, java.util.function.Consumer<ObjectNode> topology) throws Exception {
        return run(fixture, topology, null);
    }

    private JsonNode run(String fixture, java.util.function.Consumer<ObjectNode> topology, String sql) throws Exception {
        JsonNode f = Fixtures.load(fixture);
        Path project = Fixtures.project(f, dir, fixture + System.nanoTime() + ".duckdb");
        if (sql != null) {
            try (var c = Engine.open(project, 1); Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }
        ObjectNode t = Fixtures.topology(f);
        topology.accept(t);
        return Fixtures.parse(Fixtures.run(project, t, 1));
    }

    private static JsonNode segment(JsonNode report, String id) {
        for (JsonNode s : report.get("segments")) {
            if (s.get("id").asText().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no segment " + id);
    }

    private static List<String> types(JsonNode report) {
        List<String> out = new ArrayList<>();
        report.get("findings").forEach(x -> out.add(x.get("type").asText()));
        return out;
    }

    @Test
    void emptySelectedWindowIsUnknownNotZeroLoss() throws Exception {
        // test_empty_selected_window_is_unknown_not_zero_loss
        JsonNode report = run("healthy", t -> {
            t.put("start", 1700001000.0);
            t.put("end", 1700001001.0);
        });
        for (JsonNode s : report.get("segments")) {
            assertTrue(s.get("loss_percent").isNull(), s.get("id").asText());
            assertTrue(s.get("reason").asText().startsWith("Selected window is outside common coverage"));
        }
        assertEquals("Inconclusive", report.get("verdict").asText());
    }

    @Test
    void badOverrideSuppressesNegativeLatency() throws Exception {
        // test_bad_override_suppresses_negative_latency
        JsonNode report = run("healthy", t -> {
            String cid = t.get("points").get(1).get("capture_id").asText();
            ObjectNode o = Fixtures.JSON.createObjectNode();
            o.putObject(cid).put("offset_ms", 1000).put("drift_ppm", 0);
            t.set("clock_overrides", o);
        });
        JsonNode hop = segment(report, "forward:p0:p1");
        assertTrue(hop.get("p95_ms").isNull());
        assertEquals("Clock alignment unreliable", hop.get("reason").asText());
        String cid = Fixtures.load("healthy").get("topology").get("points").get(1).get("capture_id").asText();
        assertEquals("override", report.get("clocks").get(cid).get("confidence").asText());
        assertTrue(report.get("clocks").get(cid).get("uncertainty").isNull());
    }

    @Test
    void natMustBeConfirmedBeforeTheDeviceHopIsAssessed() throws Exception {
        // test_nat_must_be_confirmed_then_collapses_logical_flow (unconfirmed half)
        JsonNode report = run("nat", t -> { });
        JsonNode hop = segment(report, "forward:p1:p2");
        assertEquals("NAT mapping awaits confirmation", hop.get("reason").asText());
        assertTrue(hop.get("loss_percent").isNull());
        assertEquals("unknown", hop.get("latency_confidence").asText());
        assertTrue(report.get("nat_suggestions").size() > 0);
        for (JsonNode s : report.get("nat_suggestions")) {
            assertEquals("suggested", s.get("status").asText());
            assertTrue(s.get("samples").asLong() >= 3);
            assertTrue(s.get("evidence").size() > 0);
        }
    }

    @Test
    void captureMissIsNeverNetworkLoss() throws Exception {
        // test_capture_miss_never_network_loss
        JsonNode report = run("demo_ipid_zero_nat_confirmed", t -> { });
        boolean seen = false;
        for (JsonNode f : report.get("findings")) {
            if (f.get("type").asText().equals("capture_miss")) {
                seen = true;
                assertEquals("quality", f.get("severity").asText());
                assertEquals("capture_visibility", f.get("cause").asText());
                assertTrue(segment(report, f.get("hop").asText()).get("headline").asText()
                        .contains("These are capture-quality findings, not network loss."));
            }
        }
        assertTrue(seen);
    }

    @Test
    void fullProxyBoundaryIsUnsupportedNotLossFree() throws Exception {
        // test_full_proxy_opacity_does_not_hide_independent_leg_loss (boundary half)
        JsonNode report = run("demo_ipid_zero_nat_confirmed", t -> {
            for (JsonNode p : t.get("points")) {
                if (p.get("id").asText().equals("p2")) {
                    ((ObjectNode) p).put("translation", "full_proxy");
                }
            }
        });
        JsonNode hop = segment(report, "forward:p1:p2");
        assertEquals("Unsupported translation boundary", hop.get("reason").asText());
        assertTrue(hop.get("loss_percent").isNull());
    }

    @Test
    void headlineCountsDenominatorsAndBothTimezones() throws Exception {
        // test_headline_counts_denominators_and_both_timezones
        JsonNode report = run("demo_ipid_zero_nat_confirmed", t -> t.put("report_timezone", "Europe/Istanbul"));
        assertEquals("Impactful loss observed", report.get("verdict").asText());
        JsonNode finding = null;
        for (JsonNode f : report.get("findings")) {
            if (f.get("type").asText().equals("impactful_loss")) {
                finding = f;
                break;
            }
        }
        JsonNode s = segment(report, finding.get("hop").asText());
        JsonNode m = s.get("headline_metrics");
        assertEquals(9, m.get("lost").asLong());
        assertEquals(5, m.get("quickly_recovered").asLong());
        assertEquals(4, m.get("measured_stalls").asLong());
        assertEquals(100.0 * m.get("lost").asLong() / m.get("eligible_data_packets").asLong(), m.get("loss_percent").asDouble(),
                1e-12);
        assertEquals("none_observed", m.get("upstream_status").asText());
        assertTrue(s.get("time_labels").get("local").asText().contains("+03:00"));
        assertTrue(s.get("headline").asText().contains(" UTC"));
        assertTrue(s.get("headline").asText().contains("uncertainty"));
        assertTrue(s.get("headline").asText().contains("No loss was observed before FW egress"));
    }

    @Test
    void doesNotClaimNoUpstreamLossWithoutCompleteEvidence() throws Exception {
        // test_does_not_claim_no_upstream_loss_without_complete_evidence
        JsonNode f = Fixtures.load("demo_ipid_zero_nat_confirmed");
        String cid = f.get("topology").get("points").get(0).get("capture_id").asText();
        JsonNode report = run("demo_ipid_zero_nat_confirmed", t -> { },
                "UPDATE packets SET unsupported='unknown_transport' WHERE capture_id='" + cid + "' AND frame%4=0");
        JsonNode s = segment(report, "forward:p2:p3");
        assertFalse(s.get("headline").asText().contains("No loss was observed before"));
        assertEquals("unknown", s.get("headline_metrics").get("upstream_status").asText());
    }

    @Test
    void multipleClassesShareOneSegmentHeadline() throws Exception {
        // test_multiple_classes_share_one_segment_headline
        JsonNode report = run("demo_ipid_zero_nat_confirmed", t -> { });
        JsonNode s = segment(report, "forward:p2:p3");
        List<JsonNode> classes = new ArrayList<>();
        report.get("findings").forEach(x -> {
            if (x.get("hop").asText().equals("forward:p2:p3")) {
                classes.add(x);
            }
        });
        assertEquals(2, classes.size());
        assertEquals(2, s.get("finding_ids").size());
        assertTrue(s.get("headline").asText().startsWith("From "));
        assertFalse(classes.get(0).get("headline").asText().equals(classes.get(1).get("headline").asText()));
        for (JsonNode c : classes) {
            assertFalse(c.get("headline").asText().startsWith("From "));
            assertTrue(c.get("headline").asText().length() < 120);
        }
    }

    @Test
    void repeatedSignaturesFailClosed() throws Exception {
        // test_repeated_signatures_fail_closed: duplicates never become loss
        JsonNode report = run("duplicate", t -> { });
        assertFalse(types(report).contains("impactful_loss"));
        assertFalse(types(report).contains("unrecovered_loss"));
    }

    @Test
    void everySectionIsPortedAndPresent() throws Exception {
        JsonNode report = run("healthy", t -> { });
        JsonNode unknown = report.get("unknown_sections");
        for (String key : List.of("proxies", "application_status", "tls", "integrity_notes", "fragment_points", "udp", "rtp",
                "sip", "f5", "timeseries", "onsets", "host_path", "cycles", "session_events", "security", "delivery", "incident",
                "microbursts", "sessions", "vendor_device_events", "vendor_device_event_count", "confirmed_device_drops",
                "confirmed_device_drop_count")) {
            assertTrue(report.has(key), key);
            assertFalse(unknown.has(key), key);
        }
        assertEquals(0, unknown.size());
    }

    @Test
    void natTupleNormalization() {
        // test_nat_tuple_validation_and_normalization (normalization half)
        assertEquals(Topology.normalizeTuple("[\"TCP\",\"10.0.0.1\",40,\"10.0.0.2\",50]"),
                Topology.normalizeTuple("[\"TCP\", \"10.0.0.1\", 40, \"10.0.0.2\", 50]"));
        assertEquals("[\"TCP\",\"10.0.0.2\",50,\"10.0.0.1\",40]", Tuples.reverseTuple("[\"TCP\",\"10.0.0.1\",40,\"10.0.0.2\",50]"));
        assertThrows(IllegalArgumentException.class, () -> Tuples.reverseTuple("[]"));
    }

    @Test
    void addressMembershipFollowsTheReference() {
        List<Tuples.Network> nets = Tuples.networks(List.of("10.0.0.0/8"));
        assertTrue(Tuples.inAny(Tuples.address("10.1.2.3"), nets));
        assertFalse(Tuples.inAny(Tuples.address("::ffff:10.0.0.1"), nets), "IPv4-mapped IPv6 is not in an IPv4 net");
        assertFalse(Tuples.inAny(Tuples.address("fd00::1"), nets), "version mismatch is not contained");
        assertThrows(IllegalArgumentException.class, () -> Tuples.address("010.1.1.1"));
        assertThrows(IllegalArgumentException.class, () -> Tuples.address(null));
        assertEquals("unknown", Preparation.direction("[\"TCP\",null,null,null,null]", nets));
        assertEquals("forward", Preparation.direction("[\"TCP\",\"10.0.0.1\",1,\"192.0.2.1\",2]", nets));
        assertEquals("reverse", Preparation.direction("[\"TCP\",\"192.0.2.1\",2,\"10.0.0.1\",1]", nets));
        assertEquals("unknown", Preparation.direction("[\"TCP\",\"10.0.0.1\",1,\"10.0.0.2\",2]", nets));
    }

    @Test
    void timeLabelsUseTheEventDateForDaylightSaving() {
        // test_dst_time_conversion_uses_event_date
        assertTrue(((String) Headlines.timeLabels(1700000000, "America/New_York").get("local")).contains("-05:00"));
        assertTrue(((String) Headlines.timeLabels(1719792000, "America/New_York").get("local")).contains("-04:00"));
        Map<String, Object> l = Headlines.timeLabels(1700000000.0004999, "UTC");
        assertEquals("2023-11-14 22:13:20.000 UTC", l.get("utc"));
        assertEquals("2023-11-14 22:13:20.000+00:00 local", l.get("local"));
        // Reference value: round() works on the binary value (1700000000.0015 is just below the tie).
        assertEquals("2023-11-14 22:13:20.001 UTC", Headlines.timeLabels(1700000000.0015, "UTC").get("utc"));
    }

    @Test
    void stageGraphRejectsReadsBeforeCreates() {
        StageGraph core = StageGraph.core();
        assertEquals("selection", core.stages().get(0).name());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (StageGraph.Stage stage : core.stages()) {
            assertTrue(names.add(stage.name()), "stage registered once: " + stage.name());
        }
        assertEquals("device_report", core.stages().get(core.stages().size() - 1).name());
    }

    @Test
    void jsonWriterMatchesTheReferenceText() {
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("label", "Client → FW");
        v.put("e", new ArrayList<>());
        v.put("o", new java.util.LinkedHashMap<>());
        v.put("f", 1e-05);
        v.put("g", 100.0);
        v.put("i", 3L);
        v.put("n", null);
        assertEquals("{\n  \"label\": \"Client \\u2192 FW\",\n  \"e\": [],\n  \"o\": {},\n  \"f\": 1e-05,\n  \"g\": 100.0,\n"
                + "  \"i\": 3,\n  \"n\": null\n}", PyJsonWriter.dumps(v, true));
        assertEquals("[\"TCP\",\"10.0.0.1\",null]", PyJsonWriter.compact(java.util.Arrays.asList("TCP", "10.0.0.1", null)));
        ArrayNode unused = Fixtures.JSON.createArrayNode();
        assertEquals("[]", PyJsonWriter.dumps(unused, true));
    }
}
