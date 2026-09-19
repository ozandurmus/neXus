package com.securityexpert.nexus.ui2.worker.configuration.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigHighlight;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigSection;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigSetting;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;
import com.securityexpert.nexus.ui2.worker.configuration.core.VendorConfigParser;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigParser;
import com.securityexpert.nexus.ui2.worker.configuration.pan.PaloAltoConfigParser;

/**
 * Lightweight, internal, stateless HTTP microservice server for configuration parsing.
 * Runs on port 8084 with zero database credentials and zero device credentials.
 */
public final class Ui2ConfigurationServer {

    public static final int DEFAULT_PORT = 8084;
    private static final String HEADER_VENDOR = "X-Nexus-Vendor";
    private static final String HEADER_FORMAT = "X-Nexus-Format";
    private static final String HEADER_DEVICE_ID = "X-Nexus-Device-Id";
    private static final String HEADER_ENTITY_TYPE = "X-Nexus-Entity-Type";
    private static final String HEADER_CONTEXT_REF = "X-Nexus-Context-Ref";

    private final int port;
    private final Map<String, VendorConfigParser> parsers = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private HttpServer server;

    public Ui2ConfigurationServer(int port) {
        this.port = port;
        this.mapper = new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);

        registerParser(new CheckPointGaiaConfigParser());
        registerParser(new PaloAltoConfigParser());
    }

    public void registerParser(VendorConfigParser parser) {
        String key = parser.vendor().name() + ":" + parser.format().name();
        parsers.put(key, parser);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/healthz", new HealthHandler());
        server.createContext("/api/v1/parse", new ParseHandler());

        server.start();
        System.out.println("ui2-configuration microservice started on port " + port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
            System.out.println("ui2-configuration microservice stopped");
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
                    "service", "ui2-configuration",
                    "version", "1.0.0"
            ));
        }
    }

    private class ParseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            String vendorHeader = getHeader(exchange, HEADER_VENDOR, "check_point");
            String formatHeader = getHeader(exchange, HEADER_FORMAT, "gaia_clish");
            String deviceId = getHeader(exchange, HEADER_DEVICE_ID, "unknown");
            String entityType = getHeader(exchange, HEADER_ENTITY_TYPE, "physical");
            String contextRef = exchange.getRequestHeaders().getFirst(HEADER_CONTEXT_REF);

            ConfigVendor vendor;
            ConfigFormat format;
            try {
                vendor = ConfigVendor.fromString(vendorHeader);
                format = ConfigFormat.fromString(formatHeader);
            } catch (IllegalArgumentException e) {
                sendResponse(exchange, 400, Map.of("error", "INVALID_ARGUMENT", "message", e.getMessage()));
                return;
            }

            String key = vendor.name() + ":" + format.name();
            VendorConfigParser parser = parsers.get(key);
            if (parser == null) {
                sendResponse(exchange, 400, Map.of(
                        "error", "UNSUPPORTED_PARSER",
                        "message", "No parser registered for " + vendor.wireValue() + "/" + format.wireValue()
                ));
                return;
            }

            ConfigParseContext context = new ConfigParseContext(
                    deviceId,
                    vendor,
                    format,
                    entityType,
                    Optional.ofNullable(contextRef)
            );

            try (InputStream in = exchange.getRequestBody()) {
                ConfigParseResult result = parser.parse(context, in);
                Map<String, Object> body = toResultMap(result);
                sendResponse(exchange, 200, body);
            } catch (Exception e) {
                sendResponse(exchange, 500, Map.of(
                        "error", "PARSE_FAILED",
                        "message", String.valueOf(e.getMessage())
                ));
            }
        }
    }

    private static Map<String, Object> toResultMap(ConfigParseResult res) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("vendor", res.vendor().wireValue());
        map.put("canonical_hash", res.canonicalHash().orElse(null));
        map.put("withheld_line_count", res.withheldLineCount());
        map.put("sanitized_text", res.sanitizedText());
        map.put("total_settings_count", res.totalSettingsCount());

        List<Map<String, Object>> indexList = res.index().stream().map(entry -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("context", entry.context());
            m.put("section", entry.section());
            m.put("source", entry.source().orElse(null));
            m.put("entry_count", entry.entryCount());
            return m;
        }).toList();
        map.put("index", indexList);

        List<Map<String, Object>> sectionList = res.sections().stream().map(sec -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", sec.id());
            sm.put("label", sec.label());
            sm.put("count", sec.count());
            List<Map<String, Object>> settings = sec.settings().stream().map(s -> {
                Map<String, Object> setm = new LinkedHashMap<>();
                setm.put("setting", s.setting());
                setm.put("value", s.value());
                setm.put("origin", s.origin());
                setm.put("context", s.context().orElse(null));
                return setm;
            }).toList();
            sm.put("settings", settings);
            return sm;
        }).toList();
        map.put("sections", sectionList);

        List<Map<String, Object>> highlightList = res.highlights().stream().map(h -> {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("label", h.label());
            hm.put("value", h.value());
            hm.put("section", h.section());
            hm.put("section_label", h.sectionLabel());
            return hm;
        }).toList();
        map.put("highlights", highlightList);

        return map;
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
