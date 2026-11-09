import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { ManagementTree, ManagementTreeNode } from "../src/auth/adminApi";
import { ManagementTreePanel, domainHaLine, gatewayHaWord, notImported, splitDomainHa } from "../src/screens/ManagementTreePanel";

afterEach(() => vi.unstubAllGlobals());

const base = {
  device_id: null, imported: false, ha_role: null, enrollment_state: null, last_collection_state: null, last_collection_at: null,
  model: null, candidate_id: null, ack_token: null, acknowledged: false, acknowledged_reason: null, children: [],
} as const;
const member = (hostname: string, device_id: string | null, ha_role: string | null): ManagementTreeNode => ({
  ...base, kind: "PLAIN_CLUSTER_MEMBER", category: "device", hostname, device_id, imported: device_id !== null, ha_role,
  enrollment_state: device_id ? "ENROLLED" : null, last_collection_state: device_id ? "COMPLETED" : null,
  last_collection_at: device_id ? "2026-09-23T07:00:00Z" : null, candidate_id: `cand-${hostname}`, ack_token: device_id ? null : `tok-${hostname}`,
});
const tree: ManagementTree = {
  device_id: "mds-1", vendor: "check_point", run_id: "run-1", discovered_at: "2026-09-20T19:58:00Z",
  counts: { domains: 2, clusters: 1, gateways: 3, virtual_systems: 0, imported: 2, management_appliances: 1, not_in_nexus: 1, acknowledged: 0 },
  domains: [
    { domain: "DOM-TANGO-04", nodes: [
      { ...base, kind: "PLAIN_HIGH_AVAILABILITY_CLUSTER", category: "cluster", cluster_member_ref: "CLS-ROMEO-01", candidate_id: "cand-c",
        children: [member("FW-ROMEO-01-M1", "dev-1", "ACTIVE"), member("FW-ROMEO-01-M2", "dev-2", "STANDBY")] },
      { ...base, kind: "STANDALONE_PRODUCT_GATEWAY", category: "management_appliance", hostname: "FW-LOG-01", model: "Smart-1" },
    ] },
    { domain: "DOM-BRAVO-02", nodes: [{ ...member("FW-KILO-03", null, null), kind: "STANDALONE_PRODUCT_GATEWAY", model: "1570/1590 Appliances" }] },
  ],
};

it("lists domains with clusters, members' own HA roles, management servers apart, and warns about gateways not in neXus", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(tree))));
  const openDevice = vi.fn();
  const openCluster = vi.fn();
  render(<ManagementTreePanel deviceId="mds-1" onOpenDevice={openDevice} onOpenCluster={openCluster} />);
  await waitFor(() => expect(screen.getByText("DOM-TANGO-04")).toBeTruthy());
  expect(screen.getAllByText("1 not in neXus").length).toBeGreaterThan(0);
  expect(screen.getByText("ACTIVE")).toBeTruthy();
  expect(screen.getByText("STANDBY")).toBeTruthy();
  // one row type for a management / log server -- never "Gateway" beside a second pill (UI review 2026-10-18, P0-5B)
  expect(screen.getAllByText("Management / log server · Smart-1")).toHaveLength(1);
  expect(screen.queryByText("Management / log server")).toBeNull();
  expect(screen.queryByText("Gateway · Smart-1")).toBeNull();
  fireEvent.click(screen.getByText("CLS-ROMEO-01"));
  expect(openCluster).toHaveBeenCalledWith("CLS-ROMEO-01");
  fireEvent.click(screen.getByText("FW-ROMEO-01-M1"));
  expect(openDevice).toHaveBeenCalledWith("dev-1");
  fireEvent.click(screen.getByText("DOM-BRAVO-02"));
  expect(screen.getByText("Not in neXus")).toBeTruthy();
});

it("offers select-to-import and Not an issue with a reason on a gateway not in neXus", async () => {
  const calls: Array<{ url: string; body: unknown }> = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), body: init?.body ? JSON.parse(String(init.body)) : null });
    if (String(input).endsWith("/acknowledge")) return new Response(JSON.stringify({ ok: true }));
    if (String(input) === "/credentials") return new Response(JSON.stringify({ credentials: [] }));
    return new Response(JSON.stringify(tree));
  }));
  render(<ManagementTreePanel deviceId="mds-1" onOpenDevice={vi.fn()} onOpenCluster={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("DOM-BRAVO-02")).toBeTruthy());
  fireEvent.click(screen.getByText("DOM-BRAVO-02"));
  fireEvent.click(screen.getByLabelText("Select FW-KILO-03 to import"));
  expect(screen.getByText("Import 1 selected")).toBeTruthy();
  fireEvent.click(screen.getByText("Not an issue"));
  fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "decommissioned branch" } });
  fireEvent.click(screen.getByText("Save"));
  await waitFor(() => expect(calls.some((c) => c.url.endsWith("/acknowledge"))).toBe(true));
  expect(calls.find((c) => c.url.endsWith("/acknowledge"))!.body).toEqual({ token: "tok-FW-KILO-03", acknowledge: true, reason: "decommissioned branch" });
});

it("counts only unacknowledged gateways as not imported", () => {
  expect(notImported(tree.domains)).toBe(1);
});

// Post-release review 2026-10-20 (N14): the HA pill of a gateway the manager lists in no cluster.
it("words a read standalone gateway as Standalone, keeps a member's own UNKNOWN, and makes no claim about an unread gateway", async () => {
  const read = { last_collection_at: "2026-10-20T15:00:00Z" };
  expect(gatewayHaWord({ ha_role: "UNKNOWN", ...read }, false)).toMatchObject({ kind: "standalone", title: expect.stringContaining("lists this gateway in no cluster") });
  expect(gatewayHaWord({ ha_role: null, ...read }, false)).toMatchObject({ kind: "standalone" });
  expect(gatewayHaWord({ ha_role: "ACTIVE", ...read }, false)).toEqual({ kind: "role", role: "ACTIVE" });
  expect(gatewayHaWord({ ha_role: "UNKNOWN", ...read }, true)).toEqual({ kind: "role", role: "UNKNOWN" });
  expect(gatewayHaWord({ ha_role: "UNKNOWN", last_collection_at: null }, false)).toBeNull();
  const withStandalone: ManagementTree = { ...tree, domains: [{ domain: "DOM-TANGO-04", nodes: [
    ...tree.domains[0].nodes,
    { ...member("FW-YANKEE-01", "dev-9", "UNKNOWN"), kind: "STANDALONE_PRODUCT_GATEWAY", model: "29000 Appliances" },
  ] }] };
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(withStandalone))));
  render(<ManagementTreePanel deviceId="mds-1" onOpenDevice={vi.fn()} onOpenCluster={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("FW-YANKEE-01")).toBeTruthy());
  expect(screen.getByText("Standalone").closest("span[title]")).toHaveAttribute("title", expect.stringContaining("UNKNOWN"));
  expect(document.querySelector('[data-role="UNKNOWN"]')).toBeNull();
  expect(screen.getByText("ACTIVE")).toBeTruthy();
});

// Check Point MDS: per-Domain Active/Standby placement across the Multi-Domain Servers (domain_ha, 2026-11-09).
const haTree: ManagementTree = {
  ...tree,
  domains: [
    { domain: "DOMAIN-SYNTH-A", nodes: tree.domains[0].nodes },
    { domain: "DOMAIN-SYNTH-B", nodes: tree.domains[1].nodes },
    { domain: "DOMAIN-SYNTH-C", nodes: [] },
  ],
  domain_ha: {
    observed_at: "2026-11-09T06:00:00Z",
    domains: [
      { domain: "DOMAIN-SYNTH-A", servers: [{ hostname: "MDS-SYNTH-01", active: true }, { hostname: "MDS-SYNTH-02", active: false }] },
      { domain: "DOMAIN-SYNTH-B", servers: [{ hostname: "MDS-SYNTH-02", active: true }] },
      { domain: "DOMAIN-SYNTH-C", servers: [{ hostname: "MDS-SYNTH-01", active: false }, { hostname: "MDS-SYNTH-02", active: false }] },
      { domain: "Global", servers: [{ hostname: "MDS-SYNTH-01", active: true }, { hostname: "MDS-SYNTH-02", active: false }, { hostname: "MDS-SYNTH-03", active: false }] },
    ],
  },
};

it("words a Domain's HA placement plainly: standby list, no standby, no active server -- never an invented one", () => {
  const [a, b, c, g] = haTree.domain_ha!.domains;
  expect(domainHaLine(a)).toBe("Active on MDS-SYNTH-01, standby on MDS-SYNTH-02");
  expect(domainHaLine(b)).toBe("Active on MDS-SYNTH-02, no standby");
  expect(domainHaLine(c)).toBe("No active server listed, standby on MDS-SYNTH-01 and MDS-SYNTH-02");
  expect(domainHaLine(g)).toBe("Active on MDS-SYNTH-01, standby on MDS-SYNTH-02 and MDS-SYNTH-03");
  expect(domainHaLine({ servers: [] })).toBe("No active server listed, no standby");
});

it("matches HA entries to Domain sections by name and keeps the rest (Global) apart", () => {
  const split = splitDomainHa(haTree.domain_ha, haTree.domains);
  expect([...split.bySection.keys()]).toEqual(["DOMAIN-SYNTH-A", "DOMAIN-SYNTH-B", "DOMAIN-SYNTH-C"]);
  expect(split.unsectioned.map((e) => e.domain)).toEqual(["Global"]);
  expect(splitDomainHa(undefined, haTree.domains).bySection.size).toBe(0);
  expect(splitDomainHa(haTree.domain_ha, [{ domain: null, nodes: [] }]).unsectioned).toHaveLength(4);
});

it("shows each Domain's Active/Standby MDS under its name, Global in the header list, and when it was read", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(haTree))));
  render(<ManagementTreePanel deviceId="mds-1" onOpenDevice={vi.fn()} onOpenCluster={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("DOMAIN-SYNTH-A")).toBeTruthy());
  const lines = [...document.querySelectorAll("[data-domain-ha-line]")].map((el) => el.textContent);
  expect(lines).toEqual([
    "Active on MDS-SYNTH-01, standby on MDS-SYNTH-02",
    "Active on MDS-SYNTH-02, no standby",
    "No active server listed, standby on MDS-SYNTH-01 and MDS-SYNTH-02",
  ]);
  expect(document.querySelector('[data-domain="DOMAIN-SYNTH-A"] [data-domain-ha-line]')!.textContent).toContain("MDS-SYNTH-01");
  const global = screen.getByLabelText("Domain HA of Domains with no gateways listed here");
  expect(global.textContent).toContain("Global");
  expect(global.textContent).toContain("Active on MDS-SYNTH-01, standby on MDS-SYNTH-02 and MDS-SYNTH-03");
  expect(screen.getByText(/comes from the latest policy collection, read/)).toBeTruthy();
  expect(document.querySelector('[data-domain-ha="collected"] time')).toHaveAttribute("datetime", "2026-11-09T06:00:00Z");
  expect(document.querySelector('[data-domain-ha="not_collected"]')).toBeNull();
});

it("says Domain HA is not collected yet when observed_at is null or the key is missing, and says nothing for other vendors", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ ...haTree, domain_ha: { observed_at: null, domains: [] } }))));
  const { unmount } = render(<ManagementTreePanel deviceId="mds-1" onOpenDevice={vi.fn()} onOpenCluster={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("DOMAIN-SYNTH-A")).toBeTruthy());
  expect(screen.getByText(/has not been collected yet/)).toBeTruthy();
  expect(document.querySelector("[data-domain-ha-line]")).toBeNull();
  unmount();
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(tree))));
  const second = render(<ManagementTreePanel deviceId="mds-1" onOpenDevice={vi.fn()} onOpenCluster={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("DOM-TANGO-04")).toBeTruthy());
  expect(screen.getByText(/has not been collected yet/)).toBeTruthy();
  second.unmount();
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ ...haTree, vendor: "palo_alto", domain_ha: undefined }))));
  render(<ManagementTreePanel deviceId="pan-1" onOpenDevice={vi.fn()} onOpenCluster={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("DOMAIN-SYNTH-A")).toBeTruthy());
  expect(screen.queryByText(/Domain HA/)).toBeNull();
});
