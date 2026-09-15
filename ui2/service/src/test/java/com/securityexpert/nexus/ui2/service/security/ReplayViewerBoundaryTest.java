package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.DeviceRegistrationController;
import com.securityexpert.nexus.ui2.service.api.ReplaySessionController;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.device.DeviceQueryService;

class ReplayViewerBoundaryTest {
    @org.springframework.web.bind.annotation.RestController
    static class UnclassifiedSurface {
        @org.springframework.web.bind.annotation.RequestMapping("/**")
        public Map<String, Object> response() {
            return Map.of("unclassified", RAW_SECRET);
        }
    }

    private static final String RAW_ID = "synthetic-device-0007";
    private static final String RAW_HOST = "synthetic-private-host";
    private static final String RAW_JOB = "synthetic-job-0007";
    private static final String RAW_SECRET = "synthetic-secret-path-free-text";

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void adversarialFieldsNeverReachSerializedPayloadAndEqualitySurvivesSessions() throws Exception {
        byte[] key = key();
        ReplayProjector first = new ReplayProjector(key);
        ReplayProjector second = new ReplayProjector(key);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("device_id", RAW_ID);
        device.put("hostname", RAW_HOST);
        device.put("cluster_member_ref", RAW_ID);
        device.put("role", "FIREWALL");
        device.put("vendor_hint", "CP");
        device.put("disabled", false);
        device.put("facts", Map.of("hostname", RAW_HOST, "model", "synthetic-model",
                "unclassified", RAW_SECRET));
        device.put("job", Map.of("job_id", RAW_JOB, "state", "TERMINAL", "outcome", "SUCCESS",
                "terminal_reason", RAW_SECRET, "path", RAW_SECRET));
        for (String field : List.of("peer_follow_outcome", "peer_follow_reason", "identity_mismatch_state",
                "address", "secret", "path", "unclassified")) {
            device.put(field, RAW_SECRET);
        }
        Map<?, ?> detail = (Map<?, ?>) first.project(device);
        Map<?, ?> list = (Map<?, ?>) second.project(Map.of("devices", List.of(device), "unexpected", RAW_SECRET));
        Map<?, ?> summary = (Map<?, ?>) ((List<?>) list.get("devices")).getFirst();
        assertEquals(detail.get("device_id"), summary.get("device_id"));
        assertEquals(detail.get("device_id"), detail.get("hostname"));
        assertEquals(detail.get("device_id"), detail.get("cluster_member_ref"));
        assertEquals(detail.get("device_id"), ((Map<?, ?>) detail.get("facts")).get("hostname"));
        assertNotEquals(first.pseudonym("device", RAW_ID), first.pseudonym("job", RAW_ID));
        assertNotEquals(first.pseudonym("device", "0007"), first.pseudonym("device", "7"));
        String json = new ObjectMapper().writeValueAsString(List.of(detail, list));
        for (String raw : List.of(RAW_ID, RAW_HOST, RAW_JOB, RAW_SECRET)) {
            assertFalse(json.contains(raw), "raw synthetic sentinel leaked");
        }
        assertFalse(json.contains("terminal_reason"));
        assertFalse(json.contains("unclassified"));
        assertEquals(false, detail.get("disabled"));
        assertThrows(IllegalArgumentException.class, () -> first.project(Map.of("device_id", RAW_ID, "model", Map.of("secret", RAW_SECRET))));
        assertThrows(IllegalArgumentException.class, () -> first.project(Map.of("error", RAW_SECRET)));
    }

    private record Harness(MockMvc mvc, GateChain chain, ReplaySessionRepository replay,
            DeviceQueryService query, SessionRepository sessions) { }

    private static Harness harness(boolean active) {
        GateChain chain = mock(GateChain.class);
        ReplaySessionRepository replay = mock(ReplaySessionRepository.class);
        DeviceQueryService query = mock(DeviceQueryService.class);
        SessionRepository sessions = mock(SessionRepository.class);
        byte[] key = key();
        when(replay.isReplay(SessionHasher.hash("synthetic-cookie"))).thenReturn(active);
        when(replay.projectionKey()).thenReturn(key);
        // Root-like PERMITTED on every action: the session boundary must still refuse.
        when(chain.evaluate(any(), any())).thenReturn(new GateOutcome.Proceed("synthetic-session", "synthetic-real-actor"));
        ReplayViewerBoundary boundary = new ReplayViewerBoundary(replay, query);
        DeviceRegistrationController controller = new DeviceRegistrationController(mock(DeviceAddSingleService.class), query);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller, new ReplaySessionController(replay, sessions), new UnclassifiedSurface())
                .setControllerAdvice(boundary)
                .addInterceptors(new GateChainInterceptor(chain, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE, boundary)).build();
        return new Harness(mvc, chain, replay, query, sessions);
    }

    @Test
    void actualListAndDetailRoutesProjectAndResolveOnlyOpaqueLabels() throws Exception {
        Harness h = harness(true);
        DeviceSummaryRecord summary = mock(DeviceSummaryRecord.class);
        when(summary.deviceId()).thenReturn(RAW_ID);
        when(summary.role()).thenReturn("FIREWALL");
        when(summary.vendorHint()).thenReturn("CP");
        when(summary.enrollmentState()).thenReturn(com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED);
        when(summary.observedHostname()).thenReturn(Optional.of(RAW_HOST));
        when(summary.observedModel()).thenReturn(Optional.empty());
        when(summary.observedSoftwareVersion()).thenReturn(Optional.empty());
        when(summary.observedHaRole()).thenReturn(Optional.empty());
        when(summary.clusterMemberRef()).thenReturn(Optional.of(RAW_ID));
        when(h.query().listDevices()).thenReturn(List.of(summary));
        DeviceRecord device = mock(DeviceRecord.class);
        when(device.deviceId()).thenReturn(RAW_ID);
        when(device.enrollmentState()).thenReturn(com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED);
        DeviceConfirmFacts facts = new DeviceConfirmFacts(Optional.of(RAW_HOST), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(RAW_SECRET), Optional.empty(), RAW_SECRET, Optional.empty(),
                Optional.empty(), Optional.of(RAW_ID), Optional.empty(), RAW_SECRET, Optional.of(RAW_SECRET));
        JobRow job = new JobRow(RAW_JOB, "capability", RAW_ID, "CLASS_0_READ", "TERMINAL", null,
                0, "SUCCESS", RAW_SECRET, "DEVICE", RAW_ID);
        when(h.query().deviceDetail(RAW_ID)).thenReturn(new DeviceQueryService.DetailOutcome.Found(device, facts, Optional.of(job)));
        Cookie cookie = new Cookie("ui2_session", "synthetic-cookie");
        String list = h.mvc().perform(get("/devices").servletPath("/devices").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String label = new ObjectMapper().readTree(list).get("devices").get(0).get("device_id").asText();
        String detail = h.mvc().perform(get("/devices/{id}", label).servletPath("/devices/" + label).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value(label)).andExpect(jsonPath("$.facts.hostname").value(label))
                .andReturn().getResponse().getContentAsString();
        for (String raw : List.of(RAW_ID, RAW_HOST, RAW_JOB, RAW_SECRET)) {
            assertFalse(list.contains(raw));
            assertFalse(detail.contains(raw));
        }
        h.mvc().perform(get("/devices/{id}", RAW_ID).servletPath("/devices/" + RAW_ID).cookie(cookie)).andExpect(status().isForbidden());
        verify(h.query(), times(1)).deviceDetail(RAW_ID);
        h.mvc().perform(get("/devices/{id}", label).servletPath("/devices/" + label).param("projection", "raw").header("X-Replay-Viewer", "false").cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.device_id").value(label));
    }

    @Test
    void allOtherRegisteredAndUnmappedRoutesRefuseEvenWhenE4PermitsRoot() throws Exception {
        Harness h = harness(true);
        Cookie cookie = new Cookie("ui2_session", "synthetic-cookie");
        for (String route : SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet()) {
            if (route.equals("GET /devices") || route.equals("GET /devices/*")
                    || route.equals("POST /session/replay/deactivate")) {
                continue;
            }
            String[] parts = route.split(" ", 2);
            h.mvc().perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1].replace("*", "synthetic"))
                    .servletPath(parts[1].replace("*", "synthetic")).cookie(cookie)).andExpect(status().isForbidden());
        }
        for (String path : List.of("/session/status", "/login", "/future/export", "/future/search", "/healthz")) {
            h.mvc().perform(get(path).servletPath(path).cookie(cookie)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.reason_code").value("replay_surface_refused"));
        }
        verifyNoInteractions(h.query());
        verify(h.replay(), never()).activate(any(), any(), any(), any());
        h.mvc().perform(post("/session/replay/deactivate").servletPath("/session/replay/deactivate").cookie(cookie))
                .andExpect(status().isOk()).andExpect(cookie().maxAge("ui2_session", 0));
        verify(h.sessions()).revoke("synthetic-session", "synthetic-real-actor", ActionRegistry.REPLAY_DEACTIVATE);
    }

    @Test
    void ordinarySessionCannotSelectProjectionAndActivationRotatesCookie() throws Exception {
        Harness h = harness(false);
        when(h.query().listDevices()).thenReturn(List.of());
        h.mvc().perform(get("/devices").servletPath("/devices").header("X-Replay-Viewer", "true").param("replay_viewer", "true")
                .cookie(new Cookie("ui2_session", "synthetic-cookie"))).andExpect(status().isOk());
        verify(h.replay(), never()).projectionKey();
        h.mvc().perform(post("/session/replay/activate").servletPath("/session/replay/activate").cookie(new Cookie("ui2_session", "synthetic-cookie")))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("ui2_session", true))
                .andExpect(cookie().secure("ui2_session", true));
        verify(h.replay()).activate(eq("synthetic-session"), argThat(id -> !id.equals("synthetic-session")),
                eq("synthetic-real-actor"), any());
    }

    @Test
    void custodyAndActivationFailuresRefuseWithoutEchoingFailureDetails() throws Exception {
        Harness active = harness(true);
        when(active.replay().projectionKey()).thenThrow(new IllegalStateException(RAW_SECRET));
        active.mvc().perform(get("/devices").servletPath("/devices").cookie(new Cookie("ui2_session", "synthetic-cookie")))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(RAW_SECRET))));
        when(active.replay().isReplay(any())).thenThrow(new IllegalStateException(RAW_SECRET));
        active.mvc().perform(get("/devices").servletPath("/devices").cookie(new Cookie("ui2_session", "synthetic-cookie")))
                .andExpect(status().isForbidden());
        Harness ordinary = harness(false);
        doThrow(new IllegalStateException(RAW_SECRET)).when(ordinary.replay()).activate(any(), any(), any(), any());
        ordinary.mvc().perform(post("/session/replay/activate").servletPath("/session/replay/activate")
                .cookie(new Cookie("ui2_session", "synthetic-cookie")))
                .andExpect(status().isConflict()).andExpect(cookie().doesNotExist("ui2_session"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(RAW_SECRET))));
    }

    @Test
    void realGateRequiresOnlyReplayTokenAndPreservesCsrfAndExpiryEnforcement() {
        Instant now = Instant.now();
        SessionRepository sessions = mock(SessionRepository.class);
        SessionRecord session = new SessionRecord(SessionHasher.hash("synthetic-cookie"), "synthetic-actor", "synthetic-csrf",
                SessionState.ACTIVE, now, now, now.plusSeconds(60), now.plusSeconds(120), Optional.empty(), Optional.empty(), Optional.empty());
        when(sessions.findBySessionId(session.sessionId(), now)).thenReturn(Optional.of(session));
        RbacEvaluator rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(eq("synthetic-actor"), eq(Optional.of(RoleToken.REPLAY_VIEWER)), eq(now)))
                .thenReturn(new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("ui2_local"), Optional.empty(), Optional.empty()));
        GateChain chain = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        GateRequest request = new GateRequest("POST", Optional.of("synthetic-cookie"), Optional.of("synthetic-csrf"),
                Optional.of("https://synthetic.example"), ActionRegistry.REPLAY_ACTIVATE, Optional.empty());
        assertInstanceOf(GateOutcome.Proceed.class, chain.evaluate(request, now));
        verify(rbac).evaluate("synthetic-actor", Optional.of(RoleToken.REPLAY_VIEWER), now);
        assertInstanceOf(GateOutcome.Refused.class, chain.evaluate(new GateRequest("POST", request.sessionCookieRawValue(),
                Optional.empty(), request.origin(), request.actionId(), Optional.empty()), now));
        when(rbac.evaluate(any(), any(), any())).thenReturn(new RbacEvaluator.Decision(AuthzOutcome.DENIED,
                Optional.of("ui2_local"), Optional.of("actor_not_in_required_group"), Optional.empty()));
        assertInstanceOf(GateOutcome.Refused.class, chain.evaluate(request, now));
    }
}
