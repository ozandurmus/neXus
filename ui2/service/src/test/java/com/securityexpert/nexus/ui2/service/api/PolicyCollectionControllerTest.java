package com.securityexpert.nexus.ui2.service.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.securityexpert.nexus.ui2.service.policy.PolicyCollectionService;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;

class PolicyCollectionControllerTest {
    @Test void maskedStatusJsonPreservesProgressTimestampsAndGapCodes() throws Exception {
        var collections = mock(PolicyCollectionService.class);
        Map<String, Object> status = new LinkedHashMap<>(Map.of(
            "jobId", "job-1", "state", "COMPLETED", "outcome", "PARTIAL", "reason", "",
            "packagesDone", 14, "packagesTotal", 43, "rulesFetched", 2701, "gapUnits", 2,
            "unitFailureCodes", List.of("TIMEOUT", "HTTP_403")));
        status.put("startedAt", "2026-10-05T06:00:00Z");
        status.put("lastActivityAt", "2026-10-05T06:22:48Z");
        status.put("readTimeoutSeconds", 60);
        when(collections.status("job-1")).thenReturn(Optional.of(status));
        var mvc = MockMvcBuilders.standaloneSetup(new PolicyCollectionController(collections, mock(RbacEvaluator.class)))
            .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]),
                new TopologyNamePseudonymizer(new byte[32]))).build();
        mvc.perform(get("/api/v2/policy/collections/job-1")
                .requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
            .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "true"))
            .andExpect(jsonPath("$.state").value("COMPLETED"))
            .andExpect(jsonPath("$.outcome").value("PARTIAL"))
            .andExpect(jsonPath("$.reason").value(""))
            .andExpect(jsonPath("$.gapUnits").value(2))
            .andExpect(jsonPath("$.unitFailureCodes[0]").value("TIMEOUT"))
            .andExpect(jsonPath("$.unitFailureCodes[1]").value("HTTP_403"))
            .andExpect(jsonPath("$.packagesDone").value(14))
            .andExpect(jsonPath("$.packagesTotal").value(43))
            .andExpect(jsonPath("$.rulesFetched").value(2701))
            .andExpect(jsonPath("$.startedAt").value("2026-10-05T06:00:00Z"))
            .andExpect(jsonPath("$.lastActivityAt").value("2026-10-05T06:22:48Z"))
            .andExpect(jsonPath("$.readTimeoutSeconds").value(60));
    }
}
