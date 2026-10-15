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

/** CLI parity for the Data Sanitizer screen: the same routes, cookie and CSRF token; uploads streamed from disk. */
class SanitizerCliTest {

    private static final String JOB = "11111111-2222-4333-8444-555555555555";
    private static final String REVERT = "66666666-7777-4888-9999-aaaaaaaaaaaa";

    record Seen(String method, String path, String csrf, String contentType, byte[] body) {
    }

    @Test
    void everyScreenActionHasACommandOnTheSameRoute(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        int[] polls = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            seen.add(new Seen(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("X-CSRF-Token"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            byte[] out;
            if (path.equals("/api/v2/sanitize")) out = ("{\"job_id\":\"" + JOB + "\",\"state\":\"RUNNING\"}").getBytes();
            else if (path.equals("/api/v2/sanitize/revert")) out = ("{\"revert_id\":\"" + REVERT + "\",\"state\":\"RUNNING\"}").getBytes();
            else if (path.endsWith("/download")) out = "sanitized-bytes".getBytes();
            else out = ("{\"state\":\"" + (polls[0]++ == 0 ? "RUNNING" : "SUCCEEDED") + "\",\"counts\":{\"ipv4\":1}}").getBytes();
            exchange.sendResponseHeaders(path.equals("/api/v2/sanitize") || path.endsWith("/revert") ? 202 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        SanitizerCli.pollMillis = 1;
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            Path file = home.resolve("synthetic.log");
            Files.writeString(file, "src 192.0.2.10\n");
            Path dest = home.resolve("out.txt");

            assertEquals(0, cli.run(new String[] {"sanitize", file.toString(), dest.toString()}));
            assertEquals("sanitized-bytes", Files.readString(dest));
            assertEquals(0, cli.run(new String[] {"sanitize-job", JOB}));
            assertEquals(0, cli.run(new String[] {"sanitize-download", JOB, dest.toString()}));
            polls[0] = 1;
            assertEquals(0, cli.run(new String[] {"sanitize-revert", file.toString(), "--job", JOB}));
            assertEquals(0, cli.run(new String[] {"sanitize-revert", file.toString(), "--all"}));
            assertEquals(0, cli.run(new String[] {"sanitize-revert-job", REVERT}));
            assertEquals(0, cli.run(new String[] {"sanitize-revert-download", REVERT, dest.toString()}));

            List<String> routes = seen.stream().map(s -> s.method() + " " + s.path()).toList();
            assertEquals(List.of("POST /api/v2/sanitize", "GET /api/v2/sanitize/jobs/" + JOB, "GET /api/v2/sanitize/jobs/" + JOB,
                    "GET /api/v2/sanitize/jobs/" + JOB + "/download", "GET /api/v2/sanitize/jobs/" + JOB,
                    "GET /api/v2/sanitize/jobs/" + JOB + "/download", "POST /api/v2/sanitize/revert",
                    "GET /api/v2/sanitize/reverts/" + REVERT, "POST /api/v2/sanitize/revert", "GET /api/v2/sanitize/reverts/" + REVERT,
                    "GET /api/v2/sanitize/reverts/" + REVERT, "GET /api/v2/sanitize/reverts/" + REVERT + "/download"), routes);
            Seen upload = seen.get(0);
            assertEquals("csrf-9", upload.csrf());
            assertTrue(upload.contentType().startsWith("multipart/form-data; boundary=nexus-cli-"));
            String body = new String(upload.body(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"file\"; filename=\"synthetic.log\"") && body.contains("src 192.0.2.10"));
            String revert = new String(seen.get(6).body(), StandardCharsets.ISO_8859_1);
            assertTrue(revert.indexOf("name=\"source_job_id\"") < revert.indexOf("name=\"file\""), "fields precede the file");
            assertTrue(new String(seen.get(8).body(), StandardCharsets.ISO_8859_1).contains("name=\"scope\"\r\n\r\nall"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void identifiersMustBeOpaqueAndFilesMustExistBeforeAnyCall(@TempDir Path home) throws Exception {
        NexusSessionCli cli = new NexusSessionCli(home);
        cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:9", "c", "t"));
        assertEquals(2, cli.run(new String[] {"sanitize-job", "../jobs"}));
        assertEquals(2, cli.run(new String[] {"sanitize-download", "x", home.resolve("o").toString()}));
        assertEquals(2, cli.run(new String[] {"sanitize", home.resolve("missing.log").toString()}));
        assertEquals(2, cli.run(new String[] {"sanitize-revert", home.resolve("missing.log").toString(), "--job", "nope"}));
    }
}
