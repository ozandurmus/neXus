package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * CLI parity tests for Script Execution slice 1 ({@code SCRIPT_EXECUTION_CONTRACT.md} section 3.6 and
 * Amendment 2026-11-18): {@code script-list}, {@code script-add}, {@code script-version-add}, {@code script-set},
 * {@code script-enable} and {@code script-disable}.
 */
class ScriptCliTest {

    private static final String SCRIPT_ID = "11111111-2222-4333-8444-555555555555";

    record Seen(String method, String path, String csrf, String origin, String body) {
    }

    private static HttpServer serve(List<Seen> seen, int status, byte[] responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token"),
                    exchange.getRequestHeaders().getFirst("Origin"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(status, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void everyScriptCommandCallsTheExpectedRouteWithExpectedPayload(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = serve(seen, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-token-1"));

            Path pyFile = home.resolve("nightly_check.py");
            String content = "print('hello')\n";
            Files.writeString(pyFile, content);
            String expectedBase64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

            assertEquals(0, cli.run(new String[] {"script-list"}));
            assertEquals(0, cli.run(new String[] {"script-list", SCRIPT_ID}));
            assertEquals(0, cli.run(new String[] {"script-add", pyFile.toString(),
                    "--name", "nightly-check",
                    "--schedule", "0 2 * * *",
                    "--enabled", "true",
                    "--timeout", "600",
                    "--egress", "192.0.2.10:443,198.51.100.20:8443",
                    "--credential-refs", "cred-ref-1,cred-ref-2",
                    "--notify", "both",
                    "--run-as", "role:operator"}));
            assertEquals(0, cli.run(new String[] {"script-version-add", SCRIPT_ID, pyFile.toString(),
                    "--credential-refs", "cred-ref-1"}));
            assertEquals(0, cli.run(new String[] {"script-set", SCRIPT_ID,
                    "--schedule", "30 3 * * *",
                    "--enabled", "false",
                    "--timeout", "900",
                    "--egress", "192.0.2.10:443",
                    "--credential-refs", "cred-ref-2",
                    "--notify", "syslog",
                    "--run-as", "role:operator"}));
            assertEquals(0, cli.run(new String[] {"script-enable", SCRIPT_ID}));
            assertEquals(0, cli.run(new String[] {"script-disable", SCRIPT_ID}));
            assertEquals(0, cli.run(new String[] {"script-run", SCRIPT_ID}));
            assertEquals(0, cli.run(new String[] {"script-runs", SCRIPT_ID}));
            assertEquals(0, cli.run(new String[] {"script-output", "run-123"}));

            String expectedOrigin = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(List.of(
                    "GET /api/v2/scripts",
                    "GET /api/v2/scripts/" + SCRIPT_ID,
                    "POST /api/v2/scripts",
                    "POST /api/v2/scripts/" + SCRIPT_ID + "/versions",
                    "PATCH /api/v2/scripts/" + SCRIPT_ID,
                    "POST /api/v2/scripts/" + SCRIPT_ID + "/enable",
                    "POST /api/v2/scripts/" + SCRIPT_ID + "/disable",
                    "POST /api/v2/scripts/" + SCRIPT_ID + "/run",
                    "GET /api/v2/scripts/" + SCRIPT_ID + "/runs",
                    "GET /api/v2/scripts/runs/run-123/output"),
                    seen.stream().map(s -> s.method() + " " + s.path()).toList());

            for (Seen call : seen) {
                if (!"GET".equals(call.method())) {
                    assertEquals("csrf-token-1", call.csrf(), "CSRF header required on " + call.method() + " " + call.path());
                    assertEquals(expectedOrigin, call.origin(), "Origin header required on " + call.method() + " " + call.path());
                }
            }

            assertTrue(seen.get(2).body().contains("\"file_name\":\"nightly_check.py\""));
            assertTrue(seen.get(2).body().contains("\"content_base64\":\"" + expectedBase64 + "\""));
            assertTrue(seen.get(2).body().contains("\"credential_refs\":[\"cred-ref-1\",\"cred-ref-2\"]"));
            assertTrue(seen.get(2).body().contains("\"egress\":[\"192.0.2.10:443\",\"198.51.100.20:8443\"]"));
            assertEquals("csrf-token-1", seen.get(2).csrf());

            assertEquals("{\"schedule\":\"30 3 * * *\",\"enabled\":false,\"timeout_s\":900,"
                    + "\"egress\":[\"192.0.2.10:443\"],\"credential_refs\":[\"cred-ref-2\"],"
                    + "\"notify\":\"syslog\",\"run_as\":\"role:operator\"}", seen.get(4).body());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usageErrorsExitTwoWithoutSendingRequests(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = serve(seen, 200, "{}".getBytes(StandardCharsets.UTF_8));
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "c", "t"));

            assertEquals(2, cli.run(new String[] {"script-add", home.resolve("missing.sh").toString()}));
            assertEquals(2, cli.run(new String[] {"script-set", SCRIPT_ID}));
            assertEquals(2, cli.run(new String[] {"script-set", SCRIPT_ID, "--unknown", "1"}));
            assertEquals(2, cli.run(new String[] {"script-set", SCRIPT_ID, "--timeout", "not-a-number"}));
            assertEquals(2, cli.run(new String[] {"script-list", "bad/id"}));
            assertTrue(seen.isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
