package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;

class AiviewDiagnosticBoundaryTest {
    @Test
    void humanAndMachineReplaySessionsReachDiagnosticsButCannotPlanExecuteOrScheduleFailover() throws Exception {
        for (boolean machine : new boolean[]{false, true}) {
            Instant now = Instant.now();
            var sessions = mock(SessionRepository.class);
            var rbac = mock(RbacEvaluator.class);
            var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
            var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
            when(rbac.evaluate(anyString(), any(), any())).thenReturn(denied);
            when(rbac.evaluate(eq("synthetic-actor"), eq(Optional.of(RoleToken.REPLAY_VIEWER)), any())).thenReturn(permitted);
            when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call ->
                    ((Set<?>)call.getArgument(1)).contains(RoleToken.REPLAY_VIEWER) ? permitted : denied);
            when(sessions.findBySessionId(anyString(), any())).thenReturn(Optional.of(new SessionRecord(
                    SessionHasher.hash("synthetic-cookie"), "synthetic-actor", "synthetic-csrf", SessionState.ACTIVE,
                    now, now, now.plusSeconds(600), now.plusSeconds(1800), Optional.empty(), Optional.empty(), Optional.empty(), machine)));
            var gate = new GateChain(sessions, new ActionRegistry(), rbac,
                    mock(com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository.class));
            var interceptor = new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE, Set.of());
            for (String path : new String[]{"/api/v2/diagnostics", "/api/v2/diagnostics/", "/api/v2/cp-failover/approvals",
                    "/api/v2/cp-failover/runs", "/api/v2/failover/synthetic-cluster/dry-run", "/api/v2/failover/synthetic-cluster/authorize",
                    "/api/v2/failover/synthetic-cluster/execute", "/api/v2/failover/synthetic-cluster/schedules"}) {
                var request = new MockHttpServletRequest("POST", path);
                request.setServletPath(path);
                request.setCookies(new jakarta.servlet.http.Cookie("ui2_session", "synthetic-cookie"));
                request.addHeader("X-CSRF-Token", "synthetic-csrf");
                request.addHeader("Origin", "https://example.invalid");
                var response = new MockHttpServletResponse();
                boolean accepted = interceptor.preHandle(request, response, new Object());
                if (path.startsWith("/api/v2/diagnostics")) {
                    assertTrue(accepted);
                    assertEquals(true, request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
                } else {
                    assertFalse(accepted, path);
                    assertEquals(403, response.getStatus(), path);
                }
            }
        }
    }
}
