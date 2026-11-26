import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { INSIGHT_TYPES, INSTALL_CAVEAT, PolicyInsightsTab } from "../src/screens/PolicyInsightsTab";
import { I5_NOT_PROVEN_SUGGESTION, MERGE_CAVEAT, TYPE_LABEL, TYPE_SUGGESTION, changeListText, coverReasons, gradeWarning,
  insightGradeColor, insightHeadline, insightSuggestion, loadChangeList, suggestedActions } from "../src/screens/policyInsightActions";
import type { PolicyInsight, PolicyInsightCounts, PolicyInsightDetail, PolicyInsightsPage } from "../src/auth/adminApi";

/** Policy Insights I5 (adjacent merge candidates, P2c-2a). Masked pseudonyms only. */
const COMMAND_TEXT = /mgmt_cli|set access-rule|add access-rule|clish|position (top|bottom|above|below)|install-policy|publish/i;

const counts: PolicyInsightCounts = {
  rules: 10, decided: 6, upToWithLogs: 8, logResolvable: 2, collectionResolvable: 0, notResolvable: 2,
  notReadable: 2, waiting: 0, notDecidable: [], waitingRules: [],
  hitsCollected: 10, hitsMissing: 0, disabledRules: 0, outsideSecurityRules: 0,
};
const cell = (...refs: string[]) => ({ refs, negated: false });
const rule = (id: string, number: number, name: string, source: string, comment: string) => ({
  id, uuid: id, number, name, enabled: true, action: "Accept", log: "Log", comment,
  extras: { "install-on": ["gw"] }, source: cell(source), destination: cell("any"), service: cell("https"), application: cell(),
});
const ruleObjects = [
  { id: "any", name: "ANY", type: "any", status: "RESOLVED" },
  { id: "lo", name: "OBJ-NET-01", type: "network", status: "RESOLVED" },
  { id: "hi", name: "OBJ-NET-02", type: "network", status: "RESOLVED" },
  { id: "https", name: "OBJ-SVC-02", type: "service-tcp", status: "RESOLVED" },
  { id: "gw", name: "FW-TANGO-04", type: "simple-gateway", status: "RESOLVED" },
];

const i5Source: PolicyInsight = {
  insightId: "55555555-5555-5555-5555-555555555555",
  insightType: "I5",
  evidenceGrade: "PROVEN",
  insightScope: "package",
  rules: [
    { ruleId: "r1", number: 1, name: "RULE-ALPHA-01", insightRole: "subject" },
    { ruleId: "r2", number: 2, name: "RULE-ALPHA-02", insightRole: "merged" },
  ],
  objectIds: [],
  raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "NONE" },
  insightCaveats: ["INSTALL_STATE_NOT_VERIFIED"],
  evidence: ["Merging these two adjacent rules by taking the union in their one differing cell is proven to change no verdict or log setting: no behaviour change for the verdict and the log setting of stored management intent (profile settings are not collected for Check Point)."],
  counter: { mergeResult: "MERGE_CANDIDATE", differingCell: "source" },
};

const i5Budget: PolicyInsight = {
  ...i5Source,
  insightId: "55555555-5555-5555-5555-555555555556",
  evidenceGrade: "NOT_EVALUABLE",
  raiseTo: { evidenceGrade: "PROVEN", insightNeeds: "NOT_RESOLVABLE" },
  insightCaveats: [],
  evidence: ["Merge analysis ran out of its work budget; whether these two adjacent rules can be merged is not proven."],
  counter: { mergeReason: "analysis budget" },
};

const mergeDetail: PolicyInsightDetail = {
  insight: i5Source,
  rules: [rule("r1", 1, "RULE-ALPHA-01", "lo", "COMMENT-ALPHA"), rule("r2", 2, "RULE-ALPHA-02", "hi", "COMMENT-BRAVO")] as never,
  objects: [],
  ruleObjects,
  days: 90,
  collectedAt: "2026-11-26T08:00:00Z",
};

const mergePage: PolicyInsightsPage = {
  vendor: "CP",
  supported: true,
  insights: [i5Source],
  counts,
  total: 1,
  page: 0,
  pageSize: 200,
  days: 90,
  complete: true,
  collectedAt: "2026-11-26T08:00:00Z",
  byType: { I1: 0, I2: 0, I3: 0, I4: 0, I5: 1, I7: 0, I8: 0, I9: 0, I10: 0, I11: 0, I12: 0 },
  byGrade: { PROVEN: 1, SUPPORTED: 0, INDICATED: 0, NOT_ESTABLISHED: 0, NOT_EVALUABLE: 0 },
  merge: {
    evaluated: 9, candidates: 1, alreadyShadowed: 1, differsInSeveralCells: 3,
    differentEffect: 2, differentInstallOn: 1, anchorBlocked: 1, notProven: 0,
    notEvaluable: [{ mergeReason: "rule not resolved", rules: 2 }],
  },
};

function mount(list: PolicyInsightsPage = mergePage, detail: PolicyInsightDetail = mergeDetail) {
  const fetchMock = vi.fn(async (url: string) => new Response(
    JSON.stringify(String(url).includes("/insight?") ? detail : list), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  render(<ThemeProvider theme={m3Theme}><PolicyInsightsTab policy="p1" policyName="PKG-ALPHA-01" openRule={vi.fn()} /></ThemeProvider>);
  return { fetchMock };
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.localStorage.clear(); });

it("places I5 between I4 and I7 with the exact label, suggestion, filter chip, measurement line and non-green chip", async () => {
  expect(INSIGHT_TYPES.indexOf("I5")).toBe(INSIGHT_TYPES.indexOf("I4") + 1);
  expect(INSIGHT_TYPES.indexOf("I7")).toBe(INSIGHT_TYPES.indexOf("I5") + 1);
  expect(TYPE_LABEL.I5).toBe("Merge candidate (adjacent rules)");
  expect(TYPE_SUGGESTION.I5).toBe("Merge these two adjacent rules into one; the merged rule keeps the first rule's identity. No command is generated.");
  expect(insightGradeColor("I5", "PROVEN")).toBe("info");

  const { fetchMock } = mount();
  const table = await screen.findByRole("table", { name: "Policy insights" });
  const row = within(table).getAllByRole("row")[1];
  expect(row).toHaveTextContent("I5 · Merge candidate (adjacent rules)");
  expect(row).toHaveTextContent("#1 RULE-ALPHA-01 · #2 RULE-ALPHA-02");
  expect(row).toHaveTextContent(TYPE_SUGGESTION.I5);
  expect(row.querySelector(".MuiChip-colorSuccess")).toBeNull();
  expect(screen.getByLabelText("Merge analysis")).toHaveTextContent(
    "I5 merge (adjacent rules): 9 adjacent pairs checked · 1 merge candidates · 1 already shadowed · 3 differ in two or more cells · 2 differ in effect or log setting · 1 differ in install-on · 1 blocked by an anchor (counted, no insight) · 0 not proven (work budget) · not evaluated: 2 rule not resolved");
  expect(screen.getByLabelText("Insights by type")).toHaveTextContent("I5 Merge candidate (adjacent rules): 1");
  fireEvent.click(screen.getByText("I5 Merge candidate (adjacent rules): 1"));
  await waitFor(() => expect(fetchMock.mock.calls.some(call => String(call[0]).includes("type=I5") && String(call[0]).includes("page=0"))).toBe(true));
});

it("shows both adjacent rules side by side with the differing cell highlighted, separate names/comments, profile caveat and Merge rule #b into rule #a", async () => {
  mount();
  fireEvent.click(await screen.findByRole("button", { name: "Open I5 detail" }));
  const dialog = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(await within(dialog).findByLabelText("Suggested actions")).toBeInTheDocument();

  expect(dialog.querySelector("[data-insight-headline]")).toHaveTextContent(
    "Rule #1 (RULE-ALPHA-01) and adjacent rule #2 (RULE-ALPHA-02) match the same packets in every cell except source and can be merged into #1 with no verdict or log setting change (profile settings are not collected for Check Point).");
  expect(dialog).toHaveTextContent(INSTALL_CAVEAT);
  expect(dialog.querySelector(".MuiChip-colorSuccess")).toBeNull();
  expect(within(dialog).getAllByText("PROVEN")[0].closest(".MuiChip-root")).toHaveClass("MuiChip-colorInfo");

  const table = within(dialog).getByRole("table", { name: "Rule comparison" });
  const head = within(table).getAllByRole("columnheader");
  expect(head).toHaveLength(3);
  expect(head[1]).toHaveTextContent("Keeps identity");
  expect(head[1]).toHaveTextContent("#1 · RULE-ALPHA-01");
  expect(head[2]).toHaveTextContent("Merged into first");
  expect(head[2]).toHaveTextContent("#2 · RULE-ALPHA-02");

  expect(dialog.querySelector('td[data-field="source"][data-role="subject"]')).toHaveAttribute("data-cover", "differs");
  expect(dialog.querySelector('td[data-field="source"][data-role="merged"]')).toHaveAttribute("data-cover", "differs");
  for (const field of ["position", "destination", "service", "action", "log", "installOn"]) {
    expect(dialog.querySelector(`td[data-field="${field}"][data-role="subject"]`)).toHaveAttribute("data-cover", "holds");
  }
  expect(dialog.querySelector('td[data-field="comment"][data-role="subject"]')).toHaveTextContent("COMMENT-ALPHA");
  expect(dialog.querySelector('td[data-field="comment"][data-role="merged"]')).toHaveTextContent("COMMENT-BRAVO");

  const evidence = within(dialog).getByLabelText("Merge evidence");
  expect(evidence).toHaveTextContent("Differing cell: Source (the merged cell is the union).");
  expect(evidence).toHaveTextContent("profile settings are not collected for Check Point");
  expect(coverReasons(i5Source, mergeDetail).join(" ")).toContain("Differing cell: Source.");

  const actions = within(dialog).getByLabelText("Suggested actions");
  expect(actions.querySelector('[data-action="MERGE_RULES"] [data-action-effect]')).toHaveTextContent("No behaviour change");
  expect(actions.querySelector('[data-action="MERGE_RULES"] [data-action-caveat]')).toHaveTextContent(MERGE_CAVEAT);
  expect(MERGE_CAVEAT).toContain("profile settings are not collected for Check Point");

  fireEvent.click(within(actions).getByRole("button", { name: "Merge adjacent rules" }));
  const item = loadChangeList("p1", false)[0];
  expect(item).toMatchObject({
    insightType: "I5",
    kind: "MERGE_RULES",
    effect: "No behaviour change",
    plan: "Merge rule #2 (RULE-ALPHA-02) into rule #1 (RULE-ALPHA-01).",
    caveat: MERGE_CAVEAT,
  });
  expect(`${item.plan} ${item.caveat}`).not.toMatch(COMMAND_TEXT);
  expect(changeListText([item], { masked: true, exportedAt: new Date("2026-11-26T08:00:00Z") })).not.toMatch(COMMAND_TEXT);
});

it("says an I5 whose budget ran out is not proven, claims nothing and never labels it no behaviour change", async () => {
  expect(insightSuggestion(i5Budget)).toBe(I5_NOT_PROVEN_SUGGESTION);
  expect(insightHeadline(i5Budget)).toBe(
    "Merging rule #2 (RULE-ALPHA-02) into rule #1 (RULE-ALPHA-01) could not be checked (analysis budget): whether they can be merged is UNKNOWN.");
  mount({ ...mergePage, insights: [i5Budget] }, { ...mergeDetail, insight: i5Budget });
  fireEvent.click(await screen.findByRole("button", { name: "Open I5 detail" }));
  const dialog = await screen.findByRole("dialog", { name: "Insight detail" });
  expect(await within(dialog).findByLabelText("Merge evidence")).toHaveTextContent(
    "Not proven: analysis budget. Whether these two adjacent rules can be merged is not proven; nothing is claimed.");
  expect(suggestedActions(i5Budget)[0]).toMatchObject({
    kind: "MERGE_RULES",
    effect: "Behaviour may change",
    warning: gradeWarning("NOT_EVALUABLE"),
  });
});
