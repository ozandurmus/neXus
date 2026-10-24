package com.securityexpert.nexus.ui2.jobs.pan;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.persistence.device.PanSystemInfoRetirement;

/**
 * PO 2026-10-21 (PAN-OS 12+ defect): Palo Alto firewall reads that may be switched, each by one worker environment
 * variable, so turning a read off or limiting it is a deployment setting, not a code change. Default: always sent.
 * {@code show system info} has no switch: it is retired outright ({@link PanSystemInfoRetirement}, V171).
 *
 * <p>A switch is read once at start, like {@code NEXUS_FAILOVER_MUTATION_ENABLED}; changing it needs a restart.
 * Values (any case, stripped): {@value #RETIRED} (also {@code false} or {@code off}) = {@link Mode#NEVER}, the read is
 * never sent; {@value #BELOW_12} (also {@code pre12}) = {@link Mode#BELOW_12}, the read is sent only when the device's
 * PAN-OS major version, observed in the same job, is known and below 12 (PO 2026-10-21, "cihaz panos 12 degilse bu
 * kontrol calissin"); {@value #REPLACE_12} (also {@code replace12}) = {@link Mode#REPLACE_12}, the read is sent below
 * PAN-OS 12 exactly as under {@link Mode#BELOW_12}, and on PAN-OS 12 or later its replacement is used instead
 * ({@link Decision#REPLACE}; PO 2026-10-24, docs/design/PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md:
 * {@code show session info} -> {@code show session meter}, {@code show interface all} -> interfaces derived from the
 * stored effective-running configuration); anything else, including unset, = {@link Mode#ALWAYS}. A read that is not
 * sent reports what it provided as UNKNOWN with the reason {@link Decision#reason()} says.</p>
 *
 * <p>{@code show counter interface all} and the per-interface reads ({@code show interface ha1-a / ha1-b /
 * management}) are named in the same PO scope. neXus sends neither, because no gate row or code path exists for
 * them, so there is nothing to switch.</p>
 */
public record PanFirewallCommandSwitches(Mode showInterfaceAll, Mode showSessionInfo) {

    /** How one read is switched. */
    public enum Mode { ALWAYS, NEVER, BELOW_12, REPLACE_12 }

    /** What a switch decides for one read of one device: send it, or the reason it is not sent. */
    public enum Decision {
        SEND(null, null),
        SKIP_RETIRED(null, "command retired by PO 2026-10-21"),
        SKIP_PAN_OS_12("pan_os_12_or_later", "PAN-OS 12 or later (PO 2026-10-21)"),
        SKIP_VERSION_UNKNOWN("pan_os_version_unknown", "PAN-OS version unknown"),
        /** PAN-OS 12 or later under {@link Mode#REPLACE_12}: the read is not sent, its replacement is used (PO 2026-10-24). */
        REPLACE("pan_os_12_or_later", "PAN-OS 12 or later (PO 2026-10-21)");

        private final String token;
        private final String detail;

        Decision(String token, String detail) {
            this.token = token;
            this.detail = detail;
        }

        /** True when the original read is sent. */
        public boolean send() {
            return this == SEND;
        }

        /** True when the original read is not sent and its PAN-OS 12 replacement is used instead. */
        public boolean replace() {
            return this == REPLACE;
        }

        /** Extra token recorded beside the read token on an inventory run; null for SEND and SKIP_RETIRED. */
        public String token() {
            return token;
        }

        /** Short reason without the "Not collected" prefix; null for SEND. */
        public String detail() {
            return detail;
        }

        /** The full reason the screen shows; null for SEND. */
        public String reason() {
            return detail == null ? null : this == SKIP_RETIRED ? REASON : "Not collected: " + detail;
        }
    }

    /** Inventory's {@code <show><interface>all</interface></show>} (gate row pan_inventory_show_interface_all). */
    public static final String SHOW_INTERFACE_ALL_VARIABLE = "NEXUS_PAN_FW_SHOW_INTERFACE_ALL";
    /** Failover check 6's {@code <show><session><info/></session></show>} (gate row pan_failover_session_info). */
    public static final String SHOW_SESSION_INFO_VARIABLE = "NEXUS_PAN_FW_SHOW_SESSION_INFO";
    public static final String RETIRED = "retired";
    public static final String BELOW_12 = "below_12";
    /** PO 2026-10-24: below 12 the read as today, 12 or later its replacement. */
    public static final String REPLACE_12 = "replace_12";
    public static final String REASON = PanSystemInfoRetirement.REASON;
    /** The first PAN-OS major version the reported defect affects. */
    public static final int DEFECT_MAJOR = 12;
    /** Read-kind tokens recorded on an inventory run when a read was not sent (device_inventory_run.not_collected). */
    public static final String TOKEN_SHOW_INTERFACE_ALL = "pan_show_interface_all";

    /** Every read always sent: the state until the PO approves alternatives. */
    public static final PanFirewallCommandSwitches ALL_ENABLED =
            new PanFirewallCommandSwitches(Mode.ALWAYS, Mode.ALWAYS);

    public static PanFirewallCommandSwitches fromEnvironment() {
        return from(System::getenv);
    }

    public static PanFirewallCommandSwitches from(Map<String, String> values) {
        return from(values::get);
    }

    private static PanFirewallCommandSwitches from(Function<String, String> lookup) {
        return new PanFirewallCommandSwitches(mode(lookup.apply(SHOW_INTERFACE_ALL_VARIABLE)),
                mode(lookup.apply(SHOW_SESSION_INFO_VARIABLE)));
    }

    static Mode mode(String value) {
        if (value == null) {
            return Mode.ALWAYS;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (RETIRED.equals(v) || "false".equals(v) || "off".equals(v)) {
            return Mode.NEVER;
        }
        if (BELOW_12.equals(v) || "pre12".equals(v)) {
            return Mode.BELOW_12;
        }
        if (REPLACE_12.equals(v) || "replace12".equals(v)) {
            return Mode.REPLACE_12;
        }
        return Mode.ALWAYS;
    }

    /** True when the mode can send the read for some device (the capability gate row must then be checked). */
    public static boolean mayRead(Mode mode) {
        return mode != Mode.NEVER;
    }

    /**
     * Decides one read for one device. {@code panOsVersion} is the PAN-OS version string observed in the SAME job (never
     * a stored value); null, blank or unparseable is unknown, and unknown is never sent under {@link Mode#BELOW_12} or
     * {@link Mode#REPLACE_12} (neither the read nor its replacement: UNKNOWN, never a guessed version).
     */
    public static Decision decide(Mode mode, String panOsVersion) {
        switch (mode) {
            case ALWAYS:
                return Decision.SEND;
            case NEVER:
                return Decision.SKIP_RETIRED;
            default:
                OptionalInt major = PanOsVersion.major(panOsVersion);
                if (major.isEmpty()) {
                    return Decision.SKIP_VERSION_UNKNOWN;
                }
                if (major.getAsInt() < DEFECT_MAJOR) {
                    return Decision.SEND;
                }
                return mode == Mode.REPLACE_12 ? Decision.REPLACE : Decision.SKIP_PAN_OS_12;
        }
    }
}
