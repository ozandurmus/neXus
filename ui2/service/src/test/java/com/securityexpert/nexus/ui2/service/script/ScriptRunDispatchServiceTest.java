package com.securityexpert.nexus.ui2.service.script;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
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

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * The service half of a run ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) item 4; review findings 2,
 * 10, 12): lease-checked, once-per-epoch release of the body, the version's declared credentials and a session; second
 * masking and encrypted storage of the output; session revocation on finish and abort.
 */
class ScriptRunDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String JOB = "job-0001-abcd";

    @TempDir
    Path temp;

    private static String secretValue() {
        return "pa" + "ss" + "Word-" + Integer.toHexString(424242) + "-z";
    }

    static final class Repo implements ScriptRepository {
        ScriptRecord script;
        ScriptVersionRecord version;
        ScriptRunRecord run;
        String jobState = "EXECUTING";
        long leaseEpoch = 5L;
        String leaseWorker = "script_runner-abc";
        Optional<Long> dispatchEpoch = Optional.empty();
        Optional<String> sessionId = Optional.empty();
        String runState = "REQUESTED";
        final List<String> completed = new ArrayList<>();
        Optional<String> storedRef = Optional.empty();
        byte[] storedKey;

        @Override
        public Optional<RunLease> findRunLease(String jobId) {
            return run == null ? Optional.empty()
                    : Optional.of(new RunLease(jobId, jobState, leaseEpoch, leaseWorker, runState, dispatchEpoch, sessionId));
        }

        @Override
        public boolean beginDispatch(String jobId, long epoch, String sid, List<String> refs, Instant at, String actor) {
            if (dispatchEpoch.isPresent() && dispatchEpoch.get() >= epoch) {
                return false;
            }
            dispatchEpoch = Optional.of(epoch);
            sessionId = Optional.of(sid);
            runState = "RUNNING";
            return true;
        }

        @Override
        public Optional<ScriptRunRecord> findRunByJobId(String jobId) {
            return Optional.ofNullable(run);
        }

        @Override
        public Optional<ScriptRecord> findById(String id) {
            return Optional.ofNullable(script);
        }

        @Override
        public Optional<ScriptVersionRecord> findVersion(String id, int v) {
            return Optional.ofNullable(version);
        }

        @Override
        public boolean completeRun(String jobId, String state, String outcome, Instant finishedAt, long durationMs,
                Integer exitCode, int stdoutBytes, int stderrBytes, boolean stdoutTruncated, boolean stderrTruncated,
                Optional<String> ref, Optional<String> keyId, byte[] wrapped, Optional<String> sha, Optional<Long> size,
                Optional<String> tail, List<String> targets, List<String> refs, Optional<String> failure, String actor) {
            completed.add(state + "/" + outcome + "/" + exitCode + "/" + failure.orElse("-") + "/" + tail.orElse(""));
            storedRef = ref;
            storedKey = wrapped;
            runState = state;
            return true;
        }

        // Unused by the dispatch service.
        @Override
        public List<ScriptRecord> findAll() {
            return List.of();
        }

        @Override
        public Optional<ScriptRecord> findByName(String name) {
            return Optional.empty();
        }

        @Override
        public List<ScriptVersionRecord> findVersions(String id) {
            return List.of();
        }

        @Override
        public ScriptRecord create(ScriptRecord s, ScriptVersionRecord v, String a, String b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScriptRecord appendVersion(String id, ScriptVersionRecord v, String a, String b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ScriptRecord> updateSettings(String id, boolean enabled, Optional<String> schedule, int timeout,
                List<String> egress, List<String> targets, List<String> refs, String notify, String runAs, String a,
                String b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ScriptRecord> setEnabled(String id, boolean enabled, String a, String b) {
            throw new UnsupportedOperationException();
        }
    }

    private ArtefactStore store() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (i * 3 + 1);
        }
        return new FileArtefactStore(temp.resolve("store"),
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)));
    }

    private Repo repo(ArtefactStore store, List<String> versionRefs, List<String> scriptRefs, String runAs) throws Exception {
        Repo repo = new Repo();
        ArtefactStore.ArtefactMetadata meta;
        try (ArtefactStore.ArtefactHandle h = store.open("script-1", "script-1@v2", ArtefactClass.SCRIPT_BODY, false)) {
            h.sink().write("echo body\n".getBytes(StandardCharsets.UTF_8));
            meta = h.finish();
        }
        repo.script = new ScriptRecord("script-1", "nightly", "sh", 2, true, Optional.empty(), 120,
                List.of("192.0.2.10:443"), List.of(), scriptRefs, "none", runAs, "admin", NOW, NOW);
        repo.version = new ScriptVersionRecord("script-1", 2, meta.plaintextSha256(), meta.plaintextBytes(),
                meta.ref().value(), meta.keyId(), meta.wrappedDataKey(), List.of(), versionRefs, List.of(), "admin", NOW);
        repo.run = new ScriptRunRecord(JOB, "script-1", 2, "REQUESTED", Optional.empty(), "MANUAL", Optional.empty(),
                runAs, "op", NOW, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 0, false,
                false, Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("192.0.2.10:443"), List.of(), List.of(), Optional.empty(), Optional.empty());
        return repo;
    }

    private static ScriptCredentialSource credentials(Map<String, ScriptCredentialSource.Resolved> values) {
        return new ScriptCredentialSource() {
            @Override
            public Optional<String> displayName(String refId) {
                return Optional.ofNullable(values.get(refId)).map(ScriptCredentialSource.Resolved::displayName);
            }

            @Override
            public Optional<Resolved> resolve(String refId) {
                return Optional.ofNullable(values.get(refId));
            }
        };
    }

    @Test
    void startReleasesBodyOnlyTheVersionRefsAndASessionOncePerEpoch() throws Exception {
        ArtefactStore store = store();
        Repo repo = repo(store, List.of("ref-a", "ref-b"), List.of("ref-a", "ref-b", "ref-undeclared"), RoleToken.OPERATOR);
        Map<String, ScriptCredentialSource.Resolved> values = new LinkedHashMap<>();
        values.put("ref-a", new ScriptCredentialSource.Resolved("ref-a", "Vault Reader", "u1", secretValue()));
        values.put("ref-b", new ScriptCredentialSource.Resolved("ref-b", "vault-reader", "u2", secretValue() + "2"));
        values.put("ref-undeclared", new ScriptCredentialSource.Resolved("ref-undeclared", "x", "u3", "never-sent"));
        SessionRepository sessions = mock(SessionRepository.class);
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(repo, credentials(values), sessions, store,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var started = assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(JOB, 5L));
        var bundle = started.bundle();
        assertArrayEquals("echo body\n".getBytes(StandardCharsets.UTF_8), bundle.body());
        assertEquals(List.of("ref-a", "ref-b"), bundle.credentials().stream().map(c -> c.refId()).toList(),
                "exactly the version's refs, never the script's extra ones (finding 10)");
        // Two display names that map to the same variable: disambiguated with a suffix and a warning, never refused.
        assertEquals(List.of("VAULT_READER", "VAULT_READER_2"), bundle.credentials().stream().map(c -> c.alias()).toList());
        assertEquals(1, bundle.warnings().size());
        assertFalse(bundle.toString().contains(secretValue()));
        verify(sessions).createActive(anyString(), eq("script_session:script-1:" + JOB + ":role:operator"), anyString(),
                any(), any(), any(), eq("script_session_create"));
        assertEquals("RUNNING", repo.runState);

        // The same epoch never gets the body twice.
        var again = assertInstanceOf(ScriptRunDispatchService.StartOutcome.Refused.class, dispatch.start(JOB, 5L));
        assertEquals("SCRIPT_RUN_ALREADY_DISPATCHED", again.code(), "dispatched at 5; only a later epoch may start again");
    }

    @Test
    void startRefusesWithoutAScriptRunnerLeaseOfThatEpoch() throws Exception {
        ArtefactStore store = store();
        Repo repo = repo(store, List.of(), List.of(), RoleToken.OPERATOR);
        SessionRepository sessions = mock(SessionRepository.class);
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(repo, credentials(Map.of()), sessions, store,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("SCRIPT_RUN_LEASE_MISMATCH",
                ((ScriptRunDispatchService.StartOutcome.Refused) dispatch.start(JOB, 4L)).code());
        repo.leaseWorker = "worker-general-1";
        assertEquals("SCRIPT_RUN_LEASE_MISMATCH",
                ((ScriptRunDispatchService.StartOutcome.Refused) dispatch.start(JOB, 5L)).code());
        repo.leaseWorker = "script_runner-abc";
        repo.jobState = "CLAIMED";
        assertEquals("SCRIPT_RUN_LEASE_MISMATCH",
                ((ScriptRunDispatchService.StartOutcome.Refused) dispatch.start(JOB, 5L)).code());
        verify(sessions, never()).createActive(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void startRefusesARunAsOutsideTheAllowlist() throws Exception {
        ArtefactStore store = store();
        Repo repo = repo(store, List.of(), List.of(), RoleToken.OPERATOR);
        repo.run = new ScriptRunRecord(JOB, "script-1", 2, "REQUESTED", Optional.empty(), "MANUAL", Optional.empty(),
                "some-user", "op", NOW, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 0,
                false, false, Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(), Optional.empty());
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(repo, credentials(Map.of()),
                mock(SessionRepository.class), store, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("SCRIPT_RUN_AS_NOT_ALLOWED",
                ((ScriptRunDispatchService.StartOutcome.Refused) dispatch.start(JOB, 5L)).code());
    }

    @Test
    void finishMasksAgainStoresEncryptedOutputClosesTheRunAndRevokesTheSession() throws Exception {
        ArtefactStore store = store();
        Repo repo = repo(store, List.of("ref-a"), List.of("ref-a"), RoleToken.OPERATOR);
        String secret = secretValue();
        ScriptCredentialSource creds = credentials(Map.of("ref-a",
                new ScriptCredentialSource.Resolved("ref-a", "A", "svc_user", secret)));
        SessionRepository sessions = mock(SessionRepository.class);
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(repo, creds, sessions, store,
                Clock.fixed(NOW, ZoneOffset.UTC));
        dispatch.start(JOB, 5L);
        String sid = repo.sessionId.orElseThrow();

        // A broker that failed to mask (defence in depth): the service masks again before storing.
        Optional<ScriptRunDispatchService.Refusal> refusal = dispatch.finish(JOB, 5L, new ScriptRunDispatchService.FinishReport(
                "SUCCESS", 0, 1200L, "leaked " + secret + " and hex "
                        + java.util.HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8)) + "\n",
                "svc_user\n", false, false, null));
        assertTrue(refusal.isEmpty());
        assertEquals(1, repo.completed.size());
        assertTrue(repo.completed.get(0).startsWith("COMPLETED/SUCCESS/0/-/"));
        assertFalse(repo.completed.get(0).contains(secret));
        try (InputStream in = store.retrieve(new ArtefactRef(repo.storedRef.orElseThrow()), repo.storedKey, false)) {
            String stored = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(stored.contains(secret));
            assertFalse(stored.contains("svc_user"));
            assertTrue(stored.startsWith("=== STDOUT ==="));
        }
        verify(sessions).revoke(eq(sid), anyString(), eq("script_session_revoke"));
        // A closed run accepts nothing more.
        assertEquals("SCRIPT_RUN_ALREADY_CLOSED", dispatch.finish(JOB, 5L, new ScriptRunDispatchService.FinishReport(
                "SUCCESS", 0, 1L, "", "", false, false, null)).orElseThrow().code());
    }

    @Test
    void abortRevokesTheSessionOfThatEpochOnly() throws Exception {
        ArtefactStore store = store();
        Repo repo = repo(store, List.of(), List.of(), RoleToken.VIEWER);
        SessionRepository sessions = mock(SessionRepository.class);
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(repo, credentials(Map.of()), sessions, store,
                Clock.fixed(NOW, ZoneOffset.UTC));
        dispatch.start(JOB, 5L);
        assertEquals("SCRIPT_RUN_LEASE_MISMATCH", dispatch.abort(JOB, 4L).orElseThrow().code());
        assertTrue(dispatch.abort(JOB, 5L).isEmpty());
        verify(sessions).revoke(eq(repo.sessionId.orElseThrow()), anyString(), eq("script_session_revoke"));
    }

    @Test
    void replayViewerRunMintsAMachineSessionSoWritesAreRefused() throws Exception {
        ArtefactStore store = store();
        Repo repo = repo(store, List.of(), List.of(), RoleToken.REPLAY_VIEWER);
        SessionRepository sessions = mock(SessionRepository.class);
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(repo, credentials(Map.of()), sessions, store,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(JOB, 5L));
        verify(sessions).createMachineActive(anyString(), eq("script_session:script-1:" + JOB + ":role:replay_viewer"),
                anyString(), any(), any(), any());
        verify(sessions, never()).createActive(any(), any(), any(), any(), any(), any(), any());
    }
}
