package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Settings;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPollingRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialView;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.snmp.SnmpPollingSettingsService;

/**
 * Administration > SNMP polling behind the gate: the read reports can_write, aiview sees a masked read-only projection,
 * a write without the action stores nothing, a bad body stores nothing and names every problem, only SNMPv3 credentials
 * from the store and enrolled pilot gateways are accepted, and weak credentials or a switch with no credential warn.
 */
class SnmpPollingSettingsControllerTest {
    private static final Instant AT = Instant.parse("2026-11-24T09:00:00Z");
    SnmpPollingRepository repository;
    RbacEvaluator rbac;
    SnmpPollingSettingsController controller;
    String role;
    Configuration stored;

    private static CredentialView credential(String ref, String name, CredentialKind kind, SnmpSettings snmp) {
        return new CredentialView("id-" + ref, ref, name, kind, kind == CredentialKind.SNMP_V3 ? "synthetic-user" : "admin",
                true, true, AT, AT, snmp);
    }

    @BeforeEach
    void setUp() {
        repository = mock(SnmpPollingRepository.class);
        stored = new Configuration(Settings.DEFAULTS, List.of(Override.vendor("check_point", true, null),
                Override.vendor("palo_alto", true, null)), AT, "snmp-polling-migration");
        when(repository.read()).thenAnswer(call -> stored);
        when(repository.eligibleDevices()).thenReturn(List.of(new Device("dev-cp-1", "check_point", "192.0.2.10", 0, null, null),
                new Device("dev-pan-1", "palo_alto", "192.0.2.20", 0, null, null)));
        when(repository.health(any(Instant.class))).thenReturn(Map.of());
        when(repository.write(anyString(), any(Settings.class), anyList())).thenAnswer(call -> {
            stored = new Configuration(call.getArgument(1), call.getArgument(2), AT.plusSeconds(60), call.getArgument(0));
            return stored;
        });
        rbac = mock(RbacEvaluator.class);
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
        when(rbac.evaluateAny(anyString(), anySet(), any(Instant.class))).thenAnswer(call ->
                ((Set<?>) call.getArgument(1)).contains(role) ? permitted : denied);
        List<CredentialView> store = List.of(
                credential("ref-good", "SNMP pilot user", CredentialKind.SNMP_V3, new SnmpSettings("authPriv", "SHA-256", "AES-128")),
                credential("ref-weak", "Old SNMP user", CredentialKind.SNMP_V3, new SnmpSettings("authPriv", "MD5", "DES")),
                credential("ref-ssh", "SSH admin", CredentialKind.SSH_PASSWORD, null));
        controller = new SnmpPollingSettingsController(new SnmpPollingSettingsService(repository, () -> store),
                new ActionRegistry(), rbac);
    }

    private MockHttpServletRequest request(String roleToken, boolean replayViewer) {
        role = roleToken;
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-" + roleToken);
        if (replayViewer) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        return request;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) body.put((String) kv[i], kv[i + 1]);
        return body;
    }

    @Test
    void theReadShowsDefaultsOnlySnmpV3CredentialsAndCanWriteForTheSecurityAdmin() {
        var view = controller.read(request(RoleToken.SECURITY_ADMIN, false)).getBody();
        assertEquals(false, view.get("enabled"));
        assertEquals(300, view.get("interval_s"));
        assertEquals("VENDORS", view.get("scope_mode"));
        assertEquals(35, view.get("raw_retention_days"));
        assertEquals(400, view.get("hourly_retention_days"));
        assertEquals(true, view.get("can_write"));
        @SuppressWarnings("unchecked")
        var creds = (List<Map<String, Object>>) view.get("credentials");
        assertEquals(List.of("ref-good", "ref-weak"), creds.stream().map(c -> c.get("credential_reference_id")).toList());
        assertEquals(true, creds.get(0).get("suitable"));
        assertEquals(false, creds.get(1).get("suitable"));
        assertFalse(view.toString().contains("synthetic-user"), "no username in the view");
        assertEquals(2, ((List<?>) view.get("eligible_devices")).size());
        assertEquals(false, controller.read(request(RoleToken.OPERATOR, false)).getBody().get("can_write"));
    }

    @Test
    void aiviewSeesAMaskedReadOnlyProjectionAndCannotWrite() {
        stored = new Configuration(new Settings(true, 300, "ref-good", "VENDORS", 35, 400), stored.overrides(), AT, "actor-admin");
        var view = controller.read(request(RoleToken.SECURITY_ADMIN, true)).getBody();
        assertEquals(false, view.get("can_write"));
        assertEquals("CRED-1", view.get("default_credential_reference_id"));
        assertFalse(view.toString().contains("ref-good"));
        assertFalse(view.toString().contains("SNMP pilot user"));
        assertEquals(List.of(), view.get("warnings"));
        var refused = controller.write(body("enabled", false), request(RoleToken.SECURITY_ADMIN, true));
        assertEquals(403, refused.getStatusCode().value());
        verify(repository, never()).write(anyString(), any(), anyList());
    }

    @Test
    void aWriteWithoutTheActionStoresNothing() {
        var refused = controller.write(body("enabled", true), request(RoleToken.OPERATION_ADMIN, false));
        assertEquals(403, refused.getStatusCode().value());
        assertEquals("SNMP_POLLING_SETTINGS_NOT_PERMITTED", refused.getBody().get("code"));
        verify(repository, never()).write(anyString(), any(), anyList());
    }

    @Test
    void aBadBodyStoresNothingAndNamesEveryProblem() {
        var refused = controller.write(body("enabled", "yes", "interval_s", 30, "scope_mode", "ALL", "colour", "red",
                "default_credential_reference_id", "ref-ssh",
                "vendors", List.of(Map.of("vendor", "cisco_asa", "in_scope", true)),
                "devices", List.of(Map.of("device_id", "dev-unknown", "in_scope", true))),
                request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(400, refused.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var problems = (List<String>) refused.getBody().get("problems");
        assertEquals(7, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("interval_s")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("default_credential_reference_id")), "an SSH credential is refused");
        verify(repository, never()).write(anyString(), any(), anyList());

        var empty = controller.write(Map.of(), request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(400, empty.getStatusCode().value());
        var fractional = controller.write(body("interval_s", 300.5), request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(400, fractional.getStatusCode().value());
        var twice = controller.write(body("vendors", List.of(Map.of("vendor", "palo_alto", "in_scope", true),
                Map.of("vendor", "palo_alto", "in_scope", false))), request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(400, twice.getStatusCode().value());
    }

    @Test
    void aValidWriteReplacesTheRowsUnderTheCallerAndWarnsAboutAWeakCredential() {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("device_id", "dev-pan-1");
        device.put("in_scope", false);
        device.put("credential_reference_id", null);
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("vendor", "check_point");
        vendor.put("in_scope", true);
        vendor.put("credential_reference_id", "ref-weak");
        var ok = controller.write(body("enabled", true, "interval_s", 600, "default_credential_reference_id", "ref-good",
                "vendors", List.of(vendor, Map.of("vendor", "palo_alto", "in_scope", true)), "devices", List.of(device)),
                request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(200, ok.getStatusCode().value());
        verify(repository).write(org.mockito.ArgumentMatchers.eq("actor-" + RoleToken.SECURITY_ADMIN),
                org.mockito.ArgumentMatchers.eq(new Settings(true, 600, "ref-good", "VENDORS", 35, 400)),
                org.mockito.ArgumentMatchers.eq(List.of(Override.vendor("check_point", true, "ref-weak"),
                        Override.vendor("palo_alto", true, null), Override.device("dev-pan-1", false, null))));
        var view = ok.getBody();
        assertEquals(true, view.get("enabled"));
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) view.get("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Old SNMP user"));
        assertTrue(warnings.get(0).contains("SNMP_CREDENTIAL_UNSUITABLE"));
    }

    @Test
    void turningTheSwitchOnWithNoCredentialWarnsButIsStored() {
        var ok = controller.write(body("enabled", true), request(RoleToken.SECURITY_ADMIN, false));
        assertEquals(200, ok.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) ok.getBody().get("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("SNMP_NO_CREDENTIAL"));
        assertNull(stored.settings().defaultCredentialReferenceId());
        assertEquals(2, stored.overrides().size(), "keys left out keep their stored rows");
    }
}
