package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reference {@code device_drops.py}: positive vendor-stage evidence, persisted for all frames with bounded refs. */
final class DeviceDrops {
    static final String KIND = "confirmed_device_drop";

    private DeviceDrops() {
    }

    /** {@code prepare_proofs}. */
    @SuppressWarnings("unchecked")
    static void prepareProofs(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        db.exec(Sql.get("device_drops", "device_proofs"));
        ctx.hasDeviceProofs = false;
        boolean needed = topology.points.stream().anyMatch(p -> (p.vendor().equals("checkpoint") && "i".equals(p.vendorStage()))
                || (p.vendor().equals("paloalto") && "drop".equals(p.vendorStage())));
        if (!needed) {
            return;
        }
        db.exec(Sql.get("device_drops", "vendor_points"));
        Set<String> used = new LinkedHashSet<>(topology.forward);
        used.addAll(topology.reverse);
        List<Object[]> vp = new ArrayList<>();
        for (Topology.Point p : topology.points) {
            vp.add(new Object[] {p.id(), p.device(), p.vendorStage(), used.contains(p.id())});
        }
        db.execMany(Sql.get("device_drops", "vendor_points_insert"), vp);
        for (Topology.Point p : topology.points) {
            if (p.vendor().equals("checkpoint") && "i".equals(p.vendorStage()) && used.contains(p.id())) {
                List<Topology.Point> downstream = new ArrayList<>();
                for (Topology.Point b : topology.points) {
                    if (b.vendor().equals("checkpoint") && b.device().equals(p.device()) && b.captureId().equals(p.captureId())
                            && used.contains(b.id()) && !"i".equals(b.vendorStage())) {
                        downstream.add(b);
                    }
                }
                Map<String, Object> inv = (Map<String, Object>) Evidence.loads((String) db.scalar(Sql.get("device_drops", "inventory"),
                        p.captureId()));
                Set<String> stages = new LinkedHashSet<>();
                for (Topology.Point b : downstream) {
                    stages.add(b.vendorStage());
                }
                boolean complete = topology.points.stream().noneMatch(x -> x.device().equals(p.device()) && x.translation().equals("full_proxy"))
                        && p.inspectionComplete() && stages.contains("I") && stages.contains("o")
                        && !(PyOps.truthy(inv.get("damaged_tail")) || PyOps.truthy(inv.get("zero_tail_bytes"))
                                || PyOps.truthy(inv.get("timestamp_excluded_counts")) || PyOps.truthy(inv.get("ifdrop"))
                                || PyOps.truthy(inv.get("osdrop")));
                Set<String> nodeIds = new LinkedHashSet<>();
                for (Topology.Point x : topology.points) {
                    if (x.device().equals(p.device())) {
                        nodeIds.add(x.id());
                    }
                }
                boolean hasNat = topology.points.stream().anyMatch(x -> x.translation().equals("nat") && nodeIds.contains(x.id()));
                TreeSet<String> confirmed = new TreeSet<>(PyOps::compareStr);
                for (Topology.NatMapping m : topology.natMappings) {
                    if (nodeIds.contains(m.pointA()) && nodeIds.contains(m.pointB())) {
                        for (String key : List.of(m.tupleA(), m.tupleB())) {
                            confirmed.add(key);
                            List<Object> parts = (List<Object>) Evidence.loads(key);
                            confirmed.add(PyJsonWriter.compact(Arrays.asList(parts.get(0), parts.get(3), parts.get(4), parts.get(1),
                                    parts.get(2))));
                        }
                    }
                }
                List<String> downIds = new ArrayList<>();
                for (Topology.Point b : downstream) {
                    downIds.add(b.id());
                }
                db.exec(Sql.get("device_drops", "inspection_candidates"), downIds, hasNat, new ArrayList<>(confirmed), p.id(), downIds);
                db.exec(Sql.get("device_drops", "inspection_proofs"), p.device(), complete, PyOps.div(TimeEvents.of(ctx).matchWindowMs, 1000L),
                        inv.get("end"));
            } else if (p.vendor().equals("paloalto") && "drop".equals(p.vendorStage())) {
                db.exec(Sql.get("device_drops", "paloalto_proofs"), p.device(), p.id());
            }
        }
        db.exec(Sql.get("device_drops", "associate_drop_files"));
        db.exec(Sql.get("device_drops", "window_trim"), ctx.start, ctx.start, ctx.end, ctx.end);
        ctx.hasDeviceProofs = db.count(Sql.get("device_drops", "proof_count")) > 0;
    }

    /** {@code upgrade_events}: a unique positive device proof upgrades a disappearance to a confirmed drop. */
    static void upgradeEvents(Db db, String a, String b, String direction, String device) {
        db.exec(Sql.get("device_drops", "device_assignments"), a, b, direction, device);
        db.exec(Sql.get("device_drops", "upgrade_events"));
        db.exec(Sql.get("device_drops", "mark_proofs"));
    }

    /** {@code finding_context}. */
    static Map<String, Object> findingContext(Db db, String a, String b, String direction) {
        TreeSet<String> devices = new TreeSet<>(PyOps::compareStr);
        TreeSet<String> stages = new TreeSet<>(PyOps::compareStr);
        TreeSet<String> sources = new TreeSet<>(PyOps::compareStr);
        for (Row f : db.rows(Sql.get("device_drops", "finding_context"), a, b, direction)) {
            devices.add(f.str("device"));
            stages.add(f.str("stage"));
            sources.add(f.str("time_source"));
        }
        return dict("device", String.join(", ", devices), "evidence_stage", String.join(", ", stages), "evidence_time_source",
                String.join(", ", sources));
    }

    /** {@code add_unplaced}: proofs without a placed event become device-stage segments and findings. */
    static void addUnplaced(EngineContext ctx) {
        if (!ctx.hasDeviceProofs) {
            return;
        }
        Db db = ctx.db;
        db.exec(Sql.get("device_drops", "unplaced_events"));
        db.exec(Sql.get("device_drops", "unplaced_mark"));
        Map<String, Topology.Point> points = ctx.topology.pointMap();
        for (Row g : db.rows(Sql.get("device_drops", "unplaced_groups"))) {
            Topology.Point p = points.get(g.str("point_a"));
            String sid = g.get("direction") + ":" + p.id() + ":" + p.id();
            Map<String, Object> context = findingContext(db, p.id(), p.id(), g.str("direction"));
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("id", sid);
            segment.put("point_a", p.id());
            segment.put("point_b", p.id());
            segment.put("device", p.device());
            segment.put("label", p.device() + " · " + PyOps.str(p.vendorStage()));
            segment.put("direction", g.get("direction"));
            segment.put("location", "device_stage");
            segment.put("eligible_ratio", null);
            segment.put("excluded_counts", new LinkedHashMap<>());
            segment.put("endpoint_quality", new LinkedHashMap<>());
            segment.put("eligible_packets", 0L);
            segment.put("classes", dict(KIND, g.get("count")));
            segment.put("loss_percent", null);
            segment.put("matched", 0L);
            segment.put("min_ms", null);
            segment.put("p50_ms", null);
            segment.put("p95_ms", null);
            segment.put("max_ms", null);
            segment.put("latency_confidence", "unknown");
            segment.put("offset_uncertainty_ms", null);
            segment.put("reason", "Positive device-stage evidence; no comparable upstream loss-rate denominator");
            ctx.segments.add(segment);
            Object[] frame = db.one(Sql.get("device_drops", "unplaced_frame"), p.id(), p.id(), g.get("direction"));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", sid + ":" + KIND);
            f.put("type", KIND);
            f.put("severity", "high");
            f.put("hop", sid);
            f.put("direction", g.get("direction"));
            f.put("time_range", PyOps.list(g.get("first_seen"), g.get("last_seen")));
            f.put("metrics", g);
            f.put("confidence", "supported");
            f.put("cause", "device_stage_evidence");
            f.put("summary", PyOps.str(g.get("count")) + " confirmed device drop observations at " + p.device() + "; evidence stage "
                    + context.get("evidence_stage") + ".");
            f.put("evidence", Evidence.evidence(db, "o.point=? AND o.frame=?", Arrays.asList(frame), 1));
            f.put("evidence_note", "First positive stage observation; browse device-stage events for all frames");
            f.putAll(context);
            ctx.findings.add(f);
        }
    }

    /** {@code device_report}: the four report keys. */
    static void deviceReport(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        Db db = ctx.db;
        List<Row> sample = db.rows(Sql.get("device_drops", "sample"));
        if (!sample.isEmpty()) {
            List<String> selected = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (Row p : sample) {
                selected.add("(o.point=? AND o.frame=?)");
                values.add(p.get("point"));
                values.add(p.get("frame"));
            }
            List<Map<String, Object>> refs = Evidence.evidence(db, String.join(" OR ", selected), values, sample.size(), false);
            Map<List<Object>, Map<String, Object>> lookup = new LinkedHashMap<>();
            for (Map<String, Object> r : refs) {
                lookup.put(Arrays.asList(r.get("point"), r.get("frame")), r);
            }
            for (Row p : sample) {
                Map<String, Object> ref = lookup.get(Arrays.asList(p.get("point"), p.get("frame")));
                p.put("evidence", ref != null ? new ArrayList<>(List.of(ref)) : new ArrayList<>());
            }
        }
        List<Row> grouped = db.rows(Sql.get("device_drops", "grouped"));
        List<Object> counts = new ArrayList<>();
        for (Row g : grouped) {
            counts.add(g.get("count"));
        }
        te.deviceReport = dict("vendor_device_events", sample, "vendor_device_event_count", db.count(Sql.get("device_drops", "proof_count")),
                "confirmed_device_drops", grouped, "confirmed_device_drop_count", PyOps.sum(counts));
    }
}
