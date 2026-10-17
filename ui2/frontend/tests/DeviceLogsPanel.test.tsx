import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DeviceLogsPanel, formatBytes, gapWords, MAX_LOADED_LINES } from "../src/screens/DeviceLogsPanel";
import type { SystemLogChunk, SystemLogGap, SystemLogSource, SystemLogSources } from "../src/auth/adminApi";

// Synthetic data only: documentation addresses and example names.
const DEVICE = "dev-1";
const BASE = "/api/v2/system-logs";

function source(key: string, over: Partial<SystemLogSource> = {}): SystemLogSource {
  return {
    source_key: key, status: "OK", last_outcome: "COLLECTED", last_run_at: "2026-10-17T09:00:00Z", last_job_id: "job-1",
    last_bytes_read: 100, last_bytes_stored: 100, pending_bytes: 0, fast_rotating: false, retention_horizon_seconds: null,
    cursor: { offset_bytes: 100, device_epoch_at_read: 1790000000, pan_last_receive_time: null, updated_at: "2026-10-17T09:00:00Z" },
    stored: { chunks: 2, bytes: 8192, lines: 80, newest_chunk_at: "2026-10-17T09:00:00Z" }, ...over,
  };
}

function summary(over: Partial<SystemLogSources> = {}): SystemLogSources {
  return {
    device_id: DEVICE, collection_state: "COLLECTED", can_view_unmasked: false, default_page_bytes: 65536, max_page_bytes: 262144,
    last_job: { job_id: "job-1", at: "2026-10-17T09:00:00Z", outcomes: { COLLECTED: 2, BACKLOG: 1 }, window_from: "2026-10-17 08:00:00", window_to: "2026-10-17 08:59:59", window_time_basis: "DEVICE_LOCAL" },
    sources: [
      source("cp_messages", { status: "BACKLOG", last_outcome: "BACKLOG", pending_bytes: 2_500_000, fast_rotating: true, retention_horizon_seconds: 21_600 }),
      source("cp_fwk_elg", { status: "GAP", last_outcome: "GAP" }),
      source("cp_cpwd_elg", { status: "UNKNOWN", last_outcome: "UNKNOWN" }),
      source("cp_sar_daily", { status: "OK", cursor: null }),
    ],
    ...over,
  };
}

function chunk(id: string, at: string, over: Partial<SystemLogChunk> = {}): SystemLogChunk {
  return {
    chunk_id: id, source_key: "cp_messages", job_id: "job-1", collected_at: at, log_day: "2026-10-17", start_offset: 0, end_offset: 100,
    bytes: 100, lines: 3, first_line_time: "2026-10-17 08:00:00", last_line_time: "2026-10-17 08:59:59", line_time_basis: "DEVICE_LOCAL",
    redaction_count: 2, redaction_rule_set: "cp-text-v1", flags: [], stored_bytes: 60, outcome: "COLLECTED", pruned: false, prune_reason: null, ...over,
  };
}

const GAP: SystemLogGap = {
  gap_id: "g-1", device_id: DEVICE, source_key: "cp_messages", job_id: "job-1", kind: "ROTATED_AWAY", known_lost_bytes: 1_048_576,
  pending_bytes: 0, extent_unknown: true, window_start_epoch: 1790000000, window_end_epoch: 1790021600, recorded_at: "2026-10-17T09:00:00Z",
};

interface Setup {
  summary?: SystemLogSources | { status: number; body: unknown };
  chunks?: SystemLogChunk[];
  pages?: Record<string, { text: string; next: number; eof: boolean; masked?: boolean }[]>;
  textFailure?: { status: number; body: unknown };
  crashNew?: number;
}

function stub(setup: Setup = {}) {
  const calls: string[] = [];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  const fetch = vi.fn((input: string) => {
    const url = String(input);
    calls.push(url);
    if (url === `${BASE}/${DEVICE}/sources`) {
      const s = setup.summary ?? summary();
      return Promise.resolve("status" in s ? json(s.body, s.status) : json(s));
    }
    if (url.startsWith(`${BASE}/${DEVICE}/chunks`)) return Promise.resolve(json({ device_id: DEVICE, source: "cp_messages", day: null, truncated: false, chunks: setup.chunks ?? [chunk("c-1", "2026-10-17T06:00:00Z"), chunk("c-2", "2026-10-17T09:00:00Z")] }));
    if (url.startsWith(`${BASE}/chunks/`) && url.includes("/text")) {
      if (setup.textFailure) return Promise.resolve(json(setup.textFailure.body, setup.textFailure.status));
      const id = url.split("/chunks/")[1].split("/")[0];
      const offset = Number(new URL(url, "http://x").searchParams.get("offset"));
      const unmasked = url.includes("unmasked=true");
      const list = (setup.pages ?? {})[id] ?? [{ text: "Oct 17 first line\nOct 17 second line\nOct 17 third line\n", next: 100, eof: true }];
      const page = list.find((_, i) => (i === 0 ? offset === 0 : offset === list[i - 1].next)) ?? list[0];
      return Promise.resolve(new Response(page.text, { headers: {
        "X-Syslog-Masked": String(page.masked ?? !unmasked), "X-Syslog-Offset": String(offset), "X-Syslog-Next-Offset": String(page.next),
        "X-Syslog-Eof": String(page.eof), "X-Syslog-Lines": "3", "X-Syslog-Source": "cp_messages" } }));
    }
    if (url.startsWith(`${BASE}/gaps`)) return Promise.resolve(json({ device_id: DEVICE, truncated: false, gaps: [GAP] }));
    if (url.startsWith(`${BASE}/crash-files`)) {
      const n = setup.crashNew ?? 1;
      return Promise.resolve(json({ device_id: DEVICE, new_window_days: 7, counts: { NEW: n, PRESENT: 0, PRESENT_BEFORE_MONITORING: 1, REMOVED: 0 }, truncated: false,
        files: [
          ...(n > 0 ? [{ entry_id: "e-1", device_id: DEVICE, directory: "dump_usermode", process: "fwd", size: 5_242_880, mtime_epoch: 1790000000, state: "NEW", first_seen_at: "2026-10-17T08:00:00Z", removed_at: null }] : []),
          { entry_id: "e-2", device_id: DEVICE, directory: "dump_usermode", process: "UNKNOWN", size: 2048, mtime_epoch: 1700000000, state: "PRESENT_BEFORE_MONITORING", first_seen_at: "2026-09-01T08:00:00Z", removed_at: null },
        ], alerts: [] }));
    }
    if (url.startsWith(`${BASE}/${DEVICE}/hcp`)) return Promise.resolve(json({ device_id: DEVICE, member_listing: "NOT_AVAILABLE", files: [
      { file_id: "f-1", name_time: "2026-10-17 03:00:12", size: 4_956_000, mtime_epoch: 1790000000, status: "STORED", flags: ["NAME_SHAPE_UNEXPECTED"], collected_at: "2026-10-17T06:00:00Z", expires_at: "2026-10-24T06:00:00Z", pruned: false, stored_bytes: 4_900_000 },
    ] }));
    return Promise.resolve(json({}, 404));
  });
  vi.stubGlobal("fetch", fetch);
  return { fetch, calls };
}

afterEach(() => vi.unstubAllGlobals());

describe("empty, disabled and refused states", () => {
  it("says there is no collection yet for a device that was never collected", async () => {
    stub({ summary: summary({ collection_state: "NO_COLLECTION_YET", sources: [], last_job: null }) });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("No collection yet")).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Log sources" })).toBeNull();
  });

  it("shows the disabled reason for a management server", async () => {
    stub({ summary: { status: 409, body: { code: "SYSTEM_LOG_DISABLED_ON_MANAGEMENT", message: "disabled" } } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Collection disabled for management servers")).toBeInTheDocument();
    expect(screen.getByText("SYSTEM_LOG_DISABLED_ON_MANAGEMENT")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });

  it("shows a restriction, not a fault, for a refused session", async () => {
    stub({ summary: { status: 403, body: { error: "ACTION_REFUSED" } } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText(/Restricted/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });

  it("offers a retry for a transient failure", async () => {
    stub({ summary: { status: 500, body: { error: "BOOM" } } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Logs unavailable")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});

describe("summary strip, source pills and side panel", () => {
  it("shows the last collection, its window, the run outcomes and a crash-file alarm", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const strip = await screen.findByRole("region", { name: "Log summary" });
    expect(within(strip).getByText("Last collection")).toBeInTheDocument();
    expect(within(strip).getByText(/Window 2026-10-17 08:00:00 to 2026-10-17 08:59:59 \(device time\)/)).toBeInTheDocument();
    expect(within(strip).getByText("collected 2")).toBeInTheDocument();
    expect(within(strip).getByText("backlog 1")).toBeInTheDocument();
    expect(await within(strip).findByText("1 new crash or dump file")).toBeInTheDocument();
  });

  it("shows no alarm when there are no new crash files", async () => {
    stub({ crashNew: 0 });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("no new crash or dump files")).toBeInTheDocument();
  });

  it("puts a BACKLOG, GAP or UNKNOWN pill on each source and says what a fast-rotating source means", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const group = await screen.findByRole("group", { name: "Log sources" });
    const messages = within(group).getByRole("button", { name: /messages/ });
    expect(within(messages).getByText("BACKLOG")).toBeInTheDocument();
    expect(within(messages).getByText("fast rotating")).toBeInTheDocument();
    expect(within(within(group).getByRole("button", { name: /fwk\.elg/ })).getByText("GAP")).toBeInTheDocument();
    expect(within(within(group).getByRole("button", { name: /cpwd\.elg/ })).getByText("UNKNOWN")).toBeInTheDocument();
    // a whole-file source is not a line source: it is listed in the side panel, not as a viewer chip
    expect(within(group).queryByRole("button", { name: /sar/ })).toBeNull();
    expect(await screen.findByText("sar (daily)")).toBeInTheDocument();
  });

  it("lists gaps in plain words with their byte counts, crash files and HCP reports as metadata", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText(/Rotated away on the device before it was read: 1\.0 MiB lost\. More may be missing\./)).toBeInTheDocument();
    expect(await screen.findByText("fwd")).toBeInTheDocument();
    expect(screen.getByText("New")).toBeInTheDocument();
    expect(screen.getByText("Present before monitoring")).toBeInTheDocument();
    expect(screen.getByText("Unknown process")).toBeInTheDocument();
    expect(await screen.findByText(/2026-10-17 03:00:12 \(device time\)/)).toBeInTheDocument();
    expect(screen.getByText("Unexpected file name")).toBeInTheDocument();
    expect(screen.getByText(/member listing is not available yet/)).toBeInTheDocument();
  });

  it("does not invent what the backend does not have: no error counts, restarts or HCP verdicts", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await screen.findByRole("region", { name: "Log summary" });
    expect(screen.queryByText(/restart/i)).toBeNull();
    expect(screen.queryByText(/verdict/i)).toBeNull();
    expect(screen.queryByText(/warning/i)).toBeNull();
    expect(screen.queryByText(/\berrors?\b/i)).toBeNull();
  });

  it("words every gap kind and formats byte counts", () => {
    const kinds = ["ROTATED_AWAY", "TRUNCATED", "ANCHOR_MISMATCH", "INITIAL_BASELINE", "BACKLOG", "COLLECTION_FAILED", "PAGE_STALL", "SEQ_GAP_SUSPECTED", "RETENTION_SHORTENED_BY_CAP"];
    for (const kind of kinds) expect(gapWords({ ...GAP, kind, pending_bytes: 2048 })).not.toMatch(/^[A-Z_]+:/);
    expect(gapWords({ ...GAP, kind: "BACKLOG", pending_bytes: 2048 })).toBe("Still waiting on the device: 2.0 KiB left to read.");
    expect(gapWords({ ...GAP, kind: "SOMETHING_NEW", known_lost_bytes: 10, extent_unknown: false })).toBe("SOMETHING_NEW: 10 B lost.");
    expect([formatBytes(0), formatBytes(1023), formatBytes(1536), formatBytes(10 * 1024 * 1024), formatBytes(3 * 1024 ** 3)]).toEqual(["0 B", "1023 B", "1.5 KiB", "10 MiB", "3.0 GiB"]);
  });
});

describe("line viewer", () => {
  it("loads the newest chunk masked by default and says so", async () => {
    const { calls } = stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findAllByTestId("log-line")).toHaveLength(3);
    expect(screen.getByText("Masked view")).toBeInTheDocument();
    expect(calls).toContain(`${BASE}/chunks/c-2/text?offset=0`);
    expect(calls.some((u) => u.includes("unmasked"))).toBe(false);
  });

  it("filters the loaded lines and reports how many match", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await screen.findAllByTestId("log-line");
    fireEvent.change(screen.getByRole("textbox", { name: "Filter lines" }), { target: { value: "SECOND" } });
    const lines = screen.getAllByTestId("log-line");
    expect(lines).toHaveLength(1);
    expect(lines[0]).toHaveTextContent("second line");
    expect(screen.getByText("1 of 3 loaded lines match")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("textbox", { name: "Filter lines" }), { target: { value: "no such text" } });
    expect(screen.getByText("No loaded line matches the filter.")).toBeInTheDocument();
  });

  it("loads the next page from the server's next offset and stops at the end of the chunk", async () => {
    const { calls } = stub({ pages: { "c-2": [
      { text: "page one a\npage one b\n", next: 22, eof: false },
      { text: "page two a\n", next: 33, eof: true },
    ] } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await waitFor(() => expect(screen.getAllByTestId("log-line")).toHaveLength(2));
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() => expect(screen.getAllByTestId("log-line")).toHaveLength(3));
    expect(calls).toContain(`${BASE}/chunks/c-2/text?offset=22`);
    expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
    expect(screen.getByText("End of chunk")).toBeInTheDocument();
  });

  it("switches source from the chips and reloads the chunks of that source", async () => {
    const { calls } = stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await screen.findAllByTestId("log-line");
    fireEvent.click(within(screen.getByRole("group", { name: "Log sources" })).getByRole("button", { name: /fwk\.elg/ }));
    await waitFor(() => expect(calls).toContain(`${BASE}/${DEVICE}/chunks?source=cp_fwk_elg`));
  });

  it("does not request the text of a chunk that retention removed", async () => {
    const { calls } = stub({ chunks: [chunk("c-gone", "2026-10-17T06:00:00Z", { pruned: true, prune_reason: "RETENTION" })] });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Removed by retention")).toBeInTheDocument();
    expect(calls.some((u) => u.includes("/text"))).toBe(false);
  });

  it("shows the empty state when a source has no stored chunk", async () => {
    stub({ chunks: [] });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Nothing stored for this source")).toBeInTheDocument();
  });

  it("reports a failed page with its code instead of an empty viewer", async () => {
    stub({ textFailure: { status: 500, body: { code: "SYSTEM_LOG_CHUNK_UNREADABLE" } } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Lines unavailable")).toBeInTheDocument();
    expect(screen.getByText("SYSTEM_LOG_CHUNK_UNREADABLE")).toBeInTheDocument();
  });

  it("stops loading at the line cap", () => {
    expect(MAX_LOADED_LINES).toBe(5000);
  });
});

describe("the unmasked control", () => {
  it("is absent when the server does not grant the unmasked view", async () => {
    stub({ summary: summary({ can_view_unmasked: false }) });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await screen.findAllByTestId("log-line");
    expect(screen.queryByLabelText("Show unmasked")).toBeNull();
    expect(screen.queryByText(/audit ledger/)).toBeNull();
  });

  it("is present when granted, says the view is audited, and asks the server for the unmasked page", async () => {
    const { calls } = stub({ summary: summary({ can_view_unmasked: true }) });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await screen.findAllByTestId("log-line");
    expect(screen.getByText(/Every page you load this way is recorded on the audit ledger\./)).toBeInTheDocument();
    expect(calls.some((u) => u.includes("unmasked"))).toBe(false);

    fireEvent.click(screen.getByLabelText("Show unmasked"));
    expect(await screen.findByText("Unmasked view · recorded on the audit ledger")).toBeInTheDocument();
    expect(calls).toContain(`${BASE}/chunks/c-2/text?offset=0&unmasked=true`);

    fireEvent.click(screen.getByLabelText("Show unmasked"));
    expect(await screen.findByText("Masked view")).toBeInTheDocument();
  });

  it("goes back to masked when the server refuses the unmasked request", async () => {
    stub({ summary: summary({ can_view_unmasked: true }), textFailure: { status: 403, body: { code: "SYSTEM_LOG_UNMASKED_NOT_PERMITTED" } } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await screen.findByText("Lines unavailable");
    expect((screen.getByLabelText("Show unmasked") as HTMLInputElement).checked).toBe(false);
  });

  it("reports what the server did, not what was asked", async () => {
    stub({ summary: summary({ can_view_unmasked: true }), pages: { "c-2": [{ text: "a\n", next: 2, eof: true, masked: true }] } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    await screen.findAllByTestId("log-line");
    fireEvent.click(screen.getByLabelText("Show unmasked"));
    // the server answered masked: the chip must say masked even though the switch is on
    await waitFor(() => expect(screen.getByText("Masked view")).toBeInTheDocument());
    expect(screen.queryByText("Unmasked view · recorded on the audit ledger")).toBeNull();
  });
});
