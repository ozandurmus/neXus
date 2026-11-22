import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DeviceSummary } from "../src/auth/adminApi";
import { buildEstateModel, type EstateModel } from "../src/estate/estateModel";

const fixture = vi.hoisted(() => ({ model: null as EstateModel | null }));

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => ({
    loading: false,
    fatalError: null,
    overview: null,
    devices: { state: "failed" },
    failover: { state: "failed" },
    backups: { state: "failed" },
    lifecycle: { state: "failed" },
    controls: { state: "failed" },
    changes: { state: "failed" },
    model: fixture.model,
    refresh: () => undefined,
  }),
}));

import { EstateWallScreen } from "../src/estate/EstateWallScreen";

const NOW = new Date("2026-11-22T12:00:00Z");
// Inventory read two hours before NOW. No lens answer in this fixture carries its own evidence time.
const READ_AT = "2026-11-22T10:00:00Z";

// Synthetic devices only (FW-TANGO-04 style names, 192.0.2.x / 198.51.100.x addresses).
const DEVICES: readonly DeviceSummary[] = [
  {
    device_id: "dev-tango-04",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04",
    model: "26000",
    software_version: "R81.20",
    ha_role: "ACTIVE",
    cluster_member_ref: "CLS-ROMEO-01",
    management_ip: "192.0.2.14",
    backup_target: false,
    inventory_collected_at: READ_AT,
  },
  {
    device_id: "dev-bravo-02",
    vendor_hint: "infoblox",
    role: "appliance",
    enrollment_state: "ENROLLED",
    hostname: "DEV-BRAVO-02",
    model: "IB-2225",
    software_version: "9.0.5",
    ha_role: null,
    cluster_member_ref: null,
    management_ip: "198.51.100.42",
    backup_target: false,
    inventory_collected_at: null,
  },
];

function buildFixtureModel(): EstateModel {
  return buildEstateModel({
    now: NOW,
    devices: { state: "ok", value: DEVICES },
    overview: { state: "failed" },
    failover: { state: "failed" },
    backups: { state: "failed" },
    lifecycle: { state: "failed" },
  });
}

function rowCells(name: string): HTMLElement[] {
  const row = document.querySelector<HTMLElement>(`[data-unit-row="${name}"]`);
  if (!row) throw new Error(`row ${name} not rendered`);
  return Array.from(row.querySelectorAll<HTMLElement>("td"));
}

beforeEach(() => {
  window.localStorage.clear();
  fixture.model = buildFixtureModel();
});

describe("Estate wall table: last column is the inventory read, not lens evidence", () => {
  it("labels the last column 'Inventory read' and stamps it with the inventory read age", async () => {
    render(<EstateWallScreen />);

    const table = screen.getByRole("table", { name: "Estate units" });
    const headers = within(table).getAllByRole("columnheader").map((th) => th.textContent);
    expect(headers[headers.length - 1]).toBe("Inventory read");
    expect(headers).not.toContain("Evidence");

    const cells = rowCells("FW-TANGO-04");
    const last = cells[cells.length - 1];
    const stamp = last.querySelector<HTMLElement>("[data-evidence-stamp]");
    expect(stamp).not.toBeNull();
    expect(stamp).toHaveAttribute("data-evidence-stamp", "a1");
    expect(last).toHaveTextContent("2 h");
    expect(last).toHaveTextContent("inventory read");

    // The hover text names the source: the inventory read, not a lens.
    fireEvent.mouseOver(stamp as HTMLElement);
    await waitFor(() => {
      expect(screen.getByRole("tooltip")).toHaveTextContent("source: inventory read");
    });
  });

  it("shows the missing label, never a time borrowed from another lens, when a row has no inventory read", () => {
    render(<EstateWallScreen />);

    const cells = rowCells("DEV-BRAVO-02");
    const last = cells[cells.length - 1];
    const stamp = last.querySelector<HTMLElement>("[data-evidence-stamp]");
    expect(stamp).toHaveAttribute("data-evidence-stamp", "a0");
    expect(last).toHaveTextContent("never read");
    expect(last).not.toHaveTextContent(/\d+\s*(h|d|m)\b/);
  });
});
