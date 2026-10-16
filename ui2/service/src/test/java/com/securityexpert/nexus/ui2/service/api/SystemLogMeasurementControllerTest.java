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
}
