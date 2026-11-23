import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as adminApi from "../src/auth/adminApi";
import type { CpFailoverSummary, DeviceSummary } from "../src/auth/adminApi";
import { EstateFailoverScreen } from "../src/estate/EstateFailoverScreen";
import { EstateFailoverCellDrawer, FAILOVER_START_NOTE, PER_UNIT_PRECHECK_NOTE } from "../src/estate/EstateFailoverCellDrawer";
import {
  buildFailoverBoard,
  buildFailoverBoardModel,
  evaluateCheckMatrixCell,
  UNSUPPORTED_PAIRS_CAP,
} from "../src/estate/estateSlice2Model";
import { useEstateData } from "../src/estate/useEstateData";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  listFailoverIncidents: vi.fn(),
}));

// Synthetic board: one VSX chassis, one Virtual System under it, one plain HA pair with no observations.
// All names are masked (FW-TANGO-04 style) and addresses are documentation ranges.
vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: vi.fn(),
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
  vi.mocked(useEstateData).mockReturnValue({
    loading: false,
    fatalError: null,
    devices: { state: "ok", value: [] },
    failover: { state: "ok", value: FAILOVER_ROWS },
    model: null,
    refresh: () => undefined,
  } as never);
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

  // PO 2026-11-23: both views word the failover start the same way; Estate stays read-only and only says where it is done.
  it("words the failover start as one confirmation with a work log note, in the banner and in the drawer, with no approval form", async () => {
    render(<EstateFailoverScreen />);
    expect(FAILOVER_START_NOTE).toMatch(/one confirmation with a work log note/);
    expect(screen.getByText(new RegExp(FAILOVER_START_NOTE.slice(0, 60)))).toBeInTheDocument();
    expect(screen.queryByText(/manage failover approvals/)).not.toBeInTheDocument();

    fireEvent.click(cellButtons(/VS VS-TANGO-04-01: Blocks/)[0]);
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByTestId("failover-cell-drawer-start-note")).toHaveTextContent(FAILOVER_START_NOTE);
    expect(within(dialog).queryAllByRole("button")).toHaveLength(0);
    expect(within(dialog).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: /Open in standard view/ }).getAttribute("href")).toContain("standard=1");
  });

  it("does not describe a failover start for a unit the server offers none for, or a vendor without execution", () => {
    const cell = { state: "ok", word: "Pass", summary: "Active", observedAt: null } as never;
    const base = { unitLabel: "FW-TANGO-04", unitKind: "VSX_CHASSIS" as const, checkTitle: "Cluster state", cell, standardHref: "?screen=operations&standard=1" };
    const view = render(<EstateFailoverCellDrawer target={{ ...base, vendor: "check_point", failoverRefusal: "VSX_CHASSIS_NOT_FAILOVER_UNIT" }} onClose={() => undefined} />);
    expect(screen.getByTestId("failover-cell-drawer-gate")).toBeInTheDocument();
    expect(screen.queryByTestId("failover-cell-drawer-start-note")).not.toBeInTheDocument();
    view.unmount();
    render(<EstateFailoverCellDrawer target={{ ...base, unitKind: "CLUSTER", vendor: "cisco_asa" }} onClose={() => undefined} />);
    expect(screen.getByText("Failover execution not enabled for Cisco ASA")).toBeInTheDocument();
    expect(screen.queryByTestId("failover-cell-drawer-start-note")).not.toBeInTheDocument();
  });

  it("propagates the newest observedAt onto FailoverMatrixCell and stamps EvidenceStamp in EstateFailoverCellDrawer", () => {
    const recentIso = new Date(Date.now() - 15 * 60_000).toISOString();
    const olderIso = new Date(Date.now() - 45 * 60_000).toISOString();

    const stampedCell = evaluateCheckMatrixCell(
      [
        { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", summary: "Active", blocking: true, observedAt: olderIso },
        { checkNo: 1, title: "Cluster state", member: "Member 2", result: "PASS", summary: "Standby", blocking: true, observedAt: recentIso },
      ],
      "cluster state",
    );
    expect(stampedCell.observedAt).toBe(recentIso);

    const missingCell = evaluateCheckMatrixCell(
      [{ checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", summary: "Active", blocking: true }],
      "policy parity",
    );
    expect(missingCell.observedAt).toBeNull();

    const { rerender } = render(
      <EstateFailoverCellDrawer
        target={{
          unitLabel: "FW-TANGO-04",
          unitKind: "CLUSTER",
          checkTitle: "Cluster state",
          cell: stampedCell,
          standardHref: "?screen=operations&standard=1",
          unit: { vendor: "cisco_asa", failoverRefusal: "VSX_CHASSIS_FAILOVER_NOT_APPROVED" },
        }}
        onClose={() => undefined}
      />,
    );
    const dialog = screen.getByRole("dialog");
    expect(dialog.querySelector('[data-evidence-stamp="a1"]')).not.toBeNull();
    expect(within(dialog).queryByText("no evaluation time recorded")).not.toBeInTheDocument();
    expect(within(dialog).getByText("VSX chassis failover is not enabled")).toBeInTheDocument();
    expect(within(dialog).getByText("Failover execution not enabled for Cisco ASA")).toBeInTheDocument();

    rerender(
      <EstateFailoverCellDrawer
        target={{
          unitLabel: "FW-TANGO-04",
          unitKind: "CLUSTER",
          checkTitle: "Policy parity",
          cell: missingCell,
          standardHref: "?screen=operations&standard=1",
        }}
        onClose={() => undefined}
      />,
    );
    const dialogMissing = screen.getByRole("dialog");
    expect(dialogMissing.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
    expect(within(dialogMissing).getByText("no evaluation time recorded")).toBeInTheDocument();
  });

  it("derives active/standby member roles via observedRoles from Check 1 derived.role and Cisco ASA Check 10 NOT_ACTIVE_MEMBER even when inventory ha_role is stale", () => {
    const now = new Date("2026-11-22T12:00:00Z");
    const devices: readonly DeviceSummary[] = [
      // Pair 1 (Check Point): inventory still says FW-TANGO-01 is ACTIVE and FW-TANGO-02 is STANDBY,
      // but readiness Check 1 observed FW-TANGO-01 as STANDBY and FW-TANGO-02 as ACTIVE.
      {
        device_id: "dev-tango-01",
        vendor_hint: "check_point",
        role: "gateway",
        enrollment_state: "ENROLLED",
        hostname: "FW-TANGO-01",
        model: "26000",
        software_version: "R81.20",
        ha_role: "ACTIVE",
        cluster_member_ref: "CLS-TANGO-01",
        backup_target: true,
        inventory_collected_at: "2026-11-22T08:00:00Z",
      },
      {
        device_id: "dev-tango-02",
        vendor_hint: "check_point",
        role: "gateway",
        enrollment_state: "ENROLLED",
        hostname: "FW-TANGO-02",
        model: "26000",
        software_version: "R81.20",
        ha_role: "STANDBY",
        cluster_member_ref: "CLS-TANGO-01",
        backup_target: true,
        inventory_collected_at: "2026-11-22T08:00:00Z",
      },
      // Pair 2 (Cisco ASA): inventory says FW-JULIET-01 is ACTIVE and FW-JULIET-02 is STANDBY,
      // but Check 10 on Member 1 reports NOT_ACTIVE_MEMBER while Check 1 passed on both.
      {
        device_id: "dev-juliet-01",
        vendor_hint: "cisco_asa",
        role: "gateway",
        enrollment_state: "ENROLLED",
        hostname: "FW-JULIET-01",
        model: "FPR-2130",
        software_version: "9.20(3)",
        ha_role: "ACTIVE",
        cluster_member_ref: "CLS-JULIET-02",
        backup_target: true,
        inventory_collected_at: "2026-11-22T08:00:00Z",
      },
      {
        device_id: "dev-juliet-02",
        vendor_hint: "cisco_asa",
        role: "gateway",
        enrollment_state: "ENROLLED",
        hostname: "FW-JULIET-02",
        model: "FPR-2130",
        software_version: "9.20(3)",
        ha_role: "STANDBY",
        cluster_member_ref: "CLS-JULIET-02",
        backup_target: true,
        inventory_collected_at: "2026-11-22T08:00:00Z",
      },
    ];

    const failoverRows: readonly CpFailoverSummary[] = [
      {
        unitId: "cls-tango-01",
        clusterId: "cls-tango-01",
        cluster_member_ref: "CLS-TANGO-01",
        vendor: "check_point",
        activeWindow: true,
        lastRunState: "DONE",
        lastRunOutcome: "SUCCEEDED",
        lastRunAt: "2026-11-22T11:00:00Z",
        canRunReadiness: true,
        mode: "ACTIVE_STANDBY",
        readiness: {
          status: "READY",
          observedAt: "2026-11-22T11:00:00Z",
          failedCheck: "",
          checks: [
            {
              checkNo: 1,
              title: "Cluster state",
              member: "Member 1",
              device_id: "dev-tango-01",
              result: "PASS",
              status: "PASS",
              summary: "Standby",
              blocking: true,
              derived: { role: "STANDBY" },
            },
            {
              checkNo: 1,
              title: "Cluster state",
              member: "Member 2",
              device_id: "dev-tango-02",
              result: "PASS",
              status: "PASS",
              summary: "Active",
              blocking: true,
              derived: { role: "ACTIVE" },
            },
          ],
        },
      },
      {
        unitId: "cls-juliet-02",
        clusterId: "cls-juliet-02",
        cluster_member_ref: "CLS-JULIET-02",
        vendor: "cisco_asa",
        readinessAvailable: true,
        activeWindow: true,
        lastRunState: "DONE",
        lastRunOutcome: "SUCCEEDED",
        lastRunAt: "2026-11-22T11:00:00Z",
        canRunReadiness: true,
        mode: "ACTIVE_STANDBY",
        readiness: {
          status: "READY",
          observedAt: "2026-11-22T11:00:00Z",
          failedCheck: "",
          checks: [
            { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Pass", blocking: true, derived: {} },
            { checkNo: 1, title: "Cluster state", member: "Member 2", result: "PASS", status: "PASS", summary: "Pass", blocking: true, derived: {} },
            {
              checkNo: 10,
              title: "Traffic and ARP",
              member: "Member 1",
              result: "NOT_EVALUATED",
              status: "NOT_EVALUATED",
              summary: "Read on the active member only",
              blocking: false,
              derived: { reason: "NOT_ACTIVE_MEMBER" },
            },
            {
              checkNo: 10,
              title: "Traffic and ARP",
              member: "Member 2",
              result: "PASS",
              status: "PASS",
              summary: "ARP table populated",
              blocking: false,
              derived: {},
            },
          ],
        },
      },
    ];

    const board = buildFailoverBoard({
      now,
      devices: { state: "ok", value: devices },
      failover: { state: "ok", value: failoverRows },
      model: null,
    });
    expect(buildFailoverBoardModel).toBe(buildFailoverBoard);

    const cpUnit = board.units.find((u) => u.clusterRef === "CLS-TANGO-01")!;
    expect(cpUnit.activeMembers.map((m) => m.name)).toEqual(["FW-TANGO-02"]);
    expect(cpUnit.standbyMembers.map((m) => m.name)).toEqual(["FW-TANGO-01"]);

    const asaUnit = board.units.find((u) => u.clusterRef === "CLS-JULIET-02")!;
    expect(asaUnit.activeMembers.map((m) => m.name)).toEqual(["FW-JULIET-02"]);
    expect(asaUnit.standbyMembers.map((m) => m.name)).toEqual(["FW-JULIET-01"]);
  });

  it("renders failover refusal reason, Cisco ASA execution gate, and caps unsupported pairs at UNSUPPORTED_PAIRS_CAP with member names and MoreFooter", async () => {
    const pad = (n: number) => String(n).padStart(2, "0");
    const unsupportedCount = UNSUPPORTED_PAIRS_CAP + 2;

    const unsupportedDevices: DeviceSummary[] = Array.from({ length: unsupportedCount }, (_, idx) => {
      const n = pad(idx + 1);
      const ref = `CLS-ECHO-${n}`;
      return [
        {
          device_id: `dev-echo-${n}-a`,
          vendor_hint: "fortinet",
          role: "gateway",
          enrollment_state: "ENROLLED",
          hostname: `FW-ECHO-${n}-A`,
          model: "FG-600F",
          software_version: "7.4.3",
          ha_role: "ACTIVE",
          cluster_member_ref: ref,
          backup_target: true,
          inventory_collected_at: "2026-11-22T10:00:00Z",
        },
        {
          device_id: `dev-echo-${n}-b`,
          vendor_hint: "fortinet",
          role: "gateway",
          enrollment_state: "ENROLLED",
          hostname: `FW-ECHO-${n}-B`,
          model: "FG-600F",
          software_version: "7.4.3",
          ha_role: "STANDBY",
          cluster_member_ref: ref,
          backup_target: true,
          inventory_collected_at: "2026-11-22T10:00:00Z",
        },
      ] as DeviceSummary[];
    }).flat();

    const asaDevices: DeviceSummary[] = [
      {
        device_id: "dev-juliet-01",
        vendor_hint: "cisco_asa",
        role: "gateway",
        enrollment_state: "ENROLLED",
        hostname: "FW-JULIET-01",
        model: "FPR-2130",
        software_version: "9.20(3)",
        ha_role: "ACTIVE",
        cluster_member_ref: "CLS-JULIET-01",
        backup_target: true,
        inventory_collected_at: "2026-11-22T10:00:00Z",
      },
      {
        device_id: "dev-juliet-02",
        vendor_hint: "cisco_asa",
        role: "gateway",
        enrollment_state: "ENROLLED",
        hostname: "FW-JULIET-02",
        model: "FPR-2130",
        software_version: "9.20(3)",
        ha_role: "STANDBY",
        cluster_member_ref: "CLS-JULIET-01",
        backup_target: true,
        inventory_collected_at: "2026-11-22T10:00:00Z",
      },
    ];

    const failoverRows: readonly CpFailoverSummary[] = [
      {
        unitId: "fw-tango-04",
        clusterId: "fw-tango-04",
        cluster_member_ref: "FW-TANGO-04",
        vendor: "check_point",
        unitKind: "VSX_CHASSIS",
        failoverRefusal: "VSX_CHASSIS_FAILOVER_NOT_APPROVED",
        activeWindow: true,
        lastRunState: "DONE",
        lastRunOutcome: "STOPPED",
        lastRunAt: "2026-11-19T10:00:00Z",
        canRunReadiness: true,
        mode: "LOAD_SHARING",
        readiness: {
          status: "NOT_READY",
          observedAt: "2026-11-19T10:00:00Z",
          failedCheck: "Cluster state",
          checks: [],
        },
      },
      {
        unitId: "cls-juliet-01",
        clusterId: "cls-juliet-01",
        cluster_member_ref: "CLS-JULIET-01",
        vendor: "cisco_asa",
        readinessAvailable: true,
        activeWindow: true,
        lastRunState: "DONE",
        lastRunOutcome: "STOPPED",
        lastRunAt: "2026-11-19T10:00:00Z",
        canRunReadiness: true,
        mode: "ACTIVE_STANDBY",
        readiness: {
          status: "NOT_READY",
          observedAt: "2026-11-19T10:00:00Z",
          failedCheck: "Interface state",
          checks: [],
        },
      },
    ];

    vi.mocked(useEstateData).mockReturnValue({
      loading: false,
      fatalError: null,
      devices: { state: "ok", value: [...asaDevices, ...unsupportedDevices] },
      failover: { state: "ok", value: failoverRows },
      model: null,
      refresh: () => undefined,
    } as never);

    render(<EstateFailoverScreen />);

    expect(screen.getByTestId("failover-unit-refusal")).toHaveTextContent(
      "Failover gate: VSX chassis failover is not enabled",
    );
    expect(screen.getByTestId("failover-execution-disabled")).toHaveTextContent(
      "Readiness only — failover execution not enabled for Cisco ASA",
    );

    // Unsupported pairs list is capped at UNSUPPORTED_PAIRS_CAP (10)
    expect(screen.getAllByText(/FW-ECHO-\d+-A \/ FW-ECHO-\d+-B/)).toHaveLength(UNSUPPORTED_PAIRS_CAP);
    expect(screen.getByText("FW-ECHO-01-A / FW-ECHO-01-B")).toBeInTheDocument();
    expect(screen.getByText("FW-ECHO-10-A / FW-ECHO-10-B")).toBeInTheDocument();
    expect(screen.queryByText("FW-ECHO-11-A / FW-ECHO-11-B")).not.toBeInTheDocument();
    expect(screen.getByText("12 pairs")).toBeInTheDocument();
    expect(screen.getByTestId("failover-unsupported-more")).toHaveTextContent("+2 more unsupported pairs");
  });
});
