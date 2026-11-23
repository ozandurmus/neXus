import { describe, expect, it } from "vitest";
import type { CpFailoverSummary, DeviceSummary } from "../src/auth/adminApi";
import { buildEstateModel } from "../src/estate/estateModel";

// Regression tests for failover "no readiness evidence" handling (ESTATE_VIEW_EFFECTIVENESS_REVIEW_2026_11_21).
// Pure model tests via buildEstateModel. Synthetic names and addresses only.

const NOW = new Date("2026-11-23T12:00:00Z");
const STAMP = "2026-11-23T11:00:00Z";

function member(deviceId: string, hostname: string, clusterRef: string, haRole: string): DeviceSummary {
  return {
    device_id: deviceId,
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname,
    model: "16200",
    software_version: "R81.20",
    ha_role: haRole,
    cluster_member_ref: clusterRef,
    backup_target: false,
    inventory_collected_at: STAMP,
  } as unknown as DeviceSummary;
}

const DEVICES: readonly DeviceSummary[] = [
  // (a) UNSUPPORTED readiness
  member("dev-tango-01", "FW-TANGO-01", "CLS-TANGO-10", "ACTIVE"),
  member("dev-tango-02", "FW-TANGO-02", "CLS-TANGO-10", "STANDBY"),
  // (b) cluster absent from readiness rows
  member("dev-bravo-03", "FW-BRAVO-03", "CLS-BRAVO-20", "ACTIVE"),
  member("dev-bravo-04", "FW-BRAVO-04", "CLS-BRAVO-20", "STANDBY"),
  // (c) READY cluster with no observedAt
  member("dev-romeo-05", "FW-ROMEO-05", "CLS-ROMEO-30", "ACTIVE"),
  member("dev-romeo-06", "FW-ROMEO-06", "CLS-ROMEO-30", "STANDBY"),
] as unknown as readonly DeviceSummary[];

const FAILOVER: readonly CpFailoverSummary[] = [
  {
    unitId: "cls-tango-10",
    clusterId: "cls-tango-10",
    cluster_member_ref: "CLS-TANGO-10",
    vendor: "check_point",
    mode: "ACTIVE_STANDBY",
    readiness: { status: "UNSUPPORTED", observedAt: STAMP, checks: [] },
  } as unknown as CpFailoverSummary,
  {
    unitId: "cls-romeo-30",
    clusterId: "cls-romeo-30",
    cluster_member_ref: "CLS-ROMEO-30",
    vendor: "check_point",
    mode: "ACTIVE_STANDBY",
    readiness: { status: "READY", checks: [] },
  } as unknown as CpFailoverSummary,
];

function build() {
  return buildEstateModel({
    now: NOW,
    devices: { state: "ok", value: DEVICES },
    overview: { state: "failed" },
    failover: { state: "ok", value: FAILOVER },
    backups: { state: "failed" },
    lifecycle: { state: "failed" },
  });
}

describe("failover without readiness evidence is unk, never na or ok", () => {
  it("(a) UNSUPPORTED readiness gives the cluster and its members unk, never na", () => {
    const model = build();
    const cluster = model.clustersByRef.get("CLS-TANGO-10")!;
    expect(cluster.failoverStatus).toBe("UNSUPPORTED");
    expect(cluster.pairState).toBe("unk");
    expect(cluster.pairState).not.toBe("na");
    for (const id of ["dev-tango-01", "dev-tango-02"]) {
      const lens = model.byId.get(id)!.lenses.failover;
      expect(lens.tileState).toBe("unk");
      expect(lens.badgeState).toBe("unk");
      expect(lens.badgeWord).toBe("No evidence");
    }
  });

  it("(b) a cluster missing from the readiness rows gives pairState unk and members unk", () => {
    const model = build();
    const cluster = model.clustersByRef.get("CLS-BRAVO-20")!;
    expect(cluster.failoverStatus).toBe("UNKNOWN");
    expect(cluster.pairState).toBe("unk");
    for (const id of ["dev-bravo-03", "dev-bravo-04"]) {
      const lens = model.byId.get(id)!.lenses.failover;
      expect(lens.tileState).toBe("unk");
      expect(lens.badgeState).toBe("unk");
    }
  });

  it("(c) a READY cluster with no observedAt gives pairState unk, not ok", () => {
    const model = build();
    const cluster = model.clustersByRef.get("CLS-ROMEO-30")!;
    expect(cluster.failoverStatus).toBe("READY");
    expect(cluster.observedAt).toBeNull();
    expect(cluster.pairState).toBe("unk");
    expect(cluster.pairState).not.toBe("ok");
    const lens = model.byId.get("dev-romeo-05")!.lenses.failover;
    expect(lens.tileState).toBe("unk");
    expect(lens.tileState).not.toBe("ok");
  });
});
