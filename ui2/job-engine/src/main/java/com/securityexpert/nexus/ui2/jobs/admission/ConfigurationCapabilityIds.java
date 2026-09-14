package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Set;

/**
 * The two {@code configuration_collect} capability ids (14G CG-8), mirrors
 * {@link InventoryCapabilityIds} exactly. Admitted only against ENROLLED,
 * non-disabled devices -- never a member of {@link ConfirmCapabilityIds},
 * so {@link JobAdmissionService}'s DRAFT exception (14B EC-J1) never
 * applies to either.
 */
public final class ConfigurationCapabilityIds {

    public static final String CP_CONFIGURATION_COLLECT = "cp_configuration_collect";
    public static final String PAN_CONFIGURATION_COLLECT = "pan_configuration_collect";

    public static final Set<String> ALL = Set.of(CP_CONFIGURATION_COLLECT, PAN_CONFIGURATION_COLLECT);

    private ConfigurationCapabilityIds() {
    }

    public static boolean isConfigurationCapability(String capabilityId) {
        return ALL.contains(capabilityId);
    }
}
