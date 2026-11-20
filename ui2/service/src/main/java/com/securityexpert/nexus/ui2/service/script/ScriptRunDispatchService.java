package com.securityexpert.nexus.ui2.service.script;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.platform.ScriptCredentialAliases;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;
import com.securityexpert.nexus.ui2.platform.ScriptOutputMasking;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;

/**
 * The service half of a script run ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) items 4, 6, 7):
 * the only place the service hands a script body, a declared credential or a per-run session to anyone, and only to
 * the broker that holds the run's job lease.
 *
 * <ul>
 *   <li>{@link #start}: for a job that is {@code EXECUTING} under a {@code script_runner} lease of exactly the given
 *       epoch, and a run that was not yet dispatched for that epoch: decrypts the version's body, resolves exactly the
 *       version's declared credential references, mints the per-run session for the run's allowlisted role, and marks
 *       the run {@code RUNNING}. Once per epoch.</li>
 *   <li>{@link #finish}: masks the output again (second layer), stores it encrypted as {@code script_output}, closes
 *       the run and revokes the session.</li>
 *   <li>{@link #abort}: revokes the session of a run whose lease the broker lost; the run stays open for the next
 *       epoch.</li>
 * </ul>
 */
public final class ScriptRunDispatchService {

    private static final Logger LOG = Logger.getLogger(ScriptRunDispatchService.class.getName());

    public static final String BROKER_ACTOR = "system:script_runner";
    static final String WORKER_PREFIX = ScriptExecutionJob.MODULE + "-";
    /** Session lifetime beyond the run's timeout: pod start, log collection and the finish call. */
    static final Duration SESSION_GRACE = Duration.ofSeconds(300);

    /** What the broker needs to run one script; never logged. */
    public record RunBundle(
            String jobId,
            String scriptId,
            int version,
            String kind,
            int timeoutSeconds,
            List<String> egress,
            byte[] body,
            List<BundleCredential> credentials,
            String sessionCookie,
            String sessionCsrf,
            List<String> warnings) {
        public RunBundle {
            body = body.clone();
            egress = List.copyOf(egress);
            credentials = List.copyOf(credentials);
            warnings = List.copyOf(warnings);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "RunBundle[jobId=" + jobId + ", scriptId=" + scriptId + ", version=" + version + ", body=[REDACTED]]";
        }
    }

    public record BundleCredential(String refId, String alias, String username, String secret) {
        @Override
        public String toString() {
            return "BundleCredential[refId=" + refId + ", alias=" + alias + ", secret=[REDACTED]]";
        }
    }

    public sealed interface StartOutcome {
        record Started(RunBundle bundle) implements StartOutcome {
        }

        record Refused(int status, String code) implements StartOutcome {
        }
    }

    /** The broker's report. Output is already masked by the broker; it is masked again here. */
    public record FinishReport(
            String outcome,
            Integer exitCode,
            long durationMs,
            String stdout,
            String stderr,
            boolean stdoutCut,
            boolean stderrCut,
            String failureReason) {
    }

    public record Refusal(int status, String code) {
    }

    private final ScriptRepository repository;
    private final ScriptCredentialSource credentials;
    private final SessionRepository sessions;
    private final ArtefactStore artefactStore;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ScriptRunDispatchService(
            ScriptRepository repository,
            ScriptCredentialSource credentials,
            SessionRepository sessions,
            ArtefactStore artefactStore,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.artefactStore = artefactStore;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private Optional<Refusal> leaseRefusal(String jobId, long epoch, boolean requireDispatched) {
        Optional<ScriptRepository.RunLease> leaseOpt = repository.findRunLease(jobId);
        if (leaseOpt.isEmpty()) {
            return Optional.of(new Refusal(404, "SCRIPT_RUN_NOT_FOUND"));
        }
        ScriptRepository.RunLease lease = leaseOpt.get();
        if (!"EXECUTING".equals(lease.jobState()) || lease.leaseEpoch() != epoch
                || lease.leaseWorkerId() == null || !lease.leaseWorkerId().startsWith(WORKER_PREFIX)) {
            return Optional.of(new Refusal(409, "SCRIPT_RUN_LEASE_MISMATCH"));
        }
        if (!"REQUESTED".equals(lease.runState()) && !"RUNNING".equals(lease.runState())) {
            return Optional.of(new Refusal(409, "SCRIPT_RUN_ALREADY_CLOSED"));
        }
        if (requireDispatched && lease.dispatchEpoch().isPresent() && lease.dispatchEpoch().get() != epoch) {
            return Optional.of(new Refusal(409, "SCRIPT_RUN_LEASE_MISMATCH"));
        }
        return Optional.empty();
    }

    public StartOutcome start(String jobId, long epoch) {
        Optional<Refusal> refusal = leaseRefusal(jobId, epoch, false);
        if (refusal.isPresent()) {
            return new StartOutcome.Refused(refusal.get().status(), refusal.get().code());
        }
        ScriptRepository.RunLease lease = repository.findRunLease(jobId).orElseThrow();
        if (lease.dispatchEpoch().isPresent() && lease.dispatchEpoch().get() >= epoch) {
            return new StartOutcome.Refused(409, "SCRIPT_RUN_ALREADY_DISPATCHED");
        }
        Optional<ScriptRunRecord> runOpt = repository.findRunByJobId(jobId);
        if (runOpt.isEmpty()) {
            return new StartOutcome.Refused(404, "SCRIPT_RUN_NOT_FOUND");
        }
        ScriptRunRecord run = runOpt.get();
        if (!ScriptExecutionJob.isAllowedRunAs(run.runAs())) {
            return new StartOutcome.Refused(409, "SCRIPT_RUN_AS_NOT_ALLOWED");
        }
        Optional<ScriptRecord> scriptOpt = repository.findById(run.scriptId());
        Optional<ScriptVersionRecord> versionOpt = repository.findVersion(run.scriptId(), run.version());
        if (scriptOpt.isEmpty() || versionOpt.isEmpty()) {
            return new StartOutcome.Refused(404, "SCRIPT_OR_VERSION_NOT_FOUND");
        }
        if (artefactStore == null) {
            return new StartOutcome.Refused(503, "STORE_UNAVAILABLE");
        }
        ScriptRecord script = scriptOpt.get();
        ScriptVersionRecord version = versionOpt.get();
        // A session left by an earlier epoch of this run is revoked before a new one exists.
        lease.sessionId().ifPresent(this::revokeQuietly);

        byte[] body;
        try (InputStream in = artefactStore.retrieve(new ArtefactRef(version.artefactRef()), version.wrappedDataKey(), false)) {
            body = in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "script_run_body_unavailable: job_id={0}", jobId);
            return new StartOutcome.Refused(500, "SCRIPT_BODY_UNAVAILABLE");
        }

        // Exactly the version's declared references (Amendment 2026-11-18 (b) item 10), never the script's.
        List<ScriptCredentialSource.Resolved> resolved = new ArrayList<>();
        for (String ref : version.credentialRefs()) {
            Optional<ScriptCredentialSource.Resolved> r = credentials.resolve(ref);
            if (r.isEmpty()) {
                return new StartOutcome.Refused(409, "SCRIPT_CREDENTIAL_UNRESOLVED");
            }
            resolved.add(r.get());
        }
        ScriptCredentialAliases.Assignment aliases = ScriptCredentialAliases.assign(resolved.stream()
                .map(r -> new ScriptCredentialAliases.DeclaredRef(r.refId(), r.displayName()))
                .toList());
        List<BundleCredential> bundleCredentials = resolved.stream()
                .map(r -> new BundleCredential(r.refId(), aliases.aliasByRef().get(r.refId()), r.username(), r.secret()))
                .toList();

        String cookie = randomHex(32);
        String csrf = randomHex(24);
        String sessionId = SessionHasher.hash(cookie);
        Instant now = clock.instant();
        Duration lifetime = Duration.ofSeconds(script.timeoutSeconds()).plus(SESSION_GRACE);
        String sessionActor = ScriptExecutionJob.formatSessionActor(script.scriptId(), jobId, run.runAs());
        if (ScriptExecutionJob.isMachineRunAs(run.runAs())) {
            sessions.createMachineActive(sessionId, sessionActor, csrf, now, lifetime, lifetime);
        } else {
            sessions.createActive(sessionId, sessionActor, csrf, now, lifetime, lifetime, "script_session_create");
        }
        boolean dispatched = repository.beginDispatch(jobId, epoch, sessionId, version.credentialRefs(), now, BROKER_ACTOR);
        if (!dispatched) {
            revokeQuietly(sessionId);
            return new StartOutcome.Refused(409, "SCRIPT_RUN_ALREADY_DISPATCHED");
        }
        return new StartOutcome.Started(new RunBundle(
                jobId, script.scriptId(), version.version(), script.kind(), script.timeoutSeconds(),
                run.egressDeclared(), body, bundleCredentials, cookie, csrf, aliases.warnings()));
    }

    /** Stores the output and closes the run; empty when done, else the refusal. */
    public Optional<Refusal> finish(String jobId, long epoch, FinishReport report) {
        Optional<Refusal> refusal = leaseRefusal(jobId, epoch, true);
        if (refusal.isPresent()) {
            return refusal;
        }
        Map<String, String[]> states = Map.of(
                "SUCCESS", new String[] {"COMPLETED", "SUCCESS"},
                "FAILURE", new String[] {"FAILED", "FAILURE"},
                "TIMED_OUT", new String[] {"TIMED_OUT", "TIMED_OUT"});
        String[] state = states.get(report.outcome());
        if (state == null) {
            return Optional.of(new Refusal(400, "SCRIPT_RUN_OUTCOME_INVALID"));
        }
        ScriptRunRecord run = repository.findRunByJobId(jobId).orElseThrow();
        ScriptRepository.RunLease lease = repository.findRunLease(jobId).orElseThrow();

        // Second masking layer with the declared values, resolved again here (the broker masked first).
        List<String> values = new ArrayList<>();
        repository.findVersion(run.scriptId(), run.version()).ifPresent(v -> {
            for (String ref : v.credentialRefs()) {
                credentials.resolve(ref).ifPresent(r -> {
                    values.add(r.secret());
                    values.add(r.username());
                });
            }
        });
        ScriptOutputMasking.MaskedRunOutput masked = ScriptOutputMasking.maskThenTruncate(
                report.stdout(), report.stderr(), report.stdoutCut(), report.stderrCut(), values);

        Optional<String> ref = Optional.empty();
        Optional<String> keyId = Optional.empty();
        byte[] wrapped = null;
        Optional<String> sha = Optional.empty();
        Optional<Long> size = Optional.empty();
        String failure = report.failureReason() == null ? null
                : ScriptOutputMasking.mask(report.failureReason(), values);
        if (artefactStore != null) {
            try (ArtefactStore.ArtefactHandle handle = artefactStore.open(
                    run.scriptId(), jobId, ArtefactClass.SCRIPT_OUTPUT, false)) {
                handle.sink().write(masked.combinedUtf8Bytes());
                ArtefactStore.ArtefactMetadata meta = handle.finish();
                ref = Optional.of(meta.ref().value());
                keyId = Optional.of(meta.keyId());
                wrapped = meta.wrappedDataKey();
                sha = Optional.of(meta.plaintextSha256());
                size = Optional.of(meta.plaintextBytes());
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "script_run_output_store_failed: job_id={0}", jobId);
                failure = failure == null ? "OUTPUT_NOT_STORED" : failure + "; OUTPUT_NOT_STORED";
            }
        } else {
            failure = failure == null ? "OUTPUT_NOT_STORED" : failure + "; OUTPUT_NOT_STORED";
        }
        repository.completeRun(jobId, state[0], state[1], clock.instant(), Math.max(0L, report.durationMs()),
                report.exitCode(), masked.stdoutBytes(), masked.stderrBytes(), masked.stdoutTruncated(),
                masked.stderrTruncated(), ref, keyId, wrapped, sha, size, Optional.of(masked.inlineTail()),
                run.egressDeviceTargets(), run.credentialRefsUsed(), Optional.ofNullable(failure), BROKER_ACTOR);
        lease.sessionId().ifPresent(this::revokeQuietly);
        return Optional.empty();
    }

    /** Revokes the session of the run dispatched at {@code epoch}; the run stays open for a later epoch. */
    public Optional<Refusal> abort(String jobId, long epoch) {
        Optional<ScriptRepository.RunLease> lease = repository.findRunLease(jobId);
        if (lease.isEmpty()) {
            return Optional.of(new Refusal(404, "SCRIPT_RUN_NOT_FOUND"));
        }
        if (lease.get().dispatchEpoch().filter(e -> e == epoch).isEmpty()) {
            return Optional.of(new Refusal(409, "SCRIPT_RUN_LEASE_MISMATCH"));
        }
        lease.get().sessionId().ifPresent(this::revokeQuietly);
        return Optional.empty();
    }

    private void revokeQuietly(String sessionId) {
        try {
            sessions.revoke(sessionId, BROKER_ACTOR, "script_session_revoke");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "script_run_session_revoke_failed");
        }
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
