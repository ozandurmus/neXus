package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.ReportModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Frozen reference inputs (tools/packet_analysis/parity/export_fixture.py): the base tables of a synthetic
 * project right after reference ingest + topology, the topology, and the reference core-only report sections.
 */
final class Fixtures {
    static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {
    }

    static JsonNode load(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/packetanalysis/engine/" + name + ".json.gz")) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return JSON.readTree(new GZIPInputStream(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A fresh project database holding the fixture's base tables. */
    static Path project(JsonNode fixture, Path dir, String file) throws Exception {
        Path db = dir.resolve(file);
        try (Connection c = Engine.open(db, 1)) {
            for (Iterator<Map.Entry<String, JsonNode>> it = fixture.get("tables").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> t = it.next();
                JsonNode columns = t.getValue().get("columns");
                List<String> defs = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (JsonNode col : columns) {
                    defs.add("\"" + col.get(0).asText() + "\" " + col.get(1).asText());
                    types.add(col.get(1).asText());
                }
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE \"" + t.getKey() + "\" (" + String.join(",", defs) + ")");
                }
                String marks = String.join(",", java.util.Collections.nCopies(types.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO \"" + t.getKey() + "\" VALUES (" + marks + ")")) {
                    for (JsonNode row : t.getValue().get("rows")) {
                        for (int i = 0; i < types.size(); i++) {
                            JsonNode v = row.get(i);
                            String type = types.get(i);
                            if (v.isNull()) {
                                ps.setNull(i + 1, Types.NULL);
                            } else if (type.equals("DOUBLE")) {
                                ps.setDouble(i + 1, v.doubleValue());
                            } else if (type.equals("BIGINT") || type.equals("INTEGER")) {
                                ps.setLong(i + 1, v.longValue());
                            } else if (type.equals("BOOLEAN")) {
                                ps.setBoolean(i + 1, v.booleanValue());
                            } else {
                                ps.setString(i + 1, v.asText());
                            }
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }
        return db;
    }

    static ObjectNode topology(JsonNode fixture) {
        return fixture.get("topology").deepCopy();
    }

    /** Runs the engine; returns the report JSON text (reference json.dumps(indent=2) format). */
    static String run(Path project, JsonNode topology, int threads) throws Exception {
        try (Db db = new Db(Engine.open(project, threads))) {
            Map<String, Object> report = Engine.analyze(db, Topology.parse(topology), null);
            report.put("generated_at", "<masked>");
            return ReportModel.toJson(report);
        }
    }

    static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** core_compare.py canonicalization: GROUP BY-ordered lists by point, count dicts and range lists sorted. */
    static JsonNode canonical(String section, JsonNode value) {
        JsonNode v = value.deepCopy();
        if (List.of("coverage", "quality", "analysis_scope", "offload_points", "tunnel_points").contains(section)) {
            List<JsonNode> items = new ArrayList<>();
            v.forEach(items::add);
            items.sort(Comparator.comparing(x -> x.get("point").asText()));
            ArrayNode out = JSON.createArrayNode();
            items.forEach(out::add);
            v = out;
        }
        if (section.equals("segments")) {
            for (JsonNode s : v) {
                ((ObjectNode) s).set("classes", sortedKeys(s.get("classes")));
                ((ObjectNode) s).set("excluded_counts", sortedKeys(s.get("excluded_counts")));
                for (JsonNode q : s.get("endpoint_quality")) {
                    ((ObjectNode) q).set("excluded", sortedKeys(q.get("excluded")));
                }
            }
        }
        sortRanges(v);
        return v;
    }

    /** Deliberate difference: evidence byte_ranges come from list(DISTINCT ...), unordered in the reference. */
    private static void sortRanges(JsonNode v) {
        if (v.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equals("byte_ranges") && e.getValue().isArray()) {
                    List<JsonNode> items = new ArrayList<>();
                    e.getValue().forEach(items::add);
                    items.sort(Comparator.comparing(JsonNode::toString));
                    ArrayNode out = JSON.createArrayNode();
                    items.forEach(out::add);
                    e.setValue(out);
                } else {
                    sortRanges(e.getValue());
                }
            }
        } else if (v.isArray()) {
            v.forEach(Fixtures::sortRanges);
        }
    }

    private static JsonNode sortedKeys(JsonNode obj) {
        ObjectNode out = JSON.createObjectNode();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        obj.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
        sorted.forEach(out::set);
        return out;
    }
}
