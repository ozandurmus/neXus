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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.api.FailoverExecutionController;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.failover.FailoverExecutionService;

/** PO decision 2026-10-20 item 1 through the real gate chain: route, role, session actor, body shape. */
class CpFailoverIncidentRouteTest {
    private static final String COOKIE = "synthetic-session", ACTOR = "synthetic-actor", CSRF = "synthetic-csrf";
    private static final String RELEASE = "/api/v2/cp-failover/runs/run-a/incident/release";

    private record Fixture(MockMvc mvc, CpFailoverService service, FailoverExecutionService generic) {}

    private static Fixture fixture(Set<String> roles) {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var service = mock(CpFailoverService.class);
        var generic = mock(FailoverExecutionService.class);
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
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service), new FailoverExecutionController(generic))
            .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        return new Fixture(mvc, service, generic);
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(String path, String body) {
        return post(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)).header("X-CSRF-Token", CSRF)
            .header("Origin", "https://example.invalid").contentType("application/json").content(body);
    }

    @Test void releaseRouteIsSecurityAdminOnlyAndNeverUnderThePanPrefix() {
        assertEquals(ActionRegistry.CP_FAILOVER_INCIDENT_RELEASE,
            SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/cp-failover/runs/*/incident/release"));
        assertNull(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/pan-failover/runs/*/incident/release"));
        assertNull(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/failover/*/quarantine/acknowledge"));
        var action = new ActionRegistry().find(ActionRegistry.CP_FAILOVER_INCIDENT_RELEASE).orElseThrow();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN), action.requiredRoleTokens());
    }

    @Test void securityAdminReleasesWithTheSessionActorAndOnlyAReason() throws Exception {
        var f = fixture(Set.of(RoleToken.SECURITY_ADMIN));
        when(f.service().releaseIncident("run-a", "Former active returned out of band", ACTOR))
            .thenReturn(JooqCpFailoverRepository.INCIDENT_RELEASED);
        f.mvc().perform(write(RELEASE, "{\"reason\":\"Former active returned out of band\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"))
            .andExpect(jsonPath("$.incidentRef").value("run-a"));
        verify(f.service()).releaseIncident("run-a", "Former active returned out of band", ACTOR);
        // A caller-named actor or approver is refused, never used or silently ignored.
        for (String body : List.of("{\"reason\":\"r\",\"approver_id\":\"synthetic-other\"}",
                "{\"reason\":\"r\",\"actor\":\"synthetic-other\"}", "{\"reason\":7}"))
            f.mvc().perform(write(RELEASE, body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        when(f.service().releaseIncident("run-a", null, ACTOR)).thenThrow(new CpFailoverService.Refusal("RELEASE_REASON_REQUIRED"));
        f.mvc().perform(write(RELEASE, "{}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RELEASE_REASON_REQUIRED"));
        verifyNoMoreInteractions(f.generic());
    }

    @Test void otherRolesAndThePanPrefixAndTheGenericRouteAreRefusedBeforeTheService() throws Exception {
        for (Set<String> roles : List.of(Set.of(RoleToken.OPERATION_ADMIN), Set.of(RoleToken.OPERATOR),
                Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER))) {
            var f = fixture(roles);
            f.mvc().perform(write(RELEASE, "{\"reason\":\"r\"}")).andExpect(status().isForbidden());
            verifyNoInteractions(f.service(), f.generic());
        }
        var admin = fixture(Set.of(RoleToken.SECURITY_ADMIN));
        admin.mvc().perform(write("/api/v2/pan-failover/runs/run-a/incident/release", "{\"reason\":\"r\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACTION_MAPPING_REQUIRED"));
        admin.mvc().perform(write("/api/v2/failover/unit-a/quarantine/acknowledge",
                "{\"execution_id\":\"run-a\",\"approver_id\":\"synthetic-other\",\"reason\":\"r\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(admin.service(), admin.generic());
    }

    @Test void everyReaderSeesTheIncidentAndOnlySecurityAdminIsOfferedTheRelease() throws Exception {
        var incident = new JooqCpFailoverRepository.Incident("CLS-ROMEO-01", "run-a", null, "check_point",
            "MUTATION_STOPPED", Instant.parse("2026-10-20T10:00:00Z"), List.of("member-a", "member-b"),
            List.of("dispatch-a"), true);
        for (var roles : List.of(Set.of(RoleToken.SECURITY_ADMIN), Set.of(RoleToken.OPERATION_ADMIN),
                Set.of(RoleToken.OPERATOR), Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER))) {
            var f = fixture(roles);
            boolean admin = roles.contains(RoleToken.SECURITY_ADMIN);
            when(f.service().incidents(ACTOR)).thenReturn(List.of(new CpFailoverService.IncidentView(incident, "opaque-cluster", "opaque-cluster")));
            when(f.service().mayReleaseIncident(ACTOR)).thenReturn(admin);
            for (String prefix : List.of("/api/v2/cp-failover", "/api/v2/pan-failover")) {
                String path = prefix + "/incidents";
                f.mvc().perform(get(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.canRelease").value(admin))
                    .andExpect(admin ? jsonPath("$.roleNote").isEmpty()
                        : jsonPath("$.roleNote").value(CpFailoverController.INCIDENT_ROLE_NOTE))
                    .andExpect(jsonPath("$.incidents[0].incidentRef").value("run-a"))
                    .andExpect(jsonPath("$.incidents[0].blocksEstate").value(true))
                    .andExpect(jsonPath("$.incidents[0].unresolvedSends").value(1))
                    .andExpect(jsonPath("$.incidents[0].incidentActive").value(true));
            }
        }
    }
}
