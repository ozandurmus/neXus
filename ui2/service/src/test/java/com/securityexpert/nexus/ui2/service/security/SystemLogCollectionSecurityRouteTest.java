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

class SystemLogCollectionSecurityRouteTest {
    private static final String COLLECT = "/api/v2/system-logs/collect";

    @Test
    void theManualStartIsOneRegisteredActionForOperationAndSecurityAdministratorsOnly() {
        assertEquals(ActionRegistry.SYSTEM_LOG_COLLECT_START, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST " + COLLECT));
        assertEquals("system_log_collect_start", ActionRegistry.SYSTEM_LOG_COLLECT_START);
        assertEquals(Set.of(RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                new ActionRegistry().find(ActionRegistry.SYSTEM_LOG_COLLECT_START).orElseThrow().requiredRoleTokens());
        var routes = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.entrySet().stream()
                .filter(e -> ActionRegistry.SYSTEM_LOG_COLLECT_START.equals(e.getValue())).map(java.util.Map.Entry::getKey).toList();
        assertEquals(java.util.List.of("POST " + COLLECT), routes);
    }

    @Test
    void operationAndSecurityAdministratorsMayStartAndOthersMayNot() throws Exception {
        for (String role : new String[] {RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN}) {
            assertTrue(interceptor(role, false).preHandle(request("POST", COLLECT), new MockHttpServletResponse(), new Object()), role);
        }
        for (String role : new String[] {RoleToken.OPERATOR, RoleToken.VIEWER, RoleToken.REPLAY_VIEWER}) {
            var response = new MockHttpServletResponse();
            assertFalse(interceptor(role, false).preHandle(request("POST", COLLECT), response, new Object()), role);
            assertEquals(403, response.getStatus(), role);
        }
        // A machine (aiview) session is read-only: starting collection is a device-contacting write, not a read-only POST.
        var machine = new MockHttpServletResponse();
        assertFalse(interceptor(RoleToken.REPLAY_VIEWER, true).preHandle(request("POST", COLLECT), machine, new Object()));
        assertEquals(403, machine.getStatus());
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
