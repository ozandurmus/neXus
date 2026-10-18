import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import type { DeviceSummary } from "../src/auth/adminApi";
import { DeviceList, InventoryScreen } from "../src/screens/InventoryScreen";

/**
 * The Devices tree (UI review 2026-10-18, F-18): member rows under a cluster that has no virtual systems, the parent kept
 * highlighted while a member is open, and the tree scrolled to the open device. Synthetic names only.
 */
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));

const dev = (id: string, hostname: string, extra: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: id, hostname, vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
  cluster_member_ref: null, virtual_systems: null, model: "Quantum", software_version: "R81.20", ha_role: null, ip_addresses: "192.0.2.1", ...extra,
} as unknown as DeviceSummary);
const devices = [
  dev("m2", "FW-ROMEO-01-M2", { cluster_member_ref: "CLS-ROMEO-01", ha_role: "STANDBY" }),
  dev("m1", "FW-ROMEO-01-M1", { cluster_member_ref: "CLS-ROMEO-01", ha_role: "ACTIVE" }),
  dev("v1", "FW-VSX-01-M1", { cluster_member_ref: "CLS-VSX-01", virtual_systems: "VS-SYNTH-01, VS-SYNTH-02" }),
  dev("v2", "FW-VSX-01-M2", { cluster_member_ref: "CLS-VSX-01", virtual_systems: "VS-SYNTH-01, VS-SYNTH-02" }),
  dev("solo", "FW-SOLO-09"),
];
const clusterRow = (ref: string) => screen.getByText(ref).closest('[data-row="cluster"]') as HTMLElement;
const props = { selectedDeviceId: null, selectedClusterRef: null, onSelectDevice: vi.fn(), onSelectCluster: vi.fn() };
const mount = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);

describe("member rows", () => {
  it("opens a cluster with no virtual systems onto its members, M1 then M2, and the chevron says so", () => {
    mount(<DeviceList devices={devices} {...props} />);
    expect(screen.queryByText("FW-ROMEO-01-M1")).toBeNull();
    const chevron = within(clusterRow("CLS-ROMEO-01")).getByRole("button", { name: "▼" });
    expect(chevron).toHaveAttribute("title", "Expand members");
    expect(chevron).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(chevron);
    const names = screen.getAllByText(/^FW-ROMEO-01-M[12]$/).map((n) => n.textContent);
    expect(names).toEqual(["FW-ROMEO-01-M1", "FW-ROMEO-01-M2"]);
    expect(within(clusterRow("CLS-ROMEO-01")).getByRole("button", { name: "▲" })).toHaveAttribute("title", "Collapse members");
    fireEvent.click(within(clusterRow("CLS-ROMEO-01")).getByRole("button", { name: "▲" }));
    expect(screen.queryByText("FW-ROMEO-01-M1")).toBeNull();
  });

  it("opens the member's own device screen when a member row is clicked, not the cluster", () => {
    const onSelectDevice = vi.fn();
    const onSelectCluster = vi.fn();
    mount(<DeviceList devices={devices} {...props} onSelectDevice={onSelectDevice} onSelectCluster={onSelectCluster} />);
    fireEvent.click(within(clusterRow("CLS-ROMEO-01")).getByTitle("Expand members"));
    fireEvent.click(screen.getByText("FW-ROMEO-01-M2"));
    expect(onSelectDevice).toHaveBeenCalledWith(expect.objectContaining({ device_id: "m2" }));
    expect(onSelectCluster).not.toHaveBeenCalled();
  });

  it("keeps a cluster with virtual systems as it was: its virtual systems, no member rows", () => {
    mount(<DeviceList devices={devices} {...props} />);
    expect(screen.getByText("VS-SYNTH-01")).toBeInTheDocument();
    expect(screen.queryByText("FW-VSX-01-M1")).toBeNull();
    expect(within(clusterRow("CLS-VSX-01")).getByTitle("Collapse virtual systems")).toBeInTheDocument();
  });

  it("shows no chevron where it would open nothing", () => {
    mount(<DeviceList devices={devices} {...props} showMembers={false} />);
    expect(within(clusterRow("CLS-ROMEO-01")).queryByRole("button")).toBeNull();
  });
});

describe("the open device", () => {
  it("keeps the parent cluster highlighted and opens its members when a member is open", () => {
    mount(<DeviceList devices={devices} {...props} selectedDeviceId="m1" />);
    const cluster = clusterRow("CLS-ROMEO-01");
    expect(cluster).toHaveAttribute("data-holds-selected", "true");
    expect(cluster).not.toHaveAttribute("data-selected");
    const member = screen.getByText("FW-ROMEO-01-M1").closest('[data-row="device"]') as HTMLElement;
    expect(member).toHaveAttribute("data-selected", "true");
    expect(screen.getByText("FW-ROMEO-01-M2").closest('[data-row="device"]')).not.toHaveAttribute("data-selected");
    // another cluster is not highlighted
    expect(clusterRow("CLS-VSX-01")).not.toHaveAttribute("data-holds-selected");
  });

  it("marks the cluster itself as selected, not as a parent, when the cluster is open", () => {
    mount(<DeviceList devices={devices} {...props} selectedClusterRef="CLS-ROMEO-01" />);
    expect(clusterRow("CLS-ROMEO-01")).toHaveAttribute("data-selected", "true");
    expect(clusterRow("CLS-ROMEO-01")).not.toHaveAttribute("data-holds-selected");
  });

  it("scrolls the tree to the open device, as far as needed and no further", () => {
    const scroll = vi.fn();
    window.HTMLElement.prototype.scrollIntoView = scroll;
    mount(<DeviceList devices={devices} {...props} selectedDeviceId="m2" />);
    expect(scroll).toHaveBeenCalledWith({ block: "nearest" });
    expect((scroll.mock.contexts.at(-1) as HTMLElement).textContent).toContain("FW-ROMEO-01-M2");
  });

  it("scrolls to an open cluster and to its virtual system", () => {
    const scroll = vi.fn();
    window.HTMLElement.prototype.scrollIntoView = scroll;
    mount(<DeviceList devices={devices} {...props} selectedClusterRef="CLS-VSX-01" selectedVs="VS-SYNTH-02" />);
    expect((scroll.mock.contexts.at(-1) as HTMLElement).textContent).toContain("VS-SYNTH-02");
  });

  it("opens the manager and domain that hold the open member, so the tree can scroll to it", async () => {
    const scroll = vi.fn();
    window.HTMLElement.prototype.scrollIntoView = scroll;
    const node = (device_id: string | null, children: unknown[] = []) => ({ kind: "X", category: "device", device_id, imported: device_id !== null,
      ha_role: null, enrollment_state: null, last_collection_state: null, last_collection_at: null, model: null, candidate_id: null,
      ack_token: null, acknowledged: false, acknowledged_reason: null, children });
    const tree = { device_id: "mds", vendor: "check_point", run_id: "r", discovered_at: "2026-09-20T00:00:00Z", counts: {},
      domains: [{ domain: "DOM-ONE", nodes: [node(null, [node("m1")])] }, { domain: "DOM-TWO", nodes: [node("solo")] }] };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(tree))));
    const all = [dev("mds", "FW-MDS-01", { role: "management_server" }), ...devices];
    await act(async () => { mount(<DeviceList groupByManager devices={all} {...props} selectedDeviceId="m1" />); });
    await waitFor(() => expect(screen.getByText("FW-ROMEO-01-M1")).toBeInTheDocument());
    expect(scroll).toHaveBeenCalled();
  });
});

describe("on the Devices screen", () => {
  const stubFleet = () => vi.stubGlobal("fetch", vi.fn((url: string) => {
    if (url === "/devices") return json({ devices });
    if (url === "/api/v2/overview") return json({}, 404);
    if (url === "/clusters/CLS-ROMEO-01/inventory") return json({ cluster_member_ref: "CLS-ROMEO-01", members: [{ device_id: "m1", hostname: "FW-ROMEO-01-M1" }, { device_id: "m2", hostname: "FW-ROMEO-01-M2" }], contexts: [] });
    if (url.startsWith("/devices/m2/")) return json({ device_id: "m2", collected_at: null, contexts: [] });
    return json({ error: "NOT_FOUND" }, 404);
  }));

  it("opens the member's device screen from the tree and keeps its cluster highlighted", async () => {
    stubFleet();
    mount(<InventoryScreen />);
    await screen.findByText("CLS-ROMEO-01");
    fireEvent.click(within(clusterRow("CLS-ROMEO-01")).getByTitle("Expand members"));
    fireEvent.click(screen.getByText("FW-ROMEO-01-M2"));
    expect(await screen.findByRole("heading", { name: "FW-ROMEO-01-M2" })).toBeInTheDocument();
    expect(clusterRow("CLS-ROMEO-01")).toHaveAttribute("data-holds-selected", "true");
    expect(screen.getByRole("button", { name: "← Fleet summary" })).toBeInTheDocument();
  });

  it("opens a member named by ?device_id= with its cluster expanded and highlighted", async () => {
    window.history.replaceState({}, "", "/?screen=inventory&device_id=m2");
    stubFleet();
    mount(<InventoryScreen />);
    expect(await screen.findByRole("heading", { name: "FW-ROMEO-01-M2" })).toBeInTheDocument();
    expect(clusterRow("CLS-ROMEO-01")).toHaveAttribute("data-holds-selected", "true");
    // the member rows open under the cluster and the open member's row is marked
    await waitFor(() => expect(screen.getAllByText("FW-ROMEO-01-M2").length).toBeGreaterThan(1));
    expect(screen.getAllByText("FW-ROMEO-01-M2").map((el) => el.closest('[data-row="device"]')).filter(Boolean)[0]).toHaveAttribute("data-selected", "true");
    window.history.replaceState({}, "", "/");
  });
});

describe("tree rows (post-release review 2026-10-18, R2)", () => {
  const fortigate = dev("fg", "FW-SYNTH-FORTIGATE-WITH-A-LONG-NAME-07", { vendor_hint: "fortinet", ha_role: "PRIMARY", ha_peer_unconfirmed: true, virtual_systems: "root, dmz, guest" } as Partial<DeviceSummary>);
  const panStandalone = dev("pa", "FW-SYNTH-PA-01", { vendor_hint: "palo_alto", ha_role: "STANDALONE", virtual_systems: "vsys1, vsys2" });
  const mdsUnknown = dev("mds", "FW-MDS-UNKNOWN", { role: "management_server", ha_role: "UNKNOWN" });
  const rowOf = (name: string) => screen.getByText(name).closest('[data-row="device"]') as HTMLElement;

  it("gives the name its own line and never cuts it, even on a selected row full of pills", () => {
    mount(<DeviceList devices={[fortigate, panStandalone, mdsUnknown]} {...props} selectedDeviceId="fg" />);
    const name = screen.getByText("FW-SYNTH-FORTIGATE-WITH-A-LONG-NAME-07");
    expect(name).toHaveAttribute("data-device-name");
    expect(getComputedStyle(name).whiteSpace).toBe("normal");
    expect(getComputedStyle(name).textOverflow).not.toBe("ellipsis");
    expect(rowOf("FW-SYNTH-FORTIGATE-WITH-A-LONG-NAME-07")).toHaveAttribute("data-selected", "true");
    // the VDOM pill is on the second line, which wraps, beside vendor · model · version
    const row = rowOf("FW-SYNTH-FORTIGATE-WITH-A-LONG-NAME-07");
    let line: HTMLElement | null = within(row).getByText("3 VDOM");
    while (line && line !== row && getComputedStyle(line).flexWrap !== "wrap") line = line.parentElement;
    expect(line).not.toBe(row);
    expect(line).toHaveTextContent("Fortinet · Quantum · R81.20");
    expect(line).not.toHaveTextContent("FW-SYNTH-FORTIGATE-WITH-A-LONG-NAME-07");
  });

  it("carries no HA sentence and no role pill outside a cluster: STANDALONE, UNKNOWN and a named peer belong to the header", () => {
    mount(<DeviceList devices={[fortigate, panStandalone, mdsUnknown, ...devices]} {...props} />);
    expect(screen.queryByText(/peer named/)).toBeNull();
    expect(document.querySelector('[data-role="STANDALONE"]')).toBeNull();
    expect(document.querySelector('[data-role="UNKNOWN"]')).toBeNull();
    expect(document.querySelector('[data-role="PRIMARY"]')).toBeNull();
    // a cluster member keeps its role pill
    fireEvent.click(within(clusterRow("CLS-ROMEO-01")).getByTitle("Expand members"));
    expect(within(rowOf("FW-ROMEO-01-M1")).getByText("ACTIVE")).toHaveAttribute("data-role", "ACTIVE");
    expect(within(rowOf("FW-ROMEO-01-M2")).getByText("STANDBY")).toHaveAttribute("data-role", "STANDBY");
  });

  it("shows no green tick: a completed latest job is no glyph in the tree", () => {
    mount(<DeviceList devices={[dev("done", "FW-SYNTH-DONE", { latest_job_state: "COMPLETED", latest_job_type: "cp_inventory_collect" })]} {...props} />);
    expect(screen.queryByText("✓")).toBeNull();
    expect(rowOf("FW-SYNTH-DONE")).toHaveTextContent("FW-SYNTH-DONE");
  });
});
