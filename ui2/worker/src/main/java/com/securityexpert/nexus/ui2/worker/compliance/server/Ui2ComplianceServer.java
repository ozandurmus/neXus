package com.securityexpert.nexus.ui2.worker.compliance.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.securityexpert.nexus.ui2.worker.compliance.catalog.CheckPointComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.evaluator.CheckPointGaiaComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigParser;

/**
 * Stateless HTTP microservice for compliance evaluation.
 * Runs on port 8085 with zero database credentials and zero device credentials.
 */
public final class Ui2ComplianceServer {

    public static final int DEFAULT_PORT = 8085;
    private static final String HEADER_VENDOR = "X-Nexus-Vendor";
    private static final String HEADER_DEVICE_ID = "X-Nexus-Device-Id";

    private final int port;
    private final ObjectMapper mapper;
    private final CheckPointGaiaConfigParser cpParser;
    private HttpServer server;

    public Ui2ComplianceServer(int port) {
        this.port = port;
        this.mapper = new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
        this.cpParser = new CheckPointGaiaConfigParser();
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/healthz", new HealthHandler());
        server.createContext("/api/v1/compliance/catalog", new CatalogHandler());
        server.createContext("/api/v1/compliance/evaluate", new EvaluateHandler());

        server.start();
        System.out.println("ui2-compliance microservice started on port " + port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
            System.out.println("ui2-compliance microservice stopped");
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
                    "service", "ui2-compliance",
                    "version", "1.0.0",
                    "catalog_version", CheckPointComplianceCatalog.CATALOG_VERSION,
                    "controls_count", CheckPointComplianceCatalog.getControls().size()
            ));
        }
    }

    private class CatalogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }
            List<ComplianceControl> controls = CheckPointComplianceCatalog.getControls();
            sendResponse(exchange, 200, Map.of(
                    "version", CheckPointComplianceCatalog.CATALOG_VERSION,
                    "count", controls.size(),
                    "controls", controls
            ));
        }
    }

    private class EvaluateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            String deviceId = getHeader(exchange, HEADER_DEVICE_ID, "unknown");
            String vendor = getHeader(exchange, HEADER_VENDOR, "check_point");

            String configContent;
            try (InputStream in = exchange.getRequestBody()) {
                configContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            try {
                // Parse configuration through CheckPoint parser
                ConfigParseContext ctx = new ConfigParseContext(
                        deviceId,
                        ConfigVendor.CHECK_POINT,
                        ConfigFormat.GAIA_CLISH,
                        "physical",
                        Optional.empty()
                );
                ConfigParseResult parseResult = cpParser.parse(ctx, new ByteArrayInputStream(configContent.getBytes(StandardCharsets.UTF_8)));

                EvaluationResult evalResult = CheckPointGaiaComplianceEvaluator.evaluate(
                        deviceId,
                        vendor,
                        "gaia",
                        parseResult.sections(),
                        configContent,
                        null
                );

                sendResponse(exchange, 200, evalResult);
            } catch (Exception e) {
                sendResponse(exchange, 500, Map.of(
                        "error", "EVALUATION_FAILED",
                        "message", String.valueOf(e.getMessage())
                ));
            }
        }
    }

    private static String getHeader(HttpExchange exchange, String name, String defaultValue) {
        String val = exchange.getRequestHeaders().getFirst(name);
        return val != null && !val.isBlank() ? val.trim() : defaultValue;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
