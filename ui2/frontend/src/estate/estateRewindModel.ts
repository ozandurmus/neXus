import type { EstateAsOfFigure, EstateAsOfUnit, EstateAsOfView } from "../auth/adminApi";
import type { BadgeState } from "./estateModel";
import { BACKUP_STALE_DAYS } from "../screens/overview/attentionRules";

/** Lenses the as-of endpoint answers exactly (stored evidence `<= T`). Exposure, install and age are not answered. */
export const REWIND_LENSES = ["failover", "backups", "compliance"] as const;
export type RewindLens = (typeof REWIND_LENSES)[number];

export const REWIND_LENS_LABEL: Readonly<Record<RewindLens, string>> = {
  failover: "Failover",
  backups: "Backups",
  compliance: "Controls",
};

export const REWIND_WINDOW_DAYS = 30;

export interface RewindAnswer {
  readonly state: BadgeState;
  readonly word: string;
  readonly detail: string | null;
  readonly evidenceAt: string | null;
}

export interface RewindUnit {
  readonly id: string;
  readonly name: string;
  readonly vendor: string | null;
  readonly clusterRef: string | null;
  readonly enrolled: boolean;
  readonly lenses: Readonly<Record<RewindLens, RewindAnswer>>;
}

export interface RewindSnapshot {
  readonly asOf: string;
  readonly warnings: readonly string[];
  readonly units: readonly RewindUnit[];
}

/** Severity rank used to say "got worse" / "got better"; UNKNOWN ranks between action and review, as in the wall. */
const RANK: Readonly<Record<BadgeState, number>> = { act: 0, unk: 1, watch: 2, ok: 3, na: 4 };

function unknownAnswer(reason: string | null | undefined): RewindAnswer {
  return { state: "unk", word: "UNKNOWN", detail: reason ? humanReason(reason) : "History does not reach this moment", evidenceAt: null };
}

function humanReason(code: string): string {
  return code.toLowerCase().replace(/_/g, " ");
}

function failoverAnswer(f: EstateAsOfFigure | undefined): RewindAnswer {
  const at = f?.evidence_at ?? null;
  switch (f?.verdict) {
    case "READY": return { state: "ok", word: "Ready", detail: null, evidenceAt: at };
    case "STALE": return { state: "watch", word: "Ready, stale", detail: "Server rule: readiness older than 24 h at that moment (not the live view's rule)", evidenceAt: at };
    case "NOT_READY": return { state: "act", word: "Not ready", detail: f.reason ? humanReason(f.reason) : null, evidenceAt: at };
    case "NOT_APPLICABLE": return { state: "na", word: "Not applicable", detail: null, evidenceAt: null };
    default: return unknownAnswer(f?.reason);
  }
}

function ageDays(at: string | null, asOf: string | null): number | null {
  if (!at || !asOf) return null;
  const a = Date.parse(at);
  const b = Date.parse(asOf);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
  return Math.max(0, Math.floor((b - a) / 86_400_000));
}

/**
 * Backup answer at a moment. The same BACKUP_STALE_DAYS rule as the live view applies: the endpoint's STALE verdict
 * (24 h) is re-read against the archive's own evidence time, and the archive age at that moment is always stated.
 */
function backupAnswer(f: EstateAsOfFigure | undefined, asOf: string | null): RewindAnswer {
  const at = f?.evidence_at ?? null;
  const days = ageDays(at, asOf);
  const ageNote = days === null ? null : `Newest archive ${days === 0 ? "under 1 day" : `${days} d`} old at that moment`;
  switch (f?.verdict) {
    case "OK": return { state: "ok", word: "Stored", detail: ageNote, evidenceAt: at };
    case "STALE":
      if (days !== null && days < BACKUP_STALE_DAYS) return { state: "ok", word: "Stored", detail: ageNote, evidenceAt: at };
      return { state: "watch", word: "Stale", detail: ageNote ?? `Newest archive older than ${BACKUP_STALE_DAYS} days at that moment`, evidenceAt: at };
    case "ANOMALY": return { state: "watch", word: "Size anomaly", detail: ["Open size anomaly", ageNote].filter(Boolean).join("; "), evidenceAt: at };
    case "MISSING": return { state: "act", word: "No backup", detail: f.reason ? humanReason(f.reason) : null, evidenceAt: null };
    case "NOT_APPLICABLE": return { state: "na", word: "Not applicable", detail: null, evidenceAt: null };
    default: return unknownAnswer(f?.reason);
  }
}

function complianceAnswer(f: EstateAsOfFigure | undefined): RewindAnswer {
  const at = f?.evidence_at ?? null;
  if (f?.state === "EVIDENCED") {
    const fail = f.fail_count ?? 0;
    const crit = f.critical_fail_count ?? 0;
    const pass = f.pass_count ?? 0;
    if (crit > 0) return { state: "act", word: `${fail} fail`, detail: `${crit} critical`, evidenceAt: at };
    if (fail > 0) return { state: "watch", word: `${fail} fail`, detail: null, evidenceAt: at };
    if (pass === 0) return { state: "unk", word: "UNKNOWN", detail: "No check passed or failed", evidenceAt: at };
    return { state: "ok", word: "All pass", detail: null, evidenceAt: at };
  }
  if (f?.state === "NOT_APPLICABLE") return { state: "na", word: "Not applicable", detail: null, evidenceAt: null };
  return unknownAnswer(f?.reason);
}

export function buildRewindUnit(u: EstateAsOfUnit, asOf: string | null = null): RewindUnit {
  return {
    id: u.device_id,
    name: u.hostname || u.device_id,
    vendor: u.vendor ?? null,
    clusterRef: u.cluster_member_ref ?? null,
    enrolled: u.enrolled_at_moment !== false,
    lenses: {
      failover: failoverAnswer(u.failover),
      backups: backupAnswer(u.backup, asOf),
      compliance: complianceAnswer(u.compliance),
    },
  };
}

export function buildRewindSnapshot(view: EstateAsOfView): RewindSnapshot {
  return {
    asOf: view.as_of,
    warnings: (view.warnings ?? []).map((w) => w.message ?? w.code ?? "warning"),
    units: (Array.isArray(view.units) ? view.units : []).map((u) => buildRewindUnit(u, view.as_of)),
  };
}

export interface RewindTransition {
  readonly unit: RewindUnit;
  readonly lens: RewindLens;
  readonly then: RewindAnswer;
  readonly now: RewindAnswer;
  readonly direction: "worse" | "better" | "changed";
}

/** Only differences. Units present in one snapshot only are compared as UNKNOWN on the missing side. */
export function diffSnapshots(a: RewindSnapshot, b: RewindSnapshot): readonly RewindTransition[] {
  const byA = new Map(a.units.map((u) => [u.id, u]));
  const out: RewindTransition[] = [];
  for (const ub of b.units) {
    const ua = byA.get(ub.id);
    for (const lens of REWIND_LENSES) {
      const then = ua ? ua.lenses[lens] : unknownAnswer("ENROLLED_AFTER_MOMENT");
      const now = ub.lenses[lens];
      if (then.state === now.state && then.word === now.word) continue;
      const direction = then.state === now.state ? "changed" : RANK[now.state] < RANK[then.state] ? "worse" : "better";
      out.push({ unit: ub, lens, then, now, direction });
    }
  }
  return out;
}

/** Tile ring for a unit that moved (or did not) between the two moments. Token names only; the screen maps them to ev.*. */
export interface RewindRing {
  readonly color: "act" | "ok" | "ink3" | null;
  readonly word: string;
  readonly dashed: boolean;
}

/** worse = act, better = ok, same rank but changed = dashed ink3, no transition = no ring (shown faded). */
export function rewindRingFor(direction: "worse" | "better" | "changed" | null): RewindRing {
  if (direction === "worse") return { color: "act", word: "worse", dashed: false };
  if (direction === "better") return { color: "ok", word: "better", dashed: false };
  if (direction === "changed") return { color: "ink3", word: "unchanged", dashed: true };
  return { color: null, word: "unchanged", dashed: false };
}

export interface LensCount {
  readonly act: number;
  readonly watch: number;
  readonly ok: number;
  readonly unk: number;
  readonly na: number;
  readonly newestAt: string | null;
  readonly oldestAt: string | null;
}

export function countLens(s: RewindSnapshot, lens: RewindLens): LensCount {
  const c = { act: 0, watch: 0, ok: 0, unk: 0, na: 0 };
  let newest: string | null = null;
  let oldest: string | null = null;
  for (const u of s.units) {
    const a = u.lenses[lens];
    c[a.state] += 1;
    if (a.evidenceAt) {
      if (!newest || a.evidenceAt > newest) newest = a.evidenceAt;
      if (!oldest || a.evidenceAt < oldest) oldest = a.evidenceAt;
    }
  }
  return { ...c, newestAt: newest, oldestAt: oldest };
}

/** Evidence times inside the last 30 days (newest last), the only moments the slider snaps to. Ends at `now`. */
export function snapPoints(view: EstateAsOfView | null, now: Date): readonly string[] {
  const floor = now.getTime() - REWIND_WINDOW_DAYS * 86_400_000;
  const set = new Set<string>();
  for (const u of view?.units ?? []) {
    for (const f of [u.failover, u.backup, u.compliance]) {
      const at = f?.evidence_at;
      if (!at) continue;
      const t = Date.parse(at);
      if (Number.isFinite(t) && t >= floor && t <= now.getTime()) set.add(new Date(t).toISOString());
    }
  }
  return [...set].sort();
}

/** Index of the snap point nearest to `targetMs`; -1 when there are none. */
export function nearestSnap(points: readonly string[], targetMs: number): number {
  let best = -1;
  let bestD = Infinity;
  points.forEach((p, i) => {
    const d = Math.abs(Date.parse(p) - targetMs);
    if (d < bestD) { bestD = d; best = i; }
  });
  return best;
}
