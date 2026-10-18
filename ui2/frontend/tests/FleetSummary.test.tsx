import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { FleetSummary } from "../src/screens/FleetSummary";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import { OverviewScreen } from "../src/screens/OverviewScreen";
import type { DeviceSummary, OverviewView } from "../src/auth/adminApi";

/**
 * The Devices landing (UI review 2026-10-18, P1-1): the estate inventory summary where the empty "Select a device or
 * cluster" pane was. It is the Overview's "Estate details" itself, so both use these fixtures. Synthetic names only.
 */
const NOW = new Date().toISOString();
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
afterEach(() => vi.unstubAllGlobals());

function overview(partial: Partial<OverviewView> = {}): OverviewView {
  return {
    generated_at: NOW, masked: true,
    denominators: { active_devices: 3, gateways: 3, clusters: 1, backup_targets: 3 },
    evidence: { inventory: { at: NOW, state: "OK" }, configuration: { at: NOW, state: "OK" }, compliance: { at: null, state: "UNKNOWN" },
      backup: { at: NOW, state: "OK" }, jobs: { at: NOW, state: "OK" }, platform_facts: { at: NOW, state: "OK" } },
    attention: { failed_jobs_24h: { count: 0, terminal_24h: 10, state: "OK" }, stale_inventory: { count: 0, of: 3, state: "OK" },
      cluster_diff: { count: 0, of: 1, unknown: 0, state: "OK" }, config_changed: { count: 0, of: 3, state: "OK" }, backup_missing: { count: 0, of: 3, state: "OK" } },
    exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] }, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } },
    inventory_age: { lt24h: 2, h24_72: 1, gt72h: 0, never: 0, of: 3 },
    compliance: { state: "UNKNOWN", reason: "not_evaluated" },
    platform: { devices: 3, check_point: 2, palo_alto: 1, clusters: 1, evidence_at: NOW,
      hotfix_levels: [{ level: "R81.20 Jumbo Take 119", count: 2 }],
      versions: {
        check_point: { major: [{ label: "R81.20", count: 2 }], minor: [{ label: "R81.20 Jumbo Take 119", count: 1 }, { label: "R81.20 Jumbo Take 100", count: 1 }],
          model: [{ label: "Check Point 6600", count: 1 }, { label: "Check Point 28000", count: 1 }] },
        palo_alto: { major: [{ label: "10.2", count: 1 }], minor: [{ label: "10.2.7", count: 1 }], model: [{ label: "PA-440", count: 1 }] },
      } },
    policy_install: { state: "OK", of: 3, today: 1, yesterday: 1, days_2_7: 1, older: 0, unknown: 0, oldest_at: "2026-10-12T20:00:00Z", newest_at: "2026-10-18T20:00:00Z", last_read_at: NOW },
    nexus: { completed_24h: 20, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: NOW, palo_alto: NOW } },
    ...partial,
  };
}
const mount = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
const rows = [
  { device_id: "d1", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-01", model: "Quantum", software_version: "R81.20", ha_role: null, cluster_member_ref: null, role: "gateway" },
  { device_id: "d2", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-02", model: "PA-440", software_version: "10.2.7", ha_role: null, cluster_member_ref: null, role: "gateway" },
];

describe("the other vendors' versions (post-release review 2026-10-18, 1.5)", () => {
  it("adds one bar per vendor from the device list already loaded, each slice opening the filtered list; none without such devices", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(overview())));
    const fleet = [
      ...rows,
      { device_id: "d3", vendor_hint: "fortinet", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-03", model: "FortiGate-100F", software_version: "v7.4.12 build2902", ha_role: null, cluster_member_ref: null, role: "gateway" },
      { device_id: "d4", vendor_hint: "fortinet", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-04", model: "FMG-VM64", software_version: "v7.4.11-build2804", ha_role: null, cluster_member_ref: null, role: "management_server" },
      { device_id: "d5", vendor_hint: "cisco_asa", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-05", model: "ASA 5516", software_version: null, ha_role: null, cluster_member_ref: null, role: "gateway" },
      { device_id: "d6", vendor_hint: "radware", enrollment_state: "REGISTERED", hostname: "FW-SYNTH-06", model: "DefensePro", software_version: "8.32", ha_role: null, cluster_member_ref: null, role: "appliance" },
    ] as unknown as DeviceSummary[];
    const { unmount } = mount(<FleetSummary countLabel="6 registered · 5 enrolled" fleet={fleet} />);
    const card = (await screen.findByText("Other vendors · version as reported")).closest("[data-reported-versions]") as HTMLElement;
    expect(within(card).getByText("Fortinet · 2 devices")).toBeInTheDocument();
    expect(within(card).getByText("Cisco ASA · 1 device")).toBeInTheDocument();
    // a device that is not enrolled is not in service: no Radware bar; vendors with no device get none either
    expect(within(card).queryByText(/^Radware/)).toBeNull();
    expect(within(card).queryByText(/^Blue Coat|^Infoblox/)).toBeNull();
    const hrefs = within(card).getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toContain("?screen=inventory&vendor=fortinet&sw_version=v7.4.12+build2902");
    unmount();
    mount(<FleetSummary countLabel="2 registered · 2 enrolled" fleet={rows as unknown as DeviceSummary[]} />);
    await screen.findByText("Policy installed");
    expect(screen.queryByText("Other vendors · version as reported")).toBeNull();
  });
});

describe("the summary itself", () => {
  it("shows the count sentence, the guidance and the Overview's estate details: policy installed, hardware models, software versions, evidence age", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(overview())));
    mount(<FleetSummary countLabel="3 registered · 2 enrolled" />);
    expect(screen.getByRole("heading", { name: "Fleet summary" })).toBeInTheDocument();
    expect(screen.getByText(/^3 registered · 2 enrolled\. Choose one on the left to see its interfaces/)).toBeInTheDocument();
    expect(await screen.findByText("Policy installed")).toBeInTheDocument();
    expect(screen.getByText("Software and hardware")).toBeInTheDocument();
    expect(screen.getByText("Inventory evidence age")).toBeInTheDocument();
    // hardware models and software versions are the slices of the per-vendor figures
    expect(screen.getByText("MAJOR VERSION")).toBeInTheDocument();
    expect(screen.getByText("PAN-OS MAJOR")).toBeInTheDocument();
  });

  it("keeps the Overview's links: each slice and bucket opens the filtered device list", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json(overview())));
    mount(<FleetSummary countLabel="3 registered · 2 enrolled" />);
    await screen.findByText("Software and hardware");
    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href") ?? "");
    expect(hrefs).toContain("?screen=inventory&vendor=check_point");
    expect(hrefs).toContain("?screen=inventory&vendor=check_point&sw_major=R81.20");
    expect(hrefs).toContain("?screen=inventory&vendor=palo_alto&sw_version=10.2.7");
    expect(hrefs.some((h) => h.startsWith("?screen=inventory&vendor=check_point&hw_model="))).toBe(true);
    expect(hrefs).toContain("?screen=inventory&inventory_age=24h_72h");
    // the card's own "Open Configuration" would lead back to this very screen
    expect(screen.queryByRole("link", { name: "Open Configuration" })).toBeNull();
  });

  it("is the same component the Overview discloses as Estate details", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url.includes("/compliance/controls") ? json({ total_controls: 0, controls: [] }) : json(overview())));
    mount(<OverviewScreen />);
    fireEvent.click(await screen.findByRole("button", { name: /Estate details: policy installed · hardware models · inventory evidence age/ }));
    expect(await screen.findByText("Policy installed")).toBeInTheDocument();
    expect(screen.getByText("Software and hardware")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open Configuration" })).toHaveAttribute("href", "?screen=inventory");
  });

  it("says so, without an alert, when the summary cannot be shown, and a restriction is a restriction", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json({ error: "BOOM" }, 500)));
    const first = mount(<FleetSummary countLabel="2 enrolled devices" />);
    expect(await screen.findByText("Estate summary is not shown")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    first.unmount();
    vi.stubGlobal("fetch", vi.fn(() => json({ error: "ACTION_REFUSED" }, 403)));
    mount(<FleetSummary countLabel="2 enrolled devices" />);
    expect(await screen.findByText("Estate summary")).toBeInTheDocument();
    expect(screen.getByText(/can not view or change it/)).toBeInTheDocument();
  });

  it("does not crash on a summary that lacks its platform figures", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json({ devices: [] })));
    mount(<FleetSummary countLabel="2 enrolled devices" />);
    expect(await screen.findByText("Estate summary is not shown")).toBeInTheDocument();
  });
});

describe("on the Devices screen", () => {
  const stubScreen = () => vi.stubGlobal("fetch", vi.fn((url: string) => {
    if (url === "/devices") return json({ devices: rows });
    if (url === "/api/v2/overview") return json(overview());
    if (url === "/devices/d1/inventory") return json({ device_id: "d1", collected_at: NOW, contexts: [] });
    return json({ error: "NOT_FOUND" }, 404);
  }));

  it("fills the pane that used to say 'Select a device or cluster', and not when there is no device", async () => {
    stubScreen();
    mount(<InventoryScreen />);
    expect(await screen.findByTestId("fleet-summary")).toBeInTheDocument();
    expect(screen.queryByText("Select a device or cluster")).toBeNull();
    expect(await screen.findByText("Policy installed")).toBeInTheDocument();
  });

  it("offers a Fleet summary control while a device is open, and it returns to the summary", async () => {
    stubScreen();
    mount(<InventoryScreen />);
    fireEvent.click(await screen.findByText("FW-SYNTH-01"));
    await screen.findByRole("tablist", { name: "Device detail" });
    expect(screen.queryByTestId("fleet-summary")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "← Fleet summary" }));
    expect(await screen.findByTestId("fleet-summary")).toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: "Device detail" })).toBeNull();
    expect(screen.queryByRole("button", { name: "← Fleet summary" })).toBeNull();
    // the list keeps working: the device can be opened again
    fireEvent.click(screen.getByText("FW-SYNTH-01"));
    expect(await screen.findByRole("tablist", { name: "Device detail" })).toBeInTheDocument();
  });

  it("keeps 'No device to show' for an empty estate and makes no summary request", async () => {
    const fetchMock = vi.fn((url: string) => url === "/devices" ? json({ devices: [] }) : json({}, 404));
    vi.stubGlobal("fetch", fetchMock);
    mount(<InventoryScreen />);
    expect(await screen.findByText("No device to show")).toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(fetchMock.mock.calls.some(([url]) => url === "/api/v2/overview")).toBe(false);
    expect(within(document.body).queryByTestId("fleet-summary")).toBeNull();
  });
});
