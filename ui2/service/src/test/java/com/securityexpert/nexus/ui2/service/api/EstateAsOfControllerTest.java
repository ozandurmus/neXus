package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.service.estate.EstateAsOfResponse;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.InvalidMomentException;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Route-level tests for {@code GET /api/v2/estate/as-of}. The service is mocked (its evidence store is package-private),
 * so these tests pin what the controller passes to the service, how it maps the moment parameter to status codes, and
 * that it returns the service result without re-labelling it. Synthetic data only (FW-TANGO-04 style labels, 192.0.2.x).
 */
class EstateAsOfControllerTest {

    private static final Instant AT = Instant.parse("2026-11-12T02:00:00Z");

    private EstateAsOfService service;
    private EstateAsOfController controller;

    @BeforeEach
    void setUp() {
        service = mock(EstateAsOfService.class);
        controller = new EstateAsOfController(service);
    }

    @Test
    void passesParsedMomentAndNonMaskedFlagToServiceUnchanged() {
        when(service.asOf(eq(AT), eq(false))).thenReturn(new EstateAsOfResponse(false, Map.of("as_of", "2026-11-12T02:00:00Z")));

        ResponseEntity<EstateAsOfResponse> response = controller.asOf("2026-11-12T02:00:00Z", new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service).asOf(AT, false);
        assertEquals("2026-11-12T02:00:00Z", response.getBody().body().get("as_of"));
    }

    @Test
    void offsetTimestampReachesServiceAsTheSameInstant() {
        when(service.asOf(any(), eq(false))).thenReturn(new EstateAsOfResponse(false, Map.of()));

        controller.asOf("2026-11-12T05:00:00+03:00", new MockHttpServletRequest());

        verify(service).asOf(AT, false);
    }

    @Test
    void replayViewerRequestAsksServiceForMaskedProjection() {
        MockHttpServletRequest aiview = new MockHttpServletRequest();
        aiview.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.TRUE);
        when(service.asOf(eq(AT), eq(true))).thenReturn(new EstateAsOfResponse(true, Map.of()));

        ResponseEntity<EstateAsOfResponse> response = controller.asOf("2026-11-12T02:00:00Z", aiview);

        verify(service).asOf(AT, true);
        assertTrue(response.getBody().masked());
    }

    @Test
    void responseCarriesNoCacheHeadersAndVaryOnCookie() {
        when(service.asOf(any(), eq(false))).thenReturn(new EstateAsOfResponse(false, Map.of()));

        ResponseEntity<EstateAsOfResponse> response = controller.asOf("2026-11-12T02:00:00Z", new MockHttpServletRequest());

        assertTrue(response.getHeaders().getCacheControl().contains("no-cache"));
        assertTrue(response.getHeaders().getCacheControl().contains("private"));
        assertEquals("Cookie", response.getHeaders().getFirst("Vary"));
    }

    @Test
    void missingOrMalformedMomentThrowsInvalidMomentThatTheControllerMapsToBadRequest() {
        for (String bad : new String[] {null, "", "   ", "not-a-timestamp", "2026-11-12 02:00:00"}) {
            InvalidMomentException ex = assertThrows(InvalidMomentException.class,
                    () -> controller.asOf(bad, new MockHttpServletRequest()));
            assertEquals("TIME_INVALID", ex.code());

            ResponseEntity<Map<String, Object>> error = controller.invalidMoment(ex);
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
            assertEquals("TIME_INVALID", error.getBody().get("error"));
            assertEquals("TIME_INVALID", error.getBody().get("code"));
            assertNotNull(error.getBody().get("message"));
            assertTrue(error.getHeaders().getCacheControl().contains("no-cache"));
        }
        verify(service, never()).asOf(any(), anyBoolean());
    }

    @Test
    void unknownServiceContentIsReturnedAsUnknownNotAsHealthy() {
        // The service reports UNKNOWN where evidence does not reach the moment; the controller must pass that through.
        Map<String, Object> failover = Map.of("outcome", "UNKNOWN", "verdict", "UNKNOWN", "reason", "NO_READINESS_BEFORE_AT");
        Map<String, Object> unit = Map.of("device_id", "dev-fw-tango-01", "failover", failover);
        when(service.asOf(eq(AT), eq(false))).thenReturn(new EstateAsOfResponse(false, Map.of("units", List.of(unit))));

        ResponseEntity<EstateAsOfResponse> response = controller.asOf("2026-11-12T02:00:00Z", new MockHttpServletRequest());

        @SuppressWarnings("unchecked")
        Map<String, Object> returned = (Map<String, Object>) ((List<Map<String, Object>>) response.getBody().body().get("units"))
                .get(0).get("failover");
        assertEquals("UNKNOWN", returned.get("outcome"));
        assertEquals("UNKNOWN", returned.get("verdict"));
        assertFalse(List.of("READY", "OK", "PASS", "HEALTHY").contains(returned.get("outcome")));
        assertFalse(List.of("READY", "OK", "PASS", "HEALTHY").contains(returned.get("verdict")));
    }
}
