package com.securityexpert.nexus.ui2.worker.supportbundle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.supportbundle.FgtSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.FgtSupportBundle.Cmd;
import com.securityexpert.nexus.ui2.jobs.supportbundle.SupportBundleKind;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.SupportBundleRunStore;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGatePlan;

/**
 * Support bundle collection, FortiGate (step S5; design sections 5.8, 5.9; PO approval 2026-11-15, rows F1-F5): the
 * {@code fgt_support_bundle} job on one FortiGate member over its own management address. {@code --More--} is answered with
 * a space in session; no console setting changes.
 *
 * <ol>
 *   <li><b>Defect guard, from stored data, before any contact</b> ({@code fgt_tac_report} only): FortiOS 7.0 and 7.2, 7.4
 *       below 7.4.10 and 7.6 below 7.6.6 crash the session when the configuration holds a DoS policy. An affected version
 *       with a DoS policy, or an affected version whose stored configuration cannot be read, refuses the report
 *       ({@code VENDOR_DEFECT_SESSION_CRASH}); the crash log still runs. The live version of F1 corroborates.</li>
 *   <li>F1 {@code get system status} (version, VDOM mode). On a multi-VDOM device F3 {@code config global} follows, once.
 *       When F1 is refused at the top level, {@code config global} is tried once instead.</li>
 *   <li>F2 {@code get system performance status}, for {@code fgt_tac_report} only: CPU above 80% or unknown refuses it.</li>
 *   <li>F4 {@code diagnose debug crashlog read} (32 MiB) and F5 {@code execute tac report} (256 MiB), streamed through the
 *       redactor into the vault; {@code end} closes the global context.</li>
 * </ol>
 */
public final class FgtSupportBundleJobExecutor extends SshBundleExecutor {
    static final String ENTRY_CRASHLOG = "items/fgt_crashlog/crashlog.txt";
    static final String ENTRY_TAC_REPORT = "items/fgt_tac_report/tac-report.txt";
    private static final Pattern CPU_IDLE = Pattern.compile("([0-9]{1,3})%\\s+idle", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOS_POLICY = Pattern.compile("(?m)^\\s*config\\s+firewall\\s+DoS-policy6?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final long STORED_CONFIG_MAX_LINES = 20_000_000L;

    private boolean prechecked, inGlobal, wantsTacReport;
    private String liveVersion;
    private Integer cpu;

    public FgtSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint) {
        this(leases, attempts, devices, ssh, gates, store, artefacts, fingerprint, Clock.systemUTC());
    }

    FgtSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint, Clock clock) {
        super(leases, attempts, devices, ssh, gates, store, artefacts, fingerprint, clock);
    }

    @Override String vendor() { return FgtSupportBundle.VENDOR; }

    @Override boolean targetAllowed(DeviceRecord device, Optional<EndpointRecord> endpoint) {
        return "gateway".equals(device.role()) && endpoint.isPresent() && "ssh_exec".equals(endpoint.get().transportKind());
    }

    @Override boolean gatesKnown(DeviceRecord device) { return FgtSupportBundle.allGatesKnown(gates); }

    @Override
    java.util.Map<UUID, String> beforeConnect(Run run, RequestRow request, List<ItemRow> items) {
        prechecked = false; inGlobal = false; liveVersion = null; cpu = null;
        wantsTacReport = items.stream().anyMatch(i -> SupportBundleKind.FGT_TAC_REPORT.id().equals(i.kind()));
        java.util.Map<UUID, String> answered = new java.util.LinkedHashMap<>();
        for (ItemRow item : items) {
            if (!SupportBundleKind.FGT_TAC_REPORT.id().equals(item.kind())) continue;
            if (numericVersion(run.version) == null) continue; // no stored version: the live read decides
            String refusal = defectRefusal(run, run.version);
            if (refusal != null) {
                store.finishItem(item.id(), "REFUSED_PRECHECK", "VENDOR_DEFECT_SESSION_CRASH", refusal, List.of());
                answered.put(item.id(), "REFUSED_PRECHECK");
            }
        }
        return answered;
    }

    @Override
    String item(Run run, ItemRow item, SupportBundleKind kind, RequestRow request) throws IOException {
        precheck(run);
        return switch (kind) {
            case FGT_CRASHLOG -> capture(run, item, gate(Cmd.CRASHLOG_READ), Cmd.CRASHLOG_READ.command(),
                    FgtSupportBundle.PAGER_MARKER, FgtSupportBundle.CRASHLOG_MAX_BYTES, FgtSupportBundle.PARTIAL_MIN_BYTES,
                    ENTRY_CRASHLOG);
            case FGT_TAC_REPORT -> {
                String refusal = defectRefusal(run, liveVersion != null ? liveVersion : run.version);
                if (refusal != null) {
                    store.finishItem(item.id(), "REFUSED_PRECHECK", "VENDOR_DEFECT_SESSION_CRASH", refusal,
                            List.copyOf(run.gateIds));
                    yield "REFUSED_PRECHECK";
                }
                if (cpu == null || cpu > FgtSupportBundle.CPU_LIMIT_PERCENT) {
                    store.finishItem(item.id(), "REFUSED_PRECHECK", cpu == null ? "PRECHECK_UNKNOWN" : "CPU_OVER_LIMIT",
                            cpu == null ? "CPU unknown" : "CPU " + cpu + "% exceeds " + FgtSupportBundle.CPU_LIMIT_PERCENT + "% limit",
                            List.copyOf(run.gateIds));
                    yield "REFUSED_PRECHECK";
                }
                yield capture(run, item, gate(Cmd.TAC_REPORT), Cmd.TAC_REPORT.command(), FgtSupportBundle.PAGER_MARKER,
                        FgtSupportBundle.TAC_REPORT_MAX_BYTES, FgtSupportBundle.PARTIAL_MIN_BYTES, ENTRY_TAC_REPORT);
            }
            default -> {
                store.finishItem(item.id(), "UNSUPPORTED", "KIND_NOT_AVAILABLE", null, List.of());
                yield "UNSUPPORTED";
            }
        };
    }

    /** F1, then F3 on a multi-VDOM device, then F2 when the TAC report is requested; once per run. */
    private void precheck(Run run) {
        if (prechecked) return;
        prechecked = true;
        Read status = shortRead(run, gate(Cmd.GET_SYSTEM_STATUS), Cmd.GET_SYSTEM_STATUS.command(),
                FgtSupportBundle.timeout(gate(Cmd.GET_SYSTEM_STATUS)), FgtSupportBundle.COMMAND_MAX_CHARS);
        boolean enterGlobal;
        if (status.answer() == Answer.OK) {
            var parsed = FortiGatePlan.parseStatus(status.text());
            liveVersion = parsed.version().orElse(null);
            enterGlobal = parsed.multiVdom();
            run.precheck.put("fgt_multi_vdom", parsed.multiVdom());
            run.precheck.put("fgt_version_read", liveVersion != null);
        } else {
            // Refused at the top level (measured on VDOM devices): config global decides, once.
            enterGlobal = true;
            run.precheck.put("fgt_status", "refused");
        }
        if (enterGlobal) {
            Read moved = shortRead(run, gate(Cmd.CONFIG_GLOBAL), Cmd.CONFIG_GLOBAL.command(),
                    FgtSupportBundle.timeout(gate(Cmd.CONFIG_GLOBAL)), FgtSupportBundle.COMMAND_MAX_CHARS);
            inGlobal = moved.answer() != Answer.REFUSED;
        }
        if (wantsTacReport) {
            Read perf = shortRead(run, gate(Cmd.PERFORMANCE_STATUS), Cmd.PERFORMANCE_STATUS.command(),
                    FgtSupportBundle.timeout(gate(Cmd.PERFORMANCE_STATUS)), FgtSupportBundle.COMMAND_MAX_CHARS);
            cpu = perf.answer() == Answer.OK ? cpuPercent(perf.text()) : null;
            run.precheck.put("fgt_cpu_pct", cpu);
        }
    }

    @Override
    void afterItems(Run run) {
        if (inGlobal && !sessionEnded) {
            shortRead(run, gate(Cmd.END), Cmd.END.command(), FgtSupportBundle.timeout(gate(Cmd.END)),
                    FgtSupportBundle.COMMAND_MAX_CHARS);
            inGlobal = false;
        }
    }

    private GateResolution.Known gate(Cmd cmd) {
        return FgtSupportBundle.gate(cmd, gates).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
    }

    // ------------------------------------------------------------------ the defect guard

    /**
     * The refusal text of {@code execute tac report} for this version, or null when it may run. An unknown version is
     * refused; an affected version needs a readable stored configuration without a DoS policy.
     */
    String defectRefusal(Run run, String version) {
        String numeric = numericVersion(version);
        if (numeric == null) return "FortiOS version unknown (VENDOR_DEFECT_UNCHECKABLE)";
        if (!FgtSupportBundle.versionAffectedByTacReportDefect(numeric)) return null;
        Optional<Boolean> dos = storedDosPolicy(run.deviceId);
        if (dos.isEmpty()) return "FortiOS " + numeric + " with no readable stored configuration (VENDOR_DEFECT_UNCHECKABLE)";
        return dos.get() ? "FortiOS " + numeric + " with DoS-policy refused (VENDOR_DEFECT_SESSION_CRASH)" : null;
    }

    static String numericVersion(String version) {
        if (version == null) return null;
        Matcher m = Pattern.compile("v?([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(version);
        return m.find() ? m.group(1) : null;
    }

    /** Whether the newest stored configuration holds a DoS policy; empty when none is stored or it cannot be read. */
    private Optional<Boolean> storedDosPolicy(String deviceId) {
        var config = store.latestFortiGateConfiguration(deviceId);
        if (config.isEmpty() || artefacts == null) return Optional.empty();
        try (InputStream in = artefacts.retrieve(new ArtefactRef(config.get().artefactRef()), config.get().wrappedDataKey(),
                config.get().gzip());
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            long seen = 0;
            while ((line = reader.readLine()) != null && seen++ < STORED_CONFIG_MAX_LINES) {
                if (DOS_POLICY.matcher(line).find()) return Optional.of(true);
            }
            return Optional.of(false);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** The busiest figure: 100 minus the lowest idle share of {@code get system performance status}; null when none is read. */
    static Integer cpuPercent(String output) {
        if (output == null) return null;
        Integer lowestIdle = null;
        Matcher m = CPU_IDLE.matcher(output);
        while (m.find()) {
            int idle = Integer.parseInt(m.group(1));
            if (idle <= 100) lowestIdle = lowestIdle == null ? idle : Math.min(lowestIdle, idle);
        }
        return lowestIdle == null ? null : 100 - lowestIdle;
    }
}
