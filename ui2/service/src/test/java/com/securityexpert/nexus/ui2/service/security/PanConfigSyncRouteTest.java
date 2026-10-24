package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.failover.PanConfigSyncService;

/** PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §3.1 R1-R6 through the real gate chain: route, role, masked view, body shape. */
class PanConfigSyncRouteTest {
    private static final String COOKIE = "synthetic-session", ACTOR = "synthetic-actor", CSRF = "synthetic-csrf";
    private static final String PATH = "/api/v2/pan-failover/units/unit-a/config-sync";
    private static final String BODY = "{\"clusterId\":\"cluster-a\",\"sourceDeviceId\":\"member-a\",\"targetDeviceId\":\"member-b\"}";

    private record Fixture(MockMvc mvc, PanConfigSyncService sync, CpFailoverService failover) {}

    private static Fixture fixture(Set<String> roles) {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var failover = mock(CpFailoverService.class);
        var sync = mock(PanConfigSyncService.class);
        Instant now = Instant.now();
        var session = new SessionRecord(SessionHasher.hash(COOKIE), ACTOR, CSRF, SessionState.ACTIVE,
            now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty());
        when(sessions.findBySessionId(eq(session.sessionId()), any(Instant.class))).thenReturn(Optional.of(session));
        when(rbac.evaluateAny(eq(ACTOR), anySet(), any(Instant.class))).thenAnswer(call -> {
            Set<String> required = call.getArgument(1);
            return new RbacEvaluator.Decision(required.stream().anyMatch(roles::contains)
                ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        });
        when(rbac.evaluate(eq(ACTOR), any(), any(Instant.class))).thenAnswer(call -> {
            Optional<String> role = call.getArgument(1);
            return new RbacEvaluator.Decision(role.filter(roles::contains).isPresent()
                ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        });
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(failover, null, null, sync))
            .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        return new Fixture(mvc, sync, failover);
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(String path, String body) {
        return post(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)).header("X-CSRF-Token", CSRF)
            .header("Origin", "https://example.invalid").contentType("application/json").content(body);
    }
    private static JooqPanConfigSyncRepository.ConfigSync queued() {
        return new JooqPanConfigSyncRepository.ConfigSync("sync-a", "CLS-ROMEO-01", "member-a", "member-b", "run-a", ACTOR,
            "security_admin", Instant.EPOCH, false, false, "job-a", "QUEUED", null, null, false, null, null, null, null);
    }

    @Test void theRouteIsPaloAltoOnlyAndAdministratorOnly() {
        assertEquals(ActionRegistry.PAN_FAILOVER_CONFIG_SYNC,
            SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/pan-failover/units/*/config-sync"));
        assertEquals(ActionRegistry.CP_FAILOVER_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/pan-failover/config-syncs/*"));
        assertNull(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/cp-failover/units/*/config-sync"));
        // Every failover GET is mapped under both prefixes; the Check Point read answers CONFIG_SYNC_NOT_SUPPORTED (below).
        assertEquals(ActionRegistry.CP_FAILOVER_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/cp-failover/config-syncs/*"));
        var action = new ActionRegistry().find(ActionRegistry.PAN_FAILOVER_CONFIG_SYNC).orElseThrow();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.OPERATION_ADMIN), action.requiredRoleTokens());
    }

    @Test void anAdministratorRequestsWithTheContractBodyOnly() throws Exception {
        for (String role : List.of(RoleToken.SECURITY_ADMIN, RoleToken.OPERATION_ADMIN)) {
            var f = fixture(Set.of(role));
            when(f.sync().request("cluster-a", "unit-a", "member-a", "member-b", ACTOR, "palo_alto")).thenReturn(queued());
            f.mvc().perform(write(PATH, BODY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.syncId").value("sync-a")).andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.sourceRef").value("member-a")).andExpect(jsonPath("$.targetRef").value("member-b"));
            verify(f.sync()).request("cluster-a", "unit-a", "member-a", "member-b", ACTOR, "palo_alto");
            // Any other field (a command, a target, an actor) is refused, not used or silently dropped; types are strict.
            for (String body : List.of("{\"clusterId\":\"cluster-a\",\"sourceDeviceId\":\"member-a\",\"targetDeviceId\":\"member-b\",\"cmd\":\"x\"}",
                    "{\"clusterId\":\"cluster-a\",\"sourceDeviceId\":\"member-a\"}",
                    "{\"clusterId\":\"cluster-a\",\"sourceDeviceId\":7,\"targetDeviceId\":\"member-b\"}", "{}"))
                f.mvc().perform(write(PATH, body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            when(f.sync().request("cluster-a", "unit-a", "member-a", "member-b", ACTOR, "palo_alto"))
                .thenThrow(new CpFailoverService.Refusal("READINESS_NOT_FRESH"));
            f.mvc().perform(write(PATH, BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READINESS_NOT_FRESH"));
        }
    }

    @Test void operatorViewerAiviewAndTheCheckPointPrefixAreRefusedBeforeTheService() throws Exception {
        for (Set<String> roles : List.of(Set.of(RoleToken.OPERATOR), Set.of(RoleToken.VIEWER),
                Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER), Set.of(RoleToken.REPLAY_VIEWER))) {
            var f = fixture(roles);
            f.mvc().perform(write(PATH, BODY)).andExpect(status().isForbidden());
            verifyNoInteractions(f.sync());
        }
        var admin = fixture(Set.of(RoleToken.SECURITY_ADMIN));
        admin.mvc().perform(write("/api/v2/cp-failover/units/unit-a/config-sync", BODY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACTION_MAPPING_REQUIRED"));
        verifyNoInteractions(admin.sync());
    }

    @Test void theMaskedViewIsRefusedEvenWithAnAdministratorRole() throws Exception {
        var f = fixture(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER));
        f.mvc().perform(write(PATH, BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_ROLE"));
        verifyNoInteractions(f.sync());
    }

    @Test void everyFailoverReaderSeesTheStatus() throws Exception {
        for (Set<String> roles : List.of(Set.of(RoleToken.OPERATOR), Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER))) {
            var f = fixture(roles);
            when(f.sync().find("sync-a", ACTOR)).thenReturn(Optional.of(queued()));
            String path = "/api/v2/pan-failover/config-syncs/sync-a";
            f.mvc().perform(get(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("QUEUED"));
        }
        var f = fixture(Set.of(RoleToken.SECURITY_ADMIN));
        String path = "/api/v2/cp-failover/config-syncs/sync-a";
        f.mvc().perform(get(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(PanConfigSyncService.NOT_SUPPORTED));
        verifyNoInteractions(f.sync());
    }
}
