import { describe, expect, it } from "vitest";
import type { OfflineAnalysisResult, OfflineRule } from "../src/auth/adminApi";
import {
  OFFLINE_PAGE_SIZE,
  offlineListRequest,
  offlineModeLabel,
  offlinePager,
  offlineRuleCounts,
  offlineSampleLine,
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
