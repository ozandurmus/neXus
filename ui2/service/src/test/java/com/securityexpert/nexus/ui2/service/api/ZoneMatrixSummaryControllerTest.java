package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Cell;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Finding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Witness;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneBlock;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.PolicyCoverage;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredFinding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * {@code GET /api/v2/zone-matrix/summary} at the controller (ZONE_MATRIX_CONTRACT.md 3.4, 7.1, ZM-33, ZM-37): status codes,
 * 400 on bad parameters, the masked view for replay_viewer and for callers without {@code policy_read}, and a read-only
 * route set.
 */
class ZoneMatrixSummaryControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-25T08:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE_NAME = "Z-PLANTED-CARDHOLDER";
    private static final String RULE_NAME = "planted-rule-fw-tango-04";
    private static final String POLICY_NAME = "planted-policy-standard";

    private final String zoneA = UUID.randomUUID().toString();
    private final String zoneB = UUID.randomUUID().toString();
    private final String versionId = UUID.randomUUID().toString();
    private ZoneMatrixRunService runs;
    private ZoneMatrixController controller;
    private Set<String> roles = Set.of();

    @BeforeEach
    void setUp() {
        var content = new ZoneMatrixContent(List.of(
                new ZoneMatrixContent.Zone(zoneA, ZONE_NAME, "d", "DATABASE", List.of(ZoneBlock.parse("192.0.2.0/25"))),
                new ZoneMatrixContent.Zone(zoneB, "Z-PLANTED-BRANCH", "d", "USER", List.of(ZoneBlock.parse("198.51.100.0/24")))));
        var version = new Version(versionId, 1, "ACTIVE", 2, content.canonicalJson(), content.sha256(), null, "a", NOW, "a", NOW,
                "p", NOW);
        ZoneMatrixRepository repository = mock(ZoneMatrixRepository.class);
        when(repository.find(versionId)).thenReturn(Optional.of(version));
        when(repository.active()).thenReturn(Optional.of(version));
        var service = new ZoneMatrixService(repository, new ZoneMatrixService.Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return false; }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));

        var staleness = ZoneMatrixStaleness.assess(new ZoneMatrixStaleness.PolicyInput("P-1",
                NOW.minus(Duration.ofHours(1)).toString(), ZoneMatrixStaleness.RunState.COMPLETE, versionId, versionId), NOW,
                ZoneMatrixStaleness.INTERIM_PO_2026_11_23);
        var finding = new Finding("P-1", "uid-1", List.of(), List.of(zoneB + "->" + zoneA), "rid-1", 4, RULE_NAME, "s", "Pre rules",
                List.of(), "VIOLATION", "statement DENY_ALL", "HIGH", List.of(), null,
                List.of(new Cell(zoneB, zoneA, "DENY_ALL", "HIGH", List.of("tcp/22"), "via 198.51.100.9",
                        new Witness("192.0.2.17/32", "198.51.100.23/32", "tcp/22", 1L, 2L))),
                List.of(), null, NOW, NOW, NOW, null, null);
        var policy = new PolicyCoverage("P-1", POLICY_NAME, "CP", "src-1", "fw-tango-04.planted.example", "ctr-1", "planted-domain",
                NOW.minus(Duration.ofHours(1)).toString(), 10, staleness, "COMPLETE", List.of(), 0, null, null);
        var summary = new StoredSummary(NOW, ZoneMatrixStaleness.INTERIM_PO_2026_11_23, version, List.of(policy), List.of(),
                List.of(new StoredFinding("run-1", versionId, 1, content.sha256(), staleness.verdict(), staleness.snapshotAge(), finding)),
                null, List.of(), Map.of(), List.of(new ZoneMatrixRunService.CellCount(zoneB, zoneA, 2, 1, "HIGH")));
        runs = mock(ZoneMatrixRunService.class);
        when(runs.storedSummary()).thenReturn(summary);

        RbacEvaluator rbac = mock(RbacEvaluator.class);
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
        when(rbac.evaluateAny(anyString(), anySet(), any(Instant.class))).thenAnswer(call ->
                ((Set<?>) call.getArgument(1)).stream().anyMatch(roles::contains) ? permitted : denied);
        controller = new ZoneMatrixController(service, runs, rbac, new ActionRegistry());
    }

    private MockHttpServletRequest request(boolean replayViewer, String... roleTokens) {
        roles = Set.of(roleTokens);
        var request = new MockHttpServletRequest("GET", "/api/v2/zone-matrix/summary");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-" + String.join("-", roleTokens));
        if (replayViewer) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        return request;
    }

    private static String json(ResponseEntity<?> response) throws Exception { return MAPPER.writeValueAsString(response.getBody()); }

    @Test
    void aSecurityAdministratorReadsNamesButNeverAddressesOrWitnesses() throws Exception {
        var response = controller.summary(null, null, null, request(false, RoleToken.SECURITY_ADMIN));
        assertEquals(200, response.getStatusCode().value());
        String body = json(response);
        assertTrue(body.contains(RULE_NAME) && body.contains(ZONE_NAME) && body.contains(POLICY_NAME));
        for (String forbidden : List.of("192.0.2.", "198.51.100.", "\"witness\"", "\"nat_view\"", "\"blocks\"")) {
            assertFalse(body.contains(forbidden), "body carries " + forbidden);
        }
        assertNotNull(((Map<?, ?>) response.getBody()).get("can"), "the caller's affordances travel with the body");
    }

    @Test
    void theCellCountsTravelOnTheWireWithNamesForAdministratorsAndPseudonymsForTheReplayViewer() throws Exception {
        var body = (Map<?, ?>) controller.summary(null, null, null, request(false, RoleToken.SECURITY_ADMIN)).getBody();
        for (String key : List.of("cell_counts", "cell_counts_exact", "cell_counts_total", "cell_counts_returned",
                "cell_counts_more", "cell_counts_capped")) {
            assertTrue(body.containsKey(key), "missing key " + key);
        }
        var row = (Map<?, ?>) ((List<?>) body.get("cell_counts")).get(0);
        assertEquals(zoneB, row.get("source_zone_id"));
        assertEquals("Z-PLANTED-BRANCH", row.get("source_name"));
        assertEquals(ZONE_NAME, row.get("destination_name"));
        assertEquals(2, row.get("headline_rules"));
        assertEquals(1, row.get("outside_headline_rules"));
        assertEquals("HIGH", row.get("highest_severity"));
        assertEquals(1, body.get("cell_counts_total"));
        assertEquals(false, body.get("cell_counts_capped"));
        String masked = json(controller.summary(null, null, null, request(true, RoleToken.REPLAY_VIEWER)));
        assertTrue(masked.contains("\"cell_counts\":[{"));
        assertFalse(masked.contains("Z-PLANTED-BRANCH") || masked.contains(ZONE_NAME));
    }

    @Test
    void aViewerWithoutPolicyReadSeesZoneNamesButMaskedPolicyAndRuleNames() throws Exception {
        String body = json(controller.summary(null, null, null, request(false, RoleToken.VIEWER)));
        assertTrue(body.contains(ZONE_NAME));
        assertFalse(body.contains(RULE_NAME));
        assertFalse(body.contains(POLICY_NAME));
        assertTrue(body.contains("\"policy_names_masked\":true"));
    }

    @Test
    void theReplayViewerIsAlwaysMaskedEvenWithAnAdministratorBinding() throws Exception {
        for (var req : List.of(request(true, RoleToken.REPLAY_VIEWER), request(true, RoleToken.REPLAY_VIEWER, RoleToken.SECURITY_ADMIN))) {
            var response = controller.summary(null, null, null, req);
            assertEquals(200, response.getStatusCode().value());
            String body = json(response);
            for (String planted : List.of(ZONE_NAME, "Z-PLANTED-BRANCH", RULE_NAME, POLICY_NAME, "fw-tango-04.planted.example",
                    "planted-domain", "192.0.2.", "198.51.100.")) {
                assertFalse(body.contains(planted), "masked body carries a planted value");
            }
            assertTrue(body.contains("\"masked\":true"));
        }
    }

    @Test
    void badParametersAre400AndNeverReachTheStore() throws Exception {
        record Case(String policy, String outcome, String limit, String extra, String code) {}
        for (var c : List.of(
                new Case(null, null, "0", null, "INVALID_LIMIT"),
                new Case(null, null, "1001", null, "INVALID_LIMIT"),
                new Case(null, null, "ten", null, "INVALID_LIMIT"),
                new Case(null, "COMPLIANT", null, null, "INVALID_OUTCOME"),
                new Case("", null, null, null, "INVALID_POLICY_ID"),
                new Case(null, null, null, "outcomes", "UNKNOWN_PARAMETER"))) {
            var req = request(false, RoleToken.VIEWER);
            if (c.limit() != null) req.addParameter("limit", c.limit());
            if (c.outcome() != null) req.addParameter("outcome", c.outcome());
            if (c.policy() != null) req.addParameter("policy_id", c.policy());
            if (c.extra() != null) req.addParameter(c.extra(), "VIOLATION");
            var response = controller.summary(c.policy(), c.outcome(), c.limit(), req);
            assertEquals(400, response.getStatusCode().value(), c.code());
            assertEquals(c.code(), ((Map<?, ?>) response.getBody()).get("error"));
        }
        verify(runs, never()).storedSummary();

        var unknownPolicy = request(false, RoleToken.VIEWER);
        unknownPolicy.addParameter("policy_id", "P-MISSING");
        var response = controller.summary("P-MISSING", null, null, unknownPolicy);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("UNKNOWN_POLICY", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void validFiltersAnswer200WithCapFields() {
        var req = request(false, RoleToken.COMPLIANCE_ADMIN);
        req.addParameter("policy_id", "P-1");
        req.addParameter("outcome", "VIOLATION");
        req.addParameter("limit", "1000");
        var response = controller.summary("P-1", "VIOLATION", "1000", req);
        assertEquals(200, response.getStatusCode().value());
        var body = (Map<?, ?>) response.getBody();
        assertEquals(1, body.get("total_findings"));
        assertEquals(1, body.get("returned"));
        assertEquals(0, body.get("more"));
        assertEquals(false, body.get("capped"));
    }

    @Test
    void zm37_theControllerHasNoRouteThatMutatesOrDeletesARunOrAFinding() {
        for (Method m : ZoneMatrixController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(DeleteMapping.class), m.getName());
            assertNull(m.getAnnotation(PatchMapping.class), m.getName());
            for (String path : paths(m)) {
                boolean touchesRunsOrFindings = path.contains("/runs") || path.contains("/findings") || path.contains("/summary");
                if (touchesRunsOrFindings) assertNotNull(m.getAnnotation(GetMapping.class), path + " must be GET only");
            }
        }
    }

    private static List<String> paths(Method m) {
        if (m.getAnnotation(GetMapping.class) != null) return List.of(m.getAnnotation(GetMapping.class).value());
        if (m.getAnnotation(PostMapping.class) != null) return List.of(m.getAnnotation(PostMapping.class).value());
        if (m.getAnnotation(PutMapping.class) != null) return List.of(m.getAnnotation(PutMapping.class).value());
        return List.of();
    }
}
