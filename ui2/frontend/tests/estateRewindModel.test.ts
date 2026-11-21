import { describe, expect, it } from "vitest";
import type { EstateAsOfView } from "../src/auth/adminApi";
import {
  buildRewindSnapshot,
  countLens,
  diffSnapshots,
  nearestSnap,
  snapPoints,
} from "../src/estate/estateRewindModel";
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
