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
    private static final Pattern SUCCESS = Pattern.compile("status\\s*=\\s*[\"']success[\"']");
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
                throw new Stop("MUTATION_DISABLED_BEFORE_SEND");
            }
            String status = send(source, writeGate);
            store.recordReply(sync.id(), jobId, epoch, prepared.attemptId(), status,
                status == null ? "OUTCOME_UNKNOWN" : "success".equals(status) ? "MATCHED" : "FAILED");
            try { pause.sleep(settle); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            end(sync.id(), jobId, epoch, status == null ? JooqPanConfigSyncRepository.OUTCOME_UNKNOWN
                : "success".equals(status) ? JooqPanConfigSyncRepository.REQUESTED : JooqPanConfigSyncRepository.DEVICE_REPORTED_ERROR,
                true);
        } catch (Stop stopped) {
            end(sync.id(), jobId, epoch, stopped.code, read);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            end(sync.id(), jobId, epoch, "INTERRUPTED", read);
        } catch (RuntimeException unexpected) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unexpected);
            end(sync.id(), jobId, epoch, "PRECHECK_UNAVAILABLE", read);
        }
    }

    /**
     * Ends the record and its job together; when the record could not be ended (already terminal, or its job lost the
     * lease) the job alone is failed, fenced by its epoch, so it never stays EXECUTING.
     */
    private void end(String syncId, String jobId, long epoch, String outcome, boolean followUp) {
        if (!store.finish(syncId, jobId, epoch, outcome, followUp))
            leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "pan_config_sync_end", outcome);
    }

    /** The single write to the active member; returns {@code success}, {@code error} or null (outcome unknown). */
    private String send(Peer source, GateResolution.Known gate) {
        try {
            var result = transport.xmlApiCall(source.target(), new XmlApiSpec("POST", "op", "", "direct_firewall",
                Map.of("cmd", SYNC), Map.of("X-PAN-KEY", source.key())), Duration.ofSeconds(gate.timeoutS()));
            return deviceStatus(result);
        } catch (RuntimeException uncertain) {
            return null;
        }
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
        String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 && c.body() != null ? c.body() : "";
        var match = KEY.matcher(body);
        if (!SUCCESS.matcher(body).find() || !match.find()) throw new Stop("AUTH_UNAVAILABLE");
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
        if (!(result instanceof XmlApiResult.Completed completed) || completed.httpStatus() != 200
                || completed.body() == null || completed.body().length() > MAX_BODY
                || !SUCCESS.matcher(completed.body()).find()) {
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
