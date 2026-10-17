import { describe, expect, it } from "vitest";

import { backupFleetCounts, hasBackupMethod, isBackupFleetRow, retentionNote } from "../src/screens/backupCounts";

/**
 * The backup counts, UI review 2026-10-17 P0-2, on a synthetic estate shaped like the reviewed one: 133 enrolled
 * devices (one of them a management server with no backup method), 2 drafts, 131 targets, 124 of them with a stored
 * archive, and one management server that holds archives but is switched off as a target.
 */
type D = { device_id: string; enrollment_state: string; role?: string; vendor_hint: string; backup_target?: boolean };

function estate(): { devices: D[]; archived: Set<string> } {
  const devices: D[] = [];
  const archived = new Set<string>();
  for (let i = 0; i < 128; i++) {
    devices.push({ device_id: `gw-${i}`, enrollment_state: "ENROLLED", role: "gateway", vendor_hint: "check_point", backup_target: true });
  }
  devices.push({ device_id: "app-1", enrollment_state: "ENROLLED", role: "appliance", vendor_hint: "radware", backup_target: true });
  devices.push({ device_id: "pano-1", enrollment_state: "ENROLLED", role: "management_server", vendor_hint: "palo_alto", backup_target: true });
  devices.push({ device_id: "smc-1", enrollment_state: "ENROLLED", role: "management_server", vendor_hint: "bluecoat", backup_target: true });
  devices.push({ device_id: "mds-1", enrollment_state: "ENROLLED", role: "management_server", vendor_hint: "check_point", backup_target: false });
  devices.push({ device_id: "fmg-1", enrollment_state: "ENROLLED", role: "management_server", vendor_hint: "fortinet", backup_target: false });
  devices.push({ device_id: "draft-1", enrollment_state: "DRAFT", role: "gateway", vendor_hint: "check_point", backup_target: false });
  devices.push({ device_id: "draft-2", enrollment_state: "DRAFT", role: "gateway", vendor_hint: "check_point", backup_target: false });
  for (let i = 0; i < 124; i++) archived.add(`gw-${i}`);
  archived.add("mds-1");
  return { devices, archived };
}

describe("backup fleet counts", () => {
  it("counts each figure over its own population, the same way the Overview does", () => {
    const { devices, archived } = estate();
    const c = backupFleetCounts(devices, archived);
    expect(c.enrolled).toBe(133);
    expect(c.backupCapable).toBe(132); // the FortiManager has no backup method
    expect(c.targets).toBe(131);
    expect(c.targetsWithArchive).toBe(124); // Overview: "124 of 131 targets"
    expect(c.targetsWithoutArchive).toBe(7);
    expect(c.archivedNotTarget).toBe(1);
    expect(c.archivedDevices).toBe(125); // every device the store holds an archive for
  });

  it("keeps every total equal to the sum of its parts", () => {
    const { devices, archived } = estate();
    archived.add("gone-device"); // an archive of a device no longer in neXus
    const c = backupFleetCounts(devices, archived);
    expect(c.targets).toBe(c.targetsWithArchive + c.targetsWithoutArchive);
    expect(c.archivedDevices).toBe(c.targetsWithArchive + c.archivedNotTarget);
    expect(c.archivedDevices).toBe(archived.size);
  });

  it("keeps an enrolled target of a role without a backup method in the table, so the Targets chip lists what it counts", () => {
    const fmgTarget = { device_id: "fmg-2", enrollment_state: "ENROLLED", role: "management_server", vendor_hint: "fortinet", backup_target: true };
    expect(hasBackupMethod(fmgTarget)).toBe(false);
    expect(isBackupFleetRow(fmgTarget)).toBe(true);
    expect(isBackupFleetRow({ ...fmgTarget, enrollment_state: "DRAFT" })).toBe(false);
  });

  it("says that the snapshot depth counts Gaia snapshots, not archives", () => {
    const note = retentionNote({ backup_retention_days: 7, snapshot_retention_depth: 1 });
    expect(note).toContain("Backups older than 7 days are pruned");
    expect(note).toContain("Gaia snapshots: newest 1 kept per gateway");
    expect(note).not.toMatch(/Snapshot depth: 1 retained/);
  });
});
