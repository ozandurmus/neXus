package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.capability.AmbiguousGateResolutionException;
import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.failover.AsaFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentityRepository;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.worker.backup.asa.AsaFailoverMeasure;
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaExecutor;
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaPlan;
import com.securityexpert.nexus.ui2.worker.failover.AsaFailoverChecks.Observer;
import com.securityexpert.nexus.ui2.worker.failover.AsaFailoverChecks.Result;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * Cisco ASA failover readiness (docs/design/PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md): READINESS ONLY.
 * There is no write path, no write gate and no state-changing command in this class: a run of any other kind, or a
 * worker whose switch is off, ends UNKNOWN before any device contact.
 *
 * <p>Per member: one trusted SSH session with the inventory's own preamble (pager off, privilege 15, single context),
 * then the four approved reads one at a time through their gate rows. A gate that does not resolve KNOWN (DRAFTED
 * until the Product Owner approves) sends nothing and leaves its checks UNKNOWN.
 */
public final class AsaFailoverJobExecutor {
    private static final System.Logger LOG = System.getLogger(AsaFailoverJobExecutor.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTOR = "system:asa-failover-worker";
    static final String READINESS_KIND = "READINESS";
    static final String VENDOR = "cisco_asa";
    /** The second {@code show failover} sample is taken at least this long after the first (counter comparison, check 6). */
    static final Duration SAMPLE_GAP = Duration.ofSeconds(5);
    private static final Duration CONNECT = Duration.ofSeconds(30);
    private static final Duration PREAMBLE = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_CHARS = 262144;

    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }

    /** The four approved literals, their gate ids and the shape-log line caps. */
    enum Read {
        SHOW_FAILOVER("asa_failover_show_failover", "show failover", 160),
        SHOW_FAILOVER_STATE("asa_failover_show_failover_state", "show failover state", 60),
        SHOW_FAILOVER_HISTORY("asa_failover_show_failover_history", "show failover history", 80),
        SHOW_CONN_COUNT("asa_failover_show_conn_count", "show conn count", 10);
        final String gateId, command; final int maxShapeLines;
        Read(String gateId, String command, int maxShapeLines) {
            this.gateId = gateId; this.command = command; this.maxShapeLines = maxShapeLines;
        }
    }
    private static final class Stop extends RuntimeException {
        final String code;
        Stop(String code) { super(code); this.code = code; }
    }

    private final JooqCpFailoverRepository store;
    private final DeviceRepository devices;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final HaPairIdentityRepository identities;
    private final AsaFailoverReadinessSwitch readinessSwitch;
    private final Pause pause;
    private final Duration commandPause;
    private final Clock clock;
    private String jobId, runId, clusterRef;
    private long epoch;
    private int commandIndex;
    private final java.util.Set<Read> shapeLogged = new java.util.HashSet<>();

    public AsaFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices, JobLeaseRepository leases,
            JobStepAttemptRepository attempts, DeviceTransport ssh, GateRegistryPort gates,
            HaPairIdentityRepository identities, AsaFailoverReadinessSwitch readinessSwitch) {
        this(store, devices, leases, attempts, ssh, gates, identities, readinessSwitch,
            d -> Thread.sleep(d.toMillis()), Duration.ofSeconds(2), Clock.systemUTC());
    }

    AsaFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices, JobLeaseRepository leases,
            JobStepAttemptRepository attempts, DeviceTransport ssh, GateRegistryPort gates,
            HaPairIdentityRepository identities, AsaFailoverReadinessSwitch readinessSwitch, Pause pause,
            Duration commandPause, Clock clock) {
        this.store = store; this.devices = devices; this.leases = leases; this.attempts = attempts; this.ssh = ssh;
        this.gates = java.util.Objects.requireNonNull(gates);
        this.identities = java.util.Objects.requireNonNull(identities);
        this.readinessSwitch = java.util.Objects.requireNonNull(readinessSwitch);
        this.pause = pause; this.commandPause = commandPause; this.clock = clock;
    }

    public void execute(String jobId, long epoch) {
        var found = store.runByJob(jobId);
        if (found.isEmpty() || !VENDOR.equals(found.get().vendor())) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.FAILED, ACTOR, "asa_failover_missing_run", "RUN_NOT_FOUND");
            return;
        }
        var run = found.get();
        this.jobId = jobId; this.runId = run.id(); this.clusterRef = run.clusterRef(); this.epoch = epoch;
        this.commandIndex = 0; this.shapeLogged.clear();
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "asa_failover_start")) return;
        try {
            // Readiness only: any other run kind, or a worker whose switch is off, ends before any device contact.
            if (!READINESS_KIND.equals(run.kind())) {
                store.state(runId, "STOPPED", "STOPPED", "UNKNOWN", null, "READINESS_ONLY");
                leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "asa_failover_refused", "READINESS_ONLY");
                return;
            }
            if (!readinessSwitch.enabled()) {
                finish("UNKNOWN", null, "ASA_READINESS_SWITCH_OFF");
                return;
            }
            List<DeviceSummaryRecord> members = new ArrayList<>(devices.findMembersByClusterRef(clusterRef));
            members.sort(java.util.Comparator.comparing(DeviceSummaryRecord::deviceId));
            if (members.size() != 2 || members.get(0).deviceId().equals(members.get(1).deviceId())
                    || members.stream().anyMatch(m -> !VENDOR.equals(m.vendorHint())))
                throw new Stop("CLUSTER_NOT_ELIGIBLE");
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED");
            Map<Read, GateResolution.Known> approved = new LinkedHashMap<>();
            Map<Read, String> refused = new LinkedHashMap<>();
            for (Read read : Read.values()) {
                try {
                    var resolved = GateResolver.resolve(key(read), Optional.of(ActionClass.CLASS_0_READ), gates);
                    if (resolved instanceof GateResolution.Known known && read.gateId.equals(known.gateId())) approved.put(read, known);
                    else if (resolved instanceof GateResolution.Unknown unknown && unknown.reason().contains("DRAFTED"))
                        refused.put(read, "GATE_NOT_SIGNED_OFF");
                    else refused.put(read, "GATE_UNAVAILABLE");
                } catch (AmbiguousGateResolutionException ambiguous) {
                    refused.put(read, "GATE_UNAVAILABLE");
                }
            }
            store.state(runId, "PRECHECK", "PRECHECK", null, null, null);
            Observer first, second;
            if (approved.isEmpty()) {
                // Nothing may be sent: no contact at all.
                first = second = new Observer(null, null, null, null, Optional.empty());
            } else {
                first = collect(members.get(0), approved);
                second = collect(members.get(1), approved);
            }
            final List<Result> results = withGateReasons(
                AsaFailoverChecks.evaluate(first, second, identities.find(clusterRef), clock.instant()), refused);
            for (Result result : results) {
                String derived = derived(result);
                store.check(runId, "pre", members.get(0).deviceId(), null, result.check(), result.status(), derived);
                store.check(runId, "pre", members.get(1).deviceId(), null, result.check(), result.status(), derived);
            }
            String outcome = AsaFailoverChecks.outcome(results);
            Result lead = results.stream().filter(Result::blocking).filter(r -> "FAIL".equals(r.status())).findFirst()
                .or(() -> results.stream().filter(Result::blocking).filter(r -> !"PASS".equals(r.status())).findFirst()).orElse(null);
            finish(outcome, lead == null ? null : lead.check(), lead == null ? "ALL_CHECKS_PASSED" : lead.reason());
        } catch (Stop stopped) {
            finish("UNKNOWN", null, stopped.code);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish("UNKNOWN", null, "INTERRUPTED");
        } catch (RuntimeException unexpected) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unexpected);
            finish("UNKNOWN", null, "COLLECTION_FAILED");
        }
    }

    private static CanonicalCommandKey key(Read read) {
        return new CanonicalCommandKey(VENDOR, "cisco_asa_firewall", "privileged_exec", "SSH_INTERACTIVE", read.command);
    }

    /** A check made UNKNOWN by a read that was never sent says why. */
    private static List<Result> withGateReasons(List<Result> results, Map<Read, String> refused) {
        if (refused.isEmpty()) return results;
        List<Result> out = new ArrayList<>();
        for (Result r : results) {
            List<Read> needs = switch (r.check()) {
                case 5 -> List.of(Read.SHOW_FAILOVER_STATE);
                case 6, 7 -> List.of(Read.SHOW_FAILOVER, Read.SHOW_CONN_COUNT);
                case 9 -> List.of(Read.SHOW_FAILOVER_HISTORY);
                default -> List.of(Read.SHOW_FAILOVER);
            };
            Optional<String> reason = needs.stream().map(refused::get).filter(java.util.Objects::nonNull).findFirst();
            out.add(reason.isPresent() && "UNKNOWN".equals(r.status()) ? new Result(r.check(), r.status(), reason.get(), r.evidence()) : r);
        }
        return out;
    }

    private static String derived(Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", result.reason());
        body.putAll(result.evidence());
        try { return JSON.writeValueAsString(body); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Derived projection unavailable"); }
    }

    private void finish(String outcome, Integer check, String code) {
        store.state(runId, "DONE", "DONE", outcome, check == null ? null : checkName(check), code);
        leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR, "asa_readiness_done", outcome);
    }
    private static String checkName(int check) {
        return switch (check) {
            case 1 -> "Failover state and roles"; case 2 -> "Pair identity"; case 3 -> "Failover and state links";
            case 4 -> "Monitored interfaces"; case 5 -> "Configuration sync"; case 6 -> "Stateful replication";
            case 7 -> "Sessions carried"; case 8 -> "Version parity"; case 9 -> "Last failover"; default -> "Pre-check";
        };
    }

    // ---- one member ------------------------------------------------------------------------------------------

    private Observer collect(DeviceSummaryRecord summary, Map<Read, GateResolution.Known> approved) throws InterruptedException {
        var device = devices.find(summary.deviceId()).filter(d -> d.permitsReadCollection())
            .orElseThrow(() -> new Stop("DEVICE_NOT_ELIGIBLE"));
        var endpoint = devices.findEndpointByDeviceId(summary.deviceId())
            .filter(e -> summary.deviceId().equals(e.deviceId()) && "ssh_exec".equalsIgnoreCase(e.transportKind()))
            .orElseThrow(() -> new Stop("ENDPOINT_MISSING"));
        String host = com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.host(endpoint.addressRef());
        int port = com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.port(endpoint.addressRef(), CiscoAsaPlan.DEFAULT_PORT);
        var connected = ssh.connect(new ConnectionTarget(endpoint.endpointId(), host, port),
            new ConnectSpec(device.credentialReferenceId(), PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
            CONNECT);
        if (!(connected instanceof ConnectResult.Authenticated auth)) throw new Stop("TRUSTED_CONNECTION_REQUIRED");
        TransportSession session = auth.session();
        try {
            preamble(session);
            Optional<String> failover1 = Optional.empty(), failover2 = Optional.empty(), state = Optional.empty(),
                history = Optional.empty(), conn = Optional.empty();
            int index = 0;
            if (approved.containsKey(Read.SHOW_FAILOVER)) {
                failover1 = read(session, approved.get(Read.SHOW_FAILOVER), Read.SHOW_FAILOVER, index++);
                pause.sleep(SAMPLE_GAP);
                failover2 = read(session, approved.get(Read.SHOW_FAILOVER), Read.SHOW_FAILOVER, index++);
            }
            if (approved.containsKey(Read.SHOW_FAILOVER_STATE))
                state = read(session, approved.get(Read.SHOW_FAILOVER_STATE), Read.SHOW_FAILOVER_STATE, index++);
            if (approved.containsKey(Read.SHOW_FAILOVER_HISTORY))
                history = read(session, approved.get(Read.SHOW_FAILOVER_HISTORY), Read.SHOW_FAILOVER_HISTORY, index++);
            if (approved.containsKey(Read.SHOW_CONN_COUNT))
                conn = read(session, approved.get(Read.SHOW_CONN_COUNT), Read.SHOW_CONN_COUNT, index);
            var show1 = AsaFailoverChecks.parseShow(failover1.orElse(null));
            var show2 = AsaFailoverChecks.parseShow(failover2.orElse(null));
            var sync = AsaFailoverChecks.parseState(state.orElse(null));
            var hist = AsaFailoverChecks.parseHistory(history.orElse(null));
            var inUse = AsaFailoverChecks.inUse(conn.orElse(null));
            logShapes(Read.SHOW_FAILOVER, failover1, show1.failoverOn() == null || !show1.tableHeader());
            logShapes(Read.SHOW_FAILOVER_STATE, state, !sync.sectionPresent());
            logShapes(Read.SHOW_FAILOVER_HISTORY, history, hist.entries() == 0);
            logShapes(Read.SHOW_CONN_COUNT, conn, inUse.isEmpty());
            return new Observer(approved.containsKey(Read.SHOW_FAILOVER) ? show1 : null,
                approved.containsKey(Read.SHOW_FAILOVER) ? show2 : null,
                approved.containsKey(Read.SHOW_FAILOVER_STATE) ? sync : null,
                approved.containsKey(Read.SHOW_FAILOVER_HISTORY) ? hist : null, inUse);
        } finally {
            ssh.disconnect(session);
        }
    }

    /** The inventory session's own preamble: pager off, privilege 15, single context (all three already approved). */
    private void preamble(TransportSession session) {
        ssh.execInteractive(session, new ExecSpec(CiscoAsaPlan.TERMINAL_PAGER_0, true), PREAMBLE);
        var priv = ssh.execInteractive(session, new ExecSpec(CiscoAsaPlan.SHOW_CURPRIV, true), PREAMBLE);
        Optional<Integer> level = priv instanceof ExecResult.Completed c && !CiscoAsaPlan.isCliError(c.output())
            ? CiscoAsaPlan.parsePrivilege(CiscoAsaExecutor.stripEcho(c.output(), CiscoAsaPlan.SHOW_CURPRIV)) : Optional.empty();
        if (level.isEmpty() || level.get() < 15) throw new Stop("PRIVILEGE_NOT_15");
        var mode = ssh.execInteractive(session, new ExecSpec(CiscoAsaPlan.SHOW_MODE, true), PREAMBLE);
        String modeWord = mode instanceof ExecResult.Completed c && !CiscoAsaPlan.isCliError(c.output())
            ? CiscoAsaPlan.contextModeWord(CiscoAsaExecutor.stripEcho(c.output(), CiscoAsaPlan.SHOW_MODE)) : "unknown";
        if (!"single".equals(modeWord)) throw new Stop(AsaFailoverChecks.UNSUPPORTED_MODE);
    }

    /** One approved read: a pause, the attempt ledger, the send (never recorded in the job transcript), no retry. */
    private Optional<String> read(TransportSession session, GateResolution.Known gate, Read read, int memberIndex)
            throws InterruptedException {
        if (memberIndex > 0 && !commandPause.isZero()) pause.sleep(commandPause);
        if (!attempts.findByJobAndStep(jobId, commandIndex).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(jobId, epoch, commandIndex++, gate.gateId(), gate.actionClass().id(), 1);
        if (attempt == null) throw new Stop("PRE_CONTACT_RECORD_FAILED");
        if (!attempts.markBoundaryCrossed(attempt, epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN");
        store.command(runId, gate.gateId());
        ExecResult result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(
            () -> ssh.execInteractive(session, new ExecSpec(read.command, true), Duration.ofSeconds(gate.timeoutS())));
        if (!(result instanceof ExecResult.Completed completed) || CiscoAsaPlan.isCliError(completed.output())
                || completed.output() == null || completed.output().length() > MAX_OUTPUT_CHARS) {
            attempts.writeOutcome(attempt, epoch, "FAILED", "COMMAND_UNAVAILABLE", false, null, null, null);
            return Optional.empty();
        }
        if (!attempts.writeOutcome(attempt, epoch, "MATCHED", null, true, null, null, null))
            throw new Stop("ATTEMPT_RECORD_FAILED");
        return Optional.of(CiscoAsaExecutor.stripEcho(completed.output(), read.command));
    }

    /** On a read that did not parse: its masked shape (vendor words kept, other words {@code a}, digits {@code 9}). */
    private void logShapes(Read read, Optional<String> answer, boolean unparsed) {
        if (!unparsed || !shapeLogged.add(read)) return;
        String shape = answer.isEmpty() ? "[no answer]" : String.valueOf(AsaFailoverMeasure.shape(answer.get(), read.maxShapeLines));
        LOG.log(System.Logger.Level.INFO, "[READINESS_SHAPE] vendor=cisco_asa read={0} shape={1}", read.gateId, shape);
    }
}
