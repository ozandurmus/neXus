package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.ReportModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parity entry point: {@code EngineCli --project project.duckdb --topology topology.json --report out.json
 * [--parity parity.duckdb] [--dns page.json [--dns-point POINT]] [--retransmissions pages.json]}. The project must
 * come from the reference ingest + topology, before analysis.
 */
public final class EngineCli {
    private EngineCli() {
    }

    public static void main(String[] args) throws Exception {
        Path project = null;
        Path topologyPath = null;
        Path reportPath = null;
        Path parity = null;
        Path dns = null;
        String dnsPoint = null;
        Path retransmissionsPath = null;
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--project" -> project = Path.of(args[i + 1]);
                case "--topology" -> topologyPath = Path.of(args[i + 1]);
                case "--report" -> reportPath = Path.of(args[i + 1]);
                case "--parity" -> parity = Path.of(args[i + 1]);
                case "--dns" -> dns = Path.of(args[i + 1]);
                case "--dns-point" -> dnsPoint = args[i + 1];
                case "--retransmissions" -> retransmissionsPath = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (project == null || topologyPath == null || reportPath == null) {
            throw new IllegalArgumentException("--project, --topology and --report are required");
        }
        Topology topology = Topology.read(topologyPath);
        try (Db db = new Db(Engine.open(project))) {
            Map<String, Object> report = Engine.analyze(db, topology, parity);
            Parity.recordDetB(db, topology, report, parity);
            String text = ReportModel.toJson(report);
            Files.writeString(reportPath, text, StandardCharsets.UTF_8);
            if (dns != null) {
                // dns.dns_page(project, point=...) on the analysed project (offset 0, limit 100).
                Map<String, Object> page = Dns.page(db, topology, report, dnsPoint, null, null, null, 0, 100);
                Files.writeString(dns, PyJsonWriter.dumps(page, true), StandardCharsets.UTF_8);
            }
            if (retransmissionsPath != null) {
                // transport.prepare + retransmissions.report, per flow and for all flows.
                Transport.prepare(db, topology, report);
                Map<String, Object> pages = new LinkedHashMap<>();
                pages.put("__all__", Transport.retryReport(db, topology, null));
                for (Db.Row r : db.rows("SELECT flow FROM flow_summary ORDER BY flow")) {
                    String flow = r.str("flow");
                    pages.put(flow, Transport.retryReport(db, topology, flow));
                }
                Files.writeString(retransmissionsPath, PyJsonWriter.dumps(pages, true), StandardCharsets.UTF_8);
            }
        }
    }
}
