package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Settings;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogSettingsRepository.Row;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogSettingsService;

/**
 * The settings routes' own checks behind the gate: the read says whether the caller may write, a write by anyone
 * without the write action (or by the aiview persona) stores nothing, a body with an unknown key, a wrong type or a
 * value outside the bounds stores nothing and names every problem, and a valid subset is merged over the stored row
 * and written under the caller's fingerprint.
 */
class SystemLogSettingsControllerTest {
    private static final Instant STORED_AT = Instant.parse("2026-10-20T09:00:00Z");
    SystemLogSettingsRepository repository;
    RbacEvaluator rbac;
    SystemLogSettingsController controller;
    String role;
    Row stored;

    @BeforeEach
    void setUp() {
        repository = mock(SystemLogSettingsRepository.class);
        stored = new Row(Settings.DEFAULTS, STORED_AT, "system-log-migration");
        when(repository.read()).thenAnswer(call -> stored);
        when(repository.write(anyString(), any(Settings.class))).thenAnswer(call -> {
            stored = new Row(call.getArgument(1), STORED_AT.plusSeconds(60), call.getArgument(0));
            return stored;
        });
        rbac = mock(RbacEvaluator.class);
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
        when(rbac.evaluateAny(anyString(), anySet(), any(Instant.class))).thenAnswer(call ->
                ((Set<?>) call.getArgument(1)).contains(role) ? permitted : denied);
        controller = new SystemLogSettingsController(new SystemLogSettingsService(repository), new ActionRegistry(), rbac);
    }

    private MockHttpServletRequest request(String roleToken, boolean replayViewer) {
        role = roleToken;
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-" + roleToken);
        if (replayViewer) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        return request;
    }

    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, value);
        return body;
    }

    @Test
    void theReadAnswersEveryDocumentedKeyItsBoundsTheLastChangeAndTheCallersWritePermission() {
        var response = controller.read(request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> view = response.getBody();
        assertEquals(30, view.get("raw_retention_days"));
        assertEquals(2L * 1024 * 1024 * 1024, view.get("device_cap_bytes"));
        assertEquals(7, view.get("hcp_retention_days"));
        assertEquals(30, view.get("spike_data_retention_days"));
        assertEquals(512L * 1024 * 1024, view.get("spike_data_cap_bytes"));
        assertEquals(180, view.get("ledger_retention_days"));
        assertEquals(false, view.get("include_management_servers"));
        assertEquals(false, view.get("spike_data_enabled"));
        assertEquals(STORED_AT.toString(), view.get("updated_at"));
        assertEquals("system-log-migration", view.get("updated_by"));
        assertEquals(true, view.get("can_write"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> bounds = (Map<String, Map<String, Object>>) view.get("bounds");
        assertEquals(8, bounds.size());
        assertEquals(Map.of("type", "boolean"), bounds.get("spike_data_enabled"));
        assertEquals(1L, bounds.get("raw_retention_days").get("min"));
        assertEquals(3650L, bounds.get("raw_retention_days").get("max"));
        assertEquals(64L * 1024 * 1024, bounds.get("device_cap_bytes").get("min"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void aReaderWithoutTheWriteActionIsToldSoAndTheAiviewPersonaNever() {
        assertEquals(false, controller.read(request(RoleToken.OPERATION_ADMIN, false)).getBody().get("can_write"));
        assertEquals(false, controller.read(request(RoleToken.SECURITY_ADMIN, true)).getBody().get("can_write"));
    }

    @Test
    void securityAdminEnablesSpikeDataAndTheWriteCarriesTheActorAndTheMergedRow() {
        var response = controller.write(body("spike_data_enabled", true), request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("spike_data_enabled"));
        assertEquals(false, response.getBody().get("include_management_servers"), "untouched keys keep their stored value");
        assertEquals(30, response.getBody().get("raw_retention_days"));
        assertEquals("actor-" + RoleToken.SECURITY_ADMIN, response.getBody().get("updated_by"));
        assertEquals(STORED_AT.plusSeconds(60).toString(), response.getBody().get("updated_at"));
        verify(repository).write("actor-" + RoleToken.SECURITY_ADMIN, new Settings(30, false, Settings.DEFAULT_DEVICE_CAP_BYTES,
                7, 30, Settings.DEFAULT_SPIKE_DATA_CAP_BYTES, 180, true));
    }

    @Test
    void severalKeysAreWrittenTogetherAndIntegersMayArriveAsAnyWholeJsonNumber() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("raw_retention_days", 45);
        body.put("device_cap_bytes", 4294967296L);
        body.put("ledger_retention_days", 90.0);
        body.put("include_management_servers", true);
        var response = controller.write(body, request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(200, response.getStatusCode().value());
        verify(repository).write("actor-" + RoleToken.SECURITY_ADMIN, new Settings(45, true, 4294967296L, 7, 30,
                Settings.DEFAULT_SPIKE_DATA_CAP_BYTES, 90, false));
    }

    @Test
    void operatorViewerOperationAdminAndAiviewCannotWriteAndNothingIsStored() {
        for (String r : List.of(RoleToken.OPERATOR, RoleToken.VIEWER, RoleToken.OPERATION_ADMIN, RoleToken.REPLAY_VIEWER)) {
            var response = controller.write(body("spike_data_enabled", true), request(r, false));
            assertEquals(403, response.getStatusCode().value(), r);
            assertEquals("SYSTEM_LOG_SETTINGS_NOT_PERMITTED", response.getBody().get("code"), r);
        }
        // A security_admin credential carried by the aiview machine session is still aiview.
        assertEquals(403, controller.write(body("spike_data_enabled", true), request(RoleToken.SECURITY_ADMIN, true))
                .getStatusCode().value());
        // No session at all.
        var anonymous = new MockHttpServletRequest();
        assertEquals(403, controller.write(body("spike_data_enabled", true), anonymous).getStatusCode().value());
        verify(repository, never()).write(anyString(), any(Settings.class));
    }

    @Test
    void anUnknownKeyAWrongTypeAndAValueOutsideTheBoundsAreAllNamedAndNothingIsStored() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spike_data_enabled", "yes");
        body.put("raw_retention_days", 0);
        body.put("device_cap_bytes", 2.5);
        body.put("hcp_retention_days", "7");
        body.put("retention_days", 30);
        body.put("ledger_retention_days", 365);
        var response = controller.write(body, request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("SYSTEM_LOG_SETTINGS_INVALID", response.getBody().get("code"));
        @SuppressWarnings("unchecked")
        List<String> problems = (List<String>) response.getBody().get("problems");
        assertEquals(List.of(
                "spike_data_enabled: must be true or false",
                "raw_retention_days: must be between 1 and 3650",
                "device_cap_bytes: must be a whole number",
                "hcp_retention_days: must be a whole number",
                "retention_days: not a system-log setting"), problems);
        verify(repository, never()).write(anyString(), any(Settings.class));
    }

    @Test
    void anEmptyOrMissingBodyIsRefusedSoAMisspeltKeyNeverSilentlyDoesNothing() {
        assertEquals(400, controller.write(Map.of(), request(RoleToken.SECURITY_ADMIN, false)).getStatusCode().value());
        var missing = controller.write(null, request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(400, missing.getStatusCode().value());
        assertEquals("SYSTEM_LOG_SETTINGS_EMPTY", missing.getBody().get("code"));
        verify(repository, never()).write(anyString(), any(Settings.class));
    }

    @Test
    void theCapBoundsAreTheV160CheckConstraints() {
        for (var tooSmall : Map.of("device_cap_bytes", 64L * 1024 * 1024 - 1, "spike_data_cap_bytes", 1024L * 1024 - 1).entrySet()) {
            var response = controller.write(body(tooSmall.getKey(), tooSmall.getValue()), request(RoleToken.SECURITY_ADMIN, false));
            assertEquals(400, response.getStatusCode().value(), tooSmall.getKey());
        }
        var tooLarge = controller.write(body("spike_data_cap_bytes", 1024L * 1024 * 1024 * 1024 + 1), request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(400, tooLarge.getStatusCode().value());
        var atBound = controller.write(body("spike_data_cap_bytes", 1024L * 1024), request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(200, atBound.getStatusCode().value());
        assertTrue(response(atBound).containsKey("bounds"));
        assertFalse(response(atBound).containsKey("problems"));
        assertNull(response(atBound).get("code"));
    }

    private static Map<String, Object> response(org.springframework.http.ResponseEntity<Map<String, Object>> entity) {
        return entity.getBody();
    }
}
