import { useEffect, useState } from "react";
import {
  getSecurityScanSummary,
  getSystemHealth,
  type ScanFindingRow,
  type SecurityScanSummaryView,
  type SystemHealth,
  type SystemHealthDevice,
} from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import { isSystemHealth } from "../screens/SystemHealthPanel";

/**
 * Platform findings for the Shift brief (concept row 29, System · Platform, "Findings in Brief").
 *
 * Two sources, both read through the API clients the standard screens already call:
 *  - Device health (`getSystemHealth`, the Operations > Device health body): CRITICAL and ATTENTION devices,
 *    and devices whose state is UNKNOWN.
 *  - The latest daily security scan (`getSecurityScanSummary`): open HIGH and MEDIUM findings
 *    (`new` + `existing`; `accepted` is not open).
 *
 * Nothing listed means checked and fine only when the source was read. A source that could not be read is an
 * UNKNOWN item, never OK. Every count says what it counts, and every age is the age of its own evidence.
 */

/** Names a brief item lists before it says "+N more". */
export const PLATFORM_MAX_NAMES = 5;

export type PlatformReadState<T> =
  | { readonly state: "loading" }
  | { readonly state: "ok"; readonly value: T }
  | { readonly state: "failed" }
  | { readonly state: "restricted" };

/** `act` = needs action (critical), `review` = needs review (serious/warning), `unknown` = not readable or not evaluable. */
export type PlatformSeverity = "critical" | "serious" | "unknown";

export interface PlatformFindingItem {
  readonly key:
    | "platform-health-critical"
    | "platform-health-attention"
    | "platform-health-unknown"
    | "platform-health-unreadable"
    | "platform-scan-high"
    | "platform-scan-medium"
    | "platform-scan-stale"
    | "platform-scan-unreadable";
  readonly severity: PlatformSeverity;
  /** Lower ranks first inside a severity group. */
  readonly rank: number;
  /** The finding with its count, in plain words. */
  readonly what: string;
  readonly detail: string | null;
  /** At most {@link PLATFORM_MAX_NAMES} names, as the source sent them. */
  readonly names: readonly string[];
  readonly more: number;
  /** The age of the evidence this item is about; null when the source gave no time. */
  readonly at: string | null;
  readonly atLabel: string;
  /** The standard view that holds the detail; the brief item links there. */
  readonly href: string;
  readonly linkLabel: string;
  readonly count: number;
}

export interface PlatformFindings {
  readonly health: PlatformReadState<SystemHealth>;
  readonly scan: PlatformReadState<SecurityScanSummaryView>;
  readonly items: readonly PlatformFindingItem[];
}

export const PLATFORM_HEALTH_HREF = "?screen=operations&tab=health&standard=1";
export const PLATFORM_STATUS_HREF = "?screen=administration&tab=platform-status";

const deviceLabel = (d: SystemHealthDevice): string => d.hostname?.trim() || d.device_id;

const pluralDevices = (n: number, state: string): string =>
  `${n} ${n === 1 ? "device" : "devices"} ${state}`;

/** Ranks: act items 10–19, review 20–29, unknown 30+. Ties sort by count (more first), then by key. */
const RANK = {
  healthCritical: 10,
  scanHigh: 11,
  healthAttention: 20,
  scanMedium: 21,
  scanStale: 22,
  healthUnknown: 30,
  healthUnreadable: 31,
  scanUnreadable: 32,
} as const;

/** Open findings of one severity: `new` + `existing` (accepted ones are not open). */
function openCount(view: SecurityScanSummaryView, severity: "HIGH" | "MEDIUM"): number {
  let total = 0;
  for (const tool of Object.values(view.counts ?? {})) {
    const cell = tool?.[severity];
    if (cell) total += cell.new + cell.existing;
  }
  return total;
}

function healthItems(health: SystemHealth): PlatformFindingItem[] {
  const out: PlatformFindingItem[] = [];
  const at = health.computation.computed_at ?? null;
  const href = PLATFORM_HEALTH_HREF;
  const atLabel = "computed";
  const critical = health.devices.filter((d) => d.state === "CRITICAL");
  const attention = health.devices.filter((d) => d.state === "ATTENTION");
  const unknown = health.devices.filter((d) => d.state === "UNKNOWN");
  const window = health.window_days;
  const listed = (ds: SystemHealthDevice[]) => ({
    names: ds.slice(0, PLATFORM_MAX_NAMES).map(deviceLabel),
    more: Math.max(0, ds.length - PLATFORM_MAX_NAMES),
  });

  if (critical.length > 0) {
    out.push({
      key: "platform-health-critical", severity: "critical", rank: RANK.healthCritical, count: critical.length,
      what: `${pluralDevices(critical.length, "CRITICAL")} on device health (${window}-day window)`,
      detail: "State from the stored CPU, restart and crash reads; the device is listed in the standard view.",
      ...listed(critical), at, atLabel, href, linkLabel: "Open device health (standard view)",
    });
  }
  if (attention.length > 0) {
    out.push({
      key: "platform-health-attention", severity: "serious", rank: RANK.healthAttention, count: attention.length,
      what: `${pluralDevices(attention.length, "ATTENTION")} on device health (${window}-day window)`,
      detail: "State from the stored CPU, restart and crash reads; the device is listed in the standard view.",
      ...listed(attention), at, atLabel, href, linkLabel: "Open device health (standard view)",
    });
  }
  if (unknown.length > 0) {
    out.push({
      key: "platform-health-unknown", severity: "unknown", rank: RANK.healthUnknown, count: unknown.length,
      what: `${pluralDevices(unknown.length, "UNKNOWN")} on device health`,
      detail: "Evidence missing or not evaluable; not counted as fine.",
      ...listed(unknown), at, atLabel, href, linkLabel: "Open device health (standard view)",
    });
  }
  if (health.computation.state === "FAILED" || health.computation.state === "COMPUTING") {
    out.push({
      key: "platform-health-unreadable", severity: "unknown", rank: RANK.healthUnreadable, count: 0,
      what: health.computation.state === "FAILED"
        ? "Device health computation failed"
        : "Device health has not been computed yet",
      detail: "Not counted as fine until a snapshot is computed.",
      names: [], more: 0, at, atLabel, href, linkLabel: "Open device health (standard view)",
    });
  }
  return out;
}

function scanItems(view: SecurityScanSummaryView): PlatformFindingItem[] {
  const out: PlatformFindingItem[] = [];
  const at = view.generated_at ?? null;
  const atLabel = "scan";
  if (!view.available) {
    out.push({
      key: "platform-scan-unreadable", severity: "unknown", rank: RANK.scanUnreadable, count: 0,
      what: view.reason === "SUMMARY_UNREADABLE"
        ? "The daily security scan wrote a summary this brief cannot read"
        : "No daily security scan summary exists yet (the scan runs at 02:30)",
      detail: "Not counted as fine.",
      names: [], more: 0, at: null, atLabel: "scan", href: PLATFORM_STATUS_HREF, linkLabel: "Open platform status (standard view)",
    });
    return out;
  }
  const findings: readonly ScanFindingRow[] = view.findings ?? [];
  const listedInSummary = view.findings_listed_in_summary !== false;
  const openFindings = (severity: "HIGH" | "MEDIUM") =>
    findings.filter((f) => f.severity === severity && f.status !== "accepted");
  const high = openCount(view, "HIGH");
  const medium = openCount(view, "MEDIUM");
  const nameRows = (rows: readonly ScanFindingRow[]) =>
    rows.slice(0, PLATFORM_MAX_NAMES).map((f) => `${f.tool} ${f.rule} · ${f.location}`);
  const restNames = (rows: readonly ScanFindingRow[], total: number) => ({
    names: nameRows(rows),
    more: Math.max(0, total - Math.min(rows.length, PLATFORM_MAX_NAMES)),
  });
  const listNote = listedInSummary ? null : "the finding list is not in this summary; counts only";

  if (high > 0) {
    const rows = openFindings("HIGH");
    out.push({
      key: "platform-scan-high", severity: "critical", rank: RANK.scanHigh, count: high,
      what: `${high} open HIGH ${high === 1 ? "finding" : "findings"} in the daily security scan`,
      detail: ["new and existing; accepted findings excluded", listNote].filter(Boolean).join("; "),
      ...restNames(rows, high), at, atLabel, href: PLATFORM_STATUS_HREF, linkLabel: "Open platform status (standard view)",
    });
  }
  if (medium > 0) {
    const rows = openFindings("MEDIUM");
    out.push({
      key: "platform-scan-medium", severity: "serious", rank: RANK.scanMedium, count: medium,
      what: `${medium} open MEDIUM ${medium === 1 ? "finding" : "findings"} in the daily security scan`,
      detail: ["new and existing; accepted findings excluded", listNote].filter(Boolean).join("; "),
      ...restNames(rows, medium), at, atLabel, href: PLATFORM_STATUS_HREF, linkLabel: "Open platform status (standard view)",
    });
  }
  if (view.stale) {
    out.push({
      key: "platform-scan-stale", severity: "serious", rank: RANK.scanStale, count: 0,
      what: `The last daily scan finished ${view.age_hours ?? "UNKNOWN"} h ago`,
      detail: `Expected every ${view.stale_after_hours} h; check the security-daily job.`,
      names: [], more: 0, at, atLabel, href: PLATFORM_STATUS_HREF, linkLabel: "Open platform status (standard view)",
    });
  }
  return out;
}

/** Ranks the platform items: act before review before unknown, then by rank, then by count (more first), then by key. */
export function rankPlatformFindings(items: readonly PlatformFindingItem[]): PlatformFindingItem[] {
  const severityOrder: Record<PlatformSeverity, number> = { critical: 0, serious: 1, unknown: 2 };
  return [...items].sort(
    (a, b) =>
      severityOrder[a.severity] - severityOrder[b.severity] ||
      a.rank - b.rank ||
      b.count - a.count ||
      a.key.localeCompare(b.key),
  );
}

/** Builds the platform items from two reads. A source still loading yields no items for it; a failed or restricted one yields UNKNOWN. */
export function buildPlatformFindings(
  health: PlatformReadState<SystemHealth>,
  scan: PlatformReadState<SecurityScanSummaryView>,
): PlatformFindingItem[] {
  const items: PlatformFindingItem[] = [];
  if (health.state === "ok") {
    items.push(...healthItems(health.value));
  } else if (health.state === "failed" || health.state === "restricted") {
    items.push({
      key: "platform-health-unreadable", severity: "unknown", rank: RANK.healthUnreadable, count: 0,
      what: health.state === "restricted"
        ? "Device health is not available to this role"
        : "Device health could not be read",
      detail: "Not counted as fine.",
      names: [], more: 0, at: null, atLabel: "health", href: PLATFORM_HEALTH_HREF,
      linkLabel: "Open device health (standard view)",
    });
  }
  if (scan.state === "ok") {
    items.push(...scanItems(scan.value));
  } else if (scan.state === "failed" || scan.state === "restricted") {
    items.push({
      key: "platform-scan-unreadable", severity: "unknown", rank: RANK.scanUnreadable, count: 0,
      what: scan.state === "restricted"
        ? "The security scan summary is not available to this role"
        : "The daily security scan summary could not be read",
      detail: "Not counted as fine.",
      names: [], more: 0, at: null, atLabel: "scan", href: PLATFORM_STATUS_HREF,
      linkLabel: "Open platform status (standard view)",
    });
  }
  return rankPlatformFindings(items);
}

async function readHealth(): Promise<SystemHealth> {
  const next: unknown = await getSystemHealth();
  if (!isSystemHealth(next)) throw new Error("malformed device health body");
  return next;
}

async function readScan(): Promise<SecurityScanSummaryView> {
  const next = await getSecurityScanSummary();
  if (!next || typeof next !== "object" || typeof (next as { available?: unknown }).available !== "boolean") {
    throw new Error("malformed scan summary body");
  }
  return next;
}

function failedOrRestricted<T>(err: unknown): PlatformReadState<T> {
  return isRestricted(err) ? { state: "restricted" } : { state: "failed" };
}

/** Reads both platform sources once per mount and then every `intervalMs`. */
export function usePlatformFindings(intervalMs = 60_000): PlatformFindings {
  const [health, setHealth] = useState<PlatformReadState<SystemHealth>>({ state: "loading" });
  const [scan, setScan] = useState<PlatformReadState<SecurityScanSummaryView>>({ state: "loading" });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let live = true;
    readHealth()
      .then((value) => { if (live) setHealth({ state: "ok", value }); })
      .catch((err: unknown) => { if (live) setHealth(failedOrRestricted(err)); });
    readScan()
      .then((value) => { if (live) setScan({ state: "ok", value }); })
      .catch((err: unknown) => { if (live) setScan(failedOrRestricted(err)); });
    return () => { live = false; };
  }, [tick]);

  useEffect(() => {
    const id = window.setInterval(() => setTick((n) => n + 1), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);

  return { health, scan, items: buildPlatformFindings(health, scan) };
}
