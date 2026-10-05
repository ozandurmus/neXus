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
        status.put("domains", List.of(Map.of("containerId", "domain-1", "status", "REUSED", "rules", 7,
            "publishTime", "2026-10-03T00:00:00Z", "hitsCollectedAt", "2026-10-04T00:00:00Z")));
        status.put("domainsReused", 1); status.put("domainsCollected", 0); status.put("rulesReused", 7);
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
            .andExpect(jsonPath("$.readTimeoutSeconds").value(60))
            .andExpect(jsonPath("$.domains[0].status").value("REUSED"))
            .andExpect(jsonPath("$.domains[0].publishTime").value("2026-10-03T00:00:00Z"))
            .andExpect(jsonPath("$.domains[0].hitsCollectedAt").value("2026-10-04T00:00:00Z"))
            .andExpect(jsonPath("$.domainsReused").value(1)).andExpect(jsonPath("$.rulesReused").value(7));
    }
    @Test void modesDefaultToChangesAcceptFullForAIViewAndRejectUnknownBeforeAdmission() throws Exception {
        var collections = mock(PolicyCollectionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PolicyCollectionController(collections, mock(RbacEvaluator.class))).build();
        for (String body : List.of("{}", "{\"mode\":\"CHANGED_ONLY\"}", "{\"mode\":\"FULL\"}")) {
            var mode = body.contains("FULL") ? com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.Mode.FULL
                : com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.Mode.CHANGED_ONLY;
            when(collections.collect("source-1", "", "synthetic-actor", mode)).thenReturn(Optional.of("job-1"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/policy/sources/source-1/collect")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor")
                .requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("job-1"));
        }
        clearInvocations(collections);
        for (String mode : List.of("full", "", "INVALID"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/policy/sources/source-1/collect")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"mode\":\"" + mode + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_COLLECTION_MODE"));
        verifyNoInteractions(collections);
    }
}
