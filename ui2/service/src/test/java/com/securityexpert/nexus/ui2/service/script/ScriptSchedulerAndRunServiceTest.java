package com.securityexpert.nexus.ui2.service.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChain;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;

/**
 * Unit tests for {@link ScriptScheduler}, {@link ScriptExecutionService#runNow}, {@link ScriptExecutionController}
 * run/output routes, per-run {@code nexus-cli} session RBAC/revocation, and {@link JobLogQueryService} visibility
 * ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.3, 3.4, 3.6, 5).
 */
class ScriptSchedulerAndRunServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void schedulerClaimsDueSlotOnceEnforcesOneHourCatchUpAndIgnoresDisabledScripts() throws Exception {
        Instant now = Instant.parse("2026-11-18T02:15:00Z"); // 15 minutes after 02:00 UTC slot
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryRepo repo = new InMemoryRepo();
        ArtefactStore store = newStore(tempDir);
        ScriptExecutionService service = new ScriptExecutionService(
                repo, mock(CredentialReferenceRepository.class), null, store, fixedClock,
                ScriptExecutionService.MAX_SCRIPT_BYTES);
        ScriptScheduler scheduler = new ScriptScheduler(repo, service, fixedClock, ZoneOffset.UTC);

        // 1. Disabled script with due schedule -> fires nothing
        repo.putScript(new ScriptRecord(
                "script-disabled", "disabled-nightly", "sh", 1, false, Optional.of("0 2 * * *"),
                300, List.of(), List.of(), List.of(), "none", RoleToken.OPERATOR, "admin", now, now));

        // 2. Enabled script due at 02:00 UTC (15 minutes ago, within 1h catch-up) -> fires once
        repo.putScript(new ScriptRecord(
                "script-due", "enabled-nightly", "sh", 1, true, Optional.of("0 2 * * *"),
                300, List.of("192.0.2.10:443"), List.of("dev-1"), List.of("cred-1"),
                "none", RoleToken.OPERATOR, "admin", now, now));

        // 3. Enabled script whose due slot was 00:00 UTC (2h15m ago, outside 1h catch-up) -> does not fire
        repo.putScript(new ScriptRecord(
                "script-stale", "missed-midnight", "sh", 1, true, Optional.of("0 0 * * *"),
                300, List.of(), List.of(), List.of(), "none", RoleToken.OPERATOR, "admin", now, now));

        assertEquals(1, scheduler.evaluateAt(now), "Exactly the due enabled script within 1h catch-up should fire");
        assertEquals(0, repo.findRunsByScriptId("script-disabled", 10).size());
        assertEquals(0, repo.findRunsByScriptId("script-stale", 10).size());

        List<ScriptRunRecord> dueRuns = repo.findRunsByScriptId("script-due", 10);
        assertEquals(1, dueRuns.size());
        ScriptRunRecord fired = dueRuns.get(0);
        assertEquals("SCHEDULED", fired.triggerKind());
        assertEquals(Optional.of(Instant.parse("2026-11-18T02:00:00Z")), fired.scheduledSlot());
        assertEquals(List.of("dev-1"), fired.egressDeviceTargets());
        assertEquals(List.of("cred-1"), fired.credentialRefsUsed());

        // Second tick in the same slot is fenced by last_scheduled_slot -> fires 0
        assertEquals(0, scheduler.evaluateAt(now.plusSeconds(60)), "Same slot must not fire a second time");
        assertEquals(1, repo.findRunsByScriptId("script-due", 10).size());
    }

    @Test
    void runNowControllerAndOutputDownloadWorkAndRefuseReplayViewer() throws Exception {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryRepo repo = new InMemoryRepo();
        ArtefactStore store = newStore(tempDir);
        ScriptExecutionService service = new ScriptExecutionService(
                repo, mock(CredentialReferenceRepository.class), null, store, fixedClock,
                ScriptExecutionService.MAX_SCRIPT_BYTES);
        ScriptExecutionController controller = new ScriptExecutionController(
                service, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        repo.putScript(new ScriptRecord(
                "script-1", "manual-check", "py", 2, false, Optional.empty(),
                120, List.of("192.0.2.25:22"), List.of("dev-2"), List.of("cred-a"),
                "none", RoleToken.OPERATOR, "admin", now, now));

        // Replay viewer is refused on POST /api/v2/scripts/{id}/run
        MockHttpServletRequest aiviewReq = new MockHttpServletRequest("POST", "/api/v2/scripts/script-1/run");
        aiviewReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-aiview");
        aiviewReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.TRUE);
        assertEquals(403, controller.runNow("script-1", aiviewReq).getStatusCode().value());

        // Operator submits manual run
        MockHttpServletRequest opReq = new MockHttpServletRequest("POST", "/api/v2/scripts/script-1/run");
        opReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-operator");
        var runRes = controller.runNow("script-1", opReq);
        assertEquals(202, runRes.getStatusCode().value());
        String jobId = (String) runRes.getBody().get("job_id");
        assertEquals(2, runRes.getBody().get("version"));
        assertEquals("MANUAL", runRes.getBody().get("trigger_kind"));

        // Store encrypted script_output artefact and complete the run
        byte[] outputBytes = "=== STDOUT ===\ncheck ok\n=== STDERR ===\n".getBytes(StandardCharsets.UTF_8);
        ArtefactStore.ArtefactMetadata outMeta;
        try (ArtefactStore.ArtefactHandle h = store.open("script-1", jobId, ArtefactClass.SCRIPT_OUTPUT, false)) {
            h.sink().write(outputBytes);
            outMeta = h.finish();
        }
        repo.completeRun(
                jobId, "COMPLETED", "SUCCESS", now.plusSeconds(3), 3000L, 0,
                8, 0, false, false,
                Optional.of(outMeta.ref().value()), Optional.of(outMeta.keyId()), outMeta.wrappedDataKey(),
                Optional.of(outMeta.plaintextSha256()), Optional.of(outMeta.plaintextBytes()),
                Optional.of("check ok"), List.of("dev-2"), List.of("cred-a"),
                Optional.empty(), "system:script_runner");

        var listRes = controller.listRuns("script-1", opReq);
        assertEquals(200, listRes.getStatusCode().value());
        List<?> runs = (List<?>) listRes.getBody().get("runs");
        assertEquals(1, runs.size());

        // NEW-P2-5: when affordances is null on the controller, getRun fails closed (output_tail is withheld)
        var unconfiguredGet = controller.getRun(jobId, opReq);
        assertEquals(200, unconfiguredGet.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertNull(unconfiguredGet.getBody().get("output_tail"));

        // Replay viewer is refused on downloadRunOutput (403 FORBIDDEN_FOR_VIEWER)
        assertEquals(403, controller.downloadRunOutput(jobId, aiviewReq).getStatusCode().value());

        var outRes = controller.downloadRunOutput(jobId, opReq);
        assertEquals(200, outRes.getStatusCode().value());
        assertEquals("=== STDOUT ===\ncheck ok\n=== STDERR ===\n", outRes.getBody());
        // Review finding 11: every download writes its audit row before the first byte.
        assertEquals(1, repo.downloadAudits.size());
        assertTrue(repo.downloadAudits.get(0).startsWith(jobId + "|"));
    }

    @Test
    void scheduleIsReadInEuropeIstanbulByDefault() {
        // PO works in Istanbul (UTC+3): "0 2 * * *" fires at 02:00 local, 23:00Z the day before.
        assertEquals(java.time.ZoneId.of("Europe/Istanbul"), ScriptSchedule.DEFAULT_ZONE);
        assertEquals(Instant.parse("2026-11-18T23:00:00Z"),
                ScriptSchedule.nextFiveFires("0 2 * * *", Instant.parse("2026-11-18T12:00:00Z"),
                        ScriptSchedule.DEFAULT_ZONE).get(0));
    }

    @Test
    void perRunScriptSessionTokenIsPermittedForRunAsRoleRefusedForSecurityAdminAndReturns401OnceRevoked()
            throws Exception {
        Instant now = Instant.now();
        String rawCookie = "per-run-cookie-secret";
        String csrf = "per-run-csrf";
        String sessionId = SessionHasher.hash(rawCookie);
        String sessionActor = ScriptExecutionJob.formatSessionActor("script-1", "job-1", RoleToken.OPERATOR);

        Map<String, SessionRecord> store = new LinkedHashMap<>();
        store.put(sessionId, new SessionRecord(
                sessionId, sessionActor, csrf, SessionState.ACTIVE,
                now, now, now.plusSeconds(300), now.plusSeconds(300),
                Optional.empty(), Optional.empty(), Optional.empty(), false));

        SessionRepository sessions = mock(SessionRepository.class);
        org.mockito.Mockito.when(sessions.findBySessionId(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        org.mockito.Mockito.doAnswer(inv -> {
            String id = inv.getArgument(0);
            SessionRecord cur = store.get(id);
            if (cur != null) {
                store.put(id, new SessionRecord(
                        cur.sessionId(), cur.actorFingerprint(), cur.csrfSecret(), SessionState.REVOKED,
                        cur.createdAt(), cur.lastSeenAt(), cur.idleDeadlineAt(), cur.absoluteExpiresAt(),
                        Optional.empty(), Optional.of(inv.getArgument(1)), Optional.empty(), cur.machine()));
            }
            return null;
        }).when(sessions).revoke(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());

        RbacEvaluator rbac = new RbacEvaluator(
                mock(RoleBindingRepository.class),
                mock(ActorAuthzStateRepository.class),
                null);
        GateChain gateChain = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var routeGate = com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig.routeGate(
                gateChain, null, null);

        // 1. Active per-run operator session is permitted on operator action (POST /api/v2/scripts/script-1/run)
        // and open read (GET /devices)
        assertTrue(routeGate.decide(
                authedRequest("POST", "/api/v2/scripts/script-1/run", rawCookie, csrf),
                false).permitted());
        assertTrue(routeGate.decide(
                authedRequest("GET", "/devices", rawCookie, csrf),
                false).permitted());

        // 2. Active per-run operator session is refused (403) on security_admin action (POST /api/v2/scripts)
        var writeVerdict = routeGate.decide(
                authedRequest("POST", "/api/v2/scripts", rawCookie, csrf),
                false);
        assertFalse(writeVerdict.permitted());
        assertEquals(403, writeVerdict.status());

        // 3. Revoke session at run end -> subsequent use is 401 (contract section 3.3 & 5)
        sessions.revoke(sessionId, "system:script_runner", "script_session_revoke");
        var revokedVerdict = routeGate.decide(
                authedRequest("GET", "/devices", rawCookie, csrf),
                false);
        assertFalse(revokedVerdict.permitted());
        assertEquals(401, revokedVerdict.status());
    }

    @Test
    void replayViewerScriptSessionWithProductionWiredResolversInheritsAiviewMaskingAndMachineWriteBlocks() {
        Instant now = Instant.now();
        String rawCookie = "per-run-aiview-cookie";
        String csrf = "per-run-aiview-csrf";
        String sessionId = SessionHasher.hash(rawCookie);
        String sessionActor = ScriptExecutionJob.formatSessionActor("script-aiview", "job-aiview-1", RoleToken.REPLAY_VIEWER);

        // Mint as ScriptRunDispatchService.start does for run_as = role:replay_viewer (machine = true).
        assertTrue(ScriptExecutionJob.isMachineRunAs(RoleToken.REPLAY_VIEWER));
        Map<String, SessionRecord> store = new LinkedHashMap<>();
        store.put(sessionId, new SessionRecord(
                sessionId, sessionActor, csrf, SessionState.ACTIVE,
                now, now, now.plusSeconds(300), now.plusSeconds(300),
                Optional.empty(), Optional.empty(), Optional.empty(), true));

        SessionRepository sessions = mock(SessionRepository.class);
        org.mockito.Mockito.when(sessions.findBySessionId(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));

        RbacEvaluator rbac = new RbacEvaluator(
                mock(RoleBindingRepository.class),
                mock(ActorAuthzStateRepository.class),
                null);
        GateChain gateChain = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));

        // Production wiring (RbacConfiguration): non-null LocalIdentityResolver and LocalRoleTokenResolver.
        var localCreds = mock(com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository.class);
        org.mockito.Mockito.when(localCreds.findAll()).thenReturn(List.of());
        var localIdentityResolver = new com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver(localCreds);
        var localRoleTokenResolver = mock(com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver.class);
        var routeGate = com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig.routeGate(
                gateChain, localIdentityResolver, localRoleTokenResolver);

        // 1. Read route: permitted and carries IS_REPLAY_VIEWER_ATTRIBUTE = true so PrivacyMaskingResponseBodyAdvice masks.
        var getDevices = routeGate.decide(authedRequest("GET", "/devices", rawCookie, csrf), false);
        assertTrue(getDevices.permitted());
        assertEquals(Boolean.TRUE, getDevices.attributes().get(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));

        // 2. Read-only POST allowed for aiview (POST /api/v2/reports/preview): permitted and masked.
        var reportPreview = routeGate.decide(authedRequest("POST", "/api/v2/reports/preview", rawCookie, csrf), false);
        assertTrue(reportPreview.permitted());
        assertEquals(Boolean.TRUE, reportPreview.attributes().get(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));

        // 3. Machine-session write block: DELETE and PUT under PACKET_ANALYSIS_RUN are refused with 403 MACHINE_SESSION_READ_ONLY.
        var deleteCase = routeGate.decide(
                authedRequest("DELETE", "/api/v2/packet-analysis/cases/case-1", rawCookie, csrf), false);
        assertFalse(deleteCase.permitted());
        assertEquals(403, deleteCase.status());
        assertEquals("MACHINE_SESSION_READ_ONLY", ((Map<?, ?>) deleteCase.body()).get("error"));

        var putTopology = routeGate.decide(
                authedRequest("PUT", "/api/v2/packet-analysis/cases/case-1/topology", rawCookie, csrf), false);
        assertFalse(putTopology.permitted());
        assertEquals(403, putTopology.status());
        assertEquals("MACHINE_SESSION_READ_ONLY", ((Map<?, ?>) putTopology.body()).get("error"));
    }

    @Test
    void outputTailIsWithheldOnListRunsAndForNonOperatorRolesAndAuditedOnGetRun() throws Exception {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryRepo repo = new InMemoryRepo();
        ArtefactStore store = newStore(tempDir);
        ScriptExecutionService service = new ScriptExecutionService(
                repo, mock(CredentialReferenceRepository.class), null, store, fixedClock,
                ScriptExecutionService.MAX_SCRIPT_BYTES);

        RbacEvaluator rbac = new RbacEvaluator(
                mock(RoleBindingRepository.class),
                mock(ActorAuthzStateRepository.class),
                null);
        ScriptExecutionController controller = new ScriptExecutionController(
                service, Optional.empty(), Optional.empty(),
                Optional.of(new ActionRegistry()), Optional.of(rbac), Optional.of(mock(AuthzDecisionRepository.class)));

        repo.putScript(new ScriptRecord(
                "script-tail", "tail-audit-check", "sh", 1, true, Optional.empty(),
                120, List.of(), List.of(), List.of(), "none", RoleToken.OPERATOR, "admin", now, now));
        repo.admitRun("job-tail-1", "script-tail", 1, "MANUAL", Optional.empty(), RoleToken.OPERATOR,
                List.of(), List.of(), List.of(), now, "actor-op", ActionRegistry.SCRIPT_RUN);
        repo.completeRun("job-tail-1", "COMPLETED", "SUCCESS", now.plusSeconds(2), 2000L, 0,
                18, 0, false, false, Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(),
                Optional.of("sensitive tail line"), List.of(), List.of(), Optional.empty(), "system:script_runner");

        String opActor = ScriptExecutionJob.formatSessionActor("script-tail", "run-op", RoleToken.OPERATOR);
        String viewerActor = ScriptExecutionJob.formatSessionActor("script-tail", "run-view", RoleToken.VIEWER);
        String aiviewActor = ScriptExecutionJob.formatSessionActor("script-tail", "run-aiview", RoleToken.REPLAY_VIEWER);

        MockHttpServletRequest opReq = new MockHttpServletRequest("GET", "/api/v2/scripts/script-tail/runs");
        opReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, opActor);
        opReq.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "sess-op");

        // 1. listRuns never returns output_tail, even to role:operator, and writes no download audit row.
        var listRes = controller.listRuns("script-tail", opReq);
        assertEquals(200, listRes.getStatusCode().value());
        Map<?, ?> listedRun = (Map<?, ?>) ((List<?>) listRes.getBody().get("runs")).get(0);
        org.junit.jupiter.api.Assertions.assertNull(listedRun.get("output_tail"));
        assertTrue(repo.downloadAudits.isEmpty());

        // 2. getRun as role:viewer withholds output_tail (null) and writes no audit row.
        MockHttpServletRequest viewerReq = new MockHttpServletRequest("GET", "/api/v2/scripts/runs/job-tail-1");
        viewerReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, viewerActor);
        viewerReq.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "sess-view");
        var viewerRes = controller.getRun("job-tail-1", viewerReq);
        assertEquals(200, viewerRes.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertNull(viewerRes.getBody().get("output_tail"));
        assertTrue(repo.downloadAudits.isEmpty());

        // 3. getRun as role:replay_viewer withholds output_tail (null) and writes no audit row.
        MockHttpServletRequest aiviewReq = new MockHttpServletRequest("GET", "/api/v2/scripts/runs/job-tail-1");
        aiviewReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, aiviewActor);
        aiviewReq.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "sess-aiview");
        aiviewReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.TRUE);
        var aiviewRes = controller.getRun("job-tail-1", aiviewReq);
        assertEquals(200, aiviewRes.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertNull(aiviewRes.getBody().get("output_tail"));
        assertTrue(repo.downloadAudits.isEmpty());

        // 4. getRun as role:operator returns output_tail and writes an audit_script_output_download row.
        var opGetRes = controller.getRun("job-tail-1", opReq);
        assertEquals(200, opGetRes.getStatusCode().value());
        assertEquals("sensitive tail line", opGetRes.getBody().get("output_tail"));
        assertEquals(List.of("job-tail-1|" + opActor), repo.downloadAudits);
    }

    @Test
    void retentionPrunerDeletes30DayExpiredScriptOutputsAndClearsRunColumnsWhileKeepingRecentOutputs() throws Exception {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryRepo repo = new InMemoryRepo();
        ArtefactStore store = newStore(tempDir);
        ScriptExecutionService service = new ScriptExecutionService(
                repo, mock(CredentialReferenceRepository.class), null, store, fixedClock,
                ScriptExecutionService.MAX_SCRIPT_BYTES);
        ScriptScheduler scheduler = new ScriptScheduler(repo, service, fixedClock, ZoneOffset.UTC);

        repo.putScript(new ScriptRecord(
                "script-ret", "retention-test", "sh", 1, true, Optional.empty(),
                120, List.of(), List.of(), List.of(), "none", RoleToken.OPERATOR, "admin", now.minus(Duration.ofDays(40)), now));

        // 1. Expired run (finished 31 days ago)
        Instant oldTime = now.minus(Duration.ofDays(31));
        repo.admitRun("job-old", "script-ret", 1, "MANUAL", Optional.empty(), RoleToken.OPERATOR,
                List.of(), List.of(), List.of(), oldTime, "actor-op", ActionRegistry.SCRIPT_RUN);
        ArtefactStore.ArtefactMetadata oldMeta;
        try (ArtefactStore.ArtefactHandle h = store.open("script-ret", "job-old", ArtefactClass.SCRIPT_OUTPUT, false)) {
            h.sink().write("old output\n".getBytes(StandardCharsets.UTF_8));
            oldMeta = h.finish();
        }
        repo.completeRun("job-old", "COMPLETED", "SUCCESS", oldTime.plusSeconds(5), 5000L, 0,
                11, 0, false, false, Optional.of(oldMeta.ref().value()), Optional.of(oldMeta.keyId()),
                oldMeta.wrappedDataKey(), Optional.of(oldMeta.plaintextSha256()), Optional.of(oldMeta.plaintextBytes()),
                Optional.of("old output"), List.of(), List.of(), Optional.empty(), "system:script_runner");

        // 2. Recent run (finished 10 days ago)
        Instant recentTime = now.minus(Duration.ofDays(10));
        repo.admitRun("job-recent", "script-ret", 1, "MANUAL", Optional.empty(), RoleToken.OPERATOR,
                List.of(), List.of(), List.of(), recentTime, "actor-op", ActionRegistry.SCRIPT_RUN);
        ArtefactStore.ArtefactMetadata recentMeta;
        try (ArtefactStore.ArtefactHandle h = store.open("script-ret", "job-recent", ArtefactClass.SCRIPT_OUTPUT, false)) {
            h.sink().write("recent output\n".getBytes(StandardCharsets.UTF_8));
            recentMeta = h.finish();
        }
        repo.completeRun("job-recent", "COMPLETED", "SUCCESS", recentTime.plusSeconds(5), 5000L, 0,
                14, 0, false, false, Optional.of(recentMeta.ref().value()), Optional.of(recentMeta.keyId()),
                recentMeta.wrappedDataKey(), Optional.of(recentMeta.plaintextSha256()), Optional.of(recentMeta.plaintextBytes()),
                Optional.of("recent output"), List.of(), List.of(), Optional.empty(), "system:script_runner");

        assertEquals(1, scheduler.pruneExpiredOutputs());
        ScriptRunRecord prunedOld = repo.findRunByJobId("job-old").orElseThrow();
        assertTrue(prunedOld.outputArtefactRef().isEmpty());
        assertTrue(prunedOld.outputKeyId().isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(prunedOld.outputWrappedDataKey());
        assertTrue(prunedOld.outputTail().isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.retrieve(oldMeta.ref(), oldMeta.wrappedDataKey(), false).close());

        ScriptRunRecord keptRecent = repo.findRunByJobId("job-recent").orElseThrow();
        assertEquals(Optional.of(recentMeta.ref().value()), keptRecent.outputArtefactRef());
        assertEquals(Optional.of("recent output"), keptRecent.outputTail());

        // Also verify runnerAvailable requires both runnerTokenConfigured and repository heartbeat (P0-2).
        repo.heartbeatAvailable = true;
        service.useRunnerTokenConfigured(() -> false);
        assertFalse(service.runnerAvailable());
        service.useRunnerTokenConfigured(() -> true);
        assertTrue(service.runnerAvailable());
    }

    private static MockHttpServletRequest authedRequest(String method, String path, String cookie, String csrf) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        req.setCookies(new jakarta.servlet.http.Cookie("ui2_session", cookie));
        req.addHeader("X-CSRF-Token", csrf);
        req.addHeader("Origin", "http://ui2-service:8080");
        return req;
    }

    @Test
    void jobLogQueryServiceResolvesScriptRunTargetKindAndScriptName() {
        var ctx = org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES);
        var fJobId = org.jooq.impl.DSL.field("job_id", String.class);
        var fType = org.jooq.impl.DSL.field("job_type", String.class);
        var fDevice = org.jooq.impl.DSL.field("target_device_id", String.class);
        var fName = org.jooq.impl.DSL.field("device_name", String.class);
        var fScriptName = org.jooq.impl.DSL.field("script_name", String.class);
        var fTargetKind = org.jooq.impl.DSL.field("job_target_kind", String.class);
        var fTargetRef = org.jooq.impl.DSL.field("job_target_ref", String.class);
        var fState = org.jooq.impl.DSL.field("state", String.class);
        var fOutcome = org.jooq.impl.DSL.field("outcome", String.class);
        var fReason = org.jooq.impl.DSL.field("terminal_reason", String.class);
        var fSubmitted = org.jooq.impl.DSL.field("submitted_at", Timestamp.class);
        var fFinished = org.jooq.impl.DSL.field("finished_at", Timestamp.class);
        var fTranscript = org.jooq.impl.DSL.field("has_transcript", Boolean.class);
        var fDuration = org.jooq.impl.DSL.field("duration_ms", Double.class);

        var row = ctx.newRecord(fJobId, fType, fDevice, fName, fScriptName, fTargetKind, fTargetRef,
                fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        row.set(fJobId, "job-script-1");
        row.set(fType, "script_run");
        row.set(fScriptName, "weekly-asa-dr-sync");
        row.set(fTargetKind, "script");
        row.set(fTargetRef, "script-1@3");
        row.set(fState, "COMPLETED");
        row.set(fOutcome, "SUCCESS");
        row.set(fSubmitted, Timestamp.from(Instant.parse("2026-11-18T10:00:00Z")));
        row.set(fTranscript, false);

        JobLogQueryService.JobEvent event = JobLogQueryService.extractEventForTest(row);
        assertEquals("script_run", event.jobType());
        assertEquals("weekly-asa-dr-sync", event.deviceName());
        assertEquals("script", event.targetKind());
    }

    private static ArtefactStore newStore(Path root) {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 7);
        return new FileArtefactStore(
                root.resolve("store"),
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)));
    }

    private static final class InMemoryRepo implements ScriptRepository {
        final Map<String, ScriptRecord> scripts = new LinkedHashMap<>();
        final Map<String, List<ScriptVersionRecord>> versions = new LinkedHashMap<>();
        final Map<String, Instant> lastScheduledSlots = new LinkedHashMap<>();
        final Map<String, ScriptRunRecord> runs = new LinkedHashMap<>();
        final List<String> downloadAudits = new java.util.ArrayList<>();
        boolean heartbeatAvailable;

        @Override
        public boolean runnerAvailable() {
            return heartbeatAvailable;
        }

        @Override
        public List<ExpiredRunOutput> findRunsWithOutputOlderThan(Instant cutoff, int limit) {
            return runs.values().stream()
                    .filter(r -> r.finishedAt().orElse(r.requestedAt()).isBefore(cutoff))
                    .filter(r -> r.outputArtefactRef().isPresent() || r.outputTail().isPresent()
                            || r.outputWrappedDataKey() != null)
                    .limit(limit)
                    .map(r -> new ExpiredRunOutput(r.jobId(), r.outputArtefactRef()))
                    .toList();
        }

        @Override
        public boolean clearExpiredRunOutput(String jobId, String actorFingerprint) {
            ScriptRunRecord existing = runs.get(jobId);
            if (existing == null) {
                return false;
            }
            runs.put(jobId, new ScriptRunRecord(
                    existing.jobId(), existing.scriptId(), existing.version(), existing.state(), existing.outcome(),
                    existing.triggerKind(), existing.scheduledSlot(), existing.runAs(), existing.requestedBy(),
                    existing.requestedAt(), existing.startedAt(), existing.finishedAt(), existing.durationMs(),
                    existing.exitCode(), existing.stdoutBytes(), existing.stderrBytes(), existing.stdoutTruncated(),
                    existing.stderrTruncated(), Optional.empty(), Optional.empty(), null, existing.outputSha256(),
                    existing.outputSizeBytes(), Optional.empty(), existing.egressDeclared(),
                    existing.egressDeviceTargets(), existing.credentialRefsUsed(), existing.sessionId(),
                    existing.failureReason()));
            return true;
        }

        @Override
        public void auditOutputDownload(String jobId, String actorFingerprint) {
            downloadAudits.add(jobId + "|" + actorFingerprint);
        }

        void putScript(ScriptRecord s) {
            scripts.put(s.scriptId(), s);
            versions.put(s.scriptId(), List.of(new ScriptVersionRecord(
                    s.scriptId(), s.currentVersion(), "a".repeat(64), 16L,
                    "script_body/" + s.scriptId() + "/" + "a".repeat(64) + ".enc",
                    "key-1", new byte[32], List.of(), s.credentialRefs(), s.egressDeviceTargets(),
                    s.createdByActorFingerprint(), s.createdAt())));
        }

        @Override
        public List<ScriptRecord> findAll() {
            return List.copyOf(scripts.values());
        }

        @Override
        public Optional<ScriptRecord> findById(String scriptId) {
            return Optional.ofNullable(scripts.get(scriptId));
        }

        @Override
        public Optional<ScriptRecord> findByName(String name) {
            return scripts.values().stream().filter(s -> s.name().equals(name)).findFirst();
        }

        @Override
        public List<ScriptVersionRecord> findVersions(String scriptId) {
            return versions.getOrDefault(scriptId, List.of());
        }

        @Override
        public Optional<ScriptVersionRecord> findVersion(String scriptId, int version) {
            return versions.getOrDefault(scriptId, List.of()).stream()
                    .filter(v -> v.version() == version)
                    .findFirst();
        }

        @Override
        public ScriptRecord create(ScriptRecord script, ScriptVersionRecord initialVersion, String actor, String action) {
            scripts.put(script.scriptId(), script);
            versions.put(script.scriptId(), new ArrayList<>(List.of(initialVersion)));
            return script;
        }

        @Override
        public ScriptRecord appendVersion(String scriptId, ScriptVersionRecord version, String actor, String action) {
            return scripts.get(scriptId);
        }

        @Override
        public Optional<ScriptRecord> updateSettings(String scriptId, boolean enabled, Optional<String> schedule,
                int timeoutSeconds, List<String> egress, List<String> egressDeviceTargets, List<String> credentialRefs,
                String notifyMode, String runAs, String actor, String action) {
            return Optional.ofNullable(scripts.get(scriptId));
        }

        @Override
        public Optional<ScriptRecord> setEnabled(String scriptId, boolean enabled, String actor, String action) {
            return Optional.ofNullable(scripts.get(scriptId));
        }

        @Override
        public Optional<Instant> findLastScheduledSlot(String scriptId) {
            return Optional.ofNullable(lastScheduledSlots.get(scriptId));
        }

        @Override
        public Optional<ScriptRunRecord> admitRun(String jobId, String scriptId, int version, String triggerKind,
                Optional<Instant> scheduledSlot, String runAs, List<String> egressDeclared,
                List<String> egressDeviceTargets, List<String> credentialRefs, Instant requestedAt,
                String requestedBy, String actionId) {
            ScriptRecord s = scripts.get(scriptId);
            if (s == null) return Optional.empty();
            if (scheduledSlot.isPresent()) {
                if (!s.enabled()) return Optional.empty();
                Instant prev = lastScheduledSlots.get(scriptId);
                if (prev != null && !prev.isBefore(scheduledSlot.get())) {
                    return Optional.empty();
                }
                lastScheduledSlots.put(scriptId, scheduledSlot.get());
            }
            ScriptRunRecord run = new ScriptRunRecord(
                    jobId, scriptId, version, "REQUESTED", Optional.empty(), triggerKind, scheduledSlot,
                    runAs, requestedBy, requestedAt, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), 0, 0, false, false, Optional.empty(), Optional.empty(), null,
                    Optional.empty(), Optional.empty(), Optional.empty(), egressDeclared, egressDeviceTargets,
                    credentialRefs, Optional.empty(), Optional.empty());
            runs.put(jobId, run);
            return Optional.of(run);
        }

        @Override
        public Optional<ScriptRunRecord> findRunByJobId(String jobId) {
            return Optional.ofNullable(runs.get(jobId));
        }

        @Override
        public List<ScriptRunRecord> findRunsByScriptId(String scriptId, int limit) {
            return runs.values().stream()
                    .filter(r -> r.scriptId().equals(scriptId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean completeRun(String jobId, String state, String outcome, Instant finishedAt, long durationMs,
                Integer exitCode, int stdoutBytes, int stderrBytes, boolean stdoutTruncated, boolean stderrTruncated,
                Optional<String> outputArtefactRef, Optional<String> outputKeyId, byte[] outputWrappedDataKey,
                Optional<String> outputSha256, Optional<Long> outputSizeBytes, Optional<String> outputTail,
                List<String> egressDeviceTargets, List<String> credentialRefsUsed, Optional<String> failureReason,
                String actorFingerprint) {
            ScriptRunRecord existing = runs.get(jobId);
            if (existing == null) return false;
            runs.put(jobId, new ScriptRunRecord(
                    existing.jobId(), existing.scriptId(), existing.version(), state, Optional.ofNullable(outcome),
                    existing.triggerKind(), existing.scheduledSlot(), existing.runAs(), existing.requestedBy(),
                    existing.requestedAt(), existing.startedAt(), Optional.of(finishedAt), Optional.of(durationMs),
                    Optional.ofNullable(exitCode), stdoutBytes, stderrBytes, stdoutTruncated, stderrTruncated,
                    outputArtefactRef, outputKeyId, outputWrappedDataKey, outputSha256, outputSizeBytes, outputTail,
                    existing.egressDeclared(), egressDeviceTargets, credentialRefsUsed, existing.sessionId(),
                    failureReason));
            return true;
        }
    }
}
