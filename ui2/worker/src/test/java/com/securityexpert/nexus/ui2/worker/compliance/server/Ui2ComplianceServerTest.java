package com.securityexpert.nexus.ui2.worker.compliance.server;

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

class Ui2ComplianceServerTest {

    private static Ui2ComplianceServer server;
    private static int port;
    private static HttpClient client;
    private static ObjectMapper mapper;

    @BeforeAll
    static void startServer() throws IOException {
        port = 18085;
        server = new Ui2ComplianceServer(port);
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
        assertEquals("ui2-compliance", json.get("service").asText());
        assertEquals(24, json.get("controls_count").asInt());
    }

    @Test
    void catalogEndpointReturnsAllControls() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/compliance/catalog"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals(24, json.get("count").asInt());
        assertTrue(json.get("controls").isArray());
        assertEquals(24, json.get("controls").size());
    }

    @Test
    void evaluateEndpointEvaluatesDeviceConfig() throws Exception {
        String sampleConfig = String.join("\n",
                "set hostname FW-JULIET-06",
                "set password-controls min-password-length 14",
                "set password-controls complexity on",
                "set clienv inactivity-timeout 10",
                "set sshd protocol 2",
                "set web ssl-port 443"
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/compliance/evaluate"))
                .header("X-Nexus-Vendor", "check_point")
                .header("X-Nexus-Device-Id", "c154432c-1e28-4a20-aa26-ab4a05c0d9af")
                .POST(HttpRequest.BodyPublishers.ofString(sampleConfig, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals("c154432c-1e28-4a20-aa26-ab4a05c0d9af", json.get("deviceId").asText());
        assertEquals(24, json.get("totalAssigned").asInt());
        assertEquals(4, json.get("dataUnavailableCount").asInt());
    }
}
