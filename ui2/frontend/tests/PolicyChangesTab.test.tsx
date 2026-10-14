import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyChangesTab } from "../src/screens/PolicyChangesTab";
import type { PolicyChangesPage } from "../src/auth/adminApi";
const page: PolicyChangesPage = {
  rows: [{ revision: "2", policyId: "p1", policyName: "POL-ALPHA-01", targets: [{ deviceId: "d1", name: "FW-TANGO-04", context: "", syncStatus: "UNKNOWN" }],
    ruleId: "r1", number: 7, name: "RULE-BRAVO-02", changedOn: "2026-10-09T12:00:00Z", observedAtCollection: true,
    changeType: "modified", changedBy: "UNKNOWN (observed by collection)", changedFields: ["action"], changes: [{ field: "action", before: "allow", after: "deny" }] }],
  summary: { total: 2, actors: 0, rules: 1, counts: { added: 1, modified: 1, removed: 0 } }, nextCursor: "synthetic_cursor",
  rangeStart: "2026-09-09T12:00:00Z", rangeEnd: "2026-10-09T12:00:00Z"
};
function mount() {
  const openRule = vi.fn();
  render(<ThemeProvider theme={m3Theme}><PolicyChangesTab policies={[]} devices={[]} openRule={openRule} /></ThemeProvider>);
  return openRule;
}
const response = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("renders estate summary, UNKNOWN, collection fallback, expandable diffs and rule links", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => response(page))); const openRule = mount();
  expect(screen.getByText("Loading changes…")).toBeInTheDocument();
  fireEvent.click(await screen.findByRole("button", { name: "7 · RULE-BRAVO-02" }));
  expect(openRule).toHaveBeenCalledWith("p1", "r1");
  expect(screen.getByText("UNKNOWN (observed by collection)")).toBeInTheDocument();
  expect(screen.getByText("Observed at collection")).toBeInTheDocument();
  expect(screen.getByText("Distinct actors")).toBeInTheDocument();
  fireEvent.click(screen.getByText("action", { selector: "summary" }));
  expect(screen.getByText('Before: "allow"')).toBeInTheDocument();
  expect(screen.getByText('After: "deny"')).toBeInTheDocument();
});
it("applies combined filters, resets pagination and pins the first page period", async () => {
  const fetcher = vi.fn(async (url: string) => response(url.includes("cursor=synthetic_cursor") ? { ...page, rows: [], nextCursor: "" } : page));
  vi.stubGlobal("fetch", fetcher); mount(); await screen.findByText("Observed at collection");
  fireEvent.click(screen.getByText("Next changes page"));
  expect(await screen.findByText("No rule changes match these filters.")).toBeInTheDocument();
  expect(screen.getByText("Next changes page")).toBeDisabled();
  fireEvent.click(screen.getByText("Previous changes page")); await screen.findByText("Observed at collection");
  expect(fetcher.mock.calls.at(-1)?.[0]).toContain("from=2026-09-09T12%3A00%3A00Z");
  fireEvent.change(screen.getByLabelText("Actor"), { target: { value: "UNKNOWN" } });
  fireEvent.change(screen.getByLabelText("Rule name or number"), { target: { value: "7" } });
  fireEvent.change(screen.getByLabelText("From (UTC)"), { target: { value: "2026-10-01T09:30" } });
  fireEvent.click(screen.getByText("Apply change filters"));
  await waitFor(() => expect(fetcher.mock.calls.at(-1)?.[0]).toContain("actor=UNKNOWN"));
  expect(fetcher.mock.calls.at(-1)?.[0]).toContain("q=7");
  expect(fetcher.mock.calls.at(-1)?.[0]).toContain("from=2026-10-01T09%3A30%3A00.000Z");
  expect(screen.getByText("Previous changes page")).toBeDisabled();
});
it("reports read/export failures, retries and exports all matching pages with the active period", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response("{}", { status: 500 })).mockImplementation(async (url: string) => url.includes(".csv") ? new Response("Time,Rule\n2026-10-09,RULE-BRAVO-02") : response(page));
  vi.stubGlobal("fetch", fetcher); mount();
  expect(await screen.findByRole("alert")).toHaveTextContent("Changes could not be loaded.");
  fireEvent.click(screen.getByText("Retry changes")); await screen.findByText("Observed at collection");
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:synthetic") });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
  fireEvent.click(screen.getByText("Export changes CSV")); await waitFor(() => expect(click).toHaveBeenCalled());
  expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("/changes.csv?from=2026-09-09T12%3A00%3A00Z"), { credentials: "include" });
  fetcher.mockResolvedValueOnce(new Response("{}", { status: 500 }));
  fireEvent.click(screen.getByText("Export changes CSV"));
  expect(await screen.findByRole("alert")).toHaveTextContent("Changes CSV could not be exported.");
});
