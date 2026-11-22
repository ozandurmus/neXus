import { describe, expect, it } from "vitest";
import type { PolicyHygieneSummary, PolicyHygieneSummaryRow, PolicyMetadata, PolicyRule } from "../src/auth/adminApi";
import {
  POLICY_BROWSER_CONTAINER_CAP,
  POLICY_BROWSER_OBJECT_CAP,
  POLICY_BROWSER_RULE_CAP,
  capList,
  flattenRules,
  hygieneSummaryView,
  policyUnitOptions,
  ruleMatches,
} from "../src/estate/estatePolicyBrowserModel";

// Synthetic values only (FW-TANGO-04 style names, documentation addresses).

function policy(id: string, name: string, containerName: string, sourceName: string, ruleCount?: number): PolicyMetadata {
  return {
    id, sourceId: "src-1", sourceName, vendor: "palo_alto", containerId: "c-1", containerName, name,
    collectedAt: "2026-11-20T10:00:00Z", artefactRef: "", targets: [], ruleCount,
  };
}

function rule(n: number, name: string, action = "allow", comment = ""): PolicyRule {
  return {
    id: `r${n}`, uuid: `u${n}`, number: n, name, enabled: true,
    source: { refs: [], negated: false }, destination: { refs: [], negated: false },
    service: { refs: [], negated: false }, application: { refs: [], negated: false },
    action, log: "", comment, extras: {},
  };
}

function row(overrides: Partial<PolicyHygieneSummaryRow> & { containerId: string }): PolicyHygieneSummaryRow {
  return {
    sourceId: "src-1", sourceName: "PANORAMA-SYN", vendor: "palo_alto", containerName: `DG-${overrides.containerId}`,
    snapshotAt: "2026-11-21T08:00:00Z", state: "READY", rules: 10, counts: {}, ...overrides,
  };
}

function summary(containers: PolicyHygieneSummaryRow[], days = 30): PolicyHygieneSummary {
  return { containers, totals: {}, rules: 0, pending: 0, days };
}

describe("policy browser caps", () => {
  it("exports the documented caps", () => {
    expect(POLICY_BROWSER_RULE_CAP).toBe(50);
    expect(POLICY_BROWSER_OBJECT_CAP).toBe(50);
    expect(POLICY_BROWSER_CONTAINER_CAP).toBe(25);
  });
});

describe("capList", () => {
  it("keeps the first cap items and reports the rest as +N more", () => {
    const capped = capList([1, 2, 3, 4, 5], 2);
    expect(capped.shown).toEqual([1, 2]);
    expect(capped.hidden).toBe(3);
    expect(capped.moreLabel).toBe("+3 more");
  });

  it("shows everything and no label when the list fits", () => {
    const capped = capList(["a", "b"], 2);
    expect(capped.shown).toEqual(["a", "b"]);
    expect(capped.hidden).toBe(0);
    expect(capped.moreLabel).toBeNull();
  });

  it("handles an empty list without a label", () => {
    const capped = capList([], 50);
    expect(capped.shown).toEqual([]);
    expect(capped.moreLabel).toBeNull();
  });

  it("treats a non-finite or negative cap as zero shown, with the full count hidden", () => {
    for (const cap of [Number.NaN, Number.POSITIVE_INFINITY, -1]) {
      const capped = capList([1, 2, 3], cap);
      expect(capped.shown).toEqual([]);
      expect(capped.hidden).toBe(3);
      expect(capped.moreLabel).toBe("+3 more");
    }
  });

  it("floors a fractional cap", () => {
    const capped = capList([1, 2, 3, 4], 2.9);
    expect(capped.shown).toEqual([1, 2]);
    expect(capped.hidden).toBe(2);
  });
});

describe("policyUnitOptions", () => {
  it("labels each unit with name, container and source, sorted by label", () => {
    const options = policyUnitOptions([
      policy("p-2", "FW-TANGO-04", "DG-SYNTH-B", "PANORAMA-SYN", 7),
      policy("p-1", "FW-BRAVO-02", "DG-SYNTH-A", "PANORAMA-SYN", 3),
    ]);
    expect(options.map((o) => o.id)).toEqual(["p-1", "p-2"]);
    expect(options[0].label).toBe("FW-BRAVO-02 · DG-SYNTH-A · PANORAMA-SYN");
    expect(options[0].ruleCount).toBe(3);
    expect(options[0].collectedAt).toBe("2026-11-20T10:00:00Z");
  });

  it("reports an unknown rule count as null, never as zero", () => {
    const [option] = policyUnitOptions([policy("p-1", "FW-BRAVO-02", "DG-SYNTH-A", "PANORAMA-SYN")]);
    expect(option.ruleCount).toBeNull();
  });

  it("returns an empty list for no policies", () => {
    expect(policyUnitOptions([])).toEqual([]);
  });
});

describe("flattenRules", () => {
  it("concatenates rules across sections in stored order", () => {
    const a = rule(1, "allow-web");
    const b = rule(2, "deny-all");
    const c = rule(3, "allow-dns");
    const flat = flattenRules([{ rules: [a, b] }, { rules: [c] }]);
    expect(flat.map((r) => r.number)).toEqual([1, 2, 3]);
  });

  it("skips a section whose rules are not an array instead of failing", () => {
    const flat = flattenRules([{ rules: undefined as unknown as PolicyRule[] }, { rules: [rule(4, "x")] }]);
    expect(flat.map((r) => r.number)).toEqual([4]);
  });

  it("returns an empty list when there are no sections", () => {
    expect(flattenRules([])).toEqual([]);
  });
});

describe("ruleMatches", () => {
  const r = rule(12, "allow-web-tango", "accept", "change ticket synthetic");

  it("matches everything on an empty or whitespace-only query", () => {
    expect(ruleMatches(r, "")).toBe(true);
    expect(ruleMatches(r, "   ")).toBe(true);
  });

  it("matches case-insensitively on name, action and comment", () => {
    expect(ruleMatches(r, "WEB")).toBe(true);
    expect(ruleMatches(r, "accept")).toBe(true);
    expect(ruleMatches(r, "TICKET")).toBe(true);
  });

  it("matches the rule number as text", () => {
    expect(ruleMatches(r, "12")).toBe(true);
  });

  it("trims the query before matching", () => {
    expect(ruleMatches(r, "  web  ")).toBe(true);
  });

  it("returns false when no field contains the query", () => {
    expect(ruleMatches(r, "deny")).toBe(false);
  });

  it("tolerates a missing comment without throwing", () => {
    const noComment = { ...r, comment: undefined as unknown as string };
    expect(ruleMatches(noComment, "web")).toBe(true);
    expect(ruleMatches(noComment, "ticket")).toBe(false);
  });
});

describe("hygieneSummaryView: unreadable source is UNKNOWN, never OK or zero", () => {
  it.each([
    ["null", null],
    ["undefined", undefined],
  ])("reads a %s summary as UNKNOWN with no counts", (_label, input) => {
    const view = hygieneSummaryView(input as PolicyHygieneSummary | null | undefined);
    expect(view.state).toBe("unknown");
    expect(view.rows).toEqual([]);
    expect(view.totals).toEqual([]);
    expect(view.readyContainers).toBe(0);
    expect(view.pendingContainers).toBe(0);
    expect(view.countsLine).toMatch(/UNKNOWN/);
  });

  it("reads a summary without a containers array as UNKNOWN", () => {
    const view = hygieneSummaryView({ totals: {}, rules: 0, pending: 0, days: 30 } as unknown as PolicyHygieneSummary);
    expect(view.state).toBe("unknown");
    expect(view.rows).toEqual([]);
    expect(view.countsLine).toMatch(/UNKNOWN/);
  });
});

describe("hygieneSummaryView: rows, pending and totals", () => {
  it("returns state ok with zero containers for an empty but readable summary", () => {
    const view = hygieneSummaryView(summary([], 14));
    expect(view.state).toBe("ok");
    expect(view.rows).toEqual([]);
    expect(view.pendingLine).toBeNull();
    expect(view.countsLine).toBe("Totals count rules per finding class over 0 READY containers (window 14 days).");
  });

  it("renders a READY row with its counts and label", () => {
    const view = hygieneSummaryView(summary([row({ containerId: "a", rules: 10, counts: { shadowed: 2 } })]));
    expect(view.state).toBe("ok");
    expect(view.rows).toHaveLength(1);
    const [ready] = view.rows;
    expect(ready.kind).toBe("ready");
    if (ready.kind !== "ready") throw new Error("expected ready row");
    expect(ready.key).toBe("src-1:a");
    expect(ready.label).toBe("DG-a · PANORAMA-SYN (palo_alto)");
    expect(ready.rules).toBe(10);
    expect(ready.counts).toEqual([{ finding: "shadowed", rules: 2 }]);
  });

  it("renders a PENDING row with no counts, never as zeros", () => {
    const view = hygieneSummaryView(summary([row({ containerId: "b", state: "PENDING", rules: undefined, counts: undefined })]));
    const [pending] = view.rows;
    expect(pending.kind).toBe("pending");
    expect("counts" in pending).toBe(false);
    expect(view.readyContainers).toBe(0);
    expect(view.pendingContainers).toBe(1);
    expect(view.totals).toEqual([]);
    expect(view.pendingLine).toBe("1 container PENDING: no counts yet, not counted as zero.");
  });

  it("treats a READY row without a numeric rule count as PENDING", () => {
    const view = hygieneSummaryView(summary([row({ containerId: "c", state: "READY", rules: undefined })]));
    expect(view.rows[0].kind).toBe("pending");
    expect(view.pendingContainers).toBe(1);
  });

  it("sums totals over READY containers only and sorts them by rules descending", () => {
    const view = hygieneSummaryView(summary([
      row({ containerId: "a", counts: { shadowed: 2, unused: 5 } }),
      row({ containerId: "b", counts: { shadowed: 3 } }),
      row({ containerId: "c", state: "PENDING", rules: undefined, counts: { shadowed: 100 } }),
    ]));
    expect(view.readyContainers).toBe(2);
    expect(view.pendingContainers).toBe(1);
    expect(view.totals).toEqual([
      { finding: "shadowed", rules: 5 },
      { finding: "unused", rules: 5 },
    ]);
    expect(view.countsLine).toBe("Totals count rules per finding class over 2 READY containers (window 30 days).");
  });

  it("uses the singular noun for one READY container and no pending line when none are pending", () => {
    const view = hygieneSummaryView(summary([row({ containerId: "a" })]));
    expect(view.countsLine).toContain("over 1 READY container ");
    expect(view.pendingLine).toBeNull();
  });

  it("tolerates a READY row whose counts are missing", () => {
    const view = hygieneSummaryView(summary([row({ containerId: "a", counts: undefined })]));
    const [ready] = view.rows;
    if (ready.kind !== "ready") throw new Error("expected ready row");
    expect(ready.counts).toEqual([]);
    expect(view.totals).toEqual([]);
  });
});

describe("hygieneSummaryView: container cap", () => {
  it("caps rows at the default container cap and reports the rest as +N more", () => {
    const rows = Array.from({ length: POLICY_BROWSER_CONTAINER_CAP + 3 }, (_, i) => row({ containerId: `c${i}` }));
    const view = hygieneSummaryView(summary(rows));
    expect(view.rows).toHaveLength(POLICY_BROWSER_CONTAINER_CAP);
    expect(view.hiddenRows).toBe(3);
    expect(view.moreLabel).toBe("+3 more");
    // Totals still count every READY container, not only the shown ones.
    expect(view.readyContainers).toBe(POLICY_BROWSER_CONTAINER_CAP + 3);
  });

  it("honours an explicit cap and counts hidden PENDING rows too", () => {
    const view = hygieneSummaryView(summary([
      row({ containerId: "a" }),
      row({ containerId: "b", state: "PENDING", rules: undefined }),
      row({ containerId: "c" }),
    ]), 1);
    expect(view.rows).toHaveLength(1);
    expect(view.hiddenRows).toBe(2);
    expect(view.moreLabel).toBe("+2 more");
    expect(view.pendingContainers).toBe(1);
  });

  it("has no label when every container fits", () => {
    const view = hygieneSummaryView(summary([row({ containerId: "a" })]));
    expect(view.hiddenRows).toBe(0);
    expect(view.moreLabel).toBeNull();
  });
});
