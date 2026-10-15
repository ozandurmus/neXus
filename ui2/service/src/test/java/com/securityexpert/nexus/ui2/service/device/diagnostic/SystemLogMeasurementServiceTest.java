package com.securityexpert.nexus.ui2.service.device.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSyslogMeasure;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogMeasurementRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class SystemLogMeasurementServiceTest {
    private static final Instant IN_WINDOW = Instant.parse("2026-10-15T03:10:00Z"); // 06:10 Europe/Istanbul
    private static final Instant OUTSIDE_WINDOW = Instant.parse("2026-10-15T05:10:00Z"); // 08:10 Europe/Istanbul

    private static DeviceSummaryRecord device(String id, String role, String vendor, String hostname, String cluster) {
        return new DeviceSummaryRecord(id, role, vendor, DeviceEnrollmentState.ENROLLED, Optional.of(hostname),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(cluster), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private static JobWindowPolicy window(Instant at) {
        return new JobWindowPolicy(Clock.fixed(at, ZoneOffset.UTC), 60);
    }

    @Test
    void startAdmitsOneJobPerEnrolledPaloAltoFirewallOnlyOncePerWindow() {
        var devices = mock(DeviceRepository.class);
        when(devices.listReadCollectionTargets("pan_xml_api")).thenReturn(List.of(
                device("fw-1", "gateway", "palo_alto", "FW-TANGO-04", "CLS-ROMEO-01"),
                device("fw-2", "gateway", "palo_alto", "FW-JULIET-06", null),
                device("pano-1", "management_server", "palo_alto", "FW-BRAVO-02", null)));
        var admission = mock(JobAdmissionService.class);
        List<String> keys = new ArrayList<>();
        when(admission.submit(eq(PanSyslogMeasure.CAPABILITY), anyString(), anyString(), eq("synthetic-admin"),
                eq(ActionRegistry.SYSTEM_LOG_MEASURE_START))).thenAnswer(call -> {
                    String key = call.getArgument(2);
                    boolean seen = keys.contains(key);
                    keys.add(key);
                    return "fw-2".equals(call.getArgument(1)) && !seen ? new AdmissionResult.Refused("DEVICE_NOT_ELIGIBLE", "x")
                            : seen ? new AdmissionResult.Deduplicated("job-" + key) : new AdmissionResult.Admitted("job-" + key);
                });
        var service = new SystemLogMeasurementService(devices, admission, mock(SystemLogMeasurementRepository.class), window(IN_WINDOW));

        var first = service.startAll("synthetic-admin");
        assertEquals(2, first.enrolledFirewalls(), "Panorama is never a system-log source");
        assertEquals(1, first.admitted());
        assertEquals(1, first.refused());
        assertEquals(List.of("DEVICE_NOT_ELIGIBLE"), first.refusalCodes());
        var second = service.startAll("synthetic-admin");
        assertEquals(0, second.admitted());
        assertEquals(2, second.deduplicated());
        verify(admission, never()).submit(anyString(), eq("pano-1"), anyString(), anyString(), anyString());
        assertTrue(keys.stream().allMatch(k -> k.startsWith("syslog-measure:fw-")));
    }

    @Test
    void startOutsideAJobWindowIsRefusedBeforeAnyAdmission() {
        var admission = mock(JobAdmissionService.class);
        var service = new SystemLogMeasurementService(mock(DeviceRepository.class), admission,
                mock(SystemLogMeasurementRepository.class), window(OUTSIDE_WINDOW));
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> service.startAll("synthetic-admin"));
        verifyNoInteractions(admission);
    }

    @Test
    void theCapabilityIsAdmissibleOnlyWithItsSignedOffGates() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var capabilities = new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> rows.stream()
                .filter(row -> row.key().equals(key)).toList());
        assertTrue(capabilities.find(PanSyslogMeasure.CAPABILITY).orElseThrow().executionEligible());
        var withoutGates = new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> List.of());
        assertFalse(withoutGates.find(PanSyslogMeasure.CAPABILITY).orElseThrow().executionEligible());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summariesAreMaskedForAiviewByTheResponseAdviceAndPassUnchangedOtherwise() {
        var devices = mock(DeviceRepository.class);
        when(devices.listAll()).thenReturn(List.of(device("fw-1", "gateway", "palo_alto", "edge-lab-01", "lab-cluster-01")));
        var store = mock(SystemLogMeasurementRepository.class);
        when(store.list(Optional.empty(), 200)).thenReturn(List.of(new SystemLogMeasurementRepository.Measurement(
                "m-1", "fw-1", "palo_alto", "pan_system", Instant.parse("2026-10-15T03:12:00Z"),
                "{\"status\":\"MEASURED\",\"entries\":100,\"fieldNames\":[\"receive_time\",\"eventid\"],"
                        + "\"receiveTimeFormat\":\"yyyy/MM/dd HH:mm:ss\",\"eventidTopCounts\":{\"general\":60,\"auth-fail\":40}}",
                "job-1")));
        var service = new SystemLogMeasurementService(devices, mock(JobAdmissionService.class), store, window(IN_WINDOW));
        Map<String, Object> body = Map.of("measurements", service.list(Optional.empty(), 200));

        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));
        var http = mock(HttpServletRequest.class);
        var request = mock(ServletServerHttpRequest.class);
        when(request.getServletRequest()).thenReturn(http);
        when(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        var masked = (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, request, null);
        var row = ((List<Map<String, Object>>) masked.get("measurements")).get(0);
        assertNotEquals("edge-lab-01", row.get("hostname"));
        assertNotEquals("lab-cluster-01", row.get("cluster_member_ref"));
        assertFalse(masked.toString().contains("edge-lab-01"));
        assertFalse(masked.toString().contains("lab-cluster-01"));
        assertEquals("fw-1", row.get("device_id"));
        assertEquals("job-1", row.get("job_id"));
        var summary = (Map<String, Object>) row.get("summary");
        assertEquals(100, summary.get("entries"));
        assertEquals("yyyy/MM/dd HH:mm:ss", summary.get("receiveTimeFormat"));
        assertEquals(Map.of("general", 60, "auth-fail", 40), summary.get("eventidTopCounts"));

        when(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(false);
        assertSame(body, advice.beforeBodyWrite(body, null, null, null, request, null));
        assertEquals("edge-lab-01", ((List<Map<String, Object>>) body.get("measurements")).get(0).get("hostname"));
    }
}
