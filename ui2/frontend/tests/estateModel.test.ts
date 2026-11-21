import { describe, expect, it } from "vitest";
import type {
  BackupArtefact,
  CpFailoverSummary,
  DeviceSummary,
  LifecycleFleet,
  OverviewView,
} from "../src/auth/adminApi";
import {
  ageBucketOf,
  buildEstateModel,
  lensProblemCount,
  sortUnitsForLens,
  TILE_STATE_RANK,
} from "../src/estate/estateModel";
import { estateEntryHrefFor, resolveEstateRoute } from "../src/estate/estateRouting";
import { ESTATE_DARK, ESTATE_LIGHT } from "../src/estate/estateTokens";

function luminance(hex: string): number {
  const channels = [1, 3, 5].map((index) => {
    const value = parseInt(hex.slice(index, index + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe.each([
  ["light", ESTATE_LIGHT],
  ["dark", ESTATE_DARK],
] as const)("Estate %s token contrast", (_mode, palette) => {
  it("keeps body inks >= 4.5:1 on both page and card surfaces", () => {
    for (const ink of ["ink", "ink2", "ink3"] as const) {
      expect(contrast(palette[ink], palette.bg), `${ink} on bg`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(palette[ink], palette.surface), `${ink} on surface`).toBeGreaterThanOrEqual(4.5);
    }
    expect(contrast(palette.accent, palette.accentInk), "accent button").toBeGreaterThanOrEqual(4.5);
  });

  it("keeps state badge text >= 4.5:1 against its badge container and card surface", () => {
    for (const [fg, bg] of [
      ["act", "actBg"],
      ["watch", "watchBg"],
      ["ok", "okBg"],
      ["unk", "unkBg"],
    ] as const) {
      expect(contrast(palette[fg], palette[bg]), `${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(palette[fg], palette.surface), `${fg} on surface`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("keeps tile fills >= 3.0:1 against card surface", () => {
    for (const tile of ["tAct", "tWatch", "tOk", "tUnk"] as const) {
      expect(contrast(palette[tile], palette.surface), `${tile} on surface`).toBeGreaterThanOrEqual(3.0);
    }
  });
});

describe("estateRouting", () => {
  it("routes slice 1 and slice 2 surfaces to Estate routes, honors standard=1, and routes Ask to the workbench and keeps Cases/Jobs on standard screens", () => {
    expect(resolveEstateRoute("overview", "/", "?screen=overview")).toEqual({ kind: "brief" });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&lens=failover")).toEqual({
      kind: "wall",
      lens: "failover",
      clusterRef: null,
    });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&device_id=dev-tango-04")).toEqual({
      kind: "dossier",
      deviceId: "dev-tango-04",
    });
    expect(resolveEstateRoute("inventory", "/devices/dev-juliet-06", "")).toEqual({
      kind: "dossier",
      deviceId: "dev-juliet-06",
    });

    // Slice 2 dedicated Estate screens
    expect(resolveEstateRoute("operations", "/", "?screen=operations&cluster_ref=CLS-ROMEO-01")).toEqual({
      kind: "failover",
      clusterRef: "CLS-ROMEO-01",
    });
    expect(resolveEstateRoute("changes", "/", "?screen=changes")).toEqual({
      kind: "changes",
      deviceId: null,
      vendor: null,
    });
    expect(resolveEstateRoute("compliance", "/", "?screen=compliance&device=dev-tango-04")).toEqual({
      kind: "compliance",
      deviceId: "dev-tango-04",
      family: null,
    });
    expect(resolveEstateRoute("backups", "/", "?screen=backups&q=TANGO")).toEqual({
      kind: "backups",
      query: "TANGO",
    });

    // Explicit standard=1 links open the Standard screen even when nx.view is estate
    expect(resolveEstateRoute("operations", "/", "?screen=operations&standard=1")).toBeNull();
    expect(resolveEstateRoute("backups", "/", "?screen=backups&standard=1")).toBeNull();
    expect(resolveEstateRoute("compliance", "/", "?screen=compliance&standard=1")).toBeNull();
    expect(resolveEstateRoute("changes", "/", "?screen=changes&standard=1")).toBeNull();

    // Out-of-scope screens fall through to standard UI
    expect(resolveEstateRoute("trace", "/", "?screen=trace")).toEqual({ kind: "ask", mode: "path" });
    expect(resolveEstateRoute("trace", "/", "?screen=trace&mode=lookup")).toEqual({ kind: "ask", mode: "lookup" });
    expect(resolveEstateRoute("trace", "/", "?screen=trace&mode=zones")).toEqual({ kind: "ask", mode: "zones" });
    expect(resolveEstateRoute("trace", "/", "?screen=trace&mode=path&standard=1")).toBeNull();
    expect(resolveEstateRoute("operations", "/", "?screen=operations&tab=jobs")).toBeNull();
    expect(resolveEstateRoute("packet_analysis", "/", "?screen=packet_analysis")).toBeNull();
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&view=lifecycle")).toBeNull();
  });

  it("maps standard screens to matching Estate screens or lenses when switching View to Estate", () => {
    expect(estateEntryHrefFor("overview", "/", "?screen=overview")).toBeNull();
    expect(estateEntryHrefFor("inventory", "/", "?screen=inventory")).toBeNull();
    expect(estateEntryHrefFor("backups", "/", "?screen=backups")).toBeNull();
    expect(estateEntryHrefFor("compliance", "/", "?screen=compliance")).toBeNull();
    expect(estateEntryHrefFor("operations", "/", "?screen=operations")).toBeNull();
    expect(estateEntryHrefFor("changes", "/", "?screen=changes")).toBeNull();
    expect(estateEntryHrefFor("operations", "/", "?screen=operations&cluster_ref=CLS-ROMEO-01&standard=1")).toBe(
      "?screen=operations&cluster_ref=CLS-ROMEO-01",
    );
    expect(estateEntryHrefFor("inventory", "/", "?screen=inventory&view=lifecycle")).toBe(
      "?screen=inventory&lens=exposure",
    );
    expect(estateEntryHrefFor("trace", "/", "?screen=trace")).toBeNull();
    expect(estateEntryHrefFor("trace", "/", "?screen=trace&mode=lookup&standard=1")).toBe("?screen=trace&mode=lookup");
    expect(estateEntryHrefFor("cutover", "/", "?screen=cutover")).toBe("?screen=overview");
  });
});

const NOW = new Date("2026-11-19T12:00:00Z");

const SYNTHETIC_DEVICES: readonly DeviceSummary[] = [
  {
    device_id: "dev-tango-04",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04",
    model: "16200",
    software_version: "R81.20",
    ha_role: "STANDBY",
    cluster_member_ref: "CLS-ROMEO-01",
    backup_target: true,
    inventory_collected_at: "2026-11-19T10:00:00Z", // 2 h ago -> fresh
    policy_name: "POL-CORE-01",
    policy_installed_at: "2026-11-18T22:00:00Z",
    policy_read_at: "2026-11-19T10:00:00Z",
  },
  {
    device_id: "dev-tango-03",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-03",
    model: "16200",
    software_version: "R81.20",
    ha_role: "ACTIVE",
    cluster_member_ref: "CLS-ROMEO-01",
    backup_target: true,
    inventory_collected_at: "2026-11-19T02:00:00Z", // 10 h ago -> day
    policy_name: "POL-CORE-02",
    policy_installed_at: "2026-11-19T01:00:00Z",
    policy_read_at: "2026-11-19T02:00:00Z",
  },
  {
    device_id: "dev-juliet-06",
    vendor_hint: "palo_alto",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-JULIET-06",
    model: "PA-5420",
    software_version: "11.1.10-h7",
    ha_role: null,
    cluster_member_ref: null,
    backup_target: true,
    inventory_collected_at: "2026-11-15T10:00:00Z", // > 72 h -> stale
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
    backup_target: false,
    inventory_collected_at: null, // never read -> unk
  },
];

const SYNTHETIC_OVERVIEW: OverviewView = {
  generated_at: "2026-11-19T11:58:00Z",
  masked: true,
  denominators: { active_devices: 4, enrolled_devices: 4, gateways: 3, clusters: 1, backup_targets: 3 },
  evidence: {
    inventory: { at: "2026-11-19T10:00:00Z", state: "OK" },
    configuration: { at: "2026-11-19T09:00:00Z", state: "OK" },
    compliance: { at: "2026-11-19T08:00:00Z", state: "OK" },
    backup: { at: "2026-11-19T02:00:00Z", state: "OK" },
    jobs: { at: "2026-11-19T11:50:00Z", state: "OK" },
    platform_facts: { at: "2026-11-19T10:00:00Z", state: "OK" },
  },
  attention: {
    failed_jobs_24h: { count: 0, terminal_24h: 120, previous: 2, state: "OK" },
    stale_inventory: { count: 2, of: 4, state: "OK" },
    cluster_diff: { count: 1, of: 1, previous: 1, state: "OK" },
    config_changed: { count: 1, of: 3, previous: 0, state: "OK" },
    backup_missing: { count: 1, of: 3, previous: 2, state: "OK" },
  },
  exceptions: {
    failed_jobs: { total: 0, rows: [] },
    config_changes: { total: 1, rows: [] },
    cluster_diff: {
      total: 1,
      unknown: 0,
      all_refs: ["CLS-ROMEO-01"],
      rows: [{ cluster_ref: "CLS-ROMEO-01", diff_section_count: 2, diff_setting_count: 5, diff_sections: ["ntp", "snmp"], computed_at: "2026-11-19T09:00:00Z" }],
    },
  },
  inventory_age: { lt24h: 2, h24_72: 0, gt72h: 1, never: 1, of: 4, state: "OK" },
  compliance: {
    state: "OK",
    evaluated: 3,
    of_firewalls: 3,
    coverage_pct: 80,
    critical_deficiencies: 2,
    data_gaps: 4,
    frameworks: [{ name: "CIS Benchmarks", pass: 20, fail: 5, unavailable: 4, total: 29, score_pct: 80 }],
  },
  platform: { devices: 4, check_point: 2, palo_alto: 1, clusters: 1, hotfix_levels: [], evidence_at: "2026-11-19T10:00:00Z" },
  policy_install: { state: "OK", of: 3, today: 2, yesterday: 0, days_2_7: 0, older: 0, unknown: 1, last_read_at: "2026-11-19T02:00:00Z" },
  nexus: { completed_24h: 120, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: "2026-11-19T10:00:00Z", palo_alto: "2026-11-15T10:00:00Z" } },
  estate: {
    compliance_state: "OK",
    no_archive: 1,
    counts: { critical: 1, ageing: 0, partly_assessed: 1, not_assessed: 1, clear: 1 },
    facts: { critical: { k: 1, n: 3 }, backups: { a: 2, b: 3 }, evidence: { r: 2, d: 4, never: 1 } },
    devices: [
      { device_id: "dev-tango-03", hostname: "FW-TANGO-03", vendor: "check_point", model: "16200", condition: "clear", no_archive: false, critical_fail: 0, last_read_at: "2026-11-19T02:00:00Z", backup_target: true, last_backup_at: "2026-11-19T02:00:00Z" },
      { device_id: "dev-tango-04", hostname: "FW-TANGO-04", vendor: "check_point", model: "16200", condition: "critical", no_archive: false, critical_fail: 2, last_read_at: "2026-11-19T10:00:00Z", backup_target: true, last_backup_at: "2026-11-19T02:00:00Z" },
      { device_id: "dev-juliet-06", hostname: "FW-JULIET-06", vendor: "palo_alto", model: "PA-5420", condition: "partly_assessed", no_archive: true, critical_fail: 0, last_read_at: "2026-11-15T10:00:00Z", backup_target: true, last_backup_at: null },
      { device_id: "dev-bravo-02", hostname: "DEV-BRAVO-02", vendor: "infoblox", model: "IB-2225", condition: "not_assessed", condition_reason: "no_critical_control", no_archive: false, critical_fail: null, last_read_at: null, backup_target: false, last_backup_at: null },
    ],
  },
};

const SYNTHETIC_FAILOVER: readonly CpFailoverSummary[] = [
  {
    unitId: "cls-romeo-01",
    clusterId: "cls-romeo-01",
    cluster_member_ref: "CLS-ROMEO-01",
    vendor: "check_point",
    activeWindow: true,
    lastRunState: "DONE",
    lastRunOutcome: "SUCCEEDED",
    lastRunAt: "2026-11-19T08:00:00Z",
    canRunReadiness: true,
    mode: "ACTIVE_STANDBY",
    readiness: {
      status: "NOT_READY",
      observedAt: "2026-11-19T08:00:00Z",
      failedCheck: "Installed policy differs between members",
      checks: [
        { checkNo: 1, title: "Policy parity", member: "CLS-ROMEO-01", result: "FAIL", status: "FAIL", summary: "POL-CORE-02 vs POL-CORE-01", blocking: true, derived: {} },
      ],
    },
  },
];

const SYNTHETIC_BACKUPS: readonly BackupArtefact[] = [
  {
    artefact_id: "art-1",
    device_id: "dev-tango-03",
    collected_at: "2026-11-19T02:00:00Z",
    size_bytes: 120 * 1024 * 1024,
    digest_prefix: "a1b2c3d4",
    validation_level: "V3",
    deviation_state: "unchanged",
  },
  {
    artefact_id: "art-2",
    device_id: "dev-tango-04",
    collected_at: "2026-11-19T02:05:00Z",
    size_bytes: 122 * 1024 * 1024,
    digest_prefix: "e5f6a7b8",
    validation_level: "V3",
    deviation_state: "unchanged",
  },
];

const SYNTHETIC_LIFECYCLE: LifecycleFleet = {
  as_of: "2026-11-19T09:00:00Z",
  summary: {},
  devices: [
    {
      device_id: "dev-tango-03",
      hostname: "FW-TANGO-03",
      cluster_member_ref: "CLS-ROMEO-01",
      vendor: "check_point",
      model: "16200",
      software_version: "R81.20",
      hardware: { status: "MATCHED", reason: null, basis: "model", catalog: null },
      software: { status: "MATCHED", reason: null, basis: "version", catalog: null },
      status: "OK",
      risk: "LOW",
      support_days: 600,
      next_milestone: { name: "Software EOS", date: "2028-08-31", days_remaining: 650 },
      nearest_license: null,
      licenses: [],
      license_status: "OK",
      support_contract_status: "OK",
      vulnerabilities: {
        state: "EVALUATED",
        affected: 0,
        affected_exploited: 0,
        affected_critical_high: 0,
        compared: { source: "checkpoint", advisories: 120, oldest: null, newest: null, loaded_at: "2026-11-19T07:00:00Z", age_days: 0 },
      },
    },
    {
      device_id: "dev-tango-04",
      hostname: "FW-TANGO-04",
      cluster_member_ref: "CLS-ROMEO-01",
      vendor: "check_point",
      model: "16200",
      software_version: "R81.20",
      hardware: { status: "MATCHED", reason: null, basis: "model", catalog: null },
      software: { status: "MATCHED", reason: null, basis: "version", catalog: null },
      status: "OK",
      risk: "HIGH",
      support_days: 600,
      next_milestone: null,
      nearest_license: null,
      licenses: [],
      license_status: "OK",
      support_contract_status: "OK",
      vulnerabilities: {
        state: "EVALUATED",
        affected: 2,
        affected_exploited: 1,
        affected_critical_high: 2,
        compared: { source: "checkpoint", advisories: 120, oldest: null, newest: null, loaded_at: "2026-11-19T07:00:00Z", age_days: 0 },
      },
    },
  ],
};

describe("buildEstateModel", () => {
  it("joins HA pairs in active-first order, evaluates every lens with an evidence timestamp, and keeps UNKNOWN distinct", () => {
    const model = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: SYNTHETIC_DEVICES },
      overview: { state: "ok", value: SYNTHETIC_OVERVIEW },
      failover: { state: "ok", value: SYNTHETIC_FAILOVER },
      backups: { state: "ok", value: { backups: SYNTHETIC_BACKUPS } },
      lifecycle: { state: "ok", value: SYNTHETIC_LIFECYCLE },
    });

    expect(model.units).toHaveLength(4);
    expect(model.clusters).toHaveLength(1);

    const cl = model.clusters[0];
    expect(cl.ref).toBe("CLS-ROMEO-01");
    expect(cl.pairState).toBe("act");
    // Active member (FW-TANGO-03) ordered before Standby (FW-TANGO-04)
    expect(cl.members.map((m) => m.name)).toEqual(["FW-TANGO-03", "FW-TANGO-04"]);

    const juliet = model.byId.get("dev-juliet-06")!;
    expect(juliet.lenses.backups.tileState).toBe("act");
    expect(juliet.lenses.compliance.tileState).toBe("unk");
    expect(juliet.lenses.age.tileState).toBe("stale");
    expect(juliet.lenses.failover.tileState).toBe("na");

    const bravo = model.byId.get("dev-bravo-02")!;
    expect(bravo.lenses.age.tileState).toBe("unk");
    expect(bravo.lenses.backups.tileState).toBe("na");
    expect(bravo.lenses.compliance.tileState).toBe("na");

    // Policy parity difference between FW-TANGO-03 and FW-TANGO-04 surfaces as watch on install lens
    const tango3 = model.byId.get("dev-tango-03")!;
    expect(tango3.lenses.install.tileState).toBe("watch");

    // Problem counts per lens
    expect(lensProblemCount(model.units, "failover")).toBe(2); // 2 members in NOT_READY cluster
    expect(lensProblemCount(model.units, "backups")).toBe(1); // FW-JULIET-06 missing archive
    expect(lensProblemCount(model.units, "age")).toBe(2); // 1 stale + 1 never read
  });

  it("sorts UNKNOWN with problems (after Act, before Review and Fine), never with Fine", () => {
    expect(TILE_STATE_RANK.act).toBeLessThan(TILE_STATE_RANK.unk);
    expect(TILE_STATE_RANK.unk).toBeLessThan(TILE_STATE_RANK.watch);
    expect(TILE_STATE_RANK.watch).toBeLessThan(TILE_STATE_RANK.ok);

    const model = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: SYNTHETIC_DEVICES },
      overview: { state: "ok", value: SYNTHETIC_OVERVIEW },
      failover: { state: "ok", value: SYNTHETIC_FAILOVER },
      backups: { state: "ok", value: { backups: SYNTHETIC_BACKUPS } },
      lifecycle: { state: "ok", value: SYNTHETIC_LIFECYCLE },
    });

    const sortedCompliance = sortUnitsForLens(model.units, "compliance", {
      onlyAttention: false,
      query: "",
      vendor: "",
    });
    // FW-TANGO-04 (act) -> FW-JULIET-06 (unk) -> FW-TANGO-03 (ok) -> DEV-BRAVO-02 (na)
    expect(sortedCompliance.map((u) => u.name)).toEqual([
      "FW-TANGO-04",
      "FW-JULIET-06",
      "FW-TANGO-03",
      "DEV-BRAVO-02",
    ]);

    const onlyAttn = sortUnitsForLens(model.units, "compliance", {
      onlyAttention: true,
      query: "",
      vendor: "",
    });
    // Keeps Act and UNKNOWN, drops Fine and Not applicable
    expect(onlyAttn.map((u) => u.name)).toEqual(["FW-TANGO-04", "FW-JULIET-06"]);
  });

  it("fails closed to UNKNOWN when failover or lifecycle reads fail", () => {
    const model = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: SYNTHETIC_DEVICES },
      overview: { state: "ok", value: SYNTHETIC_OVERVIEW },
      failover: { state: "failed" },
      backups: { state: "failed" },
      lifecycle: { state: "failed" },
    });

    const tango4 = model.byId.get("dev-tango-04")!;
    expect(tango4.lenses.failover.tileState).toBe("unk");
    expect(tango4.lenses.exposure.tileState).toBe("unk");
    expect(model.clusters[0].pairState).toBe("unk");
  });

  it("buckets evidence ages accurately", () => {
    expect(ageBucketOf(null, NOW)).toBe("never");
    expect(ageBucketOf("2026-11-19T10:30:00Z", NOW)).toBe("fresh");
    expect(ageBucketOf("2026-11-19T01:00:00Z", NOW)).toBe("day");
    expect(ageBucketOf("2026-11-17T12:00:00Z", NOW)).toBe("old");
    expect(ageBucketOf("2026-11-14T12:00:00Z", NOW)).toBe("stale");
  });
});
