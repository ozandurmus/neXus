import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { App, screenFromSearch } from "../src/App";
import { PolicyOfflineTab, ruleRefusalText } from "../src/screens/PolicyOfflineTab";
import { NOT_SEEN_WORDING, RuleUsageResult, ruleUsageCsv } from "../src/screens/RuleUsageResult";
import { RuleUsageScreen, ruleUsageModeFromUrl } from "../src/screens/RuleUsageScreen";
import { activeLeafId, groupOfLeaf } from "../src/shell/navModel";
import { deleteOfflineAnalysis, getOfflineAnalysis, getPolicy, listOfflineAnalyses, listPolicies, uploadOfflineAnalysis,
  type OfflineAnalysisResult, type RuleObjectUsage } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (original) => ({
  ...(await original<typeof import("../src/auth/adminApi")>()),
  deleteOfflineAnalysis: vi.fn(), getOfflineAnalysis: vi.fn(), listOfflineAnalyses: vi.fn(), uploadOfflineAnalysis: vi.fn(), getPolicy: vi.fn(), listPolicies: vi.fn(),
}));

/** Rule object usage (RULE_OBJECT_USAGE_CONTRACT.md, delivery step 1). Synthetic values only. */
const usage: RuleObjectUsage[] = [
  { dimension: "source", objectId: "g1", name: "GROUP-ALPHA", parentId: null, depth: 0, state: "USED", flowCount: 40, reason: null },
  { dimension: "source", objectId: "o1", name: "HOST-ALPHA", parentId: "g1", depth: 1, state: "USED", flowCount: 40, reason: null },
  { dimension: "source", objectId: "o2", name: "HOST-BRAVO", parentId: "g1", depth: 1, state: "NOT_SEEN", flowCount: 0, reason: null },
  { dimension: "source", objectId: "g2", name: "GROUP-BRAVO", parentId: null, depth: 0, state: "UNKNOWN", flowCount: 0, reason: "unresolved object" },
  { dimension: "destination", objectId: "o3", name: "NET-CHARLIE", parentId: null, depth: 0, state: "USED", flowCount: 40, reason: null },
  { dimension: "service", objectId: "s1", name: "any", parentId: null, depth: 0, state: "NOT_AUDITABLE", flowCount: 0, reason: "any" },
  { dimension: "service", objectId: "s2", name: "SVC-DELTA", parentId: null, depth: 0, state: "NOT_SEEN", flowCount: 0, reason: null },
];
const oneRule: OfflineAnalysisResult = {
  id: "a2", policyId: "p1", fileName: "FILE-BRAVO", uploader: "ACTOR-ALPHA", createdAt: "2026-11-13T09:00:00Z", periodStart: "2026-11-01T00:00:00Z", periodEnd: "2026-11-08T00:00:00Z",
  mode: "ONE_RULE", ruleId: "r1", otherRuleRows: 800, rowCount: 1200, invalidRows: 10, flowCount: 1190, attributedFlows: 40,
  agreementCount: 0, disagreementCount: 0, comparisonUnknownCount: 0, comparisonEnabled: false, comparisonNote: "", attributionNote: "Sample flows only.", limitations: "Policy intent only.",
  caution: "Verify before applying; sample may be incomplete.", parseStats: [{ statReason: "invalid IP address", count: 10 }], unknownStats: [{ statReason: "protocol unsupported", count: 50 }], verdictStats: [],
  rules: [{ ruleId: "r1", name: "RULE-ALPHA", number: 12, action: "allow", sectionName: "SECTION-ALPHA", flowCount: 40, firstTime: "", lastTime: "",
    distinctSources: 1, distinctDestinations: 1, distinctServices: 1, topObserved: [{ observedSource: "192.0.2.8", count: 40 }], zeroTraffic: "", severity: "LOW", memberStatus: "RESOLVED", unseenMembers: [],
    narrowing: { memberStatus: "RESOLVED", observedSource: ["192.0.2.8/32"], observedDestination: ["198.51.100.8/32"], observedService: ["tcp/443"], coveragePercent: 100, coveredFlows: 40, caution: "Verify before applying; sample may be incomplete." },
    objectUsage: usage }],
};
const wholePolicy: OfflineAnalysisResult = { ...oneRule, id: "a1", fileName: "FILE-ALPHA", mode: undefined, ruleId: undefined, otherRuleRows: undefined,
  rules: [{ ...oneRule.rules[0], objectUsage: undefined }] };
const policyPage = { metadata: {}, objects: [], page: 0, pageSize: 50, total: 2, sections: [{ id: "s1", name: "SECTION-ALPHA", source: "", parentRuleId: null, total: 2, rules: [
  { id: "r1", uuid: "u1", number: 12, name: "RULE-ALPHA" }, { id: "r2", uuid: "u2", number: 13, name: "RULE-BRAVO" }] }] };

beforeEach(() => {
  vi.mocked(listPolicies).mockResolvedValue({ policies: [{ id: "p1", name: "POLICY-ALPHA" }], devices: [] } as never);
  vi.mocked(listOfflineAnalyses).mockResolvedValue({ analyses: [oneRule, wholePolicy], canManage: true });
  vi.mocked(getOfflineAnalysis).mockImplementation(async (id) => (id === "a2" ? oneRule : wholePolicy));
  vi.mocked(uploadOfflineAnalysis).mockResolvedValue({ id: "a2" });
  vi.mocked(deleteOfflineAnalysis).mockResolvedValue({ deleted: true });
  vi.mocked(getPolicy).mockResolvedValue(policyPage as never);
});
afterEach(() => { vi.clearAllMocks(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

function csvFile() {
  const text = "src,dst,dstport,proto\n192.0.2.8,198.51.100.8,443,tcp\n";
  const f = new File([text], "synthetic.csv", { type: "text/csv" });
  vi.spyOn(f, "slice").mockReturnValue({ size: f.size, text: async () => text } as Blob); return f;
}
async function prepareUpload() {
  fireEvent.change(await screen.findByLabelText("SIEM CSV file"), { target: { files: [csvFile()] } });
  await waitFor(() => expect(screen.getByLabelText(/I confirm the mapping/)).toBeEnabled());
  fireEvent.click(screen.getByLabelText(/I confirm the mapping/));
}
async function openStored(name: RegExp) {
  fireEvent.mouseDown(await screen.findByLabelText("Stored analysis")); fireEvent.click(await screen.findByRole("option", { name }));
}
const rowOf = (name: string) => screen.getByText(name).closest("tr")!;

describe("Rule object usage: address and menu", () => {
  it.each([
    ["?screen=offline_analysis", "screen=rule_usage&mode=policy"],
    ["?screen=offline_analysis&policy_id=p1", "screen=rule_usage&policy_id=p1&mode=policy"],
    ["?screen=policy&tab=offline&policy_id=p1", "screen=rule_usage&policy_id=p1&mode=policy"],
    ["?screen=policy&tab=offline-analysis&policy_id=p1", "screen=rule_usage&policy_id=p1&mode=policy"],
  ])("%s lands on the screen with its parameters kept", (old, home) => {
    expect(screenFromSearch(old)).toEqual({ screen: "rule_usage", preview: false });
    expect(window.location.search).toBe(`?${home}`);
    expect(activeLeafId("rule_usage", old)).toBe("rule-usage");
  });

  it("lights Rule object usage, in Policy and changes, and nothing in Tools", () => {
    expect(activeLeafId("rule_usage", "?screen=rule_usage&mode=policy")).toBe("rule-usage");
    expect(groupOfLeaf("rule-usage")!.label).toBe("Policy and changes");
    render(<App search="?screen=offline_analysis" />);
    const lit = within(screen.getByRole("navigation", { name: "Primary" })).getAllByRole("link").filter((a) => a.getAttribute("aria-current") === "page");
    expect(lit.map((a) => a.textContent)).toEqual(["Rule object usage"]);
    expect(screen.queryByRole("link", { name: "Offline analysis" })).toBeNull();
  });
});

describe("Rule object usage: mode switch", () => {
  it("opens on One rule, and Whole policy is the other mode, written to the address", async () => {
    expect(ruleUsageModeFromUrl(null)).toBe("rule"); expect(ruleUsageModeFromUrl("policy")).toBe("policy"); expect(ruleUsageModeFromUrl("nonsense")).toBe("rule");
    window.history.replaceState(null, "", "/?screen=rule_usage&policy_id=p1");
    render(<RuleUsageScreen />);
    expect(screen.getByRole("heading", { name: "Rule object usage" })).toBeInTheDocument();
    const modes = await screen.findByRole("group", { name: "Analysis mode" });
    expect(within(modes).getByRole("button", { name: "One rule" })).toHaveAttribute("aria-pressed", "true");
    expect(await screen.findByRole("combobox", { name: /Rule/ })).toBeInTheDocument();
    fireEvent.click(within(modes).getByRole("button", { name: "Whole policy" }));
    expect(within(modes).getByRole("button", { name: "Whole policy" })).toHaveAttribute("aria-pressed", "true");
    expect(window.location.search).toContain("mode=policy");
    expect(screen.queryByRole("combobox", { name: /Rule/ })).toBeNull();
    expect(screen.getByRole("region", { name: "Whole policy analysis" })).toBeInTheDocument();
  });

  it("an old address opens Whole policy, and a whole-policy upload sends the columns alone", async () => {
    window.history.replaceState(null, "", "/?screen=rule_usage&mode=policy&policy_id=p1");
    render(<RuleUsageScreen />);
    await prepareUpload();
    fireEvent.click(screen.getByRole("button", { name: "Upload and analyze" }));
    await waitFor(() => expect(uploadOfflineAnalysis).toHaveBeenCalledWith("p1", expect.any(File), { source: "src", destination: "dst", port: "dstport", protocol: "proto" }));
    expect(getPolicy).not.toHaveBeenCalled();
  });
});

describe("Rule object usage: the rule of a One rule upload", () => {
  it("needs a rule, lists the snapshot's rules by number, name and section, and sends the picked rule as ruleId in the mapping", async () => {
    render(<PolicyOfflineTab policy="p1" mode="rule" openRule={vi.fn()} />);
    await prepareUpload();
    expect(screen.getByRole("button", { name: "Upload and analyze" })).toBeDisabled();
    const picker = screen.getByRole("combobox", { name: /Rule/ });
    fireEvent.change(picker, { target: { value: "ALPHA" } });
    await waitFor(() => expect(getPolicy).toHaveBeenLastCalledWith("p1", 0, "ALPHA"));
    const option = await screen.findByRole("option", { name: /12 · RULE-ALPHA/ });
    expect(option).toHaveTextContent("Section SECTION-ALPHA");
    fireEvent.click(option);
    expect(document.querySelector("[data-rule-choice]")).toHaveAttribute("data-rule-choice", "picked");
    expect(document.querySelector("[data-rule-choice]")).toHaveTextContent("Chosen: rule 12 · RULE-ALPHA · section SECTION-ALPHA.");
    fireEvent.click(screen.getByRole("button", { name: "Upload and analyze" }));
    await waitFor(() => expect(uploadOfflineAnalysis).toHaveBeenCalledWith("p1", expect.any(File), { source: "src", destination: "dst", port: "dstport", protocol: "proto", ruleId: "r1" }));
    expect(await screen.findByRole("region", { name: "Rule object usage result" })).toBeInTheDocument();
  });

  it("sends a pasted rule UID / id as it was typed, trimmed and nothing else", async () => {
    render(<PolicyOfflineTab policy="p1" mode="rule" openRule={vi.fn()} />);
    await prepareUpload();
    fireEvent.change(screen.getByRole("combobox", { name: /Rule/ }), { target: { value: "  UID-0007  " } });
    expect(document.querySelector("[data-rule-choice]")).toHaveAttribute("data-rule-choice", "pasted");
    expect(document.querySelector("[data-rule-choice]")).toHaveTextContent("must match exactly one rule");
    fireEvent.click(screen.getByRole("button", { name: "Upload and analyze" }));
    await waitFor(() => expect(uploadOfflineAnalysis).toHaveBeenCalledWith("p1", expect.any(File), expect.objectContaining({ ruleId: "UID-0007" })));
  });

  it.each([
    ["RULE_NOT_FOUND", "The rule was not found in this policy snapshot."],
    ["RULE_AMBIGUOUS: 2 rules", "matches more than one rule of this snapshot"],
  ])("says %s in plain words and shows no result", async (code, words) => {
    vi.mocked(uploadOfflineAnalysis).mockRejectedValue({ status: 400, body: { error: code } });
    render(<PolicyOfflineTab policy="p1" mode="rule" openRule={vi.fn()} />);
    await prepareUpload();
    fireEvent.change(screen.getByRole("combobox", { name: /Rule/ }), { target: { value: "UID-0007" } });
    fireEvent.click(screen.getByRole("button", { name: "Upload and analyze" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(words); expect(alert).toHaveTextContent("Nothing was analysed."); expect(alert).not.toHaveTextContent("Upload rejected");
    expect(screen.queryByRole("region", { name: "Rule object usage result" })).toBeNull();
    expect(ruleRefusalText("CSV_ROW_LIMIT: maximum 2000000 rows")).toBeNull(); expect(ruleRefusalText(undefined)).toBeNull();
  });

  it("gives a role that cannot upload no rule picker, only the stored results", async () => {
    vi.mocked(listOfflineAnalyses).mockResolvedValue({ analyses: [oneRule], canManage: false });
    render(<PolicyOfflineTab policy="p1" mode="rule" openRule={vi.fn()} />);
    expect(await screen.findByRole("group", { name: "SIEM CSV upload" })).toHaveTextContent("Role: not permitted");
    expect(screen.queryByRole("combobox", { name: /Rule/ })).toBeNull();
    await openStored(/FILE-BRAVO/);
    const result = await screen.findByRole("region", { name: "Rule object usage result" });
    expect(within(result).queryByRole("button", { name: "Delete stored analysis" })).toBeNull();
    expect(within(result).getByRole("button", { name: "Export object list CSV" })).toBeEnabled();
  });
});

describe("Rule object usage: the one-rule result", () => {
  const show = (result = oneRule, openRule = vi.fn()) => render(<RuleUsageResult result={result} openRule={openRule} onExport={vi.fn()} />);

  it("names the rule, the period and what the file held", () => {
    const openRule = vi.fn(); show(oneRule, openRule);
    expect(screen.getByRole("heading", { name: "Rule 12 · RULE-ALPHA" })).toBeInTheDocument();
    expect(document.querySelector('[data-fact="rule-header"]')).toHaveTextContent("Section SECTION-ALPHA · Action allow");
    expect(document.querySelector('[data-fact="period"]')).toHaveTextContent("2026-11-01T00:00:00Z – 2026-11-08T00:00:00Z");
    const fact = (name: string) => document.querySelector(`[data-fact="${name}"] dd`)!.textContent;
    expect([fact("rows-read"), fact("attributed"), fact("set-aside"), fact("unknown"), fact("invalid")]).toEqual(["1,200", "40", "800", "50", "10"]);
    expect(screen.getByText("protocol unsupported: 50")).toBeInTheDocument(); expect(screen.getByText("invalid IP address: 10")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Open rule in Policy" })); expect(openRule).toHaveBeenCalledWith("r1");
  });

  it("gives every object one state with its flow count, and NOT_AUDITABLE and UNKNOWN always their reason", () => {
    show();
    expect(within(screen.getByRole("table", { name: "Source objects" })).getAllByRole("row")).toHaveLength(5);
    expect(rowOf("HOST-ALPHA")).toHaveAttribute("data-state", "USED"); expect(rowOf("HOST-ALPHA")).toHaveTextContent("Used"); expect(rowOf("HOST-ALPHA")).toHaveTextContent("40");
    expect(rowOf("HOST-BRAVO")).toHaveAttribute("data-state", "NOT_SEEN"); expect(rowOf("HOST-BRAVO")).toHaveTextContent("Not seen");
    expect(rowOf("GROUP-BRAVO")).toHaveTextContent("UNKNOWN"); expect(rowOf("GROUP-BRAVO")).toHaveTextContent("unresolved object"); expect(rowOf("GROUP-BRAVO")).not.toHaveTextContent("Not seen");
    const anyRow = document.querySelector<HTMLElement>('tr[data-object="s1"]')!;
    expect(anyRow).toHaveTextContent("Not auditable"); expect(within(anyRow).getAllByRole("cell")[3]).toHaveTextContent("any");
    // a reason the server left out is said, never left blank
    const { unmount } = show({ ...oneRule, rules: [{ ...oneRule.rules[0], objectUsage: [{ ...usage[3], name: "GROUP-ECHO", reason: null }] }] });
    expect(rowOf("GROUP-ECHO")).toHaveTextContent("No reason was given by the server.");
    unmount();
  });

  it("summarises the not seen members per dimension, with today's narrowing suggestion", () => {
    show();
    const summary = screen.getByRole("region", { name: "Tightening summary" });
    expect(within(summary).getByText(/Not seen in the uploaded period: source 1 · destination 0 · service 1\./)).toBeInTheDocument();
    expect(document.querySelector('[data-fact="not-judged"]')).toHaveTextContent("2 objects could not be judged");
    expect(within(summary).getByRole("table", { name: "Narrowing for rule 12" })).toHaveTextContent("192.0.2.8/32");
    expect(screen.getByText(/Source · 4 objects · 1 not seen/)).toBeInTheDocument();
  });

  it("filters to Not seen only, keeping the group that holds an unseen member, and back to All", () => {
    show();
    const filter = screen.getByRole("group", { name: "Objects shown" });
    expect(within(filter).getByRole("button", { name: "All" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(within(filter).getByRole("button", { name: "Not seen only" }));
    expect(screen.queryByText("HOST-ALPHA")).toBeNull(); expect(screen.queryByText("GROUP-BRAVO")).toBeNull(); expect(document.querySelector('tr[data-object="s1"]')).toBeNull();
    expect(screen.getByText("HOST-BRAVO")).toBeInTheDocument(); expect(screen.getByText("GROUP-ALPHA")).toBeInTheDocument(); expect(screen.getByText("SVC-DELTA")).toBeInTheDocument();
    expect(screen.getByText("No destination object is marked not seen.")).toBeInTheDocument();
    fireEvent.click(within(filter).getByRole("button", { name: "All" }));
    expect(screen.getByText("HOST-ALPHA")).toBeInTheDocument();
  });

  it("collapses and expands a group", () => {
    show();
    fireEvent.click(screen.getByRole("button", { name: "Collapse GROUP-ALPHA" }));
    expect(screen.queryByText("HOST-ALPHA")).toBeNull(); expect(screen.queryByText("HOST-BRAVO")).toBeNull(); expect(screen.getByText("GROUP-BRAVO")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Expand GROUP-ALPHA" }));
    expect(screen.getByText("HOST-BRAVO")).toBeInTheDocument();
  });

  it("carries the fixed wording on every result, whatever it holds", () => {
    expect(NOT_SEEN_WORDING).toBe("Not seen in the uploaded period. This is not proof that the object is unused: the export covers only what was logged in that period.");
    const variants: OfflineAnalysisResult[] = [oneRule, { ...oneRule, rules: [{ ...oneRule.rules[0], objectUsage: [] }] },
      { ...oneRule, rules: [{ ...oneRule.rules[0], objectUsage: usage.filter((u) => u.state === "USED"), narrowing: {} }] }];
    for (const variant of variants) {
      const { unmount } = show(variant);
      expect(document.querySelector('[data-wording="not-seen"]')).toHaveTextContent(NOT_SEEN_WORDING);
      unmount();
    }
    show(variants[2]);
    expect(screen.getByText("No object of this rule is marked not seen in the uploaded period.")).toBeInTheDocument();
  });

  it("says so when a stored one-rule analysis holds no rule entry, and never invents one", () => {
    show({ ...oneRule, rules: [] });
    expect(screen.getByRole("alert")).toHaveTextContent("holds no result for it");
  });

  it("exports the object list from the aggregates alone, formula-safe, the wording on each not seen row", () => {
    const csv = ruleUsageCsv({ ...oneRule, rules: [{ ...oneRule.rules[0], objectUsage: [...usage, { ...usage[1], name: "=synthetic-formula" }] }] });
    const lines = csv.split("\r\n");
    expect(lines[0]).toBe('"Rule number","Rule name","Period start","Period end","Dimension","Object","Object id","Parent id","Depth","State","Flows","Reason","Note"');
    expect(lines).toHaveLength(9);
    expect(lines[3]).toBe(`"12","RULE-ALPHA","2026-11-01T00:00:00Z","2026-11-08T00:00:00Z","source","HOST-BRAVO","o2","g1","1","NOT_SEEN","0","","${NOT_SEEN_WORDING}"`);
    expect(lines[4]).toContain('"UNKNOWN","","unresolved object",""');
    expect(csv).toContain("'=synthetic-formula");
    // nothing but the object list: no observed value of the sample leaves in this file
    expect(csv).not.toContain("192.0.2.8");
  });
});

describe("Rule object usage: stored analyses of both modes", () => {
  it("shows each stored analysis in the view of its own mode, and says so when the switch is on the other", async () => {
    render(<PolicyOfflineTab policy="p1" mode="rule" openRule={vi.fn()} />);
    await openStored(/FILE-ALPHA/);
    expect(await screen.findByText("Sample summary")).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("This stored analysis is a Whole policy analysis");
    expect(screen.queryByRole("region", { name: "Rule object usage result" })).toBeNull();
    await openStored(/FILE-BRAVO.*One rule/);
    expect(await screen.findByRole("region", { name: "Rule object usage result" })).toBeInTheDocument();
    expect(screen.queryByText("Sample summary")).toBeNull(); expect(screen.queryByRole("note")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Delete stored analysis" }));
    await waitFor(() => expect(deleteOfflineAnalysis).toHaveBeenCalledWith("a2"));
  });
});
