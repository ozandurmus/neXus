package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.persistence.device.inventory.*;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.failover.PreflightService;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FailoverPreflightControllerTest {

    private FailoverPreflightController controller;
    private FailoverPreflightController emptyClusterController;

    @BeforeEach
    void setUp() {
        byte[] key = "test-secret-key-32-bytes-long!!!".getBytes(StandardCharsets.UTF_8);
        TopologyNamePseudonymizer pseudonymizer = new TopologyNamePseudonymizer(key);

        DeviceSummaryRecord mem1 = new DeviceSummaryRecord(
            "dev-01", "FIREWALL", "CHECK_POINT", DeviceEnrollmentState.ENROLLED,
            Optional.of("cp-gw-01"), Optional.of("SecurityGateway"), Optional.of("R81.20"),
            Optional.of("ACTIVE"), Optional.of("CLS-ROMEO-01")
        );
        DeviceSummaryRecord mem2 = new DeviceSummaryRecord(
            "dev-02", "FIREWALL", "CHECK_POINT", DeviceEnrollmentState.ENROLLED,
            Optional.of("cp-gw-02"), Optional.of("SecurityGateway"), Optional.of("R81.20"),
            Optional.of("STANDBY"), Optional.of("CLS-ROMEO-01")
        );

        InventoryHaFact fact1 = new InventoryHaFact("ha-1", "physical", "ACTIVE", Optional.of("CLUSTER_XL_HA"), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT);
        InventoryRun run1 = new InventoryRun("run-01", "dev-01", "job-01", Instant.now(), 1, List.of(), List.of(fact1));

        InventoryHaFact fact2 = new InventoryHaFact("ha-2", "physical", "STANDBY", Optional.of("CLUSTER_XL_HA"), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT);
        InventoryRun run2 = new InventoryRun("run-02", "dev-02", "job-02", Instant.now(), 1, List.of(), List.of(fact2));

        DeviceRepository devRepo = new DeviceRepository() {
            @Override public Optional<DeviceRecord> find(String deviceId) { return Optional.empty(); }
            @Override public Optional<EndpointRecord> findEndpoint(String endpointId) { return Optional.empty(); }
            @Override public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) { return Optional.empty(); }
            @Override public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) { return "id"; }
            @Override public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState, DeviceEnrollmentState toState, String actorFingerprint, String actionId) { return true; }
            @Override public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) { return true; }
            @Override public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) { return true; }
            @Override public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint, String actionId) { return true; }
            @Override public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) { return Optional.empty(); }
            @Override public List<DeviceSummaryRecord> listAll() { return List.of(mem1, mem2); }
        };

        DeviceInventoryRepository invRepo = new DeviceInventoryRepository() {
            @Override public void recordRun(InventoryRun run, String actorFingerprint, String actionId) {}
            @Override public Optional<InventoryRun> findLatestRun(String deviceId) {
                if ("dev-01".equals(deviceId)) return Optional.of(run1);
                if ("dev-02".equals(deviceId)) return Optional.of(run2);
                return Optional.empty();
            }
            @Override public List<InventoryRun> findLatestRuns(List<String> deviceIds) {
                return List.of(run1, run2);
            }
        };

        PreflightService preflightServiceHealthy = new PreflightService(devRepo, invRepo, pseudonymizer);
        controller = new FailoverPreflightController(preflightServiceHealthy);

        PreflightService preflightServiceEmpty = new PreflightService(null, null, pseudonymizer);
        emptyClusterController = new FailoverPreflightController(preflightServiceEmpty);
    }

    @Test
    @DisplayName("GET /api/v2/failover/checks returns all registered pre-flight checks")
    void testListChecks() {
        ResponseEntity<Map<String, Object>> response = controller.listChecks();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((int) response.getBody().get("total_checks") >= 12);

        @SuppressWarnings("unchecked")
        List<PreflightService.PreflightCheckSummary> checks =
            (List<PreflightService.PreflightCheckSummary>) response.getBody().get("checks");
        assertNotNull(checks);
        assertTrue(checks.stream().anyMatch(c -> c.id().equals("preflight.split_brain_prevention")));
        assertTrue(checks.stream().anyMatch(c -> c.id().equals("preflight.checkpoint_pnotes")));
        assertTrue(checks.stream().anyMatch(c -> c.id().equals("preflight.paloalto_path_monitoring")));
    }

    @Test
    @DisplayName("GET /api/v2/failover/{clusterRef}/preflight returns valid pre-flight report with opaque UUID cluster_id")
    void testGetLatestPreflightHealthy() {
        ResponseEntity<Map<String, Object>> response = controller.getLatestPreflight("CLS-ROMEO-01");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        // C-6: Verify cluster_id is an opaque UUID, not raw clusterRef
        String clusterId = (String) body.get("cluster_id");
        assertNotNull(clusterId);
        assertDoesNotThrow(() -> UUID.fromString(clusterId));

        assertEquals("NO_BLOCKING_CONDITIONS_OBSERVED", body.get("overall_verdict"));
        assertTrue((int) body.get("total_checks") >= 10);
        assertEquals(0, body.get("blocking_failure_count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) body.get("checks");
        assertNotNull(checks);
        assertFalse(checks.isEmpty());
    }

    @Test
    @DisplayName("Pre-flight fails closed with BLOCKING_CONDITIONS_PRESENT when cluster members are missing")
    void testGetLatestPreflightMissingMembersFailsClosed() {
        ResponseEntity<Map<String, Object>> response = emptyClusterController.getLatestPreflight("CLS-ROMEO-01");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        // C-1: When members are missing, verdict must fail closed
        assertEquals("BLOCKING_CONDITIONS_PRESENT", body.get("overall_verdict"));
        assertTrue((int) body.get("blocking_failure_count") > 0);
    }

    @Test
    @DisplayName("POST /api/v2/failover/{clusterRef}/preflight triggers on-demand evaluation")
    void testTriggerPreflight() {
        ResponseEntity<Map<String, Object>> response = controller.triggerPreflight("CLS-ROMEO-01");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        assertEquals("NO_BLOCKING_CONDITIONS_OBSERVED", body.get("overall_verdict"));
        assertNotNull(body.get("generated_at"));
    }
}
