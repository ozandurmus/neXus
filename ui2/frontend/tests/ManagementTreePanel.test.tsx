import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ManagementTreePanel, notImported } from "../src/screens/ManagementTreePanel";

afterEach(() => vi.unstubAllGlobals());

const member = (hostname: string, device_id: string | null, ha_role: string | null) => ({
  kind: "PLAIN_CLUSTER_MEMBER", hostname, device_id, imported: device_id !== null, ha_role,
  enrollment_state: device_id ? "ENROLLED" : null, last_collection_state: device_id ? "COMPLETED" : null,
  last_collection_at: device_id ? "2026-09-23T07:00:00Z" : null, children: [],
});
const tree = {
  device_id: "mds-1",
  discovered_at: "2026-09-20T19:58:00Z",
  counts: { domains: 2, clusters: 1, gateways: 3, virtual_systems: 0, imported: 2 },
  domains: [
    { domain: "DOM-TANGO-04", nodes: [
      { kind: "PLAIN_HIGH_AVAILABILITY_CLUSTER", cluster_member_ref: "CLS-ROMEO-01", device_id: null, imported: false, ha_role: null,
        enrollment_state: null, last_collection_state: null, last_collection_at: null,
        children: [member("FW-ROMEO-01-M1", "dev-1", "ACTIVE"), member("FW-ROMEO-01-M2", "dev-2", "STANDBY")] },
    ] },
    { domain: "DOM-BRAVO-02", nodes: [{ ...member("FW-KILO-03", null, null), kind: "STANDALONE_PRODUCT_GATEWAY" }] },
  ],
};

it("lists domains with clusters, members' own HA roles, and warns about devices not in neXus", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(tree))));
  const openDevice = vi.fn();
  const openCluster = vi.fn();
  render(<ManagementTreePanel deviceId="mds-1" onOpenDevice={openDevice} onOpenCluster={openCluster} />);
  await waitFor(() => expect(screen.getByText("DOM-TANGO-04")).toBeTruthy());
  expect(screen.getByText("1 not in neXus")).toBeTruthy();
  expect(screen.getByText("ACTIVE")).toBeTruthy();
  expect(screen.getByText("STANDBY")).toBeTruthy();
  fireEvent.click(screen.getByText("CLS-ROMEO-01"));
  expect(openCluster).toHaveBeenCalledWith("CLS-ROMEO-01");
  fireEvent.click(screen.getByText("FW-ROMEO-01-M1"));
  expect(openDevice).toHaveBeenCalledWith("dev-1");
  // the second domain is collapsed until opened; its gateway is not imported
  fireEvent.click(screen.getByText("DOM-BRAVO-02"));
  expect(screen.getByText("Not imported")).toBeTruthy();
});

it("counts only devices, not cluster or virtual-system objects, as not imported", () => {
  expect(notImported(tree.domains)).toBe(1);
});
