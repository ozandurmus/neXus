package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** ACI-16: every pre-cutover impact analysis action on the screen has a command on the same route. */
class CutoverCliTest {

    private static final String SEGMENT = "11111111-2222-4333-8444-555555555555";
    private static final String ASSESSMENT = "66666666-7777-4888-9999-aaaaaaaaaaaa";

    record Seen(String method, String path, String csrf, String contentType, String body) {
    }

    @Test
    void everyScreenActionHasACommandOnTheSameRoute(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("X-CSRF-Token"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            Path spec = home.resolve("spec.json");
            Files.writeString(spec, "{\"targetDeviceId\":\"d1\",\"mappings\":[{},{}]}");
            Path first = home.resolve("first.csv");
            Path second = home.resolve("second.csv");
            Files.writeString(first, "src,dst,port,proto\n192.0.2.8,198.51.100.8,443,tcp\n");
            Files.writeString(second, "src,dst,port,proto\n198.51.100.8,192.0.2.8,22,tcp\n");

            assertEquals(0, cli.run(new String[] {"cutover-segments"}));
            assertEquals(0, cli.run(new String[] {"cutover-segment", SEGMENT}));
            assertEquals(0, cli.run(new String[] {"cutover-segment-create", "SEG \"ALPHA\"", "192.0.2.0/24, 203.0.113.0/28", "198.51.100.0/24"}));
            assertEquals(0, cli.run(new String[] {"cutover-segment-update", SEGMENT, "2", "SEG-ALPHA", "192.0.2.0/24", "198.51.100.0/24"}));
            assertEquals(0, cli.run(new String[] {"cutover-segment-delete", SEGMENT}));
            assertEquals(0, cli.run(new String[] {"cutover-assess", SEGMENT, spec.toString(), first.toString(), second.toString()}));
            assertEquals(0, cli.run(new String[] {"cutover-assessment", ASSESSMENT}));
            assertEquals(0, cli.run(new String[] {"cutover-flows", ASSESSMENT, "MISSING_RULE"}));
            assertEquals(0, cli.run(new String[] {"cutover-flows", ASSESSMENT, "FLOW", "WOULD_DROP", "3"}));
            assertEquals(0, cli.run(new String[] {"cutover-assessment-delete", ASSESSMENT}));

            assertEquals(List.of(
                    "GET /api/v2/cutover/segments",
                    "GET /api/v2/cutover/segments/" + SEGMENT,
                    "POST /api/v2/cutover/segments",
                    "PUT /api/v2/cutover/segments/" + SEGMENT,
                    "DELETE /api/v2/cutover/segments/" + SEGMENT,
                    "POST /api/v2/cutover/segments/" + SEGMENT + "/assessments",
                    "GET /api/v2/cutover/assessments/" + ASSESSMENT,
                    "GET /api/v2/cutover/assessments/" + ASSESSMENT + "/flows?kind=MISSING_RULE&page=0",
                    "GET /api/v2/cutover/assessments/" + ASSESSMENT + "/flows?kind=FLOW&decision=WOULD_DROP&page=3",
                    "DELETE /api/v2/cutover/assessments/" + ASSESSMENT),
                    seen.stream().map(s -> s.method() + " " + s.path()).toList());
            assertEquals("{\"name\":\"SEG \\\"ALPHA\\\"\",\"blocks\":[\"192.0.2.0/24\",\"203.0.113.0/28\"],\"peerBlocks\":[\"198.51.100.0/24\"]}", seen.get(2).body());
            assertEquals("{\"revision\":2,\"name\":\"SEG-ALPHA\",\"blocks\":[\"192.0.2.0/24\"],\"peerBlocks\":[\"198.51.100.0/24\"]}", seen.get(3).body());
            Seen upload = seen.get(5);
            assertEquals("csrf-9", upload.csrf());
            assertTrue(upload.contentType().startsWith("multipart/form-data; boundary="));
            int specAt = upload.body().indexOf("name=\"spec\""), firstAt = upload.body().indexOf("filename=\"first.csv\""),
                    secondAt = upload.body().indexOf("filename=\"second.csv\"");
            assertTrue(specAt >= 0 && specAt < firstAt && firstAt < secondAt, upload.body());
            assertTrue(upload.body().contains("198.51.100.8,192.0.2.8,22,tcp"));

            assertEquals(2, cli.run(new String[] {"cutover-segment", "not-a-uuid"}));
            assertEquals(2, cli.run(new String[] {"cutover-flows", ASSESSMENT, "EVERYTHING"}));
            assertEquals(2, cli.run(new String[] {"cutover-segment-update", SEGMENT, "zero", "SEG", "192.0.2.0/24", "198.51.100.0/24"}));
            assertEquals(2, cli.run(new String[] {"cutover-assess", SEGMENT, home.resolve("absent.json").toString(), first.toString()}));
            assertEquals(10, seen.size());
        } finally {
            server.stop(0);
        }
    }
}
