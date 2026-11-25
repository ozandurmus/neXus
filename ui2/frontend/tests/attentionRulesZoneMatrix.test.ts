import { describe, expect, it } from "vitest";

import type { ChangeFeedPage, CpFailoverSummary, DeviceSummary, LifecycleFleet, OverviewView } from "../src/auth/adminApi";
import { buildAttention, RULES, sourceWanted, type AttentionInputs, type SourceRead } from "../src/screens/overview/attentionRules";
import { parseZoneMatrixAttention } from "../src/screens/overview/zoneMatrixAttention";

/**
 * The zone matrix row of the Overview attention list (docs/design/ZONE_MATRIX_CONTRACT.md 7.2, ZM-26, ZM-35).
 * Synthetic bodies in the shape of ZoneMatrixSummaryView; names and counts are made up.
 */
const NOW = new Date("2026-11-25T09:00:00Z");
const ago = (h: number) => new Date(NOW.getTime() - h * 3_600_000).toISOString();

const overview = {
  generated_at: NOW.toISOString(), masked: true, estate: { devices: [], counts: {}, no_archive: 0, compliance_state: "OK", facts: {} },
  exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] } },
} as unknown as OverviewView;

const base: AttentionInputs = {
  now: NOW, overview,
  failover: { state: "ok", value: { devices: [] as DeviceSummary[], rows: [] as CpFailoverSummary[] } },
  lifecycle: { state: "ok", value: { as_of: NOW.toISOString(), devices: [], summary: {} } as LifecycleFleet },
  changes: { state: "ok", value: { events: [] } as unknown as ChangeFeedPage },
  controls: [],
};

const CLEAN_HEADLINE = {
  version_id: "v-1", version_number: 4, distinct_violating_rules: 0, violated_zone_pairs: 0, critical_rules: 0, covered_by_earlier_rule: 0,
  conditional_rules: 0, outside_headline_ordered_layer: 0, not_evaluable_findings: 0, unknown_findings: 0, became_undecided_findings: 0,
  stale_policies: 0, incomplete_policies: 0, older_version_policies: 0, not_evaluated_policies: 0, resolved_by_version_change_last_7_days: 0,
  exact: true, count_text: "0", text: "0 rules violate the zone matrix (v4)", outside_headline_text: null,
  staleness_headline: { count: 0, exact: true, text: "0" },
};
const policy = (verdict = "CURRENT", collectedAt: string | null = ago(2)) => ({
  policy_id: "p1", policy_name: "POL-TANGO-01", staleness: { verdict, reasons: [], snapshot_age_seconds: 7200, snapshot_age_known: true },
  coverage: "COMPLETE", collected_at: collectedAt,
});
const THRESHOLD = { seconds: 86_400, hours: 24, interim: true, source: "po-2026-11-23", label: "interim value set by the Product Owner (2026-11-23)" };

function summary(headline: Record<string, unknown> | null, over: Record<string, unknown> = {}) {
  return {
    state: headline ? "READY" : "NO_ACTIVE_VERSION", state_text: "findings of the latest complete run of each policy", staleness_threshold: THRESHOLD,
    headline: headline ? { ...CLEAN_HEADLINE, ...headline } : null, policies: [policy()], findings: [], ...over,
  };
}
const zoneRows = (zoneMatrix: SourceRead<unknown> | undefined, permissions?: readonly string[]) =>
  buildAttention({ ...base, zoneMatrix }, permissions).rows.filter((r) => r.key === "zone-matrix-violations" || r.key === "unknown-zonematrix");
const ok = (body: unknown): SourceRead<unknown> => ({ state: "ok", value: body });

describe("zone matrix attention row", () => {
  it("is Critical when a critical rule violates, with the server's headline text and the outside split", () => {
    const [r] = zoneRows(ok(summary({ distinct_violating_rules: 3, violated_zone_pairs: 4, critical_rules: 1, outside_headline_ordered_layer: 2,
      text: "3 rules violate the zone matrix (v4) across 4 zone pairs; 1 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)",
      outside_headline_text: "+2 outside the headline (other ordered layer denies or unknown)" })));
    expect(r.key).toBe("zone-matrix-violations");
    expect(r.severity).toBe("critical");
    expect(r.what).toBe("3 rules violate the zone matrix (v4) across 4 zone pairs; 1 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)");
    expect(r.detail).toContain("+2 outside the headline (other ordered layer denies or unknown)");
    expect(r.href).toBe("?screen=zone_matrix");
    expect(r.linkLabel).toBe("Open Zone matrix");
    expect(r.names).toEqual([]);
    expect(r.count).toBe(3);
  });

  it("is Review (serious) for violations without a critical rule, and never a warning", () => {
    const [r] = zoneRows(ok(summary({ distinct_violating_rules: 2, text: "2 rules violate the zone matrix (v4) across 2 zone pairs; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)" })));
    expect(r.severity).toBe("serious");
    expect(RULES["zone-matrix-violations"].severity).not.toBe("warning");
  });

  it("keeps the server's 'at least N' form and still ranks as Review when the count is a lower bound", () => {
    const [r] = zoneRows(ok(summary({ distinct_violating_rules: 5, exact: false, stale_policies: 1,
      text: "at least 5 rules violate the zone matrix (v4) across 6 zone pairs; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)" },
      { policies: [policy("STALE")] })));
    expect(r.severity).toBe("serious");
    expect(r.what).toMatch(/^at least 5 rules violate/);
    expect(r.detail).toContain("1 policy is STALE");
    expect(r.detail).toContain("snapshot older than 24 h (interim value set by the Product Owner, not the bank's)");
  });

  it("is UNKNOWN, never '0' or green, when nothing is proven and a policy is stale", () => {
    const [r] = zoneRows(ok(summary({ exact: false, stale_policies: 1, text: "no proven violations in evaluated rules" }, { policies: [policy("STALE")] })));
    expect(r.severity).toBe("unknown");
    expect(r.what).toBe("no proven violations in evaluated rules");
    expect(`${r.what} ${r.detail}`).not.toMatch(/\b0 rules violate|\bOK\b|all clear/i);
  });

  it("never prints a leading 0 for a count that is not exact, even if the body carries it", () => {
    const [r] = zoneRows(ok(summary({ exact: false, incomplete_policies: 1, text: "0 rules violate the zone matrix (v4)" })));
    expect(r.severity).toBe("unknown");
    expect(r.what).toBe("no proven violations in evaluated rules");
  });

  it.each([
    ["INCOMPLETE policies", { incomplete_policies: 2 }, "2 policies are INCOMPLETE"],
    ["policies not evaluated", { not_evaluated_policies: 1 }, "1 policy is not evaluated"],
    ["policies on an older version", { older_version_policies: 1 }, "1 policy is still on an older matrix version"],
    ["NOT_EVALUABLE findings", { not_evaluable_findings: 3 }, "3 findings are NOT_EVALUABLE"],
    ["UNKNOWN findings", { unknown_findings: 1 }, "1 finding is UNKNOWN"],
    ["outside the headline", { outside_headline_ordered_layer: 2, outside_headline_text: "+2 outside the headline (other ordered layer denies or unknown)" }, "+2 outside the headline"],
  ])("is UNKNOWN with N = 0 and %s, saying what it counts", (_name, over, expected) => {
    const [r] = zoneRows(ok(summary({ exact: false, text: "no proven violations in evaluated rules", ...over })));
    expect(r.severity).toBe("unknown");
    expect(r.detail).toContain(expected);
  });

  it("gives no row when everything is evaluated, current, complete and clean", () => {
    expect(zoneRows(ok(summary({})))).toEqual([]);
  });

  it("still gives a row when the count is exact but findings were resolved by a version change in the last 7 days", () => {
    const [r] = zoneRows(ok(summary({ resolved_by_version_change_last_7_days: 4 })));
    expect(r.severity).toBe("serious");
    expect(r.detail).toContain("4 findings resolved by a version change in the last 7 days");
  });

  it("is not silent when the server says exact but a policy of the body is not CURRENT", () => {
    const [r] = zoneRows(ok(summary({}, { policies: [policy("STALE")] })));
    expect(r.severity).toBe("unknown");
    expect(r.detail).toContain("1 policy is STALE");
  });

  it("gives no row without an active version", () => {
    expect(zoneRows(ok(summary(null)))).toEqual([]);
  });

  it("gives an UNKNOWN row when no policy has a complete run (no headline count is exact)", () => {
    const [r] = zoneRows(ok({ ...summary({ exact: false, text: "no proven violations in evaluated rules" }), state: "NO_COMPLETE_RUN",
      state_text: "no policy has a complete evaluation run yet" }));
    expect(r.severity).toBe("unknown");
    expect(r.detail).toContain("no policy has a complete evaluation run yet");
  });

  it("gives nothing for a role the server refused and for a skipped read", () => {
    expect(zoneRows({ state: "restricted" })).toEqual([]);
    expect(zoneRows({ state: "skipped" })).toEqual([]);
    expect(zoneRows(undefined)).toEqual([]);
  });

  it("gives one UNKNOWN row when the read failed", () => {
    const rows = zoneRows({ state: "failed" });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ key: "unknown-zonematrix", severity: "unknown", what: "The zone matrix summary could not be read" });
  });

  it("gives one UNKNOWN row when the body lacks its headline or a count", () => {
    const { headline: _headline, ...noHeadline } = summary({ distinct_violating_rules: 1 });
    expect(zoneRows(ok(noHeadline)).map((r) => r.key)).toEqual(["unknown-zonematrix"]);
    expect(zoneRows(ok({ ...summary({}), headline: { ...CLEAN_HEADLINE, stale_policies: undefined } })).map((r) => r.key)).toEqual(["unknown-zonematrix"]);
    expect(zoneRows(ok({ ...summary({}), headline: { ...CLEAN_HEADLINE, exact: "yes" } })).map((r) => r.key)).toEqual(["unknown-zonematrix"]);
    expect(zoneRows(ok({ ...summary({}), state: "READY", headline: null })).map((r) => r.key)).toEqual(["unknown-zonematrix"]);
    expect(zoneRows(ok(null)).map((r) => r.key)).toEqual(["unknown-zonematrix"]);
    expect(zoneRows(ok("<html>")).map((r) => r.key)).toEqual(["unknown-zonematrix"]);
  });

  it("shows the age of the oldest policy snapshot as the evidence age", () => {
    const [r] = zoneRows(ok(summary({ distinct_violating_rules: 1, text: "1 rule violates the zone matrix (v4) across 1 zone pair; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)" },
      { policies: [policy("CURRENT", ago(5)), policy("CURRENT", ago(30)), policy("CURRENT", null)] })));
    expect(r.at).toBe(ago(30));
    expect(r.atLabel).toBe("oldest policy snapshot");
  });

  it("is hidden from a session without the Policy plane, and the read is not wanted for it", () => {
    const body = ok(summary({ distinct_violating_rules: 1, text: "1 rule violates the zone matrix (v4)" }));
    expect(zoneRows(body, ["Devices", "Compliance"])).toEqual([]);
    expect(zoneRows(body, ["Policy"]).map((r) => r.key)).toEqual(["zone-matrix-violations"]);
    expect(sourceWanted("zonematrix", ["Devices"])).toBe(false);
    expect(sourceWanted("zonematrix", ["Policy"])).toBe(true);
  });

  it("is listed as a source and ranks Act now above Review rows", () => {
    const result = buildAttention({ ...base, zoneMatrix: ok(summary({ distinct_violating_rules: 1, critical_rules: 1, text: "1 rule violates the zone matrix (v4)" })) }, undefined);
    expect(result.sources.find((s) => s.key === "zonematrix")).toEqual({ key: "zonematrix", label: "Zone matrix", state: "ok" });
    expect(result.rows[0].key).toBe("zone-matrix-violations");
  });

  it("parses nothing it cannot prove", () => {
    expect(parseZoneMatrixAttention(summary({}))?.headline?.exact).toBe(true);
    expect(parseZoneMatrixAttention({ ...summary({}), headline: { ...CLEAN_HEADLINE, distinct_violating_rules: -1 } })).toBeNull();
    expect(parseZoneMatrixAttention({ ...summary(null), headline: { ...CLEAN_HEADLINE } })).toBeNull();
  });
});
