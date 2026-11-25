import type {
  ChangeFeedPage, ComplianceControlItem, CpFailoverSummary, DeviceSummary, LifecycleDevice, LifecycleFleet, OverviewView,
} from "../../auth/adminApi";
import { planeGranted } from "../../shell/actionAccess";
import { AGEING_UNTIL_MS, FRESH_UNDER_MS } from "../../shell/freshness";
import { plural } from "../../shell/plural";
import { groupClusters, haReadinessItems } from "../HaReadinessList";
import { parseZoneMatrixAttention, type ZoneMatrixAttentionSummary } from "./zoneMatrixAttention";

/**
 * The Overview attention list: "what should I look at today" (PO direction 2026-11-13). Every rule, threshold,
 * severity, target screen and role plane lives here and only here; the design note is
 * docs/design/OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md (DRAFT, §3 is this table).
 *
 * Pure functions over reads the app already makes (overview, device list, failover summary, lifecycle, change feed).
 * A source that could not be read gives one UNKNOWN row, never a silent zero; a source the server refused for this
 * role gives nothing. Names are the ones the server sent (`hostname`, `cluster_member_ref`), which it masks for a
 * masked session; this module never invents or unmasks a name.
 */

export type AttentionSeverity = "critical" | "serious" | "unknown" | "warning";

/** The severity words on the screen. */
export const SEVERITY_WORD: Readonly<Record<AttentionSeverity, string>> = {
  critical: "Act now", serious: "Review", unknown: "UNKNOWN", warning: "Watch",
};
const SEVERITY_ORDER: Readonly<Record<AttentionSeverity, number>> = { critical: 0, serious: 1, unknown: 2, warning: 3 };

export const BACKUP_STALE_DAYS = 7;
export const LICENSE_SOON_DAYS = 30;
export const LICENSE_WATCH_DAYS = 60;
export const CHANGE_WINDOW_DAYS = 7;
/** Names shown per row before "and N more". */
export const MAX_NAMES = 3;
/** Rows shown before "Show all"; UNKNOWN rows are always shown. */
export const VISIBLE_ROWS = 8;

const DAY_MS = 86_400_000;
const PLANE_DEVICES = "Devices";
const PLANE_COMPLIANCE = "Compliance";
const PLANE_POLICY = "Policy";

const q = (params: Record<string, string>) => `?${new URLSearchParams(params).toString()}`;
const LIFECYCLE_HREF = q({ screen: "inventory", view: "lifecycle" });

export type RuleKey =
  | "ha-not-ready" | "backup-missing" | "advisory-exploited" | "compliance-critical" | "advisory-critical-high"
  | "backup-stale" | "failed-jobs" | "not-read" | "license-expired" | "license-30" | "license-60" | "read-ageing"
  | "live-patch-missing" | "ha-unknown" | "change-unknown-actor" | "zone-matrix-violations";

export type SourceKey = "overview" | "failover" | "lifecycle" | "changes" | "compliance" | "zonematrix";

interface RuleSpec {
  readonly rank: number;
  readonly severity: AttentionSeverity;
  /** The product plane the target screen needs (the navigation's own check); null when the screen needs none. */
  readonly plane: string | null;
  readonly href: string;
  readonly linkLabel: string;
}

/** The rule table (design note §3), in rank order. */
export const RULES: Readonly<Record<RuleKey, RuleSpec>> = {
  "ha-not-ready": { rank: 1, severity: "critical", plane: null, href: q({ screen: "operations", readiness: "NOT_READY" }), linkLabel: "Open HA readiness" },
  "backup-missing": { rank: 2, severity: "critical", plane: null, href: q({ screen: "backups", artefact: "none" }), linkLabel: "Open Backups" },
  "advisory-exploited": { rank: 3, severity: "critical", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle" },
  "compliance-critical": { rank: 4, severity: "critical", plane: PLANE_COMPLIANCE, href: q({ screen: "compliance", severity: "critical", result: "fail" }), linkLabel: "Open Compliance" },
  "advisory-critical-high": { rank: 5, severity: "serious", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle" },
  "backup-stale": { rank: 6, severity: "serious", plane: null, href: q({ screen: "backups" }), linkLabel: "Open Backups" },
  "failed-jobs": { rank: 7, severity: "serious", plane: null, href: q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24" }), linkLabel: "Open Jobs" },
  "not-read": { rank: 8, severity: "serious", plane: PLANE_DEVICES, href: q({ screen: "inventory", inventory_age: "stale" }), linkLabel: "Open Devices" },
  "license-expired": { rank: 9, severity: "serious", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle" },
  "license-30": { rank: 10, severity: "serious", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle" },
  "license-60": { rank: 11, severity: "warning", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle" },
  "read-ageing": { rank: 12, severity: "warning", plane: PLANE_DEVICES, href: q({ screen: "inventory", inventory_age: "24h_72h" }), linkLabel: "Open Devices" },
  "live-patch-missing": { rank: 13, severity: "warning", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle" },
  "ha-unknown": { rank: 14, severity: "warning", plane: null, href: q({ screen: "operations", readiness: "UNKNOWN" }), linkLabel: "Open HA readiness" },
  "change-unknown-actor": { rank: 15, severity: "warning", plane: PLANE_POLICY, href: q({ screen: "changes", view: "feed" }), linkLabel: "Open Changes" },
  // Zone matrix (contract 7.2). The severity here is the default; `zoneMatrixRows` sets critical / serious / unknown from the
  // stored headline. The mapping is an engineering default awaiting the Product Owner (design note section 3), never a warning.
  "zone-matrix-violations": { rank: 16, severity: "serious", plane: PLANE_POLICY, href: q({ screen: "zone_matrix" }), linkLabel: "Open Zone matrix" },
};

/** One UNKNOWN row per source that could not be read; same shape as a rule, ranked by source. */
const UNKNOWN_SOURCE: Readonly<Record<SourceKey, RuleSpec & { readonly label: string; readonly what: string; readonly detail: string }>> = {
  overview: { rank: 19, severity: "unknown", plane: null, href: q({ screen: "operations" }), linkLabel: "Open Operations",
    label: "Overview", what: "Parts of the overview could not be read", detail: "backup, device-read, compliance and failed-job rows are missing from this list" },
  failover: { rank: 20, severity: "unknown", plane: null, href: q({ screen: "operations" }), linkLabel: "Open HA readiness",
    label: "HA readiness", what: "HA readiness could not be read", detail: "clusters not ready for failover are missing from this list" },
  compliance: { rank: 21, severity: "unknown", plane: PLANE_COMPLIANCE, href: q({ screen: "compliance" }), linkLabel: "Open Compliance",
    label: "Compliance", what: "Compliance has not been evaluated", detail: "critical check failures are missing from this list" },
  lifecycle: { rank: 22, severity: "unknown", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle",
    label: "Lifecycle", what: "Lifecycle facts could not be read", detail: "license, Live Patch and advisory rows are missing from this list" },
  changes: { rank: 23, severity: "unknown", plane: PLANE_POLICY, href: q({ screen: "changes", view: "feed" }), linkLabel: "Open Changes",
    label: "Changes", what: "The change feed could not be read", detail: "changes without a known actor are missing from this list" },
  zonematrix: { rank: 24, severity: "unknown", plane: PLANE_POLICY, href: q({ screen: "zone_matrix" }), linkLabel: "Open Zone matrix",
    label: "Zone matrix", what: "The zone matrix summary could not be read", detail: "rules that violate the zone matrix are missing from this list" },
};

/** What a read gave: its value, a refusal for this role, a failure, or "not read" (its target plane is not granted). */
export type SourceRead<T> =
  | { readonly state: "ok"; readonly value: T }
  | { readonly state: "restricted" }
  | { readonly state: "failed" }
  | { readonly state: "skipped" };

export interface AttentionInputs {
  readonly now: Date;
  readonly overview: OverviewView;
  readonly failover: SourceRead<{ readonly devices: readonly DeviceSummary[]; readonly rows: readonly CpFailoverSummary[] }>;
  readonly lifecycle: SourceRead<LifecycleFleet>;
  readonly changes: SourceRead<ChangeFeedPage>;
  /** The stored zone matrix summary body (`GET /api/v2/zone-matrix/summary`), checked here; absent for a caller that does not read it. */
  readonly zoneMatrix?: SourceRead<unknown>;
  /** The compliance control list, for the compliance row's detail line (most widespread failing critical control). */
  readonly controls?: readonly ComplianceControlItem[] | null;
}

export interface AttentionRow {
  /** `unknown-<source>`: a source that could not be read. `unevaluated-<rule>`: the rule itself threw on the data it was given. */
  readonly key: RuleKey | `unknown-${SourceKey}` | `unevaluated-${RuleKey}`;
  readonly severity: AttentionSeverity;
  readonly rank: number;
  /** The finding with its count, in plain words. */
  readonly what: string;
  /** Masked names as the server sent them, at most {@link MAX_NAMES}. */
  readonly names: readonly string[];
  /** How many more affected objects there are beyond `names`. */
  readonly more: number;
  /** The word before the names when they are a sample, not the whole set ("latest on"). */
  readonly namesLead?: string;
  readonly count: number;
  readonly detail: string | null;
  /** The time behind the row; `atKind` says whether it is shown as an age ("2 h ago") or a date (an expiry). */
  readonly at: string | null;
  readonly atKind: "age" | "date";
  /** What the time is ("checked", "last read", "expires"); shown before it. With no time, the whole age text. */
  readonly atLabel: string;
  readonly href: string;
  readonly linkLabel: string;
}

export interface SourceStatus { readonly key: SourceKey; readonly label: string; readonly state: SourceRead<unknown>["state"] }

export interface AttentionResult {
  readonly rows: readonly AttentionRow[];
  readonly sources: readonly SourceStatus[];
  readonly evaluatedAt: string;
}

const time = (iso: string | null | undefined): number | null => {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? null : t;
};
const newest = (ats: ReadonlyArray<string | null | undefined>): string | null =>
  ats.filter((a): a is string => time(a) !== null).sort((a, b) => time(b)! - time(a)!)[0] ?? null;
const oldest = (ats: ReadonlyArray<string | null | undefined>): string | null =>
  ats.filter((a): a is string => time(a) !== null).sort((a, b) => time(a)! - time(b)!)[0] ?? null;

interface Affected { readonly name: string; readonly at?: string | null }

function namesOf(items: readonly Affected[]): { names: string[]; more: number } {
  const unique = [...new Set(items.map((i) => i.name))];
  return { names: unique.slice(0, MAX_NAMES), more: Math.max(0, unique.length - MAX_NAMES) };
}

function row(key: RuleKey, items: readonly Affected[], what: string, at: string | null, atLabel: string,
  extra: { detail?: string | null; atKind?: "age" | "date"; count?: number; namesLead?: string; names?: string[]; more?: number } = {}): AttentionRow {
  const spec = RULES[key];
  const listed = extra.names ? { names: extra.names, more: extra.more ?? 0 } : namesOf(items);
  return { key, severity: spec.severity, rank: spec.rank, what, ...listed, namesLead: extra.namesLead, count: extra.count ?? items.length,
    detail: extra.detail ?? null, at, atKind: extra.atKind ?? "age", atLabel, href: spec.href, linkLabel: spec.linkLabel };
}

/** What each rule checks, in the words of the "could not be evaluated" row. */
const CHECK_NAME: Readonly<Record<RuleKey, string>> = {
  "ha-not-ready": "HA clusters not ready for failover", "backup-missing": "backup targets without a stored backup",
  "advisory-exploited": "devices affected by an actively exploited advisory", "compliance-critical": "firewalls failing a critical check",
  "advisory-critical-high": "devices matching a critical or high advisory", "backup-stale": "backup targets with an old newest backup",
  "failed-jobs": "jobs failed in the last 24 h", "not-read": "devices not read for over 72 h", "license-expired": "expired licenses",
  "license-30": "licenses expiring within 30 days", "license-60": "licenses expiring in 31–60 days", "read-ageing": "devices last read 24–72 h ago",
  "live-patch-missing": "Check Point devices without a Live Patch package", "ha-unknown": "HA clusters with UNKNOWN readiness",
  "change-unknown-actor": "changes without a known actor", "zone-matrix-violations": "rules that violate the zone matrix",
};

/** The one row a rule that threw leaves behind: UNKNOWN, never the silence of "nothing found". */
function unevaluatedRow(key: RuleKey): AttentionRow {
  const spec = RULES[key];
  return { key: `unevaluated-${key}`, severity: "unknown", rank: spec.rank, what: `This check could not be evaluated: ${CHECK_NAME[key]}`,
    names: [], more: 0, count: 0, detail: "the rule failed on the data it was given; its findings are missing from this list",
    at: null, atKind: "age", atLabel: "—", href: spec.href, linkLabel: spec.linkLabel };
}

/**
 * Runs one rule (or the shared step of several): its rows, or -- when it throws, e.g. on a field the server sent as null --
 * one "could not be evaluated" row per rule it stands for, so one bad rule never takes down the list.
 */
function guarded(keys: readonly RuleKey[], build: () => AttentionRow | readonly AttentionRow[] | null): AttentionRow[] {
  try {
    const built = build();
    return built === null ? [] : Array.isArray(built) ? [...built] : [built as AttentionRow];
  } catch {
    return keys.map(unevaluatedRow);
  }
}

const deviceName = (d: { hostname: string | null; device_id: string | null }) => d.hostname?.trim() || (d.device_id ? d.device_id.slice(0, 8) : "unknown device");

type EstateDevice = NonNullable<NonNullable<OverviewView["estate"]>["devices"]>[number];

/** The estate's device list, or null when the section failed (`{state: "READ_FAILED"}` carries none) or an older backend sent none. */
const estateDevices = (o: OverviewView): readonly EstateDevice[] | null => (Array.isArray(o.estate?.devices) ? o.estate!.devices! : null);

/** The parts of the overview body this list reads that the server could not build, by name; empty when all were read. */
export function unreadableOverviewParts(o: OverviewView): string[] {
  const parts: string[] = [];
  if (o.estate && estateDevices(o) === null) parts.push("device list");
  const failedTotal = o.exceptions?.failed_jobs?.total;
  if (o.exceptions?.state === "READ_FAILED" || (o.exceptions && !o.exceptions.failed_jobs)
    || (o.exceptions?.failed_jobs && (typeof failedTotal !== "number" || !Number.isFinite(failedTotal)))) parts.push("failed jobs");
  return parts;
}

function overviewRows(o: OverviewView, controls: readonly ComplianceControlItem[] | null | undefined, now: number): AttentionRow[] {
  const out: AttentionRow[] = [];
  const devices = estateDevices(o);
  const byName = (d: EstateDevice, at?: string | null): Affected => ({ name: deviceName(d), at });

  if (devices) {
    // Backups: a target without any stored archive, then a target whose newest archive is older than the threshold.
    out.push(...guarded(["backup-missing"], () => {
      const missing = devices.filter((d) => d.no_archive);
      return missing.length === 0 ? null : row("backup-missing", missing.map((d) => byName(d)),
        `${plural(missing.length, "backup target has", "backup targets have")} no stored backup`, null, "never backed up");
    }));
    out.push(...guarded(["backup-stale"], () => {
      const stale = devices.filter((d) => !d.no_archive && d.backup_target !== false && d.last_backup_at
        && now - time(d.last_backup_at)! > BACKUP_STALE_DAYS * DAY_MS)
        .sort((a, b) => time(a.last_backup_at)! - time(b.last_backup_at)!);
      return stale.length === 0 ? null : row("backup-stale", stale.map((d) => byName(d, d.last_backup_at)),
        `${plural(stale.length, "backup target's newest backup is", "backup targets' newest backups are")} older than ${BACKUP_STALE_DAYS} days`,
        oldest(stale.map((d) => d.last_backup_at)), "oldest backup");
    }));

    // Compliance: a firewall failing at least one critical check (only once the evaluation is warm).
    if (o.estate?.compliance_state === "OK") {
      out.push(...guarded(["compliance-critical"], () => {
        const failing = devices.filter((d) => (d.critical_fail ?? 0) > 0).sort((a, b) => (b.critical_fail ?? 0) - (a.critical_fail ?? 0));
        if (failing.length === 0) return null;
        const top = (controls ?? []).filter((c) => c.severity === "CRITICAL" && c.fail_count > 0)
          .sort((a, b) => b.fail_count - a.fail_count || a.control_id.localeCompare(b.control_id))[0];
        return row("compliance-critical", failing.map((d) => byName(d)), `${plural(failing.length, "firewall fails", "firewalls fail")} at least one critical check`,
          o.evidence?.compliance?.at ?? null, "compliance data",
          { detail: top ? `most widespread: ${top.title} (${top.fail_count} of ${top.target_device_count} firewalls)` : null });
      }));
    }
  }

  // Jobs failed in 24 h: the overview's own list (the latest five) names where; the reasons give the cause.
  const failed = o.exceptions?.failed_jobs;
  if (failed && failed.total > 0) {
    out.push(...guarded(["failed-jobs"], () => {
      const reasons = [...(failed.reasons ?? [])].sort((a, b) => b.count - a.count);
      const rows = failed.rows ?? [];
      // A job without a target device (e.g. a cluster-level policy collection) has device_id null (crash 2026-11-23 13:15).
      const labels = [...new Set(rows.map((r) => r.label?.trim() || (r.device_id ? r.device_id.slice(0, 8) : "no device")))];
      return row("failed-jobs", [], `${plural(failed.total, "job", "jobs")} failed in the last 24 h`,
        o.attention?.failed_jobs_24h?.last_at ?? newest(rows.map((r) => r.finished_at)), "latest",
        { count: failed.total, names: labels.slice(0, MAX_NAMES), more: 0, namesLead: labels.length ? "latest on" : undefined,
          detail: reasons[0] ? `most common cause: ${reasons[0].reason} (${reasons[0].count})` : null });
    }));
  }

  if (devices) {
    // Reads: never read or over 72 h (Review), 24-72 h (Watch) -- the product's one freshness scale.
    const age = (d: EstateDevice) => (time(d.last_read_at) === null ? null : now - time(d.last_read_at)!);
    out.push(...guarded(["not-read"], () => {
      const notRead = devices.filter((d) => { const a = age(d); return a === null || a > AGEING_UNTIL_MS; })
        .sort((a, b) => (time(a.last_read_at) ?? -Infinity) - (time(b.last_read_at) ?? -Infinity));
      if (notRead.length === 0) return null;
      const never = notRead.filter((d) => !d.last_read_at).length;
      return row("not-read", notRead.map((d) => byName(d, d.last_read_at)),
        never === notRead.length ? `${plural(never, "device has", "devices have")} never been read` : `${plural(notRead.length, "device", "devices")} not read for over 72 h`,
        oldest(notRead.map((d) => d.last_read_at)), never === notRead.length ? "never read" : "oldest read",
        { detail: never > 0 && never < notRead.length ? `${never} never read` : null });
    }));
    out.push(...guarded(["read-ageing"], () => {
      const ageing = devices.filter((d) => { const a = age(d); return a !== null && a > FRESH_UNDER_MS && a <= AGEING_UNTIL_MS; })
        .sort((a, b) => time(a.last_read_at)! - time(b.last_read_at)!);
      return ageing.length === 0 ? null : row("read-ageing", ageing.map((d) => byName(d, d.last_read_at)), `${plural(ageing.length, "device", "devices")} last read 24–72 h ago`,
        oldest(ageing.map((d) => d.last_read_at)), "oldest read");
    }));
  }
  return out;
}

function failoverRows(value: { devices: readonly DeviceSummary[]; rows: readonly CpFailoverSummary[] }): AttentionRow[] {
  type Items = ReturnType<typeof haReadinessItems>;
  let items: Items;
  try {
    const byRef: Record<string, CpFailoverSummary[]> = {};
    for (const r of value.rows) (byRef[r.cluster_member_ref] ??= []).push(r);
    items = haReadinessItems(groupClusters(value.devices), byRef);
  } catch {
    return (["ha-not-ready", "ha-unknown"] as const).map(unevaluatedRow);
  }
  return [
    ...guarded(["ha-not-ready"], () => {
      const notReady = items.filter((i) => i.status === "NOT_READY");
      if (notReady.length === 0) return null;
      const checks = [...new Set(notReady.map((i) => i.base?.readiness?.failedCheck).filter((c): c is string => Boolean(c)))];
      return row("ha-not-ready", notReady.map((i) => ({ name: i.cluster.title })),
        `${plural(notReady.length, "HA cluster", "HA clusters")} not ready for failover`,
        newest(notReady.map((i) => i.base?.readiness?.observedAt)), "checked",
        { detail: checks.length ? `failed check: ${checks.slice(0, 2).join(", ")}${checks.length > 2 ? ` and ${checks.length - 2} more` : ""}` : null });
    }),
    ...guarded(["ha-unknown"], () => {
      const unknown = items.filter((i) => i.status === "UNKNOWN");
      return unknown.length === 0 ? null : row("ha-unknown", unknown.map((i) => ({ name: i.cluster.title })),
        `${plural(unknown.length, "HA cluster has", "HA clusters have")} UNKNOWN readiness`,
        newest(unknown.map((i) => i.base?.readiness?.observedAt)), "checked");
    }),
  ];
}

/** A device's worst license state: expired, within 30 days, within 60 days, or null. Permanent and undated items never count. */
export function licenseState(d: LifecycleDevice): { state: "expired" | "soon" | "watch"; label: string; expiry: string | null; days: number | null } | null {
  let worst: { state: "expired" | "soon" | "watch"; label: string; expiry: string | null; days: number | null } | null = null;
  const order = { expired: 0, soon: 1, watch: 2 } as const;
  for (const l of d.licenses ?? []) {
    if (l.status === "PERMANENT") continue;
    const days = typeof l.days_remaining === "number" ? l.days_remaining : null;
    const state: "expired" | "soon" | "watch" | null = l.status === "EXPIRED" || (days !== null && days < 0) ? "expired"
      : days !== null && days <= LICENSE_SOON_DAYS ? "soon" : days !== null && days <= LICENSE_WATCH_DAYS ? "watch" : null;
    if (!state) continue;
    const candidate = { state, label: l.label, expiry: l.expiry ?? null, days };
    if (!worst || order[state] < order[worst.state] || (state === worst.state && (days ?? 0) < (worst.days ?? 0))) worst = candidate;
  }
  return worst;
}

function lifecycleRows(fleet: LifecycleFleet, enrolled: ReadonlySet<string> | null): AttentionRow[] {
  const LIFECYCLE_RULES = ["advisory-exploited", "advisory-critical-high", "license-expired", "license-30", "license-60", "live-patch-missing"] as const;
  let devices: LifecycleDevice[];
  try {
    devices = fleet.devices.filter((d) => !enrolled || enrolled.has(d.device_id));
  } catch {
    return LIFECYCLE_RULES.map(unevaluatedRow);
  }
  const out: AttentionRow[] = [];
  const name = (d: LifecycleDevice) => deviceName(d);
  const evaluated = (): LifecycleDevice[] => devices.filter((d) => d.vulnerabilities?.state === "EVALUATED");
  out.push(...guarded(["advisory-exploited"], () => {
    const exploited = evaluated().filter((d) => (d.vulnerabilities?.affected_exploited ?? 0) > 0);
    return exploited.length === 0 ? null : row("advisory-exploited", exploited.map((d) => ({ name: name(d) })),
      `${plural(exploited.length, "device is", "devices are")} affected by an actively exploited advisory`,
      newest(exploited.map((d) => d.vulnerabilities?.compared?.loaded_at)), "advisories loaded",
      { detail: "a CVE of the advisory is in the CISA Known Exploited Vulnerabilities catalog" });
  }));
  out.push(...guarded(["advisory-critical-high"], () => {
    const criticalHigh = evaluated().filter((d) => (d.vulnerabilities?.affected_exploited ?? 0) === 0 && (d.vulnerabilities?.affected_critical_high ?? 0) > 0);
    if (criticalHigh.length === 0) return null;
    const total = criticalHigh.reduce((n, d) => n + (d.vulnerabilities?.affected_critical_high ?? 0), 0);
    return row("advisory-critical-high", criticalHigh.map((d) => ({ name: name(d) })),
      `${plural(criticalHigh.length, "device matches", "devices match")} a critical or high advisory for its version`,
      newest(criticalHigh.map((d) => d.vulnerabilities?.compared?.loaded_at)), "advisories loaded",
      { detail: `${plural(total, "advisory match", "advisory matches")} in all` });
  }));
  let withLicense: Array<{ d: LifecycleDevice; l: NonNullable<ReturnType<typeof licenseState>> }> = [];
  let licenseFailed = false;
  try {
    withLicense = devices.flatMap((d) => { const l = licenseState(d); return l ? [{ d, l }] : []; });
  } catch {
    licenseFailed = true;
  }
  const bucket = (state: "expired" | "soon" | "watch") => withLicense.filter((x) => x.l.state === state)
    .sort((a, b) => (a.l.days ?? 0) - (b.l.days ?? 0));
  if (licenseFailed) out.push(...(["license-expired", "license-30", "license-60"] as const).map(unevaluatedRow));
  else {
    out.push(...guarded(["license-expired"], () => {
      const expired = bucket("expired");
      if (expired.length === 0) return null;
      const latest = [...expired].sort((a, b) => (b.l.days ?? -Infinity) - (a.l.days ?? -Infinity))[0];
      return row("license-expired", expired.map((x) => ({ name: name(x.d) })),
        `${plural(expired.length, "device has", "devices have")} an expired license, subscription or support contract`,
        latest.l.expiry, "latest expired", { atKind: "date", detail: `latest: ${latest.l.label}` });
    }));
    for (const [state, key, words] of [["soon", "license-30", `within ${LICENSE_SOON_DAYS} days`], ["watch", "license-60", `in ${LICENSE_SOON_DAYS + 1}–${LICENSE_WATCH_DAYS} days`]] as const) {
      out.push(...guarded([key], () => {
        const items = bucket(state);
        if (items.length === 0) return null;
        const first = items[0];
        return row(key, items.map((x) => ({ name: name(x.d) })),
          `${plural(items.length, "device has", "devices have")} a license or subscription expiring ${words}`,
          first.l.expiry, "nearest expires",
          { atKind: "date", detail: `nearest: ${first.l.label}${first.l.days !== null ? `, in ${plural(first.l.days, "day", "days")}` : ""}` });
      }));
    }
  }
  out.push(...guarded(["live-patch-missing"], () => {
    const noLivePatch = devices.filter((d) => d.vendor === "check_point" && d.live_patch?.missing === true);
    return noLivePatch.length === 0 ? null : row("live-patch-missing", noLivePatch.map((d) => ({ name: name(d) })),
      `${plural(noLivePatch.length, "Check Point device reports", "Check Point devices report")} no Live Patch package`,
      newest(noLivePatch.map((d) => d.live_patch?.observed_at)), "read");
  }));
  return out;
}

/** A user change whose actor the collection could not name (`null`, or the "UNKNOWN (observed by collection)" text). */
export function actorUnknown(actor: string | null | undefined): boolean {
  return !actor || /^\s*UNKNOWN\b/i.test(actor);
}

function changeRows(page: ChangeFeedPage): AttentionRow[] {
  return guarded(["change-unknown-actor"], () => {
    const events = page.events.filter((e) => e.kind === "USER" && actorUnknown(e.actor));
    if (events.length === 0) return null;
    const sorted = [...events].sort((a, b) => (time(b.time) ?? 0) - (time(a.time) ?? 0));
    const named = sorted.map((e) => ({ name: e.device_name?.trim() || (e.device_id ? e.device_id.slice(0, 8) : "unknown device") }));
    return row("change-unknown-actor", named, `${plural(events.length, "change", "changes")} in the last ${CHANGE_WINDOW_DAYS} days without a known actor`,
      sorted[0].time, "newest", { count: events.length });
  });
}

/** The detail line parts, each saying what it counts; a zero count is left out, never printed as a reassurance. */
function zoneMatrixDetail(s: ZoneMatrixAttentionSummary, staleCount: number): string[] {
  const h = s.headline!;
  const parts: string[] = [];
  if (h.outside_headline_text) parts.push(h.outside_headline_text);
  if (s.state !== "READY" && s.state_text) parts.push(s.state_text);
  if (h.not_evaluable_findings > 0) parts.push(`${plural(h.not_evaluable_findings, "finding is", "findings are")} NOT_EVALUABLE`);
  if (h.unknown_findings > 0) parts.push(`${plural(h.unknown_findings, "finding is", "findings are")} UNKNOWN`);
  if (h.became_undecided_findings > 0) {
    parts.push(`${plural(h.became_undecided_findings, "open finding", "open findings")} became undecided (were violations, now NOT_EVALUABLE or UNKNOWN)`);
  }
  if (staleCount > 0) {
    const t = s.staleness_threshold;
    const limit = t ? `${t.hours !== null ? `${t.hours} h` : `${t.seconds} s`}${t.interim ? " (interim value set by the Product Owner, not the bank's)" : ""}` : "the staleness threshold";
    parts.push(`${plural(staleCount, "policy is", "policies are")} STALE (snapshot older than ${limit}, last run failed, or not on the active version)`);
  }
  if (h.incomplete_policies > 0) parts.push(`${plural(h.incomplete_policies, "policy is", "policies are")} INCOMPLETE (collection incomplete)`);
  if (h.not_evaluated_policies > 0) parts.push(`${plural(h.not_evaluated_policies, "policy is", "policies are")} not evaluated`);
  if (h.older_version_policies > 0) parts.push(`${plural(h.older_version_policies, "policy is", "policies are")} still on an older matrix version`);
  if (h.resolved_by_version_change_last_7_days > 0) {
    parts.push(`${plural(h.resolved_by_version_change_last_7_days, "finding", "findings")} resolved by a version change in the last 7 days`);
  }
  return parts;
}

/**
 * The zone matrix row (contract 7.2, ZM-26, ZM-35): one row, never split. Silent when there is no active version, and
 * when everything is evaluated, current, complete and clean with nothing resolved by a version change; otherwise the
 * server's headline sentence ("at least N" unless every covered policy is CURRENT and COMPLETE) plus what is undecided.
 * Severity is an engineering default awaiting the Product Owner: critical when a critical rule violates, serious when
 * rules violate (or a version change resolved findings), UNKNOWN when nothing is proven but something is undecided.
 */
function zoneMatrixRows(s: ZoneMatrixAttentionSummary): AttentionRow[] {
  const h = s.headline;
  if (s.state === "NO_ACTIVE_VERSION" || !h) return [];
  const spec = RULES["zone-matrix-violations"];
  const staleCount = Math.max(h.stale_policies, s.policies.filter((p) => p.staleness_verdict !== "CURRENT").length);
  const undecided = h.not_evaluable_findings + h.unknown_findings + h.became_undecided_findings + h.outside_headline_ordered_layer
    + staleCount + h.incomplete_policies + h.not_evaluated_policies + h.older_version_policies;
  const proven = h.distinct_violating_rules > 0;
  const resolved = h.resolved_by_version_change_last_7_days > 0;
  if (!proven && !resolved && undecided === 0 && h.exact) return [];
  const severity: AttentionSeverity = h.critical_rules > 0 ? "critical" : proven || resolved ? "serious" : "unknown";
  // The server's text decides "at least N"; a "0" it would print for a count that is not exact is never shown.
  const what = !h.exact && /^\s*0\b/.test(h.text) ? "no proven violations in evaluated rules" : h.text;
  const detail = zoneMatrixDetail(s, staleCount);
  const at = oldest(s.policies.map((p) => p.collected_at));
  return [{ key: "zone-matrix-violations", severity, rank: spec.rank, what, names: [], more: 0, count: h.distinct_violating_rules,
    detail: detail.length ? detail.join("; ") : null, at, atKind: "age", atLabel: at ? "oldest policy snapshot" : "policy snapshot age",
    href: spec.href, linkLabel: spec.linkLabel }];
}

function unknownRow(source: SourceKey, what?: string): AttentionRow {
  const s = UNKNOWN_SOURCE[source];
  return { key: `unknown-${source}`, severity: "unknown", rank: s.rank, what: what ?? s.what, names: [], more: 0, count: 0, detail: s.detail,
    at: null, atKind: "age", atLabel: "—", href: s.href, linkLabel: s.linkLabel };
}

/** Severity, then the rule's rank, then affected count (most first), then newest evidence first. */
export function compareRows(a: AttentionRow, b: AttentionRow): number {
  return SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] || a.rank - b.rank || b.count - a.count
    || (time(b.at) ?? -Infinity) - (time(a.at) ?? -Infinity);
}

/** Whether a row's target screen is open to this session (presentation; the server decides every read). */
export function rowAllowed(r: AttentionRow, permissions: readonly string[] | undefined): boolean {
  const plane = r.key.startsWith("unknown-") ? UNKNOWN_SOURCE[r.key.slice("unknown-".length) as SourceKey].plane
    : RULES[(r.key.startsWith("unevaluated-") ? r.key.slice("unevaluated-".length) : r.key) as RuleKey].plane;
  return !plane || planeGranted(permissions, plane);
}

/**
 * Whether a source is worth reading for this session: the change feed (its endpoint needs POLICY_READ) and the zone matrix
 * summary (the Zone matrix screen's plane) only with the Policy plane. A read that would be refused is not issued.
 */
export function sourceWanted(source: "lifecycle" | "changes" | "zonematrix", permissions: readonly string[] | undefined): boolean {
  return planeGranted(permissions, source === "changes" || source === "zonematrix" ? PLANE_POLICY : PLANE_DEVICES);
}

/** The attention list: every rule over the reads given, filtered to what this session may open, most severe first. */
export function buildAttention(x: AttentionInputs, permissions: readonly string[] | undefined): AttentionResult {
  const now = x.now.getTime();
  const rows: AttentionRow[] = [...overviewRows(x.overview, x.controls, now)];
  // A part of the overview the server could not build is UNKNOWN, not an empty result (the rules above skipped it).
  const unreadable = unreadableOverviewParts(x.overview);
  if (unreadable.length > 0) rows.push(unknownRow("overview", `The overview's ${unreadable.join(" and ")} could not be read`));
  const sources: SourceStatus[] = [{ key: "overview", label: "Overview", state: unreadable.length > 0 ? "failed" : "ok" }];

  const compliance: SourceRead<unknown>["state"] = x.overview.estate?.compliance_state === "OK" ? "ok" : x.overview.estate ? "failed" : "skipped";
  if (compliance === "failed") rows.push(unknownRow("compliance"));
  sources.push({ key: "compliance", label: "Compliance", state: compliance });

  const estate = estateDevices(x.overview);
  const enrolled = estate ? new Set(estate.flatMap((d) => (d ? [d.device_id] : []))) : null;
  const reads: Array<[Exclude<SourceKey, "overview" | "compliance">, SourceRead<never>["state"], () => AttentionRow[]]> = [
    ["failover", x.failover.state, () => (x.failover.state === "ok" ? failoverRows(x.failover.value) : [])],
    ["lifecycle", x.lifecycle.state, () => (x.lifecycle.state === "ok" ? lifecycleRows(x.lifecycle.value, enrolled) : [])],
    ["changes", x.changes.state, () => (x.changes.state === "ok" ? changeRows(x.changes.value) : [])],
  ];
  for (const [key, state, build] of reads) {
    sources.push({ key, label: UNKNOWN_SOURCE[key].label, state });
    if (state === "ok") rows.push(...build());
    else if (state === "failed") rows.push(unknownRow(key));
  }
  if (x.zoneMatrix) {
    const read = x.zoneMatrix;
    let state: SourceRead<unknown>["state"] = read.state;
    if (read.state === "ok") {
      const summary = parseZoneMatrixAttention(read.value);
      if (!summary) {
        // A body without its headline or counts is unreadable, not "nothing to report".
        state = "failed";
        rows.push(unknownRow("zonematrix"));
      } else rows.push(...guarded(["zone-matrix-violations"], () => zoneMatrixRows(summary)));
    } else if (read.state === "failed") rows.push(unknownRow("zonematrix"));
    sources.push({ key: "zonematrix", label: UNKNOWN_SOURCE.zonematrix.label, state });
  }
  return { rows: rows.filter((r) => rowAllowed(r, permissions)).sort(compareRows), sources, evaluatedAt: x.now.toISOString() };
}

/** The rows shown before "Show all": the first {@link VISIBLE_ROWS}, plus every UNKNOWN row after them. */
export function visibleRows(rows: readonly AttentionRow[], expanded: boolean): readonly AttentionRow[] {
  if (expanded || rows.length <= VISIBLE_ROWS) return rows;
  return rows.filter((r, i) => i < VISIBLE_ROWS || r.severity === "unknown");
}

/** "2 Act now · 5 Review · 1 UNKNOWN · 3 Watch" parts, zero counts left out. */
export function severityCounts(rows: readonly AttentionRow[]): Array<{ severity: AttentionSeverity; count: number }> {
  return (Object.keys(SEVERITY_ORDER) as AttentionSeverity[])
    .map((severity) => ({ severity, count: rows.filter((r) => r.severity === severity).length }))
    .filter((c) => c.count > 0);
}
