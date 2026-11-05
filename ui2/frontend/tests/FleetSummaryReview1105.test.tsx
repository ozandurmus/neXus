import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { RankedBars, majorOrder, majorVersionVerdict, rankRows, reportedVersionVerdict } from "../src/screens/overview/RankedBars";
import { EstateDetails } from "../src/screens/OverviewScreen";
import { InventoryScreen, isProblemDevice, problemDeviceIds } from "../src/screens/InventoryScreen";
import type { DeviceSummary, OverviewView } from "../src/auth/adminApi";

// UI review 2026-11-05, C5 (PO approved). Masked pseudonyms and 192.0.2.x only.
const NOW = new Date().toISOString();
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const mount = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

describe("ranked bars", () => {
  it("ranks named slices largest first, folds the rest into Other and puts UNKNOWN last", () => {
    const rows = rankRows([
      { label: "A", count: 1 }, { label: null, count: 4 }, { label: "B", count: 9 }, { label: "C", count: 3 }, { label: "D", count: 3 },
    ], { max: 2 });
    expect(rows.map((r) => [r.display, r.count, r.kind])).toEqual([["B", 9, "named"], ["C", 3, "named"], ["Other (2)", 4, "other"], ["UNKNOWN", 4, "unknown"]]);
  });

  it("shows the full label, the count and the filtered list behind each row", () => {
    mount(<RankedBars title="Exact version" rows={[{ display: "R81.20 · Take 119 (a long label that is never cut)", count: 7, href: "?screen=inventory&x=1", kind: "named" }]} />);
    const link = screen.getByRole("link", { name: "R81.20 · Take 119 (a long label that is never cut): 7 devices" });
    expect(link).toHaveAttribute("href", "?screen=inventory&x=1");
    expect(screen.getByText("EXACT VERSION")).toBeInTheDocument();
  });
});

describe("the version verdict line", () => {
  it("names the spread and the oldest major", () => {
    expect(majorVersionVerdict("check_point", [{ label: "R81.20", count: 50 }, { label: "R80.40", count: 7 }, { label: "R81.10", count: 3 }]))
      .toBe("60 devices on more than one major version (3); oldest R80.40");
    expect(majorVersionVerdict("palo_alto", [{ label: "11.1", count: 2 }, { label: "10.2", count: 1 }, { label: null, count: 1 }]))
      .toBe("3 devices on more than one major version (2); oldest 10.2; 1 device with major version UNKNOWN");
  });

  it("says one major plainly, and never guesses the oldest of a shape it does not know", () => {
    expect(majorVersionVerdict("check_point", [{ label: "R81.20", count: 4 }])).toBe("All 4 devices on one major version: R81.20");
    expect(majorVersionVerdict("check_point", [{ label: "R81.20", count: 4 }, { label: "Gaia Embedded", count: 1 }])).toMatch(/; oldest UNKNOWN$/);
    expect(majorVersionVerdict("palo_alto", [{ label: null, count: 2 }])).toBe("Major version UNKNOWN for 2 devices");
    expect(majorOrder("palo_alto", "10.2.7")).toBeNull();
  });

  it("derives no major for the vendors whose version is only reported", () => {
    expect(reportedVersionVerdict([{ label: "v7.4.12 build2902", count: 2 }, { label: "v7.2.8", count: 1 }, { label: null, count: 1 }]))
      .toBe("3 devices on 2 reported versions; major version not derived from the reported text; 1 device reports no version");
    expect(reportedVersionVerdict([{ label: "9.18(3)", count: 1 }])).toBe("1 device reports one version: 9.18(3)");
  });

  it("puts a verdict on every vendor card and draws no donut for versions", async () => {
    const overview = {
      platform: { devices: 3, check_point: 2, palo_alto: 1, clusters: 1, evidence_at: NOW, hotfix_levels: [],
        versions: {
          check_point: { major: [{ label: "R81.20", count: 1 }, { label: "R80.40", count: 1 }], minor: [{ label: "R81.20 Jumbo Take 119", count: 1 }], model: [{ label: "Check Point 6600", count: 2 }] },
          palo_alto: { major: [{ label: "11.1", count: 1 }], minor: [{ label: "11.1.10", count: 1 }], model: [{ label: "PA-440", count: 1 }] },
        } },
      inventory_age: { lt24h: 3, h24_72: 0, gt72h: 0, never: 0, of: 3 },
      nexus: { completed_24h: 1, running: 0, oldest_running_submitted_at: null, last_inventory: {} },
    } as unknown as OverviewView;
    const fleet = [{ device_id: "f1", vendor_hint: "fortinet", enrollment_state: "ENROLLED", hostname: "FW-ECHO-05", software_version: "v7.2.8", role: "gateway" }] as unknown as DeviceSummary[];
    mount(<EstateDetails data={overview} onDevicesScreen fleet={fleet} />);
    const verdicts = (await screen.findAllByTestId("version-verdict")).map((v) => v.textContent);
    expect(verdicts).toEqual([
      "2 devices on more than one major version (2); oldest R80.40",
      "1 device on one major version: 11.1",
      "1 device reports one version: v7.2.8",
    ]);
    expect(screen.getByRole("link", { name: "R80.40: 1 device" })).toHaveAttribute("href", "?screen=inventory&vendor=check_point&sw_major=R80.40");
    expect(screen.getByRole("list", { name: "Jumbo hotfix" })).toBeInTheDocument();
  });
});

describe("the device tree: collapsed children and Problems only", () => {
  const dev = (over: Partial<DeviceSummary>): DeviceSummary => ({ device_id: "x", hostname: "FW-X", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    model: "Quantum", software_version: "R81.20", ha_role: null, cluster_member_ref: null, ip_addresses: "192.0.2.10", ...over } as DeviceSummary);
  const FLEET = [
    dev({ device_id: "ok", hostname: "FW-TANGO-04" }),
    dev({ device_id: "failed", hostname: "FW-JULIET-06", latest_job_state: "FAILED" }),
    dev({ device_id: "down", hostname: "FW-BRAVO-02", enrollment_state: "UNREACHABLE" }),
    dev({ device_id: "m1", hostname: "FW-ROMEO-01-M1", cluster_member_ref: "CLS-ROMEO-01", virtual_systems: "VS-ROMEO-01-07, VS-ROMEO-01-08", latest_job_state: "FAILED" }),
    dev({ device_id: "m2", hostname: "FW-ROMEO-01-M2", cluster_member_ref: "CLS-ROMEO-01", virtual_systems: "VS-ROMEO-01-07, VS-ROMEO-01-08" }),
  ];

  it("keeps failed and unreachable devices, and a whole cluster when one member is a problem", () => {
    expect(FLEET.filter(isProblemDevice).map((d) => d.device_id)).toEqual(["failed", "down", "m1"]);
    expect([...problemDeviceIds(FLEET)].sort()).toEqual(["down", "failed", "m1", "m2"]);
  });

  it("filters the list with one click and back", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices" ? json({ devices: FLEET }) : json({ error: "NOT_FOUND" }, 404)));
    mount(<InventoryScreen />);
    expect(await screen.findByText("FW-TANGO-04")).toBeInTheDocument();
    // the cluster's virtual systems start collapsed, the count on the row
    expect(screen.queryByText("VS-ROMEO-01-07")).toBeNull();
    expect(screen.getByTestId("tree-child-count")).toHaveTextContent("2 VS");
    const chip = screen.getByRole("button", { name: "Problems only · 3" });
    expect(chip).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(chip);
    await waitFor(() => expect(screen.queryByText("FW-TANGO-04")).toBeNull());
    const column = screen.getByTestId("device-list-column");
    expect(within(column).getByText("FW-JULIET-06")).toBeInTheDocument();
    expect(within(column).getByText("FW-BRAVO-02")).toBeInTheDocument();
    expect(within(column).getByText("CLS-ROMEO-01")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Problems only · 3" }));
    expect(await screen.findByText("FW-TANGO-04")).toBeInTheDocument();
  });
});
