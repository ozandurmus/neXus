import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { JobWindowContext, JobButton } from "../src/shell/JobWindow";
import { M3Button } from "../src/shell/M3Widgets";
import { AddDeviceDialogTrigger, ImportFromManagerTrigger } from "../src/shell/AddDeviceDialog";
import { planeGranted, ROLE_REFUSAL } from "../src/shell/actionAccess";
import { DISABLED_STYLE } from "../src/shell/disabledStyle";
import { BackupScreen } from "../src/screens/BackupScreen";
import { BackupPanel, InterfacesPanel } from "../src/screens/InventoryPanels";
import { OperationsScreen } from "../src/screens/OperationsScreen";
import { DeviceManagementPane } from "../src/screens/DeviceRegistryPanel";
import { DeviceLifecycleLine } from "../src/screens/LifecycleScreen";
import { SanitizerScreen } from "../src/screens/SanitizerScreen";
import { PacketAnalysisScreen } from "../src/screens/PacketAnalysisScreen";

/**
 * UI layout and role-affordance review (2026-10-17, 1440 px): one disabled style, read-only roles see locked controls
 * with a role reason (never the window wording), tables that carry a horizontal-scroll fallback instead of clipping,
 * and the small copy fixes.
 */
const viewerPlanes = ["Devices", "Config", "Compliance", "Policy", "PacketAnalysis"];
const adminPlanes = [...viewerPlanes, "Operations", "Admin"];

function session(permissions: readonly string[]): SessionInfo {
  return { displayName: "Synthetic user", roleTokens: [], permissions, onSignOut: () => {} };
}
function as(permissions: readonly string[] | null, node: React.ReactElement) {
  const themed = <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
  return permissions === null ? themed : <SessionContext.Provider value={session(permissions)}>{themed}</SessionContext.Provider>;
}
const closedWindow = { open: false, label: "Next window: 00:00" };
const jsonResponse = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status }));

afterEach(() => vi.unstubAllGlobals());

describe("one disabled style for every action", () => {
  it("overrides the fill and the text of every emphasis, including the filled one that rendered dark on dark blue", () => {
    const { unmount } = render(as(null, <>
      {(["filled", "tonal", "outlined", "text"] as const).map((emphasis) =>
        <M3Button key={emphasis} emphasis={emphasis} disabled>{`${emphasis} action`}</M3Button>)}
      <M3Button emphasis="filled">enabled filled</M3Button>
    </>));
    for (const emphasis of ["filled", "tonal", "outlined", "text"]) {
      const style = getComputedStyle(screen.getByRole("button", { name: `${emphasis} action` }));
      expect(style.backgroundColor, emphasis).toBe("var(--nx-sc)");
      expect(style.color, emphasis).toBe("var(--nx-on-surface-var)");
    }
    expect(getComputedStyle(screen.getByRole("button", { name: "enabled filled" })).backgroundColor).toBe("var(--nx-primary)");
    unmount();
  });

  it("applies the same style to the window-gated JobButton and to a window-closed device-job M3Button", () => {
    render(as(null,
      <JobWindowContext.Provider value={closedWindow}>
        <M3Button deviceJob emphasis="filled">Collect now</M3Button>
        <JobButton variant="contained" sx={{ bgcolor: "primary.main" }}>Request</JobButton>
      </JobWindowContext.Provider>));
    for (const name of ["Collect now · Next window: 00:00", "Request · Next window: 00:00"]) {
      const button = screen.getByRole("button", { name });
      expect(button).toBeDisabled();
      expect(getComputedStyle(button).backgroundColor).toBe("var(--nx-sc)");
      expect(getComputedStyle(button).color).toBe("var(--nx-on-surface-var)");
    }
    expect(DISABLED_STYLE["&.Mui-disabled"].color).toBe("var(--nx-on-surface-var)");
  });
});

describe("read-only roles see locked controls with a role reason", () => {
  it("treats a session without a plane list as unrestricted and checks the plane otherwise", () => {
    expect(planeGranted(undefined, "Admin")).toBe(true);
    expect(planeGranted([], "Admin")).toBe(true);
    expect(planeGranted(viewerPlanes, "Admin")).toBe(false);
    expect(planeGranted(adminPlanes, "Admin")).toBe(true);
  });

  it("locks Add device and Import from manager, and says the role reason instead of the window", () => {
    render(as(viewerPlanes, <JobWindowContext.Provider value={closedWindow}>
      <AddDeviceDialogTrigger /><ImportFromManagerTrigger />
    </JobWindowContext.Provider>));
    for (const name of ["Add device", "Import from manager"]) {
      const button = screen.getByRole("button", { name: new RegExp(`^${name} · Role: not permitted$`) });
      expect(button).toBeDisabled();
    }
    expect(screen.queryByText(/Next window/)).toBeNull();
  });

  it("keeps them enabled for a session that holds the Admin plane", () => {
    render(as(adminPlanes, <><AddDeviceDialogTrigger /><ImportFromManagerTrigger /></>));
    expect(screen.getByRole("button", { name: "Add device" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Import from manager" })).toBeEnabled();
  });

  it("keeps the window wording for a role that can act", () => {
    render(as(adminPlanes, <JobWindowContext.Provider value={closedWindow}>
      <M3Button deviceJob plane="Operations" emphasis="filled">Run Fleet Backup</M3Button>
    </JobWindowContext.Provider>));
    expect(screen.getByRole("button", { name: "Run Fleet Backup · Next window: 00:00" })).toBeDisabled();
  });

  it("locks the Operations header action for everyone: Schedule collection has no handler or endpoint yet", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(200, { devices: [], items: [] })));
    render(as(adminPlanes, <OperationsScreen />));
    const button = await screen.findByRole("button", { name: "Schedule collection · Schedule authoring is not available yet" });
    expect(button).toBeDisabled();
  });

  it("locks the device Backup tab's reason field and Request backup button, and states the role reason", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices/dev-1/backups" ? jsonResponse(200, { backups: [] }) : jsonResponse(404, {})));
    render(as(viewerPlanes, <JobWindowContext.Provider value={closedWindow}><BackupPanel deviceId="dev-1" /></JobWindowContext.Provider>));
    const field = await screen.findByRole("textbox", { name: /Backup reason/i });
    expect(field).toBeDisabled();
    expect(screen.getByRole("button", { name: /^Request backup · Role: not permitted$/ })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent(ROLE_REFUSAL);
  });

  it("leaves the Backup tab reason field editable for a role that can act", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices/dev-1/backups" ? jsonResponse(200, { backups: [] }) : jsonResponse(404, {})));
    render(as(adminPlanes, <BackupPanel deviceId="dev-1" />));
    expect(await screen.findByRole("textbox", { name: /Backup reason/i })).toBeEnabled();
    expect(screen.queryByText(ROLE_REFUSAL)).toBeNull();
  });

  describe("Backups screen", () => {
    const devices = [{ device_id: "d1", role: "gateway", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04-M1", model: "29200", software_version: "R81.20", cluster_member_ref: "CLS-TANGO-04", backup_target: true }];
    const stub = () => vi.stubGlobal("fetch", vi.fn((url: string) => {
      const body = url.startsWith("/devices") ? { devices }
        : url.startsWith("/api/v2/backups/policies") ? { schedule_enabled: false, daily_backup_cron: "0 2 * * *", backup_retention_days: 14, snapshot_retention_depth: 1 }
        : url.startsWith("/api/v2/backups/deviations") ? { active_major_deviations: [], total_deviations_checked: 0 }
        : url.startsWith("/backups") ? { backups: [], baselines: {} } : {};
      return jsonResponse(200, body);
    }));

    it("locks Run Fleet Backup, the TARGET toggles and Backup Now for a read-only role, with one role note", async () => {
      stub();
      render(as(viewerPlanes, <JobWindowContext.Provider value={closedWindow}><BackupScreen /></JobWindowContext.Provider>));
      const row = (await screen.findByText("FW-TANGO-04-M1")).closest("tr")!;
      expect(screen.getByRole("button", { name: "Run Fleet Backup · Role: not permitted" })).toBeDisabled();
      expect(screen.getByRole("status")).toHaveTextContent(ROLE_REFUSAL);
      // A role that cannot change the target sees a read-only pill, not a switch that looks live (F-4); no checkbox at all.
      expect(within(row).queryByRole("checkbox")).toBeNull();
      expect(within(row).getByRole("img", { name: "Backup target FW-TANGO-04-M1: on, locked for your role" })).toBeInTheDocument();
      expect(within(row).getByText("Target · locked")).toBeInTheDocument();
      // Backup Now stays in the row menu, disabled, with the role suffix and never the window wording.
      fireEvent.click(within(row).getByRole("button", { name: "Actions for FW-TANGO-04-M1" }));
      expect(await screen.findByRole("menuitem", { name: "Backup Now · Role: not permitted" })).toHaveAttribute("aria-disabled", "true");
      expect(screen.queryByText(/Next window/)).toBeNull();
    });

    it("leaves the TARGET toggle live for a role that holds the Operations plane", async () => {
      stub();
      render(as(adminPlanes, <BackupScreen />));
      const row = (await screen.findByText("FW-TANGO-04-M1")).closest("tr")!;
      expect(within(row).getByRole("checkbox", { name: "Backup target FW-TANGO-04-M1" })).toBeEnabled();
      expect(screen.queryByRole("status")).toBeNull();
    });
  });
});

describe("tables do not clip at 1440 px", () => {
  const contexts = [{
    context: "physical", vs_name: null, ha: null, routes: [],
    interfaces: [{ name: "ethernet1/13.2603", parent: null, kind: "subinterface", state: "up", vlan_id: 2603, mac_address: "94:56:41:**:**:**",
      zone: "ZONE-BEACON-5793", addresses: [{ address: "248.15.28.1/29", family: "ipv4" as const, role: "member" as const }] }],
  }];

  it("keeps the interfaces table in a horizontally scrollable container and each value on one line", () => {
    render(as(null, <InterfacesPanel contexts={contexts as never} member={{ device_id: "d1", hostname: "FW-TANGO-04" }} isPaloAlto />));
    const table = screen.getByRole("table");
    expect(getComputedStyle(table.parentElement!).overflowX).toBe("auto");
    for (const text of ["ZONE-BEACON-5793", "248.15.28.0/29", "ethernet1/13.2603"]) {
      expect(getComputedStyle(screen.getByText(text).closest("td")!).whiteSpace, text).toBe("nowrap");
    }
    // No fixed pixel width on the table or any of its cells.
    for (const node of [table, ...Array.from(table.querySelectorAll("th, td"))]) {
      expect(getComputedStyle(node).width, node.textContent ?? "").not.toMatch(/px$/);
    }
  });

  const devices = ["FW-FOXTROT-01", "FW-FOXTROT-02"].map((hostname, i) => ({
    device_id: `dev-${i}`, vendor_hint: "cisco_asa", enrollment_state: "ENROLLED", hostname, model: null, software_version: null,
    ha_role: null, cluster_member_ref: null, inventory_collected_at: "2026-10-17T15:01:33Z",
  }));

  it("lists the device registry as a plain table with headings that cannot collide, and the standard spelling", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url.startsWith("/devices") ? jsonResponse(200, { devices }) : jsonResponse(200, {})));
    render(as(null, <DeviceManagementPane />));
    const first = await screen.findByText("FW-FOXTROT-01");
    expect(screen.getByText("ENROLLMENT STATE")).toBeInTheDocument();
    expect(screen.queryByText(/ENROLMENT/i)).toBeNull();

    const table = screen.getByRole("table", { name: "Device registry" });
    expect(within(table).getAllByRole("columnheader").map((cell) => cell.textContent?.trim()).filter(Boolean))
      .toEqual(["VENDOR", "DEVICE / HOSTNAME", "ENROLLMENT STATE", "LAST COLLECTION"]);
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    expect(first).toHaveAttribute("title", "FW-FOXTROT-01");
  });

});

describe("Packet Analysis and Sanitizer", () => {
  // PO decision 2026-10-18: the server grants aiview the run flag; the view stays masked and has no raw view.
  const aiview = { run: true, raw_view: false, masked: true };
  const readOnly = { run: false, raw_view: false, masked: true };
  const limits = { retention_days: 7, max_file_bytes: 1024, max_store_bytes: 4096, used_bytes: 0, max_telemetry_bytes: 1024 };

  it("offers aiview the New case form, with no role note and no in-page Masked view pill", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url.startsWith("/api/v2/packet-analysis/cases")
      ? jsonResponse(200, { cases: [], permissions: aiview, limits }) : jsonResponse(200, {})));
    render(as(viewerPlanes, <PacketAnalysisScreen />));
    expect(await screen.findByText("New case")).toBeInTheDocument();
    expect(screen.getByLabelText("Case title")).toBeEnabled();
    expect(screen.getByRole("button", { name: "Create case" })).toBeDisabled(); // until a title is typed
    fireEvent.change(screen.getByLabelText("Case title"), { target: { value: "Synthetic case" } });
    expect(screen.getByRole("button", { name: "Create case" })).toBeEnabled();
    expect(screen.queryByText(ROLE_REFUSAL)).toBeNull();
    expect(screen.queryByText("Masked view")).toBeNull();
  });

  it("still shows a disabled New case with the role note when the server withholds the run flag", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url.startsWith("/api/v2/packet-analysis/cases")
      ? jsonResponse(200, { cases: [], permissions: readOnly, limits }) : jsonResponse(200, {})));
    render(as(viewerPlanes, <PacketAnalysisScreen />));
    const button = await screen.findByRole("button", { name: "New case" });
    expect(button).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent(ROLE_REFUSAL);
    expect(screen.queryByText("Masked view")).toBeNull();
  });

  it("does not show the locked New case to a role that can create cases", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url.startsWith("/api/v2/packet-analysis/cases")
      ? jsonResponse(200, { cases: [], permissions: { run: true, raw_view: false, masked: false }, limits }) : jsonResponse(200, {})));
    render(as(adminPlanes, <PacketAnalysisScreen />));
    expect(await screen.findByRole("button", { name: "Create case" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New case" })).toBeNull();
  });

  it("writes the Sanitizer subtitle in English", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(200, {})));
    render(as(null, <SanitizerScreen />));
    expect(await screen.findByText(/^Data Anonymization · pcap\/pcapng/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Veri Anonim/);
  });
});

describe("device lifecycle line", () => {
  it("writes the missing-data case plainly and keeps UNKNOWN visible", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(200, { device_id: "dev-1", risk: "UNKNOWN", status: "NO_LIFECYCLE_DATA" })));
    render(as(null, <DeviceLifecycleLine deviceId="dev-1" />));
    await waitFor(() => expect(screen.getByText(/no lifecycle data imported/)).toBeInTheDocument());
    expect(screen.getByText(/^Lifecycle: UNKNOWN \(no lifecycle data imported\)$/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NO_LIFECYCLE_DATA/);
  });
});
