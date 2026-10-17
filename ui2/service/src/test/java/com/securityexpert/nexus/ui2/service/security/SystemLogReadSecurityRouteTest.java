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
 * The phase B read side of the system-log programme (contract section 14): six GET routes, all one action
 * ({@code system_log_read}: operator and above, aiview included); the unmasked view is a second action the text route's
 * controller checks, never a route of its own.
 */
class SystemLogReadSecurityRouteTest {
    private static final String BASE = "/api/v2/system-logs";
    private static final Map<String, String> ROUTES = Map.of(
            "GET " + BASE + "/dev-1/sources", "sources",
            "GET " + BASE + "/dev-1/chunks", "chunks",
            "GET " + BASE + "/chunks/chunk-1/text", "text",
            "GET " + BASE + "/gaps", "gaps",
            "GET " + BASE + "/crash-files", "crash-files",
            "GET " + BASE + "/dev-1/hcp", "hcp");

    @Test
    void theTwoActionsAreRegisteredWithTheirRoles() {
        var registry = new ActionRegistry();
        assertEquals("system_log_read", ActionRegistry.SYSTEM_LOG_READ);
        assertEquals("system_log_read_unmasked", ActionRegistry.SYSTEM_LOG_READ_UNMASKED);
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                registry.find(ActionRegistry.SYSTEM_LOG_READ).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.SYSTEM_LOG_READ_UNMASKED).orElseThrow().requiredRoleTokens());
        assertTrue(registry.find(ActionRegistry.SYSTEM_LOG_READ).orElseThrow().consoleSubmittable());
    }

    @Test
    void sixGetRoutesMapToSystemLogReadAndNoRouteMapsToTheUnmaskedAction() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);
        for (String route : ROUTES.keySet()) {
            String[] parts = route.split(" ", 2);
            assertEquals(ActionRegistry.SYSTEM_LOG_READ, method.invoke(interceptor, parts[0], parts[1]), route);
        }
        // Reads only: nothing resolves for a write on the same paths.
        assertNull(method.invoke(interceptor, "POST", BASE + "/dev-1/sources"));
        assertNull(method.invoke(interceptor, "DELETE", BASE + "/chunks/chunk-1/text"));
        var mapped = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.entrySet().stream()
                .filter(e -> ActionRegistry.SYSTEM_LOG_READ.equals(e.getValue())).map(Map.Entry::getKey).sorted().toList();
        assertEquals(6, mapped.size(), mapped.toString());
        assertTrue(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.values().stream()
                .noneMatch(ActionRegistry.SYSTEM_LOG_READ_UNMASKED::equals));
    }

    @Test
    void operatorAndAboveAndAiviewMayReadAndTheOtherRolesMayNot() throws Exception {
        for (String role : new String[] {RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN,
                RoleToken.REPLAY_VIEWER}) {
            for (String route : ROUTES.keySet()) {
                String[] parts = route.split(" ", 2);
                assertTrue(interceptor(role, role.equals(RoleToken.REPLAY_VIEWER)).preHandle(request(parts[1]),
                        new MockHttpServletResponse(), new Object()), role + " " + route);
            }
        }
        for (String role : new String[] {RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN,
                RoleToken.COMPLIANCE_ADMIN}) {
            for (String route : ROUTES.keySet()) {
                String[] parts = route.split(" ", 2);
                var response = new MockHttpServletResponse();
                assertFalse(interceptor(role, false).preHandle(request(parts[1]), response, new Object()), role + " " + route);
                assertEquals(403, response.getStatus(), role + " " + route);
            }
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
