package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * One Palo Alto HA configuration sync (docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md; PO decision 2026-10-24,
 * RATIFIED). A fresh HA-state read of both members immediately before the write; the write goes to the member that is
 * active in that read, and only if it is the request's bound source; the reply's status is recorded; nothing is ever
 * retried; afterwards the unit's readiness re-runs. Every stop before the write leaves the device untouched.
 */
public final class PanConfigSyncJobExecutor {
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }

    static final String STATE = "<show><high-availability><state/></high-availability></show>";
    static final String SYNC = GateRow.PAN_CONFIG_SYNC_COMMAND;
    private static final String READ_GATE = "pan_inventory_show_ha_state";
    private static final Pattern KEY = Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");
    /** The reply's root element only: {@code <response status="success|error">}, optionally after an XML declaration. */
    private static final Pattern STATUS = Pattern.compile("\\A\\s*(?:<\\?xml[^>]{0,200}\\?>\\s*)?<response\\s+status\\s*=\\s*[\"'](success|error)[\"']");
    private static final int MAX_BODY = 262144;
    private static final String ACTOR = "system:pan-config-sync-worker";
    /** Design §4: the settle pause between the reply and the readiness re-run; no device contact. */
    public static final Duration DEFAULT_SETTLE = Duration.ofSeconds(15);

    private record Peer(String id, ApiTarget target, String key, String identity) {}
    static final class Stop extends RuntimeException {
        final String code;
        Stop(String code) { super(code); this.code = code; }
    }

    private final JooqPanConfigSyncRepository store;
    private final DeviceRepository devices;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceTransport transport;
    private final PanCredentialResolver credentials;
    private final GateRegistryPort gates;
    private final Pause pause;
    private final Duration settle;
    private final FailoverMutationSwitch mutationSwitch;

    public PanConfigSyncJobExecutor(JooqPanConfigSyncRepository store, DeviceRepository devices, JobLeaseRepository leases,
            JobStepAttemptRepository attempts, DeviceTransport transport, PanCredentialResolver credentials,
            GateRegistryPort gates) {
        this(store, devices, leases, attempts, transport, credentials, gates, d -> Thread.sleep(d.toMillis()),
            DEFAULT_SETTLE, FailoverMutationSwitch.fromEnvironment());
    }

    public PanConfigSyncJobExecutor(JooqPanConfigSyncRepository store, DeviceRepository devices, JobLeaseRepository leases,
            JobStepAttemptRepository attempts, DeviceTransport transport, PanCredentialResolver credentials,
            GateRegistryPort gates, Pause pause, Duration settle, FailoverMutationSwitch mutationSwitch) {
        this.store = store; this.devices = devices; this.leases = leases; this.attempts = attempts;
        this.transport = transport; this.credentials = credentials; this.gates = gates; this.pause = pause;
        this.settle = java.util.Objects.requireNonNull(settle);
        this.mutationSwitch = java.util.Objects.requireNonNull(mutationSwitch);
    }

    public void execute(String jobId, long epoch) {
        var found = store.byJob(jobId);
        if (found.isEmpty()) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.FAILED, ACTOR, "pan_config_sync_missing",
                "CONFIG_SYNC_NOT_FOUND");
            return;
        }
        var sync = found.get();
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "pan_config_sync_start")) return;
        boolean read = false;
        Write write;
        try {
            if (!mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED);
            String admitted = store.admission(sync.id(), jobId, epoch);
            if (!JooqPanConfigSyncRepository.ADMITTED.equals(admitted)) throw new Stop(admitted);
            List<DeviceSummaryRecord> members = devices.findMembersByClusterRef(sync.clusterRef());
            if (members.size() != 2 || members.stream().anyMatch(m -> !"palo_alto".equals(m.vendorHint()))
                    || !Set.of(members.get(0).deviceId(), members.get(1).deviceId()).equals(Set.of(sync.sourceRef(), sync.targetRef())))
                throw new Stop("MEMBER_SET_CHANGED");
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED");
            var readGate = gate(STATE, ActionClass.CLASS_0_READ, READ_GATE);
            var writeGate = gate(SYNC, ActionClass.CLASS_3_CONFIGURATION_WRITE, GateRow.PAN_CONFIG_SYNC_GATE);
            DeviceSummaryRecord sourceMember = members.stream().filter(m -> m.deviceId().equals(sync.sourceRef())).findFirst().orElseThrow();
            DeviceSummaryRecord targetMember = members.stream().filter(m -> m.deviceId().equals(sync.targetRef())).findFirst().orElseThrow();
            Peer source = peer(sourceMember), target = peer(targetMember);
            // Design §3.3: the fresh HA-state read of both members, immediately before the write.
            PanFailoverChecks.State a = read(jobId, epoch, 0, source, readGate);
            PanFailoverChecks.State b = read(jobId, epoch, 1, target, readGate);
            read = true;
            String roles = PanFailoverChecks.roles(a, b, "active", "passive");
            if ("UNKNOWN".equals(roles)) throw new Stop("HA_STATE_UNKNOWN");
            if (!"PASS".equals(roles)) throw new Stop("ROLES_CHANGED");
            String configSync = PanFailoverChecks.sync(a, b);
            if ("PASS".equals(configSync)) throw new Stop("CONFIG_ALREADY_SYNCHRONIZED");
            if (!"FAIL".equals(configSync)) throw new Stop("CONFIG_SYNC_STATE_UNKNOWN");
            if ("synchronization in progress".equals(a.runningSync()) || "synchronization in progress".equals(b.runningSync()))
                throw new Stop("CONFIG_SYNC_IN_PROGRESS");
            if (!mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED);
            var prepared = store.prepareSend(sync.id(), jobId, epoch, 2, source.id(), writeGate.gateId(),
                writeGate.actionClass().id());
            if (!JooqPanConfigSyncRepository.ADMITTED.equals(prepared.code())) throw new Stop(prepared.code());
            // The switch, once more, immediately before the transport: a refusal here never reached the device.
            if (!mutationSwitch.enabled()) {
                store.recordReply(sync.id(), jobId, epoch, prepared.attemptId(), null, "NOT_SENT");
                throw new Stop(JooqPanConfigSyncRepository.MUTATION_DISABLED_BEFORE_SEND);
            }
            write = new Write(source, writeGate, prepared.attemptId());
        } catch (Stop stopped) {
            endBeforeSend(sync.id(), jobId, epoch, stopped.code, read);
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            endBeforeSend(sync.id(), jobId, epoch, "INTERRUPTED", read);
            return;
        } catch (RuntimeException unexpected) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unexpected);
            endBeforeSend(sync.id(), jobId, epoch, "PRECHECK_UNAVAILABLE", read);
            return;
        }
        // Argon P0-3: from here on the write may have left neXus. Nothing below is inside the pre-send handling, and
        // no failure below can replace the write's outcome with a pre-send code.
        afterSend(sync.id(), jobId, epoch, write);
    }

    private record Write(Peer source, GateResolution.Known gate, String attemptId) {}

    /** A stop before the write: the record and the job end with the code; readiness re-runs only after a fresh read. */
    private void endBeforeSend(String syncId, String jobId, long epoch, String code, boolean read) {
        boolean ended = false;
        try { ended = store.finish(syncId, jobId, epoch, code); }
        catch (RuntimeException unavailable) { log("finish", unavailable); }
        if (!ended) failJob(jobId, epoch, JobState.FAILED, code);
        if (read && !JooqPanConfigSyncRepository.NOT_SENT.contains(code)) followUp(syncId);
    }

    /**
     * The single send and everything after it. The reply is recorded as soon as it is known; the record and the job
     * end with it; then readiness re-runs. A cancel request, a lost lease or a failing step here never hides or
     * rewrites the recorded reply (Argon P0-2): the repository binds these writes to the job's epoch only, and the V178
     * job-end trigger closes a record from its recorded reply when the job ends some other way.
     */
    private void afterSend(String syncId, String jobId, long epoch, Write write) {
        String status = null;
        boolean notSent = false;
        try {
            var result = transport.xmlApiCall(write.source().target(), new XmlApiSpec("POST", "op", "", "direct_firewall",
                Map.of("cmd", SYNC), Map.of("X-PAN-KEY", write.source().key())), Duration.ofSeconds(write.gate().timeoutS()));
            status = deviceStatus(result);
        } catch (com.securityexpert.nexus.ui2.worker.JobCancellationScope.Cancelled
                | com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.AdmissionWait
                | com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.LeaseLost preContact) {
            // Argon P1-3: PanXmlApiTransport raises these before the request is sent (the cancellation check and the
            // endpoint lease come first); a lease lost after the send surfaces as a failed reply, which stays uncertain.
            notSent = true;
        } catch (RuntimeException uncertain) {
            status = null;
        }
        String outcome = notSent ? JooqPanConfigSyncRepository.NOT_SENT_BEFORE_CONTACT
            : status == null ? JooqPanConfigSyncRepository.OUTCOME_UNKNOWN
            : "success".equals(status) ? JooqPanConfigSyncRepository.REQUESTED : JooqPanConfigSyncRepository.DEVICE_REPORTED_ERROR;
        try {
            store.recordReply(syncId, jobId, epoch, write.attemptId(), status,
                notSent ? "NOT_SENT" : status == null ? "OUTCOME_UNKNOWN" : "success".equals(status) ? "MATCHED" : "FAILED");
        } catch (RuntimeException unavailable) { log("reply", unavailable); }
        if (!notSent) {
            try { pause.sleep(settle); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        // One retry for a database failure; false means the record or the job had already ended (nothing to retry).
        Boolean ended = null;
        for (int attempt = 0; attempt < 2 && ended == null; attempt++) {
            try { ended = store.finish(syncId, jobId, epoch, outcome); }
            catch (RuntimeException unavailable) { log("finish", unavailable); }
        }
        if (!Boolean.TRUE.equals(ended)) failJob(jobId, epoch, notSent || JooqPanConfigSyncRepository.DEVICE_REPORTED_ERROR.equals(outcome) ? JobState.FAILED
            : JooqPanConfigSyncRepository.REQUESTED.equals(outcome) ? JobState.COMPLETED : JobState.OUTCOME_UNKNOWN, outcome);
        if (!notSent) followUp(syncId);
    }

    /** The job alone, fenced by its epoch, when its record could not be ended with it (never left EXECUTING). */
    private void failJob(String jobId, long epoch, JobState state, String reason) {
        try { leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "pan_config_sync_end", reason); }
        catch (RuntimeException unavailable) { log("job", unavailable); }
    }

    /** The readiness re-run (design §4); a failure here is kept on the record as its follow-up refusal, never its outcome. */
    private void followUp(String syncId) {
        try { store.followUp(syncId); }
        catch (RuntimeException unavailable) {
            log("follow-up", unavailable);
            try { store.followUpFailed(syncId, "FOLLOW_UP_UNAVAILABLE"); }
            catch (RuntimeException again) { log("follow-up", again); }
        }
    }

    private static void log(String step, RuntimeException failure) {
        System.getLogger(PanConfigSyncJobExecutor.class.getName()).log(System.Logger.Level.WARNING,
            "[PAN_CONFIG_SYNC] " + step + " not recorded: " + failure.getClass().getSimpleName());
    }

    /** Design §4: the root status of an HTTP 200 reply of bounded size; anything else is unknown. */
    static String deviceStatus(XmlApiResult result) {
        if (!(result instanceof XmlApiResult.Completed completed) || completed.httpStatus() != 200
                || completed.body() == null || completed.body().length() > MAX_BODY) return null;
        var matcher = STATUS.matcher(completed.body());
        return matcher.find() ? matcher.group(1) : null;
    }

    private GateResolution.Known gate(String command, ActionClass actionClass, String expected) {
        if (actionClass != ActionClass.CLASS_0_READ && !mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED);
        GateResolution resolved;
        try {
            resolved = GateResolver.resolve(new CanonicalCommandKey("palo_alto", "pan_firewall", "not_applicable",
                "PAN_XML_API", command), Optional.of(actionClass), gates);
        } catch (RuntimeException invalid) {
            throw new Stop("COMMAND_GATE_UNAVAILABLE");
        }
        if (!(resolved instanceof GateResolution.Known known) || !expected.equals(known.gateId()))
            throw new Stop("COMMAND_GATE_UNAVAILABLE");
        return known;
    }

    private Peer peer(DeviceSummaryRecord summary) {
        var device = devices.find(summary.deviceId()).filter(d -> d.permitsReadCollection())
            .orElseThrow(() -> new Stop("DEVICE_NOT_ELIGIBLE"));
        var endpoint = devices.findEndpointByDeviceId(summary.deviceId())
            .filter(e -> summary.deviceId().equals(e.deviceId()) && "pan_xml_api".equalsIgnoreCase(e.transportKind()))
            .orElseThrow(() -> new Stop("ENDPOINT_MISSING"));
        String identity = devices.findConfirmFacts(summary.deviceId())
            .flatMap(f -> f.recordedIdentityPrimary()).filter(value -> !value.isBlank())
            .orElseThrow(() -> new Stop("IDENTITY_NOT_VERIFIED"));
        var credential = credentials.resolve(device.credentialReferenceId());
        var target = new ApiTarget(endpoint.endpointId(), endpoint.addressRef());
        var result = transport.xmlApiCall(target, new XmlApiSpec("POST", "keygen", "", "",
            Map.of("user", credential.username(), "password", new String(credential.password())), Map.of()), Duration.ofSeconds(30));
        // Argon P2-2: a bounded body whose root element is <response status="success">, then the key.
        String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 && c.body() != null
            && c.body().length() <= MAX_BODY ? c.body() : "";
        var match = KEY.matcher(body);
        if (!"success".equals(deviceStatus(result)) || !match.find()) throw new Stop("AUTH_UNAVAILABLE");
        return new Peer(summary.deviceId(), target, match.group(1), identity);
    }

    /** One gated HA-state read with its pre-contact attempt; the member's own serial must equal its recorded identity. */
    private PanFailoverChecks.State read(String jobId, long epoch, int index, Peer peer, GateResolution.Known gate)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String attempt = attempts.insertPreContact(jobId, epoch, index, gate.gateId(), gate.actionClass().id(), 1);
        if (attempt == null) throw new Stop("PRE_CONTACT_RECORD_FAILED");
        if (!attempts.markBoundaryCrossed(attempt, epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN");
        var result = transport.xmlApiCall(peer.target(), new XmlApiSpec("POST", "op", "", "direct_firewall",
            Map.of("cmd", STATE), Map.of("X-PAN-KEY", peer.key())), Duration.ofSeconds(gate.timeoutS()));
        if (!(result instanceof XmlApiResult.Completed completed) || !"success".equals(deviceStatus(result))) {
            attempts.writeOutcome(attempt, epoch, "FAILED", "COMMAND_UNAVAILABLE", false, null, null, null);
            throw new Stop("COMMAND_UNAVAILABLE");
        }
        if (!attempts.writeOutcome(attempt, epoch, "MATCHED", null, true, null, null, null))
            throw new Stop("ATTEMPT_RECORD_FAILED");
        var state = PanFailoverChecks.parse(completed.body());
        if (!peer.identity().equals(state.localSerial())) throw new Stop("IDENTITY_NOT_VERIFIED");
        return state;
    }
}
