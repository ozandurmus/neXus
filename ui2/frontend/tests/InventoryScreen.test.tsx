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

  it("sorts the device list by name and by vendor via the sort control", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, {
      devices: [
        { device_id: "z-pan", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "zeta-pan", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
        { device_id: "a-cp", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "alpha-cp", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
        { device_id: "m-cp", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "mid-cp", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
      ],
    })));
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText("zeta-pan")).toBeInTheDocument());

    const namesInOrder = () => screen.getAllByText(/^(zeta-pan|alpha-cp|mid-cp)$/).map((el) => el.textContent);

    // Default: Name (A->Z).
    expect(namesInOrder()).toEqual(["alpha-cp", "mid-cp", "zeta-pan"]);

    fireEvent.change(screen.getByLabelText("Sort devices"), { target: { value: "name_desc" } });
    expect(namesInOrder()).toEqual(["zeta-pan", "mid-cp", "alpha-cp"]);

    fireEvent.change(screen.getByLabelText("Sort devices"), { target: { value: "vendor" } });
    // Check Point sorts before Palo Alto alphabetically; within Check Point, name breaks the tie.
    expect(namesInOrder()).toEqual(["alpha-cp", "mid-cp", "zeta-pan"]);
  });

  it("distinguishes completed, failed, and never-collected devices and filters only failures", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, {
      devices: [
        { device_id: "done", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "done-device", model: null, software_version: null, ha_role: null, cluster_member_ref: null, latest_job_state: "COMPLETED" },
        { device_id: "failed", vendor_hint: "check_point", enrollment_state: "DRAFT", hostname: "failed-device", model: null, software_version: null, ha_role: null, cluster_member_ref: null, latest_job_state: "FAILED", latest_job_terminal_reason: "connect_failed: transport detail" },
        { device_id: "unknown-failure", vendor_hint: "check_point", enrollment_state: "DRAFT", hostname: "unknown-failure-device", model: null, software_version: null, ha_role: null, cluster_member_ref: null, latest_job_state: "FAILED", latest_job_terminal_reason: "unknown_class: transport detail" },
        { device_id: "new", vendor_hint: "check_point", enrollment_state: "DRAFT", hostname: "new-device", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
      ],
    })));
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("Collection completed")).toBeInTheDocument());
    expect(screen.getByText("Collection attempt failed · Connection failed")).toBeInTheDocument();
    expect(screen.getByText("Collection attempt failed · Recorded failure")).toBeInTheDocument();
    expect(screen.getByText("Not yet collected")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Failed 2 of 4" }));
    expect(screen.getByText("failed-device")).toBeInTheDocument();
    expect(screen.getByText("unknown-failure-device")).toBeInTheDocument();
    expect(screen.queryByText("done-device")).toBeNull();
    expect(screen.queryByText("new-device")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "All 4" }));
    expect(screen.getByText("done-device")).toBeInTheDocument();
    expect(screen.getByText("new-device")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Draft 3" }));
    expect(screen.getByText("failed-device")).toBeInTheDocument();
    expect(screen.getByText("unknown-failure-device")).toBeInTheDocument();
    expect(screen.getByText("new-device")).toBeInTheDocument();
    expect(screen.queryByText("done-device")).toBeNull();
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

    await waitFor(() => expect(screen.getByText("cluster-1")).toBeInTheDocument());
    expect(screen.getByText("CLS")).toBeInTheDocument();
    // Design language §2 (refined by Product Owner direction): the sidebar row keeps only the
    // cluster's own name, a "CLS" tag and a health chip -- members are named in the detail
    // header once selected, never rendered as their own sibling row or caption line here.
    expect(screen.queryByText((_, node) => node?.textContent === "ClusterXL · member-a · member-b")).toBeNull();
    expect(screen.queryByRole("button", { name: /^member-a$/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /^member-b$/ })).toBeNull();
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

    await waitFor(() => expect(screen.getByText("FW-CKP-GARANTIDMZAPP-CLS-AA")).toBeInTheDocument());
    expect(screen.getByText("GarantiBetaAA")).toBeInTheDocument();
    expect(screen.getByText("GarantiPosAppAA")).toBeInTheDocument();

    // Clicking the Virtual System selects the cluster and displays the VS details
    fireEvent.click(screen.getByText("GarantiBetaAA"));
    await waitFor(() => expect(screen.getByText(/CLS > FW-CKP-GARANTIDMZAPP-CLS-AA/)).toBeInTheDocument());
    expect(screen.getAllByText("GarantiBetaAA").length).toBeGreaterThanOrEqual(2);
  });

  it("merges a Check Point cluster's Physical and VSX contexts by default like Palo Alto, filters on VS selection, and resets on re-selecting the cluster", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return Promise.resolve(
            jsonResponse(200, {
              devices: [
                {
                  device_id: "dev-vsx-1",
                  vendor_hint: "check_point",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-CKP-VSX-1",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "active",
                  cluster_member_ref: "FW-CKP-VSX-CLS",
                  virtual_systems: "GarantiBetaAA",
                },
                {
                  device_id: "dev-vsx-2",
                  vendor_hint: "check_point",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-CKP-VSX-2",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "standby",
                  cluster_member_ref: "FW-CKP-VSX-CLS",
                  virtual_systems: "GarantiBetaAA",
                },
              ],
            }),
          );
        }
        if (url.includes("/clusters/FW-CKP-VSX-CLS/inventory")) {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_member_ref: "FW-CKP-VSX-CLS",
              members: [
                { device_id: "dev-vsx-1", hostname: "FW-CKP-VSX-1" },
                { device_id: "dev-vsx-2", hostname: "FW-CKP-VSX-2" },
              ],
              contexts: [
                {
                  context: "physical",
                  interfaces: [
                    { name: "Mgmt", kind: "physical", addresses: [{ address: "10.1.1.1/24", family: "ipv4", role: "cluster_virtual" }], presence: "all", differences: [] },
                  ],
                  routes: [],
                },
                {
                  context: "1",
                  vs_name: "GarantiBetaAA",
                  interfaces: [
                    { name: "eth0.100", kind: "vlan", addresses: [{ address: "192.0.2.1/24", family: "ipv4", role: "cluster_virtual" }], presence: "all", differences: [] },
                  ],
                  routes: [],
                },
              ],
              virtual_systems: ["GarantiBetaAA"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("FW-CKP-VSX-CLS")).toBeInTheDocument());
    fireEvent.click(screen.getAllByText("FW-CKP-VSX-CLS")[0]);

    // Default (no VS selected) merges every collected context, physical included, into one table --
    // matching Palo Alto's own reference view, per the Product Owner's explicit direction.
    await waitFor(() => expect(screen.getByText("Mgmt")).toBeInTheDocument());
    expect(screen.getByText("eth0.100")).toBeInTheDocument();

    // Selecting the VS from the sidebar filters to that VS's own interface only.
    fireEvent.click(screen.getByText("GarantiBetaAA"));
    await waitFor(() => expect(screen.queryByText("Mgmt")).toBeNull());
    expect(screen.getByText("eth0.100")).toBeInTheDocument();

    // Re-clicking the cluster's own row resets back to the merged view -- this must not stay
    // stuck showing the previously selected VS (the bug the Product Owner reported live).
    fireEvent.click(screen.getAllByText("FW-CKP-VSX-CLS")[0]);
    await waitFor(() => expect(screen.getByText("Mgmt")).toBeInTheDocument());
    expect(screen.getByText("eth0.100")).toBeInTheDocument();
  });

  it("keeps a standalone Check Point VSX device's Physical and VS contexts separate and sidebar-driven, never merged or double-selectable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return Promise.resolve(
            jsonResponse(200, {
              devices: [
                {
                  device_id: "standalone-vsx-1",
                  vendor_hint: "check_point",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-CKP-STANDALONE-VSX",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: null,
                  cluster_member_ref: null,
                  virtual_systems: "GarantiBetaAA",
                },
              ],
            }),
          );
        }
        if (url === "/devices/standalone-vsx-1/inventory") {
          return Promise.resolve(
            jsonResponse(200, {
              device_id: "standalone-vsx-1",
              collected_at: "2026-09-21T12:00:00Z",
              contexts: [
                {
                  context: "physical",
                  interfaces: [{ name: "Mgmt", kind: "physical", addresses: [{ address: "10.1.1.1/24", family: "ipv4", role: "member" }] }],
                  routes: [],
                },
                {
                  context: "1",
                  vs_name: "GarantiBetaAA",
                  interfaces: [{ name: "eth0.100", kind: "vlan", addresses: [{ address: "192.0.2.1/24", family: "ipv4", role: "member" }] }],
                  routes: [],
                },
              ],
              virtual_systems: ["GarantiBetaAA"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("FW-CKP-STANDALONE-VSX")).toBeInTheDocument());
    fireEvent.click(screen.getByText("FW-CKP-STANDALONE-VSX"));

    // Physical shows only the chassis's own interface -- never merged with the VS's, and no
    // second tab-strip selector (beyond the outer Interfaces/Routing/... tabs) duplicates the
    // sidebar's own VS sub-navigation.
    await waitFor(() => expect(screen.getByText("Mgmt")).toBeInTheDocument());
    expect(screen.queryByText("eth0.100")).toBeNull();
    expect(screen.queryByRole("tab", { name: /GarantiBetaAA/i })).toBeNull();
    expect(screen.queryByRole("tab", { name: /Physical/i })).toBeNull();

    // Selecting the VS from the sidebar switches straight to that VS's own interface.
    fireEvent.click(screen.getByText("GarantiBetaAA"));
    await waitFor(() => expect(screen.getByText("eth0.100")).toBeInTheDocument());
    expect(screen.queryByText("Mgmt")).toBeNull();
    expect(screen.queryByRole("tab", { name: /GarantiBetaAA/i })).toBeNull();
  });

  it("reads two members agreeing on a non-up/down state as that state, not Degraded, and hides loopback", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return Promise.resolve(
            jsonResponse(200, {
              devices: [
                { device_id: "m-a", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "PA-A", model: "PA-440", software_version: "11.0", ha_role: "active", cluster_member_ref: "PA-PAIR" },
                { device_id: "m-b", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "PA-B", model: "PA-440", software_version: "11.0", ha_role: "passive", cluster_member_ref: "PA-PAIR" },
              ],
            }),
          );
        }
        if (url.includes("/clusters/PA-PAIR/inventory")) {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_member_ref: "PA-PAIR",
              members: [{ device_id: "m-a", hostname: "PA-A" }, { device_id: "m-b", hostname: "PA-B" }],
              contexts: [
                {
                  context: "physical",
                  interfaces: [
                    {
                      name: "ethernet1/1", kind: "physical", addresses: [], presence: "all", differences: [],
                      member_states: { "m-a": "unknown", "m-b": "unknown" },
                    },
                    {
                      name: "ethernet1/2", kind: "physical", addresses: [], presence: "all", differences: [],
                      member_states: { "m-a": "up", "m-b": "down" },
                    },
                    { name: "loopback", kind: "loopback", addresses: [], presence: "all", differences: [] },
                  ],
                  routes: [],
                },
              ],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("PA-PAIR")).toBeInTheDocument());
    fireEvent.click(screen.getByText("PA-PAIR"));

    // Both members agreeing on "unknown" is agreement, not a difference -- reads as Unknown, not Degraded.
    await waitFor(() => expect(screen.getByText("ethernet1/1")).toBeInTheDocument());
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    // Members genuinely disagreeing (up vs down) is the only case that reads Degraded.
    expect(screen.getByText("Degraded")).toBeInTheDocument();
    // The loopback interface is collected evidence but never shown on this screen.
    expect(screen.queryByText("loopback")).toBeNull();
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

    await waitFor(() => expect(screen.getByText("PA-HA-PAIR")).toBeInTheDocument());
    expect(screen.getByText("default (vsys1)")).toBeInTheDocument();
    expect(screen.getByText("VR-DMZ (vsys2)")).toBeInTheDocument();
  });

  it("renders one shared Address column for a Palo Alto HA pair instead of a column per member", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") {
          return Promise.resolve(
            jsonResponse(200, {
              devices: [
                { device_id: "pa-1", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "PA-FW-01", model: "PA-3220", software_version: "10.2.4", ha_role: "active", cluster_member_ref: "PA-HA-PAIR" },
                { device_id: "pa-2", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "PA-FW-02", model: "PA-3220", software_version: "10.2.4", ha_role: "passive", cluster_member_ref: "PA-HA-PAIR" },
              ],
            }),
          );
        }
        if (url === "/clusters/PA-HA-PAIR/inventory") {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_ref: "PA-HA-PAIR",
              members: [
                { device_id: "pa-1", hostname: "PA-FW-01" },
                { device_id: "pa-2", hostname: "PA-FW-02" },
              ],
              contexts: [
                {
                  context: "physical",
                  interfaces: [
                    {
                      name: "ethernet1/5",
                      kind: "physical",
                      addresses: [],
                      presence: "all",
                      differences: [],
                      member_addresses: {
                        "pa-1": [{ address: "192.168.250.5/28", family: "ipv4" }],
                        "pa-2": [{ address: "192.168.250.5/28", family: "ipv4" }],
                      },
                    },
                  ],
                  routes: [],
                },
              ],
              virtual_systems: [],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("PA-HA-PAIR")).toBeInTheDocument());
    fireEvent.click(screen.getByText("PA-HA-PAIR"));

    await waitFor(() => expect(screen.getByText("ethernet1/5")).toBeInTheDocument());
    // One shared Address column, not one column per member -- the header above
    // still names each member (PO: that part stays as is), so the table itself
    // is what this asserts, not the whole screen.
    expect(screen.getByText("Address")).toBeInTheDocument();
    expect(screen.getAllByText("192.168.250.5/28")).toHaveLength(1);
    expect(screen.queryByText("Cluster VIP")).toBeNull();
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

    // Clicking CorpNet selects the standalone device and filters to that one VS's own context --
    // no second, redundant place names it now that the old per-VS tab strip is gone.
    fireEvent.click(screen.getByText("CorpNet (vsys2)"));
    await waitFor(() => expect(screen.getByText("No interface evidence")).toBeInTheDocument());
    expect(screen.getAllByText(/CorpNet \(vsys2\)/)).toHaveLength(1);
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
