package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reference {@code evidence.py}: portable Wireshark filters built only from observed fields. */
public final class Evidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FIELD = Pattern.compile("\\b(ipv6|ip|tcp|udp|icmpv6|icmp)\\.[a-zA-Z_][\\w.]*");

    private Evidence() {
    }

    // ------------------------------------------------------------------ JSON helpers

    /** {@code json.loads}: objects keep key order, ints are Long, floats Double. */
    public static Object loads(String text) {
        try {
            return fromNode(JSON.readTree(text));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static Object fromNode(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(e.getKey(), fromNode(e.getValue()));
            }
            return out;
        }
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode x : n) {
                out.add(fromNode(x));
            }
            return out;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        return n.asText();
    }

    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put((String) e.getKey(), deepCopy(e.getValue()));
            }
            return (T) out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object x : list) {
                out.add(deepCopy(x));
            }
            return (T) out;
        }
        return value;
    }

    // ------------------------------------------------------------------ filters

    public static String tupleFilter(Object proto, Object src, Object dst, Object sport, Object dport,
            boolean bidirectional) {
        String family;
        try {
            Tuples.Address a = Tuples.address(src);
            family = a.version() == 6 ? "ipv6" : "ip";
            if (Tuples.address(dst).version() != a.version()) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        String protocol = switch (String.valueOf(proto)) {
            case "TCP" -> "tcp";
            case "UDP" -> "udp";
            case "ICMP" -> "icmp";
            case "ICMPv6" -> "icmpv6";
            case "GRE" -> "gre";
            default -> null;
        };
        if (protocol == null) {
            return null;
        }
        String forward = one(family, protocol, src, dst, sport, dport);
        if (!bidirectional) {
            return forward;
        }
        String back = one(family, protocol, dst, src, dport, sport);
        List<String> both = new ArrayList<>(List.of(forward, back));
        both.sort(null);
        return "(" + String.join(" || ", both) + ")";
    }

    private static String one(String family, String protocol, Object a, Object b, Object pa, Object pb) {
        String result = family + ".src == " + a + " && " + family + ".dst == " + b + " && " + protocol;
        if (protocol.equals("tcp") || protocol.equals("udp")) {
            result += " && " + protocol + ".srcport == " + intText(pa) + " && " + protocol + ".dstport == "
                    + intText(pb);
        }
        return "(" + result + ")";
    }

    private static String intText(Object v) {
        if (v instanceof Long l) {
            return Long.toString(l);
        }
        if (v instanceof Double d) {
            return Long.toString(PyMath.trunc(d));
        }
        if (v instanceof String s) {
            return Long.toString(PyConvert.parseInt(s));
        }
        throw new IllegalArgumentException("int() argument must be a string or a real number, not 'NoneType'");
    }

    @SuppressWarnings("unchecked")
    public static String tunnelFilter(String expression, Object tunnelValue) {
        if (!(tunnelValue instanceof Map<?, ?> t) || t.isEmpty()) {
            return expression;
        }
        Map<String, Object> tunnel = (Map<String, Object>) t;
        Map<String, Object> scope = tunnel.get("scope") instanceof Map<?, ?> s
                ? (Map<String, Object>) s : new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(scope.keySet());
        // sorted(scope, key=len, reverse=True): stable, equal lengths keep insertion order.
        keys.sort((x, y) -> Integer.compare(y.length(), x.length()));
        Matcher m = FIELD.matcher(expression);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String found = m.group();
            String key = null;
            for (String k : keys) {
                if (found.startsWith(k + ".")) {
                    key = k;
                    break;
                }
            }
            String rep = key != null ? found + "#" + pyStr(scope.get(key)) : found;
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        expression = sb.toString();
        String outerSrc = String.valueOf(tunnel.get("outer_src"));
        String outer = outerSrc.contains(":") ? "ipv6" : "ip";
        expression += " && " + outer + ".src#1 == " + outerSrc + " && " + outer + ".dst#1 == "
                + pyStr(tunnel.get("outer_dst"));
        if (tunnel.get("session") != null) {
            expression += " && erspan.spanid == " + PyConvert.parseInt(String.valueOf(tunnel.get("session")), 0);
        }
        if (tunnel.get("vni") != null) {
            expression += " && " + pyStr(tunnel.get("kind")) + ".vni == "
                    + PyConvert.parseInt(String.valueOf(tunnel.get("vni")), 0);
        }
        return expression;
    }

    static String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Double d) {
            return com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloatRepr.repr(d);
        }
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public static String contentFilter(Row p) {
        String result = tupleFilter(p.get("proto"), p.get("src"), p.get("dst"), p.get("sport"), p.get("dport"), false);
        if (result == null) {
            return null;
        }
        String src = String.valueOf(p.get("src"));
        result += " && " + (src.contains(":") ? "ipv6.flow" : "ip.id") + " == " + intText(p.get("ipid"));
        String proto = String.valueOf(p.get("proto"));
        if (proto.equals("TCP")) {
            result += " && tcp.seq_raw == " + p.get("seq") + " && tcp.ack_raw == " + p.get("ack") + " && tcp.len == "
                    + p.get("length") + " && tcp.flags == 0x" + PyFormat.format((long) (Long) p.get("flags"), "04x");
        } else if (proto.equals("UDP")) {
            result += " && udp.length == " + ((Long) p.get("length") + 8);
            if (p.get("dns_id") != null) {
                result += " && dns.id == " + p.get("dns_id");
            }
        } else if (proto.equals("ICMP")) {
            String[][] fields = {{"icmp.type", "icmp_type"}, {"icmp.ident", "icmp_id"}, {"icmp.seq", "icmp_seq"}};
            for (String[] f : fields) {
                if (p.get(f[1]) != null) {
                    result += " && " + f[0] + " == " + p.get(f[1]);
                }
            }
        } else if (proto.equals("ICMPv6") && p.get("icmp_type") != null) {
            result += " && icmpv6.type == " + p.get("icmp_type");
        }
        Object meta = loads(p.get("path_fields") != null && !((String) p.get("path_fields")).isEmpty()
                ? (String) p.get("path_fields") : "{}");
        Object tunnel = meta instanceof Map<?, ?> mm ? ((Map<String, Object>) mm).get("tunnel") : null;
        return tunnelFilter(result, tunnel);
    }

    // ------------------------------------------------------------------ flow filters

    public static void prepareFlowFilters(Db db) {
        List<Row> metadata = db.rows(Sql.get("evidence", "flow_filter_metadata"));
        Map<List<String>, Object[]> grouped = new LinkedHashMap<>();
        for (Row item : metadata) {
            List<Object> t = Tuples.parse(item.str("tuple_key"));
            String expression = tupleFilter(t.get(0), t.get(1), t.get(3), t.get(2), t.get(4), true);
            if (expression == null) {
                continue;
            }
            Object tunnel = item.get("tunnel") != null ? loads(String.valueOf(item.get("tunnel"))) : null;
            expression = tunnelFilter(expression, tunnel);
            List<String> key = List.of(item.str("flow"), item.str("capture_id"));
            Object[] g = grouped.get(key);
            if (g == null) {
                g = new Object[] {new LinkedHashSet<String>(), item.dbl("start"), item.dbl("end")};
                grouped.put(key, g);
            }
            @SuppressWarnings("unchecked")
            Set<String> expressions = (Set<String>) g[0];
            expressions.add(expression);
            g[1] = pyMin((Double) g[1], item.dbl("start"));
            g[2] = pyMax((Double) g[2], item.dbl("end"));
        }
        db.exec(Sql.get("evidence", "flow_filters"));
        List<Object[]> data = new ArrayList<>();
        for (Map.Entry<List<String>, Object[]> e : grouped.entrySet()) {
            @SuppressWarnings("unchecked")
            Set<String> expressions = new TreeSet<>((Set<String>) e.getValue()[0]);
            String expression = "(" + String.join(" || ", expressions) + ")";
            expression += " && frame.time_epoch >= " + PyFormat.format((Double) e.getValue()[1] - 1e-6, ".9f")
                    + " && frame.time_epoch <= " + PyFormat.format((Double) e.getValue()[2] + 1e-6, ".9f");
            data.add(new Object[] {e.getKey().get(0), e.getKey().get(1), expression});
        }
        db.execMany(Sql.get("evidence", "flow_filters_insert"), data);
    }

    /** Reference {@code min(a, b)}: the first argument unless the second is strictly smaller. */
    static Double pyMin(Double a, Double b) {
        return b < a ? b : a;
    }

    static Double pyMax(Double a, Double b) {
        return b > a ? b : a;
    }

    // ------------------------------------------------------------------ evidence

    public static List<Map<String, Object>> evidence(Db db, String predicate, List<Object> params) {
        return evidence(db, predicate, params, 128, true);
    }

    public static List<Map<String, Object>> evidence(Db db, String predicate, List<Object> params, int limit) {
        return evidence(db, predicate, params, limit, true);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> evidence(Db db, String predicate, List<Object> params, int limit,
            boolean expandRanges) {
        boolean filters = db.tableExists("flow_filters");
        String filterJoin = Sql.get("evidence", "filter_join");
        String sql = Sql.fmt("evidence", "evidence", "filter_join", filterJoin, "predicate", predicate, "limit", limit);
        if (!filters) {
            sql = sql.replace("ff.flow_filter,", "").replace(filterJoin, "");
        }
        List<Row> data = db.rows(sql, params.toArray());
        boolean hasRanges = expandRanges && !data.isEmpty() && db.tableExists("byte_flows")
                && db.one(Sql.get("evidence", "has_byte_flows")) != null;
        if (hasRanges) {
            List<Object> keys = new ArrayList<>(new LinkedHashSet<>(column(data, "packet_key")));
            List<Row> peers = db.rows(Sql.get("evidence", "range_peers"), keys);
            Map<List<Object>, Object> rangeContext = new LinkedHashMap<>();
            for (Row r : peers) {
                List<Object> ranges = (List<Object>) r.get("ranges");
                rangeContext.put(List.of(r.get("point"), r.get("source_frame")),
                        new ArrayList<>(ranges.subList(0, Math.min(128, ranges.size()))));
            }
            List<List<Object>> chosen = new ArrayList<>();
            for (Row r : data) {
                List<Object> k = List.of(r.get("point"), r.get("frame"));
                if (!chosen.contains(k)) {
                    chosen.add(k);
                }
            }
            List<Object> peerPoints = new ArrayList<>(new LinkedHashSet<>(column(peers, "point")));
            for (Object point : peerPoints) {
                Row peer = peers.stream().filter(r -> Objects.equals(r.get("point"), point)).findFirst().orElseThrow();
                boolean present = chosen.stream().anyMatch(c -> Objects.equals(c.get(0), point));
                if (!present) {
                    chosen.add(List.of(point, peer.get("source_frame")));
                }
            }
            for (List<Object> k : rangeContext.keySet()) {
                if (!chosen.contains(k)) {
                    chosen.add(k);
                }
            }
            chosen = new ArrayList<>(chosen.subList(0, Math.min(limit, chosen.size())));
            List<String> parts = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (List<Object> c : chosen) {
                parts.add("(o.point=? AND o.frame=?)");
                values.addAll(c);
            }
            List<Map<String, Object>> result = evidence(db, String.join(" OR ", parts), values, limit, false);
            for (Map<String, Object> item : result) {
                Object ranges = rangeContext.get(List.of(item.get("point"), item.get("frame")));
                item.put("byte_ranges", ranges != null ? ranges : new ArrayList<>());
                item.put("range_note", "One-to-many TCP sequence-range evidence; up to 128 ranges shown per frame. "
                        + "Offload timestamps describe the captured unit, not each wire segment; payload comparison is "
                        + "limited to captured prefixes.");
            }
            return result;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Row e : data) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (String key : List.of("point", "file", "capture_id", "frame", "display_filter", "observed_time",
                    "corrected_time")) {
                item.put(key, e.get(key));
            }
            if (!Objects.equals(e.get("seq"), e.get("canonical_seq")) || !Objects.equals(e.get("ack"), e.get("canonical_ack"))
                    || truthy(e.get("translation_reason"))) {
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("observed_seq", e.get("seq"));
                st.put("observed_ack", e.get("ack"));
                st.put("canonical_seq", e.get("canonical_seq"));
                st.put("canonical_ack", e.get("canonical_ack"));
                st.put("seq_offset", PyMath.mod((Long) e.get("seq") - (Long) e.get("canonical_seq"), 4294967296L));
                st.put("ack_offset", PyMath.mod((Long) e.get("ack") - (Long) e.get("canonical_ack"), 4294967296L));
                st.put("reason", e.get("translation_reason"));
                item.put("sequence_translation", st);
            }
            if (truthy(e.get("vendor"))) {
                item.put("vendor", loads(e.str("vendor")));
            }
            item.put("content_filter", contentFilter(e));
            Object ff = e.get("flow_filter");
            item.put("flow_filter", truthy(ff) ? ff
                    : tupleFilter(e.get("proto"), e.get("src"), e.get("dst"), e.get("sport"), e.get("dport"), true));
            item.put("filter_note", item.get("content_filter") != null
                    ? "Content filters survive frame renumbering; identical retransmissions or merged capture points can match multiple frames. Compare observed time."
                    : "No supported IP/L4 tuple is available for a content filter.");
            Map<String, Object> vendor = item.get("vendor") instanceof Map<?, ?> v && !v.isEmpty()
                    ? (Map<String, Object>) v : new LinkedHashMap<>();
            if ("checkpoint".equals(vendor.get("adapter"))
                    && List.of("i", "I", "o", "O", "e", "E").contains(vendor.get("fw1.direction"))) {
                if (truthy(item.get("content_filter"))) {
                    String cf = item.get("content_filter") + " && fw1.direction == "
                            + PyJsonWriter.string(String.valueOf(vendor.get("fw1.direction")));
                    if (List.of("oe", "OE").contains(vendor.get("stage"))) {
                        cf += " && fw1.chain == " + PyJsonWriter.compact(vendor.get("fw1.chain"));
                    }
                    item.put("content_filter", cf);
                }
                item.put("filter_note", item.get("filter_note")
                        + " Enable Ethernet fw monitor interpretation in Wireshark; chain/UUID settings must match the source.");
            }
            if ("paloalto".equals(vendor.get("adapter"))) {
                item.put("filter_note", item.get("filter_note")
                        + " The stage is an external file tag; preserve the original file provenance when merging.");
            }
            String pf = e.str("path_fields");
            Map<String, Object> meta = (Map<String, Object>) loads(pf != null && !pf.isEmpty() ? pf : "{}");
            item.put("tunnel", meta.get("tunnel"));
            item.put("fragment", meta.get("fragment"));
            result.add(item);
            if (item.get("fragment") instanceof Map<?, ?> frag && truthy(frag.get("members"))) {
                Map<String, Object> fragment = (Map<String, Object>) frag;
                String family = String.valueOf(fragment.get("family"));
                String field = family.equals("ip") ? "ip.id" : "ipv6.fraghdr.ident";
                String expression = family + ".src == " + e.get("src") + " && " + family + ".dst == " + e.get("dst")
                        + " && " + field + " == " + PyConvert.parseInt(String.valueOf(fragment.get("id")), 0);
                if (family.equals("ip")) {
                    expression += " && (ip.flags.mf == 1 || ip.frag_offset > 0)";
                }
                expression = tunnelFilter(expression, item.get("tunnel"));
                List<Object> members = (List<Object>) fragment.get("members");
                List<Object> head = new ArrayList<>(members.subList(0, Math.min(128, members.size())));
                Map<Object, Row> physical = new LinkedHashMap<>();
                for (Row r : db.rows(Sql.get("fragments", "fragment_member_rows"), item.get("point"), head)) {
                    physical.put(r.get("frame"), r);
                }
                if (!"complete".equals(fragment.get("status"))) {
                    item.put("content_filter", expression);
                }
                for (Object frame : head) {
                    if (Objects.equals(frame, item.get("frame"))) {
                        continue;
                    }
                    Map<String, Object> copy = new LinkedHashMap<>(item);
                    copy.put("frame", frame);
                    copy.put("display_filter", "frame.number == " + frame);
                    copy.put("content_filter", expression + " && " + family + "."
                            + (family.equals("ip") ? "frag_offset" : "fraghdr.offset") + " == "
                            + PyMath.floorDiv((Long) physical.get(frame).get("off"), 8));
                    copy.put("observed_time", physical.get(frame).get("ts"));
                    copy.put("corrected_time", null);
                    copy.put("filter_note",
                            "IP datagram fragment membership; identical reused IDs require timestamp/context comparison");
                    result.add(copy);
                }
            }
        }
        return result;
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        if (v instanceof Double d) {
            return d != 0.0;
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    static List<Object> column(List<Row> rows, String key) {
        List<Object> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(r.get(key));
        }
        return out;
    }
}
