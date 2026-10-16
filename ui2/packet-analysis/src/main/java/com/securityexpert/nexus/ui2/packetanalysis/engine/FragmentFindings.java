package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Reference {@code fragments.fragment_findings}: first observed fragmentation boundary and per-point quality. */
final class FragmentFindings {
    private FragmentFindings() {
    }

    static void run(EngineContext ctx) {
        Db db = ctx.db;
        Map<String, Point> pointmap = ctx.topology.pointMap();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> seg : ctx.segments) {
            if (seg.get("loss_percent") == null) {
                continue;
            }
            String[] id = ((String) seg.get("id")).split(":", -1);
            if (id.length != 3) {
                throw new IllegalArgumentException("too many values to unpack");
            }
            String direction = id[0];
            String a = id[1];
            String b = id[2];
            List<Row> data = db.rows(Sql.get("fragments", "fragment_findings"), ctx.topology.matchWindowMs / 1000, a, b, direction);
            Point pa = pointmap.get(a);
            Point pb = pointmap.get(b);
            String device = pa.device().equals(pb.device()) ? pa.device() : null;
            String location = Evidence.truthy(device) ? device : "link between " + pa.label() + " and " + pb.label();
            for (Row r : data) {
                List<Map<String, Object>> refs = Evidence.evidence(db, "(o.point=? AND o.frame=?) OR (o.point=? AND o.frame=?)",
                        List.of(a, r.get("before"), b, r.get("after")), 128);
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("datagrams", 1L);
                metrics.put("fragments", r.get("fragments"));
                metrics.put("fragment_id", r.get("fragment_id"));
                metrics.put("reassembly", r.get("status"));
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", "fragment:" + seg.get("id") + ":" + r.get("after"));
                f.put("type", "fragmentation");
                f.put("hop", seg.get("id"));
                f.put("device", device);
                f.put("direction", direction);
                f.put("flow", r.get("flow"));
                f.put("time_range", new ArrayList<>(java.util.Arrays.asList(r.get("corrected"), r.get("corrected"))));
                f.put("metrics", metrics);
                f.put("evidence", refs);
                f.put("confidence", "supported");
                f.put("severity", "quality");
                f.put("cause", "observed_transformation");
                f.put("summary", "Fragmented by " + location + " (first observed here)");
                f.put("tooltip", "One uniquely identified datagram is unfragmented upstream and has fragment headers downstream; incomplete reassembly is reported separately. The first observed boundary is supported; an unseen internal device is not identified.");
                findings.add(f);
            }
        }
        List<Row> quality = db.rows(Sql.get("fragments", "fragment_quality"));
        ctx.path.fragmentFindings = findings;
        ctx.path.fragmentQuality = quality;
        Parity.record(ctx, "fragment_findings", Parity.pair(findings, quality));
        ctx.findings.addAll(findings);
    }
}
