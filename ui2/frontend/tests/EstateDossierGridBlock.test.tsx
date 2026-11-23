import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { DeviceInventory, GridMemberView, GridSummaryView } from "../src/auth/adminApi";
import { EstateDossierGridBlock } from "../src/estate/EstateDossierGridBlock";
import { GRID_MEMBER_ROW_CAP } from "../src/estate/estateDossierGridModel";

// Synthetic FW-TANGO-04 style members only; no addresses or serials appear in the fixtures.
const DEVICE_ID = "dev-tango-04";
const COLLECTED_AT = "2026-11-20T08:00:00Z";

function member(name: string): GridMemberView {
  return {
    virtual_system: name,
    address: null,
    platform: null,
    hardware_type: null,
    hypervisor: null,
    grid_master: false,
    master_candidate: false,
    ha_enabled: false,
    ha_status: null,
    node_status: "WORKING",
    replication: null,
    disk_percent: 40,
    memory_percent: 50,
    cpu_percent: 10,
    db_percent: 5,
    services: [{ service: "DNS", status: "WORKING" }],
  };
}

const SUMMARY: GridSummaryView = {
  dns_views: 2,
  dns_views_at_least: false,
  auth_zones: 9,
  auth_zones_at_least: true,
  dhcp_networks: null,
  dhcp_networks_at_least: false,
  dhcp_ranges: 4,
  dhcp_ranges_at_least: false,
  top_networks: null,
  licenses: null,
};

function inventory(overrides: Partial<DeviceInventory>) {
  return {
    state: "ok" as const,
    value: { device_id: DEVICE_ID, collected_at: COLLECTED_AT, job: null, ...overrides } as DeviceInventory,
  };
}

describe("EstateDossierGridBlock", () => {
  it("renders the MoreFooter with the true overflow count when members exceed the row cap", () => {
    const members = Array.from({ length: GRID_MEMBER_ROW_CAP + 3 }, (_, i) =>
      member(`FW-TANGO-${String(i + 1).padStart(2, "0")}`),
    );
    const { container } = render(
      <EstateDossierGridBlock deviceId={DEVICE_ID} inventory={inventory({ grid_members: members })} now={new Date(COLLECTED_AT)} />,
    );
    const footer = screen.getByTestId("grid-members-more");
    expect(footer.textContent).toBe("+3 more grid members");
    expect(container.querySelectorAll("[data-grid-member-row]")).toHaveLength(GRID_MEMBER_ROW_CAP);
  });

  it("renders the evidence stamp with the inventory timestamp and a standard=1 link with no action buttons", () => {
    const { container } = render(
      <EstateDossierGridBlock deviceId={DEVICE_ID} inventory={inventory({ grid_members: [member("FW-TANGO-04")], grid_summary: SUMMARY })} now={new Date(COLLECTED_AT)} />,
    );
    expect(container.querySelector("[data-evidence-stamp]")).not.toBeNull();
    const link = screen.getByRole("link", { name: /Grid members \(standard view\)/ });
    expect(link.getAttribute("href")).toBe(`/devices/${DEVICE_ID}?standard=1`);
    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("shows the summary with unit words and never prints a zero member count when the member list is missing", () => {
    const { container } = render(
      <EstateDossierGridBlock deviceId={DEVICE_ID} inventory={inventory({ grid_members: undefined, grid_summary: { ...SUMMARY, dns_views: 2 } })} />,
    );
    const badge = container.querySelector('[data-state-badge="unk"]');
    expect(badge).not.toBeNull();
    const block = screen.getByTestId("dossier-grid-members-block");
    expect(block.textContent).not.toContain("0 grid members");
    expect(block.textContent).toContain("Grid member list not collected (UNKNOWN)");
    expect(screen.getByTestId("grid-member-unknown").textContent).toContain("Member list UNKNOWN");
    const lines = screen.getByTestId("grid-summary-lines");
    expect(within(lines).getByText("2 views")).toBeTruthy();
    expect(within(lines).getByText("≥9 zones")).toBeTruthy();
  });
});
