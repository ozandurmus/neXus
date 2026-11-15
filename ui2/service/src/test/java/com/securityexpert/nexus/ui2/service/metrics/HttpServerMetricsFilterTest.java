package com.securityexpert.nexus.ui2.service.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class HttpServerMetricsFilterTest {

    @Test
    void timesAreLabelledByTheRoutePatternNeverTheRawPath() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new HttpServerMetricsFilter(registry);
        var request = new MockHttpServletRequest("GET", "/api/v2/devices/fw-lab-01.example.test/inventory");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v2/devices/{id}/inventory");
        var response = new MockHttpServletResponse();
        response.setStatus(200);
        filter.doFilter(request, response, new MockFilterChain());
        var timer = registry.get("http.server.requests").tag("uri", "/api/v2/devices/{id}/inventory").timer();
        assertEquals(1, timer.count());
        assertEquals("SUCCESS", timer.getId().getTag("outcome"));
        assertEquals("200", timer.getId().getTag("status"));
        assertNull(registry.find("http.server.requests").tag("uri", "/api/v2/devices/fw-lab-01.example.test/inventory").timer());
    }

    @Test
    void anUnmatchedRequestAndAnUnknownMethodAreFoldedIntoFixedValues() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new HttpServerMetricsFilter(registry);
        var request = new MockHttpServletRequest("PROPFIND", "/actuator/prometheus");
        var response = new MockHttpServletResponse();
        response.setStatus(404);
        filter.doFilter(request, response, new MockFilterChain());
        assertNotNull(registry.find("http.server.requests").tag("uri", "UNMAPPED").tag("method", "OTHER")
                .tag("outcome", "CLIENT_ERROR").timer());
    }
}
