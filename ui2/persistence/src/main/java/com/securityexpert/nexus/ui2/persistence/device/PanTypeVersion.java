package com.securityexpert.nexus.ui2.persistence.device;

/**
 * PO decision 2026-10-21 ({@value #DECISION_RECORD}): a Palo Alto firewall's serial, model and PAN-OS version are read
 * with the documented XML API request {@code type=version} (a read-only request type, not an operational command),
 * for every PAN-OS version. It replaces {@code show system info} at the identity step of the enrollment confirm,
 * inventory and configuration collect ({@link PanSystemInfoRetirement}).
 *
 * <p>The documented response is {@code response/result/{sw-version, multi-vsys, model, serial}}. It carries no
 * hostname, platform family, uptime or content version, so those stay UNKNOWN ({@link PanSystemInfoRetirement#REASON}).
 * One place for the request, the gate id and the read token, so worker and service say the same.</p>
 */
public final class PanTypeVersion {

    /** The XML API request type: the whole request is {@code POST /api/} with the body {@code type=version}. */
    public static final String TYPE = "version";
    /** The gate row's canonical command key (the request, as the other non-op rows write theirs). */
    public static final String CANONICAL_COMMAND_KEY = "type=version";
    /** The firewall gate row, SIGNED_OFF by V172. */
    public static final String GATE_ID = "pan_identity_version";
    /** {@code device_platform_facts.source_read} of a firewall whose last inventory run read type=version. */
    public static final String SOURCE_READ = "pan_type_version";
    public static final String DECISION_RECORD = "docs/design/PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md";

    private PanTypeVersion() {
    }
}
