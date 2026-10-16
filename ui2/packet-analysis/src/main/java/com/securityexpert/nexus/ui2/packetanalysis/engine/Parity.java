package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parity runs only: a detector's direct output (before later stages change it) is written as JSON text to
 * {@code parity."detectors"(stage, value)} so the harness compares it with the reference function's return value.
 */
final class Parity {
    private Parity() {
    }

    static void record(EngineContext ctx, String stage, Object value) {
        if (ctx.parity == null) {
            return;
        }
        ctx.db.exec("CREATE TABLE IF NOT EXISTS " + ctx.parity + ".detectors(stage VARCHAR,value VARCHAR)");
        ctx.db.exec("INSERT INTO " + ctx.parity + ".detectors VALUES (?,?)", stage, PyJsonWriter.dumps(Evidence.deepCopy(value), false));
    }

    static List<Object> pair(Object a, Object b) {
        List<Object> out = new ArrayList<>();
        out.add(a);
        out.add(b);
        return out;
    }

    static void recordDetB(Db db, Topology topology, java.util.Map<String, Object> report, java.nio.file.Path parityDb) {
        if (parityDb == null || !Boolean.getBoolean("packetanalysis.parity.detb")) {
            return;
        }
        java.util.Map<String, Object> transportExport = Transport.exportProfiles(db, topology, report, 200);
        Object proxyWaterfall = null;
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> proxies = (java.util.Map<String, Object>) report.getOrDefault("proxies", java.util.Map.of());
        if (Evidence.truthy(proxies.get("transactions"))
                && topology.points.stream().anyMatch(p -> "full_proxy".equals(p.translation()) && !"f5".equals(p.vendor()))) {
            Object[] row = db.one("SELECT flow FROM proxy_requests WHERE point='client' AND kind='http' ORDER BY start,last_frame,id LIMIT 1");
            if (row != null && row[0] != null) {
                proxyWaterfall = Proxies.waterfall(db, report, topology, (String) row[0], null, null, 0);
            }
        }
        db.exec("ATTACH '" + parityDb.toAbsolutePath().toString().replace("'", "''") + "' AS parity");
        try {
            EngineContext ctx = new EngineContext(db, topology, "parity");
            ctx.snapshot("transport", List.of(
                    "transport_tcp", "transport_handshake", "transport_acks", "transport_samples",
                    "transport_point_facts", "transport_edges", "transport_transit", "transport_path_facts",
                    "transport_rtt_facts", "transport_retry_signals", "transport_retries", "transport_reordering"));
            record(ctx, "transport_export", transportExport);
            if (proxyWaterfall != null) {
                record(ctx, "proxy_waterfall", proxyWaterfall);
            }
        } finally {
            db.exec("DETACH parity");
        }
    }
}
