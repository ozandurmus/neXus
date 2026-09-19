package com.securityexpert.nexus.ui2.worker.configuration.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies internal HTTP REST endpoints of {@link Ui2ConfigurationServer}:
 * {@code GET /healthz} and {@code POST /api/v1/parse}.
 */
class Ui2ConfigurationServerTest {

    private static Ui2ConfigurationServer server;
    private static int port;
    private static HttpClient client;
    private static ObjectMapper mapper;

    @BeforeAll
    static void startServer() throws IOException {
        port = 18084;
        server = new Ui2ConfigurationServer(port);
        server.start();
        client = HttpClient.newHttpClient();
        mapper = new ObjectMapper();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void healthCheckReturnsStatusUp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/healthz"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals("UP", json.get("status").asText());
        assertEquals("ui2-configuration", json.get("service").asText());
    }

    @Test
    void parseEndpointProcessesCheckPointConfig() throws Exception {
        String payload = String.join("\n",
                "set hostname FW-TEST-CLUSTER-01",
                "set domainname corp.local",
                "set dns primary 192.0.2.1",
                "set password-controls min-password-length 14",
                "set user admin password-hash $6$secretHash"
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/parse"))
                .header("X-Nexus-Vendor", "check_point")
                .header("X-Nexus-Format", "gaia_clish")
                .header("X-Nexus-Device-Id", "test-dev-uuid")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals("check_point", json.get("vendor").asText());
        assertEquals(1, json.get("withheld_line_count").asInt());
        assertTrue(json.get("canonical_hash").asText().length() == 64);
        assertTrue(json.get("sanitized_text").asText().contains("set password-controls min-password-length 14"));
        assertTrue(json.get("sanitized_text").asText().contains("[SECURITYEXPERT SECRET-BEARING CONFIGURATION LINE WITHHELD]"));

        // Index entries present
        assertTrue(json.get("index").isArray());
        assertTrue(json.get("index").size() >= 2);

        // Highlights present
        assertTrue(json.get("highlights").isArray());
        boolean hasHostname = false;
        for (JsonNode h : json.get("highlights")) {
            if ("Hostname".equals(h.get("label").asText())) {
                assertEquals("FW-TEST-CLUSTER-01", h.get("value").asText());
                hasHostname = true;
            }
        }
        assertTrue(hasHostname);
    }

    @Test
    void parseEndpointRejectsUnsupportedVendor() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/parse"))
                .header("X-Nexus-Vendor", "fortinet")
                .POST(HttpRequest.BodyPublishers.ofString("config system global", StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }
}
