package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunState;
import com.securityexpert.nexus.ui2.service.discovery.DiscoveryRunService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** PO 2026-11-11: aiview starts discovery runs and reads a run as state and counts only, never names or addresses. */
class DiscoveryControllerReplayViewerTest {

    private static final String SYNTHETIC_NAME = "synthetic-host-name";
    private static final String SYNTHETIC_ADDRESS = "192.0.2.77";

    private DiscoveryController controller(DiscoveryRunService service) {
        return new DiscoveryController(service,
                mock(com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService.class),
                mock(com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointPanCertTrustService.class));
    }

    private DiscoveryRunService serviceWithOneCandidate() {
        var run = new DiscoveryRun("run-1", "check_point", SYNTHETIC_ADDRESS, "cred-1", "actor", DiscoveryRunState.FINISHED,
                Optional.of("job-1"), Optional.of(Instant.parse("2026-11-11T01:00:00Z")),
                Optional.of(Instant.parse("2026-11-11T01:01:00Z")), Optional.of(Map.of("candidates", 1)));
        var candidate = new DiscoveryCandidateRecord("cand-1", "run-1", "check_point", "stable-1", Optional.empty(), "gateway",
                SYNTHETIC_NAME, Optional.of(SYNTHETIC_ADDRESS), Optional.of(SYNTHETIC_ADDRESS), Optional.empty(),
                Optional.empty(), Optional.of("model-x"), Optional.of("R82"), Optional.of("reachable"), true, Optional.empty());
        var service = mock(DiscoveryRunService.class);
        when(service.read(eq("run-1"), anyString())).thenReturn(new DiscoveryRunService.ReadOutcome.Found(run, List.of(candidate),
                Map.of("cand-1", new DiscoveryRunService.CandidateRegistryState("new", Optional.empty()))));
        return service;
    }

    private MockHttpServletRequest request(boolean replayViewer) {
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor");
        if (replayViewer) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.TRUE);
        return request;
    }

    @Test
    void replayViewerReadsStateAndCountsOnlyNeverNamesOrAddresses() {
        var response = controller(serviceWithOneCandidate()).read("run-1", request(true));
        var body = response.getBody();
        assertEquals("FINISHED", body.get("state"));
        assertEquals(Map.of("candidates", 1), body.get("outcome_summary"));
        assertEquals(1, body.get("candidate_count"));
        assertEquals(List.of(), body.get("candidates"));
        assertEquals(Boolean.TRUE, body.get("candidates_withheld"));
        String text = body.toString();
        assertFalse(text.contains(SYNTHETIC_NAME));
        assertFalse(text.contains(SYNTHETIC_ADDRESS));
    }

    @Test
    void administratorStillReadsTheFullCandidateRows() {
        var body = controller(serviceWithOneCandidate()).read("run-1", request(false)).getBody();
        assertNull(body.get("candidates_withheld"));
        var candidates = (List<?>) body.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals(SYNTHETIC_NAME, ((Map<?, ?>) candidates.get(0)).get("display_name"));
    }
}
