import type { InsightGrade, InsightNeeds, InsightType, PolicyInsight, PolicyInsightDetail } from "../auth/adminApi";
import { readPreference, writePreference } from "../shell/preference";
import { toCsv } from "./DeviceShared";
import { actionWord } from "./PolicyRuleViewer";

/**
 * Policy Insights detail panel (PO 2026-11-25): the headline sentence of an insight, the changes it suggests and the
 * per-package change list. Everything here is words about the stored policy. Nothing writes to a device: policy writes
 * are not approved (decision record 2026-11-24, Q7), so a suggested action only ever becomes a line in a list the user
 * exports and applies in their own change window.
 */
export const CHANGE_LIST_NOTICE = "Nothing is sent to the firewall. Apply these in your own change window.";

export const INSTALL_CAVEAT = "Proven for the stored management policy, not for what is installed on a gateway (INSTALL_STATE_NOT_VERIFIED).";

export const TYPE_LABEL: Readonly<Record<InsightType, string>> = {
  I1: "Fully shadowed, same action", I2: "Fully shadowed, different action", I3: "Shadowed by several rules together", I4: "Partly shadowed",
  I5: "Merge candidate (adjacent rules)", I7: "Promote (proven reorder)",
  I8: "Disabled rule", I9: "Expired or expiring schedule", I10: "Zero or old hits", I11: "Unused object", I12: "Duplicate objects",
};
/** What the user does, one sentence per type; a grade other than PROVEN adds its own limit below the sentence. */
export const TYPE_SUGGESTION: Readonly<Record<InsightType, string>> = {
  I1: "Remove: an earlier rule already decides every packet this rule matches, with the same action.",
  I2: "Decide which rule is right: an earlier rule with a different action decides every packet this rule matches. Never removed automatically.",
  I3: "Remove: earlier rules of this layer together decide every packet this rule matches; the covering set is listed. If one of them has another action, decide which rule is right first.",
  I4: "Review: earlier rules of this layer with the same action already decide part of this rule's match. Nothing here shows what changing the order would do.",
  I5: "Merge these two adjacent rules into one; the merged rule keeps the first rule's identity. No command is generated.",
  I7: "Promote this rule above the broad rule it overlaps. Stage 1 of the reorganisation; no command is generated.",
  I8: "Remove after your team's holding period.",
  I9: "Remove or renew the rule.",
  I10: "Review: the stored counters show no recent use. This does not prove the rule is unused.",
  I11: "Remove the object or tighten the group that holds it.",
  I12: "Consolidate: these objects have the same type and the same values.",
};
export const I5_NOT_PROVEN_SUGGESTION = "Not proven (work budget): whether these two adjacent rules can be merged is UNKNOWN.";
/** I7 (design 4, decision record Q2): the inspection-change variant of the suggestion, and the one for a move the budget left unproven. */
export const I7_INSPECTION_SUGGESTION = `${TYPE_SUGGESTION.I7} Verdict-safe, inspection changes: review the profile and log differences first.`;
export const I7_NOT_PROVEN_SUGGESTION = "Not proven (work budget): whether this rule can move above the broad rule it overlaps is UNKNOWN.";
/** The suggestion sentence of one insight: the type's own, except the I5 budget and two I7 variants. */
export function insightSuggestion(insight: PolicyInsight): string {
  if (insight.insightType === "I5" && insight.evidenceGrade === "NOT_EVALUABLE") return I5_NOT_PROVEN_SUGGESTION;
  if (insight.insightType === "I7") {
    if (insight.evidenceGrade === "NOT_EVALUABLE") return I7_NOT_PROVEN_SUGGESTION;
    if (insight.counter?.reorderResult !== "SAFE") return I7_INSPECTION_SUGGESTION;
  }
  return TYPE_SUGGESTION[insight.insightType] ?? "UNKNOWN";
}
export const DIFFERING_CELL_WORDS: Readonly<Record<string, string>> = {
  source: "Source", destination: "Destination", service: "Service", application: "Application",
};
export const MERGE_CAVEAT = "Proven for the verdict and the log setting of stored management intent (profile settings are not collected for Check Point). The merged rule keeps the first rule's identity; names and comments are not concatenated. No command is generated: apply it in your own change window.";
/** I7 results in words: "no behaviour change" only for SAFE; never a bare "safe". */
export const REORDER_RESULT_WORDS: Readonly<Record<string, string>> = {
  SAFE: "No behaviour change", SAFE_INSPECTION_CHANGES: "Verdict-safe, inspection changes",
};
export const CROSSED_REASON_WORDS: Readonly<Record<string, string>> = {
  disjoint: "Disjoint: it matches none of this rule's packets on its install-on",
  "same verdict effect": "Same verdict effect: same action and inline layer",
};
export const REORDER_CAVEAT = "One move against the stored order; moves are not combined. No command is generated: apply it in your own change window.";
export const GRADE_EXPLAIN: Readonly<Record<InsightGrade, string>> = {
  PROVEN: "Follows from the stored policy by set semantics.",
  SUPPORTED: "Proven on the policy side, plus dated runtime evidence with a known window.",
  INDICATED: "Runtime evidence exists but its window or completeness is unknown, or the analysis ignored a constraint.",
  NOT_ESTABLISHED: "The policy shows a candidate but nothing shows it is safe.",
  NOT_EVALUABLE: "The analysis cannot read this; the reason is named.",
};
/** I3: how the covering rules' actions relate to the rule's own (the I1/I2 split, for a set). */
export const COVER_ACTION_WORDS: Readonly<Record<string, string>> = {
  SAME: "Every covering rule has this rule's action.",
  DIFFERENT: "At least one covering rule has another action: which rule is right is a question of intent.",
  INLINE_LAYER: "At least one covering rule is an Apply Layer rule; its inline layer's verdict is not analysed.",
};
const NEEDS_WORDS: Readonly<Record<InsightNeeds, string>> = {
  NONE: "nothing", TRAFFIC_LOGS: "traffic logs", COUNTER_SAMPLES: "hit-counter samples over time", NEW_COLLECTION: "a new collection",
  VENDOR_REPORT: "the management's unused-object report", NOT_RESOLVABLE: "nothing in this design",
};
export const raiseText = (insight: PolicyInsight) => {
  const { evidenceGrade: to, insightNeeds } = insight.raiseTo;
  return insightNeeds === "NONE" || to === insight.evidenceGrade ? "Nothing raises this" : `${to} with ${NEEDS_WORDS[insightNeeds] ?? "UNKNOWN"}`;
};
export const gradeColor = (grade: InsightGrade): "success" | "info" | "warning" | "default" =>
  grade === "PROVEN" ? "success" : grade === "SUPPORTED" ? "info" : grade === "INDICATED" ? "warning" : "default";
/** The grade chip of one insight: a merge (I5) or reorder (I7) is never shown in a green state, whatever its grade. */
export const insightGradeColor = (type: InsightType, grade: InsightGrade): "success" | "info" | "warning" | "default" =>
  (type === "I5" || type === "I7") && grade === "PROVEN" ? "info" : gradeColor(grade);

interface Party { id: string; number: number; name: string; action?: string }
const position = (p: Party | undefined) => p && p.number > 0 ? `#${p.number}` : "#?";
const named = (p: Party | undefined) => p?.name && p.name !== "UNKNOWN" ? ` (${p.name})` : "";
/** `rule #3 (name)`; a covering rule in an earlier section has no number in the list row, and says so. */
const ruleWords = (p: Party | undefined) => !p ? "a rule" : p.number > 0 ? `rule ${position(p)}${named(p)}` : `an earlier rule${named(p)}`;
const ruleSetWords = (parties: readonly Party[], more = 0) => {
  if (parties.length === 0) return "earlier rules";
  const list = parties.map(p => position(p)).join(", ");
  const suffix = more > 0 ? ` (+${more} more)` : "";
  return parties.length === 1 && more <= 0 ? `rule ${list}` : `rules ${list}${suffix}`;
};
const cap = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);
const withAction = (p: Party | undefined) => p?.action ? ` (${actionWord(p.action)})` : "";

/** The rules of an insight by role. The detail, once loaded, carries the real position and action; the list row is the fallback. */
export function insightParties(insight: PolicyInsight, detail?: PolicyInsightDetail | null): { subject?: Party; covering?: Party; coverings: Party[]; overlapping: Party[];
  above?: Party; crossed: Party[]; merged?: Party } {
  const collect = (role: "subject" | "covering" | "overlapping" | "promoteAbove" | "crossed" | "merged"): Party[] =>
    insight.rules.filter(r => r.insightRole === role).map(ref => {
      const full = detail?.rules.find(r => r.id === ref.ruleId);
      return { id: ref.ruleId, number: full?.number ?? ref.number, name: full?.name ?? ref.name, action: full?.action };
    });
  const coverings = collect("covering");
  return { subject: collect("subject")[0], covering: coverings[0], coverings, overlapping: collect("overlapping"),
    above: collect("promoteAbove")[0], crossed: collect("crossed"), merged: collect("merged")[0] };
}
const objectNames = (insight: PolicyInsight, detail?: PolicyInsightDetail | null) =>
  (detail?.objects ?? []).filter(o => insight.objectIds.includes(o.uid)).map(o => o.name).filter(Boolean);

/** One sentence saying what the insight is, in the words of its type; the engine's own evidence strings decide the I9 and I10 wording. */
export function insightHeadline(insight: PolicyInsight, detail?: PolicyInsightDetail | null): string {
  const { subject, covering, coverings, overlapping } = insightParties(insight, detail);
  const s = position(subject), c = covering && covering.number > 0 ? position(covering) : "the earlier rule";
  const Subject = cap(ruleWords(subject));
  const names = objectNames(insight, detail);
  switch (insight.insightType) {
    case "I1": return `${Subject} is fully shadowed by ${ruleWords(covering)}: every packet ${s} matches is decided earlier by ${c} with the same action${withAction(subject)}.`;
    case "I2": return `${Subject} is fully shadowed by ${ruleWords(covering)}, which has a different action: every packet ${s} matches is decided earlier by ${c}${withAction(covering)}, so the action of ${s}${withAction(subject)} never applies.`;
    case "I3": {
      if (insight.evidenceGrade === "NOT_EVALUABLE") {
        const reason = String(insight.counter?.unionReason ?? "UNKNOWN");
        return `${Subject} could not be checked for union cover (${reason}): whether earlier rules together cover it is UNKNOWN.`;
      }
      const set = ruleSetWords(coverings);
      const coverAction = String(insight.counter?.coverAction ?? "");
      if (coverAction === "DIFFERENT") {
        return `${Subject} is covered by the union of ${set}, at least one of which has a different action: every packet ${s} matches is decided earlier, so ${s}${withAction(subject)} is never reached.`;
      }
      if (coverAction === "INLINE_LAYER") {
        return `${Subject} is covered by the union of ${set}, including an Apply Layer rule whose inline layer verdict is not analysed.`;
      }
      return `${Subject} is covered by the union of ${set}: every packet ${s} matches is decided earlier by those rules together with the same action${withAction(subject)}.`;
    }
    case "I4": {
      if (insight.evidenceGrade === "NOT_EVALUABLE") {
        const reason = String(insight.counter?.partialReason ?? "UNKNOWN");
        return `${Subject} could not be checked for partial shadowing (${reason}): whether earlier rules overlap part of its match is UNKNOWN.`;
      }
      const more = Number(insight.counter?.moreOverlapping ?? 0);
      const set = ruleSetWords(overlapping, more);
      return `${Subject} is partly shadowed by ${set}: part of what ${s} matches is decided earlier with the same action${withAction(subject)}, which can explain a zero or low hit counter.`;
    }
    case "I5": {
      const { merged } = insightParties(insight, detail);
      if (insight.evidenceGrade === "NOT_EVALUABLE") {
        const reason = String(insight.counter?.mergeReason ?? "UNKNOWN");
        return `Merging ${ruleWords(merged)} into ${ruleWords(subject)} could not be checked (${reason}): whether they can be merged is UNKNOWN.`;
      }
      const cellName = (DIFFERING_CELL_WORDS[String(insight.counter?.differingCell ?? "")] ?? "UNKNOWN").toLowerCase();
      return `${Subject} and adjacent ${ruleWords(merged)} match the same packets in every cell except ${cellName} and can be merged into ${s} with no verdict or log setting change (profile settings are not collected for Check Point).`;
    }
    case "I7": {
      const { above } = insightParties(insight, detail);
      if (insight.evidenceGrade === "NOT_EVALUABLE") {
        const reason = String(insight.counter?.reorderReason ?? "UNKNOWN");
        return `Moving ${ruleWords(subject)} above ${ruleWords(above)} could not be checked (${reason}): whether it changes a verdict is UNKNOWN.`;
      }
      const crossedCount = Number(insight.counter?.crossedRules ?? 0);
      const across = crossedCount > 0 ? `, across ${crossedCount} ${crossedCount === 1 ? "rule" : "rules"} in between,` : "";
      return insight.counter?.reorderResult === "SAFE"
        ? `${Subject} can move above ${ruleWords(above)}${across} with no verdict or log setting change for any packet (profile settings are not collected for Check Point).`
        : `${Subject} can move above ${ruleWords(above)}${across} without changing a verdict. Verdict-safe, inspection changes: a crossed rule that overlaps its match has another profile or log setting, or that setting was not collected.`;
    }
    case "I8": return `${Subject} is disabled: it decides no packet while it stays disabled.`;
    case "I9":
      if (insight.evidenceGrade === "NOT_EVALUABLE") return `${Subject} has a time restriction the stored policy does not fully describe: whether its schedule has ended is UNKNOWN.`;
      return insight.evidence.includes("Stored schedule expires within 14 days.")
        ? `${Subject} is limited to a schedule that ends within 14 days.`
        : `${Subject} is limited to a schedule that has already ended.`;
    case "I10": {
      const zero = insight.counter?.hits === 0;
      const seen = zero ? "has zero hits in the stored counters" : "has no recent hit in the stored counters";
      const window = insight.counter?.windowKnown === true
        ? `The counters cover a measured window of ${insight.counter.windowDays} days.`
        : "The counter window is unknown, so this does not prove the rule is unused.";
      return `${Subject} ${seen}. ${window}`;
    }
    case "I11": {
      const object = names.length === 1 ? `Object ${names[0]}` : "This object";
      return insight.evidenceGrade === "PROVEN"
        ? `${object} is unused: the management's unused-object report lists it and no stored rule or group refers to it.`
        : `${object} has no stored reference from a rule or group. Without the management's unused-object report this is a lower bound, not proof that it is unused.`;
    }
    case "I12": return `${insight.objectIds.length} objects have the same type and the same values${names.length ? `: ${names.join(", ")}` : ""}. They can be merged into one.`;
    default: return "UNKNOWN insight type.";
  }
}

/** Why the engine's verdict holds, cell by cell (I1, I2, I3, I4). */
export function coverReasons(insight: PolicyInsight, detail?: PolicyInsightDetail | null): string[] {
  const { subject, covering, coverings, overlapping } = insightParties(insight, detail);
  const s = position(subject);
  if (insight.insightType === "I1" || insight.insightType === "I2") {
    const c = covering && covering.number > 0 ? position(covering) : "The earlier rule";
    return [
      `${c} is evaluated before ${s}.`,
      `Source, destination and service / application of ${c} each contain those of ${s}.`,
      "Both rules have the same install-on placement.",
      insight.insightType === "I1"
        ? `Both rules have the same action${withAction(subject)}, so ${s} changes no verdict.`
        : `The actions differ: ${c} decides${withAction(covering)}, so the action of ${s}${withAction(subject)} never applies.`,
    ];
  }
  if (insight.insightType === "I3" && insight.evidenceGrade !== "NOT_EVALUABLE" && coverings.length > 0) {
    const set = ruleSetWords(coverings);
    const coverAction = String(insight.counter?.coverAction ?? "");
    return [
      `${cap(set)} are all evaluated before ${s} in the same layer.`,
      `Together, their matches cover every packet ${s} matches across source, destination and service / application.`,
      "Every covering rule has a compatible install-on placement.",
      COVER_ACTION_WORDS[coverAction] ?? "Action relation UNKNOWN.",
    ];
  }
  if (insight.insightType === "I4" && insight.evidenceGrade !== "NOT_EVALUABLE" && overlapping.length > 0) {
    const more = Number(insight.counter?.moreOverlapping ?? 0);
    const set = ruleSetWords(overlapping, more);
    return [
      `${cap(set)} ${overlapping.length === 1 && more <= 0 ? "is" : "are"} evaluated before ${s} in the same layer.`,
      `Their matches overlap part of ${s} across source, destination and service / application, while leaving a non-empty remainder.`,
      `Every overlapping earlier rule has the same action${withAction(subject)} and a compatible install-on placement.`,
      `Nothing here checks the rules between ${s} and ${set} for reordering.`,
    ];
  }
  if (insight.insightType === "I5" && insight.evidenceGrade !== "NOT_EVALUABLE") {
    const { merged } = insightParties(insight, detail);
    const m = position(merged);
    const cellLabel = DIFFERING_CELL_WORDS[String(insight.counter?.differingCell ?? "")] ?? "UNKNOWN";
    return [
      `${s} and ${m} are adjacent enabled rules in the same layer and section; ${s} keeps its identity.`,
      `Both rules have the same install-on placement, action${withAction(subject)} and log setting (profile settings are not collected for Check Point).`,
      `Differing cell: ${cellLabel}. Every other cell matches the same values, so replacing ${s} and ${m} by their union in ${cellLabel.toLowerCase()} matches the union of the two rules.`,
    ];
  }
  if (insight.insightType === "I7" && insight.evidenceGrade !== "NOT_EVALUABLE") {
    const { above, crossed } = insightParties(insight, detail);
    const b = above && above.number > 0 ? position(above) : "the broad rule";
    const crossedCount = Number(insight.counter?.crossedRules ?? crossed.length);
    return [
      `${b} is the first earlier rule of the same layer and section that overlaps ${s}; both have the same action, inline layer and install-on.`,
      crossedCount === 0 ? `No enabled rule lies between ${b} and ${s}.`
        : `Each of the ${crossedCount} enabled ${crossedCount === 1 ? "rule" : "rules"} between them is disjoint from ${s} or has its verdict effect; none is an anchor (user, application or time constraint, unresolved object, Apply Layer).`,
      insight.counter?.equivalence === "EQUIVALENT"
        ? "Symbolic check: every packet whose deciding rule changes keeps its action, profile and log setting."
        : `Symbolic check: every packet whose deciding rule changes keeps its action; ${String(insight.counter?.changedRules ?? "?")} of the rules deciding it today differ in profile or log setting (${String(insight.counter?.changedBoxes ?? "?")} regions, not listed).`,
    ];
  }
  return [];
}

export type ChangeKind =
  | "REMOVE_RULE"
  | "DISABLE_RULE"
  | "REVIEW_INTENT"
  | "MOVE_ABOVE_COVERING"
  | "NARROW_RULE"
  | "MERGE_RULES"
  | "REORDER_RULE"
  | "EXTEND_SCHEDULE"
  | "DISABLE_ONE_WINDOW"
  | "REMOVE_OBJECT"
  | "MERGE_OBJECTS";
/**
 * Design section 4: only a PROVEN I1, a PROVEN same-action I3 or a PROVEN SAFE I7 enters a proposal as "no behaviour change";
 * an I7 whose verdicts hold but whose profile or log setting changes is "verdict-safe, inspection changes" (Q2); a review
 * changes nothing by itself.
 */
export type ChangeEffect = "No behaviour change" | "Verdict-safe, inspection changes" | "Behaviour may change" | "Review only";
export interface SuggestedAction {
  kind: ChangeKind; label: string;
  /** The action the insight speaks for: the main button when the grade is PROVEN, shown with a warning otherwise. */
  strong: boolean;
  /** The planned change in plain words, as it goes into the change list. */
  plan: string;
  effect: ChangeEffect;
  /** What the grade does not establish; empty for PROVEN. */
  warning: string;
  /** A limit of this action itself, whatever the grade (a move that changes verdicts, a holding period); empty when none. */
  caveat: string;
}

const GRADE_WARNING: Readonly<Record<InsightGrade, string>> = {
  PROVEN: "",
  SUPPORTED: "SUPPORTED, not proven: this rests on dated runtime evidence. Confirm before you apply it.",
  INDICATED: "INDICATED only: the evidence window or its completeness is unknown. Confirm before you apply it.",
  NOT_ESTABLISHED: "NOT_ESTABLISHED: nothing shows this change is safe. Confirm before you apply it.",
  NOT_EVALUABLE: "NOT_EVALUABLE: the analysis could not read this. Check it by hand before you apply it.",
};
export const gradeWarning = (grade: InsightGrade) => GRADE_WARNING[grade] ?? GRADE_WARNING.NOT_EVALUABLE;
const HOLDING = "Remove it after your team's holding period.";

/**
 * The actions an insight offers, by type; the grade decides the warning, never which actions exist. I2 and different-action
 * I3 have no remove (conflict is a question of intent, design section 6.3). I4 has narrow or reorder, never a one-click remove.
 */
export function suggestedActions(insight: PolicyInsight, detail?: PolicyInsightDetail | null): SuggestedAction[] {
  const { subject, covering, coverings, overlapping } = insightParties(insight, detail);
  const rule = ruleWords(subject), over = ruleWords(covering);
  const s = position(subject), c = covering && covering.number > 0 ? position(covering) : "the earlier rule";
  const proven = insight.evidenceGrade === "PROVEN", warning = gradeWarning(insight.evidenceGrade);
  const names = objectNames(insight, detail);
  const action = (kind: ChangeKind, label: string, strong: boolean, plan: string, effect: ChangeEffect, caveat = ""): SuggestedAction =>
    ({ kind, label, strong, plan, effect, warning, caveat });
  switch (insight.insightType) {
    case "I1": {
      const effect: ChangeEffect = proven ? "No behaviour change" : "Behaviour may change";
      return [
        action("REMOVE_RULE", "Remove shadowed rule", true, `Remove ${rule}: ${over} already decides every packet it matches, with the same action.`, effect),
        action("DISABLE_RULE", "Disable instead", false, `Disable ${rule} instead of removing it: ${over} already decides every packet it matches, with the same action.`, effect, HOLDING),
      ];
    }
    case "I2": return [
      action("REVIEW_INTENT", "Review intent", true,
        `Review which rule is right: ${rule}${withAction(subject)} is never reached because ${over}${withAction(covering)} decides first with a different action.`, "Review only",
        "No one-click remove: which of the two actions is intended is a human decision."),
      action("MOVE_ABOVE_COVERING", "Move above covering rule", false, `Move ${rule} above ${over}.`, "Behaviour may change",
        `Not verdict-safe: packets that ${c} decides today${withAction(covering)} and that ${s} also matches would get the action of ${s}${withAction(subject)} instead. The rules between the two positions are not checked here.`),
    ];
    case "I3": {
      const set = ruleSetWords(coverings);
      const coverAction = String(insight.counter?.coverAction ?? "");
      if (coverAction === "DIFFERENT" || coverAction === "INLINE_LAYER") {
        return [
          action("REVIEW_INTENT", "Review intent", true,
            `Review which rule is right: ${rule}${withAction(subject)} is covered by the union of ${set}, and not every covering rule has the same action.`, "Review only",
            COVER_ACTION_WORDS[coverAction]),
          action("DISABLE_RULE", "Disable instead", false,
            `Disable ${rule} instead of removing it after confirming the covering set (${set}).`, "Behaviour may change", HOLDING),
        ];
      }
      const effect: ChangeEffect = proven ? "No behaviour change" : "Behaviour may change";
      return [
        action("REMOVE_RULE", "Remove shadowed rule", true,
          `Remove ${rule}: ${set} together already decide every packet it matches, with the same action.`, effect),
        action("DISABLE_RULE", "Disable instead", false,
          `Disable ${rule} instead of removing it: ${set} together already decide every packet it matches, with the same action.`, effect, HOLDING),
      ];
    }
    case "I4": {
      const more = Number(insight.counter?.moreOverlapping ?? 0);
      const set = ruleSetWords(overlapping, more);
      return [
        action("NARROW_RULE", "Narrow rule", false,
          `Narrow ${rule} to exclude the overlap already decided by ${set}, or review whether ${rule} still carries traffic of its own.`, "Behaviour may change",
          `No one-click remove: ${s} still has a non-empty remainder not covered by earlier rules.`),
        action("REORDER_RULE", "Reorder above overlapping rules", false,
          `Move ${rule} above ${set} so ${s} collects its own hits directly.`, "Behaviour may change",
          `Not yet verified by safe-reorder (I7): rules between ${s} and ${set}, as well as logging and inspection profiles, are not checked here.`),
      ];
    }
    case "I5": {
      const { merged } = insightParties(insight, detail);
      const target = merged && merged.number > 0 ? `rule ${position(merged)}${named(merged)}` : `a rule${named(merged)}`;
      const effect: ChangeEffect = proven && insight.counter?.mergeResult === "MERGE_CANDIDATE" ? "No behaviour change" : "Behaviour may change";
      return [action("MERGE_RULES", "Merge adjacent rules", true, `Merge ${target} into ${rule}.`, effect, MERGE_CAVEAT)];
    }
    case "I7": {
      const { above } = insightParties(insight, detail);
      const effect: ChangeEffect = !proven ? "Behaviour may change" : insight.counter?.reorderResult === "SAFE" ? "No behaviour change" : "Verdict-safe, inspection changes";
      return [action("REORDER_RULE", "Promote above broad rule", true, `Move ${rule} above ${ruleWords(above)}.`, effect,
        effect === "Verdict-safe, inspection changes" ? `Review the profile and log differences first. ${REORDER_CAVEAT}` : REORDER_CAVEAT)];
    }
    case "I8": return [action("REMOVE_RULE", "Remove disabled rule", true, `Remove disabled ${rule}.`, "Behaviour may change", "Keep it for your team's holding period first.")];
    case "I9": {
      const ending = insight.evidence.includes("Stored schedule expires within 14 days.");
      return [
        action("REMOVE_RULE", "Remove rule", true, ending ? `Remove ${rule} once its schedule has ended.` : `Remove ${rule}: its schedule has ended.`, "Behaviour may change"),
        action("EXTEND_SCHEDULE", "Extend schedule", false, `Extend the schedule of ${rule} if the rule is still needed.`, "Behaviour may change"),
      ];
    }
    case "I10": return [action("DISABLE_ONE_WINDOW", "Disable for one window with logging on", true,
      `Disable ${rule} for one observation window with logging on, and remove it only after that window shows no need for it.`, "Behaviour may change",
      "The stored counters do not prove the rule is unused.")];
    case "I11": return [action("REMOVE_OBJECT", "Remove object", true,
      `Remove ${names.length === 1 ? `object ${names[0]}` : "the object"}: no stored rule or group refers to it.`, "Behaviour may change")];
    case "I12": return [action("MERGE_OBJECTS", "Merge duplicate objects", true,
      `Merge ${insight.objectIds.length} objects with the same type and values into one, point their references to it and remove the others${names.length ? `: ${names.join(", ")}` : ""}.`, "Behaviour may change")];
    default: return [];
  }
}

/** One planned change. An insight has at most one: choosing another action for the same insight replaces the earlier choice. */
export interface ChangeItem {
  insightId: string; insightType: InsightType; evidenceGrade: InsightGrade; kind: ChangeKind;
  label: string; plan: string; effect: ChangeEffect; warning: string; caveat: string;
  /** The snapshot the rule positions were read from; a later collection can renumber rules. */
  snapshotAt: string | null; addedAt: string;
}
export const changeItem = (insight: PolicyInsight, action: SuggestedAction, snapshotAt: string | null | undefined, now: Date = new Date()): ChangeItem => ({
  insightId: insight.insightId, insightType: insight.insightType, evidenceGrade: insight.evidenceGrade, kind: action.kind,
  label: action.label, plan: action.plan, effect: action.effect, warning: action.warning, caveat: action.caveat,
  snapshotAt: snapshotAt ?? null, addedAt: now.toISOString(),
});
export const withChange = (items: readonly ChangeItem[], item: ChangeItem): ChangeItem[] => [...items.filter(i => i.insightId !== item.insightId), item];
export const withoutChange = (items: readonly ChangeItem[], insightId: string): ChangeItem[] => items.filter(i => i.insightId !== insightId);

const MAX_ITEMS = 500;
/**
 * Per package and per view: a list written from a masked (aiview) session and one written from an unmasked session are
 * kept apart, so names stored by one never appear in the other in the same browser.
 */
export const changeListKey = (policy: string, masked: boolean) => `nx.policyInsights.changeList.v1:${masked ? "masked" : "plain"}:${policy}`;
const isItem = (value: unknown): value is ChangeItem => {
  const v = value as Record<string, unknown> | null;
  return !!v && typeof v === "object" && ["insightId", "insightType", "evidenceGrade", "kind", "label", "plan", "effect", "warning", "caveat", "addedAt"].every(k => typeof v[k] === "string")
    && (v.snapshotAt === null || typeof v.snapshotAt === "string");
};
/** The stored list; a missing, blocked or malformed store is an empty list, never an error. */
export function loadChangeList(policy: string, masked: boolean): ChangeItem[] {
  try {
    const parsed: unknown = JSON.parse(readPreference(changeListKey(policy, masked)) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter(isItem).slice(0, MAX_ITEMS) : [];
  } catch { return []; }
}
export function saveChangeList(policy: string, masked: boolean, items: readonly ChangeItem[]): void {
  writePreference(changeListKey(policy, masked), JSON.stringify(items.slice(0, MAX_ITEMS)));
}

export interface ChangeListExport { packageName?: string; masked: boolean; exportedAt: Date }
/** The list as a text file a person reads top to bottom in a change window. */
export function changeListText(items: readonly ChangeItem[], meta: ChangeListExport): string {
  const lines = [
    `Policy change list${meta.packageName ? ` · ${meta.packageName}` : ""}`,
    `Exported ${meta.exportedAt.toISOString()} · ${items.length} planned ${items.length === 1 ? "change" : "changes"}`,
    `Names: ${meta.masked ? "masked (aiview pseudonyms)" : "as recorded"}`,
    CHANGE_LIST_NOTICE,
    "Rule numbers are positions in the stored snapshot named on each change; check them against the current policy.",
    "",
  ];
  items.forEach((item, index) => {
    lines.push(`${index + 1}. ${item.label} [${item.insightType} · ${item.evidenceGrade} · ${item.effect}]`, `   ${item.plan}`);
    if (item.caveat) lines.push(`   Note: ${item.caveat}`);
    if (item.warning) lines.push(`   Warning: ${item.warning}`);
    lines.push(`   Snapshot collected: ${item.snapshotAt ?? "UNKNOWN"} · added ${item.addedAt}`, "");
  });
  return lines.join("\n");
}
export const CHANGE_LIST_CSV_HEADER = ["#", "Change", "Plan", "Insight", "Grade", "Effect", "Note", "Warning", "Snapshot collected", "Added"] as const;
export function changeListCsv(items: readonly ChangeItem[]): string {
  return toCsv([CHANGE_LIST_CSV_HEADER, ...items.map((item, index) =>
    [index + 1, item.label, item.plan, item.insightType, item.evidenceGrade, item.effect, item.caveat, item.warning, item.snapshotAt ?? "UNKNOWN", item.addedAt])]);
}
