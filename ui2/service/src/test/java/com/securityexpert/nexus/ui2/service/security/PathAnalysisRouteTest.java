package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.api.PathAnalysisController;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathEngine;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathGoldenAccess;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathQuery;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.InMemoryPathPolicyCatalog;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPolicyBinder;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPseudonymMap;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPseudonymResolver;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathQueryService;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.InMemoryTopologyRepository;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyFixtures;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyService;
import com.securityexpert.nexus.ui2.service.policy.PolicyBulkhead;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Path analysis stage A5a through the real gate chain (PO record 2026-11-03 D-2, D-3, D-8) over the A4 synthetic chain
 * estate (FW-TANGO-04 Check Point, FW-BRAVO-02 Palo Alto; documentation addresses only) and the A4 policies, bound by
 * the production {@link PathPolicyBinder} over a synthetic catalog. Covers RBAC per D-8, the five D-3 conditions at the
 * API, the one masked audit row per query (D-2), the rate limit, the bulkhead, staleness, a held-topology requirement
 * that never builds, and an end-to-end golden of the API body.
 */
class PathAnalysisRouteTest {
    private static final String COOKIE = "synthetic-session", ACTOR = "synthetic-actor", CSRF = "synthetic-csrf";
    private static final String PATH = "/api/v2/path-analysis/query";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");
    private static final String HTTPS = "{\"src\":\"198.51.100.20\",\"dst\":\"203.0.113.200\",\"protocol\":\"tcp\",\"port\":443}";

    /** One estate (topology, policy catalog, maskers shared with the advice, audit capture), many sessions. */
    static final class Estate {
        final InMemoryTopologyRepository repository = new InMemoryTopologyRepository();
        final TopologyService topology;
        final byte[] key = String.join("-", "synthetic", "path", "mask", "key", "0001").getBytes(StandardCharsets.UTF_8);
        final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(key);
        final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(key);
        final PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(ips, names);
        final InMemoryPathPolicyCatalog catalog = new InMemoryPathPolicyCatalog();
        final AuthzDecisionRepository audit = mock(AuthzDecisionRepository.class);
        final PolicyBulkhead bulkhead;
        final PathQueryService service;
        PathAnalysisController controller;

        Estate(String estateName) {
            this(estateName, null, new PolicyBulkhead(4, 50));
        }

        Estate(String estateName, PathPseudonymMap.Masking masking, PolicyBulkhead bulkhead) {
            this.bulkhead = bulkhead;
            var facts = TopologyFixtures.devices(PathGoldenAccess.estate(estateName));
            var devices = mock(DeviceRepository.class);
            List<DeviceSummaryRecord> summaries = new ArrayList<>();
            for (TopologyInput.DeviceFacts f : facts) {
                summaries.add(new DeviceSummaryRecord(f.deviceId(), "gateway", f.vendor(), DeviceEnrollmentState.ENROLLED,
                        f.hostname(), Optional.empty(), Optional.empty(), Optional.empty(), f.clusterMemberRef(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()));
            }
            when(devices.listAll()).thenReturn(summaries);
            var inventory = mock(DeviceInventoryRepository.class);
            when(inventory.findLatestRuns(anyList())).thenReturn(facts.stream().flatMap(f -> f.latestRun().stream()).toList());
            topology = new TopologyService(devices, inventory, repository, Clock.fixed(NOW, ZoneOffset.UTC));
            catalog.add(PathGoldenAccess.cpChain("dev-t04")).add(PathGoldenAccess.panHttpsOnlyWithSourceNat("dev-b02"));
            service = new PathQueryService(topology, new PathPolicyBinder(catalog),
                    new PathPseudonymResolver(masking != null ? masking : PathPseudonymMap.over(ips, names)), ips, names,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        Estate built() {
            topology.rebuild("synthetic-admin");
            return this;
        }

        TopologyGraph graph() {
            return topology.current().orElseThrow();
        }

        MockMvc as(Set<String> roles) {
            return as(roles, false);
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
            controller = new PathAnalysisController(service, audit, bulkhead, rbac, new ActionRegistry());
            return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice)
                    .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
        }

        /** The reason-code JSON of every audit row written so far, in order. */
        List<JsonNode> rows() throws IOException {
            ArgumentCaptor<Optional<String>> reason = ArgumentCaptor.forClass(Optional.class);
            verify(audit, atLeast(0)).insert(anyString(), anyString(), anyString(), any(), any(), any(), reason.capture(), any());
            List<JsonNode> out = new ArrayList<>();
            for (Optional<String> r : reason.getAllValues()) {
                out.add(JSON.readTree(r.orElseThrow()));
            }
            return out;
        }

        /** What aiview was shown for a host inside a subnet it read (the subnet is registered, then the host masked). */
        String shown(String subnet, String host) {
            ips.mask(subnet);
            return ips.mask(host);
        }
    }

    static MockHttpServletRequestBuilder query(String body) {
        return post(PATH).servletPath(PATH).cookie(new Cookie("ui2_session", COOKIE)).header("X-CSRF-Token", CSRF)
                .header("Origin", "https://example.invalid").contentType("application/json").content(body);
    }

    static String body(String src, String dst, int port) {
        return "{\"src\":\"" + src + "\",\"dst\":\"" + dst + "\",\"protocol\":\"tcp\",\"port\":" + port + "}";
    }

    static MvcResult ok(MockMvc mvc, String body) throws Exception {
        return mvc.perform(query(body)).andExpect(status().isOk()).andReturn();
    }

    static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---- gate --------------------------------------------------------------------------------------------------------

    @Test
    void theRouteAndActionFollowTheInventoryRead() {
        assertEquals(ActionRegistry.PATH_ANALYSIS_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST " + PATH));
        var registry = new ActionRegistry();
        var action = registry.find(ActionRegistry.PATH_ANALYSIS_READ).orElseThrow();
        assertEquals(registry.find(ActionRegistry.DEVICE_READ).orElseThrow().requiredRoleTokens(), action.requiredRoleTokens(),
                "D-8: roles that read inventory query paths");
        assertTrue(action.consoleSubmittable());
    }

    @Test
    void d8EveryInventoryRoleQueriesRawAndAiviewOnlyMasked() throws Exception {
        var estate = new Estate("chain.json").built();
        for (Set<String> roles : List.of(Set.of(RoleToken.VIEWER), Set.of(RoleToken.OPERATOR),
                Set.of(RoleToken.OPERATION_ADMIN), Set.of(RoleToken.SECURITY_ADMIN), Set.of(RoleToken.ONBOARDING_ADMIN))) {
            var result = ok(estate.as(roles), HTTPS);
            assertNull(result.getResponse().getHeader(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER), roles.toString());
            JsonNode body = json(result);
            assertEquals("ALLOWED", body.get("verdict").asText(), roles.toString());
            assertEquals("198.51.100.20", body.get("query").get("src").asText(), "raw for " + roles);
        }
        // aiview submitting a raw value is refused...
        for (Set<String> roles : List.of(Set.of(RoleToken.REPLAY_VIEWER), Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER),
                Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER))) {
            estate.as(roles).perform(query(HTTPS)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PSEUDONYM_REFUSED"));
        }
        // ...and the same question in masked values is answered, masked.
        String src = estate.shown("198.51.100.1/26", "198.51.100.20");
        String dst = estate.shown("203.0.113.192/26", "203.0.113.200");
        var result = ok(estate.as(Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER)), body(src, dst, 443));
        assertEquals("true", result.getResponse().getHeader(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER));
        JsonNode masked = json(result);
        assertEquals("ALLOWED", masked.get("verdict").asText());
        assertEquals(src, masked.get("query").get("src").asText(), "aiview sees its own masked value back");
        assertEquals(dst, masked.get("query").get("dst").asText());
    }

    @Test
    void ruleIdentityFollowsPolicyReadAndVerdictsDoNot() throws Exception {
        var estate = new Estate("chain.json").built();
        String denied = body("198.51.100.20", "203.0.113.200", 22);
        JsonNode shown = json(ok(estate.as(Set.of(RoleToken.SECURITY_ADMIN)), denied));
        assertEquals("Traffic is DENIED at hop 2 by rule 2", shown.get("executive_summary").get("headline").asText());
        assertFalse(shown.findValuesAsText("rule_name").isEmpty());
        assertEquals(shown.toString(), json(ok(estate.as(Set.of(RoleToken.ONBOARDING_ADMIN)), denied)).toString(),
                "every policy_read role sees the rules");
        for (Set<String> roles : List.of(Set.of(RoleToken.VIEWER), Set.of(RoleToken.OPERATOR), Set.of(RoleToken.OPERATION_ADMIN))) {
            JsonNode withheld = json(ok(estate.as(roles), denied));
            String text = withheld.toString();
            for (String key : List.of("\"rule_id\"", "\"rule_number\"", "\"rule_name\"", "\"section_id\"", "\"section_name\"")) {
                assertFalse(text.contains(key), roles + " " + key);
            }
            for (String name : List.of("RULE-r1", "RULE-r2", "RULE-WEB-01", "RULE-DENY-01", "NAT-n1", "NAT-SNAT-01", "OBJ-SECTION-01")) {
                assertFalse(text.contains(name), roles + " " + name);
            }
            assertEquals("Traffic is DENIED at hop 2 by a rule (withheld)",
                    withheld.get("executive_summary").get("headline").asText(), roles.toString());
            assertEquals(shown.findValues("rule_id").size(), withheld.findValuesAsText("rule_identity").stream()
                    .filter(PathResultView_RULE_WITHHELD::equals).count(), "every rule map is marked withheld");
            // Verdicts, reasons, actions and everything else stay exactly as the policy_read view shows them.
            for (String key : List.of("verdict", "verdict_reason", "own_verdict", "security_verdict", "security_reason",
                    "nat_status", "nat_reason", "action", "reasons", "termination", "node_id", "src", "dst")) {
                assertEquals(shown.findValues(key), withheld.findValues(key), roles + " " + key);
            }
        }
        // aiview holds policy_read: rule identity stays, masked.
        String src = estate.shown("198.51.100.1/26", "198.51.100.20");
        String dst = estate.shown("203.0.113.192/26", "203.0.113.200");
        JsonNode aiview = json(ok(estate.as(Set.of(RoleToken.REPLAY_VIEWER)), body(src, dst, 22)));
        assertEquals(shown.findValuesAsText("rule_number"), aiview.findValuesAsText("rule_number"));
        assertEquals(shown.findValuesAsText("rule_id"), aiview.findValuesAsText("rule_id"));
        assertEquals(shown.findValuesAsText("rule_name").size(), aiview.findValuesAsText("rule_name").size());
        for (String name : aiview.findValuesAsText("rule_name")) {
            assertFalse(shown.findValuesAsText("rule_name").contains(name), "masked rule name");
        }
        assertFalse(aiview.toString().contains("RULE_WITHHELD_NO_POLICY_READ"));
    }

    private static final String PathResultView_RULE_WITHHELD =
            com.securityexpert.nexus.ui2.service.pathanalysis.query.PathResultView.RULE_WITHHELD;

    @Test
    void theAiviewBodyCarriesNoRawAddressNameOrRuleIdentity() throws Exception {
        var estate = new Estate("chain.json").built();
        String raw = json(ok(estate.as(Set.of(RoleToken.SECURITY_ADMIN)), HTTPS)).toString();
        String src = estate.shown("198.51.100.1/26", "198.51.100.20");
        String dst = estate.shown("203.0.113.192/26", "203.0.113.200");
        String text = ok(estate.as(Set.of(RoleToken.REPLAY_VIEWER)), body(src, dst, 443)).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        Matcher fixture = IPV4.matcher(PathGoldenAccess.estate("chain.json").toString() + raw);
        while (fixture.find()) {
            if (!fixture.group().equals("0.0.0.0")) {
                assertFalse(text.contains("\"" + fixture.group()), "raw address " + fixture.group());
            }
        }
        Matcher addresses = IPV4.matcher(text);
        while (addresses.find()) {
            assertTrue(addresses.group().matches("(?:24[0-9]|25[0-5])\\..*"), addresses.group());
        }
        for (String name : List.of("FW-TANGO-04", "FW-BRAVO-02", "\"eth1\"", "\"eth2\"", "ethernet1/1", "ethernet1/2",
                "vr-main", "ZONE-TRANSIT", "ZONE-DMZ", "RULE-r1", "RULE-WEB-01", "NAT-n1", "NAT-SNAT-01", "LAYER-ALPHA")) {
            assertFalse(text.contains(name), name);
        }
        JsonNode masked = JSON.readTree(text), rawBody = JSON.readTree(raw);
        assertEquals(rawBody.get("snapshot_id"), masked.get("snapshot_id"), "opaque ids relate to the masked topology");
        assertEquals(rawBody.findValuesAsText("node_id"), masked.findValuesAsText("node_id"));
        assertEquals(rawBody.findValuesAsText("rule_id"), masked.findValuesAsText("rule_id"));
        assertEquals(rawBody.findValuesAsText("verdict"), masked.findValuesAsText("verdict"));
    }

    @Test
    void anAiviewMachineSessionMaySendTheReadOnlyQueryButNotARebuild() throws Exception {
        var estate = new Estate("chain.json").built();
        String src = estate.shown("198.51.100.1/26", "198.51.100.20");
        String dst = estate.shown("203.0.113.192/26", "203.0.113.200");
        ok(estate.as(Set.of(RoleToken.REPLAY_VIEWER), true), body(src, dst, 443));
        var sessions = mock(SessionRepository.class);
        Instant now = Instant.now();
        when(sessions.findBySessionId(anyString(), any(Instant.class))).thenReturn(Optional.of(new SessionRecord(
                SessionHasher.hash(COOKIE), ACTOR, CSRF, SessionState.ACTIVE, now, now, now.plusSeconds(60),
                now.plusSeconds(120), Optional.empty(), Optional.empty(), Optional.empty(), true)));
        var gate = new GateChain(sessions, new ActionRegistry(), mock(RbacEvaluator.class), mock(AuthzDecisionRepository.class));
        assertTrue(gate.machineWriteRefusal(Optional.of(COOKIE), "POST", now, ActionRegistry.PATH_ANALYSIS_READ).isEmpty());
        assertTrue(gate.machineWriteRefusal(Optional.of(COOKIE), "POST", now, ActionRegistry.TOPOLOGY_REBUILD).isPresent());
    }

    @Test
    void aMissingCsrfTokenIsRefusedBeforeTheService() throws Exception {
        var estate = new Estate("chain.json").built();
        estate.as(Set.of(RoleToken.SECURITY_ADMIN)).perform(post(PATH).servletPath(PATH)
                .cookie(new Cookie("ui2_session", COOKIE)).header("Origin", "https://example.invalid")
                .contentType("application/json").content(HTTPS)).andExpect(status().isUnauthorized());
        verifyNoInteractions(estate.audit);
    }

    // ---- D-3 at the API ----------------------------------------------------------------------------------------------

    @Test
    void d3RefusalsLookTheSameWhetherRawUnknownOrAmbiguous() throws Exception {
        var estate = new Estate("chain.json").built();
        var aiview = estate.as(Set.of(RoleToken.REPLAY_VIEWER));
        String dst = estate.shown("203.0.113.192/26", "203.0.113.200");
        String rawSrc = aiview.perform(query(body("198.51.100.20", dst, 443))).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String unknown = aiview.perform(query(body("250.1.2.3", dst, 443))).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String rawInterface = aiview.perform(query("{\"src\":\"" + estate.shown("198.51.100.1/26", "198.51.100.20")
                + "\",\"dst\":\"" + dst + "\",\"protocol\":\"tcp\",\"port\":443,\"ingress_node_id\":\""
                + tango(estate).nodeId() + "\",\"ingress_interface_name\":\"eth1\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Ambiguity needs a masked value of two raw values: a masker that collides the client LAN and the DMZ.
        var colliding = new PathPseudonymMap.Masking() {
            final PathPseudonymMap.Masking real = PathPseudonymMap.over(estate.ips, estate.names);

            @Override
            public String peek(String host) {
                return host.startsWith("198.51.100.") || host.startsWith("203.0.113.")
                        ? "245.1.1." + (Integer.parseInt(host.substring(host.lastIndexOf('.') + 1)) & 63) : real.peek(host);
            }

            @Override
            public String maskNetwork(String cidr) {
                return Set.of("198.51.100.0/26", "203.0.113.0/26").contains(cidr) ? "245.1.1.0/26" : real.maskNetwork(cidr);
            }

            @Override
            public String maskPrefixed(String hostWithLength) {
                String host = hostWithLength.substring(0, hostWithLength.indexOf('/'));
                int offset = Integer.parseInt(host.substring(host.lastIndexOf('.') + 1));
                boolean inEither = (host.startsWith("198.51.100.") || host.startsWith("203.0.113.")) && offset < 64;
                return inEither && hostWithLength.endsWith("/26") ? peek(host) + "/26" : real.maskPrefixed(hostWithLength);
            }

            @Override
            public List<String> registeredSubnets() {
                return List.of();
            }

            @Override
            public List<String> handedOutAs(String pseudonym) {
                return real.handedOutAs(pseudonym);
            }

            @Override
            public Optional<String> peekInterface(String rawInterfaceName) {
                return real.peekInterface(rawInterfaceName);
            }
        };
        var collision = new Estate("chain.json", colliding, new PolicyBulkhead(4, 50)).built();
        String ambiguous = collision.as(Set.of(RoleToken.REPLAY_VIEWER)).perform(query(body("245.1.1.20", "245.1.1.21", 443)))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals("{\"code\":\"PSEUDONYM_REFUSED\"}", rawSrc);
        assertEquals(rawSrc, unknown, "unknown looks like raw");
        assertEquals(rawSrc, rawInterface, "a raw interface looks like raw");
        assertEquals(rawSrc, ambiguous, "ambiguous looks like raw");
        // The specific reason is kept for the operators, in the audit row only.
        List<JsonNode> rows = estate.rows();
        assertEquals(List.of("RAW_VALUE_REFUSED", "UNKNOWN_PSEUDONYM", "RAW_VALUE_REFUSED"),
                rows.stream().map(r -> r.get("verdict_reason").asText()).toList());
        assertEquals("AMBIGUOUS_PSEUDONYM", collision.rows().get(0).get("verdict_reason").asText());
        verify(estate.audit, times(3)).insert(anyString(), eq(ACTOR), eq(ActionRegistry.PATH_ANALYSIS_READ), eq(Optional.empty()),
                eq(AuthzOutcome.DENIED), eq(Optional.of("path_analysis_query")), any(), eq(Optional.empty()));
        // A refused raw value is never written to the row; aiview's masked values are kept as submitted.
        assertEquals("[REFUSED]", rows.get(0).get("query").get("src").asText());
        assertEquals("250.1.2.3", rows.get(1).get("query").get("src").asText());
        assertEquals("[REFUSED]", rows.get(2).get("query").get("ingress_interface_name").asText());
        for (JsonNode row : rows) {
            assertFalse(row.toString().contains("198.51.100.20") || row.toString().contains("\"eth1\""), row.toString());
        }
    }

    @Test
    void d3AMaskedIngressInterfaceResolvesAgainstTheHintedUnit() throws Exception {
        var estate = new Estate("chain.json").built();
        var tango = tango(estate);
        String raw = "{\"src\":\"198.51.100.20\",\"dst\":\"203.0.113.200\",\"protocol\":\"tcp\",\"port\":443,"
                + "\"ingress_node_id\":\"" + tango.nodeId() + "\",\"ingress_interface_name\":\"eth1\"}";
        JsonNode admin = json(ok(estate.as(Set.of(RoleToken.SECURITY_ADMIN)), raw));
        assertEquals("GIVEN", admin.get("ingress").get("ingress_mode").asText());
        String label = estate.names.maskPolicyName("interface", "eth1"); // aiview read the topology
        String masked = raw.replace("198.51.100.20", estate.shown("198.51.100.1/26", "198.51.100.20"))
                .replace("203.0.113.200", estate.shown("203.0.113.192/26", "203.0.113.200")).replace("\"eth1\"", "\"" + label + "\"");
        JsonNode aiview = json(ok(estate.as(Set.of(RoleToken.REPLAY_VIEWER)), masked));
        assertEquals(admin.get("verdict"), aiview.get("verdict"));
        assertEquals(admin.get("ingress").get("ingress_status"), aiview.get("ingress").get("ingress_status"));
        assertEquals(label, aiview.get("query").get("ingress_interface_name").asText());
        assertEquals(label, estate.rows().get(1).get("query").get("ingress_interface_name").asText());
    }

    private static TopologyGraph.Node tango(Estate estate) {
        return estate.graph().nodes().stream().filter(n -> n.activeMemberDeviceId().filter("dev-t04"::equals).isPresent())
                .findFirst().orElseThrow();
    }

    // ---- D-2 audit, rate limit, bulkhead, topology not built ------------------------------------------------------------

    @Test
    void d2OneMaskedAuditRowPerQueryWithTheVerdictAndNoResultStored() throws Exception {
        var estate = new Estate("chain.json").built();
        var admin = estate.as(Set.of(RoleToken.OPERATOR));
        ok(admin, HTTPS);
        ok(admin, body("198.51.100.20", "203.0.113.200", 22));
        ArgumentCaptor<Optional<String>> target = ArgumentCaptor.forClass(Optional.class);
        verify(estate.audit, times(2)).insert(anyString(), eq(ACTOR),
                eq(ActionRegistry.PATH_ANALYSIS_READ), target.capture(), eq(AuthzOutcome.PERMITTED),
                eq(Optional.of("path_analysis_query")), any(), eq(Optional.empty()));
        assertEquals(Optional.of(estate.graph().snapshotId()), target.getValue(), "the row names the opaque snapshot");
        List<JsonNode> rows = estate.rows();
        assertEquals(List.of("ALLOWED", "DENIED"), rows.stream().map(r -> r.get("verdict").asText()).toList());
        for (JsonNode row : rows) {
            String text = row.toString();
            assertFalse(text.contains("198.51.100.20") || text.contains("203.0.113.200"), text);
            assertEquals(estate.ips.mask("198.51.100.20"), row.get("query").get("src").asText());
            assertEquals(Set.of("query", "verdict", "verdict_reason"), Set.copyOf(iterate(row.fieldNames())),
                    "the masked query and the verdict only: no hop, rule or route of the result");
        }
        assertTrue(estate.repository.snapshots.size() == 1, "only the one rebuild this test ran is stored");
    }

    private static List<String> iterate(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    @Test
    void anUnauditedResultIsNotReturned() throws Exception {
        var estate = new Estate("chain.json").built();
        doThrow(new IllegalStateException("synthetic")).when(estate.audit)
                .insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
        estate.as(Set.of(RoleToken.VIEWER)).perform(query(HTTPS)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE")).andExpect(jsonPath("$.verdict").doesNotExist());
    }

    @Test
    void thirtyQueriesAMinutePerActorThenRetryAfter() throws Exception {
        var estate = new Estate("chain.json").built();
        var viewer = estate.as(Set.of(RoleToken.VIEWER));
        for (int i = 0; i < 30; i++) {
            ok(viewer, HTTPS);
        }
        viewer.perform(query(HTTPS)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("PATH_QUERY_RATE_LIMIT"));
        verify(estate.audit, times(30)).insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void aFullBulkheadAnswersBusyWithoutComputing() throws Exception {
        var bulkhead = new PolicyBulkhead(1, 0);
        var estate = new Estate("chain.json", null, bulkhead).built();
        try (var held = bulkhead.tryEnter()) {
            assertNotNull(held);
            estate.as(Set.of(RoleToken.VIEWER)).perform(query(HTTPS)).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5")).andExpect(jsonPath("$.error").value(PolicyBulkhead.BUSY));
        }
        verifyNoInteractions(estate.audit);
        ok(estate.as(Set.of(RoleToken.VIEWER)), HTTPS);
    }

    @Test
    void anUnbuiltTopologyIs409AndIsNeverBuiltByAQuery() throws Exception {
        var estate = new Estate("chain.json");
        for (Set<String> roles : List.of(Set.of(RoleToken.SECURITY_ADMIN), Set.of(RoleToken.REPLAY_VIEWER))) {
            estate.as(roles).perform(query(HTTPS)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TOPOLOGY_NOT_BUILT"));
        }
        assertTrue(estate.topology.current().isEmpty(), "no build on demand");
        assertTrue(estate.repository.snapshots.isEmpty(), "no snapshot header written");
        List<JsonNode> rows = estate.rows();
        assertEquals(List.of("NOT_EVALUATED", "NOT_EVALUATED"), rows.stream().map(r -> r.get("verdict").asText()).toList());
        assertEquals("[REFUSED]", rows.get(1).get("query").get("src").asText(), "aiview's raw value is not stored");
    }

    @Test
    void aQueryRaisesTheAutomaticRebuildCheckOnlyForANonReplayViewerAndStillAnswers409() throws Exception {
        var estate = new Estate("chain.json");
        var signals = new java.util.concurrent.atomic.AtomicInteger();
        estate.topology.attachRefreshTrigger(signals::incrementAndGet);
        estate.as(Set.of(RoleToken.REPLAY_VIEWER)).perform(query(HTTPS)).andExpect(status().isConflict());
        assertEquals(0, signals.get(), "an aiview query has no side effect");
        estate.as(Set.of(RoleToken.VIEWER)).perform(query(HTTPS)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPOLOGY_NOT_BUILT"));
        assertEquals(1, signals.get());
        assertTrue(estate.topology.current().isEmpty(), "the query itself never builds");
    }

    @Test
    void malformedBodiesAre400AndUnaudited() throws Exception {
        var estate = new Estate("chain.json").built();
        var viewer = estate.as(Set.of(RoleToken.VIEWER));
        for (String bad : List.of("{}", HTTPS.replace("443", "70000"), HTTPS.replace("443", "\"443\""),
                HTTPS.replace("tcp", "icmp"), HTTPS.replace("198.51.100.20", "198.51.100.0/24"),
                HTTPS.replace("198.51.100.20", "198.051.100.20"), HTTPS.replace("}", ",\"command\":\"x\"}"),
                HTTPS.replace("}", ",\"ingress_interface_name\":\"eth1\"}"), HTTPS.replace("}", ",\"edge_mode\":\"yes\"}"))) {
            viewer.perform(query(bad)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(estate.audit);
    }

    // ---- staleness and the end-to-end golden ----------------------------------------------------------------------------

    @Test
    void aStaleOrOutOfSyncPolicyMakesItsHopUnknownThroughTheApi() throws Exception {
        var estate = new Estate("chain.json").built();
        PolicySnapshot cp = estate.catalog.snapshots.get(PathGoldenAccess.cpChain("dev-t04").metadata().id());
        var m = cp.metadata();
        estate.catalog.add(new PolicySnapshot(new PolicySnapshot.Metadata(m.id(), m.sourceId(), m.sourceName(), m.vendor(),
                m.containerId(), m.containerName(), m.name(), "2026-11-02T05:00:00Z", m.artefactRef(), m.targets()),
                cp.sections(), cp.objects(), cp.failures()));
        JsonNode body = json(ok(estate.as(Set.of(RoleToken.VIEWER)), HTTPS));
        assertEquals("UNKNOWN", body.get("verdict").asText());
        assertEquals("STALE", body.get("executive_summary").get("freshness_status").asText());
        JsonNode first = body.get("branches").get(0).get("hops").get(0);
        assertEquals("UNKNOWN", first.get("own_verdict").asText());
        assertEquals("STALE_OR_OUT_OF_SYNC_POLICY", first.get("verdict_reason").asText());
        assertTrue(first.get("policy_stale").asBoolean());
        assertEquals("2026-11-02T05:00:00Z", first.get("policy_collected_at").asText(), "last-known policy shown as context");
    }

    @Test
    void theProductionBindingReproducesTheA4ReferenceBinding() {
        var estate = new Estate("chain.json").built();
        TopologyGraph graph = estate.graph();
        for (var q : List.of(PathQuery.of("198.51.100.20", "203.0.113.200", "tcp", 443),
                PathQuery.of("198.51.100.20", "203.0.113.200", "tcp", 22),
                PathQuery.of("198.51.100.20", "203.0.113.150", "tcp", 443).withEdgeMode(true))) {
            var reference = PathEngine.analyze(graph, PathGoldenAccess.chainReferencePolicies(), q, NOW);
            var stored = PathEngine.analyze(graph, new PathPolicyBinder(estate.catalog).forQuery(NOW), q, NOW);
            assertEquals(PathGoldenAccess.render("parity", graph, reference), PathGoldenAccess.render("parity", graph, stored));
        }
    }

    @Test
    void goldenEndToEndThroughTheApi() throws Exception {
        var estate = new Estate("chain.json").built();
        var admin = estate.as(Set.of(RoleToken.SECURITY_ADMIN));
        StringBuilder rendered = new StringBuilder();
        for (String q : List.of(HTTPS, body("198.51.100.20", "203.0.113.200", 22),
                "{\"src\":\"198.51.100.20\",\"dst\":\"203.0.113.150\",\"protocol\":\"tcp\",\"port\":443,\"edge_mode\":true}",
                body("198.51.100.20", "203.0.113.70", 443))) {
            rendered.append("== ").append(q).append('\n').append(stable(estate.graph(),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json(ok(admin, q))))).append('\n');
        }
        String expected;
        try (InputStream in = getClass().getResourceAsStream("/pathanalysis/query/api-chain.golden.txt")) {
            expected = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!rendered.toString().equals(expected)) {
            Path out = Path.of("build", "path-golden", "api-chain.golden.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, rendered.toString());
            fail("API golden mismatch; actual written to " + out.toAbsolutePath());
        }
    }

    /** Replaces the salted ids of this build by stable labels (hostname/context/table, or a kind and ordinal). */
    private static String stable(TopologyGraph g, String text) {
        Map<String, String> labels = new java.util.LinkedHashMap<>(PathGoldenAccess.labels(g));
        labels.put(g.snapshotId(), "SNAPSHOT");
        for (int i = 0; i < g.segments().size(); i++) {
            labels.put(g.segments().get(i).segmentId(), "SEGMENT-" + g.segments().get(i).network().map(Object::toString).orElse("?"));
        }
        for (TopologyGraph.Attachment a : g.attachments()) {
            labels.put(a.attachmentId(), "ATTACHMENT-" + labels.get(a.nodeId()) + "-" + a.interfaceName().orElse("?"));
        }
        List<Map.Entry<String, String>> ordered = new ArrayList<>(labels.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        for (var e : ordered) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }
}
