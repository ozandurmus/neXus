package com.securityexpert.nexus.ui2.packetanalysis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Evidence;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.PacketParser;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.PacketColumns;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of reference {@code tests/test_export.py}, {@code tests/test_field_diff.py},
 * {@code tests/test_text_encoding.py}, and export checks from {@code tests/test_incident_viz.py},
 * {@code tests/test_real_case_2.py}, and {@code tests/test_paloalto.py} (negative cases first).
 */
class ExportAndFieldDiffTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmpDir;

    private static Map<String, Object> parsePacketMap(List<String> values, String captureId, int prefixBytes,
            List<String> fields) {
        Object[] parsed = PacketParser.parse(values, captureId, prefixBytes, fields);
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < PacketColumns.NAMES.size(); i++) {
            out.put(PacketColumns.NAMES.get(i), parsed[i]);
        }
        return out;
    }

    private static Path loadAnalyzedFixture(String name, Path dir, String dbFile) throws Exception {
        JsonNode fixture;
        try (InputStream in = ExportAndFieldDiffTest.class.getResourceAsStream(
                "/packetanalysis/engine/" + name + ".json.gz")) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            fixture = JSON.readTree(new GZIPInputStream(in));
        }
        Path dbPath = dir.resolve(dbFile);
        try (Connection c = Engine.open(dbPath, 1)) {
            for (Iterator<Map.Entry<String, JsonNode>> it = fixture.get("tables").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> t = it.next();
                JsonNode columns = t.getValue().get("columns");
                List<String> defs = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (JsonNode col : columns) {
                    String colName = col.get(0).asText();
                    String colType = col.get(1).asText();
                    String pk = (t.getKey().equals("settings") && colName.equals("key")) ? " PRIMARY KEY" : "";
                    defs.add("\"" + colName + "\" " + colType + pk);
                    types.add(colType);
                }
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE \"" + t.getKey() + "\" (" + String.join(",", defs) + ")");
                }
                String marks = String.join(",", Collections.nCopies(types.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO \"" + t.getKey() + "\" VALUES (" + marks + ")")) {
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
        try (Db db = new Db(Engine.open(dbPath, 1))) {
            Engine.analyze(db, Topology.parse(fixture.get("topology")), null);
        }
        return dbPath;
    }

    @Test
    void negativeMissingAnalysisAndInvalidFindingsRejected() {
        ProjectStore empty = ProjectStore.open(tmpDir.resolve("empty"), ProjectStore.Settings.defaults(null));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> Export.exportData(empty));
        assertTrue(ex.getMessage().contains("Run analysis"));

        // Confirmed device drop finding requires both device and evidence_stage (test_paloalto.py).
        Map<String, Object> dropFinding = new LinkedHashMap<>();
        dropFinding.put("id", "drop:1");
        dropFinding.put("type", "confirmed_device_drop");
        dropFinding.put("hop", "PA");
        dropFinding.put("device", null);
        dropFinding.put("evidence_stage", "drop");
        dropFinding.put("direction", "forward");
        dropFinding.put("severity", "high");
        dropFinding.put("confidence", "supported");
        dropFinding.put("time_range", List.of(1.0, 2.0));
        dropFinding.put("metrics", Map.of("count", 1));
        dropFinding.put("summary", "Confirmed drop");
        dropFinding.put("cause", "Confirmed device drop on PA");
        dropFinding.put("evidence_refs", List.of(Map.of("frame", 1)));
        IllegalArgumentException dropEx = assertThrows(
                IllegalArgumentException.class, () -> Export.Finding.validate(dropFinding));
        assertTrue(dropEx.getMessage().contains("device and evidence_stage"));

        // Supported finding works when device and evidence_stage are present.
        dropFinding.put("device", "PA");
        assertEquals("PA", Export.Finding.validate(dropFinding).get("device"));

        // Unsupported export format rejected.
        assertThrows(IllegalArgumentException.class, () -> EngineExport.exportTables(empty, "xml"));
        assertThrows(IllegalArgumentException.class, () -> ExportCli.run(
                new PrintStream(new ByteArrayOutputStream()),
                "--project", empty.path().toString(),
                "--format", "csv"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fieldDiffFullHashNotPrefixAndTruncation() throws Exception {
        List<String> fields = List.of(
                "frame.number",
                "frame.time_epoch",
                "ip.src",
                "ip.dst",
                "tcp.srcport",
                "tcp.dstport",
                "tcp.len",
                "tcp.payload",
                "frame.len",
                "frame.cap_len",
                "tcp.options.wscale.shift");
        byte[] payload = ("a".repeat(64) + "changed past prefix").getBytes(StandardCharsets.UTF_8);
        List<String> row = new ArrayList<>(List.of(
                "1",
                "1700000000",
                "192.0.2.1",
                "192.0.2.2",
                "50000",
                "80",
                String.valueOf(payload.length),
                HexFormat.of().formatHex(payload),
                "140",
                "140",
                "0"));
        Map<String, Object> packet = parsePacketMap(row, "capture", 64, fields);
        Map<String, Object> decoded = (Map<String, Object>) PyJson.loads((String) packet.get("path_fields"));
        String expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
        assertEquals(expectedSha, decoded.get("payload_sha256"));
        assertEquals(0L, ((Number) decoded.get("window_scale")).longValue());
        assertEquals(Boolean.TRUE, decoded.get("payload_complete"));

        // Truncated capture: cap_len (100) < frame.len (140) clears payload_sha256 in FieldDiff.values.
        row.set(row.size() - 2, "100");
        Map<String, Object> truncated = parsePacketMap(row, "capture", 64, fields);
        truncated.put("raw_seq", 0L);
        assertNull(FieldDiff.values(truncated).get("payload_sha256"));
    }

    @Test
    void fieldComparisonFollowsPythonEquality() {
        // Python: True == 1, False == 0, 1 == 1.0, and a missing value never compares equal to a present one.
        assertTrue(FieldDiff.pyEquals(Boolean.TRUE, 1L));
        assertTrue(FieldDiff.pyEquals(0L, Boolean.FALSE));
        assertTrue(FieldDiff.pyEquals(1L, 1.0d));
        assertFalse(FieldDiff.pyEquals(2L, Boolean.TRUE));
        assertFalse(FieldDiff.pyEquals(null, 0L));
        assertTrue(FieldDiff.pyEquals(List.of(1L, Boolean.TRUE), List.of(Boolean.TRUE, 1L)));
    }

    @Test
    void valuesRejectsMissingOperandsLikeTheReference() {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("proto", "TCP");
        packet.put("src", "192.0.2.1");
        packet.put("seq", 1L);
        packet.put("raw_seq", 5L);
        packet.put("mss", 1460L);
        packet.put("path_fields", "{}");
        // flags is None in the reference: `packet["flags"] & 2` is a TypeError, never a silent zero
        packet.put("flags", null);
        assertThrows(IllegalStateException.class, () -> FieldDiff.values(packet));
        packet.put("flags", 2L);
        assertEquals(4L, FieldDiff.values(packet).get("seq_offset"));
        assertEquals(1460L, FieldDiff.values(packet).get("mss"));
        packet.put("flags", 16L);
        assertNull(FieldDiff.values(packet).get("mss"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void icmpErrorKeepsQuotedTcpOutOfOuterIdentity() {
        List<String> fields = List.of(
                "frame.number",
                "frame.time_epoch",
                "ip.src",
                "ip.dst",
                "ip.proto",
                "icmp.type",
                "icmp.code",
                "icmp.mtu",
                "tcp.srcport",
                "tcp.dstport",
                "tcp.seq_raw",
                "frame.len",
                "frame.cap_len");
        List<String> row = List.of(
                "1",
                "1700000000",
                "192.0.2.254,192.0.2.1",
                "192.0.2.1,192.0.2.2",
                "1,6",
                "3",
                "4",
                "1280",
                "50000",
                "80",
                "123",
                "100",
                "100");
        Map<String, Object> packet = parsePacketMap(row, "capture", 64, fields);
        assertEquals("ICMP", packet.get("proto"));
        assertEquals(0L, ((Number) packet.get("sport")).longValue());
        assertEquals(0L, ((Number) packet.get("seq")).longValue());
        Map<String, Object> values = (Map<String, Object>) PyJson.loads((String) packet.get("path_fields"));
        assertEquals(1280L, ((Number) values.get("icmp_mtu")).longValue());
        Map<String, Object> expectedQuoted = new LinkedHashMap<>();
        expectedQuoted.put("src", "192.0.2.1");
        expectedQuoted.put("dst", "192.0.2.2");
        expectedQuoted.put("sport", 50000L);
        expectedQuoted.put("dport", 80L);
        expectedQuoted.put("seq", 123L);
        assertEquals(expectedQuoted, values.get("quoted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void matchedFieldDiffEvidence() throws Exception {
        Path dbPath = loadAnalyzedFixture("det_a_onset_loss", tmpDir, "diff.duckdb");
        try (Db db = new Db(Engine.open(dbPath, 1))) {
            String key = (String) db.scalar(
                    "SELECT packet_key FROM obs WHERE eligible GROUP BY packet_key HAVING count(*)>2 ORDER BY packet_key LIMIT 1");
            Map<String, Object> result = FieldDiff.packetDiff(db, key);
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
            assertFalse(items.isEmpty());
            for (Map<String, Object> item : items) {
                Map<String, Object> fields = (Map<String, Object>) item.get("fields");
                Map<String, Object> srcField = (Map<String, Object>) fields.get("src");
                assertEquals("unchanged", srcField.get("status"));
                assertFalse(((String) item.get("tooltip")).isEmpty());
                List<Map<String, Object>> evidence = (List<Map<String, Object>> ) item.get("evidence");
                assertEquals(2, evidence.size());
                for (Map<String, Object> ref : evidence) {
                    assertFalse(((String) ref.get("content_filter")).isEmpty());
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void offlineExportSchemaEscapeAndAllFindings() throws Exception {
        Path dbPath = loadAnalyzedFixture("det_a_onset_loss", tmpDir, "export.duckdb");
        try (Db db = new Db(Engine.open(dbPath, 1))) {
            Map<String, Object> data = Export.exportData(db);
            assertEquals(11L, ((Number) Export.FindingsExport.validate(data).get("schema_version")).longValue());
            List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
            assertFalse(findings.isEmpty());
            assertTrue(findings.get(0).keySet().containsAll(
                    Set.of("type", "hop", "time_range", "metrics", "evidence_refs", "confidence")));
            Map<String, Object> ts = (Map<String, Object>) data.get("timeseries");
            assertFalse(((List<?>) ts.get("items")).isEmpty());
            assertFalse(((List<?>) data.get("flow_filters")).isEmpty());
            assertFalse(PyJsonWriter.dumps(data, false).contains("\"prefix\":"));

            String malicious = "</script><img src=\"https://invalid.example/steal\">&";
            Map<String, Object> report = (Map<String, Object>) data.get("report");
            List<Map<String, Object>> segments = (List<Map<String, Object>>) report.get("segments");
            segments.get(0).put("label", malicious);

            Map<String, Object> onsets = (Map<String, Object>) report.get("onsets");
            Map<String, Object> senderOnset = new LinkedHashMap<>();
            senderOnset.put("segment", "sender");
            senderOnset.put("metric", "retrans_percent");
            senderOnset.put("scope", "capture_signal");
            senderOnset.put("time", 1);
            senderOnset.put("explanation", "symptom observed here");
            Map<String, Object> suspectOnset = new LinkedHashMap<>();
            suspectOnset.put("segment", "suspect");
            suspectOnset.put("metric", "loss_percent");
            suspectOnset.put("scope", "network_segment");
            suspectOnset.put("time", 2);
            suspectOnset.put("explanation", "prime loss");
            onsets.put("items", List.of(senderOnset, suspectOnset));
            onsets.put("directions", Map.of("forward", Map.of("prime_suspects", List.of("suspect"))));

            String html = Export.htmlReport(data);
            assertTrue(html.indexOf("<h3>suspect") < html.indexOf("<h3>sender"));
            assertTrue(html.contains("article class='symptom'"));

            // Count <script tags and inspect HTML tag src/href attributes outside <script> bodies.
            int scriptCount = 0;
            Matcher scriptMatcher = Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE).matcher(html);
            while (scriptMatcher.find()) {
                scriptCount++;
            }
            assertEquals(2, scriptCount, "Fixed code and escaped inert JSON only");

            String nonScriptHtml = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "");
            Matcher refMatcher = Pattern.compile("\\b(?:src|href)\\s*=\\s*\"([^\"]*)\"").matcher(nonScriptHtml);
            List<String> refs = new ArrayList<>();
            while (refMatcher.find()) {
                refs.add(refMatcher.group(1));
            }
            assertFalse(refs.isEmpty());
            for (String ref : refs) {
                assertFalse(ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("//"));
                assertTrue(ref.startsWith("#"));
            }
            assertFalse(html.contains(malicious));
            assertTrue(html.contains("&lt;/script&gt;"));
            assertTrue(html.contains("connect-src 'none'"));
            assertTrue(html.contains("r.coverage==='not capturing'"));

            // Over 200 findings are preserved in export_data via export_findings setting.
            List<Map<String, Object>> many = new ArrayList<>();
            for (int i = 0; i < 201; i++) {
                Map<String, Object> copy = new LinkedHashMap<>(findings.get(0));
                copy.put("id", String.valueOf(i));
                copy.put("evidence", copy.remove("evidence_refs"));
                many.add(copy);
            }
            db.exec("DELETE FROM settings WHERE key = 'export_findings'");
            db.exec("INSERT INTO settings VALUES ('export_findings', ?)", PyJsonWriter.dumps(many, false));
            assertEquals(201, ((List<?>) Export.exportData(db).get("findings")).size());
        }
    }

    @Test
    void offlineHtmlWithCp1252IncompatibleAssetAndUnicodeReport() throws Exception {
        // Prove the bundled echarts-offline.js asset contains bytes that fail windows-1252 decoding.
        byte[] echartsBytes;
        try (InputStream in = Export.class.getResourceAsStream(
                "/packetanalysis/export/echarts-offline.js.gz")) {
            echartsBytes = new GZIPInputStream(in).readAllBytes();
        }
        var cp1252Decoder = java.nio.charset.Charset.forName("windows-1252")
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        assertThrows(CharacterCodingException.class, () -> cp1252Decoder.decode(ByteBuffer.wrap(echartsBytes)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("verdict", "Unicode fixture: ağ → ✓");
        report.put("segments", List.of());
        report.put("window", Map.of());
        report.put("clocks", Map.of());
        report.put("incident", Map.of("timeline", List.of()));
        report.put("onsets", Map.of());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("report", report);
        data.put("findings", List.of());
        data.put("flow_filters", List.of());
        data.put("engine_version", "fixture");
        data.put("schema_version", 11);
        data.put("exported_at", "fixture");

        String html = Export.htmlReport(data);
        assertTrue(html.contains("ağ → ✓"));
        assertFalse(html.contains("https://cdn"));
    }

    @Test
    void engineExportCsvAndXlsxTablesAndCli() throws Exception {
        Path dbPath = loadAnalyzedFixture("healthy", tmpDir, "healthy.duckdb");
        try (Db db = new Db(Engine.open(dbPath, 1))) {
            byte[] csvZip = EngineExport.exportTables(db, "csv");
            Map<String, String> csvFiles = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(csvZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    csvFiles.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(9, csvFiles.size());
            assertTrue(csvFiles.containsKey("top_cycles.csv"));
            assertTrue(csvFiles.containsKey("flow_port.csv"));
            assertTrue(csvFiles.containsKey("README.json"));
            assertTrue(csvFiles.get("flow_port.csv").lines().findFirst().orElse("").contains("before_p95_ms"));
            assertTrue(csvFiles.get("top_cycles.csv").lines().findFirst().orElse("").contains("period"));

            byte[] xlsxBytes = EngineExport.exportTables(db, "xlsx");
            Map<String, byte[]> xlsxEntries = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xlsxBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    xlsxEntries.put(entry.getName(), zis.readAllBytes());
                }
            }
            assertTrue(xlsxEntries.size() >= 12);
            var dbf = DocumentBuilderFactory.newInstance();
            for (Map.Entry<String, byte[]> entry : xlsxEntries.entrySet()) {
                if (entry.getKey().endsWith(".xml") || entry.getKey().endsWith(".rels")) {
                    dbf.newDocumentBuilder().parse(new ByteArrayInputStream(entry.getValue()));
                }
            }
        }

        // Test ExportCli JSON, HTML, CSV, XLSX and no-overwrite guarantee.
        Path jsonOut = tmpDir.resolve("report.json");
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ExportCli.run(new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                "--project", dbPath.toString(),
                "--format", "json",
                "-o", jsonOut.toString());
        JsonNode exportedJson = JSON.readTree(Files.readString(jsonOut, StandardCharsets.UTF_8));
        assertEquals(11, exportedJson.get("schema_version").asInt());

        byte[] before = Files.readAllBytes(jsonOut);
        assertThrows(FileAlreadyExistsException.class, () ->
                ExportCli.run(new PrintStream(new ByteArrayOutputStream()),
                        "--project", dbPath.toString(),
                        "--format", "json",
                        "-o", jsonOut.toString()));
        assertEquals( before.length, Files.readAllBytes(jsonOut).length);

        ByteArrayOutputStream htmlStdout = new ByteArrayOutputStream();
        ExportCli.run(new PrintStream(htmlStdout, true, StandardCharsets.UTF_8),
                "--project", dbPath.toString(),
                "--format", "html");
        String htmlText = htmlStdout.toString(StandardCharsets.UTF_8);
        assertTrue(htmlText.contains("<canvas id=\"map\""));
        assertTrue(htmlText.contains("Incident timeline"));
        assertTrue(htmlText.contains("Where was the time added"));
        assertTrue(htmlText.contains("Hold and release"));
        assertTrue(htmlText.contains("Capture quality"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameInputSameExportAcrossThreadCounts() throws Exception {
        Path one = loadAnalyzedFixture("demo_ipid_zero_nat_confirmed", tmpDir, "one.duckdb");
        Path four = tmpDir.resolve("four.duckdb");
        Files.copy(one, four);
        try (Db db1 = new Db(Engine.open(one, 1)); Db db4 = new Db(Engine.open(four, 4))) {
            Map<String, Object> d1 = Export.exportData(db1);
            Map<String, Object> d4 = Export.exportData(db4);
            d1.put("exported_at", "<masked>");
            d4.put("exported_at", "<masked>");
            ((Map<String, Object>) d1.get("report")).put("generated_at", "<masked>");
            ((Map<String, Object>) d4.get("report")).put("generated_at", "<masked>");
            assertEquals(PyJsonWriter.dumps(d1, true), PyJsonWriter.dumps(d4, true));
            assertEquals(Export.htmlReport(d1), Export.htmlReport(d4));
            assertEquals(
                    HexFormat.of().formatHex(EngineExport.exportTables(db1, "csv")),
                    HexFormat.of().formatHex(EngineExport.exportTables(db4, "csv")));
            assertEquals(
                    HexFormat.of().formatHex(EngineExport.exportTables(db1, "xlsx")),
                    HexFormat.of().formatHex(EngineExport.exportTables(db4, "xlsx")));
        }
    }
}
