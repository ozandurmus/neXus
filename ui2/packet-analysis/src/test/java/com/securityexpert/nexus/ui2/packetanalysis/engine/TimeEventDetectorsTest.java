package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyRound;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Time and event detector lane: reference unit cases of {@code tests/test_onset.py}, {@code test_timeseries.py},
 * {@code test_sessions.py} (negative and unknown cases first), full det-a section parity on frozen reference inputs
 * (tools/packet_analysis/parity/det_a_export_fixture.py, DuckDB threads=1) and threads=1 vs threads=4 identity.
 */
class TimeEventDetectorsTest {
    static final List<String> SECTIONS = List.of("verdict", "segments", "findings", "finding_count", "timeseries", "onsets",
            "microbursts", "host_path", "cycles", "incident", "delivery", "vendor_device_events", "vendor_device_event_count",
            "confirmed_device_drops", "confirmed_device_drop_count", "sessions", "session_events", "security");

    @TempDir
    Path dir;

    /** {@code test_onset.series}: one capturing bucket per value, 20 eligible packets each. */
    static List<Row> series(double... values) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Row r = new Row();
            r.put("bucket", (long) i);
            r.put("start", (long) i);
            r.put("end", (long) i + 1);
            r.put("coverage", "capturing");
            r.put("reason", null);
            r.put("eligible_packets", 20L);
            r.put("capture_misses", 0L);
            r.put("unknown_events", 0L);
            r.put("loss_percent", values[i] == Math.rint(values[i]) ? (Object) (long) values[i] : (Object) values[i]);
            r.put("loss_count", PyRound.round(values[i] * 20 / 100));
            out.add(r);
        }
        return out;
    }

    static double[] repeat(double v, int n, double... tail) {
        double[] out = new double[n + tail.length];
        java.util.Arrays.fill(out, 0, n, v);
        System.arraycopy(tail, 0, out, n, tail.length);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> onset(Object[] result) {
        return (Map<String, Object>) result[0];
    }

    // ------------------------------------------------------------------ negative / unknown first

    @Test
    void insufficientAndUnhealthyBaselinesAreUnknown() {
        for (double[] values : List.of(repeat(0, 5), repeat(5, 20))) {
            Object[] r = Onsets.detectSeries(series(values), "loss_percent", 1L);
            assertNull(r[0]);
            assertTrue(((String) r[1]).startsWith("unknown:"), (String) r[1]);
        }
    }

    @Test
    void isolatedSpikeIsNotAnOnset() {
        assertNull(Onsets.detectSeries(series(repeat(0, 6, 20, 0, 0)), "loss_percent", 1L)[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"retrans_percent", "failed_handshakes", "resets", "zero_windows"})
    void countSignalsRequireMultipleExcessEvents(String metric) {
        String countKey = Onsets.COUNT_SIGNALS.get(metric)[0];
        String denominator = Onsets.COUNT_SIGNALS.get(metric)[1];
        List<Row> values = series(repeat(0, 40));
        for (Row row : values) {
            row.put(countKey, 0L);
            if (denominator != null) {
                row.put(denominator, 100L);
            }
        }
        values.get(8).put(countKey, 1L);
        assertNull(Onsets.detectSeries(values, metric, 0.0)[0]);
        values.get(8).put(countKey, 10L);
        assertNull(Onsets.detectSeries(values, metric, 0.0)[0], "a single-bucket spike is not sustained");
        values.get(8).put(countKey, 1L);
        values.get(12).put(countKey, 1L);
        values.get(16).put(countKey, 1L);
        Map<String, Object> onset = onset(Onsets.detectSeries(values, metric, 0.0));
        assertEquals(8L, onset.get("bucket"));
        assertEquals(3L, onset.get("window_events"));
        assertEquals(3L, onset.get("count_threshold"));
        assertEquals(3L, onset.get("event_buckets"));
    }

    @Test
    void qualityNotesDoNotPoisonBaselineOrInventLoss() {
        List<Row> values = series(repeat(0, 8, 20, 20, 20));
        for (Row row : values) {
            row.put("capture_misses", 2L);
            row.put("unknown_events", 1L);
            row.put("reason", "capture quality note");
        }
        assertEquals(8L, onset(Onsets.detectSeries(values, "loss_percent", 0.0)).get("bucket"));
        for (Row row : values) {
            row.put("loss_count", 0L);
            row.put("loss_percent", 0L);
        }
        Object[] r = Onsets.detectSeries(values, "loss_percent", 0.0);
        assertNull(r[0]);
        assertFalse(((String) r[1]).startsWith("unknown:"));
    }

    @Test
    void unknownSegmentsDoNotOverruleValidResults() {
        Object[] r = Onsets.statusSummary(List.of(Map.of("onset_status", "none"), Map.of("onset_status", "unknown")));
        assertEquals("partial", r[0]);
        assertEquals(Map.of("none", 1L, "unknown", 1L), r[1]);
        assertTrue(((String) r[2]).contains("1 segment(s) have unknown"));
        assertFalse(((String) r[2]).startsWith("Onset unknown"));
        assertEquals("unknown", Onsets.statusSummary(List.of(Map.of("onset_status", "unknown")))[0]);
    }

    @Test
    void latencyWindowNeedsEnoughSamplesAndAStableBaseline() {
        List<Map<String, Object>> few = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            few.add(PyOps.dict("start", (double) i, "end", i + 1.0, "latency_p95_ms", 1.0));
        }
        assertTrue(((String) Onsets.latencyWindow(few, 1.0, "latency_p95_ms")[1]).startsWith("unknown:"));
        List<Map<String, Object>> unstable = new ArrayList<>();
        double[] values = {1, 9, 1, 9, 1, 1, 1, 1, 1};
        for (int i = 0; i < values.length; i++) {
            unstable.add(PyOps.dict("start", (double) i, "end", i + 1.0, "latency_p95_ms", values[i]));
        }
        assertEquals("unknown: unstable early latency baseline", Onsets.latencyWindow(unstable, 1.0, "latency_p95_ms")[1]);
    }

    @Test
    void stormFoldingKeepsSupportedResetOrigins() {
        Map<String, Object> storm = PyOps.dict("type", "reset_storm", "confidence", "supported", "time_range", PyOps.list(10.0, 10.0),
                "metrics", new LinkedHashMap<>());
        Map<String, Object> unknown = PyOps.dict("type", "reset_origin", "confidence", "unknown", "time_range", PyOps.list(10.005, 10.005));
        Map<String, Object> supported = PyOps.dict("type", "reset_origin", "confidence", "supported", "time_range", PyOps.list(10.0, 10.0));
        Map<String, Object> far = PyOps.dict("type", "reset_origin", "confidence", "unknown", "time_range", PyOps.list(11.0, 11.0));
        List<Map<String, Object>> kept = SessionEvents.foldStormResets(new ArrayList<>(List.of(storm, unknown, supported, far)),
                List.of(PyOps.dict("rst_times", PyOps.list(10.0))));
        assertEquals(List.of(storm, supported, far), kept);
        assertEquals(1L, PyOps.map(storm.get("metrics")).get("unattributed_reset_origin_folded"));
        List<Map<String, Object>> none = new ArrayList<>(List.of(unknown));
        assertTrue(SessionEvents.foldStormResets(none, List.of()) == none, "no storm: findings untouched");
    }

    @Test
    void primeSuspectPrecedesSenderSymptoms() {
        Map<String, Object> symptom = PyOps.dict("segment", "upstream", "metric", "retrans_percent", "time", 1L, "scope", "capture_signal");
        Map<String, Object> loss = PyOps.dict("segment", "loss", "metric", "loss_percent", "time", 3L, "scope", "network_segment");
        assertEquals(List.of(loss, symptom), Onsets.orderedOnsets(List.of(symptom, loss),
                PyOps.dict("forward", PyOps.dict("prime_suspects", PyOps.list("loss")))));
    }

    @Test
    void handshakeRetriesDoNotCountAsNewFailedConnections() throws Exception {
        try (Db db = new Db(DriverManager.getConnection("jdbc:duckdb:"))) {
            db.exec("CREATE TABLE events(point_a VARCHAR,point_b VARCHAR,direction VARCHAR,kind VARCHAR,flow VARCHAR,ts DOUBLE)");
            db.execMany("INSERT INTO events VALUES ('a','b','forward','handshake_blocked',?,?)",
                    List.of(new Object[] {"one", 8.0}, new Object[] {"one", 11.0}, new Object[] {"one", 14.0}, new Object[] {"two", 17.0}));
            List<Row> buckets = db.rows(Sql.get("timeseries", "event_buckets"), "a", "b", "forward", 0L, 1L);
            long n = 0;
            long flows = 0;
            List<Object> first = new ArrayList<>();
            for (Row r : buckets) {
                n += r.lng("n");
                flows += r.lng("flows");
                if (r.lng("flows") > 0) {
                    first.add(r.get("bucket"));
                }
            }
            assertEquals(4, n);
            assertEquals(2, flows);
            first.sort(Comparator.comparingLong(x -> (Long) x));
            assertEquals(List.of(8L, 17L), first);
        }
    }

    // ------------------------------------------------------------------ positive cases

    @Test
    void detectorExplainsFirstCrossing() {
        Map<String, Object> onset = onset(Onsets.detectSeries(series(repeat(0, 6, 20, 20, 20)), "loss_percent", 1L));
        assertEquals(6L, onset.get("bucket"));
        assertEquals(7L, onset.get("confirmed_bucket"));
        assertEquals(0.0, PyOps.dbl(onset.get("baseline_value")));
        assertEquals(1.0, PyOps.dbl(onset.get("threshold")));
        List<Row> capture = series(repeat(0, 6, 30, 30, 30));
        for (Row row : capture.subList(6, 9)) {
            row.put("capture_misses", 2L);
        }
        assertEquals(6L, onset(Onsets.detectSeries(capture, "loss_percent", 1L)).get("bucket"));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5})
    void rollingLossKeepsFirstIntermittentBucket(int interval) {
        List<Row> values = series(repeat(0, 45));
        for (int i = 8; i < values.size(); i += interval) {
            values.get(i).put("loss_count", 1L);
            values.get(i).put("loss_percent", 5L);
        }
        Map<String, Object> onset = onset(Onsets.detectSeries(values, "loss_percent", 0L));
        assertEquals(8L, onset.get("bucket"));
        assertEquals((long) (8 + interval * 2), onset.get("confirmed_bucket"));
        assertEquals(3L, onset.get("window_events"));
    }

    @Test
    void counterReferenceIsNotBackdatedIntoBaseline() {
        List<Row> values = series(repeat(0, 20));
        long[] counts = {1, 0, 1, 0, 1, 3, 3};
        for (int i = 0; i < values.size(); i++) {
            values.get(i).put("resets", i < counts.length ? counts[i] : 0L);
        }
        Map<String, Object> onset = onset(Onsets.detectSeries(values, "resets", 0L));
        assertEquals(5L, onset.get("bucket"));
        assertEquals(0.6, PyOps.dbl(onset.get("baseline_value")), 1e-12);
    }

    // ------------------------------------------------------------------ frozen reference parity

    @ParameterizedTest
    @ValueSource(strings = {"syn_blocked", "security", "onset_loss"})
    void detectorSectionsEqualTheReference(String name) throws Exception {
        JsonNode fixture = Fixtures.load("det_a_" + name);
        Path project = Fixtures.project(fixture, dir, "p.duckdb");
        JsonNode report = Fixtures.parse(Fixtures.run(project, fixture.get("topology"), 1));
        JsonNode reference = fixture.get("reference_det_a");
        for (String section : SECTIONS) {
            assertEquals(canonical(section, reference.get(section)), canonical(section, report.get(section)), name + ": " + section);
        }
    }

    @Test
    void sameInputSameOutputAcrossThreadCounts() throws Exception {
        JsonNode fixture = Fixtures.load("det_a_security");
        Path one = Fixtures.project(fixture, dir, "one.duckdb");
        Path four = Fixtures.project(fixture, dir, "four.duckdb");
        assertEquals(Fixtures.run(one, fixture.get("topology"), 1), Fixtures.run(four, fixture.get("topology"), 4), "report text");
        for (String table : List.of("segment_buckets", "session_lifecycle", "session_buckets", "security_cohorts", "microbursts",
                "cycle_points", "cycle_pairs", "delivery_series", "adjacent_match_rates", "handshake_stops", "device_proofs")) {
            assertEquals(dump(one, table), dump(four, table), "table " + table);
        }
    }

    private static final java.util.regex.Pattern INTEGRITY_COUNTER =
            java.util.regex.Pattern.compile("^(integrity:.*):\\d+(:merged)?$");

    private static String canonical(String section, JsonNode value) {
        JsonNode v = Fixtures.canonical(section, value);
        if (section.equals("findings") && v.isArray()) {
            // Recorded deliberate difference (DETERMINISM.md, integrity.sql endpoint_packets): the reference emits the
            // reset/ICMP origin packets without an outer ORDER BY, so the per-run counter at the end of an integrity
            // finding id follows its output order; Java orders by corrected, frame. Same findings, same order.
            for (JsonNode f : v) {
                String id = f.path("id").asText();
                if (id.startsWith("integrity:")) {
                    ((ObjectNode) f).put("id", INTEGRITY_COUNTER.matcher(id).replaceFirst("$1$2"));
                }
            }
        }
        if (section.equals("cycles") && v.isObject()) {
            for (JsonNode r : v.path("flow_ports")) {
                if (r.hasNonNull("per_hop_added")) {
                    ArrayNode items = (ArrayNode) Fixtures.parse(r.get("per_hop_added").asText());
                    List<JsonNode> sorted = new ArrayList<>();
                    items.forEach(sorted::add);
                    sorted.sort(Comparator.comparing((JsonNode x) -> x.get("hop").asText()).thenComparing(x -> x.get("component").asText()));
                    ((ObjectNode) r).put("per_hop_added", sorted.toString());
                }
            }
        }
        return v.toString();
    }

    /** Key order is compared by the JSON text tests; here values are compared with keys sorted. */
    private static JsonNode sortKeys(JsonNode v) {
        if (v.isObject()) {
            ObjectNode out = Fixtures.JSON.createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), sortKeys(e.getValue()));
            }
            sorted.forEach(out::set);
            return out;
        }
        if (v.isArray()) {
            ArrayNode out = Fixtures.JSON.createArrayNode();
            v.forEach(x -> out.add(sortKeys(x)));
            return out;
        }
        return v;
    }

    private static List<String> dump(Path project, String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (var c = Engine.open(project, 1); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM " + table + " ORDER BY ALL")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    row.append(rs.getString(i)).append('|');
                }
                out.add(row.toString());
            }
        }
        return out;
    }
}
