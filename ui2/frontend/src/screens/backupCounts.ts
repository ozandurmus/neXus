import type { DeviceSummary } from "../auth/adminApi";

/**
 * The device roles neXus has a backup method for: gateways and firewalls, appliances backed up over HTTPS (V64), and
 * the management servers whose own backup is implemented -- a Check Point MDS (Gaia backup), a Radware management
 * server, a Symantec Management Center and a Panorama (device-state, else its running configuration). A
 * FortiManager, for one, has none.
 */
export function hasBackupMethod(d: Pick<DeviceSummary, "role" | "vendor_hint">): boolean {
  if (d.role === undefined || d.role === "gateway" || d.role === "firewall" || d.role === "appliance") return true;
  return d.role === "management_server"
    && (d.vendor_hint === "check_point" || d.vendor_hint === "radware" || d.vendor_hint === "bluecoat" || d.vendor_hint === "palo_alto");
}

type Counted = Pick<DeviceSummary, "device_id" | "enrollment_state" | "role" | "vendor_hint" | "backup_target">;

/** A row of the backup fleet table: an enrolled device with a backup method, or any enrolled backup target. */
export function isBackupFleetRow(d: Counted): boolean {
  return d.enrollment_state === "ENROLLED" && (hasBackupMethod(d) || d.backup_target === true);
}

export interface BackupFleetCounts {
  /** Enrolled devices (the Overview's "active devices"). */
  readonly enrolled: number;
  /** Rows of the fleet table: enrolled devices neXus can back up, plus any enrolled target. */
  readonly backupCapable: number;
  /** Enrolled devices switched on as a backup target -- the Overview's "of N targets". */
  readonly targets: number;
  /** Targets with at least one stored archive -- the Overview's "N of ... targets". */
  readonly targetsWithArchive: number;
  readonly targetsWithoutArchive: number;
  /** Devices with a stored archive that are not an enrolled backup target (switched off, not enrolled, or gone). */
  readonly archivedNotTarget: number;
  /** Every device the artefact store holds a live archive for: targetsWithArchive + archivedNotTarget. */
  readonly archivedDevices: number;
}

/**
 * One definition of every backup count on the Backups screen, the same as the Overview's (OverviewService: a target is
 * an enrolled device with backup_target on; a stored archive is one the retention ledger has not removed, which is
 * what the store listing returns). UI review 2026-10-17: "125 devices with a stored backup" read against "124 of 131
 * targets" because it also counted a device that is not a target; both figures are now shown with their population.
 */
export function backupFleetCounts(devices: readonly Counted[], archivedDeviceIds: ReadonlySet<string>): BackupFleetCounts {
  const enrolled = devices.filter((d) => d.enrollment_state === "ENROLLED");
  const targets = enrolled.filter((d) => d.backup_target === true);
  const targetIds = new Set(targets.map((d) => d.device_id));
  const targetsWithArchive = targets.filter((d) => archivedDeviceIds.has(d.device_id)).length;
  const archivedNotTarget = [...archivedDeviceIds].filter((id) => !targetIds.has(id)).length;
  return {
    enrolled: enrolled.length,
    backupCapable: enrolled.filter(isBackupFleetRow).length,
    targets: targets.length,
    targetsWithArchive,
    targetsWithoutArchive: targets.length - targetsWithArchive,
    archivedNotTarget,
    archivedDevices: targetsWithArchive + archivedNotTarget,
  };
}

/** The retention card's note: the snapshot depth is a count of Gaia snapshots, not of archives. */
export function retentionNote(policy: { backup_retention_days: number; snapshot_retention_depth: number }): string {
  return `Backups older than ${policy.backup_retention_days} days are pruned (a device's last one is kept) · `
    + `Gaia snapshots: newest ${policy.snapshot_retention_depth} kept per gateway`;
}
