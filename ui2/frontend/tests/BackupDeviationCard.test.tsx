import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { BackupDeviations } from "../src/auth/adminApi";
import { BackupDeviationCard, MajorDeviationList, configChangesHref, majorDeviationsByDevice } from "../src/screens/BackupDeviationCard";
import { BackupScreen, withMajorDeviations, type BackupDeviceItem } from "../src/screens/BackupScreen";
import { DeviceConfigurationPanels } from "../src/screens/ConfigurationPanels";

// docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md -- masked pseudonyms only, as the replay viewer sees them.
const deviations: BackupDeviations = {
  window_days: 7,
  total_deviations_checked: 112,
  devices_known: 124,
  active_major_deviations: [
    { device_id: "d1", hostname: "FW-TANGO-04-M1", vendor: "check_point", category: "routing", changed_at: "2026-10-22T06:00:00Z", count: 1, sections: ["static-route"] },
    { device_id: "d1", hostname: "FW-TANGO-04-M1", vendor: "check_point", category: "interfaces", changed_at: "2026-10-21T06:00:00Z", count: 2, sections: ["interface"] },
    { device_id: "d2", hostname: "FW-ROMEO-01-M1", vendor: "palo_alto", category: "admin_access", changed_at: "2026-10-20T06:00:00Z", count: 1, sections: ["users"] },
  ],
};

describe("Active major deviations card (2026-10-23)", () => {
  it("counts devices, not rows, and says how many devices were compared", () => {
    render(<BackupDeviationCard deviations={deviations} />);
    const card = screen.getByText("Active major deviations").closest("[data-kpi]")!;
    expect(card.querySelector("[data-kpi-figure]")?.textContent).toBe("2");
    expect(card.textContent).toContain("112 of 124 devices compared · last 7 days");
    expect(card.textContent).not.toContain("UNKNOWN");
    expect(within(card as HTMLElement).getByRole("link", { name: "Which devices →" })).toHaveAttribute("href", "#major-deviations");
  });

  it("reads 0, not UNKNOWN, when devices were compared and none changed in a major section", () => {
    render(<BackupDeviationCard deviations={{ ...deviations, active_major_deviations: [] }} />);
    const card = screen.getByText("Active major deviations").closest("[data-kpi]")!;
    expect(card.querySelector("[data-kpi-figure]")?.textContent).toBe("0");
    expect(within(card as HTMLElement).queryByRole("link")).toBeNull();
  });

  it("stays UNKNOWN only when no device could be compared", () => {
    render(<BackupDeviationCard deviations={{ active_major_deviations: [], total_deviations_checked: 0, devices_known: 124, window_days: 7 }} />);
    const card = screen.getByText("Active major deviations").closest("[data-kpi]")!;
    expect(card.textContent).toContain("UNKNOWN");
    expect(card.textContent).toContain("0 of 124 devices compared · last 7 days");
  });

  it("links every listed device to its configuration Changes tab", () => {
    render(<MajorDeviationList deviations={deviations} />);
    const tango = screen.getByRole("link", { name: "FW-TANGO-04-M1: open the configuration changes" });
    expect(tango).toHaveAttribute("href", "?screen=inventory&device_id=d1&tab=configuration&config_tab=changes");
    expect(screen.getByRole("link", { name: "FW-ROMEO-01-M1: open the configuration changes" })).toHaveAttribute("href", configChangesHref("d2"));
    const list = document.getElementById("major-deviations")!;
    expect(list.textContent).toContain("Routing");
    expect(list.textContent).toContain("Interfaces");
    expect(list.textContent).toContain("Admin access");
    expect(majorDeviationsByDevice(deviations.active_major_deviations).map((r) => r.deviceId)).toEqual(["d1", "d2"]);
  });

  it("marks the fleet row of a device with a major deviation and leaves the others", () => {
    const rows = [{ deviceId: "d1", deviationState: "CHANGED" }, { deviceId: "d3", deviationState: "UNCHANGED" }] as unknown as BackupDeviceItem[];
    expect(withMajorDeviations(rows, deviations).map((r) => r.deviationState)).toEqual(["MAJOR DEVIATION", "UNCHANGED"]);
    expect(withMajorDeviations(rows, null).map((r) => r.deviationState)).toEqual(["CHANGED", "UNCHANGED"]);
  });
});

describe("the Backups screen with comparisons (2026-10-23)", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows the count, the device links and the major word in the fleet table", async () => {
    const devices = [
      { device_id: "d1", role: "gateway", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04-M1", backup_target: true },
    ];
    const backups = [{ artefact_id: "a1", device_id: "d1", collected_at: "2026-10-22T02:00:00Z", size_bytes: 2048, digest_prefix: "x", validation_level: "V1", deviation_state: "changed", vendor: "check_point" }];
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const body = url.startsWith("/devices") ? { devices }
        : url.startsWith("/api/v2/backups/policies") ? { schedule_enabled: true, daily_backup_cron: "0 2 * * *", backup_retention_days: 14, snapshot_retention_depth: 1 }
        : url.startsWith("/api/v2/backups/deviations") ? deviations
        : url.startsWith("/backups") ? { backups, baselines: {} } : {};
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    }));
    render(<BackupScreen />);
    expect(await screen.findByRole("link", { name: "FW-TANGO-04-M1: open the configuration changes" })).toHaveAttribute("href", configChangesHref("d1"));
    const card = screen.getByText("Active major deviations", { selector: "[data-kpi] p" }).closest("[data-kpi]")!;
    expect(card.querySelector("[data-kpi-figure]")?.textContent).toBe("2");
    expect(await screen.findByText("Major change")).toBeInTheDocument();
  });
});

describe("the configuration Changes tab (2026-10-23)", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("opens on Changes when asked and says which sections made the change major", async () => {
    const configuration = {
      device_id: "d1", text_readable: false, collected_at: "2026-10-22T06:00:00Z", vendor: "check_point", read_kind: "show_configuration",
      canonical_hash: "abc", change_state: "changed", withheld_line_count: 0, sanitized_text_available: false, index: [], overrides: [],
      supplementary_runs: [],
      change_deviation_summary: { status: "computed", entries: [
        { context: "physical", section: "static-route", kind: "recounted", old_count: 3, new_count: 4, category: "routing" },
        { context: "physical", section: "ntp", kind: "modified", old_count: 1, new_count: 1, category: null },
      ] },
    };
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const body = url.startsWith("/devices/d1/configuration") ? configuration : url.startsWith("/devices/d1") ? { device_id: "d1", facts: {} } : {};
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    }));
    render(<DeviceConfigurationPanels deviceId="d1" hostname="FW-TANGO-04-M1" vendorHint="check_point" initialTab="Changes" />);
    expect(await screen.findByText("Major · Routing")).toBeInTheDocument();
    expect(screen.getByText("Minor")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Changes" })).toHaveAttribute("aria-selected", "true");
  });
});

// V180 / PO decision 2026-10-30 (docs/design/BACKUP_SIZE_ANOMALY_RETENTION.md): masked pseudonyms, sizes and ratio only.
const GIB = 1024 ** 3;
const withSize: BackupDeviations = {
  ...deviations,
  active_major_deviations: [],
  size_anomalies: [{
    device_id: "d9", hostname: "FW-JULIET-06-M1", deviation_id: "dv-1", artefact_id: "a-bad", last_good_artefact_id: "a-good",
    direction: "growth", plaintext_bytes: 29 * GIB, median_bytes: GIB, ratio: 29, summary: "Backup size anomaly: grew 29.0x",
    detected_at: "2026-10-30T02:00:00Z", open_copies: 7,
  }],
  can_accept_size: true,
  size_role_note: null,
};

describe("backup size anomalies on the major deviations card (2026-10-30)", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("counts a device with an open size anomaly, even with no configuration comparison", () => {
    render(<BackupDeviationCard deviations={{ ...withSize, total_deviations_checked: 0 }} />);
    const card = screen.getByText("Active major deviations").closest("[data-kpi]")!;
    expect(card.querySelector("[data-kpi-figure]")?.textContent).toBe("1");
    expect(card.textContent).not.toContain("UNKNOWN");
  });

  it("shows sizes, ratio and copies, and accepts the new size with the reason only", async () => {
    const calls: { url: string; method?: string; body?: string }[] = [];
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method, body: init?.body as string | undefined });
      return Promise.resolve(new Response(JSON.stringify({ device_id: "d9", resolved: 7, status: "ACCEPTED" }), { status: 200 }));
    }));
    const onChanged = vi.fn();
    render(<MajorDeviationList deviations={withSize} onChanged={onChanged} />);
    const list = document.getElementById("major-deviations")!;
    expect(list.textContent).toContain("FW-JULIET-06-M1");
    expect(list.textContent).toContain("Grew 29.0x");
    expect(list.textContent).toContain("1.0 GB → 29.0 GB");
    expect(list.textContent).toContain("7 anomalous backups (only the newest kept)");
    expect(list.textContent).not.toContain("Major configuration changes");

    fireEvent.click(screen.getByRole("button", { name: "Accept new size" }));
    const accept = screen.getByRole("button", { name: "Accept" });
    expect(accept).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "local logs are kept on purpose" } });
    fireEvent.click(accept);
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.url).toBe("/api/v2/backups/deviations/size-anomalies/d9/accept");
    expect(JSON.parse(post.body!)).toEqual({ reason: "local logs are kept on purpose" });
  });

  it("offers no action without the role and says who can accept", () => {
    render(<MajorDeviationList deviations={{ ...withSize, can_accept_size: false, size_role_note: "Only a security_admin can accept a new backup size." }} />);
    expect(screen.queryByRole("button", { name: "Accept new size" })).toBeNull();
    expect(screen.getByText("Only a security_admin can accept a new backup size.")).toBeInTheDocument();
  });

  it("marks the fleet row of a device with a size anomaly", () => {
    const rows = [{ deviceId: "d9", deviationState: "CHANGED" }] as unknown as BackupDeviceItem[];
    expect(withMajorDeviations(rows, withSize).map((r) => r.deviationState)).toEqual(["MAJOR DEVIATION"]);
  });
});
