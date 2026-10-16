package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Synthetic analysed case databases for the expert report tests: the frozen engine fixtures
 * ({@code src/test/resources/packetanalysis/engine/*.json.gz}, documentation-range addresses) run through the engine at a
 * given DuckDB thread count. Never a real capture.
 */
final class ExpertReportFixtures {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExpertReportFixtures() {
    }

    static JsonNode load(String name) throws Exception {
        try (InputStream in = ExpertReportFixtures.class.getResourceAsStream("/packetanalysis/engine/" + name + ".json.gz")) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return JSON.readTree(new GZIPInputStream(in));
        }
    }

    /** Creates {@code dir/file}, loads the fixture's base tables, analyses with {@code threads} and stores report and topology. */
    @SuppressWarnings("unchecked")
    static Path analysed(String name, Path dir, String file, int threads) throws Exception {
        JsonNode fixture = load(name);
        Path db = dir.resolve(file);
        try (Connection c = Engine.open(db, threads)) {
            for (Iterator<Map.Entry<String, JsonNode>> it = fixture.get("tables").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> t = it.next();
                List<String> defs = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (JsonNode col : t.getValue().get("columns")) {
                    String colName = col.get(0).asText();
                    String colType = col.get(1).asText();
                    String pk = t.getKey().equals("settings") && colName.equals("key") ? " PRIMARY KEY" : "";
                    defs.add("\"" + colName + "\" " + colType + pk);
                    types.add(colType);
                }
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE \"" + t.getKey() + "\" (" + String.join(",", defs) + ")");
                }
                String marks = String.join(",", Collections.nCopies(types.size(), "?"));
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
        try (Db engineDb = new Db(Engine.open(db, threads))) {
            Engine.analyze(engineDb, Topology.parse(fixture.get("topology")), null);
            engineDb.exec("INSERT OR REPLACE INTO settings VALUES ('topology', ?)",
                    PyJsonWriter.dumps(JSON.convertValue(fixture.get("topology"), Map.class), false));
        }
        return db;
    }
}
