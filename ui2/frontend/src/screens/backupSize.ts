import { formatBytes } from "../shell/bytes";
import { vendorLabel } from "../shell/presenters";

/**
 * Archive size on the Backups fleet table (Product Owner, 2026-11-02): a 28,537 MB Check Point backup sat on page 2 of
 * 131 rows and nothing on the screen surfaced it, while a normal Check Point backup is about 0.2 GB. Two things fix
 * that: the Size column sorts the whole filtered set, and a latest archive that is unusually large for its vendor
 * carries a "Large" marker and a filter chip. The rule here is computed client-side from the fleet rows already
 * loaded; nothing is asked of the backend.
 */

/** The Size header's three states: the default hostname order, largest first, smallest first. */
export type SizeSort = "none" | "desc" | "asc";

/** A click on the Size header: default -> largest first -> smallest first -> default. */
export function nextSizeSort(current: SizeSort): SizeSort {
  return current === "none" ? "desc" : current === "desc" ? "asc" : "none";
}

/**
 * Orders two rows by their latest archive size; a row without an archive (`null` size) sorts last whichever way the
 * column points. Equal sizes keep the caller's order (the sort is stable), so ties stay in hostname order.
 */
export function compareBySize(a: number | null, b: number | null, direction: Exclude<SizeSort, "none">): number {
  if (a === null && b === null) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  return direction === "desc" ? b - a : a - b;
}

/** A device's latest archive is "large" when it is at least this many times its vendor's median ... */
export const LARGE_BACKUP_MEDIAN_FACTOR = 10;
/** ... and at least this big (1 GiB), so a small vendor with tiny archives does not flag a modest one. */
export const LARGE_BACKUP_MIN_BYTES = 1024 * 1024 * 1024;

export interface SizedArchive {
  readonly vendor: string;
  readonly sizeBytes: number;
}

/** Why a row is flagged: the vendor whose fleet it was measured against, that fleet's median, and the ratio. */
export interface LargeBackupFlag {
  readonly vendor: string;
  readonly medianBytes: number;
  readonly factor: number;
}

/**
 * The median latest-archive size per vendor over the devices that have an archive (one entry per device, the fleet
 * rows as loaded). The median, not the mean, so one 28 GB archive does not lift the bar it is measured against.
 */
export function vendorMedianSizes(archives: readonly SizedArchive[]): ReadonlyMap<string, number> {
  const byVendor = new Map<string, number[]>();
  for (const a of archives) {
    if (!Number.isFinite(a.sizeBytes) || a.sizeBytes < 0) continue;
    const sizes = byVendor.get(a.vendor) ?? [];
    sizes.push(a.sizeBytes);
    byVendor.set(a.vendor, sizes);
  }
  const medians = new Map<string, number>();
  for (const [vendor, sizes] of byVendor) {
    sizes.sort((x, y) => x - y);
    const mid = Math.floor(sizes.length / 2);
    medians.set(vendor, sizes.length % 2 === 1 ? sizes[mid] : (sizes[mid - 1] + sizes[mid]) / 2);
  }
  return medians;
}

/**
 * Flags an archive that is at least {@link LARGE_BACKUP_MEDIAN_FACTOR} times its vendor's median and at least
 * {@link LARGE_BACKUP_MIN_BYTES}; `null` otherwise. A vendor with a single device is its own median (factor 1), so it
 * never flags; a zero median never flags either, rather than dividing by it.
 */
export function largeBackupFlag(archive: SizedArchive, medians: ReadonlyMap<string, number>): LargeBackupFlag | null {
  const medianBytes = medians.get(archive.vendor);
  if (medianBytes === undefined || medianBytes <= 0) return null;
  if (archive.sizeBytes < LARGE_BACKUP_MIN_BYTES) return null;
  const factor = archive.sizeBytes / medianBytes;
  if (factor < LARGE_BACKUP_MEDIAN_FACTOR) return null;
  return { vendor: archive.vendor, medianBytes, factor };
}

/** The marker's hover text: `10.3x the Check Point fleet median (277.0 MB)`. */
export function largeBackupTitle(flag: LargeBackupFlag): string {
  return `${flag.factor.toFixed(1)}x the ${vendorLabel(flag.vendor)} fleet median (${formatBytes(flag.medianBytes)})`;
}
