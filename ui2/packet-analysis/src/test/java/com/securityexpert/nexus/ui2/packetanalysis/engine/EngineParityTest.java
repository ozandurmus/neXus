package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.ReportModel;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * L3 golden parity on frozen reference inputs (the reference core-only sections at DuckDB threads=1), and
 * determinism: the same input with threads=1 and threads=4 gives byte-identical report text and core tables.
 */
class EngineParityTest {
    static final List<String> SECTIONS = List.of("verdict", "window", "clocks", "coverage", "quality", "segments",
            "findings", "finding_count", "flow_count", "nat_suggestions", "limitations", "analysis_scope",
            "sequence_translations", "offload_points", "prefilter_excluded", "auto_order_suggestion", "tunnel_points");
    static final java.util.Set<String> CORE_FINDING_TYPES = java.util.Set.of("recovered_loss", "impactful_loss",
            "unrecovered_loss", "handshake_blocked", "capture_miss", "unknown", "confirmed_device_drop");

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "nat", "offload", "healthy", "demo_ipid_zero_nat_confirmed"})
    void coreSectionsEqualTheReference(String name) throws Exception {
        JsonNode fixture = Fixtures.load(name);
        Path project = Fixtures.project(fixture, dir, "p.duckdb");
        // The reference fixture is the core-only report, taken right after add_headlines; later detectors
        // (onset.add_onsets, symptoms.label_retransmissions) extend segment headlines in both engines. The Java
        // headline is therefore taken at the same point: after the "headlines" stage of this very run.
        Map<String, Object> coreHeadlines = new LinkedHashMap<>();
        String text;
        try (Db db = new Db(Engine.open(project, 1))) {
            Map<String, Object> out = Engine.analyze(db, Topology.parse(fixture.get("topology")), null, (stage, ctx) -> {
                if (stage.name().equals("headlines")) {
                    for (Map<String, Object> segment : ctx.segments) {
                        coreHeadlines.put((String) segment.get("id"), segment.get("headline"));
                    }
                }
            });
            out.put("generated_at", "<masked>");
            text = ReportModel.toJson(out);
        }
        assertFalse(coreHeadlines.isEmpty(), name + ": headlines stage ran");
        JsonNode report = Fixtures.parse(text);
        JsonNode reference = fixture.get("reference_core");
        com.fasterxml.jackson.databind.node.ArrayNode coreFindings = Fixtures.JSON.createArrayNode();
        for (JsonNode f : report.get("findings")) {
            if (CORE_FINDING_TYPES.contains(f.get("type").asText())) {
                coreFindings.add(f);
            }
        }
        for (String section : SECTIONS) {
            JsonNode actual = switch (section) {
                // Detector lanes add their own findings: only the core finding types are compared, and counted.
                case "findings" -> coreProjection(section, coreFindings, reference.get(section), coreHeadlines);
                case "finding_count" -> Fixtures.JSON.getNodeFactory().numberNode(coreFindings.size());
                default -> coreProjection(section, report.get(section), reference.get(section), coreHeadlines);
            };
            assertEquals(Fixtures.canonical(section, reference.get(section)).toString(),
                    Fixtures.canonical(section, actual).toString(), name + ": " + section);
        }
    }

    /**
     * The Java report restricted to what the core-only reference contains: keys and findings that only the
     * secondary detectors add are dropped, and each segment headline is the Java headline after the headlines stage.
     */
    private static JsonNode coreProjection(String section, JsonNode actual, JsonNode refCore,
            Map<String, Object> coreHeadlines) {
        if (section.equals("segments") && actual.isArray() && refCore.isArray() && actual.size() == refCore.size()) {
            var out = Fixtures.JSON.createArrayNode();
            for (int i = 0; i < actual.size(); i++) {
                var s = actual.get(i).deepCopy();
                var r = refCore.get(i);
                if (s.isObject() && r.isObject()) {
                    List<String> extra = new ArrayList<>();
                    s.fieldNames().forEachRemaining(k -> {
                        if (!r.has(k)) {
                            extra.add(k);
                        }
                    });
                    extra.forEach(((com.fasterxml.jackson.databind.node.ObjectNode) s)::remove);
                    String id = s.path("id").asText();
                    if (s.has("headline") && coreHeadlines.containsKey(id)) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) s).set("headline",
                                Fixtures.JSON.valueToTree(coreHeadlines.get(id)));
                    }
                }
                out.add(s);
            }
            return out;
        }
        if (section.equals("findings") && actual.isArray() && refCore.isArray()) {
            var out = Fixtures.JSON.createArrayNode();
            for (JsonNode r : refCore) {
                for (JsonNode a : actual) {
                    if (a.get("id").equals(r.get("id"))) {
                        var copy = a.deepCopy();
                        List<String> extra = new ArrayList<>();
                        copy.fieldNames().forEachRemaining(k -> {
                            if (!r.has(k)) {
                                extra.add(k);
                            }
                        });
                        extra.forEach(((com.fasterxml.jackson.databind.node.ObjectNode) copy)::remove);
                        out.add(copy);
                        break;
                    }
                }
            }
            return out;
        }
        return actual;
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "offload", "demo_ipid_zero_nat_confirmed"})
    void sameInputSameOutputAcrossThreadCounts(String name) throws Exception {
        JsonNode fixture = Fixtures.load(name);
        Path one = Fixtures.project(fixture, dir, "one.duckdb");
        Path four = Fixtures.project(fixture, dir, "four.duckdb");
        String single = Fixtures.run(one, fixture.get("topology"), 1);
        String parallel = Fixtures.run(four, fixture.get("topology"), 4);
        assertEquals(single, parallel, name + ": report text");
        for (String table : List.of("obs", "events", "flow_summary", "flow_filters", "index_scope", "analysis_scope")) {
            assertEquals(dump(one, table), dump(four, table), name + ": table " + table);
        }
        assertTrue(single.contains("\"verdict\""));
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
