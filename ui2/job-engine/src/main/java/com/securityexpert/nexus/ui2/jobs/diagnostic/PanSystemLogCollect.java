package com.securityexpert.nexus.ui2.jobs.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * System-log programme phase B, Palo Alto half (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §3.3, §6, §10.2; PO approval
 * 2026-10-17): the closed set of five read-only PAN-OS XML API requests a {@code pan_system_log_collect} job may issue
 * against an enrolled Palo Alto firewall (never Panorama), and the bounds of §3.4/§6. Each request is resolved again
 * against its live gate (rows P1-P5 of migration V160) before it is sent; the V151 measurement rows are never used.
 *
 * <p>The command strings are the canonical keys of the gate rows, placeholders included: the executor substitutes
 * only {@code <n>} (digits), {@code <ts>} (the stored cursor, shape-checked) and {@code <id>} (digits the enqueue
 * answered).</p>
 */
public final class PanSystemLogCollect {
    private PanSystemLogCollect() {}

    public static final String CAPABILITY = "pan_system_log_collect";

    /** P1/P2: the first run only (no cursor): the newest {@code <n>} entries, newest first. */
    public static final String SYSTEM_SEED = "type=log&log-type=system&nlogs=<n>";
    public static final String CONFIG_SEED = "type=log&log-type=config&nlogs=<n>";
    /** P3/P4: one forward page from the stored cursor, oldest first. */
    public static final String SYSTEM_ENQUEUE = "type=log&log-type=system&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')";
    public static final String CONFIG_ENQUEUE = "type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')";
    /** P5: {@code <id>} is the digits-only log job id the enqueue answered. */
    public static final String JOB_GET = "type=log&action=get&job-id=<id>";

    public static final String SYSTEM_SEED_GATE = "pan_syslog_collect_system_seed";
    public static final String CONFIG_SEED_GATE = "pan_syslog_collect_config_seed";
    public static final String SYSTEM_ENQUEUE_GATE = "pan_syslog_collect_system_enqueue";
    public static final String CONFIG_ENQUEUE_GATE = "pan_syslog_collect_config_enqueue";
    public static final String JOB_GET_GATE = "pan_syslog_collect_job_get";
    public static final List<String> COMMANDS = List.of(SYSTEM_SEED, CONFIG_SEED, SYSTEM_ENQUEUE, CONFIG_ENQUEUE, JOB_GET);
    public static final int TIMEOUT_S = 30;

    /** P5 repeats the approved V151 measurement string, so its row is keyed with this narrowed shell context (V160). */
    public static final String JOB_GET_SHELL_CONTEXT = "not_applicable_collect";

    /** §6: page size {@code <n>}; the PAN maximum of 5000 stays unused. */
    public static final int PAGE_SIZE = 2000;
    /** §6: at most 4 pages per log type per run; more data than that is {@code BACKLOG}. */
    public static final int MAX_PAGES = 4;
    /** §6 seed sizes: 2000 system entries, 100 config entries. */
    public static final int SYSTEM_SEED_ENTRIES = 2000;
    public static final int CONFIG_SEED_ENTRIES = 100;
    /** §6 / P5: 2 s between {@code action=get} checks, at most 30 checks per enqueued job. */
    public static final long POLL_INTERVAL_SECONDS = 2;
    public static final int MAX_POLLS = 30;
    /** §6: the 8 MiB result cap, kept (as in V151). */
    public static final int MAX_RESULT_CHARS = 8 * 1024 * 1024;

    private static final Map<String, String> GATE_ID = Map.of(SYSTEM_SEED, SYSTEM_SEED_GATE, CONFIG_SEED, CONFIG_SEED_GATE,
            SYSTEM_ENQUEUE, SYSTEM_ENQUEUE_GATE, CONFIG_ENQUEUE, CONFIG_ENQUEUE_GATE, JOB_GET, JOB_GET_GATE);

    private static String shellContext(String command) {
        return JOB_GET.equals(command) ? JOB_GET_SHELL_CONTEXT : "not_applicable";
    }

    public static CanonicalCommandKey key(String command) {
        return new CanonicalCommandKey("palo_alto", "pan_firewall", shellContext(command), "PAN_XML_API", command);
    }

    /** The signed-off read gate for one of {@link #COMMANDS}; empty when it is missing, unsigned, not a read or ambiguous. */
    public static Optional<GateResolution.Known> gate(String command, GateRegistryPort registry) {
        String expected = GATE_ID.get(command);
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
            steps.add(new CapabilityStep(StepKind.XML_API_CALL, shellContext(command), command, false,
                    Optional.empty(), Optional.empty(), Optional.empty()));
        }
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        return new CapabilityRegistryLoader(registry).load(new CapabilitySpec(CAPABILITY, "palo_alto", "pan_firewall",
                TransportKind.PAN_XML_API, MaturityState.CAP_OFFLINE, steps, List.of(disconnect), "2026-10-17",
                List.of(), false));
    }
}
