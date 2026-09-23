import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { DeviceSummary } from "../src/auth/adminApi";
import { DeviceList } from "../src/screens/InventoryScreen";

afterEach(() => vi.unstubAllGlobals());

const dev = (id: string, hostname: string, extra: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: id, hostname, vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
  cluster_member_ref: null, virtual_systems: null, model: null, software_version: null, ha_role: null,
  ...extra,
} as unknown as DeviceSummary);

const devices = [
  dev("mds", "FW-MDS-01", { role: "management_server" }),
  dev("m1", "FW-ROMEO-01-M1", { cluster_member_ref: "CLS-ROMEO-01" }),
  dev("m2", "FW-ROMEO-01-M2", { cluster_member_ref: "CLS-ROMEO-01" }),
  dev("g9", "FW-SOLO-09"),
];
const node = (device_id: string | null, children: unknown[] = []) => ({ kind: "X", category: "device", device_id, imported: device_id !== null,
  ha_role: null, enrollment_state: null, last_collection_state: null, last_collection_at: null, model: null, candidate_id: null,
  ack_token: null, acknowledged: false, acknowledged_reason: null, children });
const tree = { device_id: "mds", vendor: "check_point", run_id: "r", discovered_at: "2026-09-20T00:00:00Z", counts: {},
  domains: [{ domain: "DOM-TANGO-04", nodes: [node(null, [node("m1")])] }, { domain: "DOM-BRAVO-02", nodes: [node(null)] }] };

it("nests a manager's clusters under it by domain and keeps unmanaged devices at the top", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(tree))));
  render(<DeviceList groupByManager devices={devices} selectedDeviceId={null} selectedClusterRef={null}
    onSelectDevice={vi.fn()} onSelectCluster={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("2 managed")).toBeTruthy()); // the whole cluster moves with its member
  expect(screen.getByText("FW-SOLO-09")).toBeTruthy();
  expect(screen.queryByText("CLS-ROMEO-01")).toBeNull();
  fireEvent.click(screen.getByLabelText("Expand managed devices"));
  expect(screen.getByText("CLS-ROMEO-01")).toBeTruthy(); // a single domain with devices opens directly
});

it("stays flat without grouping", () => {
  render(<DeviceList devices={devices} selectedDeviceId={null} selectedClusterRef={null} onSelectDevice={vi.fn()} onSelectCluster={vi.fn()} />);
  expect(screen.getByText("CLS-ROMEO-01")).toBeTruthy();
});
