import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen, registeredLabel } from "../src/screens/InventoryScreen";
import { deriveClusterTitle } from "../src/screens/InventoryPanels";
import { GlobalSearch } from "../src/shell/GlobalSearch";
import { setListSearchTerm } from "../src/shell/listSearch";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

describe("InventoryScreen detail before a selection", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // Review §3: the detail tabs stay hidden until a device or cluster is chosen.
  it("shows no detail tabs until a device or cluster is selected", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { devices: [] }))));
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText("No devices")).toBeInTheDocument());
    expect(screen.queryByRole("tablist", { name: "Device detail" })).toBeNull();
    expect(screen.getByText("No device to show")).toBeInTheDocument();
    expect(screen.queryByText("No routing evidence")).toBeNull();
  });

  it("titles the page Devices, as the rail does", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { devices: [] }))));
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText("No devices")).toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "Devices" })).toBeInTheDocument();
    expect(screen.queryByText("Network inventory")).toBeNull();
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
                hostname: "FW-TANGO-04",
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

    await waitFor(() => expect(screen.getByText("FW-TANGO-04")).toBeInTheDocument());
    expect(screen.getByText("Registered, not confirmed")).toBeInTheDocument();
    // a DRAFT device is registered, not enrolled (it read "1 device enrolled" until 2026-10-17)
    expect(screen.getByText("1 registered · 0 enrolled")).toBeInTheDocument();
    // F-3 (UI review 2026-10-18): the landing sentence carries the header's count, from the same list.
    expect(screen.getByText(/^1 registered · 0 enrolled\. Choose one on the left to see its interfaces, routing, cluster members, configuration, backup, logs and identity\./)).toBeInTheDocument();
  });

  it("names registered and enrolled devices in one sentence, worded like the Overview", () => {
    const rows = (states: string[]) => states.map((enrollment_state) => ({ enrollment_state }));
    expect(registeredLabel(rows([...Array(133).fill("ENROLLED"), "DRAFT", "DRAFT"]))).toBe("135 registered · 133 enrolled");
    expect(registeredLabel(rows(Array(133).fill("ENROLLED")))).toBe("133 enrolled devices");
    expect(registeredLabel(rows(["ENROLLED"]))).toBe("1 enrolled device");
    expect(registeredLabel(rows(["ENROLLED", "DEGRADED"]))).toBe("2 registered · 1 enrolled");
  });

  it("sorts the device list by name and by vendor via the sort control", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, {
      devices: [
        { device_id: "z-pan", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-ZULU-03", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
        { device_id: "a-cp", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-ALPHA-01", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
        { device_id: "m-cp", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-MIKE-02", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
      ],
    })));
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText("FW-ZULU-03")).toBeInTheDocument());

    const namesInOrder = () => screen.getAllByText(/^(FW-ZULU-03|FW-ALPHA-01|FW-MIKE-02)$/).map((el) => el.textContent);

    // Default: Name (A->Z).
    expect(namesInOrder()).toEqual(["FW-ALPHA-01", "FW-MIKE-02", "FW-ZULU-03"]);

    // Sort lives in the list's view-and-sort menu.
    fireEvent.click(screen.getByRole("button", { name: "List view and sort" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Name (Z→A)" }));
    expect(namesInOrder()).toEqual(["FW-ZULU-03", "FW-MIKE-02", "FW-ALPHA-01"]);

    fireEvent.click(screen.getByRole("button", { name: "List view and sort" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Vendor (A→Z)" }));
    // Check Point sorts before Palo Alto alphabetically; within Check Point, name breaks the tie.
    expect(namesInOrder()).toEqual(["FW-ALPHA-01", "FW-MIKE-02", "FW-ZULU-03"]);
  });

  it("distinguishes completed, failed, and never-collected devices and filters only failures", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, {
      devices: [
        { device_id: "done", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-ALPHA-01", model: null, software_version: null, ha_role: null, cluster_member_ref: null, latest_job_state: "COMPLETED" },
        { device_id: "failed", vendor_hint: "check_point", enrollment_state: "DRAFT", hostname: "FW-BRAVO-02", model: null, software_version: null, ha_role: null, cluster_member_ref: null, latest_job_state: "FAILED", latest_job_terminal_reason: "connect_failed: transport detail" },
        { device_id: "unknown-failure", vendor_hint: "check_point", enrollment_state: "DRAFT", hostname: "FW-CHARLIE-03", model: null, software_version: null, ha_role: null, cluster_member_ref: null, latest_job_state: "FAILED", latest_job_terminal_reason: "unknown_class: transport detail" },
        { device_id: "new", vendor_hint: "check_point", enrollment_state: "DRAFT", hostname: "FW-DELTA-04", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
      ],
    })));
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("FW-BRAVO-02")).toBeInTheDocument());
    const rowText = (name: string) => [...document.querySelectorAll('[data-row="device"]')]
      .find((row) => row.textContent?.includes(name))?.textContent ?? "";
    expect(rowText("FW-BRAVO-02")).toContain("Collection failed · Connection failed");
    expect(rowText("FW-CHARLIE-03")).toContain("Collection failed");
    expect(screen.queryByText("Collection completed")).toBeNull();
    expect(screen.queryByText("Not yet collected")).toBeNull();

    // Each filter is a labelled dropdown (PO, 2026-09-24) whose options read "Label · n".
    const stateRow = screen.getByRole("combobox", { name: "State" }) as HTMLSelectElement;
    expect(within(screen.getByRole("combobox", { name: "Vendor" })).getByRole("option", { name: "Check Point · 4" })).toBeInTheDocument();

    fireEvent.change(stateRow, { target: { value: "failed" } });
    expect(stateRow.value).toBe("failed");
    expect(screen.getByText("FW-BRAVO-02")).toBeInTheDocument();
    expect(screen.getByText("FW-CHARLIE-03")).toBeInTheDocument();
    expect(screen.queryByText("FW-ALPHA-01")).toBeNull();
    expect(screen.queryByText("FW-DELTA-04")).toBeNull();

    fireEvent.change(stateRow, { target: { value: "all" } });
    expect(screen.getByText("FW-ALPHA-01")).toBeInTheDocument();
    expect(screen.getByText("FW-DELTA-04")).toBeInTheDocument();

    fireEvent.change(stateRow, { target: { value: "draft" } });
    expect(screen.getByText("FW-BRAVO-02")).toBeInTheDocument();
    expect(screen.getByText("FW-CHARLIE-03")).toBeInTheDocument();
    expect(screen.getByText("FW-DELTA-04")).toBeInTheDocument();
    expect(screen.queryByText("FW-ALPHA-01")).toBeNull();
  });

  it("shows only available device facts in the sidebar subtitle", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, {
      devices: [
        { device_id: "full", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04", role: "gateway", model: "Quantum 6600", software_version: "R81.20", ha_role: null, management_ip: "192.0.2.10", latest_job_state: "COMPLETED" },
        { device_id: "sparse", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-JULIET-06", model: "PA-440", software_version: null, ha_role: null },
      ],
    })));
    render(withTheme(<InventoryScreen />));
    await waitFor(() => expect(screen.getByText("FW-TANGO-04")).toBeInTheDocument());
    const rowText = (name: string) => [...document.querySelectorAll('[data-row="device"]')]
      .find((row) => row.textContent?.includes(name))?.textContent ?? "";
    expect(rowText("FW-TANGO-04")).toContain("Check Point · Quantum 6600 · R81.20");
    expect(rowText("FW-JULIET-06")).toContain("Palo Alto · PA-440");
    expect(screen.queryByText(/Collection completed|No HA role|Unknown model|192\.0\.2\.10/)).toBeNull();
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
                hostname: "FW-ROMEO-01-M1",
                model: "Quantum",
                software_version: "R81.20",
                ha_role: "active",
                cluster_member_ref: "CLS-ROMEO-01",
              },
              {
                device_id: "dev-b",
                vendor_hint: "check_point",
                enrollment_state: "ENROLLED",
                hostname: "FW-ROMEO-01-M2",
                model: "Quantum",
                software_version: "R81.20",
                ha_role: "standby",
                cluster_member_ref: "CLS-ROMEO-01",
              },
              {
                device_id: "dev-c",
                vendor_hint: "palo_alto",
                enrollment_state: "ENROLLED",
                hostname: "FW-TANGO-04",
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

    await waitFor(() => expect(screen.getByText("CLS-ROMEO-01")).toBeInTheDocument());
    expect(screen.getByText("CLS")).toBeInTheDocument();
    // Design language §2 (refined by Product Owner direction): the sidebar row keeps only the
    // cluster's own name, a "CLS" tag and a health chip -- members are named in the detail
    // header once selected, never rendered as their own sibling row or caption line here.
    expect(screen.queryByText((_, node) => node?.textContent === "ClusterXL · FW-ROMEO-01-M1 · FW-ROMEO-01-M2")).toBeNull();
    expect(screen.queryByRole("button", { name: /^FW-ROMEO-01-M1$/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /^FW-ROMEO-01-M2$/ })).toBeNull();
    // The standalone device (null cluster_member_ref) renders as a normal row, not nested.
    expect(screen.getByText("FW-TANGO-04")).toBeInTheDocument();
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
                  hostname: "FW-ROMEO-01-M1",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "active",
                  cluster_member_ref: "CLS-ROMEO-01",
                  virtual_systems: "VS-ROMEO-01-01, VS-ROMEO-01-02",
                },
                {
                  device_id: "dev-dmz-2",
                  vendor_hint: "check_point",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-ROMEO-01-M2",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "standby",
                  cluster_member_ref: "CLS-ROMEO-01",
                  virtual_systems: "VS-ROMEO-01-01, VS-ROMEO-01-02",
                },
              ],
            }),
          );
        }
        if (url.includes("/clusters/CLS-ROMEO-01/inventory")) {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_member_ref: "CLS-ROMEO-01",
              members: [
                { device_id: "dev-dmz-1", hostname: "FW-ROMEO-01-M1", ha_role: "active", model: "Quantum", software_version: "R81.20", enrollment_state: "ENROLLED", virtual_systems: "VS-ROMEO-01-01, VS-ROMEO-01-02" },
                { device_id: "dev-dmz-2", hostname: "FW-ROMEO-01-M2", ha_role: "standby", model: "Quantum", software_version: "R81.20", enrollment_state: "ENROLLED", virtual_systems: "VS-ROMEO-01-01, VS-ROMEO-01-02" },
              ],
              contexts: [
                {
                  context: "physical",
                  interfaces: [
                    { name: "Mgmt", kind: "physical", addresses: [{ address: "192.0.2.91/24", family: "ipv4", role: "cluster_virtual" }], presence: "all", differences: [] },
                  ],
                  routes: [],
                },
              ],
              virtual_systems: ["VS-ROMEO-01-01", "VS-ROMEO-01-02"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("CLS-ROMEO-01")).toBeInTheDocument());
    // UI review 2026-11-05, C5: children start collapsed, with their count on the cluster row
    expect(screen.queryByText("VS-ROMEO-01-01")).toBeNull();
    expect(screen.getByTestId("tree-child-count")).toHaveTextContent("2 VS");
    fireEvent.click(screen.getByTitle("Expand virtual systems"));
    expect(screen.getByText("VS-ROMEO-01-01")).toBeInTheDocument();
    expect(screen.getByText("VS-ROMEO-01-02")).toBeInTheDocument();

    // Clicking the Virtual System selects the cluster and displays the VS details
    fireEvent.click(screen.getByText("VS-ROMEO-01-01"));
    // The pane header names the parent and the selected virtual system (UI review 2026-10-18, P0-3B)
    expect(await screen.findByRole("heading", { name: "CLS-ROMEO-01 › VS-ROMEO-01-01" })).toBeInTheDocument();
    expect(screen.getAllByText("VS-ROMEO-01-01").length).toBeGreaterThanOrEqual(2);
  });

  it("shows a Check Point cluster's own physical context by default, filters to a VS on sidebar selection, and resets on re-selecting the cluster", async () => {
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
                  hostname: "FW-ROMEO-01-M1",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "active",
                  cluster_member_ref: "CLS-ROMEO-01",
                  virtual_systems: "VS-ROMEO-01-01",
                },
                {
                  device_id: "dev-vsx-2",
                  vendor_hint: "check_point",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-ROMEO-01-M2",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: "standby",
                  cluster_member_ref: "CLS-ROMEO-01",
                  virtual_systems: "VS-ROMEO-01-01",
                },
              ],
            }),
          );
        }
        if (url.includes("/clusters/CLS-ROMEO-01/inventory")) {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_member_ref: "CLS-ROMEO-01",
              members: [
                { device_id: "dev-vsx-1", hostname: "FW-ROMEO-01-M1" },
                { device_id: "dev-vsx-2", hostname: "FW-ROMEO-01-M2" },
              ],
              contexts: [
                {
                  context: "physical",
                  interfaces: [
                    { name: "Mgmt", kind: "physical", addresses: [{ address: "192.0.2.10/24", family: "ipv4", role: "cluster_virtual" }], presence: "all", differences: [] },
                  ],
                  routes: [],
                },
                {
                  context: "1",
                  vs_name: "VS-ROMEO-01-01",
                  interfaces: [
                    { name: "eth0.100", kind: "vlan", addresses: [{ address: "192.0.2.1/24", family: "ipv4", role: "cluster_virtual" }], presence: "all", differences: [] },
                  ],
                  routes: [],
                },
              ],
              virtual_systems: ["VS-ROMEO-01-01"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("CLS-ROMEO-01")).toBeInTheDocument());
    fireEvent.click(screen.getAllByText("CLS-ROMEO-01")[0]);

    // Default (no VS selected) is the cluster's own physical context only -- a virtual system is
    // its own sidebar node with its own view (Product Owner, 2026-09-22, reversing the earlier
    // merged-by-default direction for Check Point; Palo Alto keeps its merged vsys view).
    await waitFor(() => expect(screen.getByText("Mgmt")).toBeInTheDocument());
    expect(screen.queryByText("eth0.100")).toBeNull();

    // Selecting the VS from the sidebar filters to that VS's own interface only.
    fireEvent.click(screen.getByText("VS-ROMEO-01-01"));
    await waitFor(() => expect(screen.queryByText("Mgmt")).toBeNull());
    expect(screen.getByText("eth0.100")).toBeInTheDocument();

    // Re-clicking the cluster's own row resets back to the physical view -- this must not stay
    // stuck showing the previously selected VS (the bug the Product Owner reported live).
    fireEvent.click(screen.getAllByText("CLS-ROMEO-01")[0]);
    await waitFor(() => expect(screen.getByText("Mgmt")).toBeInTheDocument());
    expect(screen.queryByText("eth0.100")).toBeNull();
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
                  hostname: "FW-TANGO-04",
                  model: "Quantum",
                  software_version: "R81.20",
                  ha_role: null,
                  cluster_member_ref: null,
                  virtual_systems: "VS-ROMEO-01-01",
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
                  interfaces: [{ name: "Mgmt", kind: "physical", addresses: [{ address: "192.0.2.10/24", family: "ipv4", role: "member" }] }],
                  routes: [],
                },
                {
                  context: "1",
                  vs_name: "VS-ROMEO-01-01",
                  interfaces: [{ name: "eth0.100", kind: "vlan", addresses: [{ address: "192.0.2.1/24", family: "ipv4", role: "member" }] }],
                  routes: [],
                },
              ],
              virtual_systems: ["VS-ROMEO-01-01"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("FW-TANGO-04")).toBeInTheDocument());
    fireEvent.click(screen.getByText("FW-TANGO-04"));

    // Physical shows only the chassis's own interface -- never merged with the VS's, and no
    // second tab-strip selector (beyond the outer Interfaces/Routing/... tabs) duplicates the
    // sidebar's own VS sub-navigation.
    await waitFor(() => expect(screen.getByText("Mgmt")).toBeInTheDocument());
    expect(screen.queryByText("eth0.100")).toBeNull();
    expect(screen.queryByRole("tab", { name: /VS-ROMEO-01-01/i })).toBeNull();
    expect(screen.queryByRole("tab", { name: /Physical/i })).toBeNull();

    // Selecting the VS from the sidebar switches straight to that VS's own interface.
    expect(screen.queryByText("VS-ROMEO-01-01")).toBeNull();
    fireEvent.click(screen.getByTitle("Expand virtual systems"));
    fireEvent.click(screen.getByText("VS-ROMEO-01-01"));
    await waitFor(() => expect(screen.getByText("eth0.100")).toBeInTheDocument());
    expect(screen.queryByText("Mgmt")).toBeNull();
    expect(screen.queryByRole("tab", { name: /VS-ROMEO-01-01/i })).toBeNull();
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
                { device_id: "m-a", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-ECHO-01-M1", model: "PA-440", software_version: "11.0", ha_role: "active", cluster_member_ref: "CLS-ECHO-01" },
                { device_id: "m-b", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-ECHO-01-M2", model: "PA-440", software_version: "11.0", ha_role: "passive", cluster_member_ref: "CLS-ECHO-01" },
              ],
            }),
          );
        }
        if (url.includes("/clusters/CLS-ECHO-01/inventory")) {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_member_ref: "CLS-ECHO-01",
              members: [{ device_id: "m-a", hostname: "FW-ECHO-01-M1" }, { device_id: "m-b", hostname: "FW-ECHO-01-M2" }],
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

    await waitFor(() => expect(screen.getByText("CLS-ECHO-01")).toBeInTheDocument());
    fireEvent.click(screen.getByText("CLS-ECHO-01"));

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
                  hostname: "FW-JULIET-01-M1",
                  model: "PA-3220",
                  software_version: "10.2.4",
                  ha_role: "active",
                  cluster_member_ref: "CLS-JULIET-01",
                  virtual_systems: "default (vsys1), VS-JULIET-01-02 (vsys2)",
                },
                {
                  device_id: "pa-2",
                  vendor_hint: "palo_alto",
                  enrollment_state: "ENROLLED",
                  hostname: "FW-JULIET-01-M2",
                  model: "PA-3220",
                  software_version: "10.2.4",
                  ha_role: "passive",
                  cluster_member_ref: "CLS-JULIET-01",
                  virtual_systems: "default (vsys1), VS-JULIET-01-02 (vsys2)",
                },
              ],
            }),
          );
        }
        if (url === "/clusters/CLS-JULIET-01/inventory") {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_ref: "CLS-JULIET-01",
              collected_at: "2026-09-19T12:00:00Z",
              members: [
                { device_id: "pa-1", hostname: "FW-JULIET-01-M1", ha_role: "active", model: "PA-3220", software_version: "10.2.4", enrollment_state: "ENROLLED", virtual_systems: "default (vsys1), VS-JULIET-01-02 (vsys2)" },
                { device_id: "pa-2", hostname: "FW-JULIET-01-M2", ha_role: "passive", model: "PA-3220", software_version: "10.2.4", enrollment_state: "ENROLLED", virtual_systems: "default (vsys1), VS-JULIET-01-02 (vsys2)" },
              ],
              contexts: [],
              virtual_systems: ["default (vsys1)", "VS-JULIET-01-02 (vsys2)"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("CLS-JULIET-01")).toBeInTheDocument());
    expect(screen.getByTestId("tree-child-count")).toHaveTextContent("2 VSYS");
    fireEvent.click(screen.getByTitle("Expand virtual systems"));
    expect(screen.getByText("default (vsys1)")).toBeInTheDocument();
    expect(screen.getByText("VS-JULIET-01-02 (vsys2)")).toBeInTheDocument();
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
                { device_id: "pa-1", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-JULIET-01-M1", model: "PA-3220", software_version: "10.2.4", ha_role: "active", cluster_member_ref: "CLS-JULIET-01" },
                { device_id: "pa-2", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-JULIET-01-M2", model: "PA-3220", software_version: "10.2.4", ha_role: "passive", cluster_member_ref: "CLS-JULIET-01" },
              ],
            }),
          );
        }
        if (url === "/clusters/CLS-JULIET-01/inventory") {
          return Promise.resolve(
            jsonResponse(200, {
              cluster_ref: "CLS-JULIET-01",
              members: [
                { device_id: "pa-1", hostname: "FW-JULIET-01-M1" },
                { device_id: "pa-2", hostname: "FW-JULIET-01-M2" },
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
                        "pa-1": [{ address: "192.0.2.5/28", family: "ipv4" }],
                        "pa-2": [{ address: "192.0.2.5/28", family: "ipv4" }],
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

    await waitFor(() => expect(screen.getByText("CLS-JULIET-01")).toBeInTheDocument());
    fireEvent.click(screen.getByText("CLS-JULIET-01"));

    await waitFor(() => expect(screen.getByText("ethernet1/5")).toBeInTheDocument());
    // One shared Address column, not one column per member -- the header above
    // still names each member (PO: that part stays as is), so the table itself
    // is what this asserts, not the whole screen.
    expect(screen.getByText("Address")).toBeInTheDocument();
    expect(screen.getAllByText("192.0.2.5/28")).toHaveLength(1);
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
                  hostname: "FW-TANGO-04",
                  model: "PA-440",
                  software_version: "11.0.1",
                  ha_role: null,
                  cluster_member_ref: null,
                  virtual_systems: "default (vsys1), VS-TANGO-04-02 (vsys2)",
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
                { context: "vsys2", vs_name: "VS-TANGO-04-02 (vsys2)", interfaces: [], routes: [] },
              ],
              virtual_systems: ["default (vsys1)", "VS-TANGO-04-02 (vsys2)"],
            }),
          );
        }
        return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
      }),
    );
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("FW-TANGO-04")).toBeInTheDocument());
    expect(screen.getByText("2 VSYS")).toBeInTheDocument();
    expect(screen.queryByText("default (vsys1)")).toBeNull();
    fireEvent.click(screen.getByTitle("Expand virtual systems"));
    expect(screen.getByText("default (vsys1)")).toBeInTheDocument();
    expect(screen.getByText("VS-TANGO-04-02 (vsys2)")).toBeInTheDocument();

    // Clicking VS-TANGO-04-02 selects the standalone device and filters to that one VS's own context --
    // no second, redundant place names it now that the old per-VS tab strip is gone.
    fireEvent.click(screen.getByText("VS-TANGO-04-02 (vsys2)"));
    await waitFor(() => expect(screen.getByText("No interface evidence")).toBeInTheDocument());
    // The old per-VS tab strip stays gone; the pane header now names the device and the virtual system (P0-3B).
    expect(screen.getByRole("heading", { name: "FW-TANGO-04 › VS-TANGO-04-02 (vsys2)" })).toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: "Inventory context" })).toBeNull();
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
      { device_id: "1", hostname: "FW-TANGO-01", vendor_hint: "check_point" },
      { device_id: "2", hostname: "FW-TANGO-02", vendor_hint: "check_point" },
    ] as any;
    expect(deriveClusterTitle("CLS-ROMEO-01", members)).toBe("CLS-ROMEO-01");
    expect(deriveClusterTitle("CLS-JULIET-01", members)).toBe("CLS-JULIET-01");
  });

  it("derives clean cluster name from Palo Alto reciprocal serial pair references", () => {
    const members = [
      { device_id: "1", hostname: "FW-TANGO-01.1", vendor_hint: "palo_alto" },
      { device_id: "2", hostname: "FW-TANGO-01.2", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("SN-00000001|SN-00000002", members)).toBe("FW-TANGO-01-CLS");
  });

  it("handles numeric and hyphenated member suffixes like -01 / -02", () => {
    const members = [
      { device_id: "1", hostname: "FW-BRAVO-01", vendor_hint: "palo_alto" },
      { device_id: "2", hostname: "FW-BRAVO-02", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("SN-00000003|SN-00000004", members)).toBe("FW-BRAVO-CLS");
  });

  it("does not duplicate -CLS if the common base already ends in -CLS", () => {
    const members = [
      { device_id: "1", hostname: "FW-ROMEO-01-CLS-1", vendor_hint: "palo_alto" },
      { device_id: "2", hostname: "FW-ROMEO-01-CLS-2", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("serial1|serial2", members)).toBe("FW-ROMEO-01-CLS");
  });

  it("falls back to clusterRef if no member hostnames are present", () => {
    const members = [
      { device_id: "1", vendor_hint: "palo_alto" },
      { device_id: "2", vendor_hint: "palo_alto" },
    ] as any;
    expect(deriveClusterTitle("SN-00000005|SN-00000006", members)).toBe("SN-00000005|SN-00000006");
  });
});

describe("One search box (PO, 2026-09-24)", () => {
  afterEach(() => {
    act(() => setListSearchTerm(""));
    vi.unstubAllGlobals();
  });

  it("narrows the device list from the top bar's search, by address as well as name, and clears with the ✕", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, {
      devices: [
        { device_id: "a", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-ALPHA-01", model: null, software_version: null, ha_role: null, cluster_member_ref: null, management_ip: "192.0.2.10" },
        { device_id: "b", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-BRAVO-02", model: null, software_version: null, ha_role: null, cluster_member_ref: null, management_ip: "198.51.100.7" },
      ],
    }))));
    render(withTheme(<><GlobalSearch /><InventoryScreen /></>));
    await waitFor(() => expect(screen.getByText("FW-BRAVO-02")).toBeInTheDocument());
    const input = screen.getByRole("textbox", { name: "Search devices, settings and evidence" });
    expect(input).toHaveAttribute("placeholder", "Filter this list, or search everything…");
    fireEvent.change(input, { target: { value: "192.0.2" } });
    expect(screen.queryByText("FW-BRAVO-02")).toBeNull();
    expect(screen.getAllByText("FW-ALPHA-01").length).toBeGreaterThan(0);
    expect(screen.getByText("filtering list")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Clear search" }));
    expect(screen.getByText("FW-BRAVO-02")).toBeInTheDocument();
  });
});
