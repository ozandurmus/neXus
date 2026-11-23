import { describe, expect, it } from "vitest";
import type { PolicyHygieneSummary, PolicyHygieneSummaryRow, PolicyMetadata } from "../src/auth/adminApi";
import { buildDossierHygieneModel, DOSSIER_HYGIENE_ROW_CAP } from "../src/estate/estateDossierHygieneModel";

// Synthetic identifiers only (FW-TANGO-04 style names, 192.0.2.x addresses).
const UNIT = "dev-tango-04";
const OTHER_UNIT = "dev-juliet-06";
const STAMP = "2026-11-20T10:00:00Z";

function policy(containerId: string, targets: string[], sourceId = "src-bravo"): PolicyMetadata {
  return {
    id: `${sourceId}:${containerId}`,
    sourceId,
    sourceName: "Panorama BRAVO",
    vendor: "PAN",
    containerId,
    containerName: `Container ${containerId}`,
    name: `Package ${containerId}`,
    collectedAt: STAMP,
    artefactRef: "",
    targets: targets.map((deviceId) => ({ deviceId, name: "FW-TANGO-04", context: "", syncStatus: "IN_SYNC" })),
    ruleCount: 10,
  };
}

function ready(containerId: string, rules: number, counts: Record<string, number>, sourceId = "src-bravo"): PolicyHygieneSummaryRow {
  return { sourceId, sourceName: "Panorama BRAVO", vendor: "PAN", containerId, containerName: `Container ${containerId}`, snapshotAt: STAMP, state: "READY", rules, counts };
}

function pending(containerId: string, sourceId = "src-bravo"): PolicyHygieneSummaryRow {
  return { sourceId, sourceName: "Panorama BRAVO", vendor: "PAN", containerId, containerName: `Container ${containerId}`, snapshotAt: STAMP, state: "PENDING" };
}

function summary(containers: PolicyHygieneSummaryRow[]): PolicyHygieneSummary {
  return { containers, totals: {}, rules: 0, pending: 0, days: 90 };
}

const OK_POLICIES = { ok: true as const, value: [policy("c-1", [UNIT])] };

describe("dossier policy hygiene: pending containers", () => {
  it("shows a PENDING container as pending and never as zero findings", () => {
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies: OK_POLICIES,
      summary: { ok: true, value: summary([pending("c-1")]) },
    });
    expect(m.rows).toHaveLength(1);
    expect(m.rows[0]?.kind).toBe("pending");
    expect(m.pendingLine).toMatch(/PENDING/);
    expect(m.pendingLine).toMatch(/not counted as zero/);
    expect(m.totals).toEqual([]);
    expect(m.badgeWord).toBe("PENDING");
    expect(m.badgeWord).not.toBe("No findings");
  });
});

describe("dossier policy hygiene: unreadable or malformed reads", () => {
  it("treats a failed summary read as UNKNOWN, never OK or zero", () => {
    const m = buildDossierHygieneModel({ deviceId: UNIT, policies: OK_POLICIES, summary: { ok: false } });
    expect(m.badge).toBe("unk");
    expect(m.badgeWord).toBe("UNKNOWN");
    expect(m.badge).not.toBe("ok");
    expect(m.countsLine).toMatch(/Not shown as OK or zero/);
    expect(m.rows).toEqual([]);
  });

  it("treats a malformed summary (no containers array) as UNKNOWN", () => {
    const malformed = { totals: {}, rules: 0, pending: 0, days: 90 } as unknown as PolicyHygieneSummary;
    const m = buildDossierHygieneModel({ deviceId: UNIT, policies: OK_POLICIES, summary: { ok: true, value: malformed } });
    expect(m.badge).toBe("unk");
    expect(m.badgeWord).toBe("UNKNOWN");
  });

  it("treats a failed policy container list as UNKNOWN, not not-applicable", () => {
    const m = buildDossierHygieneModel({ deviceId: UNIT, policies: { ok: false }, summary: { ok: true, value: summary([]) } });
    expect(m.badge).toBe("unk");
    expect(m.notApplicable).toBe(false);
  });

  it("treats a READY row with a non-numeric count as UNKNOWN, not as zero", () => {
    const bad = { ...ready("c-1", 5, {}), counts: { "any-rule-any-source": "NaN" } } as unknown as PolicyHygieneSummaryRow;
    const m = buildDossierHygieneModel({ deviceId: UNIT, policies: OK_POLICIES, summary: { ok: true, value: summary([bad]) } });
    expect(m.rows[0]?.kind).toBe("unknown");
    expect(m.badge).toBe("unk");
    expect(m.badgeWord).toBe("UNKNOWN");
    expect(m.badge).not.toBe("ok");
    expect(m.badgeWord).not.toBe("No findings");
    expect(m.totals).toEqual([]);
    expect(m.evidenceAt).toBeNull();
  });

  it("treats a READY row with missing or null counts as UNKNOWN, never No findings", () => {
    const missingCounts = { ...ready("c-1", 5, {}), counts: undefined } as unknown as PolicyHygieneSummaryRow;
    const nullCounts = { ...ready("c-1", 5, {}), counts: null } as unknown as PolicyHygieneSummaryRow;
    for (const bad of [missingCounts, nullCounts]) {
      const m = buildDossierHygieneModel({ deviceId: UNIT, policies: OK_POLICIES, summary: { ok: true, value: summary([bad]) } });
      expect(m.rows[0]?.kind).toBe("unknown");
      expect(m.badge).toBe("unk");
      expect(m.badgeWord).toBe("UNKNOWN");
      expect(m.badge).not.toBe("ok");
      expect(m.badgeWord).not.toBe("No findings");
      expect(m.evidenceAt).toBeNull();
    }
  });

  it("treats a READY row with NaN or Infinity counts or rules as UNKNOWN, never No findings", () => {
    const nanCount = ready("c-1", 5, { "shadowed-rule": Number.NaN });
    const infCount = ready("c-1", 5, { "shadowed-rule": Number.POSITIVE_INFINITY });
    const nanRules = ready("c-1", Number.NaN, {});
    for (const bad of [nanCount, infCount, nanRules]) {
      const m = buildDossierHygieneModel({ deviceId: UNIT, policies: OK_POLICIES, summary: { ok: true, value: summary([bad]) } });
      expect(m.rows[0]?.kind).toBe("unknown");
      expect(m.badge).toBe("unk");
      expect(m.badgeWord).toBe("UNKNOWN");
      expect(m.badge).not.toBe("ok");
      expect(m.badgeWord).not.toBe("No findings");
      expect(m.evidenceAt).toBeNull();
    }
  });

  it("a container with no summary row is UNKNOWN, not OK", () => {
    const m = buildDossierHygieneModel({ deviceId: UNIT, policies: OK_POLICIES, summary: { ok: true, value: summary([]) } });
    expect(m.rows[0]?.kind).toBe("unknown");
    expect(m.rows[0]?.snapshotAt).toBeNull();
    expect(m.badge).toBe("unk");
    expect(m.evidenceAt).toBeNull();
  });

  it("populates evidenceAt from the oldest container snapshotAt when readable and null on failed reads", () => {
    const older = "2026-11-19T08:00:00Z";
    const newer = "2026-11-20T10:00:00Z";
    const policies = { ok: true as const, value: [policy("c-1", [UNIT]), policy("c-2", [UNIT])] };
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies,
      summary: {
        ok: true,
        value: summary([
          { ...ready("c-1", 10, {}), snapshotAt: newer },
          { ...ready("c-2", 8, {}), snapshotAt: older },
        ]),
      },
    });
    expect(m.evidenceAt).toBe(older);
    expect(m.rows[0]?.snapshotAt).toBe(newer);
    expect(m.rows[1]?.snapshotAt).toBe(older);

    const failed = buildDossierHygieneModel({ deviceId: UNIT, policies: OK_POLICIES, summary: { ok: false } });
    expect(failed.evidenceAt).toBeNull();
  });
});

describe("dossier policy hygiene: totals and their labels", () => {
  it("sums counts over READY containers of this unit only and says what they count", () => {
    const policies = { ok: true as const, value: [policy("c-1", [UNIT]), policy("c-2", [UNIT]), policy("c-3", [OTHER_UNIT])] };
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies,
      summary: {
        ok: true,
        value: summary([
          ready("c-1", 40, { "shadowed-rule": 2, "any-source": 1 }),
          ready("c-2", 30, { "shadowed-rule": 3 }),
          ready("c-3", 99, { "shadowed-rule": 50 }),
        ]),
      },
    });
    expect(m.totals).toEqual([
      { finding: "shadowed-rule", rules: 5 },
      { finding: "any-source", rules: 1 },
    ]);
    expect(m.countsLine).toMatch(/rules per finding class/);
    expect(m.countsLine).toMatch(/READY/);
    expect(m.headline).toMatch(/2 READY/);
    expect(m.badge).toBe("watch");
  });

  it("does not present a row count as a package total when a container is unknown", () => {
    const policies = { ok: true as const, value: [policy("c-1", [UNIT]), policy("c-2", [UNIT])] };
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies,
      summary: { ok: true, value: summary([ready("c-1", 40, { "shadowed-rule": 2 })]) },
    });
    expect(m.badge).toBe("unk");
    expect(m.headline).toMatch(/1 UNKNOWN/);
    expect(m.headline).not.toMatch(/2 READY/);
  });

  it("a clean READY unit says no findings, not a pass on unknown data", () => {
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies: OK_POLICIES,
      summary: { ok: true, value: summary([ready("c-1", 12, {})]) },
    });
    expect(m.badge).toBe("ok");
    expect(m.badgeWord).toBe("No findings");
    expect(m.totals).toEqual([]);
  });
});

describe("dossier policy hygiene: cap", () => {
  it("prints +N more when more than the cap of containers belong to the unit", () => {
    const total = DOSSIER_HYGIENE_ROW_CAP + 3;
    const ids = Array.from({ length: total }, (_, i) => `c-${i + 1}`);
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies: { ok: true, value: ids.map((id) => policy(id, [UNIT])) },
      summary: { ok: true, value: summary(ids.map((id) => ready(id, 1, {}))) },
    });
    expect(m.rows).toHaveLength(DOSSIER_HYGIENE_ROW_CAP);
    expect(m.moreLabel).toBe("+3 more");
    expect(m.headline).toMatch(new RegExp(`${total} READY`));
  });
});

describe("dossier policy hygiene: not applicable", () => {
  it("a unit with no policy container renders not-applicable, never a pass", () => {
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies: { ok: true, value: [policy("c-1", [OTHER_UNIT])] },
      summary: { ok: true, value: summary([ready("c-1", 5, {})]) },
    });
    expect(m.notApplicable).toBe(true);
    expect(m.badge).toBe("na");
    expect(m.badgeWord).toBe("Not applicable");
    expect(m.headline).toMatch(/Not applicable/);
    expect(m.badge).not.toBe("ok");
    expect(m.rows).toEqual([]);
  });

  it("a unit with an empty policy list is not applicable", () => {
    const m = buildDossierHygieneModel({ deviceId: UNIT, policies: { ok: true, value: [] }, summary: { ok: true, value: summary([]) } });
    expect(m.notApplicable).toBe(true);
    expect(m.badgeWord).toBe("Not applicable");
  });
});

describe("dossier policy hygiene: block evidence age", () => {
  it("a container with an unparseable snapshot time nulls the block stamp, not the age of the other containers", () => {
    const badRow = { ...ready("c-2", 4, {}), snapshotAt: "not-a-time" };
    const m = buildDossierHygieneModel({
      deviceId: UNIT,
      policies: { ok: true, value: [policy("c-1", [UNIT]), policy("c-2", [UNIT])] },
      summary: { ok: true, value: summary([ready("c-1", 4, {}), badRow]) },
    });
    expect(m.rows.find((r) => r.key.includes("c-2"))?.snapshotAt).toBeNull();
    expect(m.evidenceAt).toBeNull();
  });
});
