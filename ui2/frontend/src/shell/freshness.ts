import type { StateRole } from "../theme/tokens";
import { STATUS } from "./Charts";

/**
 * The product's one data-freshness scale (UI review 2026-11-05, B1; Argon R-01, Fable F-3). Every freshness pill, chip,
 * dot, bucket and banner takes its thresholds and colours from here, so the same age has the same colour on every screen.
 *
 *   fresh    under 24 h            green   (`ok`)
 *   ageing   24 h up to 72 h       amber   (`warn`)
 *   stale    over 72 h             red     (`bad`)
 *   unknown  no read on record     grey    (`unknown`) -- never fresh, never zero
 *
 * The bucket edges are the server's (`OverviewService.inventoryAge`: `age < 1 d`, `age <= 3 d`, else older), so the
 * Overview's counts and a filtered device list never disagree about which side of an edge a device is on. Age only
 * colours a presentation; it never changes a status value.
 */
export type FreshnessLevel = "fresh" | "ageing" | "stale" | "unknown";

export const FRESH_UNDER_MS = 24 * 3_600_000;
export const AGEING_UNTIL_MS = 72 * 3_600_000;

/**
 * The stale-evidence banner shows when fewer than this share of the active devices were read in the last 24 h
 * (Argon R-01: "whenever <80% of active devices were read in the last 24 h").
 */
export const STALE_BANNER_MIN_FRESH_SHARE = 0.8;

export const FRESHNESS_LABEL: Readonly<Record<FreshnessLevel, string>> = {
  fresh: "under 24 h",
  ageing: "24–72 h",
  stale: "over 72 h",
  unknown: "never read",
};

export const FRESHNESS_ROLE: Readonly<Record<FreshnessLevel, StateRole>> = {
  fresh: "ok",
  ageing: "warn",
  stale: "bad",
  unknown: "unknown",
};

/** The chart colour of each level (dots, donut slices, bars). */
export const FRESHNESS_COLOR: Readonly<Record<FreshnessLevel, string>> = {
  fresh: STATUS.good,
  ageing: STATUS.warning,
  stale: STATUS.critical,
  unknown: STATUS.neutral,
};

/** The level of an age in milliseconds; null (no read, unparseable time) is `unknown`. */
export function freshnessOfAge(ageMs: number | null | undefined): FreshnessLevel {
  if (ageMs === null || ageMs === undefined || Number.isNaN(ageMs)) return "unknown";
  const age = Math.max(0, ageMs);
  if (age < FRESH_UNDER_MS) return "fresh";
  if (age <= AGEING_UNTIL_MS) return "ageing";
  return "stale";
}

/** The level of a read time (ISO string or epoch ms) at `now`. */
export function freshnessOf(at: string | number | null | undefined, now: Date | number = Date.now()): FreshnessLevel {
  if (at === null || at === undefined || at === "") return "unknown";
  const t = typeof at === "number" ? at : new Date(at).getTime();
  if (Number.isNaN(t)) return "unknown";
  return freshnessOfAge((typeof now === "number" ? now : now.getTime()) - t);
}

/** True once a read is no longer fresh (24 h or older): the edge the readiness and configuration flags use. */
export function isPastFresh(at: string | number | null | undefined, now: Date | number = Date.now()): boolean {
  const level = freshnessOf(at, now);
  return level === "ageing" || level === "stale";
}

/** Whether a device read at `at` falls in an Overview age bucket (the device list's `inventory_age` filter). */
export function inFreshnessBucket(at: string | null | undefined, bucket: string, now: number = Date.now()): boolean {
  const level = freshnessOf(at, now);
  switch (bucket) {
    case "stale": return level !== "fresh"; // "devices without evidence, 24 h": everything not read in the last day
    case "lt24h": return level === "fresh";
    case "24h_72h": return level === "ageing";
    case "gt72h": return level === "stale";
    case "never": return level === "unknown";
    default: return true;
  }
}

export interface FleetFreshness {
  /** Active devices read in the last 24 h. */
  readonly fresh: number;
  /** Active devices (the population the share is taken over). */
  readonly of: number;
  /** Active devices never read. */
  readonly never?: number;
}

/**
 * Whether the stale-evidence banner shows: fewer than 80 % of the active devices read in 24 h. An empty estate shows no
 * banner (there is nothing to be stale); an unknown population (null) is decided by the caller.
 */
export function evidenceIsStale(f: FleetFreshness): boolean {
  if (f.of <= 0) return false;
  return f.fresh / f.of < STALE_BANNER_MIN_FRESH_SHARE;
}

/** "131 of 132 active devices were last read more than 24 h ago" (plain words, C7). */
export function staleSentence(f: FleetFreshness): string {
  const older = Math.max(0, f.of - f.fresh);
  const never = f.never ?? 0;
  const read = older - never;
  const parts: string[] = [];
  if (read > 0) parts.push(`${read} of ${f.of} active devices were last read more than 24 h ago`);
  if (never > 0) parts.push(`${never} ${never === 1 ? "has" : "have"} never been read`);
  return parts.length ? parts.join("; ") : `${f.fresh} of ${f.of} active devices were read in the last 24 h`;
}
