import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { COVER_ACTION_WORDS, INSIGHT_TYPES, INSTALL_CAVEAT, PolicyInsightsTab, packageNumbers, raiseText } from "../src/screens/PolicyInsightsTab";
import { POLICY_TAB_GROUPS, policyTabFromSlug } from "../src/screens/PolicyScreen";
import type { PolicyInsight, PolicyInsightCounts, PolicyInsightDetail, PolicyInsightsPage } from "../src/auth/adminApi";

const counts: PolicyInsightCounts = {
  rules: 10, decided: 4, upToWithLogs: 7, logResolvable: 3, collectionResolvable: 1, notResolvable: 2,
  notDecidable: [{ shadowReason: "user or category constraint", reasonClass: "LOGS", rules: 3 }, { shadowReason: "snapshot collection incomplete", reasonClass: "COLLECTION", rules: 1 },
    { shadowReason: "unresolved object", reasonClass: "NOT_RESOLVABLE", rules: 2 }],
  hitsCollected: 6, hitsMissing: 4, disabledRules: 1, outsideSecurityRules: 2,
};
const shadow: PolicyInsight = { insightId: "11111111-1111-1111-1111-111111111111", insightType: "I1", evidenceGrade: "PROVEN", insightScope: "package",
  rules: [{ ruleId: "r2", number: 2, name: "RULE-ALPHA-02", insightRole: "subject" }, { ruleId: "r1", number: 1, name: "RULE-ALPHA-01", insightRole: "covering" }], objectIds: [],
  raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "NONE" }, insightCaveats: ["INSTALL_STATE_NOT_VERIFIED"],
  evidence: ["Earlier enabled rule fully covers this match with the same action."], counter: {} };
const zero: PolicyInsight = { insightId: "22222222-2222-2222-2222-222222222222", insightType: "I10", evidenceGrade: "INDICATED", insightScope: "package",
  rules: [{ ruleId: "r3", number: 3, name: "RULE-ALPHA-03", insightRole: "subject" }], objectIds: [],
  raiseTo: { evidenceGrade: "SUPPORTED", insightNeeds: "COUNTER_SAMPLES" }, insightCaveats: [],
  evidence: ["Zero collected hits; counter reset/window is unknown."],
  counter: { hits: 0, firstHit: null, lastHit: null, hitSource: "mds", hitsCollectedAt: "2026-11-23T08:00:00Z", level: "zero", counterWindow: "Counter window unknown.", windowDays: null, windowKnown: false } };
const page: PolicyInsightsPage = { vendor: "CP", supported: true, insights: [shadow, zero], counts, total: 2, page: 0, pageSize: 200, days: 90, complete: true,
  collectedAt: "2026-11-23T08:00:00Z", byType: { I1: 1, I2: 0, I3: 0, I8: 0, I9: 0, I10: 1, I11: 0, I12: 0 }, byGrade: { PROVEN: 1, SUPPORTED: 0, INDICATED: 1, NOT_ESTABLISHED: 0, NOT_EVALUABLE: 0 } };
const rule = (id: string, number: number, name: string) => ({ id, uuid: id, number, name, enabled: true, action: "Accept", log: "Log", comment: "", extras: {}, source: { refs: [], negated: false },
  destination: { refs: [], negated: false }, service: { refs: [], negated: false }, application: { refs: [], negated: false } });
const detail: PolicyInsightDetail = { insight: shadow, rules: [rule("r2", 2, "RULE-ALPHA-02"), rule("r1", 1, "RULE-ALPHA-01")] as never, objects: [], days: 90 };

function mount(responses: { list?: unknown; detail?: unknown } = {}, openRule = vi.fn()) {
  const fetchMock = vi.fn(async (url: string) => new Response(JSON.stringify(String(url).includes("/insight?") ? responses.detail ?? detail : responses.list ?? page), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  render(<ThemeProvider theme={m3Theme}><PolicyInsightsTab policy="p1" openRule={openRule} /></ThemeProvider>);
  return { fetchMock, openRule };
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.localStorage.clear(); });

it("states the three package numbers as counts first and a share second, with up to as a ceiling", async () => {
  mount();
  const region = await screen.findByLabelText("Package counts");
  expect(region).toHaveTextContent("4 of 10 rules decided from the policy alone (40 %) · up to 7 with traffic logs (70 %) · 3 not decidable by logs");
  expect(region.querySelector("[data-count=decided]")).toHaveTextContent("4");
  expect(region.querySelector("[data-count=logs]")).toHaveTextContent("3");
  expect(region.querySelector("[data-count=collection]")).toHaveTextContent("1");
  expect(region.querySelector("[data-count=none]")).toHaveTextContent("2");
  expect(region.querySelectorAll("[data-segment]").length).toBe(4);
  const reasons = within(region).getByLabelText("Not decidable by reason");
  expect(reasons).toHaveTextContent("3 rules · user or category constraint (traffic logs could decide)");
  expect(reasons).toHaveTextContent("2 rules · unresolved object (nothing in this design can decide)");
  expect(region).toHaveTextContent("Over 10 enabled security rules. 1 disabled and 2 NAT rules are left out");
  expect(region).toHaveTextContent("Hit counters: 6 stored · 4 not collected");
  expect(region).toHaveTextContent("known before any log exists");
});
it("lists each insight with its type, rules, grade and what raises the grade", async () => {
  mount();
  const table = await screen.findByRole("table", { name: "Policy insights" });
  const rows = within(table).getAllByRole("row");
  expect(rows).toHaveLength(3);
  expect(rows[1]).toHaveTextContent("I1 · Fully shadowed, same action");
  expect(rows[1]).toHaveTextContent("#2 RULE-ALPHA-02 · #1 RULE-ALPHA-01");
  expect(rows[1]).toHaveTextContent("PROVEN");
  expect(rows[1]).toHaveTextContent("Nothing raises this");
  expect(rows[2]).toHaveTextContent("INDICATED");
  expect(rows[2]).toHaveTextContent("SUPPORTED with hit-counter samples over time");
  expect(screen.getByLabelText("Insights by type")).toHaveTextContent("I10 Zero or old hits: 1");
});
it("opens the detail view with the rules, the evidence and the install-state caveat for a PROVEN insight", async () => {
  const { fetchMock, openRule } = mount();
  fireEvent.click(await screen.findByRole("button", { name: "Open I1 detail" }));
  const region = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(fetchMock.mock.calls.some(call => String(call[0]).includes("/insight?insightId=11111111-1111-1111-1111-111111111111"))).toBe(true);
  expect(await within(region).findByRole("table", { name: "Rule comparison" })).toHaveTextContent("Decides first");
  expect(within(region).getByLabelText("Insight evidence")).toHaveTextContent("Earlier enabled rule fully covers this match with the same action.");
  expect(within(region).getByRole("note")).toHaveTextContent(INSTALL_CAVEAT);
  expect(INSTALL_CAVEAT).toContain("INSTALL_STATE_NOT_VERIFIED");
  fireEvent.click(within(region).getByRole("button", { name: /#1 · RULE-ALPHA-01/ }));
  expect(openRule).toHaveBeenCalledWith("r1", 90);
});
it("shows the counter evidence of a zero-hit insight with an unknown window and no install-state caveat", async () => {
  mount({ detail: { ...detail, insight: zero, rules: [rule("r3", 3, "RULE-ALPHA-03")] } });
  fireEvent.click(await screen.findByRole("button", { name: "Open I10 detail" }));
  const region = await screen.findByRole("dialog", { name: "Insight detail" });
  const counter = await within(region).findByLabelText("Counter evidence");
  expect(counter).toHaveTextContent("Hits: 0");
  expect(counter).toHaveTextContent("Counter window unknown. No measured window; hit counters carry no window start.");
  expect(within(region).queryByRole("note")).toBeNull();
  expect(region).toHaveTextContent("Raise to: SUPPORTED with hit-counter samples over time.");
});
it("asks the service for the chosen type and grade and starts again from the first page", async () => {
  const { fetchMock } = mount();
  await screen.findByRole("table", { name: "Policy insights" });
  fireEvent.click(screen.getByText(/I10 Zero or old hits: 1/));
  await waitFor(() => expect(fetchMock.mock.calls.some(call => String(call[0]).includes("type=I10") && String(call[0]).includes("page=0"))).toBe(true));
});
it("says a package that is not Check Point is unsupported, not that nothing was found", async () => {
  mount({ list: { vendor: "PAN", supported: false } });
  expect(await screen.findByText(/not Check Point/)).toHaveAttribute("data-insights-supported", "false");
  expect(screen.queryByLabelText("Package counts")).toBeNull();
  expect(screen.queryByRole("table", { name: "Policy insights" })).toBeNull();
});
it("reports a failed read and a malformed answer instead of an empty list", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ unexpected: true }), { status: 200 })));
  render(<ThemeProvider theme={m3Theme}><PolicyInsightsTab policy="p1" openRule={vi.fn()} /></ThemeProvider>);
  expect(await screen.findByRole("alert")).toHaveTextContent("Insights could not be loaded.");
});
it("computes the package sentence and the raise-to words from the service numbers only", () => {
  expect(packageNumbers({ ...counts, rules: 0, decided: 0, upToWithLogs: 0 }).decidedShare).toBeNull();
  expect(packageNumbers(counts)).toMatchObject({ decided: 4, upToWithLogs: 7, notDecidable: 3, decidedShare: 40, upToShare: 70 });
  expect(raiseText({ ...zero, evidenceGrade: "SUPPORTED", raiseTo: { evidenceGrade: "SUPPORTED", insightNeeds: "NONE" } })).toBe("Nothing raises this");
  expect(raiseText({ ...shadow, evidenceGrade: "INDICATED", raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "VENDOR_REPORT" } })).toBe("PROVEN with the management's unused-object report");
});
it("is the second tab of the Judge group and has its own address slug", () => {
  expect(POLICY_TAB_GROUPS.find(g => g.group === "Judge")?.tabs).toEqual(["Hygiene", "Insights"]);
  expect(policyTabFromSlug("insights")).toBe("Insights");
  expect(policyTabFromSlug("hygiene")).toBe("Hygiene");
});

const union: PolicyInsight = { insightId: "33333333-3333-3333-3333-333333333333", insightType: "I3", evidenceGrade: "PROVEN", insightScope: "package",
  rules: [{ ruleId: "r5", number: 5, name: "RULE-ALPHA-05", insightRole: "subject" }, { ruleId: "r1", number: 1, name: "RULE-ALPHA-01", insightRole: "covering" },
    { ruleId: "r4", number: 4, name: "RULE-ALPHA-04", insightRole: "covering" }], objectIds: [],
  raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "NONE" }, insightCaveats: ["INSTALL_STATE_NOT_VERIFIED"],
  evidence: ["Earlier enabled rules together cover this match; at least one has a different action."], counter: { coveringRules: 2, coverAction: "DIFFERENT" } };
const unionPage: PolicyInsightsPage = { ...page, insights: [union], total: 1, byType: { ...page.byType, I3: 1 },
  unionCover: { evaluated: 9, covered: 1, notProven: 0, notEvaluable: [{ unionReason: "rule not resolved", rules: 2 }] } };
it("lists I3 with its covering set, offers it as a type filter and states the union-cover numbers", async () => {
  expect(INSIGHT_TYPES).toContain("I3");
  mount({ list: unionPage });
  const table = await screen.findByRole("table", { name: "Policy insights" });
  const row = within(table).getAllByRole("row")[1];
  expect(row).toHaveTextContent("I3 · Shadowed by several rules together");
  expect(row).toHaveTextContent("#5 RULE-ALPHA-05 · #1 RULE-ALPHA-01 · #4 RULE-ALPHA-04");
  expect(row).toHaveTextContent("the covering set is listed");
  expect(screen.getByLabelText("Insights by type")).toHaveTextContent("I3 Shadowed by several rules together: 1");
  expect(screen.getByLabelText("Union cover analysis")).toHaveTextContent("9 rules checked against the earlier rules of their layer · 1 covered · 0 not proven (work budget) · not evaluated: 2 rule not resolved");
});
it("shows the I3 detail with every covering rule and the action relation", async () => {
  mount({ list: unionPage, detail: { insight: union, rules: [rule("r5", 5, "RULE-ALPHA-05"), rule("r1", 1, "RULE-ALPHA-01"), rule("r4", 4, "RULE-ALPHA-04")] as never, objects: [], days: 90 } });
  fireEvent.click(await screen.findByRole("button", { name: "Open I3 detail" }));
  const region = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(await within(region).findAllByText("Decides first")).toHaveLength(2);
  expect(within(region).getByLabelText("Union cover evidence")).toHaveTextContent(`Covered together by 2 earlier rules of the same layer. ${COVER_ACTION_WORDS.DIFFERENT}`);
  expect(within(region).getByRole("note")).toHaveTextContent(INSTALL_CAVEAT);
});
it("says an I3 whose budget ran out is not proven and claims nothing", async () => {
  const budget: PolicyInsight = { ...union, evidenceGrade: "NOT_EVALUABLE", rules: [union.rules[0]], insightCaveats: [],
    raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "NOT_RESOLVABLE" }, evidence: ["Union cover analysis ran out of its work budget; whether earlier rules together cover this match is not proven."],
    counter: { unionReason: "analysis budget" } };
  mount({ list: { ...unionPage, insights: [budget] }, detail: { insight: budget, rules: [rule("r5", 5, "RULE-ALPHA-05")] as never, objects: [], days: 90 } });
  fireEvent.click(await screen.findByRole("button", { name: "Open I3 detail" }));
  const region = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(await within(region).findByLabelText("Union cover evidence")).toHaveTextContent("Not proven: analysis budget. The rule may or may not be covered; nothing is claimed.");
  expect(within(region).queryByRole("note")).toBeNull();
});

const partialRule: PolicyInsight = { insightId: "44444444-4444-4444-4444-444444444444", insightType: "I4", evidenceGrade: "PROVEN", insightScope: "package",
  rules: [{ ruleId: "r5", number: 5, name: "RULE-ALPHA-05", insightRole: "subject" }, { ruleId: "r1", number: 1, name: "RULE-ALPHA-01", insightRole: "overlapping" },
    { ruleId: "r4", number: 4, name: "RULE-ALPHA-04", insightRole: "overlapping" }], objectIds: [],
  raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "NONE" }, insightCaveats: ["INSTALL_STATE_NOT_VERIFIED"],
  evidence: ["Part of this match is proven to overlap earlier enabled rules of the same layer; every overlapping earlier rule has the same action."],
  counter: { overlappingRules: 12, moreOverlapping: 2 } };
const partialPage: PolicyInsightsPage = { ...page, insights: [partialRule], total: 1, byType: { ...page.byType, I4: 1 },
  partialShadow: { evaluated: 9, partial: 1, differentAction: 3, notProven: 1, notEvaluable: [{ partialReason: "rule not resolved", rules: 2 }] } };
it("lists I4 with its overlapping rules, offers it as a type filter and states the partial-shadow numbers", async () => {
  expect(INSIGHT_TYPES).toContain("I4");
  mount({ list: partialPage });
  const table = await screen.findByRole("table", { name: "Policy insights" });
  const row = within(table).getAllByRole("row")[1];
  expect(row).toHaveTextContent("I4 · Partly shadowed");
  expect(row).toHaveTextContent("#5 RULE-ALPHA-05 · #1 RULE-ALPHA-01 · #4 RULE-ALPHA-04");
  expect(screen.getByLabelText("Insights by type")).toHaveTextContent("I4 Partly shadowed: 1");
  expect(screen.getByLabelText("Partial shadow analysis")).toHaveTextContent(
    "9 rules checked against the earlier rules of their layer · 1 partly shadowed, same action · 3 overlap an earlier rule with another action (counted, no insight) · 1 not proven (work budget) · not evaluated: 2 rule not resolved");
});
it("shows the I4 detail with the overlapping earlier rules, the fixed sentence and the number not listed", async () => {
  mount({ list: partialPage, detail: { insight: partialRule, rules: [rule("r5", 5, "RULE-ALPHA-05"), rule("r1", 1, "RULE-ALPHA-01"), rule("r4", 4, "RULE-ALPHA-04")] as never, objects: [], days: 90 } });
  fireEvent.click(await screen.findByRole("button", { name: "Open I4 detail" }));
  const region = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(await within(region).findAllByText("Overlapping earlier rule")).toHaveLength(2);
  const evidence = within(region).getByLabelText("Partial shadow evidence");
  expect(evidence).toHaveTextContent("Part of this rule's match is decided by earlier rules with the same action; its zero or low counter can be explained by them.");
  expect(evidence).toHaveTextContent("Overlapping earlier rules: 12 (+2 more not listed).");
  expect(within(region).getByRole("note")).toHaveTextContent(INSTALL_CAVEAT);
});
it("filters the Insights list by the I4 type chip", async () => {
  const { fetchMock } = mount({ list: partialPage });
  fireEvent.click(await screen.findByText("I4 Partly shadowed: 1"));
  await waitFor(() => expect(fetchMock.mock.calls.some(call => String(call[0]).includes("type=I4") && String(call[0]).includes("page=0"))).toBe(true));
});
it("says an I4 whose budget ran out is not proven and claims nothing", async () => {
  const budget: PolicyInsight = { ...partialRule, evidenceGrade: "NOT_EVALUABLE", rules: [partialRule.rules[0]], insightCaveats: [],
    raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "NOT_RESOLVABLE" }, evidence: ["Partial overlap analysis ran out of its work budget; whether earlier rules overlap part of this match is not proven."],
    counter: { partialReason: "analysis budget" } };
  mount({ list: { ...partialPage, insights: [budget] }, detail: { insight: budget, rules: [rule("r5", 5, "RULE-ALPHA-05")] as never, objects: [], days: 90 } });
  fireEvent.click(await screen.findByRole("button", { name: "Open I4 detail" }));
  const region = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(await within(region).findByLabelText("Partial shadow evidence")).toHaveTextContent("Not proven: analysis budget. The rule may or may not overlap earlier rules; nothing is claimed.");
  expect(within(region).queryByRole("note")).toBeNull();
});
