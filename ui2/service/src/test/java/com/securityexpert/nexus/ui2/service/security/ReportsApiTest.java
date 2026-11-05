package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.service.api.ReportsController;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.report.ReportTestSupport;

/**
 * Reports R1 contract "Masking and RBAC" through the real gate chain: the route map, report_read / report_generate for
 * every role (aiview included), RAW only with report_raw_view and never for aiview (preview, generate, history and
 * download), a missing underlying domain read refused, and aiview's responses always masked.
 */
class ReportsApiTest {

    private static final String BASE = "/api/v2/reports";
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final Map<String, Set<String>> roles = Map.of(
            "synthetic-secadmin", Set.of(RoleToken.SECURITY_ADMIN),
            "synthetic-viewer", Set.of(RoleToken.VIEWER),
            "synthetic-aiview", Set.of(RoleToken.REPLAY_VIEWER),
            "synthetic-aiview-and-secadmin", Set.of(RoleToken.REPLAY_VIEWER, RoleToken.SECURITY_ADMIN),
            "synthetic-onboarding", Set.of(RoleToken.ONBOARDING_ADMIN),
            "synthetic-unbound", Set.of());
    @TempDir Path root;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        for (String actor : roles.keySet()) {
            var session = new SessionRecord(SessionHasher.hash("cookie-" + actor), actor, "csrf-" + actor, SessionState.ACTIVE,
                    now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty(), false);
            when(sessions.findBySessionId(eq(session.sessionId()), any())).thenReturn(Optional.of(session));
        }
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        when(rbac.evaluate(anyString(), any(), any())).thenAnswer(call -> {
            Optional<?> token = call.getArgument(1);
            return token.isPresent() && roles.getOrDefault(call.getArgument(0), Set.of()).contains(token.get()) ? permitted : denied;
        });
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call -> {
            Set<?> wanted = call.getArgument(1);
            return roles.getOrDefault(call.getArgument(0), Set.of()).stream().anyMatch(wanted::contains) ? permitted : denied;
        });
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var names = new TopologyNamePseudonymizer(new byte[32]);
        var addresses = new SubnetPreservingIpMasker(new byte[32]);
        var service = ReportTestSupport.service(rbac, root, names, addresses);
        mvc = MockMvcBuilders.standaloneSetup(new ReportsController(service))
                .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(addresses, names))
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
    }

    private static Cookie cookie(String actor) {
        return new Cookie("ui2_session", "cookie-" + actor);
    }

    private static MockHttpServletRequestBuilder read(String path, String actor) {
        return get(path).servletPath(path).cookie(cookie(actor));
    }

    private static MockHttpServletRequestBuilder post(String path, String actor, String body) {
        return request(HttpMethod.POST, path).servletPath(path).cookie(cookie(actor)).header("X-CSRF-Token", "csrf-" + actor)
                .header("Origin", "https://example.invalid").contentType("application/json").content(body);
    }

    private static String body(String template, String projection, String format) {
        return "{\"template_id\":\"" + template + "\",\"projection\":\"" + projection + "\""
                + (format == null ? "" : ",\"format\":\"" + format + "\"") + "}";
    }

    private JsonNode jsonOf(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(streamed(result).getResponse().getContentAsString());
    }

    /** A download route answers through a streaming body: dispatch it to read what was written. */
    private org.springframework.test.web.servlet.MvcResult streamed(org.springframework.test.web.servlet.MvcResult result)
            throws Exception {
        if (!result.getRequest().isAsyncStarted()) return result;
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result)).andReturn();
    }

    @Test
    void theRouteMapGatesEveryReportRouteAndRawIsClassOneOutsideTheMap() {
        var map = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE;
        assertEquals(ActionRegistry.REPORT_READ, map.get("GET /api/v2/reports/catalogue"));
        assertEquals(ActionRegistry.REPORT_READ, map.get("POST /api/v2/reports/preview"));
        assertEquals(ActionRegistry.REPORT_READ, map.get("GET /api/v2/reports/runs"));
        assertEquals(ActionRegistry.REPORT_READ, map.get("GET /api/v2/reports/runs/*"));
        assertEquals(ActionRegistry.REPORT_GENERATE, map.get("POST /api/v2/reports/runs"));
        assertEquals(ActionRegistry.REPORT_GENERATE, map.get("GET /api/v2/reports/runs/*/download"));
        assertFalse(map.containsValue(ActionRegistry.REPORT_RAW_VIEW));
        var registry = new ActionRegistry();
        assertTrue(registry.find(ActionRegistry.REPORT_READ).orElseThrow().consoleSubmittable());
        assertTrue(registry.find(ActionRegistry.REPORT_GENERATE).orElseThrow().consoleSubmittable());
        var raw = registry.find(ActionRegistry.REPORT_RAW_VIEW).orElseThrow();
        assertFalse(raw.consoleSubmittable(), "report_raw_view is CLASS_1");
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN), raw.requiredRoleTokens());
        assertTrue(registry.find(ActionRegistry.REPORT_READ).orElseThrow().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
        assertTrue(registry.find(ActionRegistry.REPORT_GENERATE).orElseThrow().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
    }

    @Test
    void everyRoleReadsTheCatalogueAndAnUnboundSessionIsRefused() throws Exception {
        for (String actor : List.of("synthetic-secadmin", "synthetic-viewer", "synthetic-aiview", "synthetic-onboarding")) {
            mvc.perform(read(BASE + "/catalogue", actor)).andExpect(status().isOk());
        }
        mvc.perform(read(BASE + "/catalogue", "synthetic-unbound")).andExpect(status().isForbidden());
        JsonNode aiview = jsonOf(mvc.perform(read(BASE + "/catalogue", "synthetic-aiview")).andReturn());
        assertTrue(aiview.path("permissions").path("masked").asBoolean());
        assertFalse(aiview.path("permissions").path("raw_view").asBoolean());
        assertEquals(4, aiview.path("templates").size());
        JsonNode admin = jsonOf(mvc.perform(read(BASE + "/catalogue", "synthetic-secadmin")).andReturn());
        assertTrue(admin.path("permissions").path("raw_view").asBoolean());
    }

    @Test
    void aiviewNeverGetsRawEvenWithAnAdminRole() throws Exception {
        for (String actor : List.of("synthetic-aiview", "synthetic-aiview-and-secadmin")) {
            var preview = mvc.perform(post(BASE + "/preview", actor, body("RPT-03", "RAW", null))).andReturn();
            assertEquals(403, preview.getResponse().getStatus());
            assertEquals("RAW_VIEW_REFUSED", jsonOf(preview).path("error").asText());
            var generate = mvc.perform(post(BASE + "/runs", actor, body("RPT-03", "RAW", "CSV"))).andReturn();
            assertEquals(403, generate.getResponse().getStatus());
            assertEquals("RAW_VIEW_REFUSED", jsonOf(generate).path("error").asText());
        }
        // A RAW run made by a security admin is invisible to aiview and its download refused.
        var raw = jsonOf(mvc.perform(post(BASE + "/runs", "synthetic-secadmin", body("RPT-03", "RAW", "CSV")))
                .andExpect(status().isAccepted()).andReturn());
        String runId = raw.path("run_id").asText();
        assertEquals("SUCCEEDED", raw.path("state").asText());
        String download = BASE + "/runs/" + runId + "/download";
        var refused = mvc.perform(read(download, "synthetic-aiview")).andReturn();
        assertEquals(403, refused.getResponse().getStatus());
        assertEquals("RAW_VIEW_REFUSED", jsonOf(refused).path("error").asText());
        mvc.perform(read(BASE + "/runs/" + runId, "synthetic-aiview")).andExpect(status().isNotFound());
        JsonNode history = jsonOf(mvc.perform(read(BASE + "/runs", "synthetic-aiview")).andReturn());
        assertEquals(0, history.path("runs").size());
        mvc.perform(read(download, "synthetic-secadmin")).andExpect(status().isOk());
        // a viewer without report_raw_view is refused the same way
        mvc.perform(read(download, "synthetic-viewer")).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/preview", "synthetic-viewer", body("RPT-03", "RAW", null))).andExpect(status().isForbidden());
    }

    @Test
    void aMissingUnderlyingDomainReadIsRefused() throws Exception {
        // onboarding_admin holds report_read and report_generate but not cp_failover_read (RPT-05 and RPT-01 need it).
        var preview = mvc.perform(post(BASE + "/preview", "synthetic-onboarding", body("RPT-05", "MASKED", null))).andReturn();
        assertEquals(403, preview.getResponse().getStatus());
        assertEquals("DOMAIN_READ_REQUIRED", jsonOf(preview).path("error").asText());
        assertTrue(jsonOf(preview).path("reason").asText().contains(ActionRegistry.CP_FAILOVER_READ));
        mvc.perform(post(BASE + "/runs", "synthetic-onboarding", body("RPT-01", "MASKED", "PDF"))).andExpect(status().isForbidden());
        // RPT-03 needs only the backup read, open to every signed-in session
        mvc.perform(post(BASE + "/preview", "synthetic-onboarding", body("RPT-03", "MASKED", null))).andExpect(status().isOk());
        JsonNode catalogue = jsonOf(mvc.perform(read(BASE + "/catalogue", "synthetic-onboarding")).andReturn());
        assertEquals(ActionRegistry.CP_FAILOVER_READ, catalogue.path("templates").get(3).path("missing_reads").get(0).asText());
    }

    @Test
    void aiviewGetsTheMaskedProjectionAndCanGenerateAndDownloadIt() throws Exception {
        var preview = mvc.perform(post(BASE + "/preview", "synthetic-aiview", body("RPT-02", "MASKED", null))).andReturn();
        assertEquals(200, preview.getResponse().getStatus());
        assertEquals("true", preview.getResponse().getHeader(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER));
        String text = preview.getResponse().getContentAsString();
        for (String value : ReportTestSupport.rawValues()) assertFalse(text.contains(value), "aiview preview leaks a raw value");
        assertEquals("MASKED", jsonOf(preview).path("projection").asText());
        JsonNode run = jsonOf(mvc.perform(post(BASE + "/runs", "synthetic-aiview", body("RPT-02", "MASKED", "CSV")))
                .andExpect(status().isAccepted()).andReturn());
        var download = mvc.perform(read(BASE + "/runs/" + run.path("run_id").asText() + "/download", "synthetic-aiview"))
                .andReturn();
        assertEquals(200, download.getResponse().getStatus(), String.valueOf(download.getResolvedException()));
        String csv = streamed(download).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\"Report\",\"RPT-02"), csv.substring(0, Math.min(80, csv.length())));
        for (String value : ReportTestSupport.rawValues()) assertFalse(csv.contains(value), "aiview file leaks a raw value");
        assertTrue(download.getResponse().getHeader("Content-Disposition").contains("-masked.csv"));
    }

    @Test
    void badRequestsAreRefusedWithCodes() throws Exception {
        assertEquals("INVALID_TEMPLATE", jsonOf(mvc.perform(post(BASE + "/preview", "synthetic-viewer",
                body("RPT-04", "MASKED", null))).andReturn()).path("error").asText());
        assertEquals("INVALID_FORMAT", jsonOf(mvc.perform(post(BASE + "/runs", "synthetic-viewer",
                body("RPT-03", "MASKED", "DOCX"))).andReturn()).path("error").asText());
        assertEquals("INVALID_PROJECTION", jsonOf(mvc.perform(post(BASE + "/preview", "synthetic-viewer",
                body("RPT-03", "PLAIN", null))).andReturn()).path("error").asText());
        mvc.perform(read(BASE + "/runs/not-a-uuid", "synthetic-viewer")).andExpect(status().isBadRequest());
        mvc.perform(read(BASE + "/runs/00000000-0000-4000-8000-000000000000", "synthetic-viewer")).andExpect(status().isNotFound());
    }
}
