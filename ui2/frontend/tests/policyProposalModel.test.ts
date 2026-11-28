import { expect, it } from "vitest";
import { CHANGES_HEADLINE, INSPECTION_HEADLINE, NOT_PROVEN_HEADLINE, namesById, orderRows, proposalChangeItems, proposalCountsLine, proposalHeadline, refusalItems, resultWord,
  stepSentence, mergeSentence } from "../src/screens/policyProposalModel";
import type { PolicyProposalResponse, ProposalStep } from "../src/auth/adminApi";

const ref = (id: string, number: number, name: string) => ({ ruleId: id, ruleName: name, ruleNumber: number });
const step = (over: Partial<ProposalStep> = {}): ProposalStep => ({ subject: ref("r3", 3, "RULE-ALPHA-03"), above: ref("r1", 1, "RULE-ALPHA-01"), result: "SAFE", accepted: true,
  equivalence: "EQUIVALENT", crossedRules: 1, inspectionChanges: false, ...over });
const base: PolicyProposalResponse = { vendor: "CP", supported: true, packageVerdict: "EQUIVALENT", label: "no behaviour change", accepted: 1, refused: {}, steps: [step()], order: [] };

it("headlines follow packageVerdict and label only", () => {
  expect(proposalHeadline(base)).toBe("No behaviour change");
  expect(proposalHeadline({ ...base, label: "verdict-safe, inspection changes" })).toBe(INSPECTION_HEADLINE);
  expect(proposalHeadline({ ...base, label: null })).toBe("UNKNOWN");
  expect(proposalHeadline({ ...base, packageVerdict: "NOT_PROVEN", label: null })).toBe(NOT_PROVEN_HEADLINE);
  expect(proposalHeadline({ ...base, packageVerdict: "CHANGES", label: null })).toBe(CHANGES_HEADLINE);
  expect(proposalHeadline({ ...base, packageVerdict: "NO_MOVES", label: null, emptyReason: "no offered move" })).toMatch(/no move was proposed/);
});
it("never says safe or no behaviour change for NOT_PROVEN and CHANGES", () => {
  for (const verdict of ["NOT_PROVEN", "CHANGES"]) {
    const text = proposalHeadline({ ...base, packageVerdict: verdict, label: null });
    expect(text).not.toMatch(/safe/i);
    expect(text).not.toMatch(/no behaviour change/i);
  }
});
it("yields one change item per accepted step and none for refused", () => {
  const resp: PolicyProposalResponse = { ...base, steps: [step(), step({ subject: ref("r4", 4, "RULE-ALPHA-04"), result: "SAFE_INSPECTION_CHANGES", inspectionChanges: true }),
    step({ subject: ref("r5", 5, "RULE-ALPHA-05"), result: "ANCHOR_BLOCKED", accepted: false })] };
  const items = proposalChangeItems(resp, "2026-11-23T08:00:00Z");
  expect(items.map(i => i.kind)).toEqual(["REORDER_RULE", "REORDER_RULE"]);
  expect(items.map(i => i.effect)).toEqual(["No behaviour change", "Verdict-safe, inspection changes"]);
  expect(items[0].plan).toBe("Move rule 3 RULE-ALPHA-03 above rule 1 RULE-ALPHA-01");
  expect(items.every(i => i.effect !== "Behaviour may change" && i.snapshotAt === "2026-11-23T08:00:00Z")).toBe(true);
  expect(new Set(items.map(i => i.insightId)).size).toBe(2);
  expect(proposalChangeItems({ ...base, steps: [step({ accepted: false, result: "NOT_PROVEN" })] }, null)).toEqual([]);
});
it("adds merges as MERGE_RULES items and words them with the differing cell", () => {
  const merge = { surviving: ref("r1", 1, "RULE-ALPHA-01"), merged: ref("r2", 2, "RULE-ALPHA-02"), differingCell: "service", accepted: true, equivalence: "EQUIVALENT" };
  expect(mergeSentence(merge)).toBe("Merge rule 2 RULE-ALPHA-02 into rule 1 RULE-ALPHA-01 (differs only in Service)");
  const items = proposalChangeItems({ ...base, steps: [], merges: [merge, { ...merge, merged: ref("r9", 9, "X"), accepted: false }] }, null);
  expect(items).toHaveLength(1);
  expect(items[0]).toMatchObject({ kind: "MERGE_RULES", effect: "No behaviour change" });
});
it("marks moved rules and strikes a merged-away rule in the order rows", () => {
  const rows = orderRows({ sectionId: "s", offset: 4, totalRules: 9, before: ["a", "b", "c"], after: ["c", "a"], moreRules: 0 }, new Map([["c", "#7 RULE-ALPHA-07"]]), new Set(["b"]));
  expect(rows.before.map(r => [r.position, r.label, r.moved, r.struck])).toEqual([
    [5, "rule at position 5", true, false], [6, "rule at position 6", false, true], [7, "#7 RULE-ALPHA-07", true, false]]);
  expect(rows.after.map(r => [r.position, r.moved])).toEqual([[5, true], [6, true]]);
  expect(rows.more).toBeNull();
});
it("a rule pushed out of a shifted window is not shown as merged away, and one entering it is not 'moved'", () => {
  const rows = orderRows({ sectionId: "s", offset: 0, totalRules: 60, before: ["a", "b", "z"], after: ["x", "a", "b"], moreRules: 57 }, new Map());
  expect(rows.before.find(r => r.ruleId === "z")).toMatchObject({ struck: false, outOfWindow: true, moved: false });
  expect(rows.after.find(r => r.ruleId === "x")).toMatchObject({ enteredWindow: true, moved: false });
  expect(rows.before.some(r => r.struck)).toBe(false);
});
it("missing counts read UNKNOWN, not zero", () => {
  expect(proposalCountsLine({ ...base, refused: undefined as never })).toBe("UNKNOWN of UNKNOWN candidate rules tried; 1 moves accepted; UNKNOWN refused");
});
it("prints +N more and counts what it counts", () => {
  expect(orderRows({ sectionId: "s", offset: 0, totalRules: 12, before: ["a"], after: ["a"], moreRules: 11, more: "+11 more" }, new Map()).more).toBe("+11 more");
  expect(proposalCountsLine({ ...base, tried: 3, candidatesTotal: 5, accepted: 1, refused: { ANCHOR_BLOCKED: 2 }, truncated: true, notTried: 2, notTriedNote: "+2 more not tried" }))
    .toBe("3 of 5 candidate rules tried; 1 moves accepted; 2 refused · +2 more not tried");
  expect(namesById({ ...base }).get("r3")).toBe("#3 RULE-ALPHA-03");
});
it("reads words outside the closed vocabulary as UNKNOWN", () => {
  expect(refusalItems({ MADE_UP: 2, ANCHOR_BLOCKED: 0 })).toEqual([{ word: "MADE_UP", count: 2, text: "UNKNOWN" }]);
  expect(resultWord("MADE_UP")).toBe("UNKNOWN");
  expect(stepSentence({ ...step(), above: null })).toBe("Move rule 3 RULE-ALPHA-03 above UNKNOWN");
  expect(proposalHeadline({ ...base, packageVerdict: "MADE_UP" })).toBe("UNKNOWN");
});
