import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { OperationsScreen } from "../src/screens/OperationsScreen";
import { HaReadinessList } from "../src/screens/HaReadinessList";
import { setBusinessHoursConfirm, type CpFailoverSummary, type DeviceSummary } from "../src/auth/adminApi";

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
    if (url.includes("/units?")) return json(200, (summary as typeof READY_SUMMARY ?? []).map(row => ({ ...row, canApprove: false, canStart: false, canSchedule: false })));
    if (url.includes("/approvals?") || url.includes("/runs?")) return json(200, []);
    if (url.startsWith("/api/v2/jobs")) return json(200, { items: [], page: 1, page_size: 50, total: 0, states: [], job_types: [], total_24h: 0, completed_24h: 0, failed_24h: 0, running: 0 });
    // neXus' own storage and service view is the Platform status tab (PO 2026-11-13); no server here.
    if (url.startsWith("/api/v2/system/")) return json(503, { code: "SYSTEM_STATUS_READ_FAILED", message: "The system status read failed." });
    if (url === "/api/v2/config/scheduled-jobs") return json(200, { master_enabled: true, master_env: "UI2_SCHEDULING_ENABLED", can_write: false, masked: true, business_hours: null,
      families: [{ family: "discovery_refresh", label: "Discovery refresh", description: "Synthetic description.", cadence: "Every 6 hours",
        enabled: true, state: "ON", will_run: true, lock_reason: null, note: null, window_open_now: false, next_window_at: "2026-11-11T00:00:00+03:00",
        last_run_at: null, changed_by: "USR-0a1b2c3d4e", changed_at: "2026-11-10T18:05:00Z", reason: null }] });
    if (url === "/api/v2/system-logs/health") return json(503, { code: "SYSTEM_HEALTH_READ_FAILED", message: "The device health read failed." });
    if (url === "/api/v2/scripts") return json(200, { scripts: [] });
    return json(200, {});
  }));
}

afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

const TABS = [
  { label: "HA and failover", marker: "No HA pair or cluster enrolled" },
  { label: "Jobs", marker: "All jobs" },
  { label: "Device health", marker: "Device health could not be read" },
  { label: "Script execution", marker: "No scripts registered yet" },
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

  it("has six tabs, the fleet's live activity only: Scheduled jobs and Platform status are Administration pages (PO 2026-11-13 amendment)", async () => {
    stubFetch([], null);
    render(withTheme(<OperationsScreen />));
    await screen.findByText("No HA pair or cluster enrolled");
    const tablist = screen.getByRole("tablist", { name: "Operations sections" });
    expect(within(tablist).getAllByRole("tab").map(t => t.textContent)).toEqual(["HA and failover", "Jobs", "Device health", "Policy installation", "Static routes", "Script execution"]);
    expect(screen.queryByRole("tab", { name: "Scheduled jobs" })).toBeNull();
    expect(screen.queryByRole("tab", { name: "Platform status" })).toBeNull();
  });

  it("opens Device health at its old address and reads only the fleet there, never neXus' own status", async () => {
    stubFetch([], null);
    window.history.replaceState(null, "", "/?screen=operations&tab=health");
    render(withTheme(<OperationsScreen />));
    expect(screen.getByRole("tab", { name: "Device health" })).toHaveAttribute("aria-selected", "true");
    expect(await within(screen.getByRole("tabpanel", { name: "Device health" })).findByText("Device health could not be read")).toBeInTheDocument();
    const urls = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.map(([url]) => String(url));
    expect(urls.some((url) => url.startsWith("/api/v2/system/"))).toBe(false);
    expect(urls).not.toContain("/api/v2/config/scheduled-jobs");
    fireEvent.click(screen.getByRole("tab", { name: "Jobs" }));
    fireEvent.click(screen.getByRole("tab", { name: "Device health" }));
    expect(new URLSearchParams(window.location.search).get("tab")).toBe("health");
  });

  it("defaults to the HA and failover tab, empty", async () => {
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

  it("a row click opens one detail with the checks by consequence from the summary, without requesting Phase A preflight", async () => {
    stubFetch(MEMBERS, READY_SUMMARY);
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
    const drawer = await screen.findByRole("dialog", { name: "HA readiness detail" });
    expect(await within(drawer).findByLabelText("Readiness checks by consequence")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Open full detail" })).toBeNull();
    expect(drawer.textContent).not.toMatch(/[{}]/);
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes("/preflight"))).toBe(false);
    // Exactly one verdict and one check list, not a summary card above a second one.
    expect(within(drawer).getAllByText(/^Ready$/)).toHaveLength(1);
    expect(drawer.querySelectorAll("[data-check-counts]")).toHaveLength(1);
    fireEvent.click(within(drawer).getByRole("button", { name: "Close detail" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "HA readiness detail" })).toBeNull());
    expect(await screen.findByText("1 clusters enrolled")).toBeInTheDocument();
  });

  it.each([true, false])("shows the AIView badge only for server-masked sessions (%s)", async (masked) => {
    stubFetch(MEMBERS, READY_SUMMARY.map(row => ({ ...row, masked })));
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
    const drawer = await screen.findByRole("dialog", { name: "HA readiness detail" });
    await within(drawer).findByLabelText("Readiness checks by consequence");
    expect(screen.queryByText("AIView Pseudonymized") !== null).toBe(masked);
  });

  it("opens the existing request-bound panel without generic execution or scheduling", async () => {
    stubFetch(MEMBERS, READY_SUMMARY);
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
    expect(await screen.findByLabelText("Check Point failover")).toBeInTheDocument();
    await act(async () => {});
    expect(vi.mocked(fetch).mock.calls.some(([input]) => /\/(schedules|authorize|execute)$/.test(String(input)))).toBe(false);
    expect(screen.queryByRole("button", { name: /4-Eyes|Schedule Maintenance/ })).toBeNull();
  });

  it("submits read-only readiness from the unit row", async () => {
    stubFetch(MEMBERS, READY_SUMMARY);
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByRole("button", { name: "Re-check CLS-ROMEO-01" }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([input, init]) =>
      String(input).endsWith("/units/opaque-cluster/readiness") && (init as RequestInit)?.method === "POST")).toBe(true));
  });

  it("lists the enrolled cluster in a dense table with vendor, members and readiness", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    await screen.findByText("1 clusters enrolled");
    expect(screen.getByRole("table", { name: "HA clusters" })).toBeInTheDocument();
    expect(screen.getByText(/Active: FW-ROMEO-01-M1/)).toHaveTextContent("Standby: FW-ROMEO-01-M2");
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThan(0);
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
    expect(screen.getByText("ready of 1 clusters")).toBeInTheDocument();
    expect(screen.getByText("0 not ready · 1 unknown")).toBeInTheDocument();
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThan(0);
  });

  it("links the Failed jobs KPI card to the Jobs table with its Failed pill pressed (roadmap 2.4)", async () => {
    stubFetch(MEMBERS, null);
    render(withTheme(<OperationsScreen />));
    // UI review 2026-11-05, B3: the job cards live on the Jobs tab only, the readiness card on HA & readiness only.
    expect(await screen.findByText("Readiness checks", { selector: "p" })).toBeInTheDocument();
    expect(screen.queryByText("Open failed jobs")).toBeNull();
    expect(screen.queryByText("Jobs finished (24 h)")).toBeNull();
    fireEvent.click(screen.getByRole("tab", { name: "Jobs" }));
    expect(screen.queryByText("Readiness checks", { selector: "p" })).toBeNull();
    expect(screen.getByText("Jobs finished (24 h)")).toBeInTheDocument();
    // Exactly one link: the header no longer duplicates the job table with its own button.
    expect(await screen.findAllByText("Open failed jobs")).toHaveLength(1);
    fireEvent.click(screen.getByText("Open failed jobs"));
    expect(await screen.findByText("All jobs")).toBeInTheDocument();
    const pills = await screen.findByRole("group", { name: "Job state" });
    await waitFor(() => expect(within(pills).getByRole("button", { name: /^Failed/ })).toHaveAttribute("aria-pressed", "true"));
    expect(within(pills).getByRole("button", { name: /^All/ })).toHaveAttribute("aria-pressed", "false");
    expect(new URLSearchParams(window.location.search).get("tab")).toBe("jobs");
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
    // UI review 2026-11-05, B3: the job cards are on the Jobs tab
    const { fireEvent } = await import("@testing-library/react");
    fireEvent.click(screen.getByRole("tab", { name: "Jobs" }));
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
      : url.includes("/units?") || url.includes("/approvals?") || url.includes("/runs?") ? [] : {};
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
  }));
  render(withTheme(<OperationsScreen />));
  const toggle = await screen.findByRole("button", { name: "Virtual systems in CLS-ROMEO-01" });
  expect(toggle).toHaveAttribute("aria-expanded", "false");
  fireEvent.click(toggle);
  const row = screen.getByRole("row", { name: "Virtual System VS-ROMEO-01-07" });
  expect(within(row).getByRole("button", { name: "Run pre-checks Virtual System VS-ROMEO-01-07" })).toBeInTheDocument();
  fireEvent.click(row);
  expect(await screen.findByLabelText("Check Point failover")).toBeInTheDocument();
  await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes("/units?memberDeviceId="))).toBe(true));
  // The estate-wide incident list (PO 2026-10-20) is read beside the summary; the summary itself stays on the cp prefix.
  expect(String(vi.mocked(fetch).mock.calls.find(([input]) => String(input).includes("cp-failover") && !String(input).endsWith("/incidents"))?.[0])).toBe("/api/v2/cp-failover/summary");
});

it.each([
  { scenario: "stored results", summary: READY_SUMMARY },
  { scenario: "masked stored results", summary: READY_SUMMARY.map(row => ({ ...row, masked: true })) },
  { scenario: "no summary yet", summary: [] },
])("opens the detail with $scenario as one drawer, closed by the X button", async ({ summary }) => {
  stubFetch(MEMBERS, summary);
  render(withTheme(<OperationsScreen />));
  const url = window.location.href;
  fireEvent.keyDown(await screen.findByRole("row", { name: "CLS-ROMEO-01" }), { key: "Enter" });
  const drawer = await screen.findByRole("dialog", { name: "HA readiness detail" });
  expect(drawer).toHaveClass("MuiDrawer-paperAnchorRight");
  expect(within(drawer).getByLabelText("Check Point failover")).toBeInTheDocument();
  if (summary.length) {
    expect(await within(drawer).findByLabelText("Readiness checks by consequence")).toBeVisible();
    expect(within(drawer).getAllByText(/^Ready$/)).toHaveLength(1);
  } else {
    expect(await within(drawer).findByText("No eligible failover units")).toBeVisible();
  }
  expect(screen.getByRole("table", { name: "HA clusters", hidden: true })).toBeInTheDocument();
  expect(window.location.href).toBe(url);
  fireEvent.click(within(drawer).getByRole("button", { name: "Close detail" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "HA readiness detail" })).toBeNull());
  expect(screen.getByRole("table", { name: "HA clusters" })).toBeInTheDocument();
});

it("a failed summary read says so with a Retry, shows every cluster as unknown, and recovers on Retry", async () => {
  stubFetch(MEMBERS, READY_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  let fail = true;
  vi.mocked(fetch).mockImplementation((input, init) => fail && String(input) === "/api/v2/cp-failover/summary"
    ? Promise.resolve(new Response("{}", { status: 503 })) : original(input, init));
  render(withTheme(<OperationsScreen />));
  await waitFor(() => expect(document.querySelector("[data-readiness-summary-failed]")).toBeInTheDocument());
  const notice = document.querySelector("[data-readiness-summary-failed]")!;
  expect(notice).toHaveTextContent("Readiness could not be read (Request failed (HTTP 503)). Statuses below are unknown, not bad.");
  expect(within(screen.getByRole("row", { name: "CLS-ROMEO-01" })).getByText("UNKNOWN")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /^Fail over/ })).toBeNull();
  fail = false;
  fireEvent.click(within(notice as HTMLElement).getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(document.querySelector("[data-readiness-summary-failed]")).toBeNull());
  expect(await screen.findByRole("button", { name: "Fail over CLS-ROMEO-01" })).toBeInTheDocument();
});

it("a failed incidents read is not an empty list: the Ready row is Unknown with the reason, no failover is offered, Retry recovers", async () => {
  stubFetch(MEMBERS, READY_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  let fail = true;
  vi.mocked(fetch).mockImplementation((input, init) => fail && String(input).endsWith("/incidents")
    ? Promise.resolve(new Response("{}", { status: 503 })) : original(input, init));
  render(withTheme(<OperationsScreen />));
  const notice = await waitFor(() => { const found = document.querySelector("[data-incidents-failed]"); expect(found).toBeInTheDocument(); return found as HTMLElement; });
  expect(notice).toHaveTextContent("Open incidents could not be read, so no cluster is shown as ready to fail over.");
  const row = screen.getByRole("row", { name: "CLS-ROMEO-01" });
  expect(row).toHaveAttribute("data-row-state", "UNKNOWN");
  expect(within(row).getByText("UNKNOWN")).toBeInTheDocument();
  expect(within(row).queryByText("Ready")).toBeNull();
  expect(within(row).getByText(/incidents could not be read/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /^Fail over/ })).toBeNull();
  fail = false;
  fireEvent.click(within(notice).getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(document.querySelector("[data-incidents-failed]")).toBeNull());
  expect(await screen.findByRole("button", { name: "Fail over CLS-ROMEO-01" })).toBeInTheDocument();
});

it("reads the failover incidents once for the screen, not once per panel", async () => {
  stubFetch(MEMBERS, READY_SUMMARY);
  render(withTheme(<OperationsScreen />));
  fireEvent.click(await screen.findByText("CLS-ROMEO-01"));
  await screen.findByLabelText("Check Point failover");
  await act(async () => {});
  const reads = vi.mocked(fetch).mock.calls.filter(([input]) => String(input).endsWith("/incidents"));
  expect(reads).toHaveLength(1);
});

const BULK_MEMBERS = [...MEMBERS, ...MEMBERS.map((member, index) => ({ ...member, device_id: `pan-member-${index}`, hostname: `FW-TANGO-02-M${index + 1}`, vendor_hint: "palo_alto", cluster_member_ref: "CLS-TANGO-02" }))];
const BULK_SUMMARY = [...READY_SUMMARY, { ...READY_SUMMARY[0], clusterId: "opaque-pan", unitId: "opaque-pan", cluster_member_ref: "CLS-TANGO-02", vendor: "palo_alto", members: BULK_MEMBERS.slice(2) }];
// hidden: the confirm dialog of "Re-check shown" marks the page aria-hidden while it closes.
const rowOf = (name: string) => screen.getByRole("row", { name, hidden: true });

it("draws no row until the summary has answered, so the segment it opens on never changes under the reader", async () => {
  stubFetch(BULK_MEMBERS, BULK_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  let finishSummary!: (response: Response) => void;
  const summary = new Promise<Response>(resolve => { finishSummary = resolve; });
  vi.mocked(fetch).mockImplementation((input, init) => String(input) === "/api/v2/cp-failover/summary"
    ? summary : original(input, init));
  render(withTheme(<OperationsScreen />));
  expect(await screen.findByText("Reading readiness and open incidents…")).toBeInTheDocument();
  // Before the summary every row would be Unknown and listed; once it lands the list would jump to another segment.
  expect(screen.queryByRole("row", { name: "CLS-ROMEO-01" })).toBeNull();
  expect(screen.queryByRole("group", { name: "Readiness segments" })).toBeNull();
  await act(async () => finishSummary(new Response(JSON.stringify([
    BULK_SUMMARY[0], { ...BULK_SUMMARY[1], readiness: { ...BULK_SUMMARY[1].readiness, status: "NOT_READY" } },
  ]))));
  // The list opens on "Can fail over now": the ready cluster is shown, the not-ready one is under Blocked.
  const row = await screen.findByRole("row", { name: "CLS-ROMEO-01" });
  expect(row).toHaveAttribute("data-readiness-row");
  expect(screen.queryByText("Reading readiness and open incidents…")).toBeNull();
  expect(screen.queryByRole("row", { name: "CLS-TANGO-02" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Blocked: 1 clusters" }));
  expect(screen.getByRole("row", { name: "CLS-TANGO-02" })).toBeInTheDocument();
});

const startBulk = async () => {
  await screen.findByText("2 clusters enrolled");
  await waitFor(() => expect(screen.getByTestId("toolbar-run-readiness")).toBeEnabled());
  fireEvent.click(screen.getByTestId("toolbar-run-readiness"));
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Re-check" }));
};

it("calls each shown unit's vendor readiness API sequentially and reports per-row progress", async () => {
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
  expect(await screen.findByText("Re-check shown (2)")).toBeInTheDocument();
  await startBulk();
  const postCalls = () => vi.mocked(fetch).mock.calls.filter(([input]) => String(input).endsWith("/readiness"));
  await waitFor(() => expect(postCalls()).toHaveLength(1));
  expect(String(postCalls()[0][0])).toBe("/api/v2/cp-failover/units/opaque-cluster/readiness");
  expect(postCalls()[0][1]).toMatchObject({ method: "POST", body: JSON.stringify({ clusterId: "opaque-cluster", unitId: "opaque-cluster" }) });
  expect(within(rowOf("CLS-ROMEO-01")).getByText("Running")).toBeInTheDocument();
  expect(within(rowOf("CLS-TANGO-02")).getByText("Queued")).toBeInTheDocument();
  expect(screen.getByTestId("toolbar-run-readiness")).toBeDisabled();
  await act(async () => finishFirst(new Response(JSON.stringify({ state: "DONE" }))));
  await waitFor(() => expect(postCalls()).toHaveLength(2));
  expect(String(postCalls()[1][0])).toBe("/api/v2/pan-failover/units/opaque-pan/readiness");
  expect(within(rowOf("CLS-ROMEO-01")).getByText("Completed")).toBeInTheDocument();
  expect(within(rowOf("CLS-TANGO-02")).getByText("Running")).toBeInTheDocument();
  await act(async () => finishSecond(new Response(JSON.stringify({ state: "DONE" }))));
  await waitFor(() => expect(screen.getAllByText("Completed")).toHaveLength(2));
  expect(screen.getByTestId("toolbar-run-readiness")).toBeEnabled();
});

it("stops a bulk re-check after a failed API call and reports the failed and unstarted rows, the error on its own row", async () => {
  stubFetch(BULK_MEMBERS, BULK_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input, init) => String(input).endsWith("/readiness")
    ? Promise.resolve(new Response("{}", { status: 503 })) : original(input, init));
  render(withTheme(<OperationsScreen />));
  await startBulk();
  await waitFor(() => expect(within(rowOf("CLS-TANGO-02")).getByText("Not run")).toBeInTheDocument());
  expect(within(rowOf("CLS-ROMEO-01")).getByText("Failed")).toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls.filter(([input]) => String(input).endsWith("/readiness"))).toHaveLength(1);
  const notice = within(rowOf("CLS-ROMEO-01")).getByText("Could not start or refresh the readiness run.");
  expect(notice).toHaveAttribute("role", "alert");
  expect(screen.getByTestId("toolbar-run-readiness")).toBeEnabled();
});

it("counts clusters once per segment, beside a KPI that counts the same clusters, without double counting virtual systems or unsupported vendors", async () => {
  const extra = ["CLS-BRAVO-03", "CLS-CHARLIE-04"].flatMap((ref, index) => MEMBERS.map((member, m) => ({ ...member, device_id: `extra-${index}-${m}`, hostname: `FW-${ref}-M${m + 1}`, cluster_member_ref: ref, vendor_hint: index ? "cisco_asa" : "check_point" })));
  stubFetch([...BULK_MEMBERS, ...extra], [READY_SUMMARY[0], { ...BULK_SUMMARY[1], readiness: { ...BULK_SUMMARY[1].readiness, status: "NOT_READY" } },
    { ...READY_SUMMARY[0], unitId: "opaque-vs", virtual_system: "VS-ROMEO-07" },
    { ...READY_SUMMARY[0], clusterId: "unsupported", unitId: "unsupported", cluster_member_ref: "CLS-CHARLIE-04" }]);
  render(withTheme(<OperationsScreen />));
  expect(await screen.findByText("1 not ready · 1 unknown")).toBeInTheDocument();
  const card = screen.getByText("Readiness checks", { selector: "p" }).closest("[data-kpi]") as HTMLElement;
  expect(card.querySelector("[data-kpi-figure]")).toHaveTextContent(/^1$/);
  expect(within(card).getByText("ready of 4 clusters")).toBeInTheDocument();
  for (const [name, n] of [["Can fail over now", 1], ["Blocked", 1], ["Unknown", 1], ["Readiness only", 0], ["All", 4]] as const)
    expect(screen.getByRole("button", { name: `${name}: ${n} clusters` })).toBeInTheDocument();
  expect(screen.queryByText("NOT EVALUATED")).toBeNull();
  expect(screen.queryByRole("button", { name: /Readiness records/ })).toBeNull();
});

it("lets aiview re-check when the server allows it, and locks the controls when it does not; no failover button either way", async () => {
  stubFetch(MEMBERS, READY_SUMMARY.map(unit => ({ ...unit, masked: true, canRunReadiness: true })));
  const first = render(withTheme(<OperationsScreen />));
  await screen.findByRole("row", { name: "CLS-ROMEO-01" });
  expect(screen.getByRole("button", { name: "Re-check CLS-ROMEO-01" })).toBeEnabled();
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Re-check shown (1)");
  expect(screen.queryByRole("button", { name: /^Fail over/ })).toBeNull();
  first.unmount();

  stubFetch(MEMBERS, READY_SUMMARY.map(unit => ({ ...unit, masked: true, canRunReadiness: false })));
  render(withTheme(<OperationsScreen />));
  await screen.findByRole("row", { name: "CLS-ROMEO-01" });
  expect(screen.queryByRole("button", { name: /^Re-check CLS/ })).toBeNull();
  expect(screen.getByTestId("toolbar-run-readiness")).toBeDisabled();
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Re-check shown · Role: not permitted");
  expect(screen.queryByRole("button", { name: /^Fail over/ })).toBeNull();
});

// Argon review 2026-10-20 P1-2: the bulk readiness run asks the business-hours question once for its own requests.
it("a bulk re-check asks once inside business hours and acknowledges only its own requests", async () => {
  stubFetch(BULK_MEMBERS, BULK_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input, init) => {
    const url = String(input);
    if (!url.endsWith("/readiness")) return original(input, init);
    const ack = (init?.headers as Record<string, string> | undefined)?.["X-Nexus-Business-Hours-Ack"];
    if (ack !== "true") return Promise.resolve(new Response(JSON.stringify({ code: "BUSINESS_HOURS_CONFIRM_REQUIRED",
      message: "Business hours (08:00–20:00 GMT+3). This sends commands to the device. Continue?" }), { status: 409 }));
    return Promise.resolve(new Response(JSON.stringify({ runId: "run-ready" })));
  });
  const ask = vi.fn(() => Promise.resolve(true));
  const unregister = setBusinessHoursConfirm(ask);
  try {
    render(withTheme(<OperationsScreen />));
    await startBulk();
    await waitFor(() => expect(screen.getAllByText("Completed")).toHaveLength(2));
    expect(ask).toHaveBeenCalledTimes(1);
    const acks = vi.mocked(fetch).mock.calls.filter(([input]) => String(input).endsWith("/readiness"))
      .map(([, init]) => (init?.headers as Record<string, string> | undefined)?.["X-Nexus-Business-Hours-Ack"] ?? null);
    expect(acks).toEqual([null, "true", null, "true"]);
  } finally {
    unregister();
  }
});

it("declining the business-hours question is a neutral notice on that row, not a red error", async () => {
  stubFetch(MEMBERS, READY_SUMMARY);
  const original = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input, init) => String(input).endsWith("/readiness")
    ? Promise.resolve(new Response(JSON.stringify({ code: "BUSINESS_HOURS_CONFIRM_REQUIRED", message: "Business hours (08:00–20:00 GMT+3)." }), { status: 409 }))
    : original(input, init));
  const unregister = setBusinessHoursConfirm(() => Promise.resolve(false));
  try {
    render(withTheme(<OperationsScreen />));
    fireEvent.click(await screen.findByRole("button", { name: "Re-check CLS-ROMEO-01" }));
    await waitFor(() => expect(document.querySelector("[data-run-notice]")).toBeInTheDocument());
    const notice = document.querySelector("[data-run-notice]")!;
    expect(notice).toHaveAttribute("data-run-notice", "neutral");
    expect(notice.closest("tr")).toHaveAttribute("aria-label", "CLS-ROMEO-01");
    expect(notice).not.toHaveAttribute("role", "alert");
  } finally {
    unregister();
  }
});
