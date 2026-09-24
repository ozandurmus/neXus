import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ConfigurationScreen, groupByCluster } from "../src/screens/ConfigurationScreen";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

describe("ConfigurationScreen device list", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches GET /configuration and shows an empty state with no devices", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { devices: [] }))));
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("No devices")).toBeInTheDocument());
    expect(screen.getByText("No device selected")).toBeInTheDocument();
  });

  it("renders devices with their change state", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          jsonResponse(200, {
            devices: [
              {
                device_id: "dev-1",
                hostname: "fw-edge-1",
                vendor: "check_point",
                last_collected_at: "2026-09-14T10:00:00Z",
                change_state: "changed",
              },
            ],
          }),
        ),
      ),
    );
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    // Labelled filter dropdowns (PO, 2026-09-24), options written "Label · n".
    const state = screen.getByRole("combobox", { name: "State" });
    expect(within(state).getByRole("option", { name: "Changed · 1" })).toBeInTheDocument();
    expect(within(state).getByRole("option", { name: "First run · 0" })).toBeInTheDocument();
    expect(within(state).getByRole("option", { name: "Not collected · 0" })).toBeInTheDocument();
    expect(within(screen.getByRole("combobox", { name: "Vendor" })).getByRole("option", { name: "Vendor · 1" })).toBeInTheDocument();
    expect(screen.getByText("1 device collected")).toBeInTheDocument();
  });

  it("shows an error state with a retry action when the fetch fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network down")));
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("Configuration unavailable")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});

describe("the configuration list presents the cluster as the unit (design language section 2/3)", () => {
  const entry = (device_id: string, extra: Record<string, unknown> = {}) => ({
    device_id, hostname: `FW-${device_id}`, vendor: "check_point", last_collected_at: "2026-09-22T10:00:00Z",
    change_state: "unchanged" as const, ...extra,
  });

  it("groups members under their cluster and judges agreement by canonical hash", () => {
    const rows = groupByCluster([
      entry("m1", { cluster_member_ref: "CLS-ROMEO-01", canonical_hash: "h1", projected_settings: 352 }),
      entry("s1"),
      entry("m2", { cluster_member_ref: "CLS-ROMEO-01", canonical_hash: "h1", projected_settings: 352 }),
      entry("x1", { cluster_member_ref: "CLS-JULIET-02", canonical_hash: "a" }),
      entry("x2", { cluster_member_ref: "CLS-JULIET-02", canonical_hash: "b" }),
      entry("u1", { cluster_member_ref: "CLS-TANGO-03", canonical_hash: "a" }),
      entry("u2", { cluster_member_ref: "CLS-TANGO-03", canonical_hash: null, change_state: null, last_collected_at: null }),
    ]);

    expect(rows.map((r) => (r.kind === "cluster" ? `cluster:${r.group.clusterRef}:${r.group.agreement}` : `device:${r.device.device_id}`)))
      .toEqual(["cluster:CLS-ROMEO-01:agree", "device:s1", "cluster:CLS-JULIET-02:differ", "cluster:CLS-TANGO-03:unknown"]);
  });

  it("renders the cluster as one tree row and opens the members-side-by-side view on click", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [
          { device_id: "x1", hostname: "FW-x1", vendor_hint: "check_point", enrollment_state: "ENROLLED", model: null, software_version: null, ha_role: "active", cluster_member_ref: "CLS-JULIET-02" },
          { device_id: "x2", hostname: "FW-x2", vendor_hint: "check_point", enrollment_state: "ENROLLED", model: null, software_version: null, ha_role: "standby", cluster_member_ref: "CLS-JULIET-02" },
        ] }));
      }
      if (url === "/configuration") {
        return Promise.resolve(jsonResponse(200, { devices: [
          entry("x1", { cluster_member_ref: "CLS-JULIET-02", canonical_hash: "a", projected_settings: 352 }),
          entry("x2", { cluster_member_ref: "CLS-JULIET-02", canonical_hash: "b", projected_settings: 354, change_state: "changed" }),
        ] }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    }));
    render(withTheme(<ConfigurationScreen />));

    const clusterRow = await screen.findByText(/JULIET-02/);
    expect(screen.getByText("CLS")).toBeInTheDocument();
    fireEvent.click(clusterRow);
    await waitFor(() => expect(screen.getByText("Cluster configuration")).toBeInTheDocument());
    expect(screen.getByText(/No member has a configuration read/)).toBeInTheDocument();
  });
});
