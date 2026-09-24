package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.securityexpert.nexus.ui2.service.overview.OverviewService;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** 2026-09-24: under aiview the Overview's device, cluster and HA-pair names are masked, and never browser-cached. */
class OverviewControllerMaskingTest {

    private static Map<String, Object> body() {
        Map<String, Object> changeRow = new LinkedHashMap<>(Map.of("device_id", "d1", "label", "REAL-GW-01"));
        changeRow.put("label_cluster", "REALCLUSTER");
        Map<String, Object> diffRow = new LinkedHashMap<>(Map.of("cluster_ref", "000111|000222", "diff_section_count", 3));
        Map<String, Object> exceptions = new LinkedHashMap<>();
        exceptions.put("config_changes", new LinkedHashMap<>(Map.of("rows", new ArrayList<>(List.of(changeRow)))));
        exceptions.put("cluster_diff", new LinkedHashMap<>(Map.of("rows", new ArrayList<>(List.of(diffRow)),
                "all_refs", new ArrayList<>(List.of("000111|000222", "REALCLUSTER")))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exceptions", exceptions);
        return body;
    }

    @Test
    void aiviewSeesPseudonymsAndTheResponseIsNeverServedFromTheBrowserCache() {
        OverviewService service = Mockito.mock(OverviewService.class);
        Mockito.when(service.build()).thenAnswer(i -> body());
        OverviewController controller = new OverviewController(service,
                new TopologyNamePseudonymizer("k".repeat(32).getBytes(StandardCharsets.UTF_8)));
        HttpServletRequest aiview = Mockito.mock(HttpServletRequest.class);
        Mockito.when(aiview.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(Boolean.TRUE);

        var response = controller.overview(aiview);
        String text = String.valueOf(response.getBody());
        for (String real : List.of("REAL-GW-01", "REALCLUSTER", "000111", "000222")) {
            assertFalse(text.contains(real), real + " leaked: " + text);
        }
        assertEquals(Boolean.TRUE, response.getBody().get("masked"));
        String cacheControl = response.getHeaders().getCacheControl();
        assertTrue(cacheControl != null && cacheControl.contains("no-cache") && !cacheControl.contains("max-age"), cacheControl);
    }
}
