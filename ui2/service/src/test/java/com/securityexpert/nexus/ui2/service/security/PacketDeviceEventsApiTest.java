package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.PacketDeviceEventsController;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsResponse;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Packet Analysis > Device events through the real gate chain: case-read RBAC (viewer refused, unauthenticated
 * refused), the masked projection requested for the aiview persona only and passed through by the body advice with the
 * masked header, the system_log_read decision handed to the service, the window/finding parameters and the refusal
 * body. Opaque ids and example values only.
 */
class PacketDeviceEventsApiTest {
    private static final String CASE = "11111111-2222-4333-8444-555555555555";
    private static final String PATH = "/api/v2/packet-analysis/cases/" + CASE + "/device-events";

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final PacketDeviceEventsService service = mock(PacketDeviceEventsService.class);
    private final Map<String, Set<String>> roles = Map.of(
            "synthetic-operator", Set.of(RoleToken.OPERATOR),
            "synthetic-viewer", Set.of(RoleToken.VIEWER),
            "synthetic-aiview", Set.of(RoleToken.REPLAY_VIEWER));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        for (String actor : roles.keySet()) {
            var session = new SessionRecord(SessionHasher.hash("cookie-" + actor), actor, "csrf-" + actor, SessionState.ACTIVE,
                    now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty(), false);
            when(sessions.findBySessionId(eq(session.sessionId()), any())).thenReturn(Optional.of(session));
        }
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        when(rbac.evaluate(anyString(), any(), any())).thenAnswer(call -> {
            Optional<?> token = call.getArgument(1);
            return token.isPresent() && roles.getOrDefault(call.getArgument(0), Set.of()).contains(token.get()) ? permitted : denied;
        });
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call -> {
            Set<?> wanted = call.getArgument(1);
            return roles.getOrDefault(call.getArgument(0), Set.of()).stream().anyMatch(wanted::contains) ? permitted : denied;
        });
        when(service.deviceEvents(any(), anyBoolean(), any())).thenAnswer(call -> {
            boolean masked = call.getArgument(1);
            PacketDeviceEventsService.Permissions permissions = call.getArgument(2);
            return new PacketDeviceEventsResponse(masked, Map.of("case_id", CASE, "masked", masked,
                    "system_logs", permissions.systemLogs(), "devices", List.of(Map.of("label", masked ? "FW-TANGO-04" : "edge-fw"))));
        });
        mvc = mvc(new ActionRegistry());
    }

    /** The route through the gate chain; {@code controllerActions} is the registry the controller asks for system_log_read. */
    private MockMvc mvc(ActionRegistry controllerActions) {
        var names = new TopologyNamePseudonymizer(new byte[32]);
        var addresses = new SubnetPreservingIpMasker(new byte[32]);
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        return MockMvcBuilders.standaloneSetup(new PacketDeviceEventsController(service, controllerActions, rbac))
                .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(addresses, names))
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
    }

    private static Cookie cookie(String actor) {
        return new Cookie("ui2_session", "cookie-" + actor);
    }

    @Test
    void theRouteIsGatedLikeTheCaseRead() throws Exception {
        assertEquals(ActionRegistry.PACKET_ANALYSIS_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/packet-analysis/cases/*/device-events"));
        mvc.perform(get(PATH).servletPath(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).servletPath(PATH).cookie(cookie("synthetic-viewer"))).andExpect(status().isForbidden());
        verify(service, never()).deviceEvents(any(), anyBoolean(), any());
    }

    @Test
    void anOperatorGetsThePlainProjectionWithSystemLogFactsAndNoStore() throws Exception {
        String body = mvc.perform(get(PATH).servletPath(PATH).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.masked").value(false))
                .andExpect(jsonPath("$.system_logs").value(true))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("edge-fw"));
        verify(service).deviceEvents(eq(new PacketDeviceEventsService.Request(CASE, "", 0)), eq(false),
                eq(new PacketDeviceEventsService.Permissions(true)));
    }

    @Test
    void aiviewGetsTheMaskedProjectionMarkedByTheMaskedHeader() throws Exception {
        var result = mvc.perform(get(PATH).servletPath(PATH).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk())
                .andExpect(header().string(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER, "true"))
                .andExpect(jsonPath("$.masked").value(true))
                .andExpect(jsonPath("$.system_logs").value(true))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("edge-fw"), "the label reaches aiview only as its pseudonym");
        assertTrue(body.contains("FW-TANGO-04"));
        verify(service).deviceEvents(any(), eq(true), eq(new PacketDeviceEventsService.Permissions(true)));
    }

    @Test
    void aCaseReaderWithoutSystemLogReadIsHandedNotPermitted() throws Exception {
        // A deployment whose system_log_read grant the operator does not hold: the case read passes, the log facts do not.
        var narrowed = new ActionRegistry();
        narrowed.register(new ActionDescriptor(ActionRegistry.SYSTEM_LOG_READ, true, Optional.of("custom_log_reader")));
        mvc(narrowed).perform(get(PATH).servletPath(PATH).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system_logs").value(false));
        verify(service).deviceEvents(any(), eq(false), eq(new PacketDeviceEventsService.Permissions(false)));
    }

    @Test
    void theFindingAndWindowParametersReachTheServiceAndARefusalKeepsItsStatusAndCode() throws Exception {
        mvc.perform(get(PATH).servletPath(PATH).cookie(cookie("synthetic-operator"))
                .param("finding", "forward:p1:p2:impactful_loss").param("window_seconds", "120")).andExpect(status().isOk());
        verify(service).deviceEvents(eq(new PacketDeviceEventsService.Request(CASE, "forward:p1:p2:impactful_loss", 120)), eq(false), any());

        var refusing = mock(PacketDeviceEventsService.class);
        when(refusing.deviceEvents(any(), anyBoolean(), any()))
                .thenThrow(new PacketDeviceEventsService.RefusedException(404, "FINDING_NOT_FOUND", "No such finding in the stored report"));
        var plain = MockMvcBuilders.standaloneSetup(new PacketDeviceEventsController(refusing, new ActionRegistry(), rbac)).build();
        plain.perform(get(PATH).param("finding", "no-such"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("FINDING_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No such finding in the stored report"));
    }
}
