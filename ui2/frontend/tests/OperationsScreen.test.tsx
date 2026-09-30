import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { OperationsScreen } from "../src/screens/OperationsScreen";

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const MEMBERS = [
  { device_id: "d1", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-ROMEO-01-M1", model: null, software_version: null, ha_role: "active", cluster_member_ref: "CLS-ROMEO-01" },
  { device_id: "d2", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-ROMEO-01-M2", model: null, software_version: null, ha_role: "standby", cluster_member_ref: "CLS-ROMEO-01" },
];

const READY_SUMMARY = [{ clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: "CLS-ROMEO-01", vendor: "check_point", activeWindow: false, lastRunState: null, lastRunOutcome: null, lastRunAt: null, canRunReadiness: true,
  readiness: { status: "READY", observedAt: new Date(Date.now() - 2 * 3600_000).toISOString(), failedCheck: "", checks: [
    { checkNo: 1, title: "Cluster state", member: "Member 1", status: "PASS", result: "PASS", summary: "Active", blocking: true, derived: { role: "ACTIVE" } },
    { checkNo: 1, title: "Cluster state", member: "Member 2", status: "PASS", result: "PASS", summary: "Standby", blocking: true, derived: { role: "STANDBY" } },
  ] } }];

function stubFetch(devices: unknown[], summary: unknown | null) {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input);
    const json = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status }));
    if (url === "/devices") return json(200, { devices });
    if (url === "/api/v2/cp-failover/summary") return json(200, summary ?? []);
    if (url.includes("/readiness")) return json(200, { runId: "run-ready" });
    if (url.endsWith("/runs/run-ready")) return json(200, { state: "DONE", outcome: "READY", checks: [] });
    if (url.endsWith("/schedules")) return json(200, []);
    if (url.startsWith("/api/v2/jobs")) return json(200, { items: [], page: 1, page_size: 50, total: 0, states: [], job_types: [], total_24h: 0, completed_24h: 0, failed_24h: 0, running: 0 });
    return json(200, {});
  }));
}

afterEach(() => vi.unstubAllGlobals());

const TABS = [
  { label: "HA & readiness", marker: "No HA pair or cluster enrolled" },
  { label: "Jobs", marker: "All jobs" },
  { label: "Queue", marker: "Queued and running jobs" },
  { label: "History", marker: "Finished jobs" },
  { label: "Diagnostics", marker: "Super administrator role required." },
];

describe("OperationsScreen tabs", () => {
  it("renders each tab's own panel, and no other tab's panel, tab by tab", async () => {
    stubFetch([], null);
    render(withTheme(<OperationsScreen />));
    await screen.findByText("No HA pair or cluster enrolled");
    const tablist = screen.getByRole("tablist", { name: "Operations sections" });

    for (const tab of TABS) {
      fireEvent.click(within(tablist).getByRole("tab", { name: tab.label }));
      expect(await screen.findByText(tab.marker)).toBeInTheDocument();
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("defaults to the HA & readiness tab, empty", async () => {
    stubFetch([], null);
    render(withTheme(<OperationsScreen />));
    expect(await screen.findByText("No HA pair or cluster enrolled")).toBeInTheDocument();
  });

  it("lists the enrolled clusters from the device list, never a demo name", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    expect(await screen.findByText("1 clusters enrolled")).toBeInTheDocument();
    expect(screen.queryByText(/CLS-TANGO-01/)).toBeNull();
  });

  it("shows readiness checks from the summary, without requesting Phase A preflight", async () => {
    stubFetch(MEMBERS, READY_SUMMARY);
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
    expect(await screen.findByText("Readiness: Ready · 2 h ago")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Readiness results" });
    expect(within(table).getByText("Active")).toBeInTheDocument();
    expect(within(table).getByText("Standby")).toBeInTheDocument();
    expect(within(table).getByText("Member 1")).toBeInTheDocument();
    expect(within(table).getByText("Member 2")).toBeInTheDocument();
    expect(table.textContent).not.toMatch(/[{}]/);
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes("/preflight"))).toBe(false);
    fireEvent.click(screen.getByText("All clusters"));
    expect(await screen.findByText("1 clusters enrolled")).toBeInTheDocument();
  });

  it("submits read-only readiness from the unit row", async () => {
    stubFetch(MEMBERS, READY_SUMMARY);
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByRole("button", { name: "Run pre-checks" }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([input, init]) =>
      String(input).endsWith("/units/opaque-cluster/readiness") && (init as RequestInit)?.method === "POST")).toBe(true));
  });

  it("opens the 4-Eyes gate and the maintenance-window dialogs for a real cluster", async () => {
    stubFetch(MEMBERS, READY_SUMMARY);
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
    await screen.findByText("Readiness: Ready · 2 h ago");
    fireEvent.click(screen.getByRole("button", { name: "Authorize Failover (4-Eyes)" }));
    expect(screen.getByText(/Phase B & C: 4-Eyes Controlled Failover Gate/i)).toBeInTheDocument();
  });

  it("lists the enrolled cluster in a table with vendor, members and readiness, not a chip wall", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    await screen.findByText("1 clusters enrolled");
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("FW-ROMEO-01-M1")).toBeInTheDocument();
    expect(screen.getByText("FW-ROMEO-01-M2")).toBeInTheDocument();
    expect(screen.getAllByText("Unknown").length).toBeGreaterThan(0);
  });

  it("preselects the cluster named in ?cluster_ref= (a link from another screen)", async () => {
    const originalLocation = window.location.href;
    window.history.pushState({}, "", "/?screen=operations&cluster_ref=CLS-ROMEO-01");
    try {
      stubFetch(MEMBERS, READY_SUMMARY);
      render(withTheme(<OperationsScreen />));
      expect(await screen.findByText("Readiness: Ready · 2 h ago")).toBeInTheDocument();
    } finally {
      window.history.pushState({}, "", originalLocation);
    }
  });

  it("shows readiness-record count without claiming unobserved states", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    await screen.findByText("1 clusters enrolled · 0 readiness records");
    expect(screen.getAllByText("Unknown").length).toBeGreaterThan(0);
  });

  it("moves the Job history action into the Failed jobs KPI card and switches to the History tab", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    // Exactly one "Job history" control: the header no longer duplicates the History tab with its own button.
    expect(await screen.findAllByText("Job history")).toHaveLength(1);
    fireEvent.click(screen.getByText("Job history"));
    expect(await screen.findByText("Finished jobs")).toBeInTheDocument();
  });
});

describe("job totals reconcile (review 2026-09-23)", () => {
  it("shows every terminal state of the 24 h window and the success-rate denominator", async () => {
    const { render, screen } = await import("@testing-library/react");
    const { vi } = await import("vitest");
    const { OperationsScreen } = await import("../src/screens/OperationsScreen");
    vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(
      url.includes("/api/v2/jobs/stats")
        ? { total: 5000, total_24h: 1018, completed_24h: 971, failed_24h: 45, outcome_unknown_24h: 2, rejected_24h: 0, cancelled_24h: 0, submitted_24h: 1018, running: 0 }
        : {}), { status: url.includes("/api/v2/jobs/stats") ? 200 : 404 }))));
    render(<OperationsScreen />);
    expect(await screen.findByText(/971 completed · 45 failed · 2 outcome unknown · 0 in flight/)).toBeInTheDocument();
    expect(screen.getByText(/completed of 1016 completed or failed; 2 other outcomes not counted/)).toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});

it("shows a VSX unit directly below its cluster with outcome and window", async () => {
  const from = new Date(Date.now() - 3600_000).toISOString();
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    const body = url === "/devices" ? { devices: MEMBERS }
      : url.endsWith("/cp-failover/summary") ? [{ clusterId: "opaque-cluster", unitId: "opaque-vs", cluster_member_ref: "CLS-ROMEO-01", virtual_system: "VS-ROMEO-01-07", vendor: "check_point", activeWindow: true, lastRunState: "DONE", lastRunOutcome: "PASS", lastRunAt: from, canRunReadiness: true, readiness: null }]
      : {};
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
  }));
  render(withTheme(<OperationsScreen />));
  expect(await screen.findByText("VS-ROMEO-01-07")).toBeInTheDocument();
  const row = screen.getByText("VS-ROMEO-01-07").closest("tr")!;
  expect(within(row).getByText("PASS")).toBeInTheDocument();
  expect(within(row).getByText("Active window")).toBeInTheDocument();
  await waitFor(() => expect(vi.mocked(fetch).mock.calls.filter(([input]) => String(input).includes("cp-failover"))).toHaveLength(1));
  expect(String(vi.mocked(fetch).mock.calls.find(([input]) => String(input).includes("cp-failover"))?.[0])).toBe("/api/v2/cp-failover/summary");
});
