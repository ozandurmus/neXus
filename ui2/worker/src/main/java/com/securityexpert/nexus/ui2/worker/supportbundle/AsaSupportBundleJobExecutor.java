package com.securityexpert.nexus.ui2.worker.supportbundle;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.supportbundle.AsaSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.AsaSupportBundle.Cmd;
import com.securityexpert.nexus.ui2.jobs.supportbundle.SupportBundleKind;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.SupportBundleRunStore;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * Support bundle collection, Cisco ASA (step S4; design sections 5.8, 5.9; PO approval 2026-11-15, rows A1-A4): the
 * {@code asa_support_bundle} job on one ASA in privileged EXEC. <b>No {@code terminal pager 0} is ever sent</b> (PO Q12):
 * the {@code <--- More --->} prompt is answered with a space in session, so the console setting of the device is not
 * touched. A5-A7 (the disk0: fallback) are not approved and not built.
 *
 * <ol>
 *   <li>A1 {@code show cpu usage}, once. The learned prompt tells the privilege (a trailing {@code >} is not privileged)
 *       and whether the device runs multiple contexts (the context name follows a slash in the prompt).</li>
 *   <li>A2 {@code changeto system}, once, on multiple contexts only; the prompt is learned again.</li>
 *   <li>{@code asa_crashinfo}: A3 streamed into the vault (64 MiB bound).</li>
 *   <li>{@code asa_show_tech}: refused when the CPU is above 80% or unknown; otherwise A4 streamed (256 MiB bound).</li>
 * </ol>
 * Every line is redacted before it is written; a capture cut short with at least 4 KiB stored is PARTIAL.
 */
public final class AsaSupportBundleJobExecutor extends SshBundleExecutor {
    static final String ENTRY_CRASHINFO = "items/asa_crashinfo/crashinfo.txt";
    static final String ENTRY_SHOW_TECH = "items/asa_show_tech/show-tech-support.txt";

    private boolean prechecked;
    private boolean privileged;
    private Integer cpu;

    public AsaSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint) {
        this(leases, attempts, devices, ssh, gates, store, artefacts, fingerprint, Clock.systemUTC());
    }

    AsaSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint, Clock clock) {
        super(leases, attempts, devices, ssh, gates, store, artefacts, fingerprint, clock);
    }

    @Override String vendor() { return AsaSupportBundle.VENDOR; }

    @Override boolean targetAllowed(DeviceRecord device, Optional<EndpointRecord> endpoint) {
        return "gateway".equals(device.role()) && endpoint.isPresent() && "ssh_exec".equals(endpoint.get().transportKind());
    }

    @Override boolean gatesKnown(DeviceRecord device) { return AsaSupportBundle.allGatesKnown(gates); }

    @Override
    String item(Run run, ItemRow item, SupportBundleKind kind, RequestRow request) throws IOException {
        precheck(run);
        if (!privileged) {
            store.finishItem(item.id(), "REFUSED_PRECHECK", "PRIVILEGE_REQUIRED", "the login is not in privileged EXEC",
                    List.copyOf(run.gateIds));
            return "REFUSED_PRECHECK";
        }
        return switch (kind) {
            case ASA_CRASHINFO -> capture(run, item, gate(Cmd.SHOW_CRASHINFO), Cmd.SHOW_CRASHINFO.command(),
                    AsaSupportBundle.PAGER_MARKER, AsaSupportBundle.CRASHINFO_MAX_BYTES, AsaSupportBundle.PARTIAL_MIN_BYTES,
                    ENTRY_CRASHINFO);
            case ASA_SHOW_TECH -> {
                if (cpu == null || cpu > AsaSupportBundle.CPU_LIMIT_PERCENT) {
                    store.finishItem(item.id(), "REFUSED_PRECHECK", cpu == null ? "PRECHECK_UNKNOWN" : "CPU_OVER_LIMIT",
                            cpu == null ? "CPU unknown" : "CPU " + cpu + "% exceeds " + AsaSupportBundle.CPU_LIMIT_PERCENT + "% limit",
                            List.copyOf(run.gateIds));
                    yield "REFUSED_PRECHECK";
                }
                yield capture(run, item, gate(Cmd.SHOW_TECH_SUPPORT), Cmd.SHOW_TECH_SUPPORT.command(),
                        AsaSupportBundle.PAGER_MARKER, AsaSupportBundle.SHOW_TECH_MAX_BYTES, AsaSupportBundle.PARTIAL_MIN_BYTES,
                        ENTRY_SHOW_TECH);
            }
            default -> {
                store.finishItem(item.id(), "UNSUPPORTED", "KIND_NOT_AVAILABLE", null, List.of());
                yield "UNSUPPORTED";
            }
        };
    }

    /** A1, then A2 on multiple contexts; once per run. */
    private void precheck(Run run) {
        if (prechecked) return;
        prechecked = true;
        Read usage = shortRead(run, gate(Cmd.SHOW_CPU_USAGE), Cmd.SHOW_CPU_USAGE.command(),
                AsaSupportBundle.timeout(gate(Cmd.SHOW_CPU_USAGE)), AsaSupportBundle.COMMAND_MAX_CHARS);
        cpu = usage.answer() == Answer.OK ? cpuPercent(usage.text()) : null;
        run.precheck.put("asa_cpu_pct", cpu);
        String prompt = session.interactivePrompt().orElse(null);
        privileged = prompt != null && prompt.endsWith("#");
        run.precheck.put("asa_privileged", privileged);
        boolean multiple = multipleContext(prompt);
        run.precheck.put("asa_multiple_context", multiple);
        if (privileged && multiple) {
            Read moved = shortRead(run, gate(Cmd.CHANGETO_SYSTEM), Cmd.CHANGETO_SYSTEM.command(),
                    AsaSupportBundle.timeout(gate(Cmd.CHANGETO_SYSTEM)), AsaSupportBundle.COMMAND_MAX_CHARS);
            run.precheck.put("asa_changeto_system", moved.answer() == Answer.REFUSED ? "refused" : "done");
            if (moved.answer() != Answer.REFUSED) ssh.resyncPrompt(session, Duration.ofMillis(500));
        }
    }

    private static final java.util.Set<String> FAILOVER_PROMPT_WORDS = java.util.Set.of("pri", "sec", "act", "stby");

    /**
     * Whether the learned prompt names a security context ({@code host/context#}): no extra command is needed to tell. The
     * failover words of {@code prompt hostname state priority} ({@code pri}, {@code sec}, {@code act}, {@code stby}) are not a
     * context name.
     */
    static boolean multipleContext(String prompt) {
        if (prompt == null) return false;
        String bare = prompt.strip();
        while (bare.endsWith("#") || bare.endsWith(">")) bare = bare.substring(0, bare.length() - 1);
        String[] parts = bare.split("/");
        return parts.length >= 2 && !parts[1].isBlank() && !FAILOVER_PROMPT_WORDS.contains(parts[1].toLowerCase(java.util.Locale.ROOT));
    }

    private GateResolution.Known gate(Cmd cmd) {
        return AsaSupportBundle.gate(cmd, gates).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
    }

    /** The highest of the 5 second, 1 minute and 5 minute figures of {@code show cpu usage}; null when none is read. */
    static Integer cpuPercent(String output) {
        return output == null ? null : maxPercent(output, "CPU utilization");
    }
}
