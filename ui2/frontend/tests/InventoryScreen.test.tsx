import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import { deriveClusterTitle } from "../src/screens/InventoryPanels";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const TABS = [
  { label: "Interfaces", marker: "No interface evidence" },
  { label: "Routing", marker: "No routing evidence" },
  { label: "Cluster members", marker: "No cluster membership evidence" },
  { label: "Identity & provenance", marker: "No identity or provenance evidence" },
];

describe("InventoryScreen detail tabs", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders each tab's own panel, and no other tab's panel, tab by tab", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { devices: [] }))));
    render(withTheme(<InventoryScreen />));
    // Let the list panel's own GET /devices settle before driving the
    // unrelated detail tabs, so its state update isn't left dangling
    // outside of React's act() once the test's synchronous body returns.
    await waitFor(() => expect(screen.getByText("No devices")).toBeInTheDocument());
    const tablist = screen.getByRole("tablist", { name: "Device detail" });

    for (const tab of TABS) {
      fireEvent.click(within(tablist).getByRole("tab", { name: tab.label }));
      expect(screen.getByText(tab.marker)).toBeInTheDocument();
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("defaults to the Interfaces tab, empty", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { devices: [] }))));
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText("No devices")).toBeInTheDocument());
    expect(screen.getByText("No interface evidence")).toBeInTheDocument();
    expect(screen.queryByText("No routing evidence")).toBeNull();
  });
});

describe("InventoryScreen device list", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches GET /devices and renders standalone devices", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          jsonResponse(200, {
            devices: [
              {
                device_id: "dev-1",
                vendor_hint: "check_point",
                enrollment_state: "DRAFT",
                hostname: "fw-edge-1",
                model: null,
                software_version: null,
                ha_role: null,
                cluster_member_ref: null,
              },
            ],
          }),
        ),
      ),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    expect(screen.getByText("Registered, not confirmed")).toBeInTheDocument();
    expect(screen.getByText("1 device enrolled")).toBeInTheDocument();
  });

  it("groups devices sharing a cluster_member_ref under one parent grouping", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          jsonResponse(200, {
            devices: [
              {
                device_id: "dev-a",
                vendor_hint: "check_point",
                enrollment_state: "ENROLLED",
                hostname: "member-a",
                model: "Quantum",
                software_version: "R81.20",
                ha_role: "active",
                cluster_member_ref: "cluster-1",
              },
              {
                device_id: "dev-b",
                vendor_hint: "check_point",
                enrollment_state: "ENROLLED",
                hostname: "member-b",
                model: "Quantum",
                software_version: "R81.20",
                ha_role: "standby",
                cluster_member_ref: "cluster-1",
              },
              {
                device_id: "dev-c",
                vendor_hint: "palo_alto",
                enrollment_state: "ENROLLED",
                hostname: "standalone-c",
                model: "PA-440",
                software_version: "11.0",
                ha_role: null,
                cluster_member_ref: null,
              },
            ],
          }),
        ),
      ),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("Cluster cluster-1")).toBeInTheDocument());
    expect(screen.getByText("2 members")).toBeInTheDocument();
    expect(screen.getByText("member-a")).toBeInTheDocument();
    expect(screen.getByText("member-b")).toBeInTheDocument();
    // The standalone device (null cluster_member_ref) renders as a normal row, not nested.
    expect(screen.getByText("standalone-c")).toBeInTheDocument();
  });

  it("renders virtual systems under their parent cluster and selects a virtual system context", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return Promise.resolve(
            jsonResponse(200, {
              devices: [
                {
                  device_id: "dev-dmz-1",
                  vendor_hint: "check_point",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-CKP-GARANTIDMZAPP-AA.1",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "active",
                  cluster_member_ref: "FW-CKP-GARANTIDMZAPP-CLS-AA",
                  virtual_systems: "GarantiBetaAA, GarantiPosAppAA",
                },
                {
                  device_id: "dev-dmz-2",
                  vendor_hint: "check_point",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-CKP-GARANTIDMZAPP-AA.2",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "standby",
                  cluster_member_ref: "FW-CKP-GARANTIDMZAPP-CLS-AA",
                  virtual_systems: "GarantiBetaAA, GarantiPosAppAA",
                },
              ],
            }),
          );
        }
        if (url.includes("/clusters/FW-CKP-GARANTIDMZAPP-CLS-AA/inventory")) {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_member_ref: "FW-CKP-GARANTIDMZAPP-CLS-AA",
              members: [
                { device_id: "dev-dmz-1", hostname: "FW-CKP-GARANTIDMZAPP-AA.1", ha_role: "active", model: "Quantum", software_version: "R81.20", enrollment_state: "ENROLLED", virtual_systems: "GarantiBetaAA, GarantiPosAppAA" },
                { device_id: "dev-dmz-2", hostname: "FW-CKP-GARANTIDMZAPP-AA.2", ha_role: "standby", model: "Quantum", software_version: "R81.20", enrollment_state: "ENROLLED", virtual_systems: "GarantiBetaAA, GarantiPosAppAA" },
              ],
              contexts: [
                {
                  context: "physical",
                  interfaces: [
                    { name: "Mgmt", kind: "physical", addresses: [{ address: "10.176.107.91/24", family: "ipv4", role: "cluster_virtual" }], presence: "all", differences: [] },
                  ],
                  routes: [],
                },
              ],
              virtual_systems: ["GarantiBetaAA", "GarantiPosAppAA"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("Cluster FW-CKP-GARANTIDMZAPP-CLS-AA")).toBeInTheDocument());
    expect(screen.getByText("2 members")).toBeInTheDocument();
    expect(screen.getByText("2 VS")).toBeInTheDocument();
    expect(screen.getByText("GarantiBetaAA")).toBeInTheDocument();
    expect(screen.getByText("GarantiPosAppAA")).toBeInTheDocument();

    // Clicking the Virtual System selects the cluster and displays the VS details
    fireEvent.click(screen.getByText("GarantiBetaAA"));
    await waitFor(() => expect(screen.getByText(/CLS > FW-CKP-GARANTIDMZAPP-CLS-AA/)).toBeInTheDocument());
    expect(screen.getAllByText("GarantiBetaAA").length).toBeGreaterThanOrEqual(2);
  });

  it("groups Palo Alto HA members and renders PAN-OS HA with VSYS chips", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return Promise.resolve(
            jsonResponse(200, {
              devices: [
                {
                  device_id: "pa-1",
                  vendor_hint: "palo_alto",
                  enrollment_state: "ENROLLED",
                  hostname: "PA-FW-01",
                  model: "PA-3220",
                  software_version: "10.2.4",
                  ha_role: "active",
                  cluster_member_ref: "PA-HA-PAIR",
                  virtual_systems: "default (vsys1), VR-DMZ (vsys2)",
                },
                {
                  device_id: "pa-2",
                  vendor_hint: "palo_alto",
                  enrollment_state: "ENROLLED",
                  hostname: "PA-FW-02",
                  model: "PA-3220",
                  software_version: "10.2.4",
                  ha_role: "passive",
                  cluster_member_ref: "PA-HA-PAIR",
                  virtual_systems: "default (vsys1), VR-DMZ (vsys2)",
                },
              ],
            }),
          );
        }
        if (url === "/clusters/PA-HA-PAIR/inventory") {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_ref: "PA-HA-PAIR",
              collected_at: "2026-09-19T12:00:00Z",
              members: [
                { device_id: "pa-1", hostname: "PA-FW-01", ha_role: "active", model: "PA-3220", software_version: "10.2.4", enrollment_state: "ENROLLED", virtual_systems: "default (vsys1), VR-DMZ (vsys2)" },
                { device_id: "pa-2", hostname: "PA-FW-02", ha_role: "passive", model: "PA-3220", software_version: "10.2.4", enrollment_state: "ENROLLED", virtual_systems: "default (vsys1), VR-DMZ (vsys2)" },
              ],
              contexts: [],
              virtual_systems: ["default (vsys1)", "VR-DMZ (vsys2)"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("Cluster PA-HA-PAIR")).toBeInTheDocument());
    expect(screen.getByText("2 members")).toBeInTheDocument();
    expect(screen.getByText("2 VSYS")).toBeInTheDocument();
    expect(screen.getByText(/PAN-OS HA · PA-FW-01 · PA-FW-02/)).toBeInTheDocument();
    expect(screen.getByText("default (vsys1)")).toBeInTheDocument();
    expect(screen.getByText("VR-DMZ (vsys2)")).toBeInTheDocument();
  });

  it("renders standalone device virtual systems with expand/collapse and selection", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return Promise.resolve(
            jsonResponse(200, {
              devices: [
                {
                  device_id: "standalone-pa-1",
                  vendor_hint: "palo_alto",
                  enrollment_state: "ENROLLED",
                  hostname: "PA-STANDALONE",
                  model: "PA-440",
                  software_version: "11.0.1",
                  ha_role: null,
                  cluster_member_ref: null,
                  virtual_systems: "default (vsys1), CorpNet (vsys2)",
                },
              ],
            }),
          );
        }
        if (url === "/devices/standalone-pa-1/inventory") {
          return Promise.resolve(
            jsonResponse(200, {
              device_id: "standalone-pa-1",
              collected_at: "2026-09-19T12:00:00Z",
              contexts: [
                { context: "vsys1", vs_name: "default (vsys1)", interfaces: [], routes: [] },
                { context: "vsys2", vs_name: "CorpNet (vsys2)", interfaces: [], routes: [] },
              ],
              virtual_systems: ["default (vsys1)", "CorpNet (vsys2)"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("PA-STANDALONE")).toBeInTheDocument());
    expect(screen.getByText("2 VSYS")).toBeInTheDocument();
    expect(screen.getByText("default (vsys1)")).toBeInTheDocument();
    expect(screen.getByText("CorpNet (vsys2)")).toBeInTheDocument();

    // Clicking CorpNet selects the standalone device and activates the CorpNet context tab
    fireEvent.click(screen.getByText("CorpNet (vsys2)"));
    await waitFor(() => expect(screen.getAllByText(/CorpNet \(vsys2\)/).length).toBeGreaterThanOrEqual(2));
  });

  it("shows an error state with a retry action when the fetch fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network down")));
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("Inventory unavailable")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});

describe("deriveClusterTitle", () => {
  it("preserves explicit cluster references that do not contain pipe delimiters", () => {
    const members = [
      { device_id: "1", hostname: "cp-gw-1", vendor_hint: "check_point" },
      { device_id: "2", hostname: "cp-gw-2", vendor_hint: "check_point" },
    ] as any;
    expect(deriveClusterTitle("MyClusterName", members)).toBe("MyClusterName");
    expect(deriveClusterTitle("PA-HA-PAIR", members)).toBe("PA-HA-PAIR");
  });

  it("derives clean cluster name from Palo Alto reciprocal serial pair references", () => {
    const members = [
      { device_id: "1", hostname: "FW-PALT-GARTEST.AA.1", vendor_hint: "palo_alto" },
      { device_id: "2", hostname: "FW-PALT-GARTEST.AA.2", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("026109000729|026109000751", members)).toBe("FW-PALT-GARTEST.AA-CLS");
  });

  it("handles numeric and hyphenated member suffixes like -01 / -02", () => {
    const members = [
      { device_id: "1", hostname: "FW-PALT-PENDIKCAMPUS-01", vendor_hint: "palo_alto" },
      { device_id: "2", hostname: "FW-PALT-PENDIKCAMPUS-02", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("025501001167|025509000707", members)).toBe("FW-PALT-PENDIKCAMPUS-CLS");
  });

  it("does not duplicate -CLS if the common base already ends in -CLS", () => {
    const members = [
      { device_id: "1", hostname: "FW-EDGE-CLS-1", vendor_hint: "palo_alto" },
      { device_id: "2", hostname: "FW-EDGE-CLS-2", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("serial1|serial2", members)).toBe("FW-EDGE-CLS");
  });

  it("falls back to clusterRef if no member hostnames are present", () => {
    const members = [
      { device_id: "1", vendor_hint: "palo_alto" },
      { device_id: "2", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("024409002545|024409002564", members)).toBe("024409002545|024409002564");
  });
});

