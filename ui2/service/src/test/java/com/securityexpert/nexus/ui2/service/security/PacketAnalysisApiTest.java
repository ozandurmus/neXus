package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaptureRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaseRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.Outcome;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.TelemetryRow;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.service.api.PacketAnalysisController;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketCaptureStore;
import com.securityexpert.nexus.ui2.service.privacy.*;

/**
 * PO decision record 2026-10-15 §5/§6 through the real gate chain: route map, role grants per role (operator,
 * security_admin, viewer, aiview), the upload limits (per-file size, store cap, magic bytes) and the masked
 * projection the replay viewer receives.
 */
class PacketAnalysisApiTest {

    private static final String CASE = "11111111-2222-4333-8444-555555555555";
    private static final String CAPTURE = "66666666-7777-4888-9999-aaaaaaaaaaaa";
    private static final String JOB = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
    private static final String TELEMETRY = "99999999-8888-4777-8666-555555555555";
    private static final String PRIVATE_TELEMETRY = "synthetic-customer-core-srv-vmstat.txt";
    private static final String BASE = "/api/v2/packet-analysis";
    private static final String PRIVATE_TITLE = "synthetic-customer-site-outage";
    private static final String PRIVATE_FILE = "synthetic-customer-core-fw.pcap";

    @TempDir Path root;
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final PacketAnalysisRepository repository = mock(PacketAnalysisRepository.class);
    private final Map<String, Set<String>> roles = Map.of(
            "synthetic-operator", Set.of(RoleToken.OPERATOR),
            "synthetic-secadmin", Set.of(RoleToken.SECURITY_ADMIN),
            "synthetic-operator-and-secadmin", Set.of(RoleToken.OPERATOR, RoleToken.SECURITY_ADMIN),
            "synthetic-viewer", Set.of(RoleToken.VIEWER),
            "synthetic-aiview", Set.of(RoleToken.REPLAY_VIEWER));
    private PacketCaptureStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new PacketCaptureStore(root, 4096, 64 * 1024);
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
        mvc = MockMvcBuilders.standaloneSetup(new PacketAnalysisController(repository, store, rbac, names, addresses))
                .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(addresses, names))
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();

        CaseRow row = new CaseRow(CASE, PRIVATE_TITLE, "synthetic-operator", now, now.plusSeconds(7 * 86400), "OPEN", 3048,
                false, 1, null);
        when(repository.listCases(anyInt())).thenReturn(List.of(row));
        when(repository.findCase(CASE)).thenReturn(Optional.of(row));
        when(repository.captures(CASE)).thenReturn(List.of(new CaptureRow(CAPTURE, CASE, PRIVATE_FILE, "pcap", 3048,
                "a".repeat(64), CAPTURE + ".pcap", "synthetic-operator", now)));
        when(repository.findCapture(CAPTURE)).thenReturn(Optional.of(new CaptureRow(CAPTURE, CASE, PRIVATE_FILE, "pcap", 3048,
                "a".repeat(64), CAPTURE + ".pcap", "synthetic-operator", now)));
        RunRow run = new RunRow(JOB, CASE, "COMPLETED", "synthetic-operator", now, now, now, "stub-0",
                "{\"verdict\":\"unknown\",\"reason\":\"engine not yet ported\",\"capture_count\":1}", null);
        when(repository.runs(CASE)).thenReturn(List.of(run));
        when(repository.findRun(JOB)).thenReturn(Optional.of(run));
        when(repository.createCase(anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(call ->
                new CaseRow(call.getArgument(0), call.getArgument(1), call.getArgument(2), now, now.plusSeconds(7 * 86400),
                        "OPEN", 0, call.getArgument(3), 0, null));
        when(repository.addCapture(any(), anyString())).thenReturn(Outcome.DONE);
        when(repository.addTelemetry(any(), anyString())).thenReturn(Outcome.DONE);
        when(repository.requestAnalysis(eq(CASE), anyString(), anyString())).thenReturn(Outcome.DONE);
    }

    // ---- route map and grants ------------------------------------------------------------------------------

    @Test void actionsGrantRunToOperatorLevelRawToSecurityAdminAndOnlyReadToAiview() {
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.PACKET_ANALYSIS_RUN).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.PACKET_ANALYSIS_RAW_VIEW).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                registry.find(ActionRegistry.PACKET_ANALYSIS_READ).orElseThrow().requiredRoleTokens());
        for (String write : List.of(ActionRegistry.PACKET_ANALYSIS_RUN, ActionRegistry.PACKET_ANALYSIS_RAW_VIEW)) {
            var tokens = registry.find(write).orElseThrow().requiredRoleTokens();
            assertFalse(tokens.contains(RoleToken.REPLAY_VIEWER), write);
            assertFalse(tokens.contains(RoleToken.VIEWER), write);
        }
        Map<String, String> expected = Map.ofEntries(
                Map.entry("GET " + BASE + "/cases", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("POST " + BASE + "/cases", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("GET " + BASE + "/cases/*", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("DELETE " + BASE + "/cases/*", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("POST " + BASE + "/cases/*/captures", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("POST " + BASE + "/cases/*/analyze", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("POST " + BASE + "/cases/*/telemetry", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("PUT " + BASE + "/telemetry/*", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("DELETE " + BASE + "/telemetry/*", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("GET " + BASE + "/cases/*/state", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/flows", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/frames", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/diagnosis", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/charts", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/ladder", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/waterfall", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/timeseries", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/topology/overlay", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/topology/inspect", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/findings", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/events", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/dns", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/field-diff", ActionRegistry.PACKET_ANALYSIS_RAW_VIEW),
                Map.entry("GET " + BASE + "/cases/*/export/findings", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/export/download", ActionRegistry.PACKET_ANALYSIS_RAW_VIEW),
                Map.entry("GET " + BASE + "/cases/*/export/expert-report", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/sessions/evidence", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/reference/template", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("POST " + BASE + "/cases/*/compare", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/cases/*/correlation", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("PUT " + BASE + "/cases/*/topology", ActionRegistry.PACKET_ANALYSIS_RUN),
                Map.entry("GET " + BASE + "/runs/*", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/runs/*/report", ActionRegistry.PACKET_ANALYSIS_READ),
                Map.entry("GET " + BASE + "/captures/*/download", ActionRegistry.PACKET_ANALYSIS_RAW_VIEW));
        expected.forEach((route, action) -> assertEquals(action, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route), route));
        SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet().stream().filter(route -> route.contains("/packet-analysis/"))
                .forEach(route -> {
                    assertTrue(expected.containsKey(route), "unlisted packet-analysis route " + route);
                    assertTrue(route.chars().filter(c -> c == '*').count() <= 1, route);
                });
    }

    @Test void uploadGateNeverInvokesContainerMultipartParsingOrReadsTheBody() throws Exception {
        var chain = mock(GateChain.class);
        when(chain.machineWriteRefusal(any(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(chain.evaluate(any(), any())).thenReturn(new GateOutcome.Refused("RBAC", 403, Map.of("error", "DENIED")));
        String path = BASE + "/cases/" + CASE + "/captures";
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", path) {
            @Override public String getParameter(String name) { fail("Container multipart parsing must not run"); return null; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { fail("Upload must not be read before authorization"); return null; }
        };
        request.setServletPath(path);
        request.setContentType("multipart/form-data; boundary=SyntheticBoundary");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(new GateChainInterceptor(chain, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE).preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    // ---- operator ------------------------------------------------------------------------------------------

    @Test void operatorCreatesUploadsAndAnalysesButCannotDownload() throws Exception {
        mvc.perform(write("POST", BASE + "/cases", "synthetic-operator").contentType("application/json")
                .content("{\"title\":\"Synthetic loss case\",\"payload_zeroing\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.payload_zeroing").value(true))
                .andExpect(jsonPath("$.expires_at").exists());
        verify(repository).createCase(anyString(), eq("Synthetic loss case"), eq("synthetic-operator"), eq(true));

        byte[] capture = pcap(2000);
        mvc.perform(upload("synthetic-operator", "C:\\captures\\" + PRIVATE_FILE, capture))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.format").value("pcap"))
                .andExpect(jsonPath("$.size_bytes").value(capture.length));
        verify(repository).addCapture(argThat(row -> row.displayName().equals(PRIVATE_FILE) && row.sizeBytes() == capture.length
                && row.storageKey().endsWith(".pcap")), eq("synthetic-operator"));

        mvc.perform(write("POST", BASE + "/cases/" + CASE + "/analyze", "synthetic-operator"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("QUEUED"));
        verify(repository).requestAnalysis(eq(CASE), anyString(), eq("synthetic-operator"));

        mvc.perform(get(BASE + "/captures/" + CAPTURE + "/download").servletPath(BASE + "/captures/" + CAPTURE + "/download")
                .cookie(cookie("synthetic-operator"))).andExpect(status().isForbidden());
        verify(repository, never()).recordAccess(anyString(), anyString(), any(), eq("CAPTURE_DOWNLOADED"), anyString(), any());
    }

    @Test void uploadRefusesNonCapturesOversizeFilesAndAFullStore() throws Exception {
        mvc.perform(upload("synthetic-operator", "renamed.pcap", "synthetic,csv,content,not,a,capture,file".getBytes()))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").value("NOT_A_CAPTURE"));
        mvc.perform(upload("synthetic-operator", "large.pcap", pcap(5000)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").value("CAPTURE_TOO_LARGE"));
        Files.createDirectories(root.resolve("cases").resolve(CASE));
        Files.write(root.resolve("cases").resolve(CASE).resolve("filler.bin"), new byte[63 * 1024]);
        mvc.perform(upload("synthetic-operator", "ok.pcap", pcap(2000)))
                .andExpect(status().isInsufficientStorage()).andExpect(jsonPath("$.error").value("STORE_FULL"));
        verify(repository, never()).addCapture(any(), anyString());
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".pcap") || p.toString().contains(".partial-")));
        }
    }

    @Test void operatorReadsTheFullReportAndTheViewIsLedgeredFirst() throws Exception {
        Path report = root.resolve("cases").resolve(CASE).resolve("runs").resolve(JOB).resolve("report.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"verdict\":\"unknown\",\"captures\":[{\"display_name\":\"" + PRIVATE_FILE + "\"}]}");
        String path = BASE + "/runs/" + JOB + "/report";
        mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projection").value("full"))
                .andExpect(jsonPath("$.report.captures[0].display_name").value(PRIVATE_FILE));
        verify(repository).recordAccess(eq(CASE), isNull(), eq(JOB), eq("REPORT_VIEWED"), eq("synthetic-operator"), anyLong());
    }

    // ---- security_admin ------------------------------------------------------------------------------------

    @Test void securityAdminDownloadsTheCaptureOnlyAfterTheLedgerRow() throws Exception {
        byte[] capture = pcap(3024 - 24);
        Path file = root.resolve("cases").resolve(CASE).resolve("captures").resolve(CAPTURE + ".pcap");
        Files.createDirectories(file.getParent());
        Files.write(file, capture);
        String path = BASE + "/captures/" + CAPTURE + "/download";
        var result = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-secadmin"))).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"capture-66666666.pcap\""));
        assertArrayEquals(capture, result.getResponse().getContentAsByteArray());
        assertFalse(result.getResponse().getHeader("Content-Disposition").contains("customer"));
        verify(repository).recordAccess(CASE, CAPTURE, null, "CAPTURE_DOWNLOADED", "synthetic-secadmin", 3048L);
    }

    // ---- aiview and viewer ---------------------------------------------------------------------------------

    @Test void aiviewListsAndReadsOnlyTheMaskedProjection() throws Exception {
        String list = mvc.perform(get(BASE + "/cases").servletPath(BASE + "/cases").cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "true"))
                .andExpect(jsonPath("$.permissions.run").value(false)).andExpect(jsonPath("$.permissions.raw_view").value(false))
                .andExpect(jsonPath("$.cases[0].expires_at").exists())
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(PRIVATE_TITLE));
        assertFalse(list.contains("synthetic-operator"), "creator identity is withheld from aiview");

        String path = BASE + "/cases/" + CASE;
        String detail = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.captures[0].format").value("pcap"))
                .andExpect(jsonPath("$.runs[0].summary.verdict").value("unknown"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(detail.contains(PRIVATE_FILE));
        assertFalse(detail.contains(PRIVATE_TITLE));
        assertFalse(detail.contains("a".repeat(64)), "capture digests are withheld from aiview");

        Path report = root.resolve("cases").resolve(CASE).resolve("runs").resolve(JOB).resolve("report.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"raw\":\"192.0.2.44\"}");
        String reportPath = BASE + "/runs/" + JOB + "/report";
        String masked = mvc.perform(get(reportPath).servletPath(reportPath).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projection").value("masked"))
                .andExpect(jsonPath("$.summary.reason").value("engine not yet ported"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(masked.contains("192.0.2.44"));
        verify(repository, never()).recordAccess(anyString(), any(), any(), eq("REPORT_VIEWED"), anyString(), any());
    }

    @Test void aiviewIsRefusedEveryWriteRawViewAndDownload() throws Exception {
        mvc.perform(write("POST", BASE + "/cases", "synthetic-aiview").contentType("application/json")
                .content("{\"title\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(upload("synthetic-aiview", "x.pcap", pcap(100))).andExpect(status().isForbidden());
        mvc.perform(write("POST", BASE + "/cases/" + CASE + "/analyze", "synthetic-aiview")).andExpect(status().isForbidden());
        mvc.perform(write("DELETE", BASE + "/cases/" + CASE, "synthetic-aiview")).andExpect(status().isForbidden());
        String download = BASE + "/captures/" + CAPTURE + "/download";
        mvc.perform(get(download).servletPath(download).cookie(cookie("synthetic-aiview"))).andExpect(status().isForbidden());
        verify(repository, never()).createCase(anyString(), anyString(), anyString(), anyBoolean());
        verify(repository, never()).addCapture(any(), anyString());
        verify(repository, never()).requestAnalysis(anyString(), anyString(), anyString());
        verify(repository, never()).deleteCase(anyString(), anyString(), any(), any());
        verify(repository, never()).recordAccess(anyString(), any(), any(), anyString(), anyString(), any());
    }

    @Test void viewerHasNoPacketAnalysisAccess() throws Exception {
        mvc.perform(get(BASE + "/cases").servletPath(BASE + "/cases").cookie(cookie("synthetic-viewer")))
                .andExpect(status().isForbidden());
    }

    @Test void deleteIsOwnerScopedForOperatorsAndUnscopedForSecurityAdmins() throws Exception {
        when(repository.deleteCase(eq(CASE), anyString(), any(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(3)).run();
            return Outcome.DONE;
        });
        Files.createDirectories(root.resolve("cases").resolve(CASE).resolve("captures"));
        mvc.perform(write("DELETE", BASE + "/cases/" + CASE, "synthetic-operator")).andExpect(status().isOk());
        verify(repository).deleteCase(eq(CASE), eq("synthetic-operator"), eq("synthetic-operator"), any());
        assertFalse(Files.exists(root.resolve("cases").resolve(CASE)), "files are removed inside the deletion");
        mvc.perform(write("DELETE", BASE + "/cases/" + CASE, "synthetic-secadmin")).andExpect(status().isOk());
        verify(repository).deleteCase(eq(CASE), eq("synthetic-secadmin"), isNull(), any());
        when(repository.deleteCase(eq(CASE), anyString(), any(), any())).thenReturn(Outcome.BUSY);
        mvc.perform(write("DELETE", BASE + "/cases/" + CASE, "synthetic-operator")).andExpect(status().isConflict());
    }

    @Test void detailEndpointsReturn409AnalysisRunningWhileCaseIsQueuedOrAnalyzing() throws Exception {
        Instant now = Instant.now();
        CaseRow analyzingRow = new CaseRow(CASE, PRIVATE_TITLE, "synthetic-operator", now, now.plusSeconds(7 * 86400),
                "ANALYZING", 3048, false, 2, JOB);
        when(repository.findCase(CASE)).thenReturn(Optional.of(analyzingRow));

        for (String suffix : List.of("/state", "/flows", "/frames", "/diagnosis", "/charts", "/ladder", "/waterfall",
                "/timeseries", "/topology/overlay", "/topology/inspect?point=p1", "/findings", "/events", "/dns",
                "/export/findings", "/correlation", "/reference/template",
                "/sessions/evidence?point=p1&start=1&end=2")) {
            String full = BASE + "/cases/" + CASE + suffix;
            String servletPath = full.contains("?") ? full.substring(0, full.indexOf('?')) : full;
            mvc.perform(get(full).servletPath(servletPath).cookie(cookie("synthetic-operator")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("ANALYSIS_RUNNING"));
        }
        String diffPath = BASE + "/cases/" + CASE + "/field-diff";
        mvc.perform(get(diffPath).servletPath(diffPath).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ANALYSIS_RUNNING"));
        String downloadPath = BASE + "/cases/" + CASE + "/export/download";
        mvc.perform(get(downloadPath).param("format", "xlsx").servletPath(downloadPath).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ANALYSIS_RUNNING"));
        String expertPath = BASE + "/cases/" + CASE + "/export/expert-report";
        mvc.perform(get(expertPath).servletPath(expertPath).cookie(cookie("synthetic-operator")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ANALYSIS_RUNNING"));
        String comparePath = BASE + "/cases/" + CASE + "/compare";
        mvc.perform(write("POST", comparePath, "synthetic-operator").contentType("application/json").content("{\"reference\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ANALYSIS_RUNNING"));
        String topPath = BASE + "/cases/" + CASE + "/topology";
        mvc.perform(write("PUT", topPath, "synthetic-operator").contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ANALYSIS_RUNNING"));
    }

    @Test void detailEndpointsReadCaseDuckDbMaskAddressesAndNamesForAiviewAndGateRawFieldDiff() throws Exception {
        seedSyntheticCaseDuckDb();

        // Operator reads unmasked flows, ladder, diagnosis, charts, waterfall, findings, overlay, timeseries, export
        String flowsPath = BASE + "/cases/" + CASE + "/flows";
        String opFlows = mvc.perform(get(flowsPath).servletPath(flowsPath).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andReturn().getResponse().getContentAsString();
        assertTrue(opFlows.contains("192.0.2.10"), "Operator sees unmasked synthetic client address");
        String flowTuple = com.jayway.jsonpath.JsonPath.read(opFlows, "$.items[0].flow");

        // AIView reads masked flows, state, ladder, frames, findings, overlay: addresses & capture names masked, no raw payload
        String aiviewFlows = mvc.perform(get(flowsPath).servletPath(flowsPath).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Nexus-Masked", "true"))
                .andExpect(jsonPath("$.total").value(1))
                .andReturn().getResponse().getContentAsString();
        assertFalse(aiviewFlows.contains("192.0.2.10"), "AIView must not see raw client IP");
        assertFalse(aiviewFlows.contains("192.0.2.20"), "AIView must not see raw server IP");

        String statePath = BASE + "/cases/" + CASE + "/state";
        String aiviewState = mvc.perform(get(statePath).servletPath(statePath).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Nexus-Masked", "true"))
                .andExpect(jsonPath("$.report.verdict").value("No supported network loss in the selected window"))
                .andExpect(jsonPath("$.nexus_correlation.case_id").value(CASE))
                .andReturn().getResponse().getContentAsString();
        assertFalse(aiviewState.contains(PRIVATE_FILE), "AIView must not see raw capture filename in state");
        assertFalse(aiviewState.contains(PRIVATE_TITLE), "AIView must not see raw case title in state");

        String framesPath = BASE + "/cases/" + CASE + "/frames";
        String aiviewFrames = mvc.perform(get(framesPath).param("flow", flowTuple).param("point", "p1")
                .param("frames", "1", "4").servletPath(framesPath).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Nexus-Masked", "true"))
                .andExpect(jsonPath("$.items[0].frame").value(1))
                .andExpect(jsonPath("$.items[0].prefix").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(aiviewFrames.contains("474554202f"), "AIView must never receive payload hex bytes");
        assertFalse(aiviewFrames.contains(PRIVATE_FILE), "AIView must not see raw capture filename in frame evidence");

        for (String endpoint : List.of(
                BASE + "/cases/" + CASE + "/diagnosis?flow=" + java.net.URLEncoder.encode(flowTuple, StandardCharsets.UTF_8),
                BASE + "/cases/" + CASE + "/charts?flow=" + java.net.URLEncoder.encode(flowTuple, StandardCharsets.UTF_8),
                BASE + "/cases/" + CASE + "/ladder?flow=" + java.net.URLEncoder.encode(flowTuple, StandardCharsets.UTF_8),
                BASE + "/cases/" + CASE + "/waterfall?flow=" + java.net.URLEncoder.encode(flowTuple, StandardCharsets.UTF_8),
                BASE + "/cases/" + CASE + "/timeseries",
                BASE + "/cases/" + CASE + "/topology/overlay",
                BASE + "/cases/" + CASE + "/topology/inspect?point=p1",
                BASE + "/cases/" + CASE + "/findings",
                BASE + "/cases/" + CASE + "/events",
                BASE + "/cases/" + CASE + "/dns",
                BASE + "/cases/" + CASE + "/export/findings",
                BASE + "/cases/" + CASE + "/correlation")) {
            String servletPath = endpoint.contains("?") ? endpoint.substring(0, endpoint.indexOf('?')) : endpoint;
            String body = mvc.perform(get(endpoint).servletPath(servletPath).cookie(cookie("synthetic-aiview")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Nexus-Masked", "true"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains(PRIVATE_FILE), "AIView must not see raw capture filename on " + servletPath);
            assertFalse(body.contains("474554202f"), "AIView must not see raw payload hex on " + servletPath);
        }

        // field-diff requires PACKET_ANALYSIS_RAW_VIEW (security_admin only) and is audited
        String diffPath = BASE + "/cases/" + CASE + "/field-diff";
        mvc.perform(get(diffPath).servletPath(diffPath).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isForbidden());
        mvc.perform(get(diffPath).servletPath(diffPath).cookie(cookie("synthetic-operator")))
                .andExpect(status().isForbidden());
        verify(repository, never()).recordAccess(anyString(), any(), any(), eq("RAW_VIEWED"), anyString(), any());

        mvc.perform(get(diffPath).servletPath(diffPath).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        verify(repository).recordAccess(eq(CASE), isNull(), isNull(), eq("RAW_VIEWED"), eq("synthetic-secadmin"), eq(0L));
    }

    @Test void exportSessionEvidenceReferenceAndCompareFollowTheEngineAndTheRawViewGate() throws Exception {
        seedSyntheticCaseDuckDb();
        String casePath = BASE + "/cases/" + CASE;

        // Report and table downloads carry raw addresses: aiview and operators are refused before any ledger row.
        String download = casePath + "/export/download";
        for (String actor : List.of("synthetic-aiview", "synthetic-operator")) {
            mvc.perform(get(download).param("format", "html").servletPath(download).cookie(cookie(actor)))
                    .andExpect(status().isForbidden());
        }
        verify(repository, never()).recordAccess(anyString(), any(), any(), eq("RAW_VIEWED"), anyString(), any());
        mvc.perform(get(download).param("format", "pdf").servletPath(download).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isBadRequest());

        String html = mvc.perform(get(download).param("format", "html").servletPath(download).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/html")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(PRIVATE_TITLE))))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("<html") || html.contains("<!doctype html") || html.contains("<!DOCTYPE html"),
                "the engine HTML export is returned");
        byte[] zip = mvc.perform(get(download).param("format", "csv").servletPath(download).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(zip.length > 4 && zip[0] == 'P' && zip[1] == 'K', "CSV tables are a ZIP archive");
        byte[] xlsx = mvc.perform(get(download).param("format", "xlsx").servletPath(download).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(xlsx.length > 4 && xlsx[0] == 'P' && xlsx[1] == 'K', "the XLSX workbook is an OOXML ZIP");
        verify(repository, times(3)).recordAccess(eq(CASE), isNull(), isNull(), eq("RAW_VIEWED"),
                eq("synthetic-secadmin"), anyLong());

        // Lifecycle bucket evidence: unknown points and inverted windows are refused, never guessed.
        String sessions = casePath + "/sessions/evidence";
        mvc.perform(get(sessions).param("point", "p-unknown").param("start", "1700000000").param("end", "1700000002")
                        .servletPath(sessions).cookie(cookie("synthetic-operator")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(sessions).param("point", "p1").param("start", "1700000002").param("end", "1700000000")
                        .servletPath(sessions).cookie(cookie("synthetic-operator")))
                .andExpect(status().isBadRequest());

        // Reference template and comparison run on the saved report and are masked for aiview.
        String template = casePath + "/reference/template";
        String templateBody = mvc.perform(get(template).servletPath(template).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String compare = casePath + "/compare";
        mvc.perform(write("POST", compare, "synthetic-operator").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write("POST", compare, "synthetic-operator").contentType("application/json")
                        .content("{\"unexpected\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write("POST", compare, "synthetic-operator").contentType("application/json")
                        .content("{\"reference\":" + templateBody + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        String exportPath = casePath + "/export/findings";
        String previous = mvc.perform(get(exportPath).servletPath(exportPath).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(write("POST", compare, "synthetic-operator").contentType("application/json")
                        .content("{\"against\":" + previous + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff.metric_difference_count").isNumber());

        // aiview reads the same endpoints (read-grade), but every address it is shown is masked: the comparison echoes
        // values of the uploaded run, so an address in that upload must not come back in clear text.
        String payload = "{\"against\":{\"note\":\"client 192.0.2.10 reached 192.0.2.20\"}}";
        String operatorDiff = mvc.perform(write("POST", compare, "synthetic-operator").contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(operatorDiff.contains("192.0.2.10"), "the operator sees the compared value as sent");
        String aiviewDiff = mvc.perform(write("POST", compare, "synthetic-aiview").contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(aiviewDiff.contains("192.0.2.10"), "aiview comparison must not carry the first address");
        assertFalse(aiviewDiff.contains("192.0.2.20"), "aiview comparison must not carry the second address");
        mvc.perform(get(template).servletPath(template).cookie(cookie("synthetic-aiview"))).andExpect(status().isOk());
    }


    // ---- expert report ---------------------------------------------------------------------------------------

    @Test void expertReportIsReadGradeWithTheRawViewerGettingEngineValuesAndEveryoneElseTheMaskedProjection() throws Exception {
        seedSyntheticCaseDuckDb();
        String path = BASE + "/cases/" + CASE + "/export/expert-report";

        // the plain viewer holds none of the packet-analysis read roles
        mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-viewer"))).andExpect(status().isForbidden());

        byte[] raw = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("X-Nexus-Masked", "false"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"packet-analysis-11111111-expert-report.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        String rawText = workbookText(raw);
        assertTrue(rawText.contains(PRIVATE_FILE), "raw-view role: the engine's names");
        assertTrue(rawText.contains("a".repeat(64)), "raw-view role: the SHA-256 of the uploaded capture");
        assertTrue(rawText.contains(PRIVATE_TITLE + ": "), "raw-view role: the heading names the case");
        verify(repository).recordAccess(eq(CASE), isNull(), isNull(), eq("RAW_VIEWED"), eq("synthetic-secadmin"), eq((long) raw.length));

        for (String actor : List.of("synthetic-aiview", "synthetic-operator")) {
            byte[] masked = mvc.perform(get(path).servletPath(path).cookie(cookie(actor)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Nexus-Masked", "true"))
                    .andReturn().getResponse().getContentAsByteArray();
            String maskedText = workbookText(masked);
            assertFalse(maskedText.contains(PRIVATE_FILE), actor + " must not see the capture file name");
            assertFalse(maskedText.contains("synthetic-server-edge"), actor + " must not see the second capture name");
            assertFalse(maskedText.contains(PRIVATE_TITLE), actor + " must not see the case title");
            assertTrue(maskedText.contains("OBJ-") && maskedText.contains(" kayıt noktası"), actor + ": the heading carries the case pseudonym");
            assertFalse(maskedText.contains("a".repeat(64)), actor + " must not see the digest");
            assertFalse(maskedText.contains("192.0.2."), actor + " must not see an address");
            assertTrue(maskedText.contains("Akış 1") || maskedText.contains("FW-"), "pseudonymous names or flow ordinals");
        }
        verify(repository, times(2)).recordAccess(eq(CASE), isNull(), isNull(), eq("REPORT_VIEWED"), anyString(), anyLong());
        verify(repository, times(1)).recordAccess(eq(CASE), isNull(), isNull(), eq("RAW_VIEWED"), anyString(), anyLong());
    }

    @Test void expertReportFollowsTheRawViewActionNotTheRoleName() throws Exception {
        seedSyntheticCaseDuckDb();
        String path = BASE + "/cases/" + CASE + "/export/expert-report";
        // whatever packet_analysis_raw_view grants decides: the operator token is not in it ...
        assertFalse(ActionRegistry.PACKET_ANALYSIS_RAW_ROLES.contains(RoleToken.OPERATOR));
        assertTrue(ActionRegistry.PACKET_ANALYSIS_RUN_ROLES.contains(RoleToken.OPERATOR));
        String operator = workbookText(mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "true"))
                .andReturn().getResponse().getContentAsByteArray());
        assertFalse(operator.contains(PRIVATE_FILE), "run authority alone is not raw view");
        verify(repository).recordAccess(eq(CASE), isNull(), isNull(), eq("REPORT_VIEWED"), eq("synthetic-operator"), anyLong());
        // ... and a session that holds the raw-view action (here alongside operator) gets the raw projection
        String raw = workbookText(mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-operator-and-secadmin")))
                .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "false"))
                .andReturn().getResponse().getContentAsByteArray());
        assertTrue(raw.contains(PRIVATE_FILE));
        verify(repository).recordAccess(eq(CASE), isNull(), isNull(), eq("RAW_VIEWED"), eq("synthetic-operator-and-secadmin"), anyLong());
        // the replay viewer is masked even if it were also bound to a raw-view role
        verify(repository, never()).recordAccess(eq(CASE), isNull(), isNull(), eq("RAW_VIEWED"), eq("synthetic-operator"), anyLong());
    }

    @Test void expertReportValidatesItsWindowAndStateBeforeAnyLedgerRow() throws Exception {
        seedSyntheticCaseDuckDb();
        String path = BASE + "/cases/" + CASE + "/export/expert-report";
        for (var bad : List.<Map.Entry<String, String>>of(Map.entry("end", "1700000001"), Map.entry("top", "0"), Map.entry("top", "101"))) {
            mvc.perform(get(path).param(bad.getKey(), bad.getValue()).servletPath(path).cookie(cookie("synthetic-aiview")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("QUERY_INVALID"));
        }
        mvc.perform(get(path).param("start", "1700000100").param("end", "1700000050").servletPath(path).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("QUERY_INVALID"));
        mvc.perform(get(path).param("start", "1800000000").servletPath(path).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("WINDOW_OUT_OF_RANGE"));
        String missing = BASE + "/cases/not-an-id/export/expert-report";
        mvc.perform(get(missing).servletPath(missing).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_ID"));
        verify(repository, never()).recordAccess(anyString(), any(), any(), anyString(), anyString(), any());
        // a valid explicit window inside the capture is accepted
        mvc.perform(get(path).param("start", "1700000000.5").param("top", "5").servletPath(path).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk());
    }

    @Test void expertReportRefusesWhenTheLedgerCannotRecordTheDownload() throws Exception {
        seedSyntheticCaseDuckDb();
        doThrow(new IllegalStateException("ledger down")).when(repository)
                .recordAccess(anyString(), any(), any(), anyString(), anyString(), any());
        String path = BASE + "/cases/" + CASE + "/export/expert-report";
        mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value("AUDIT_REFUSED"));
    }

    private static String workbookText(byte[] xlsx) throws Exception {
        StringBuilder out = new StringBuilder();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                out.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return out.toString();
    }

    @Test void correlationEndpointReturnsMatchedFirewallInterfaceZoneAndRuleAndMasksForAiview() throws Exception {
        seedSyntheticCaseDuckDb();

        Instant now = Instant.parse("2026-10-16T06:00:00Z");
        var fwTango = new com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord(
                "dev-tango-04", "firewall", "palo_alto", DeviceEnrollmentState.ENROLLED,
                Optional.of("synthetic-core-fw-01"), Optional.of("PA-5220"), Optional.of("11.1.0"),
                Optional.empty(), Optional.empty(), Optional.of("COMPLETED"),
                Optional.of("inventory_collect"), Optional.empty(), Optional.empty(),
                Optional.of("192.0.2.254"), Optional.empty(), false);
        var ifaceIn = new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface(
                "if-tango-in", "ethernet1/1", Optional.empty(), "physical", "up",
                List.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress(
                        "addr-1", "192.0.2.1/24", "ipv4", "member")),
                Optional.empty(), Optional.of("00:53:00:aa:bb:01"), Optional.of("synthetic-trust-zone"));
        var invRun = new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun(
                "run-1", "dev-tango-04", "job-1", now.minusSeconds(120), 1,
                List.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext(
                        "physical", List.of(ifaceIn), List.of())));
        var rule = new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule(
                "rule-http-1", "uuid-http-1", 1, "Synthetic-Allow-HTTP", true,
                new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell(List.of("192.0.2.0/24"), false),
                new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell(List.of("192.0.2.20/32"), false),
                new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell(List.of("tcp/80"), false),
                new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell(List.of("web-browsing"), false),
                "allow", "log", "", Map.of());
        var policy = new com.securityexpert.nexus.ui2.policy.PolicySnapshot(
                new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata(
                        "pol-1", "src-1", "Panorama", "palo_alto", "dg-1", "DG-1",
                        "Synthetic-Core-Policy", now.minusSeconds(300).toString(), "cas-1",
                        List.of(new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target(
                                "dev-tango-04", "synthetic-core-fw-01", "vsys1", "in_sync"))),
                List.of(new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section(
                        "sec-1", "Security", "security", null, List.of(rule))),
                Map.of());

        var corrService = new com.securityexpert.nexus.ui2.service.packetanalysis.PacketAnalysisCorrelationService(
                repository, store, () -> List.of(fwTango), ids -> List.of(invRun), () -> List.of(policy), () -> now);
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var names = new TopologyNamePseudonymizer(new byte[32]);
        MockMvc correlatedMvc = MockMvcBuilders.standaloneSetup(
                new PacketAnalysisController(repository, store, rbac, names, corrService))
                .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), names))
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE))
                .build();

        String corrPath = BASE + "/cases/" + CASE + "/correlation";
        String opBody = correlatedMvc.perform(get(corrPath).servletPath(corrPath).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value(CASE))
                .andExpect(jsonPath("$.hops[0].status").value("MATCH"))
                .andExpect(jsonPath("$.hops[0].evidence[0]").value("interface_subnet_equality"))
                .andExpect(jsonPath("$.hops[0].device_id").value("dev-tango-04"))
                .andExpect(jsonPath("$.hops[0].hostname").value("synthetic-core-fw-01"))
                .andExpect(jsonPath("$.hops[0].interface_id").value("if-tango-in"))
                .andExpect(jsonPath("$.hops[0].zone").value("synthetic-trust-zone"))
                .andExpect(jsonPath("$.hops[0].configured_inventory_says.device_id").value("dev-tango-04"))
                .andExpect(jsonPath("$.flows[0].status").value("MATCH"))
                .andExpect(jsonPath("$.flows[0].evidence[0]").value("rule_5tuple_containment"))
                .andExpect(jsonPath("$.flows[0].rule_id").value("rule-http-1"))
                .andExpect(jsonPath("$.flows[0].rule_name").value("Synthetic-Allow-HTTP"))
                .andExpect(jsonPath("$.flows[0].configured_policy_says.rule_id").value("rule-http-1"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(opBody.contains("192.0.2.1/24"));
        // Fail closed: every string of the correlation view has a payload-masking classification (else TXT- tokens).
        assertFalse(opBody.matches("(?s).*\"TXT-[0-9A-F]{10}\".*"), "unclassified correlation field: " + opBody);

        String aiviewBody = correlatedMvc.perform(get(corrPath).servletPath(corrPath).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Nexus-Masked", "true"))
                .andExpect(jsonPath("$.case_id").value(CASE))
                .andExpect(jsonPath("$.hops[0].status").value("MATCH"))
                .andExpect(jsonPath("$.hops[0].evidence[0]").value("interface_subnet_equality"))
                .andExpect(jsonPath("$.hops[0].device_id").value("dev-tango-04"))
                .andExpect(jsonPath("$.hops[0].interface_id").value("if-tango-in"))
                .andExpect(jsonPath("$.flows[0].status").value("MATCH"))
                .andExpect(jsonPath("$.flows[0].rule_id").value("rule-http-1"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(aiviewBody.contains("synthetic-core-fw-01"), "AIView must see pseudonymized firewall name");
        assertFalse(aiviewBody.contains("synthetic-trust-zone"), "AIView must see pseudonymized zone name");
        assertFalse(aiviewBody.contains("Synthetic-Allow-HTTP"), "AIView must see pseudonymized rule name");
        assertFalse(aiviewBody.contains(PRIVATE_FILE), "AIView must not see raw capture filename");
        assertFalse(aiviewBody.contains("00:53:00:aa:bb:01"), "AIView must see masked MAC");
    }

    private void seedSyntheticCaseDuckDb() throws Exception {
        Path caseDir = PacketAnalysisPaths.caseDir(root, CASE);
        var projectStore = com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore.open(
                caseDir, com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore.Settings.defaults(null));
        String invJson = "{\"packet_count\":6,\"records_read\":6,\"prefilter_excluded\":0,\"prefilter_skipped\":false}";
        try (var db = projectStore.connect()) {
            db.execute("INSERT INTO captures (id, path, name, state, checkpoint, inventory, error) VALUES (?, ?, ?, 'ready', 6, ?, NULL)",
                    "cap1", "/synthetic/cap1.pcap", PRIVATE_FILE, invJson);
            db.execute("INSERT INTO captures (id, path, name, state, checkpoint, inventory, error) VALUES (?, ?, ?, 'ready', 6, ?, NULL)",
                    "cap2", "/synthetic/cap2.pcap", "synthetic-server-edge.pcap", invJson);
            String insertPkt = "INSERT INTO packets (capture_id, frame, ts, iface, src, dst, sport, dport, proto, ipid, "
                    + "seq, ack, flags, length, wirelen, caplen, prefix, payload_hash, signature, tuple_key, reverse_tuple, "
                    + "stream, ttl, dscp, mss, \"window\", retrans, fast_retrans, spurious, out_of_order, acked_unseen, "
                    + "zero_window, rtt, unsupported, frame_hash, icmp_id, icmp_seq, icmp_type, dns_id, dns_response, vendor, path_fields) "
                    + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, 'TCP', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 1460, 65535, "
                    + "false, false, false, false, false, false, NULL, '', ?, NULL, NULL, NULL, NULL, false, '{}', '{\"transport_index\":1,\"dns_index\":1}')";
            String fwdTuple = "[\"TCP\",\"192.0.2.10\",40000,\"192.0.2.20\",80]";
            String revTuple = "[\"TCP\",\"192.0.2.20\",80,\"192.0.2.10\",40000]";
            Object[][] pkts = {
                {1L, 1700000000.100, 1700000000.105, "192.0.2.10", "192.0.2.20", 40000L, 80L, 1000L, 0L, 2L, 64L, 63L, 101L, 0L, "", "", "sig1", fwdTuple, revTuple, "h1"},
                {2L, 1700000000.120, 1700000000.115, "192.0.2.20", "192.0.2.10", 80L, 40000L, 2000L, 1001L, 18L, 63L, 64L, 201L, 0L, "", "", "sig2", revTuple, fwdTuple, "h2"},
                {3L, 1700000000.130, 1700000000.135, "192.0.2.10", "192.0.2.20", 40000L, 80L, 1001L, 2001L, 16L, 64L, 63L, 102L, 0L, "", "", "sig3", fwdTuple, revTuple, "h3"},
                {4L, 1700000001.100, 1700000001.105, "192.0.2.10", "192.0.2.20", 40000L, 80L, 1001L, 2001L, 24L, 64L, 63L, 103L, 18L, "474554202f", "p1", "sig4", fwdTuple, revTuple, "h4"},
                {5L, 1700000001.150, 1700000001.145, "192.0.2.20", "192.0.2.10", 80L, 40000L, 2001L, 1019L, 16L, 63L, 64L, 202L, 0L, "", "", "sig5", revTuple, fwdTuple, "h5"},
                {6L, 1700000001.200, 1700000001.195, "192.0.2.20", "192.0.2.10", 80L, 40000L, 2001L, 1019L, 24L, 63L, 64L, 203L, 15L, "485454502f", "p2", "sig6", revTuple, fwdTuple, "h6"}
            };
            for (Object[] p : pkts) {
                long len = (Long) p[13];
                db.execute(insertPkt, "cap1", p[0], p[1], p[3], p[4], p[5], p[6], p[12], p[7], p[8], p[9], len,
                        54L + len, 54L + len, p[14], p[15], p[16], p[17], p[18], p[10], p[19]);
                db.execute(insertPkt, "cap2", p[0], p[2], p[3], p[4], p[5], p[6], p[12], p[7], p[8], p[9], len,
                        54L + len, 54L + len, p[14], p[15], p[16], p[17], p[18], p[11], p[19]);
            }
        }
        var top = new com.securityexpert.nexus.ui2.packetanalysis.topology.Topology();
        top.points.add(com.securityexpert.nexus.ui2.packetanalysis.topology.Point.of(
                "p1", PRIVATE_FILE, PRIVATE_FILE, "cap1",
                com.securityexpert.nexus.ui2.packetanalysis.topology.Py.i(0),
                com.securityexpert.nexus.ui2.packetanalysis.topology.Py.i(0)));
        top.points.add(com.securityexpert.nexus.ui2.packetanalysis.topology.Point.of(
                "p2", "synthetic-server-edge.pcap", "synthetic-server-edge.pcap", "cap2",
                com.securityexpert.nexus.ui2.packetanalysis.topology.Py.i(240),
                com.securityexpert.nexus.ui2.packetanalysis.topology.Py.i(0)));
        top.forward = List.of("p1", "p2");
        top.reverse = List.of("p2", "p1");
        top.clientCidrs = List.of("192.0.2.10/32");
        com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner.runEngineOnTopology(projectStore.path(), top.dump());
    }


    // ---- host telemetry --------------------------------------------------------------------------------------

    private static final String VMSTAT_TEXT = "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu----- -----timestamp-----\n"
            + " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st                 UTC\n"
            + " 2  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0 2023-11-14 22:13:19\n"
            + " 3  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0 2023-11-14 22:13:20\n"
            + " 9  0      0 8037584  79200 5978804    0    0     7    23   40   64  1 30 69  0  0 2023-11-14 22:13:21\n";

    private MockHttpServletRequestBuilder telemetryUpload(String actor, String fileName, byte[] content) throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(("--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write("\r\n--SyntheticBoundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return write("POST", BASE + "/cases/" + CASE + "/telemetry", actor)
                .contentType("multipart/form-data; boundary=SyntheticBoundary").content(body.toByteArray());
    }

    private TelemetryRow telemetryRow(String zone, int offset) {
        return new TelemetryRow(TELEMETRY, CASE, PRIVATE_TELEMETRY, VMSTAT_TEXT.length(), "e".repeat(64), TELEMETRY + ".txt", "vmstat",
                null, zone, offset, null, "synthetic-operator", Instant.now(), null, null);
    }

    @Test void operatorAttachesTelemetryThatIsSniffedByContentAndStoredNextToTheCapturesWithTheSameGates() throws Exception {
        mvc.perform(telemetryUpload("synthetic-operator", "C:\\logs\\" + PRIVATE_TELEMETRY, VMSTAT_TEXT.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.detected_format").value("vmstat"))
                .andExpect(jsonPath("$.recognized").value(true)).andExpect(jsonPath("$.zone_confirmed").value(false))
                .andExpect(jsonPath("$.display_name").value(PRIVATE_TELEMETRY)).andExpect(jsonPath("$.size_bytes").value(VMSTAT_TEXT.length()));
        var rows = org.mockito.ArgumentCaptor.forClass(TelemetryRow.class);
        verify(repository).addTelemetry(rows.capture(), eq("synthetic-operator"));
        TelemetryRow stored = rows.getValue();
        assertEquals(PRIVATE_TELEMETRY, stored.displayName());
        assertEquals("vmstat", stored.detectedFormat());
        assertTrue(stored.storageKey().endsWith(".txt") && stored.storageKey().startsWith(stored.telemetryId()));
        assertNull(stored.timeZone(), "no zone confirmed yet: UTC is assumed and the report says so");
        assertTrue(Files.isRegularFile(root.resolve("cases").resolve(CASE).resolve("telemetry").resolve(stored.storageKey())));
        assertEquals(VMSTAT_TEXT, Files.readString(root.resolve("cases").resolve(CASE).resolve("telemetry").resolve(stored.storageKey())));

        // text that no parser knows is still accepted, flagged as not recognised with a closed reason
        mvc.perform(telemetryUpload("synthetic-operator", "table.csv", "a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.detected_format").value("unknown"))
                .andExpect(jsonPath("$.recognized").value(false)).andExpect(jsonPath("$.recognition_note").value("NO_KNOWN_FORMAT"));
        // a binary file (a capture sent to the wrong place) is refused by its bytes
        mvc.perform(telemetryUpload("synthetic-operator", "renamed.txt", pcap(200)))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").value("NOT_A_TEXT_FILE"));
        // over the per-file limit (the test store allows 4096 bytes)
        mvc.perform(telemetryUpload("synthetic-operator", "big.txt", ("x".repeat(100) + "\n").repeat(60).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").value("CAPTURE_TOO_LARGE"));
        verify(repository, times(2)).addTelemetry(any(), anyString());
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(p -> p.toString().contains(".partial-")), "no partial file is left behind");
        }
    }

    @Test void telemetryUploadAlignmentAndDeleteAreRefusedToEveryRoleWithoutRunAuthority() throws Exception {
        byte[] text = VMSTAT_TEXT.getBytes(StandardCharsets.UTF_8);
        for (String actor : List.of("synthetic-aiview", "synthetic-viewer")) {
            mvc.perform(telemetryUpload(actor, "x.txt", text)).andExpect(status().isForbidden());
            mvc.perform(write("PUT", BASE + "/telemetry/" + TELEMETRY, actor).contentType("application/json").content("{\"time_zone\":\"UTC\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(write("DELETE", BASE + "/telemetry/" + TELEMETRY, actor)).andExpect(status().isForbidden());
        }
        verify(repository, never()).addTelemetry(any(), anyString());
        verify(repository, never()).setTelemetryAlignment(anyString(), any(), anyInt(), any(), anyString());
        verify(repository, never()).deleteTelemetry(anyString(), anyString(), any());
        // a security administrator holds the run authority too
        mvc.perform(telemetryUpload("synthetic-secadmin", "x.txt", text)).andExpect(status().isCreated());
    }

    @Test void theAlignmentTheUserConfirmsIsValidatedAndRecordedAndAnInvalidOneIsRefused() throws Exception {
        when(repository.setTelemetryAlignment(eq(TELEMETRY), any(), anyInt(), any(), anyString())).thenReturn(Outcome.DONE);
        when(repository.findTelemetry(TELEMETRY)).thenReturn(Optional.of(telemetryRow("Europe/Istanbul", 5)));
        mvc.perform(write("PUT", BASE + "/telemetry/" + TELEMETRY, "synthetic-operator").contentType("application/json")
                .content("{\"time_zone\":\"Europe/Istanbul\",\"offset_seconds\":5,\"anchor_local\":\"2023-11-15 01:13:19\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.zone_confirmed").value(true))
                .andExpect(jsonPath("$.time_zone").value("Europe/Istanbul")).andExpect(jsonPath("$.offset_seconds").value(5));
        verify(repository).setTelemetryAlignment(TELEMETRY, "Europe/Istanbul", 5, "2023-11-15 01:13:19", "synthetic-operator");
        // clearing the zone goes back to "not confirmed"
        mvc.perform(write("PUT", BASE + "/telemetry/" + TELEMETRY, "synthetic-operator").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        verify(repository).setTelemetryAlignment(TELEMETRY, null, 0, null, "synthetic-operator");
        for (String bad : List.of("{\"time_zone\":\"Mars/Olympus\"}", "{\"time_zone\":\"../../etc/passwd\"}",
                "{\"offset_seconds\":99999}", "{\"anchor_local\":\"last tuesday\"}", "{\"anchor_local\":\"2023-13-45 00:00:00\"}")) {
            mvc.perform(write("PUT", BASE + "/telemetry/" + TELEMETRY, "synthetic-operator").contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("ALIGNMENT_INVALID"));
        }
        verify(repository, times(2)).setTelemetryAlignment(anyString(), any(), anyInt(), any(), anyString());
        when(repository.setTelemetryAlignment(eq(TELEMETRY), any(), anyInt(), any(), anyString())).thenReturn(Outcome.NOT_FOUND);
        mvc.perform(write("PUT", BASE + "/telemetry/" + TELEMETRY, "synthetic-operator").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("TELEMETRY_NOT_FOUND"));
        mvc.perform(write("PUT", BASE + "/telemetry/not-an-id", "synthetic-operator").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void deletingTelemetryRemovesTheFileInsideTheLedgeredDeletion() throws Exception {
        Path file = root.resolve("cases").resolve(CASE).resolve("telemetry").resolve(TELEMETRY + ".txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, VMSTAT_TEXT);
        when(repository.findTelemetry(TELEMETRY)).thenReturn(Optional.of(telemetryRow(null, 0)));
        when(repository.deleteTelemetry(eq(TELEMETRY), anyString(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return Outcome.DONE;
        });
        mvc.perform(write("DELETE", BASE + "/telemetry/" + TELEMETRY, "synthetic-operator")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        assertFalse(Files.exists(file));
        verify(repository).deleteTelemetry(eq(TELEMETRY), eq("synthetic-operator"), any());
        when(repository.findTelemetry(TELEMETRY)).thenReturn(Optional.empty());
        mvc.perform(write("DELETE", BASE + "/telemetry/" + TELEMETRY, "synthetic-operator")).andExpect(status().isNotFound());
    }

    @Test void theCaseDetailListsTelemetryAndAiviewSeesNeitherItsNameNorItsDigest() throws Exception {
        when(repository.telemetry(CASE)).thenReturn(List.of(telemetryRow("UTC", 0)));
        String path = BASE + "/cases/" + CASE;
        String operator = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.telemetry[0].display_name").value(PRIVATE_TELEMETRY))
                .andExpect(jsonPath("$.telemetry[0].detected_format").value("vmstat"))
                .andExpect(jsonPath("$.telemetry[0].zone_confirmed").value(true))
                .andReturn().getResponse().getContentAsString();
        assertTrue(operator.contains("e".repeat(64)));
        String aiview = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-aiview"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.telemetry[0].detected_format").value("vmstat"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(aiview.contains(PRIVATE_TELEMETRY), "the file name is masked for aiview");
        assertFalse(aiview.contains("e".repeat(64)), "the digest is withheld from aiview");
        assertFalse(aiview.contains("synthetic-operator"), "the uploader is withheld from aiview");
    }

    @Test void theExpertReportCarriesTheTelemetrySheetAndMasksItsNamesForEveryoneWithoutRawView() throws Exception {
        seedSyntheticCaseDuckDb();
        Path file = root.resolve("cases").resolve(CASE).resolve("telemetry").resolve(TELEMETRY + ".txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, VMSTAT_TEXT);
        when(repository.telemetry(CASE)).thenReturn(List.of(telemetryRow("UTC", 0)));
        String path = BASE + "/cases/" + CASE + "/export/expert-report";

        byte[] raw = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-secadmin"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(workbookText(raw).contains("A. Saat eşlemesi"), "the host sheet is in the workbook");
        assertTrue(workbookText(raw).contains(PRIVATE_TELEMETRY));
        byte[] masked = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-operator"))).andExpect(status().isOk())
                .andExpect(header().string("X-Nexus-Masked", "true")).andReturn().getResponse().getContentAsByteArray();
        String maskedText = workbookText(masked);
        assertFalse(maskedText.contains(PRIVATE_TELEMETRY));
        assertFalse(maskedText.contains("e".repeat(64)));
        assertTrue(maskedText.contains("A. Saat eşlemesi"), "masked readers get the sheet too, with tokens for the names");
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static Cookie cookie(String actor) {
        return new Cookie("ui2_session", "cookie-" + actor);
    }

    private static MockHttpServletRequestBuilder write(String method, String path, String actor) {
        return request(org.springframework.http.HttpMethod.valueOf(method), path).servletPath(path).cookie(cookie(actor)).header("X-CSRF-Token", "csrf-" + actor)
                .header("Origin", "https://example.invalid");
    }

    private MockHttpServletRequestBuilder upload(String actor, String fileName, byte[] content) throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(("--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write("\r\n--SyntheticBoundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return write("POST", BASE + "/cases/" + CASE + "/captures", actor)
                .contentType("multipart/form-data; boundary=SyntheticBoundary").content(body.toByteArray());
    }

    private static byte[] pcap(int payload) {
        byte[] bytes = new byte[24 + payload];
        bytes[0] = (byte) 0xd4; bytes[1] = (byte) 0xc3; bytes[2] = (byte) 0xb2; bytes[3] = (byte) 0xa1;
        return bytes;
    }
}
