package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogMeasurementService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class SystemLogMeasurementControllerTest {

    @Test
    void anUnauthenticatedStartIsRefusedBeforeTheService() {
        var service = mock(SystemLogMeasurementService.class);
        var response = new SystemLogMeasurementController(service).start(new MockHttpServletRequest());
        assertEquals(403, response.getStatusCode().value());
        verify(service, never()).startAll(anyString());
    }

    /** PO 2026-10-16: the aiview session may start the read-only M3/M4 measurement (its RBAC already allowed it). */
    @Test
    void aReplayViewerStartsTheMeasurement() {
        var service = mock(SystemLogMeasurementService.class);
        when(service.startAll("aiview-fp")).thenReturn(new SystemLogMeasurementService.BulkOutcome(3, 3, 0, 0, List.of()));
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "aiview-fp");
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var response = new SystemLogMeasurementController(service).start(request);
        assertEquals(202, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals(3, body.get("admitted"));
        verify(service).startAll("aiview-fp");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListPassesTheDeviceVendorAndSourceFilters() {
        var service = mock(SystemLogMeasurementService.class);
        when(service.list(java.util.Optional.of("dev-1"), java.util.Optional.of("check_point"),
                java.util.Optional.of("cp_syslog_measure_ls_sa"), 25)).thenReturn(List.of(Map.of("measurement_id", "m-1")));
        var response = new SystemLogMeasurementController(service).list("dev-1", "check_point", "cp_syslog_measure_ls_sa", 25);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(Map.of("measurement_id", "m-1")), response.getBody().get("measurements"));
        new SystemLogMeasurementController(service).list(null, null, null, 200);
        verify(service).list(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), 200);
    }
}
