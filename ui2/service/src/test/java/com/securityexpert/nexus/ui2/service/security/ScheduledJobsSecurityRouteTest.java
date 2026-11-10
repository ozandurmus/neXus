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
 * V203: the scheduled-job switches. One GET on {@code /api/v2/config/scheduled-jobs} open to every signed-in role (aiview
 * included; the controller masks), one PUT per family for operation_admin and security_admin only.
 */
class ScheduledJobsSecurityRouteTest {
    private static final String READ = "/api/v2/config/scheduled-jobs";
    private static final String WRITE = "/api/v2/config/scheduled-jobs/nightly_inventory";

    @Test
    void theWriteIsOperationAdminOrSecurityAdminAndTheReadNeedsNoRole() {
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.SCHEDULED_JOBS_SET).orElseThrow().requiredRoleTokens());
        assertTrue(registry.find(ActionRegistry.SCHEDULED_JOBS_READ).orElseThrow().requiredRoleTokens().isEmpty());
        assertTrue(registry.find(ActionRegistry.SCHEDULED_JOBS_SET).orElseThrow().consoleSubmittable());
    }

    @Test
    void getMapsToTheReadAndPutPerFamilyToTheWriteAndNothingElseResolves() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        assertEquals(ActionRegistry.SCHEDULED_JOBS_READ, method.invoke(interceptor, "GET", READ));
        assertEquals(ActionRegistry.SCHEDULED_JOBS_SET, method.invoke(interceptor, "PUT", WRITE));
        assertNull(method.invoke(interceptor, "PUT", READ));
        assertNull(method.invoke(interceptor, "POST", WRITE));
        assertNull(method.invoke(interceptor, "DELETE", WRITE));
        assertEquals(1, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.values().stream()
                .filter(ActionRegistry.SCHEDULED_JOBS_SET::equals).count(), "one write route");
    }

    @Test
    void bothAdministratorRolesMayWriteAndEveryOtherRoleIsRefused() throws Exception {
        for (String role : new String[] {RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN}) {
            assertTrue(interceptor(role, false).preHandle(request("PUT", WRITE), new MockHttpServletResponse(), new Object()), role);
        }
        for (String role : new String[] {RoleToken.VIEWER, RoleToken.OPERATOR, RoleToken.ONBOARDING_ADMIN,
                RoleToken.BACKUP_ADMIN, RoleToken.COMPLIANCE_ADMIN, RoleToken.REPLAY_VIEWER}) {
            var response = new MockHttpServletResponse();
            assertFalse(interceptor(role, false).preHandle(request("PUT", WRITE), response, new Object()), role);
            assertEquals(403, response.getStatus(), role);
        }
        var machine = new MockHttpServletResponse();
        assertFalse(interceptor(RoleToken.REPLAY_VIEWER, true).preHandle(request("PUT", WRITE), machine, new Object()));
    }

    @Test
    void everyRoleMayRead() throws Exception {
        for (String role : new String[] {RoleToken.VIEWER, RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN,
                RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER}) {
            assertTrue(interceptor(role, false).preHandle(request("GET", READ), new MockHttpServletResponse(), new Object()), role);
        }
    }

    private static MockHttpServletRequest request(String method, String route) {
        var request = new MockHttpServletRequest(method, route);
        request.setServletPath(route);
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
        // An action with no required role: the real evaluator answers NO_APPLICABLE_AUTHORITY, which proceeds.
        when(rbac.evaluate(anyString(), eq(Optional.empty()), any())).thenReturn(new RbacEvaluator.Decision(
                AuthzOutcome.NO_APPLICABLE_AUTHORITY, Optional.empty(), Optional.empty(), Optional.empty()));
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
