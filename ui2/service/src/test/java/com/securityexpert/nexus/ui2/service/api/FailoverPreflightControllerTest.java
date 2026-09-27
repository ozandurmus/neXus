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
import static org.mockito.Mockito.*;

class FailoverPreflightControllerTest {

    private FailoverPreflightController controller;
    private FailoverPreflightController emptyClusterController;
    private PreflightService serviceWithInventory;

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

        serviceWithInventory = new PreflightService(devRepo, invRepo, pseudonymizer);
        controller = new FailoverPreflightController(serviceWithInventory);

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
    @DisplayName("GET preflight keeps observed roles and versions but does not pass unmeasured readiness checks")
    void testGetLatestPreflightWithInventory() {
        ResponseEntity<Map<String, Object>> response = controller.getLatestPreflight("CLS-ROMEO-01");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        // C-6: Verify cluster_id is an opaque UUID, not raw clusterRef
        String clusterId = (String) body.get("cluster_id");
        assertNotNull(clusterId);
        assertDoesNotThrow(() -> UUID.fromString(clusterId));

        assertEquals("BLOCKING_CONDITIONS_PRESENT", body.get("overall_verdict"));
        assertTrue((int) body.get("total_checks") >= 10);
        assertTrue((int) body.get("blocking_failure_count") > 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) body.get("checks");
        assertNotNull(checks);
        assertFalse(checks.isEmpty());
        Map<String, String> statuses = checks.stream().collect(java.util.stream.Collectors.toMap(
            check -> (String) check.get("check_id"), check -> (String) check.get("status")));
        for (String id : List.of("preflight.state_sync_current", "preflight.policy_parity",
            "preflight.control_sync_link_health", "preflight.checkpoint_pnotes",
            "preflight.standby_resource_headroom")) {
            assertEquals("INSUFFICIENT_EVIDENCE", statuses.get(id), id);
        }
        assertEquals("PASS", statuses.get("preflight.viable_target"));
        assertEquals("PASS", statuses.get("preflight.split_brain_prevention"));

        var snapshot = serviceWithInventory.buildSnapshotForCluster("CLS-ROMEO-01");
        assertEquals("ACTIVE", snapshot.memberA().selfState());
        assertEquals("STANDBY", snapshot.memberB().selfState());
        assertEquals("R81.20", snapshot.memberA().softwareVersion());
        assertTrue(snapshot.memberA().directObservationSuccessful());
        assertNotEquals(Instant.EPOCH, snapshot.memberA().observedAt());
        assertNull(snapshot.memberA().installedPolicyHash());
        assertEquals(-1, snapshot.memberB().cpuUtilizationPct());
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

        assertEquals("BLOCKING_CONDITIONS_PRESENT", body.get("overall_verdict"));
        assertNotNull(body.get("generated_at"));
    }

    @Test
    @DisplayName("Palo Alto inventory is never labelled ClusterXL")
    void testPaloAltoMode() {
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        DeviceSummaryRecord active = new DeviceSummaryRecord(
            "pan-01", "FIREWALL", "PALO_ALTO", DeviceEnrollmentState.ENROLLED,
            Optional.of("FW-TANGO-04"), Optional.empty(), Optional.of("11.2"),
            Optional.of("ACTIVE"), Optional.of("CLS-ROMEO-01"));
        DeviceSummaryRecord standby = new DeviceSummaryRecord(
            "pan-02", "FIREWALL", "PALO_ALTO", DeviceEnrollmentState.ENROLLED,
            Optional.of("FW-JULIET-06"), Optional.empty(), Optional.empty(),
            Optional.of("PASSIVE"), Optional.of("CLS-ROMEO-01"));
        when(devices.findMembersByClusterRef("CLS-ROMEO-01")).thenReturn(List.of(active, standby));
        PreflightService service = new PreflightService(devices, inventory, null);

        var snapshot = service.buildSnapshotForCluster("CLS-ROMEO-01");
        assertEquals("PAN_ACTIVE_PASSIVE", snapshot.haMode());
        assertEquals("PAN_ACTIVE_PASSIVE", snapshot.memberA().haMode());
        assertEquals("PAN_ACTIVE_PASSIVE", snapshot.memberB().haMode());
        assertNull(snapshot.memberB().softwareVersion());
    }
}
