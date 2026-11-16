package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Support bundle collection, Palo Alto Tech Support File (step S3; PO approval 2026-11-15, rows P1-P6 of
 * SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6). The six requests of the XML API, each resolved against its
 * live V237 gate row before it is sent, for a firewall ({@code pan_firewall}) and for a Panorama ({@code panorama}).
 * P3 and P6 are recovery-write (BW-2: the device writes and removes {@code /tmp/techsupport.tgz}); P4 and P5 take the
 * device-issued job number, digits only, and nothing else.
 */
public final class PanSupportBundle {
    private PanSupportBundle() {}

    public static final String CAPABILITY = "pan_support_bundle";
    public static final String VENDOR = "palo_alto";
    public static final String FIREWALL_SCOPE = "pan_firewall";
    public static final String PANORAMA_SCOPE = "panorama";

    /** The job number the device answered the export with; only digits are ever sent back. */
    public static final Pattern JOB_ID = Pattern.compile("[0-9]{1,12}");
    public static final long TSF_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(15);
    /** The 30 minute deadline of the tech-support job (design 5.8), as a count of 15 second polls. */
    public static final int MAX_POLLS = 120;
    public static final int COMMAND_MAX_CHARS = 256 * 1024;
    /** Management plane CPU above this refuses the TSF (local heuristic, design 5.8). */
    public static final int CPU_LIMIT_PERCENT = 80;
    /** Root or /opt/panlogs above this refuses the TSF (local heuristic, design 5.8). */
    public static final int DISK_LIMIT_PERCENT = 90;

    public enum Cmd {
        SYSTEM_RESOURCES("system_resources", "type=op&cmd=<show><system><resources></resources></system></show>",
                ActionClass.CLASS_0_READ),
        DISK_SPACE("disk_space", "type=op&cmd=<show><system><disk-space></disk-space></system></show>",
                ActionClass.CLASS_0_READ),
        TSF_START("tsf_start", "type=export&category=tech-support", ActionClass.CLASS_1_RECOVERY_WRITE),
        TSF_STATUS("tsf_status", "type=export&category=tech-support&action=status&job-id=<n>", ActionClass.CLASS_0_READ),
        TSF_GET("tsf_get", "type=export&category=tech-support&action=get&job-id=<n>", ActionClass.CLASS_0_READ),
        TSF_FINISH("tsf_finish", "type=export&category=tech-support&action=finish&job-id=<n>",
                ActionClass.CLASS_1_RECOVERY_WRITE);

        private final String suffix;
        private final String command;
        private final ActionClass actionClass;

        Cmd(String suffix, String command, ActionClass actionClass) {
            this.suffix = suffix; this.command = command; this.actionClass = actionClass;
        }

        /** {@code pan_sb_<suffix>} for a firewall, {@code panorama_sb_<suffix>} for a Panorama (one gate row per platform scope). */
        public String gateId(String roleScope) {
            return (PANORAMA_SCOPE.equals(roleScope) ? "panorama_sb_" : "pan_sb_") + suffix;
        }
        /** The canonical key of the gate row, placeholder included. */
        public String command() { return command; }
        public ActionClass actionClass() { return actionClass; }

        public CanonicalCommandKey key(String roleScope) {
            return new CanonicalCommandKey(VENDOR, roleScope, "not_applicable", "PAN_XML_API", command);
        }
    }

    /** The signed-off gate of one request for a platform scope; empty when missing, unsigned, of another class or ambiguous. */
    public static Optional<GateResolution.Known> gate(Cmd cmd, String roleScope, GateRegistryPort registry) {
        return SupportBundleGates.resolve(cmd.key(roleScope), cmd.gateId(roleScope), cmd.actionClass(), registry);
    }

    public static boolean allGatesKnown(String roleScope, GateRegistryPort registry) {
        for (Cmd cmd : Cmd.values()) if (gate(cmd, roleScope, registry).isEmpty()) return false;
        return true;
    }

    public static Duration timeout(GateResolution.Known gate) {
        return SupportBundleGates.timeout(gate);
    }

    public static List<String> platformScopes() {
        return List.of(FIREWALL_SCOPE, PANORAMA_SCOPE);
    }
}
