import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { OperationsScreen } from "../src/screens/OperationsScreen";
import { HaReadinessList } from "../src/screens/HaReadinessList";
import type { CpFailoverSummary, DeviceSummary } from "../src/auth/adminApi";

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const MEMBERS = [
  { device_id: "d1", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-ROMEO-01-M1", model: null, software_version: null, ha_role: "active", cluster_member_ref: "CLS-ROMEO-01" },
  { device_id: "d2", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-ROMEO-01-M2", model: null, software_version: null, ha_role: "standby", cluster_member_ref: "CLS-ROMEO-01" },
];

const READY_SUMMARY = [{ clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: "CLS-ROMEO-01", vendor: "check_point", members: MEMBERS, masked: false, activeWindow: false, lastRunState: null, lastRunOutcome: null, lastRunAt: null, canRunReadiness: true,
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
  { label: "Diagnostics", marker: "Diagnostics could not be loaded or access is unavailable." },
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
    expect(await screen.findByRole("region", { name: "Checks for CLS-ROMEO-01" })).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Readiness results" });
    expect(within(table).getByText("Active")).toBeInTheDocument();
    expect(within(table).getByText("Standby")).toBeInTheDocument();
    expect(within(table).getByText("FW-ROMEO-01-M1")).toBeInTheDocument();
    expect(within(table).getByText("FW-ROMEO-01-M2")).toBeInTheDocument();
    expect(table.textContent).not.toMatch(/[{}]/);
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes("/preflight"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Open full detail" }));
    expect(screen.getByRole("dialog", { name: "HA readiness detail" })).toBeInTheDocument();
    fireEvent.click(screen.getByText("Close detail"));
    expect(await screen.findByText("1 clusters enrolled")).toBeInTheDocument();
  });

  it.each([true, false])("shows the AIView badge only for server-masked sessions (%s)", async (masked) => {
    stubFetch(MEMBERS, READY_SUMMARY.map(row => ({ ...row, masked })));
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
    fireEvent.click(screen.getByRole("button", { name: "Open full detail" }));
    expect(screen.queryByText("AIView Pseudonymized") !== null).toBe(masked);
  });

  it.each([
    { masked: true, canRun: true, allowed: false },
    { masked: false, canRun: false, allowed: false },
    { masked: false, canRun: true, allowed: true },
  ])("gates schedule reads on the row affordance (%j)", async ({ masked, canRun, allowed }) => {
    stubFetch(MEMBERS, READY_SUMMARY.map(row => ({ ...row, masked, canRunReadiness: canRun })));
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
    fireEvent.click(screen.getByRole("button", { name: "Open full detail" }));
    await screen.findByRole("dialog", { name: "HA readiness detail" });
    await act(async () => {});
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).endsWith("/schedules"))).toBe(allowed);
    expect(screen.queryByText("Available to operators") !== null).toBe(!allowed);
    expect(screen.queryByRole("button", { name: "Schedule Maintenance Window" }) !== null).toBe(allowed);
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
    fireEvent.click(screen.getByRole("button", { name: "Open full detail" }));
    fireEvent.click(screen.getByRole("button", { name: "Authorize Failover (4-Eyes)" }));
    expect(screen.getByText(/Phase B & C: 4-Eyes Controlled Failover Gate/i)).toBeInTheDocument();
  });

  it("lists the enrolled cluster in a dense table with vendor, members and readiness", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    await screen.findByText("1 clusters enrolled");
    expect(screen.getByRole("table", { name: "HA clusters" })).toBeInTheDocument();
    expect(screen.getByText(/Active: FW-ROMEO-01-M1/)).toHaveTextContent("Standby: FW-ROMEO-01-M2");
    expect(screen.getAllByText("Unknown").length).toBeGreaterThan(0);
  });

  it("preselects the cluster named in ?cluster_ref= (a link from another screen)", async () => {
    const originalLocation = window.location.href;
    window.history.pushState({}, "", "/?screen=operations&cluster_ref=CLS-ROMEO-01");
    try {
      stubFetch(MEMBERS, READY_SUMMARY);
      render(withTheme(<OperationsScreen />));
      expect(await screen.findByRole("dialog", { name: "HA readiness detail" })).toBeInTheDocument();
    } finally {
      window.history.pushState({}, "", originalLocation);
    }
  });

  it("shows readiness-record count without claiming unobserved states", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    await screen.findByText("1 clusters enrolled · 0 readiness records · 0 not supported");
    expect(screen.getByText("0 ready · 0 not ready · 1 unknown")).toBeInTheDocument();
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

it("nests a VSX unit inside its cluster with a separate readiness action", async () => {
  const from = new Date(Date.now() - 3600_000).toISOString();
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    const body = url === "/devices" ? { devices: MEMBERS }
      : url.endsWith("/cp-failover/summary") ? [{ clusterId: "opaque-cluster", unitId: "opaque-vs", cluster_member_ref: "CLS-ROMEO-01", virtual_system: "VS-ROMEO-01-07", vendor: "check_point", members: MEMBERS, masked: false, activeWindow: true, lastRunState: "DONE", lastRunOutcome: "PASS", lastRunAt: from, canRunReadiness: true, readiness: null }]
      : {};
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
  }));
  render(withTheme(<OperationsScreen />));
  const toggle = await screen.findByRole("button", { name: "Virtual systems in CLS-ROMEO-01" });
  expect(toggle).toHaveAttribute("aria-expanded", "false");
  fireEvent.click(toggle);
  const row = screen.getByRole("row", { name: "Virtual System VS-ROMEO-01-07" });
  expect(within(row).getByRole("button", { name: "Run pre-checks" })).toBeInTheDocument();
  fireEvent.click(row);
  expect(screen.getByRole("region", { name: "Checks for Virtual System VS-ROMEO-01-07" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Open full detail" }));
  expect(await screen.findByLabelText("Readiness checks")).toBeInTheDocument();
  await waitFor(() => expect(vi.mocked(fetch).mock.calls.filter(([input]) => String(input).includes("cp-failover"))).toHaveLength(1));
  expect(String(vi.mocked(fetch).mock.calls.find(([input]) => String(input).includes("cp-failover"))?.[0])).toBe("/api/v2/cp-failover/summary");
});

it("opens full detail in a right drawer, keeps the list and does not change the URL", async () => {
  stubFetch(MEMBERS, READY_SUMMARY);
  render(withTheme(<OperationsScreen />));
  const url = window.location.href;
  fireEvent.keyDown(await screen.findByRole("row", { name: "CLS-ROMEO-01" }), { key: "Enter" });
  fireEvent.click(screen.getByRole("button", { name: "Open full detail" }));
  const drawer = screen.getByRole("dialog", { name: "HA readiness detail" });
  expect(drawer).toHaveClass("MuiDrawer-paperAnchorRight");
  expect(within(drawer).getByRole("table", { name: "Readiness results" })).toBeInTheDocument();
  expect(screen.getByRole("table", { name: "HA clusters", hidden: true })).toBeInTheDocument();
  expect(window.location.href).toBe(url);
  fireEvent.click(within(drawer).getByRole("button", { name: "Close detail" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "HA readiness detail" })).toBeNull());
  expect(screen.getByRole("table", { name: "HA clusters" })).toBeInTheDocument();
});

const BULK_MEMBERS = [...MEMBERS, ...MEMBERS.map((member, index) => ({ ...member, device_id: `pan-member-${index}`, hostname: `FW-TANGO-02-M${index + 1}`, vendor_hint: "palo_alto", cluster_member_ref: "CLS-TANGO-02" }))];
const BULK_SUMMARY = [...READY_SUMMARY, { ...READY_SUMMARY[0], clusterId: "opaque-pan", unitId: "opaque-pan", cluster_member_ref: "CLS-TANGO-02", vendor: "palo_alto", members: BULK_MEMBERS.slice(2) }];

it("starts every HA row collapsed and unselected when a late summary reorders the list", async () => {
  stubFetch(BULK_MEMBERS, BULK_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  let finishSummary!: (response: Response) => void;
  const summary = new Promise<Response>(resolve => { finishSummary = resolve; });
  vi.mocked(fetch).mockImplementation((input, init) => String(input) === "/api/v2/cp-failover/summary"
    ? summary : original(input, init));
  render(withTheme(<OperationsScreen />));
  const row = await screen.findByRole("row", { name: "CLS-ROMEO-01" });
  const identity = row.getAttribute("data-readiness-row");
  expect(row).toHaveAttribute("aria-expanded", "false");
  expect(within(row).getByRole("checkbox")).not.toBeChecked();
  await act(async () => finishSummary(new Response(JSON.stringify([
    BULK_SUMMARY[0], { ...BULK_SUMMARY[1], readiness: { ...BULK_SUMMARY[1].readiness, status: "NOT_READY" } },
  ]))));
  const table = screen.getByRole("table", { name: "HA clusters" });
  expect(within(table).getAllByRole("row")[1]).toHaveAccessibleName("CLS-TANGO-02");
  expect(screen.getByRole("row", { name: "CLS-ROMEO-01" })).toBe(row);
  expect(row).toHaveAttribute("data-readiness-row", identity);
  for (const name of ["CLS-ROMEO-01", "CLS-TANGO-02"]) {
    const refreshed = within(table).getByRole("row", { name });
    expect(refreshed).toHaveAttribute("aria-expanded", "false");
    expect(within(refreshed).getByRole("checkbox")).not.toBeChecked();
  }
});

it("keeps user expansion on the same HA identity when a refresh changes status ordering", () => {
  const clusters = ["CLS-ROMEO-01", "CLS-TANGO-02"].map(ref => ({
    ref, title: ref, members: BULK_MEMBERS.filter(member => member.cluster_member_ref === ref) as DeviceSummary[],
  }));
  const rows = Object.fromEntries(BULK_SUMMARY.map(row => [row.cluster_member_ref, [row]])) as Record<string, CpFailoverSummary[]>;
  const props = { clusters, rows, running: null, busy: false, progress: {}, error: null,
    onOpen: vi.fn(), onRun: vi.fn(), onBulkRun: vi.fn() };
  const { rerender } = render(withTheme(<HaReadinessList {...props} />));
  const row = screen.getByRole("row", { name: "CLS-ROMEO-01" });
  fireEvent.click(within(row).getByRole("button", { name: "Checks for CLS-ROMEO-01" }));
  rerender(withTheme(<HaReadinessList {...props} rows={{ ...rows,
    "CLS-TANGO-02": [{ ...rows["CLS-TANGO-02"][0], readiness: { ...rows["CLS-TANGO-02"][0].readiness!, status: "NOT_READY" } }],
  }} />));
  expect(within(screen.getByRole("table", { name: "HA clusters" })).getAllByRole("row")[1]).toHaveAccessibleName("CLS-TANGO-02");
  expect(screen.getByRole("row", { name: "CLS-ROMEO-01" })).toBe(row);
  expect(row).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByRole("row", { name: "CLS-TANGO-02" })).toHaveAttribute("aria-expanded", "false");
});

it("calls each selected unit's vendor readiness API sequentially and reports per-row progress", async () => {
  stubFetch(BULK_MEMBERS, BULK_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  let finishFirst!: (response: Response) => void;
  let finishSecond!: (response: Response) => void;
  const first = new Promise<Response>(resolve => { finishFirst = resolve; });
  const second = new Promise<Response>(resolve => { finishSecond = resolve; });
  vi.mocked(fetch).mockImplementation((input, init) => {
    const url = String(input);
    if (url.endsWith("/readiness")) return Promise.resolve(new Response(JSON.stringify({ runId: url.includes("pan-failover") ? "run-second" : "run-first" })));
    if (url.endsWith("/runs/run-first")) return first;
    if (url.endsWith("/runs/run-second")) return second;
    return original(input, init);
  });
  render(withTheme(<OperationsScreen />));
  await screen.findByText("2 clusters enrolled");
  await waitFor(() => expect(screen.getByRole("checkbox", { name: "Select all visible" })).toBeEnabled());
  fireEvent.click(screen.getByRole("checkbox", { name: "Select all visible" }));
  fireEvent.click(screen.getByRole("button", { name: "Run pre-checks (2)" }));
  const postCalls = () => vi.mocked(fetch).mock.calls.filter(([input]) => String(input).endsWith("/readiness"));
  await waitFor(() => expect(postCalls()).toHaveLength(1));
  expect(String(postCalls()[0][0])).toBe("/api/v2/cp-failover/units/opaque-cluster/readiness");
  expect(postCalls()[0][1]).toMatchObject({ method: "POST", body: JSON.stringify({ clusterId: "opaque-cluster", unitId: "opaque-cluster" }) });
  expect(within(screen.getByRole("row", { name: "CLS-ROMEO-01" })).getByRole("status")).toHaveTextContent("Running");
  expect(within(screen.getByRole("row", { name: "CLS-TANGO-02" })).getByRole("status")).toHaveTextContent("Queued");
  expect(screen.getByRole("button", { name: "Run pre-checks (2)" })).toBeDisabled();
  await act(async () => finishFirst(new Response(JSON.stringify({ state: "DONE" }))));
  await waitFor(() => expect(postCalls()).toHaveLength(2));
  expect(String(postCalls()[1][0])).toBe("/api/v2/pan-failover/units/opaque-pan/readiness");
  expect(within(screen.getByRole("row", { name: "CLS-ROMEO-01" })).getByRole("status")).toHaveTextContent("Completed");
  expect(within(screen.getByRole("row", { name: "CLS-TANGO-02" })).getByRole("status")).toHaveTextContent("Running");
  await act(async () => finishSecond(new Response(JSON.stringify({ state: "DONE" }))));
  await waitFor(() => expect(screen.getAllByRole("status").filter(element => element.textContent === "Completed")).toHaveLength(2));
  expect(screen.getByRole("button", { name: "Run pre-checks (2)" })).toBeEnabled();
});

it("stops bulk after a failed API call, reports the failed and unstarted rows, and unlocks selection", async () => {
  stubFetch(BULK_MEMBERS, BULK_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input, init) => String(input).endsWith("/readiness")
    ? Promise.resolve(new Response("{}", { status: 503 })) : original(input, init));
  render(withTheme(<OperationsScreen />));
  await screen.findByText("2 clusters enrolled");
  await waitFor(() => expect(screen.getByRole("checkbox", { name: "Select all visible" })).toBeEnabled());
  fireEvent.click(screen.getByRole("checkbox", { name: "Select all visible" }));
  fireEvent.click(screen.getByRole("button", { name: "Run pre-checks (2)" }));
  await waitFor(() => expect(within(screen.getByRole("row", { name: "CLS-TANGO-02" })).getByRole("status")).toHaveTextContent("Not run"));
  expect(within(screen.getByRole("row", { name: "CLS-ROMEO-01" })).getByRole("status")).toHaveTextContent("Failed");
  expect(vi.mocked(fetch).mock.calls.filter(([input]) => String(input).endsWith("/readiness"))).toHaveLength(1);
  expect(screen.getByText("Could not start or refresh the readiness run.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Clear selection" })).toBeEnabled();
});

it("summarizes the same cluster readiness counts in the KPI and chips without double counting VS or unsupported vendors", async () => {
  const extra = ["CLS-BRAVO-03", "CLS-CHARLIE-04"].flatMap((ref, index) => MEMBERS.map((member, m) => ({ ...member, device_id: `extra-${index}-${m}`, hostname: `FW-${ref}-M${m + 1}`, cluster_member_ref: ref, vendor_hint: index ? "cisco_asa" : "check_point" })));
  stubFetch([...BULK_MEMBERS, ...extra], [READY_SUMMARY[0], { ...BULK_SUMMARY[1], readiness: { ...BULK_SUMMARY[1].readiness, status: "NOT_READY" } },
    { ...READY_SUMMARY[0], unitId: "opaque-vs", virtual_system: "VS-ROMEO-07" },
    { ...READY_SUMMARY[0], clusterId: "unsupported", unitId: "unsupported", cluster_member_ref: "CLS-CHARLIE-04" }]);
  render(withTheme(<OperationsScreen />));
  expect(await screen.findByText("1 ready · 1 not ready · 1 unknown")).toBeInTheDocument();
  for (const name of ["Ready", "Not ready", "Unknown", "Not supported"]) expect(screen.getByRole("button", { name: `${name}: 1 clusters` })).toBeInTheDocument();
  expect(screen.queryByText("NOT EVALUATED")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Ready: 1 clusters" }));
  expect(screen.getByText("1 ready · 1 not ready · 1 unknown")).toBeInTheDocument();
});

it("offers aiview inspection with no readiness or bulk actions", async () => {
  stubFetch(MEMBERS, READY_SUMMARY.map(unit => ({ ...unit, masked: true, canRunReadiness: true })));
  render(withTheme(<OperationsScreen />));
  fireEvent.click(await screen.findByRole("row", { name: "CLS-ROMEO-01" }));
  expect(screen.getByRole("region", { name: "Checks for CLS-ROMEO-01" })).toBeInTheDocument();
  expect(screen.getByRole("checkbox", { name: "Select all visible" })).toBeDisabled();
  expect(screen.queryByRole("toolbar", { name: "Bulk readiness actions" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Run pre-checks" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Open full detail" }));
  expect(screen.queryByRole("button", { name: "Run pre-checks" })).toBeNull();
});
