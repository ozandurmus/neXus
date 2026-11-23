package com.securityexpert.nexus.ui2.service.staticroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidationException;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.ConflictException;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.PreviewSubmissionRequest;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.SubmissionOutcome;

class StaticRoutePreviewServiceTest {

    private static final String CP_DEVICE_ID = "11111111-2222-4333-8444-555555555555";
    private static final String ASA_DEVICE_ID = "22222222-3333-4444-8555-666666666666";
    private static final String FGT_DEVICE_ID = "33333333-4444-4555-8666-777777777777";

    private StaticRoutePreviewService createService() {
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        JobAdmissionService admission = mock(JobAdmissionService.class);
        JobWindowPolicy windows = mock(JobWindowPolicy.class);

        when(devices.find(CP_DEVICE_ID)).thenReturn(Optional.of(new DeviceRecord(
                CP_DEVICE_ID, "gateway", "check_point", "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-cp")));
        DeviceSummaryRecord cpSummary = new DeviceSummaryRecord(
                CP_DEVICE_ID, "gateway", "check_point", DeviceEnrollmentState.ENROLLED, Optional.of("FW-TANGO-04"),
                Optional.empty(), Optional.empty(), Optional.of("ACTIVE"), Optional.of("CLS-ROMEO-01"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("192.0.2.2"), Optional.empty(), false);
        when(devices.findSummary(CP_DEVICE_ID)).thenReturn(Optional.of(cpSummary));
        when(devices.listAll()).thenReturn(List.of(cpSummary));
        when(devices.findEndpointByDeviceId(CP_DEVICE_ID)).thenReturn(Optional.of(new EndpointRecord(
                "ep-cp", CP_DEVICE_ID, "ssh_exec", "192.0.2.2", Instant.now())));
        when(devices.findMembersByClusterRef("CLS-ROMEO-01")).thenReturn(List.of());

        when(devices.find(ASA_DEVICE_ID)).thenReturn(Optional.of(new DeviceRecord(
                ASA_DEVICE_ID, "gateway", "cisco_asa", "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-asa")));
        when(devices.findSummary(ASA_DEVICE_ID)).thenReturn(Optional.of(new DeviceSummaryRecord(
                ASA_DEVICE_ID, "gateway", "cisco_asa", DeviceEnrollmentState.ENROLLED, Optional.of("FW-JULIET-06"),
                Optional.empty(), Optional.empty(), Optional.of("STANDALONE"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("192.0.2.3"), Optional.empty(), false)));
        when(devices.findEndpointByDeviceId(ASA_DEVICE_ID)).thenReturn(Optional.of(new EndpointRecord(
                "ep-asa", ASA_DEVICE_ID, "ssh_exec", "192.0.2.3", Instant.now())));

        when(devices.find(FGT_DEVICE_ID)).thenReturn(Optional.of(new DeviceRecord(
                FGT_DEVICE_ID, "gateway", "fortinet", "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-fgt")));

        InventoryInterface eth0 = new InventoryInterface(
                "if-cp", "eth0", Optional.empty(), InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-cp", "192.0.2.2/25", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
        when(inventory.findLatestRun(CP_DEVICE_ID)).thenReturn(Optional.of(new InventoryRun(
                "run-cp", CP_DEVICE_ID, "job-0", Instant.now(), 1,
                List.of(new InventoryContext("physical", List.of(eth0), List.of())), List.of())));

        InventoryInterface outside = new InventoryInterface(
                "if-asa", "outside", Optional.empty(), InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-asa", "192.0.2.3/25", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
        when(inventory.findLatestRun(ASA_DEVICE_ID)).thenReturn(Optional.of(new InventoryRun(
                "run-asa", ASA_DEVICE_ID, "job-0", Instant.now(), 1,
                List.of(new InventoryContext("physical", List.of(outside), List.of())), List.of())));

        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AdmissionResult.Admitted("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));

        return new StaticRoutePreviewService(
                new MapPreviewRepository(),
                devices,
                inventory,
                admission,
                windows,
                new TopologyNamePseudonymizer("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void enforcesIdempotencyPayloadBindingAndMaskingForReplayViewer() {
        StaticRoutePreviewService service = createService();

        PreviewSubmissionRequest req = new PreviewSubmissionRequest(
                "idem-key-0001",
                CP_DEVICE_ID,
                "check_point",
                "gaia-default",
                "ADD",
                "192.0.2.128/25",
                "192.0.2.1",
                null,
                null,
                null,
                2,
                null,
                "cutover comment",
                "192.0.2.129",
                "CHG-2026-0099",
                "Partner cutover preview");

        SubmissionOutcome first = service.submitPreview(req, "actor-op-admin", false);
        assertFalse(first.deduplicated());
        assertEquals(Boolean.FALSE, first.body().get("mutation_possible"));
        assertEquals(StaticRoutePreviewService.PREVIEW_BADGE, first.body().get("preview_badge"));
        assertEquals("CHG-2026-0099", first.body().get("change_ticket_ref"));

        // Replaying with the exact same payload returns deduplicated = true
        SubmissionOutcome replay = service.submitPreview(req, "actor-op-admin", true);
        assertTrue(replay.deduplicated());
        // Under aiview (masked = true), sensitive actor/ticket/reason/comment fields are withheld
        assertNull(replay.body().get("requested_by"));
        assertNull(replay.body().get("change_ticket_ref"));
        assertNull(replay.body().get("reason"));
        assertNull(replay.body().get("device_comment"));
        assertNotNull(replay.body().get("command_plan"));

        // Reusing the same idempotency key with a different prefix throws 409 ConflictException
        PreviewSubmissionRequest differentPayload = new PreviewSubmissionRequest(
                "idem-key-0001",
                CP_DEVICE_ID,
                "check_point",
                "gaia-default",
                "ADD",
                "192.0.2.192/26",
                "192.0.2.1",
                null, null, null, 2, null, null, null, null, null);
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.submitPreview(differentPayload, "actor-op-admin", false));
        assertEquals("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH", conflict.code());
    }

    /** Raw-versus-masked sweep 2026-11-23: the target list returned the raw cluster name as cluster_id to aiview. */
    @Test
    void maskedTargetListCarriesNoRawClusterName() {
        StaticRoutePreviewService service = createService();
        @SuppressWarnings("unchecked")
        Map<String, Object> plain = ((List<Map<String, Object>>) service.listPreviewsAndTargets(false).get("targets")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> masked = ((List<Map<String, Object>>) service.listPreviewsAndTargets(true).get("targets")).get(0);
        assertEquals("CLS-ROMEO-01", plain.get("cluster_id"));
        assertNotNull(masked.get("cluster_id"));
        assertFalse(masked.get("cluster_id").toString().contains("CLS-ROMEO-01"));
        // the unit a preview is submitted for stays the opaque device id
        assertEquals(CP_DEVICE_ID, masked.get("unit_id"));
    }

    @Test
    void enforcesContract53ValidationRulesIncludingAsaDistanceAndFortiGateRefusal() {
        StaticRoutePreviewService service = createService();

        // FortiGate refused with FORTIMANAGER_MANAGED
        ValidationException fgt = assertThrows(ValidationException.class, () ->
                service.submitPreview(new PreviewSubmissionRequest(
                        "idem-fgt-0001", FGT_DEVICE_ID, "fortinet", "root", "ADD",
                        "192.0.2.128/25", "192.0.2.1", "port1", 10, null, null, null, null, null, null, null),
                        "actor-1", false));
        assertEquals("FORTIMANAGER_MANAGED", fgt.code());

        // ASA distance 255 refused (range 1..254)
        ValidationException asaDist = assertThrows(ValidationException.class, () ->
                service.submitPreview(new PreviewSubmissionRequest(
                        "idem-asa-0001", ASA_DEVICE_ID, "cisco_asa", "single", "ADD",
                        "192.0.2.128/25", "192.0.2.1", "outside", 255, null, null, null, null, null, null, null),
                        "actor-1", false));
        assertEquals("INVALID_DISTANCE", asaDist.code());

        // ASA distance 254 accepted
        SubmissionOutcome asaOk = service.submitPreview(new PreviewSubmissionRequest(
                "idem-asa-0002", ASA_DEVICE_ID, "cisco_asa", "single", "ADD",
                "192.0.2.128/25", "192.0.2.1", "outside", 254, null, null, null, null, null, null, null),
                "actor-1", false);
        assertEquals(254, asaOk.body().get("distance"));

        // Trailing newline injection rejected by \A...\z anchor
        assertThrows(ValidationException.class, () ->
                service.submitPreview(new PreviewSubmissionRequest(
                        "idem-asa-0003", ASA_DEVICE_ID, "cisco_asa", "single\ninjected", "ADD",
                        "192.0.2.128/25", "192.0.2.1", "outside", 1, null, null, null, null, null, null, null),
                        "actor-1", false));
    }

    private static final class MapPreviewRepository implements StaticRoutePreviewRepository {
        private final Map<String, PreviewRow> byId = new LinkedHashMap<>();
        private final Map<String, PreviewRow> byIdem = new LinkedHashMap<>();

        @Override
        public Optional<PreviewRow> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byIdem.get(idempotencyKey));
        }

        @Override
        public void create(CreateSpec s) {
            PreviewRow row = new PreviewRow(
                    s.previewId(), s.idempotencyKey(), s.requestPayloadSha256(), s.requestedBy(), Instant.now(),
                    s.unitId(), s.targetDeviceId(), s.vendor(), s.routingContext(), s.operation(),
                    s.destinationCidr(), s.nextHopIp(), Optional.ofNullable(s.interfaceName()),
                    Optional.ofNullable(s.distance()), Optional.ofNullable(s.metric()), Optional.ofNullable(s.priority()),
                    Optional.ofNullable(s.routeName()), Optional.ofNullable(s.deviceComment()), s.representativeHost(),
                    Optional.ofNullable(s.changeTicketRef()), Optional.ofNullable(s.reason()),
                    Optional.empty(), Optional.empty(), STATE_QUEUED, VERDICT_PENDING, Optional.empty(),
                    false, Optional.empty(), s.preChecksJson(), Optional.empty(), s.commandPlanJson(),
                    s.inversePlanJson(), Optional.empty());
            byId.put(s.previewId(), row);
            byIdem.put(s.idempotencyKey(), row);
        }

        @Override
        public boolean attachJob(String previewId, String jobId) {
            PreviewRow cur = byId.get(previewId);
            if (cur == null) return false;
            PreviewRow updated = new PreviewRow(
                    cur.previewId(), cur.idempotencyKey(), cur.requestPayloadSha256(), cur.requestedBy(), cur.requestedAt(),
                    cur.unitId(), cur.targetDeviceId(), cur.vendor(), cur.routingContext(), cur.operation(),
                    cur.destinationCidr(), cur.nextHopIp(), cur.interfaceName(), cur.distance(), cur.metric(), cur.priority(),
                    cur.routeName(), cur.deviceComment(), cur.representativeHost(), cur.changeTicketRef(), cur.reason(),
                    Optional.of(jobId), Optional.of("REQUESTED"), cur.state(), cur.verdict(), cur.stopCode(),
                    false, cur.preRouteTableSha256(), cur.preChecksJson(), cur.beforePathJson(), cur.commandPlanJson(),
                    cur.inversePlanJson(), cur.completedAt());
            byId.put(previewId, updated);
            byIdem.put(cur.idempotencyKey(), updated);
            return true;
        }

        @Override public Optional<PreviewRow> findByJob(String jobId) { return Optional.empty(); }
        @Override public boolean markRunning(String jobId, long leaseEpoch) { return true; }
        @Override
        public boolean recordOutcome(String jobId, long leaseEpoch, String state, String verdict, String stopCode,
                String preRouteTableSha256, String preChecksJson, String beforePathJson, String commandPlanJson,
                String inversePlanJson) {
            return true;
        }
        @Override public boolean abandonByJob(String jobId, String stopCode) { return true; }
        @Override
        public void abandonById(String previewId, String stopCode) {
            PreviewRow cur = byId.get(previewId);
            if (cur == null) return;
            PreviewRow updated = new PreviewRow(
                    cur.previewId(), cur.idempotencyKey(), cur.requestPayloadSha256(), cur.requestedBy(), cur.requestedAt(),
                    cur.unitId(), cur.targetDeviceId(), cur.vendor(), cur.routingContext(), cur.operation(),
                    cur.destinationCidr(), cur.nextHopIp(), cur.interfaceName(), cur.distance(), cur.metric(), cur.priority(),
                    cur.routeName(), cur.deviceComment(), cur.representativeHost(), cur.changeTicketRef(), cur.reason(),
                    cur.jobId(), cur.jobState(), STATE_STOPPED, VERDICT_FAIL, Optional.ofNullable(stopCode),
                    false, cur.preRouteTableSha256(), cur.preChecksJson(), cur.beforePathJson(), cur.commandPlanJson(),
                    cur.inversePlanJson(), Optional.of(Instant.now()));
            byId.put(previewId, updated);
            byIdem.put(cur.idempotencyKey(), updated);
        }
        @Override public Optional<PreviewRow> find(String previewId) { return Optional.ofNullable(byId.get(previewId)); }
        @Override public List<PreviewRow> listRecent(int limit) { return new ArrayList<>(byId.values()); }
    }
}
