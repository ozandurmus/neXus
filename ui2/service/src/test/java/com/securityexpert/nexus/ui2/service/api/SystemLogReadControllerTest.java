package com.securityexpert.nexus.ui2.service.api;

import static com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture.CHUNK;
import static com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * Roles and masking of the system-log text route: masked for every caller unless the caller holds
 * system_log_read_unmasked and explicitly asks; aiview never gets the unmasked view; every unmasked view is a ledger row.
 */
class SystemLogReadControllerTest {
    private static final String RAW = "drop src 192.0.2.17 dst 198.51.100.5\n";
    @TempDir
    Path root;
    SystemLogReadFixture fx;
    RbacEvaluator rbac;
    SystemLogReadController controller;
    String role;

    @BeforeEach
    void setUp() throws Exception {
        fx = new SystemLogReadFixture(root);
        fx.chunk(CHUNK, bytes(RAW));
        rbac = mock(RbacEvaluator.class);
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
        when(rbac.evaluateAny(anyString(), anySet(), any(Instant.class))).thenAnswer(call ->
                ((Set<?>) call.getArgument(1)).contains(role) ? permitted : denied);
        controller = new SystemLogReadController(fx.service(), new ActionRegistry(), rbac);
    }

    private MockHttpServletRequest request(String roleToken, boolean replayViewer) {
        role = roleToken;
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-" + roleToken);
        if (replayViewer) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        return request;
    }

    private String body(org.springframework.http.ResponseEntity<?> response) {
        Object body = response.getBody();
        return body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(body);
    }

    @Test
    void everyRoleGetsTheMaskedViewByDefault() {
        for (String r : List.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN)) {
            var response = controller.text(CHUNK, 0, 0, false, request(r, false));
            assertEquals(200, response.getStatusCode().value(), r);
            assertEquals("true", response.getHeaders().getFirst("X-Syslog-Masked"), r);
            assertFalse(body(response).contains("192.0.2.17"), r);
        }
        assertTrue(fx.ledgerRows.isEmpty());
    }

    @Test
    void operationAndSecurityAdministratorsMayAskForUnmaskedAndEachViewIsOnTheLedger() {
        for (String r : List.of(RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN)) {
            var response = controller.text(CHUNK, 0, 0, true, request(r, false));
            assertEquals(200, response.getStatusCode().value(), r);
            assertEquals("false", response.getHeaders().getFirst("X-Syslog-Masked"), r);
            assertEquals(RAW, body(response), r);
        }
        assertEquals(2, fx.ledgerRows.size());
        assertEquals(List.of("actor-" + RoleToken.OPERATION_ADMIN, "actor-" + RoleToken.SECURITY_ADMIN),
                fx.ledgerRows.stream().map(SystemLogReadFixture.LedgerRow::actor).toList());
        assertTrue(fx.ledgerRows.stream().allMatch(row -> ActionRegistry.SYSTEM_LOG_READ_UNMASKED.equals(row.action())));
    }

    @Test
    void anOperatorAskingForUnmaskedIsRefusedAndNothingIsRecorded() {
        var response = controller.text(CHUNK, 0, 0, true, request(RoleToken.OPERATOR, false));
        assertEquals(403, response.getStatusCode().value());
        assertEquals("SYSTEM_LOG_UNMASKED_NOT_PERMITTED", ((Map<?, ?>) response.getBody()).get("code"));
        assertTrue(fx.ledgerRows.isEmpty());
        assertEquals(0, fx.store.retrievals.get());
    }

    @Test
    void aiviewIsAlwaysMaskedEvenIfItsSessionAlsoHoldsAnAdministratorRole() {
        // The persona is a replay viewer; an administrator binding on the same session must not open the unmasked view.
        var response = controller.text(CHUNK, 0, 0, true, request(RoleToken.SECURITY_ADMIN, true));
        assertEquals(403, response.getStatusCode().value());
        assertTrue(fx.ledgerRows.isEmpty());
        var masked = controller.text(CHUNK, 0, 0, false, request(RoleToken.REPLAY_VIEWER, true));
        assertEquals(200, masked.getStatusCode().value());
        assertEquals("true", masked.getHeaders().getFirst("X-Syslog-Masked"));
        assertFalse(body(masked).contains("192.0.2.17"));
    }

    @Test
    void theCanViewUnmaskedFlagOfTheSourcesRouteFollowsTheSameDecision() {
        when(fx.repository.sources(SystemLogReadFixture.DEVICE)).thenReturn(List.of());
        when(fx.repository.lastJob(SystemLogReadFixture.DEVICE)).thenReturn(Optional.empty());
        assertEquals(true, flag(controller.sources(SystemLogReadFixture.DEVICE, request(RoleToken.OPERATION_ADMIN, false))));
        assertEquals(true, flag(controller.sources(SystemLogReadFixture.DEVICE, request(RoleToken.SECURITY_ADMIN, false))));
        assertEquals(false, flag(controller.sources(SystemLogReadFixture.DEVICE, request(RoleToken.OPERATOR, false))));
        assertEquals(false, flag(controller.sources(SystemLogReadFixture.DEVICE, request(RoleToken.REPLAY_VIEWER, true))));
        assertEquals(false, flag(controller.sources(SystemLogReadFixture.DEVICE, request(RoleToken.SECURITY_ADMIN, true))));
    }

    private static Object flag(org.springframework.http.ResponseEntity<Map<String, Object>> response) {
        return response.getBody().get("can_view_unmasked");
    }

    @Test
    void aSessionWithoutAnActorFingerprintIsRefused() {
        var response = controller.text(CHUNK, 0, 0, false, new MockHttpServletRequest());
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void theTextResponseCarriesPagingFactsAndIsNeverCached() {
        var response = controller.text(CHUNK, 0, 5, false, request(RoleToken.OPERATOR, false));
        assertEquals("text/plain;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertEquals("0", response.getHeaders().getFirst("X-Syslog-Offset"));
        assertEquals(String.valueOf(RAW.length()), response.getHeaders().getFirst("X-Syslog-Next-Offset"));
        assertEquals("true", response.getHeaders().getFirst("X-Syslog-Eof"));
        assertEquals("1", response.getHeaders().getFirst("X-Syslog-Lines"));
        assertEquals("1024", response.getHeaders().getFirst("X-Syslog-Page-Limit"));
        assertEquals("cp_messages", response.getHeaders().getFirst("X-Syslog-Source"));
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }

    @Test
    void aManagementServerRefusalIsAConflictWithTheDisabledReason() {
        fx.device("mgmt-1", "management_server", "check_point");
        var response = controller.hcp("mgmt-1");
        assertEquals(409, response.getStatusCode().value());
        assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", response.getBody().get("code"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("management servers"));
        assertEquals(409, controller.chunks("mgmt-1", null, null, 10).getStatusCode().value());
        assertEquals(409, controller.gaps("mgmt-1", 10).getStatusCode().value());
        assertEquals(409, controller.crashFiles("mgmt-1", 10).getStatusCode().value());
    }

    @Test
    void theListRoutesPassTheirFiltersAndAnswerJson() {
        when(fx.repository.chunks(SystemLogReadFixture.DEVICE, Optional.of("cp_messages"), Optional.of("2026-10-17"), 5))
                .thenReturn(List.<SystemLogReadRepository.ChunkRow>of());
        var response = controller.chunks(SystemLogReadFixture.DEVICE, "cp_messages", "2026-10-17", 5);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(), response.getBody().get("chunks"));
        assertEquals(400, controller.chunks(SystemLogReadFixture.DEVICE, "bad/source", null, 5).getStatusCode().value());
    }

    @Test
    void overHttpTheTextRouteIsPlainTextWithPagingHeadersAndARefusalIsJson() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        role = RoleToken.OPERATOR;
        var ok = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v2/system-logs/chunks/" + CHUNK + "/text").param("offset", "0")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-op").accept("*/*"))
                .andReturn().getResponse();
        assertEquals(200, ok.getStatus());
        assertTrue(ok.getContentType().startsWith("text/plain"), ok.getContentType());
        assertEquals("true", ok.getHeader("X-Syslog-Masked"));
        assertEquals("true", ok.getHeader("X-Syslog-Eof"));
        assertEquals("nosniff", ok.getHeader("X-Content-Type-Options"));
        assertFalse(ok.getContentAsString(StandardCharsets.UTF_8).contains("192.0.2.17"));

        var refused = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v2/system-logs/chunks/" + CHUNK + "/text").param("unmasked", "true")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-op").accept("application/json"))
                .andReturn().getResponse();
        assertEquals(403, refused.getStatus());
        assertTrue(refused.getContentType().startsWith("application/json"), refused.getContentType());
        assertTrue(refused.getContentAsString().contains("SYSTEM_LOG_UNMASKED_NOT_PERMITTED"));

        var disabled = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v2/system-logs/dev-x/sources"))
                .andReturn().getResponse();
        assertEquals(404, disabled.getStatus());
        assertTrue(disabled.getContentAsString().contains("DEVICE_NOT_FOUND"));
    }
}
