import type {
  ChangeFeedPage, ComplianceControlItem, CpFailoverSummary, DeviceSummary, LifecycleDevice, LifecycleFleet, OverviewView,
} from "../../auth/adminApi";
import { planeGranted } from "../../shell/actionAccess";
import { AGEING_UNTIL_MS, FRESH_UNDER_MS } from "../../shell/freshness";
import { groupClusters, haReadinessItems } from "../HaReadinessList";

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
  | "live-patch-missing" | "ha-unknown" | "change-unknown-actor";

export type SourceKey = "overview" | "failover" | "lifecycle" | "changes" | "compliance";

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
};

/** One UNKNOWN row per source that could not be read; same shape as a rule, ranked by source. */
const UNKNOWN_SOURCE: Readonly<Record<Exclude<SourceKey, "overview">, RuleSpec & { readonly label: string; readonly what: string; readonly detail: string }>> = {
  failover: { rank: 20, severity: "unknown", plane: null, href: q({ screen: "operations" }), linkLabel: "Open HA readiness",
    label: "HA readiness", what: "HA readiness could not be read", detail: "clusters not ready for failover are missing from this list" },
  compliance: { rank: 21, severity: "unknown", plane: PLANE_COMPLIANCE, href: q({ screen: "compliance" }), linkLabel: "Open Compliance",
    label: "Compliance", what: "Compliance has not been evaluated", detail: "critical check failures are missing from this list" },
  lifecycle: { rank: 22, severity: "unknown", plane: PLANE_DEVICES, href: LIFECYCLE_HREF, linkLabel: "Open Lifecycle",
    label: "Lifecycle", what: "Lifecycle facts could not be read", detail: "license, Live Patch and advisory rows are missing from this list" },
  changes: { rank: 23, severity: "unknown", plane: PLANE_POLICY, href: q({ screen: "changes", view: "feed" }), linkLabel: "Open Changes",
    label: "Changes", what: "The change feed could not be read", detail: "changes without a known actor are missing from this list" },
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
  /** The compliance control list, for the compliance row's detail line (most widespread failing critical control). */
  readonly controls?: readonly ComplianceControlItem[] | null;
}

export interface AttentionRow {
  readonly key: RuleKey | `unknown-${SourceKey}`;
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

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
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

const deviceName = (d: { hostname: string | null; device_id: string }) => d.hostname?.trim() || d.device_id.slice(0, 8);

type EstateDevice = NonNullable<OverviewView["estate"]>["devices"][number];

function overviewRows(o: OverviewView, controls: readonly ComplianceControlItem[] | null | undefined, now: number): AttentionRow[] {
  const out: AttentionRow[] = [];
  const devices: readonly EstateDevice[] = o.estate?.devices ?? [];
  const byName = (d: EstateDevice, at?: string | null): Affected => ({ name: deviceName(d), at });

  // Backups: a target without any stored archive, then a target whose newest archive is older than the threshold.
  const missing = devices.filter((d) => d.no_archive);
  if (missing.length > 0) {
    out.push(row("backup-missing", missing.map((d) => byName(d)), `${plural(missing.length, "backup target has", "backup targets have")} no stored backup`,
      null, "never backed up"));
  }
  const stale = devices.filter((d) => !d.no_archive && d.backup_target !== false && d.last_backup_at
    && now - time(d.last_backup_at)! > BACKUP_STALE_DAYS * DAY_MS)
    .sort((a, b) => time(a.last_backup_at)! - time(b.last_backup_at)!);
  if (stale.length > 0) {
    out.push(row("backup-stale", stale.map((d) => byName(d, d.last_backup_at)),
      `${plural(stale.length, "backup target's newest backup is", "backup targets' newest backups are")} older than ${BACKUP_STALE_DAYS} days`,
      oldest(stale.map((d) => d.last_backup_at)), "oldest backup"));
  }

  // Compliance: a firewall failing at least one critical check (only once the evaluation is warm).
  if (o.estate && o.estate.compliance_state === "OK") {
    const failing = devices.filter((d) => (d.critical_fail ?? 0) > 0).sort((a, b) => (b.critical_fail ?? 0) - (a.critical_fail ?? 0));
    if (failing.length > 0) {
      const top = (controls ?? []).filter((c) => c.severity === "CRITICAL" && c.fail_count > 0)
        .sort((a, b) => b.fail_count - a.fail_count || a.control_id.localeCompare(b.control_id))[0];
      out.push(row("compliance-critical", failing.map((d) => byName(d)), `${plural(failing.length, "firewall fails", "firewalls fail")} at least one critical check`,
        o.evidence.compliance?.at ?? null, "compliance data",
        { detail: top ? `most widespread: ${top.title} (${top.fail_count} of ${top.target_device_count} firewalls)` : null }));
    }
  }

  // Jobs failed in 24 h: the overview's own list (the latest five) names where; the reasons give the cause.
  const failed = o.exceptions?.failed_jobs;
  if (failed && failed.total > 0) {
    const reasons = [...(failed.reasons ?? [])].sort((a, b) => b.count - a.count);
    const labels = [...new Set(failed.rows.map((r) => r.label?.trim() || r.device_id.slice(0, 8)))];
    out.push(row("failed-jobs", [], `${plural(failed.total, "job", "jobs")} failed in the last 24 h`,
      o.attention?.failed_jobs_24h?.last_at ?? newest(failed.rows.map((r) => r.finished_at)), "latest",
      { count: failed.total, names: labels.slice(0, MAX_NAMES), more: 0, namesLead: labels.length ? "latest on" : undefined,
        detail: reasons[0] ? `most common cause: ${reasons[0].reason} (${reasons[0].count})` : null }));
  }

  // Reads: never read or over 72 h (Review), 24-72 h (Watch) -- the product's one freshness scale.
  const age = (d: EstateDevice) => (time(d.last_read_at) === null ? null : now - time(d.last_read_at)!);
  const notRead = devices.filter((d) => { const a = age(d); return a === null || a > AGEING_UNTIL_MS; })
    .sort((a, b) => (time(a.last_read_at) ?? -Infinity) - (time(b.last_read_at) ?? -Infinity));
  if (notRead.length > 0) {
    const never = notRead.filter((d) => !d.last_read_at).length;
    out.push(row("not-read", notRead.map((d) => byName(d, d.last_read_at)),
      never === notRead.length ? `${plural(never, "device has", "devices have")} never been read` : `${plural(notRead.length, "device", "devices")} not read for over 72 h`,
      oldest(notRead.map((d) => d.last_read_at)), never === notRead.length ? "never read" : "oldest read",
      { detail: never > 0 && never < notRead.length ? `${never} never read` : null }));
  }
  const ageing = devices.filter((d) => { const a = age(d); return a !== null && a > FRESH_UNDER_MS && a <= AGEING_UNTIL_MS; })
    .sort((a, b) => time(a.last_read_at)! - time(b.last_read_at)!);
  if (ageing.length > 0) {
    out.push(row("read-ageing", ageing.map((d) => byName(d, d.last_read_at)), `${plural(ageing.length, "device", "devices")} last read 24–72 h ago`,
      oldest(ageing.map((d) => d.last_read_at)), "oldest read"));
  }
  return out;
}

function failoverRows(value: { devices: readonly DeviceSummary[]; rows: readonly CpFailoverSummary[] }): AttentionRow[] {
  const byRef: Record<string, CpFailoverSummary[]> = {};
  for (const r of value.rows) (byRef[r.cluster_member_ref] ??= []).push(r);
  const items = haReadinessItems(groupClusters(value.devices), byRef);
  const out: AttentionRow[] = [];
  const notReady = items.filter((i) => i.status === "NOT_READY");
  if (notReady.length > 0) {
    const checks = [...new Set(notReady.map((i) => i.base?.readiness?.failedCheck).filter((c): c is string => Boolean(c)))];
    out.push(row("ha-not-ready", notReady.map((i) => ({ name: i.cluster.title })),
      `${plural(notReady.length, "HA cluster", "HA clusters")} not ready for failover`,
      newest(notReady.map((i) => i.base?.readiness?.observedAt)), "checked",
      { detail: checks.length ? `failed check: ${checks.slice(0, 2).join(", ")}${checks.length > 2 ? ` and ${checks.length - 2} more` : ""}` : null }));
  }
  const unknown = items.filter((i) => i.status === "UNKNOWN");
  if (unknown.length > 0) {
    out.push(row("ha-unknown", unknown.map((i) => ({ name: i.cluster.title })),
      `${plural(unknown.length, "HA cluster has", "HA clusters have")} UNKNOWN readiness`,
      newest(unknown.map((i) => i.base?.readiness?.observedAt)), "checked"));
  }
  return out;
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
  const devices = fleet.devices.filter((d) => !enrolled || enrolled.has(d.device_id));
  const out: AttentionRow[] = [];
  const name = (d: LifecycleDevice) => deviceName(d);
  const evaluated = devices.filter((d) => d.vulnerabilities?.state === "EVALUATED");
  const exploited = evaluated.filter((d) => (d.vulnerabilities?.affected_exploited ?? 0) > 0);
  if (exploited.length > 0) {
    out.push(row("advisory-exploited", exploited.map((d) => ({ name: name(d) })),
      `${plural(exploited.length, "device is", "devices are")} affected by an actively exploited advisory`,
      newest(exploited.map((d) => d.vulnerabilities?.compared?.loaded_at)), "advisories loaded",
      { detail: "a CVE of the advisory is in the CISA Known Exploited Vulnerabilities catalog" }));
  }
  const criticalHigh = evaluated.filter((d) => (d.vulnerabilities?.affected_exploited ?? 0) === 0 && (d.vulnerabilities?.affected_critical_high ?? 0) > 0);
  if (criticalHigh.length > 0) {
    const total = criticalHigh.reduce((n, d) => n + (d.vulnerabilities?.affected_critical_high ?? 0), 0);
    out.push(row("advisory-critical-high", criticalHigh.map((d) => ({ name: name(d) })),
      `${plural(criticalHigh.length, "device matches", "devices match")} a critical or high advisory for its version`,
      newest(criticalHigh.map((d) => d.vulnerabilities?.compared?.loaded_at)), "advisories loaded",
      { detail: `${plural(total, "advisory match", "advisory matches")} in all` }));
  }
  const withLicense = devices.map((d) => ({ d, l: licenseState(d) })).filter((x) => x.l !== null);
  const bucket = (state: "expired" | "soon" | "watch") => withLicense.filter((x) => x.l!.state === state)
    .sort((a, b) => (a.l!.days ?? 0) - (b.l!.days ?? 0));
  const expired = bucket("expired");
  if (expired.length > 0) {
    const latest = [...expired].sort((a, b) => (b.l!.days ?? -Infinity) - (a.l!.days ?? -Infinity))[0];
    out.push(row("license-expired", expired.map((x) => ({ name: name(x.d) })),
      `${plural(expired.length, "device has", "devices have")} an expired license, subscription or support contract`,
      latest.l!.expiry, "latest expired", { atKind: "date", detail: `latest: ${latest.l!.label}` }));
  }
  for (const [state, key, words] of [["soon", "license-30", `within ${LICENSE_SOON_DAYS} days`], ["watch", "license-60", `in ${LICENSE_SOON_DAYS + 1}–${LICENSE_WATCH_DAYS} days`]] as const) {
    const items = bucket(state);
    if (items.length === 0) continue;
    const first = items[0];
    out.push(row(key, items.map((x) => ({ name: name(x.d) })),
      `${plural(items.length, "device has", "devices have")} a license or subscription expiring ${words}`,
      first.l!.expiry, "nearest expires",
      { atKind: "date", detail: `nearest: ${first.l!.label}${first.l!.days !== null ? `, in ${plural(first.l!.days, "day", "days")}` : ""}` }));
  }
  const noLivePatch = devices.filter((d) => d.vendor === "check_point" && d.live_patch?.missing === true);
  if (noLivePatch.length > 0) {
    out.push(row("live-patch-missing", noLivePatch.map((d) => ({ name: name(d) })),
      `${plural(noLivePatch.length, "Check Point device reports", "Check Point devices report")} no Live Patch package`,
      newest(noLivePatch.map((d) => d.live_patch?.observed_at)), "read"));
  }
  return out;
}

/** A user change whose actor the collection could not name (`null`, or the "UNKNOWN (observed by collection)" text). */
export function actorUnknown(actor: string | null | undefined): boolean {
  return !actor || /^\s*UNKNOWN\b/i.test(actor);
}

function changeRows(page: ChangeFeedPage): AttentionRow[] {
  const events = page.events.filter((e) => e.kind === "USER" && actorUnknown(e.actor));
  if (events.length === 0) return [];
  const sorted = [...events].sort((a, b) => (time(b.time) ?? 0) - (time(a.time) ?? 0));
  const named = sorted.map((e) => ({ name: e.device_name?.trim() || (e.device_id ? e.device_id.slice(0, 8) : "unknown device") }));
  return [row("change-unknown-actor", named, `${plural(events.length, "change", "changes")} in the last ${CHANGE_WINDOW_DAYS} days without a known actor`,
    sorted[0].time, "newest", { count: events.length })];
}

function unknownRow(source: Exclude<SourceKey, "overview">): AttentionRow {
  const s = UNKNOWN_SOURCE[source];
  return { key: `unknown-${source}`, severity: "unknown", rank: s.rank, what: s.what, names: [], more: 0, count: 0, detail: s.detail,
    at: null, atKind: "age", atLabel: "—", href: s.href, linkLabel: s.linkLabel };
}

/** Severity, then the rule's rank, then affected count (most first), then newest evidence first. */
export function compareRows(a: AttentionRow, b: AttentionRow): number {
  return SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] || a.rank - b.rank || b.count - a.count
    || (time(b.at) ?? -Infinity) - (time(a.at) ?? -Infinity);
}

/** Whether a row's target screen is open to this session (presentation; the server decides every read). */
export function rowAllowed(r: AttentionRow, permissions: readonly string[] | undefined): boolean {
  const plane = r.key.startsWith("unknown-") ? UNKNOWN_SOURCE[r.key.slice("unknown-".length) as Exclude<SourceKey, "overview">].plane
    : RULES[r.key as RuleKey].plane;
  return !plane || planeGranted(permissions, plane);
}

/** Whether a source is worth reading for this session: the change feed only with the Policy plane (its endpoint needs POLICY_READ). */
export function sourceWanted(source: "lifecycle" | "changes", permissions: readonly string[] | undefined): boolean {
  return planeGranted(permissions, source === "changes" ? PLANE_POLICY : PLANE_DEVICES);
}

/** The attention list: every rule over the reads given, filtered to what this session may open, most severe first. */
export function buildAttention(x: AttentionInputs, permissions: readonly string[] | undefined): AttentionResult {
  const now = x.now.getTime();
  const rows: AttentionRow[] = [...overviewRows(x.overview, x.controls, now)];
  const sources: SourceStatus[] = [{ key: "overview", label: "Overview", state: "ok" }];

  const compliance: SourceRead<unknown>["state"] = x.overview.estate?.compliance_state === "OK" ? "ok" : x.overview.estate ? "failed" : "skipped";
  if (compliance === "failed") rows.push(unknownRow("compliance"));
  sources.push({ key: "compliance", label: "Compliance", state: compliance });

  const enrolled = x.overview.estate ? new Set(x.overview.estate.devices.map((d) => d.device_id)) : null;
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
