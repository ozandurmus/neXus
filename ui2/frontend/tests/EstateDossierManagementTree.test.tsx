import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ManagementTree, ManagementTreeNode } from "../src/auth/adminApi";
import { EstateDossierManagementTreeBlock } from "../src/estate/EstateDossierManagementTreeBlock";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
const NOW = new Date("2026-11-22T09:00:00.000Z");

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
