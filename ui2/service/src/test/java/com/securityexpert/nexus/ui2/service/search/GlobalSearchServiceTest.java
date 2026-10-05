package com.securityexpert.nexus.ui2.service.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

class GlobalSearchServiceTest {
    @Test
    void maskedSessionCannotConfirmRawNameButFindsDisplayedPseudonym() {
        byte[] key = "synthetic-search-test-key-32-bytes".getBytes(StandardCharsets.UTF_8);
        var pseudonymizer = new TopologyNamePseudonymizer(key);
        var masking = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), pseudonymizer);
        String raw = "FW-TANGO-04";
        Map<String, Object> visible = GlobalSearchService.visibleIdentity(
                Map.of("hostname", raw, "device_id", "opaque-001"), masking, true);
        String pseudonym = (String) visible.get("hostname");
        assertFalse(GlobalSearchService.matches(raw, visible, "device_id"));
        assertTrue(GlobalSearchService.matches(pseudonym, visible, "device_id"));
        assertTrue(GlobalSearchService.matches(raw,
                GlobalSearchService.visibleIdentity(Map.of("hostname", raw), masking, false)));
    }

    @Test
    void ipSearchProjectionPassesTheHttpFunnelExactlyOnceAndFailsClosedIfMissing() {
        byte[] key = "synthetic-search-test-key-32-bytes".getBytes(StandardCharsets.UTF_8);
        var masking = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));
        var projected = GlobalSearchService.visibleIdentity(Map.of("hostname", "Synthetic gateway",
            "destination", "192.0.2.0/24", "virtual_system", "Synthetic context"), masking, true);
        var body = Map.<String, Object>of("routes", List.of(projected));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var servletRequest = new org.springframework.http.server.ServletServerHttpRequest(request);
        var response = new org.springframework.http.server.ServletServerHttpResponse(new org.springframework.mock.web.MockHttpServletResponse());
        assertSame(body, masking.beforeBodyWrite(new IpSearchResponse(body, true), null, null, null, servletRequest, response));
        assertEquals("true", response.getHeaders().getFirst(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER));
        assertFalse(body.toString().contains("192.0.2."));
        assertFalse(body.toString().contains("Synthetic"));
        assertThrows(IllegalStateException.class, () -> masking.beforeBodyWrite(new IpSearchResponse(body, false), null, null, null, servletRequest, response));
    }

    @Test
    void limitsAcrossGroups() {
        var hit = Map.<String, Object>of("label", "match");
        var result = GlobalSearchService.limited(List.of(hit, hit), List.of(hit), List.of(hit), 3);
        assertEquals(1, ((List<?>) result.get("devices")).size());
        assertEquals(1, ((List<?>) result.get("settings")).size());
        assertEquals(1, ((List<?>) result.get("evidence")).size());
    }
}
