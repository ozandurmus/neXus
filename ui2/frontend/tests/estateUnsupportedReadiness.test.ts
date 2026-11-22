import { describe, expect, it } from "vitest";
import type { CpFailoverSummary, DeviceSummary } from "../src/auth/adminApi";
import { buildEstateModel } from "../src/estate/estateModel";

// Pins review fix 7: UNSUPPORTED readiness is "No evidence" (badge `unk`), never "Not applicable" (`na`).
// Synthetic names and addresses only.

const NOW = new Date("2026-11-19T12:00:00Z");

const DEVICES: readonly DeviceSummary[] = [
  // Check Point pair, READY
  { device_id: "dev-tango-03", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-TANGO-03", model: "16200", software_version: "R81.20", ha_role: "ACTIVE", cluster_member_ref: "CLS-ROMEO-01", backup_target: true, inventory_collected_at: "2026-11-19T10:00:00Z" },
  { device_id: "dev-tango-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04", model: "16200", software_version: "R81.20", ha_role: "STANDBY", cluster_member_ref: "CLS-ROMEO-01", backup_target: true, inventory_collected_at: "2026-11-19T10:00:00Z" },
  // Fortinet pair, readiness UNSUPPORTED for this vendor
  { device_id: "dev-echo-05", vendor_hint: "fortinet", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-ECHO-05", model: "FG-600", software_version: "7.4", ha_role: "ACTIVE", cluster_member_ref: "CLS-ECHO-02", backup_target: false, inventory_collected_at: "2026-11-19T10:00:00Z" },
  { device_id: "dev-echo-06", vendor_hint: "fortinet", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-ECHO-06", model: "FG-600", software_version: "7.4", ha_role: "STANDBY", cluster_member_ref: "CLS-ECHO-02", backup_target: false, inventory_collected_at: "2026-11-19T10:00:00Z" },
  // Check Point pair with no readiness rows at all
  { device_id: "dev-golf-07", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-GOLF-07", model: "16200", software_version: "R81.20", ha_role: "ACTIVE", cluster_member_ref: "CLS-GOLF-03", backup_target: false, inventory_collected_at: "2026-11-19T10:00:00Z" },
  { device_id: "dev-golf-08", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-GOLF-08", model: "16200", software_version: "R81.20", ha_role: "STANDBY", cluster_member_ref: "CLS-GOLF-03", backup_target: false, inventory_collected_at: "2026-11-19T10:00:00Z" },
  // Standalone gateway, not in an HA cluster
  { device_id: "dev-hotel-09", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-HOTEL-09", model: "6200", software_version: "R81.20", ha_role: "NONE", backup_target: false, inventory_collected_at: "2026-11-19T10:00:00Z" },
] as unknown as readonly DeviceSummary[];

const FAILOVER: readonly CpFailoverSummary[] = [
  {
    unitId: "cls-romeo-01",
    clusterId: "cls-romeo-01",
    cluster_member_ref: "CLS-ROMEO-01",
    vendor: "check_point",
    mode: "ACTIVE_STANDBY",
    readiness: { status: "READY", observedAt: "2026-11-19T11:00:00Z", checks: [] },
  } as unknown as CpFailoverSummary,
  {
    unitId: "cls-echo-02",
    clusterId: "cls-echo-02",
    cluster_member_ref: "CLS-ECHO-02",
    vendor: "fortinet",
    mode: "ACTIVE_STANDBY",
    readinessAvailable: false,
  } as unknown as CpFailoverSummary,
];

function build(failover: readonly CpFailoverSummary[] = FAILOVER) {
  return buildEstateModel({
    now: NOW,
    devices: { state: "ok", value: DEVICES },
    overview: { state: "failed" },
    failover: { state: "ok", value: failover },
    backups: { state: "failed" },
    lifecycle: { state: "failed" },
  });
}

describe("UNSUPPORTED readiness fails closed to UNKNOWN (No evidence), never Not applicable", () => {
  it("maps an UNSUPPORTED cluster's members to unk with the No evidence word, never na", () => {
    const model = build();
    const echo = model.byId.get("dev-echo-05")!;
    expect(echo.lenses.failover.tileState).toBe("unk");
    expect(echo.lenses.failover.badgeState).toBe("unk");
    expect(echo.lenses.failover.badgeWord).toBe("No evidence");
    expect(model.byId.get("dev-echo-06")!.lenses.failover.tileState).toBe("unk");
    expect(model.byId.get("dev-echo-06")!.lenses.failover.badgeWord).toBe("No evidence");
  });

  it("gives an UNSUPPORTED cluster pairState unk and failoverStatus UNSUPPORTED", () => {
    const model = build();
    const cluster = model.clustersByRef.get("CLS-ECHO-02")!;
    expect(cluster.failoverStatus).toBe("UNSUPPORTED");
    expect(cluster.pairState).toBe("unk");
    expect(cluster.pairState).not.toBe("na");
  });

  it("counts UNSUPPORTED in haCounts.unsupported only, not in ready or notReady", () => {
    const model = build();
    expect(model.haCounts).not.toBeNull();
    expect(model.haCounts).toMatchObject({ total: 3, ready: 1, notReady: 0, unsupported: 1 });
    // The Check Point pair with no readiness rows is UNKNOWN, not notReady and not ready
    expect(model.haCounts!.unknown).toBe(1);
    expect(model.haCounts!.ready + model.haCounts!.notReady + model.haCounts!.unknown + model.haCounts!.unsupported)
      .toBe(model.haCounts!.total);
  });

  it("maps a cluster with no readiness item to unk, with its members unk", () => {
    const model = build();
    const golf = model.clustersByRef.get("CLS-GOLF-03")!;
    expect(golf.pairState).toBe("unk");
    expect(model.byId.get("dev-golf-07")!.lenses.failover.tileState).toBe("unk");
    expect(model.byId.get("dev-golf-08")!.lenses.failover.tileState).toBe("unk");
  });

  it("keeps a standalone unit (no HA cluster) as na", () => {
    const model = build();
    const hotel = model.byId.get("dev-hotel-09")!;
    expect(hotel.lenses.failover.tileState).toBe("na");
    expect(hotel.lenses.failover.badgeState).toBe("na");
  });

  it("never produces na for any failover lens of a clustered unit", () => {
    const model = build();
    for (const unit of model.units) {
      if (!unit.clusterRef) continue;
      expect(unit.lenses.failover.badgeState, unit.name).not.toBe("na");
    }
  });
});
