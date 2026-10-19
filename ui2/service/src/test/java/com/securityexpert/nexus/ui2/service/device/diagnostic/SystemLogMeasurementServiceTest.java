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
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSpikeLayoutReview;
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
        when(store.list(Optional.empty(), Optional.empty(), Optional.empty(), 200)).thenReturn(List.of(new SystemLogMeasurementRepository.Measurement(
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

    /** R1: a Check Point numeric summary keeps every size, mtime and count exact through the aiview masking. */
    @Test
    @SuppressWarnings("unchecked")
    void checkPointNumericSummariesKeepLargeNumbersAndAreFilteredByVendorAndSource() {
        var devices = mock(DeviceRepository.class);
        when(devices.listAll()).thenReturn(List.of(device("gw-1", "gateway", "check_point", "edge-lab-02", null)));
        var store = mock(SystemLogMeasurementRepository.class);
        when(store.list(Optional.empty(), Optional.of("check_point"), Optional.of("cp_syslog_measure_stat"), 50)).thenReturn(List.of(
                new SystemLogMeasurementRepository.Measurement("m-2", "gw-1", "check_point", "cp_syslog_measure_stat",
                        Instant.parse("2026-10-17T03:05:00Z"),
                        "{\"status\":\"MEASURED\",\"kind\":\"stat\",\"files\":[{\"class\":\"messages.1\","
                                + "\"present\":true,\"inode\":\"i_0123456789abcdef\",\"sizeBytes\":20971520,"
                                + "\"mtimeEpoch\":1760650000}]}", "job-2")));
        var service = new SystemLogMeasurementService(devices, mock(JobAdmissionService.class), store, window(IN_WINDOW));
        Map<String, Object> body = Map.of("measurements",
                service.list(Optional.empty(), Optional.of("check_point"), Optional.of("cp_syslog_measure_stat"), 50));

        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));
        var http = mock(HttpServletRequest.class);
        var request = mock(ServletServerHttpRequest.class);
        when(request.getServletRequest()).thenReturn(http);
        when(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        var masked = (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, request, null);
        var row = ((List<Map<String, Object>>) masked.get("measurements")).get(0);
        assertNotEquals("edge-lab-02", row.get("hostname"));
        var file = ((List<Map<String, Object>>) ((Map<String, Object>) row.get("summary")).get("files")).get(0);
        assertEquals(20971520, file.get("sizeBytes"));
        assertEquals(1760650000, file.get("mtimeEpoch"));
        assertEquals("messages.1", file.get("class"));
        assertEquals("i_0123456789abcdef", file.get("inode"));
        assertEquals("cp_syslog_measure_stat", row.get("source"));
    }

    // ------------------------------------------------------------------ the Spike Detective layout review (2026-10-19)

    private static DeviceRepository oneDevice(String id, String role, String vendor) {
        var devices = mock(DeviceRepository.class);
        when(devices.find(id)).thenReturn(Optional.of(new com.securityexpert.nexus.ui2.persistence.device.DeviceRecord(id, role,
                vendor, "manual", Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
        return devices;
    }

    private static SystemLogMeasurementRepository.Measurement review(String status) {
        return new SystemLogMeasurementRepository.Measurement("m-9", "gw-1", "check_point", CpSpikeLayoutReview.SOURCE,
                Instant.parse("2026-10-15T03:12:00Z"), "{\"status\":\"" + status + "\",\"outcome\":\"REVIEWED\"}", "job-9");
    }

    @Test
    void theLayoutReviewAdmitsOneJobForOneCheckPointGatewayUnderTheReadOnlyMeasurementAction() {
        var devices = oneDevice("gw-1", "gateway", "check_point");
        var admission = mock(JobAdmissionService.class);
        var store = mock(SystemLogMeasurementRepository.class);
        when(store.list(Optional.of("gw-1"), Optional.of("check_point"), Optional.of(CpSpikeLayoutReview.SOURCE), 1000))
                .thenReturn(List.of(review("UNKNOWN")));
        String slot = String.valueOf(window(IN_WINDOW).slotStart(IN_WINDOW).toEpochSecond());
        when(admission.submit(CpSpikeLayoutReview.CAPABILITY, "gw-1", "spike-layout-review:gw-1:" + slot, "synthetic-aiview",
                ActionRegistry.SYSTEM_LOG_MEASURE_START)).thenReturn(new AdmissionResult.Admitted("job-1"),
                new AdmissionResult.Deduplicated("job-1"));
        var service = new SystemLogMeasurementService(devices, admission, store, window(IN_WINDOW));
        assertEquals(new SystemLogMeasurementService.ReviewOutcome("ADMITTED", "job-1", null),
                service.startSpikeLayoutReview("gw-1", "synthetic-aiview"), "an earlier UNKNOWN review does not block a retry");
        assertEquals(new SystemLogMeasurementService.ReviewOutcome("DEDUPLICATED", "job-1", null),
                service.startSpikeLayoutReview("gw-1", "synthetic-aiview"), "one job per device per window");
        verify(admission, times(2)).submit(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void theLayoutReviewIsRefusedForAReviewedDeviceAnotherVendorOrAManagementServerAndOutsideAWindow() {
        var store = mock(SystemLogMeasurementRepository.class);
        when(store.list(Optional.of("gw-1"), Optional.of("check_point"), Optional.of(CpSpikeLayoutReview.SOURCE), 1000))
                .thenReturn(List.of(review("MEASURED")));
        var admission = mock(JobAdmissionService.class);
        assertEquals("SPIKE_LAYOUT_ALREADY_REVIEWED", new SystemLogMeasurementService(oneDevice("gw-1", "gateway", "check_point"),
                admission, store, window(IN_WINDOW)).startSpikeLayoutReview("gw-1", "a").code());
        assertEquals("SPIKE_REVIEW_VENDOR_UNSUPPORTED", new SystemLogMeasurementService(oneDevice("gw-1", "gateway", "palo_alto"),
                admission, store, window(IN_WINDOW)).startSpikeLayoutReview("gw-1", "a").code());
        assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", new SystemLogMeasurementService(
                oneDevice("gw-1", "management_server", "check_point"), admission, store, window(IN_WINDOW))
                .startSpikeLayoutReview("gw-1", "a").code());
        assertEquals("DEVICE_NOT_FOUND", new SystemLogMeasurementService(mock(DeviceRepository.class), admission, store,
                window(IN_WINDOW)).startSpikeLayoutReview("gw-1", "a").code());
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> new SystemLogMeasurementService(
                oneDevice("gw-1", "gateway", "check_point"), admission, store, window(OUTSIDE_WINDOW))
                .startSpikeLayoutReview("gw-1", "a"));
        verifyNoInteractions(admission);
    }

    @Test
    void theLayoutReviewCapabilityIsAdmissibleOnlyWithBothSignedOffGates() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var capabilities = new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> rows.stream()
                .filter(row -> row.key().equals(key)).toList());
        assertTrue(capabilities.find(CpSpikeLayoutReview.CAPABILITY).orElseThrow().executionEligible());
        var withoutC5 = new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> rows.stream()
                .filter(row -> row.key().equals(key) && !row.gateId().equals(CpSpikeLayoutReview.LS_SPIKE_DIR_GATE)).toList());
        assertFalse(withoutC5.find(CpSpikeLayoutReview.CAPABILITY).orElseThrow().executionEligible());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLayoutReviewSummaryKeepsItsClassesAndNumbersThroughTheAiviewMasking() {
        var devices = mock(DeviceRepository.class);
        when(devices.listAll()).thenReturn(List.of(device("gw-1", "gateway", "check_point", "edge-lab-02", null)));
        var store = mock(SystemLogMeasurementRepository.class);
        when(store.list(Optional.of("gw-1"), Optional.of("check_point"), Optional.of(CpSpikeLayoutReview.SOURCE), 10)).thenReturn(List.of(
                new SystemLogMeasurementRepository.Measurement("m-3", "gw-1", "check_point", CpSpikeLayoutReview.SOURCE,
                        Instant.parse("2026-10-19T09:12:00Z"),
                        "{\"status\":\"MEASURED\",\"outcome\":\"REVIEWED\",\"directory\":{\"nameClass\":\"X_YYYYMMDD_NNN\","
                                + "\"ageClass\":\"10M_TO_1H\",\"memberCount\":3,\"nameClasses\":{\"perf_cpu_NN.log\":"
                                + "{\"count\":2,\"minBytes\":400000,\"maxBytes\":7000000,\"bytes\":7400000}}}}", "job-3")));
        var service = new SystemLogMeasurementService(devices, mock(JobAdmissionService.class), store, window(IN_WINDOW));
        Map<String, Object> body = Map.of("measurements", service.list(Optional.of("gw-1"), Optional.of("check_point"),
                Optional.of(CpSpikeLayoutReview.SOURCE), 10));
        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));
        var http = mock(HttpServletRequest.class);
        var request = mock(ServletServerHttpRequest.class);
        when(request.getServletRequest()).thenReturn(http);
        when(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        var masked = (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, request, null);
        var row = ((List<Map<String, Object>>) masked.get("measurements")).get(0);
        var directory = (Map<String, Object>) ((Map<String, Object>) row.get("summary")).get("directory");
        assertEquals("X_YYYYMMDD_NNN", directory.get("nameClass"));
        assertEquals("10M_TO_1H", directory.get("ageClass"));
        var perf = (Map<String, Object>) ((Map<String, Object>) directory.get("nameClasses")).get("perf_cpu_NN.log");
        assertEquals(7400000, perf.get("bytes"));
        assertEquals(400000, perf.get("minBytes"));
    }
}
