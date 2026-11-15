package com.securityexpert.nexus.ui2.worker.supportbundle;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.supportbundle.SupportBundleKind;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.SupportBundleRunStore;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * The run skeleton shared by the Palo Alto, Cisco ASA and FortiGate support bundle jobs (steps S3-S5): claim checks, one
 * session per run, the items cheapest first, a typed outcome per item and one for the request. The Check Point job
 * (step S2) keeps its own class. A vendor subclass supplies the target checks, the session and each item.
 *
 * <p>Command output never reaches a transcript, a log line or an outcome detail: details are closed codes and numbers.
 * One command is sent at most once: after a command started there is no retry, and a session that ended in an unknown
 * state ({@link ShellClosed}) ends the run; the items after it record {@code SESSION_CLOSED_EARLIER}.</p>
 */
abstract class VendorBundleExecutor {
    static final String ACTOR = JooqSupportBundleStore.WORKER_ACTOR;
    /** PO 2026-11-15 (Q2): a bundle lives 14 days. */
    static final Duration TTL = Duration.ofDays(14);

    protected final JobLeaseRepository leases;
    protected final JobStepAttemptRepository attempts;
    protected final DeviceRepository devices;
    protected final GateRegistryPort gates;
    protected final SupportBundleRunStore store;
    protected final ArtefactStore artefacts;
    protected final HostnameFingerprint fingerprint;
    protected final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    VendorBundleExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts, HostnameFingerprint fingerprint,
            Clock clock) {
        this.leases = leases; this.attempts = attempts; this.devices = devices; this.gates = gates; this.store = store;
        this.artefacts = artefacts; this.fingerprint = fingerprint; this.clock = clock;
    }

    /** A step the job could not record or a lease it lost: the job stops. */
    static final class Abort extends RuntimeException {
        final JobState state; final String code;
        Abort(JobState state, String code) { super(code, null, false, false); this.state = state; this.code = code; }
    }

    /** One command's typed failure: the item records it and the next item still runs. */
    static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    /** The session ended in an unknown state (timeout, lost channel, interrupted command): the run goes no further. */
    static final class ShellClosed extends RuntimeException {
        final String code;
        ShellClosed(String code) { super(code, null, false, false); this.code = code; }
    }

    static final class Run {
        final String jobId; final long epoch; final String deviceId; final String version; final String hostSource;
        final Instant expiresAt; final DeviceRecord device; final Map<String, Object> precheck = new LinkedHashMap<>();
        final List<String> gateIds = new ArrayList<>();
        int step;
        Run(String jobId, long epoch, DeviceRecord device, String version, String hostSource, Instant expiresAt) {
            this.jobId = jobId; this.epoch = epoch; this.deviceId = device.deviceId(); this.device = device;
            this.version = version; this.hostSource = hostSource; this.expiresAt = expiresAt;
        }
    }

    // ------------------------------------------------------------------ vendor hooks

    /** The vendor hint of a device this job collects. */
    abstract String vendor();

    /** Whether this role and endpoint are a target of the job (no device contact). */
    abstract boolean targetAllowed(DeviceRecord device, Optional<EndpointRecord> endpoint);

    /** Whether every gate the job may send resolves against the live registry for this device. */
    abstract boolean gatesKnown(DeviceRecord device);

    /** Items answered before any device contact (stored-data guards); the result strings are those of {@link #item}. */
    Map<UUID, String> beforeConnect(Run run, RequestRow request, List<ItemRow> items) { return Map.of(); }

    /** Opens the session; a failure code, or empty when the session is open. */
    abstract Optional<String> open(Run run, String host, int port, String credentialRef, Optional<EndpointRecord> endpoint);

    /**
     * Runs one item and records it in the store. Returns {@code STORED} (artefact stored, complete), {@code STORED_PARTIAL},
     * {@code COMPLETED} (complete, nothing stored) or another closed word for an item that did not complete.
     */
    abstract String item(Run run, ItemRow item, SupportBundleKind kind, RequestRow request) throws IOException;

    /** Called once after the last item when the session is still usable (a FortiGate leaves {@code config global}). */
    void afterItems(Run run) {}

    abstract void close(Run run);

    /** Set by a subclass when the session ended in an unknown state; the items after it record SESSION_CLOSED_EARLIER. */
    protected boolean sessionEnded;

    // ------------------------------------------------------------------ run

    public final void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        Optional<RequestRow> request = store == null ? Optional.empty() : store.findByJob(jobId);
        Optional<DeviceRecord> device = devices.find(deviceId);
        Optional<EndpointRecord> endpoint = devices.findEndpointByDeviceId(deviceId).filter(e -> deviceId.equals(e.deviceId()));
        if (request.isEmpty() || device.isEmpty() || !device.get().permitsReadCollection()
                || !vendor().equals(device.get().vendorHint()) || !targetAllowed(device.get(), endpoint) || artefacts == null
                || !gatesKnown(device.get()) || credentialRef == null) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "support_bundle_claim_check",
                    "SUPPORT_BUNDLE_UNAVAILABLE");
            request.ifPresent(r -> store.finishRequest(r.id(), "FAILED", "FAILED", "{}", clock.instant().plus(TTL)));
            return;
        }
        RequestRow req = request.get();
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "support_bundle_start")) return;
        if (!store.markRunning(req.id())) {
            finish(jobId, epoch, JobState.COMPLETED, "SUPPORT_BUNDLE_NOT_QUEUED");
            return;
        }
        List<ItemRow> items = store.items(req.id()).stream()
                .sorted(Comparator.comparingInt(i -> SupportBundleKind.fromId(i.kind()).map(SupportBundleKind::order).orElse(99)))
                .toList();
        Run run = null;
        boolean opened = false;
        try {
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
            var summary = devices.findSummary(deviceId);
            String version = summary.flatMap(DeviceSummaryRecord::observedSoftwareVersion).orElse("unknown");
            String hostSource = summary.flatMap(DeviceSummaryRecord::observedHostname).orElse(deviceId);
            run = new Run(jobId, epoch, device.get(), version, hostSource, clock.instant().plus(TTL));
            int stored = 0, completed = 0;
            Map<UUID, String> early = beforeConnect(run, req, items);
            for (String result : early.values()) {
                if ("COMPLETED".equals(result) || "STORED".equals(result)) completed++;
                if ("STORED_PARTIAL".equals(result)) stored++;
            }
            List<ItemRow> pending = items.stream().filter(i -> !early.containsKey(i.id())).toList();
            if (!pending.isEmpty()) {
                Optional<String> failure = open(run, host, port, credentialRef, endpoint);
                if (failure.isPresent()) {
                    for (ItemRow item : pending) store.finishItem(item.id(), "FAILED", failure.get(), null, List.of());
                    String state = completed + stored > 0 ? "PARTIAL" : "FAILED";
                    store.finishRequest(req.id(), state, state, json.writeValueAsString(run.precheck), run.expiresAt);
                    finish(jobId, epoch, "FAILED".equals(state) ? JobState.FAILED : JobState.COMPLETED,
                            "SUPPORT_BUNDLE_CONNECTION_FAILED");
                    return;
                }
                opened = true;
                sessionEnded = false;
                String ended = null;
                for (ItemRow item : pending) {
                    if (ended != null) {
                        store.finishItem(item.id(), "FAILED", "SESSION_CLOSED_EARLIER", null, List.of());
                        continue;
                    }
                    store.markItemRunning(item.id());
                    run.gateIds.clear();
                    String result;
                    try {
                        SupportBundleKind kind = SupportBundleKind.fromId(item.kind()).orElseThrow();
                        if (!vendor().equals(kind.vendor()) || !kind.enabled()) {
                            store.finishItem(item.id(), "UNSUPPORTED", "KIND_NOT_AVAILABLE", null, List.of());
                            result = "UNSUPPORTED";
                        } else {
                            result = item(run, item, kind, req);
                        }
                    } catch (Unknown unknown) {
                        store.finishItem(item.id(), "FAILED", unknown.code, null, List.copyOf(run.gateIds));
                        result = "FAILED";
                    } catch (ShellClosed closed) {
                        store.finishItem(item.id(), "FAILED", closed.code, null, List.copyOf(run.gateIds));
                        result = "FAILED";
                        ended = closed.code;
                    }
                    if ("COMPLETED".equals(result)) completed++;
                    if ("STORED".equals(result)) { stored++; completed++; }
                    if ("STORED_PARTIAL".equals(result)) stored++;
                    if (sessionEnded && ended == null) ended = "SESSION_CLOSED_EARLIER";
                }
                if (ended == null) afterItems(run);
            }
            String state = completed == items.size() ? "COMPLETED" : (completed + stored > 0 ? "PARTIAL" : "FAILED");
            store.finishRequest(req.id(), state, state, json.writeValueAsString(run.precheck), run.expiresAt);
            finish(jobId, epoch, "FAILED".equals(state) ? JobState.FAILED : JobState.COMPLETED, "SUPPORT_BUNDLE_" + state);
        } catch (Abort abort) {
            finish(jobId, epoch, abort.state, abort.code);
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "SUPPORT_BUNDLE_OUTCOME_UNCERTAIN");
        } finally {
            if (run != null && opened) close(run);
        }
    }

    // ------------------------------------------------------------------ shared pieces

    /** One ledgered step: the pre-contact row is committed and the boundary marked before anything is sent. */
    final String beginStep(Run run, GateResolution.Known gate, String actionClassLabel) {
        run.gateIds.add(gate.gateId());
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.gateId(), actionClassLabel, 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        return attempt;
    }

    final void endStep(Run run, String attempt, boolean ok, String failure, long bytes) {
        if (!attempts.writeOutcome(attempt, run.epoch, ok ? "MATCHED" : "EXPECTATION_UNMET", ok ? null : failure, ok, bytes, null, null))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
    }

    final BackupArtefactManifestRecord manifest(Run run, ArtefactStore.ArtefactMetadata meta) {
        return new BackupArtefactManifestRecord(UUID.randomUUID().toString(), run.deviceId, Optional.empty(),
                ArtefactClass.SUPPORT_BUNDLE, vendor(), Optional.of(run.version), fingerprint.of(run.hostSource),
                meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                meta.wrappedDataKey(), ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                Optional.of(run.expiresAt), meta.ref().value(), Optional.empty());
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "support_bundle_finished", reason);
    }

    /**
     * The receiving end of a text capture: each line is redacted ({@link SupportBundleTextRedactor.LineRedactor}) and
     * written to the artefact sink; the item stays under its byte bound. Nothing unredacted is ever written.
     */
    static final class TextCapture implements com.securityexpert.nexus.ui2.jobs.transport.LineSink {
        private final OutputStream out;
        private final SupportBundleTextRedactor.LineRedactor redactor;
        private final long maxBytes;
        private long written;
        boolean tooLarge, redactionFailed;

        TextCapture(OutputStream out, SupportBundleTextRedactor.LineRedactor redactor, long maxBytes) {
            this.out = out; this.redactor = redactor; this.maxBytes = maxBytes;
        }

        @Override
        public void line(String line) throws IOException {
            byte[] bytes;
            try {
                bytes = (redactor.line(line) + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (SupportBundleTextRedactor.RedactionFailed failed) {
                redactionFailed = true;
                throw new IOException("REDACTION_FAILED");
            }
            if (written + bytes.length > maxBytes) {
                tooLarge = true;
                throw new IOException("TOO_LARGE");
            }
            out.write(bytes);
            written += bytes.length;
        }

        long written() { return written; }
    }
}
