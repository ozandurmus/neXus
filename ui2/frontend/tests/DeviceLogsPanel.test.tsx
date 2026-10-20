import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DeviceLogsPanel, formatBytes, gapKindLabel, gapTone, gapWords, MAX_LOADED_LINES } from "../src/screens/DeviceLogsPanel";
import { lineDeviceTime, windowWords } from "../src/screens/logLineTime";
import { m3 } from "../src/theme/m3Theme";
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
  gaps?: SystemLogGap[];
  /** The newest collection job the Jobs list reports. */
  job?: { state: string };
  /** The masking rule set the server names on a masked page (`X-Syslog-Mask-Rules`). */
  maskRules?: string;
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
        "X-Syslog-Eof": String(page.eof), "X-Syslog-Lines": "3", "X-Syslog-Source": "cp_messages",
        ...(setup.maskRules && !unmasked ? { "X-Syslog-Mask-Rules": setup.maskRules } : {}) } }));
    }
    if (url.startsWith("/api/v2/jobs")) {
      const items = setup.job ? [{ job_id: "job-1", job_type: "cp_system_log_collect", target_device_id: DEVICE, submitted_at: "2026-10-17T08:59:50Z", finished_at: "2026-10-17T09:00:00Z", state: setup.job.state, has_transcript: false }] : [];
      return Promise.resolve(json({ items, total: items.length, page: 1, page_size: 1 }));
    }
    if (url.startsWith(`${BASE}/gaps`)) return Promise.resolve(json({ device_id: DEVICE, truncated: false, gaps: setup.gaps ?? [GAP] }));
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
    if (url.startsWith(`${BASE}/${DEVICE}/spike-data`)) return Promise.resolve(json({ device_id: DEVICE, enabled: true, retention_days: 30,
      counts: { BASELINE: 71, COLLECTED: 1, TOO_LARGE: 1 }, truncated: false, directories: [
        { directory_id: "d-1", name_class: "X_spike_X_NNN_NNN-NN-NN_NN-NN-NN", mtime_epoch: 1790000000, state: "COLLECTED", member_count: 2,
          fetched_files: 2, member_bytes: 10_937, fetched_bytes: 10_937, recorded_at: "2026-10-20T09:10:00Z", expires_at: "2026-11-19T09:10:00Z",
          pruned: false, stored_bytes: 4_100, member_classes: [
            { type: "file", class: "perf_X_NNN.log", count: 1, bytes: 10_851, fetched: 1 },
            { type: "file", class: "profiling_summary.elg", count: 1, bytes: 86, fetched: 1 }] },
        { directory_id: "d-2", name_class: "X_spike_X_NNN_NNN-NN-NN_NN-NN-NN", mtime_epoch: 1790000100, state: "TOO_LARGE", member_count: 1,
          fetched_files: 0, member_bytes: 17_000_000, fetched_bytes: 0, recorded_at: "2026-10-20T09:10:00Z", expires_at: null,
          pruned: false, stored_bytes: null, member_classes: [{ type: "file", class: "perf_X_N.log", count: 1, bytes: 17_000_000, fetched: 0 }] },
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
    stub({ summary: summary({ collection_state: "DISABLED_ON_MANAGEMENT", sources: [], last_job: null, code: "SYSTEM_LOG_DISABLED_ON_MANAGEMENT", message: "disabled" }) });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Collection disabled for management servers")).toBeInTheDocument();
    expect(screen.getByText("SYSTEM_LOG_DISABLED_ON_MANAGEMENT")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });

  it("shows the unsupported-vendor state as an answer, not a fault", async () => {
    stub({ summary: summary({ collection_state: "VENDOR_UNSUPPORTED", sources: [], last_job: null, code: "SYSTEM_LOG_VENDOR_UNSUPPORTED", message: "unsupported" }) });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Logs are not collected for this device")).toBeInTheDocument();
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
    stub({ job: { state: "COMPLETED" } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const strip = await screen.findByRole("region", { name: "Log summary" });
    const sentence = within(strip).getByTestId("last-collection");
    expect(sentence).toHaveTextContent("Last collection");
    // the state in sentence case (R7), the raw state on hover
    expect(sentence).toHaveTextContent("Completed");
    expect(sentence).not.toHaveTextContent("COMPLETED");
    // the window of the newest lines, device time, as a short range
    expect(sentence).toHaveTextContent("window 08:00–08:59 device time");
    expect(within(strip).getByText("collected 2")).toBeInTheDocument();
    expect(within(strip).getByText("backlog 1")).toBeInTheDocument();
    expect(await screen.findByText("1 new crash or dump file")).toBeInTheDocument();
  });

  it("shows no alarm when there are no new crash files", async () => {
    stub({ crashNew: 0 });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("No new crash or dump files")).toBeInTheDocument();
    expect(screen.queryByText(/new crash or dump file$/)).toBeNull();
  });

  it("puts a BACKLOG, GAP or UNKNOWN pill on each source and says what a fast-rotating source means", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const group = await screen.findByRole("group", { name: "Log sources" });
    const messages = within(group).getByRole("button", { name: /messages/ });
    expect(within(messages).getByText("Backlog")).toBeInTheDocument();
    expect(within(messages).getByText("fast rotating")).toBeInTheDocument();
    expect(within(within(group).getByRole("button", { name: /fwk\.elg/ })).getByText("Gap")).toBeInTheDocument();
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

  it("shows Spike Detective data as name classes, device time and sizes only when the source is collected", async () => {
    const { calls } = stub({ summary: summary({ sources: [...summary().sources, source("cp_spike_data", { cursor: null })] }) });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("Spike Detective data")).toBeInTheDocument();
    expect(await screen.findByText("Collected")).toBeInTheDocument();
    expect(screen.getByText("Too large, not fetched")).toBeInTheDocument();
    expect(screen.getByText(/perf_X_NNN\.log 11 KiB \(stored\) · profiling_summary\.elg 86 B \(stored\)/)).toBeInTheDocument();
    expect(screen.getByText(/71 directories were present before monitoring and are not fetched/)).toBeInTheDocument();
    expect(calls.some((u) => u === `${BASE}/${DEVICE}/spike-data`)).toBe(true);
  });

  it("does not ask for Spike Detective data while the source has never run", async () => {
    const { calls } = stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findByText("HCP reports")).toBeInTheDocument();
    expect(screen.queryByText("Spike Detective data")).toBeNull();
    expect(calls.some((u) => u.includes("/spike-data"))).toBe(false);
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

  it("names the masking rule set the server applied on the Masked view chip", async () => {
    stub({ maskRules: "logmask-v3" });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    expect(await screen.findAllByTestId("log-line")).toHaveLength(3);
    expect(screen.getByText("Masked view · rules logmask-v3")).toBeInTheDocument();
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


const PAN_LINES = [
  '{"receive_time": "2026/10/18 06:11:02", "type": "SYSTEM", "eventid": "synthetic", "opaque": "synthetic text that is long"}',
  '{"receive_time": "2026/10/18 06:11:09", "type": "CONFIG", "cmd": "synthetic"}',
  "    continuation line without a time",
].join("\n") + "\n";

function panSummary(): SystemLogSources {
  return summary({
    last_job: { job_id: "job-1", at: "2026-10-18T03:13:03Z", outcomes: { COLLECTED: 2 }, window_from: "2026-10-18 00:15:00", window_to: "2026-10-18 06:11:00", window_time_basis: "DEVICE_LOCAL" },
    sources: [source("pan_config", { status: "OK", stored: { chunks: 4, bytes: 7680, lines: 13, newest_chunk_at: "2026-10-18T03:13:03Z" } }),
      source("pan_system", { status: "OK", stored: { chunks: 4, bytes: 2048, lines: 0, newest_chunk_at: null } })],
  });
}

describe("filled state, summary first (UI review 2026-10-18, F-8)", () => {
  it("reads the last collection as one sentence: when, how long ago, how it ended, the device-time window", async () => {
    stub({ summary: panSummary(), job: { state: "COMPLETED" } });
    render(<DeviceLogsPanel deviceId={DEVICE} vendorHint="palo_alto" />);
    const sentence = await screen.findByTestId("last-collection");
    expect(sentence).toHaveTextContent(/^Last collection2026-10-18 06:13 GMT\+3· .+ ago· Completed· window 00:15–06:11 device time$/);
  });

  it("says the job state is UNKNOWN when the Jobs list has none, and never invents COMPLETED", async () => {
    stub({ summary: panSummary() });
    render(<DeviceLogsPanel deviceId={DEVICE} vendorHint="palo_alto" />);
    expect(await screen.findByTestId("last-collection")).toHaveTextContent("job state UNKNOWN");
  });

  it("gives each line source its status word and stored line count once, on the selector (R7): counts in grey and only OK in green", async () => {
    stub({ summary: panSummary(), job: { state: "COMPLETED" } });
    render(<DeviceLogsPanel deviceId={DEVICE} vendorHint="palo_alto" />);
    const strip = await screen.findByRole("region", { name: "Log summary" });
    expect(within(strip).queryByRole("list", { name: "Lines stored per source" })).toBeNull();
    const group = screen.getByRole("group", { name: "Log sources" });
    const config = within(group).getByRole("button", { name: "config · OK · 13 lines" });
    const system = within(group).getByRole("button", { name: "system · OK · 0 lines" });
    expect(within(config).getByText("OK").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.successContainer });
    // a count, and a zero count, are neutral grey: zero is never green
    expect(within(config).getByText("13 lines").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.scHigh });
    expect(within(system).getByText("0 lines").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.scHigh });
  });

  it("counts of sources in the run are grey; a source that did not end cleanly keeps its state word and colour", async () => {
    stub({ summary: summary(), job: { state: "COMPLETED" } });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const strip = await screen.findByRole("region", { name: "Log summary" });
    expect(within(strip).getByText("collected 2").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.scHigh });
    expect(within(strip).getByText("backlog 1").closest("[data-pill]")).not.toHaveStyle({ backgroundColor: m3.scHigh });
  });

  it("puts gaps and the crash check in one grey sentence, each a link to its panel, in plain words", async () => {
    const seq: SystemLogGap = { ...GAP, gap_id: "g-2", kind: "SEQ_GAP_SUSPECTED", source_key: "cp_fwk_elg", recorded_at: "2026-10-17T21:15:00Z" };
    const first: SystemLogGap = { ...GAP, gap_id: "g-3", kind: "INITIAL_BASELINE", source_key: "cp_messages", recorded_at: "2026-10-17T21:15:00Z" };
    stub({ gaps: [seq, first], crashNew: 0 });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const line = await screen.findByRole("region", { name: "Attention" });
    const gapLink = await within(line).findByRole("button", { name: /Sequence gap suspected \(fwk\.elg 00:15\)/ });
    expect(within(line).getByRole("button", { name: /First collection: older content not fetched \(messages 00:15\)/ })).toBeInTheDocument();
    expect(within(line).getByRole("button", { name: "No new crash or dump files" })).toBeInTheDocument();
    // jargon and red-tinted "initial baseline" pills are gone
    expect(screen.queryByText(/initial baseline/i)).toBeNull();
    expect(screen.queryByText(/seq gap suspected/)).toBeNull();
    const scroll = vi.fn();
    const panel = document.getElementById("logs-gaps") as HTMLElement;
    panel.scrollIntoView = scroll;
    fireEvent.click(gapLink);
    expect(scroll).toHaveBeenCalled();
    // the first collection pill in the panel is grey; the suspected gap is amber, not red-tinted
    expect(within(panel).getByText("First collection: older content not fetched").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.scHigh });
    expect(within(panel).getByText("Sequence gap suspected").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.warningContainer });
  });

  it("repeats a kind once with its count, and raises a red word only for a NEW crash or dump file", async () => {
    const a: SystemLogGap = { ...GAP, gap_id: "g-4", kind: "SEQ_GAP_SUSPECTED", source_key: "cp_messages", recorded_at: "2026-10-17T21:15:00Z" };
    const b: SystemLogGap = { ...a, gap_id: "g-5", source_key: "cp_fwk_elg" };
    stub({ gaps: [a, b], crashNew: 2 });
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const line = await screen.findByRole("region", { name: "Attention" });
    expect(await within(line).findByRole("button", { name: /Sequence gap suspected ×2 \(messages 00:15, fwk\.elg 00:15\)/ })).toBeInTheDocument();
    expect(within(line).getByText("2 new crash or dump files").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.errorContainer });
  });

  it("is honest while the gaps and the crash check are loading or unavailable", async () => {
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      const url = String(input);
      if (url === `${BASE}/${DEVICE}/sources`) return Promise.resolve(json(summary()));
      if (url.startsWith("/api/v2/jobs")) return Promise.resolve(json({ items: [], total: 0, page: 1, page_size: 1 }));
      if (url.startsWith(`${BASE}/${DEVICE}/chunks`)) return Promise.resolve(json({ device_id: DEVICE, source: "cp_messages", day: null, truncated: false, chunks: [] }));
      if (url.startsWith(`${BASE}/${DEVICE}/hcp`)) return Promise.resolve(json({ device_id: DEVICE, member_listing: "NOT_AVAILABLE", files: [] }));
      return Promise.resolve(json({ code: "BOOM" }, 500));
    }));
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const line = await screen.findByRole("region", { name: "Attention" });
    await waitFor(() => expect(line).toHaveTextContent("Gaps unavailable"));
    expect(line).toHaveTextContent("Crash and dump check unavailable");
    expect(line).not.toHaveTextContent("No new crash");
  });

  it("reads the day and chunk pickers as a sentence", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const sentence = await screen.findByTestId("picker-sentence");
    expect(sentence).toHaveTextContent("Day 2026-10-17 · run 2 of 2 · 12:00 · 3 lines · 100 B");
    const run = screen.getByRole("combobox", { name: "Run" });
    expect(within(run).getByRole("option", { name: /^1 of 2 · 09:00 · 3 lines · 100 B/ })).toBeInTheDocument();
  });

  it("shows the masked pill beside the line filter", async () => {
    stub();
    render(<DeviceLogsPanel deviceId={DEVICE} />);
    const filter = await screen.findByRole("textbox", { name: "Filter lines" });
    const pill = await screen.findByText("Masked view");
    // the filter field and the pill share one row
    expect(filter.closest(".MuiFormControl-root")?.parentElement).toBe(pill.closest("[data-pill]")?.parentElement);
  });

  it("keeps a fixed device-time column on the raw lines and the whole line behind a click", async () => {
    stub({ summary: panSummary(), chunks: [chunk("c-1", "2026-10-18T03:13:03Z", { source_key: "pan_config", log_day: "2026-10-18" })],
      pages: { "c-1": [{ text: PAN_LINES, next: 300, eof: true }] } });
    render(<DeviceLogsPanel deviceId={DEVICE} vendorHint="palo_alto" />);
    const rows = await screen.findAllByTestId("log-line");
    expect(rows).toHaveLength(3);
    const times = rows.map((r) => within(r).getByTestId("log-line-time").textContent);
    expect(times).toEqual(["2026/10/18 06:11:02", "2026/10/18 06:11:09", ""]);
    // a line with no time of its own gets an empty cell and says so on hover; nothing is invented
    expect(within(rows[2]).getByTestId("log-line-time")).toHaveAttribute("title", "No device time on this line");
    expect(rows[0]).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(rows[0]);
    expect(rows[0]).toHaveAttribute("aria-expanded", "true");
    expect(rows[0]).toHaveTextContent("synthetic text that is long");
    // the filter still sees the whole line, time included
    fireEvent.change(screen.getByRole("textbox", { name: "Filter lines" }), { target: { value: "06:11:09" } });
    expect(screen.getAllByTestId("log-line")).toHaveLength(1);
  });

  it("invents no severity: no severity chip or count anywhere", async () => {
    stub({ summary: panSummary(), job: { state: "COMPLETED" } });
    render(<DeviceLogsPanel deviceId={DEVICE} vendorHint="palo_alto" />);
    await screen.findByTestId("last-collection");
    await screen.findAllByTestId("log-line");
    expect(screen.queryByText(/severity|critical|informational/i)).toBeNull();
  });

  it("lays out Check Point and Palo Alto the same: summary, attention line, viewer, then the side panels", async () => {
    for (const [vendor, data] of [["check_point", summary()], ["palo_alto", panSummary()]] as const) {
      stub({ summary: data, job: { state: "COMPLETED" } });
      const { unmount } = render(<DeviceLogsPanel deviceId={DEVICE} vendorHint={vendor} />);
      const root = await screen.findByTestId("device-logs");
      const order = ["Log summary", "Attention"].map((name) => within(root).getByRole("region", { name }));
      expect(order[0].compareDocumentPosition(order[1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(await within(root).findByRole("group", { name: "Log sources" })).toBeInTheDocument();
      for (const title of ["Gaps", "Crash and dump files", "HCP reports"]) expect(within(root).getByText(title)).toBeInTheDocument();
      unmount();
      vi.unstubAllGlobals();
    }
  });
});

describe("lineDeviceTime and windowWords", () => {
  it("takes the time a line itself carries, in the device's own format", () => {
    expect(lineDeviceTime('{"receive_time":"2026/10/18 06:11:02","x":1}')).toBe("2026/10/18 06:11:02");
    expect(lineDeviceTime("receive_time=2026-10-18T06:11:02 type=SYSTEM")).toBe("2026-10-18 06:11:02");
    expect(lineDeviceTime("Oct  7 08:00:01 gw-1 kernel: synthetic")).toBe("Oct 7 08:00:01");
    expect(lineDeviceTime("2026-10-17 08:00:00 synthetic")).toBe("2026-10-17 08:00:00");
    // a Check Point daemon log line: the stamp sits in brackets after the process tag; a dumped object line has none
    expect(lineDeviceTime("[cpd 4321 140000]@FW-TANGO-04[18 Oct 11:13:23] synthetic message")).toBe("18 Oct 11:13:23");
    expect(lineDeviceTime("[18 Oct 2026 11:13:23] synthetic message")).toBe("18 Oct 2026 11:13:23");
    expect(lineDeviceTime("                :dlp_blob_retry_interval (180)")).toBeNull();
    expect(lineDeviceTime("    at synthetic.frame(Synthetic.java:1)")).toBeNull();
    expect(lineDeviceTime("")).toBeNull();
  });

  it("writes a one-day window as a short range and a multi-day one in full", () => {
    expect(windowWords("2026-10-18 00:15:00", "2026-10-18 06:11:59")).toBe("00:15–06:11");
    expect(windowWords("2026-10-17 23:00:00", "2026-10-18 06:11:59")).toBe("2026-10-17 23:00 – 2026-10-18 06:11");
    expect(windowWords(null, "2026-10-18 06:11:59")).toBeNull();
    expect(windowWords("unparsed", "alsoUnparsed")).toBe("unparsed to alsoUnparsed");
  });

  it("words every gap kind and gives each a tone: grey for the first run, amber for a suspected gap", () => {
    expect(gapKindLabel("SEQ_GAP_SUSPECTED")).toBe("Sequence gap suspected");
    expect(gapKindLabel("INITIAL_BASELINE")).toBe("First collection: older content not fetched");
    expect(gapKindLabel("SOMETHING_NEW")).toBe("Something new");
    expect([gapTone("INITIAL_BASELINE"), gapTone("SEQ_GAP_SUSPECTED"), gapTone("BACKLOG"), gapTone("ROTATED_AWAY")]).toEqual(["neutral", "warn", "warn", "attn"]);
  });
});
