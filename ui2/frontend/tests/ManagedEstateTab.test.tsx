import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { DeviceInventoryPanels } from "../src/screens/InventoryPanels";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import type { DeviceSummary, ManagementTree } from "../src/auth/adminApi";

/**
 * Management servers (UI review 2026-10-18, P0-2B): the device header comes first and what the server manages is a
 * "Managed estate" tab on all five manager types, keeping every row, chevron, pill and read time.
 */
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
afterEach(() => vi.unstubAllGlobals());

const manager = (vendor: string, over: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: `mgr-${vendor}`, hostname: "FW-SYNTH-MGR", vendor_hint: vendor, role: "management_server", enrollment_state: "ENROLLED", model: "Management", software_version: "1.0",
  ha_role: null, cluster_member_ref: null, ...over,
});
const base = {
  device_id: null, imported: false, ha_role: null, enrollment_state: null, last_collection_state: null, last_collection_at: null, model: null,
  candidate_id: null, ack_token: null, acknowledged: false, acknowledged_reason: null, children: [],
} as const;
const tree = (vendor: string): ManagementTree => ({
  device_id: `mgr-${vendor}`, vendor, run_id: "run-1", discovered_at: "2026-10-18T03:00:00Z",
  counts: { domains: 1, clusters: 1, gateways: 3, virtual_systems: 0, imported: 2, management_appliances: 1, not_in_nexus: 1, acknowledged: 0 },
  domains: [{ domain: "DOM-SYNTH", nodes: [
    { ...base, kind: "PLAIN_HIGH_AVAILABILITY_CLUSTER", category: "cluster", cluster_member_ref: "CLS-SYNTH-01", children: [
      { ...base, kind: "PLAIN_CLUSTER_MEMBER", category: "device", hostname: "FW-SYNTH-M1", device_id: "m1", imported: true, ha_role: "ACTIVE", last_collection_state: "COMPLETED", last_collection_at: "2026-10-18T02:00:00Z" }] },
    { ...base, kind: "STANDALONE_PRODUCT_GATEWAY", category: "management_appliance", hostname: "FW-LOG-01", model: "Smart-1" },
    { ...base, kind: "STANDALONE_PRODUCT_GATEWAY", category: "device", hostname: "FW-MISSING-01", ack_token: "tok", candidate_id: "cand-1" },
  ] }],
});

describe("Managed estate is a tab under the device header", () => {
  it("opens an MDS on the header and then the Managed estate tab, with every row, pill and read time", async () => {
    const mds = manager("check_point");
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url === "/devices") return json({ devices: [mds] });
      if (url === `/devices/${mds.device_id}/management-tree`) return json(tree("check_point"));
      if (url === `/devices/${mds.device_id}/inventory`) return json({ device_id: mds.device_id, collected_at: "2026-10-18T03:00:00Z", job: null, contexts: [] });
      return json({}, 404);
    }));
    render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    fireEvent.click(await screen.findByText("FW-SYNTH-MGR"));
    const tabs = await screen.findByRole("tablist", { name: "Device detail" });
    const estate = within(tabs).getByRole("tab", { name: "Managed estate" });
    expect(estate).toHaveAttribute("aria-selected", "true");
    expect(within(tabs).getAllByRole("tab")[0]).toBe(estate);
    const header = screen.getByRole("heading", { name: "FW-SYNTH-MGR" });
    const card = await screen.findByLabelText("Managed estate", { selector: ".MuiCard-root" });
    // the device header is first; the discovery card is inside the tab panel under it
    // eslint-disable-next-line no-bitwise
    expect(header.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole("tabpanel")).toContainElement(card);
    // every row, chevron, pill and read time stays
    expect(within(card).getByText("Domains, clusters and gateways")).toBeInTheDocument();
    expect(within(card).getByText("DOM-SYNTH")).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: /DOM-SYNTH/ })).toHaveAttribute("aria-expanded", "true");
    expect(within(card).getByText("FW-SYNTH-M1")).toBeInTheDocument();
    expect(within(card).getByText("ACTIVE")).toBeInTheDocument();
    expect(within(card).getAllByText("1 not in neXus").length).toBeGreaterThan(0);
    expect(within(card).getByText(/read /)).toBeInTheDocument();
    // one row type for the management / log server
    expect(within(card).getByText("Management / log server · Smart-1")).toBeInTheDocument();
    expect(within(card).queryByText(/Gateway · Smart-1/)).toBeNull();
    // singular counts read singular
    expect(within(card).getByText(/1 domain · 1 cluster · 3 gateways · 0 virtual systems · 1 management \/ log server/)).toBeInTheDocument();
    // no HA pill on a management server (PO decision 3)
    expect(document.querySelector('[data-role="UNKNOWN"]')).toBeNull();
    // the link to the policies is below the header, not above it, and worded for a management server (R10)
    const link = screen.getByRole("link", { name: "Open policies this server manages →" });
    // eslint-disable-next-line no-bitwise
    expect(header.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it.each(["check_point", "palo_alto", "radware"])("shows the tab on a %s manager and falls back to a sentence without discovery content", async (vendor) => {
    vi.stubGlobal("fetch", vi.fn(() => json({ device_id: "x", contexts: [] })));
    render(<ThemeProvider theme={m3Theme}><DeviceInventoryPanels device={manager(vendor)} managedEstate={<p>Discovery content</p>} /></ThemeProvider>);
    expect(await screen.findByRole("tab", { name: "Managed estate" })).toBeInTheDocument();
    expect(screen.getByText("Discovery content")).toBeInTheDocument();
  });

  it.each(["fortinet", "bluecoat"])("reuses the existing tab pattern for %s: the managed devices, under the same name", async (vendor) => {
    const members = [{ virtual_system: "FW-SYNTH-01", address: "192.0.2.1", platform: "ProxySG", hardware_type: "SG", hypervisor: "SGOS", node_status: "FULLY_MANAGED", services: [],
      grid_master: false, master_candidate: false, ha_enabled: false }];
    vi.stubGlobal("fetch", vi.fn(() => json({ device_id: "x", collected_at: "2026-10-18T03:00:00Z", contexts: [], grid_members: members, job: null })));
    render(<ThemeProvider theme={m3Theme}><DeviceInventoryPanels device={manager(vendor)} /></ThemeProvider>);
    expect(await screen.findByRole("tab", { name: "Managed estate" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("tab", { name: "Managed devices" })).toBeNull();
    expect(await screen.findByRole("button", { name: "FW-SYNTH-01" })).toBeInTheDocument();
    // Fixed column widths (UI review 2026-10-18, P2-1B / roadmap 3.4): a long value clips in its cell, never the Deployment column.
    const table = document.querySelector("table[data-managed-devices]") as HTMLTableElement;
    expect(table).toHaveStyle({ tableLayout: "fixed" });
    expect(table.querySelectorAll("colgroup col")).toHaveLength(7);
    expect(screen.getByRole("columnheader", { name: "Deployment" })).toBeInTheDocument();
  });

  it("gives a gateway no Managed estate tab", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json({ device_id: "x", contexts: [] })));
    render(<ThemeProvider theme={m3Theme}><DeviceInventoryPanels device={manager("check_point", { role: "gateway" })} /></ThemeProvider>);
    await screen.findByRole("tablist", { name: "Device detail" });
    expect(screen.queryByRole("tab", { name: "Managed estate" })).toBeNull();
  });
});
