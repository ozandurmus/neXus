import { describe, expect, it } from "vitest";
import type { DeviceSummary, SystemHealth, SystemHealthDevice } from "../src/auth/adminApi";
import { buildEstateModel, healthLensAnswer, LENS_META } from "../src/estate/estateModel";
import { ESTATE_LENSES, parseEstateLens } from "../src/estate/estateRouting";

// Synthetic only: masked pseudonyms and documentation addresses.
const NOW = new Date("2026-11-19T12:00:00Z");
const FRESH_AT = "2026-11-19T11:00:00Z"; // 1 h before NOW
const STALE_AT = "2026-11-10T12:00:00Z"; // 9 days before NOW

function healthRow(deviceId: string, state: SystemHealthDevice["state"], collection: SystemHealthDevice["collection"]["state"] = "OK", reason: string | null = null): SystemHealthDevice {
  // Only the fields the Health lens reads are meaningful here; the rest are inert placeholders.
  return {
    device_id: deviceId,
    hostname: "FW-TANGO-04",
    vendor_hint: "check_point",
    role: "gateway",
    cluster_member_ref: null,
    monitored: true,
    state,
    state_reason: reason,
    cpu: { level: "NORMAL", reason: null, threshold_percent: 80, newest_p95_percent: null, days_over_threshold: 0, longest_run_over_threshold: 0, days: [], day_basis: "" },
    memory: { level: "UNKNOWN", reason: null, newest_used_p95_percent: null },
    restarts: { level: "NORMAL", reason: null, count: 0, max_in_one_hour: 0, termination_lines: 0, busiest_hour: null, rule: "" },
    crash: { level: "NORMAL", reason: null, new_alerts: 0, newest_alert_at: null },
    collection: { state: collection, reason: null, last_job_at: null, last_job_id: null, outcomes: {} },
    logs_day: null,
  };
}

function healthSnapshot(computedAt: string | null, devices: readonly SystemHealthDevice[], state: SystemHealth["computation"]["state"] = "READY"): SystemHealth {
  return {
    computation: { state, computed_at: computedAt, duration_ms: 10, snapshot_ttl_seconds: 900, store_available: true },
    window_days: 7,
    cpu_p95_threshold_percent: 80,
    time_basis: "",
    rules: {
      critical: [], attention: [], normal: "", unknown: "", state_order: ["CRITICAL", "ATTENTION", "NORMAL", "UNKNOWN"], p95: "",
      restart_rule: { id: "", status: "", text: "" },
    },
    kpis: { monitored: null, crash_alerts: null, cpu_over_threshold: null, collection_not_ok: null },
    devices,
  };
}

const ok = (value: SystemHealth) => ({ state: "ok" as const, value });

describe("Health lens registration", () => {
  it("is a wall lens between Policy installed and Evidence age, labelled for operators", () => {
    expect(ESTATE_LENSES).toContain("health");
    expect(ESTATE_LENSES.indexOf("health")).toBe(ESTATE_LENSES.indexOf("install") + 1);
    expect(parseEstateLens("health")).toBe("health");
    expect(LENS_META.health.label).toBe("Health");
  });
});

describe("healthLensAnswer", () => {
  it("healthy: NORMAL on a fresh, fully collected snapshot is OK with the snapshot age", () => {
    const a = healthLensAnswer({
      source: ok(healthSnapshot(FRESH_AT, [healthRow("dev-tango-04", "NORMAL")])),
      device: healthRow("dev-tango-04", "NORMAL"),
      now: NOW,
    });
    expect(a.tileState).toBe("ok");
    expect(a.badgeState).toBe("ok");
    expect(a.evidenceAt).toBe(FRESH_AT);
    expect(a.evidenceSource).toBe("device health");
    expect(a.summary).toContain("1 h");
  });

  it("degraded: ATTENTION is watch and CRITICAL is act, each keeping the reason code as detail", () => {
    const attention = healthLensAnswer({
      source: ok(healthSnapshot(FRESH_AT, [])),
      device: healthRow("dev-juliet-06", "ATTENTION", "OK", "CPU_P95_OVER_THRESHOLD_1_DAYS"),
      now: NOW,
    });
    expect(attention.tileState).toBe("watch");
    expect(attention.detail).toBe("CPU_P95_OVER_THRESHOLD_1_DAYS");

    const critical = healthLensAnswer({
      source: ok(healthSnapshot(FRESH_AT, [])),
      device: healthRow("dev-bravo-02", "CRITICAL", "OK", "RESTARTS_3_IN_ONE_HOUR"),
      now: NOW,
    });
    expect(critical.tileState).toBe("act");
  });

  it("unknown: never reads as OK when the row, the read, the snapshot or the collection is missing", () => {
    const sourceCases = [
      healthLensAnswer({ source: undefined, device: null, now: NOW }),
      healthLensAnswer({ source: { state: "restricted" }, device: null, now: NOW }),
      healthLensAnswer({ source: { state: "failed" }, device: null, now: NOW }),
    ];
    for (const a of sourceCases) {
      expect(a.tileState).toBe("unk");
      expect(a.badgeState).toBe("unk");
    }

    const notComputed = healthLensAnswer({
      source: ok(healthSnapshot(null, [], "COMPUTING")),
      device: healthRow("dev-tango-04", "NORMAL"),
      now: NOW,
    });
    expect(notComputed.tileState).toBe("unk");
    expect(notComputed.evidenceAt).toBeNull();

    const noRow = healthLensAnswer({ source: ok(healthSnapshot(FRESH_AT, [])), device: null, now: NOW });
    expect(noRow.tileState).toBe("unk");

    const unknownState = healthLensAnswer({
      source: ok(healthSnapshot(FRESH_AT, [])),
      device: healthRow("dev-tango-04", "UNKNOWN"),
      now: NOW,
    });
    expect(unknownState.tileState).toBe("unk");

    const collectionFailed = healthLensAnswer({
      source: ok(healthSnapshot(FRESH_AT, [])),
      device: healthRow("dev-tango-04", "NORMAL", "FAILED"),
      now: NOW,
    });
    expect(collectionFailed.tileState).toBe("unk");
    expect(collectionFailed.summary).toMatch(/not proven/);
  });

  it("stale: NORMAL on a snapshot older than 72 h is UNKNOWN, while a stale CRITICAL still shows act", () => {
    const staleNormal = healthLensAnswer({
      source: ok(healthSnapshot(STALE_AT, [])),
      device: healthRow("dev-tango-04", "NORMAL"),
      now: NOW,
    });
    expect(staleNormal.tileState).toBe("unk");
    expect(staleNormal.summary).toMatch(/stale/);
    expect(staleNormal.evidenceAt).toBe(STALE_AT);

    const staleCritical = healthLensAnswer({
      source: ok(healthSnapshot(STALE_AT, [])),
      device: healthRow("dev-bravo-02", "CRITICAL"),
      now: NOW,
    });
    expect(staleCritical.tileState).toBe("act");
    expect(staleCritical.evidenceAt).toBe(STALE_AT);
  });
});

describe("buildEstateModel Health lens", () => {
  const devices: readonly DeviceSummary[] = [
    { device_id: "dev-tango-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04", model: "16200", software_version: null, ha_role: null, cluster_member_ref: null, backup_target: false, inventory_collected_at: FRESH_AT },
    { device_id: "dev-juliet-06", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-JULIET-06", model: "16200", software_version: null, ha_role: null, cluster_member_ref: null, backup_target: false, inventory_collected_at: FRESH_AT },
  ];

  it("answers the Health lens per unit from the snapshot and leaves Worst of all unchanged", () => {
    const withHealth = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: devices },
      overview: { state: "failed" },
      failover: { state: "failed" },
      backups: { state: "failed" },
      lifecycle: { state: "failed" },
      health: ok(healthSnapshot(FRESH_AT, [healthRow("dev-tango-04", "NORMAL"), healthRow("dev-juliet-06", "ATTENTION")])),
    });
    const without = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: devices },
      overview: { state: "failed" },
      failover: { state: "failed" },
      backups: { state: "failed" },
      lifecycle: { state: "failed" },
    });

    expect(withHealth.byId.get("dev-tango-04")!.lenses.health.tileState).toBe("ok");
    expect(withHealth.byId.get("dev-juliet-06")!.lenses.health.tileState).toBe("watch");
    expect(without.byId.get("dev-tango-04")!.lenses.health.tileState).toBe("unk");
    expect(withHealth.byId.get("dev-tango-04")!.lenses.worst.tileState).toBe(without.byId.get("dev-tango-04")!.lenses.worst.tileState);
  });
});
