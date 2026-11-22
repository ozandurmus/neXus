import type { LifecycleCatalogEntry } from "../auth/adminApi";

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
