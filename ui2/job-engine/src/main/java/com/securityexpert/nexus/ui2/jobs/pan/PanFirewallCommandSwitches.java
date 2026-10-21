package com.securityexpert.nexus.ui2.jobs.pan;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.persistence.device.PanSystemInfoRetirement;

/**
 * PO 2026-10-21 (PAN-OS 12+ defect): Palo Alto firewall reads that may be retired once the PO approves their
 * alternatives. Each read has one switch, so turning it off is a deployment setting, not a code change. Default: still
 * enabled. {@code show system info} has no switch: it is retired outright ({@link PanSystemInfoRetirement}, V171).
 *
 * <p>A switch is a worker environment variable, read once at start, like {@code NEXUS_FAILOVER_MUTATION_ENABLED}.
 * Changing it needs a restart. {@value #RETIRED} (also {@code false} or {@code off}, any case) turns the read off.
 * Anything else, including unset, leaves it on. When a read is off, its path never sends it and reports the facts
 * it provided as UNKNOWN with {@link #REASON}, which is the same reason {@code show system info} carries.</p>
 *
 * <p>{@code show counter interface all} and the per-interface reads ({@code show interface ha1-a / ha1-b /
 * management}) are named in the same PO scope. neXus sends neither, because no gate row or code path exists for
 * them, so there is nothing to switch.</p>
 */
public record PanFirewallCommandSwitches(boolean showInterfaceAll, boolean showSessionInfo) {

    /** Inventory's {@code <show><interface>all</interface></show>} (gate row pan_inventory_show_interface_all). */
    public static final String SHOW_INTERFACE_ALL_VARIABLE = "NEXUS_PAN_FW_SHOW_INTERFACE_ALL";
    /** Failover check 6's {@code <show><session><info/></session></show>} (gate row pan_failover_session_info). */
    public static final String SHOW_SESSION_INFO_VARIABLE = "NEXUS_PAN_FW_SHOW_SESSION_INFO";
    public static final String RETIRED = "retired";
    public static final String REASON = PanSystemInfoRetirement.REASON;
    /** Read-kind tokens recorded on an inventory run when a read was not sent (device_inventory_run.not_collected). */
    public static final String TOKEN_SHOW_INTERFACE_ALL = "pan_show_interface_all";

    /** Every read enabled: the state until the PO approves alternatives. */
    public static final PanFirewallCommandSwitches ALL_ENABLED = new PanFirewallCommandSwitches(true, true);

    public static PanFirewallCommandSwitches fromEnvironment() {
        return from(System::getenv);
    }

    public static PanFirewallCommandSwitches from(Map<String, String> values) {
        return from(values::get);
    }

    private static PanFirewallCommandSwitches from(Function<String, String> lookup) {
        return new PanFirewallCommandSwitches(enabled(lookup.apply(SHOW_INTERFACE_ALL_VARIABLE)),
                enabled(lookup.apply(SHOW_SESSION_INFO_VARIABLE)));
    }

    static boolean enabled(String value) {
        if (value == null) {
            return true;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        return !(RETIRED.equals(v) || "false".equals(v) || "off".equals(v));
    }
}
