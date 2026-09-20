import { describe, expect, it } from "vitest";

import { toFleetRows } from "../src/screens/BackupScreen";
import type { BackupArtefact } from "../src/auth/adminApi";

function artefact(overrides: Partial<BackupArtefact> & Pick<BackupArtefact, "device_id" | "collected_at">): BackupArtefact {
  return {
    artefact_id: "art-1",
    size_bytes: 1024,
    digest_prefix: "abc123",
    validation_level: "V1",
    deviation_state: null,
    ...overrides,
  };
}

describe("the fleet backup table", () => {
  it("shows nothing when the store holds nothing", () => {
    // The screen used to render three devices with passing validation badges from a
    // hardcoded array, so an operator saw a protected fleet whether or not a single
    // backup existed. An empty store must read as empty.
    expect(toFleetRows([])).toEqual([]);
  });

  it("keeps the newest artefact per device rather than one row per artefact", () => {
    const rows = toFleetRows([
      artefact({ device_id: "dev-a", collected_at: "2026-09-18T02:00:00Z", artefact_id: "old" }),
      artefact({ device_id: "dev-a", collected_at: "2026-09-20T02:00:00Z", artefact_id: "new" }),
      artefact({ device_id: "dev-b", collected_at: "2026-09-19T02:00:00Z", artefact_id: "b" }),
    ]);

    expect(rows).toHaveLength(2);
    expect(rows.map((r) => r.artefactId)).toEqual(["new", "b"]);
  });

  it("reports the validation level exactly as recorded and never upgrades it", () => {
    const rows = toFleetRows([artefact({ device_id: "dev-a", collected_at: "2026-09-20T02:00:00Z", validation_level: "V1" })]);

    expect(rows[0].validationLevel).toBe("V1");
  });

  it("says UNKNOWN when the store recorded no validation level", () => {
    const rows = toFleetRows([artefact({ device_id: "dev-a", collected_at: "2026-09-20T02:00:00Z", validation_level: "" })]);

    expect(rows[0].validationLevel).toBe("UNKNOWN");
  });

  it("distinguishes a deviation that was never evaluated from one that came back unchanged", () => {
    const never = toFleetRows([artefact({ device_id: "dev-a", collected_at: "2026-09-20T02:00:00Z", deviation_state: null })]);
    const unchanged = toFleetRows([artefact({ device_id: "dev-b", collected_at: "2026-09-20T02:00:00Z", deviation_state: "unchanged" })]);

    expect(never[0].deviationState).toBe("NOT EVALUATED");
    expect(unchanged[0].deviationState).toBe("UNCHANGED");
  });
});
