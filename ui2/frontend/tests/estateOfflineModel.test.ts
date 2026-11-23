import { describe, expect, it } from "vitest";
import type { OfflineAnalysisResult, OfflineRule } from "../src/auth/adminApi";
import {
  OFFLINE_PAGE_SIZE,
  OFFLINE_RULE_CAP,
  offlineComparisonLine,
  offlineListRequest,
  offlineModeLabel,
  offlinePager,
  offlinePeriodLine,
  offlineRuleCounts,
  offlineRuleRow,
  offlineSampleLine,
  offlineStatLines,
} from "../src/estate/estateOfflineModel";

// Synthetic values only (FW-TANGO-04 style names, documentation addresses).
function rule(overrides: Partial<OfflineRule>): OfflineRule {
  return {
    ruleId: "r-1", name: "allow-web", number: 1, action: "allow", sectionName: "SEC-SYN", flowCount: 3,
    firstTime: "", lastTime: "", distinctSources: 1, distinctDestinations: 1, distinctServices: 1,
    topObserved: [], zeroTraffic: "", severity: "", memberStatus: "OK", unseenMembers: [],
    narrowing: {}, ...overrides,
  };
}

describe("offlineListRequest", () => {
  it("maps a page index to the same policy and 0-based page the standard tab requests", () => {
    expect(offlineListRequest("pol-tango-04", 2)).toEqual({ policy: "pol-tango-04", page: 2 });
  });

  it("clamps negative, fractional and non-finite pages to a valid page index", () => {
    expect(offlineListRequest("pol-tango-04", -3).page).toBe(0);
    expect(offlineListRequest("pol-tango-04", 1.9).page).toBe(1);
    expect(offlineListRequest("pol-tango-04", Number.NaN).page).toBe(0);
  });
});

describe("offlinePager", () => {
  it("uses the standard tab's page size of 50", () => {
    expect(OFFLINE_PAGE_SIZE).toBe(50);
  });

  it("enables previous only after page 0 and next only on a full page", () => {
    expect(offlinePager(0, 12)).toMatchObject({ canPrevious: false, canNext: false, note: null });
    expect(offlinePager(1, 12)).toMatchObject({ canPrevious: true, canNext: false });
  });

  it("says more analyses may exist when the page is full (+N more style note)", () => {
    const full = offlinePager(0, 50);
    expect(full.canNext).toBe(true);
    expect(full.note).toContain("more stored analyses may exist");
  });
});

describe("offlineRuleCounts", () => {
  it("counts each class of rule under the name the standard tab uses, not as a verdict", () => {
    const counts = offlineRuleCounts([
      rule({ ruleId: "a", flowCount: 0 }),
      rule({ ruleId: "b", flowCount: 4, unseenMembers: [{ memberId: "m1", name: "OBJ-SYN-1", dimension: "source" }] }),
      rule({ ruleId: "c", flowCount: 2, memberStatus: "UNKNOWN", lookupReason: "not resolved" }),
      rule({ ruleId: "d", flowCount: 1, narrowing: { memberStatus: "SUGGESTED" } }),
    ]);
    expect(counts).toEqual({ rules: 4, zeroTraffic: 1, unseenMembers: 2, narrowing: 1 });
  });

  it("reports zero rules for an empty result rather than inventing findings", () => {
    expect(offlineRuleCounts([])).toEqual({ rules: 0, zeroTraffic: 0, unseenMembers: 0, narrowing: 0 });
  });
});

describe("offline labels", () => {
  it("labels a missing mode as a whole-policy analysis, as the standard tab does", () => {
    expect(offlineModeLabel({})).toBe("Whole policy");
    expect(offlineModeLabel({ mode: "ONE_RULE" })).toBe("One rule");
  });

  it("states the sample counts with their names", () => {
    const result = {
      rowCount: 10, invalidRows: 1, flowCount: 8, attributedFlows: 6,
    } as OfflineAnalysisResult;
    expect(offlineSampleLine(result)).toBe("10 rows · 1 invalid rows · 8 valid sample flows · 6 attributed flows");
  });
});

describe("offlinePeriodLine", () => {
  it("formats the sample period and shows missing bounds as UNKNOWN", () => {
    expect(offlinePeriodLine({ periodStart: "2026-11-01", periodEnd: "2026-11-07" })).toBe("Period 2026-11-01 – 2026-11-07");
    expect(offlinePeriodLine({ periodStart: "", periodEnd: "" })).toBe("Period UNKNOWN – UNKNOWN");
  });
});

describe("offlineComparisonLine", () => {
  it("returns null when the SIEM comparison was not enabled", () => {
    expect(offlineComparisonLine({
      comparisonEnabled: false, agreementCount: 3, disagreementCount: 1, comparisonUnknownCount: 0, comparisonNote: "",
    })).toBeNull();
  });

  it("formats agreement, disagreement and UNKNOWN counts when enabled, with the note appended", () => {
    expect(offlineComparisonLine({
      comparisonEnabled: true, agreementCount: 3, disagreementCount: 1, comparisonUnknownCount: 2, comparisonNote: "synthetic note",
    })).toBe("SIEM rule agreement: 3 · Disagreement: 1 · UNKNOWN: 2 · synthetic note");
    expect(offlineComparisonLine({
      comparisonEnabled: true, agreementCount: 0, disagreementCount: 0, comparisonUnknownCount: 0, comparisonNote: "",
    })).toBe("SIEM rule agreement: 0 · Disagreement: 0 · UNKNOWN: 0");
  });
});

describe("offlineStatLines", () => {
  it("names each stat by its reason and count, and tolerates an absent list", () => {
    expect(offlineStatLines([{ statReason: "BAD_TIME", count: 1 }, { statReason: "NO_RULE", count: 4 }]))
      .toEqual(["BAD_TIME: 1", "NO_RULE: 4"]);
    expect(offlineStatLines(null)).toEqual([]);
    expect(offlineStatLines(undefined)).toEqual([]);
  });
});

describe("offlineRuleRow", () => {
  it("never renders UNKNOWN member status as ok", () => {
    const row = offlineRuleRow(rule({ memberStatus: "UNKNOWN", lookupReason: "lookup timed out", unseenMembers: [] }));
    expect(row.memberBadge).toEqual({ state: "unk", word: "Members UNKNOWN" });
    expect(row.memberNote).toBe("UNKNOWN: lookup timed out");
  });

  it("maps a known member status with no unseen members to ok, and formats unseen members", () => {
    expect(offlineRuleRow(rule({})).memberBadge).toEqual({ state: "ok", word: "Members seen" });
    const row = offlineRuleRow(rule({
      unseenMembers: [{ memberId: "m1", name: "OBJ-SYN-1", dimension: "source" }, { memberId: "m2", name: "OBJ-SYN-2", dimension: "service" }],
    }));
    expect(row.memberBadge).toEqual({ state: "watch", word: "2 unseen members" });
    expect(row.memberNote).toBe("source: OBJ-SYN-1, service: OBJ-SYN-2");
  });

  it("states the zero-traffic note with its severity and the no-unused-rule disclaimer", () => {
    const row = offlineRuleRow(rule({ flowCount: 0, zeroTraffic: "No attributed flows in sample.", severity: "INFO" }));
    expect(row.zeroTrafficNote).toBe("INFO: No attributed flows in sample. This does not establish an unused rule.");
    expect(offlineRuleRow(rule({ zeroTraffic: "" })).zeroTrafficNote).toBeNull();
  });

  it("formats narrowing status, and shows Narrowing UNKNOWN as UNKNOWN", () => {
    expect(offlineRuleRow(rule({ narrowing: { memberStatus: "SUGGESTED", coveragePercent: 80, coveredFlows: 5 } })).narrowingNote)
      .toBe("Narrowing: SUGGESTED (80% of 5 attributed flows)");
    expect(offlineRuleRow(rule({ narrowing: { memberStatus: "UNKNOWN", suggestionNote: "not resolved" } })).narrowingNote)
      .toBe("Narrowing UNKNOWN: not resolved");
    expect(offlineRuleRow(rule({ narrowing: {} })).narrowingNote).toBeNull();
  });

  it("builds the title, key and meta with counts named by their nouns", () => {
    const row = offlineRuleRow(rule({ ruleId: "", number: 7, name: "", flowCount: 1, distinctSources: 2, distinctDestinations: 1, distinctServices: 1 }));
    expect(row.key).toBe("7");
    expect(row.title).toBe("#7 · Unnamed rule");
    expect(row.meta).toBe("SEC-SYN · allow · 1 attributed flow · 2 sources, 1 destination, 1 service");
  });

  it("exposes the rule display cap as 20", () => {
    expect(OFFLINE_RULE_CAP).toBe(20);
  });
});
