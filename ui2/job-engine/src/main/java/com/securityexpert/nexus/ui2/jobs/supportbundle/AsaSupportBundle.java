package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.time.Duration;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Support bundle collection, Cisco ASA (step S4; PO approval 2026-11-15, rows A1-A4 of
 * SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6). The four reads, each resolved against its live V237 gate
 * row before it is sent. {@code terminal pager 0} is never sent here (PO Q12): the {@code <--- More --->} prompt is
 * answered in session. The fallback rows A5-A7 (a file on {@code disk0:}) are not approved and do not exist.
 */
public final class AsaSupportBundle {
    private AsaSupportBundle() {}

    public static final String CAPABILITY = "asa_support_bundle";
    public static final String VENDOR = "cisco_asa";
    public static final String PLATFORM_ROLE_SCOPE = "cisco_asa_firewall";
    public static final String PAGER_MARKER = "<--- More --->";
    public static final long CRASHINFO_MAX_BYTES = 64L * 1024 * 1024;
    public static final long SHOW_TECH_MAX_BYTES = 256L * 1024 * 1024;
    /** A capture that ended early with at least this much stored is PARTIAL, below it FAILED (design section 7). */
    public static final long PARTIAL_MIN_BYTES = 4096;
    public static final int COMMAND_MAX_CHARS = 256 * 1024;
    /** Management CPU above this refuses {@code asa_show_tech} (local heuristic, design 5.8). */
    public static final int CPU_LIMIT_PERCENT = 80;

    public enum Cmd {
        SHOW_CPU_USAGE("asa_sb_show_cpu_usage", "show cpu usage", "privileged_exec"),
        CHANGETO_SYSTEM("asa_sb_changeto_system", "changeto system", "context"),
        SHOW_CRASHINFO("asa_sb_show_crashinfo", "show crashinfo", "privileged_exec"),
        SHOW_TECH_SUPPORT("asa_sb_show_tech_support", "show tech-support", "privileged_exec");

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
}
