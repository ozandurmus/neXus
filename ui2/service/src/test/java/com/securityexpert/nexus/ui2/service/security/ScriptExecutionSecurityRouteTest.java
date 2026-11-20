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
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * Role matrix and route mapping verification for Script Execution slice 1
 * ({@code SCRIPT_EXECUTION_CONTRACT.md} section 3.6 and Amendment 2026-11-18).
 */
class ScriptExecutionSecurityRouteTest {

    private static final String LIST_ROUTE = "/api/v2/scripts";
    private static final String ONE_ROUTE = "/api/v2/scripts/script-1";
    private static final String VERSIONS_ROUTE = "/api/v2/scripts/script-1/versions";
    private static final String ENABLE_ROUTE = "/api/v2/scripts/script-1/enable";
    private static final String DISABLE_ROUTE = "/api/v2/scripts/script-1/disable";
    private static final String RUN_ROUTE = "/api/v2/scripts/script-1/run";
    private static final String RUNS_ROUTE = "/api/v2/scripts/script-1/runs";
    private static final String ONE_RUN_ROUTE = "/api/v2/scripts/runs/run-1";
    private static final String RUN_OUTPUT_ROUTE = "/api/v2/scripts/runs/run-1/output";

    @Test
    void writeActionRequiresSecurityAdminAndReadActionIsOpenToEveryAuthenticatedRole() {
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.SCRIPT_WRITE).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.SCRIPT_RUN).orElseThrow().requiredRoleTokens());
        assertTrue(registry.find(ActionRegistry.SCRIPT_READ).orElseThrow().requiredRoleTokens().isEmpty());
        assertTrue(registry.find(ActionRegistry.SCRIPT_READ).orElseThrow().consoleSubmittable());
        assertTrue(registry.find(ActionRegistry.SCRIPT_WRITE).orElseThrow().consoleSubmittable());
        assertTrue(registry.find(ActionRegistry.SCRIPT_RUN).orElseThrow().consoleSubmittable());
    }

    @Test
    void routesResolveToScriptReadAndScriptWriteAndUnmappedMethodsAreRefused() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        var method = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        method.setAccessible(true);

        assertEquals(ActionRegistry.SCRIPT_READ, method.invoke(interceptor, "GET", LIST_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_READ, method.invoke(interceptor, "GET", ONE_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_WRITE, method.invoke(interceptor, "POST", LIST_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_WRITE, method.invoke(interceptor, "POST", VERSIONS_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_WRITE, method.invoke(interceptor, "PATCH", ONE_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_WRITE, method.invoke(interceptor, "POST", ENABLE_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_WRITE, method.invoke(interceptor, "POST", DISABLE_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_RUN, method.invoke(interceptor, "POST", RUN_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_READ, method.invoke(interceptor, "GET", RUNS_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_READ, method.invoke(interceptor, "GET", ONE_RUN_ROUTE));
        assertEquals(ActionRegistry.SCRIPT_OUTPUT_READ, method.invoke(interceptor, "GET", RUN_OUTPUT_ROUTE));

        // No body download route or DELETE route exists
        assertNull(method.invoke(interceptor, "GET", "/api/v2/scripts/script-1/body"));
        assertNull(method.invoke(interceptor, "GET", "/api/v2/scripts/script-1/download"));
        assertNull(method.invoke(interceptor, "DELETE", ONE_ROUTE));
    }

    @Test
    void onlySecurityAdminMayMutateScriptsAndEveryOtherRoleAndMachineSessionIsRefused() throws Exception {
        List<String[]> writeRequests = List.of(
                new String[] {"POST", LIST_ROUTE},
                new String[] {"POST", VERSIONS_ROUTE},
                new String[] {"PATCH", ONE_ROUTE},
                new String[] {"POST", ENABLE_ROUTE},
                new String[] {"POST", DISABLE_ROUTE});

        for (String[] write : writeRequests) {
            String label = write[0] + " " + write[1];
            assertTrue(interceptor(RoleToken.SECURITY_ADMIN, false)
                    .preHandle(request(write[0], write[1]), new MockHttpServletResponse(), new Object()),
                    "security_admin must be permitted on " + label);

            for (String role : new String[] {
                    RoleToken.VIEWER, RoleToken.OPERATOR, RoleToken.ONBOARDING_ADMIN,
                    RoleToken.BACKUP_ADMIN, RoleToken.COMPLIANCE_ADMIN, RoleToken.OPERATION_ADMIN,
                    RoleToken.REPLAY_VIEWER}) {
                var response = new MockHttpServletResponse();
                assertFalse(interceptor(role, false).preHandle(request(write[0], write[1]), response, new Object()),
                        role + " must be refused on " + label);
                assertEquals(403, response.getStatus(), role + " status on " + label);
            }

            var machineResponse = new MockHttpServletResponse();
            assertFalse(interceptor(RoleToken.REPLAY_VIEWER, true)
                    .preHandle(request(write[0], write[1]), machineResponse, new Object()),
                    "machine session must be refused on " + label);
            assertEquals(403, machineResponse.getStatus());
        }
    }

    @Test
    void everyAuthenticatedRoleMayReadScriptMetadata() throws Exception {
        for (String role : RoleToken.values()) {
            assertTrue(interceptor(role, false)
                    .preHandle(request("GET", LIST_ROUTE), new MockHttpServletResponse(), new Object()), role);
            assertTrue(interceptor(role, false)
                    .preHandle(request("GET", ONE_ROUTE), new MockHttpServletResponse(), new Object()), role);
            assertTrue(interceptor(role, false)
                    .preHandle(request("GET", RUNS_ROUTE), new MockHttpServletResponse(), new Object()), role);
            assertTrue(interceptor(role, false)
                    .preHandle(request("GET", ONE_RUN_ROUTE), new MockHttpServletResponse(), new Object()), role);
        }
    }

    @Test
    void outputDownloadIsOperatorAndAboveOnly() throws Exception {
        // Amendment 2026-11-18 (b) item 11 (review finding 11): output may carry operational text, so it is operator+.
        for (String permitted : List.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN)) {
            assertTrue(interceptor(permitted, false)
                    .preHandle(request("GET", RUN_OUTPUT_ROUTE), new MockHttpServletResponse(), new Object()), permitted);
        }
        for (String refused : List.of(RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN,
                RoleToken.COMPLIANCE_ADMIN, RoleToken.REPLAY_VIEWER)) {
            var response = new MockHttpServletResponse();
            assertFalse(interceptor(refused, false)
                    .preHandle(request("GET", RUN_OUTPUT_ROUTE), response, new Object()), refused);
            assertEquals(403, response.getStatus(), refused);
        }
    }

    @Test
    void operatorAndAboveMayRunScriptsWhileViewerAndReplayViewerAreRefused() throws Exception {
        for (String permitted : List.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN)) {
            assertTrue(interceptor(permitted, false)
                    .preHandle(request("POST", RUN_ROUTE), new MockHttpServletResponse(), new Object()),
                    permitted + " must be permitted on POST " + RUN_ROUTE);
        }
        for (String refused : List.of(
                RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN,
                RoleToken.COMPLIANCE_ADMIN, RoleToken.REPLAY_VIEWER)) {
            var response = new MockHttpServletResponse();
            assertFalse(interceptor(refused, false)
                    .preHandle(request("POST", RUN_ROUTE), response, new Object()),
                    refused + " must be refused on POST " + RUN_ROUTE);
            assertEquals(403, response.getStatus(), refused);
        }
        var machineResponse = new MockHttpServletResponse();
        assertFalse(interceptor(RoleToken.OPERATOR, true)
                .preHandle(request("POST", RUN_ROUTE), machineResponse, new Object()),
                "machine session must be refused on POST " + RUN_ROUTE);
        assertEquals(403, machineResponse.getStatus());
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
        when(rbac.evaluate(anyString(), eq(Optional.empty()), any())).thenReturn(new RbacEvaluator.Decision(
                AuthzOutcome.NO_APPLICABLE_AUTHORITY, Optional.empty(), Optional.empty(), Optional.empty()));
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call ->
                ((Set<?>) call.getArgument(1)).contains(role) ? permitted : denied);
        when(sessions.findBySessionId(anyString(), any())).thenReturn(Optional.of(new SessionRecord(
                SessionHasher.hash("synthetic-cookie"), "synthetic-actor", "synthetic-csrf", SessionState.ACTIVE,
                now, now, now.plusSeconds(600), now.plusSeconds(1800), Optional.empty(), Optional.empty(), Optional.empty(), machine)));
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        return new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE, Set.of());
    }
}
