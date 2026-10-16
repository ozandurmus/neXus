package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reference {@code vendors.py}: F5 Ethernet-trailer connections ({@code prepare_f5_connections}, reciprocal TMM
 * flow/peer IDs, the proxy's TCP legs never merged) and the F5 report ({@code f5_report}).
 */
public final class F5Vendor {
    private F5Vendor() {
    }

    /** {@code prepare_f5_connections(db, topology)}; a capture without trailer decoding raises (reference ValueError). */
    @SuppressWarnings("unchecked")
    public static void prepareConnections(Db db, Topology topology) {
        Set<String> idSet = new LinkedHashSet<>();
        for (Point p : topology.points) {
            if (p.vendor().equals("f5")) {
                idSet.add(p.captureId());
            }
        }
        List<String> ids = new ArrayList<>(idSet);
        db.exec(Sql.get("vendors", "f5_connections"));
        if (ids.isEmpty()) {
            return;
        }
        for (Object[] r : db.tuples(Sql.get("vendors", "f5_inventory"), ids)) {
            Map<String, Object> inv = (Map<String, Object>) Evidence.loads((String) r[2]);
            if (!Evidence.truthy(inv.get("f5_fields"))) {
                throw new IllegalArgumentException(r[1] + ": enable F5 trailer decoding in Settings and reattach to index TMM fields");
            }
        }
        List<IpNet.Network> networks = new ArrayList<>();
        for (String c : topology.clientCidrs) {
            networks.add(IpNet.network(c));
        }
        Map<List<Object>, Boolean> grouped = new LinkedHashMap<>();
        for (Row row : db.rows(Sql.get("vendors", "f5_tuples"), ids)) {
            List<Object> key = Arrays.asList(row.get("capture_id"), row.get("stream"), row.get("flowid"), row.get("peerid"),
                    row.get("tmm"));
            boolean client = false;
            for (String ip : Arrays.asList(row.str("src"), row.str("dst"))) {
                for (IpNet.Network net : networks) {
                    client |= net.contains(ip);
                }
            }
            grouped.put(key, grouped.getOrDefault(key, false) || client);
        }
        Map<List<Object>, List<List<Object>>> idsToKeys = new LinkedHashMap<>();
        for (List<Object> key : grouped.keySet()) {
            idsToKeys.computeIfAbsent(Arrays.asList(key.get(0), key.get(4), key.get(2)), k -> new ArrayList<>()).add(key);
        }
        List<Object[]> records = new ArrayList<>();
        for (Map.Entry<List<Object>, Boolean> e : grouped.entrySet()) {
            List<Object> key = e.getKey();
            boolean isClient = e.getValue();
            Object cid = key.get(0);
            Object stream = key.get(1);
            String fid = (String) key.get(2);
            String peer = (String) key.get(3);
            String tmm = (String) key.get(4);
            List<List<Object>> peers = new ArrayList<>();
            for (List<Object> k : idsToKeys.getOrDefault(Arrays.asList(cid, tmm, peer), List.of())) {
                if (Objects.equals(k.get(3), fid)) {
                    peers.add(k);
                }
            }
            String reason = null;
            if (fid == null || fid.isEmpty() || peer == null || peer.isEmpty() || strip0x0(fid).isEmpty()
                    || strip0x0(peer).isEmpty() || fid.contains(",") || peer.contains(",") || tmm.startsWith(":")
                    || tmm.endsWith(":")) {
                reason = "Missing/zero F5 flow, peer or processor identity";
            } else {
                Set<Object> streams = new LinkedHashSet<>();
                for (List<Object> k : idsToKeys.get(Arrays.asList(cid, tmm, fid))) {
                    streams.add(k.get(1));
                }
                if (streams.size() != 1 || peers.size() != 1) {
                    reason = "Missing or reused F5 flow/peer ID; session correspondence unknown";
                } else if (!Objects.equals(peers.get(0).get(3), fid) || grouped.get(peers.get(0)) == isClient) {
                    reason = "Non-reciprocal F5 IDs or ambiguous client networks; narrow the client CIDRs";
                }
            }
            String role = isClient ? "client" : "server";
            records.add(new Object[] {cid, stream, fid, peer, tmm, reason == null ? role : "unknown", reason});
        }
        db.execMany(Sql.get("vendors", "f5_insert"), records);
    }

    /** {@code str.strip("0x0")}: strips the characters '0' and 'x' from both ends. */
    static String strip0x0(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == '0' || s.charAt(a) == 'x')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == '0' || s.charAt(b - 1) == 'x')) {
            b--;
        }
        return s.substring(a, b);
    }

    static void stage(EngineContext ctx) {
        ctx.sections.put("f5", report(ctx.db, ctx.topology));
    }

    /** {@code f5_report(db, topology)}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> report(Db db, Topology topology) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (topology.points.stream().noneMatch(p -> p.vendor().equals("f5"))) {
            result.put("pairs", new ArrayList<>());
            result.put("requests", new ArrayList<>());
            result.put("resets", new ArrayList<>());
            return result;
        }
        List<Row> connections = db.rows(Sql.get("vendors", "f5_report_connections"));
        List<Map<String, Object>> requests = new ArrayList<>();
        List<Map<String, Object>> resets = new ArrayList<>();
        result.put("pairs", connections);
        result.put("requests", requests);
        result.put("resets", resets);
        result.put("connection_count", db.count(Sql.get("vendors", "f5_connection_count")));
        result.put("note", "Overview shows up to 100 connection pairs, 20 unknown mappings, 200 requests and 200 reset "
                + "annotations. Full frame metadata remains in the flow ladder.");
        Map<String, Point> points = topology.pointMap();
        for (Row c : connections) {
            if (!"client".equals(c.get("role")) || requests.size() >= 200) {
                continue;
            }
            Row peer = null;
            for (Row x : connections) {
                if (Objects.equals(x.get("capture_id"), c.get("capture_id")) && Objects.equals(x.get("tmm"), c.get("tmm"))
                        && Objects.equals(x.get("flowid"), c.get("peerid")) && Objects.equals(x.get("peerid"), c.get("flowid"))
                        && "server".equals(x.get("role"))) {
                    peer = x;
                    break;
                }
            }
            if (peer == null) {
                continue;
            }
            List<Row> packets = db.rows(Sql.get("vendors", "f5_request_packets"), c.get("capture_id"), c.get("stream"),
                    peer.get("stream"), c.get("flowid"), c.get("peerid"), c.get("peerid"), c.get("flowid"));
            c.put("client_flow", db.scalar(Sql.get("vendors", "f5_min_flow"), c.get("capture_id"), c.get("stream")));
            c.put("server_flow", db.scalar(Sql.get("vendors", "f5_min_flow"), c.get("capture_id"), peer.get("stream")));
            if (packets.size() > 2000) {
                c.put("reason", "F5 request evidence budget exceeded (2000 request observations)");
                continue;
            }
            Map<List<Object>, List<List<Row>>> grouped = new LinkedHashMap<>();
            Set<List<Object>> seen = new LinkedHashSet<>();
            for (Row p : packets) {
                Map<String, Object> v = (Map<String, Object>) Evidence.loads(p.str("vendor"));
                if (!v.containsKey("http.request.method")) {
                    throw new IllegalStateException("KeyError: 'http.request.method'");
                }
                List<Object> key = Arrays.asList(v.get("http.request.method"), v.get("http.request.uri"),
                        v.get("http.request.version"));
                List<Object> identity = Arrays.asList(p.get("stream"), p.get("seq"), key);
                if (!seen.add(identity)) {
                    continue;
                }
                List<List<Row>> sides = grouped.computeIfAbsent(key, k -> List.of(new ArrayList<>(), new ArrayList<>()));
                sides.get(Objects.equals(p.get("stream"), c.get("stream")) ? 0 : 1).add(p);
            }
            for (Map.Entry<List<Object>, List<List<Row>>> e : grouped.entrySet()) {
                List<Object> key = e.getKey();
                List<Row> left = e.getValue().get(0);
                List<Row> right = e.getValue().get(1);
                for (int i = 0; i < left.size(); i++) {
                    if (requests.size() >= 200) {
                        break;
                    }
                    Row a = left.get(i);
                    Row b = left.size() == right.size() ? right.get(i) : null;
                    String reason = b != null ? null : "Request counts differ across proxy legs; correspondence unknown";
                    if (left.size() > 1) {
                        reason = "Repeated identical request lines: per-request proxy association unknown";
                    }
                    Double dwell = b != null ? (b.dbl("ts") - a.dbl("ts")) * 1000 : null;
                    if (dwell != null && dwell < 0) {
                        reason = "Server request precedes client request; pairing/streaming order unknown";
                    }
                    List<Map<String, Object>> refs = Evidence.evidence(db, "o.capture_id=? AND o.frame IN (?,?)",
                            Arrays.asList(c.get("capture_id"), a.get("frame"), b != null ? b.get("frame") : (Object) (-1L)), 2);
                    List<String> parts = new ArrayList<>();
                    for (Object x : key) {
                        parts.add(x == null ? "" : (String) x);
                    }
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("device", points.get((String) a.get("point")).device());
                    request.put("request", String.join(" ", parts));
                    request.put("client_flow", c.get("client_flow"));
                    request.put("server_flow", c.get("server_flow"));
                    request.put("flowid", c.get("flowid"));
                    request.put("peerid", c.get("peerid"));
                    request.put("request_dwell_ms", reason == null ? dwell : null);
                    request.put("clock_uncertainty_ms", 0L);
                    request.put("reason", reason);
                    request.put("evidence", refs);
                    request.put("note", "Same-capture interval between tshark-decoded request-bearing frames; not "
                            + "whole-body completion or server processing");
                    requests.add(request);
                }
            }
        }
        for (Row r : db.rows(Sql.get("vendors", "f5_resets"))) {
            Map<String, Object> v = (Map<String, Object>) Evidence.loads(r.str("vendor"));
            Map<String, Object> reset = new LinkedHashMap<>();
            reset.put("device", points.get((String) r.get("point")).device());
            reset.put("flow", r.get("flow"));
            reset.put("time", r.get("corrected"));
            reset.put("reason", v.get("f5ethtrailer.rstcausetxt"));
            reset.put("evidence", Evidence.evidence(db, "o.point=? AND o.frame=?", Arrays.asList(r.get("point"), r.get("frame")), 1));
            resets.add(reset);
        }
        return result;
    }
}
