import type { PolicyProposalResponse, ProposalMerge, ProposalRuleRef, ProposalSectionOrder, ProposalStep } from "../auth/adminApi";
import { DIFFERING_CELL_WORDS, REORDER_RESULT_WORDS, type ChangeEffect, type ChangeItem } from "./policyInsightActions";

/**
 * Policy Insights reorder proposal (design section 8 items 4 and 6, decision record Q2/Q3/Q7/Q9). Words only: the claim
 * a headline makes never exceeds `packageVerdict` + `label`, and nothing here is a device command.
 */
export const UNSUPPORTED_TEXT = "The reorder proposal is available for Check Point packages only.";
export const INCOMPLETE_TEXT = "The stored snapshot is incomplete: this result is not a whole-package statement.";
export const LOAD_FAILED_TEXT = "The reorder proposal could not be loaded.";
export const SHOW_LABEL = "Show reorder proposal";
export const ADD_LABEL = "Add the proposal to the change list";
export const NO_BEHAVIOUR_CHANGE = "No behaviour change";
export const INSPECTION_HEADLINE = "Verdict-safe, inspection changes: review profile and log differences first";
export const NOT_PROVEN_HEADLINE = "Not proven (work budget)";
export const CHANGES_HEADLINE = "Behaviour may change";

export const EMPTY_REASON_WORDS: Readonly<Record<string, string>> = {
  "no offered move": "No reorder is offered for this package: no move was proposed.",
  "snapshot collection incomplete": "No reorder is proposed: the snapshot collection is incomplete.",
  "unsupported rulebase": "No reorder is proposed: this rulebase is not supported.",
};
export const REFUSAL_WORDS: Readonly<Record<string, string>> = {
  UNSAFE_DIFFERENT_EFFECT: "crosses a rule with another verdict",
  ANCHOR_BLOCKED: "blocked by an anchor (unreadable rule)",
  CROSSES_SECTION: "would leave its section",
  NOT_PROVEN: "not proven (work budget)",
  NO_LONGER_A_CANDIDATE: "no longer a candidate after earlier moves",
};
export const EQUIVALENCE_WORDS: Readonly<Record<string, string>> = { EQUIVALENT: "EQUIVALENT", CHANGES: "CHANGES", NOT_PROVEN: "NOT_PROVEN" };
export const equivalenceWord = (value: string | null | undefined) => (value && EQUIVALENCE_WORDS[value]) || "UNKNOWN";
export const resultWord = (value: string | null | undefined) =>
  (value && (REORDER_RESULT_WORDS[value] ?? REFUSAL_WORDS[value] ?? undefined)) || "UNKNOWN";

const n = (value: number) => value.toLocaleString("en-US");
const refText = (ref: ProposalRuleRef | null | undefined) => ref ? `rule ${ref.ruleNumber || "?"} ${ref.ruleName || "UNKNOWN"}` : "UNKNOWN";

/** Headline from `packageVerdict` and `label` only; "No behaviour change" only for EQUIVALENT with the no-change label. */
export function proposalHeadline(resp: PolicyProposalResponse): string {
  switch (resp.packageVerdict) {
    case "EQUIVALENT":
      if (resp.label === "no behaviour change") return NO_BEHAVIOUR_CHANGE;
      if (resp.label === "verdict-safe, inspection changes") return INSPECTION_HEADLINE;
      return "UNKNOWN";
    case "NOT_PROVEN": return NOT_PROVEN_HEADLINE;
    case "CHANGES": return CHANGES_HEADLINE;
    case "NO_MOVES": return (resp.emptyReason && EMPTY_REASON_WORDS[resp.emptyReason]) || "No moves proposed: UNKNOWN reason.";
    default: return "UNKNOWN";
  }
}

export const refusedTotal = (refused: Record<string, number> | undefined) => Object.values(refused ?? {}).reduce((sum, v) => sum + (Number.isFinite(v) ? v : 0), 0);

/** "<tried> of <candidatesTotal> candidate rules tried; <accepted> moves accepted; <n> refused" plus the not-tried note when truncated. */
export function proposalCountsLine(resp: PolicyProposalResponse): string {
  const count = (value: number | null | undefined) => typeof value === "number" && Number.isFinite(value) ? n(value) : "UNKNOWN";
  const refused = resp.refused ? n(refusedTotal(resp.refused)) : "UNKNOWN";
  const base = `${count(resp.tried)} of ${count(resp.candidatesTotal)} candidate rules tried; ${count(resp.accepted)} moves accepted; ${refused} refused`;
  return resp.truncated ? `${base} · ${resp.notTriedNote || `+${count(resp.notTried)} more not tried`}` : base;
}
/** Refusals by reason word with their counts; a word outside the closed vocabulary reads UNKNOWN. */
export function refusalItems(refused: Record<string, number> | undefined): { word: string; text: string; count: number }[] {
  return Object.entries(refused ?? {}).filter(([, c]) => c > 0).map(([word, count]) => ({ word, count, text: REFUSAL_WORDS[word] ?? "UNKNOWN" }));
}

export const crossedRefs = (step: ProposalStep): ProposalRuleRef[] => Array.isArray(step.crossedRules) ? step.crossedRules : [];
export const crossedCount = (step: ProposalStep) => Array.isArray(step.crossedRules) ? step.crossedRules.length : typeof step.crossedRules === "number" ? step.crossedRules : 0;

export function stepSentence(step: ProposalStep): string {
  return `Move ${refText(step.subject)} above ${refText(step.above)}`;
}
export function mergeSentence(merge: ProposalMerge): string {
  const cell = merge.differingCell ? DIFFERING_CELL_WORDS[merge.differingCell] ?? "UNKNOWN" : "UNKNOWN";
  return `Merge ${refText(merge.merged)} into ${refText(merge.surviving)} (differs only in ${cell})`;
}

export interface OrderRow { position: number; ruleId: string; label: string; moved: boolean; struck: boolean; outOfWindow: boolean; enteredWindow: boolean; }
export interface OrderRows { section: string; before: OrderRow[]; after: OrderRow[]; more: string | null }
/**
 * Positions are offset + index + 1. A rule is shown by name only when `nameById` has it (a step names it); otherwise
 * "rule at position N", so a masked name is never reconstructed. A rule is struck (merged away) only when `mergedIds`
 * names it; a rule missing from the "after" window without such a merge was pushed out of the window (outOfWindow), and a
 * rule only in the "after" window came in from outside it (enteredWindow) -- neither is a merge and neither is "moved".
 */
export function orderRows(section: ProposalSectionOrder, nameById: ReadonlyMap<string, string>, mergedIds: ReadonlySet<string> = new Set()): OrderRows {
  const afterPos = new Map(section.after.map((id, i) => [id, section.offset + i + 1] as const));
  const beforePos = new Map(section.before.map((id, i) => [id, section.offset + i + 1] as const));
  const label = (id: string, position: number) => nameById.get(id) ?? `rule at position ${position}`;
  const before = section.before.map((id, i) => {
    const position = section.offset + i + 1, now = afterPos.get(id);
    return { position, ruleId: id, label: label(id, position), struck: now === undefined && mergedIds.has(id), outOfWindow: now === undefined && !mergedIds.has(id), enteredWindow: false,
      moved: now !== undefined && now !== position };
  });
  const after = section.after.map((id, i) => {
    const position = section.offset + i + 1, was = beforePos.get(id);
    return { position, ruleId: id, label: label(id, position), struck: false, outOfWindow: false, enteredWindow: was === undefined, moved: was !== undefined && was !== position };
  });
  return { section: section.sectionId, before, after, more: section.moreRules > 0 ? section.more || `+${n(section.moreRules)} more` : null };
}

/** Rule ids that a step or merge names, with the name the response gave them. */
export function namesById(resp: PolicyProposalResponse): Map<string, string> {
  const out = new Map<string, string>();
  const add = (ref: ProposalRuleRef | null | undefined) => { if (ref?.ruleId) out.set(ref.ruleId, `#${ref.ruleNumber || "?"} ${ref.ruleName || "UNKNOWN"}`); };
  for (const s of resp.steps ?? []) { add(s.subject); add(s.above); if (Array.isArray(s.crossedRules)) s.crossedRules.forEach(add); }
  for (const m of resp.merges ?? []) { add(m.surviving); add(m.merged); }
  return out;
}

const effectOf = (result: string | null | undefined, equivalence: string | null | undefined, inspection: boolean | undefined): ChangeEffect =>
  result === "SAFE_INSPECTION_CHANGES" || inspection ? "Verdict-safe, inspection changes"
    : result === "SAFE" || (result == null && equivalence === "EQUIVALENT") ? "No behaviour change" : "Review only";

/** One change-list item per ACCEPTED move and merge; never "Behaviour may change". Words only, no command text. */
export function proposalChangeItems(resp: PolicyProposalResponse, snapshotAt: string | null | undefined, now: Date = new Date()): ChangeItem[] {
  const base = { evidenceGrade: "PROVEN" as const, warning: "", caveat: resp.caveat ?? "", snapshotAt: snapshotAt ?? null, addedAt: now.toISOString() };
  const moves = (resp.steps ?? []).filter(s => s.accepted && s.subject).map((s): ChangeItem => ({
    ...base, insightId: `proposal:move:${s.subject!.ruleId}:${s.above?.ruleId ?? ""}`, insightType: "I7", kind: "REORDER_RULE",
    label: "Reorder rule", plan: stepSentence(s), effect: effectOf(s.result, s.equivalence, s.inspectionChanges),
  }));
  const merges = (resp.merges ?? []).filter(m => m.accepted && m.surviving && m.merged).map((m): ChangeItem => ({
    ...base, insightId: `proposal:merge:${m.surviving!.ruleId}:${m.merged!.ruleId}`, insightType: "I5", kind: "MERGE_RULES",
    label: "Merge rules", plan: mergeSentence(m), effect: effectOf(m.result, m.equivalence, false),
  }));
  return [...moves, ...merges];
}

/** Ids of rules an ACCEPTED merge folded into a surviving rule: the only rules the order view may show as merged away. */
export const mergedAwayIds = (resp: PolicyProposalResponse): Set<string> =>
  new Set((resp.merges ?? []).filter(m => m.accepted && m.merged?.ruleId).map(m => m.merged!.ruleId));
