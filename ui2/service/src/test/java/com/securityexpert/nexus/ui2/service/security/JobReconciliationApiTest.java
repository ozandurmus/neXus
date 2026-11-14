package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobReconciliationRepository;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.service.api.JobReconciliationController;

class JobReconciliationApiTest {
    @Test void reconcileIsBackupAdminOrStrongerAndTheActorIsTheSession() throws Exception {
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var decisions = mock(AuthzDecisionRepository.class);
        var jobs = mock(JobReconciliationRepository.class);
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.BACKUP_ADMIN),
            registry.find(ActionRegistry.JOB_RECONCILE).orElseThrow().requiredRoleTokens());
        assertEquals(ActionRegistry.JOB_RECONCILE, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/jobs/*/reconcile"));
        var mvc = MockMvcBuilders.standaloneSetup(new JobReconciliationController(jobs))
            .addInterceptors(new GateChainInterceptor(new GateChain(sessions, registry, rbac, decisions),
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        String path = "/api/v2/jobs/job-1/reconcile";
        String json = "{\"outcome\":\"RECONCILED_FAILURE\",\"evidence\":\"checked on device\"}";
        for (String role : List.of(RoleToken.SECURITY_ADMIN, RoleToken.BACKUP_ADMIN, RoleToken.VIEWER, RoleToken.REPLAY_VIEWER)) {
            String cookie = "synthetic-session-" + role, actor = "synthetic-actor-" + role;
            Instant now = Instant.now();
            var session = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE,
                now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty());
            when(sessions.findBySessionId(eq(session.sessionId()), any())).thenReturn(Optional.of(session));
            boolean allowed = role.equals(RoleToken.SECURITY_ADMIN) || role.equals(RoleToken.BACKUP_ADMIN);
            when(rbac.evaluateAny(eq(actor), anySet(), any(Instant.class))).thenAnswer(call ->
                new RbacEvaluator.Decision(allowed ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                    Optional.of("test"), Optional.empty(), Optional.empty()));
            when(jobs.record("job-1", "RECONCILED_FAILURE", "checked on device", actor)).thenReturn("RECONCILED");
            var response = mvc.perform(post(path).servletPath(path).cookie(new Cookie("ui2_session", cookie))
                .header("X-CSRF-Token", "synthetic-csrf").header("Origin", "https://example.invalid")
                .contentType(MediaType.APPLICATION_JSON).content(json));
            response.andExpect(status().is(allowed ? 200 : 403));
            if (allowed) {
                response.andExpect(jsonPath("$.state").value("RECONCILED"));
                verify(jobs).record("job-1", "RECONCILED_FAILURE", "checked on device", actor);
            } else verify(jobs, never()).record(any(), any(), any(), eq(actor));
        }
    }

    @Test void refusalsMapToStatusCodes() throws Exception {
        var jobs = mock(JobReconciliationRepository.class);
        var mvc = MockMvcBuilders.standaloneSetup(new JobReconciliationController(jobs)).build();
        when(jobs.record(eq("a"), any(), any(), any())).thenReturn("JOB_NOT_FOUND");
        when(jobs.record(eq("b"), any(), any(), any())).thenReturn("JOB_NOT_OUTCOME_UNKNOWN");
        when(jobs.record(eq("c"), any(), any(), any())).thenThrow(new IllegalArgumentException("x"));
        String ok = "{\"outcome\":\"RECONCILED_SUCCESS\",\"evidence\":\"e\"}";
        mvc.perform(post("/api/v2/jobs/a/reconcile").contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v2/jobs/b/reconcile").contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isConflict());
        mvc.perform(post("/api/v2/jobs/c/reconcile").contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/jobs/a/reconcile").contentType(MediaType.APPLICATION_JSON)
            .content("{\"outcome\":\"RECONCILED_SUCCESS\",\"evidence\":\"e\",\"approver\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/jobs/a/reconcile")).andExpect(status().isBadRequest());
    }
}
