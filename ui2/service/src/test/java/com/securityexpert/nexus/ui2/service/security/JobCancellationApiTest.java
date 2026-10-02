package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobCancellationRepository;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.service.api.JobCancellationController;

class JobCancellationApiTest {
    @Test void cancelRequiresSecurityAdminSessionCsrfAndRecordsTheActor() throws Exception {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var decisions = mock(AuthzDecisionRepository.class);
        var jobs = mock(JobCancellationRepository.class);
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN), registry.find(ActionRegistry.JOB_CANCEL).orElseThrow().requiredRoleTokens());
        assertEquals(ActionRegistry.JOB_CANCEL, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/jobs/*/cancel"));
        var mvc = MockMvcBuilders.standaloneSetup(new JobCancellationController(jobs))
            .addInterceptors(new GateChainInterceptor(new GateChain(sessions, registry, rbac, decisions),
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        String path = "/api/v2/jobs/job-1/cancel";
        mvc.perform(post(path).servletPath(path)).andExpect(status().isUnauthorized());
        for (String role : List.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN, RoleToken.VIEWER, RoleToken.REPLAY_VIEWER)) {
            String cookie = "synthetic-session-" + role, actor = "synthetic-actor-" + role;
            Instant now = Instant.now();
            var session = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE,
                now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty());
            when(sessions.findBySessionId(eq(session.sessionId()), any())).thenReturn(Optional.of(session));
            boolean admin = role.equals(RoleToken.SECURITY_ADMIN);
            when(rbac.evaluateAny(eq(actor), eq(Set.of(RoleToken.SECURITY_ADMIN)), any())).thenReturn(
                new RbacEvaluator.Decision(admin ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                    Optional.of("test"), Optional.empty(), Optional.empty()));
            when(rbac.evaluate(eq(actor), eq(Optional.of(RoleToken.REPLAY_VIEWER)), any())).thenReturn(
                new RbacEvaluator.Decision(role.equals(RoleToken.REPLAY_VIEWER) ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                    Optional.of("test"), Optional.empty(), Optional.empty()));
            when(jobs.request("job-1", actor)).thenReturn(Optional.of("EXECUTING"));
            mvc.perform(post(path).servletPath(path).cookie(new Cookie("ui2_session", cookie)))
                .andExpect(status().isUnauthorized());
            var response = mvc.perform(post(path).servletPath(path).cookie(new Cookie("ui2_session", cookie))
                .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid"));
            response.andExpect(status().is(admin ? 202 : 403));
            if (admin) {
                response.andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.cancelRequested").value(true)).andExpect(jsonPath("$.state").value("EXECUTING"));
                verify(jobs).request("job-1", actor);
                when(jobs.request("job-1", actor)).thenReturn(Optional.empty());
                mvc.perform(post(path).servletPath(path).cookie(new Cookie("ui2_session", cookie))
                    .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid"))
                    .andExpect(status().isConflict());
            } else verify(jobs, never()).request("job-1", actor);
        }
    }
}
