import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen } from "../src/screens/InventoryScreen";

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
