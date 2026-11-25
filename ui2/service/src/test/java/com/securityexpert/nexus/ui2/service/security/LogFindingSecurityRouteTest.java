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
import java.util.List;
import java.util.Map;
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
 * The log detection engine's routes (design section 9): five GET reads of one action ({@code system_log_read}: operator and
 * above, aiview included; the aiview action set is unchanged), the acknowledgement ({@code log_finding_acknowledge}: the two administrators, never aiview) and the
 * switch ({@code log_detection_settings_*}: security_admin only). No route maps to the system-log read action.
 */
class LogFindingSecurityRouteTest {
    private static final String BASE = "/api/v2/log-findings";
    private static final Map<String, String> READS = Map.of(
            "GET " + BASE, "list", "GET " + BASE + "/chips", "chips", "GET " + BASE + "/coverage", "coverage",
            "GET " + BASE + "/budget", "budget", "GET " + BASE + "/" + "a".repeat(32), "one");

    @Test
    void theActionsAreRegisteredWithTheirRoles() {
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                registry.find(ActionRegistry.SYSTEM_LOG_READ).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.LOG_FINDING_ACKNOWLEDGE).orElseThrow().requiredRoleTokens(),
                "aiview never acknowledges");
        for (String action : new String[] {ActionRegistry.LOG_DETECTION_SETTINGS_READ, ActionRegistry.LOG_DETECTION_SETTINGS_WRITE}) {
            assertEquals(Set.of(RoleToken.SECURITY_ADMIN), registry.find(action).orElseThrow().requiredRoleTokens(), action);
        }
    }

    @Test
    void routesMapToTheirActionAndNothingElseResolves() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        for (String route : READS.keySet()) {
            String[] parts = route.split(" ", 2);
            assertEquals(ActionRegistry.SYSTEM_LOG_READ, method.invoke(interceptor, parts[0], parts[1]), route);
        }
        assertEquals(ActionRegistry.LOG_FINDING_ACKNOWLEDGE, method.invoke(interceptor, "POST", BASE + "/" + "a".repeat(32) + "/acknowledge"));
        assertEquals(ActionRegistry.LOG_DETECTION_SETTINGS_READ, method.invoke(interceptor, "GET", "/api/v2/config/log-detection"));
        assertEquals(ActionRegistry.LOG_DETECTION_SETTINGS_WRITE, method.invoke(interceptor, "PUT", "/api/v2/config/log-detection"));
        assertNull(method.invoke(interceptor, "DELETE", BASE + "/" + "a".repeat(32)));
        assertNull(method.invoke(interceptor, "POST", BASE));
        assertNull(method.invoke(interceptor, "POST", "/api/v2/config/log-detection"));
        assertTrue(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.entrySet().stream()
                .filter(e -> e.getKey().contains("log-detection") || e.getKey().contains("acknowledge"))
                .noneMatch(e -> e.getValue().equals(ActionRegistry.SYSTEM_LOG_READ)), "the switch and the acknowledgement are never a plain read");
    }

    @Test
    void operatorAndAboveAndAiviewMayReadAndTheOtherRolesMayNot() throws Exception {
        for (String role : new String[] {RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN,
                RoleToken.REPLAY_VIEWER}) {
            for (String route : READS.keySet()) {
                String[] parts = route.split(" ", 2);
                assertTrue(interceptor(role, role.equals(RoleToken.REPLAY_VIEWER)).preHandle(request(parts[1]),
                        new MockHttpServletResponse(), new Object()), role + " " + route);
            }
        }
        for (String role : new String[] {RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN,
                RoleToken.COMPLIANCE_ADMIN}) {
            for (String route : READS.keySet()) {
                String[] parts = route.split(" ", 2);
                var response = new MockHttpServletResponse();
                assertFalse(interceptor(role, false).preHandle(request(parts[1]), response, new Object()), role + " " + route);
                assertEquals(403, response.getStatus(), role + " " + route);
            }
        }
    }

    @Test
    void theSwitchIsSecurityAdminOnly() throws Exception {
        assertTrue(interceptor(RoleToken.SECURITY_ADMIN, false).preHandle(request("/api/v2/config/log-detection"),
                new MockHttpServletResponse(), new Object()));
        for (String role : new String[] {RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.REPLAY_VIEWER}) {
            var response = new MockHttpServletResponse();
            assertFalse(interceptor(role, role.equals(RoleToken.REPLAY_VIEWER)).preHandle(request("/api/v2/config/log-detection"),
                    response, new Object()), role);
            assertEquals(403, response.getStatus(), role);
        }
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setCookies(new jakarta.servlet.http.Cookie("ui2_session", "synthetic-cookie"));
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
