import type { OfflineAnalysisResult, OfflineAnalysisSummary, OfflineRule } from "../auth/adminApi";

/** Page size the standard offline tab uses (PolicyOfflineTab: a full page of 50 means a next page may exist). */
export const OFFLINE_PAGE_SIZE = 50;

/** The list request for one page: the same policy and 0-based page index the standard tab sends. Pages below 0 clamp to 0. */
export function offlineListRequest(policy: string, page: number): { readonly policy: string; readonly page: number } {
  const safe = Number.isFinite(page) ? Math.max(0, Math.floor(page)) : 0;
  return { policy, page: safe };
}

/** Pager affordances: previous only after page 0; next only when the page is full (the standard tab's rule). */
export function offlinePager(page: number, shownCount: number): { readonly canPrevious: boolean; readonly canNext: boolean; readonly note: string | null } {
  const current = Number.isFinite(page) ? Math.max(0, Math.floor(page)) : 0;
  const canNext = shownCount >= OFFLINE_PAGE_SIZE;
  return {
    canPrevious: current > 0,
    canNext,
    note: canNext ? `Page ${current + 1} is full: more stored analyses may exist on the next page.` : null,
  };
}

/** Rule-level counts from a loaded analysis. Each count says exactly what it counts; nothing here is a verdict. */
export interface OfflineRuleCounts {
  /** Rules in the stored analysis result. */
  readonly rules: number;
  /** Rules with zero attributed flows in this sample ("Zero-traffic rules"; not proof of an unused rule). */
  readonly zeroTraffic: number;
  /** Rules with at least one unseen resolved member, or whose member status is UNKNOWN ("Unseen members"). */
  readonly unseenMembers: number;
  /** Rules carrying a narrowing suggestion status ("Narrowing suggestions"). */
  readonly narrowing: number;
}

export function offlineRuleCounts(rules: readonly OfflineRule[]): OfflineRuleCounts {
  let zeroTraffic = 0;
  let unseenMembers = 0;
  let narrowing = 0;
  for (const rule of rules) {
    if (rule.flowCount === 0) zeroTraffic += 1;
    if (rule.unseenMembers.length > 0 || rule.memberStatus === "UNKNOWN") unseenMembers += 1;
    if (rule.narrowing?.memberStatus) narrowing += 1;
  }
  return { rules: rules.length, zeroTraffic, unseenMembers, narrowing };
}

/** One-line sample summary: "N rows · M invalid rows · …", the same figures the standard tab names. */
export function offlineSampleLine(result: OfflineAnalysisResult): string {
  return `${result.rowCount} rows · ${result.invalidRows} invalid rows · ${result.flowCount} valid sample flows · ${result.attributedFlows} attributed flows`;
}

/** The mode label the standard tab uses for a stored analysis; an analysis without a mode is a whole-policy one. */
export function offlineModeLabel(summary: Pick<OfflineAnalysisSummary, "mode">): string {
  return summary.mode === "ONE_RULE" ? "One rule" : "Whole policy";
}
