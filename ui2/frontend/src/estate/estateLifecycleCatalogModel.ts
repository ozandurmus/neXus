import type { LifecycleCatalogEntry, LifecycleDevice, LifecycleVulnerabilities } from "../auth/adminApi";
import { vulnerabilitySummary } from "../screens/LifecycleVulnerabilitiesPanel";

/** Catalog rows shown before "+N more" (the same cap the standard Lifecycle view applies to its catalog table). */
export const LIFECYCLE_CATALOG_CAP = 50;

/** A catalog row older than this (by its import stamp) is NOT_EVALUATED, the same rule the standard view states. */
export const LIFECYCLE_CATALOG_STALE_DAYS = 180;

const DAY_MS = 24 * 60 * 60 * 1000;

/** Whole days between the import stamp and `now`; null when the stamp cannot be read (age UNKNOWN). */
export function catalogRowAgeDays(importedAt: string | null | undefined, now: Date): number | null {
  if (!importedAt) return null;
  const at = Date.parse(importedAt);
  if (Number.isNaN(at)) return null;
  return Math.max(0, Math.floor((now.getTime() - at) / DAY_MS));
}

/**
 * A milestone date as shown. A blank or unreadable date is UNKNOWN: never a date, never OK.
 */
export function catalogMilestone(date: string | null | undefined): string {
  const text = typeof date === "string" ? date.trim() : "";
  return text.length > 0 ? text : "UNKNOWN";
}

export type CatalogRowStatus = "CURRENT" | "NOT_EVALUATED";

export interface LifecycleCatalogRow {
  readonly catalogId: string;
  readonly vendor: string;
  readonly kind: string;
  readonly product: string;
  readonly endOfSale: string;
  readonly endOfSupport: string;
  readonly endOfEngineering: string;
  readonly source: string;
  readonly note: string;
  readonly importedAt: string | null;
  readonly importedBy: string;
  readonly ageDays: number | null;
  readonly status: CatalogRowStatus;
  readonly reason: string | null;
}

/**
 * One catalog row for display. The age is the age of the evidence (the import stamp). A row older than 180 days, or a
 * row whose import stamp cannot be read, is NOT_EVALUATED: fail closed, never CURRENT by default.
 */
export function lifecycleCatalogRow(entry: LifecycleCatalogEntry, now: Date): LifecycleCatalogRow {
  const ageDays = catalogRowAgeDays(entry.imported_at, now);
  let status: CatalogRowStatus = "CURRENT";
  let reason: string | null = null;
  if (ageDays === null) {
    status = "NOT_EVALUATED";
    reason = "import age UNKNOWN";
  } else if (ageDays > LIFECYCLE_CATALOG_STALE_DAYS) {
    status = "NOT_EVALUATED";
    reason = `imported ${ageDays} days ago (older than ${LIFECYCLE_CATALOG_STALE_DAYS})`;
  }
  return {
    catalogId: entry.catalog_id,
    vendor: entry.vendor || "UNKNOWN",
    kind: entry.kind || "UNKNOWN",
    product: entry.product || "UNKNOWN",
    endOfSale: catalogMilestone(entry.end_of_sale),
    endOfSupport: catalogMilestone(entry.end_of_support),
    endOfEngineering: catalogMilestone(entry.end_of_engineering),
    source: entry.source || "UNKNOWN",
    note: entry.note?.trim() ? entry.note.trim() : "",
    importedAt: entry.imported_at || null,
    importedBy: entry.imported_by || "UNKNOWN",
    ageDays,
    status,
    reason,
  };
}

/** Rows sorted by vendor then product, so the capped list is stable across reloads. */
export function lifecycleCatalogRows(entries: readonly LifecycleCatalogEntry[], now: Date): LifecycleCatalogRow[] {
  return entries
    .map((entry) => lifecycleCatalogRow(entry, now))
    .sort((x, y) => x.vendor.localeCompare(y.vendor) || x.product.localeCompare(y.product) || x.catalogId.localeCompare(y.catalogId));
}

/** The count line: says what it counts. */
export function lifecycleCatalogCountText(total: number, shown: number): string {
  if (total === 0) return "No lifecycle catalog data";
  const more = total - shown;
  return more > 0
    ? `${shown} of ${total} catalog rows shown (+${more} more)`
    : `${total} catalog ${total === 1 ? "row" : "rows"}`;
}

/** Device vulnerability rows shown before "+N more" (the same cap the advisory and catalog lists use). */
export const LIFECYCLE_DEVICE_CAP = 50;

export interface DeviceVulnerabilityCell {
  /** "evaluated" only when the server judged the device; every other case reads UNKNOWN, never OK or a zero count. */
  readonly state: "evaluated" | "unknown";
  /** The cell as one line: the same words as the standard view's Vulnerabilities cell (vulnerabilitySummary). */
  readonly text: string;
  /** Age of the advisory data the judgement used (its load stamp); null when not reported. */
  readonly evidenceAt: string | null;
  /** Sort key: affected and exploited first, then UNKNOWN, then evaluated with nothing affected. */
  readonly sortRank: number;
}

/**
 * The Vulnerabilities cell of one device. Reuses vulnerabilitySummary from the standard Lifecycle view. A missing record
 * or a device that was not judged is UNKNOWN: fail closed, never a zero count or OK.
 */
export function deviceVulnerabilityCell(v: LifecycleVulnerabilities | null | undefined): DeviceVulnerabilityCell {
  const evidenceAt = v?.compared?.loaded_at ?? null;
  if (v?.state === "EVALUATED") {
    const affected = (v.affected ?? 0) > 0;
    const rank = !affected ? 4 : (v.affected_exploited ?? 0) > 0 ? 0 : (v.affected_critical_high ?? 0) > 0 ? 1 : 2;
    return { state: "evaluated", text: vulnerabilitySummary(v), evidenceAt, sortRank: rank };
  }
  return { state: "unknown", text: `UNKNOWN · ${vulnerabilitySummary(v)}`, evidenceAt, sortRank: 3 };
}

export interface LifecycleDeviceVulnerabilityRow {
  readonly deviceId: string;
  readonly label: string;
  readonly cell: DeviceVulnerabilityCell;
}

/** Device rows for the vulnerability card: sorted by rank then label, so the capped list is stable across reloads. */
export function lifecycleDeviceVulnerabilityRows(devices: readonly LifecycleDevice[]): LifecycleDeviceVulnerabilityRow[] {
  return devices
    .map((d) => ({ deviceId: d.device_id, label: d.hostname ?? d.device_id, cell: deviceVulnerabilityCell(d.vulnerabilities) }))
    .sort((x, y) => x.cell.sortRank - y.cell.sortRank || x.label.localeCompare(y.label) || x.deviceId.localeCompare(y.deviceId));
}

/** The count line for the device card: says what it counts. */
export function lifecycleDeviceCountText(total: number, shown: number): string {
  if (total === 0) return "No devices in the lifecycle read";
  const more = total - shown;
  return more > 0 ? `${shown} of ${total} devices shown (+${more} more)` : `${total} ${total === 1 ? "device" : "devices"}`;
}
