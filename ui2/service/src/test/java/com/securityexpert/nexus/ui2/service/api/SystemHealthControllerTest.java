package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.server.ServletServerHttpRequest;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.FleetRunRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.systemlog.SystemHealthService;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * The health route answers the service's body with no-store caching, and that body is shaped so the privacy advice masks
 * every device name for the aiview persona (a {@code hostname} beside its {@code device_id}, a {@code cluster_member_ref})
 * while the opaque ids, counters and state words pass unchanged.
 */
class SystemHealthControllerTest {
    @TempDir
    Path root;

    @Test
    @SuppressWarnings("unchecked")
    void theBodyIsServedNoStoreAndMasksForTheReplayViewer() {
        var fx = new SystemLogReadFixture(root);
        String host = "edge-" + Long.toString(System.nanoTime() % 1000) + ".example.invalid";
        var device = new DeviceSummaryRecord("dev-1", "gateway", "check_point", DeviceEnrollmentState.ENROLLED, Optional.of(host),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("cluster-ref-1"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
        when(fx.devices.listAll()).thenReturn(List.of(device));
        when(fx.repository.fleetLastRuns()).thenReturn(List.of(new FleetRunRow("dev-1", "cp_crash_listing", "COLLECTED", "job-1", Instant.now())));
        var service = new SystemHealthService(fx.repository, fx.settings, () -> List.of(device), fx.devices, fx.store, 85);
        service.refreshNow(Instant.now());

        var response = new SystemHealthController(service).health();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = response.getBody();
        var row = ((List<Map<String, Object>>) body.get("devices")).get(0);
        assertEquals(host, row.get("hostname"), "the controller itself does not mask: the advice does, per session");

        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        http.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var masked = (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, new ServletServerHttpRequest(http), null);
        var maskedRow = ((List<Map<String, Object>>) masked.get("devices")).get(0);
        assertNotEquals(host, maskedRow.get("hostname"));
        assertNotEquals("cluster-ref-1", maskedRow.get("cluster_member_ref"));
        assertEquals("dev-1", maskedRow.get("device_id"));
        assertEquals("UNKNOWN", maskedRow.get("state"));
        assertTrue(!masked.toString().contains(host), "the name is nowhere else in the masked body");
    }
}
