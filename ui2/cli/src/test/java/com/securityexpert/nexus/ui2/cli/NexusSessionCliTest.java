package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** cli_parity_for_every_ui_action: the CLI is the screen's calls -- same routes, same cookie, same CSRF token. */
class NexusSessionCliTest {

    @Test
    void aStoredSessionIsSentAsTheScreenWouldSendIt(@TempDir Path home) throws Exception {
        AtomicReference<String> cookie = new AtomicReference<>();
        AtomicReference<String> csrf = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            csrf.set(exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
            path.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"job_id\":\"j-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            assertTrue(Files.exists(home.resolve("session.json")));

            int exit = cli.run(new String[] {"backup-run", "dev-1", "DR drill SEC-4091", "snapshot"});

            assertEquals(0, exit);
            assertEquals("/devices/dev-1/backup/collect", path.get());
            assertEquals("ui2_session=opaque-cookie", cookie.get());
            assertEquals("csrf-9", csrf.get(), "a non-GET carries the CSRF token exactly like the browser");
            assertTrue(body.get().contains("\"reason\":\"DR drill SEC-4091\"") && body.get().contains("\"type\":\"snapshot\""), body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void systemLogMeasurementCommandsCallTheScreenRoutes(@TempDir Path home) throws Exception {
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            assertEquals(0, cli.run(new String[] {"syslog-measure-start"}));
            assertEquals(0, cli.run(new String[] {"syslog-measurements"}));
            assertEquals(0, cli.run(new String[] {"syslog-measurements", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-measurements", "vendor=check_point", "source=cp_syslog_measure_ls_sa", "limit=50"}));
            assertEquals(0, cli.run(new String[] {"syslog-measurements", "dev-1", "vendor=check_point"}));
            assertEquals(0, cli.run(new String[] {"syslog-collect-start"}));
            assertEquals(0, cli.run(new String[] {"syslog-collect-start", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-spike-review", "dev-1"}));
            assertEquals(java.util.List.of("POST /api/v2/diagnostics/system-log-measurements/start",
                    "GET /api/v2/diagnostics/system-log-measurements",
                    "GET /api/v2/diagnostics/system-log-measurements?device_id=dev-1",
                    "GET /api/v2/diagnostics/system-log-measurements?vendor=check_point&source=cp_syslog_measure_ls_sa&limit=50",
                    "GET /api/v2/diagnostics/system-log-measurements?device_id=dev-1&vendor=check_point",
                    "POST /api/v2/system-logs/collect", "POST /api/v2/system-logs/collect",
                    "POST /api/v2/diagnostics/system-log-measurements/spike-layout-review"), calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void systemLogReadCommandsCallTheScreenRoutes(@TempDir Path home) throws Exception {
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            assertEquals(0, cli.run(new String[] {"syslog-sources", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-chunks", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-chunks", "dev-1", "cp_messages"}));
            assertEquals(0, cli.run(new String[] {"syslog-chunks", "dev-1", "2026-10-17"}));
            assertEquals(0, cli.run(new String[] {"syslog-chunks", "dev-1", "cp_messages", "2026-10-17"}));
            assertEquals(0, cli.run(new String[] {"syslog-gaps"}));
            assertEquals(0, cli.run(new String[] {"syslog-gaps", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-crash-files"}));
            assertEquals(0, cli.run(new String[] {"syslog-crash-files", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-hcp", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-health"}));
            assertEquals(0, cli.run(new String[] {"syslog-spike-data", "dev-1"}));
            assertEquals(0, cli.run(new String[] {"syslog-settings"}));
            assertEquals(0, cli.run(new String[] {"syslog-settings-set", "spike_data_enabled=true", "raw_retention_days=45"}));
            assertEquals(java.util.List.of(
                    "GET /api/v2/system-logs/dev-1/sources",
                    "GET /api/v2/system-logs/dev-1/chunks",
                    "GET /api/v2/system-logs/dev-1/chunks?source=cp_messages",
                    "GET /api/v2/system-logs/dev-1/chunks?day=2026-10-17",
                    "GET /api/v2/system-logs/dev-1/chunks?source=cp_messages&day=2026-10-17",
                    "GET /api/v2/system-logs/gaps", "GET /api/v2/system-logs/gaps?deviceId=dev-1",
                    "GET /api/v2/system-logs/crash-files", "GET /api/v2/system-logs/crash-files?deviceId=dev-1",
                    "GET /api/v2/system-logs/dev-1/hcp", "GET /api/v2/system-logs/health", "GET /api/v2/system-logs/dev-1/spike-data",
                    "GET /api/v2/config/system-logs", "PUT /api/v2/config/system-logs"), calls);
            // syslog-settings-set sends the screen's PUT body: booleans and whole numbers typed, in the order given.
            assertEquals("{\"spike_data_enabled\":true,\"raw_retention_days\":45}", bodies.get(bodies.size() - 1));
            calls.clear();
            // identifiers are opaque tokens: a path or query fragment is refused before any call
            assertEquals(2, cli.run(new String[] {"syslog-sources", "dev-1/../../x"}));
            assertEquals(2, cli.run(new String[] {"syslog-gaps", "dev-1&x=1"}));
            assertEquals(2, cli.run(new String[] {"syslog-chunks", "dev-1", "a", "b"}));
            assertEquals(2, cli.run(new String[] {"syslog-sources"}));
            // a settings write needs at least one key=value; a bare word or an odd key is usage, never a call
            assertEquals(2, cli.run(new String[] {"syslog-settings-set"}));
            assertEquals(2, cli.run(new String[] {"syslog-settings-set", "spike_data_enabled"}));
            assertEquals(2, cli.run(new String[] {"syslog-settings-set", "Spike=true"}));
            assertTrue(calls.isEmpty(), calls.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syslogViewPrintsThePageToStdoutAndPagesFollowNextOffsetUntilEof(@TempDir Path home) throws Exception {
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String uri = exchange.getRequestURI().toString();
            calls.add(uri);
            boolean unmasked = uri.contains("unmasked=true");
            if (uri.contains("chunks/denied/")) {
                byte[] refusal = "{\"code\":\"SYSTEM_LOG_UNMASKED_NOT_PERMITTED\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(403, refusal.length);
                exchange.getResponseBody().write(refusal);
                exchange.close();
                return;
            }
            boolean second = uri.contains("offset=6");
            byte[] out = (second ? "page two\n" : "page 1\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Syslog-Masked", String.valueOf(!unmasked));
            if (!unmasked) exchange.getResponseHeaders().add("X-Syslog-Mask-Rules", "logmask-v3");
            exchange.getResponseHeaders().add("X-Syslog-Next-Offset", second ? "15" : "6");
            exchange.getResponseHeaders().add("X-Syslog-Eof", String.valueOf(second));
            exchange.getResponseHeaders().add("X-Syslog-Lines", "1");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        java.io.PrintStream originalOut = System.out, originalErr = System.err;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(), err = new java.io.ByteArrayOutputStream();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));

            assertEquals(0, cli.run(new String[] {"syslog-view", "chunk-1"}));
            assertEquals("page 1\n", out.toString(StandardCharsets.UTF_8));
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("masked=true") && err.toString(StandardCharsets.UTF_8).contains("next_offset=6"));
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("masked=true rules=logmask-v3"), "the CLI names the masking rule set");
            assertEquals(java.util.List.of("/api/v2/system-logs/chunks/chunk-1/text?offset=0"), calls);

            out.reset(); err.reset(); calls.clear();
            assertEquals(0, cli.run(new String[] {"syslog-view", "chunk-1", "limit=2048", "all"}));
            assertEquals("page 1\npage two\n", out.toString(StandardCharsets.UTF_8));
            assertEquals(java.util.List.of("/api/v2/system-logs/chunks/chunk-1/text?offset=0&limit=2048",
                    "/api/v2/system-logs/chunks/chunk-1/text?offset=6&limit=2048"), calls);

            out.reset(); err.reset(); calls.clear();
            assertEquals(0, cli.run(new String[] {"syslog-view", "chunk-1", "offset=6", "unmasked"}));
            assertEquals(java.util.List.of("/api/v2/system-logs/chunks/chunk-1/text?offset=6&unmasked=true"), calls);
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("audit ledger"));
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("masked=false"));

            out.reset(); err.reset();
            assertEquals(3, cli.run(new String[] {"syslog-view", "denied", "unmasked"}), "a refusal is exit 3, like every 403");
            assertEquals("", out.toString(StandardCharsets.UTF_8));
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("SYSTEM_LOG_UNMASKED_NOT_PERMITTED"));

            out.reset(); err.reset(); calls.clear();
            assertEquals(2, cli.run(new String[] {"syslog-view", "chunk-1", "offset=abc"}));
            assertEquals(2, cli.run(new String[] {"syslog-view", "../x"}));
            assertEquals(2, cli.run(new String[] {"syslog-view"}));
            assertTrue(calls.isEmpty());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            server.stop(0);
        }
    }

    @Test
    void withoutASessionEveryCommandRefusesBeforeAnyCall(@TempDir Path home) {
        int exit = new NexusSessionCli(home).run(new String[] {"devices"});
        assertEquals(2, exit);
    }

    @Test
    void changesCallsTheChangeFeedRouteWithTheScreenFilters(@TempDir Path home) throws Exception {
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] out = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NexusSessionCli cli = new NexusSessionCli(home);
            cli.saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            assertEquals(0, cli.run(new String[] {"changes"}));
            assertEquals(0, cli.run(new String[] {"changes", "from=2026-10-16T00:00:00Z", "kinds=USER,DEVICE_EVENT", "vendor=palo_alto", "limit=20"}));
            assertEquals(java.util.List.of("GET /api/v2/changes/feed",
                    "GET /api/v2/changes/feed?from=2026-10-16T00%3A00%3A00Z&kinds=USER%2CDEVICE_EVENT&vendor=palo_alto&limit=20"), calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownCommandsAreNotThisClassS(@TempDir Path home) {
        assertEquals(-1, new NexusSessionCli(home).run(new String[] {"credential-list"}));
    }

    @Test
    void filtersBecomeAQueryStringAndJsonIsEscaped() {
        assertEquals("?state=FAILED&q=sftp+fetch+failed%3A+x", NexusSessionCli.queryString(new String[] {"jobs", "state=FAILED", "q=sftp fetch failed: x"}, 1));
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("reason", "say \"hi\"");
        fields.put("enabled", true);
        fields.put("n", 3);
        assertEquals("{\"reason\":\"say \\\"hi\\\"\",\"enabled\":true,\"n\":3}", NexusSessionCli.json(fields));
        assertEquals(java.util.Optional.of("tok-1"), NexusSessionCli.jsonString("{\"authenticated\":true,\"csrf_token\":\"tok-1\"}", "csrf_token"));
    }
}
