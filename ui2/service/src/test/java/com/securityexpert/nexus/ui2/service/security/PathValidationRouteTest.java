package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.JobWindowAdvice;
import com.securityexpert.nexus.ui2.service.api.PathValidationController;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathGoldenAccess;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyFixtures;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.pathanalysis.validation.InMemoryPathValidationRepository;
import com.securityexpert.nexus.ui2.service.pathanalysis.validation.PathValidationService;

/**
 * Stage A6 through the real gate chain over the A4 chain estate (FW-TANGO-04, FW-BRAVO-02; documentation addresses):
 * the route and its roles, aiview under the CLASS_0 diagnostic allowance (masked input only, machine session allowed),
 * the closed body (no command, gate, context, zone, virtual router or rule ever accepted), the audit row, the job
 * window and the read-back.
 */
class PathValidationRouteTest {
    private static final String COOKIE = "synthetic-session", ACTOR = "synthetic-actor", CSRF = "synthetic-csrf";
    private static final String PATH = "/api/v2/path-analysis/validate";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HTTPS = "{\"src\":\"198.51.100.20\",\"dst\":\"203.0.113.200\",\"protocol\":\"tcp\",\"port\":443}";
    /** 06:05 Europe/Istanbul: inside the 06:00 job window. */
    private static final Instant IN_WINDOW = Instant.parse("2026-11-04T03:05:00Z");

    final class Harness {
        final PathAnalysisRouteTest.Estate estate = new PathAnalysisRouteTest.Estate("chain.json").built();
        final InMemoryPathValidationRepository repository = new InMemoryPathValidationRepository();
        final JobAdmissionService admission = mock(JobAdmissionService.class);
        final PathValidationService validations;

        Harness(Instant now) {
            var facts = TopologyFixtures.devices(PathGoldenAccess.estate("chain.json"));
            var inventory = mock(DeviceInventoryRepository.class);
            when(inventory.findLatestRun(anyString())).thenAnswer(call -> facts.stream()
                    .filter(f -> f.deviceId().equals(call.getArgument(0))).findFirst()
                    .flatMap(TopologyInput.DeviceFacts::latestRun));
            when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(call -> new AdmissionResult.Admitted("job-" + call.getArgument(1)));
            validations = new PathValidationService(repository, admission, inventory,
                    new JobWindowPolicy(Clock.fixed(now, ZoneOffset.UTC), 30));
        }

        MockMvc as(Set<String> roles, boolean machine) {
            var sessions = mock(SessionRepository.class);
            var rbac = mock(RbacEvaluator.class);
            Instant now = Instant.now();
            var session = new SessionRecord(SessionHasher.hash(COOKIE), ACTOR, CSRF, SessionState.ACTIVE, now, now,
                    now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty(),
                    machine);
            when(sessions.findBySessionId(eq(session.sessionId()), any(Instant.class))).thenReturn(Optional.of(session));
            when(rbac.evaluateAny(eq(ACTOR), anySet(), any(Instant.class))).thenAnswer(call -> {
                Set<String> required = call.getArgument(1);
                return new RbacEvaluator.Decision(required.stream().anyMatch(roles::contains)
                        ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
            });
            when(rbac.evaluate(eq(ACTOR), any(), any(Instant.class))).thenAnswer(call -> {
                Optional<String> role = call.getArgument(1);
                boolean permitted = role.isEmpty() || role.filter(roles::contains).isPresent();
                return new RbacEvaluator.Decision(permitted ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                        Optional.of("test"), Optional.empty(), Optional.empty());
            });
            var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
            var controller = new PathValidationController(estate.service, validations, estate.audit, estate.bulkhead);
            return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(estate.advice, new JobWindowAdvice())
                    .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        }

        JsonNode lastAudit() throws Exception {
            ArgumentCaptor<Optional<String>> evidence = ArgumentCaptor.forClass(Optional.class);
            verify(estate.audit, atLeastOnce()).insert(anyString(), anyString(), eq(ActionRegistry.PATH_ANALYSIS_VALIDATE),
                    any(), any(), any(), evidence.capture(), any());
            return JSON.readTree(evidence.getValue().orElseThrow());
        }
    }

    static MockHttpServletRequestBuilder submit(String body) {
        return post(PATH).servletPath(PATH).cookie(new Cookie("ui2_session", COOKIE)).header("X-CSRF-Token", CSRF)
                .header("Origin", "https://example.invalid").contentType("application/json").content(body);
    }

    static JsonNode json(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void theRoutesUseTheValidateActionHeldByTheTwoAdminsAndAiviewOnly() {
        assertEquals(ActionRegistry.PATH_ANALYSIS_VALIDATE, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST " + PATH));
        assertEquals(ActionRegistry.PATH_ANALYSIS_VALIDATE, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET " + PATH + "/*"));
        var action = new ActionRegistry().find(ActionRegistry.PATH_ANALYSIS_VALIDATE).orElseThrow();
        assertTrue(action.consoleSubmittable());
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.OPERATION_ADMIN, RoleToken.REPLAY_VIEWER),
                action.requiredRoleTokens());
    }

    @Test
    void anAdministratorSubmitsTheComputedPathAndGetsHopStatesOnly() throws Exception {
        var h = new Harness(IN_WINDOW);
        for (Set<String> roles : List.of(Set.of(RoleToken.SECURITY_ADMIN), Set.of(RoleToken.OPERATION_ADMIN))) {
            JsonNode body = json(h.as(roles, false).perform(submit(HTTPS)).andExpect(status().isAccepted()).andReturn());
            assertEquals("PENDING", body.get("result").asText());
            assertEquals(2, body.get("hops").size());
            assertTrue(body.get("validation_id").asText().matches("pv-[0-9a-f-]{36}"));
            for (String raw : List.of("198.51.100.", "203.0.113.", "192.0.2.", "eth", "ZONE-", "vr-main", "RULE-", "NAT-"))
                assertFalse(body.toString().contains(raw), raw);
            JsonNode audit = h.lastAudit();
            assertEquals("SUBMITTED", audit.get("validation").asText());
            assertEquals(body.get("validation_id").asText(), audit.get("validation_id").asText());
            assertEquals(2, audit.get("hops_runnable").asInt());
            assertFalse(audit.get("query").get("src").asText().equals("198.51.100.20"), "the audit query is masked");

            JsonNode read = json(h.as(roles, false).perform(get(PATH + "/" + body.get("validation_id").asText())
                    .servletPath(PATH + "/" + body.get("validation_id").asText()).cookie(new Cookie("ui2_session", COOKIE)))
                    .andExpect(status().isOk()).andReturn());
            assertEquals(body.get("validation_id"), read.get("validation_id"));
        }
        verify(h.admission, times(4)).submit(eq(PathValidation.CAPABILITY), anyString(), anyString(), eq(ACTOR),
                eq(ActionRegistry.PATH_ANALYSIS_VALIDATE));
    }

    @Test
    void otherRolesAreRefusedByTheGate() throws Exception {
        var h = new Harness(IN_WINDOW);
        for (String role : List.of(RoleToken.OPERATOR, RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN))
            h.as(Set.of(role), false).perform(submit(HTTPS)).andExpect(status().isForbidden());
        verifyNoInteractions(h.admission);
        assertTrue(h.repository.heads.isEmpty());
    }

    @Test
    void theBodyIsTheQueryOnlyAndNeverCarriesCommandOrDeviceParameters() throws Exception {
        var h = new Harness(IN_WINDOW);
        MockMvc admin = h.as(Set.of(RoleToken.SECURITY_ADMIN), false);
        for (int i = 0; i < 6; i++) admin.perform(submit("{}")).andExpect(status().isBadRequest());
        admin.perform(submit(HTTPS)).andExpect(status().isTooManyRequests()); // 6 per minute per actor
        String query = HTTPS.substring(0, HTTPS.length() - 1);
        for (String extra : List.of("\"command\":\"ip -4 route get 203.0.113.10\"", "\"gate_id\":\"path_validation_cp_route_get\"",
                "\"vsid\":\"2\"", "\"from_zone\":\"ZONE-TRANSIT\"", "\"virtual_router\":\"vr-main\"", "\"rule\":\"RULE-WEB-01\"",
                "\"device_id\":\"dev-t04\"", "\"plan\":{}", "\"branch_id\":\"b1; reboot\"", "\"branch_id\":1"))
            h.as(Set.of(RoleToken.SECURITY_ADMIN), false).perform(submit(query + "," + extra + "}")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        h.as(Set.of(RoleToken.SECURITY_ADMIN), false).perform(submit("{\"src\":\"198.51.100.20\",\"dst\":\"203.0.113.200\",\"protocol\":\"tcp\",\"port\":443,"
                + "\"branch_id\":\"b7\"}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
        verifyNoInteractions(h.admission);
        assertTrue(h.repository.heads.isEmpty());
    }

    @Test
    void aiviewValidatesWithMaskedValuesIncludingFromItsMachineSessionAndIsRefusedRawOnes() throws Exception {
        var h = new Harness(IN_WINDOW);
        String src = h.estate.shown("198.51.100.1/26", "198.51.100.20");
        String dst = h.estate.shown("203.0.113.192/26", "203.0.113.200");
        String masked = "{\"src\":\"" + src + "\",\"dst\":\"" + dst + "\",\"protocol\":\"tcp\",\"port\":443}";
        for (boolean machine : List.of(false, true)) {
            JsonNode body = json(h.as(Set.of(RoleToken.REPLAY_VIEWER), machine).perform(submit(masked))
                    .andExpect(status().isAccepted()).andReturn());
            assertEquals(2, body.get("hops").size());
        }
        h.as(Set.of(RoleToken.REPLAY_VIEWER), true).perform(submit(HTTPS)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PSEUDONYM_REFUSED"));
        assertEquals("REFUSED", h.lastAudit().get("validation").asText());
        verify(h.admission, times(4)).submit(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void outsideAJobWindowAiviewIsRefusedBeforeAnythingIsWritten() throws Exception {
        var h = new Harness(Instant.parse("2026-11-04T08:00:00Z")); // 11:00 Istanbul
        String src = h.estate.shown("198.51.100.1/26", "198.51.100.20");
        String dst = h.estate.shown("203.0.113.192/26", "203.0.113.200");
        h.as(Set.of(RoleToken.REPLAY_VIEWER), false).perform(submit("{\"src\":\"" + src + "\",\"dst\":\"" + dst
                + "\",\"protocol\":\"tcp\",\"port\":443}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(JobWindowPolicy.CODE));
        verifyNoInteractions(h.admission);
        assertTrue(h.repository.heads.isEmpty());
    }

    @Test
    void anUnknownValidationIsNotFound() throws Exception {
        var h = new Harness(IN_WINDOW);
        h.as(Set.of(RoleToken.SECURITY_ADMIN), false).perform(get(PATH + "/pv-00000000-0000-0000-0000-000000000000")
                .servletPath(PATH + "/pv-00000000-0000-0000-0000-000000000000").cookie(new Cookie("ui2_session", COOKIE)))
                .andExpect(status().isNotFound());
    }
}
