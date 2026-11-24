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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.identity.LocalAuthModeRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicySettings;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Slice 1 of the aiview masked read-only administrator (UI2_AIVIEW_READONLY_ADMIN, default off). With the switch on, the
 * replay viewer reads exactly four masked settings GETs (system-log settings, local password policy, local auth mode,
 * SNMP polling);
 * every write, raw/unmasked view, export and download stays denied, and the switch widens nothing else.
 */
class AiviewReadonlyAdminFlagTest {
    private static final Set<String> GRANTED = ActionRegistry.AIVIEW_READONLY_ADMIN_READS;
    private static final String[] ROUTES = {"/api/v2/config/system-logs", "/api/v2/config/local-password-policy",
            "/api/v2/config/local-auth-mode", "/api/v2/config/snmp-polling"};

    @Test
    void switchOffLeavesEveryDescriptorSecurityAdminOnly() {
        var off = new ActionRegistry(false);
        for (String id : GRANTED) {
            assertEquals(Set.of(RoleToken.SECURITY_ADMIN), off.find(id).orElseThrow().requiredRoleTokens(), id);
        }
    }

    @Test
    void switchOnGrantsExactlyTheThreeReadsAndChangesNoOtherRoute() {
        var on = new ActionRegistry(true);
        var off = new ActionRegistry(false);
        for (String id : GRANTED) {
            assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                    on.find(id).orElseThrow().requiredRoleTokens(), id);
        }
        // Every route in the map: the decision inputs are identical with the switch on or off, except the three reads.
        for (var entry : SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.entrySet()) {
            String id = entry.getValue();
            if (GRANTED.contains(id)) {
                assertTrue(entry.getKey().startsWith("GET "), "only GET routes are granted: " + entry.getKey());
                continue;
            }
            assertEquals(off.find(id).orElseThrow().requiredRoleTokens(), on.find(id).orElseThrow().requiredRoleTokens(),
                    entry.getKey());
            assertEquals(off.find(id).orElseThrow().consoleSubmittable(), on.find(id).orElseThrow().consoleSubmittable(),
                    entry.getKey());
        }
    }

    @Test
    void writesStayDeniedToTheReplayViewerWithTheSwitchOn() {
        var on = new ActionRegistry(true);
        for (String id : new String[] {ActionRegistry.SYSTEM_LOG_SETTINGS_WRITE, ActionRegistry.LOCAL_PASSWORD_POLICY_WRITE,
                ActionRegistry.LOCAL_AUTH_MODE_WRITE, ActionRegistry.SESSION_REVOKE, ActionRegistry.ROLE_BINDING_CREATE,
                ActionRegistry.ROLE_BINDING_REVOKE, ActionRegistry.LOCAL_IDENTITY_CREATE,
                ActionRegistry.LOCAL_IDENTITY_SET_PASSWORD, ActionRegistry.LOCAL_IDENTITY_DISABLE,
                ActionRegistry.CREDENTIAL_CREATE, ActionRegistry.CREDENTIAL_REPLACE_SECRET, ActionRegistry.CREDENTIAL_DELETE,
                ActionRegistry.SCHEDULED_JOBS_SET, ActionRegistry.SCRIPT_WRITE, ActionRegistry.SCRIPT_RUN}) {
            assertFalse(on.find(id).orElseThrow().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER), id);
        }
    }

    @Test
    void rawViewsExportsAndDownloadsStayDeniedToTheReplayViewerWithTheSwitchOn() {
        var on = new ActionRegistry(true);
        for (String id : new String[] {ActionRegistry.SYSTEM_LOG_READ_UNMASKED, ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ,
                ActionRegistry.ZONE_MATRIX_ZONES_READ, ActionRegistry.JOB_TRANSCRIPT_READ, ActionRegistry.DEVICE_BACKUP_RETRIEVE,
                ActionRegistry.SUPPORT_BUNDLE_RETRIEVE, ActionRegistry.PACKET_ANALYSIS_RAW_VIEW,
                ActionRegistry.REPORT_RAW_VIEW, ActionRegistry.SCRIPT_OUTPUT_READ}) {
            assertFalse(on.find(id).orElseThrow().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER), id);
        }
    }

    @Test
    void gateChainPermitsTheThreeGetsAndRefusesEveryOtherMethodForTheReplayViewer() throws Exception {
        for (String route : ROUTES) {
            assertTrue(gate(true, RoleToken.REPLAY_VIEWER, false).preHandle(req("GET", route), new MockHttpServletResponse(),
                    new Object()), "on GET " + route);
            // The machine persona (aiview-e2e) reads them too, and still cannot write.
            assertTrue(gate(true, RoleToken.REPLAY_VIEWER, true).preHandle(req("GET", route), new MockHttpServletResponse(),
                    new Object()), "on machine GET " + route);
            for (String method : new String[] {"PUT", "POST", "DELETE"}) {
                for (boolean machine : new boolean[] {false, true}) {
                    var response = new MockHttpServletResponse();
                    assertFalse(gate(true, RoleToken.REPLAY_VIEWER, machine).preHandle(req(method, route), response, new Object()),
                            "on " + method + " " + route + " machine=" + machine);
                    assertEquals(403, response.getStatus(), method + " " + route);
                }
            }
            // Switch off: the same read is refused.
            var off = new MockHttpServletResponse();
            assertFalse(gate(false, RoleToken.REPLAY_VIEWER, false).preHandle(req("GET", route), off, new Object()), route);
            assertEquals(403, off.getStatus(), route);
            // Other roles gain nothing from the switch.
            for (String role : new String[] {RoleToken.VIEWER, RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN}) {
                assertFalse(gate(true, role, false).preHandle(req("GET", route), new MockHttpServletResponse(), new Object()),
                        role + " " + route);
            }
        }
    }

    @Test
    void controllersReportCanWriteFalseAndTheMaskedProjectionHidesTheActor() {
        // Leak test for each newly granted route: the DTO as the controller builds it for aiview, then the response advice.
        String actor = "synthetic-admin-alpha";
        var instant = Instant.parse("2026-11-20T10:00:00Z");
        Map<String, Object> policy = LocalPasswordPolicyService.view(
                new LocalPasswordPolicyRepository.Row(LocalPasswordPolicySettings.DEFAULTS, instant, actor), false);
        Map<String, Object> mode = LocalAuthModeService.view(new LocalAuthModeRepository.Row("ALL_LOCAL", instant, actor), false);
        Map<String, Object> logs = new java.util.LinkedHashMap<>();
        logs.put("raw_retention_days", 7);
        logs.put("updated_at", instant.toString());
        logs.put("updated_by", actor); // SystemLogSettingsService.view puts the stored actor here, can_write false
        logs.put("can_write", false);

        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));
        var http = mock(HttpServletRequest.class);
        when(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        var server = mock(ServletServerHttpRequest.class);
        when(server.getServletRequest()).thenReturn(http);
        for (Map<String, Object> body : java.util.List.of(policy, mode, logs)) {
            assertEquals(false, body.get("can_write"));
            @SuppressWarnings("unchecked")
            Map<String, Object> masked = (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, server, null);
            assertFalse(masked.toString().contains(actor), masked.toString());
            assertTrue(String.valueOf(masked.get("updated_by")).startsWith("USR-"), masked.toString());
            assertEquals(false, masked.get("can_write"));
        }
        // A system literal stays verbatim.
        Map<String, Object> sys = new java.util.LinkedHashMap<>(mode);
        sys.put("updated_by", "migration:V203");
        @SuppressWarnings("unchecked")
        Map<String, Object> maskedSys = (Map<String, Object>) advice.beforeBodyWrite(sys, null, null, null, server, null);
        assertEquals("migration:V203", maskedSys.get("updated_by"));
        assertNull(maskedSys.get("hostname"));
    }

    private static MockHttpServletRequest req(String method, String route) {
        var request = new MockHttpServletRequest(method, route);
        request.setServletPath(route);
        request.setCookies(new jakarta.servlet.http.Cookie("ui2_session", "synthetic-cookie"));
        request.addHeader("X-CSRF-Token", "synthetic-csrf");
        request.addHeader("Origin", "https://example.invalid");
        return request;
    }

    private static GateChainInterceptor gate(boolean flag, String role, boolean machine) {
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
        var gate = new GateChain(sessions, new ActionRegistry(flag), rbac,
                mock(com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository.class));
        return new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE, Set.of());
    }
}
