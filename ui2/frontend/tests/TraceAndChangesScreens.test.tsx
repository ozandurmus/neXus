import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { TraceScreen, traceModeFromUrl } from "../src/screens/TraceScreen";
import { ChangesScreen, changesViewFromUrl } from "../src/screens/ChangesScreen";
import { DiagnosticsScreen } from "../src/screens/DiagnosticsScreen";
import { OfflineAnalysisScreen } from "../src/screens/OfflineAnalysisScreen";
import type { PolicyMetadata } from "../src/auth/adminApi";

const policy: PolicyMetadata = { id: "p1", sourceId: "s1", sourceName: "MGR-ALPHA-01", vendor: "PAN", containerId: "c1", containerName: "DG-ALPHA-01", name: "POL-ALPHA-01",
  collectedAt: "2026-10-09T12:00:00Z", artefactRef: "a1", targets: [] };
const lookupResult = { policyId: "p1", collectedAt: policy.collectedAt, verdict: "DENIED", lookupReason: "implicit default deny", decidingRule: null, undecidableRules: [], parts: [],
  query: { querySource: "192.0.2.8", queryDestination: "198.51.100.8", queryService: "tcp/443" }, limitations: "Policy intent only." };

function stub(overrides: Record<string, unknown> = {}) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    const path = url.split("?")[0];
    const body = path in overrides ? overrides[path]
      : url.endsWith("/api/v2/policy/devices") ? { policies: [policy], devices: [] }
      : url.endsWith("/lookup") ? lookupResult
      : url.includes("/api/v2/policy/changes") ? { rows: [], summary: { total: 0, actors: 0, rules: 0, counts: {} }, nextCursor: "", rangeStart: "", rangeEnd: "" }
      : url.includes("/api/v2/changes/feed") ? { events: [], coverage: [], page: 1, page_size: 50, total: 0 }
      : {};
    return new Response(JSON.stringify(body), { status: 200 });
  }));
  return calls;
}
const themed = (node: React.ReactElement) => <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

describe("Trace (C3): one screen, two modes", () => {
  it("opens on the topology path, with the advanced fields collapsed behind one disclosure", () => {
    stub();
    render(themed(<TraceScreen />));
    expect(screen.getByRole("heading", { name: "Trace" })).toBeInTheDocument();
    const tabs = screen.getByRole("tablist", { name: "Trace modes" });
    expect(within(tabs).getAllByRole("tab").map(t => t.textContent)).toEqual(["Topology path", "Policy rule lookup"]);
    expect(within(tabs).getByRole("tab", { name: "Topology path" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByLabelText(/^Source\s*\*?$/)).toBeInTheDocument();
    const advanced = screen.getByTestId("path-advanced");
    expect(advanced).not.toHaveAttribute("open");
    expect(within(advanced).getByLabelText("Ingress unit")).toBeInTheDocument();
    expect(screen.getByText("No path evaluated yet")).toBeInTheDocument();
  });

  it("switches to the policy rule lookup, writes the mode into the address, collapses the optional lookup fields, and keeps a lookup result when switching back and forth", async () => {
    stub();
    render(themed(<TraceScreen />));
    fireEvent.click(screen.getByRole("tab", { name: "Policy rule lookup" }));
    expect(new URLSearchParams(window.location.search).get("mode")).toBe("lookup");
    fireEvent.mouseDown(await screen.findByLabelText(/Lookup policy/));
    fireEvent.click(await screen.findByRole("option", { name: policy.name }));
    expect(screen.getByTestId("lookup-advanced")).not.toHaveAttribute("open");
    // One visible mode panel at a time: the path form is hidden, not shown beside the lookup.
    const panel = within(screen.getByRole("tabpanel", { name: "Policy rule lookup" }));
    fireEvent.change(panel.getByLabelText(/^Source\s*\*?$/), { target: { value: "192.0.2.8" } });
    fireEvent.change(panel.getByLabelText(/^Destination\s*\*?$/), { target: { value: "198.51.100.8" } });
    fireEvent.click(panel.getByRole("button", { name: "Look up policy intent" }));
    expect(await screen.findByLabelText("Lookup result 1")).toHaveTextContent("Denied");
    fireEvent.click(screen.getByRole("tab", { name: "Topology path" }));
    expect(new URLSearchParams(window.location.search).get("mode")).toBe("path");
    expect(screen.getByLabelText("Lookup result 1")).not.toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "Policy rule lookup" }));
    expect(screen.getByLabelText("Lookup result 1")).toBeVisible();
  });

  it("opens in lookup mode, scoped to the policy, from the Search in this policy link", async () => {
    window.history.replaceState(null, "", "/?screen=trace&mode=lookup&policy_id=p1");
    stub();
    render(themed(<TraceScreen />));
    expect(screen.getByRole("tab", { name: "Policy rule lookup" })).toHaveAttribute("aria-selected", "true");
    await waitFor(() => expect(screen.getByLabelText(/Lookup policy/)).toHaveTextContent(policy.name));
  });

  it("states a restricted catalog read as Restricted, not as an error", async () => {
    window.history.replaceState(null, "", "/?screen=trace&mode=lookup");
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: "ACTION_REFUSED" }), { status: 403 })));
    render(themed(<TraceScreen />));
    expect(await screen.findByText(/This account can not view or change it/)).toBeInTheDocument();
  });

  it("reads the mode from the address, defaulting to the path", () => {
    expect(traceModeFromUrl("lookup")).toBe("lookup");
    expect(traceModeFromUrl("path")).toBe("path");
    expect(traceModeFromUrl(null)).toBe("path");
    expect(traceModeFromUrl("junk")).toBe("path");
  });
});

describe("Changes (C3): one entry for the timeline and the policy rule revisions", () => {
  it("opens on the timeline and offers the policy rule revisions as the second view", async () => {
    const calls = stub();
    render(themed(<ChangesScreen />));
    expect(screen.getByRole("heading", { name: "Changes" })).toBeInTheDocument();
    const tabs = screen.getByRole("tablist", { name: "Changes views" });
    expect(within(tabs).getAllByRole("tab").map(t => t.textContent)).toEqual(["Timeline", "Policy rule revisions"]);
    await waitFor(() => expect(calls.some(c => c.includes("/api/v2/changes/feed"))).toBe(true));
    expect(calls.some(c => c.includes("/api/v2/policy/changes"))).toBe(false);
    fireEvent.click(within(tabs).getByRole("tab", { name: "Policy rule revisions" }));
    expect(new URLSearchParams(window.location.search).get("view")).toBe("policy");
    expect(await screen.findByText("No rule changes match these filters.")).toBeInTheDocument();
  });

  it("opens the policy view from ?view=policy and hands a device filter to the timeline", () => {
    expect(changesViewFromUrl("policy")).toBe("policy");
    expect(changesViewFromUrl("feed")).toBe("feed");
    expect(changesViewFromUrl(null)).toBe("feed");
    window.history.replaceState(null, "", "/?screen=changes&view=policy");
    stub();
    render(themed(<ChangesScreen />));
    expect(screen.getByRole("tab", { name: "Policy rule revisions" })).toHaveAttribute("aria-selected", "true");
  });
});

describe("Tools (C4): Diagnostics and Offline analysis are screens of their own", () => {
  it("renders Diagnostics with its own panel", async () => {
    stub();
    render(themed(<DiagnosticsScreen />));
    expect(screen.getAllByRole("heading", { name: "Diagnostics" }).length).toBeGreaterThan(0);
    // The panel is the unchanged Diagnostics panel: with no readable target list it states that, in its own words.
    expect(await screen.findByText("Diagnostics could not be loaded or access is unavailable.")).toBeInTheDocument();
  });

  it("renders Offline analysis with the policy it is checked against named on the screen, not borrowed from Policy", async () => {
    stub({ "/api/v2/policy/policies/p1/offline-analyses": { analyses: [], canManage: true } });
    render(themed(<OfflineAnalysisScreen />));
    expect(screen.getByRole("heading", { name: "Offline analysis" })).toBeInTheDocument();
    expect(await screen.findByLabelText("Policy snapshot")).toBeInTheDocument();
  });

  it("says plainly when there is no stored policy to analyse against", async () => {
    stub({ "/api/v2/policy/devices": { policies: [], devices: [] } });
    render(themed(<OfflineAnalysisScreen />));
    expect(await screen.findByText("No stored policy to analyse against")).toBeInTheDocument();
  });
});
