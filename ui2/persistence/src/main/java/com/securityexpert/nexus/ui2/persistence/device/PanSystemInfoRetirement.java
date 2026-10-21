package com.securityexpert.nexus.ui2.persistence.device;

/**
 * PO decision 2026-10-21 ({@value #DECISION_RECORD}): neXus never sends {@code show system info} to a Palo Alto
 * firewall. The firewall gate row {@value #FIREWALL_GATE_ID} is BLOCKED (V171) and every firewall code path runs
 * without it. The Panorama row ({@code pan_panorama_show_system_info}) is out of scope and stays SIGNED_OFF: the
 * Panorama inventory and the Panorama enrollment confirm keep this read, scoped to the Panorama.
 *
 * <p>Fields that only this read provided (model, serial, PAN-OS version, content versions, uptime, platform family,
 * the hostname as reported) are not collected for a firewall any more: they are UNKNOWN with {@link #REASON}, never a
 * stale value presented as current, never 0. Direct-evidence identity that rested on the serial is NOT_EVALUABLE.</p>
 *
 * <p>One place for the literal, the gate id, the read token and the reason, so worker and service say the same.</p>
 */
public final class PanSystemInfoRetirement {

    /** The retired literal; still sent to a Panorama (its own gate row), never to a firewall. */
    public static final String COMMAND = "<show><system><info/></system></show>";
    /** The firewall gate row, BLOCKED by V171. */
    public static final String FIREWALL_GATE_ID = "pan_inventory_show_system_info";
    /** The Panorama gate row: out of scope, unchanged. */
    public static final String PANORAMA_GATE_ID = "pan_panorama_show_system_info";
    /** {@code device_platform_facts.source_read} of a firewall inventory run: nothing read, on purpose. */
    public static final String SOURCE_READ = "pan_show_system_info_retired";
    public static final String DECISION_RECORD = "docs/design/PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md";
    /** What the screen and every UNKNOWN reason say. */
    public static final String REASON = "Not collected (command retired by PO 2026-10-21)";
    /** The identity check's reason when the serial was the only direct evidence. */
    public static final String IDENTITY_NOT_EVALUABLE =
            "identity NOT_EVALUABLE: the firewall serial came only from show system info, retired by PO 2026-10-21";

    private PanSystemInfoRetirement() {
    }

    /** True for a Palo Alto firewall (any role but a Panorama management server). */
    public static boolean appliesTo(String vendor, String role) {
        return "palo_alto".equals(vendor) && !"management_server".equals(role);
    }
}
