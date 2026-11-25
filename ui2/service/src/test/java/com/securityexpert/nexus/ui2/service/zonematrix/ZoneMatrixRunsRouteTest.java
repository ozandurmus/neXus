package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.api.ZoneMatrixController;
import com.securityexpert.nexus.ui2.service.api.ZoneMatrixRequestSizeFilter;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.FakeRuns;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.HoldingExecutor;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.CellInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PublishRequest;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.ZoneInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Zone matrix step 4b-2, the on-demand trigger of ZONE_MATRIX_CONTRACT.md section 5.1 ("On demand (zone_matrix_evaluate),
 * same code path"): {@code POST /api/v2/zone-matrix/runs} through MockMvc against the real {@link ZoneMatrixRunService}
 * (in-memory runs, a held pool so a run stays RUNNING), the real {@link ZoneMatrixService} and the ZM-40 size filter. The
 * gate's per-role verdict is pinned from the route decision table, which {@code RouteAuthorizationDecisionTableTest}
 * checks against the real gate.
 */
class ZoneMatrixRunsRouteTest {
    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROUTE = "/api/v2/zone-matrix/runs";
    private static final String POLICY = "pol-" + "alpha-01";
    private static final String POLICY_NAME = "PLANTED-POLICY-NAME";

    private final List<ZoneMatrixRunService> services = new ArrayList<>();
    private ZoneMatrixServiceTest.Memory versions;
    private FakeRuns runs;
    private HoldingExecutor held;
    private String activeVersionId;
    private String draftVersionId;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        versions = new ZoneMatrixServiceTest.Memory();
        var zones = zoneService(versions);
        String alpha = (String) zones.createZone("security-1", null,
                new ZoneInput("ZONE-ALPHA", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
        long rev = versions.draft().orElseThrow().revision();
        String bravo = (String) zones.createZone("security-1", rev,
                new ZoneInput("ZONE-BRAVO", "", "APPLICATION", List.of("198.51.100.0/24"))).get("zone_id");
        rev = versions.draft().orElseThrow().revision();
        zones.setCell("security-1", rev, new CellInput(alpha, bravo, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", "web tier", ""));
        var draft = versions.draft().orElseThrow();
        activeVersionId = (String) zones.publish("compliance-1", false,
                new PublishRequest(draft.revision(), draft.contentSha256(), null)).get("version_id");
        // a new draft after the publish: an edit always goes to the draft
        zones.createZone("security-1", null, new ZoneInput("ZONE-CHARLIE", "", "USER", List.of("192.0.2.128/25")));
        draftVersionId = versions.draft().orElseThrow().versionId();
        runs = new FakeRuns();
        held = new HoldingExecutor();
        mvc = mvc(zones, runService(versions, runs, held));
    }

    @AfterEach
    void close() { services.forEach(ZoneMatrixRunService::close); }

    private static ZoneMatrixService zoneService(ZoneMatrixServiceTest.Memory repository) {
        return new ZoneMatrixService(repository, new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ZoneMatrixRunService runService(ZoneMatrixServiceTest.Memory repository, FakeRuns store, HoldingExecutor pool) {
        PolicyCatalog catalog = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", POLICY);
                view.put("name", POLICY_NAME);
                view.put("collectedAt", "2026-11-25T08:00:00Z");
                return List.of(view);
            }
            @Override public Optional<PolicySnapshot> find(String policyId) { return Optional.empty(); }
        };
        var service = new ZoneMatrixRunService(store, repository, catalog, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5), pool, ZoneMatrixRunService.newWatchdog());
        services.add(service);
        return service;
    }

    private static MockMvc mvc(ZoneMatrixService zones, ZoneMatrixRunService runService) {
        var controller = new ZoneMatrixController(zones, runService, mock(RbacEvaluator.class), new ActionRegistry());
        return MockMvcBuilders.standaloneSetup(controller).addFilters(new ZoneMatrixRequestSizeFilter()).build();
    }

    private MockHttpServletResponse send(MockMvc target, String body, boolean replayViewer) throws Exception {
        var request = post(ROUTE).requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "security-1");
        if (replayViewer) request.requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return target.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse send(String body) throws Exception { return send(mvc, body, false); }

    private static Map<?, ?> json(MockHttpServletResponse response) throws Exception {
        return MAPPER.readValue(response.getContentAsString(), Map.class);
    }

    // ------------------------------------------------------------------ accepted

    @Test
    void aRunIsAcceptedWith202AndCarriesTheRunIdWhetherOneWasAlreadyRunningAndTheStateOnly() throws Exception {
        var response = send("{\"policy_id\":\"" + POLICY + "\"}");
        assertEquals(202, response.getStatus());
        var body = json(response);
        assertEquals(Set.of("run_id", "already_running", "state"), body.keySet(), "ids and a state only");
        assertEquals(false, body.get("already_running"));
        assertEquals("RUNNING", body.get("state"));
        String runId = (String) body.get("run_id");
        assertTrue(runId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), runId);
        var row = runs.rows.get(runId);
        assertNotNull(row, "a durable run row is written");
        assertEquals(POLICY, row.policy);
        assertEquals(activeVersionId, row.versionId, "no version named: the active version");
        assertEquals(1, held.tasks.size(), "the run is queued on the run service's own pool");

        String text = response.getContentAsString();
        for (String forbidden : List.of(POLICY_NAME, "ZONE-ALPHA", "ZONE-BRAVO", "192.0.2.", "198.51.100.", "tcp/443", "\"can\"")) {
            assertFalse(text.contains(forbidden), "the answer carries " + forbidden);
        }
    }

    @Test
    void aSecondSubmitWhileTheFirstIsRunningReturnsTheSameRunMarkedAlreadyRunning() throws Exception {
        var first = json(send("{\"policy_id\":\"" + POLICY + "\"}"));
        var secondResponse = send("{\"policy_id\":\"" + POLICY + "\",\"version_id\":\"" + activeVersionId + "\"}");
        assertEquals(202, secondResponse.getStatus());
        var second = json(secondResponse);
        assertEquals(first.get("run_id"), second.get("run_id"));
        assertEquals(true, second.get("already_running"));
        assertEquals("RUNNING", second.get("state"));
        assertEquals(1, runs.rows.size(), "never a parallel run");
        assertEquals(1, held.tasks.size());

        held.runAll();
        assertNotEquals("RUNNING", runs.rows.get((String) first.get("run_id")).state, "the held run finished");
        var third = json(send("{\"policy_id\":\"" + POLICY + "\"}"));
        assertNotEquals(first.get("run_id"), third.get("run_id"), "a finished run frees the policy");
        assertEquals(false, third.get("already_running"));
    }

    @Test
    void aNamedPublishedVersionIsEvaluatedAgainstThatVersion() throws Exception {
        var body = json(send("{\"policy_id\":\"" + POLICY + "\",\"version_id\":\"" + activeVersionId + "\"}"));
        assertEquals(activeVersionId, runs.rows.get((String) body.get("run_id")).versionId);
    }

    // ------------------------------------------------------------------ refused by submit

    @Test
    void aBlankOrMissingPolicyIs400InvalidPolicyAndStartsNothing() throws Exception {
        for (String body : new String[]{null, "{}", "{\"policy_id\":\"\"}", "{\"policy_id\":\"   \"}", "{\"version_id\":\"" + activeVersionId + "\"}"}) {
            var response = send(body);
            assertEquals(400, response.getStatus(), String.valueOf(body));
            assertEquals("INVALID_POLICY", json(response).get("error"), String.valueOf(body));
        }
        assertTrue(runs.rows.isEmpty());
        assertTrue(held.tasks.isEmpty());
    }

    @Test
    void anUnknownVersionADraftAndNoActiveVersionAreRefusedWithTheCodesSubmitThrows() throws Exception {
        var unknown = send("{\"policy_id\":\"" + POLICY + "\",\"version_id\":\"00000000-0000-4000-8000-0000000000ff\"}");
        assertEquals(404, unknown.getStatus());
        assertEquals("NOT_FOUND", json(unknown).get("error"));

        var draft = send("{\"policy_id\":\"" + POLICY + "\",\"version_id\":\"" + draftVersionId + "\"}");
        assertEquals(409, draft.getStatus());
        assertEquals("DRAFT_NOT_EVALUATED", json(draft).get("error"));

        var empty = new ZoneMatrixServiceTest.Memory();
        var emptyRuns = new FakeRuns();
        var noActive = send(mvc(zoneService(empty), runService(empty, emptyRuns, new HoldingExecutor())),
                "{\"policy_id\":\"" + POLICY + "\"}", false);
        assertEquals(409, noActive.getStatus());
        assertEquals("NO_ACTIVE_VERSION", json(noActive).get("error"));

        assertTrue(runs.rows.isEmpty() && emptyRuns.rows.isEmpty(), "a refused submit writes no run");
    }

    // ------------------------------------------------------------------ who may call it

    @Test
    void theMaskedPersonaIsRefusedAtTheControllerAndStartsNoRun() throws Exception {
        var response = send(mvc, "{\"policy_id\":\"" + POLICY + "\"}", true);
        assertEquals(403, response.getStatus());
        assertEquals("READ_ONLY_PERSONA", json(response).get("error"));
        assertTrue(runs.rows.isEmpty());
    }

    /**
     * The gate decides the new route exactly as {@code POST /api/v2/zone-matrix/evaluate} for every session kind: the
     * replay viewer (aiview), its machine session and every role without {@code zone_matrix_evaluate} are refused 403;
     * only security_admin, compliance_admin and root are let through.
     */
    @Test
    void theGateDecidesTheRunRouteExactlyAsTheEvaluateRouteForEverySessionKind() throws IOException {
        Map<String, String> runsVerdicts = verdicts("POST " + ROUTE);
        Map<String, String> evaluateVerdicts = verdicts("POST /api/v2/zone-matrix/evaluate");
        assertFalse(runsVerdicts.isEmpty(), "the decision table has rows for the new route");
        assertEquals(evaluateVerdicts, runsVerdicts);
        for (String refused : List.of("role-replay_viewer", "role-viewer", "role-operator", "role-onboarding_admin",
                "role-backup_admin", "role-operation_admin", "no-role")) {
            assertTrue(runsVerdicts.get(refused).startsWith("REFUSE 403"), refused);
        }
        assertTrue(runsVerdicts.get("machine-replay-viewer").startsWith("REFUSE 403"), "the aiview machine session never starts a run");
        for (String permitted : List.of("role-security_admin", "role-compliance_admin", "root")) {
            assertTrue(runsVerdicts.get(permitted).startsWith("PERMIT"), permitted);
        }
    }

    private static Map<String, String> verdicts(String label) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = ZoneMatrixRunsRouteTest.class.getResourceAsStream("/authorization/route-authorization-decision-table.golden.txt")) {
            assertNotNull(in, "the decision table golden is on the test classpath");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.split(" \\| ", 3);
                if (parts.length == 3 && parts[1].equals(label)) out.put(parts[0], parts[2]);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ZM-40

    @Test
    void aBodyOverTheZm40LimitIsRefused413BeforeParsingAndStartsNoRun() throws Exception {
        String head = "{\"policy_id\":\"" + POLICY + "\"}";
        String over = head + " ".repeat(ZoneMatrixRequestSizeFilter.MAX_REQUEST_BYTES + 1 - head.length());
        var response = send(over);
        assertEquals(413, response.getStatus());
        assertEquals(ZoneMatrixRequestSizeFilter.CODE, json(response).get("error"));
        assertTrue(runs.rows.isEmpty());
        assertTrue(held.tasks.isEmpty());
    }
}
