package com.securityexpert.nexus.ui2.jobs.diagnostic;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * System-log programme phase A, M3/M4 (DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15):
 * the closed set of three read-only PAN-OS XML API requests a {@code pan_syslog_measure} job may issue against an
 * enrolled Palo Alto firewall (never Panorama). Each is resolved again against the live gate before it is sent.
 */
public final class PanSyslogMeasure {
    private PanSyslogMeasure() {}

    public static final String CAPABILITY = "pan_syslog_measure";
    public static final int REQUESTED_ENTRIES = 100;
    public static final String SYSTEM_ENQUEUE = "type=log&log-type=system&nlogs=100";
    public static final String CONFIG_ENQUEUE = "type=log&log-type=config&nlogs=100";
    /** {@code <id>} is the digits-only log job id the enqueue answered; nothing else is ever substituted. */
    public static final String JOB_GET = "type=log&action=get&job-id=<id>";
    public static final String SYSTEM_ENQUEUE_GATE = "pan_syslog_measure_system_enqueue";
    public static final String CONFIG_ENQUEUE_GATE = "pan_syslog_measure_config_enqueue";
    public static final String JOB_GET_GATE = "pan_syslog_measure_job_get";
    public static final List<String> COMMANDS = List.of(SYSTEM_ENQUEUE, CONFIG_ENQUEUE, JOB_GET);
    public static final int TIMEOUT_S = 30;

    public static CanonicalCommandKey key(String command) {
        return new CanonicalCommandKey("palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", command);
    }

    /** The signed-off read gate for one of {@link #COMMANDS}; empty when it is missing, unsigned, not a read or ambiguous. */
    public static Optional<GateResolution.Known> gate(String command, GateRegistryPort registry) {
        String expected = SYSTEM_ENQUEUE.equals(command) ? SYSTEM_ENQUEUE_GATE
                : CONFIG_ENQUEUE.equals(command) ? CONFIG_ENQUEUE_GATE : JOB_GET.equals(command) ? JOB_GET_GATE : null;
        if (expected == null) return Optional.empty();
        try {
            var resolved = GateResolver.resolve(key(command), Optional.of(ActionClass.CLASS_0_READ), registry);
            return resolved instanceof GateResolution.Known known && expected.equals(known.gateId())
                    && known.actionClass() == ActionClass.CLASS_0_READ && known.timeoutS() == TIMEOUT_S
                    ? Optional.of(known) : Optional.empty();
        } catch (RuntimeException invalidGate) {
            return Optional.empty();
        }
    }

    public static Capability capability(GateRegistryPort registry) {
        var steps = new java.util.ArrayList<CapabilityStep>();
        steps.add(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty()));
        for (String command : COMMANDS) {
            steps.add(new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", command, false,
                    Optional.empty(), Optional.empty(), Optional.empty()));
        }
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        return new CapabilityRegistryLoader(registry).load(new CapabilitySpec(CAPABILITY, "palo_alto", "pan_firewall",
                TransportKind.PAN_XML_API, MaturityState.CAP_OFFLINE, steps, List.of(disconnect), "2026-10-15",
                List.of(), false));
    }
}
