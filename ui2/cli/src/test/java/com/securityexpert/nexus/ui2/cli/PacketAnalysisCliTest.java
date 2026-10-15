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
            assertEquals(0, cli.run(new String[] {"pa-delete", CASE}));

            assertEquals(List.of("GET /api/v2/packet-analysis/cases", "POST /api/v2/packet-analysis/cases",
                    "POST /api/v2/packet-analysis/cases/" + CASE + "/captures", "POST /api/v2/packet-analysis/cases/" + CASE + "/analyze",
                    "GET /api/v2/packet-analysis/cases/" + CASE, "DELETE /api/v2/packet-analysis/cases/" + CASE),
                    seen.stream().map(s -> s.method() + " " + s.path()).toList());
            assertTrue(new String(seen.get(1).body()).contains("\"payload_zeroing\":true"));
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
    void identifiersMustBeOpaqueBeforeAnyCall(@TempDir Path home) throws Exception {
        NexusSessionCli cli = new NexusSessionCli(home);
        cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:9", "c", "t"));
        assertEquals(2, cli.run(new String[] {"pa-analyze", "../cases"}));
        assertEquals(2, cli.run(new String[] {"pa-download", "x", home.resolve("out").toString()}));
    }
}
