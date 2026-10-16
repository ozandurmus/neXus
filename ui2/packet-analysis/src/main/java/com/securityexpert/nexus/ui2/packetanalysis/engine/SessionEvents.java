package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reference {@code session_events.py} (who closed connections, peer-side state loss, sender rate drops) and
 * {@code pilot.flow_columns} (window-scoped flow inventory columns).
 */
final class SessionEvents {
    static final double STORM_GAP_S = 0.05;
    static final double TRIGGER_WINDOW_S = 1.0;
    static final double RECONNECT_WINDOW_S = 300.0;
    static final double RECONNECT_NOTABLE_S = 1.0;
    static final double RATE_BUCKET_S = 10.0;
    static final double RATE_DROP_RATIO = 0.6;
    static final int RATE_DROP_MIN_BUCKETS = 6;
    static final int MIN_BASELINE_BUCKETS = 12;
    static final double BULK_SHARE = 0.10;
    static final String NOTE = "Session behaviour observed at one capture point. Endpoint roles come from the SYN direction "
            + "and the configured client CIDRs; causes outside the captures are not observed.";

    private SessionEvents() {
    }

    // ------------------------------------------------------------------ pilot.flow_columns

    @SuppressWarnings("unchecked")
    static void flowColumns(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        if (!topology.forward.isEmpty()) {
            db.exec(Sql.get("pilot", "handshake_rtt"), topology.forward.get(0));
        }
        Map<Object, TreeSet<String>> origins = new LinkedHashMap<>();
        for (Map<String, Object> finding : ctx.findings) {
            if (!List.of("reset_origin", "proxy_reset_originated", "proxy_reset_propagated").contains(finding.get("type"))
                    || !"supported".equals(finding.get("confidence"))) {
                continue;
            }
            List<Map<String, Object>> refs = (List<Map<String, Object>>) PyOps.get(finding, "evidence", new ArrayList<>());
            if (refs.isEmpty()) {
                continue;
            }
            List<Object> values = new ArrayList<>();
            List<String> placeholders = new ArrayList<>();
            for (Map<String, Object> r : refs) {
                if (r.containsKey("capture_id") && r.containsKey("frame")) {
                    values.add(r.get("capture_id"));
                    values.add(r.get("frame"));
                    placeholders.add("(?,?)");
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            for (Object[] row : db.tuples(Sql.fmt("pilot", "reset_flows", "placeholders", String.join(",", placeholders)), values.toArray())) {
                origins.computeIfAbsent(row[0], k -> new TreeSet<>(PyOps::compareStr)).add((String) finding.get("summary"));
            }
        }
        List<Object[]> updates = new ArrayList<>();
        for (Map.Entry<Object, TreeSet<String>> e : origins.entrySet()) {
            updates.add(new Object[] {String.join("; ", e.getValue()), e.getKey()});
        }
        db.execMany(Sql.get("pilot", "reset_origin"), updates);
    }

    // ------------------------------------------------------------------ session_events

    /** {@code _clock}: local wall-clock time with its UTC offset. */
    static String clock(Object ts, String zone, boolean millis) {
        String local = ((String) Headlines.timeLabels(PyOps.dbl(ts), zone).get("local")).split(" ", -1)[1];
        String clock = local.substring(0, Math.min(12, local.length()));
        String offset = local.length() > 12 ? local.substring(12) : "";
        return (millis ? clock : clock.substring(0, Math.min(8, clock.length()))) + offset;
    }

    static Map<String, Object> finding(String kind, String severity, Object start, Object end, String summary,
            Map<String, Object> metrics, List<Map<String, Object>> refs, String hop) {
        Map<String, Object> m = new LinkedHashMap<>(metrics);
        m.put("count", PyOps.get(metrics, "connections", 1L));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "session:" + kind + ":" + PyOps.fmt(start, ".6f"));
        f.put("type", kind);
        f.put("hop", hop);
        f.put("device", null);
        f.put("direction", "observed");
        f.put("severity", severity);
        f.put("confidence", "supported");
        f.put("time_range", PyOps.list(start, end));
        f.put("summary", summary);
        f.put("headline", summary);
        f.put("tooltip", NOTE);
        f.put("metrics", m);
        f.put("evidence", refs);
        f.put("cause", "unknown");
        f.put("clock_caveat", "Observed at one capture point; clock alignment is not needed for these findings");
        return f;
    }

    static List<Map<String, Object>> connectionLifecycle(Db db, String zone) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row c : db.rows(Sql.get("session_events", "lifecycle"), RECONNECT_WINDOW_S)) {
            Object flags = c.get("close_flags");
            String kind = flags == null ? null : ((Long) flags & 1) != 0 ? "fin" : ((Long) flags & 16) != 0 ? "rst_abort" : "rst_no_socket";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", c.get("flow"));
            m.put("client", PyOps.truthy(c.get("client_ip")) ? c.get("client_ip") + ":" + PyOps.str(c.get("client_port")) : null);
            m.put("server", PyOps.truthy(c.get("server_ip")) ? c.get("server_ip") + ":" + PyOps.str(c.get("server_port")) : null);
            m.put("opened", c.get("syn_t"));
            m.put("first_seen", c.get("first_t"));
            m.put("last_seen", c.get("last_t"));
            m.put("bytes_client_to_server", c.get("c2s_bytes"));
            m.put("bytes_server_to_client", c.get("s2c_bytes"));
            m.put("closed", c.get("close_t"));
            m.put("closed_by", kind == null ? null : "forward".equals(c.get("closer")) ? "client" : "server");
            m.put("close_kind", kind);
            m.put("tls_close_like", "fin".equals(kind) && PyOps.truthy(c.get("final_len")));
            m.put("rst_after_close", c.get("rsts_after_fin"));
            m.put("reconnect_after_s", c.get("next_syn") == null ? null : PyOps.sub(c.get("next_syn"), c.get("close_t")));
            m.put("reconnect_flow", c.get("next_flow"));
            m.put("close_frame", c.get("close_frame"));
            m.put("closed_label", c.get("close_t") == null ? null : clock(c.get("close_t"), zone, true));
            m.put("closed_short", c.get("close_t") == null ? null : clock(c.get("close_t"), zone, false));
            out.add(m);
        }
        return out;
    }

    static Object[] resetStorms(Db db, String point, String zone) {
        List<Row> resets = db.rows(Sql.fmt("session_events", "resets", "trigger", "r.t-" + PyOps.str(TRIGGER_WINDOW_S)));
        List<List<Row>> clusters = new ArrayList<>();
        List<Row> current = new ArrayList<>();
        for (Row r : resets) {
            if (!current.isEmpty()) {
                Row last = current.get(current.size() - 1);
                if (!r.get("direction").equals(last.get("direction")) || PyOps.gt(PyOps.sub(r.get("t"), last.get("t")), STORM_GAP_S)) {
                    clusters.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(r);
        }
        if (!current.isEmpty()) {
            clusters.add(current);
        }
        List<Map<String, Object>> storms = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (List<Row> cluster : clusters) {
            TreeSet<String> flowSet = new TreeSet<>(PyOps::compareStr);
            for (Row r : cluster) {
                flowSet.add(r.str("flow"));
            }
            if (flowSet.size() < 2) {
                continue;
            }
            Object[] sf = storm(db, point, zone, cluster, new ArrayList<>(flowSet));
            storms.add(PyOps.map(sf[0]));
            findings.add(PyOps.map(sf[1]));
        }
        return new Object[] {storms, findings};
    }

    private static String span(List<Object> v) {
        if (v.isEmpty()) {
            return "unknown";
        }
        return PyOps.fmt(Onsets.minOf(v), ".1f") + "–" + PyOps.fmt(Onsets.maxOf(v), ".1f") + " s";
    }

    private static Object[] storm(Db db, String point, String zone, List<Row> cluster, List<String> flows) {
        String direction = cluster.get(0).str("direction");
        String side = direction.equals("reverse") ? "server" : "client";
        String otherSide = side.equals("server") ? "client" : "server";
        String other = direction.equals("reverse") ? "forward" : "reverse";
        Object start = cluster.get(0).get("t");
        Object end = cluster.get(cluster.size() - 1).get("t");
        List<Row> details = db.rows(Sql.get("session_events", "storm_details"), direction, start, other, start, other, start,
                direction, flows);
        Map<Object, Object> closedFirst = new LinkedHashMap<>();
        for (Row r : cluster) {
            if (r.get("own_fin") != null) {
                closedFirst.put(r.get("flow"), PyOps.sub(start, r.get("own_fin")));
            }
        }
        boolean late = closedFirst.size() * 2 >= flows.size() + 1;
        List<Object> silences = new ArrayList<>();
        for (Row d : details) {
            if (d.get("side_last") != null) {
                silences.add(PyOps.sub(start, d.get("side_last")));
            }
        }
        TreeMap<Object, Object> normal = new TreeMap<>(PyOps::compare);
        for (Row d : details) {
            if (d.get("normal_ttl") != null) {
                normal.put(d.get("normal_ttl"), d.get("normal_ttl"));
            }
        }
        TreeMap<Object, Object> rst = new TreeMap<>(PyOps::compare);
        for (Row r : cluster) {
            rst.put(r.get("ttl"), r.get("ttl"));
        }
        List<Object> normalTtls = new ArrayList<>(normal.keySet());
        List<Object> rstTtls = new ArrayList<>(rst.keySet());
        List<Object> frames = new ArrayList<>();
        for (Row r : cluster) {
            frames.add(r.get("frame"));
        }
        for (Row d : details) {
            if (PyOps.truthy(d.get("trigger_frame"))) {
                frames.add(d.get("trigger_frame"));
            }
        }
        List<Map<String, Object>> refs = Evidence.evidence(db, "o.point=? AND o.frame IN (SELECT unnest(?::BIGINT[]))",
                Arrays.asList(point, frames), 64);
        String ttlText = rstTtls.equals(normalTtls) ? "same TTL as normal " + side + " packets"
                : "RST TTL " + PyOps.str(rstTtls) + " vs normal " + side + " TTL " + PyOps.str(normalTtls);
        long triggerFin = details.stream().filter(d -> d.get("trigger_flags") != null && ((Long) d.get("trigger_flags") & 1) != 0).count();
        String kind;
        String severity;
        String summary;
        if (late) {
            kind = "late_close";
            severity = "low";
            summary = "Late close of " + flows.size() + " connections at " + clock(start, zone, true) + ": the " + otherSide
                    + " closed connections that the " + side + " had already closed with FIN " + span(new ArrayList<>(closedFirst.values()))
                    + " earlier (" + triggerFin + " of " + flows.size() + " triggers carry FIN); the " + side + " answered with RSTs. "
                    + "These RSTs are the expected answer, not state loss; the " + otherSide + " reacts late to " + side + " closes.";
        } else {
            kind = "state_loss";
            severity = "high";
            summary = "Peer-side state loss for " + flows.size() + " connections at " + clock(start, zone, true) + ": the " + side
                    + " side answered with no-socket RSTs (no ACK flag) after " + span(silences) + " of " + side
                    + " silence and without closing first; " + ttlText + ". Typical causes: LB/firewall session flush or failover, "
                    + "rerouting to another backend, or a " + side + " restart.";
        }
        List<Object> rstTimes = new ArrayList<>();
        for (Row r : cluster) {
            rstTimes.add(r.get("t"));
        }
        List<Object> closed = new ArrayList<>(closedFirst.values());
        Map<String, Object> storm = new LinkedHashMap<>();
        storm.put("kind", kind);
        storm.put("time", start);
        storm.put("label", clock(start, zone, true));
        storm.put("short", clock(start, zone, false));
        storm.put("side", side);
        storm.put("connections", (long) flows.size());
        storm.put("flows", new ArrayList<>(flows));
        storm.put("silence_before_s", silences.isEmpty() ? null : dict("min", Onsets.minOf(silences), "max", Onsets.maxOf(silences)));
        storm.put("closed_by_resetting_side_before_s", closed.isEmpty() ? null : dict("min", Onsets.minOf(closed), "max", Onsets.maxOf(closed)));
        storm.put("rst_ttl", rstTtls);
        storm.put("normal_ttl", normalTtls);
        storm.put("resets", (long) cluster.size());
        storm.put("rst_times", rstTimes);
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : storm.entrySet()) {
            if (!List.of("flows", "rst_times").contains(e.getKey())) {
                metrics.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> f = finding(kind.equals("state_loss") ? "reset_storm" : "late_close_resets", severity, start, end, summary,
                metrics, refs, "observed:" + point + ":" + point);
        return new Object[] {storm, f};
    }

    private static List<Row> buckets(Db db, Object flow, String dataDirection, Object lo, Object hi) {
        String ackDirection = dataDirection.equals("reverse") ? "forward" : "reverse";
        String bucket = PyOps.str(RATE_BUCKET_S);
        List<Row> acks = db.rows(Sql.fmt("session_events", "acks", "bucket", bucket), flow, ackDirection, lo, lo, hi);
        Map<Object, Object> retrans = new LinkedHashMap<>();
        for (Object[] r : db.tuples(Sql.fmt("session_events", "bucket_retrans", "bucket", bucket), lo, flow, dataDirection, lo, hi)) {
            retrans.put(r[0], r[1]);
        }
        for (Row b : acks) {
            b.put("retrans", retrans.containsKey(b.get("bucket")) ? retrans.get(b.get("bucket")) : 0L);
        }
        return acks;
    }

    private static List<List<Row>> lowRuns(List<Row> buckets, Object baseline) {
        List<List<Row>> runs = new ArrayList<>();
        List<Row> run = new ArrayList<>();
        Object limit = PyOps.mul(RATE_DROP_RATIO, baseline);
        for (Row b : buckets) {
            boolean low = PyOps.le(b.get("delivered"), limit);
            if (low && !run.isEmpty() && PyOps.numEq(b.get("bucket"), PyOps.add(run.get(run.size() - 1).get("bucket"), 1L))) {
                run.add(b);
                continue;
            }
            if (run.size() >= RATE_DROP_MIN_BUCKETS) {
                runs.add(run);
            }
            run = low ? new ArrayList<>(List.of(b)) : new ArrayList<>();
        }
        if (run.size() >= RATE_DROP_MIN_BUCKETS) {
            runs.add(run);
        }
        return runs;
    }

    static Object[] rateDrops(Db db, String point, String zone) {
        List<Row> flows = db.rows(Sql.get("session_events", "flows_by_volume"));
        List<Map<String, Object>> drops = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        if (flows.isEmpty()) {
            return new Object[] {drops, findings};
        }
        Object largest = PyOps.max(flows.get(0).get("c2s_bytes"), flows.get(0).get("s2c_bytes"));
        for (Row f : flows) {
            Object volume = PyOps.max(f.get("c2s_bytes"), f.get("s2c_bytes"));
            if (!PyOps.truthy(largest) || PyOps.lt(volume, PyOps.mul(BULK_SHARE, largest))) {
                break;
            }
            String dataDirection = PyOps.ge(f.get("s2c_bytes"), f.get("c2s_bytes")) ? "reverse" : "forward";
            Object lo = f.get("first_t");
            Object hi = PyOps.sub(PyOps.truthy(f.get("close_t")) ? f.get("close_t") : f.get("last_t"), RATE_BUCKET_S);
            List<Row> bs = new ArrayList<>();
            for (Row b : buckets(db, f.get("flow"), dataDirection, lo, hi)) {
                if (b.get("delivered") != null) {
                    bs.add(b);
                }
            }
            if (bs.size() < MIN_BASELINE_BUCKETS) {
                continue;
            }
            List<Object> delivered = new ArrayList<>();
            List<Object> windows = new ArrayList<>();
            List<Object> rtts = new ArrayList<>();
            List<Object> retrans = new ArrayList<>();
            for (Row b : bs) {
                delivered.add(b.get("delivered"));
                if (b.get("median_window") != null) {
                    windows.add(b.get("median_window"));
                }
                if (b.get("rtt_p95") != null) {
                    rtts.add(b.get("rtt_p95"));
                }
                retrans.add(b.get("retrans"));
            }
            Object baseline = PyOps.median(delivered);
            if (!PyOps.truthy(baseline)) {
                continue;
            }
            Map<String, Object> base = dict("delivered", baseline, "window", windows.isEmpty() ? null : PyOps.median(windows), "rtt",
                    rtts.isEmpty() ? null : PyOps.median(rtts), "retrans", PyOps.median(retrans));
            for (List<Row> run : lowRuns(bs, baseline)) {
                Object[] r = classifyDrop(db, point, f, dataDirection, run, lo, base, zone);
                drops.add(PyOps.map(r[0]));
                if (r[1] != null) {
                    findings.add(PyOps.map(r[1]));
                }
            }
        }
        return new Object[] {drops, findings};
    }

    private static Object[] classifyDrop(Db db, String point, Row flow, String dataDirection, List<Row> run, Object lo,
            Map<String, Object> base, String zone) {
        Object baseline = base.get("delivered");
        Object baseWindow = base.get("window");
        Object baseRtt = base.get("rtt");
        Object baseRetrans = base.get("retrans");
        Object start = PyOps.add(lo, PyOps.mul(run.get(0).get("bucket"), RATE_BUCKET_S));
        Object end = PyOps.add(lo, PyOps.mul(PyOps.add(run.get(run.size() - 1).get("bucket"), 1L), RATE_BUCKET_S));
        List<Object> delivered = new ArrayList<>();
        List<Object> windows = new ArrayList<>();
        List<Object> rtts = new ArrayList<>();
        List<Object> retrans = new ArrayList<>();
        boolean zero = false;
        for (Row b : run) {
            delivered.add(b.get("delivered"));
            if (b.get("min_window") != null) {
                windows.add(b.get("min_window"));
            }
            if (b.get("rtt_p95") != null) {
                rtts.add(b.get("rtt_p95"));
            }
            retrans.add(b.get("retrans"));
            zero |= PyOps.truthy(b.get("zero_window"));
        }
        Object rate = PyOps.median(delivered);
        long losses = db.count(Sql.get("session_events", "drop_losses"), flow.get("flow"), start, end);
        boolean windowLimited = zero || (PyOps.truthy(baseWindow) && !windows.isEmpty()
                && PyOps.lt(Onsets.minOf(windows), PyOps.mul(0.5, baseWindow)));
        boolean rttUp = PyOps.truthy(baseRtt) && !rtts.isEmpty()
                && PyOps.gt(PyOps.median(rtts), PyOps.add(PyOps.mul(1.5, baseRtt), 0.001));
        boolean retransUp = PyOps.gt(PyOps.sum(retrans), PyOps.mul(PyOps.mul(PyOps.add(baseRetrans, 1L), (long) run.size()), 2L));
        String cause = windowLimited ? "receiver_window" : losses > 0 || rttUp || retransUp ? "network" : "sender";
        String sender = dataDirection.equals("reverse") ? "server" : "client";
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("flow", flow.get("flow"));
        item.put("start", start);
        item.put("end", end);
        item.put("label", clock(start, zone, true) + "–" + clock(end, zone, true));
        item.put("sender", sender);
        item.put("baseline_bytes_per_s", PyOps.div(baseline, RATE_BUCKET_S));
        item.put("drop_bytes_per_s", PyOps.div(rate, RATE_BUCKET_S));
        item.put("drop_percent", PyOps.mul(100L, PyOps.sub(1L, PyOps.div(rate, baseline))));
        item.put("losses", losses);
        item.put("receiver_window_limited", windowLimited);
        item.put("rtt_increased", rttUp);
        item.put("retrans_increased", retransUp);
        item.put("cause", cause);
        if (cause.equals("network")) {
            return new Object[] {item, null};
        }
        List<Map<String, Object>> refs = Evidence.evidence(db,
                "o.point=? AND o.flow=? AND o.corrected>=? AND o.corrected<? AND (o.flags&16)>0",
                Arrays.asList(point, flow.get("flow"), start, end), 8);
        String pair = flow.get("server_ip") + ":" + PyOps.str(flow.get("server_port")) + " ⇄ " + flow.get("client_ip") + ":"
                + PyOps.str(flow.get("client_port"));
        String hop = "observed:" + point + ":" + point;
        if (cause.equals("receiver_window")) {
            String summary = "Receiver-limited slowdown on " + pair + " from " + item.get("label") + ": delivered rate −"
                    + PyOps.fmt(item.get("drop_percent"), ".0f") + "% while the "
                    + "receiver's advertised window shrank (receiver/application slow, not the network).";
            return new Object[] {item, finding("receiver_rate_drop", "low", start, end, summary, item, refs, hop)};
        }
        String summary = "The " + sender + " reduced its sending rate on " + pair + " from " + item.get("label") + ": delivered "
                + PyOps.fmt(PyOps.div(item.get("baseline_bytes_per_s"), 1e3), ".0f") + " → "
                + PyOps.fmt(PyOps.div(item.get("drop_bytes_per_s"), 1e3), ".0f") + " kB/s (−" + PyOps.fmt(item.get("drop_percent"), ".0f")
                + "%, from ACK progression) with no loss, no RTT increase and no "
                + "receiver-window limit (network and receiver not limiting).";
        return new Object[] {item, finding("sender_rate_drop", "low", start, end, summary, item, refs, hop)};
    }

    static List<Map<String, Object>> streamReconnects(List<Map<String, Object>> connections, String point, Db db, String zone) {
        List<Map<String, Object>> findings = new ArrayList<>();
        if (connections.isEmpty()) {
            return findings;
        }
        Object largest = null;
        for (Map<String, Object> c : connections) {
            Object v = PyOps.max(c.get("bytes_client_to_server"), c.get("bytes_server_to_client"));
            largest = largest == null ? v : PyOps.max(largest, v);
        }
        for (Map<String, Object> c : connections) {
            Object volume = PyOps.max(c.get("bytes_client_to_server"), c.get("bytes_server_to_client"));
            if (PyOps.lt(volume, PyOps.mul(BULK_SHARE, largest)) || c.get("closed") == null || c.get("reconnect_after_s") == null) {
                continue;
            }
            String closeKind = (String) c.get("close_kind");
            boolean notable = PyOps.numEq(volume, largest) || (closeKind != null && closeKind.startsWith("rst"));
            if (!notable || PyOps.lt(c.get("reconnect_after_s"), RECONNECT_NOTABLE_S)) {
                continue;
            }
            String how = PyOps.truthy(c.get("tls_close_like")) ? "TLS-close-like final record + FIN" : "fin".equals(closeKind) ? "FIN" : "RST";
            String summary = "The " + PyOps.str(c.get("closed_by")) + " closed the " + (PyOps.numEq(volume, largest) ? "main" : "bulk")
                    + " stream " + PyOps.str(c.get("server")) + " ⇄ " + PyOps.str(c.get("client")) + " at " + PyOps.str(c.get("closed_label"))
                    + " (" + how + ") and the client reconnected after " + PyOps.fmt(c.get("reconnect_after_s"), ".1f") + " s.";
            List<Map<String, Object>> refs = Evidence.evidence(db, "o.point=? AND o.frame=?", Arrays.asList(point, c.get("close_frame")), 4);
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (String k : List.of("closed_by", "close_kind", "tls_close_like", "reconnect_after_s", "server", "client", "closed_label",
                    "closed_short")) {
                metrics.put(k, c.get(k));
            }
            findings.add(finding("stream_reconnect", "low", c.get("closed"), PyOps.add(c.get("closed"), c.get("reconnect_after_s")),
                    summary, metrics, refs, "observed:" + point + ":" + point));
        }
        return findings;
    }

    /** {@code fold_storm_resets}: unattributed reset-origin findings a storm explains move under the storm. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> foldStormResets(List<Map<String, Object>> findings, List<Map<String, Object>> storms) {
        List<Object> times = new ArrayList<>();
        for (Map<String, Object> s : storms) {
            times.addAll((List<Object>) PyOps.get(s, "rst_times", new ArrayList<>()));
        }
        if (times.isEmpty()) {
            return findings;
        }
        times.sort(PyOps::compare);
        List<Map<String, Object>> kept = new ArrayList<>();
        long folded = 0;
        for (Map<String, Object> f : findings) {
            List<Object> range = (List<Object>) f.get("time_range");
            Object start = range == null || range.isEmpty() ? null : range.get(0);
            boolean explained = start != null && times.stream().anyMatch(t -> PyOps.le(PyOps.abs(PyOps.sub(start, t)), 0.01));
            if ("reset_origin".equals(f.get("type")) && "unknown".equals(f.get("confidence")) && explained) {
                folded++;
                continue;
            }
            kept.add(f);
        }
        for (Map<String, Object> f : kept) {
            if (List.of("reset_storm", "late_close_resets").contains(f.get("type"))) {
                PyOps.map(f.get("metrics")).put("unattributed_reset_origin_folded", folded);
            }
        }
        return kept;
    }

    @SuppressWarnings("unchecked")
    static void sessionReport(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        Topology topology = ctx.topology;
        List<Map<String, Object>> sessionFindings;
        if (topology.forward.isEmpty()) {
            te.sessionReport = dict("status", "unknown", "reason", "No capture point");
            sessionFindings = new ArrayList<>();
        } else {
            String point = topology.forward.get(0);
            String zone = topology.reportTimezone;
            db.exec(Sql.get("session_events", "sess_obs"), point);
            db.exec(Sql.get("session_events", "sess_flows"));
            List<Map<String, Object>> connections = connectionLifecycle(db, zone);
            Object[] st = resetStorms(db, point, zone);
            List<Map<String, Object>> storms = (List<Map<String, Object>>) st[0];
            Object[] dr = rateDrops(db, point, zone);
            List<Map<String, Object>> drops = (List<Map<String, Object>>) dr[0];
            List<Map<String, Object>> reconnects = streamReconnects(connections, point, db, zone);
            long client = 0;
            long server = 0;
            Map<String, Object> kinds = new LinkedHashMap<>();
            for (String k : List.of("fin", "rst_abort", "rst_no_socket")) {
                kinds.put(k, 0L);
            }
            for (Map<String, Object> c : connections) {
                if (!PyOps.truthy(c.get("closed_by"))) {
                    continue;
                }
                client += "client".equals(c.get("closed_by")) ? 1 : 0;
                server += "server".equals(c.get("closed_by")) ? 1 : 0;
                if (kinds.containsKey(c.get("close_kind"))) {
                    kinds.put((String) c.get("close_kind"), (Long) kinds.get(c.get("close_kind")) + 1);
                }
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("observation_point", point);
            report.put("note", NOTE);
            report.put("connections", new ArrayList<>(connections.subList(0, Math.min(500, connections.size()))));
            report.put("connection_count", (long) connections.size());
            report.put("closed_by", dict("client", client, "server", server));
            report.put("close_kinds", kinds);
            report.put("storms", storms);
            report.put("rate_drops", drops);
            sessionFindings = new ArrayList<>((List<Map<String, Object>>) st[1]);
            sessionFindings.addAll(reconnects);
            sessionFindings.addAll((List<Map<String, Object>>) dr[1]);
            report.put("summary", sessionSummary(storms, reconnects, drops));
            te.sessionReport = report;
        }
        List<Map<String, Object>> high = new ArrayList<>();
        for (Map<String, Object> f : sessionFindings) {
            if ("high".equals(f.get("severity"))) {
                high.add(f);
            }
        }
        ctx.findings.addAll(0, high);
        for (Map<String, Object> f : sessionFindings) {
            if (!"high".equals(f.get("severity"))) {
                ctx.findings.add(f);
            }
        }
        List<Map<String, Object>> folded = foldStormResets(ctx.findings,
                (List<Map<String, Object>>) PyOps.get(te.sessionReport, "storms", new ArrayList<>()));
        if (folded != ctx.findings) {
            ctx.findings.clear();
            ctx.findings.addAll(folded);
        }
    }

    static String sessionSummary(List<Map<String, Object>> storms, List<Map<String, Object>> reconnects, List<Map<String, Object>> drops) {
        List<String> parts = new ArrayList<>();
        List<Map<String, Object>> loss = new ArrayList<>();
        List<Map<String, Object>> late = new ArrayList<>();
        for (Map<String, Object> s : storms) {
            if ("state_loss".equals(s.get("kind"))) {
                loss.add(s);
            }
            if ("late_close".equals(s.get("kind"))) {
                late.add(s);
            }
        }
        if (!loss.isEmpty()) {
            List<String> times = new ArrayList<>();
            for (Map<String, Object> s : loss) {
                times.add(s.get("short") + " (" + PyOps.str(s.get("connections")) + ")");
            }
            parts.add(loss.get(0).get("side") + "-side state loss (no-socket RST storms) at " + String.join(", ", times));
        }
        for (Map<String, Object> f : reconnects) {
            Map<String, Object> m = PyOps.map(f.get("metrics"));
            parts.add(PyOps.str(m.get("closed_by")) + " closed a bulk stream at " + PyOps.str(m.get("closed_short"))
                    + " and the client reconnected after " + PyOps.fmt(m.get("reconnect_after_s"), ".1f") + " s");
        }
        for (Map<String, Object> d : drops) {
            if ("sender".equals(d.get("cause"))) {
                parts.add(d.get("sender") + " reduced its sending rate " + d.get("label") + " (−" + PyOps.fmt(d.get("drop_percent"), ".0f") + "%)");
            }
        }
        if (!late.isEmpty()) {
            String other = "server".equals(late.get(0).get("side")) ? "client" : "server";
            List<String> times = new ArrayList<>();
            for (Map<String, Object> s : late) {
                times.add(s.get("short") + " (" + PyOps.str(s.get("connections")) + ")");
            }
            parts.add(other + " closed " + late.get(0).get("side") + "-closed connections late at " + String.join(", ", times)
                    + " (expected RSTs)");
        }
        return String.join("; ", parts);
    }
}
