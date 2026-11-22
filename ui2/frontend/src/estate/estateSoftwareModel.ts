// Pure grouping and counting for the Estate "Software changes" section (Changes screen).
// Input is the same read the standard Recent upgrades panel uses (getSoftwareChanges(90)); no DOM, no fetch here.
import type { CpInstallEventWithDevice, SoftwareChange } from "../auth/adminApi";

export const SOFTWARE_CHANGE_WINDOW_DAYS = 90;
export const SOFTWARE_CHANGE_ROW_CAP = 10;

/** Read state of the software-change list. A failed read is never a "no changes" answer. */
export type SoftwareChangesRead =
  | { readonly status: "not_read" }
  | { readonly status: "loading" }
  | { readonly status: "failed" }
  | {
      readonly status: "ok";
      readonly days: number;
      readonly changes: readonly SoftwareChange[];
      readonly installEvents: readonly CpInstallEventWithDevice[];
    };

export interface SoftwareUnitCount {
  readonly deviceId: string;
  /** Estate unit label (already pseudonymised by the Estate model). Never the raw hostname. */
  readonly unitName: string;
  readonly count: number;
  /** Newest change of this unit: the evidence age the count is stamped with. */
  readonly newestAt: string | null;
}

export interface SoftwareChangeRow {
  readonly key: string;
  readonly deviceId: string;
  readonly unitName: string;
  readonly summary: string;
  /** The change's own timestamp (end of its change window, else first seen). */
  readonly at: string | null;
}

export interface EstateSoftwareChangesModel {
  readonly status: SoftwareChangesRead["status"];
  /** What the figure counts, in words. Never "no changes" unless the read succeeded. */
  readonly headline: string;
  readonly totalChanges: number;
  readonly days: number;
  readonly newestAt: string | null;
  readonly perUnit: readonly SoftwareUnitCount[];
  readonly rows: readonly SoftwareChangeRow[];
  readonly hiddenRows: number;
  readonly installEventLine: string | null;
}

type UnitLookup = ReadonlyMap<string, { readonly name: string }>;

const NOT_ON_WALL = "Unit not on this Estate wall";

function versionText(version: string | null, hotfix: string | null): string {
  return `${version ?? "UNKNOWN"}${hotfix ? ` (${hotfix})` : ""}`;
}

/** The change itself as one line: "R81.10 (Take 50) to R81.20 (Take 119)". */
export function softwareChangeSummary(c: Pick<SoftwareChange, "from_version" | "from_hotfix_level" | "to_version" | "to_hotfix_level">): string {
  return `${versionText(c.from_version, c.from_hotfix_level)} to ${versionText(c.to_version, c.to_hotfix_level)}`;
}

/** The change's own timestamp: the end of its window (first time the new version was seen), else first seen. */
export function softwareChangeAt(c: Pick<SoftwareChange, "window_end" | "first_seen_at">): string | null {
  return c.window_end ?? c.first_seen_at ?? null;
}

function newerFirst(a: string | null, b: string | null): number {
  const ta = a ? new Date(a).getTime() : Number.NEGATIVE_INFINITY;
  const tb = b ? new Date(b).getTime() : Number.NEGATIVE_INFINITY;
  return (Number.isNaN(tb) ? Number.NEGATIVE_INFINITY : tb) - (Number.isNaN(ta) ? Number.NEGATIVE_INFINITY : ta);
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

export function buildEstateSoftwareModel(read: SoftwareChangesRead, unitsById: UnitLookup): EstateSoftwareChangesModel {
  const days = SOFTWARE_CHANGE_WINDOW_DAYS;
  const empty = (status: SoftwareChangesRead["status"], headline: string): EstateSoftwareChangesModel => ({
    status,
    headline,
    totalChanges: 0,
    days,
    newestAt: null,
    perUnit: [],
    rows: [],
    hiddenRows: 0,
    installEventLine: null,
  });

  if (read.status === "not_read") return empty("not_read", "Software changes not read yet");
  if (read.status === "loading") return empty("loading", `Reading software changes for the last ${days} days…`);
  if (read.status === "failed") {
    return empty("failed", "Software changes could not be read. This is not the same as no changes.");
  }

  const windowDays = read.days || days;
  const changes = read.changes;
  const dated = changes
    .map((c, i) => ({ c, i, at: softwareChangeAt(c) }))
    .sort((x, y) => newerFirst(x.at, y.at) || x.i - y.i);

  const rows: SoftwareChangeRow[] = dated.map(({ c, i, at }) => ({
    key: `${c.device_id}-${at ?? "undated"}-${i}`,
    deviceId: c.device_id,
    unitName: unitsById.get(c.device_id)?.name ?? NOT_ON_WALL,
    summary: softwareChangeSummary(c),
    at,
  }));

  const byUnit = new Map<string, { count: number; newestAt: string | null }>();
  for (const r of rows) {
    const cur = byUnit.get(r.deviceId);
    if (!cur) byUnit.set(r.deviceId, { count: 1, newestAt: r.at });
    else {
      cur.count += 1;
      if (cur.newestAt === null || (r.at !== null && newerFirst(cur.newestAt, r.at) > 0)) cur.newestAt = r.at;
    }
  }
  const perUnit: SoftwareUnitCount[] = [...byUnit.entries()]
    .map(([deviceId, v]) => ({
      deviceId,
      unitName: unitsById.get(deviceId)?.name ?? NOT_ON_WALL,
      count: v.count,
      newestAt: v.newestAt,
    }))
    .sort((a, b) => b.count - a.count || a.unitName.localeCompare(b.unitName));

  const total = rows.length;
  const installCount = read.installEvents.length;
  return {
    status: "ok",
    headline:
      total === 0
        ? `No software changes in ${windowDays} days`
        : `${plural(total, "version change")} in ${windowDays} days`,
    totalChanges: total,
    days: windowDays,
    newestAt: rows[0]?.at ?? null,
    perUnit,
    rows: rows.slice(0, SOFTWARE_CHANGE_ROW_CAP),
    hiddenRows: Math.max(0, total - SOFTWARE_CHANGE_ROW_CAP),
    installEventLine:
      installCount > 0
        ? `${plural(installCount, "Check Point install log entry")} in ${windowDays} days (device clock, not observed by Overwatch)`
        : null,
  };
}
