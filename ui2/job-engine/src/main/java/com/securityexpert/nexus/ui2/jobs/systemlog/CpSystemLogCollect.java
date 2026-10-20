package com.securityexpert.nexus.ui2.jobs.systemlog;

import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * System-log programme phase B, Check Point half (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §3.2, §8, §10.1; PO approval
 * 2026-10-17): the closed set of device reads a {@code cp_system_log_collect} job may issue against one Check Point Gaia
 * gateway, each resolved again against its live V160 gate row before it is sent. The phase A measurement rows (V149,
 * V151, V157) never authorize collection (§10.3).
 *
 * <ul>
 *   <li>C1 {@value #STAT_GATE} ({@code expert}): the family stat.</li>
 *   <li>C2: the SFTP ranged read, only through {@link CpSyslogRangedRead}.</li>
 *   <li>C3 {@value #LS_CRASH_GATE}, C6 {@value #LS_SA_GATE}, C7 {@value #LS_HCP_GATE}: listings. Their command text repeats
 *       an approved measurement row, so V160 keys them with the narrowed shell context {@value #COLLECT_SHELL}.</li>
 *   <li>C8 {@value #FETCH_GATE}: one bounded whole-file SFTP fetch of a name a listing of the same run printed
 *       ({@link #fetch}); gate {@code NOT_APPLICABLE} under C4 §2.3, but its row is still resolved so a withdrawn sign-off
 *       stops the fetch.</li>
 * </ul>
 *
 * C4 {@link CpSpikeLayoutReview#LS_SPIKE_GATE} and C5 {@link CpSpikeLayoutReview#LS_SPIKE_DIR_GATE} (Spike Detective
 * listings) and their C8 member fetches ({@link #fetchSpikeMember}) are issued only while
 * {@code system_log.spike_data_enabled} is on (§8.3, default off; fetch list as amended by the PO on 2026-10-20).
 */
public final class CpSystemLogCollect {
    private CpSystemLogCollect() {}

    public static final String CAPABILITY = "cp_system_log_collect";
    public static final String VENDOR = "check_point";
    public static final String PLATFORM_ROLE_SCOPE = "cp_gaia_gateway";
    /** C1 is a new command string: its row carries the plain {@code expert} context. */
    public static final String STAT_SHELL = "expert";
    /** C3/C4/C6/C7 repeat the approved V157 measurement strings: V160 narrows their key with this context. */
    public static final String COLLECT_SHELL = "expert_collect";

    public static final String STAT_GATE = "cp_syslog_collect_stat";
    public static final String LS_CRASH_GATE = "cp_syslog_collect_ls_crash";
    public static final String LS_SA_GATE = "cp_syslog_collect_ls_sa";
    public static final String LS_HCP_GATE = "cp_syslog_collect_ls_hcp";
    public static final String FETCH_GATE = "cp_syslog_collect_fetch";

    /** §10.1 C1, byte-identical to the V160 row. */
    public static final String STAT_COMMAND = "bash -lc 'date +%s; stat -c \"%n %i %s %Y\" /var/log/messages* "
            + "$FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* "
            + "/var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*'";
    /** §10.1 C3. */
    public static final String LS_CRASH_COMMAND = "bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'";
    /** §10.1 C6. */
    public static final String LS_SA_COMMAND = "bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'";
    /** §10.1 C7. */
    public static final String LS_HCP_COMMAND = "bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'";
    /** §10.1 C8 as V160 registers it (SFTP record row; the key is the contract's step description). */
    public static final String FETCH_KEY = "fetch one absolute path printed by C4/C5 (spike data), C6 (`sar<DD>`) or C7 "
            + "(HCP archive) in the same run; no other path";

    /** §10.1 "Max bytes" of each listing: the transport stops and the read is UNKNOWN past it. */
    public static final int STAT_MAX_BYTES = 64 * 1024;
    public static final int LS_CRASH_MAX_BYTES = 256 * 1024;
    public static final int LS_SA_MAX_BYTES = 64 * 1024;
    public static final int LS_HCP_MAX_BYTES = 64 * 1024;

    /** The exec reads in run order (§3.2) with their gate ids and shell contexts. */
    public enum Exec {
        STAT(STAT_GATE, STAT_SHELL, STAT_COMMAND, STAT_MAX_BYTES),
        LS_CRASH(LS_CRASH_GATE, COLLECT_SHELL, LS_CRASH_COMMAND, LS_CRASH_MAX_BYTES),
        LS_SA(LS_SA_GATE, COLLECT_SHELL, LS_SA_COMMAND, LS_SA_MAX_BYTES),
        LS_HCP(LS_HCP_GATE, COLLECT_SHELL, LS_HCP_COMMAND, LS_HCP_MAX_BYTES);

        private final String gateId;
        private final String shellContext;
        private final String command;
        private final int maxBytes;

        Exec(String gateId, String shellContext, String command, int maxBytes) {
            this.gateId = gateId; this.shellContext = shellContext; this.command = command; this.maxBytes = maxBytes;
        }

        public String gateId() { return gateId; }
        public String shellContext() { return shellContext; }
        public String command() { return command; }
        public int maxBytes() { return maxBytes; }

        public CanonicalCommandKey key() {
            return new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, shellContext, "SSH_EXEC", command);
        }
    }

    // ---------------------------------------------------------------- §8.3 Spike Detective data (PO amendment 2026-10-20)

    /** The {@code system_log_run} / {@code system_log_file} source key of the spike data. */
    public static final String SPIKE_SOURCE = "cp_spike_data";
    /** The parent directory C4 lists; C5 lists one data directory under it. */
    public static final String SPIKE_PARENT = "/var/log/spike_detective";
    /**
     * §8.3 fetch list as amended by the PO on 2026-10-20 (review record SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md §4:
     * the CPU sample is {@code perf_X_NNN.log}, its middle word is not {@code cpu}).
     */
    public static final Pattern SPIKE_PERF_MEMBER = Pattern.compile("^perf_[a-z]+_[0-9]+\\.log$");
    public static final Pattern SPIKE_PROFILING_MEMBER = Pattern.compile("^profiling_summary\\.elg$");
    /** §3.4 / §10.1 C8: spike data per directory (the sum of the fetched members). Over it = TOO_LARGE, not fetched. */
    public static final long SPIKE_DIRECTORY_MAX_BYTES = 16L * 1024 * 1024;
    /** §3.4: new, settled data directories per run; the rest is BACKLOG. */
    public static final int SPIKE_DIRECTORIES_PER_RUN = 16;
    /** §10.1 C8: 120 s for a spike member (the shorter of this and the gate row's timeout). */
    public static final int SPIKE_FETCH_TIMEOUT_S = 120;

    /** True when a data-directory member is on the amended §8.3 fetch list (by name; the caller checks it is a file). */
    public static boolean spikeMemberFetchable(String memberName) {
        return memberName != null && (SPIKE_PERF_MEMBER.matcher(memberName).matches()
                || SPIKE_PROFILING_MEMBER.matcher(memberName).matches());
    }

    /**
     * C8 for one spike data member: {@code <SPIKE_PARENT>/<directory>/<member>}. The directory must match the §8.3 safe
     * form and be one C4 printed in this run ({@code listedDirectories}); the member must be on the amended fetch list
     * and one C5 printed for that directory in this run ({@code listedMembers}); its listed size must be within
     * {@code boundBytes} (what is left of the 16 MiB per-directory bound). One byte more than the bound is asked for, so
     * a member that grew past it is detected, never silently cut. Timeout: the shorter of the gate row's and 120 s.
     *
     * @return the transport result, or a refusal ({@code REFUSED} with a {@link FetchRefusal} code) before any I/O
     */
    public static RangedFetchResult fetchSpikeMember(DeviceTransport transport, TransportSession session,
            GateRegistryPort gates, Set<String> listedDirectories, String directory, Set<String> listedMembers,
            String member, long listedSize, long boundBytes, OutputStream sink) {
        Objects.requireNonNull(transport, "transport");
        Optional<GateResolution.Known> gate = fetchGate(gates);
        if (gate.isEmpty()) return RangedFetchResult.refused(0, FetchRefusal.GATE_NOT_KNOWN.name());
        if (!CpSpikeLayoutReview.safeDirectoryName(directory) || !spikeMemberFetchable(member))
            return RangedFetchResult.refused(0, FetchRefusal.NAME_NOT_ALLOWED.name());
        if (listedDirectories == null || !listedDirectories.contains(directory) || listedMembers == null
                || !listedMembers.contains(member))
            return RangedFetchResult.refused(0, FetchRefusal.NAME_NOT_LISTED.name());
        long bound = Math.min(boundBytes, SPIKE_DIRECTORY_MAX_BYTES);
        if (listedSize < 0 || bound < 0 || listedSize > bound)
            return RangedFetchResult.refused(0, FetchRefusal.SIZE_ABOVE_BOUND.name());
        Duration timeout = Duration.ofSeconds(Math.min(gate.get().timeoutS(), SPIKE_FETCH_TIMEOUT_S));
        return transport.fetchBounded(session, new RangedFetchSpec(SPIKE_PARENT + "/" + directory + "/" + member, 0, bound + 1),
                timeout, sink, SPIKE_SOURCE);
    }

    /** The whole-file kinds of §8.1/§8.2 (spike data is fetched per member, {@link #fetchSpikeMember}). */
    public enum FileKind {
        /** §8.2: yesterday's {@code sar<DD>} text; bound 32 MiB (PROVISIONAL), timeout 120 s. */
        SAR_DAILY("cp_sar_daily", "/var/log/sa", Pattern.compile("sar(0[1-9]|[12][0-9]|3[01])"), 32L * 1024 * 1024, 120),
        /** §8.1: the newest HCP archive; bound 16 MiB (RESOLVED), timeout 300 s. */
        HCP_REPORT("cp_hcp_report", "/var/log/hcp/last", Pattern.compile("[A-Za-z0-9._-]+\\.tar\\.gz"), 16L * 1024 * 1024, 300);

        private final String sourceKey;
        private final String directory;
        private final Pattern name;
        private final long maxBytes;
        private final int timeoutS;

        FileKind(String sourceKey, String directory, Pattern name, long maxBytes, int timeoutS) {
            this.sourceKey = sourceKey; this.directory = directory; this.name = name; this.maxBytes = maxBytes;
            this.timeoutS = timeoutS;
        }

        public String sourceKey() { return sourceKey; }
        public String directory() { return directory; }
        public long maxBytes() { return maxBytes; }
        public int timeoutS() { return timeoutS; }
        public boolean nameAllowed(String fileName) { return fileName != null && name.matcher(fileName).matches(); }
    }

    /** Why a C8 fetch was refused before any I/O. */
    public enum FetchRefusal { GATE_NOT_KNOWN, NAME_NOT_ALLOWED, NAME_NOT_LISTED, SIZE_ABOVE_BOUND }

    /** The signed-off read gate of one exec read; empty when it is missing, unsigned, not a read or ambiguous. */
    public static Optional<GateResolution.Known> gate(Exec exec, GateRegistryPort registry) {
        return resolve(exec.key(), exec.gateId(), registry);
    }

    /** The C8 record row; resolved like the others so a withdrawn sign-off stops the fetch. */
    public static Optional<GateResolution.Known> fetchGate(GateRegistryPort registry) {
        return resolve(new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, "not_applicable", "SFTP_GET", FETCH_KEY),
                FETCH_GATE, registry);
    }

    private static Optional<GateResolution.Known> resolve(CanonicalCommandKey key, String expectedGate,
            GateRegistryPort registry) {
        if (registry == null) return Optional.empty();
        try {
            var resolved = GateResolver.resolve(key, Optional.of(ActionClass.CLASS_0_READ), registry);
            return resolved instanceof GateResolution.Known known && expectedGate.equals(known.gateId())
                    && known.actionClass() == ActionClass.CLASS_0_READ && known.timeoutS() > 0
                    ? Optional.of(known) : Optional.empty();
        } catch (RuntimeException invalidGate) {
            return Optional.empty();
        }
    }

    /**
     * C8: one bounded whole-file fetch of {@code fileName} in the kind's fixed directory. {@code listedNames} are the
     * names the kind's listing printed in this run; the file must be one of them and match the kind's safe name form,
     * and the listed size must be within the kind's bound (a larger file is {@code TOO_LARGE} and never fetched). The
     * timeout is the shorter of the gate row's and the kind's (§10.1 C8: 120 s sar, 300 s HCP). The transport asks for
     * one byte more than the bound, so a file that grew past it after the listing is detected, never silently cut.
     *
     * @return the transport result, or a refusal ({@code REFUSED} with a {@link FetchRefusal} code) before any I/O
     */
    public static RangedFetchResult fetch(DeviceTransport transport, TransportSession session, GateRegistryPort gates,
            FileKind kind, Set<String> listedNames, String fileName, long listedSize, OutputStream sink) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(kind, "kind");
        Optional<GateResolution.Known> gate = fetchGate(gates);
        if (gate.isEmpty()) return RangedFetchResult.refused(0, FetchRefusal.GATE_NOT_KNOWN.name());
        if (!kind.nameAllowed(fileName)) return RangedFetchResult.refused(0, FetchRefusal.NAME_NOT_ALLOWED.name());
        if (listedNames == null || !listedNames.contains(fileName))
            return RangedFetchResult.refused(0, FetchRefusal.NAME_NOT_LISTED.name());
        if (listedSize < 0 || listedSize > kind.maxBytes())
            return RangedFetchResult.refused(0, FetchRefusal.SIZE_ABOVE_BOUND.name());
        Duration timeout = Duration.ofSeconds(Math.min(gate.get().timeoutS(), kind.timeoutS()));
        return transport.fetchBounded(session, new RangedFetchSpec(kind.directory() + "/" + fileName, 0, kind.maxBytes() + 1),
                timeout, sink, kind.sourceKey());
    }

    /** The commands of {@link #capability}: every exec read plus the two SFTP steps (C2 ranged, C8 whole file). */
    public static final Map<String, Exec> EXEC_BY_GATE = Map.of(STAT_GATE, Exec.STAT, LS_CRASH_GATE, Exec.LS_CRASH,
            LS_SA_GATE, Exec.LS_SA, LS_HCP_GATE, Exec.LS_HCP);

    public static Capability capability(GateRegistryPort registry) {
        List<CapabilityStep> steps = new ArrayList<>();
        steps.add(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(),
                Optional.empty()));
        CapabilityStep sftp = new CapabilityStep(StepKind.SFTP_GET, "not_applicable", null, true, Optional.empty(),
                Optional.empty(), Optional.empty());
        steps.add(new CapabilityStep(StepKind.EXEC, Exec.STAT.shellContext(), Exec.STAT.command(), false, Optional.empty(),
                Optional.empty(), Optional.empty()));
        steps.add(sftp);
        steps.add(new CapabilityStep(StepKind.EXEC, Exec.LS_CRASH.shellContext(), Exec.LS_CRASH.command(), false,
                Optional.empty(), Optional.empty(), Optional.empty()));
        // §3.2 step 4 (spike data, only while spike_data_enabled is on): C4, then C5 (the template) per directory.
        steps.add(new CapabilityStep(StepKind.EXEC, CpSpikeLayoutReview.LS_SPIKE_SHELL, CpSpikeLayoutReview.LS_SPIKE_COMMAND,
                false, Optional.empty(), Optional.empty(), Optional.empty()));
        steps.add(new CapabilityStep(StepKind.EXEC, CpSpikeLayoutReview.LS_SPIKE_DIR_SHELL,
                CpSpikeLayoutReview.LS_SPIKE_DIR_TEMPLATE, false, Optional.empty(), Optional.empty(), Optional.empty()));
        for (Exec exec : List.of(Exec.LS_SA, Exec.LS_HCP))
            steps.add(new CapabilityStep(StepKind.EXEC, exec.shellContext(), exec.command(), false, Optional.empty(),
                    Optional.empty(), Optional.empty()));
        steps.add(sftp);
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(),
                Optional.empty(), Optional.empty());
        return new CapabilityRegistryLoader(registry).load(new CapabilitySpec(CAPABILITY, VENDOR, PLATFORM_ROLE_SCOPE,
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, steps, List.of(disconnect), "2026-10-17", List.of(), false));
    }
}
