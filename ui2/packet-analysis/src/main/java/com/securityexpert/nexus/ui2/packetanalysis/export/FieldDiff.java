package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Evidence;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Sql;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Port of the reference {@code field_diff.py}: compares observed headers of an already matched occurrence
 * across adjacent non-proxy hops without inferring missing fields.
 */
public final class FieldDiff {
    public static final List<String> FIELDS = List.of(
            "src",
            "dst",
            "sport",
            "dport",
            "ttl",
            "dscp",
            "mss",
            "window_scale",
            "tcp_options",
            "sack_permitted",
            "timestamp_value",
            "timestamp_echo",
            "seq_offset",
            "payload_sha256",
            "ipid");

    public static final String TOOLTIP =
            "Raw observed fields across an occurrence matched using confirmed tuple/sequence mappings. "
                    + "Payload hashes cover the complete captured transport payload only; truncated payloads and "
                    + "different offload boundaries are unknown. IPv6 IP ID is not applicable (flow label is separate). "
                    + "A difference alone does not prove malicious modification.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FieldDiff() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> values(Map<String, Object> packet) {
        Map<String, Object> value = new LinkedHashMap<>(packet);
        Object pf = packet.get("path_fields");
        String pfText = pf instanceof String s && !s.isEmpty() ? s : "{}";
        Object parsed = Evidence.loads(pfText);
        if (parsed instanceof Map<?, ?> meta) {
            for (Map.Entry<?, ?> e : meta.entrySet()) {
                value.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if ("TCP".equals(packet.get("proto"))) {
            long rawSeq = requireLong(packet.get("raw_seq"), "-");
            long seq = requireLong(packet.get("seq"), "-");
            value.put("seq_offset", PyMath.mod(rawSeq - seq, 4294967296L));
        } else {
            value.put("seq_offset", null);
        }
        if (!truthy(value.get("payload_complete"))) {
            value.put("payload_sha256", null);
        }
        if (String.valueOf(packet.get("src")).contains(":")) {
            value.put("ipid", null);
        }
        long flags = requireLong(packet.get("flags"), "&");
        if ((flags & 2L) == 0L || !truthy(packet.get("mss"))) {
            value.put("mss", null);
        }
        return value;
    }

    public static Map<String, Object> fieldDiffs(Db db, Topology topology) {
        return fieldDiffs(db, topology, null, 200);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fieldDiffs(Db db, Topology topology, String packetKey, int limit) {
        List<String> reversePath = !topology.reverse.isEmpty()
                ? topology.reverse
                : Topology.reversed(topology.forward);
        List<Map.Entry<String, List<String>>> paths = List.of(
                Map.entry("forward", topology.forward),
                Map.entry("reverse", reversePath));
        Map<String, Topology.Point> points = topology.pointMap();
        List<Map<String, Object>> result = new ArrayList<>();
        int totalSegments = 0;
        for (Map.Entry<String, List<String>> entry : paths) {
            totalSegments += Math.max(0, entry.getValue().size() - 1);
        }
        int perSegment = Math.max(1, (int) PyMath.floorDiv(limit, Math.max(1, totalSegments)));
        for (Map.Entry<String, List<String>> entry : paths) {
            String direction = entry.getKey();
            List<String> path = entry.getValue();
            for (int i = 0; i + 1 < path.size(); i++) {
                String a = path.get(i);
                String b = path.get(i + 1);
                if ("full_proxy".equals(points.get(a).translation())
                        || "full_proxy".equals(points.get(b).translation())) {
                    continue;
                }
                List<Db.Row> matched = db.rows(
                        Sql.get("field_diff", "matched_pairs"),
                        a,
                        b,
                        direction,
                        packetKey,
                        packetKey,
                        (long) perSegment);
                Set<String> keySet = new LinkedHashSet<>();
                for (Db.Row r : matched) {
                    Map<String, Object> bp = (Map<String, Object>) r.get("before_packet");
                    Map<String, Object> ap = (Map<String, Object>) r.get("after_packet");
                    keySet.add((String) bp.get("packet_key"));
                    keySet.add((String) ap.get("packet_key"));
                }
                List<String> keys = new ArrayList<>(keySet);
                List<Map<String, Object>> refs = !keys.isEmpty()
                        ? Evidence.evidence(
                                db,
                                "o.point IN (?,?) AND o.packet_key IN (SELECT unnest(?::VARCHAR[]))",
                                Arrays.asList(a, b, keys),
                                (limit + 1) * 2,
                                false)
                        : List.of();
                Map<List<Object>, Map<String, Object>> refmap = new LinkedHashMap<>();
                for (Map<String, Object> r : refs) {
                    refmap.put(Arrays.asList(r.get("point"), r.get("frame")), r);
                }
                for (Db.Row pair : matched) {
                    Map<String, Object> packet = (Map<String, Object>) pair.get("before_packet");
                    Map<String, Object> peer = (Map<String, Object>) pair.get("after_packet");
                    Map<String, Object> left = values(packet);
                    Map<String, Object> right = values(peer);
                    Map<String, Object> fields = new LinkedHashMap<>();
                    for (String name : FIELDS) {
                        Object lv = left.get(name);
                        Object rv = right.get(name);
                        String status = (lv == null || rv == null)
                                ? "unknown"
                                : pyEquals(lv, rv) ? "unchanged" : "changed";
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("before", lv);
                        f.put("after", rv);
                        f.put("status", status);
                        fields.put(name, f);
                    }
                    if (!pyEquals(packet.get("length"), peer.get("length"))) {
                        ((Map<String, Object>) fields.get("payload_sha256")).put("status", "unknown");
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("point_a", a);
                    item.put("point_b", b);
                    item.put("device",
                            Objects.equals(points.get(a).device(), points.get(b).device())
                                    ? points.get(a).device()
                                    : null);
                    item.put("location", points.get(a).label() + " \u2192 " + points.get(b).label());
                    item.put("packet_key", packet.get("packet_key"));
                    item.put("flow", packet.get("flow"));
                    item.put("time", packet.get("corrected"));
                    item.put("direction", direction);
                    item.put("fields", fields);
                    item.put("tooltip", TOOLTIP);
                    item.put("evidence", new ArrayList<>(List.of(
                            ref(refmap, a, packet.get("frame")),
                            ref(refmap, b, peer.get("frame")))));
                    result.add(item);
                    if (result.size() >= limit) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("items", result);
                        out.put("limit", (long) limit);
                        out.put("note",
                                "Bounded matched-packet sample; select a packet in the stream drill-down for its comparisons.");
                        return out;
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result);
        out.put("limit", (long) limit);
        out.put("note",
                "Bounded sample distributed across segments. Unmatched, unsupported proxy boundaries and differently segmented offload frames have no packet-level comparison: unknown.");
        return out;
    }

    /** {@code refmap[(point, frame)]}: a missing key is the reference's KeyError, never a null evidence entry. */
    private static Map<String, Object> ref(Map<List<Object>, Map<String, Object>> refmap, String point, Object frame) {
        Map<String, Object> r = refmap.get(Arrays.asList(point, frame));
        if (r == null) {
            throw new IllegalStateException("KeyError: (" + point + ", " + frame + ")");
        }
        return r;
    }

    /** An integer operand the reference applies {@code -} or {@code &} to: None is its TypeError. */
    private static long requireLong(Object v, String operator) {
        if (v == null) {
            throw new IllegalStateException("unsupported operand type(s) for " + operator
                    + ": 'NoneType' and 'int'");
        }
        return toLong(v);
    }

    public static Map<String, Object> packetDiff(Db db, String packetKey) {
        if (!truthy(Export.getSetting(db, "report"))) {
            throw new IllegalArgumentException("Run analysis before inspecting field differences");
        }
        Object rawTopology = Export.getSetting(db, "topology");
        if (!truthy(rawTopology)) {
            throw new IllegalArgumentException("Run analysis before inspecting field differences");
        }
        Topology topology = Topology.parse(MAPPER.valueToTree(rawTopology));
        return fieldDiffs(db, topology, packetKey, 200);
    }

    public static Map<String, Object> packetDiff(Path projectPath, String packetKey) {
        try (Db db = Export.openDb(projectPath)) {
            return packetDiff(db, packetKey);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> packetDiff(ProjectStore project, String packetKey) {
        return packetDiff(project.path(), packetKey);
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    static boolean pyEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if ((a instanceof Number || a instanceof Boolean) && (b instanceof Number || b instanceof Boolean)) {
            // Python: True == 1 and False == 0, numbers by exact value
            return PyArith.eq(toPyNum(a), toPyNum(b));
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!pyEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size() || !ma.keySet().equals(mb.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!pyEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    static PyNum toPyNum(Object o) {
        if (o instanceof Boolean flag) {
            return new PyInt(flag ? 1L : 0L);
        }
        Number n = (Number) o;
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return new PyInt(n.longValue());
        }
        return new PyFloat(n.doubleValue());
    }
}
