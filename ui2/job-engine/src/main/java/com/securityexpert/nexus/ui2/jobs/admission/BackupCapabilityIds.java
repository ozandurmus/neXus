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
    /** The CLI half of the PAN bundle (V43); registered for gate alignment, run inside PAN_DEVICE_STATE_BACKUP, never submitted alone (hence not in ALL). */
    public static final String PAN_SET_CONFIG_READ = "pan_set_config_read";

    /** Check Point Multi-Domain Server export: one mds_backup of the whole server, every domain (V61, PO 2026-09-23). */
    public static final String CP_MDS_EXPORT = "cp_mds_export";
    /** Vendors backed up over HTTPS (V64): Infoblox Grid Manager, Radware DefensePro -- one capability, routed by vendor. */
    public static final String HTTPS_VENDOR_BACKUP = "https_vendor_backup";
    public static final Set<String> ALL = Set.of(CP_GAIA_BACKUP_LOCAL, CP_GAIA_SNAPSHOT, PAN_DEVICE_STATE_BACKUP, CP_MDS_EXPORT,
            HTTPS_VENDOR_BACKUP);

    private BackupCapabilityIds() {
    }

    public static boolean isBackupCapability(String capabilityId) {
        return ALL.contains(capabilityId);
    }
}
