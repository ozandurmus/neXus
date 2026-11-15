package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** Every support bundle screen action has a command on the same route (design 5.11); exit codes 0, 1, 2, 3. */
class SupportBundleCliTest {

    private static final String ID = "d8f19c2a-4b10-4e8e-9c1a-7b3e2f109a44";

    record Seen(String method, String path, String csrf, String ack, String body) {
    }

    private HttpServer serve(List<Seen> seen, int status, byte[] answer) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token"),
                    exchange.getRequestHeaders().getFirst("X-Nexus-Business-Hours-Ack"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void everyActionHasACommandOnTheSameRoute(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = serve(seen, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            Path dest = home.resolve("bundle.tgz");
            assertEquals(0, cli.run(new String[] {"support-bundles"}));
            assertEquals(0, cli.run(new String[] {"support-bundles", "dev-1", "state=COMPLETED", "limit=5"}));
            assertEquals(0, cli.run(new String[] {"support-bundle", ID}));
            assertEquals(0, cli.run(new String[] {"--business-hours-ack", "support-bundle-request", "dev-1", "cp_gaia_extras,cp_hcp_latest",
                    "TAC case preparation", "--at", "2026-11-16T01:00:00Z", "--case", "CASE-1"}));
            assertEquals(0, cli.run(new String[] {"support-bundle-cancel", ID, "Not needed any more"}));
            assertEquals(0, cli.run(new String[] {"support-bundle-download", ID, "Attach to the case", dest.toString(), "--sanitized"}));
            assertEquals(0, cli.run(new String[] {"support-bundle-delete", ID, "Downloaded and attached"}));
            assertEquals(List.of(
                    "GET /api/v2/support-bundles",
                    "GET /api/v2/devices/dev-1/support-bundles?state=COMPLETED&limit=5",
                    "GET /api/v2/support-bundles/" + ID,
                    "POST /api/v2/devices/dev-1/support-bundles",
                    "POST /api/v2/support-bundles/" + ID + "/cancel",
                    "POST /api/v2/support-bundles/" + ID + "/download",
                    "POST /api/v2/support-bundles/" + ID + "/delete"),
                    seen.stream().map(s -> s.method() + " " + s.path()).toList());
            assertEquals("{\"kinds\":[\"cp_gaia_extras\",\"cp_hcp_latest\"],\"reason\":\"TAC case preparation\","
                    + "\"run_at\":\"2026-11-16T01:00:00Z\",\"case_ref\":\"CASE-1\"}", seen.get(3).body());
            assertEquals("true", seen.get(3).ack());
            assertEquals("csrf-9", seen.get(3).csrf());
            assertEquals("{\"reason\":\"Attach to the case\",\"mode\":\"sanitized\"}", seen.get(5).body());
            assertTrue(Files.exists(dest), "the download streams to the file");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usageErrorsExitTwoAndNothingIsSent(@TempDir Path home) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = serve(seen, 200, "{}".getBytes(StandardCharsets.UTF_8));
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "c", "t"));
            assertEquals(2, cli.run(new String[] {"support-bundle", "not-a-uuid"}));
            assertEquals(2, cli.run(new String[] {"support-bundle-request", "dev-1", "cp_gaia_extras"}));
            assertEquals(2, cli.run(new String[] {"support-bundle-request", "dev-1", "cp_gaia_extras", "TAC case preparation", "--bogus"}));
            assertEquals(2, cli.run(new String[] {"support-bundles", "dev-1", "limit=1;drop"}));
            assertEquals(2, cli.run(new String[] {"support-bundle-download", ID, "reason here ok", "x.tgz", "--raw"}));
            assertTrue(seen.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusalsExitOneAndAuthorizationRefusalsExitThree(@TempDir Path home) throws Exception {
        for (int[] pair : new int[][] {{409, 1}, {403, 3}, {401, 3}}) {
            List<Seen> seen = new ArrayList<>();
            HttpServer server = serve(seen, pair[0], "{\"code\":\"X\"}".getBytes(StandardCharsets.UTF_8));
            try {
                NexusSessionCli cli = new NexusSessionCli(home);
                cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "c", "t"));
                assertEquals(pair[1], cli.run(new String[] {"support-bundle-delete", ID, "Downloaded and attached"}));
                assertEquals(pair[1], cli.run(new String[] {"support-bundle-download", ID, "Attach to the case", home.resolve("n.tgz").toString()}));
            } finally {
                server.stop(0);
            }
        }
    }
}
