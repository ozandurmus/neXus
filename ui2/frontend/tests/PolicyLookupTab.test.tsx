import { fireEvent, render, screen, within, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { PolicyLookupTab } from "../src/screens/PolicyLookupTab";
import type { PolicyLookupResult, PolicyMetadata } from "../src/auth/adminApi";
const policy: PolicyMetadata = { id: "p1", sourceId: "s1", sourceName: "MGR-ALPHA", vendor: "PAN", containerId: "c1", containerName: "DOM-ALPHA", name: "POL-ALPHA", collectedAt: "2026-10-09T12:00:00Z", artefactRef: "a1", targets: [] };
const rule = { ruleId: "r2", number: 2, name: "RULE-BRAVO", sectionId: "s2", sectionName: "SECTION-BRAVO", action: "allow" };
const result: PolicyLookupResult = { policyId: "p1", collectedAt: policy.collectedAt, verdict: "UNKNOWN", lookupReason: "earlier rule could match",
  decidingRule: rule, undecidableRules: [{ ...rule, ruleId: "r1", number: 1, name: "RULE-ALPHA", lookupReason: "negated selector" }],
  query: { querySource: "192.0.2.8", queryDestination: "198.51.100.8", queryService: "tcp/443" }, parts: [],
  limitations: "Policy intent only. NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported." };
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
function fill() {
  fireEvent.change(screen.getByLabelText(/^Source\s*\*?$/), { target: { value: "192.0.2.8" } });
  fireEvent.change(screen.getByLabelText(/^Destination\s*\*?$/), { target: { value: "198.51.100.8" } });
}
it("submits a lookup, shows uncertainty and first proven match, links rules and keeps session history", async () => {
  const fetcher = vi.fn(async () => new Response(JSON.stringify(result), { status: 200 })); vi.stubGlobal("fetch", fetcher);
  const openRule = vi.fn(); render(<PolicyLookupTab policies={[policy]} policy="p1" selectPolicy={vi.fn()} openRule={openRule} />);
  fill(); fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" }));
  const card = await screen.findByLabelText("Lookup result 1");
  expect(within(card).getByText("UNKNOWN")).toBeInTheDocument();
  expect(within(card).getByText(/UNKNOWN: rule 1 could match/)).toHaveTextContent("negated selector");
  expect(within(card).getByText(/First proven match after uncertainty/)).toBeInTheDocument();
  fireEvent.click(within(card).getByRole("button", { name: "2 · RULE-BRAVO" })); expect(openRule).toHaveBeenCalledWith("p1", "r2");
  expect(fetcher).toHaveBeenCalledWith("/api/v2/policy/policies/p1/lookup", expect.objectContaining({ method: "POST", body: JSON.stringify({ source: "192.0.2.8", destination: "198.51.100.8", service: "tcp/443" }) }));
  fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" })); await screen.findByLabelText("Lookup result 2");
});
it("renders partial ranges and implicit default deny", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ ...result, verdict: "PARTIAL", lookupReason: "partial query coverage", decidingRule: null, undecidableRules: [], parts: [
    { querySource: "192.0.2.0-192.0.2.7", queryDestination: "198.51.100.8", verdict: "DENIED", lookupReason: "implicit default deny", decidingRule: null, undecidableRules: [] },
    { querySource: "192.0.2.8", queryDestination: "198.51.100.8", verdict: "ALLOWED", lookupReason: "first matching rule", decidingRule: rule, undecidableRules: [] },
  ] }), { status: 200 })));
  render(<PolicyLookupTab policies={[policy]} policy="p1" selectPolicy={vi.fn()} openRule={vi.fn()} />);
  fill(); fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" }));
  expect(await screen.findByText("Partial")).toBeInTheDocument(); expect(screen.getByText("implicit default deny")).toBeInTheDocument();
  expect(screen.getByText(/192.0.2.0-192.0.2.7/)).toBeInTheDocument();
});
it("reports rate limits, retries and never fabricates a result on failures", async () => {
  let attempts = 0;
  const fetcher = vi.fn(async (url: string) => url === "/session/status" ? new Response("{}", { status: 200 })
    : new Response("{}", { status: ++attempts === 1 ? 429 : 500 })); vi.stubGlobal("fetch", fetcher);
  render(<PolicyLookupTab policies={[policy]} policy="p1" selectPolicy={vi.fn()} openRule={vi.fn()} />);
  fill(); fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Retry after one minute");
  fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" }));
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("could not be completed"));
  expect(screen.queryByLabelText("Lookup result 1")).not.toBeInTheDocument();
});
