package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reference {@code udp.py}: one-to-one UDP transactions scoped to a point and the reversed observed tuple. */
public final class UdpTransactions {
    static final String NOTE = "Known transaction IDs come from tshark. Unknown protocols pair forward initiators with "
            + "reversed-tuple packets in a bounded window, never nearest-time guesses. Timing is same-point; unanswered "
            + "traffic is not proof of an endpoint or device cause.";

    private UdpTransactions() {
    }

    static void stage(EngineContext ctx) {
        ctx.sections.put("udp", build(ctx.db, ctx.topology, ctx.segments));
    }

    /** {@code build_udp(db, topology, segments)}. */
    public static Map<String, Object> build(Db db, Topology topology, List<Map<String, Object>> segments) {
        double timeout = topology.udpTimeoutMs.doubleValue() / 1000;
        db.exec(Sql.get("udp", "udp_protocol"));
        Map<String, Object> result = new LinkedHashMap<>();
        if (db.one(Sql.get("udp", "any_protocol")) == null) {
            db.exec(Sql.get("udp", "drop_transactions"));
            result.put("summary", new ArrayList<>());
            result.put("items", new ArrayList<>());
            result.put("total", 0L);
            result.put("timeout_ms", topology.udpTimeoutMs);
            result.put("note", NOTE);
            result.put("scope", "Query initiations in selected window");
            return result;
        }
        db.exec(Sql.get("udp", "udp_candidates"), timeout);
        db.exec(Sql.get("udp", "udp_transactions"), timeout, timeout, topology.start, topology.start, topology.end,
                topology.end);
        db.exec(Sql.get("udp", "udp_supported"));
        List<Object[]> supported = new ArrayList<>();
        for (Map<String, Object> s : segments) {
            if (s.get("loss_percent") != null) {
                supported.add(new Object[] {s.get("id")});
            }
        }
        db.execMany(Sql.get("udp", "udp_supported_insert"), supported);
        db.exec(Sql.get("udp", "udp_stops"));
        db.exec(Sql.get("udp", "udp_far"));
        db.exec(Sql.get("udp", "udp_hops"));
        List<Row> summary = db.rows(Sql.get("udp", "summary"));
        List<Row> items = db.rows(Sql.get("udp", "items"));
        List<List<Object>> pairs = new ArrayList<>();
        for (Row i : items) {
            pairs.add(Arrays.asList(i.get("point"), i.get("frame")));
            pairs.add(Arrays.asList(i.get("point"), i.get("response_frame")));
        }
        Map<List<Object>, Map<String, Object>> refsByFrame = UdpIndex.frameEvidence(db, pairs);
        for (Row item : items) {
            String status = item.str("status");
            if (status.equals("answered")) {
                item.put("reason", "Unique reversed-tuple and decoded identity response");
            } else if (status.equals("no_response_expected")) {
                item.put("reason", "Known one-way traffic; no reply is expected");
            } else if (status.equals("unanswered")) {
                item.put("reason", "No unique response before the covered deadline; endpoint/device cause unknown");
            } else {
                Object decodeReason = item.get("decode_reason");
                item.put("reason", Evidence.truthy(decodeReason) ? decodeReason
                        : Evidence.truthy(item.get("incomplete_response")) || Evidence.truthy(item.get("capture_drops"))
                                ? "Capture drops or incomplete candidate response; absence cannot establish timeout"
                                : "Ambiguous ownership, unsupported identity, incomplete packet or insufficient deadline coverage");
            }
            item.put("tooltip", NOTE);
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Object f : Arrays.asList(item.get("frame"), item.get("response_frame"))) {
                Map<String, Object> ref = refsByFrame.get(Arrays.asList(item.get("point"), f));
                if (ref != null) {
                    evidence.add(ref);
                }
            }
            item.put("evidence", evidence);
            if (Evidence.truthy(item.get("hop"))) {
                evidence.addAll(UdpIndex.protocolEvidence(db, "o.packet_key IN (?,?)",
                        Arrays.asList(item.get("packet_key"), item.get("lost_response_key")), 16));
            }
        }
        result.put("summary", summary);
        result.put("items", items);
        result.put("total", db.count(Sql.get("udp", "total")));
        result.put("timeout_ms", topology.udpTimeoutMs);
        result.put("note", NOTE);
        result.put("scope", "Query initiations in the selected window; replies may extend to the covered deadline; "
                + "bounded examples, all transactions stored");
        return result;
    }
}
