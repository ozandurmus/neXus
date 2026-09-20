package com.securityexpert.nexus.ui2.jobs.admission;

import java.util.Set;

/**
 * The one {@code cp_gateway_backup} capability id (14H BK-9: Check Point
 * gateway only -- Palo Alto and management servers are later movements),
 * mirrors {@link ConfigurationCapabilityIds} exactly. Admitted only against
 * an ENROLLED, non-disabled device that is ALSO on the pilot allowlist
 * (14H BK-1) -- never a member of {@link ConfirmCapabilityIds}, so {@link
 * JobAdmissionService}'s DRAFT exception (14B EC-J1) never applies.
 */
public final class BackupCapabilityIds {

    public static final String CP_GAIA_BACKUP_LOCAL = "cp_gateway_backup";
    public static final String CP_GAIA_SNAPSHOT = "cp_gaia_snapshot";
    public static final String PAN_DEVICE_STATE_BACKUP = "pan_device_state_backup";

    public static final Set<String> ALL = Set.of(CP_GAIA_BACKUP_LOCAL, CP_GAIA_SNAPSHOT, PAN_DEVICE_STATE_BACKUP);

    private BackupCapabilityIds() {
    }

    public static boolean isBackupCapability(String capabilityId) {
        return ALL.contains(capabilityId);
    }
}
