package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSyslogMeasure;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogMeasurementRepository;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * System-log programme phase A, M3/M4 (DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15):
 * on one enrolled Palo Alto firewall, enqueue a system-log and then a config-log query of 100 entries, poll each
 * log job until FIN within a fixed bound, and record only the per-source summary {@link PanLogMeasurementSummary}
 * computes in memory. The XML answers are discarded as soon as they are summarized; no job transcript is opened,
 * so they never reach the artefact store either. An API error, an unfinished log job or an unreadable answer is a
 * typed {@code UNKNOWN} summary, never a number. Never retries a recorded attempt.
 */
public final class PanSyslogMeasureJobExecutor {
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }

    static final String ACTOR = "system:worker";
    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    static final int MAX_POLLS = 30;
    static final int MAX_RESULT_CHARS = 8 * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");
    private static final Pattern JOB_ID = Pattern.compile("[0-9]{1,20}");

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final DeviceTransport transport;
    private final PanCredentialResolver credentials;
    private final GateRegistryPort gates;
    private final SystemLogMeasurementRepository measurements;
    private final Pause pause;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public PanSyslogMeasureJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport transport, PanCredentialResolver credentials, GateRegistryPort gates,
            SystemLogMeasurementRepository measurements) {
        this(leases, attempts, devices, transport, credentials, gates, measurements, d -> Thread.sleep(d.toMillis()),
                Clock.systemUTC());
    }

    PanSyslogMeasureJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport transport, PanCredentialResolver credentials, GateRegistryPort gates,
            SystemLogMeasurementRepository measurements, Pause pause, Clock clock) {
        this.leases = leases; this.attempts = attempts; this.devices = devices; this.transport = transport;
        this.credentials = credentials; this.gates = gates; this.measurements = measurements;
        this.pause = pause; this.clock = clock;
    }

    /** A step the job could not record or a lease it lost: the job stops; nothing further is sent. */
    private static final class Abort extends RuntimeException {
        final JobState state; final String code;
        Abort(JobState state, String code) { super(code, null, false, false); this.state = state; this.code = code; }
    }

    /** One source's typed failure: recorded as an UNKNOWN summary, the next source still runs. */
    private static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    private static final class Run {
        final String jobId; final long epoch; final ApiTarget target; int step;
        Run(String jobId, long epoch, ApiTarget target) { this.jobId = jobId; this.epoch = epoch; this.target = target; }
    }

    public void execute(String jobId, long epoch, String deviceId) {
        var device = devices.find(deviceId);
        var endpoint = devices.findEndpointByDeviceId(deviceId)
                .filter(e -> deviceId.equals(e.deviceId()) && "pan_xml_api".equalsIgnoreCase(e.transportKind()));
        var system = PanSyslogMeasure.gate(PanSyslogMeasure.SYSTEM_ENQUEUE, gates);
        var config = PanSyslogMeasure.gate(PanSyslogMeasure.CONFIG_ENQUEUE, gates);
        var get = PanSyslogMeasure.gate(PanSyslogMeasure.JOB_GET, gates);
        if (device.isEmpty() || !device.get().permitsReadCollection() || !"palo_alto".equals(device.get().vendorHint())
                || !"gateway".equals(device.get().role()) || endpoint.isEmpty() || measurements == null
                || system.isEmpty() || config.isEmpty() || get.isEmpty()) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "syslog_measure_claim_check",
                    "SYSLOG_MEASURE_UNAVAILABLE");
            return;
        }
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "syslog_measure_start")) return;
        Run run = new Run(jobId, epoch, new ApiTarget(endpoint.get().endpointId(), endpoint.get().addressRef()));
        try {
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
            Optional<String> key = apiKey(run.target, device.get().credentialReferenceId());
            List<String> failures = new java.util.ArrayList<>();
            for (String source : List.of(PanLogMeasurementSummary.SYSTEM, PanLogMeasurementSummary.CONFIG)) {
                Map<String, Object> summary;
                if (key.isEmpty()) {
                    summary = PanLogMeasurementSummary.unknown("AUTH_UNAVAILABLE");
                } else {
                    GateResolution.Known enqueue = PanLogMeasurementSummary.SYSTEM.equals(source) ? system.get() : config.get();
                    try {
                        summary = measure(run, source, enqueue, get.get(), key.get());
                    } catch (Unknown unknown) {
                        summary = PanLogMeasurementSummary.unknown(unknown.code);
                    }
                }
                if (!"MEASURED".equals(summary.get("status"))) failures.add((String) summary.get("reason"));
                if (!measurements.record(jobId, epoch, deviceId, "palo_alto", source, json.writeValueAsString(summary)))
                    throw new Abort(JobState.OUTCOME_UNKNOWN, "MEASUREMENT_NOT_RECORDED");
            }
            finish(jobId, epoch, failures.isEmpty() ? JobState.COMPLETED : JobState.FAILED,
                    failures.isEmpty() ? "MEASUREMENT_RECORDED" : failures.get(0));
        } catch (Abort abort) {
            finish(jobId, epoch, abort.state, abort.code);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "INTERRUPTED");
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "SYSLOG_MEASURE_OUTCOME_UNCERTAIN");
        }
    }

    private Map<String, Object> measure(Run run, String source, GateResolution.Known enqueueGate,
            GateResolution.Known getGate, String key) throws InterruptedException {
        String logType = PanLogMeasurementSummary.SYSTEM.equals(source) ? "system" : "config";
        Map<String, String> enqueueParams = new LinkedHashMap<>();
        enqueueParams.put("log-type", logType);
        enqueueParams.put("nlogs", String.valueOf(PanSyslogMeasure.REQUESTED_ENTRIES));
        String enqueued = call(run, enqueueGate, enqueueParams, key);
        long enqueuedAt = clock.millis();
        String logJob = PanLogMeasurementSummary.enqueuedJobId(enqueued).filter(id -> JOB_ID.matcher(id).matches())
                .orElseThrow(() -> new Unknown("LOG_JOB_NOT_ENQUEUED"));
        for (int poll = 1; poll <= MAX_POLLS; poll++) {
            pause.sleep(POLL_INTERVAL);
            Map<String, String> getParams = new LinkedHashMap<>();
            getParams.put("action", "get");
            getParams.put("job-id", logJob);
            String answer = call(run, getGate, getParams, key);
            String status = PanLogMeasurementSummary.jobStatus(answer).orElseThrow(() -> new Unknown("LOG_JOB_STATUS_UNAVAILABLE"));
            if ("FIN".equals(status)) {
                long latency = Math.max(0, clock.millis() - enqueuedAt);
                return PanLogMeasurementSummary.summarize(source, answer, latency, poll)
                        .orElseThrow(() -> new Unknown("LOG_RESULT_UNREADABLE"));
            }
        }
        throw new Unknown("LOG_JOB_NOT_FINISHED");
    }

    /** One gated, ledgered read; an answer that is not an HTTP 200 success within the size bound is UNKNOWN. */
    private String call(Run run, GateResolution.Known gate, Map<String, String> params, String key) {
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        XmlApiResult result = transport.xmlApiCall(run.target, new XmlApiSpec("POST", "log", "", "direct_firewall",
                params, Map.of("X-PAN-KEY", key)), Duration.ofSeconds(gate.timeoutS()));
        String failure = !(result instanceof XmlApiResult.Completed completed) ? "TRANSPORT_FAILED"
                : completed.httpStatus() != 200 || completed.body() == null ? "API_ERROR"
                : completed.body().length() > MAX_RESULT_CHARS ? "RESULT_TOO_LARGE"
                : !PanLogMeasurementSummary.success(completed.body()) ? "API_ERROR" : null;
        long bytes = result instanceof XmlApiResult.Completed completed && completed.body() != null
                ? completed.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0L;
        if (!attempts.writeOutcome(attempt, run.epoch, failure == null ? "MATCHED" : "EXPECTATION_UNMET", failure,
                failure == null, bytes, null, null))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        if (failure != null) throw new Unknown(failure);
        return ((XmlApiResult.Completed) result).body();
    }

    /** The existing PAN authentication path (as inventory and failover use it); the key lives in memory only. */
    private Optional<String> apiKey(ApiTarget target, String credentialRef) {
        try {
            var credential = credentials.resolve(credentialRef);
            var result = transport.xmlApiCall(target, new XmlApiSpec("POST", "keygen", "", "",
                    Map.of("user", credential.username(), "password", new String(credential.password())), Map.of()),
                    Duration.ofSeconds(PanSyslogMeasure.TIMEOUT_S));
            String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : "";
            var match = KEY.matcher(body == null ? "" : body);
            return PanLogMeasurementSummary.success(body) && match.find() ? Optional.of(match.group(1)) : Optional.empty();
        } catch (RuntimeException unavailable) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unavailable);
            if (unavailable instanceof com.securityexpert.nexus.ui2.worker.JobCancellationScope.Cancelled cancelled) throw cancelled;
            return Optional.empty();
        }
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "syslog_measure_finished", reason);
    }
}
