import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { ManagementTree, ManagementTreeNode } from "../src/auth/adminApi";
import { ManagementTreePanel, notImported } from "../src/screens/ManagementTreePanel";

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
  expect(screen.getByText("Management / log server")).toBeTruthy();
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
