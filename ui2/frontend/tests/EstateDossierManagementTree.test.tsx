import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { ManagementTree, ManagementTreeNode } from "../src/auth/adminApi";
import { EstateDossierManagementTreeBlock } from "../src/estate/EstateDossierManagementTreeBlock";
import { buildEstateModel, type EstateInputs } from "../src/estate/estateModel";
import { EstateDossierScreen } from "../src/estate/EstateDossierScreen";
import type { EstateDataState } from "../src/estate/useEstateData";
import { vi } from "vitest";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
const NOW = new Date("2026-11-22T09:00:00.000Z");

const mockState = vi.hoisted(() => ({ model: null as unknown }));

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => mockState.model,
}));

const device = (hostname: string): ManagementTreeNode => ({
  kind: "STANDALONE_PRODUCT_GATEWAY",
  device_id: null,
  imported: false,
  ha_role: null,
  enrollment_state: null,
  last_collection_state: null,
  last_collection_at: null,
  category: "device",
  model: null,
  candidate_id: null,
  ack_token: null,
  acknowledged: false,
  acknowledged_reason: null,
  children: [],
  hostname,
});

const fifteenNodes = (): ManagementTree => ({
  device_id: "dev-mgmt-tango",
  vendor: "check_point",
  run_id: "run-1",
  discovered_at: "2026-11-21T10:00:00Z",
  domains: [
    {
      domain: "DOMAIN-ALPHA",
      nodes: Array.from({ length: 15 }, (_, i) => device(`FW-SYNTH-${String(i).padStart(2, "0")}`)),
    },
  ],
  counts: {},
});

describe("EstateDossierManagementTreeBlock", () => {
  it("lists 12 rows and a +3 more footer for a domain with 15 nodes", () => {
    render(<EstateDossierManagementTreeBlock deviceId="dev-mgmt-tango" tree={{ state: "ok", value: fifteenNodes() }} now={NOW} />);
    expect(screen.getByTestId("dossier-management-tree-block")).toBeTruthy();
    expect(screen.getAllByTestId("dossier-management-tree-row")).toHaveLength(12);
    const footer = screen.getByTestId(/^dossier-management-tree-more-/);
    expect(footer.textContent).toBe("+3 more nodes");
  });

  it("labels the block as management-plane intent and carries no acknowledge or import control", () => {
    render(<EstateDossierManagementTreeBlock deviceId="dev-mgmt-tango" tree={{ state: "ok", value: fifteenNodes() }} now={NOW} />);
    const block = screen.getByTestId("dossier-management-tree-block");
    expect(within(block).getAllByText("Management-plane intent, not device runtime truth.").length).toBeGreaterThan(0);
    expect(within(block).queryByRole("button", { name: /acknowledge|import/i })).toBeNull();
    expect(within(block).queryAllByRole("button")).toHaveLength(0);
  });

  it("the standard-view link carries standard=1 and points at the unit", () => {
    render(<EstateDossierManagementTreeBlock deviceId="dev-mgmt-tango" tree={{ state: "ok", value: fifteenNodes() }} now={NOW} />);
    const link = screen.getByRole("link", { name: /Open management tree \(standard view\)/ });
    const href = link.getAttribute("href") ?? "";
    expect(href).toContain("standard=1");
    expect(href).toContain("device_id=dev-mgmt-tango");
  });

  it("an unread tree shows UNKNOWN text and no node rows", () => {
    render(<EstateDossierManagementTreeBlock deviceId="dev-mgmt-tango" tree="loading" now={NOW} />);
    const block = screen.getByTestId("dossier-management-tree-block");
    expect(block.getAttribute("data-tree-state")).toBe("UNKNOWN");
    expect(within(block).getByText("Management tree not read yet")).toBeTruthy();
    expect(screen.queryAllByTestId("dossier-management-tree-row")).toHaveLength(0);
  });
});

function dossierState(deviceId: string, role: string, vendorHint: string): EstateDataState {
  const dev: Record<string, unknown> = {
    device_id: deviceId,
    role,
    vendor_hint: vendorHint,
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04",
    model: "SYNTH-MGR-1",
    software_version: "1.0.0",
    ha_role: null,
    cluster_member_ref: null,
    management_ip: "192.0.2.20",
    backup_target: false,
    platform_facts_observed_at: "2026-11-22T08:00:00.000Z",
  };
  const inputs: EstateInputs = {
    now: NOW,
    devices: { state: "ok", value: [dev] } as never,
    overview: { state: "unavailable" } as never,
    failover: { state: "ok", value: [] },
    backups: { state: "ok", value: { backups: [] } },
    lifecycle: { state: "ok", value: { devices: [] } } as never,
    controls: { state: "ok", value: [] },
    changes: { state: "ok", value: { items: [] } } as never,
    health: { state: "unavailable" } as never,
  };
  return { loading: false, fatalError: null, overview: null, model: buildEstateModel(inputs) } as unknown as EstateDataState;
}

describe("Estate Dossier management tree scope", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 500, headers: { "Content-Type": "application/json" } })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("omits the block for a gateway, and for BlueCoat or Fortinet managers", async () => {
    const cases: [string, string][] = [
      ["gateway", "palo_alto"],
      ["management_server", "bluecoat"],
      ["management_server", "fortinet"],
    ];
    for (const [role, vendor] of cases) {
      mockState.model = dossierState("dev-tango-05", role, vendor);
      const { unmount } = render(<EstateDossierScreen deviceId="dev-tango-05" />);
      await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());
      expect(screen.queryByTestId("dossier-management-tree-block")).toBeNull();
      unmount();
    }
  });

  it("renders the block for a Check Point management server", async () => {
    mockState.model = dossierState("dev-tango-06", "management_server", "check_point");
    render(<EstateDossierScreen deviceId="dev-tango-06" />);
    await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());
    expect(screen.getByTestId("dossier-management-tree-block")).toBeTruthy();
  });
});
