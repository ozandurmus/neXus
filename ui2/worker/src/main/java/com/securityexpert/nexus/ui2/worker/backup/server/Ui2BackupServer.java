package com.securityexpert.nexus.ui2.worker.backup.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.securityexpert.nexus.ui2.worker.backup.diff.SemanticDeviationEngine;

/**
 * Lightweight HTTP microservice for the neXus Backup Engine (ui2-backup).
 * 
 * <p>Runs on port 8086 with virtual threads, servicing:
 * <ul>
 *   <li>{@code /healthz} - Liveness and readiness probe with 400Gi vault status.</li>
 *   <li>{@code /api/v1/backup/diff} - Semantic configuration deviation evaluation.</li>
 *   <li>{@code /api/v1/backup/policy} - Schedule and retention policy defaults.</li>
 * </ul>
 */
public final class Ui2BackupServer {

    public static final int DEFAULT_PORT = 8086;

    private final int port;
    private final ObjectMapper mapper;
    private final SemanticDeviationEngine deviationEngine;
    private HttpServer server;

    public Ui2BackupServer(int port) {
        this.port = port;
        this.mapper = new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
        this.deviationEngine = new SemanticDeviationEngine();
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/healthz", new HealthHandler());
        server.createContext("/api/v1/backup/diff", new DiffHandler());
        server.createContext("/api/v1/backup/policy", new PolicyHandler());

        server.start();
        System.out.println("ui2-backup microservice started on port " + port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
            System.out.println("ui2-backup microservice stopped");
        }
    }

    public int port() {
        return port;
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }
            sendResponse(exchange, 200, Map.of(
                    "status", "UP",
                    "service", "ui2-backup",
                    "version", "1.0.0",
                    "storage_capacity", "400Gi",
                    "vault_mount", "/vault/recovery",
                    "retention_policy", Map.of(
                            "daily_backup_retention_days", 30,
                            "snapshot_retention_depth", 2,
                            "deviation_alert_enabled", true
                    )
            ));
        }
    }

    private class DiffHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            try (InputStream is = exchange.getRequestBody()) {
                byte[] bodyBytes = is.readAllBytes();
                @SuppressWarnings("unchecked")
                Map<String, Object> req = mapper.readValue(bodyBytes, Map.of().getClass());

                String vendor = (String) req.getOrDefault("vendor", "check_point");
                String previousConfig = (String) req.get("previous_config");
                String currentConfig = (String) req.get("current_config");

                SemanticDeviationEngine.DeviationOutcome outcome =
                        deviationEngine.evaluate(vendor, previousConfig, currentConfig);

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("deviation_class", outcome.deviationClass().wireValue());
                resp.put("summary", outcome.summary());
                resp.put("major_alert", outcome.majorAlert());
                resp.put("details", outcome.details());

                sendResponse(exchange, 200, resp);
            } catch (Exception e) {
                sendResponse(exchange, 400, Map.of("error", "Invalid request", "message", String.valueOf(e.getMessage())));
            }
        }
    }

    private class PolicyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 200, Map.of(
                        "daily_backup_cron", "0 2 * * *",
                        "weekly_snapshot_cron", "0 3 * * 0",
                        "backup_retention_days", 30,
                        "snapshot_retention_depth", 2,
                        "major_alert_enabled", true
                ));
            } else {
                sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
