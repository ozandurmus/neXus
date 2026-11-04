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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.TopologyController;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.InMemoryTopologyRepository;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyFixtures;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Path analysis A3 through the real gate chain (PO record 2026-11-03 D-8): {@code topology_read} follows the inventory
 * read and is masked for aiview (no raw address, name, routing instance, interface, zone or note); {@code
 * topology_override_write} and {@code topology_rebuild} are security_admin only and refused to aiview even when the
 * same account also holds security_admin. A refused request never reaches the service: nothing is persisted.
 */
class TopologySecurityRouteTest {
    private static final String COOKIE = "synthetic-session", ACTOR = "synthetic-actor", CSRF = "synthetic-csrf";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final String OVERRIDE_BODY = "{\"action\":\"FORCE_LINK\",\"link_kind\":\"STATIC_L3\","
            + "\"a_device_id\":\"dev-b02\",\"a_context\":\"physical\",\"a_interface_name\":\"eth4\","
            + "\"b_device_id\":\"dev-m09\",\"b_context\":\"vsys1\",\"b_interface_name\":\"ethernet1/1\","
            + "\"reason_code\":\"DUPLICATE_PRIVATE_PREFIX_SAME_NETWORK\",\"note\":\"synthetic override note\"}";

    /** One estate, one repository, many sessions: each fixture call builds a MockMvc acting with a role set. */
    private static final class Estate {
        final InMemoryTopologyRepository repository = new InMemoryTopologyRepository();
        final TopologyService service;
        final byte[] key = String.join("-", "synthetic", "topology", "mask", "key", "0001").getBytes(StandardCharsets.UTF_8);
        final PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(
                new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));

        Estate() {
            var facts = TopologyFixtures.devices(TopologyFixtures.resource("estate.json"));
            var devices = mock(DeviceRepository.class);
            List<DeviceSummaryRecord> summaries = new ArrayList<>();
            for (TopologyInput.DeviceFacts f : facts) {
                summaries.add(new DeviceSummaryRecord(f.deviceId(), "gateway", f.vendor(), DeviceEnrollmentState.ENROLLED,
                        f.hostname(), Optional.empty(), Optional.empty(), Optional.empty(), f.clusterMemberRef(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()));
                when(devices.find(f.deviceId())).thenReturn(Optional.of(new DeviceRecord(f.deviceId(), "gateway",
                        f.vendor(), "manual", Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "cred")));
            }
            when(devices.listAll()).thenReturn(summaries);
            var inventory = mock(DeviceInventoryRepository.class);
            when(inventory.findLatestRuns(anyList())).thenReturn(facts.stream()
                    .flatMap(f -> f.latestRun().stream()).toList());
            service = new TopologyService(devices, inventory, repository,
                    Clock.fixed(Instant.parse("2026-11-04T07:00:00Z"), ZoneOffset.UTC));
        }

        MockMvc as(Set<String> roles) {
            var sessions = mock(SessionRepository.class);
            var rbac = mock(RbacEvaluator.class);
            Instant now = Instant.now();
            var session = new SessionRecord(SessionHasher.hash(COOKIE), ACTOR, CSRF, SessionState.ACTIVE,
                    now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty());
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
            return MockMvcBuilders.standaloneSetup(new TopologyController(service)).setControllerAdvice(advice)
                    .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        }
    }

    private static MockHttpServletRequestBuilder write(String path, String body) {
        return post(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE)).header("X-CSRF-Token", CSRF)
                .header("Origin", "https://example.invalid").contentType("application/json").content(body);
    }

    private static MockHttpServletRequestBuilder read(String path) {
        return get(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE));
    }

    private static JsonNode json(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void theRoutesAndActionsAreExactlyTheD8Gates() {
        assertEquals(ActionRegistry.TOPOLOGY_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/topology"));
        assertEquals(ActionRegistry.TOPOLOGY_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/topology/overrides"));
        assertEquals(ActionRegistry.TOPOLOGY_OVERRIDE_WRITE,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/topology/overrides"));
        assertEquals(ActionRegistry.TOPOLOGY_OVERRIDE_WRITE,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/topology/overrides/*/revoke"));
        assertEquals(ActionRegistry.TOPOLOGY_REBUILD, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/topology/rebuild"));
        var registry = new ActionRegistry();
        var readGate = registry.find(ActionRegistry.TOPOLOGY_READ).orElseThrow();
        assertEquals(registry.find(ActionRegistry.DEVICE_READ).orElseThrow().requiredRoleTokens(),
                readGate.requiredRoleTokens(), "topology read follows the inventory read");
        for (String write : List.of(ActionRegistry.TOPOLOGY_OVERRIDE_WRITE, ActionRegistry.TOPOLOGY_REBUILD)) {
            var action = registry.find(write).orElseThrow();
            assertEquals(Set.of(RoleToken.SECURITY_ADMIN), action.requiredRoleTokens(), write);
            assertTrue(action.consoleSubmittable(), write);
        }
    }

    @Test
    void aSecurityAdministratorRebuildsCreatesAndRevokesAndReadsRaw() throws Exception {
        var estate = new Estate();
        var admin = estate.as(Set.of(RoleToken.SECURITY_ADMIN));
        JsonNode empty = json(admin, read("/api/v2/topology"));
        assertEquals("NOT_BUILT", empty.get("status").asText(), "a read never builds (no side effect)");
        assertTrue(estate.repository.snapshots.isEmpty());

        JsonNode rebuilt = json(admin, write("/api/v2/topology/rebuild", ""));
        assertEquals("BUILT", rebuilt.get("status").asText());
        assertEquals(8, rebuilt.get("snapshot").get("counts").get("devices").asInt(), "the FortiGate is not part of it");
        assertEquals(1, estate.repository.snapshots.size());
        assertEquals(5, estate.repository.conflicts.size());

        JsonNode created = json(admin, write("/api/v2/topology/overrides", OVERRIDE_BODY));
        String id = created.get("override_id").asText();
        assertTrue(id.matches("tlo-[0-9a-f-]{36}"), id);
        assertEquals("PENDING_REBUILD", created.get("override_status").asText());
        assertEquals(List.of(ActionRegistry.TOPOLOGY_OVERRIDE_WRITE), estate.repository.auditedActions);

        json(admin, write("/api/v2/topology/rebuild", ""));
        JsonNode listed = json(admin, read("/api/v2/topology/overrides"));
        assertEquals("APPLIED", listed.get("overrides").get(0).get("override_status").asText());
        assertEquals("synthetic override note", listed.get("overrides").get(0).get("override_note").asText());

        JsonNode raw = json(admin, read("/api/v2/topology"));
        assertEquals("BUILT", raw.get("status").asText());
        assertTrue(raw.get("held_is_latest").asBoolean());
        assertTrue(raw.toString().contains("192.0.2.64/27"), "raw for roles that read inventory raw");

        String revoke = "/api/v2/topology/overrides/" + id + "/revoke";
        json(admin, write(revoke, ""));
        admin.perform(write(revoke, "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERRIDE_ALREADY_REVOKED"));
        admin.perform(write("/api/v2/topology/overrides/tlo-unknown/revoke", "")).andExpect(status().isNotFound());
        assertEquals("REVOKED", json(admin, read("/api/v2/topology/overrides")).get("overrides").get(0)
                .get("override_status").asText(), "revoked, never deleted");
    }

    @Test
    void invalidOverridesAreRefusedWithoutAWrite() throws Exception {
        var estate = new Estate();
        var admin = estate.as(Set.of(RoleToken.SECURITY_ADMIN));
        for (String body : List.of("{}", OVERRIDE_BODY.replace("FORCE_LINK", "DELETE_LINK"),
                OVERRIDE_BODY.replace("\"note\"", "\"command\""),
                OVERRIDE_BODY.replace("\"STATIC_L3\"", "7"),
                OVERRIDE_BODY.replace("}", ",\"assumed_subnet\":\"192.0.2.999/24\"}"),
                OVERRIDE_BODY.replace("}", ",\"assumed_subnet\":\"192.0.2.1\"}"),
                OVERRIDE_BODY.replace("synthetic override note", "x".repeat(501)))) {
            admin.perform(write("/api/v2/topology/overrides", body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        admin.perform(write("/api/v2/topology/overrides", OVERRIDE_BODY.replace("dev-m09", "dev-unknown")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        assertTrue(estate.repository.overrides.isEmpty());
        JsonNode created = json(admin, write("/api/v2/topology/overrides",
                OVERRIDE_BODY.replace("}", ",\"assumed_subnet\":\"192.0.2.77/26\"}")));
        assertEquals("192.0.2.64/26", created.get("assumed_subnet").asText(), "canonical network, host bits cleared");
    }

    @Test
    void everyOtherRoleIsRefusedTheWritesBeforeTheService() throws Exception {
        var estate = new Estate();
        for (Set<String> roles : List.of(Set.of(RoleToken.OPERATION_ADMIN), Set.of(RoleToken.ONBOARDING_ADMIN),
                Set.of(RoleToken.OPERATOR), Set.of(RoleToken.VIEWER), Set.of(RoleToken.REPLAY_VIEWER),
                Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER))) {
            var mvc = estate.as(roles);
            mvc.perform(write("/api/v2/topology/rebuild", "")).andExpect(status().isForbidden());
            mvc.perform(write("/api/v2/topology/overrides", OVERRIDE_BODY)).andExpect(status().isForbidden());
            mvc.perform(write("/api/v2/topology/overrides/tlo-x/revoke", "")).andExpect(status().isForbidden());
            // ...while the read stays open, like the inventory read.
            mvc.perform(read("/api/v2/topology")).andExpect(status().isOk());
        }
        assertTrue(estate.repository.snapshots.isEmpty());
        assertTrue(estate.repository.overrides.isEmpty());
    }

    @Test
    void aiviewIsRefusedEveryWriteEvenWhenItAlsoHoldsSecurityAdmin() throws Exception {
        var estate = new Estate();
        var both = estate.as(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER));
        both.perform(write("/api/v2/topology/rebuild", "")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_ROLE"));
        both.perform(write("/api/v2/topology/overrides", OVERRIDE_BODY)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_ROLE"));
        estate.as(Set.of(RoleToken.SECURITY_ADMIN)).perform(write("/api/v2/topology/overrides", OVERRIDE_BODY))
                .andExpect(status().isOk());
        String id = estate.repository.overrides.get(0).overrideId();
        both.perform(write("/api/v2/topology/overrides/" + id + "/revoke", "")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_ROLE"));
        assertTrue(estate.repository.snapshots.isEmpty());
        assertTrue(estate.repository.overrides.get(0).active());
    }

    @Test
    void aMissingCsrfTokenOrOriginIsRefusedBeforeTheService() throws Exception {
        var estate = new Estate();
        var admin = estate.as(Set.of(RoleToken.SECURITY_ADMIN));
        String path = "/api/v2/topology/rebuild";
        admin.perform(post(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE))
                .header("Origin", "https://example.invalid")).andExpect(status().isUnauthorized());
        admin.perform(post(path).servletPath(path).cookie(new Cookie("ui2_session", COOKIE))
                .header("X-CSRF-Token", CSRF)).andExpect(status().isUnauthorized());
        assertTrue(estate.repository.snapshots.isEmpty());
    }

    @Test
    void aiviewReadsTheMaskedProjectionWithStableIdsAndNoNote() throws Exception {
        var estate = new Estate();
        var admin = estate.as(Set.of(RoleToken.SECURITY_ADMIN));
        json(admin, write("/api/v2/topology/rebuild", ""));
        json(admin, write("/api/v2/topology/overrides", OVERRIDE_BODY));
        json(admin, write("/api/v2/topology/rebuild", ""));
        JsonNode raw = json(admin, read("/api/v2/topology"));

        var aiview = estate.as(Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER));
        var response = aiview.perform(read("/api/v2/topology")).andExpect(status().isOk())
                .andExpect(header().string(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER, "true")).andReturn();
        String maskedText = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode masked = JSON.readTree(maskedText);

        String fixture = TopologyFixtures.resource("estate.json").toString();
        Matcher ips = IPV4.matcher(fixture);
        while (ips.find()) {
            String ip = ips.group();
            if (!ip.equals("0.0.0.0")) {
                assertFalse(maskedText.contains(ip), "raw address in the aiview body");
            }
        }
        Matcher maskedIps = IPV4.matcher(maskedText);
        while (maskedIps.find()) {
            assertTrue(maskedIps.group().matches("(?:24[0-9]|25[0-5])\\..*"), "masked into reserved space");
        }
        for (String name : List.of("FW-TANGO-04", "FW-JULIET-06", "FW-BRAVO-02", "FW-MIKE-09", "cls-ref-romeo",
                "cls-ref-sierra", "vr-default", "vr-a\"", "ethernet1/1", "\"eth1\"", "\"wrp128\"", "\"trust\"", "\"dmz\"")) {
            assertFalse(maskedText.contains(name), name);
        }
        // Opaque ids pass through unchanged, so aiview can still relate nodes, segments and conflicts.
        assertEquals(raw.get("snapshot_id"), masked.get("snapshot_id"));
        assertEquals(raw.get("nodes").size(), masked.get("nodes").size());
        for (int i = 0; i < raw.get("nodes").size(); i++) {
            assertEquals(raw.get("nodes").get(i).get("node_id"), masked.get("nodes").get(i).get("node_id"));
            assertEquals(raw.get("nodes").get(i).get("flags"), masked.get("nodes").get(i).get("flags"));
        }
        assertEquals(raw.get("conflicts").size(), masked.get("conflicts").size());
        // The interface's virtual router and the node's routing instance share one pseudonym namespace.
        assertTrue(masked.get("nodes").findValuesAsText("routing_context").stream().noneMatch(v -> v.startsWith("vr-")));

        String overridesText = aiview.perform(read("/api/v2/topology/overrides")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode overrides = JSON.readTree(overridesText);
        assertFalse(overrides.get("overrides").get(0).has("override_note"), "D-8: the note never reaches aiview");
        assertFalse(overrides.get("overrides").get(0).has("created_by"));
        assertFalse(overridesText.contains("synthetic override note"));
        assertFalse(overridesText.contains("\"eth4\"") || overridesText.contains("\"ethernet1/1\""));
        assertEquals("APPLIED", overrides.get("overrides").get(0).get("override_status").asText());
    }

    @Test
    void theAdviceWithholdsAnOverrideNoteEvenIfABodyCarriesOne() {
        var estate = new Estate();
        Object masked = estate.advice.maskObject(java.util.Map.of("override_note", "synthetic override note",
                "override_id", "tlo-00000000-0000-4000-8000-000000000001"), null);
        assertEquals(java.util.Collections.singletonMap("override_note", null).get("override_note"),
                ((java.util.Map<?, ?>) masked).get("override_note"));
        assertEquals("tlo-00000000-0000-4000-8000-000000000001", ((java.util.Map<?, ?>) masked).get("override_id"));
    }
}
