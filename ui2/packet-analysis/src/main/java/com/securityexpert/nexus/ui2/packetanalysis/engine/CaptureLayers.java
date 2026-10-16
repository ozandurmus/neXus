package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.PacketParser;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkFields;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.PacketColumns;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reference {@code capture_layers}: select one tshark-decoded IP/transport layer ({@code decoded_layers},
 * {@code layer_rows}, {@code fragment_metadata}, {@code apply_layers}, {@code overlay_layers}). Never dissects
 * packet bytes directly.
 */
final class CaptureLayers {
    static final int VERSION = 3;
    static final Set<String> TUNNELS = Set.of("gre", "vxlan", "geneve");
    static final List<String> FIELDS;

    static {
        List<String> all = new ArrayList<>(TsharkFields.BASE_FIELDS);
        all.addAll(TsharkFields.VENDOR_FIELDS);
        FIELDS = List.copyOf(all);
    }

    private CaptureLayers() {
    }

    static long number(String value) {
        if ("True".equals(value) || "False".equals(value)) {
            return "True".equals(value) ? 1L : 0L;
        }
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        return value.startsWith("0x") ? PyConvert.parseInt(value, 16) : PyConvert.parseInt(value);
    }

    static Map<String, String> flat(Object tree) {
        Map<String, String> result = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Consumer<Object>[] walk = new Consumer[1];
        walk[0] = node -> {
            if (node instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Object v = e.getValue();
                    if (!(v instanceof Map<?, ?>) && !(v instanceof List<?>)) {
                        result.putIfAbsent((String) e.getKey(), Evidence.pyStr(v));
                    }
                    walk[0].accept(v);
                }
            } else if (node instanceof List<?> l) {
                for (Object v : l) {
                    walk[0].accept(v);
                }
            }
        };
        walk[0].accept(tree);
        return result;
    }

    static Object occurrence(Map<String, Object> layers, List<String> stack, String name, int position) {
        Object value = layers.get(name);
        int index = (int) countInSlice(stack, 0, position, name);
        if (value instanceof List<?> l) {
            return index < l.size() ? l.get(index) : null;
        }
        return index == 0 ? value : null;
    }

    private static long countInSlice(List<String> stack, int from, int to, String target) {
        long n = 0;
        for (int i = from; i < to; i++) {
            if (target.equals(stack.get(i))) {
                n++;
            }
        }
        return n;
    }

    private static String firstTruthyStr(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> decodedLayers(Map<String, Object> layers) {
        String protoStr = String.valueOf(ApplicationIndex.first(layers.getOrDefault("frame", Map.of()), "frame.protocols", ""));
        List<String> stack = Arrays.asList(protoStr.split(":", -1));
        List<Integer> net = new ArrayList<>();
        List<Integer> tunnels = new ArrayList<>();
        for (int i = 0; i < stack.size(); i++) {
            String p = stack.get(i);
            if ("ip".equals(p) || "ipv6".equals(p)) {
                net.add(i);
            }
            if (TUNNELS.contains(p)) {
                tunnels.add(i);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reason", null);
        Map<String, Map<String, Object>> resultLayers = new LinkedHashMap<>();
        result.put("layers", resultLayers);
        result.put("tunnel", null);
        if (tunnels.isEmpty() && net.isEmpty()) {
            return result;
        }
        boolean hasInner = !tunnels.isEmpty() && net.stream().anyMatch(i -> i > tunnels.get(0));
        if (!tunnels.isEmpty() && (tunnels.size() != 1 || net.isEmpty() || !hasInner)) {
            result.put("reason", "Unknown tunnel layer: nested or incomplete IP envelope");
            return result;
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        if (!tunnels.isEmpty()) {
            positions.put("outer", net.get(0));
            int t0 = tunnels.get(0);
            positions.put("inner", net.stream().filter(i -> i > t0).findFirst().orElseThrow());
        } else {
            positions.put("plain", net.get(0));
        }
        Map<String, String> outer = flat(occurrence(layers, stack, stack.get(net.get(0)), net.get(0)));
        String kind = stack.contains("erspan") ? "erspan" : !tunnels.isEmpty() ? stack.get(tunnels.get(0)) : null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("kind", kind);
        info.put("outer_src", firstTruthyStr(outer.get("ip.src"), outer.get("ipv6.src")));
        info.put("outer_dst", firstTruthyStr(outer.get("ip.dst"), outer.get("ipv6.dst")));
        info.put("session", ApplicationIndex.first(layers.getOrDefault("erspan", Map.of()), "erspan.spanid"));
        Object vni = ApplicationIndex.first(layers.getOrDefault("vxlan", Map.of()), "vxlan.vni");
        if (!Evidence.truthy(vni)) {
            vni = ApplicationIndex.first(layers.getOrDefault("geneve", Map.of()), "geneve.vni");
        }
        info.put("vni", vni);
        info.put("truncated",
                number(String.valueOf(ApplicationIndex.first(layers.getOrDefault("erspan", Map.of()), "erspan.truncated", "0"))) != 0);
        result.put("tunnel", !tunnels.isEmpty() ? info : null);
        for (Map.Entry<String, Integer> entry : positions.entrySet()) {
            String choice = entry.getKey();
            int pos = entry.getValue();
            String family = stack.get(pos);
            Object ipObj = occurrence(layers, stack, family, pos);
            if (!(ipObj instanceof Map<?, ?>) || !Evidence.truthy(ApplicationIndex.first(ipObj, family + ".src"))
                    || !Evidence.truthy(ApplicationIndex.first(ipObj, family + ".dst"))) {
                result.put("reason", "Unknown tunnel layer: missing decoded IP identity");
                continue;
            }
            Map<String, Object> ip = (Map<String, Object>) ipObj;
            int end = net.stream().filter(i -> i > pos).findFirst().orElse(stack.size());
            Map<String, String> data = new LinkedHashMap<>(flat(layers.getOrDefault("frame", Map.of())));
            data.putAll(flat(ip));
            Integer l4pos = null;
            for (int i = pos + 1; i < end; i++) {
                String p = stack.get(i);
                if ("tcp".equals(p) || "udp".equals(p) || "icmp".equals(p) || "icmpv6".equals(p)) {
                    l4pos = i;
                    break;
                }
            }
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put(family, countInSlice(stack, 0, pos, family) + 1L);
            if (l4pos != null) {
                String proto = stack.get(l4pos);
                data.putAll(flat(occurrence(layers, stack, proto, l4pos)));
                scope.put(proto, countInSlice(stack, 0, l4pos, proto) + 1L);
                if ("inner".equals(choice)) {
                    for (String p : List.of("dns", "http", "tls")) {
                        data.putAll(flat(layers.getOrDefault(p, Map.of())));
                    }
                }
            }
            if (stack.subList(pos, end).contains("ipv6.fraghdr")) {
                scope.put("ipv6.fraghdr", countInSlice(stack, 0, end, "ipv6.fraghdr"));
            }
            List<String> filtered = new ArrayList<>();
            for (String p : stack.subList(pos, end)) {
                if (!TUNNELS.contains(p) && !"erspan".equals(p)) {
                    filtered.add(p);
                }
            }
            data.put("frame.protocols", String.join(":", filtered));
            Object raw = layers.get(family + "_raw");
            if (raw instanceof List<?> rawList && !rawList.isEmpty() && rawList.get(0) instanceof List<?>) {
                int idx = ((Number) scope.get(family)).intValue() - 1;
                raw = idx >= 0 && idx < rawList.size() ? rawList.get(idx) : null;
            }
            Map<String, Object> layerMap = new LinkedHashMap<>();
            layerMap.put("fields", data);
            layerMap.put("scope", scope);
            layerMap.put("raw", raw);
            layerMap.put("ip", ip);
            layerMap.put("family", family);
            resultLayers.put(choice, layerMap);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fragmentMetadata(Map<String, Object> layer) {
        Map<String, String> data = (Map<String, String>) layer.get("fields");
        Map<String, Object> ip = (Map<String, Object>) layer.get("ip");
        String family = (String) layer.get("family");
        long offset = number(data.getOrDefault("ip".equals(family) ? "ip.frag_offset" : "ipv6.fraghdr.offset", "0")) * 8;
        boolean more = number(data.getOrDefault("ip".equals(family) ? "ip.flags.mf" : "ipv6.fraghdr.more", "0")) != 0;
        if (offset == 0 && !more) {
            return null;
        }
        boolean complete = Evidence.truthy(ApplicationIndex.first(ip, family + ".reassembled.length"));
        Object target = ApplicationIndex.first(ip, "ip".equals(family) ? "ip.reassembled_in" : "ipv6.reassembled.in");
        boolean invalid = false;
        for (String k : List.of("overlap", "overlap.conflict", "multipletails", "toolongfragment", "error")) {
            for (Object v : ApplicationIndex.values(ip, family + ".fragment." + k)) {
                if (number(Evidence.pyStr(v)) != 0) {
                    invalid = true;
                    break;
                }
            }
        }
        long size = "ip".equals(family)
                ? number(data.getOrDefault("ip.len", "0")) - number(data.getOrDefault("ip.hdr_len", "0"))
                : number(data.getOrDefault("ipv6.plen", "0")) - 8;
        if ("ipv6".equals(family)) {
            Object rawFragment = ip.get("ipv6.fraghdr_raw");
            Object layerRaw = layer.get("raw");
            if (rawFragment instanceof List<?> rf && rf.size() >= 3 && layerRaw instanceof List<?> lr && lr.size() >= 2) {
                size = number(data.getOrDefault("ipv6.plen", "0"))
                        - (PyConvert.parseInt(String.valueOf(rf.get(1))) + PyConvert.parseInt(String.valueOf(rf.get(2)))
                                - PyConvert.parseInt(String.valueOf(lr.get(1))) - 40);
            } else {
                invalid = true;
            }
        }
        List<Long> members = new ArrayList<>();
        for (Object v : ApplicationIndex.values(ip, family + ".fragment")) {
            members.add(PyConvert.parseInt(String.valueOf(v)));
        }
        long localFrame = PyConvert.parseInt(data.get("frame.number"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "ip".equals(family) ? data.get("ip.id") : data.get("ipv6.fraghdr.ident"));
        out.put("protocol", "ip".equals(family) ? data.get("ip.proto") : data.get("ipv6.fraghdr.nxt"));
        out.put("family", family);
        out.put("offset", offset);
        out.put("more", more);
        out.put("size", size);
        out.put("complete", complete);
        out.put("invalid", invalid);
        out.put("target", Evidence.truthy(target) ? Long.valueOf(PyConvert.parseInt(String.valueOf(target)))
                : complete ? Long.valueOf(localFrame) : null);
        out.put("members", members);
        out.put("local_frame", localFrame);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object[] layerRows(Map<String, Object> layers, String cid, int prefixBytes) {
        Map<String, Object> decoded = decodedLayers(layers);
        Map<String, Map<String, Object>> decodedMap = (Map<String, Map<String, Object>>) decoded.get("layers");
        Map<String, Object> tunnel = (Map<String, Object>) decoded.get("tunnel");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : decodedMap.entrySet()) {
            String choice = entry.getKey();
            Map<String, Object> layer = entry.getValue();
            Map<String, String> data = (Map<String, String>) layer.get("fields");
            String stamp = data.get("frame.time_epoch");
            if (stamp != null && stamp.contains("T")) {
                data.put("frame.time_epoch", Evidence.pyStr(ApplicationIndex.isoTimestamp(stamp)));
            }
            Map<String, Object> fragment = fragmentMetadata(layer);
            if (fragment != null && Boolean.TRUE.equals(fragment.get("complete"))) {
                long reassembled = number(String.valueOf(
                        ApplicationIndex.first(layer.get("ip"), layer.get("family") + ".reassembled.length", "0")));
                if ("ip".equals(layer.get("family"))) {
                    data.put("ip.len", Long.toString(reassembled + number(data.getOrDefault("ip.hdr_len", "0"))));
                } else {
                    data.put("ipv6.plen", Long.toString(reassembled));
                }
            }
            List<String> values = new ArrayList<>(FIELDS.size());
            for (String k : FIELDS) {
                values.add(data.getOrDefault(k, ""));
            }
            Object[] parsed = PacketParser.parse(values, cid, prefixBytes, FIELDS);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < PacketColumns.NAMES.size(); i++) {
                row.put(PacketColumns.NAMES.get(i), parsed[i]);
            }
            if ("outer".equals(choice) && tunnel != null
                    && ("gre".equals(tunnel.get("kind")) || "erspan".equals(tunnel.get("kind")))) {
                Object rawObj = layer.get("raw");
                Object frameRawObj = layers.get("frame_raw");
                String frameRaw = frameRawObj instanceof List<?> l && !l.isEmpty() ? String.valueOf(l.get(0)) : "";
                if (rawObj instanceof List<?> raw && raw.size() >= 3) {
                    int start = (int) ((PyConvert.parseInt(String.valueOf(raw.get(1)))
                            + PyConvert.parseInt(String.valueOf(raw.get(2)))) * 2);
                    long size = data.containsKey("ip.src")
                            ? number(data.getOrDefault("ip.len", "0")) - PyConvert.parseInt(String.valueOf(raw.get(2)))
                            : number(data.getOrDefault("ipv6.plen", "0"));
                    int s = Math.min(Math.max(0, start), frameRaw.length());
                    int e = Math.min(Math.max(s, start + (int) Math.max(0L, size) * 2), frameRaw.length());
                    String payload = frameRaw.substring(s, e);
                    String prefix = payload.substring(0, Math.min(payload.length(), prefixBytes * 2));
                    row.put("proto", "GRE");
                    row.put("sport", 0L);
                    row.put("dport", 0L);
                    row.put("seq", 0L);
                    row.put("ack", 0L);
                    row.put("flags", 0L);
                    row.put("length", size);
                    row.put("prefix", prefix);
                    row.put("payload_hash", ApplicationIndex.sha256Hex(ApplicationIndex.fromHex(prefix)));
                    row.put("signature", ApplicationIndex.sha256Hex(
                            PyJson.dumps(Arrays.asList("GRE", row.get("ipid"), size)).getBytes(StandardCharsets.UTF_8)));
                    row.put("unsupported", "");
                    row.put("tuple_key", PyJson.dumpsCompact(Arrays.asList("GRE", row.get("src"), 0L, row.get("dst"), 0L)));
                    row.put("reverse_tuple", PyJson.dumpsCompact(Arrays.asList("GRE", row.get("dst"), 0L, row.get("src"), 0L)));
                }
            }
            Map<String, Object> info = null;
            if (tunnel != null) {
                info = new LinkedHashMap<>(tunnel);
                info.put("layer", choice);
                info.put("scope", layer.get("scope"));
            }
            if ("inner".equals(choice) && info != null) {
                info.put("truncated", Boolean.TRUE.equals(info.get("truncated"))
                        || ((Number) row.get("caplen")).longValue() < ((Number) row.get("wirelen")).longValue());
            }
            Map<String, Object> meta = (Map<String, Object>) Evidence.loads((String) row.get("path_fields"));
            if (info != null) {
                meta.put("tunnel", info);
            }
            if (fragment != null) {
                meta.put("fragment", fragment);
                if (Boolean.TRUE.equals(fragment.get("complete")) && !Boolean.TRUE.equals(fragment.get("invalid"))) {
                    row.put("unsupported", "");
                }
            }
            row.put("path_fields", PyJsonWriter.dumps(meta, false));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("layer", choice);
            item.put("local_frame", row.get("frame"));
            item.put("packet", row);
            result.add(item);
        }
        return new Object[] {result, decoded.get("reason")};
    }

    @SuppressWarnings("unchecked")
    static void applyLayers(Db db, Point point) {
        if (db.count(Sql.get("capture_layers", "layer_candidates"), point.id()) == 0) {
            return;
        }
        db.exec(Sql.get("capture_layers", "layer_cache"));
        db.exec(Sql.get("capture_layers", "layer_packets"));
        Object[] capture = db.one(Sql.get("capture_layers", "capture_row"), point.captureId());
        Map<String, Object> inventory = (Map<String, Object>) Evidence.loads((String) capture[2]);
        Point selection = point.withSourceCidr(null);
        String identity = "[" + PyJsonWriter.dumps(capture[1], false) + ", "
                + PyJsonWriter.dumps(ApplicationIndex.pointIdentity(selection, false), false) + ", " + VERSION + "]";
        Object[] cached = db.one(Sql.get("capture_layers", "cache_get"), point.id());
        String reason = null;
        if (cached == null || !identity.equals(cached[0])) {
            String binary = ApplicationIndex.tsharkBinary(db);
            Path source = Path.of((String) capture[0]);
            if (!ApplicationIndex.unchanged(source, (String) capture[1])) {
                throw new IllegalArgumentException("Capture changed; reattach before layer selection");
            }
            db.exec(Sql.get("capture_layers", "delete_packets"), point.id());
            db.exec(Sql.get("capture_layers", "layer_frames"), point.id());
            Object[] prefRow = db.one(Sql.get("application_index", "preferences"), "preferences");
            Object prefs = prefRow == null ? Map.of() : Evidence.loads((String) prefRow[0]);
            int prefixBytes = prefs instanceof Map<?, ?> m && m.get("prefix_bytes") instanceof Number n ? n.intValue() : 64;
            Map<String, Object> schemaItem = new LinkedHashMap<>();
            schemaItem.put("layer", "VARCHAR");
            schemaItem.put("local_frame", "BIGINT");
            schemaItem.put("packet", PacketColumns.TYPES);
            String batchSchema = PyJsonWriter.dumps(List.of(schemaItem), false);
            List<Map<String, Object>> batch = new ArrayList<>();
            Runnable flush = () -> {
                if (!batch.isEmpty()) {
                    db.exec(Sql.get("capture_layers", "packets_insert"), point.id(), PyJsonWriter.dumps(batch, false), batchSchema);
                    batch.clear();
                }
            };
            try {
                if (!"none".equals(point.vendor())) {
                    throw new ApplicationIndex.Failure("Tunnel layer selection with a vendor adapter is unknown");
                }
                Path directory;
                try {
                    directory = Files.createTempDirectory("packetbreaker-layer-");
                } catch (IOException e) {
                    throw new ApplicationIndex.Failure(e.getMessage());
                }
                try {
                    Path selected = directory.resolve("point.pcapng");
                    long count = db.count(Sql.get("capture_layers", "frames_count"));
                    if (ApplicationIndex.canDecodeSource(selection, inventory, count)) {
                        selected = source;
                    } else {
                        List<String> select = new ArrayList<>(List.of(binary, "-n", "-r", source.toString(), "-Y",
                                ApplicationIndex.readFilter(selection, inventory), "-w", selected.toString(), "-F", "pcapng",
                                "-o", "ip.defragment:FALSE", "-o", "ipv6.defragment:FALSE"));
                        if (inventory.get("frame_limit") != null) {
                            select.addAll(List.of("-c", Evidence.pyStr(inventory.get("frame_limit"))));
                        }
                        int selectCode = ApplicationIndex.runSelect(select);
                        if (selectCode != 0 && !Evidence.truthy(inventory.get("damaged_tail"))) {
                            throw new ApplicationIndex.Failure("tshark point selection failed");
                        }
                        long expected = db.count(Sql.get("capture_layers", "frames_count"));
                        if (ApplicationIndex.completeRecords(selected) != expected) {
                            throw new ApplicationIndex.Failure("Layer frame mapping differs from validated index");
                        }
                    }
                    List<String> cmd = List.of(binary, "-n", "-2", "-r", selected.toString(), "-Y",
                            "gre || vxlan || geneve || ip.flags.mf || ip.frag_offset > 0 || ipv6.fraghdr", "-T", "json", "-x",
                            "--no-duplicate-keys", "-o", "ip.defragment:TRUE", "-o", "ipv6.defragment:TRUE", "-o",
                            "tcp.desegment_tcp_streams:FALSE");
                    int code;
                    try (TsharkRunner proc = TsharkRunner.start(cmd, null, null, List.of("SSLKEYLOGFILE"))) {
                        ApplicationIndex.jsonPackets(proc.stdout(), packet -> {
                            Map<String, Object> pktLayers = (Map<String, Object>) ((Map<String, Object>) packet.get("_source"))
                                    .get("layers");
                            Object[] decoded = layerRows(pktLayers, point.captureId(), prefixBytes);
                            batch.addAll((List<Map<String, Object>>) decoded[0]);
                            if (batch.size() >= 500) {
                                flush.run();
                            }
                        });
                        code = proc.waitFor();
                    }
                    if (code != 0) {
                        throw new ApplicationIndex.Failure("tshark layer decode failed");
                    }
                    flush.run();
                } finally {
                    ApplicationIndex.deleteTree(directory);
                }
            } catch (ApplicationIndex.Failure | IllegalArgumentException | UncheckedIOException exc) {
                reason = exc.getMessage();
                db.exec(Sql.get("capture_layers", "delete_packets"), point.id());
            }
            db.exec(Sql.get("capture_layers", "cache_insert"), point.id(), identity, reason);
        } else {
            reason = (String) cached[1];
        }
        if (Evidence.truthy(reason)) {
            db.exec(Sql.get("capture_layers", "layer_unavailable"), "Capture layer decode unavailable: " + reason, point.id());
            return;
        }
        overlayLayers(db, point.id(), point.matchingLayer(), 5000);
    }

    static void overlayLayers(Db db, String pointId, String chosen, int batchSize) {
        db.exec(Sql.get("capture_layers", "layer_batches"), batchSize, pointId);
        Object maxBatch = db.scalar(Sql.get("capture_layers", "max_batch"));
        List<String> excluded = List.of("frame", "capture_id", "iface", "frame_hash", "ts");
        String decoded = "c.frame IS NOT NULL AND (json_extract_string(c.p.path_fields,'$.fragment.complete') IS DISTINCT FROM 'false' OR json_exists(c.p.path_fields,'$.tunnel'))";
        Map<String, String> replacements = new LinkedHashMap<>();
        for (String k : PacketColumns.NAMES) {
            if (!excluded.contains(k)) {
                replacements.put(k, "CASE WHEN " + decoded + " THEN c.p.\"" + k + "\" ELSE o.\"" + k + "\" END");
            }
        }
        String pf = replacements.get("path_fields");
        replacements.put("path_fields", "CASE WHEN json_exists(c.p.path_fields,'$.fragment') THEN json_merge_patch(" + pf
                + ",c.p.path_fields) ELSE " + pf + " END");
        String unsup = replacements.get("unsupported");
        replacements.put("unsupported", "CASE WHEN (" + unsup
                + ") LIKE 'Tunnel encapsulation%' THEN 'Tunnel encapsulation: choose inner or outer; incomplete/nested layers stay unknown' ELSE "
                + unsup + " END");
        for (String[] pair : new String[][] {{"raw_seq", "seq"}, {"raw_ack", "ack"}, {"canon", "tuple_key"}}) {
            replacements.put(pair[0], "CASE WHEN " + decoded + " THEN c.p.\"" + pair[1] + "\" ELSE o.\"" + pair[0] + "\" END");
        }
        List<String> exprs = new ArrayList<>();
        for (Map.Entry<String, String> e : replacements.entrySet()) {
            exprs.add(e.getValue() + " AS \"" + e.getKey() + "\"");
        }
        String replaceSql = Sql.fmt("capture_layers", "layer_overlay", "replacements", String.join(",", exprs));
        String schemaJson = PyJsonWriter.dumps(PacketColumns.TYPES, false);
        long count = maxBatch == null ? 0 : ((Number) maxBatch).longValue() + 1;
        for (long batch = 0; batch < count; batch++) {
            db.exec(Sql.get("capture_layers", "batch_frames"), batch);
            db.exec(Sql.get("capture_layers", "chosen_layer"), schemaJson, pointId, chosen, chosen);
            db.exec(replaceSql, pointId);
            db.exec(Sql.get("capture_layers", "delete_batch_obs"), pointId);
            db.exec(Sql.get("capture_layers", "insert_overlay"));
            db.exec(Sql.get("capture_layers", "drop_overlay"));
            db.exec(Sql.get("capture_layers", "drop_chosen"));
        }
        db.exec(Sql.get("capture_layers", "tunnel_unselected"), pointId);
    }
}
