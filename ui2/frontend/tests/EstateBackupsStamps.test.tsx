import { describe, expect, it } from "vitest";
import { buildBackupsLensModel } from "../src/estate/estateSlice2Model";

// Synthetic data only (FW-TANGO-04 style names, 192.0.2.x addresses).
// Guards the Backups stamp source: the lens-level "last run" stamp must come
// from backup evidence (scheduled backup run, stored archive, backup evidence
// timestamp) and never from an inventory / device-read time.

const NOW = new Date("2026-11-22T12:00:00Z");
const FRESH_BACKUP_RUN = "2026-11-22T11:58:00Z";
const BACKUP_EVIDENCE = "2026-11-21T02:00:00Z";

const okSource = <T,>(value: T) => ({ state: "ok" as const, value });
const skipped = { state: "skipped" as const };

function build(overrides: {
  backupEvidenceAt: string | null;
  policy?: unknown;
}) {
  return buildBackupsLensModel({
    now: NOW,
    estateUnits: [],
    backupsRead: skipped as never,
    policy: (overrides.policy ? okSource(overrides.policy) : skipped) as never,
    quotas: skipped as never,
    backupJobs: skipped as never,
    backupEvidenceAt: overrides.backupEvidenceAt,
  });
}

describe("Backups stamp source (no inventory age under backup stamps)", () => {
  it("with backupEvidenceAt null, a fresh backup-run time is still backup evidence, not inventory", () => {
    const model = build({
      backupEvidenceAt: null,
      policy: { last_scheduled_run_at: FRESH_BACKUP_RUN },
    });
    // lastFleetRunAt is derived from the scheduled backup run only; it is never
    // populated from device-read (inventory) timestamps.
    expect(model.lastFleetRunAt).toBe(FRESH_BACKUP_RUN);
  });

  it("with backupEvidenceAt null and no backup run evidence, the stamp source is null (renders UNKNOWN)", () => {
    const model = build({ backupEvidenceAt: null });
    expect(model.lastFleetRunAt).toBeNull();
  });

  it("with backupEvidenceAt set and no other backup timestamps, that backup evidence time is the stamp source", () => {
    const model = build({ backupEvidenceAt: BACKUP_EVIDENCE });
    expect(model.lastFleetRunAt).toBe(BACKUP_EVIDENCE);
  });
});
