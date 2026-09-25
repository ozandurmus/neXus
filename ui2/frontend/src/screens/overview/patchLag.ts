import type { VersionSlice } from "../../auth/adminApi";

/**
 * Version and patch lag per vendor, in that vendor's own terms (PO 2026-09-25): a device is "behind" when a newer
 * build of the same line runs elsewhere in the fleet. Check Point: the jumbo hotfix take within a major version
 * ("R81.20 · Take 119"); Palo Alto: the maintenance build within a feature release ("11.1.10-h4" within 11.1).
 * Devices whose build was not read are left out (never counted as behind); a vendor with no such facts gives null.
 */
export interface Lag {
  readonly behind: number;
  readonly of: number;
  /** One line per version line that has devices behind: "R81.20: 18 below Take 161". */
  readonly lines: readonly string[];
  /** Per version line: the newest build and how many devices run it or an older one. */
  readonly groups: ReadonlyArray<{ line: string; newest: string; current: number; behind: number }>;
}

function checkPointTake(label: string): { line: string; take: number } | null {
  const m = /^(.+?)\s*(?:·|Jumbo)\s*Take\s+(\d+)$/i.exec(label.trim());
  return m ? { line: m[1].trim(), take: Number(m[2]) } : null;
}

function panBuild(label: string): { line: string; parts: number[] } | null {
  const m = /^(\d+)\.(\d+)\.(\d+)(?:-h(\d+))?$/.exec(label.trim());
  return m ? { line: `${m[1]}.${m[2]}`, parts: [Number(m[3]), Number(m[4] ?? 0)] } : null;
}

function lag<T>(slices: readonly VersionSlice[] | undefined, parse: (label: string) => T | null,
    lineOf: (v: T) => string, cmp: (a: T, b: T) => number, describe: (line: string, newest: T) => string): Lag | null {
  const read = (slices ?? []).flatMap((s) => {
    const v = s.label ? parse(s.label) : null;
    return v ? [{ v, count: s.count }] : [];
  });
  if (read.length === 0) return null;
  const newest = new Map<string, T>();
  for (const { v } of read) {
    const cur = newest.get(lineOf(v));
    if (cur === undefined || cmp(v, cur) > 0) newest.set(lineOf(v), v);
  }
  const behindByLine = new Map<string, number>();
  let behind = 0;
  let of = 0;
  for (const { v, count } of read) {
    of += count;
    if (cmp(v, newest.get(lineOf(v)) as T) < 0) {
      behind += count;
      behindByLine.set(lineOf(v), (behindByLine.get(lineOf(v)) ?? 0) + count);
    }
  }
  const lines = [...behindByLine.entries()].sort((a, b) => b[1] - a[1])
    .map(([line, n]) => `${n} on ${line} below ${describe(line, newest.get(line) as T)}`);
  const totalByLine = new Map<string, number>();
  for (const { v, count } of read) totalByLine.set(lineOf(v), (totalByLine.get(lineOf(v)) ?? 0) + count);
  const groups = [...totalByLine.entries()].sort((a, b) => b[1] - a[1]).map(([line, total]) => ({
    line, newest: describe(line, newest.get(line) as T), behind: behindByLine.get(line) ?? 0, current: total - (behindByLine.get(line) ?? 0) }));
  return { behind, of, lines, groups };
}

export function checkPointLag(minor: readonly VersionSlice[] | undefined): Lag | null {
  return lag(minor, checkPointTake, (v) => v.line, (a, b) => a.take - b.take, (_l, n) => `Take ${n.take}`);
}

export function paloAltoLag(minor: readonly VersionSlice[] | undefined): Lag | null {
  return lag(minor, panBuild, (v) => v.line, (a, b) => a.parts[0] - b.parts[0] || a.parts[1] - b.parts[1],
    (line, n) => `${line}.${n.parts[0]}${n.parts[1] ? `-h${n.parts[1]}` : ""}`);
}
