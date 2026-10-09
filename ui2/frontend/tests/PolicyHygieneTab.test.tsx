import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyHygieneTab } from "../src/screens/PolicyHygieneTab";
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
  fireEvent.click(screen.getByRole("button", { name: "Next hygiene page" }));
  expect(await screen.findByText("No hygiene findings match these filters.")).toBeInTheDocument();
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
  expect(screen.getByText(/Why this level: Any destination · Score 2/)).toBeInTheDocument();
  expect(screen.getByText(/HIGH · conflict/)).toBeInTheDocument();
});
it("does not show missing analysis as a clean rule", () => {
  mount(<RuleMetrics rule={{ ...detail.rule, hygiene: undefined }} />);
  expect(screen.getByText("UNKNOWN because analysis not available")).toBeInTheDocument();
});
