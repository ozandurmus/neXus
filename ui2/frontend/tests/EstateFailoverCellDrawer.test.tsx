import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as adminApi from "../src/auth/adminApi";
import type { CpFailoverSummary } from "../src/auth/adminApi";
import { EstateFailoverScreen } from "../src/estate/EstateFailoverScreen";
import { PER_UNIT_PRECHECK_NOTE } from "../src/estate/EstateFailoverCellDrawer";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  listFailoverIncidents: vi.fn(),
}));

// Synthetic board: one VSX chassis, one Virtual System under it, one plain HA pair with no observations.
// All names are masked (FW-TANGO-04 style) and addresses are documentation ranges.
vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => ({
    loading: false,
    fatalError: null,
    devices: { state: "ok", value: [] },
    failover: { state: "ok", value: FAILOVER_ROWS },
    model: null,
    refresh: () => undefined,
  }),
}));

const FAILOVER_ROWS: readonly CpFailoverSummary[] = [
  {
    unitId: "fw-tango-04",
    clusterId: "fw-tango-04",
    cluster_member_ref: "FW-TANGO-04",
    vendor: "check_point",
    unitKind: "VSX_CHASSIS",
    failoverRefusal: "VSX_CHASSIS_NOT_FAILOVER_UNIT",
    activeWindow: true,
    lastRunState: "DONE",
    lastRunOutcome: "SUCCEEDED",
    lastRunAt: "2026-11-19T10:00:00Z",
    canRunReadiness: true,
    mode: "LOAD_SHARING",
    readiness: {
      status: "READY",
      observedAt: "2026-11-19T10:00:00Z",
      failedCheck: "",
      checks: [
        { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
        { checkNo: 1, title: "Cluster state", member: "Member 2", result: "PASS", status: "PASS", summary: "Standby", blocking: true, derived: {} },
      ],
    },
  },
  {
    unitId: "fw-tango-04:vs-1",
    clusterId: "fw-tango-04",
    cluster_member_ref: "FW-TANGO-04",
    vendor: "check_point",
    unitKind: "VIRTUAL_SYSTEM",
    virtual_system: "VS-TANGO-04-01",
    activeWindow: true,
    lastRunState: "DONE",
    lastRunOutcome: "STOPPED",
    lastRunAt: "2026-11-19T10:00:00Z",
    canRunReadiness: true,
    mode: "ACTIVE_STANDBY",
    readiness: {
      status: "NOT_READY",
      observedAt: "2026-11-19T10:00:00Z",
      failedCheck: "Policy parity failed",
      checks: [
        { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
        { checkNo: 3, title: "Policy parity", member: "Member 1", result: "FAIL", status: "FAIL", summary: "POL-A vs POL-B", blocking: true, derived: {} },
      ],
    },
  },
  {
    unitId: "fw-bravo-02",
    clusterId: "fw-bravo-02",
    cluster_member_ref: "FW-BRAVO-02",
    vendor: "palo_alto",
    activeWindow: true,
    lastRunState: null,
    lastRunOutcome: null,
    lastRunAt: null,
    canRunReadiness: true,
    readiness: null, // never evaluated -> every cell UNKNOWN
  },
];

const cellButtons = (name: RegExp) => screen.getAllByRole("button", { name });

beforeEach(() => {
  vi.mocked(adminApi.listFailoverIncidents).mockResolvedValue({ canRelease: false, roleNote: null, incidents: [] });
});

describe("EstateFailoverScreen pre-check cell drawer", () => {
  it("opens a read-only drawer with the unit label, pre-check title, word and full summary on click", async () => {
    render(<EstateFailoverScreen />);
    const cell = cellButtons(/VS VS-TANGO-04-01: Blocks/)[0];
    fireEvent.click(cell);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("heading", { level: 2, name: "Policy parity" })).toBeInTheDocument();
    expect(within(dialog).getByText(/FW-TANGO-04 · VS VS-TANGO-04-01/)).toBeInTheDocument();
    expect(within(dialog).getByText("Blocks")).toBeInTheDocument();
    expect(within(dialog).getByText("Member 1: POL-A vs POL-B")).toBeInTheDocument();
    expect(within(dialog).getByText("no evaluation time recorded")).toBeInTheDocument();
  });

  it("opens the drawer from Enter and Space on a focused cell; the cell is a keyboard button", async () => {
    render(<EstateFailoverScreen />);
    const cell = cellButtons(/VS VS-TANGO-04-01: Blocks/)[0];
    expect(cell).toHaveAttribute("tabindex", "0");
    expect(cell).toHaveAttribute("role", "button");

    cell.focus();
    fireEvent.keyDown(cell, { key: "Enter" });
    expect(await screen.findByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    fireEvent.keyDown(cellButtons(/VS VS-TANGO-04-01: Blocks/)[0], { key: " " });
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  it("renders UNKNOWN, never a pass word, for a cell with no observations", async () => {
    render(<EstateFailoverScreen />);
    const unknownCell = cellButtons(/^FW-BRAVO-02: UNKNOWN$/)[0];
    expect(unknownCell.closest("td")).toHaveAttribute("data-check-state", "unk");
    fireEvent.click(unknownCell);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(dialog).queryByText("Pass")).not.toBeInTheDocument();
    expect(within(dialog).getByText("No pre-check observations recorded for this unit")).toBeInTheDocument();
  });

  it("shows the per-VS evaluation sentence for a Virtual System cell and not for a plain HA pair cell", async () => {
    render(<EstateFailoverScreen />);

    fireEvent.click(cellButtons(/VS VS-TANGO-04-01: Blocks/)[0]);
    const vsDialog = await screen.findByRole("dialog");
    expect(within(vsDialog).getByText(PER_UNIT_PRECHECK_NOTE)).toBeInTheDocument();
    fireEvent.keyDown(vsDialog, { key: "Escape" });
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    fireEvent.click(cellButtons(/^FW-BRAVO-02: UNKNOWN$/)[0]);
    const pairDialog = await screen.findByRole("dialog");
    expect(within(pairDialog).queryByText(PER_UNIT_PRECHECK_NOTE)).not.toBeInTheDocument();
  });

  it("has no button in the drawer; the only control is the standard-view link", async () => {
    render(<EstateFailoverScreen />);
    fireEvent.click(cellButtons(/VS VS-TANGO-04-01: Blocks/)[0]);
    const dialog = await screen.findByRole("dialog");

    expect(within(dialog).queryAllByRole("button")).toHaveLength(0);
    const link = within(dialog).getByRole("link", { name: /Open in standard view/ });
    expect(link.getAttribute("href")).toContain("standard=1");
  });
});
