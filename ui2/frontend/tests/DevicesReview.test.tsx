import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen, clusterCounts, inventoryCsv } from "../src/screens/InventoryScreen";
import { contentVersionText, isReportedZero, orderMembers } from "../src/screens/DeviceShared";
import type { DeviceSummary } from "../src/auth/adminApi";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const base = (id: string, extra: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: id, vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: `FW-${id}`, model: "Quantum 6600",
  software_version: "R81.20", ha_role: null, cluster_member_ref: null, ip_addresses: "192.0.2.1", management_ip: "192.0.2.10", ...extra,
});

// Listed M2 first on purpose: the screens must still show M1, M2.
const M2 = base("tango-02", { hostname: "FW-TANGO-02", ha_role: "ACTIVE", cluster_member_ref: "CLS-ROMEO-01", virtual_systems: "vs-a, vs-b",
  serial_number: "SN-00000002", hotfix_level: "Take 99", platform_facts_observed_at: "2026-09-22T22:57:54Z", inventory_collected_at: "2026-09-22T23:00:00Z" });
const M1 = base("tango-01", { hostname: "FW-TANGO-01", ha_role: "STANDBY", cluster_member_ref: "CLS-ROMEO-01", virtual_systems: "vs-a",
  serial_number: null, hotfix_level: "Take 99", management_ip: "192.0.2.11" });
const DRAFT_CLUSTER = base("juliet-01", { hostname: "FW-JULIET-01", enrollment_state: "DRAFT", cluster_member_ref: "CLS-JULIET-02", ip_addresses: null });
const STANDALONE = base("bravo-02", { hostname: "FW-BRAVO-02", vendor_hint: "palo_alto" });

const CLUSTER_INVENTORY = {
  cluster_member_ref: "CLS-ROMEO-01",
  members: [{ device_id: "tango-02", hostname: "FW-TANGO-02" }, { device_id: "tango-01", hostname: "FW-TANGO-01" }],
  contexts: [{
    context: "physical",
    interfaces: [
      { name: "eth1", kind: "physical", addresses: [], presence: "all", differences: [],
        member_addresses: { "tango-01": [{ address: "192.0.2.21/24", family: "ipv4", role: "member" }], "tango-02": [{ address: "192.0.2.22/24", family: "ipv4", role: "member" }] },
        member_states: { "tango-01": "up", "tango-02": "down" } },
      { name: "eth2", kind: "physical", addresses: [], presence: "all", differences: [],
        member_addresses: { "tango-01": [{ address: "192.0.2.31/24", family: "ipv4", role: "member" }], "tango-02": [{ address: "192.0.2.32/24", family: "ipv4", role: "member" }] },
        member_states: { "tango-01": "down", "tango-02": "down" } },
    ],
    routes: [],
  }],
};

function stubFleet() {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return Promise.resolve(jsonResponse(200, { devices: [M2, M1, DRAFT_CLUSTER, STANDALONE] }));
    if (url === "/clusters/CLS-ROMEO-01/inventory") return Promise.resolve(jsonResponse(200, CLUSTER_INVENTORY));
    if (url === "/devices/bravo-02/inventory") return Promise.resolve(jsonResponse(200, { device_id: "bravo-02", contexts: [] }));
    return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
  }));
}

describe("Devices screen after the Fable review", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState({}, "", "/");
  });

  it("counts clusters as enrolled and active, and labels every chip row", async () => {
    stubFleet();
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText("FW-BRAVO-02")).toBeInTheDocument());
    const scope = screen.getByRole("group", { name: "Scope" });
    expect(within(scope).getByRole("button", { name: "Clusters · 2 enrolled · 1 active" })).toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Vendor" })).getByRole("button", { name: "Palo Alto · 1" })).toBeInTheDocument();
    // The Live chip is gone from Live rows; a row that is not Live still carries its state word.
    expect(screen.queryByText("Live")).toBeNull();
    expect(screen.getByText("Not enrolled")).toBeInTheDocument();
  });

  it("opens the cluster named by ?cluster_ref=, members M1 then M2 with neutral role chips, and links the cluster across screens", async () => {
    window.history.replaceState({}, "", "/?screen=inventory&cluster_ref=CLS-ROMEO-01");
    stubFleet();
    const { container } = render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText(/CLS > /)).toBeInTheDocument());

    const cards = [...container.querySelectorAll("[data-member-card]")].map((el) => el.getAttribute("data-member-card"));
    expect(cards).toEqual(["tango-01", "tango-02"]);
    const card1 = container.querySelector('[data-member-card="tango-01"]') as HTMLElement;
    expect(within(card1).getByText("STANDBY")).toHaveAttribute("data-role", "STANDBY");

    const strip = screen.getByRole("navigation", { name: "This cluster on other screens" });
    const hrefs = within(strip).getAllByRole("link").map((a) => [a.textContent, a.getAttribute("href")]);
    expect(hrefs).toEqual([
      ["Inventory", "?screen=inventory&cluster_ref=CLS-ROMEO-01"],
      ["Configuration", "?screen=configuration&cluster_ref=CLS-ROMEO-01"],
      ["Backups", "?screen=backups&q=CLS-ROMEO-01"],
      ["Readiness", "?screen=operations&cluster_ref=CLS-ROMEO-01"],
    ]);

    // Interfaces: "n of m shown · Active only"; the member columns run M1, M2; the link state is a word.
    await waitFor(() => expect(screen.getByText("Interfaces · 1 of 2 shown · Active only")).toBeInTheDocument());
    const heads = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(heads.indexOf("FW-TANGO-01")).toBeLessThan(heads.indexOf("FW-TANGO-02"));
    expect(screen.getByText("Degraded")).toBeInTheDocument();
    expect(screen.getByText("FW-TANGO-01: up")).toBeInTheDocument();
    expect(screen.getByText("FW-TANGO-02: down")).toBeInTheDocument();
    expect(screen.getByText("192.0.2.21/24")).toHaveAttribute("title", "FW-TANGO-01 link state: up");

    // Cluster members tab: the same role chips, the same order.
    fireEvent.click(screen.getByRole("tab", { name: "Cluster members" }));
    const rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[0]).getByText("FW-TANGO-01")).toBeInTheDocument();
    expect(within(rows[1]).getByText("ACTIVE")).toHaveAttribute("data-role", "ACTIVE");

    // Identity & provenance: the per-member identity table.
    fireEvent.click(screen.getByRole("tab", { name: "Identity & provenance" }));
    const table = screen.getByRole("table", { name: "Member identity" });
    const idRows = within(table).getAllByRole("row").slice(1);
    expect(within(idRows[0]).getByText("FW-TANGO-01")).toBeInTheDocument();
    expect(within(idRows[0]).getByText("1 · differs")).toBeInTheDocument();
    expect(within(idRows[0]).getByText("vs-b")).toBeInTheDocument();
    expect(within(idRows[1]).getByText("2 · same as cluster")).toBeInTheDocument();
    expect(within(idRows[1]).getByText("SN-00000002")).toBeInTheDocument();
    expect(within(idRows[1]).getByText("2026-09-23 01:57:54")).toBeInTheDocument();
    expect(within(idRows[0]).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
  });

  it("opens the device named by ?device_id=", async () => {
    window.history.replaceState({}, "", "/?screen=inventory&device_id=bravo-02");
    stubFleet();
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByRole("heading", { name: "FW-BRAVO-02" })).toBeInTheDocument());
    expect(screen.getByRole("tablist", { name: "Device detail" })).toBeInTheDocument();
    expect(screen.queryByText("Select a device or cluster")).toBeNull();
  });
});

describe("shared device pieces", () => {
  it("orders members by name suffix, M1 before M2 and 2 before 10", () => {
    const names = orderMembers([
      { device_id: "c", hostname: "FW-X-10" }, { device_id: "b", hostname: "FW-X-2" }, { device_id: "a", hostname: "FW-X-1" },
    ]).map((m) => m.hostname);
    expect(names).toEqual(["FW-X-1", "FW-X-2", "FW-X-10"]);
  });

  it("writes a reported zero as reported, and an unread content version as UNKNOWN", () => {
    expect(isReportedZero("0")).toBe(true);
    expect(isReportedZero("0000.00.00.000")).toBe(true);
    expect(isReportedZero("8800-1234")).toBe(false);
    expect(contentVersionText({ threat: "0", url: "0000.00.00.000", app: "8800-1234" }))
      .toBe("Applications 8800-1234 · Threats 0 (as reported) · URL filtering 0000.00.00.000 (as reported)");
    expect(contentVersionText(null)).toBeNull();
  });

  it("counts clusters from the list: every reference enrolled, those with an ENROLLED member active", () => {
    expect(clusterCounts([M1, M2, DRAFT_CLUSTER, STANDALONE])).toEqual({ enrolled: 2, active: 1 });
  });

  it("exports the device list with UNKNOWN for facts not read", () => {
    const csv = inventoryCsv([M1]);
    const [header, row] = csv.trim().split("\r\n");
    expect(header.split(",")[0]).toBe("hostname");
    expect(row).toContain("FW-TANGO-01");
    expect(row).toContain("UNKNOWN");
  });
});
