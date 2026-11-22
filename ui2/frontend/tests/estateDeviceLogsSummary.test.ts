import { createElement } from "react";
import { render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SystemLogCrashFiles, SystemLogGap, SystemLogSource, SystemLogSources } from "../src/auth/adminApi";

const mocks = vi.hoisted(() => ({
  getSystemLogSources: vi.fn(),
  getSystemLogGaps: vi.fn(),
  getSystemLogCrashFiles: vi.fn(),
  getSystemLogText: vi.fn(),
}));

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getSystemLogSources: mocks.getSystemLogSources,
  getSystemLogGaps: mocks.getSystemLogGaps,
  getSystemLogCrashFiles: mocks.getSystemLogCrashFiles,
  getSystemLogText: mocks.getSystemLogText,
}));

import { EstateDossierLogsBlock, loadDeviceLogsReads } from "../src/estate/EstateDossierLogsBlock";
import { buildDeviceLogsSummary, standardDeviceLogsHref, type DeviceLogsReads } from "../src/estate/estateDeviceLogsSummary";

const NOW = new Date("2026-11-22T12:00:00Z");
const HOUR = 3_600_000;
const iso = (ms: number) => new Date(NOW.getTime() - ms).toISOString();
const DEVICE = "dev-tango-04";

function source(key: string, chunks: number, newestAgoMs: number | null): SystemLogSource {
  return {
    source_key: key, status: "OK", last_outcome: "OK", last_run_at: iso(HOUR), last_job_id: "job-1",
    last_bytes_read: 10, last_bytes_stored: 10, pending_bytes: 0, fast_rotating: false,
    retention_horizon_seconds: null, cursor: null,
    stored: { chunks, bytes: chunks * 100, lines: chunks * 10, newest_chunk_at: newestAgoMs === null ? null : iso(newestAgoMs) },
  };
}

function sourcesOf(sources: readonly SystemLogSource[], collection: SystemLogSources["collection_state"] = "COLLECTED"): SystemLogSources {
  return {
    device_id: DEVICE, collection_state: collection, can_view_unmasked: false, default_page_bytes: 4096, max_page_bytes: 65536,
    last_job: { job_id: "job-1", at: iso(HOUR), outcomes: { OK: 2 }, window_from: null, window_to: null, window_time_basis: "job" },
    sources,
  };
}

function gap(kind: string, recordedAgoMs: number): SystemLogGap {
  return {
    gap_id: `gap-${kind}`, device_id: DEVICE, source_key: "cp_messages", job_id: "job-1", kind, known_lost_bytes: 0,
    pending_bytes: 0, extent_unknown: false, window_start_epoch: null, window_end_epoch: null, recorded_at: iso(recordedAgoMs),
  };
}

function crash(state: "NEW" | "REMOVED", firstSeenAgoMs: number): SystemLogCrashFiles["files"][number] {
  return {
    entry_id: `crash-${state}-${firstSeenAgoMs}`, device_id: DEVICE, directory: "crash", process: "fwk", size: 1024,
    mtime_epoch: 0, state, first_seen_at: iso(firstSeenAgoMs),
  } as unknown as SystemLogCrashFiles["files"][number];
}

const FIXTURE: DeviceLogsReads = {
  sources: {
    ok: true,
    value: sourcesOf([source("cp_messages", 3, 2 * HOUR), source("cp_fwd_elg", 2, 5 * HOUR)]),
  },
  gaps: {
    ok: true,
    value: { truncated: false, gaps: [gap("SEQ_GAP_SUSPECTED", 4 * HOUR), gap("INITIAL_BASELINE", 9 * HOUR)] },
  },
  crashFiles: {
    ok: true,
    value: {
      device_id: DEVICE, new_window_days: 7, counts: {}, truncated: false,
      files: [crash("NEW", 6 * HOUR), crash("REMOVED", 20 * HOUR)], alerts: [],
    },
  },
};

describe("buildDeviceLogsSummary", () => {
  it("counts sources, chunks and gaps, and dates the newest chunk", () => {
    const s = buildDeviceLogsSummary(FIXTURE);
    expect(s.state).toBe("collected");
    expect(s.sourceCount).toBe(2);
    expect(s.chunkCount).toBe(5);
    expect(s.newestChunkAt).toBe(iso(2 * HOUR));
    expect(s.sourcesEvidenceAt).toBe(iso(2 * HOUR));
    expect(s.gapCount).toBe(2);
    expect(s.gapKinds).toEqual([
      { kind: "INITIAL_BASELINE", count: 1 },
      { kind: "SEQ_GAP_SUSPECTED", count: 1 },
    ]);
    expect(s.gapsEvidenceAt).toBe(iso(HOUR)); // newest of gap records (4h ago) and the last job (1h ago)
    expect(s.crashFileCount).toBe(1); // the REMOVED file is not present
    expect(s.crashEvidenceAt).toBe(iso(HOUR));
    expect(s.failedFigures).toEqual([]);
    expect(s.badge).toBe("watch");
    expect(s.badgeWord).toBe("Review");
  });

  it("reports UNKNOWN for the figure whose read was rejected, and keeps the others", () => {
    const s = buildDeviceLogsSummary({ ...FIXTURE, gaps: { ok: false } });
    expect(s.gapCount).toBeNull();
    expect(s.gapKinds).toEqual([]);
    expect(s.failedFigures).toEqual(["gaps"]);
    expect(s.sourceCount).toBe(2);
    expect(s.crashFileCount).toBe(1);
    expect(s.badge).toBe("unk");
    expect(s.badgeWord).toBe("UNKNOWN");
  });

  it("says 'No logs collected yet' for a first collection with zero chunks, never OK", () => {
    const first: DeviceLogsReads = {
      sources: { ok: true, value: sourcesOf([source("cp_messages", 0, null)], "NO_COLLECTION_YET") },
      gaps: { ok: true, value: { truncated: false, gaps: [] } },
      crashFiles: { ok: true, value: { device_id: DEVICE, new_window_days: 7, counts: {}, truncated: false, files: [], alerts: [] } },
    };
    const s = buildDeviceLogsSummary(first);
    expect(s.state).toBe("no_logs_yet");
    expect(s.headline).toBe("No logs collected yet");
    expect(s.badgeWord).not.toBe("OK");
    expect(s.newestChunkAt).toBeNull();
  });

  it("builds the standard-view link with standard=1 for this device", () => {
    const href = standardDeviceLogsHref(DEVICE);
    const params = new URLSearchParams(href.slice(1));
    expect(params.get("standard")).toBe("1");
    expect(params.get("device_id")).toBe(DEVICE);
    expect(params.get("tab")).toBe("logs");
  });
});

describe("loadDeviceLogsReads", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("turns a rejected gap read into an UNKNOWN part and never reads log text", async () => {
    mocks.getSystemLogSources.mockResolvedValue(FIXTURE.sources.ok ? FIXTURE.sources.value : undefined);
    mocks.getSystemLogGaps.mockRejectedValue({ status: 500, body: {} });
    mocks.getSystemLogCrashFiles.mockResolvedValue(FIXTURE.crashFiles.ok ? FIXTURE.crashFiles.value : undefined);

    const reads = await loadDeviceLogsReads(DEVICE);
    expect(reads.gaps.ok).toBe(false);
    expect(reads.sources.ok).toBe(true);
    expect(reads.crashFiles.ok).toBe(true);
    expect(mocks.getSystemLogText).not.toHaveBeenCalled();
  });
});

describe("EstateDossierLogsBlock", () => {
  it("renders counts, gap labels, stamps and the standard=1 link, with no log text", () => {
    const { container } = render(createElement(EstateDossierLogsBlock, { deviceId: DEVICE, logs: { state: "ok", value: FIXTURE }, now: NOW }));
    const block = within(container).getByTestId("dossier-logs-block");
    expect(block.textContent).toContain("Logs");
    expect(block.textContent).toContain("Sequence gap suspected");
    expect(block.textContent).toContain("First collection: older content not fetched");
    expect(block.querySelectorAll("[data-logs-figure]")).toHaveLength(3);

    const link = screen.getByRole("link", { name: "Open full logs" });
    expect(new URLSearchParams(link.getAttribute("href")!.slice(1)).get("standard")).toBe("1");

    // No log lines or text: the block shows counts and labels only.
    expect(block.textContent).not.toMatch(/\bcp_messages\b/);
    expect(mocks.getSystemLogText).not.toHaveBeenCalled();
  });

  it("shows UNKNOWN for a failed read instead of a count", () => {
    const { container } = render(
      createElement(EstateDossierLogsBlock, { deviceId: DEVICE, logs: { state: "ok", value: { ...FIXTURE, gaps: { ok: false } } }, now: NOW }),
    );
    const gapsRow = container.querySelector('[data-logs-figure="gaps"]') as HTMLElement;
    expect(gapsRow.textContent).toContain("UNKNOWN");
  });

  it("shows 'No logs collected yet' in the rendered block for a first collection", async () => {
    const first: DeviceLogsReads = {
      sources: { ok: true, value: sourcesOf([source("cp_messages", 0, null)], "NO_COLLECTION_YET") },
      gaps: { ok: true, value: { truncated: false, gaps: [] } },
      crashFiles: { ok: true, value: { device_id: DEVICE, new_window_days: 7, counts: {}, truncated: false, files: [], alerts: [] } },
    };
    render(createElement(EstateDossierLogsBlock, { deviceId: DEVICE, logs: { state: "ok", value: first }, now: NOW }));
    await waitFor(() => expect(screen.getByText("No logs collected yet")).toBeTruthy());
  });
});
