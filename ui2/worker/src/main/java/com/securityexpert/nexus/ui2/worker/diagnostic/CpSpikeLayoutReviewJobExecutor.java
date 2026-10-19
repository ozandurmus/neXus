package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSpikeLayoutReview;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogMeasurementRepository;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * The one-off Spike Detective layout review (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §8.3; PO approval 2026-10-19,
 * docs/design/SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md): the {@code cp_spike_layout_review} job on one Check Point
 * Gaia gateway. One trusted SSH session, at most two read-only commands, strictly one after the other:
 *
 * <ol>
 *   <li><b>C4</b> {@link CpSpikeLayoutReview#LS_SPIKE_COMMAND}, exactly as approved (timeout from its gate row, no
 *       retry, 256 KiB bound).</li>
 *   <li><b>C5</b> for ONE directory only: the newest data directory (type {@code d}, link count 2) C4 printed in this
 *       run whose mtime is at least 10 minutes older than the clock, and only when its name matches the §8.3 safe form
 *       (otherwise no C5 is sent and the outcome is {@code NAME_REJECTED}). Timeout from its gate row, no retry,
 *       64 KiB bound.</li>
 * </ol>
 *
 * No file is fetched, no content is read and nothing else runs on the device. Each command is resolved against its live
 * gate row immediately before it is sent and is ledgered like every device step; a recorded attempt is never replayed.
 * The outputs live in memory only: the transport is asked for a bounded, transcript-free exec, nothing is written to the
 * artefact store, and the one stored row ({@code system_log_measurement}, source {@value CpSpikeLayoutReview#SOURCE}) is
 * the summary of {@link CpSpikeLayoutSummary} -- counts, sizes, name classes and an age class, never a name, a path, a
 * host or a line. The chosen directory name exists only in a local variable, to build C5. Device text never reaches a
 * reason code, a log line or an exception message.
 */
public final class CpSpikeLayoutReviewJobExecutor {
    static final String ACTOR = "system:worker";
    private static final String VENDOR = "check_point";

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final SystemLogMeasurementRepository measurements;
    private final HostnameFingerprint fingerprint;
    private final DevicePlatformFactsRepository platformFacts;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public CpSpikeLayoutReviewJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts,
            DeviceRepository devices, DeviceTransport ssh, GateRegistryPort gates, SystemLogMeasurementRepository measurements,
            HostnameFingerprint fingerprint, DevicePlatformFactsRepository platformFacts) {
        this(leases, attempts, devices, ssh, gates, measurements, fingerprint, platformFacts, Clock.systemUTC());
    }

    CpSpikeLayoutReviewJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SystemLogMeasurementRepository measurements,
            HostnameFingerprint fingerprint, DevicePlatformFactsRepository platformFacts, Clock clock) {
        this.leases = leases; this.attempts = attempts; this.devices = devices; this.ssh = ssh; this.gates = gates;
        this.measurements = measurements; this.fingerprint = fingerprint;
        this.platformFacts = platformFacts == null ? DevicePlatformFactsRepository.NONE : platformFacts;
        this.clock = clock;
    }

    /** A step the job could not record or a lease it lost: the job stops; nothing further is sent. */
    private static final class Abort extends RuntimeException {
        final JobState state; final String code;
        Abort(JobState state, String code) { super(code, null, false, false); this.state = state; this.code = code; }
    }

    /** One listing's typed failure (timeout, channel failure, output over the bound). */
    private static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    private record Output(String text, int exitStatus) {}

    private static final class Run {
        final String jobId; final long epoch; final TransportSession session; int step;
        Run(String jobId, long epoch, TransportSession session) { this.jobId = jobId; this.epoch = epoch; this.session = session; }
    }

    public void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        Optional<DeviceRecord> device = devices.find(deviceId);
        var endpoint = devices.findEndpointByDeviceId(deviceId)
                .filter(e -> deviceId.equals(e.deviceId()) && "ssh_exec".equals(e.transportKind()));
        if (device.isPresent() && VENDOR.equals(device.get().vendorHint()) && "management_server".equals(device.get().role())) {
            reject(jobId, epoch, "SYSTEM_LOG_DISABLED_ON_MANAGEMENT");
            return;
        }
        if (device.isEmpty() || !device.get().permitsReadCollection() || !VENDOR.equals(device.get().vendorHint())
                || !"gateway".equals(device.get().role()) || endpoint.isEmpty() || measurements == null
                || fingerprint == null || host == null || credentialRef == null
                || CpSpikeLayoutReview.listingGate(gates).isEmpty() || CpSpikeLayoutReview.directoryGate(gates).isEmpty()) {
            reject(jobId, epoch, "SPIKE_REVIEW_UNAVAILABLE");
            return;
        }
        if (gaiaEmbedded(deviceId)) {
            // The C4/C5 rows are cp_gaia_gateway rows; a Gaia Embedded device is never contacted.
            reject(jobId, epoch, "SPIKE_REVIEW_UNSUPPORTED_PLATFORM");
            return;
        }
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "spike_review_start")) return;
        TransportSession session = null;
        try {
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
            long now = clock.instant().getEpochSecond();
            var connected = ssh.connect(new ConnectionTarget(UUID.randomUUID().toString(), host, port),
                    new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
                    Duration.ofSeconds(30));
            if (!(connected instanceof ConnectResult.Authenticated authenticated)) {
                Map<String, Object> summary = CpSpikeLayoutSummary.head("UNKNOWN", "UNKNOWN", now);
                summary.put("reason", "CONNECTION_FAILED");
                summary.put("commandsSent", 0);
                record(jobId, epoch, deviceId, summary);
                finish(jobId, epoch, JobState.FAILED, "CONNECTION_FAILED");
                return;
            }
            session = authenticated.session();
            Map<String, Object> summary = review(new Run(jobId, epoch, session), now);
            record(jobId, epoch, deviceId, summary);
            boolean measured = "MEASURED".equals(summary.get("status"));
            finish(jobId, epoch, measured ? JobState.COMPLETED : JobState.FAILED,
                    measured ? "SPIKE_LAYOUT_RECORDED" : String.valueOf(summary.get("reason")));
        } catch (Abort abort) {
            finish(jobId, epoch, abort.state, abort.code);
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "SPIKE_REVIEW_OUTCOME_UNCERTAIN");
        } finally {
            if (session != null) ssh.disconnect(session);
        }
    }

    /** C4, the selection, at most one C5; the summary of what they showed. */
    private Map<String, Object> review(Run run, long now) {
        Output c4;
        try {
            c4 = exec(run, CpSpikeLayoutReview.listingGate(gates), CpSpikeLayoutReview.LS_SPIKE_COMMAND,
                    CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES);
        } catch (Unknown unknown) {
            Map<String, Object> summary = CpSpikeLayoutSummary.head("UNKNOWN", "UNKNOWN", now);
            summary.put("reason", "PARENT_" + unknown.code);
            summary.put("commandsSent", run.step);
            return summary;
        }
        CpSpikeLayoutSummary.Listing parent = CpSpikeLayoutSummary.parse(c4.text(), c4.exitStatus(), CpSpikeLayoutSummary.PARENT);
        Map<String, Object> parentView = CpSpikeLayoutSummary.parentView(parent, now);
        if (parent.state() == CpSpikeLayoutSummary.State.UNKNOWN) {
            Map<String, Object> summary = CpSpikeLayoutSummary.head("UNKNOWN", "UNKNOWN", now);
            summary.put("reason", "PARENT_" + parent.reason());
            summary.put("commandsSent", run.step);
            summary.put("parent", parentView);
            return summary;
        }
        if (parent.state() == CpSpikeLayoutSummary.State.ABSENT) return measured("ABSENT", now, run, parentView, null);
        CpSpikeLayoutSummary.Selection selection = CpSpikeLayoutSummary.select(parent, now);
        if (selection.chosen().isEmpty()) {
            String outcome = (Integer) parentView.get("dataDirectories") == 0 ? "NO_DATA_DIRECTORY" : "NO_SETTLED_DIRECTORY";
            return measured(outcome, now, run, parentView, null);
        }
        CpLsListing.Entry chosen = selection.chosen().get();
        Map<String, Object> directory;
        if (!selection.nameAccepted()) {
            directory = CpSpikeLayoutSummary.directoryView(chosen, null, now, tokens());
            directory.put("unsettledNewerSkipped", selection.unsettledNewerSkipped());
            return measured("NAME_REJECTED", now, run, parentView, directory);
        }
        String command = selection.directoryCommand().orElseThrow(() -> new Abort(JobState.OUTCOME_UNKNOWN, "C5_NOT_BUILT"));
        Output c5;
        try {
            c5 = exec(run, CpSpikeLayoutReview.directoryGate(gates), command, CpSpikeLayoutReview.LS_SPIKE_DIR_MAX_BYTES);
        } catch (Unknown unknown) {
            directory = CpSpikeLayoutSummary.directoryView(chosen, null, now, tokens());
            directory.put("unsettledNewerSkipped", selection.unsettledNewerSkipped());
            directory.put("state", "UNKNOWN");
            directory.put("reason", unknown.code);
            Map<String, Object> summary = CpSpikeLayoutSummary.head("UNKNOWN", "UNKNOWN", now);
            summary.put("reason", "DIRECTORY_" + unknown.code);
            summary.put("commandsSent", run.step);
            summary.put("parent", parentView);
            summary.put("directory", directory);
            return summary;
        }
        CpSpikeLayoutSummary.Listing members = CpSpikeLayoutSummary.parse(c5.text(), c5.exitStatus(), "c5");
        directory = CpSpikeLayoutSummary.directoryView(chosen, members, now, tokens());
        directory.put("unsettledNewerSkipped", selection.unsettledNewerSkipped());
        if (members.state() == CpSpikeLayoutSummary.State.UNKNOWN) {
            Map<String, Object> summary = CpSpikeLayoutSummary.head("UNKNOWN", "UNKNOWN", now);
            summary.put("reason", "DIRECTORY_" + members.reason());
            summary.put("commandsSent", run.step);
            summary.put("parent", parentView);
            summary.put("directory", directory);
            return summary;
        }
        return measured(members.state() == CpSpikeLayoutSummary.State.ABSENT ? "DIRECTORY_ABSENT" : "REVIEWED", now, run,
                parentView, directory);
    }

    private static Map<String, Object> measured(String outcome, long now, Run run, Map<String, Object> parent,
            Map<String, Object> directory) {
        Map<String, Object> summary = CpSpikeLayoutSummary.head("MEASURED", outcome, now);
        summary.put("commandsSent", run.step);
        summary.put("parent", parent);
        if (directory != null) summary.put("directory", directory);
        return summary;
    }

    /**
     * One gated, ledgered exec read. The gate is resolved again here, right before the command is sent; a withdrawn
     * sign-off stops the job. A timeout, a channel failure or an output over the bound is {@link Unknown}. A non-zero
     * exit is a result (a missing directory), parsed by shape.
     */
    private Output exec(Run run, Optional<GateResolution.Known> gate, String command, int maxBytes) {
        if (gate.isEmpty()) throw new Abort(JobState.FAILED, "GATE_NOT_KNOWN");
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.get().gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        // A positive output limit makes the transport bound the answer and keep the command and answer out of its log
        // lines and transcripts.
        ExecResult result = ssh.exec(run.session, new ExecSpec(command, false, 0, maxBytes),
                Duration.ofSeconds(gate.get().timeoutS()));
        String failure = result instanceof ExecResult.Completed completed
                ? completed.output() == null ? "OUTPUT_UNAVAILABLE"
                : completed.output().getBytes(StandardCharsets.UTF_8).length > maxBytes ? "OUTPUT_LIMIT_EXCEEDED" : null
                : result instanceof ExecResult.TimedOut ? "TIMEOUT"
                : result instanceof ExecResult.ChannelFailed failed && "OUTPUT_LIMIT_EXCEEDED".equals(failed.reason())
                        ? "OUTPUT_LIMIT_EXCEEDED" : "CHANNEL_FAILED";
        long bytes = result instanceof ExecResult.Completed c && c.output() != null
                ? c.output().getBytes(StandardCharsets.UTF_8).length : 0L;
        if (!attempts.writeOutcome(attempt, run.epoch, failure == null ? "MATCHED" : "EXPECTATION_UNMET", failure,
                failure == null, bytes, null, null))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        if (failure != null) throw new Unknown(failure);
        ExecResult.Completed completed = (ExecResult.Completed) result;
        return new Output(completed.output(), completed.exitStatus());
    }

    private void record(String jobId, long epoch, String deviceId, Map<String, Object> summary) throws Exception {
        if (!measurements.record(jobId, epoch, deviceId, VENDOR, CpSpikeLayoutReview.SOURCE,
                json.writeValueAsString(new LinkedHashMap<>(summary))))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "MEASUREMENT_NOT_RECORDED");
    }

    /** Keyed tokens for the name-class vocabulary (only a core-dump-shaped name of an unknown process uses one). */
    private CpSyslogMeasurementSummary.KeyedTokens tokens() {
        return new CpSyslogMeasurementSummary.KeyedTokens() {
            @Override public String inode(long inode) { return "i_unused"; }
            @Override public String word(String lowerWord) {
                return fingerprint.of("cp-syslog-word|" + lowerWord).substring(0, 10);
            }
        };
    }

    private boolean gaiaEmbedded(String deviceId) {
        Optional<DeviceSummaryRecord> summary = devices.findSummary(deviceId);
        boolean family = platformFacts.find(deviceId).flatMap(f -> f.platformFamily()).filter("gaia_embedded"::equals).isPresent();
        return family || com.securityexpert.nexus.ui2.jobs.admission.CheckPointSparkModelHint.isKnownSparkModel(
                summary.flatMap(DeviceSummaryRecord::observedModel));
    }

    private void reject(String jobId, long epoch, String code) {
        leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "spike_review_claim_check", code);
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "spike_review_finished", reason);
    }
}
