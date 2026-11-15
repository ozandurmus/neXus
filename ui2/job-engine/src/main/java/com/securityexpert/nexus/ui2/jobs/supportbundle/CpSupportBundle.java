package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Support bundle collection, Check Point light reads (step S2; PO approval 2026-11-15, rows C0-C7 of
 * SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6). Each exec read is resolved against its live V230 gate row
 * before it is sent. Every literal carries the {@code CP_ENV} prefix (no login shell). The cpdata_collector {@code --list} and
 * {@code --run} literals are approved gate rows only; nothing here executes them.
 */
public final class CpSupportBundle {
    private CpSupportBundle() {}

    public static final String CAPABILITY = "cp_support_bundle";
    public static final String VENDOR = "check_point";
    public static final String PLATFORM_ROLE_SCOPE = "cp_gaia_gateway";
    public static final String SHELL = "expert";

    /** CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md 6.1 (same text as MdsExportPlan.CP_ENV). */
    public static final String CP_ENV = "unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ";

    public static final String HCP_DIRECTORY = "/var/log/hcp/last";
    /** Same name form as the system-log HCP fetch; C7 takes only a name C6 printed in the same run. */
    public static final Pattern HCP_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    public static final long HCP_MAX_BYTES = 16L * 1024 * 1024;
    public static final long GAIA_EXTRAS_MAX_BYTES = 1024L * 1024;
    public static final int COMMAND_MAX_BYTES = 256 * 1024;

    public static final String SFTP_KEY = "sftp get of /var/log/hcp/last/<listed name>; name pattern [A-Za-z0-9_.-]+\\.tar\\.gz";
    public static final String SFTP_GATE = "cp_sb_sftp_get_hcp";
    public static final String CPDATA_RUN_GATE = "cp_sb_cpdata_collector_run";

    /** C0-C6, byte-identical to the V230 rows. */
    public enum Exec {
        CPDATA_PRESENT("cp_sb_cpdata_collector_present", CP_ENV + "command -v cpdata_collector"),
        CPU_MULTI("cp_sb_cpu_multi", CP_ENV + "cpstat os -f multi_cpu"),
        DF_VAR_LOG("cp_sb_df_var_log", CP_ENV + "df -P /var/log"),
        SHOW_ASSET_ALL("cp_sb_show_asset_all", CP_ENV + "clish -c 'show asset all'"),
        SHOW_VERSION_ALL("cp_sb_show_version_all", CP_ENV + "clish -c 'show version all'"),
        CPHAPROB_STAT("cp_sb_cphaprob_stat", CP_ENV + "cphaprob stat"),
        LS_HCP_LAST("cp_sb_ls_hcp_last", CP_ENV + "ls -lgo --time-style=+%s /var/log/hcp/last/");

        private final String gateId;
        private final String command;

        Exec(String gateId, String command) { this.gateId = gateId; this.command = command; }

        public String gateId() { return gateId; }
        public String command() { return command; }

        public CanonicalCommandKey key() {
            return new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, SHELL, "SSH_EXEC", command);
        }
    }

    /** The signed-off read gate of one exec read; empty when missing, unsigned, not a read or ambiguous. */
    public static Optional<GateResolution.Known> gate(Exec exec, GateRegistryPort registry) {
        return resolve(exec.key(), exec.gateId(), registry);
    }

    /** C7. */
    public static Optional<GateResolution.Known> sftpGate(GateRegistryPort registry) {
        return resolve(new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, "not_applicable", "SFTP_GET", SFTP_KEY),
                SFTP_GATE, registry);
    }

    /**
     * The two cpdata_collector literals the PO approved after the S2 brief (2026-11-15): the gate rows exist (V230), but no
     * executor, plan or capability step uses them in this step ({@code CpSupportBundleGateTest} keeps it so). The run is a
     * recovery-write (BW-2), so it never resolves as a class 0 read here; a later lane builds its own resolution.
     */
    public static final String CPDATA_LIST_GATE = "cp_sb_cpdata_collector_list";

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

    public static Duration timeout(GateResolution.Known gate) {
        return Duration.ofSeconds(gate.timeoutS());
    }

    /** Steps in run order; the SFTP step is gate-not-applicable here (C7 is resolved by {@link #sftpGate}). */
    public static Capability capability(GateRegistryPort registry) {
        List<CapabilityStep> steps = new ArrayList<>();
        steps.add(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(),
                Optional.empty()));
        for (Exec exec : Exec.values()) {
            steps.add(new CapabilityStep(StepKind.EXEC, SHELL, exec.command(), false, Optional.empty(), Optional.empty(),
                    Optional.empty()));
        }
        steps.add(new CapabilityStep(StepKind.SFTP_GET, "not_applicable", null, true, Optional.empty(), Optional.empty(),
                Optional.empty()));
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(),
                Optional.empty(), Optional.empty());
        return new CapabilityRegistryLoader(registry).load(new CapabilitySpec(CAPABILITY, VENDOR, PLATFORM_ROLE_SCOPE,
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, steps, List.of(disconnect), "2026-11-15", List.of(), false));
    }
}
