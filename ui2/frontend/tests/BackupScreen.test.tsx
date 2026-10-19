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

  function stub(policy: object | null, deviations: object | null, backupRows: object[] = backups) {
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const body = url.startsWith("/devices") ? { devices }
        : url.startsWith("/api/v2/backups/policies") ? policy
        : url.startsWith("/api/v2/backups/deviations") ? deviations
        : url.startsWith("/backups") ? { backups: backupRows, baselines: {} } : {};
      return Promise.resolve(new Response(JSON.stringify(body ?? {}), { status: body === null ? 500 : 200 }));
    }));
  }

  afterEach(() => vi.unstubAllGlobals());

  it("describes the daily Istanbul backup window separately from polling", async () => {
    stub({ schedule_enabled: true, daily_backup_cron: "0 2 * * *", backup_retention_days: 14, snapshot_retention_depth: 1 },
      { active_major_deviations: [], total_deviations_checked: 0 });
    render(<BackupScreen />);
    await screen.findByText("FW-TANGO-04-M1");
    expect(screen.getByText("02:00 daily")).toBeInTheDocument();
    expect(screen.queryByText("00 / 06 / 12 / 18")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Retention/i }));
    expect(await screen.findByText("Fleet backup schedule: daily at 02:00 GMT+3; starts allowed until 02:59.")).toBeInTheDocument();
    expect(screen.getByText(/every backup target runs once a day at 02:00 GMT\+3/)).toBeInTheDocument();
  });

  it("shows one row per device with the target switch, the latest archive and every action", async () => {
    stub({ schedule_enabled: false, daily_backup_cron: "0 2 * * *", backup_retention_days: 14, snapshot_retention_depth: 1 }, { active_major_deviations: [], total_deviations_checked: 0 });
    render(<BackupScreen />);
    const row = (await screen.findByText("FW-TANGO-04-M1")).closest("tr")!;
    expect(within(row).getByRole("checkbox", { name: "Backup target FW-TANGO-04-M1" })).toBeChecked();
    // the fleet column shows the age; the exact stamp is on hover (post-release review 2026-10-18, R3)
    expect(within(row).getByText(/ago$|^just now$/)).toHaveAttribute("title", expect.stringContaining("2026-09-22 14:43 GMT+3"));
    // every action moved into the row's menu (F-4): the row itself carries no action button but the menu trigger
    expect(within(row).queryByRole("button", { name: "Backup Now" })).toBeNull();
    fireEvent.click(within(row).getByRole("button", { name: "Actions for FW-TANGO-04-M1" }));
    for (const action of ["Backup Now", "Snapshot", "History", "Contents", "Compare", "Download"]) {
      expect(screen.getByRole("menuitem", { name: action })).toBeInTheDocument();
    }
    // validation is an evidence grade (neutral); a byte change stays neutral until a comparison has run
    expect(within(row).getByText("Checked (V1)")).toBeInTheDocument();
    expect(within(row).getByText("Bytes changed")).toBeInTheDocument();
    // a target without an archive says so
    expect(within((await screen.findByText("FW-ROMEO-01-M1")).closest("tr")!).getByText("No archive")).toBeInTheDocument();
  });

  it("shows plain words for backup validation and changes", async () => {
    stub(null, null, [
      ...backups,
      { artefact_id: "a2", device_id: "d2", collected_at: "2026-09-21T11:43:28Z", size_bytes: 2048, digest_prefix: "y", validation_level: "V1", deviation_state: "first", vendor: "palo_alto" },
      { artefact_id: "a3", device_id: "d3", collected_at: "2026-09-20T11:43:28Z", size_bytes: 2048, digest_prefix: "z", validation_level: "V1", deviation_state: "major deviation", vendor: "palo_alto" },
    ]);
    render(<BackupScreen />);
    expect((await screen.findAllByText("Checked (V1)")).length).toBeGreaterThan(0);
    expect(screen.getByText("Bytes changed")).toBeInTheDocument();
    expect(screen.getByText("First backup")).toBeInTheDocument();
    expect(screen.getByText("Major change")).toBeInTheDocument();
  });

  it("writes Not scheduled and UNKNOWN deviations instead of Off and 0", async () => {
    stub({ schedule_enabled: false, daily_backup_cron: "0 2 * * *", backup_retention_days: 14, snapshot_retention_depth: 1 }, { active_major_deviations: [], total_deviations_checked: 0 });
    render(<BackupScreen />);
    expect(await screen.findByText("Not scheduled")).toBeInTheDocument();
    const card = screen.getByText("Active major deviations").closest("[data-kpi]")!;
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

  it("states targets with a stored backup over the Overview's population and names an archived non-target (UI review 2026-10-17)", async () => {
    // d1 is a target with an archive, d2 a target without, d3 not a target but holding an archive
    stub({ schedule_enabled: true, daily_backup_cron: "0 2 * * *", backup_retention_days: 7, snapshot_retention_depth: 1 },
      { active_major_deviations: [], total_deviations_checked: 0 }, [
        ...backups,
        { artefact_id: "a3", device_id: "d3", collected_at: "2026-09-20T11:43:28Z", size_bytes: 2048, digest_prefix: "z", validation_level: "V1", deviation_state: null, vendor: "palo_alto" },
      ]);
    render(<BackupScreen />);
    await screen.findByText("FW-TANGO-04-M1");
    const card = (await screen.findByText("Targets with a stored backup")).closest("[data-kpi]")!;
    expect(card.textContent).toContain("1");
    expect(card.textContent).toContain("of 2 targets");
    expect(card.textContent).toContain("2 devices hold a stored backup: 1 of them is not a backup target");
    expect(screen.getByText("Targets · 2")).toBeInTheDocument();
    expect(screen.getByText("Targets without archive · 1")).toBeInTheDocument();
    expect(screen.getByText("Backup-capable · 3")).toBeInTheDocument();
    const retention = screen.getByText("Retention policy").closest("[data-kpi]")!;
    expect(retention.textContent).toContain("Gaia snapshots: newest 1 kept per gateway");
    expect(retention.textContent).not.toContain("Snapshot depth: 1 retained");
  });

  it("says UNKNOWN for the target count when the device list cannot be read", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.startsWith("/devices")) return Promise.resolve(new Response("{}", { status: 500 }));
      const body = url.startsWith("/backups") ? { backups, baselines: {} } : {};
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    }));
    render(<BackupScreen />);
    const card = (await screen.findByText("Targets with a stored backup")).closest("[data-kpi]")!;
    expect(card.textContent).toContain("UNKNOWN");
  });
});
