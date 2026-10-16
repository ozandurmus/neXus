package com.securityexpert.nexus.ui2.packetanalysis.privacy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.privacy.PayloadFields.Field;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * {@link PayloadMasker} on the frozen engine outputs of the synthetic corpora (end-to-end reports and exports, the
 * parity key sections, the engine fixtures' reference sections -- including the HTTP/TLS proxy scenario and the SIP
 * transaction scenarios) and on small hand-made cases. Synthetic data only.
 */
class PayloadMaskerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path RESOURCES = Path.of("src/test/resources");

    /** A stand-in for the service's keyed tokens: the same shape, a plain hash. */
    static final PayloadMasker.Tokens TOKENS = new PayloadMasker.Tokens() {
        @Override public String token(String kind, String raw) {
            return kind + "-" + sha(kind + ":" + raw).substring(0, 10).toUpperCase();
        }
        @Override public String captureName(String raw) { return "FW-ALPHA-0" + (Math.abs(raw.hashCode()) % 9 + 1); }
        @Override public String interfaceName(String raw) { return "OBJ-BRAVO-" + (Math.abs(raw.hashCode()) % 90 + 10); }
    };

    // ---- negative first: what must never pass ---------------------------------------------------------------

    @Test void anUnclassifiedStringFieldIsTokenizedNeverPassedThrough() {
        Map<String, Object> body = Map.of("brand_new_engine_field", "customer.example.test/secret", "count", 3,
                "flag", true, "status", "unknown");
        Map<?, ?> masked = (Map<?, ?>) PayloadMasker.mask(body, TOKENS, false);
        assertTrue(PayloadMasker.TOKEN.matcher((String) masked.get("brand_new_engine_field")).matches());
        assertTrue(((String) masked.get("brand_new_engine_field")).startsWith("TXT-"));
        assertEquals(3, masked.get("count"));
        assertEquals(true, masked.get("flag"));
        assertEquals("unknown", masked.get("status"));
        assertEquals(Field.UNCLASSIFIED, PayloadFields.field("brand_new_engine_field", null, body));
    }

    @Test void payloadValuesNeverSurviveInsideFreeTextOrFilters() {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", "media:one_way_audio:forward:a:b:flow:call-9@fixture.example:17.0");
        finding.put("summary", "Proxy reset after GET /private/path HTTP/1.1 for www.fixture.example");
        finding.put("metrics", Map.of("call_id", "call-9@fixture.example", "host", "WWW.Fixture.Example."));
        finding.put("evidence", List.of(Map.of("content_filter", "udp && sip.Call-ID == \"call-9@fixture.example\"",
                "protocol_identity", Map.of("sip.Call-ID", "call-9@fixture.example", "sip.CSeq.seq", 2))));
        finding.put("request", "GET /private/path HTTP/1.1");
        String masked = text(PayloadMasker.mask(finding, TOKENS, false));
        for (String raw : List.of("call-9", "fixture.example", "Fixture", "private", "/path")) {
            assertFalse(masked.contains(raw), raw + " in " + masked);
        }
        String call = TOKENS.token("CALL", "call-9@fixture.example");
        assertEquals(4, masked.split(call, -1).length - 1, "one Call-ID token in id, metrics, filter and identity");
        assertTrue(masked.contains(TOKENS.token("HOST", "www.fixture.example")), "host case and root dot normalized");
    }

    @Test void theInputIsNeverMutated() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("sni", "tls.fixture.example"));
        PayloadMasker.mask(body, TOKENS, true);
        assertEquals("tls.fixture.example", body.get("sni"));
    }

    // ---- structure ------------------------------------------------------------------------------------------

    @Test void requestLinesKeepMethodVersionPortAndSlashes() {
        String seg = TOKENS.token("PATH", "a");
        assertLine("GET /" + seg + "/" + TOKENS.token("PATH", "b") + "/?" + TOKENS.token("QUERY", "x=1") + " HTTP/1.1",
                "GET /a/b/?x=1 HTTP/1.1");
        assertLine("GET / HTTP/1.0", "GET / HTTP/1.0");
        assertLine("OPTIONS * HTTP/1.1", "OPTIONS * HTTP/1.1");
        assertLine("POST http://" + TOKENS.token("HOST", "h.fixture.example") + ":8080/" + seg + " HTTP/1.1",
                "POST http://H.fixture.example:8080/a HTTP/1.1");
        assertLine("CONNECT " + TOKENS.token("HOST", "h.fixture.example") + ":443 HTTP/1.1",
                "CONNECT h.fixture.example:443 HTTP/1.1");
        assertLine("GET https://" + TOKENS.token("USER", "user:pw") + "@" + TOKENS.token("HOST", "h.fixture.example")
                + "/" + seg + "#" + TOKENS.token("QUERY", "frag") + " HTTP/1.1", "GET https://user:pw@h.fixture.example/a#frag HTTP/1.1");
        // An address literal host stays for the IP masker; F5 request text without a version is a request line too.
        assertLine("GET http://192.0.2.1:80/" + seg + " HTTP/1.1", "GET http://192.0.2.1:80/a HTTP/1.1");
        assertLine("PUT /" + seg, "PUT /a");
        // Engine prose that only looks like "WORD WORD" is not a request line.
        Map<?, ?> prose = (Map<?, ?>) PayloadMasker.mask(Map.of("reason", "TCP RST", "summary", "RST 192.0.2.1:80"),
                TOKENS, false);
        assertEquals("TCP RST", prose.get("reason"));
        assertEquals("RST 192.0.2.1:80", prose.get("summary"));
    }

    @Test void anSniInARequestFieldIsAHostAndRelationshipsHold() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactions", List.of(Map.of("kind", "tls", "request", "Shared.Fixture.Example"),
                Map.of("kind", "http", "request", "GET http://shared.fixture.example/x HTTP/1.1")));
        body.put("dns", List.of(Map.of("question", "shared.fixture.example."), Map.of("question", "other.fixture.example")));
        String masked = text(PayloadMasker.mask(body, TOKENS, false));
        String host = TOKENS.token("HOST", "shared.fixture.example");
        assertEquals(3, masked.split(host, -1).length - 1, "one host, one token across TLS, HTTP and DNS");
        assertTrue(masked.contains(TOKENS.token("HOST", "other.fixture.example")));
        assertEquals(text(PayloadMasker.mask(body, TOKENS, false)), masked, "deterministic");
    }

    @Test void sipChangesKeepTheirShapeAndNumbers() {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sip.Contact", Map.of("before", "<sip:u@fixture.example>", "after", "<sip:u@192.0.2.9>"));
        changes.put("sdp.connection_info.address", Map.of("before", "192.0.2.1", "after", "192.0.2.9"));
        changes.put("sdp.media.port", Map.of("before", "49170", "after", "49172"));
        Map<?, ?> masked = (Map<?, ?>) ((Map<?, ?>) PayloadMasker.mask(Map.of("changes", changes, "status",
                "expected rewriting"), TOKENS, false)).get("changes");
        assertEquals(TOKENS.token("SIP", "<sip:u@fixture.example>"), ((Map<?, ?>) masked.get("sip.Contact")).get("before"));
        assertEquals("192.0.2.1", ((Map<?, ?>) masked.get("sdp.connection_info.address")).get("before"), "address: IP masker");
        assertEquals("49170", ((Map<?, ?>) masked.get("sdp.media.port")).get("before"));
    }

    @Test void vendorTrailerTextAndNamesAreTokens() {
        Map<String, Object> f5 = Map.of("resets", List.of(Map.of("reason", "[0x1] fixture reset text", "device", "F5",
                "evidence", List.of(Map.of("vendor", Map.of("adapter", "f5", "f5ethtrailer.vip", "/Common/fixture-vip",
                        "f5ethtrailer.rstcausetxt", "[0x1] fixture reset text", "fw1.direction", "i",
                        "f5ethtrailer.flowid", "0x1a"))))));
        String masked = text(PayloadMasker.mask(f5, TOKENS, false));
        assertFalse(masked.contains("fixture"), masked);
        assertTrue(masked.contains(TOKENS.token("VTXT", "[0x1] fixture reset text")));
        assertTrue(masked.contains(TOKENS.token("VNAME", "/Common/fixture-vip")));
        assertTrue(masked.contains("\"0x1a\"") && masked.contains("\"i\""), "codes stay");
    }

    @Test void capturedNamesAreMaskedOnlyInNamesMode() {
        Map<String, Object> state = Map.of(
                "fortinet_conversion", Map.of("source", "fixture-source.txt", "files", List.of(Map.of(
                        "path", "/analysis/fixture/port7.pcap", "interface", "fixture-port7", "packets", 3))),
                "topology", Map.of("points", List.of(Map.of("id", "forti-1", "label", "fixture-port7",
                        "vendor_stage", "fixture-port7", "vendor", "fortinet"), Map.of("id", "p1", "label", "p1-label",
                        "vendor_stage", "i", "vendor", "checkpoint", "interface", "fixture-eth0"))));
        String operator = text(PayloadMasker.mask(state, TOKENS, false));
        assertTrue(operator.contains("fixture-source.txt") && operator.contains("fixture-port7"), "names stay for run roles");
        String aiview = text(PayloadMasker.mask(state, TOKENS, true));
        assertFalse(aiview.contains("fixture"), aiview);
        assertTrue(aiview.contains(PayloadMasker.REDACTED_PATH));
        assertTrue(aiview.contains("\"vendor_stage\":\"i\""), "a Check Point stage code stays");
    }

    // ---- the frozen synthetic corpora -----------------------------------------------------------------------

    /**
     * The fail-closed gate on real engine output shapes: every string of every frozen synthetic engine output sits
     * under a classified key. A new engine field fails here until it is classified in {@link PayloadFields} and the
     * README inventory.
     */
    @Test void everyStringKeyInTheFrozenEngineOutputsIsClassified() {
        Set<String> unclassified = new TreeSet<>();
        for (Path file : corpus()) {
            walk(load(file), null, null, null, (key, field, value) -> {
                if (field == Field.UNCLASSIFIED) unclassified.add(key + " in " + RESOURCES.relativize(file));
            });
        }
        assertEquals(Set.of(), unclassified);
    }

    /**
     * The HTTP/TLS proxy scenario and the SIP transaction scenarios: every payload value of the raw output is gone
     * from the masked output, and every masked string is a safe field or a masked value.
     */
    @Test void theSyntheticHttpTlsAndSipScenariosLeaveNoPayloadValue() {
        List<Path> scenarios = new ArrayList<>(List.of(RESOURCES.resolve("packetanalysis/engine/detb_proxy_zero_ipv4.json.gz")));
        try (Stream<Path> s = Files.list(RESOURCES.resolve("parity/cases"))) {
            s.filter(p -> p.getFileName().toString().startsWith("cli-udp_transactions")
                    && p.toString().endsWith(".key-sections.json.gz")).sorted().forEach(scenarios::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int payloadValues = 0;
        for (Path file : scenarios) {
            Object raw = load(file);
            Set<String> values = new LinkedHashSet<>();
            walk(raw, null, null, null, (key, field, value) -> {
                if (field != Field.KEEP && field != Field.TEXT && field != Field.ADDRESS && value.length() > 3) values.add(value);
            });
            assertFalse(values.isEmpty(), "the scenario carries payload values: " + file);
            payloadValues += values.size();
            Object masked = PayloadMasker.mask(raw, TOKENS, false);
            String text = text(masked);
            for (String value : values) {
                assertFalse(text.contains(JSON_STRING(value)), value + " survives in " + file);
            }
            List<String> problems = new ArrayList<>();
            walk(masked, null, null, null, (key, field, value) -> {
                boolean safe = field == Field.KEEP || field == Field.TEXT || field == Field.ADDRESS;
                if (!safe && !PayloadMasker.isMasked(value)) problems.add(key + "=" + value);
            });
            assertEquals(List.of(), problems, file.toString());
        }
        assertTrue(payloadValues >= 10, "HTTP request lines, hosts, SNI and Call-IDs were exercised: " + payloadValues);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static void assertLine(String expected, String raw) {
        Map<?, ?> masked = (Map<?, ?>) PayloadMasker.mask(Map.of("line", raw), TOKENS, false);
        assertEquals(expected, masked.get("line"));
        assertTrue(PayloadMasker.isMasked(expected) || expected.equals(raw), expected);
    }

    /** The JSON text of a string without its quotes, as it would appear inside the serialized tree. */
    private static String JSON_STRING(String value) {
        try {
            String quoted = JSON.writeValueAsString(value);
            return quoted.substring(1, quoted.length() - 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private interface Visitor {
        void string(String key, Field field, String value);
    }

    static void walk(Object node, String key, String context, Map<?, ?> owner, Visitor visitor) {
        if (node instanceof Map<?, ?> map) {
            String inner = PayloadFields.context(key, context);
            for (Map.Entry<?, ?> e : map.entrySet()) walk(e.getValue(), String.valueOf(e.getKey()), inner, map, visitor);
        } else if (node instanceof List<?> list) {
            for (Object item : list) walk(item, key, context, owner, visitor);
        } else if (node instanceof String s && !s.isEmpty()) {
            visitor.string(key, PayloadFields.field(key, context, owner), s);
        }
    }

    /** Frozen engine outputs: e2e reports and exports, parity key sections, engine fixture reference sections. */
    private static List<Path> corpus() {
        try (Stream<Path> s = Files.walk(RESOURCES)) {
            return s.filter(p -> p.toString().endsWith(".json.gz"))
                    .filter(p -> p.toString().contains("/e2e/") || p.toString().endsWith(".key-sections.json.gz")
                            || p.toString().contains("/packetanalysis/engine/"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One corpus file; for an engine fixture only its reference outputs (its input tables are not responses). */
    private static Object load(Path file) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            Object tree = JSON.readValue(in, Object.class);
            if (file.toString().contains("/packetanalysis/engine/") && tree instanceof Map<?, ?> m) {
                Map<String, Object> outputs = new LinkedHashMap<>();
                for (String k : List.of("reference_core", "reference_det_a", "sections", "outputs")) {
                    if (m.containsKey(k)) outputs.put(k, m.get(k));
                }
                return outputs;
            }
            return tree;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(Object tree) {
        try {
            return JSON.writeValueAsString(tree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
