import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JobLogsPanel, JOB_PILLS, pillCount, pillForState, resultWords } from "../src/screens/JobLogsPanel";
import { operationsHeadline, tabFromUrl } from "../src/screens/OperationsScreen";
import { jobOutcomeWord, outcomeEchoesState } from "../src/shell/presenters";

afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

const BY_STATE = { COMPLETED: 870, FAILED: 27, EXECUTING: 1, REQUESTED: 2, OUTCOME_UNKNOWN: 3 };

function stub(items: unknown[], byState: Record<string, number> | null = BY_STATE) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
    if (url.startsWith("/api/v2/jobs/stats")) return byState ? json({ total: 903, total_24h: 879, completed_24h: 852, failed_24h: 27, running: 3, by_state: byState }) : json({}, 500);
    if (url.startsWith("/api/v2/jobs/facets")) return json({ states: ["COMPLETED", "FAILED"], job_types: ["cp_failover_readiness"] });
    if (url.startsWith("/api/v2/jobs")) {
      const q = new URL(url, "http://x").searchParams.get("q");
      return json({ items: q ? items.filter((j) => (j as { job_id: string }).job_id.includes(q)) : items, page: 1, page_size: 50, total: items.length });
    }
    if (url === "/devices") return json({ devices: [{ device_id: "opaque-001", hostname: "FW-TANGO-04", vendor_hint: "check_point" }] });
    return json({}, 404);
  }));
  return calls;
}

const JOBS = [
  { job_id: "job-ready", target_device_id: "opaque-001", job_type: "cp_failover_readiness", state: "COMPLETED", outcome: "NOT_READY", submitted_at: "2026-10-18T03:57:00Z", has_transcript: false },
  { job_id: "job-logs", target_device_id: "opaque-001", job_type: "cp_system_log_collect", state: "COMPLETED", outcome: "PARTIAL", submitted_at: "2026-10-18T03:05:00Z", has_transcript: false },
  { job_id: "job-plain", target_device_id: "opaque-001", job_type: "cp_inventory_collect", state: "COMPLETED", outcome: "SUCCESS", submitted_at: "2026-10-18T03:00:00Z", has_transcript: false },
  { job_id: "job-failed", target_device_id: "opaque-001", job_type: "cp_gateway_backup", state: "FAILED", outcome: "FAILURE", terminal_reason: "COLLECTION_FAILED", submitted_at: "2026-10-18T02:00:00Z", has_transcript: false },
];

describe("Jobs state pills (roadmap 2.4)", () => {
  it("defines All, Queued / Running, Finished, Failed and Other outcome, each a state set the server already accepts", () => {
    expect(JOB_PILLS.map((p) => p.id)).toEqual(["all", "active", "finished", "failed", "other"]);
    expect(pillForState("REQUESTED,CLAIMED,EXECUTING")).toBe("active");
    expect(pillForState("FAILED")).toBe("failed");
    expect(pillForState("")).toBe("all");
    expect(pillForState("COMPLETED,FAILED")).toBeNull();
  });

  it("counts every pill from the all-time per-state totals, and shows no number before they are read", () => {
    expect(pillCount(BY_STATE, "all")).toBe(903);
    expect(pillCount(BY_STATE, "active")).toBe(3);
    expect(pillCount(BY_STATE, "finished")).toBe(870);
    expect(pillCount(BY_STATE, "failed")).toBe(27);
    expect(pillCount(BY_STATE, "other")).toBe(3);
    expect(pillCount(null, "failed")).toBeNull();
  });

  it("renders the pills with counts, presses the one matching the filter, and filters the table through the server", async () => {
    const calls = stub(JOBS);
    render(<JobLogsPanel />);
    const pills = await screen.findByRole("group", { name: "Job state" });
    await waitFor(() => expect(within(pills).getByRole("button", { name: "Failed · 27" })).toBeInTheDocument());
    expect(within(pills).getByRole("button", { name: "All · 903" })).toHaveAttribute("aria-pressed", "true");
    expect(within(pills).getByRole("button", { name: "Queued / Running · 3" })).toBeInTheDocument();
    expect(within(pills).getByRole("button", { name: "Other outcome · 3" })).toBeInTheDocument();
    fireEvent.click(within(pills).getByRole("button", { name: "Failed · 27" }));
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?state=FAILED&page=1&page_size=50")).toBe(true));
    expect(within(pills).getByRole("button", { name: "Failed · 27" })).toHaveAttribute("aria-pressed", "true");
    // The State dropdown still names the same filter: the pills are presets of it, not a second filter.
    expect(screen.getByLabelText("State")).toHaveValue("FAILED");
    fireEvent.click(within(pills).getByRole("button", { name: "Queued / Running · 3" }));
    await waitFor(() => expect(calls.some((c) => c.includes("state=REQUESTED%2CCLAIMED%2CEXECUTING"))).toBe(true));
  });

  it("writes UNKNOWN, never 0, on every pill when the totals could not be read", async () => {
    stub(JOBS, null);
    render(<JobLogsPanel />);
    const pills = await screen.findByRole("group", { name: "Job state" });
    await waitFor(() => expect(within(pills).getByRole("button", { name: "Failed · UNKNOWN" })).toBeInTheDocument());
    expect(within(pills).queryByRole("button", { name: /· 0$/ })).toBeNull();
  });

  it("honours a pill the owner asks for after mount (the Failed card, an old route)", async () => {
    const calls = stub(JOBS);
    const { rerender } = render(<JobLogsPanel pillRequest={{ pill: "active", seq: 0 }} />);
    await waitFor(() => expect(calls.some((c) => c.includes("state=REQUESTED%2CCLAIMED%2CEXECUTING"))).toBe(true));
    rerender(<JobLogsPanel pillRequest={{ pill: "failed", seq: 1 }} />);
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?state=FAILED&page=1&page_size=50")).toBe(true));
  });
});

describe("RESULT words and the transcript column (P1-4B)", () => {
  it("turns a recorded outcome into a word, drops one that only repeats the state, and keeps the raw code reachable", () => {
    expect(jobOutcomeWord("NOT_READY")).toBe("Not ready");
    expect(jobOutcomeWord("PARTIAL")).toBe("Partial");
    expect(jobOutcomeWord("")).toBeNull();
    expect(jobOutcomeWord("SOMETHING_NEW")).toBe("SOMETHING_NEW");
    expect(outcomeEchoesState("SUCCESS", "COMPLETED")).toBe(true);
    expect(outcomeEchoesState("NOT_READY", "COMPLETED")).toBe(false);
    expect(resultWords({ state: "COMPLETED", outcome: "NOT_READY", terminal_reason: "COMPLETED" })).toEqual({ outcome: "Not ready", reason: null });
    expect(resultWords({ state: "COMPLETED", outcome: "SUCCESS" })).toEqual({ outcome: null, reason: null });
    expect(resultWords({ state: "FAILED", outcome: "FAILURE", terminal_reason: "COLLECTION_FAILED" })).toEqual({ outcome: null, reason: "COLLECTION_FAILED" });
  });

  it("shows the outcome word for readiness and log-collect rows, a dash for a clean run, and hides the empty Transcript column", async () => {
    stub(JOBS);
    render(<JobLogsPanel />);
    const table = await screen.findByRole("table", { name: "Job logs" });
    const cells = within(table).getAllByRole("cell").filter((c) => c.hasAttribute("data-result")).map((c) => c.textContent);
    // the raw reason code is on hover, not in the text (post-release review 2026-10-18, R8)
    expect(cells).toEqual(["Not ready", "Partial", "—", "Collection failed"]);
    expect(within(table).queryByRole("columnheader", { name: "Transcript" })).toBeNull();
    expect(within(table).getByRole("columnheader", { name: "Result" })).toBeInTheDocument();
  });

  it("keeps the Transcript column as soon as one row on the page has a transcript", async () => {
    stub([{ ...JOBS[3], has_transcript: true }]);
    render(<JobLogsPanel />);
    const table = await screen.findByRole("table", { name: "Job logs" });
    expect(within(table).getByRole("columnheader", { name: "Transcript" })).toBeInTheDocument();
    expect(within(table).getByRole("button", { name: "Transcript" })).toBeInTheDocument();
    expect(within(table).getByRole("button", { name: "Download" })).toBeInTheDocument();
  });

  it("downloads that row's transcript as a text file named for its job type, time and id (PO 2026-10-25)", async () => {
    const calls = stub([{ ...JOBS[3], has_transcript: true }]);
    vi.mocked(fetch).mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      calls.push(url);
      if (url === "/jobs/job-failed/transcript") return Promise.resolve(new Response(JSON.stringify([{ seq: 1, at: "2026-10-18T02:00:05Z", elapsedMs: 5, channel: "ssh", kind: "response", text: "synthetic output" }])));
      if (url.startsWith("/api/v2/jobs/stats")) return Promise.resolve(new Response(JSON.stringify({ total: 1, total_24h: 1, completed_24h: 0, failed_24h: 1, running: 0, by_state: BY_STATE })));
      if (url.startsWith("/api/v2/jobs/facets")) return Promise.resolve(new Response(JSON.stringify({ states: ["FAILED"], job_types: ["cp_gateway_backup"] })));
      if (url.startsWith("/api/v2/jobs")) return Promise.resolve(new Response(JSON.stringify({ items: [{ ...JOBS[3], has_transcript: true }], page: 1, page_size: 50, total: 1 })));
      return Promise.resolve(new Response("{}", { status: 404 }));
    });
    const names: string[] = [];
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:transcript") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { names.push(this.download); });
    render(<JobLogsPanel />);
    const table = await screen.findByRole("table", { name: "Job logs" });
    fireEvent.click(within(table).getByRole("button", { name: "Download" }));
    // 02:00Z on 2026-10-18 is 05:00 in Turkey.
    await waitFor(() => expect(names).toEqual(["cp-gateway-backup-20261018-0500-job-fail.txt"]));
    expect(calls).toContain("/jobs/job-failed/transcript");
    // The click stays on the download: the row did not expand into its detail.
    expect(table.querySelector("[data-job-detail]")).toBeNull();
    vi.restoreAllMocks();
  });
});

describe("addressable job detail (roadmap 3.6)", () => {
  it("opens the job named by ?job= on load, fetched by its id", async () => {
    window.history.replaceState(null, "", "/?screen=operations&tab=jobs&job=job-failed");
    const calls = stub(JOBS);
    render(<JobLogsPanel />);
    expect(await screen.findByText("job-failed")).toBeInTheDocument();
    expect(screen.getByRole("row", { expanded: true })).toHaveAttribute("data-job-id", "job-failed");
    expect(calls.some((c) => c.includes("q=job-failed"))).toBe(true);
  });

  it("writes ?job= when a row opens, removes it when it closes, and closes on the browser's Back", async () => {
    stub(JOBS);
    render(<JobLogsPanel />);
    const table = await screen.findByRole("table", { name: "Job logs" });
    fireEvent.click(within(table).getAllByRole("row")[1]);
    expect(new URLSearchParams(window.location.search).get("job")).toBe("job-ready");
    expect(screen.getByRole("button", { name: "Copy link to this job" })).toBeInTheDocument();
    act(() => { window.history.back(); });
    await act(async () => { window.dispatchEvent(new PopStateEvent("popstate")); });
    await waitFor(() => expect(screen.queryByRole("row", { expanded: true })).toBeNull());
    fireEvent.click(within(table).getAllByRole("row")[1]);
    fireEvent.click(within(table).getAllByRole("row")[1]);
    expect(new URLSearchParams(window.location.search).get("job")).toBeNull();
  });
});

describe("Operations routes and headline", () => {
  it("keeps the old Queue and History routes as deep links into Jobs with the matching pill", () => {
    expect(tabFromUrl("queue")).toEqual({ tab: 1, pill: "active" });
    expect(tabFromUrl("history")).toEqual({ tab: 1, pill: "finished" });
    expect(tabFromUrl("jobs")).toEqual({ tab: 1, pill: null });
    expect(tabFromUrl("diagnostics")).toEqual({ tab: 2, pill: null });
    expect(tabFromUrl("health")).toEqual({ tab: 3, pill: null });
    expect(tabFromUrl("changes")).toEqual({ tab: 4, pill: null });
    expect(tabFromUrl(null)).toEqual({ tab: 0, pill: null });
  });

  it("writes the headline as number · noun · denominator · age, UNKNOWN where a read failed", () => {
    expect(operationsHeadline({ stats: { failed24h: 27, total24h: 879 }, clusters: 45, ready: 22, unknown: 13, window: { open: false, label: "Next window: 12:00" } }))
      .toBe("27 failed · 879 finished (24 h) · 22 of 45 clusters ready · 13 unknown · next window 12:00");
    expect(operationsHeadline({ stats: "failed", clusters: null, ready: 0, unknown: 0, window: { open: true, label: null } }))
      .toBe("failed jobs UNKNOWN · finished jobs UNKNOWN · job window open now");
  });
});
