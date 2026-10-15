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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository.Entry;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository.Found;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository.JobRow;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository.RevertRow;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.api.SanitizerController;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.sanitizer.SanitizerService;
import com.securityexpert.nexus.ui2.service.sanitizer.VaultCrypto;

/**
 * Data Sanitizer API through the real gate chain (DATA_SANITIZER_CONTRACT.md §2 "Who", §3.1): grants per role
 * (operator, operation_admin, security_admin run; aiview and viewer neither), jobs kept to their creator, revert
 * scoped to the actor's own vaults unless security_admin, streamed uploads after the gates, one download then the
 * temporary file is gone, and a vault whose rows hold no original and no token.
 */
class SanitizerApiTest {

    private static final String BASE = "/api/v2/sanitize";
    private static final String LOG = "Oct 16 12:00:01 corefw kernel: DROP SRC=192.0.2.10 DST=198.51.100.20 host www.example.com\n";

    @TempDir Path work;
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final SanitizerRepository repository = mock(SanitizerRepository.class);
    private final Map<String, Set<String>> roles = Map.of(
            "synthetic-operator", Set.of(RoleToken.OPERATOR),
            "synthetic-opadmin", Set.of(RoleToken.OPERATION_ADMIN),
            "synthetic-other-operator", Set.of(RoleToken.OPERATOR),
            "synthetic-secadmin", Set.of(RoleToken.SECURITY_ADMIN),
            "synthetic-viewer", Set.of(RoleToken.VIEWER),
            "synthetic-aiview", Set.of(RoleToken.REPLAY_VIEWER));
    private final Map<String, JobRow> jobs = new HashMap<>();
    private final Map<String, RevertRow> reverts = new HashMap<>();
    private final List<Object[]> vaultRows = new ArrayList<>(); // jobId, Entry
    private final List<String[]> lookups = new ArrayList<>();    // createdBy, jobId
    private VaultCrypto crypto;
    private MockMvc mvc;

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
        fakeRepository();
        byte[] privacyKey = new byte[32];
        Arrays.fill(privacyKey, (byte) 7);
        crypto = new VaultCrypto(privacyKey, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
        var service = new SanitizerService(repository, crypto, privacyKey, () -> KnownNames.of(Map.of("corefw", "FW-TANGO-04")),
                work, 64 * 1024, 90, Duration.ofMinutes(60), new DirectExecutor());
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var names = new TopologyNamePseudonymizer(new byte[32]);
        mvc = MockMvcBuilders.standaloneSetup(new SanitizerController(service, rbac))
                .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), names))
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
    }

    // ---- grants and routes ---------------------------------------------------------------------------------

    @Test
    void actionsGrantRunAndRevertToOperatorLevelRolesAndNeitherToAiviewOrViewer() {
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.DATA_SANITIZE_RUN).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                registry.find(ActionRegistry.DATA_SANITIZE_REVERT).orElseThrow().requiredRoleTokens());
        for (String action : List.of(ActionRegistry.DATA_SANITIZE_RUN, ActionRegistry.DATA_SANITIZE_REVERT)) {
            var tokens = registry.find(action).orElseThrow().requiredRoleTokens();
            assertFalse(tokens.contains(RoleToken.REPLAY_VIEWER), action);
            assertFalse(tokens.contains(RoleToken.VIEWER), action);
        }
        Map<String, String> expected = Map.of(
                "POST " + BASE, ActionRegistry.DATA_SANITIZE_RUN,
                "GET " + BASE + "/jobs", ActionRegistry.DATA_SANITIZE_RUN,
                "GET " + BASE + "/jobs/*", ActionRegistry.DATA_SANITIZE_RUN,
                "GET " + BASE + "/jobs/*/download", ActionRegistry.DATA_SANITIZE_RUN,
                "POST " + BASE + "/revert", ActionRegistry.DATA_SANITIZE_REVERT,
                "GET " + BASE + "/reverts/*", ActionRegistry.DATA_SANITIZE_REVERT,
                "GET " + BASE + "/reverts/*/download", ActionRegistry.DATA_SANITIZE_REVERT);
        expected.forEach((route, action) -> assertEquals(action, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route), route));
        SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet().stream().filter(r -> r.contains(" " + BASE))
                .forEach(r -> assertTrue(expected.containsKey(r), "unlisted sanitizer route " + r));
    }

    @Test
    void uploadGateNeverParsesTheMultipartBodyBeforeAuthorization() throws Exception {
        for (String path : List.of(BASE, BASE + "/revert")) {
            var chain = mock(GateChain.class);
            when(chain.machineWriteRefusal(any(), anyString(), any(), any())).thenReturn(Optional.empty());
            when(chain.evaluate(any(), any())).thenReturn(new GateOutcome.Refused("RBAC", 403, Map.of("error", "DENIED")));
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
    }

    // ---- refusals first ------------------------------------------------------------------------------------

    @Test
    void aiviewAndViewerAreRefusedEveryRouteAndNothingIsWritten() throws Exception {
        for (String actor : List.of("synthetic-aiview", "synthetic-viewer")) {
            mvc.perform(upload(BASE, actor, LOG.getBytes(StandardCharsets.UTF_8), Map.of())).andExpect(status().isForbidden());
            mvc.perform(upload(BASE + "/revert", actor, LOG.getBytes(StandardCharsets.UTF_8), Map.of())).andExpect(status().isForbidden());
            String id = UUID.randomUUID().toString();
            for (String path : List.of(BASE + "/jobs", BASE + "/jobs/" + id, BASE + "/jobs/" + id + "/download",
                    BASE + "/reverts/" + id, BASE + "/reverts/" + id + "/download")) {
                mvc.perform(get(path).servletPath(path).cookie(cookie(actor))).andExpect(status().isForbidden());
            }
        }
        verify(repository, never()).createJob(anyString(), anyString(), any(), anyString(), anyInt());
        verify(repository, never()).createRevert(anyString(), anyString(), anyString(), any());
        try (var files = Files.list(work)) {
            assertEquals(0, files.count(), "nothing reached the work area");
        }
    }

    @Test
    void oversizeAndEmptyUploadsAreRefusedAndLeaveNoFile() throws Exception {
        mvc.perform(upload(BASE, "synthetic-operator", new byte[64 * 1024 + 1], Map.of()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").value("FILE_TOO_LARGE"));
        mvc.perform(upload(BASE, "synthetic-operator", new byte[0], Map.of())).andExpect(status().isBadRequest());
        try (var files = Files.list(work)) {
            assertEquals(0, files.count());
        }
        verify(repository, never()).createJob(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void anotherOperatorCannotSeeDownloadOrRevertAgainstSomeoneElsesJob() throws Exception {
        String jobId = sanitize("synthetic-operator");
        String path = BASE + "/jobs/" + jobId;
        mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-other-operator"))).andExpect(status().isForbidden());
        mvc.perform(get(path + "/download").servletPath(path + "/download").cookie(cookie("synthetic-other-operator")))
                .andExpect(status().isForbidden());
        mvc.perform(upload(BASE + "/revert", "synthetic-other-operator", "x".getBytes(StandardCharsets.UTF_8),
                Map.of("source_job_id", jobId))).andExpect(status().isForbidden());
        mvc.perform(upload(BASE + "/revert", "synthetic-other-operator", "x".getBytes(StandardCharsets.UTF_8),
                Map.of("scope", "all"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("NOT_JOB_OWNER"));
    }

    // ---- the operator's round trip -------------------------------------------------------------------------

    @Test
    void operatorSanitizesDownloadsOnceAndRevertsAgainstOwnVaultOnly() throws Exception {
        String jobId = sanitize("synthetic-operator");
        JobRow row = jobs.get(jobId);
        assertEquals("SUCCEEDED", row.state());
        assertEquals("text", row.fileType());
        String path = BASE + "/jobs/" + jobId;
        String view = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counts.ipv4").value(2))
                .andExpect(jsonPath("$.counts.device_name").value(1)).andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(view.contains("192.0.2.10") || view.contains("corefw") || view.contains("example"), "a job view carries counts only");
        try (var files = Files.list(work)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".in")), "the input is deleted when the job ends");
        }

        MvcResult result = mvc.perform(get(path + "/download").servletPath(path + "/download").cookie(cookie("synthetic-operator"))).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"sanitized-" + jobId.substring(0, 8) + ".txt\""));
        byte[] sanitized = result.getResponse().getContentAsByteArray();
        String text = new String(sanitized, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("Oct 16 12:00:01 FW-TANGO-04 kernel: DROP SRC="), text);
        assertFalse(text.contains("192.0.2.10") || text.contains("www.example.com"));
        try (var files = Files.list(work)) {
            assertEquals(0, files.count(), "the sanitized file is gone after its download");
        }
        mvc.perform(get(path + "/download").servletPath(path + "/download").cookie(cookie("synthetic-operator")))
                .andExpect(status().isGone());

        // The file comes back edited; the operator reverts it against the vaults of their own jobs.
        byte[] returned = ("AI summary: " + text).getBytes(StandardCharsets.UTF_8);
        String revertId = revert("synthetic-operator", returned, Map.of());
        assertEquals("OWN_JOBS", reverts.get(revertId).scope());
        assertEquals("synthetic-operator", lookups.get(lookups.size() - 1)[0], "an operator's lookups are restricted to their jobs");
        String rpath = BASE + "/reverts/" + revertId + "/download";
        MvcResult back = mvc.perform(get(rpath).servletPath(rpath).cookie(cookie("synthetic-operator"))).andReturn();
        mvc.perform(asyncDispatch(back)).andExpect(status().isOk());
        assertEquals("AI summary: " + LOG, back.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void securityAdminRevertsAgainstEveryVaultOrOneNamedJob() throws Exception {
        String jobId = sanitize("synthetic-operator");
        String revertAll = revert("synthetic-secadmin", "x 192.0.2.10".getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals("ALL_JOBS", reverts.get(revertAll).scope());
        assertNull(lookups.get(lookups.size() - 1)[0], "no creator filter");
        String revertOne = revert("synthetic-secadmin", "x".getBytes(StandardCharsets.UTF_8), Map.of("source_job_id", jobId));
        assertEquals("ONE_JOB", reverts.get(revertOne).scope());
        String rpath = BASE + "/reverts/" + revertAll;
        mvc.perform(get(rpath).servletPath(rpath).cookie(cookie("synthetic-operator"))).andExpect(status().isForbidden());
    }

    @Test
    void vaultRowsHoldNeitherOriginalsNorTokensAndUnsealOnlyInTheirOwnRow() throws Exception {
        String jobId = sanitize("synthetic-opadmin");
        assertFalse(vaultRows.isEmpty());
        List<String> originals = List.of("192.0.2.10", "198.51.100.20", "corefw", "www", "example", "FW-TANGO-04");
        for (Object[] row : vaultRows) {
            Entry entry = (Entry) row[1];
            assertEquals(32, entry.lookupKey().length);
            String sealed = new String(entry.sealedOriginal(), StandardCharsets.ISO_8859_1);
            String key = new String(entry.lookupKey(), StandardCharsets.ISO_8859_1);
            for (String original : originals) {
                assertFalse(sealed.contains(original) || key.contains(original), "no original in a vault row");
            }
        }
        byte[] wrapped = wrappedKeys.get(jobId);
        var dataKey = crypto.unwrap(wrapped);
        Entry first = (Entry) vaultRows.get(0)[1];
        assertNotNull(crypto.unseal(dataKey, jobId, first.lookupKey(), first.sealedOriginal()));
        assertThrows(IllegalStateException.class,
                () -> crypto.unseal(dataKey, UUID.randomUUID().toString(), first.lookupKey(), first.sealedOriginal()),
                "a sealed value moved to another job's row does not open");
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private final Map<String, byte[]> wrappedKeys = new HashMap<>();

    private void fakeRepository() {
        doAnswer(call -> {
            String id = call.getArgument(0);
            wrappedKeys.put(id, call.getArgument(2));
            jobs.put(id, new JobRow(id, call.getArgument(1), Instant.now(), "RUNNING", null, null, null, "{}", null, null,
                    "PENDING", 0, Instant.now().plusSeconds(90 * 86400L), null));
            return null;
        }).when(repository).createJob(anyString(), anyString(), any(), anyString(), anyInt());
        when(repository.insertEntries(anyString(), anyString(), anyList())).thenAnswer(call -> {
            List<Entry> entries = call.getArgument(2);
            for (Entry e : entries) vaultRows.add(new Object[] {call.getArgument(0), e});
            return entries.size();
        });
        doAnswer(call -> {
            String id = call.getArgument(0);
            JobRow old = jobs.get(id);
            boolean ok = call.getArgument(2);
            jobs.put(id, new JobRow(id, old.createdBy(), old.createdAt(), ok ? "SUCCEEDED" : "FAILED", call.getArgument(3),
                    call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7), Instant.now(),
                    ok ? "READY" : "NONE", call.getArgument(8), old.vaultExpiresAt(), null));
            return null;
        }).when(repository).finishJob(anyString(), anyString(), anyBoolean(), any(), anyLong(), anyLong(), anyString(), any(), anyLong());
        when(repository.findJob(anyString())).thenAnswer(call -> Optional.ofNullable(jobs.get((String) call.getArgument(0))));
        when(repository.markOutput(anyString(), anyString(), anyString())).thenAnswer(call -> {
            JobRow old = jobs.get((String) call.getArgument(0));
            if (old == null || !"READY".equals(old.outputState())) return false;
            jobs.put(old.jobId(), new JobRow(old.jobId(), old.createdBy(), old.createdAt(), old.state(), old.fileType(),
                    old.inputBytes(), old.outputBytes(), old.countsJson(), old.failureReason(), old.finishedAt(),
                    call.getArgument(2), old.vaultEntries(), old.vaultExpiresAt(), null));
            return true;
        });
        doAnswer(call -> {
            String id = call.getArgument(0);
            reverts.put(id, new RevertRow(id, call.getArgument(1), Instant.now(), call.getArgument(2), call.getArgument(3),
                    "RUNNING", null, null, null, "{}", null, null, "PENDING"));
            return null;
        }).when(repository).createRevert(anyString(), anyString(), anyString(), any());
        doAnswer(call -> {
            String id = call.getArgument(0);
            RevertRow old = reverts.get(id);
            boolean ok = call.getArgument(2);
            reverts.put(id, new RevertRow(id, old.createdBy(), old.createdAt(), old.scope(), old.sourceJobId(),
                    ok ? "SUCCEEDED" : "FAILED", call.getArgument(3), call.getArgument(4), call.getArgument(5),
                    call.getArgument(6), call.getArgument(7), Instant.now(), ok ? "READY" : "NONE"));
            return null;
        }).when(repository).finishRevert(anyString(), anyString(), anyBoolean(), any(), anyLong(), anyLong(), anyString(), any(), anyLong());
        when(repository.findRevert(anyString())).thenAnswer(call -> Optional.ofNullable(reverts.get((String) call.getArgument(0))));
        when(repository.markRevertOutput(anyString(), anyString(), anyString())).thenAnswer(call -> {
            RevertRow old = reverts.get((String) call.getArgument(0));
            if (old == null || !"READY".equals(old.outputState())) return false;
            reverts.put(old.revertId(), new RevertRow(old.revertId(), old.createdBy(), old.createdAt(), old.scope(),
                    old.sourceJobId(), old.state(), old.fileType(), old.inputBytes(), old.outputBytes(), old.countsJson(),
                    old.failureReason(), old.finishedAt(), call.getArgument(2)));
            return true;
        });
        when(repository.lookup(anyCollection(), any(), any())).thenAnswer(call -> {
            Collection<byte[]> keys = call.getArgument(0);
            String createdBy = call.getArgument(1);
            String jobId = call.getArgument(2);
            lookups.add(new String[] {createdBy, jobId});
            List<Found> out = new ArrayList<>();
            for (Object[] row : vaultRows) {
                String rowJob = (String) row[0];
                Entry e = (Entry) row[1];
                if (createdBy != null && !jobs.get(rowJob).createdBy().equals(createdBy)) continue;
                if (jobId != null && !jobId.equals(rowJob)) continue;
                if (keys.stream().anyMatch(k -> Arrays.equals(k, e.lookupKey()))) {
                    out.add(new Found(rowJob, e.lookupKey(), e.sealedOriginal(), wrappedKeys.get(rowJob), "v1"));
                }
            }
            return out;
        });
    }

    private String sanitize(String actor) throws Exception {
        String body = mvc.perform(upload(BASE, actor, LOG.getBytes(StandardCharsets.UTF_8), Map.of()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("RUNNING"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"job_id\":\"([0-9a-f-]{36})\".*", "$1");
    }

    private String revert(String actor, byte[] content, Map<String, String> fields) throws Exception {
        String body = mvc.perform(upload(BASE + "/revert", actor, content, fields))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"revert_id\":\"([0-9a-f-]{36})\".*", "$1");
    }

    private static Cookie cookie(String actor) {
        return new Cookie("ui2_session", "cookie-" + actor);
    }

    private static MockHttpServletRequestBuilder upload(String path, String actor, byte[] content, Map<String, String> fields)
            throws Exception {
        var body = new ByteArrayOutputStream();
        for (var f : fields.entrySet()) {
            body.write(("--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                    + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.log\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write("\r\n--SyntheticBoundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return post(path).servletPath(path).cookie(cookie(actor)).header("X-CSRF-Token", "csrf-" + actor)
                .header("Origin", "https://example.invalid").contentType("multipart/form-data; boundary=SyntheticBoundary")
                .content(body.toByteArray());
    }

    /** Runs a job on the calling thread, so the test sees its end state. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
