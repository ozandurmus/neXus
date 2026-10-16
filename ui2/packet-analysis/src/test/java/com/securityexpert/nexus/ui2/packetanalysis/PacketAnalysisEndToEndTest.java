package com.securityexpert.nexus.ui2.packetanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The whole pipeline ({@link PacketAnalysisMain}: tshark ingest, topology or automatic case assembly, engine,
 * report and FindingsExport) on three tiny synthetic inputs built at test time by {@link E2eFixtures}; the expected
 * JSON under {@code packetanalysis/e2e/<name>/}
 * is the engine's own output, frozen by {@code tools/packet_analysis/parity/e2e_fixtures.py} only after it was
 * shown identical to the frozen reference (canonical form, every section). Compared in strict form: capture ids as
 * {@code <capture:FILE>}, generation times dropped, other 32-hex ids and private IPv4 literals masked; list order and
 * number types kept.
 * The export's report snapshot must equal the report (without findings) apart from the transport, DNS and
 * field-difference sections the export adds, which are frozen in the expected export.
 * Each fixture also runs with four DuckDB threads and must give the same strict output. Skipped when tshark is
 * not installed.
 */
class PacketAnalysisEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VOLATILE = Set.of("generated_at", "exported_at");
    private static final List<String> EXPORT_SECTIONS = List.of("transport", "dns", "field_diffs");
    private static final Pattern HEX32 = Pattern.compile("(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])");
    private static String tshark;

    @TempDir
    Path tmp;

    @BeforeAll
    static void findTshark() {
        try {
            tshark = TsharkRunner.find(System.getenv("TSHARK"));
        } catch (RuntimeException e) {
            tshark = null;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"folder_b", "chain_loss", "nat_confirm"})
    void pipelineReproducesTheFrozenOutput(String name) throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        Path fixture = resource(name);
        E2eFixtures.Fixture input = E2eFixtures.write(name, tmp.resolve("input-" + name));
        List<Path> files = input.files();
        Path topology = input.topology();
        JsonNode expectedReport = gz(fixture.resolve("expected-report.json.gz"));
        JsonNode expectedExport = gz(fixture.resolve("expected-export.json.gz"));
        for (int threads : new int[] {1, 4}) {
            Path out = tmp.resolve(name + "-t" + threads);
            List<String> args = new ArrayList<>(List.of("--out", out.toString(), "--threads", Integer.toString(threads),
                    "--memory-mb", "4096", "--tshark", tshark, "--deterministic-capture-ids"));
            if (topology == null) {
                args.add(input.dir().toString());
            } else {
                args.addAll(List.of("--topology", topology.toString(), "--no-prefilter"));
                if (input.confirmNat()) {
                    args.add("--confirm-suggested-nat");
                }
                files.forEach(f -> args.add(f.toString()));
            }
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int status = PacketAnalysisMain.run(args.toArray(String[]::new),
                    new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
            assertEquals(0, status, () -> stderr.toString(StandardCharsets.UTF_8));
            Map<String, String> ids = new LinkedHashMap<>();
            JSON.readTree(out.resolve("capture-map.json").toFile()).fields()
                    .forEachRemaining(e -> ids.put(e.getKey(), e.getValue().asText()));
            assertEquals(files.size(), ids.size());
            JsonNode report = strict(JSON.readTree(out.resolve("report.json").toFile()), ids);
            ObjectNode export = (ObjectNode) strict(JSON.readTree(out.resolve("findings-export.json").toFile()), ids);
            assertSame(name + " t" + threads + " report", expectedReport, report);
            // The export's report snapshot is the report without findings plus the sections the export adds.
            ObjectNode snapshot = (ObjectNode) export.get("report");
            ObjectNode added = JsonNodeFactory.instance.objectNode();
            for (String k : EXPORT_SECTIONS) {
                added.set(k, snapshot.get(k));
            }
            ObjectNode rest = snapshot.deepCopy();
            rest.remove(EXPORT_SECTIONS);
            ObjectNode reportWithoutFindings = ((ObjectNode) report).deepCopy();
            reportWithoutFindings.remove("findings");
            assertSame(name + " t" + threads + " export report snapshot", reportWithoutFindings, rest);
            export.set("report", added);
            assertSame(name + " t" + threads + " export", expectedExport, export);
            JsonNode run = JSON.readTree(out.resolve("run.json").toFile());
            assertEquals(threads, run.path("budget").path("duckdb_threads").asInt());
            assertTrue(run.path("analysed").asBoolean());
            // Within the default budget nothing is bounded: the window record says so and the report is untouched.
            assertEquals("full", run.path("analysis_window").path("mode").asText());
            assertTrue(report.path("analysis_window").isMissingNode(), "no window record in a report within budget");
            assertTrue(run.path("budget").path("memory_plan").asText().contains("container 4096 MiB"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chain_loss"})
    void aRangeOverTheBudgetThatNoWindowFitsStopsCleanlyAndAsksForAWindow(String name) throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        E2eFixtures.Fixture input = E2eFixtures.write(name, tmp.resolve("input-" + name));
        Path out = tmp.resolve(name + "-guard");
        List<String> args = new ArrayList<>(List.of("--out", out.toString(), "--threads", "1", "--memory-mb", "4096",
                "--tshark", tshark, "--deterministic-capture-ids", "--max-packets-per-capture", "5",
                "--topology", input.topology().toString(), "--no-prefilter"));
        input.files().forEach(f -> args.add(f.toString()));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = PacketAnalysisMain.run(args.toArray(String[]::new),
                new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals(4, status, () -> stderr.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("ANALYSIS_WINDOW_REQUIRED"));
        JsonNode run = JSON.readTree(out.resolve("run.json").toFile());
        assertEquals(false, run.path("analysed").asBoolean());
        assertEquals("ANALYSIS_WINDOW_REQUIRED", run.path("failure").path("reason").asText());
        assertTrue(run.path("failure").path("message").asText().contains("Choose an analysis window"));
        assertTrue(!Files.exists(out.resolve("report.json")), "a stopped run writes no report");
    }

    private static void assertSame(String what, JsonNode expected, JsonNode actual) {
        if (!expected.equals(actual)) {
            List<String> diffs = new ArrayList<>();
            diff(expected, actual, "$", diffs);
            throw new AssertionError(what + " differs from the frozen output: " + diffs);
        }
    }

    private static void diff(JsonNode a, JsonNode b, String path, List<String> out) {
        if (out.size() >= 8 || a.equals(b)) {
            return;
        }
        if (a.isObject() && b.isObject()) {
            Set<String> keys = new java.util.TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (!a.has(k) || !b.has(k)) {
                    out.add(path + "." + k + (a.has(k) ? " missing" : " unexpected"));
                } else {
                    diff(a.get(k), b.get(k), path + "." + k, out);
                }
            }
        } else if (a.isArray() && b.isArray() && a.size() == b.size()) {
            for (int i = 0; i < a.size(); i++) {
                diff(a.get(i), b.get(i), path + "[" + i + "]", out);
            }
        } else {
            out.add(path + ": " + abbreviate(a) + " != " + abbreviate(b));
        }
    }

    private static String abbreviate(JsonNode n) {
        String s = n.toString();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    /** {@code corpus_run.strict_form}: volatile keys dropped, capture ids named, other 32-hex ids masked. */
    static JsonNode strict(JsonNode n, Map<String, String> ids) {
        if (n.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!VOLATILE.contains(e.getKey())) {
                    out.set(text(e.getKey(), ids), strict(e.getValue(), ids));
                }
            }
            return out;
        }
        if (n.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            n.forEach(x -> out.add(strict(x, ids)));
            return out;
        }
        if (n.isTextual()) {
            return TextNode.valueOf(text(n.asText(), ids));
        }
        return n;
    }

    private static String text(String value, Map<String, String> ids) {
        String v = value;
        if (ids.containsKey(v)) {
            v = "<capture:" + ids.get(v) + ">";
        } else {
            for (Map.Entry<String, String> e : ids.entrySet()) {
                v = v.replace(e.getKey(), "<capture:" + e.getValue() + ">");
            }
        }
        return maskIpv4(HEX32.matcher(v).replaceAll("<id>"));
    }

    /**
     * {@code canonical.mask_for_commit}: IPv4 literals the repository privacy gate classifies as private or link-local
     * (documentation ranges, loopback and unspecified excepted) -> {@code <ipv4:SHA256_12>}.
     */
    static String maskIpv4(String text) {
        Matcher m = IPV4.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String literal = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(flagged(literal) ? "<ipv4:" + sha12(literal) + ">" : literal));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean flagged(String literal) {
        String[] parts = literal.split("\\.");
        long ip = 0;
        for (String p : parts) {
            if (p.length() > 1 && p.startsWith("0")) {
                return false; // ipaddress rejects leading zeros
            }
            int octet = Integer.parseInt(p);
            if (octet > 255) {
                return false;
            }
            ip = ip << 8 | octet;
        }
        if (in(ip, "127.0.0.0", 8) || ip == 0 || in(ip, "192.0.2.0", 24) || in(ip, "198.51.100.0", 24)
                || in(ip, "203.0.113.0", 24)) {
            return false;
        }
        if (in(ip, "169.254.0.0", 16)) {
            return true;
        }
        // ipaddress.IPv4Address.is_private (CPython 3.12): the private networks minus the two global exceptions.
        if (literal.equals("192.0.0.9") || literal.equals("192.0.0.10")) {
            return false;
        }
        return in(ip, "0.0.0.0", 8) || in(ip, "10.0.0.0", 8) || in(ip, "172.16.0.0", 12) || in(ip, "192.0.0.0", 29)
                || in(ip, "192.0.0.170", 31) || in(ip, "192.168.0.0", 16) || in(ip, "198.18.0.0", 15)
                || in(ip, "240.0.0.0", 4) || ip == 0xFFFFFFFFL;
    }

    private static boolean in(long ip, String network, int prefix) {
        long net = 0;
        for (String p : network.split("\\.")) {
            net = net << 8 | Integer.parseInt(p);
        }
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (ip & mask) == (net & mask);
    }

    private static String sha12(String literal) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(literal.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode gz(Path path) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return JSON.readTree(in);
        }
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(PacketAnalysisEndToEndTest.class.getResource("/packetanalysis/e2e/" + name + "/expected-report.json.gz")
                .toURI()).getParent();
    }
}
