package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reference {@code tls_analysis.tls_checks}: visible certificate chain comparisons with conservative attribution. */
final class Tls {
    private final Db db;
    private final Map<String, Point> points;
    private final Map<String, ClockModel> models;
    private final Double start;
    private final Double end;
    private final Map<List<Object>, Map<String, Object>> referenceLookup;
    private final Set<List<Object>> inconsistent = new LinkedHashSet<>();
    final List<Map<String, Object>> findings = new ArrayList<>();
    final List<Object> notes = new ArrayList<>();

    private Tls(EngineContext ctx, Map<List<Object>, Map<String, Object>> lookup) {
        this.db = ctx.db;
        this.points = ctx.topology.pointMap();
        this.models = ctx.models;
        this.start = ctx.start;
        this.end = ctx.end;
        this.referenceLookup = lookup;
    }

    static void stage(EngineContext ctx) {
        List<Object> result = checks(ctx);
        Parity.record(ctx, "tls_checks", result);
        ctx.snapshot("path", List.of("modified_payload_pairs", "fragment_groups", "fragment_members", "layer_packets", "layer_cache"));
        ctx.findings.addAll(ctx.path.tlsFindings);
    }

    static List<Object> checks(EngineContext ctx) {
        Db db = ctx.db;
        if (db.count(Sql.get("tls", "has_setup")) == 0) {
            ctx.path.tlsFindings = new ArrayList<>();
            ctx.path.tlsNotes = new ArrayList<>();
            return Parity.pair(ctx.path.tlsFindings, ctx.path.tlsNotes);
        }
        List<Row> facts = db.rows(Sql.get("tls", "facts"));
        Set<List<Object>> keys = new LinkedHashSet<>();
        for (Row r : facts) {
            for (String k : List.of("last_frame", "cert_frame")) {
                if (Evidence.truthy(r.get(k))) {
                    keys.add(Arrays.asList(r.get("point"), r.get(k)));
                }
            }
        }
        Tls t = new Tls(ctx, Proxies.frameLookup(db, keys));
        t.run(ctx, facts);
        ctx.path.tlsFindings = t.findings;
        ctx.path.tlsNotes = t.notes;
        return Parity.pair(t.findings, t.notes);
    }

    private static List<Object> key(Map<String, Object> r) {
        return Arrays.asList(r.get("flow"), r.get("start_seq"), r.get("sni"));
    }

    private static Map<String, Object> ref(Object point, Object frame) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("point", point);
        m.put("frame", frame);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void run(EngineContext ctx, List<Row> facts) {
        Map<Object, Row> byid = new LinkedHashMap<>();
        for (Row r : facts) {
            byid.put(r.get("id"), r);
        }
        for (Row r : facts) {
            Object chainValue = r.get("chain");
            List<Object> chain = Evidence.truthy(chainValue) ? (List<Object>) Evidence.loads(String.valueOf(chainValue)) : new ArrayList<>();
            r.put("chain", chain);
            List<Object> fingerprints = new ArrayList<>();
            boolean incomplete = chain.isEmpty();
            for (Object c : chain) {
                Map<String, Object> cert = (Map<String, Object>) c;
                fingerprints.add(cert.get("fingerprint"));
                if (!Evidence.truthy(cert.get("subject")) || !Evidence.truthy(cert.get("issuer"))) {
                    incomplete = true;
                }
            }
            r.put("fingerprints", fingerprints);
            Object version = r.get("version");
            Object unknown = version != null && ((Number) version).longValue() == 0x304 ? "TLS 1.3 certificates encrypted; interception unknown"
                    : incomplete ? "Certificate chain absent/incomplete; interception unknown"
                    : !Boolean.TRUE.equals(r.get("ready")) ? r.get("reason") : null;
            r.put("unknown", unknown);
            if (Evidence.truthy(unknown)) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("status", "unknown");
                note.put("point", r.get("point"));
                note.put("sni", r.get("sni"));
                note.put("reason", unknown);
                note.put("evidence", Proxies.frameRefs(db, List.of(ref(r.get("point"), r.get("last_frame"))), referenceLookup));
                notes.add(note);
            }
        }
        Map<List<Object>, List<Row>> groups = new LinkedHashMap<>();
        Map<Object, List<Row>> byPoint = new LinkedHashMap<>();
        Map<List<Object>, List<Row>> byPointKey = new LinkedHashMap<>();
        for (Row r : facts) {
            groups.computeIfAbsent(key(r), k -> new ArrayList<>()).add(r);
            byPoint.computeIfAbsent(r.get("point"), k -> new ArrayList<>()).add(r);
            byPointKey.computeIfAbsent(Arrays.asList(r.get("point"), key(r)), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<List<Object>, List<Row>> e : groups.entrySet()) {
            Set<Object> distinct = new LinkedHashSet<>();
            for (Row r : e.getValue()) {
                if (Evidence.truthy(r.get("fingerprints"))) {
                    distinct.add(r.get("fingerprints"));
                }
            }
            if (distinct.size() > 1) {
                inconsistent.add(e.getKey());
            }
        }
        for (Map<String, Object> segment : ctx.segments) {
            if (!"forward".equals(segment.get("direction"))) {
                continue;
            }
            Point a = points.get(segment.get("point_a"));
            Point b = points.get(segment.get("point_b"));
            if (a.device().equals(b.device()) && (a.translation().equals("full_proxy") || b.translation().equals("full_proxy"))) {
                continue;
            }
            for (Row ra : byPoint.getOrDefault(a.id(), List.of())) {
                List<Row> matches = byPointKey.getOrDefault(Arrays.asList(b.id(), key(ra)), List.of());
                if (matches.size() == 1) {
                    compare(ra, matches.get(0), segment, false);
                }
            }
        }
        for (Object[] p : db.tuples(Sql.get("tls", "matched_pairs"))) {
            Row a = byid.get(p[1]);
            Row b = byid.get(p[2]);
            if (a == null || b == null) {
                continue;
            }
            for (Map<String, Object> s : ctx.segments) {
                if ("forward".equals(s.get("direction")) && Objects.equals(s.get("point_a"), a.get("point"))
                        && Objects.equals(s.get("point_b"), b.get("point"))) {
                    compare(a, b, s, true);
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void compare(Row a, Row b, Map<String, Object> segment, boolean proxy) {
        if (a.get("start") == null || start != null && a.dbl("start") < start || end != null && a.dbl("start") > end) {
            return;
        }
        Object reason = Proxies.firstTruthy(a.get("unknown"), b.get("unknown"));
        PyNum u = Proxies.uncertainty(models, points.get(a.get("point")), points.get(b.get("point")));
        if (u == null) {
            reason = Proxies.firstTruthy(reason, "Clock evidence insufficient to associate certificate handshakes at these points");
        }
        if (proxy && (inconsistent.contains(key(a)) || inconsistent.contains(key(b)))) {
            reason = Proxies.firstTruthy(reason, "Certificate observations disagree inside a TCP leg; proxy attribution unknown");
        }
        if (Evidence.truthy(reason)) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("status", "unknown");
            note.put("device", segment.get("device"));
            note.put("sni", a.get("sni"));
            note.put("reason", reason);
            List<Map<String, Object>> refs = new ArrayList<>();
            for (Row r : List.of(a, b)) {
                refs.add(ref(r.get("point"), Evidence.truthy(r.get("cert_frame")) ? r.get("cert_frame") : r.get("last_frame")));
            }
            note.put("evidence", Proxies.frameRefs(db, refs, null));
            notes.add(note);
            return;
        }
        List<Map<String, Object>> packets = new ArrayList<>();
        for (Row r : List.of(a, b)) {
            Map<String, Object> m = ref(r.get("point"), r.get("cert_frame"));
            m.put("corrected", r.get("response"));
            packets.add(m);
        }
        if (Objects.equals(a.get("fingerprints"), b.get("fingerprints"))) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("status", "consistent");
            note.put("device", segment.get("device"));
            note.put("sni", a.get("sni"));
            note.put("reason", "Observed certificate chains agree for this visible handshake");
            note.put("evidence", Proxies.frameRefs(db, packets, referenceLookup));
            notes.add(note);
            return;
        }
        boolean declared = points.get(segment.get("point_a")).payloadTransform().equals("ssl_inspection")
                || points.get(segment.get("point_b")).payloadTransform().equals("ssl_inspection");
        Object device = segment.get("device");
        String summary = Evidence.truthy(device) ? "TLS intercepted by " + device + " (not declared)"
                : "Certificate chain changed on link between " + points.get(a.get("point")).label() + " and "
                        + points.get(b.get("point")).label() + "; device unknown";
        if (declared) {
            summary = "Certificate chain changed at declared SSL inspection device " + Evidence.pyStr(device);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("sni", a.get("sni"));
        metrics.put("before_chain", a.get("chain"));
        metrics.put("after_chain", b.get("chain"));
        metrics.put("declared", declared);
        metrics.put("uncertainty_ms", Proxies.times1000(u));
        findings.add(Proxies.finding(db, segment, "tls_interception", summary, packets, metrics, Evidence.truthy(device),
                declared ? "quality" : "high", referenceLookup));
    }
}
