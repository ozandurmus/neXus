import { describe, expect, it } from "vitest";
import type { EstateAsOfUnit, EstateAsOfView } from "../src/auth/adminApi";
import {
  REWIND_TRANSITION_CAP,
  buildRewindSnapshot,
  countLens,
  diffSnapshots,
  mergeRewindWarnings,
  nearestSnap,
  rewindRingFor,
  snapPoints,
  transitionSummaryLine,
} from "../src/estate/estateRewindModel";
import { buildRewindUnit } from "../src/estate/estateRewindModel";
import { resolveEstateRoute, rewindHref } from "../src/estate/estateRouting";

function view(asOf: string, verdict: string, evidenceAt: string | null, extra: Partial<EstateAsOfView> = {}): EstateAsOfView {
  return {
    as_of: asOf,
    generated_at: asOf,
    units: [
      {
        device_id: "dev-1",
        hostname: "FW-TANGO-04",
        failover: { verdict, evidence_at: evidenceAt, reason: verdict === "UNKNOWN" ? "NO_RUN_AT_MOMENT" : null },
        backup: { verdict: "OK", evidence_at: evidenceAt },
        compliance: { state: "EVIDENCED", pass_count: 3, fail_count: 0, critical_fail_count: 0, evidence_at: evidenceAt },
      },
    ],
    ...extra,
  };
}

describe("estate rewind model", () => {
  it("maps verdicts and keeps UNKNOWN with no evidence time", () => {
    const s = buildRewindSnapshot(view("2026-11-12T02:00:00Z", "UNKNOWN", null));
    expect(s.units[0].lenses.failover).toMatchObject({ state: "unk", word: "UNKNOWN", evidenceAt: null });
    expect(s.units[0].lenses.compliance.state).toBe("ok");
  });

  it("does not read an EVIDENCED figure with a missing fail count as All pass", () => {
    const v = view("2026-11-12T02:00:00Z", "READY", "2026-11-12T01:00:00Z");
    const unit = v.units[0] as unknown as { compliance: Record<string, unknown> };
    unit.compliance = { state: "EVIDENCED", pass_count: 5, critical_fail_count: 0, evidence_at: "2026-11-12T01:00:00Z" };
    const s = buildRewindSnapshot(v);
    expect(s.units[0].lenses.compliance).toMatchObject({ state: "unk", word: "UNKNOWN", evidenceAt: null });
    expect(s.units[0].lenses.compliance.state).not.toBe("ok");
  });

  it("lists only differences and states direction", () => {
    const a = buildRewindSnapshot(view("2026-11-12T02:00:00Z", "READY", "2026-11-12T01:00:00Z"));
    const b = buildRewindSnapshot(view("2026-11-19T02:00:00Z", "NOT_READY", "2026-11-18T01:00:00Z"));
    const d = diffSnapshots(a, b);
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ lens: "failover", direction: "worse" });
    expect(diffSnapshots(a, a)).toHaveLength(0);
  });

  it("counts a lens with newest and oldest evidence", () => {
    const c = countLens(buildRewindSnapshot(view("2026-11-12T02:00:00Z", "READY", "2026-11-12T01:00:00Z")), "failover");
    expect(c).toMatchObject({ ok: 1, newestAt: "2026-11-12T01:00:00Z" });
  });

  it("snaps to evidence times inside the 30-day window only", () => {
    const now = new Date("2026-11-20T12:00:00Z");
    const v = view("2026-11-20T12:00:00Z", "READY", "2026-11-19T01:00:00Z");
    const old = view("x", "READY", "2026-09-01T00:00:00Z");
    expect(snapPoints(old, now)).toEqual([]);
    const pts = snapPoints(v, now);
    expect(pts).toEqual(["2026-11-19T01:00:00.000Z"]);
    expect(nearestSnap(pts, Date.parse("2026-11-01T00:00:00Z"))).toBe(0);
    expect(nearestSnap([], 0)).toBe(-1);
  });

  it("routes the rewind view", () => {
    const r = resolveEstateRoute("inventory", "/", "?screen=inventory&view=rewind&at=2026-11-12T02:00:00Z");
    expect(r).toEqual({ kind: "rewind", at: "2026-11-12T02:00:00Z", compare: null });
    expect(rewindHref("2026-11-12T02:00:00.000Z", "2026-11-19T00:00:00.000Z")).toContain("compare=");
  });
});

describe("rewindRingFor", () => {
  it("maps worse to act, better to ok and a same-rank change to a dashed ink3 ring", () => {
    expect(rewindRingFor("worse")).toEqual({ color: "act", word: "worse", dashed: false });
    expect(rewindRingFor("better")).toEqual({ color: "ok", word: "better", dashed: false });
    expect(rewindRingFor("changed")).toEqual({ color: "ink3", word: "changed", dashed: true });
  });

  it("gives no ring and no dash when the unit did not move", () => {
    expect(rewindRingFor(null)).toEqual({ color: null, word: "unchanged", dashed: false });
  });

  it("names a same-rank change as changed, never unchanged, in the tile label", () => {
    const answer = (word: string) => ({ state: "ok", word, detail: null, evidenceAt: null });
    const unit = { id: "dev-x", name: "FW-TANGO-04", vendor: null, clusterRef: null, enrolled: true,
      lenses: { failover: answer("READY"), backups: answer("OK"), compliance: answer("OK") } };
    const unitChanged = { ...unit, lenses: { ...unit.lenses, failover: answer("READY (re-read)") } };
    const transitions = diffSnapshots(
      { asOf: "2026-11-12T02:00:00.000Z", warnings: [], units: [unit] } as never,
      { asOf: "2026-11-19T00:00:00.000Z", warnings: [], units: [unitChanged] } as never,
    );
    expect(transitions).toHaveLength(1);
    expect(transitions[0].direction).toBe("changed");
    const ring = rewindRingFor(transitions[0].direction);
    const label = `FW-TANGO-04: READY; ${ring.word}; evidence UNKNOWN`;
    expect(label).toContain("changed");
    expect(label).not.toContain("unchanged");
    expect(ring.word).not.toBe("unchanged");
  });
});

describe("transition summary and warnings", () => {
  it("states the transition count with unit words and the lens label", () => {
    expect(REWIND_TRANSITION_CAP).toBe(25);
    expect(transitionSummaryLine(1, "failover")).toBe("1 transition on Failover");
    expect(transitionSummaryLine(3, "backups")).toBe("3 transitions on Backups");
    expect(transitionSummaryLine(0, "compliance")).toBe("0 transitions on Controls");
  });

  it("merges warnings from Moment A and B only when compared, deduplicated and non-empty", () => {
    expect(mergeRewindWarnings(["w1", " ", "w2"], ["w2", "w3"], true)).toEqual(["w1", "w2", "w3"]);
    expect(mergeRewindWarnings(["w1"], ["w9"], false)).toEqual(["w1"]);
    expect(mergeRewindWarnings(undefined, undefined, true)).toEqual([]);
  });
});

describe("backups lens threshold rule", () => {
  const ASOF = "2026-11-23T12:00:00Z";
  const unitWith = (backup: Record<string, unknown>): EstateAsOfUnit => ({
    device_id: "dev-tango-04",
    hostname: "FW-TANGO-04",
    failover: { verdict: "READY", evidence_at: "2026-11-23T10:00:00Z" },
    backup: backup as EstateAsOfUnit["backup"],
    compliance: { state: "NOT_APPLICABLE" },
  });
  const daysBefore = (days: number): string => new Date(Date.parse(ASOF) - days * 86_400_000).toISOString();

  it("OK verdict is Stored and keeps its evidence time", () => {
    const at = daysBefore(1);
    const a = buildRewindUnit(unitWith({ verdict: "OK", evidence_at: at }), ASOF).lenses.backups;
    expect(a).toMatchObject({ state: "ok", word: "Stored", evidenceAt: at });
  });

  it("STALE verdict with archive evidence 6 days old is still Stored", () => {
    const at = daysBefore(6);
    const a = buildRewindUnit(unitWith({ verdict: "STALE", evidence_at: at }), ASOF).lenses.backups;
    expect(a).toMatchObject({ state: "ok", word: "Stored", evidenceAt: at });
    expect(a.detail).toContain("6 d old");
  });

  it("STALE verdict with archive evidence 8 days old is Stale and states the archive age", () => {
    const at = daysBefore(8);
    const a = buildRewindUnit(unitWith({ verdict: "STALE", evidence_at: at }), ASOF).lenses.backups;
    expect(a).toMatchObject({ state: "watch", word: "Stale", evidenceAt: at });
    expect(a.detail).toContain("8 d old");
  });

  it("ANOMALY verdict is a Size anomaly watch", () => {
    const at = daysBefore(1);
    const a = buildRewindUnit(unitWith({ verdict: "ANOMALY", evidence_at: at }), ASOF).lenses.backups;
    expect(a).toMatchObject({ state: "watch", word: "Size anomaly", evidenceAt: at });
  });

  it("MISSING verdict is No backup with no evidence time", () => {
    const a = buildRewindUnit(unitWith({ verdict: "MISSING", evidence_at: null, reason: "NO_ARCHIVE" }), ASOF).lenses.backups;
    expect(a).toMatchObject({ state: "act", word: "No backup", evidenceAt: null });
  });

  it("absent or unrecognised verdict is UNKNOWN, never ok", () => {
    const absent = buildRewindUnit(unitWith({}), ASOF).lenses.backups;
    expect(absent).toMatchObject({ state: "unk", word: "UNKNOWN", evidenceAt: null });
    const absentFigure = buildRewindUnit({ device_id: "dev-tango-05", hostname: "FW-TANGO-05" }, ASOF).lenses.backups;
    expect(absentFigure.state).toBe("unk");
    const odd = buildRewindUnit(unitWith({ verdict: "SOMETHING_NEW", evidence_at: daysBefore(1) }), ASOF).lenses.backups;
    expect(odd.state).toBe("unk");
    expect(odd.word).not.toBe("Stored");
  });
});
