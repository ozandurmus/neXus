import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyHygieneTab, hygieneCoverage, hygieneReasonBreakdown } from "../src/screens/PolicyHygieneTab";
import { RuleMetrics } from "../src/screens/PolicyRuleViewer";
import type { PolicyHygieneAssessment, PolicyHygienePage, PolicyRuleDetail } from "../src/auth/adminApi";
const hygiene: PolicyHygieneAssessment = { shadowStatus: "CONFLICT", shadowReason: null, coveringRuleId: "r1",
  findings: [{ findingClass: "conflict", severity: "HIGH", evidence: "Earlier enabled rule fully covers this match with a different action." }],
  permissiveness: { level: "Medium", score: 2, reasons: ["Any destination"] }, timeStatus: "always", expiring: false,
  counterWindow: "Counter window unknown; first hit is not a counter start.", hitSource: "mds", hitsCollectedAt: "2026-10-09T12:00:00Z" };
const page: PolicyHygienePage = { rows: [{ ruleId: "r2", number: 2, name: "RULE-BRAVO-02", sectionId: "s1", findings: hygiene.findings, hygiene }],
  counts: { shadowed: 0, conflict: 1, disabled: 0, unused: 0, expired: 0, broad: 0, unknown: 0 }, total: 1, page: 0, pageSize: 200, budgetReached: false, days: 90 };
const cell = { refs: ["a1"], negated: false };
const detail: PolicyRuleDetail = { metadata: { id: "p1", name: "POL-ALPHA-01", sourceId: "src1", sourceName: "MGR-BRAVO-01", vendor: "PAN", containerId: "c1", containerName: "DOM-TANGO-01", collectedAt: "2026-10-09T12:00:00Z", artefactRef: "a1", targets: [] },
  section: { id: "s1", name: "Pre rules", source: "Shared", parentRuleId: null, rules: [], total: 2 }, objects: [{ id: "a1", name: "ADDR-ALPHA-01", type: "address", status: "RESOLVED" }],
  rule: { id: "r2", uuid: "uuid2", number: 2, name: "RULE-BRAVO-02", enabled: true, source: cell, destination: cell, service: cell, application: cell, action: "allow", log: "Log", comment: "", extras: {}, hygiene, permissiveness: hygiene.permissiveness } };
function mount(node: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>); }
function response(value: unknown) { return new Response(JSON.stringify(value), { status: 200 }); }
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("loads counts, filters and threshold, links rules and compares covering vs shadowed", async () => {
  const fetcher = vi.fn(async (url: string) => url.includes("/rules?") ? response({ ...detail, rule: { ...detail.rule, id: url.includes("ruleId=r1&") ? "r1" : "r2", name: url.includes("ruleId=r1&") ? "RULE-ALPHA-01" : "RULE-BRAVO-02" } }) : response(page));
  vi.stubGlobal("fetch", fetcher); const openRule = vi.fn();
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={openRule} />);
  expect(screen.getByText("Loading hygiene…")).toBeInTheDocument();
  fireEvent.click(await screen.findByRole("button", { name: "2 · RULE-BRAVO-02" }));
  expect(openRule).toHaveBeenCalledWith("r2", 90);
  expect(screen.getByText("conflict: 1")).toBeInTheDocument();
  fireEvent.click(screen.getByText("Compare rules"));
  expect(await screen.findByText("Covering rule")).toBeInTheDocument(); expect(screen.getByText("Shadowed rule")).toBeInTheDocument();
  const comparison = screen.getByLabelText("Shadow comparison");
  expect(within(comparison).getByText("RULE-ALPHA-01")).toBeInTheDocument();
  fireEvent.click(screen.getByText("conflict: 1"));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("findingClass=conflict"), expect.anything()));
  fireEvent.change(screen.getByLabelText("Unused threshold (days)"), { target: { value: "30" } });
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("days=30"), expect.anything()));
});
it("renders explicit unknown, budget, pagination and empty states", async () => {
  const fetcher = vi.fn(async (url: string) => response(url.includes("page=1") ? { ...page, rows: [], total: 0, page: 1 } : {
    ...page, total: 201, budgetReached: true, rows: [{ ...page.rows[0], hygiene: { ...hygiene, coveringRuleId: null, shadowStatus: "UNKNOWN", shadowReason: "analysis budget" },
      findings: [{ findingClass: "unknown", severity: "LOW", evidence: "Hit counts were not collected or are invalid." }] }] }));
  vi.stubGlobal("fetch", fetcher); mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} />);
  expect(await screen.findByText("UNKNOWN because analysis budget")).toBeInTheDocument();
  expect(screen.getByText(/UNKNOWN because Hit counts/)).toBeInTheDocument();
  // The budget is an explicit, visible state of the whole analysis, not a sentence under the chips.
  const coverage = screen.getByRole("region", { name: "Analysis coverage" });
  expect(coverage).toHaveAttribute("data-budget-reached", "true");
  expect(within(coverage).getByText("Analysis limit reached", { selector: ".MuiChip-label" })).toBeInTheDocument();
  expect(within(coverage).getByRole("status")).toHaveTextContent("a limit of the analysis, not a finding");
  fireEvent.click(screen.getByRole("button", { name: "Next hygiene page" }));
  expect(await screen.findByText("No hygiene findings match these filters.")).toBeInTheDocument();
});
it("splits coverage into the shadow question and the hit-counter question, policy-wide, never derived from a page or the class chips", () => {
  // Every shadow result decided, no hit counters stored: all rules proven on the first line, none stored on the second -- never "0 proven".
  const noCounters = hygieneCoverage({ rules: 871, shadowProven: 871, shadowUnknown: 0, hitsCollected: 0, hitsMissing: 871 });
  expect(noCounters.sentence).toBe("Shadow analysis: 871 proven · 0 not provable (of 871 rules)");
  expect(noCounters.hitsSentence).toBe("Hit counters: 0 stored · 871 not collected");
  expect(noCounters.shadow).toEqual({ proven: 871, unknown: 0, rules: 871 }); expect(noCounters.hits).toEqual({ collected: 0, missing: 871, rules: 871 });
  const mixed = hygieneCoverage({ rules: 105710, shadowProven: 3049, shadowUnknown: 102661, hitsCollected: 71200, hitsMissing: 34510 });
  expect(mixed.sentence).toBe("Shadow analysis: 3,049 proven · 102,661 not provable (of 105,710 rules)");
  expect(mixed.hitsSentence).toBe("Hit counters: 71,200 stored · 34,510 not collected");
  expect(mixed.basis).toContain("separate question");
  // No coverage from the service (older build), or a malformed one: UNKNOWN, not a number computed from the chips.
  for (const absent of [undefined, { rules: 871, shadowProven: -1, shadowUnknown: 0, hitsCollected: 0, hitsMissing: 0 }, { rules: 871 } as never]) {
    const coverage = hygieneCoverage(absent);
    expect(coverage).toMatchObject({ shadow: null, hits: null, sentence: "Shadow analysis: coverage UNKNOWN (not returned by the service)", hitsSentence: "Hit counters: coverage UNKNOWN" });
  }
});
it("groups the not-provable reasons of the rows it sees in plain words, counting each rule once per group", () => {
  const row = (ruleId: string, shadowReason: string | null, shadowStatus: PolicyHygieneAssessment["shadowStatus"] = "UNKNOWN", unknownEvidence?: string) => ({
    ...page.rows[0], ruleId, hygiene: { ...hygiene, coveringRuleId: null, shadowStatus, shadowReason },
    findings: unknownEvidence ? [{ findingClass: "unknown", severity: "LOW" as const, evidence: unknownEvidence }] : [] });
  const breakdown = hygieneReasonBreakdown([
    row("r1", "user or category constraint"), row("r2", "access-role object unresolved", "UNKNOWN", "user objects not resolvable"),
    row("r3", "unresolved install-on selector"), row("r4", "analysis budget", "UNKNOWN", "Hit counts were not collected or are invalid."),
    row("r5", null, "NOT_SHADOWED"), row("r6", "zone or install-on coverage is unproven"),
  ]);
  expect(breakdown.map(entry => [entry.group.key, entry.rules])).toEqual([["identity", 2], ["install-on", 1], ["budget", 1], ["hits", 1], ["zone", 1]]);
  expect(breakdown[0].reasons).toEqual(["user or category constraint", "access-role object unresolved", "user objects not resolvable"]);
  expect(hygieneReasonBreakdown([row("r5", null, "NOT_SHADOWED")])).toEqual([]);
  // The policy-wide counts win over the page: one entry per evidence string, the `Shadowing:` wrapper removed, rules summed per group.
  const policyWide = hygieneReasonBreakdown([row("r1", "user or category constraint")], [
    { evidence: "Shadowing: earlier rule has unsupported constraints.", rules: 30680 }, { evidence: "Shadowing: unresolved object.", rules: 14921 },
    { evidence: "Hit counts were not collected or are invalid.", rules: 34510 }, { evidence: "Hit counts are missing for a target.", rules: 12 },
    { evidence: "Shadowing: zone or install-on coverage is unproven.", rules: 14844 }, { evidence: 7, rules: "x" } as never,
  ]);
  expect(policyWide.map(entry => [entry.group.key, entry.rules])).toEqual([["hits", 34522], ["earlier", 30680], ["objects", 14921], ["zone", 14844]]);
  expect(policyWide[1].group.label).toBe("Earlier rule in this layer not provable");
  expect(policyWide[1].reasons).toEqual(["earlier rule has unsupported constraints"]);
  expect(policyWide[0].reasons).toEqual(["Hit counts were not collected or are invalid", "Hit counts are missing for a target"]);
  expect(hygieneReasonBreakdown([row("r1", "user or category constraint")], [])).toEqual([]);
});
it("shows analysed coverage, the plain-words reason breakdown and what it covers instead of a bare unknown count", async () => {
  const rows = [
    { ...page.rows[0], ruleId: "r1", number: 1, name: "RULE-ALPHA-01", hygiene: { ...hygiene, coveringRuleId: null, shadowStatus: "UNKNOWN" as const, shadowReason: "user or category constraint" }, findings: [] },
    { ...page.rows[0], ruleId: "r2", number: 2, name: "RULE-BRAVO-02", hygiene: { ...hygiene, coveringRuleId: null, shadowStatus: "UNKNOWN" as const, shadowReason: "unresolved install-on selector" }, findings: [] },
    { ...page.rows[0], ruleId: "r3", number: 3, name: "RULE-CHARLIE-03", hygiene: { ...hygiene, coveringRuleId: null, shadowStatus: "NOT_SHADOWED" as const, shadowReason: null }, findings: [] },
  ];
  vi.stubGlobal("fetch", vi.fn(async () => response({ ...page, rows, total: 3, counts: { ...page.counts, unknown: 845 },
    coverage: { rules: 871, shadowProven: 26, shadowUnknown: 845, hitsCollected: 871, hitsMissing: 0 },
    unknownReasons: [{ evidence: "Shadowing: user or category constraint.", rules: 800 }, { evidence: "Shadowing: unresolved install-on selector.", rules: 45 }] })));
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} />);
  const coverage = await screen.findByRole("region", { name: "Analysis coverage" });
  expect(within(coverage).getByText("Shadow analysis: 26 proven · 845 not provable (of 871 rules)")).toBeInTheDocument();
  expect(within(coverage).getByText("Hit counters: 871 stored · 0 not collected")).toHaveAttribute("data-hits-coverage", "true");
  expect(coverage).not.toHaveAttribute("data-budget-reached");
  // The reasons are the whole policy's, not the three rows on this page.
  expect(within(coverage).getByText(/^Why not provable · across the whole policy/)).toBeInTheDocument();
  const reasons = within(coverage).getByRole("list", { name: "Not provable reasons" });
  const items = within(reasons).getAllByRole("listitem");
  expect(items.map(item => item.getAttribute("data-reason-group"))).toEqual(["identity", "install-on"]);
  expect(items[0]).toHaveTextContent(/^800Identity or dynamic objects/); expect(items[0]).toHaveTextContent("not a rule problem");
  expect(items[1]).toHaveTextContent(/^45Install-on/); expect(items[1]).toHaveAttribute("title", "unresolved install-on selector");
  // The class chip and the row say "not provable" in words beside the UNKNOWN, with the group under the raw reason.
  expect(screen.getByText("not provable (UNKNOWN): 845")).toBeInTheDocument();
  expect(screen.getByText("Identity or dynamic objects", { selector: "[data-reason-group]" })).toBeInTheDocument();
  expect(screen.getByText("Not shadowed (proven)")).toBeInTheDocument();
  expect(screen.getAllByText(/Collected: 2026-10-09 15:00 GMT\+3$/).length).toBe(3);
});
it("says the coverage is UNKNOWN when the service did not return it, and tells where the reasons are when none is on the page", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => response({ ...page, total: 250, counts: { ...page.counts, unknown: 12 } })));
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} />);
  const coverage = await screen.findByRole("region", { name: "Analysis coverage" });
  expect(within(coverage).getByText("Shadow analysis: coverage UNKNOWN (not returned by the service)")).toBeInTheDocument();
  expect(within(coverage).getByText("Hit counters: coverage UNKNOWN")).toHaveAttribute("data-hits-coverage", "unknown");
  expect(within(coverage).queryByRole("list", { name: "Not provable reasons" })).toBeNull();
  expect(within(coverage).getByText(/No not-provable rule is on this page; filter by "not provable" to see why the 12 could not be decided\./)).toBeInTheDocument();
});
it("warns about missing hit counters as their own line, prints a shadow reason once per row, and lists the proven classes before the coverage chip", async () => {
  const shadowUnknown = { ...hygiene, coveringRuleId: null, shadowStatus: "UNKNOWN" as const, shadowReason: "earlier rule has unsupported constraints" };
  vi.stubGlobal("fetch", vi.fn(async () => response({ ...page, rows: [{ ...page.rows[0], hygiene: shadowUnknown,
    findings: [{ findingClass: "unknown", severity: "LOW", evidence: "Shadowing: earlier rule has unsupported constraints." }, { findingClass: "unknown", severity: "LOW", evidence: "Hit counts were not collected or are invalid." }, { findingClass: "broad", severity: "MEDIUM", evidence: "Broad selectors are present; see permissiveness reasons." }] }],
    counts: { ...page.counts, unknown: 1, broad: 1 }, coverage: { rules: 1, shadowProven: 0, shadowUnknown: 1, hitsCollected: 0, hitsMissing: 1 },
    unknownReasons: [{ evidence: "Shadowing: earlier rule has unsupported constraints.", rules: 1 }, { evidence: "Hit counts were not collected or are invalid.", rules: 1 }] })));
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} />);
  const coverage = await screen.findByRole("region", { name: "Analysis coverage" });
  expect(within(coverage).getByText(/^Hit counters: 0 stored · 1 not collected · a full refresh/)).toBeInTheDocument();
  expect(within(coverage).getByText("Earlier rule in this layer not provable")).toBeInTheDocument();
  // The row: the shadow reason sits in the Shadow comparison column only; the hit-counter gap stays in Findings.
  expect(screen.getAllByText(/earlier rule has unsupported constraints/)).toHaveLength(1);
  expect(screen.getByText("UNKNOWN because earlier rule has unsupported constraints")).toBeInTheDocument();
  expect(screen.getByText(/UNKNOWN because Hit counts were not collected/)).toBeInTheDocument();
  expect(screen.getByText("Earlier rule in this layer not provable", { selector: "[data-reason-group]" })).toBeInTheDocument();
  const chips = screen.getAllByRole("button", { name: /^(conflict|shadowed|broad|unused|expired|disabled|not provable \(UNKNOWN\)): / }).map(chip => chip.textContent);
  expect(chips[0]).toMatch(/^conflict/); expect(chips[chips.length - 1]).toMatch(/^not provable \(UNKNOWN\)/);
  expect(screen.getByText(/rules whose only result is not provable are under/)).toBeInTheDocument();
});
it("names each blocked rule's blocker and lists the biggest blockers policy-wide, jumping to the rule", async () => {
  const blocked = { ...hygiene, coveringRuleId: null, shadowStatus: "UNKNOWN" as const, shadowReason: "earlier rule has unsupported constraints", blockingRuleId: "r12", blockingRuleNumber: 12 };
  vi.stubGlobal("fetch", vi.fn(async () => response({ ...page, rows: [{ ...page.rows[0], hygiene: blocked, findings: [{ findingClass: "unknown", severity: "LOW", evidence: "Shadowing: earlier rule has unsupported constraints." }] }],
    counts: { ...page.counts, unknown: 400 }, coverage: { rules: 500, shadowProven: 100, shadowUnknown: 400, hitsCollected: 500, hitsMissing: 0 },
    unknownReasons: [{ evidence: "Shadowing: earlier rule has unsupported constraints.", rules: 400 }],
    blockers: { distinct: 7, top: [{ ruleId: "r12", number: 12, blocks: 340 }, { ruleId: "r3", number: 3, blocks: 1 }] } })));
  const openRule = vi.fn();
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={openRule} initialClass="unknown" />);
  const coverage = await screen.findByRole("region", { name: "Analysis coverage" });
  expect(within(coverage).getByText(/^7 earlier rules keep other rules from being proven\. Biggest blockers:/)).toBeInTheDocument();
  fireEvent.click(within(coverage).getByRole("button", { name: "rule #12 blocks 340 rules," }));
  expect(openRule).toHaveBeenLastCalledWith("r12", 90);
  expect(within(coverage).getByRole("button", { name: "rule #3 blocks 1 rule" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Blocked by rule #12" }));
  expect(openRule).toHaveBeenLastCalledWith("r12", 90);
});
it("reads the bulkhead's 503 POLICY_BUSY as a wait, not as a failed read", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ error: "POLICY_BUSY" }), { status: 503, headers: { "Retry-After": "5" } })).mockImplementation(async () => response(page)));
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Policy analysis is busy; retry in a few seconds.");
  expect(screen.getByRole("alert")).not.toHaveTextContent("could not be loaded");
  fireEvent.click(screen.getByText("Retry hygiene")); await screen.findByText("conflict: 1");
});
it("handles failed reads and retries and requests masked CSV with active filters", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response("{}", { status: 500 })).mockImplementation(async (url: string) => url.includes(".csv") ? new Response("Rule,Class\nRULE-BRAVO-02,conflict") : response(page));
  vi.stubGlobal("fetch", fetcher);
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:synthetic") });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Hygiene could not be loaded.");
  fireEvent.click(screen.getByText("Retry hygiene")); await screen.findByText("conflict: 1");
  fireEvent.click(screen.getByText("Export hygiene CSV"));
  await waitFor(() => expect(click).toHaveBeenCalled());
  expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("hygiene.csv?findingClass=&severity=&days=90"), { credentials: "include" });
});
it("fills rule metrics with conflict link, violations and inline scoring explanation", () => {
  const openRule = vi.fn(); mount(<RuleMetrics rule={detail.rule} openRuleId={openRule} />);
  expect(screen.getByText("CONFLICT")).toBeInTheDocument();
  fireEvent.click(screen.getByText("Covering rule")); expect(openRule).toHaveBeenCalledWith("r1");
  expect(screen.getByText("Why this level · score 2")).toBeInTheDocument();
  expect(within(screen.getByRole("list", { name: "Reasons" })).getByText("Any destination")).toBeInTheDocument();
  expect(screen.getByText(/HIGH · conflict/)).toBeInTheDocument();
});
it("does not show missing analysis as a clean rule", () => {
  mount(<RuleMetrics rule={{ ...detail.rule, hygiene: undefined }} />);
  expect(screen.getByText("UNKNOWN because analysis not available")).toBeInTheDocument();
});

it("opens with the finding class a rule flag linked to, and ignores a class it does not know", async () => {
  const fetcher = vi.fn(async () => response(page)); vi.stubGlobal("fetch", fetcher);
  const { unmount } = mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} initialClass="broad" />);
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("findingClass=broad"), expect.anything()));
  expect(screen.getByLabelText("Finding class")).toHaveTextContent("broad");
  unmount(); fetcher.mockClear();
  mount(<PolicyHygieneTab openObject={vi.fn()} policy="p1" openRule={vi.fn()} initialClass="not-a-class" />);
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("findingClass=&"), expect.anything()));
});
