import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { BackupDeviations } from "../src/auth/adminApi";
import { MajorDeviationList, groupSimultaneousDeviations, majorDeviationsByDevice } from "../src/screens/BackupDeviationCard";
import { BackupScreen, SCHEDULE_STALE_HOURS, scheduledBackupStaleWords } from "../src/screens/BackupScreen";

// UI review 2026-11-05, B2 (PO approved). Masked pseudonyms only, as the replay viewer sees them.
const same = (n: number) => ({ device_id: `s${n}`, hostname: `FW-HOTEL-0${n}-M2`, vendor: "check_point", category: "admin_access",
  changed_at: `2026-11-03T15:27:${String(10 + n).padStart(2, "0")}Z`, count: 1, sections: ["users"] });

const deviations: BackupDeviations = {
  window_days: 7,
  total_deviations_checked: 30,
  devices_known: 32,
  active_major_deviations: [
    same(1), same(2), same(3),
    { device_id: "d9", hostname: "FW-TANGO-04-M1", vendor: "check_point", category: "routing", changed_at: "2026-11-03T15:27:30Z", count: 1, sections: ["static-route"] },
    { device_id: "d8", hostname: "FW-ROMEO-01-M1", vendor: "palo_alto", category: "admin_access", changed_at: "2026-11-02T09:00:00Z", count: 1, sections: ["users"] },
  ],
};

describe("grouping simultaneous identical deviations", () => {
  it("puts devices with the same change in the same minute on one line and leaves the others alone", () => {
    const groups = groupSimultaneousDeviations(majorDeviationsByDevice(deviations.active_major_deviations));
    expect(groups.map((g) => g.devices.length)).toEqual([1, 3, 1]);
    expect(groups[1].devices.map((d) => d.deviceId).sort()).toEqual(["s1", "s2", "s3"]);
  });

  it("never merges a different minute or a different change", () => {
    const rows = [same(1), { ...same(2), changed_at: "2026-11-03T15:28:01Z" }, { ...same(3), sections: ["roles"] }];
    expect(groupSimultaneousDeviations(majorDeviationsByDevice(rows)).map((g) => g.devices.length)).toEqual([1, 1, 1]);
  });

  it("renders 'N devices, same minute, <change>' with the devices behind a disclosure", () => {
    render(<MajorDeviationList deviations={deviations} />);
    const group = screen.getByTestId("deviation-group");
    expect(group.textContent).toContain("3 devices, same minute, Admin access: users");
    expect(within(group).queryByRole("link", { name: /FW-HOTEL-01-M2/ })).toBeNull();
    fireEvent.click(within(group).getByRole("button", { name: "Show 3 devices" }));
    expect(within(group).getByRole("link", { name: "FW-HOTEL-01-M2: open the configuration changes" })).toBeInTheDocument();
    // a device alone keeps its own line
    expect(screen.getByRole("link", { name: "FW-ROMEO-01-M1: open the configuration changes" })).toBeInTheDocument();
  });
});

describe("the scheduled fleet backup stale badge", () => {
  const now = new Date("2026-11-05T05:00:00Z");
  it("reads Stale with the age once the daily run is overdue", () => {
    expect(scheduledBackupStaleWords({ schedule_enabled: true, last_scheduled_run_at: "2026-11-03T06:00:00Z" }, now)).toBe("Stale · 47 h ago");
  });
  it("stays silent inside the window, when off, or when the run is unknown", () => {
    const fresh = new Date(now.getTime() - (SCHEDULE_STALE_HOURS - 1) * 3600_000).toISOString();
    expect(scheduledBackupStaleWords({ schedule_enabled: true, last_scheduled_run_at: fresh }, now)).toBeNull();
    expect(scheduledBackupStaleWords({ schedule_enabled: false, last_scheduled_run_at: "2026-11-01T00:00:00Z" }, now)).toBeNull();
    expect(scheduledBackupStaleWords({ schedule_enabled: true, last_scheduled_run_at: null }, now)).toBeNull();
    expect(scheduledBackupStaleWords(null, now)).toBeNull();
  });
});

describe("the Backups screen layout (B2)", () => {
  const devices = [
    { device_id: "d9", role: "gateway", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04-M1", model: "Check Point", software_version: "R81.20", ha_role: "ACTIVE", cluster_member_ref: null, backup_target: true },
  ];
  const backups = [{ artefact_id: "a1", device_id: "d9", collected_at: "2026-11-03T06:00:00Z", size_bytes: 2048, digest_prefix: "x", validation_level: "V1", deviation_state: "changed", vendor: "check_point" }];
  afterEach(() => vi.unstubAllGlobals());

  it("keeps the deviation list out of the page until the card opens it in a drawer, and badges an overdue schedule", async () => {
    const policy = { schedule_enabled: true, daily_backup_cron: "0 2 * * *", backup_retention_days: 7, snapshot_retention_depth: 1, last_scheduled_run_at: "2026-11-01T23:00:00Z" };
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const body = url.startsWith("/devices") ? { devices }
        : url.startsWith("/api/v2/backups/policies") ? policy
        : url.startsWith("/api/v2/backups/deviations") ? deviations
        : url.startsWith("/backups") ? { backups, baselines: {} } : {};
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    }));
    render(<BackupScreen />);
    await screen.findByText("FW-TANGO-04-M1", { selector: "td *, td" });
    await waitFor(() => expect(screen.getByText(/^Stale · /)).toBeInTheDocument());
    expect(document.getElementById("major-deviations")).toBeNull();
    const card = screen.getByText("Active major deviations").closest("[data-kpi]")!;
    await waitFor(() => expect(within(card as HTMLElement).getByRole("link", { name: "Which devices →" })).toBeInTheDocument());
    fireEvent.click(within(card as HTMLElement).getByRole("link", { name: "Which devices →" }));
    expect(await screen.findByTestId("deviation-group")).toBeInTheDocument();
    expect(document.getElementById("major-deviations")).not.toBeNull();
  });
});
