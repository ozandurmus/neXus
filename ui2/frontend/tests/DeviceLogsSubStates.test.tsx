import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { JobWindowContext } from "../src/shell/JobWindow";
import { DeviceLogsPanel, classifyCollection, type LatestCollection } from "../src/screens/DeviceLogsPanel";
import type { SystemLogSource, SystemLogSources } from "../src/auth/adminApi";

/**
 * The Logs tab's three empty sub-states (UI review 2026-10-18, F-6): no collection yet, collected with 0 lines, and
 * collection failed with its reason; plus the case the review found: a completed collection job on record while the tab
 * said "No collection yet".
 */
const DEVICE = "dev-1";
const BASE = "/api/v2/system-logs";
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
afterEach(() => vi.unstubAllGlobals());

const source = (key: string, over: Partial<SystemLogSource> = {}, lines = 0): SystemLogSource => ({
  source_key: key, status: "OK", last_outcome: "NO_NEW_DATA", last_run_at: "2026-10-18T03:05:20Z", last_job_id: "job-1", last_bytes_read: 0, last_bytes_stored: 0,
  pending_bytes: 0, fast_rotating: false, retention_horizon_seconds: null, cursor: null,
  stored: { chunks: lines > 0 ? 1 : 0, bytes: lines * 10, lines, newest_chunk_at: null }, ...over,
});
const sources = (over: Partial<SystemLogSources> = {}): SystemLogSources => ({
  device_id: DEVICE, collection_state: "COLLECTED", can_view_unmasked: false, default_page_bytes: 65536, max_page_bytes: 262144,
  last_job: { job_id: "job-1", at: "2026-10-18T03:05:20Z", outcomes: { NO_NEW_DATA: 2 }, window_from: null, window_to: null, window_time_basis: "DEVICE_LOCAL" },
  sources: [source("cp_messages"), source("cp_fwd_elg")], ...over,
});
const emptyState = (): SystemLogSources => sources({ collection_state: "NO_COLLECTION_YET", last_job: null, sources: [] });
const job = (state: string, reason?: string) => ({ job_id: "job-9", job_type: "cp_system_log_collect", target_device_id: DEVICE, submitted_at: "2026-10-18T03:05:20Z", finished_at: "2026-10-18T03:05:23Z", state, has_transcript: false, terminal_reason: reason });

function stub(summary: SystemLogSources, jobs: object[], jobsStatus = 200) {
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    if (url === `${BASE}/${DEVICE}/sources`) return json(summary);
    if (url.startsWith("/api/v2/jobs")) return jobsStatus === 200 ? json({ items: jobs, total: jobs.length, page: 1, page_size: 1 }) : json({}, jobsStatus);
    if (url.startsWith(`${BASE}/${DEVICE}/chunks`)) return json({ device_id: DEVICE, source: "cp_messages", day: null, truncated: false, chunks: [] });
    if (url.startsWith(`${BASE}/gaps`)) return json({ device_id: DEVICE, truncated: false, gaps: [] });
    if (url.startsWith(`${BASE}/crash-files`)) return json({ device_id: DEVICE, new_window_days: 7, counts: { NEW: 0, PRESENT: 0, PRESENT_BEFORE_MONITORING: 0, REMOVED: 0 }, truncated: false, files: [] });
    if (url.startsWith(`${BASE}/${DEVICE}/hcp`)) return json({ device_id: DEVICE, files: [] });
    return json({}, 404);
  }));
}
const view = (label: string | null = null) => render(
  <ThemeProvider theme={m3Theme}>
    <JobWindowContext.Provider value={{ open: label === null, label }}><DeviceLogsPanel deviceId={DEVICE} vendorHint="check_point" /></JobWindowContext.Provider>
  </ThemeProvider>);

describe("classifyCollection", () => {
  const latest = (state: string, reason: string | null = null): LatestCollection => ({ state, at: "2026-10-18T03:05:20Z", reason });

  it("is 'none' only when no run and no collection job is on record", () => {
    expect(classifyCollection(emptyState(), null)).toEqual({ kind: "none" });
    expect(classifyCollection(emptyState(), latest("EXECUTING"))).toEqual({ kind: "none" });
  });

  it("never says 'no collection yet' for a completed job: it is collected with nothing recorded", () => {
    expect(classifyCollection(emptyState(), latest("COMPLETED"))).toEqual({ kind: "zero", unrecorded: true });
  });

  it("names the reason of a failed job, without the partial: echo", () => {
    expect(classifyCollection(emptyState(), latest("FAILED", "partial:CONNECTION_FAILED"))).toEqual({ kind: "failed", reason: "CONNECTION_FAILED" });
    expect(classifyCollection(emptyState(), latest("REJECTED", null))).toEqual({ kind: "failed", reason: "rejected" });
  });

  it("is collected with 0 lines when runs are recorded and nothing is stored", () => {
    expect(classifyCollection(sources(), latest("COMPLETED"))).toEqual({ kind: "zero", unrecorded: false });
    expect(classifyCollection(sources(), null)).toEqual({ kind: "zero", unrecorded: false });
  });

  it("is a failure when every source ended UNKNOWN and nothing is stored", () => {
    const failed = sources({ sources: [source("cp_messages", { status: "UNKNOWN" }), source("cp_fwd_elg", { status: "UNKNOWN" })] });
    expect(classifyCollection(failed, latest("FAILED", "partial:CONNECTION_FAILED"))).toEqual({ kind: "failed", reason: "CONNECTION_FAILED" });
    expect(classifyCollection(failed, null)).toEqual({ kind: "failed", reason: "no source could be read" });
  });

  it("has lines to show as soon as anything is stored, whatever else went wrong", () => {
    const stored = sources({ sources: [source("cp_messages", { status: "UNKNOWN" }, 12), source("cp_fwd_elg", { status: "UNKNOWN" })] });
    expect(classifyCollection(stored, latest("FAILED", "partial:X"))).toEqual({ kind: "stored" });
  });
});

describe("the Logs tab renders each sub-state", () => {
  it("no collection yet, with the next window in the title", async () => {
    stub(emptyState(), []);
    view("Next window: 12:00");
    expect(await screen.findByText("No collection yet · next window 12:00")).toBeInTheDocument();
    expect(screen.getByText(/shared job windows/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeInTheDocument();
  });

  it("no collection yet, plainly, while the window is open", async () => {
    stub(emptyState(), []);
    view(null);
    expect(await screen.findByText("No collection yet")).toBeInTheDocument();
  });

  it("collected with 0 lines, naming the run", async () => {
    stub(sources(), [job("COMPLETED", "SYSTEM_LOG_COLLECTED")]);
    view();
    expect(await screen.findByText("Collected · 0 lines")).toBeInTheDocument();
    expect(screen.getByText(/the device had no new log lines to read/)).toBeInTheDocument();
    expect(screen.queryByText("No collection yet")).toBeNull();
    // the run's own facts stay: the summary strip and the side panels
    expect(screen.getByRole("region", { name: "Log summary" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Collection health" }));
    expect(await screen.findByText("Gaps")).toBeInTheDocument();
  });

  it("collection failed, with its reason", async () => {
    stub(emptyState(), [job("FAILED", "partial:CONNECTION_FAILED")]);
    view();
    expect(await screen.findByText("Collection failed · CONNECTION_FAILED")).toBeInTheDocument();
    // a finding about the device, not a failed screen: a status, so the read-only E2E safety check does not read it as a load error
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("status")).toHaveTextContent("Collection failed");
  });

  it("a completed job with no stored record is collected, not 'no collection yet'", async () => {
    stub(emptyState(), [job("COMPLETED", "SYSTEM_LOG_COLLECTED")]);
    view();
    expect(await screen.findByText("Collected · 0 lines")).toBeInTheDocument();
    expect(screen.getByText(/left no source record under this device/)).toBeInTheDocument();
    expect(screen.queryByText(/No collection yet/)).toBeNull();
  });

  it("an unreadable Jobs list is unknown, not an error banner: the tab falls back to no collection yet", async () => {
    stub(emptyState(), [], 500);
    view();
    expect(await screen.findByText("No collection yet")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("asks the Jobs list about this device and the vendor's own collection job", async () => {
    stub(emptyState(), []);
    view();
    await screen.findByText("No collection yet");
    const urls = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map(([url]) => String(url)).filter((url) => url.startsWith("/api/v2/jobs"));
    expect(urls).toHaveLength(1);
    expect(urls[0]).toContain(`device_id=${DEVICE}`);
    expect(urls[0]).toContain("job_type=cp_system_log_collect");
  });

  it("Refresh reads both again", async () => {
    stub(emptyState(), []);
    view();
    fireEvent.click(await screen.findByRole("button", { name: "Refresh" }));
    await waitFor(() => expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.filter(([url]) => String(url).startsWith("/api/v2/jobs"))).toHaveLength(2));
  });
});
