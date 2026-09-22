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
    void withoutASessionEveryCommandRefusesBeforeAnyCall(@TempDir Path home) {
        int exit = new NexusSessionCli(home).run(new String[] {"devices"});
        assertEquals(2, exit);
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
