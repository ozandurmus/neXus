package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;

class CpFailoverSecurityRouteTest {
    @Test
    void everyControllerGetMappingIsRegisteredUnderBothPrefixes() {
        var prefixes = CpFailoverController.class.getAnnotation(RequestMapping.class).value();
        assertEquals(Set.of("/api/v2/cp-failover", "/api/v2/pan-failover"), Set.of(prefixes));
        int registered = 0;
        for (var method : CpFailoverController.class.getDeclaredMethods()) {
            var mapping = method.getAnnotation(GetMapping.class);
            if (mapping == null) continue;
            for (String prefix : prefixes) {
                for (String path : mapping.value()) {
                    String route = "GET " + prefix + path.replaceAll("\\{[^}]+}", "*");
                    assertEquals(ActionRegistry.CP_FAILOVER_READ,
                            SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route), route);
                    registered++;
                }
            }
        }
        assertEquals(12, registered);
        for (String path : List.of("/approvals", "/approvals/*/revoke", "/runs")) {
            assertNull(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/pan-failover" + path));
        }
    }

    @Test
    void replayViewerCanReadPanUnitsButCannotStartPanRuns() throws Exception {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var service = mock(CpFailoverService.class);
        String cookie = "synthetic-replay-session", actor = "synthetic-replay-actor";
        Instant now = Instant.now();
        var session = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE,
                now, now, now.plusSeconds(3600), now.plusSeconds(7200),
                Optional.empty(), Optional.empty(), Optional.empty());
        when(sessions.findBySessionId(eq(session.sessionId()), any(Instant.class))).thenReturn(Optional.of(session));
        // AIView holds viewer and replay_viewer: the former admits reads, the latter selects masking.
        var roles = Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER);
        when(rbac.evaluateAny(eq(actor), anySet(), any(Instant.class))).thenAnswer(call -> {
            Set<String> required = call.getArgument(1);
            return new RbacEvaluator.Decision(required.stream().anyMatch(roles::contains)
                    ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                    Optional.of("test"), Optional.empty(), Optional.empty());
        });
        when(rbac.evaluate(eq(actor), eq(Optional.of(RoleToken.REPLAY_VIEWER)), any(Instant.class)))
                .thenReturn(new RbacEvaluator.Decision(AuthzOutcome.PERMITTED,
                        Optional.of("test"), Optional.empty(), Optional.empty()));
        when(service.unitsForMember("opaque-member", actor, "palo_alto")).thenReturn(List.of());
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service))
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();

        String units = "/api/v2/pan-failover/units";
        var result = mvc.perform(get(units).servletPath(units).param("memberDeviceId", "opaque-member")
                        .cookie(new Cookie("ui2_session", cookie)))
                .andExpect(status().isOk()).andExpect(content().json("[]")).andReturn();
        assertEquals(true, result.getRequest().getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
        verify(service).unitsForMember("opaque-member", actor, "palo_alto");

        String runs = "/api/v2/pan-failover/runs";
        mvc.perform(post(runs).servletPath(runs).cookie(new Cookie("ui2_session", cookie))
                        .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid")
                        .contentType("application/json").content("{\"clusterId\":\"opaque-cluster\",\"unitId\":\"opaque-unit\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACTION_MAPPING_REQUIRED"));
        verifyNoMoreInteractions(service);
    }
}
