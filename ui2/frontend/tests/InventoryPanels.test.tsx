import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import { BackupPanel } from "../src/screens/InventoryPanels";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const STANDALONE_DEVICE = {
  device_id: "dev-1",
  vendor_hint: "check_point",
  enrollment_state: "ENROLLED",
  hostname: "fw-edge-1",
  model: "Quantum",
  software_version: "R81.20",
  ha_role: null,
  cluster_member_ref: null,
};

const CLUSTER_MEMBER_A = {
  device_id: "dev-a",
  vendor_hint: "check_point",
  enrollment_state: "ENROLLED",
  hostname: "member-a",
  model: "Quantum",
  software_version: "R81.20",
  ha_role: "active",
  cluster_member_ref: "cluster-1",
};

const CLUSTER_MEMBER_B = {
  ...CLUSTER_MEMBER_A,
  device_id: "dev-b",
  hostname: "member-b",
  ha_role: "standby",
};

describe("InventoryScreen device selection and panels", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches and renders a selected standalone device's interfaces and routes", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [STANDALONE_DEVICE] }));
      }
      if (url === "/devices/dev-1/inventory") {
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            collected_at: "2026-09-14T12:00:00Z",
            job: { job_id: "job-1", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
            contexts: [
              {
                context: "physical",
                interfaces: [
                  {
                    name: "eth0",
                    parent: null,
                    kind: "physical",
                    state: "up",
                    addresses: [{ address: "198.51.100.5/24", family: "ipv4", role: "member" }],
                  },
                ],
                routes: [
                  { destination: "0.0.0.0/0", next_hop: "198.51.100.1", interface: "eth0", protocol: "default", table: null },
                ],
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    await waitFor(() => expect(screen.getByText("eth0")).toBeInTheDocument());
    expect(screen.getByText("198.51.100.5/24")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Routing" }));
    await waitFor(() => expect(screen.getByText("0.0.0.0/0")).toBeInTheDocument());
  });

  it("renders the VLAN id column and the context's HA role when the run recorded them", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [STANDALONE_DEVICE] }));
      }
      if (url === "/devices/dev-1/inventory") {
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            collected_at: "2026-09-14T12:00:00Z",
            job: { job_id: "job-1", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
            contexts: [
              {
                context: "physical",
                ha: { role: "ACTIVE", cluster_mode: "High Availability" },
                interfaces: [
                  {
                    name: "eth0.100",
                    parent: "eth0",
                    kind: "vlan",
                    state: "up",
                    vlan_id: 100,
                    addresses: [],
                  },
                ],
                routes: [],
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    await waitFor(() => expect(screen.getByText("eth0.100")).toBeInTheDocument());
    expect(screen.getByText("100")).toBeInTheDocument();
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
    expect(screen.getByText("High Availability")).toBeInTheDocument();
  });

  it("renders the cluster unified view with a member marker and VIP once for a cluster device", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [CLUSTER_MEMBER_A, CLUSTER_MEMBER_B] }));
      }
      if (url === "/clusters/cluster-1/inventory") {
        return Promise.resolve(
          jsonResponse(200, {
            cluster_member_ref: "cluster-1",
            members: [
              { device_id: "dev-a", hostname: "member-a" },
              { device_id: "dev-b", hostname: "member-b" },
            ],
            contexts: [
              {
                context: "physical",
                interfaces: [
                  {
                    name: "eth0",
                    kind: "physical",
                    addresses: [{ address: "192.0.2.100/24", family: "ipv4", role: "cluster_virtual" }],
                    presence: "all",
                    differences: [{ device_id: "dev-b", field: "state", value: "down" }],
                  },
                ],
                routes: [],
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("member-a")).toBeInTheDocument());
    fireEvent.click(screen.getByText("member-a"));

    // The membership marker names both members, and the VIP appears exactly once.
    await waitFor(() => expect(screen.getAllByText("member-b").length).toBeGreaterThan(0));
    expect(screen.getAllByText("192.0.2.100/24")).toHaveLength(1);
    expect(screen.getByText("VIP")).toBeInTheDocument();
    expect(screen.getByText(/state = down/)).toBeInTheDocument();
  });

  it("Collect now submits, polls the device, and refreshes the inventory once the job is terminal", async () => {
    let devicePollCount = 0;
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [STANDALONE_DEVICE] }));
      }
      if (url === "/devices/dev-1/inventory") {
        return Promise.resolve(jsonResponse(200, { device_id: "dev-1", collected_at: null, job: null, contexts: [] }));
      }
      if (url === "/session/status") {
        return Promise.resolve(jsonResponse(200, { csrf_token: "token-1" }));
      }
      if (url === "/devices/dev-1/inventory/collect" && init?.method === "POST") {
        return Promise.resolve(jsonResponse(202, { job_id: "job-2" }));
      }
      if (url === "/devices/dev-1") {
        devicePollCount += 1;
        const terminal = devicePollCount >= 2;
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            vendor_hint: "check_point",
            enrollment_state: "ENROLLED",
            disabled: false,
            facts: null,
            peer_follow_outcome: null,
            peer_follow_reason: null,
            identity_mismatch_state: "NONE",
            cluster_member_ref: null,
            job: { job_id: "job-2", state: terminal ? "COMPLETED" : "EXECUTING", outcome: terminal ? "SUCCESS" : null, terminal_reason: null },
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    const collectButton = await screen.findByRole("button", { name: "Collect now" });
    fireEvent.click(collectButton);

    await waitFor(() =>
      expect(fetchMock.mock.calls.some((call) => String(call[0]) === "/devices/dev-1/inventory/collect")).toBe(true),
    );
    await waitFor(() => expect(devicePollCount).toBeGreaterThanOrEqual(2), { timeout: 5000 });
  });
});

describe("BackupPanel", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders an empty panel when no backups are retained", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, { backups: [] }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    
    render(withTheme(<BackupPanel deviceId="dev-1" />));
    await waitFor(() => expect(screen.getByText("No backups")).toBeInTheDocument());
    expect(screen.getByText("No backup has been retained for this device.")).toBeInTheDocument();
  });

  it("renders a list of backups with their properties, translating a null deviation state to 'not evaluated'", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, {
          backups: [
            {
              artefact_id: "art-1",
              device_id: "dev-1",
              collected_at: "2026-09-14T12:00:00Z",
              size_bytes: 1024,
              digest_prefix: "abcd123",
              validation_level: "trusted",
              deviation_state: null
            },
            {
              artefact_id: "art-2",
              device_id: "dev-1",
              collected_at: "2026-09-13T12:00:00Z",
              size_bytes: 1024,
              digest_prefix: "9876xyz",
              validation_level: "trusted",
              deviation_state: "changed"
            }
          ]
        }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    
    render(withTheme(<BackupPanel deviceId="dev-1" />));
    
    await waitFor(() => expect(screen.getByText("abcd123")).toBeInTheDocument());
    expect(screen.getByText("9876xyz")).toBeInTheDocument();
    
    expect(screen.getByText("not evaluated")).toBeInTheDocument();
    expect(screen.queryByText("unchanged")).not.toBeInTheDocument();
    expect(screen.getByText("changed")).toBeInTheDocument();
  });

  it("shows the error rather than an empty table on failure", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(500, { error: "Failed to connect to storage" }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    
    render(withTheme(<BackupPanel deviceId="dev-1" />));
    
    await waitFor(() => expect(screen.getByText("Failed to connect to storage")).toBeInTheDocument());
    expect(screen.getByText("Backup unavailable")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
