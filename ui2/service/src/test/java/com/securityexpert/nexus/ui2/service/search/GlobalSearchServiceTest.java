package com.securityexpert.nexus.ui2.service.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void limitsAcrossGroups() {
        var hit = Map.<String, Object>of("label", "match");
        var result = GlobalSearchService.limited(List.of(hit, hit), List.of(hit), List.of(hit), 3);
        assertEquals(1, ((List<?>) result.get("devices")).size());
        assertEquals(1, ((List<?>) result.get("settings")).size());
        assertEquals(1, ((List<?>) result.get("evidence")).size());
    }
}
