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

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;

/** CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md §5.1 / §6 through the real gate chain: route, role, session actor, body shape, view. */
class CpFailoverResumeRouteTest {
    private static final String COOKIE = "synthetic-session", ACTOR = "synthetic-actor", CSRF = "synthetic-csrf";
    private static final String RESUME = "/api/v2/cp-failover/runs/run-a/resume-return";
    private static final String APPROVE = "/api/v2/cp-failover/runs/run-a/resume-return/approve";

    private record Fixture(MockMvc mvc, CpFailoverService service) {}

    private static Fixture fixture(Set<String> roles) {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var service = mock(CpFailoverService.class);
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
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service))
            .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        return new Fixture(mvc, service);
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(String path, String body) {
        return post(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)).header("X-CSRF-Token", CSRF)
            .header("Origin", "https://example.invalid").contentType("application/json").content(body);
    }

    @Test void resumeRoutesAreAdministratorOnlyAndNeverUnderThePanPrefix() {
        assertEquals(ActionRegistry.CP_FAILOVER_RESUME_RETURN,
            SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/cp-failover/runs/*/resume-return"));
        assertEquals(ActionRegistry.CP_FAILOVER_RESUME_RETURN,
            SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/cp-failover/runs/*/resume-return/approve"));
        assertNull(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/pan-failover/runs/*/resume-return"));
        assertNull(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/pan-failover/runs/*/resume-return/approve"));
        var action = new ActionRegistry().find(ActionRegistry.CP_FAILOVER_RESUME_RETURN).orElseThrow();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.OPERATION_ADMIN), action.requiredRoleTokens());
    }

    @Test void theSessionActorRequestsAndApprovesWithTheContractBodyOnly() throws Exception {
        var f = fixture(Set.of(RoleToken.SECURITY_ADMIN));
        when(f.service().resumeReturn("run-a", "run-a", "Return to standby", true, ACTOR))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "resume-a"));
        f.mvc().perform(write(RESUME, "{\"expectedIncidentId\":\"run-a\",\"reason\":\"Return to standby\",\"businessHoursAck\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resumeId").value("resume-a"))
            .andExpect(jsonPath("$.state").value("QUEUED"));
        verify(f.service()).resumeReturn("run-a", "run-a", "Return to standby", true, ACTOR);
        // A caller-named actor or approver is refused, never used or silently ignored; types are strict.
        for (String body : List.of("{\"reason\":\"r\",\"actor\":\"synthetic-other\"}",
                "{\"reason\":\"r\",\"approvedBy\":\"synthetic-other\"}", "{\"reason\":7}", "{\"businessHoursAck\":\"yes\"}"))
            f.mvc().perform(write(RESUME, body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        when(f.service().resumeReturn("run-a", "run-a", "r", false, ACTOR))
            .thenThrow(new CpFailoverService.Refusal("BUSINESS_HOURS_ACK_REQUIRED"));
        f.mvc().perform(write(RESUME, "{\"expectedIncidentId\":\"run-a\",\"reason\":\"r\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_ACK_REQUIRED"));
        when(f.service().approveResume("run-a", "resume-a", false, ACTOR))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "resume-a"));
        f.mvc().perform(write(APPROVE, "{\"resumeId\":\"resume-a\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("QUEUED"));
        f.mvc().perform(write(APPROVE, "{\"resumeId\":\"resume-a\",\"approver\":\"synthetic-other\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void anOperationAdminRequestIsPendingAndItsSelfApprovalIsRefused() throws Exception {
        var f = fixture(Set.of(RoleToken.OPERATION_ADMIN));
        when(f.service().resumeReturn("run-a", "run-a", "Return", false, ACTOR))
            .thenReturn(new JooqCpFailoverRepository.Decision("PENDING_APPROVAL", "resume-a"));
        f.mvc().perform(write(RESUME, "{\"expectedIncidentId\":\"run-a\",\"reason\":\"Return\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING_APPROVAL"));
        when(f.service().approveResume("run-a", "resume-a", false, ACTOR)).thenThrow(new CpFailoverService.Refusal("SELF_APPROVAL"));
        f.mvc().perform(write(APPROVE, "{\"resumeId\":\"resume-a\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
    }

    @Test void operatorViewerAiviewAndThePanPrefixAreRefusedBeforeTheService() throws Exception {
        for (Set<String> roles : List.of(Set.of(RoleToken.OPERATOR), Set.of(RoleToken.VIEWER),
                Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER))) {
            var f = fixture(roles);
            f.mvc().perform(write(RESUME, "{\"expectedIncidentId\":\"run-a\",\"reason\":\"r\"}")).andExpect(status().isForbidden());
            f.mvc().perform(write(APPROVE, "{\"resumeId\":\"resume-a\"}")).andExpect(status().isForbidden());
            verifyNoInteractions(f.service());
        }
        var admin = fixture(Set.of(RoleToken.SECURITY_ADMIN));
        admin.mvc().perform(write("/api/v2/pan-failover/runs/run-a/resume-return", "{\"reason\":\"r\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACTION_MAPPING_REQUIRED"));
        verifyNoInteractions(admin.service());
    }

    @Test void theRunViewOffersTheButtonOnlyToAnAllowedRoleAndShowsStateToEveryReader() throws Exception {
        var run = new JooqCpFailoverRepository.Run("run-a", "CLS-ROMEO-01", null, "approval-a", "synthetic-initiator",
            Instant.EPOCH, "job-a", "STOPPED", "STOPPED", "OUTCOME_UNKNOWN", null, "TRAFFIC_BELOW_TOLERANCE");
        for (var roles : List.of(Set.of(RoleToken.SECURITY_ADMIN), Set.of(RoleToken.OPERATOR),
                Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER))) {
            var f = fixture(roles);
            boolean admin = roles.contains(RoleToken.SECURITY_ADMIN);
            when(f.service().detail("run-a", ACTOR)).thenReturn(Optional.of(new JooqCpFailoverRepository.Detail(run, List.of())));
            when(f.service().mayResume(ACTOR)).thenReturn(admin);
            when(f.service().resumeEligibility("run-a", ACTOR)).thenReturn(null);
            when(f.service().insideBusinessHours()).thenReturn(true);
            when(f.service().businessHoursLabel()).thenReturn("08:00–20:00");
            when(f.service().resumeOf("run-a", ACTOR)).thenReturn(Optional.empty());
            String path = "/api/v2/cp-failover/runs/run-a";
            f.mvc().perform(get(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resume.eligible").value(true))
                .andExpect(jsonPath("$.resume.canResume").value(admin))
                .andExpect(jsonPath("$.resume.businessHoursInside").value(true));
        }
        var f = fixture(Set.of(RoleToken.OPERATION_ADMIN));
        when(f.service().detail("run-a", ACTOR)).thenReturn(Optional.of(new JooqCpFailoverRepository.Detail(run, List.of())));
        when(f.service().mayResume(ACTOR)).thenReturn(true);
        when(f.service().resumeEligibility("run-a", ACTOR)).thenReturn("RESUME_ALREADY_USED");
        when(f.service().businessHoursLabel()).thenReturn("08:00–20:00");
        var resume = new JooqCpFailoverRepository.Resume("resume-a", "run-a", "CLS-ROMEO-01", "member-a", "member-b",
            List.of("member-a", "member-b"), "revision-a", "run-a", JooqCpFailoverRepository.TWO_PERSON, "synthetic-initiator",
            Instant.now(), Instant.now().plusSeconds(1800), false, false, null, null, "PENDING_APPROVAL", "PENDING_APPROVAL",
            null, null, null, false, null);
        when(f.service().resumeOf("run-a", ACTOR)).thenReturn(Optional.of(resume));
        String path = "/api/v2/cp-failover/runs/run-a";
        f.mvc().perform(get(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resume.refusal").value("RESUME_ALREADY_USED"))
            .andExpect(jsonPath("$.resume.canResume").value(false))
            .andExpect(jsonPath("$.resume.state").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.resume.canApprove").value(true))
            .andExpect(jsonPath("$.resume.formerActiveRef").value("member-a"));
    }
}
