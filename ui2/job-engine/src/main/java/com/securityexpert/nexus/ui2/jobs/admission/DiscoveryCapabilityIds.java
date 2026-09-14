package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Set;

/**
 * The two discovery-enumeration capability ids (14F DR-1), mirroring {@link
 * ConfirmCapabilityIds}/{@link InventoryCapabilityIds}'s shape. Admitted
 * against a {@code discovery_run} row, never a device -- {@link
 * JobAdmissionService#submitForRun} is the only admission path that ever
 * resolves either of these.
 */
public final class DiscoveryCapabilityIds {

    public static final String CP_DISCOVERY_ENUMERATE = "cp_discovery_enumerate";
    public static final String PAN_DISCOVERY_ENUMERATE = "pan_discovery_enumerate";

    public static final Set<String> ALL = Set.of(CP_DISCOVERY_ENUMERATE, PAN_DISCOVERY_ENUMERATE);

    private DiscoveryCapabilityIds() {
    }

    public static boolean isDiscoveryCapability(String capabilityId) {
        return ALL.contains(capabilityId);
    }
}
