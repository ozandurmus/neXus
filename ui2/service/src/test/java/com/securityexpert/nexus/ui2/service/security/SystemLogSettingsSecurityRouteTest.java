package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

/**
 * The system-log settings routes (contract section 14): one GET and one PUT on {@code /api/v2/config/system-logs},
 * each its own action, both {@code role:security_admin} only. The aiview persona reads nothing here (the contract
 * names security_admin alone) and writes nothing anywhere.
 */
class SystemLogSettingsSecurityRouteTest {
    private static final String ROUTE = "/api/v2/config/system-logs";

    @Test
    void bothActionsAreSecurityAdminOnlyAndConsoleSubmittable() {
        var registry = new ActionRegistry();
        assertEquals("system_log_settings_read", ActionRegistry.SYSTEM_LOG_SETTINGS_READ);
        assertEquals("system_log_settings_write", ActionRegistry.SYSTEM_LOG_SETTINGS_WRITE);
        for (String action : new String[] {ActionRegistry.SYSTEM_LOG_SETTINGS_READ, ActionRegistry.SYSTEM_LOG_SETTINGS_WRITE}) {
            var descriptor = registry.find(action).orElseThrow();
            assertEquals(Set.of(RoleToken.SECURITY_ADMIN), descriptor.requiredRoleTokens(), action);
            assertTrue(descriptor.consoleSubmittable(), action);
        }
    }

    @Test
    void getMapsToTheReadActionAndPutToTheWriteActionAndNothingElseResolves() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        assertEquals(ActionRegistry.SYSTEM_LOG_SETTINGS_READ, method.invoke(interceptor, "GET", ROUTE));
        assertEquals(ActionRegistry.SYSTEM_LOG_SETTINGS_WRITE, method.invoke(interceptor, "PUT", ROUTE));
        assertNull(method.invoke(interceptor, "POST", ROUTE));
        assertNull(method.invoke(interceptor, "DELETE", ROUTE));
        assertEquals(1, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.values().stream()
                .filter(ActionRegistry.SYSTEM_LOG_SETTINGS_WRITE::equals).count(), "one write route");
    }

    @Test
    void securityAdminReadsAndWritesAndEveryOtherRoleIsRefusedOnBoth() throws Exception {
        for (String method : new String[] {"GET", "PUT"}) {
            assertTrue(interceptor(RoleToken.SECURITY_ADMIN, false).preHandle(request(method), new MockHttpServletResponse(),
                    new Object()), method);
            for (String role : new String[] {RoleToken.VIEWER, RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN,
                    RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN, RoleToken.COMPLIANCE_ADMIN, RoleToken.REPLAY_VIEWER}) {
                var response = new MockHttpServletResponse();
                assertFalse(interceptor(role, false).preHandle(request(method), response, new Object()), role + " " + method);
                assertEquals(403, response.getStatus(), role + " " + method);
            }
            // The aiview machine session: refused like its role, on the read as on the write.
            var machine = new MockHttpServletResponse();
            assertFalse(interceptor(RoleToken.REPLAY_VIEWER, true).preHandle(request(method), machine, new Object()), method);
            assertEquals(403, machine.getStatus(), method);
        }
    }

    private static MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, ROUTE);
        request.setServletPath(ROUTE);
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
