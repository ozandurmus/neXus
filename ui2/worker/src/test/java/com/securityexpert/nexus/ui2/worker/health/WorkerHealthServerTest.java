package com.securityexpert.nexus.ui2.worker.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class WorkerHealthServerTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> call(int port, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesLivezAndReadyzOnly() throws Exception {
        AtomicLong now = new AtomicLong();
        WorkerHealth health = new WorkerHealth(now::get, Duration.ofSeconds(120), Duration.ofSeconds(60));
        try (WorkerHealthServer server = WorkerHealthServer.start(0, health)) {
            int port = server.port();
            assertEquals(200, call(port, "GET", "/livez").statusCode());
            assertEquals("ok\n", call(port, "GET", "/livez").body());
            HttpResponse<String> notReady = call(port, "GET", "/readyz");
            assertEquals(503, notReady.statusCode());
            assertEquals("starting\n", notReady.body());

            health.markStarted();
            health.observeHeartbeat(() -> true);
            assertEquals(200, call(port, "GET", "/readyz").statusCode());
            assertEquals(200, call(port, "HEAD", "/readyz").statusCode());

            health.register("general-0");
            now.addAndGet(Duration.ofSeconds(121).toNanos());
            assertEquals(503, call(port, "GET", "/livez").statusCode());
            assertEquals("stalled\n", call(port, "GET", "/livez").body());

            assertEquals(404, call(port, "GET", "/metrics").statusCode());
            assertEquals(404, call(port, "GET", "/healthz").statusCode());
            assertEquals(405, call(port, "POST", "/livez").statusCode());
        }
    }

    @Test
    void environmentCanDisableTheServer() throws Exception {
        WorkerHealth health = WorkerHealth.fromEnvironment(Map.<String, String>of()::get);
        assertNull(WorkerHealthServer.startFromEnvironment(Map.of("UI2_HEALTH_PORT", "off")::get, health));
        WorkerHealthServer server = WorkerHealthServer.startFromEnvironment(Map.of("UI2_HEALTH_PORT", "0")::get, health);
        assertNotNull(server);
        try (server) {
            assertEquals(200, call(server.port(), "GET", "/livez").statusCode());
        }
    }
}
