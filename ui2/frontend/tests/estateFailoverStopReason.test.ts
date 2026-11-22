import { describe, expect, it } from "vitest";
import type { CpFailoverSummary, DeviceSummary } from "../src/auth/adminApi";
import { readinessStopReason } from "../src/screens/ReadinessChecksTable";
import { buildFailoverBoardModel } from "../src/estate/estateSlice2Model";

const NOW = new Date("2026-11-22T12:00:00Z");

// Synthetic only: FW-TANGO-style names and documentation addresses.
const DEVICES = [
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
    inventory_collected_at: "2026-11-22T10:00:00Z",
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
    inventory_collected_at: "2026-11-22T10:00:00Z",
  },
] as unknown as DeviceSummary[];

const STOP_CODE = ["COMMAND_UNAVAILABLE", "SYNTHETIC_PROBE", "login environment"].join(":");

function pairRow(readiness: Record<string, unknown>): CpFailoverSummary {
  return {
    unitId: "cls-tango-01",
    clusterId: "cls-tango-01",
    cluster_member_ref: "CLS-TANGO-01",
    vendor: "check_point",
    unitKind: "CLUSTER",
    failoverRefusal: null,
    activeWindow: true,
    lastRunState: null,
    lastRunOutcome: null,
    lastRunAt: null,
    canRunReadiness: true,
    mode: "ACTIVE_STANDBY",
    members: [],
    readiness: {
      failedCheck: "",
      checks: [
        { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
        { checkNo: 1, title: "Cluster state", member: "Member 2", result: "PASS", status: "PASS", summary: "Standby", blocking: true, derived: {} },
      ],
      ...readiness,
    },
  } as unknown as CpFailoverSummary;
}

function boardFor(row: CpFailoverSummary) {
  return buildFailoverBoardModel({
    now: NOW,
    devices: { state: "ok", value: DEVICES },
    failover: { state: "ok", value: [row] },
    model: null,
  });
}

describe("Estate failover: readiness stop reason on the pair card", () => {
  it("carries a COMMAND_UNAVAILABLE stop onto the pair and shows the shared plain-words line", () => {
    const board = boardFor(
      pairRow({ status: "UNKNOWN", observedAt: "2026-11-22T09:00:00Z", stopCode: STOP_CODE, checks: [] }),
    );
    const unit = board.units.find((u) => u.unitKind === "CLUSTER")!;
    expect(unit.stopCode).toBe(STOP_CODE);
    const line = readinessStopReason(unit.stopCode);
    expect(line).toBeTruthy();
    expect(typeof line).toBe("string");
  });

  it("shows no stop line when the readiness read carries no stop code", () => {
    const board = boardFor(pairRow({ status: "READY", observedAt: "2026-11-22T09:00:00Z" }));
    const unit = board.units.find((u) => u.unitKind === "CLUSTER")!;
    expect(unit.stopCode ?? null).toBeNull();
    expect(readinessStopReason(unit.stopCode)).toBeNull();
  });

  it("shows every check of a stopped read as UNKNOWN, never OK", () => {
    const board = boardFor(
      pairRow({ status: "UNKNOWN", observedAt: "2026-11-22T09:00:00Z", stopCode: STOP_CODE }),
    );
    const unit = board.units.find((u) => u.unitKind === "CLUSTER")!;
    expect(unit.checks.length).toBeGreaterThan(0);
    expect(unit.checks.every((c) => c.result === "UNKNOWN")).toBe(true);
    const cells = board.cellsByUnit.get(unit.key)!;
    expect(cells.get("cluster state")?.state).toBe("unk");
    for (const cell of cells.values()) {
      expect(cell.state).not.toBe("ok");
      expect(cell.word).not.toBe("Pass");
    }
  });

  it("does not crash on a stop code outside the known set and never shows it as OK", () => {
    const unknownCode = ["SYNTHETIC_NEW_STOP", "not-in-known-set"].join(":");
    const board = boardFor(
      pairRow({ status: "UNKNOWN", observedAt: "2026-11-22T09:00:00Z", stopCode: unknownCode, checks: [] }),
    );
    const unit = board.units.find((u) => u.unitKind === "CLUSTER")!;
    expect(readinessStopReason(unit.stopCode)).toBeNull();
    expect(unit.status).not.toBe("READY");
    expect(unit.boardColumn).not.toBe("ready_fresh");
    expect(unit.boardColumn).not.toBe("ready_stale");
    const cells = board.cellsByUnit.get(unit.key)!;
    for (const cell of cells.values()) expect(cell.state).not.toBe("ok");
  });
});
