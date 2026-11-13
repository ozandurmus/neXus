import { describe, expect, it } from "vitest";

import type { ChangeFeedPage, CpFailoverSummary, DeviceSummary, LifecycleDevice, LifecycleFleet, OverviewView } from "../src/auth/adminApi";
import {
  actorUnknown, buildAttention, compareRows, licenseState, RULES, severityCounts, visibleRows, VISIBLE_ROWS,
  type AttentionInputs, type AttentionRow, type SourceRead,
} from "../src/screens/overview/attentionRules";
import { sinceText, whereText } from "../src/screens/overview/AttentionList";

/** Synthetic fleet; names are masked pseudonyms only. */
const NOW = new Date("2026-11-13T09:00:00Z");
const ago = (h: number) => new Date(NOW.getTime() - h * 3_600_000).toISOString();
const days = (d: number) => ago(d * 24);

type EstateDevice = NonNullable<OverviewView["estate"]>["devices"][number];
const dev = (n: number, extra: Partial<EstateDevice> = {}): EstateDevice => ({
  device_id: `d${n}`, hostname: `FW-TANGO-${String(n).padStart(2, "0")}`, vendor: "check_point", model: null, condition: "clear",
  no_archive: false, critical_fail: 0, last_read_at: ago(1), backup_target: true, last_backup_at: ago(10), ...extra,
});

function overview(devices: EstateDevice[], extra: Partial<OverviewView> = {}): OverviewView {
  return {
    generated_at: NOW.toISOString(), masked: true,
    denominators: { active_devices: devices.length, gateways: devices.length, clusters: 0, backup_targets: devices.length },
    evidence: { inventory: { at: ago(1), state: "OK" }, configuration: { at: ago(1), state: "OK" }, compliance: { at: ago(2), state: "OK" },
      backup: { at: ago(3), state: "OK" }, jobs: { at: ago(1), state: "OK" }, platform_facts: { at: ago(1), state: "OK" } },
    attention: { failed_jobs_24h: { count: 0, terminal_24h: 0, state: "OK" }, stale_inventory: { count: 0, state: "OK" },
      cluster_diff: { count: 0, state: "OK" }, config_changed: { count: 0, state: "OK" }, backup_missing: { count: 0, state: "OK" } },
    exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] }, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } },
    inventory_age: { lt24h: devices.length, h24_72: 0, gt72h: 0, never: 0, of: devices.length },
    compliance: { state: "OK" },
    platform: { devices: devices.length, clusters: 0, evidence_at: ago(1) },
    nexus: {},
    estate: { devices, counts: { critical: 0, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: devices.length }, no_archive: 0, compliance_state: "OK",
      facts: { critical: { k: 0, n: devices.length }, backups: { a: devices.length, b: devices.length }, evidence: { r: devices.length, d: devices.length, never: 0 } } },
    ...extra,
  } as OverviewView;
}

const OK_EMPTY_FAILOVER: SourceRead<{ devices: DeviceSummary[]; rows: CpFailoverSummary[] }> = { state: "ok", value: { devices: [], rows: [] } };
const OK_EMPTY_LIFECYCLE: SourceRead<LifecycleFleet> = { state: "ok", value: { as_of: NOW.toISOString(), devices: [], summary: {} } };
const OK_EMPTY_CHANGES = { state: "ok", value: { events: [] } as unknown as ChangeFeedPage } as SourceRead<ChangeFeedPage>;

function inputs(o: OverviewView, extra: Partial<AttentionInputs> = {}): AttentionInputs {
  return { now: NOW, overview: o, failover: OK_EMPTY_FAILOVER, lifecycle: OK_EMPTY_LIFECYCLE, changes: OK_EMPTY_CHANGES, controls: [], ...extra };
}
const keys = (rows: readonly AttentionRow[]) => rows.map((r) => r.key);
const find = (rows: readonly AttentionRow[], key: string) => rows.find((r) => r.key === key)!;

const life = (n: number, extra: Partial<LifecycleDevice> = {}): LifecycleDevice => ({
  device_id: `d${n}`, hostname: `FW-TANGO-${String(n).padStart(2, "0")}`, cluster_member_ref: null, vendor: "check_point", model: null,
  software_version: "R81.20", hardware: {} as LifecycleDevice["hardware"], software: {} as LifecycleDevice["software"], status: "OK", risk: "LOW",
  support_days: null, next_milestone: null, nearest_license: null, licenses: [], license_status: "OK", support_contract_status: "OK", ...extra,
});
const lifecycle = (devices: LifecycleDevice[]): SourceRead<LifecycleFleet> => ({ state: "ok", value: { as_of: NOW.toISOString(), devices, summary: {} } });

describe("attention rules: overview sources", () => {
  it("is empty for a clean fleet", () => {
    const r = buildAttention(inputs(overview([dev(1), dev(2)])), undefined);
    expect(r.rows).toEqual([]);
    expect(r.evaluatedAt).toBe(NOW.toISOString());
    expect(r.sources.every((s) => s.state === "ok")).toBe(true);
  });

  it("backup-missing: a backup target with no stored archive is Act now, never backed up", () => {
    const row = find(buildAttention(inputs(overview([dev(1, { no_archive: true, last_backup_at: null }), dev(2)])), undefined).rows, "backup-missing");
    expect(row).toMatchObject({ severity: "critical", names: ["FW-TANGO-01"], count: 1, at: null, href: "?screen=backups&artefact=none" });
    expect(sinceText(row, NOW)).toBe("never backed up");
  });

  it("backup-stale: the newest archive older than 7 days is Review; 6 days is not", () => {
    const rows = buildAttention(inputs(overview([dev(1, { last_backup_at: days(9) }), dev(2, { last_backup_at: days(6) }), dev(3, { last_backup_at: days(8) })])), undefined).rows;
    const row = find(rows, "backup-stale");
    expect(row).toMatchObject({ severity: "serious", count: 2, names: ["FW-TANGO-01", "FW-TANGO-03"], at: days(9) });
    expect(sinceText(row, NOW)).toBe("oldest backup 9 d ago");
  });

  it("backup-stale: a device that is not a backup target, or an older backend without the field, never counts", () => {
    const rows = buildAttention(inputs(overview([dev(1, { backup_target: false, last_backup_at: days(30) }), dev(2, { last_backup_at: undefined })])), undefined).rows;
    expect(keys(rows)).not.toContain("backup-stale");
  });

  it("compliance-critical: a firewall failing a critical check is Act now, with the most widespread control as detail", () => {
    const o = overview([dev(1, { critical_fail: 1 }), dev(2, { critical_fail: 4 }), dev(3)]);
    const row = find(buildAttention(inputs(o, { controls: [
      { control_id: "c1", title: "Password policy", severity: "CRITICAL", fail_count: 2, target_device_count: 3 },
      { control_id: "c2", title: "High thing", severity: "HIGH", fail_count: 9, target_device_count: 9 },
    ] as unknown as AttentionInputs["controls"] }), undefined).rows, "compliance-critical");
    expect(row).toMatchObject({ severity: "critical", count: 2, names: ["FW-TANGO-02", "FW-TANGO-01"], at: ago(2),
      detail: "most widespread: Password policy (2 of 3 firewalls)" });
  });

  it("compliance-critical: a cold evaluation is one UNKNOWN row, not a clean bill", () => {
    const o = overview([dev(1, { critical_fail: null })]);
    const rows = buildAttention(inputs({ ...o, estate: { ...o.estate!, compliance_state: "UNKNOWN" } }), undefined).rows;
    expect(keys(rows)).toEqual(["unknown-compliance"]);
  });

  it("failed-jobs: Review, the latest devices named as a sample, the most common cause as detail", () => {
    const o = overview([dev(1)], {
      attention: { ...overview([]).attention, failed_jobs_24h: { count: 7, terminal_24h: 20, state: "OK", last_at: ago(0.5) } },
      exceptions: { ...overview([]).exceptions, failed_jobs: { total: 7, rows: [
        { job_id: "j1", job_type: "backup", device_id: "d1", label: "FW-TANGO-01", terminal_reason: "connect_failed", finished_at: ago(0.5) },
        { job_id: "j2", job_type: "backup", device_id: "d1", label: "FW-TANGO-01", terminal_reason: "connect_failed", finished_at: ago(1) },
        { job_id: "j3", job_type: "inventory", device_id: "d9", label: "FW-MIKE-09", terminal_reason: "timeout", finished_at: ago(2) },
      ], reasons: [{ reason: "timeout", count: 2, devices: 1, job_types: [], last_at: ago(2) }, { reason: "connect_failed", count: 5, devices: 2, job_types: [], last_at: ago(0.5) }] } },
    });
    const row = find(buildAttention(inputs(o), undefined).rows, "failed-jobs");
    expect(row).toMatchObject({ severity: "serious", count: 7, names: ["FW-TANGO-01", "FW-MIKE-09"], detail: "most common cause: connect_failed (5)" });
    expect(whereText(row)).toBe("latest on FW-TANGO-01, FW-MIKE-09");
    expect(row.href).toBe("?screen=operations&tab=jobs&state=FAILED&since_hours=24");
  });

  it("not-read and read-ageing follow the one freshness scale (24 h / 72 h)", () => {
    const rows = buildAttention(inputs(overview([dev(1, { last_read_at: ago(23) }), dev(2, { last_read_at: ago(30) }), dev(3, { last_read_at: ago(80) }),
      dev(4, { last_read_at: null })])), undefined).rows;
    expect(find(rows, "not-read")).toMatchObject({ severity: "serious", count: 2, names: ["FW-TANGO-04", "FW-TANGO-03"], detail: "1 never read", at: ago(80) });
    expect(find(rows, "not-read").what).toBe("2 devices not read for over 72 h");
    expect(find(rows, "read-ageing")).toMatchObject({ severity: "warning", count: 1, names: ["FW-TANGO-02"] });
  });

  it("not-read: devices that were never read say so", () => {
    const row = find(buildAttention(inputs(overview([dev(1, { last_read_at: null })])), undefined).rows, "not-read");
    expect(row.what).toBe("1 device has never been read");
    expect(sinceText(row, NOW)).toBe("never read");
  });

  it("lists at most three names, then 'and N more'", () => {
    const row = find(buildAttention(inputs(overview([1, 2, 3, 4, 5].map((n) => dev(n, { no_archive: true })))), undefined).rows, "backup-missing");
    expect(row.names).toHaveLength(3);
    expect(whereText(row)).toBe("FW-TANGO-01, FW-TANGO-02, FW-TANGO-03 and 2 more");
  });
});

describe("attention rules: failover, lifecycle and change sources", () => {
  const member = (ref: string, n: number, vendor = "check_point"): DeviceSummary => ({
    device_id: `${ref}-${n}`, vendor_hint: vendor, enrollment_state: "ENROLLED", hostname: `FW-${ref}-${n}`, model: null, software_version: null,
    ha_role: null, cluster_member_ref: ref,
  } as DeviceSummary);
  const unit = (ref: string, status: "READY" | "NOT_READY" | "UNKNOWN", at: string, failedCheck = "") => ({
    clusterId: ref, unitId: ref, cluster_member_ref: ref, readiness: { status, observedAt: at, failedCheck, checks: [] },
  } as unknown as CpFailoverSummary);

  it("ha-not-ready is Act now with the failed check; ha-unknown is Watch; an unsupported vendor is neither", () => {
    const failover = { state: "ok" as const, value: {
      devices: [member("CLS-ROMEO-01", 1), member("CLS-TANGO-04", 1), member("CLS-ECHO-02", 1), member("CLS-ASA-01", 1, "cisco_asa")],
      rows: [unit("CLS-ROMEO-01", "NOT_READY", ago(2), "sync_interface"), unit("CLS-TANGO-04", "READY", ago(1)), unit("CLS-ECHO-02", "UNKNOWN", ago(5))],
    } };
    const rows = buildAttention(inputs(overview([dev(1)]), { failover }), undefined).rows;
    expect(find(rows, "ha-not-ready")).toMatchObject({ severity: "critical", names: ["CLS-ROMEO-01"], at: ago(2), detail: "failed check: sync_interface" });
    expect(find(rows, "ha-unknown")).toMatchObject({ severity: "warning", names: ["CLS-ECHO-02"] });
    expect(rows.some((r) => r.names.includes("CLS-ASA-01"))).toBe(false);
  });

  it("advisory-exploited is Act now; advisory-critical-high is Review; a device not evaluated is neither", () => {
    const loaded = ago(5);
    const v = (extra: object) => ({ state: "EVALUATED", compared: { source: "s", advisories: 10, oldest: null, newest: null, loaded_at: loaded, age_days: 0 }, ...extra });
    const rows = buildAttention(inputs(overview([dev(1), dev(2), dev(3)]), { lifecycle: lifecycle([
      life(1, { vulnerabilities: v({ affected_exploited: 1, affected_critical_high: 2 }) as LifecycleDevice["vulnerabilities"] }),
      life(2, { vulnerabilities: v({ affected_exploited: 0, affected_critical_high: 3 }) as LifecycleDevice["vulnerabilities"] }),
      life(3, { vulnerabilities: { state: "NOT_EVALUATED", affected_critical_high: 9 } }),
    ]) }), undefined).rows;
    expect(find(rows, "advisory-exploited")).toMatchObject({ severity: "critical", names: ["FW-TANGO-01"], at: loaded });
    expect(find(rows, "advisory-critical-high")).toMatchObject({ severity: "serious", names: ["FW-TANGO-02"], detail: "3 advisory matches in all" });
  });

  it("licenses: expired and within 30 days are Review, 31-60 days Watch; permanent and far ones never count", () => {
    const lic = (label: string, d: number | null, status = "DATED") => ({ label, status, days_remaining: d, expiry: d === null ? null : days(-d).slice(0, 10) });
    const rows = buildAttention(inputs(overview([1, 2, 3, 4, 5].map((n) => dev(n))), { lifecycle: lifecycle([
      life(1, { licenses: [lic("Support contract", -3, "EXPIRED"), lic("Threat Prevention", 10)] }),
      life(2, { licenses: [lic("Threat Prevention", 9)] }),
      life(3, { licenses: [lic("URL Filtering", 45)] }),
      life(4, { licenses: [lic("Base", null, "PERMANENT"), lic("IPS", 200)] }),
      life(5, { licenses: [lic("IPS", 60)] }),
    ]) }), undefined).rows;
    expect(find(rows, "license-expired")).toMatchObject({ severity: "serious", names: ["FW-TANGO-01"], atKind: "date", detail: "latest: Support contract" });
    expect(find(rows, "license-30")).toMatchObject({ severity: "serious", names: ["FW-TANGO-02"], detail: "nearest: Threat Prevention, in 9 days" });
    expect(find(rows, "license-60")).toMatchObject({ severity: "warning", names: ["FW-TANGO-03", "FW-TANGO-05"] });
    // a device is counted once, in its worst bucket
    expect(rows.filter((r) => r.names.includes("FW-TANGO-01"))).toHaveLength(1);
    expect(sinceText(find(rows, "license-30"), NOW)).toMatch(/^nearest expires \d{4}-\d{2}-\d{2}$/);
  });

  it("licenseState: an EXPIRED status counts even without a date", () => {
    expect(licenseState(life(1, { licenses: [{ label: "X", status: "EXPIRED", days_remaining: null }] }))?.state).toBe("expired");
    expect(licenseState(life(1, { licenses: [{ label: "X", status: "UNKNOWN", days_remaining: null }] }))).toBeNull();
  });

  it("live-patch-missing: a Check Point device reporting no Live Patch package is Watch; other vendors never", () => {
    const lp = { package_take: null, jumbo: null, read_outcome: "OK", observed_at: ago(6), armed: 0, ready: 0, jumbofix: 0, other: 0, items: [], missing: true };
    const rows = buildAttention(inputs(overview([dev(1), dev(2)]), { lifecycle: lifecycle([
      life(1, { live_patch: lp }), life(2, { vendor: "palo_alto", live_patch: lp }),
    ]) }), undefined).rows;
    expect(find(rows, "live-patch-missing")).toMatchObject({ severity: "warning", names: ["FW-TANGO-01"], at: ago(6) });
  });

  it("lifecycle rows only count enrolled devices (the estate list)", () => {
    const rows = buildAttention(inputs(overview([dev(1)]), { lifecycle: lifecycle([
      life(7, { licenses: [{ label: "X", status: "EXPIRED", days_remaining: -1 }] }),
    ]) }), undefined).rows;
    expect(rows).toEqual([]);
  });

  it("change-unknown-actor: user changes whose actor is unknown are Watch; named actors and job events are not", () => {
    const ev = (actor: string | null, kind = "USER", name = "FW-TANGO-01", at = ago(3)) => ({ key: `${actor}${at}`, kind, actor, device_name: name, device_id: "d1", time: at });
    const changes = { state: "ok" as const, value: { events: [
      ev(null), ev("UNKNOWN (observed by collection)", "USER", "FW-BRAVO-02", ago(1)), ev("admin-07"), ev(null, "NEXUS_JOB"),
    ] } as unknown as ChangeFeedPage };
    const row = find(buildAttention(inputs(overview([dev(1)]), { changes }), undefined).rows, "change-unknown-actor");
    expect(row).toMatchObject({ severity: "warning", count: 2, names: ["FW-BRAVO-02", "FW-TANGO-01"], at: ago(1), href: "?screen=changes&view=feed" });
    expect(actorUnknown("  unknown")).toBe(true);
    expect(actorUnknown("ops-user")).toBe(false);
  });
});

describe("attention rules: ordering, roles and display", () => {
  const busy = () => {
    const o = overview([dev(1, { no_archive: true }), dev(2, { critical_fail: 2 }), dev(3, { last_read_at: ago(30) }), dev(4, { last_read_at: ago(100) })]);
    return inputs(o, { lifecycle: { state: "failed" }, changes: { state: "ok", value: { events: [
      { key: "k", kind: "USER", actor: null, device_name: "FW-TANGO-01", device_id: "d1", time: ago(2) },
    ] } as unknown as ChangeFeedPage } });
  };

  it("orders by severity (Act now, Review, UNKNOWN, Watch), then rule rank", () => {
    expect(keys(buildAttention(busy(), undefined).rows)).toEqual([
      "backup-missing", "compliance-critical", "not-read", "unknown-lifecycle", "read-ageing", "change-unknown-actor",
    ]);
  });

  it("within one rank, more affected objects first, then newest evidence first", () => {
    const row = (count: number, at: string | null): AttentionRow => ({ key: "not-read", severity: "serious", rank: 8, what: "", names: [], more: 0, count,
      detail: null, at, atKind: "age", atLabel: "", href: "", linkLabel: "" });
    expect([row(1, ago(1)), row(3, ago(5)), row(1, ago(0.1))].sort(compareRows).map((r) => r.count + ":" + r.at))
      .toEqual([`3:${ago(5)}`, `1:${ago(0.1)}`, `1:${ago(1)}`]);
  });

  it("keeps the rule table ranks unique and in one order", () => {
    const ranks = Object.values(RULES).map((r) => r.rank);
    expect(new Set(ranks).size).toBe(ranks.length);
    expect(ranks).toEqual([...ranks].sort((a, b) => a - b));
  });

  it("shows a row only when the session may open its target screen", () => {
    // a viewer with Devices only: no compliance row, no change row, no Policy UNKNOWN; backups and devices stay
    expect(keys(buildAttention(busy(), ["Devices"]).rows)).toEqual(["backup-missing", "not-read", "unknown-lifecycle", "read-ageing"]);
    // a compliance-only session: no Devices-plane rows (device reads, lifecycle)
    expect(keys(buildAttention(busy(), ["Compliance"]).rows)).toEqual(["backup-missing", "compliance-critical"]);
    // no plane list at all (tests, previews): unrestricted, as in the navigation
    expect(buildAttention(busy(), []).rows).toHaveLength(6);
  });

  it("a refused or unread source gives no row; a failed one gives one UNKNOWN row", () => {
    const base = inputs(overview([dev(1)]));
    expect(buildAttention({ ...base, lifecycle: { state: "restricted" }, changes: { state: "skipped" } }, undefined).rows).toEqual([]);
    const failed = buildAttention({ ...base, failover: { state: "failed" }, changes: { state: "failed" } }, undefined);
    expect(keys(failed.rows)).toEqual(["unknown-failover", "unknown-changes"]);
    expect(failed.sources.find((s) => s.key === "changes")?.state).toBe("failed");
  });

  it("shows the first rows and keeps every UNKNOWN row visible", () => {
    const r = (i: number, severity: AttentionRow["severity"]): AttentionRow => ({ key: `unknown-overview`, severity, rank: i, what: `${i}`, names: [], more: 0,
      count: 1, detail: null, at: null, atKind: "age", atLabel: "", href: "", linkLabel: "" });
    const rows = [...Array.from({ length: VISIBLE_ROWS + 2 }, (_, i) => r(i, "critical")), r(99, "unknown")];
    expect(visibleRows(rows, false)).toHaveLength(VISIBLE_ROWS + 1);
    expect(visibleRows(rows, false).at(-1)?.severity).toBe("unknown");
    expect(visibleRows(rows, true)).toHaveLength(rows.length);
    expect(severityCounts(rows)).toEqual([{ severity: "critical", count: VISIBLE_ROWS + 2 }, { severity: "unknown", count: 1 }]);
  });
});
