package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reference {@code dns.py}: scoped DNS transactions ({@code prepare}, run on demand after an analysis and cached
 * under the {@code dns_generation} setting) and the DNS page ({@code dns_page}).
 */
public final class Dns {
    public static final String TOOLTIP = "Same-point query-to-response latency, scoped by TCP/UDP conversation, reversed "
            + "tuple, DNS ID, question name/type/class and a bounded deadline. A timeout is an observation, not proof of "
            + "resolver internals.";
    static final Map<String, String> REASONS = new LinkedHashMap<>();
    static final String NOTE = "One complete unicast DNS question/message per indexed frame. Repeated equally plausible "
            + "attempts are unknown; no nearest-time guess. Local latency is query-to-response turnaround, not isolated "
            + "resolver processing. Deadline coverage and hop clock uncertainty apply.";

    static {
        REASONS.put("answered", "Unique question/tuple/transaction response at this point before the configured deadline");
        REASONS.put("query_disappeared",
                "Query disappearance is independently classified on the monitored path; resolver failure is not established");
        REASONS.put("response_disappeared", "Resolver response is visible before its supported disappearance on the "
                + "return path; this is not a resolver timeout");
        REASONS.put("resolver_timeout", "Query reached the declared resolver-side capture, with coverage through its "
                + "deadline and no response observed there; internal cause unknown");
    }

    private Dns() {
    }

    /** The reference {@code OUTCOME} CASE expression. */
    public static String outcome() {
        return Sql.get("dns", "outcome");
    }

    private static String setting(Db db, String key) {
        Object[] row = db.one("SELECT value FROM settings WHERE key=?", key);
        return row == null ? null : (String) row[0];
    }

    /** {@code prepare(project, db, topology, report)}. */
    public static void prepare(Db db, Topology topology, Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) report.get("segments");
        String generation = PyJsonWriter.dumps(Arrays.asList(report.get("generated_at"), topology.dnsTimeoutMs.doubleValue(), 1L), false);
        String stored = setting(db, "dns_generation");
        if (stored != null && Objects.equals(Evidence.loads(stored), Evidence.loads(generation))) {
            return;
        }
        db.exec(Sql.get("dns", "dns_observations"));
        List<Object[]> unicast = new ArrayList<>();
        for (Object[] r : db.tuples(Sql.get("dns", "destinations"))) {
            unicast.add(new Object[] {r[0], !IpNet.isMulticast((String) r[0])});
        }
        db.exec(Sql.get("dns", "dns_unicast"));
        db.execMany(Sql.get("dns", "dns_unicast_insert"), unicast);
        db.exec(Sql.get("dns", "dns_queries"));
        double timeout = topology.dnsTimeoutMs.doubleValue() / 1000;
        db.exec(Sql.get("dns", "dns_candidates"), timeout);
        db.exec(Sql.get("dns", "dns_local_pairs"), timeout);
        db.exec(Sql.get("dns", "dns_supported_hops"));
        List<Object[]> hops = new ArrayList<>();
        for (Map<String, Object> s : segments) {
            if (s.get("loss_percent") != null) {
                hops.add(new Object[] {s.get("id")});
            }
        }
        db.execMany(Sql.get("dns", "dns_supported_insert"), hops);
        db.exec(Sql.get("dns", "dns_losses"));
        db.exec(Sql.get("dns", "dns_resolver_points"));
        List<Object[]> servers = new ArrayList<>();
        for (Point p : topology.points) {
            if (p.kind().equals("Server")) {
                servers.add(new Object[] {p.id()});
            }
        }
        db.execMany(Sql.get("dns", "dns_resolver_insert"), servers);
        db.exec(Sql.get("dns", "dns_far"));
        db.exec(Sql.get("dns", "dns_signals"));
        db.exec(Sql.fmt("dns", "dns_transactions", "outcome", outcome()));
        db.exec("INSERT OR REPLACE INTO settings VALUES (?, ?)", "dns_generation", generation);
    }

    /** {@code dns_page(project, point, resolver, start, end, offset, limit)} for an analysed project. */
    public static Map<String, Object> page(Db db, Topology topology, Map<String, Object> report, String point,
            String resolver, Double start, Double end, long offset, long limit) {
        prepare(db, topology, report);
        String where = Sql.get("dns", "where");
        Object[] params = {point, point, resolver, resolver, start, start, end, end};
        List<Row> summary = db.rows(Sql.fmt("dns", "summary", "where", where), params);
        Object[] dataParams = Arrays.copyOf(params, params.length + 2);
        dataParams[params.length] = limit;
        dataParams[params.length + 1] = offset;
        List<Row> data = db.rows(Sql.fmt("dns", "data", "where", where), dataParams);
        Map<String, Point> points = topology.pointMap();
        for (Row row : data) {
            String status = row.str("status");
            row.put("tooltip", TOOLTIP);
            String reason = REASONS.get(status);
            row.put("reason", reason != null ? reason : "Question/index unsupported, ambiguous response ownership, "
                    + "incomplete deadline coverage or insufficient path/endpoint evidence");
            row.put("hop", "query_disappeared".equals(status) ? row.get("query_hop")
                    : "response_disappeared".equals(status) ? row.get("response_hop") : null);
            row.put("device", null);
            String hop = (String) row.get("hop");
            if (Evidence.truthy(hop)) {
                String[] parts = hop.split(":", -1);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("not enough values to unpack");
                }
                Point a = points.get(parts[1]);
                Point b = points.get(parts[2]);
                String device = a.device().equals(b.device()) ? a.device() : null;
                row.put("device", device);
                row.put("location", Evidence.truthy(device) ? device : "link between " + a.label() + " and " + b.label());
            }
            List<Map<String, Object>> evidence = new ArrayList<>(Evidence.evidence(db, "o.point=? AND o.frame IN (?,?)",
                    Arrays.asList(row.get("point"), row.get("frame"), row.get("response_frame")), 2));
            row.put("evidence", evidence);
            if (Evidence.truthy(row.get("resolver_frame"))) {
                evidence.addAll(Evidence.evidence(db, "o.point=? AND o.frame=?",
                        Arrays.asList(row.get("resolver_point"), row.get("resolver_frame")), 1));
            }
            if (Evidence.truthy(hop)) {
                evidence.addAll(Evidence.evidence(db, "o.packet_key=?",
                        Arrays.asList("query_disappeared".equals(status) ? row.get("packet_key") : row.get("far_response_key")),
                        16));
            }
        }
        List<Object> pointList = new ArrayList<>();
        for (Point p : topology.points) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("point", p.id());
            m.put("label", p.label());
            m.put("kind", p.kind());
            pointList.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", data);
        result.put("total", db.count(Sql.fmt("dns", "total", "where", where), params));
        result.put("summary", summary);
        result.put("points", pointList);
        result.put("timeout_ms", topology.dnsTimeoutMs.doubleValue());
        result.put("tooltip", TOOLTIP);
        result.put("reason", null);
        result.put("note", NOTE);
        return result;
    }
}
