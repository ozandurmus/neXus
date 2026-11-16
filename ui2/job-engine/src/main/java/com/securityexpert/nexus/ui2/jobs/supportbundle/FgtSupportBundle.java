package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.time.Duration;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Support bundle collection, FortiGate (step S5; PO approval 2026-11-15, rows F1-F5 of
 * SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6; F3 is the two context literals {@code config global} and
 * {@code end}). Each is resolved against its live V237 gate row before it is sent. {@code --More--} is answered in
 * session; no console setting of the device is changed.
 */
public final class FgtSupportBundle {
    private FgtSupportBundle() {}

    public static final String CAPABILITY = "fgt_support_bundle";
    public static final String VENDOR = "fortinet";
    public static final String PLATFORM_ROLE_SCOPE = "fortigate";
    public static final String PAGER_MARKER = "--More--";
    public static final long CRASHLOG_MAX_BYTES = 32L * 1024 * 1024;
    public static final long TAC_REPORT_MAX_BYTES = 256L * 1024 * 1024;
    public static final long PARTIAL_MIN_BYTES = 4096;
    public static final int COMMAND_MAX_CHARS = 256 * 1024;
    /** Management CPU above this refuses {@code fgt_tac_report} (local heuristic, design 5.8). */
    public static final int CPU_LIMIT_PERCENT = 80;

    public enum Cmd {
        GET_SYSTEM_STATUS("fgt_sb_get_system_status", "get system status", "top_or_global"),
        PERFORMANCE_STATUS("fgt_sb_performance_status", "get system performance status", "top_or_global"),
        CONFIG_GLOBAL("fgt_sb_config_global", "config global", "context"),
        END("fgt_sb_end", "end", "context"),
        CRASHLOG_READ("fgt_sb_crashlog_read", "diagnose debug crashlog read", "top_or_global"),
        TAC_REPORT("fgt_sb_tac_report", "execute tac report", "top_or_global");

        private final String gateId;
        private final String command;
        private final String shellContext;

        Cmd(String gateId, String command, String shellContext) {
            this.gateId = gateId; this.command = command; this.shellContext = shellContext;
        }

        public String gateId() { return gateId; }
        public String command() { return command; }

        public CanonicalCommandKey key() {
            return new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, shellContext, "SSH_INTERACTIVE", command);
        }
    }

    public static Optional<GateResolution.Known> gate(Cmd cmd, GateRegistryPort registry) {
        return SupportBundleGates.resolve(cmd.key(), cmd.gateId(), ActionClass.CLASS_0_READ, registry);
    }

    public static boolean allGatesKnown(GateRegistryPort registry) {
        for (Cmd cmd : Cmd.values()) if (gate(cmd, registry).isEmpty()) return false;
        return true;
    }

    public static Duration timeout(GateResolution.Known gate) {
        return SupportBundleGates.timeout(gate);
    }

    /**
     * The vendor defect guard (design 5.8, from stored data, no device command): {@code execute tac report} crashes the
     * session on FortiOS 7.0 and 7.2 and on 7.4 below 7.4.10 and 7.6 below 7.6.6 when the configuration holds a DoS policy.
     * Returns whether the version is on the affected list; the caller combines it with the stored configuration.
     */
    public static boolean versionAffectedByTacReportDefect(String version) {
        if (version == null) return false;
        var m = java.util.regex.Pattern.compile("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)").matcher(version);
        if (!m.find()) return false;
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2)), patch = Integer.parseInt(m.group(3));
        if (major == 7 && (minor == 0 || minor == 2)) return true;
        if (major == 7 && minor == 4) return patch < 10;
        if (major == 7 && minor == 6) return patch < 6;
        return false;
    }
}
