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
        mvc = MockMvcBuilders.standaloneSetup(new PacketAnalysisController(repository, store, rbac, names))
                .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), names))
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
        Map<String, String> expected = Map.of(
                "GET " + BASE + "/cases", ActionRegistry.PACKET_ANALYSIS_READ,
                "POST " + BASE + "/cases", ActionRegistry.PACKET_ANALYSIS_RUN,
                "GET " + BASE + "/cases/*", ActionRegistry.PACKET_ANALYSIS_READ,
                "DELETE " + BASE + "/cases/*", ActionRegistry.PACKET_ANALYSIS_RUN,
                "POST " + BASE + "/cases/*/captures", ActionRegistry.PACKET_ANALYSIS_RUN,
                "POST " + BASE + "/cases/*/analyze", ActionRegistry.PACKET_ANALYSIS_RUN,
                "GET " + BASE + "/runs/*", ActionRegistry.PACKET_ANALYSIS_READ,
                "GET " + BASE + "/runs/*/report", ActionRegistry.PACKET_ANALYSIS_READ,
                "GET " + BASE + "/captures/*/download", ActionRegistry.PACKET_ANALYSIS_RAW_VIEW);
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
