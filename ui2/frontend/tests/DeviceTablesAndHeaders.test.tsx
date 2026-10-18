import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { JobWindowContext } from "../src/shell/JobWindow";
import { plural } from "../src/shell/plural";
import {
  ClusterDetailPanels, DeviceInventoryPanels, InterfacesPanel, RoutesPanel, haStatement, interfaceRouteCounts, interfaceRouteLabel, ROUTES_PAGE_SIZE,
} from "../src/screens/InventoryPanels";
import { DeviceIdentityTable } from "../src/screens/DeviceShared";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import type { DeviceSummary, InventoryContext } from "../src/auth/adminApi";

/**
 * UI review 2026-10-18: F-5 (tables that fit), F-15 (routes paged at 50), F-7 (one suffix rule and one Collect now per
 * device), P0-3B (a selected virtual system scopes the pane), P0-5B (one HA statement, one denominator, plural counts).
 */
const adminPlanes = ["Devices", "Config", "Compliance", "Operations", "Admin"];
const viewerPlanes = ["Devices", "Config", "Compliance"];
const session = (permissions: readonly string[]): SessionInfo => ({ displayName: "Synthetic user", roleTokens: [], permissions, onSignOut: () => {} });
const as = (permissions: readonly string[] | null, node: React.ReactElement, windowView = { open: true, label: null as string | null }) => (
  <ThemeProvider theme={m3Theme}>
    {permissions === null ? node : (
      <SessionContext.Provider value={session(permissions)}>
        <JobWindowContext.Provider value={windowView}>{node}</JobWindowContext.Provider>
      </SessionContext.Provider>
    )}
  </ThemeProvider>
);
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
afterEach(() => vi.unstubAllGlobals());

const route = (i: number) => ({ destination: `10.${i % 250}.${(i * 7) % 250}.0/24`, next_hop: `10.1.${i % 200}.1`, interface: `ethernet1/${i % 20}`, protocol: "static" });
const iface = (name: string, kind = "physical") => ({ name, kind, state: "up", vlan_id: null, addresses: [{ address: "192.0.2.1/24", family: "ipv4" as const, role: "member" as const }] });

describe("F-15: the routing tab pages at 50", () => {
  const contexts = [{ context: "physical", vs_name: null, ha: null, interfaces: [], routes: Array.from({ length: 371 }, (_, i) => route(i)) }] as unknown as InventoryContext[];

  it("lists 50 of 371 routes with a page counter and reaches every route", () => {
    render(as(null, <RoutesPanel contexts={contexts} member={{ device_id: "d1", hostname: "FW-SYNTH-01" }} />));
    expect(ROUTES_PAGE_SIZE).toBe(50);
    expect(screen.getByText("Routing table · 371 routes")).toBeInTheDocument();
    expect(screen.getByText("1–50 of 371")).toBeInTheDocument();
    expect(screen.getAllByRole("row").slice(1)).toHaveLength(50);
    for (let page = 2; page <= 8; page++) fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("351–371 of 371")).toBeInTheDocument();
    expect(screen.getAllByRole("row").slice(1)).toHaveLength(21);
    expect(screen.getByRole("button", { name: "Go to next page" })).toBeDisabled();
  });

  it("filters across every route, not just the page, and starts again at page 1", () => {
    render(as(null, <RoutesPanel contexts={contexts} member={{ device_id: "d1", hostname: "FW-SYNTH-01" }} />));
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("51–100 of 371")).toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText("Filter routes..."), { target: { value: "10.249." } });
    const expected = contexts[0].routes.filter((r) => r.destination.includes("10.249.") || (r.next_hop ?? "").includes("10.249.")).length;
    expect(expected).toBeGreaterThan(0);
    expect(screen.getByText(`Routing table · 371 routes · ${expected} match`)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`^1–${Math.min(expected, 50)} of ${expected}$`))).toBeInTheDocument();
  });

  it("shows no pager for a table that fits one page's worth of routes only as a single page", () => {
    const small = [{ ...contexts[0], routes: contexts[0].routes.slice(0, 7) }] as unknown as InventoryContext[];
    render(as(null, <RoutesPanel contexts={small} member={{ device_id: "d1", hostname: "FW-SYNTH-01" }} />));
    expect(screen.getByText("1–7 of 7")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Go to next page" })).toBeDisabled();
  });
});

describe("F-5: columns that must not clip", () => {
  const pa = (over: Partial<DeviceSummary> = {}): DeviceSummary => ({
    device_id: "pa-1", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-PA", model: "PA-5445", software_version: "11.1.10-h7",
    ha_role: "ACTIVE", cluster_member_ref: null, management_ip: "198.51.100.25", serial_number: "SYNTH-SERIAL-1", uptime_text: "81 days, 3:23",
    content_versions: { threat: "9001-8888", av: "5000-5555", wildfire: "0", url: "0000.00.00.000" }, ...over,
  });

  it("gives MANAGEMENT ADDRESS a floor and one line, and lets CONTENT VERSIONS wrap", () => {
    render(as(null, <DeviceIdentityTable devices={[pa()]} />));
    const header = screen.getByRole("columnheader", { name: "MANAGEMENT ADDRESS" });
    expect(parseFloat(getComputedStyle(header).minWidth)).toBeGreaterThanOrEqual(100);
    const address = screen.getByText("198.51.100.25").closest("td")!;
    expect(getComputedStyle(address).whiteSpace).toBe("nowrap");
    expect(parseFloat(getComputedStyle(address).minWidth)).toBeGreaterThanOrEqual(100);
    // header cells may wrap, so the table is narrower than one line per header
    expect(getComputedStyle(header).whiteSpace).not.toBe("nowrap");
    const versions = screen.getByRole("columnheader", { name: "CONTENT VERSIONS" });
    expect(parseFloat(getComputedStyle(versions).minWidth)).toBeGreaterThanOrEqual(150);
    const url = screen.getByText(/URL filtering/).closest("span")!;
    expect(getComputedStyle(url).whiteSpace).not.toBe("nowrap");
  });

  it("keeps NETWORK straight after the addresses with a floor, the MAC column last and the scope tag short", () => {
    const ctx = (name: string) => ({ context: name, vs_name: name, ha: null, routes: [], interfaces: [{ ...iface("ethernet1/1"), mac_address: "94:56:41:aa:bb:01", zone: "ZONE-SYNTH" }] }) as unknown as InventoryContext;
    render(as(null, <InterfacesPanel contexts={[ctx("vsys1"), ctx("vsys2")]} virtualSystems={["vsys1", "vsys2"]} member={{ device_id: "pa-1", hostname: "FW-SYNTH-PA" }} isPaloAlto />));
    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    // F-19: the merged view is grouped by VSYS, so the group header carries the name and the column is gone
    expect(headers).toEqual(["Interface", "Kind", "VLAN", "Zone", "Address", "Network", "State", "MAC"]);
    expect(parseFloat(getComputedStyle(screen.getByRole("columnheader", { name: "Network" })).minWidth)).toBeGreaterThanOrEqual(100);
    // the scope tag shows the name only (the column says VSYS), cut by an ellipsis rather than pushing NETWORK off
    const tag = screen.getAllByText("vsys1")[0];
    expect(tag).toHaveAttribute("title", "VSYS: vsys1");
    expect(getComputedStyle(tag).textOverflow).toBe("ellipsis");
    expect(getComputedStyle(tag).maxWidth).toBe("132px");
  });
});

describe("P0-5B: one denominator, one HA statement, plural counts", () => {
  const ctx = (name: string, interfaces: string[], routes: number) => ({ context: name, interfaces: interfaces.map((n) => ({ kind: n.startsWith("lo") ? "loopback" : "physical" })), routes: Array.from({ length: routes }) });

  it("states both the headline and the interfaces that live in other contexts, never a folded total", () => {
    const contexts = [ctx("root", Array.from({ length: 36 }, (_, i) => `port${i}`), 24), ctx("VS-SEC-62", Array.from({ length: 14 }, (_, i) => `v${i}`), 3), ctx("VS-SEC-38", Array.from({ length: 10 }, (_, i) => `w${i}`), 1)];
    expect(interfaceRouteLabel(interfaceRouteCounts(contexts, "fortinet"), "fortinet")).toBe("36 interfaces · 24 in VDOMs · 24 routes");
    expect(interfaceRouteLabel(interfaceRouteCounts(contexts, "check_point"), "check_point")).toBe("36 interfaces · 24 in virtual systems · 24 routes");
    // Palo Alto's tab merges every virtual system, so one denominator is the whole sum
    expect(interfaceRouteLabel(interfaceRouteCounts(contexts, "palo_alto"), "palo_alto")).toBe("60 interfaces · 28 routes");
  });

  it("leaves loopbacks out (the table does) and counts one virtual system alone when one is selected", () => {
    const contexts = [ctx("physical", ["eth0", "lo"], 1), ctx("7", ["a", "b", "c"], 2)];
    expect(interfaceRouteLabel(interfaceRouteCounts(contexts, "check_point"), "check_point")).toBe("1 interface · 3 in virtual systems · 1 route");
    expect(interfaceRouteLabel(interfaceRouteCounts(contexts, "check_point", contexts[1]), "check_point")).toBe("3 interfaces · 2 routes");
  });

  it("pluralises exactly one", () => {
    expect(plural(1, "interface")).toBe("1 interface");
    expect(plural(0, "interface")).toBe("0 interfaces");
    expect(plural(2, "management / log server")).toBe("2 management / log servers");
    expect(plural(1, "stored analysis", "stored analyses")).toBe("1 stored analysis");
    expect(plural(2, "stored analysis", "stored analyses")).toBe("2 stored analyses");
    expect(interfaceRouteLabel({ interfaces: 1, elsewhere: 0, routes: 1 }, "fortinet")).toBe("1 interface · 1 route");
  });

  it("says one HA sentence per device and nothing for a management server", () => {
    const base = { ha_role: "primary", cluster_member_ref: null as string | null, ha_peer_unconfirmed: true };
    expect(haStatement(base)).toEqual({ kind: "statement", text: "HA: peer named, not confirmed · observed role PRIMARY" });
    expect(haStatement({ ...base, ha_role: null })).toEqual({ kind: "statement", text: "HA: peer named, not confirmed" });
    expect(haStatement({ ...base, ha_peer_unconfirmed: false })).toEqual({ kind: "role", role: "primary" });
    expect(haStatement({ ...base, ha_peer_unconfirmed: false, ha_role: null })).toBeNull();
    expect(haStatement({ ...base, role: "management_server" })).toBeNull();
  });

  it("shows the HA statement once in the header of a FortiGate and counts in one denominator", async () => {
    const fg: DeviceSummary = { device_id: "fg-1", vendor_hint: "fortinet", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-FG", model: "FortiGate",
      software_version: "7.4", ha_role: "PRIMARY", ha_peer_unconfirmed: true, cluster_member_ref: null };
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices/fg-1/inventory"
      ? json({ device_id: "fg-1", collected_at: "2026-10-18T03:00:00Z", job: null, contexts: [
        { context: "root", vs_name: null, ha: { role: "PRIMARY", cluster_mode: null }, interfaces: Array.from({ length: 4 }, (_, i) => iface(`port${i}`)), routes: [route(1)] },
        { context: "VS-SEC-62", vs_name: "VS-SEC-62", ha: null, interfaces: [iface("vdom-a"), iface("vdom-b")], routes: [] }] })
      : json({})));
    render(as(adminPlanes, <DeviceInventoryPanels device={fg} />));
    expect(await screen.findByText("4 interfaces · 2 in VDOMs · 1 route")).toBeInTheDocument();
    expect(screen.getAllByText(/observed role PRIMARY/)).toHaveLength(1);
    expect(screen.queryByText("HA peer named, not confirmed")).toBeNull();
    expect(document.querySelector('[data-role="PRIMARY"]')).toBeNull();
    // the per-context "HA role:" line repeats the header: it is gone
    expect(screen.queryByText("HA role:")).toBeNull();
  });
});

describe("F-7: one suffix rule and one Collect now per device", () => {
  const closed = { open: false, label: "Next window: 12:00" };
  const cp: DeviceSummary = { device_id: "gw-1", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-01", model: "Quantum", software_version: "R81.20", ha_role: null, cluster_member_ref: null };
  const stubGateway = () => vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices/gw-1/inventory"
    ? json({ device_id: "gw-1", collected_at: "2026-10-18T03:00:00Z", job: null, contexts: [{ context: "physical", vs_name: null, ha: null, interfaces: [iface("eth0")], routes: [] }] })
    : json({})));

  it.each([
    ["a read-only role", viewerPlanes, { open: true, label: null as string | null }, "Collect now · Role: not permitted"],
    ["an admin outside the window", adminPlanes, closed, "Collect now · Next window: 12:00"],
  ])("suffixes Collect now for %s", async (_label, planes, windowView, name) => {
    stubGateway();
    render(as(planes, <DeviceInventoryPanels device={cp} />, windowView));
    const button = await screen.findByRole("button", { name });
    expect(button).toBeDisabled();
    expect(screen.getAllByRole("button", { name: /^Collect now/ })).toHaveLength(1);
  });

  it("leaves Collect now plain and live for an admin inside the window", async () => {
    stubGateway();
    render(as(adminPlanes, <DeviceInventoryPanels device={cp} />));
    expect(await screen.findByRole("button", { name: "Collect now" })).toBeEnabled();
  });

  it("carries the same suffix on the two fleet buttons of the Devices screen", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices" ? json({ devices: [cp] }) : json({})));
    render(as(viewerPlanes, <InventoryScreen />));
    expect(await screen.findByRole("button", { name: "Bulk Collect · Role: not permitted" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Read configuration, all · Role: not permitted" })).toBeDisabled();
  });

  it("keeps one Collect now per cluster page: in the header with M1 / M2 pills, the last read age in the Cluster members tab", async () => {
    const members: DeviceSummary[] = ["m1", "m2"].map((id, i) => ({ ...cp, device_id: id, hostname: `FW-SYNTH-0${i + 1}`, ha_role: i ? "STANDBY" : "ACTIVE", cluster_member_ref: "CLS-SYNTH-01",
      inventory_collected_at: "2026-10-18T03:00:00Z" }));
    vi.stubGlobal("fetch", vi.fn((url: string) => url.includes("/clusters/CLS-SYNTH-01/inventory")
      ? json({ cluster_member_ref: "CLS-SYNTH-01", members, contexts: [{ context: "physical", interfaces: [], routes: [] }], virtual_systems: [] })
      : json({})));
    render(as(adminPlanes, <ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} />));
    await screen.findByRole("tablist", { name: "Cluster detail" });
    expect(screen.getAllByRole("button", { name: "Collect now" })).toHaveLength(1);
    const pills = within(screen.getByTestId("entity-header")).getByRole("group", { name: "Collect from member" });
    const [m1, m2] = within(pills).getAllByRole("button");
    expect([m1.textContent, m2.textContent]).toEqual(["M1", "M2"]);
    expect(m1).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(m2);
    expect(m2).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("button", { name: "Collect now" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("tab", { name: "Cluster members" }));
    expect(screen.getAllByRole("button", { name: "Collect now" })).toHaveLength(1);
    expect(screen.getByRole("columnheader", { name: "Last read" })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Action" })).toBeNull();
  });
});

describe("P0-3B: a selected virtual system scopes the pane", () => {
  const vs = ["VS-SYNTH-12", "VS-SYNTH-13"];
  const members: DeviceSummary[] = ["m1", "m2"].map((id, i) => ({ device_id: id, vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: `FW-SYNTH-0${i + 1}`,
    model: "Quantum", software_version: "R81.20", ha_role: i ? "STANDBY" : "ACTIVE", cluster_member_ref: "CLS-SYNTH-01", virtual_systems: vs.join(", ") }));
  const cctx = (context: string, vsName: string | null, names: string[]) => ({ context, vs_name: vsName, routes: [],
    interfaces: names.map((name) => ({ name, kind: "physical", addresses: [], presence: "all", differences: [], member_addresses: { m1: [{ address: "192.0.2.1/24", family: "ipv4", role: "member" }], m2: [{ address: "192.0.2.1/24", family: "ipv4", role: "member" }] }, member_states: { m1: "up", m2: "up" } })) });
  const stubCluster = () => vi.stubGlobal("fetch", vi.fn((url: string) => url.includes("/clusters/CLS-SYNTH-01/inventory")
    ? json({ cluster_member_ref: "CLS-SYNTH-01", members, virtual_systems: vs, contexts: [cctx("physical", null, ["Mgmt", "Sync"]), cctx("12", "VS-SYNTH-12", ["eth1-12"]), cctx("13", "VS-SYNTH-13", ["eth1-13a", "eth1-13b", "eth1-13c"])] })
    : json({})));

  it("heads the pane `parent › VS`, with a switcher, only that VS's tabs and its own counts", async () => {
    stubCluster();
    const onSelectVs = vi.fn();
    render(as(adminPlanes, <ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} initialVs="VS-SYNTH-13" onSelectVs={onSelectVs} />));
    expect(await screen.findByRole("heading", { name: "CLS-SYNTH-01 › VS-SYNTH-13" })).toBeInTheDocument();
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Interfaces", "Routing"]);
    expect(await screen.findByText("3 interfaces · 0 routes")).toBeInTheDocument();
    expect(screen.getByText("eth1-13a")).toBeInTheDocument();
    expect(screen.queryByText("Mgmt")).toBeNull();
    expect(screen.getByRole("combobox", { name: "Switch virtual system" })).toBeInTheDocument();
    // moving to a sibling updates the caller's selection and the pane
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Switch virtual system" }));
    fireEvent.click(await screen.findByRole("option", { name: "VS-SYNTH-12" }));
    expect(onSelectVs).toHaveBeenCalledWith("VS-SYNTH-12");
    expect(await screen.findByRole("heading", { name: "CLS-SYNTH-01 › VS-SYNTH-12" })).toBeInTheDocument();
    expect(screen.getByText("eth1-12")).toBeInTheDocument();
  });

  it("brings the cluster's own tabs back through the parent link", async () => {
    stubCluster();
    const onSelectVs = vi.fn();
    render(as(adminPlanes, <ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} initialVs="VS-SYNTH-13" onSelectVs={onSelectVs} />));
    fireEvent.click(await screen.findByRole("button", { name: "CLS-SYNTH-01" }));
    expect(onSelectVs).toHaveBeenCalledWith(null);
    // F-21: the title is the cluster's name, without the `CLS >` prefix; the badge and tree keep the kind.
    expect(await screen.findByRole("heading", { name: "CLS-SYNTH-01" })).toBeInTheDocument();
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Interfaces", "Routing", "Cluster members", "Configuration", "Logs", "Identity", "Failover"].filter((name) => name !== "Configuration"));
    expect(screen.queryByRole("combobox", { name: "Switch virtual system" })).toBeNull();
  });

  it("bolds the selected vsys in a Palo Alto member card", async () => {
    const pan: DeviceSummary[] = ["p1", "p2"].map((id, i) => ({ ...members[i], device_id: id, vendor_hint: "palo_alto", cluster_member_ref: "CLS-SYNTH-PA", virtual_systems: "vsys1, vsys2" }));
    vi.stubGlobal("fetch", vi.fn((url: string) => url.includes("/clusters/CLS-SYNTH-PA/inventory")
      ? json({ cluster_member_ref: "CLS-SYNTH-PA", members: pan, virtual_systems: ["vsys1", "vsys2"], contexts: [cctx("vsys1", "vsys1", ["ethernet1/1"]), cctx("vsys2", "vsys2", ["ethernet1/2"])] })
      : json({})));
    render(as(adminPlanes, <ClusterDetailPanels clusterRef="CLS-SYNTH-PA" members={pan} initialVs="vsys2" />));
    await screen.findByRole("heading", { name: "CLS-SYNTH-PA › vsys2" });
    const card = document.querySelector('[data-member-card="p1"]') as HTMLElement;
    expect(within(card).getByText("vsys2")).toHaveStyle({ fontWeight: "700" });
    expect(within(card).getByText("vsys1")).not.toHaveStyle({ fontWeight: "700" });
  });

  it("heads a standalone device's virtual system `device › VSYS` and scopes its tabs too", async () => {
    const standalone: DeviceSummary = { device_id: "pa-1", vendor_hint: "palo_alto", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-SYNTH-PA", model: "PA-440",
      software_version: "11.0", ha_role: null, cluster_member_ref: null, virtual_systems: "vsys1, vsys2" };
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices/pa-1/inventory"
      ? json({ device_id: "pa-1", collected_at: "2026-10-18T03:00:00Z", job: null, virtual_systems: ["vsys1", "vsys2"], contexts: [
        { context: "vsys1", vs_name: "vsys1", ha: null, interfaces: [iface("ethernet1/1")], routes: [] },
        { context: "vsys2", vs_name: "vsys2", ha: null, interfaces: [iface("ethernet1/2"), iface("ethernet1/3")], routes: [route(1)] }] })
      : json({})));
    render(as(adminPlanes, <DeviceInventoryPanels device={standalone} initialVs="vsys2" />));
    expect(await screen.findByRole("heading", { name: "FW-SYNTH-PA › vsys2" })).toBeInTheDocument();
    expect(await screen.findByText("2 interfaces · 1 route")).toBeInTheDocument();
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Interfaces", "Routing"]);
    await waitFor(() => expect(screen.getByText("ethernet1/3")).toBeInTheDocument());
  });
});
