import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { EstateView } from "../src/auth/adminApi";
import { STATUS } from "../src/shell/Charts";
import { CONDITION, EXEC, EstateMap, NOT_ASSESSED_REASON } from "../src/screens/overview/EstateMap";

/**
 * Estate map, UI review 2026-10-17 P0-1. The classification is the backend's (OverviewService.estateCondition,
 * EstateConditionTest); here: what each condition looks like, that a not-assessed cell is never green and says why,
 * and that a target without an archive carries a marker of its own while the legend keeps its count.
 */
const NOW = new Date().toISOString();
type Cell = EstateView["devices"][number];
const cell = (id: string, vendor: string, condition: Cell["condition"], extra: Partial<Cell> = {}): Cell => ({
  device_id: id, hostname: `FW-${id.toUpperCase()}`, vendor, model: null, condition, no_archive: false,
  critical_fail: condition === "critical" ? 1 : 0, last_read_at: NOW, ...extra,
});

function estate(devices: Cell[]): EstateView {
  const counts = { critical: 0, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: 0 };
  devices.forEach((d) => { counts[d.condition]++; });
  return { devices, counts, no_archive: devices.filter((d) => d.no_archive).length, compliance_state: "OK",
    facts: { critical: { k: 0, n: 0 }, backups: { a: 0, b: 0 }, evidence: { r: 0, d: 0, never: 0 } } };
}

describe("estate map cells", () => {
  it("draws a device with no evidenced compliance result as Not assessed, hatched, never green", async () => {
    // the Cisco ASA shape: every check "evidence not collected"
    render(<EstateMap estate={estate([cell("tango-01", "cisco_asa", "not_assessed", { condition_reason: "no_evidence" })])} />);
    const tile = screen.getByLabelText("FW-TANGO-01: Not assessed");
    expect(tile.getAttribute("data-condition")).toBe("not_assessed");
    expect(CONDITION.not_assessed.bg).not.toBe(EXEC.green);
    // drawn hatched (the computed style of the rendered tile), not in the green fill
    expect(getComputedStyle(tile).backgroundImage).toContain("repeating-linear-gradient");
    expect(getComputedStyle(tile).background).not.toContain(STATUS.good);
    fireEvent.mouseOver(tile);
    expect((await screen.findByRole("tooltip", {}, { timeout: 5000 })).textContent).toContain(NOT_ASSESSED_REASON.no_evidence);
    expect(screen.queryByText(/^Clear/)).toBeNull();
  });

  it("names a device without any critical control as not assessed for that reason", async () => {
    render(<EstateMap estate={estate([cell("juliet-06", "fortinet", "not_assessed", { condition_reason: "no_critical_control" })])} />);
    fireEvent.mouseOver(screen.getByLabelText("FW-JULIET-06: Not assessed"));
    expect((await screen.findByRole("tooltip", {}, { timeout: 5000 })).textContent).toContain(NOT_ASSESSED_REASON.no_critical_control);
  });

  it("keeps green for Clear only: zero failing critical checks with evidence", () => {
    render(<EstateMap estate={estate([cell("bravo-02", "palo_alto", "clear")])} />);
    expect(screen.getByLabelText("FW-BRAVO-02: Clear").getAttribute("data-condition")).toBe("clear");
    expect(CONDITION.clear.bg).toBe(EXEC.green);
    for (const c of ["critical", "ageing", "partly_assessed", "not_assessed"] as const) {
      expect(CONDITION[c].bg).not.toBe(EXEC.green);
    }
  });
});

describe("no-archive marker", () => {
  it("marks exactly the targets without an archive, on any fill, and keeps the legend count", () => {
    const devices = [
      cell("tango-04", "check_point", "critical", { no_archive: true, critical_fail: 1 }),
      cell("tango-05", "check_point", "critical", { critical_fail: 3 }),
      cell("romeo-01", "palo_alto", "not_assessed", { no_archive: true, condition_reason: "not_evaluated" }),
      cell("bravo-02", "palo_alto", "clear"),
    ];
    render(<EstateMap estate={estate(devices)} />);
    const marked = screen.getAllByTestId("no-archive-mark");
    expect(marked).toHaveLength(2);
    expect(screen.getByLabelText("FW-TANGO-04: Critical finding, no stored archive").contains(marked[0])).toBe(true);
    expect(screen.getByLabelText("FW-ROMEO-01: Not assessed, no stored archive").contains(marked[1])).toBe(true);
    expect(screen.getByLabelText("FW-TANGO-05: Critical finding").querySelector("[data-testid=no-archive-mark]")).toBeNull();
    // the legend entry keeps its count and its link to the filtered Backups list
    const legend = screen.getByRole("link", { name: /No archive/ });
    expect(legend.textContent).toBe("No archive 2");
    expect(legend.getAttribute("href")).toBe("?screen=backups&artefact=none");
  });

  it("lists every legend count so the parts add up to the devices drawn", () => {
    const devices = [
      cell("a", "check_point", "critical", { critical_fail: 1 }), cell("b", "check_point", "critical", { critical_fail: 2 }),
      cell("c", "palo_alto", "ageing"), cell("d", "cisco_asa", "not_assessed"), cell("e", "cisco_asa", "not_assessed"),
      cell("f", "palo_alto", "clear"),
    ];
    const { container } = render(<EstateMap estate={estate(devices)} />);
    const shown = [...container.querySelectorAll("b")].map((b) => Number(b.textContent)).filter((n) => !Number.isNaN(n));
    expect(shown.reduce((n, x) => n + x, 0)).toBe(devices.length);
  });
});
