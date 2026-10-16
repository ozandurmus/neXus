package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.ReportModel;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Path detectors on frozen reference inputs (tools/packet_analysis/parity/export_detb_fixture.py): every lane B
 * stage's return value and report section equal the reference at DuckDB threads=1; the same input with threads=1
 * and threads=4 gives identical report text and stage outputs. The proxy fixture needs tshark (application index).
 */
class PathParityTest {
    private static final Pattern HEX32 = Pattern.compile("\\b[0-9a-f]{32}\\b");

    @TempDir
    Path dir;

    record Run(String text, JsonNode report, Map<String, JsonNode> stages) {
    }

    private Path project(JsonNode fixture, String name) throws Exception {
        Path db = Fixtures.project(fixture, dir, name + ".duckdb");
        if (fixture.get("files").isEmpty()) {
            return db;
        }
        Path wire = Files.createDirectories(dir.resolve(name + "-wire"));
        try (Connection c = Engine.open(db, 1); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT id,path,identity FROM captures")) {
            List<String[]> updates = new ArrayList<>();
            while (rs.next()) {
                Path file = wire.resolve(rs.getString(2));
                Files.write(file, Base64.getDecoder().decode(fixture.get("files").get(rs.getString(2)).asText()));
                ArrayNode identity = (ArrayNode) Fixtures.JSON.readTree(rs.getString(3));
                identity.set(0, TextNode.valueOf(file.toString()));
                Files.setLastModifiedTime(file, FileTime.from(identity.get(2).asLong(), TimeUnit.NANOSECONDS));
                updates.add(new String[] {file.toString(), Fixtures.JSON.writeValueAsString(identity), rs.getString(1)});
            }
            for (String[] u : updates) {
                try (var ps = c.prepareStatement("UPDATE captures SET path=?,identity=? WHERE id=?")) {
                    ps.setString(1, u[0]);
                    ps.setString(2, u[1]);
                    ps.setString(3, u[2]);
                    ps.execute();
                }
            }
        }
        return db;
    }

    private Run run(JsonNode fixture, String name, int threads) throws Exception {
        Path db = project(fixture, name);
        Path parity = dir.resolve(name + "-parity.duckdb");
        String text;
        try (Db d = new Db(Engine.open(db, threads))) {
            Topology topology = Topology.parse(fixture.get("topology"));
            Map<String, Object> report = Engine.analyze(d, topology, parity);
            report.put("generated_at", "<masked>");
            text = ReportModel.toJson(report);
            // The post-analysis exports the parity harness records (transport profiles, proxy waterfall).
            System.setProperty("packetanalysis.parity.detb", "true");
            try {
                Parity.recordDetB(d, topology, report, parity);
            } finally {
                System.clearProperty("packetanalysis.parity.detb");
            }
        }
        Map<String, JsonNode> stages = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + parity); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT stage,value FROM detectors")) {
            while (rs.next()) {
                stages.put(rs.getString(1), Fixtures.parse(rs.getString(2)));
            }
        }
        return new Run(text, Fixtures.parse(text), stages);
    }

    /** Local-time labels depend on the host zone; everything else is compared as is. */
    private static JsonNode withoutLocalTime(JsonNode v) {
        JsonNode c = v.deepCopy();
        strip(c);
        return c;
    }

    private static void strip(JsonNode v) {
        if (v.isObject()) {
            if (v.has("local") && v.has("utc")) {
                ((ObjectNode) v).remove("local");
            }
            v.forEach(PathParityTest::strip);
        } else if (v.isArray()) {
            v.forEach(PathParityTest::strip);
        }
    }

    /** det_b_compare.order_free: the lists of a stage output as multisets (integrity id counter dropped). */
    private static List<String> orderFree(JsonNode v) {
        List<String> out = new ArrayList<>();
        for (JsonNode list : v) {
            List<String> items = new ArrayList<>();
            for (JsonNode x : list) {
                JsonNode copy = x.deepCopy();
                if (copy.has("id") && copy.get("id").asText().startsWith("integrity:")) {
                    ((ObjectNode) copy).put("id", copy.get("id").asText().replaceAll(":\\d+$", ""));
                }
                items.add(copy.toString());
            }
            items.sort(null);
            out.add(String.join("\n", items));
        }
        return out;
    }

    /** core_compare.canon_section for the lane B sections: capture ids to names, random ids masked. */
    private static JsonNode canonSection(String section, JsonNode value, JsonNode names) {
        JsonNode v = rename(value, names);
        if (section.equals("tunnel_points")) {
            v = Fixtures.canonical("tunnel_points", v);
        }
        return v;
    }

    private static JsonNode rename(JsonNode v, JsonNode names) {
        if (v.isTextual()) {
            String s = names.has(v.asText()) ? names.get(v.asText()).asText() : v.asText();
            return TextNode.valueOf(HEX32.matcher(s).replaceAll("<id>"));
        }
        if (v.isArray()) {
            ArrayNode out = Fixtures.JSON.createArrayNode();
            v.forEach(x -> out.add(rename(x, names)));
            return out;
        }
        if (v.isObject()) {
            ObjectNode out = Fixtures.JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = names.has(e.getKey()) ? names.get(e.getKey()).asText() : e.getKey();
                out.set(HEX32.matcher(k).replaceAll("<id>"), rename(e.getValue(), names));
            }
            return out;
        }
        return v;
    }

    private static void assertStages(JsonNode fixture, Run java, List<String> orderOnly, List<String> skip) {
        for (Iterator<Map.Entry<String, JsonNode>> it = fixture.get("outputs").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String stage = e.getKey();
            if (skip.contains(stage)) {
                continue;
            }
            assertTrue(java.stages().containsKey(stage), stage);
            JsonNode a = withoutLocalTime(e.getValue());
            JsonNode b = withoutLocalTime(java.stages().get(stage));
            if (orderOnly.contains(stage)) {
                assertEquals(orderFree(a), orderFree(b), stage);
            } else {
                assertEquals(a.toString(), b.toString(), stage);
            }
        }
    }

    private static void assertSections(JsonNode fixture, Run java, List<String> orderOnly) {
        for (Iterator<Map.Entry<String, JsonNode>> it = fixture.get("sections").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode b = canonSection(e.getKey(), java.report().get(e.getKey()), fixture.get("capture_names"));
            if (orderOnly.contains(e.getKey())) {
                ArrayNode wrapA = Fixtures.JSON.createArrayNode().add(e.getValue());
                ArrayNode wrapB = Fixtures.JSON.createArrayNode().add(b);
                assertEquals(orderFree(wrapA), orderFree(wrapB), e.getKey());
            } else {
                assertEquals(e.getValue().toString(), b.toString(), e.getKey());
            }
        }
    }

    @Test
    void integrityStagesAndSectionsEqualTheReference() throws Exception {
        JsonNode fixture = Fixtures.load("detb_integrity_all");
        Run java = run(fixture, "integrity", 1);
        assertStages(fixture, java, List.of(), List.of());
        assertSections(fixture, java, List.of());
        // The fixture holds every check (exact device attribution per fault location, negative guards included).
        JsonNode integrity = fixture.get("outputs").get("integrity").get(0);
        assertTrue(integrity.size() >= 20);
        boolean claims = false;
        for (JsonNode f : integrity) {
            claims |= f.get("confidence").asText().equals("supported") && !f.get("severity").asText().equals("quality");
        }
        assertTrue(claims);
    }

    /** test_declared_alg_and_unknown_clock_do_not_claim_modification, negative cases on the frozen input. */
    @Test
    void declaredAlgAndUnknownClockDoNotClaimModification() throws Exception {
        JsonNode fixture = Fixtures.load("detb_integrity_all");
        ObjectNode declared = fixture.get("topology").deepCopy();
        for (JsonNode p : declared.get("points")) {
            if (p.get("device").asText().equals("FW")) {
                ((ObjectNode) p).put("payload_transform", "alg");
            }
        }
        ((ObjectNode) fixture).set("topology", declared);
        Run alg = run(fixture, "alg", 1);
        JsonNode findings = alg.stages().get("integrity").get(0);
        List<String> devices = new ArrayList<>();
        for (JsonNode f : findings) {
            if (f.get("type").asText().equals("payload_modified")) {
                assertFalse(f.get("device").asText("").equals("FW"), f.toString());
                devices.add(f.get("device").isNull() ? "link" : f.get("device").asText());
            }
        }
        assertTrue(devices.contains("LB") && devices.contains("link"), devices.toString());

        ObjectNode uncertain = Fixtures.load("detb_integrity_all").get("topology").deepCopy();
        ObjectNode overrides = uncertain.putObject("clock_overrides");
        for (JsonNode p : uncertain.get("points")) {
            overrides.putObject(p.get("capture_id").asText()).put("offset_ms", 0);
        }
        ((ObjectNode) fixture).set("topology", uncertain);
        Run unknownClock = run(fixture, "clock", 1);
        for (JsonNode f : unknownClock.stages().get("integrity").get(0)) {
            boolean claim = List.of("reset_origin", "payload_modified", "mtu_black_hole").contains(f.get("type").asText())
                    && f.get("confidence").asText().equals("supported") && !f.get("severity").asText().equals("quality");
            assertFalse(claim, f.toString());
        }
    }

    @Test
    void integritySameOutputAcrossThreadCounts() throws Exception {
        JsonNode fixture = Fixtures.load("detb_integrity_all");
        Run one = run(fixture, "one", 1);
        Run four = run(fixture, "four", 4);
        assertEquals(one.text(), four.text());
        assertEquals(one.stages().toString(), four.stages().toString());
    }

    private static boolean tshark() {
        try {
            TsharkRunner.find(null);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Test
    void proxyTlsAndApplicationIndexEqualTheReference() throws Exception {
        assumeTrue(tshark(), "tshark is required for the application index");
        JsonNode fixture = Fixtures.load("detb_proxy_zero_ipv4");
        Run java = run(fixture, "proxy", 1);
        // Recorded tie-breaks (DETERMINISM.md): endpoint_packets and TLS facts order; group_downstream_findings
        // carries the core first_event tie at ts=1700000000.013.
        assertStages(fixture, java, List.of("integrity", "tls_checks"), List.of("group_downstream_findings"));
        assertSections(fixture, java, List.of("tls"));
        JsonNode proxies = java.report().get("proxies");
        assertTrue(proxies.get("transaction_count").asLong() > 0);
        assertTrue(proxies.get("leg_count").asLong() > 0);
        assertTrue(java.report().get("application_status").size() > 0);
    }

    /** capture_layers decode (tshark) + fragment normalization + fragment_findings on the reference fixture. */
    @Test
    void fragmentLayersAndFindingsEqualTheReference() throws Exception {
        assumeTrue(tshark(), "tshark is required for the capture-layer decode");
        JsonNode fixture = Fixtures.load("detb_fragments_ipv4_zero");
        Run java = run(fixture, "fragments", 1);
        assertStages(fixture, java, List.of(), List.of());
        assertSections(fixture, java, List.of());
        assertEquals(5, java.stages().get("fragment_findings").get(0).size());
        Run four = run(fixture, "fragments4", 4);
        assertEquals(java.text(), four.text());
    }

    @Test
    void proxySameOutputAcrossThreadCounts() throws Exception {
        assumeTrue(tshark(), "tshark is required for the application index");
        JsonNode fixture = Fixtures.load("detb_proxy_zero_ipv4");
        Run one = run(fixture, "one", 1);
        Run four = run(fixture, "four", 4);
        assertEquals(one.text(), four.text());
        assertEquals(one.stages().toString(), four.stages().toString());
    }
}
