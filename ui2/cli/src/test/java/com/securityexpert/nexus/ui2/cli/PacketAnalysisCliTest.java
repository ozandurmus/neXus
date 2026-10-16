package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** CLI parity for the Packet Analysis screen: the same routes, cookie and CSRF token; uploads streamed from disk. */
class PacketAnalysisCliTest {
    private static final String CASE = "11111111-2222-4333-8444-555555555555";

    record Seen(String method, String path, String csrf, String contentType, byte[] body) {
    }

    @Test
    void everyScreenActionHasACommandOnTheSameRoute(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token"), exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestBody().readAllBytes()));
            byte[] out = "{}".getBytes();
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            Path capture = home.resolve("synthetic.pcap");
            byte[] bytes = new byte[5000];
            bytes[0] = (byte) 0xd4;
            Files.write(capture, bytes);

            assertEquals(0, cli.run(new String[] {"pa-cases"}));
            assertEquals(0, cli.run(new String[] {"pa-case-create", "Synthetic case", "--payload-zeroing"}));
            assertEquals(0, cli.run(new String[] {"pa-upload", CASE, capture.toString()}));
            assertEquals(0, cli.run(new String[] {"pa-analyze", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-case", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-state", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-flows", CASE, "offset=0", "limit=50"}));
            assertEquals(0, cli.run(new String[] {"pa-frames", CASE, "point=p1"}));
            assertEquals(0, cli.run(new String[] {"pa-diagnosis", CASE, "flow=f1"}));
            assertEquals(0, cli.run(new String[] {"pa-charts", CASE, "flow=f1"}));
            assertEquals(0, cli.run(new String[] {"pa-ladder", CASE, "flow=f1"}));
            assertEquals(0, cli.run(new String[] {"pa-waterfall", CASE, "flow=f1"}));
            assertEquals(0, cli.run(new String[] {"pa-timeseries", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-topology-overlay", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-topology-inspect", CASE, "point=p1"}));
            assertEquals(0, cli.run(new String[] {"pa-findings", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-events", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-dns", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-correlation", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-field-diff", CASE, "packet_key=k1"}));
            assertEquals(0, cli.run(new String[] {"pa-export-findings", CASE}));
            Path reference = home.resolve("reference.json");
            Files.writeString(reference, "{\"items\":[]}");
            Path previous = home.resolve("previous.json");
            Files.writeString(previous, "{\"schema\":\"previous\"}");
            Path exported = home.resolve("report.html");
            assertEquals(0, cli.run(new String[] {"pa-session-evidence", CASE, "point=p1", "start=1700000000", "end=1700000001"}));
            assertEquals(0, cli.run(new String[] {"pa-reference-template", CASE}));
            assertEquals(0, cli.run(new String[] {"pa-compare", CASE, "--reference", reference.toString(), "--against", previous.toString()}));
            assertEquals(0, cli.run(new String[] {"pa-export", CASE, "html", exported.toString()}));
            assertEquals("{}", Files.readString(exported));
            Path workbook = home.resolve("expert.xlsx");
            assertEquals(0, cli.run(new String[] {"pa-expert-report", CASE, workbook.toString()}));
            assertEquals("{}", Files.readString(workbook));
            assertEquals(0, cli.run(new String[] {"pa-expert-report", CASE, workbook.toString(), "start=1700000012.5", "end=1700000030", "top=10"}));
            assertEquals(0, cli.run(new String[] {"pa-topology-save", CASE, "{\"points\":[]}"}));
            assertEquals(0, cli.run(new String[] {"pa-delete", CASE}));

            assertEquals(List.of(
                    "GET /api/v2/packet-analysis/cases",
                    "POST /api/v2/packet-analysis/cases",
                    "POST /api/v2/packet-analysis/cases/" + CASE + "/captures",
                    "POST /api/v2/packet-analysis/cases/" + CASE + "/analyze",
                    "GET /api/v2/packet-analysis/cases/" + CASE,
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/state",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/flows?offset=0&limit=50",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/frames?point=p1",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/diagnosis?flow=f1",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/charts?flow=f1",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/ladder?flow=f1",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/waterfall?flow=f1",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/timeseries",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/topology/overlay",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/topology/inspect?point=p1",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/findings",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/events",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/dns",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/correlation",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/field-diff?packet_key=k1",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/export/findings",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/sessions/evidence?point=p1&start=1700000000&end=1700000001",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/reference/template",
                    "POST /api/v2/packet-analysis/cases/" + CASE + "/compare",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/export/download?format=html",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/export/expert-report",
                    "GET /api/v2/packet-analysis/cases/" + CASE + "/export/expert-report?start=1700000012.5&end=1700000030&top=10",
                    "PUT /api/v2/packet-analysis/cases/" + CASE + "/topology",
                    "DELETE /api/v2/packet-analysis/cases/" + CASE),
                    seen.stream().map(s -> s.method() + " " + s.path()).toList());
            assertTrue(new String(seen.get(1).body()).contains("\"payload_zeroing\":true"));
            Seen compare = seen.stream().filter(s -> s.path().endsWith("/compare")).findFirst().orElseThrow();
            assertEquals("csrf-9", compare.csrf());
            assertEquals("{\"reference\":{\"items\":[]},\"against\":{\"schema\":\"previous\"}}", new String(compare.body()));
            Seen upload = seen.get(2);
            assertEquals("csrf-9", upload.csrf());
            assertTrue(upload.contentType().startsWith("multipart/form-data; boundary=nexus-cli-"));
            String head = new String(upload.body(), 0, 200, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(head.contains("name=\"file\"; filename=\"synthetic.pcap\""), head);
            assertTrue(upload.body().length > bytes.length, "the whole file was sent inside the multipart body");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hostTelemetryCommandsUseTheTelemetryRoutesAndSetTheAlignmentAfterTheUpload(@TempDir Path home) throws Exception {
        String telemetryId = "99999999-8888-4777-8666-555555555555";
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token"), exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestBody().readAllBytes()));
            byte[] out = ("{\"telemetry_id\":\"" + telemetryId + "\"}").getBytes();
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            Path file = home.resolve("srv-vmstat.txt");
            Files.writeString(file, "r b swpd free\n");
            assertEquals(0, cli.run(new String[] {"pa-telemetry-upload", CASE, file.toString()}));
            assertEquals(0, cli.run(new String[] {"pa-telemetry-upload", CASE, file.toString(), "zone=Europe/Istanbul", "offset=-3",
                "start=2026-10-08 13:00:00"}));
            assertEquals(0, cli.run(new String[] {"pa-telemetry-align", telemetryId, "zone=UTC"}));
            assertEquals(0, cli.run(new String[] {"pa-telemetry-align", telemetryId}));
            assertEquals(0, cli.run(new String[] {"pa-telemetry-delete", telemetryId}));
            assertEquals(List.of(
                    "POST /api/v2/packet-analysis/cases/" + CASE + "/telemetry",
                    "POST /api/v2/packet-analysis/cases/" + CASE + "/telemetry",
                    "PUT /api/v2/packet-analysis/telemetry/" + telemetryId,
                    "PUT /api/v2/packet-analysis/telemetry/" + telemetryId,
                    "PUT /api/v2/packet-analysis/telemetry/" + telemetryId,
                    "DELETE /api/v2/packet-analysis/telemetry/" + telemetryId),
                    seen.stream().map(s -> s.method() + " " + s.path()).toList());
            assertTrue(seen.get(0).contentType().startsWith("multipart/form-data; boundary=nexus-cli-"));
            assertEquals("csrf-9", seen.get(0).csrf());
            assertEquals("{\"time_zone\":\"Europe/Istanbul\",\"offset_seconds\":-3,\"anchor_local\":\"2026-10-08 13:00:00\"}",
                    new String(seen.get(2).body()));
            assertEquals("{\"time_zone\":\"UTC\"}", new String(seen.get(3).body()));
            assertEquals("{}", new String(seen.get(4).body()), "no keys clears the alignment back to 'not confirmed'");
        } finally {
            server.stop(0);
        }
        NexusSessionCli offline = new NexusSessionCli(home);
        offline.saveSession(new NexusSessionCli.Session("http://127.0.0.1:9", "c", "t"));
        Path file = home.resolve("srv-vmstat.txt");
        assertEquals(2, offline.run(new String[] {"pa-telemetry-upload", "../cases", file.toString()}));
        assertEquals(2, offline.run(new String[] {"pa-telemetry-upload", CASE}));
        assertEquals(2, offline.run(new String[] {"pa-telemetry-upload", CASE, file.toString(), "tz=UTC"}));
        assertEquals(2, offline.run(new String[] {"pa-telemetry-upload", CASE, file.toString(), "offset=soon"}));
        assertEquals(2, offline.run(new String[] {"pa-telemetry-upload", CASE, home.resolve("missing.txt").toString()}));
        assertEquals(2, offline.run(new String[] {"pa-telemetry-align", "../x"}));
        assertEquals(2, offline.run(new String[] {"pa-telemetry-align", telemetryIdForOffline(), "zone"}));
        assertEquals(2, offline.run(new String[] {"pa-telemetry-delete", "not-an-id"}));
    }

    private static String telemetryIdForOffline() {
        return "99999999-8888-4777-8666-555555555555";
    }

    @Test
    void identifiersMustBeOpaqueBeforeAnyCall(@TempDir Path home) throws Exception {
        NexusSessionCli cli = new NexusSessionCli(home);
        cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:9", "c", "t"));
        assertEquals(2, cli.run(new String[] {"pa-analyze", "../cases"}));
        assertEquals(2, cli.run(new String[] {"pa-flows", "../cases"}));
        assertEquals(2, cli.run(new String[] {"pa-correlation", "../cases"}));
        assertEquals(2, cli.run(new String[] {"pa-compare", "../cases", "--reference", home.resolve("x.json").toString()}));
        assertEquals(2, cli.run(new String[] {"pa-export", CASE, "pdf", home.resolve("out").toString()}));
        assertEquals(2, cli.run(new String[] {"pa-expert-report", "../cases", home.resolve("out").toString()}));
        assertEquals(2, cli.run(new String[] {"pa-expert-report", CASE}));
        assertEquals(2, cli.run(new String[] {"pa-expert-report", CASE, home.resolve("out").toString(), "window=1"}));
        assertEquals(2, cli.run(new String[] {"pa-expert-report", CASE, home.resolve("out").toString(), "start=soon"}));
        assertEquals(2, cli.run(new String[] {"pa-expert-report", CASE, home.resolve("out").toString(), "start"}));
        assertEquals(2, cli.run(new String[] {"pa-compare", CASE, "--reference", home.resolve("missing.json").toString()}));
        assertEquals(2, cli.run(new String[] {"pa-download", "x", home.resolve("out").toString()}));
    }
}
