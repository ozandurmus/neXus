import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyInsightsTab } from "../src/screens/PolicyInsightsTab";
import { CHANGE_LIST_NOTICE, changeItem, changeListCsv, changeListKey, changeListText, coverReasons, gradeWarning, insightHeadline, loadChangeList, saveChangeList, suggestedActions,
  withChange, withoutChange } from "../src/screens/policyInsightActions";
import { downloadText } from "../src/screens/DeviceShared";
import type { InsightGrade, InsightType, PolicyInsight, PolicyInsightDetail, PolicyInsightsPage } from "../src/auth/adminApi";

vi.mock("../src/screens/DeviceShared", async original => ({ ...(await original<typeof import("../src/screens/DeviceShared")>()), downloadText: vi.fn() }));

/** Policy Insights detail panel (PO 2026-11-25). Fixtures carry the AIView masked state only: pseudonyms and 192.0.2.x. */
const ids: Record<InsightType, string> = {
  I1: "11111111-1111-1111-1111-111111111111", I2: "22222222-2222-2222-2222-222222222222",
  I3: "33333333-3333-3333-3333-333333333333", I4: "44444444-4444-4444-4444-444444444444",
  I8: "88888888-8888-8888-8888-888888888888", I9: "99999999-9999-9999-9999-999999999999",
  I10: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", I11: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", I12: "cccccccc-cccc-cccc-cccc-cccccccccccc",
};
const subject = { ruleId: "r3", number: 3, name: "RULE-ALPHA-03", insightRole: "subject" as const };
const covering = { ruleId: "r2", number: 2, name: "RULE-ALPHA-02", insightRole: "covering" as const };
const coveringA = { ruleId: "r1", number: 1, name: "RULE-ALPHA-01", insightRole: "covering" as const };
const coveringB = { ruleId: "r2", number: 2, name: "RULE-ALPHA-02", insightRole: "covering" as const };
const overlapA = { ruleId: "r1", number: 1, name: "RULE-ALPHA-01", insightRole: "overlapping" as const };
const overlapB = { ruleId: "r2", number: 2, name: "RULE-ALPHA-02", insightRole: "overlapping" as const };
const make = (type: InsightType, grade: InsightGrade, over: Partial<PolicyInsight> = {}): PolicyInsight => ({
  insightId: ids[type], insightType: type, evidenceGrade: grade, insightScope: type === "I11" || type === "I12" ? "domain" : "package",
  rules: type === "I1" || type === "I2" ? [subject, covering]
    : type === "I3" ? [subject, coveringA, coveringB]
      : type === "I4" ? [subject, overlapA, overlapB]
        : type === "I11" || type === "I12" ? [] : [subject],
  objectIds: [], raiseTo: { evidenceGrade: grade, insightNeeds: "NONE" },
  insightCaveats: grade === "PROVEN" ? ["INSTALL_STATE_NOT_VERIFIED"] : [], evidence: [], counter: {}, ...over });
const cell = (...refs: string[]) => ({ refs, negated: false });
const rule = (id: string, number: number, name: string, action: string, source: string, over: Record<string, unknown> = {}) => ({ id, uuid: id, number, name, enabled: true, action, log: "Log",
  comment: "Withheld in AIView", extras: { "install-on": ["gw"] }, source: cell(source), destination: cell("any"), service: cell("https"), application: cell(), ...over });
const ruleObjects = [{ id: "any", name: "ANY", type: "any", status: "RESOLVED" }, { id: "net", name: "OBJ-NET-01", type: "network", status: "RESOLVED" },
  { id: "net2", name: "OBJ-NET-02", type: "network", status: "RESOLVED" },
  { id: "host", name: "OBJ-HOST-07", type: "host", status: "RESOLVED" }, { id: "https", name: "OBJ-SVC-02", type: "service-tcp", status: "RESOLVED" },
  { id: "gw", name: "FW-TANGO-04", type: "simple-gateway", status: "RESOLVED" }];
const shadowRules = (subjectAction: string) => [rule("r3", 3, "RULE-ALPHA-03", subjectAction, "host"), rule("r2", 2, "RULE-ALPHA-02", "Accept", "net")];
const unionRules = (secondAction = "Accept") => [
  rule("r3", 3, "RULE-ALPHA-03", "Accept", "host"),
  rule("r1", 1, "RULE-ALPHA-01", "Accept", "net"),
  rule("r2", 2, "RULE-ALPHA-02", secondAction, "net2"),
];
const detailOf = (insight: PolicyInsight, rules: unknown[] = [], objects: PolicyInsightDetail["objects"] = []): PolicyInsightDetail =>
  ({ insight, rules: rules as never, objects, ruleObjects, days: 90, collectedAt: "2026-11-23T08:00:00Z" });
const i1 = make("I1", "PROVEN", { evidence: ["Earlier enabled rule fully covers this match with the same action."] });
const i2 = make("I2", "PROVEN", { evidence: ["Earlier enabled rule fully covers this match with a different action."] });
const i3Same = make("I3", "PROVEN", { evidence: ["Earlier enabled rules together cover this match with the same action."], counter: { coveringRules: 2, coverAction: "SAME" } });
const i3Diff = make("I3", "PROVEN", { evidence: ["Earlier enabled rules together cover this match; at least one has a different action."], counter: { coveringRules: 2, coverAction: "DIFFERENT" } });
const i4 = make("I4", "PROVEN", { evidence: ["Part of this match is proven to overlap earlier enabled rules of the same layer; every overlapping earlier rule has the same action."],
  counter: { overlappingRules: 12, moreOverlapping: 2 } });
const i10 = make("I10", "INDICATED", { evidence: ["Zero collected hits; counter reset/window is unknown."], raiseTo: { evidenceGrade: "SUPPORTED", insightNeeds: "COUNTER_SAMPLES" },
  counter: { hits: 0, firstHit: null, lastHit: null, hitSource: "mds", hitsCollectedAt: "2026-11-23T08:00:00Z", counterWindow: "Counter window unknown.", windowDays: null, windowKnown: false } });
const d1 = detailOf(i1, shadowRules("Accept")), d2 = detailOf(i2, shadowRules("Drop"));
const d3Same = detailOf(i3Same, unionRules("Accept")), d3Diff = detailOf(i3Diff, unionRules("Drop"));
const d4 = detailOf(i4, unionRules("Accept"));
const d10 = detailOf(i10, [rule("r3", 3, "RULE-ALPHA-03", "Accept", "host")]);
const counts = { rules: 10, decided: 4, upToWithLogs: 7, logResolvable: 3, collectionResolvable: 1, notResolvable: 2, notDecidable: [], hitsCollected: 6, hitsMissing: 4, disabledRules: 1, outsideSecurityRules: 2 };
const pageOf = (...insights: PolicyInsight[]): PolicyInsightsPage => ({ vendor: "CP", supported: true, insights, counts, total: insights.length, page: 0, pageSize: 200, days: 90, complete: true, collectedAt: "2026-11-23T08:00:00Z" });

function mount(insights: PolicyInsight[], details: PolicyInsightDetail[], openRule = vi.fn(), openObject = vi.fn()) {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    const id = new URL(String(url), "http://localhost").searchParams.get("insightId");
    return new Response(JSON.stringify(id ? details.find(d => d.insight.insightId === id) : pageOf(...insights)), { status: 200 });
  }));
  const view = render(<ThemeProvider theme={m3Theme}><PolicyInsightsTab policy="p1" policyName="PKG-ALPHA-01" openRule={openRule} openObject={openObject} /></ThemeProvider>);
  return { openRule, openObject, ...view };
}
const rowOf = async (type: InsightType) => { await screen.findByRole("table", { name: "Policy insights" }); return document.querySelector<HTMLElement>(`tr[data-insight-id="${ids[type]}"]`)!; };
const openPanel = async (type: InsightType) => {
  fireEvent.click(await rowOf(type));
  const dialog = await screen.findByRole("dialog", { name: "Insight detail" });
  await within(dialog).findByLabelText("Suggested actions");
  return dialog;
};
const comparisonCell = (dialog: HTMLElement, field: string, role: string) => dialog.querySelector<HTMLElement>(`td[data-field="${field}"][data-role="${role}"]`)!;
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); window.localStorage.clear(); });

it("opens the detail in a drawer when the row is clicked, keeps the row highlighted and returns focus on Close", async () => {
  mount([i1, i10], [d1, d10]);
  const row = await rowOf("I1");
  expect(screen.queryByRole("dialog")).toBeNull();
  fireEvent.click(within(row).getByText("#3 RULE-ALPHA-03 · #2 RULE-ALPHA-02"));
  const dialog = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(await within(dialog).findByRole("table", { name: "Rule comparison" })).toBeInTheDocument();
  expect(row).toHaveClass("Mui-selected");
  fireEvent.click(within(dialog).getByRole("button", { name: "Close detail" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(row).toHaveFocus();
  expect(row).toHaveClass("Mui-selected");
});
it("opens from the keyboard on the row and closes on Escape with focus back on the row", async () => {
  mount([i1, i10], [d1, d10]);
  const row = await rowOf("I10");
  expect(row).toHaveAttribute("tabindex", "0");
  row.focus();
  fireEvent.keyDown(row, { key: "Enter" });
  const dialog = await screen.findByRole("dialog", { name: "Insight detail" });
  await within(dialog).findByLabelText("Suggested actions");
  fireEvent.keyDown(dialog, { key: "Escape" });
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(row).toHaveFocus();
  fireEvent.keyDown(row, { key: " " });
  expect(await screen.findByRole("dialog", { name: "Insight detail" })).toBeInTheDocument();
});

it("words the headline for every insight type", () => {
  expect(insightHeadline(i1, d1)).toBe("Rule #3 (RULE-ALPHA-03) is fully shadowed by rule #2 (RULE-ALPHA-02): every packet #3 matches is decided earlier by #2 with the same action (Accept).");
  expect(insightHeadline(i2, d2)).toBe("Rule #3 (RULE-ALPHA-03) is fully shadowed by rule #2 (RULE-ALPHA-02), which has a different action: "
    + "every packet #3 matches is decided earlier by #2 (Accept), so the action of #3 (Drop) never applies.");
  expect(insightHeadline(i3Same, d3Same)).toBe("Rule #3 (RULE-ALPHA-03) is covered by the union of rules #1, #2: every packet #3 matches is decided earlier by those rules together with the same action (Accept).");
  expect(insightHeadline(i3Diff, d3Diff)).toBe("Rule #3 (RULE-ALPHA-03) is covered by the union of rules #1, #2, at least one of which has a different action: every packet #3 matches is decided earlier, so #3 (Accept) is never reached.");
  expect(insightHeadline(make("I3", "PROVEN", { counter: { coveringRules: 2, coverAction: "INLINE_LAYER" } }), d3Same))
    .toBe("Rule #3 (RULE-ALPHA-03) is covered by the union of rules #1, #2, including an Apply Layer rule whose inline layer verdict is not analysed.");
  expect(insightHeadline(make("I3", "NOT_EVALUABLE", { rules: [subject], counter: { unionReason: "analysis budget" } })))
    .toBe("Rule #3 (RULE-ALPHA-03) could not be checked for union cover (analysis budget): whether earlier rules together cover it is UNKNOWN.");
  expect(insightHeadline(i4, d4)).toBe("Rule #3 (RULE-ALPHA-03) is partly shadowed by rules #1, #2 (+2 more): part of what #3 matches is decided earlier with the same action (Accept), which can explain a zero or low hit counter.");
  expect(insightHeadline(make("I4", "NOT_EVALUABLE", { rules: [subject], counter: { partialReason: "analysis budget" } })))
    .toBe("Rule #3 (RULE-ALPHA-03) could not be checked for partial shadowing (analysis budget): whether earlier rules overlap part of its match is UNKNOWN.");
  expect(insightHeadline(make("I8", "PROVEN"))).toBe("Rule #3 (RULE-ALPHA-03) is disabled: it decides no packet while it stays disabled.");
  expect(insightHeadline(make("I9", "PROVEN", { evidence: ["Stored schedule has expired."] }))).toBe("Rule #3 (RULE-ALPHA-03) is limited to a schedule that has already ended.");
  expect(insightHeadline(make("I9", "PROVEN", { evidence: ["Stored schedule expires within 14 days."] }))).toBe("Rule #3 (RULE-ALPHA-03) is limited to a schedule that ends within 14 days.");
  expect(insightHeadline(make("I9", "NOT_EVALUABLE", { evidence: ["Schedule evidence is incomplete."] }))).toContain("whether its schedule has ended is UNKNOWN");
  expect(insightHeadline(i10, d10)).toBe("Rule #3 (RULE-ALPHA-03) has zero hits in the stored counters. The counter window is unknown, so this does not prove the rule is unused.");
  expect(insightHeadline(make("I10", "SUPPORTED", { counter: { hits: 4, windowKnown: true, windowDays: 120 } })))
    .toBe("Rule #3 (RULE-ALPHA-03) has no recent hit in the stored counters. The counters cover a measured window of 120 days.");
  const object = { id: "o1", uid: "u1", name: "OBJ-HOST-07", type: "host" }, twin = { id: "o2", uid: "u2", name: "OBJ-HOST-09", type: "host" };
  const unused = make("I11", "PROVEN", { objectIds: ["u1"] });
  expect(insightHeadline(unused, detailOf(unused, [], [object]))).toBe("Object OBJ-HOST-07 is unused: the management's unused-object report lists it and no stored rule or group refers to it.");
  expect(insightHeadline(make("I11", "INDICATED", { objectIds: ["u1"] }))).toContain("this is a lower bound, not proof that it is unused");
  const twins = make("I12", "PROVEN", { objectIds: ["u1", "u2"] });
  expect(insightHeadline(twins, detailOf(twins, [], [object, twin]))).toBe("2 objects have the same type and the same values: OBJ-HOST-07, OBJ-HOST-09. They can be merged into one.");
});
it("does not invent a position for a covering rule the list row could not number", () => {
  const far = make("I1", "PROVEN", { rules: [subject, { ruleId: "r0", number: 0, name: "UNKNOWN", insightRole: "covering" }] });
  expect(insightHeadline(far)).toBe("Rule #3 (RULE-ALPHA-03) is fully shadowed by an earlier rule: every packet #3 matches is decided earlier by the earlier rule with the same action.");
});
it("shows the headline in the panel", async () => {
  mount([i2], [d2]);
  const dialog = await openPanel("I2");
  expect(dialog.querySelector("[data-insight-headline]")).toHaveTextContent(insightHeadline(i2, d2));
});

it("compares the rules side by side, marks who decides first and highlights the cells the cover rests on", async () => {
  const { openRule, openObject } = mount([i1], [d1]);
  const dialog = await openPanel("I1");
  const table = within(dialog).getByRole("table", { name: "Rule comparison" });
  const head = within(table).getAllByRole("columnheader");
  expect(head[1]).toHaveTextContent("Decides first"); expect(head[1]).toHaveTextContent("#2 · RULE-ALPHA-02");
  expect(head[2]).toHaveTextContent("Never reached"); expect(head[2]).toHaveTextContent("#3 · RULE-ALPHA-03");
  expect(within(table).getAllByRole("rowheader").map(h => h.textContent)).toEqual(
    ["Position", "Name", "Source", "Destination", "Service / application", "Action", "Track / log", "Enabled", "Install on", "Comments"]);
  expect(comparisonCell(dialog, "position", "covering")).toHaveTextContent("#2");
  expect(comparisonCell(dialog, "source", "covering")).toHaveTextContent("OBJ-NET-01");
  expect(comparisonCell(dialog, "source", "subject")).toHaveTextContent("OBJ-HOST-07");
  expect(comparisonCell(dialog, "destination", "subject")).toHaveTextContent("ANY");
  expect(comparisonCell(dialog, "service", "subject")).toHaveTextContent("OBJ-SVC-02");
  expect(comparisonCell(dialog, "action", "subject")).toHaveTextContent("Accept");
  expect(comparisonCell(dialog, "log", "subject")).toHaveTextContent("Log");
  expect(comparisonCell(dialog, "enabled", "subject")).toHaveTextContent("Enabled");
  expect(comparisonCell(dialog, "installOn", "covering")).toHaveTextContent("FW-TANGO-04");
  expect(comparisonCell(dialog, "comment", "subject")).toHaveTextContent("Withheld in AIView");
  for (const field of ["position", "source", "destination", "service", "action", "installOn"]) for (const role of ["covering", "subject"])
    expect(comparisonCell(dialog, field, role)).toHaveAttribute("data-cover", "holds");
  for (const field of ["name", "log", "enabled", "comment"]) expect(comparisonCell(dialog, field, "subject")).not.toHaveAttribute("data-cover");
  expect(within(dialog).getByLabelText("Why the cover holds")).toHaveTextContent("Source, destination and service / application of #2 each contain those of #3.");
  fireEvent.click(within(table).getByRole("button", { name: /#2 · RULE-ALPHA-02/ }));
  expect(openRule).toHaveBeenCalledWith("r2", 90);
  fireEvent.click(within(comparisonCell(dialog, "source", "subject")).getByRole("button"));
  expect(openObject).toHaveBeenCalledWith("host");
});
it("marks the action cells of a conflict as differing and a disabled rule's enabled cell", async () => {
  const off = make("I8", "PROVEN", { evidence: ["Rule is disabled."] });
  const view = mount([i2], [d2]);
  const dialog = await openPanel("I2");
  expect(comparisonCell(dialog, "action", "covering")).toHaveAttribute("data-cover", "differs");
  expect(comparisonCell(dialog, "action", "subject")).toHaveTextContent("Drop");
  expect(coverReasons(i2, d2)[3]).toBe("The actions differ: #2 decides (Accept), so the action of #3 (Drop) never applies.");
  expect(coverReasons(off)).toEqual([]);
  view.unmount();
  mount([off], [detailOf(off, [rule("r3", 3, "RULE-ALPHA-03", "Accept", "host", { enabled: false })])]);
  const single = await openPanel("I8");
  expect(comparisonCell(single, "enabled", "subject")).toHaveAttribute("data-cover", "holds");
  expect(comparisonCell(single, "enabled", "subject")).toHaveTextContent("Disabled");
  expect(single.querySelector('td[data-role="covering"]')).toBeNull();
});

it("compares every covering rule of an I3 union cover side by side and offers I1-like actions when PROVEN with the same action", async () => {
  mount([i3Same], [d3Same]);
  const dialog = await openPanel("I3");
  expect(dialog.querySelector("[data-insight-headline]")).toHaveTextContent(
    "Rule #3 (RULE-ALPHA-03) is covered by the union of rules #1, #2: every packet #3 matches is decided earlier by those rules together with the same action (Accept).");
  const table = within(dialog).getByRole("table", { name: "Rule comparison" });
  const head = within(table).getAllByRole("columnheader");
  expect(head).toHaveLength(4);
  expect(head[1]).toHaveTextContent("Decides first"); expect(head[1]).toHaveTextContent("#1 · RULE-ALPHA-01");
  expect(head[2]).toHaveTextContent("Decides first"); expect(head[2]).toHaveTextContent("#2 · RULE-ALPHA-02");
  expect(head[3]).toHaveTextContent("Never reached"); expect(head[3]).toHaveTextContent("#3 · RULE-ALPHA-03");
  expect(within(dialog).getByLabelText("Why the cover holds")).toHaveTextContent("Rules #1, #2 are all evaluated before #3 in the same layer.");
  const actions = within(dialog).getByLabelText("Suggested actions");
  const removeBtn = within(actions).getByRole("button", { name: "Remove shadowed rule" });
  expect(removeBtn).toHaveClass("MuiButton-contained");
  expect(actions.querySelector('[data-action="REMOVE_RULE"] [data-action-effect]')).toHaveTextContent("No behaviour change");
  expect(within(actions).getByRole("button", { name: "Disable instead" })).toHaveClass("MuiButton-outlined");
  fireEvent.click(removeBtn);
  expect(loadChangeList("p1", false)[0]).toMatchObject({ insightType: "I3", kind: "REMOVE_RULE", effect: "No behaviour change" });
});

it("marks differing action on I3 when a covering rule has another action and offers review instead of remove", async () => {
  mount([i3Diff], [d3Diff]);
  const dialog = await openPanel("I3");
  expect(comparisonCell(dialog, "action", "subject")).toHaveAttribute("data-cover", "differs");
  const actions = within(dialog).getByLabelText("Suggested actions");
  expect(within(actions).queryByRole("button", { name: /Remove/ })).toBeNull();
  expect(within(actions).getByRole("button", { name: "Review intent" })).toHaveClass("MuiButton-contained");
  expect(within(actions).getByRole("button", { name: "Disable instead" })).toHaveClass("MuiButton-outlined");
});

it("shows I4 side by side with every overlapping earlier rule, +N more, and offers narrow or reorder but never a one-click remove", async () => {
  mount([i4], [d4]);
  const dialog = await openPanel("I4");
  expect(dialog.querySelector("[data-insight-headline]")).toHaveTextContent(
    "Rule #3 (RULE-ALPHA-03) is partly shadowed by rules #1, #2 (+2 more): part of what #3 matches is decided earlier with the same action (Accept), which can explain a zero or low hit counter.");
  const table = within(dialog).getByRole("table", { name: "Rule comparison" });
  const head = within(table).getAllByRole("columnheader");
  expect(head).toHaveLength(4);
  expect(head[1]).toHaveTextContent("Overlapping earlier rule"); expect(head[1]).toHaveTextContent("#1 · RULE-ALPHA-01");
  expect(head[2]).toHaveTextContent("Overlapping earlier rule"); expect(head[2]).toHaveTextContent("#2 · RULE-ALPHA-02");
  expect(head[3]).toHaveTextContent("Partly shadowed"); expect(head[3]).toHaveTextContent("#3 · RULE-ALPHA-03");
  expect(dialog).toHaveTextContent("Showing 2 of 12 overlapping earlier rules (+2 more not listed).");
  const actions = within(dialog).getByLabelText("Suggested actions");
  expect(within(actions).queryByRole("button", { name: /Remove/ })).toBeNull();
  const narrowBtn = within(actions).getByRole("button", { name: "Narrow rule" });
  const reorderBtn = within(actions).getByRole("button", { name: "Reorder above overlapping rules" });
  expect(narrowBtn).toHaveClass("MuiButton-outlined");
  expect(reorderBtn).toHaveClass("MuiButton-outlined");
  expect(actions.querySelector('[data-action="NARROW_RULE"] [data-action-effect]')).toHaveTextContent("Behaviour may change");
  expect(actions.querySelector('[data-action="REORDER_RULE"] [data-action-caveat]')).toHaveTextContent("Not yet verified by safe-reorder (I7)");
  fireEvent.click(reorderBtn);
  expect(loadChangeList("p1", false)[0]).toMatchObject({ insightType: "I4", kind: "REORDER_RULE", effect: "Behaviour may change" });
  fireEvent.click(narrowBtn);
  expect(loadChangeList("p1", false).map(i => i.kind)).toEqual(["NARROW_RULE"]);
});

it("offers the actions of each type and never a remove for a conflict or partial shadow", () => {
  const kinds = (insight: PolicyInsight) => suggestedActions(insight).map(a => `${a.kind}:${a.label}`);
  expect(kinds(i1)).toEqual(["REMOVE_RULE:Remove shadowed rule", "DISABLE_RULE:Disable instead"]);
  expect(kinds(i2)).toEqual(["REVIEW_INTENT:Review intent", "MOVE_ABOVE_COVERING:Move above covering rule"]);
  expect(kinds(i3Same)).toEqual(["REMOVE_RULE:Remove shadowed rule", "DISABLE_RULE:Disable instead"]);
  expect(kinds(i3Diff)).toEqual(["REVIEW_INTENT:Review intent", "DISABLE_RULE:Disable instead"]);
  expect(kinds(i4)).toEqual(["NARROW_RULE:Narrow rule", "REORDER_RULE:Reorder above overlapping rules"]);
  expect(kinds(make("I8", "PROVEN"))).toEqual(["REMOVE_RULE:Remove disabled rule"]);
  expect(kinds(make("I9", "PROVEN"))).toEqual(["REMOVE_RULE:Remove rule", "EXTEND_SCHEDULE:Extend schedule"]);
  expect(kinds(i10)).toEqual(["DISABLE_ONE_WINDOW:Disable for one window with logging on"]);
  expect(kinds(make("I11", "INDICATED"))).toEqual(["REMOVE_OBJECT:Remove object"]);
  expect(kinds(make("I12", "PROVEN"))).toEqual(["MERGE_OBJECTS:Merge duplicate objects"]);
  const move = suggestedActions(i2, d2)[1];
  expect(move.plan).toBe("Move rule #3 (RULE-ALPHA-03) above rule #2 (RULE-ALPHA-02).");
  expect(move.caveat).toContain("Not verdict-safe: packets that #2 decides today (Accept) and that #3 also matches would get the action of #3 (Drop) instead.");
  expect(suggestedActions(i2, d2)[0].effect).toBe("Review only");
});
it("grades the actions: PROVEN carries no warning, a weaker grade carries its own, and only PROVEN I1 or same-action I3 changes no behaviour", () => {
  expect(suggestedActions(i1).map(a => [a.strong, a.effect, a.warning])).toEqual([[true, "No behaviour change", ""], [false, "No behaviour change", ""]]);
  expect(suggestedActions(i3Same).map(a => [a.strong, a.effect, a.warning])).toEqual([[true, "No behaviour change", ""], [false, "No behaviour change", ""]]);
  expect(suggestedActions(i3Diff).map(a => [a.strong, a.effect, a.warning])).toEqual([[true, "Review only", ""], [false, "Behaviour may change", ""]]);
  expect(suggestedActions(i4).map(a => [a.strong, a.effect, a.warning])).toEqual([[false, "Behaviour may change", ""], [false, "Behaviour may change", ""]]);
  expect(suggestedActions(make("I3", "NOT_EVALUABLE", { rules: [subject], counter: { unionReason: "analysis budget" } }))[0])
    .toMatchObject({ effect: "Behaviour may change", warning: gradeWarning("NOT_EVALUABLE") });
  expect(suggestedActions(make("I4", "NOT_EVALUABLE", { rules: [subject], counter: { partialReason: "analysis budget" } }))[0])
    .toMatchObject({ effect: "Behaviour may change", warning: gradeWarning("NOT_EVALUABLE") });
  expect(suggestedActions(make("I8", "PROVEN"))[0]).toMatchObject({ effect: "Behaviour may change", warning: "" });
  expect(suggestedActions(i10)[0]).toMatchObject({ strong: true, warning: gradeWarning("INDICATED") });
  expect(gradeWarning("INDICATED")).toContain("INDICATED only");
  expect(suggestedActions(make("I9", "NOT_EVALUABLE"))[0].warning).toContain("NOT_EVALUABLE");
  expect(suggestedActions(make("I11", "INDICATED"))[0].warning).toBe(gradeWarning("INDICATED"));
  expect(suggestedActions(make("I11", "PROVEN"))[0].warning).toBe("");
  expect(suggestedActions(make("I1", "INDICATED"))[0].effect).toBe("Behaviour may change");
});
it("shows the strong action as the main button when PROVEN and with a warning when only INDICATED", async () => {
  const view = mount([i1], [d1]);
  const proven = within(await openPanel("I1")).getByLabelText("Suggested actions");
  expect(within(proven).getByRole("button", { name: "Remove shadowed rule" })).toHaveClass("MuiButton-contained");
  expect(within(proven).getByRole("button", { name: "Disable instead" })).toHaveClass("MuiButton-outlined");
  expect(proven.querySelector("[data-action-warning]")).toBeNull();
  expect(proven.querySelector('[data-action="REMOVE_RULE"] [data-action-effect]')).toHaveTextContent("No behaviour change");
  view.unmount();
  mount([i10], [d10]);
  const indicated = within(await openPanel("I10")).getByLabelText("Suggested actions");
  const button = within(indicated).getByRole("button", { name: "Disable for one window with logging on" });
  expect(button).toBeEnabled(); expect(button).toHaveClass("MuiButton-outlinedWarning");
  expect(indicated.querySelector("[data-action-warning]")).toHaveTextContent(gradeWarning("INDICATED"));
});
it("shows the move of a conflict with its verdict-safety caveat and no remove button", async () => {
  mount([i2], [d2]);
  const actions = within(await openPanel("I2")).getByLabelText("Suggested actions");
  expect(within(actions).queryByRole("button", { name: /Remove/ })).toBeNull();
  const move = actions.querySelector<HTMLElement>('[data-action="MOVE_ABOVE_COVERING"]')!;
  expect(move).toHaveTextContent("Move rule #3 (RULE-ALPHA-03) above rule #2 (RULE-ALPHA-02).");
  expect(move.querySelector("[data-action-caveat]")).toHaveTextContent("Not verdict-safe");
});

it("adds a suggested action to the change list, counts it, keeps it in the browser and replaces it by another choice", async () => {
  mount([i1, i10], [d1, d10]);
  const dialog = await openPanel("I1");
  expect(dialog.querySelector("[data-change-notice]")).toHaveTextContent(CHANGE_LIST_NOTICE);
  fireEvent.click(within(dialog).getByRole("button", { name: "Remove shadowed rule" }));
  expect(within(dialog).getByRole("button", { name: "Change list, 1 planned change" })).toBeInTheDocument();
  expect(within(dialog).getByRole("button", { name: "✓ Remove shadowed rule" })).toHaveAttribute("aria-pressed", "true");
  let stored = loadChangeList("p1", false);
  expect(stored).toHaveLength(1);
  expect(stored[0]).toMatchObject({ insightId: ids.I1, kind: "REMOVE_RULE", evidenceGrade: "PROVEN", effect: "No behaviour change", snapshotAt: "2026-11-23T08:00:00Z",
    plan: "Remove rule #3 (RULE-ALPHA-03): rule #2 (RULE-ALPHA-02) already decides every packet it matches, with the same action." });
  expect(window.localStorage.getItem(changeListKey("p1", true))).toBeNull();
  fireEvent.click(within(dialog).getByRole("button", { name: "Disable instead" }));
  stored = loadChangeList("p1", false);
  expect(stored.map(i => i.kind)).toEqual(["DISABLE_RULE"]);
  fireEvent.click(within(dialog).getByRole("button", { name: "Close detail" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(document.querySelector("[data-change-count]")).toHaveTextContent("1");
  expect(await rowOf("I1")).toHaveTextContent("Disable instead");
});
it("lists the planned changes in plain words, says nothing is sent to the firewall, exports and removes", async () => {
  saveChangeList("p1", false, [changeItem(i1, suggestedActions(i1, d1)[0], d1.collectedAt, new Date("2026-11-25T09:00:00Z")),
    changeItem(i10, suggestedActions(i10, d10)[0], d10.collectedAt, new Date("2026-11-25T09:01:00Z"))]);
  mount([i1, i10], [d1, d10]);
  const open = await screen.findByRole("button", { name: "Change list, 2 planned changes" });
  fireEvent.click(open);
  const panel = await screen.findByRole("dialog", { name: "Change list" });
  expect(within(panel).getByRole("note")).toHaveTextContent("Nothing is sent to the firewall. Apply these in your own change window.");
  const items = within(within(panel).getByLabelText("Planned changes")).getAllByRole("listitem");
  expect(items).toHaveLength(2);
  expect(items[0]).toHaveTextContent("1. Remove shadowed rule");
  expect(items[0]).toHaveTextContent("Remove rule #3 (RULE-ALPHA-03): rule #2 (RULE-ALPHA-02) already decides every packet it matches, with the same action.");
  expect(items[1]).toHaveTextContent("Disable rule #3 (RULE-ALPHA-03) for one observation window with logging on");
  expect(items[1]).toHaveTextContent(gradeWarning("INDICATED"));
  fireEvent.click(within(panel).getByRole("button", { name: "Export change list (text)" }));
  fireEvent.click(within(panel).getByRole("button", { name: "Export change list (CSV)" }));
  const calls = vi.mocked(downloadText).mock.calls;
  expect(calls[0][0]).toMatch(/^policy-change-list-.*\.txt$/); expect(calls[0][2]).toBe("text/plain;charset=utf-8");
  expect(calls[0][1]).toContain("Policy change list · PKG-ALPHA-01"); expect(calls[0][1]).toContain(CHANGE_LIST_NOTICE);
  expect(calls[1][0]).toMatch(/\.csv$/); expect(calls[1][1]).toContain("Disable for one window with logging on");
  fireEvent.click(within(panel).getByRole("button", { name: "Remove from list: Remove shadowed rule, change 1" }));
  expect(within(within(panel).getByLabelText("Planned changes")).getAllByRole("listitem")).toHaveLength(1);
  expect(loadChangeList("p1", false).map(i => i.insightId)).toEqual([ids.I10]);
  fireEvent.click(within(panel).getByRole("button", { name: "Close change list" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(screen.getByRole("button", { name: "Change list, 1 planned change" })).toHaveFocus();
});
it("removes the planned change of an insight from its own panel and reaches the list from there", async () => {
  mount([i1], [d1]);
  const dialog = await openPanel("I1");
  expect(within(dialog).queryByRole("button", { name: "Remove from list" })).toBeNull();
  fireEvent.click(within(dialog).getByRole("button", { name: "Remove shadowed rule" }));
  fireEvent.click(within(dialog).getByRole("button", { name: "Remove from list" }));
  expect(loadChangeList("p1", false)).toEqual([]);
  fireEvent.click(within(dialog).getByRole("button", { name: "Change list, 0 planned changes" }));
  const panel = await screen.findByRole("dialog", { name: "Change list" });
  expect(within(panel).getByRole("status")).toHaveTextContent("No planned changes.");
  expect(within(panel).getByRole("button", { name: "Export change list (text)" })).toBeDisabled();
  fireEvent.click(within(panel).getByRole("button", { name: "Back to insight" }));
  expect(await screen.findByRole("dialog", { name: "Insight detail" })).toBeInTheDocument();
});

it("exports a readable text and a CSV, keeps one change per insight and ignores a malformed store", () => {
  const first = changeItem(i1, suggestedActions(i1, d1)[0], "2026-11-23T08:00:00Z", new Date("2026-11-25T09:00:00Z"));
  const move = changeItem(i2, suggestedActions(i2, d2)[1], null, new Date("2026-11-25T09:02:00Z"));
  const list = withChange(withChange([first], move), changeItem(i1, suggestedActions(i1, d1)[1], null, new Date("2026-11-25T09:03:00Z")));
  expect(list.map(i => `${i.insightType}:${i.kind}`)).toEqual(["I2:MOVE_ABOVE_COVERING", "I1:DISABLE_RULE"]);
  expect(withoutChange(list, ids.I2).map(i => i.insightType)).toEqual(["I1"]);
  const text = changeListText([first, move], { packageName: "PKG-ALPHA-01", masked: true, exportedAt: new Date("2026-11-25T10:00:00Z") });
  expect(text.split("\n").slice(0, 4)).toEqual(["Policy change list · PKG-ALPHA-01", "Exported 2026-11-25T10:00:00.000Z · 2 planned changes", "Names: masked (aiview pseudonyms)", CHANGE_LIST_NOTICE]);
  expect(text).toContain("1. Remove shadowed rule [I1 · PROVEN · No behaviour change]");
  expect(text).toContain("2. Move above covering rule [I2 · PROVEN · Behaviour may change]");
  expect(text).toContain("   Note: Not verdict-safe");
  expect(text).toContain("   Snapshot collected: UNKNOWN · added 2026-11-25T09:02:00.000Z");
  const csv = changeListCsv([first, move]).split("\r\n");
  expect(csv[0]).toBe("#,Change,Plan,Insight,Grade,Effect,Note,Warning,Snapshot collected,Added");
  expect(csv[1]).toBe('1,Remove shadowed rule,"Remove rule #3 (RULE-ALPHA-03): rule #2 (RULE-ALPHA-02) already decides every packet it matches, with the same action.",I1,PROVEN,No behaviour change,,,2026-11-23T08:00:00Z,2026-11-25T09:00:00.000Z');
  expect(csv).toHaveLength(4);
  window.localStorage.setItem(changeListKey("p1", false), JSON.stringify([first, { insightId: 7 }, "x"]));
  expect(loadChangeList("p1", false)).toEqual([first]);
  window.localStorage.setItem(changeListKey("p1", false), "{not json");
  expect(loadChangeList("p1", false)).toEqual([]);
});
