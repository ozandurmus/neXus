package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureMetadata;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reference {@code application_index}: the bounded, cached, isolated tshark application-event index
 * ({@code index_applications}, {@code event_rows}, {@code certificates}, {@code read_filter},
 * {@code can_decode_source}, {@code apply_tcp_analysis}). tshark is the only dissector; this class reads its JSON.
 */
final class ApplicationIndex {
    static final int VERSION = 8;
    static final int MAX_EVENTS = 10000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApplicationIndex() {
    }

    /** A reference ValueError/OSError whose message becomes the point's application status reason. */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ tree helpers

    @SuppressWarnings("unchecked")
    static List<Object> values(Object tree, String name) {
        List<Object> result = new ArrayList<>();
        if (tree instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (name.equals(e.getKey())) {
                    if (e.getValue() instanceof List<?> l) {
                        result.addAll(l);
                    } else {
                        result.add(e.getValue());
                    }
                } else {
                    result.addAll(values(e.getValue(), name));
                }
            }
        } else if (tree instanceof List<?> list) {
            for (Object child : list) {
                result.addAll(values(child, name));
            }
        }
        return result;
    }

    static Object first(Object tree, String name, Object fallback) {
        List<Object> found = values(tree, name);
        return found.isEmpty() ? fallback : found.get(0);
    }

    static Object first(Object tree, String name) {
        return first(tree, name, null);
    }

    /** {@code integer(value, default)}. */
    static Long integer(Object value, Long fallback) {
        if (value == null || value instanceof Map || value instanceof List) {
            return fallback;
        }
        String text = value instanceof Boolean b ? (b ? "True" : "False") : String.valueOf(value);
        try {
            return text.startsWith("0x") ? PyConvert.parseInt(text, 0) : PyConvert.parseInt(text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static Long integer(Object value) {
        return integer(value, 0L);
    }

    /** {@code ip_list(value)}: comma separated addresses normalized as {@code str(ipaddress.ip_address(...))}. */
    static List<Object> ipList(Object value) {
        List<Object> result = new ArrayList<>();
        String text = value == null ? "" : String.valueOf(value);
        for (String token : text.split(",", -1)) {
            String a = Addresses.normalize(token.strip());
            if (a != null) {
                result.add(a);
            }
        }
        return result;
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code bytes.fromhex}: whitespace between bytes allowed, anything else is a ValueError. */
    static byte[] fromHex(String text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (i + 1 >= text.length() || Character.digit(c, 16) < 0 || Character.digit(text.charAt(i + 1), 16) < 0) {
                throw new Failure("non-hexadecimal number found in fromhex() arg at position " + (Character.digit(c, 16) < 0 ? i : i + 1));
            }
            out.write(Character.digit(c, 16) * 16 + Character.digit(text.charAt(i + 1), 16));
            i += 2;
        }
        return out.toByteArray();
    }

    static String certificateNames(Object tree, String name) {
        List<String> attributes = new ArrayList<>();
        Consumer<Object>[] walk = new Consumer[1];
        walk[0] = value -> {
            if (value instanceof Map<?, ?> m) {
                if (m.containsKey("x509if.type") || m.containsKey("x509if.oid")) {
                    List<Object> text = new ArrayList<>(values(m, "x509sat.uTF8String"));
                    text.addAll(values(m, "x509sat.printableString"));
                    text.addAll(values(m, "x509sat.iA5String"));
                    Object oid = Evidence.truthy(m.get("x509if.oid")) ? m.get("x509if.oid") : m.get("x509if.type");
                    for (Object v : text) {
                        attributes.add(Evidence.pyStr(oid) + "=" + Evidence.pyStr(v));
                    }
                } else {
                    for (Object child : m.values()) {
                        walk[0].accept(child);
                    }
                }
            } else if (value instanceof List<?> l) {
                for (Object child : l) {
                    walk[0].accept(child);
                }
            }
        };
        for (Object subtree : values(tree, name + "_tree")) {
            walk[0].accept(subtree);
        }
        return attributes.isEmpty() ? null : String.join(", ", attributes);
    }

    static List<Object> certificates(Object tree) {
        List<Object> found = new ArrayList<>();
        Consumer<Object>[] walk = new Consumer[1];
        walk[0] = node -> {
            if (node instanceof Map<?, ?> m) {
                if (m.containsKey("tls.handshake.certificate")) {
                    Object encoded = m.get("tls.handshake.certificate");
                    List<?> ders = encoded instanceof List<?> l ? l : Arrays.asList(encoded);
                    Object sub = m.containsKey("tls.handshake.certificate_tree") ? m.get("tls.handshake.certificate_tree") : Map.of();
                    List<?> subs = sub instanceof List<?> l ? l : Arrays.asList(sub);
                    for (int i = 0; i < ders.size(); i++) {
                        Object info = i < subs.size() ? subs.get(i) : Map.of();
                        Map<String, Object> cert = new LinkedHashMap<>();
                        cert.put("fingerprint", sha256Hex(fromHex(String.valueOf(ders.get(i)).replace(":", ""))));
                        cert.put("subject", certificateNames(info, "x509af.subject"));
                        cert.put("issuer", certificateNames(info, "x509af.issuer"));
                        found.add(cert);
                    }
                } else {
                    for (Object child : m.values()) {
                        walk[0].accept(child);
                    }
                }
            } else if (node instanceof List<?> l) {
                for (Object child : l) {
                    walk[0].accept(child);
                }
            }
        };
        walk[0].accept(tree);
        return found;
    }

    /** {@code datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp()} (fractions past microseconds truncated). */
    static double isoTimestamp(String stamp) {
        String s = stamp.replace("Z", "+00:00");
        int t = s.indexOf('T');
        String date = s.substring(0, t);
        String rest = s.substring(t + 1);
        int sign = Math.max(rest.lastIndexOf('+'), rest.lastIndexOf('-'));
        int offsetSeconds = 0;
        String time = rest;
        if (sign > 0) {
            String[] hm = rest.substring(sign + 1).split(":");
            offsetSeconds = (Integer.parseInt(hm[0]) * 3600 + Integer.parseInt(hm[1]) * 60) * (rest.charAt(sign) == '-' ? -1 : 1);
            time = rest.substring(0, sign);
        }
        String fraction = "";
        int dot = time.indexOf('.');
        if (dot >= 0) {
            fraction = time.substring(dot + 1);
            time = time.substring(0, dot);
        }
        LocalDateTime base = LocalDateTime.parse(date + "T" + time);
        long seconds = base.toEpochSecond(ZoneOffset.UTC) - offsetSeconds;
        long micros = fraction.isEmpty() ? 0 : Long.parseLong((fraction + "000000").substring(0, 6));
        return (double) (seconds * 1_000_000L + micros) / 1e6;
    }

    static double observedTime(String stamp) {
        return stamp.contains("T") ? isoTimestamp(stamp) : PyConvert.parseFloat(stamp);
    }

    // ------------------------------------------------------------------ event_rows

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> eventRows(Map<String, Object> packet) {
        Map<String, Object> layers = (Map<String, Object>) ((Map<String, Object>) packet.get("_source")).get("layers");
        List<Map<String, Object>> result = new ArrayList<>();
        if (!layers.containsKey("tcp")) {
            return result;
        }
        Object frameLayer = layers.getOrDefault("frame", Map.of());
        long frame = integer(first(frameLayer, "frame.number"));
        List<Object> parts = new ArrayList<>();
        for (Object v : values(layers, "tcp.segment")) {
            parts.add(integer(v));
        }
        if (parts.isEmpty()) {
            parts.add(frame);
        }
        Map<String, List<Object>> tcp = new LinkedHashMap<>();
        Consumer<Object>[] collect = new Consumer[1];
        collect[0] = node -> {
            if (node instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    List<Object> slot = tcp.computeIfAbsent((String) e.getKey(), k -> new ArrayList<>());
                    if (e.getValue() instanceof List<?> l) {
                        slot.addAll(l);
                    } else {
                        slot.add(e.getValue());
                    }
                    collect[0].accept(e.getValue());
                }
            } else if (node instanceof List<?> l) {
                for (Object v : l) {
                    collect[0].accept(v);
                }
            }
        };
        collect[0].accept(layers.getOrDefault("tcp", Map.of()));
        String stamp = String.valueOf(first(frameLayer, "frame.time_epoch"));
        double observed = observedTime(stamp);
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("observed", observed);
        common.put("local_frame", frame);
        common.put("components", parts);
        common.put("protocol_stream", integer(head(tcp, "tcp.stream"), -1L));
        Object src = first(layers.getOrDefault("ip", Map.of()), "ip.src");
        common.put("src", Evidence.truthy(src) ? src : first(layers.getOrDefault("ipv6", Map.of()), "ipv6.src"));
        common.put("raw_seq", integer(head(tcp, "tcp.seq_raw")));
        common.put("tcp_len", integer(head(tcp, "tcp.len")));
        Map<String, Object> analysis = new LinkedHashMap<>();
        String[][] flags = {{"retrans", "retransmission"}, {"fast_retrans", "fast_retransmission"},
            {"spurious", "spurious_retransmission"}, {"out_of_order", "out_of_order"}, {"zero_window", "zero_window"},
            {"duplicate_ack", "duplicate_ack"}, {"window_full", "window_full"}, {"zero_probe", "zero_window_probe"}};
        for (String[] f : flags) {
            List<Object> v = tcp.get("tcp.analysis." + f[1]);
            analysis.put(f[0], v != null && !v.isEmpty());
        }
        for (String[] f : new String[][] {{"rtt", "tcp.analysis.ack_rtt"}, {"rto_seconds", "tcp.analysis.rto"}}) {
            Object value = head(tcp, f[1]);
            analysis.put(f[0], Evidence.truthy(value) ? (Object) PyConvert.parseFloat(String.valueOf(value)) : null);
        }
        analysis.put("bytes_in_flight", integer(head(tcp, "tcp.analysis.bytes_in_flight"), null));
        analysis.put("rto_frame", integer(head(tcp, "tcp.analysis.rto_frame"), null));
        analysis.put("sack_left", integer(head(tcp, "tcp.options.sack_le"), null));
        analysis.put("sack_right", integer(head(tcp, "tcp.options.sack_re"), null));
        if (analysis.values().stream().anyMatch(Evidence::truthy) || analysis.get("bytes_in_flight") != null) {
            Map<String, Object> e = new LinkedHashMap<>(common);
            e.put("components", new ArrayList<>(List.of(frame)));
            e.put("kind", "tcp_analysis");
            e.put("metadata", analysis);
            result.add(e);
        }
        Object http = layers.getOrDefault("http", Map.of());
        if (!values(http, "http.request.method").isEmpty()) {
            List<Object> method = values(http, "http.request.method");
            List<Object> uri = values(http, "http.request.uri");
            List<Object> version = values(http, "http.request.version");
            boolean single = method.size() == 1 && uri.size() == 1 && version.size() == 1;
            String reason = single ? null : "Multiple HTTP messages in one frame; byte boundaries unknown";
            long content = integer(first(http, "http.content_length"), 0L);
            List<Object> rawLength = values(http, "http.content_length_header");
            if (rawLength.size() > 1 || !rawLength.isEmpty() && !isDigit(String.valueOf(rawLength.get(0)))) {
                reason = reason != null ? reason : "Ambiguous or invalid HTTP content length";
            }
            String body = String.valueOf(first(http, "http.file_data", ""));
            if (Evidence.truthy(first(http, "http.transfer_encoding")) || Evidence.truthy(first(http, "http.content_encoding"))) {
                reason = reason != null ? reason : "Encoded/chunked request byte boundary not supported";
            }
            if (content != 0 && body.replace(":", "").length() / 2 != content) {
                reason = reason != null ? reason : "Request body incomplete in tshark decode";
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("line", single ? Evidence.pyStr(method.get(0)) + " " + Evidence.pyStr(uri.get(0)) + " " + Evidence.pyStr(version.get(0)) : null);
            Object hostValue = first(http, "http.host");
            String host = Evidence.truthy(hostValue) ? String.valueOf(hostValue).strip().toLowerCase(Locale.ROOT) : "";
            meta.put("host", host.isEmpty() ? null : host);
            meta.put("xff", ipList(first(http, "http.x_forwarded_for")));
            meta.put("ready", reason == null);
            meta.put("reason", reason);
            Map<String, Object> e = new LinkedHashMap<>(common);
            e.put("kind", "http_request");
            e.put("metadata", meta);
            result.add(e);
        }
        if (Evidence.truthy(first(http, "http.response.code"))) {
            long content = integer(first(http, "http.content_length"), 0L);
            String body = String.valueOf(first(http, "http.file_data", ""));
            boolean ready = values(http, "http.response.code").size() == 1 && !Evidence.truthy(first(http, "http.transfer_encoding"));
            ready = ready && (content == 0 || body.replace(":", "").length() / 2 == content);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("request_in", integer(first(http, "http.request_in"), null));
            meta.put("code", integer(first(http, "http.response.code")));
            meta.put("ready", ready);
            Map<String, Object> e = new LinkedHashMap<>(common);
            e.put("kind", "http_response");
            e.put("metadata", meta);
            result.add(e);
        }
        Object tls = layers.getOrDefault("tls", Map.of());
        List<Long> types = new ArrayList<>();
        for (Object v : values(tls, "tls.handshake.type")) {
            types.add(integer(v));
        }
        if (types.contains(1L)) {
            List<Object> names = values(tls, "tls.handshake.extensions_server_name");
            String sni = names.size() == 1 ? stripDots(String.valueOf(names.get(0)).toLowerCase(Locale.ROOT)) : null;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sni", sni);
            meta.put("ready", Evidence.truthy(sni));
            meta.put("reason", Evidence.truthy(sni) ? null : "TLS SNI absent or ambiguous");
            Map<String, Object> e = new LinkedHashMap<>(common);
            e.put("kind", "tls_setup");
            e.put("metadata", meta);
            result.add(e);
        }
        if (types.contains(2L)) {
            Object v = first(tls, "tls.handshake.extensions.supported_version");
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("version", integer(Evidence.truthy(v) ? v : first(tls, "tls.handshake.version")));
            Map<String, Object> e = new LinkedHashMap<>(common);
            e.put("kind", "tls_server");
            e.put("metadata", meta);
            result.add(e);
        }
        if (types.contains(11L)) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chain", certificates(tls));
            Map<String, Object> e = new LinkedHashMap<>(common);
            e.put("kind", "tls_certificate");
            e.put("metadata", meta);
            result.add(e);
        }
        return result;
    }

    private static Object head(Map<String, List<Object>> tcp, String key) {
        List<Object> v = tcp.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static boolean isDigit(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static String stripDots(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    /** {@code json_packets(stream)}: the top-level array elements of tshark's JSON output. */
    @SuppressWarnings("unchecked")
    static void jsonPackets(Reader stream, Consumer<Map<String, Object>> sink) {
        try (JsonParser parser = new JsonFactory().createParser(stream)) {
            parser.setCodec(JSON);
            JsonToken token = parser.nextToken();
            if (token == null) {
                return;
            }
            if (token != JsonToken.START_ARRAY) {
                throw new Failure("Incomplete tshark JSON application record");
            }
            while ((token = parser.nextToken()) == JsonToken.START_OBJECT) {
                JsonNode node = parser.readValueAsTree();
                sink.accept((Map<String, Object>) Evidence.fromNode(node));
            }
            if (token != JsonToken.END_ARRAY) {
                throw new Failure("Incomplete tshark JSON application record");
            }
        } catch (JsonProcessingException e) {
            throw new Failure("Incomplete tshark JSON application record");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ point selection

    @SuppressWarnings("unchecked")
    static boolean canDecodeSource(Point point, Map<String, Object> inventory, long count) {
        Object interfaces = inventory.get("interfaces");
        return point.iface() == null && point.sourceCidr() == null && point.vendor().equals("none")
                && Boolean.TRUE.equals(inventory.getOrDefault("timestamps_validated", false))
                && numEq(inventory.get("records_read"), inventory.get("packet_count")) && numEq(inventory.get("packet_count"), count)
                && (interfaces == null ? 0 : ((List<Object>) interfaces).size()) == 1
                && !Evidence.truthy(inventory.get("damaged_tail")) && inventory.get("frame_limit") == null;
    }

    private static boolean numEq(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return java.util.Objects.equals(a, b);
    }

    /** {@code read_filter(point, inventory)}. */
    static String readFilter(Point point, Map<String, Object> inventory) {
        List<String> terms = new ArrayList<>();
        terms.add("frame.time_epoch >= " + PyFormat.format(((Number) inventory.get("start")).doubleValue() - 1e-6, ".9f"));
        terms.add("frame.time_epoch <= " + PyFormat.format(((Number) inventory.get("end")).doubleValue() + 1e-6, ".9f"));
        if (point.iface() != null) {
            terms.add("frame.interface_id == " + point.iface());
        }
        if (point.sourceCidr() != null && !point.sourceCidr().isEmpty()) {
            String family = point.sourceCidr().contains(":") ? "ipv6" : "ip";
            terms.add(family + ".src == " + point.sourceCidr());
        }
        if (point.vendor().equals("checkpoint")) {
            String stage = point.vendorStage();
            terms.add("fw1.direction == " + PyJsonWriter.string(stage.substring(0, 1)));
            if (stage.length() > 1) {
                terms.add("fw1.chain == " + PyJsonWriter.string(stage.substring(1)));
            } else if (stage.equals("o") || stage.equals("O")) {
                terms.add("fw1.chain != " + PyJsonWriter.string(stage.equals("o") ? "e" : "E"));
            }
            if (point.vendorInterface() != null && !point.vendorInterface().isEmpty()) {
                terms.add("fw1.interface == " + PyJsonWriter.string(point.vendorInterface()));
            }
        }
        return String.join(" && ", terms);
    }

    /** {@code point.model_dump(exclude=...)} of the identity, keys sorted ({@code json.dumps(sort_keys=True)}). */
    static Map<String, Object> pointIdentity(Point p, boolean keepMatchingLayer) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", p.id());
        m.put("side", p.side());
        m.put("capture_id", p.captureId());
        m.put("clock_domain", p.clockDomain());
        m.put("host_capture", p.hostCapture());
        m.put("stage_capture", p.stageCapture());
        m.put("interface", p.iface());
        m.put("vendor", p.vendor());
        m.put("vendor_stage", p.vendorStage());
        m.put("vendor_interface", p.vendorInterface());
        m.put("inspection_complete", p.inspectionComplete());
        if (keepMatchingLayer) {
            m.put("matching_layer", p.matchingLayer());
        }
        m.put("source_cidr", p.sourceCidr());
        return new LinkedHashMap<>(m);
    }

    static String tsharkBinary(Db db) {
        Object[] row = db.one(Sql.get("application_index", "preferences"), "preferences");
        Object prefs = row == null ? Map.of() : Evidence.loads((String) row[0]);
        Object override = prefs instanceof Map<?, ?> m ? m.get("tshark") : null;
        try {
            return TsharkRunner.find(Evidence.truthy(override) ? String.valueOf(override) : null);
        } catch (IllegalArgumentException e) {
            throw new Failure(e.getMessage());
        }
    }

    /** Capture identity check: {@code [str(source), st_size, st_mtime_ns] != json.loads(identity)[:3]}. */
    @SuppressWarnings("unchecked")
    static boolean unchanged(Path source, String identity) {
        try {
            BasicFileAttributes a = Files.readAttributes(source, BasicFileAttributes.class);
            List<Object> indexed = (List<Object>) Evidence.loads(identity);
            return indexed.size() >= 3 && source.toString().equals(indexed.get(0)) && numEq(indexed.get(1), a.size())
                    && numEq(indexed.get(2), a.lastModifiedTime().to(TimeUnit.NANOSECONDS));
        } catch (IOException e) {
            throw new Failure("[Errno 2] No such file or directory: '" + source + "'");
        }
    }

    static int runSelect(List<String> command) {
        try (TsharkRunner proc = TsharkRunner.start(command, null, null, List.of("SSLKEYLOGFILE"))) {
            return proc.waitFor();
        }
    }

    static long completeRecords(Path file) {
        try {
            return ((Number) CaptureMetadata.read(file, null).get("complete_records")).longValue();
        } catch (IOException e) {
            throw new Failure(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ index_applications

    static void stage(EngineContext ctx) {
        ctx.applicationStatus = indexApplications(ctx.db, ctx.topology);
        Parity.record(ctx, "index_applications", ctx.applicationStatus);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> indexApplications(Db db, Topology topology) {
        for (String name : List.of("app_protocol", "app_protocol_cache", "tcp_point_analysis", "tcp_state_raw")) {
            db.exec(Sql.get("application_index", name));
        }
        boolean needed = topology.points.stream().anyMatch(p -> p.translation().equals("full_proxy"))
                || db.count(Sql.get("application_index", "needed")) > 0;
        List<Map<String, Object>> statuses = new ArrayList<>();
        if (!needed && topology.points.stream().noneMatch(Point::hostCapture)) {
            for (String table : List.of("app_protocol", "app_protocol_cache", "tcp_point_analysis")) {
                db.exec(Sql.fmt("application_index", "clear", "table", table));
            }
            return statuses;
        }
        String binary = tsharkBinary(db);
        for (Point point : topology.points) {
            if (!needed && !point.hostCapture()) {
                continue;
            }
            Object[] capture = db.one(Sql.get("application_index", "capture_row"), point.captureId());
            if (db.count(Sql.get("application_index", "point_count"), point.id()) == 0) {
                continue;
            }
            Object[] hash = db.one(Sql.get("application_index", "identity_hash"), point.id());
            String identity = "[" + PyJsonWriter.dumps(capture[1], false) + ", " + PyJsonWriter.dumps(pointIdentity(point, true), false)
                    + ", " + VERSION + ", [" + hash[0] + ", " + (hash[1] == null ? "null" : hash[1]) + "], "
                    + PyJsonWriter.dumps(binary, false) + "]";
            Object[] existing = db.one(Sql.get("application_index", "cache_get"), point.id());
            if (existing != null && identity.equals(existing[0])) {
                if (existing[1] == null) {
                    applyTcpAnalysis(db, point.id());
                }
                statuses.add(status(point.id(), existing[1], true));
                continue;
            }
            Map<String, Object> inventory = (Map<String, Object>) Evidence.loads((String) capture[2]);
            String reason = null;
            db.exec(Sql.get("application_index", "delete_state"), point.id());
            db.exec(Sql.get("application_index", "delete_analysis"), point.id());
            db.exec(Sql.get("application_index", "delete_app"), point.id());
            try {
                decodePoint(db, point, capture, inventory, binary);
            } catch (Failure | IllegalArgumentException | UncheckedIOException e) {
                reason = e.getMessage();
                db.exec(Sql.get("application_index", "delete_app"), point.id());
                db.exec(Sql.get("application_index", "delete_analysis"), point.id());
            }
            db.exec(Sql.get("application_index", "cache_insert"), point.id(), identity, reason);
            if (reason == null) {
                applyTcpAnalysis(db, point.id());
            }
            statuses.add(status(point.id(), reason, false));
        }
        return statuses;
    }

    private static Map<String, Object> status(String point, Object reason, boolean cached) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("point", point);
        s.put("reason", reason);
        s.put("cached", cached);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static void decodePoint(Db db, Point point, Object[] capture, Map<String, Object> inventory, String binary) {
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> stateBuffer = new ArrayList<>();
        Runnable flushState = () -> {
            if (!stateBuffer.isEmpty()) {
                db.exec(Sql.get("application_index", "state_insert"), PyJsonWriter.dumps(stateBuffer, false),
                        Sql.get("application_index", "state_schema"));
                stateBuffer.clear();
            }
        };
        Path source = Path.of((String) capture[0]);
        if (!unchanged(source, (String) capture[1])) {
            throw new Failure("Capture changed; reattach before application analysis");
        }
        if (point.vendor().equals("f5")) {
            throw new Failure("F5 trailer points use the existing vendor adapter");
        }
        List<String> options = new ArrayList<>();
        if ("snoop".equals(inventory.get("format"))) {
            options.addAll(List.of("-o", "eth.interpret_as_fw1_monitor:TRUE", "-o", "fw1.iflist_with_chain:TRUE", "-o",
                    "fw1.with_uuid:" + Evidence.pyStr(inventory.getOrDefault("checkpoint_uuid", false)).toUpperCase(Locale.ROOT)));
        }
        String scopedFilter = readFilter(point, inventory);
        List<Object[]> tuples = db.tuples(Sql.get("application_index", "tuples"), point.id());
        if (tuples.size() > 128) {
            throw new Failure("Application decode exceeds 128 scoped tuples; select the incident flow for application timing");
        }
        TreeSet<String> filters = new TreeSet<>();
        for (Object[] t : tuples) {
            if ("TCP".equals(t[0]) || "UDP".equals(t[0])) {
                filters.add(Evidence.tupleFilter(t[0], t[1], t[2], t[3], t[4], true));
            }
        }
        if (!filters.isEmpty()) {
            scopedFilter += " && (" + String.join(" || ", filters) + ")";
        }
        Path directory;
        try {
            directory = Files.createTempDirectory("app-point-");
        } catch (IOException e) {
            throw new Failure(e.getMessage());
        }
        try {
            Path selected = directory.resolve("point.pcapng");
            long count = db.count(Sql.get("application_index", "point_count"), point.id());
            if (canDecodeSource(point, inventory, count)) {
                selected = source;
            } else {
                List<String> select = new ArrayList<>(List.of(binary, "-n", "-r", source.toString(), "-Y", scopedFilter, "-w",
                        selected.toString(), "-F", "pcapng", "-o", "tcp.desegment_tcp_streams:FALSE"));
                select.addAll(options);
                if (inventory.get("frame_limit") != null) {
                    select.addAll(List.of("-c", Evidence.pyStr(inventory.get("frame_limit"))));
                }
                String stderr;
                int code;
                try (TsharkRunner proc = TsharkRunner.start(select, null, null, List.of("SSLKEYLOGFILE"))) {
                    code = proc.waitFor();
                    stderr = proc.stderrHead(4000);
                }
                if (code != 0 && !Evidence.truthy(inventory.get("damaged_tail"))) {
                    throw new Failure("Point selection failed: " + stderr);
                }
                count = db.count(Sql.get("application_index", "point_count"), point.id());
                if (completeRecords(selected) != count) {
                    throw new Failure("Selected point frame count disagrees with index");
                }
            }
            List<String> command = new ArrayList<>(List.of(binary, "-n", "-2", "-r", selected.toString(), "-Y",
                    "tcp && !(vxlan || geneve || gre || icmp || icmpv6)", "-o", "tcp.desegment_tcp_streams:TRUE", "-o",
                    "ip.defragment:FALSE", "-o", "ipv6.defragment:FALSE", "-o", "tcp.reassemble_out_of_order:TRUE", "-o",
                    "http.desegment_body:TRUE", "-o", "tls.keylog_file:", "-o", "tls.debug_file:", "-T", "json",
                    "--no-duplicate-keys"));
            command.addAll(options);
            int code;
            String stderr;
            try (TsharkRunner proc = TsharkRunner.start(command, null, null, List.of("SSLKEYLOGFILE"))) {
                jsonPackets(proc.stdout(), packet -> {
                    for (Map<String, Object> event : eventRows(packet)) {
                        if ("tcp_analysis".equals(event.get("kind"))) {
                            Map<String, Object> state = new LinkedHashMap<>();
                            state.put("point", point.id());
                            state.put("local_frame", event.get("local_frame"));
                            state.put("src", event.get("src"));
                            state.put("raw_seq", event.get("raw_seq"));
                            state.put("tcp_len", event.get("tcp_len"));
                            state.put("observed", event.get("observed"));
                            state.put("metadata", PyJsonWriter.dumps(event.get("metadata"), false));
                            stateBuffer.add(state);
                            if (stateBuffer.size() >= 1000) {
                                flushState.run();
                            }
                        } else {
                            events.add(event);
                        }
                    }
                    if (events.size() > MAX_EVENTS) {
                        throw new Failure("Application index exceeds 10,000 events per point; narrow capture");
                    }
                });
                code = proc.waitFor();
                stderr = proc.stderrHead(4000);
            }
            if (code != 0) {
                throw new Failure("Application tshark decode failed: " + stderr);
            }
        } finally {
            deleteTree(directory);
        }
        flushState.run();
        db.exec(Sql.get("application_index", "state_mapping"), point.id());
        if (db.count(Sql.get("application_index", "state_invalid"), point.id()) > 0) {
            throw new Failure("Isolated TCP state mapping disagrees with packet index");
        }
        db.exec(Sql.get("application_index", "analysis_insert"), point.id());
        TreeSet<Long> ordinals = new TreeSet<>();
        for (Map<String, Object> event : events) {
            for (Object f : (List<Object>) event.get("components")) {
                ordinals.add((Long) f);
            }
            ordinals.add((Long) event.get("local_frame"));
            Map<String, Object> meta = (Map<String, Object>) event.get("metadata");
            if (Evidence.truthy(meta.get("request_in"))) {
                ordinals.add((Long) meta.get("request_in"));
            }
            if (Evidence.truthy(meta.get("rto_frame"))) {
                ordinals.add((Long) meta.get("rto_frame"));
            }
        }
        Map<Long, Row> mapping = new LinkedHashMap<>();
        if (!ordinals.isEmpty()) {
            for (Row r : db.rows(Sql.get("application_index", "mapped"), point.id(), new ArrayList<>(ordinals))) {
                mapping.put(r.lng("local_frame"), r);
            }
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> event : events) {
            Row sourceFrame = mapping.get((Long) event.get("local_frame"));
            if (sourceFrame == null || !java.util.Objects.equals(sourceFrame.get("src"), event.get("src"))
                    || !java.util.Objects.equals(sourceFrame.get("raw_seq"), event.get("raw_seq"))
                    || !java.util.Objects.equals(sourceFrame.get("length"), event.get("tcp_len"))
                    || Math.abs(sourceFrame.dbl("ts") - (Double) event.get("observed")) > 1e-6) {
                throw new Failure("Application frame mapping disagrees with packet index; use unambiguous point filters");
            }
            List<Object> components = (List<Object>) event.get("components");
            for (Object f : components) {
                if (!mapping.containsKey((Long) f)) {
                    throw new Failure("Application component frame not indexed");
                }
            }
            Map<String, Object> meta = (Map<String, Object>) event.get("metadata");
            Object reference = meta.get("request_in");
            if (Evidence.truthy(reference)) {
                Row r = mapping.get((Long) reference);
                meta.put("request_in", r != null ? r.get("frame") : null);
            }
            if (Evidence.truthy(meta.get("rto_frame"))) {
                Row r = mapping.get((Long) meta.get("rto_frame"));
                meta.put("rto_frame", r != null ? r.get("frame") : null);
            }
            List<Object> frames = new ArrayList<>();
            for (Object f : components) {
                frames.add(mapping.get((Long) f).get("frame"));
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("point", point.id());
            record.put("capture_id", point.captureId());
            record.put("frame", sourceFrame.get("frame"));
            record.put("kind", event.get("kind"));
            record.put("components", PyJsonWriter.dumps(frames, false));
            record.put("protocol_stream", event.get("protocol_stream"));
            record.put("metadata", PyJsonWriter.dumps(meta, false));
            records.add(record);
        }
        if (!records.isEmpty()) {
            db.exec(Sql.get("application_index", "app_insert"), PyJsonWriter.dumps(records, false), Sql.get("application_index", "app_schema"));
        }
    }

    static void deleteTree(Path directory) {
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best effort, as TemporaryDirectory cleanup
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    /** {@code apply_tcp_analysis(db, point)}: replace stateful TCP fields after a successful isolated decode. */
    static void applyTcpAnalysis(Db db, String point) {
        List<String> fields = new ArrayList<>();
        for (String name : List.of("retrans", "fast_retrans", "spurious", "out_of_order", "zero_window")) {
            fields.add(Sql.fmt("application_index", "apply_flag", "name", name));
        }
        fields.add(Sql.get("application_index", "apply_rtt"));
        fields.add(Sql.get("application_index", "apply_path_fields"));
        db.exec(Sql.get("application_index", "point_tcp_state"), point);
        db.exec(Sql.fmt("application_index", "apply_obs", "fields", String.join(",", fields)), point, point);
        db.exec(Sql.get("application_index", "drop_point_tcp_state"));
    }

    /** {@code str(ipaddress.ip_address(text))}, or null for a ValueError. */
    static final class Addresses {
        private Addresses() {
        }

        static String normalize(String text) {
            String v4 = v4(text);
            if (v4 != null) {
                return v4;
            }
            return v6(text);
        }

        static String v4(String text) {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9')
                        || p.length() > 1 && p.charAt(0) == '0' || Integer.parseInt(p) > 255) {
                    return null;
                }
                out.append(out.length() == 0 ? "" : ".").append(Integer.parseInt(p));
            }
            return out.toString();
        }

        static String v6(String text) {
            String scope = "";
            int pct = text.indexOf('%');
            if (pct >= 0) {
                scope = text.substring(pct);
                text = text.substring(0, pct);
                if (scope.length() == 1) {
                    return null;
                }
            }
            if (text.isEmpty() || !text.contains(":")) {
                return null;
            }
            List<String> parts = new ArrayList<>(Arrays.asList(text.split(":", -1)));
            List<Integer> tail = new ArrayList<>();
            String last = parts.get(parts.size() - 1);
            if (last.contains(".")) {
                String v4 = v4(last);
                if (v4 == null) {
                    return null;
                }
                String[] o = v4.split("\\.");
                parts.remove(parts.size() - 1);
                parts.add(Integer.toHexString(Integer.parseInt(o[0]) * 256 + Integer.parseInt(o[1])));
                parts.add(Integer.toHexString(Integer.parseInt(o[2]) * 256 + Integer.parseInt(o[3])));
            }
            if (parts.size() < 3 || parts.size() > 9) {
                return null;
            }
            int skip = -1;
            for (int i = 1; i < parts.size() - 1; i++) {
                if (parts.get(i).isEmpty()) {
                    if (skip >= 0) {
                        return null;
                    }
                    skip = i;
                }
            }
            int[] groups = new int[8];
            if (skip >= 0) {
                int hi = skip;
                int lo = parts.size() - skip - 1;
                if (parts.get(0).isEmpty()) {
                    hi--;
                    if (hi != 0) {
                        return null;
                    }
                }
                if (parts.get(parts.size() - 1).isEmpty()) {
                    lo--;
                    if (lo != 0) {
                        return null;
                    }
                }
                if (hi + lo > 7) {
                    return null;
                }
                List<String> head = parts.subList(0, hi);
                List<String> end = parts.subList(parts.size() - lo, parts.size());
                for (int i = 0; i < hi; i++) {
                    Integer g = hextet(head.get(i));
                    if (g == null) {
                        return null;
                    }
                    groups[i] = g;
                }
                for (int i = 0; i < lo; i++) {
                    Integer g = hextet(end.get(i));
                    if (g == null) {
                        return null;
                    }
                    groups[8 - lo + i] = g;
                }
            } else {
                if (parts.size() != 8) {
                    return null;
                }
                for (int i = 0; i < 8; i++) {
                    Integer g = hextet(parts.get(i));
                    if (g == null) {
                        return null;
                    }
                    groups[i] = g;
                }
            }
            int bestStart = -1;
            int bestLen = 0;
            int start = -1;
            for (int i = 0; i <= 8; i++) {
                if (i < 8 && groups[i] == 0) {
                    if (start < 0) {
                        start = i;
                    }
                } else if (start >= 0) {
                    if (i - start > bestLen) {
                        bestLen = i - start;
                        bestStart = start;
                    }
                    start = -1;
                }
            }
            List<String> hex = new ArrayList<>();
            for (int g : groups) {
                hex.add(Integer.toHexString(g));
            }
            if (bestLen > 1) {
                List<String> out = new ArrayList<>(hex.subList(0, bestStart));
                out.add("");
                out.addAll(hex.subList(bestStart + bestLen, 8));
                if (bestStart == 0) {
                    out.add(0, "");
                }
                if (bestStart + bestLen == 8) {
                    out.add("");
                }
                hex = out;
            }
            return String.join(":", hex) + scope;
        }

        private static Integer hextet(String s) {
            if (s.isEmpty() || s.length() > 4) {
                return null;
            }
            for (char c : s.toCharArray()) {
                if (Character.digit(c, 16) < 0 || c > 0x7f) {
                    return null;
                }
            }
            return Integer.parseInt(s, 16);
        }
    }
}
