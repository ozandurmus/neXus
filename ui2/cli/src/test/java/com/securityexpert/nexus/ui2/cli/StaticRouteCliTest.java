package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class StaticRouteCliTest {

    private static final String PREVIEW_ID = "srp-99999999-8888-4777-8666-555555555555";
    private static final String UNIT_ID = "11111111-2222-4333-8444-555555555555";

    record Seen(String method, String path, String csrf, String idemKey, String body) {
    }

    private HttpServer serve(List<Seen> seen, int status, byte[] answer) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void staticRoutePreviewAndShowHitTheExactR0Routes(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = serve(seen, 202, "{\"preview_id\":\"srp-99999999-8888-4777-8666-555555555555\",\"mutation_possible\":false}".getBytes(StandardCharsets.UTF_8));
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "cookie-1", "csrf-1"));

            assertEquals(0, cli.run(new String[] {
                    "static-route-preview", UNIT_ID, "192.0.2.128/25", "192.0.2.1",
                    "--op", "ADD",
                    "--vendor", "cisco_asa",
                    "--context", "single",
                    "--interface", "outside",
                    "--distance", "1",
                    "--host", "192.0.2.129",
                    "--ticket", "CHG-2026-0100",
                    "--reason", "Partner subnet preview",
                    "--idempotency-key", "cli-idem-1001"
            }));
            assertEquals(0, cli.run(new String[] {"static-route-preview-show", PREVIEW_ID}));
            assertEquals(0, cli.run(new String[] {"static-route-previews"}));

            assertEquals(List.of(
                    "POST /api/v2/operations/static-route/preview",
                    "GET /api/v2/operations/static-route/preview/" + PREVIEW_ID,
                    "GET /api/v2/operations/static-route/preview"),
                    seen.stream().map(s -> s.method() + " " + s.path()).toList());

            assertEquals("csrf-1", seen.get(0).csrf());
            assertEquals("cli-idem-1001", seen.get(0).idemKey());
            assertTrue(seen.get(0).body().contains("\"destination_prefix\":\"192.0.2.128/25\""));
            assertTrue(seen.get(0).body().contains("\"next_hop_ip\":\"192.0.2.1\""));
            assertTrue(seen.get(0).body().contains("\"interface_name\":\"outside\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usageErrorsIncludingDefaultRouteExitTwoWithoutSendingRequests(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = serve(seen, 200, "{}".getBytes(StandardCharsets.UTF_8));
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "c", "t"));

            assertEquals(2, cli.run(new String[] {"static-route-preview", UNIT_ID, "0.0.0.0/0", "192.0.2.1"}));
            assertEquals(2, cli.run(new String[] {"static-route-preview", UNIT_ID, "192.0.2.128/25"}));
            assertEquals(2, cli.run(new String[] {"static-route-preview", UNIT_ID, "192.0.2.128/25", "192.0.2.1", "--unknown-flag"}));
            assertEquals(2, cli.run(new String[] {"static-route-preview-show", "not-a-uuid"}));
            assertEquals(2, cli.run(new String[] {"static-route-preview-show", "99999999-8888-4777-8666-555555555555"}));
            assertEquals(2, cli.run(new String[] {"static-route-preview-show", "srp-99999999-8888-4777-8666-55555555555A"}));
            assertTrue(seen.isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
