package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

class SystemLogMeasurementSecurityRouteTest {
    private static final String LIST = "/api/v2/diagnostics/system-log-measurements";
    private static final String START = "/api/v2/diagnostics/system-log-measurements/start";

    @Test
    void readIsPolicyReadAndStartIsARegisteredActionWithoutWildcards() {
        assertEquals(ActionRegistry.POLICY_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET " + LIST));
        assertEquals(ActionRegistry.SYSTEM_LOG_MEASURE_START, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST " + START));
        var actions = new ActionRegistry();
        // A read-only diagnostic measurement: security_admin or aiview (PO 2026-10-04 diagnostic rule).
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                actions.find(ActionRegistry.SYSTEM_LOG_MEASURE_START).orElseThrow().requiredRoleTokens());
        assertTrue(actions.find(ActionRegistry.POLICY_READ).orElseThrow().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
        var routes = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.entrySet().stream()
                .filter(e -> ActionRegistry.SYSTEM_LOG_MEASURE_START.equals(e.getValue())).map(java.util.Map.Entry::getKey).toList();
        assertEquals(java.util.List.of("POST " + START), routes);
    }

    @Test
    void aReplayViewerReadsTheSummariesMaskedAndMayStartTheReadOnlyMeasurement() throws Exception {
        for (boolean machine : new boolean[] {false, true}) {
            var interceptor = interceptor(RoleToken.REPLAY_VIEWER, machine);
            var read = request("GET", LIST);
            assertTrue(interceptor.preHandle(read, new MockHttpServletResponse(), new Object()));
            assertEquals(true, read.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
            var start = request("POST", START);
            assertTrue(interceptor.preHandle(start, new MockHttpServletResponse(), new Object()));
        }
    }

    @Test
    void aSecurityAdministratorMayStartAndAnOperatorMayNot() throws Exception {
        var admin = request("POST", START);
        assertTrue(interceptor(RoleToken.SECURITY_ADMIN, false).preHandle(admin, new MockHttpServletResponse(), new Object()));
        var operator = request("POST", START);
        var response = new MockHttpServletResponse();
        assertFalse(interceptor(RoleToken.OPERATOR, false).preHandle(operator, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setCookies(new jakarta.servlet.http.Cookie("ui2_session", "synthetic-cookie"));
        request.addHeader("X-CSRF-Token", "synthetic-csrf");
        request.addHeader("Origin", "https://example.invalid");
        return request;
    }

    private static GateChainInterceptor interceptor(String role, boolean machine) {
        Instant now = Instant.now();
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        when(rbac.evaluate(anyString(), any(), any())).thenReturn(denied);
        when(rbac.evaluate(eq("synthetic-actor"), eq(Optional.of(role)), any())).thenReturn(permitted);
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call ->
                ((Set<?>) call.getArgument(1)).contains(role) ? permitted : denied);
        when(sessions.findBySessionId(anyString(), any())).thenReturn(Optional.of(new SessionRecord(
                SessionHasher.hash("synthetic-cookie"), "synthetic-actor", "synthetic-csrf", SessionState.ACTIVE,
                now, now, now.plusSeconds(600), now.plusSeconds(1800), Optional.empty(), Optional.empty(), Optional.empty(), machine)));
        var gate = new GateChain(sessions, new ActionRegistry(), rbac,
                mock(com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository.class));
        return new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE, Set.of());
    }
}
