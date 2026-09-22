import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { BackupScreen, toFleetRows } from "../src/screens/BackupScreen";
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

describe("the Backups screen (UI review 2026-09-23)", () => {
  const devices = [
    { device_id: "d1", role: "gateway", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04-M1", model: "Check Point VSX", software_version: "R81.20", ha_role: "ACTIVE", cluster_member_ref: "CLS-TANGO-04", backup_target: true },
    { device_id: "d2", role: "gateway", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-ROMEO-01-M1", model: "PA-5445", software_version: "11.1.10-h7", ha_role: "PASSIVE", cluster_member_ref: "CLS-ROMEO-01", backup_target: true },
    { device_id: "d3", role: "gateway", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-BRAVO-02", model: "PA-3440", software_version: "11.1.11", ha_role: null, cluster_member_ref: null, backup_target: false },
  ];
  const backups = [{ artefact_id: "a1", device_id: "d1", collected_at: "2026-09-22T11:43:28.377470Z", size_bytes: 2048, digest_prefix: "x", validation_level: "V1", deviation_state: "changed", vendor: "check_point" }];

  function stub(policy: object | null, deviations: object | null) {
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const body = url.startsWith("/devices") ? { devices }
        : url.startsWith("/api/v2/backups/policies") ? policy
        : url.startsWith("/api/v2/backups/deviations") ? deviations
        : url.startsWith("/backups") ? { backups, baselines: {} } : {};
      return Promise.resolve(new Response(JSON.stringify(body ?? {}), { status: body === null ? 500 : 200 }));
    }));
  }

  afterEach(() => vi.unstubAllGlobals());

  it("shows one row per device with the target switch, the latest archive and every action", async () => {
    stub({ schedule_enabled: false, daily_backup_cron: "0 2 * * *", backup_retention_days: 14, snapshot_retention_depth: 1 }, { active_major_deviations: [], total_deviations_checked: 0 });
    render(<BackupScreen />);
    const row = (await screen.findByText("FW-TANGO-04-M1")).closest("tr")!;
    expect(within(row).getByRole("checkbox", { name: "Backup target FW-TANGO-04-M1" })).toBeChecked();
    expect(within(row).getByText("2026-09-22 11:43:28")).toBeInTheDocument();
    for (const action of ["Backup Now", "Snapshot", "History", "Contents", "Compare", "Download"]) {
      expect(within(row).getByRole("button", { name: action })).toBeInTheDocument();
    }
    // validation is an evidence grade (neutral), CHANGED an attention word
    expect(within(row).getByText("V1")).toBeInTheDocument();
    expect(within(row).getByText("CHANGED")).toBeInTheDocument();
    // a target without an archive says so
    expect(within((await screen.findByText("FW-ROMEO-01-M1")).closest("tr")!).getByText("No archive")).toBeInTheDocument();
  });

  it("writes Not scheduled and UNKNOWN deviations instead of Off and 0", async () => {
    stub({ schedule_enabled: false, daily_backup_cron: "0 2 * * *", backup_retention_days: 14, snapshot_retention_depth: 1 }, { active_major_deviations: [], total_deviations_checked: 0 });
    render(<BackupScreen />);
    expect(await screen.findByText("Not scheduled")).toBeInTheDocument();
    const card = screen.getByText("Active major deviations").parentElement!;
    expect(card.textContent).toContain("UNKNOWN");
    expect(card.textContent).toContain("0 comparisons run");
  });

  it("filters to targets without an archive", async () => {
    stub(null, null);
    render(<BackupScreen />);
    await screen.findByText("FW-TANGO-04-M1");
    fireEvent.click(screen.getByText("Targets without archive · 1"));
    expect(screen.queryByText("FW-TANGO-04-M1")).toBeNull();
    expect(screen.getByText("FW-ROMEO-01-M1")).toBeInTheDocument();
    expect(screen.queryByText("FW-BRAVO-02")).toBeNull();
  });
});
