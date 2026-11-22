import { fireEvent, render, screen, within } from "@testing-library/react";
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
const READ_AT = "2026-11-22T10:00:00Z";

// Synthetic devices only (FW-TANGO-04 style names, 192.0.2.x addresses).
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
    device_id: "dev-tango-05",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-05",
    model: "26000",
    software_version: "R81.20",
    ha_role: "STANDBY",
    cluster_member_ref: "CLS-ROMEO-01",
    management_ip: "192.0.2.15",
    backup_target: false,
    inventory_collected_at: READ_AT,
  },
  {
    device_id: "dev-sierra-01",
    vendor_hint: "palo_alto",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-SIERRA-01",
    model: "PA-5420",
    software_version: "11.1.10-h7",
    ha_role: null,
    cluster_member_ref: null,
    management_ip: "198.51.100.21",
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
    inventory_collected_at: READ_AT,
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

function tileFor(name: string): HTMLElement {
  const tile = document.querySelector<HTMLElement>(`[data-unit-tile="${name}"]`);
  if (!tile) throw new Error(`tile ${name} not rendered`);
  return tile;
}

function searchBox(): HTMLElement {
  return screen.getByRole("textbox", { name: "Filter units by name or cluster" });
}

function setQuery(value: string) {
  fireEvent.change(searchBox(), { target: { value } });
}

beforeEach(() => {
  window.localStorage.clear();
  fixture.model = buildFixtureModel();
});

describe("Estate wall search: Filter and Dim modes", () => {
  it("defaults to Filter: non-matching list rows are removed and every tile stays in place", () => {
    render(<EstateWallScreen />);

    const filterBtn = screen.getByRole("button", { name: "Filter" });
    const dimBtn = screen.getByRole("button", { name: "Dim" });
    expect(filterBtn).toHaveAttribute("aria-pressed", "true");
    expect(dimBtn).toHaveAttribute("aria-pressed", "false");

    setQuery("tango");

    expect(document.querySelectorAll("[data-unit-row]")).toHaveLength(2);
    expect(document.querySelector('[data-unit-row="FW-SIERRA-01"]')).toBeNull();
    expect(screen.getByTestId("estate-wall-match-count")).toHaveTextContent("2 of 4 units match");

    // Filter output is unchanged: the wall still renders all four tiles, none dimmed.
    expect(document.querySelectorAll("[data-unit-tile]")).toHaveLength(4);
    for (const name of ["FW-TANGO-04", "FW-TANGO-05", "FW-SIERRA-01", "DEV-BRAVO-02"]) {
      expect(tileFor(name)).toHaveAttribute("data-dimmed", "false");
    }
  });

  it("Dim mode keeps every tile and list row, dims only non-matching units, and prints the match count", () => {
    render(<EstateWallScreen />);

    fireEvent.click(screen.getByRole("button", { name: "Dim" }));
    expect(screen.getByRole("button", { name: "Dim" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Filter" })).toHaveAttribute("aria-pressed", "false");

    setQuery("tango");

    expect(document.querySelectorAll("[data-unit-tile]")).toHaveLength(4);
    expect(document.querySelectorAll("[data-unit-row]")).toHaveLength(4);
    expect(tileFor("FW-TANGO-04")).toHaveAttribute("data-dimmed", "false");
    expect(tileFor("FW-TANGO-05")).toHaveAttribute("data-dimmed", "false");
    expect(tileFor("FW-SIERRA-01")).toHaveAttribute("data-dimmed", "true");
    expect(tileFor("DEV-BRAVO-02")).toHaveAttribute("data-dimmed", "true");
    expect(screen.getByTestId("estate-wall-match-count")).toHaveTextContent("2 of 4 units match");
  });

  it("matches by cluster ref in Dim mode", () => {
    render(<EstateWallScreen />);
    fireEvent.click(screen.getByRole("button", { name: "Dim" }));

    setQuery("CLS-ROMEO");

    expect(tileFor("FW-TANGO-04")).toHaveAttribute("data-dimmed", "false");
    expect(tileFor("FW-TANGO-05")).toHaveAttribute("data-dimmed", "false");
    expect(tileFor("FW-SIERRA-01")).toHaveAttribute("data-dimmed", "true");
    expect(screen.getByTestId("estate-wall-match-count")).toHaveTextContent("2 of 4 units match");
  });

  it("says 'no query' with the lens total when the box is empty, and dims nothing", () => {
    render(<EstateWallScreen />);
    fireEvent.click(screen.getByRole("button", { name: "Dim" }));

    expect(screen.getByTestId("estate-wall-match-count")).toHaveTextContent("no query · 4 units in lens");
    for (const tile of document.querySelectorAll("[data-unit-tile]")) {
      expect(tile).toHaveAttribute("data-dimmed", "false");
    }
  });

  it("prints a zero match count without hiding anything in Dim mode", () => {
    render(<EstateWallScreen />);
    fireEvent.click(screen.getByRole("button", { name: "Dim" }));

    setQuery("ZZ-NO-SUCH-UNIT");

    expect(screen.getByTestId("estate-wall-match-count")).toHaveTextContent("0 of 4 units match");
    expect(document.querySelectorAll("[data-unit-tile]")).toHaveLength(4);
    for (const tile of document.querySelectorAll("[data-unit-tile]")) {
      expect(tile).toHaveAttribute("data-dimmed", "true");
      // State is still carried by the tile's glyph/label, not by the fade alone.
      expect(tile.getAttribute("aria-label")).toBeTruthy();
    }
    // Dim does not restore the Filter behaviour: switching back to Filter hides rows again.
    fireEvent.click(screen.getByRole("button", { name: "Filter" }));
    expect(document.querySelectorAll("[data-unit-row]")).toHaveLength(0);
    expect(within(screen.getByRole("table", { name: "Estate units" })).queryByText("FW-TANGO-04")).toBeNull();
  });
});

describe("Estate wall lens source stamp under Backups", () => {
  it("shows the oldest and newest archive evidence and counts units with no archive evidence time", () => {
    // Synthetic: two archives of different ages and one backup target with no archive at all.
    const archivesDevices: readonly DeviceSummary[] = [DEVICES[0], DEVICES[2], DEVICES[3]].map((d) => ({
      ...d,
      backup_target: true,
    }));
    fixture.model = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: archivesDevices },
      overview: { state: "failed" },
      failover: { state: "failed" },
      backups: {
        state: "ok",
        value: {
          backups: [
            {
              artefact_id: "art-tango-04",
              device_id: "dev-tango-04",
              collected_at: "2026-11-16T12:00:00Z",
              size_bytes: 1024,
              digest_prefix: "00000000",
              validation_level: "VALIDATED",
              deviation_state: "unchanged",
            },
            {
              artefact_id: "art-sierra-01",
              device_id: "dev-sierra-01",
              collected_at: "2026-11-22T11:57:00Z",
              size_bytes: 1024,
              digest_prefix: "11111111",
              validation_level: "VALIDATED",
              deviation_state: "unchanged",
            },
          ],
          deviations: null,
        },
      },
      lifecycle: { state: "failed" },
    });
    render(<EstateWallScreen />);
    window.history.replaceState({}, "", "/?lens=backups");
    fireEvent(window, new PopStateEvent("popstate"));

    const stamp = screen.getByTestId("estate-lens-evidence-stamp");
    // Oldest (6 d) and newest (3 min) are both shown, never only the newest.
    expect(stamp).toHaveTextContent(/6 d/);
    expect(stamp).toHaveTextContent(/3 min/);
    expect(stamp).toHaveTextContent("to");
    expect(stamp).toHaveTextContent("across 2 of 3 units with backup evidence");
    // The unit with no archive counts as no evidence time (UNKNOWN), not as inventory age (inventory read 2 h ago).
    expect(stamp).toHaveTextContent("1 unit has no backup evidence time (UNKNOWN)");
    expect(stamp).not.toHaveTextContent("2 h");

    window.history.replaceState({}, "", "/");
  });
});
