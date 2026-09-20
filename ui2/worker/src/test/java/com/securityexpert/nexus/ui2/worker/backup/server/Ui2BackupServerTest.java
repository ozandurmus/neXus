package com.securityexpert.nexus.ui2.worker.backup.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class Ui2BackupServerTest {

    private Ui2BackupServer server;
    private HttpClient client;
    private ObjectMapper mapper;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        port = 18086;
        server = new Ui2BackupServer(port);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void healthCheckReturnsUpWith400GiStorageAndRetentionPolicies() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
        assertEquals("UP", body.get("status"));
        assertEquals("ui2-backup", body.get("service"));
        assertEquals("400Gi", body.get("storage_capacity"));
        assertEquals("/vault/recovery", body.get("vault_mount"));

        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) body.get("retention_policy");
        assertEquals(30, policy.get("daily_backup_retention_days"));
        assertEquals(2, policy.get("snapshot_retention_depth"));
    }

    @Test
    void semanticDiffEvaluatesMajorInterfaceChange() throws Exception {
        Map<String, Object> payload = Map.of(
                "vendor", "check_point",
                "previous_config", "set interface eth0 ipv4-address 192.0.2.1 mask-length 24",
                "current_config", "set interface eth0 ipv4-address 192.0.2.2 mask-length 24"
        );

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/backup/diff"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
        assertEquals("major", body.get("deviation_class"));
        assertEquals(Boolean.TRUE, body.get("major_alert"));
        assertTrue(body.get("summary").toString().contains("interface change"));
    }

    @Test
    void getPolicyReturnsConfigurableRetentionSchedule() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/backup/policy"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
        assertEquals("0 2 * * *", body.get("daily_backup_cron"));
        assertEquals("0 3 * * 0", body.get("weekly_snapshot_cron"));
        assertEquals(30, body.get("backup_retention_days"));
        assertEquals(2, body.get("snapshot_retention_depth"));
    }
}
