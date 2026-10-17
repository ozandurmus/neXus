import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  getSystemLogChunks, getSystemLogCrashFiles, getSystemLogGaps, getSystemLogHcp, getSystemLogSources, getSystemLogText,
  type ApiError, type SystemLogChunk, type SystemLogCrashFiles, type SystemLogGap, type SystemLogHcp, type SystemLogSource,
  type SystemLogSources,
} from "../auth/adminApi";
import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, isRestricted } from "../shell/States";
import type { Tone } from "../shell/tone";
import { formatTime } from "../shell/time";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { MONO, m3 } from "../theme/m3Theme";

/**
 * Device > Logs (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14). Everything here is read from the service's
 * `/api/v2/system-logs/*` routes and nothing is derived that the data cannot support:
 *
 *  - the line viewer shows the text route's pages, masked unless the server says otherwise; the "show unmasked" control
 *    exists only when the server's own flag `can_view_unmasked` is true (the browser holds no role concept) and says
 *    that every page is recorded on the audit ledger;
 *  - the status pill of a source is the server's reading of its newest run (OK, BACKLOG, GAP, UNKNOWN, ...);
 *  - left out on purpose, because the backend has no data for them yet: error/warning line counts against the previous
 *    window, process restarts from cpwd, the HCP test verdicts, and the HCP archive member listing.
 */

/** A page that holds more than this many lines stops loading more: narrow with the filter or pick another chunk. */
export const MAX_LOADED_LINES = 5000;

const LINE_SOURCES = ["cp_messages", "cp_fwd_elg", "cp_cpd_elg", "cp_cpwd_elg", "cp_fwk_elg", "cp_mq_mng_elg", "cp_spike_log",
  "cp_spike_csv", "pan_system", "pan_config"] as const;

const SOURCE_LABELS: Record<string, string> = {
  cp_messages: "messages", cp_fwd_elg: "fwd.elg", cp_cpd_elg: "cpd.elg", cp_cpwd_elg: "cpwd.elg", cp_fwk_elg: "fwk.elg",
  cp_mq_mng_elg: "mq_mng.elg", cp_spike_log: "spike log", cp_spike_csv: "spike csv", pan_system: "system", pan_config: "config",
  cp_sar_daily: "sar (daily)", cp_hcp_report: "HCP report", cp_spike_data: "Spike data", cp_crash_listing: "Crash listing",
};

export function sourceLabel(key: string): string {
  return SOURCE_LABELS[key] ?? key;
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KiB", "MiB", "GiB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`;
}

export function statusTone(status: string): Tone {
  switch (status) {
    case "OK": return "ok";
    case "BACKLOG": case "TOO_LARGE": return "warn";
    case "GAP": return "attn";
    case "UNKNOWN": case "STORAGE_REFUSED": return "bad";
    default: return "neutral";
  }
}

/** A gap, in plain words, with its byte count. */
export function gapWords(g: SystemLogGap): string {
  const lost = formatBytes(g.known_lost_bytes);
  const more = g.extent_unknown ? " More may be missing." : "";
  switch (g.kind) {
    case "ROTATED_AWAY": return `Rotated away on the device before it was read: ${lost} lost.${more}`;
    case "TRUNCATED": return `The file was truncated on the device: up to ${lost} lost.${more}`;
    case "ANCHOR_MISMATCH": return "The file no longer matched the saved position, so reading restarted. The amount lost is not known.";
    case "INITIAL_BASELINE": return `First collection started here: ${lost} of older content was skipped.`;
    case "BACKLOG": return `Still waiting on the device: ${formatBytes(g.pending_bytes)} left to read.`;
    case "COLLECTION_FAILED": return "Collection failed in this window. Any loss shows up in the next run.";
    case "PAGE_STALL": return "Paging stalled in this window; the rest waits for the next run.";
    case "SEQ_GAP_SUSPECTED": return "A gap in the sequence numbers was suspected; some entries may be missing.";
    case "RETENTION_SHORTENED_BY_CAP": return `The oldest chunks were removed early because the device reached its storage cap: ${lost} removed.`;
    default: return `${g.kind}: ${lost} lost.${more}`;
  }
}

const DIRECTORY_LABELS: Record<string, string> = { crash: "Kernel crash", dump_usermode: "User-mode dump" };
const CRASH_STATE_LABELS: Record<string, string> = {
  NEW: "New", PRESENT: "Present", PRESENT_BEFORE_MONITORING: "Present before monitoring", REMOVED: "Removed",
};

interface Refusal { readonly status: number; readonly code: string }

function refusalOf(err: unknown): Refusal {
  const e = err as Partial<ApiError> | null;
  const body = (e?.body ?? {}) as Record<string, unknown>;
  const code = typeof body.code === "string" ? body.code : typeof body.error === "string" ? body.error : "";
  return { status: typeof e?.status === "number" ? e.status : 0, code };
}

function describe(err: unknown): string {
  const r = refusalOf(err);
  return r.code || `request failed${r.status ? ` (status ${r.status})` : ""}`;
}

function epochIso(epoch: number): string {
  return new Date(epoch * 1000).toISOString();
}

export function DeviceLogsPanel({ deviceId }: { readonly deviceId: string }) {
  const sources = useFetchOnMount<SystemLogSources>(() => getSystemLogSources(deviceId), describe);

  if (sources.error) {
    const r = refusalOf(sources.rawError);
    if (r.code === "SYSTEM_LOG_DISABLED_ON_MANAGEMENT") {
      return (
        <StatePanel variant="empty" title="Collection disabled for management servers"
          body="System logs are not collected from management servers (the setting that allows it is off). Gateways managed by this server are collected on their own."
          code={r.code} />
      );
    }
    if (r.code === "SYSTEM_LOG_VENDOR_UNSUPPORTED") {
      return <StatePanel variant="empty" title="Logs are not collected for this device" body="System-log collection covers Check Point and Palo Alto gateways." code={r.code} />;
    }
    if (isRestricted(sources.rawError)) return <RestrictedPanel area="System logs" />;
    return (
      <StatePanel variant="error" title="Logs unavailable" body="The log summary could not be read." code={sources.error}
        action={<M3Button emphasis="outlined" onClick={sources.refresh}>Retry</M3Button>} />
    );
  }
  if (!sources.data) return <EmptyPanel title="Logs" body="Loading…" />;
  if (sources.data.collection_state === "NO_COLLECTION_YET") {
    return (
      <StatePanel variant="empty" title="No collection yet"
        body="No system log has been collected from this device yet. Collection runs in the shared job windows (00:00, 06:00, 12:00 and 18:00 Europe/Istanbul); this tab fills in after the first run."
        action={<M3Button emphasis="outlined" onClick={sources.refresh}>Refresh</M3Button>} />
    );
  }
  return <LogsBody key={deviceId} deviceId={deviceId} data={sources.data} onRefresh={sources.refresh} />;
}

function LogsBody({ deviceId, data, onRefresh }: { readonly deviceId: string; readonly data: SystemLogSources; readonly onRefresh: () => void }) {
  const gaps = useFetchOnMount(() => getSystemLogGaps(deviceId), describe);
  const crash = useFetchOnMount<SystemLogCrashFiles>(() => getSystemLogCrashFiles(deviceId), describe);
  const hcp = useFetchOnMount<SystemLogHcp>(() => getSystemLogHcp(deviceId), describe);
  const otherSources = data.sources.filter((s) => !(LINE_SOURCES as readonly string[]).includes(s.source_key));
  const lineSources = data.sources.filter((s) => (LINE_SOURCES as readonly string[]).includes(s.source_key));

  return (
    <Stack spacing={2} data-testid="device-logs">
      <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 2 }}>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          Loglar · system logs collected from the device, stored encrypted and masked on screen by default.
        </Typography>
        <M3Button emphasis="text" onClick={onRefresh}>Refresh</M3Button>
      </Box>
      <SummaryStrip data={data} crash={crash.data} crashFailed={Boolean(crash.error)} />
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: "minmax(0, 2fr) minmax(280px, 1fr)" }, alignItems: "start" }}>
        <LogViewer deviceId={deviceId} sources={lineSources} canViewUnmasked={data.can_view_unmasked} maxPageBytes={data.max_page_bytes} />
        <Stack spacing={2}>
          <SidePanel title="Gaps">
            {gaps.error ? <Typography variant="body2" color="text.secondary">Gaps unavailable ({gaps.error}).</Typography>
              : !gaps.data ? <Typography variant="body2">Loading…</Typography>
              : gaps.data.gaps.length === 0 ? <Typography variant="body2" color="text.secondary">No gaps recorded.</Typography>
              : <Stack spacing={1.25}>{gaps.data.gaps.map((g) => <GapRow key={g.gap_id} gap={g} />)}</Stack>}
            {gaps.data?.truncated && <Typography variant="caption" color="text.secondary">Showing the newest gaps only.</Typography>}
          </SidePanel>
          <SidePanel title="Crash and dump files">
            {crash.error ? <Typography variant="body2" color="text.secondary">Crash files unavailable ({crash.error}).</Typography>
              : !crash.data ? <Typography variant="body2">Loading…</Typography>
              : crash.data.files.length === 0 ? <Typography variant="body2" color="text.secondary">No crash or dump files listed.</Typography>
              : <Stack spacing={1}>{crash.data.files.map((f) => (
                  <Box key={f.entry_id} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                    <StatusChip tone={f.state === "NEW" ? "bad" : "neutral"} label={CRASH_STATE_LABELS[f.state] ?? f.state} dense />
                    <Typography variant="body2" sx={{ fontWeight: 500 }}>{f.process === "UNKNOWN" ? "Unknown process" : f.process}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {DIRECTORY_LABELS[f.directory] ?? f.directory} · {formatBytes(f.size)} · <Ts at={epochIso(f.mtime_epoch)} seconds={false} />
                    </Typography>
                  </Box>
                ))}</Stack>}
            <Typography variant="caption" color="text.secondary">
              Files are listed by process and size only; the product never reads their content. Files present before monitoring began raise no alert.
            </Typography>
          </SidePanel>
          <SidePanel title="HCP reports">
            {hcp.error ? <Typography variant="body2" color="text.secondary">HCP reports unavailable ({hcp.error}).</Typography>
              : !hcp.data ? <Typography variant="body2">Loading…</Typography>
              : hcp.data.files.length === 0 ? <Typography variant="body2" color="text.secondary">No HCP report has been collected.</Typography>
              : <Stack spacing={1}>{hcp.data.files.map((f) => (
                  <Box key={f.file_id} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                    <StatusChip tone={f.status === "STORED" ? "ok" : "bad"} label={f.status === "STORED" ? "Stored" : f.status === "CORRUPT" ? "Unreadable archive" : f.status} dense />
                    {f.flags.includes("NAME_SHAPE_UNEXPECTED") && <StatusChip tone="warn" label="Unexpected file name" dense />}
                    {f.pruned && <StatusChip tone="neutral" label="Removed by retention" dense />}
                    <Typography variant="caption" color="text.secondary">
                      {f.name_time ? `${f.name_time} (device time)` : "time unknown"} · {formatBytes(f.size)}
                    </Typography>
                  </Box>
                ))}</Stack>}
            <Typography variant="caption" color="text.secondary">
              Archives are kept encrypted and are not served from this screen. The member listing is not available yet.
            </Typography>
          </SidePanel>
          {otherSources.length > 0 && (
            <SidePanel title="Other collection">
              <Stack spacing={0.75}>
                {otherSources.map((s) => (
                  <Box key={s.source_key} sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <Typography variant="body2" sx={{ minWidth: 110 }}>{sourceLabel(s.source_key)}</Typography>
                    <StatusChip tone={statusTone(s.status)} label={s.status} dense />
                    <Typography variant="caption" color="text.secondary">{s.last_run_at ? <Ts at={s.last_run_at} relative seconds={false} /> : "never run"}</Typography>
                  </Box>
                ))}
              </Stack>
            </SidePanel>
          )}
        </Stack>
      </Box>
    </Stack>
  );
}

function SidePanel({ title, children }: { readonly title: string; readonly children: React.ReactNode }) {
  return (
    <Box sx={{ bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2, display: "flex", flexDirection: "column", gap: 1.25 }}>
      <Typography variant="subtitle2">{title}</Typography>
      {children}
    </Box>
  );
}

function GapRow({ gap }: { readonly gap: SystemLogGap }) {
  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <StatusChip tone={gap.kind === "BACKLOG" ? "warn" : "attn"} label={gap.kind.replace(/_/g, " ").toLowerCase()} dense />
        <Typography variant="caption" color="text.secondary">{sourceLabel(gap.source_key)} · <Ts at={gap.recorded_at} seconds={false} /></Typography>
      </Box>
      <Typography variant="body2">{gapWords(gap)}</Typography>
    </Box>
  );
}

function SummaryStrip({ data, crash, crashFailed }: { readonly data: SystemLogSources; readonly crash: SystemLogCrashFiles | null; readonly crashFailed: boolean }) {
  const job = data.last_job;
  const totals = data.sources.reduce((acc, s) => ({ chunks: acc.chunks + s.stored.chunks, bytes: acc.bytes + s.stored.bytes, lines: acc.lines + s.stored.lines }), { chunks: 0, bytes: 0, lines: 0 });
  const newCrash = crash?.counts?.NEW ?? 0;
  return (
    <Box role="region" aria-label="Log summary" sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2 }}>
      <Box>
        <Typography variant="caption" color="text.secondary">Last collection</Typography>
        <Typography variant="body2">{job?.at ? <Ts at={job.at} relative /> : "UNKNOWN"}</Typography>
        <Typography variant="caption" color="text.secondary">
          {job?.window_from && job.window_to ? `Window ${job.window_from} to ${job.window_to} (device time)` : "Window of the newest lines is not known"}
        </Typography>
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">Sources in that run</Typography>
        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", pt: 0.5 }}>
          {job && Object.keys(job.outcomes).length > 0
            ? Object.entries(job.outcomes).map(([outcome, n]) => <StatusChip key={outcome} tone={statusTone(outcome === "COLLECTED" || outcome === "NO_NEW_DATA" ? "OK" : outcome)} label={`${outcome.replace(/_/g, " ").toLowerCase()} ${n}`} dense />)
            : <Typography variant="body2">UNKNOWN</Typography>}
        </Box>
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">Stored now</Typography>
        <Typography variant="body2">{totals.chunks} chunks · {totals.lines.toLocaleString("en-US")} lines · {formatBytes(totals.bytes)}</Typography>
      </Box>
      <Box>
        <Typography variant="caption" color="text.secondary">Crash and dump files</Typography>
        <Box sx={{ pt: 0.5 }}>
          {crashFailed ? <StatusChip tone="neutral" label="unavailable" dense />
            : !crash ? <StatusChip tone="neutral" label="loading" dense />
            : newCrash > 0 ? <StatusChip tone="bad" label={`${newCrash} new crash or dump file${newCrash === 1 ? "" : "s"}`} dense />
            : <StatusChip tone="ok" label="no new crash or dump files" dense />}
        </Box>
      </Box>
    </Box>
  );
}

interface TextState {
  readonly chunkId: string | null;
  readonly lines: readonly string[];
  readonly nextOffset: number;
  readonly eof: boolean;
  readonly masked: boolean | null;
  readonly loading: boolean;
  readonly error: string | null;
  readonly capped: boolean;
}

const EMPTY_TEXT: TextState = { chunkId: null, lines: [], nextOffset: 0, eof: false, masked: null, loading: false, error: null, capped: false };

function splitLines(text: string): string[] {
  if (text === "") return [];
  const lines = text.split("\n");
  if (lines[lines.length - 1] === "") lines.pop();
  return lines;
}

function LogViewer({ deviceId, sources, canViewUnmasked, maxPageBytes }: {
  readonly deviceId: string;
  readonly sources: readonly SystemLogSource[];
  readonly canViewUnmasked: boolean;
  readonly maxPageBytes: number;
}) {
  const [source, setSource] = useState<string | null>(() => (sources.find((s) => s.stored.chunks > 0) ?? sources[0])?.source_key ?? null);
  const [chunks, setChunks] = useState<readonly SystemLogChunk[] | null>(null);
  const [chunksError, setChunksError] = useState<string | null>(null);
  const [day, setDay] = useState<string | null>(null);
  const [chunkId, setChunkId] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [unmasked, setUnmasked] = useState(false);
  const [text, setText] = useState<TextState>(EMPTY_TEXT);
  const requestSeq = useRef(0);

  // The chunks of the selected source (newest first from the server); the days and the chunk list derive from them.
  useEffect(() => {
    if (!source) return;
    let cancelled = false;
    setChunks(null);
    setChunksError(null);
    setDay(null);
    setChunkId(null);
    setText(EMPTY_TEXT);
    getSystemLogChunks(deviceId, source)
      .then((r) => { if (!cancelled) setChunks(r.chunks); })
      .catch((err: unknown) => { if (!cancelled) setChunksError(describe(err)); });
    return () => { cancelled = true; };
  }, [deviceId, source]);

  const days = useMemo(() => Array.from(new Set((chunks ?? []).map((c) => c.log_day))).sort().reverse(), [chunks]);
  useEffect(() => {
    if (chunks && day === null && days.length > 0) setDay(days[0]);
  }, [chunks, day, days]);
  const dayChunks = useMemo(
    () => (chunks ?? []).filter((c) => c.log_day === day)
      .sort((a, b) => (a.collected_at ?? "").localeCompare(b.collected_at ?? "") || a.start_offset - b.start_offset),
    [chunks, day],
  );
  useEffect(() => {
    if (dayChunks.length > 0 && !dayChunks.some((c) => c.chunk_id === chunkId)) setChunkId(dayChunks[dayChunks.length - 1].chunk_id);
  }, [dayChunks, chunkId]);
  const chunk = dayChunks.find((c) => c.chunk_id === chunkId) ?? null;

  const loadPage = useCallback((target: SystemLogChunk, offset: number, replace: boolean) => {
    const seq = ++requestSeq.current;
    setText((prev) => ({ ...(replace ? EMPTY_TEXT : prev), chunkId: target.chunk_id, loading: true, error: null }));
    getSystemLogText(target.chunk_id, { offset, unmasked })
      .then((page) => {
        if (seq !== requestSeq.current) return;
        setText((prev) => {
          const merged = [...(replace ? [] : prev.lines), ...splitLines(page.text)];
          const capped = merged.length >= MAX_LOADED_LINES;
          return { chunkId: target.chunk_id, lines: merged, nextOffset: page.next_offset, eof: page.eof, masked: page.masked, loading: false, error: null, capped };
        });
      })
      .catch((err: unknown) => {
        if (seq !== requestSeq.current) return;
        const r = refusalOf(err);
        if (r.code === "SYSTEM_LOG_UNMASKED_NOT_PERMITTED") setUnmasked(false);
        setText((prev) => ({ ...prev, loading: false, error: r.code === "SYSTEM_LOG_CHUNK_PRUNED" ? "The bytes of this chunk were removed by retention." : describe(err) }));
      });
  }, [unmasked]);

  // First page of the selected chunk, again whenever the chunk or the masked/unmasked choice changes.
  useEffect(() => {
    if (!chunk || chunk.pruned) { setText(EMPTY_TEXT); return; }
    loadPage(chunk, 0, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chunk?.chunk_id, chunk?.pruned, loadPage]);

  const needle = filter.trim().toLowerCase();
  const shown = needle ? text.lines.filter((l) => l.toLowerCase().includes(needle)) : text.lines;
  const chunkIndex = dayChunks.findIndex((c) => c.chunk_id === chunkId);

  if (sources.length === 0) {
    return <EmptyPanel title="No line sources" body="This device has no source whose lines can be viewed here." />;
  }

  return (
    <Box sx={{ bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2, display: "flex", flexDirection: "column", gap: 1.5, minWidth: 0 }}>
      <Box role="group" aria-label="Log sources" sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
        {sources.map((s) => (
          <Box key={s.source_key} component="button" type="button" aria-pressed={s.source_key === source}
            onClick={() => setSource(s.source_key)}
            sx={{
              display: "inline-flex", alignItems: "center", gap: 0.75, cursor: "pointer", font: "inherit", fontSize: 13,
              px: 1.25, py: 0.5, borderRadius: "16px", color: m3.onSurface,
              border: `1px solid ${s.source_key === source ? m3.primary : m3.outline}`,
              bgcolor: s.source_key === source ? m3.secondaryContainer : "transparent",
            }}>
            {sourceLabel(s.source_key)}
            <StatusChip tone={statusTone(s.status)} label={s.status} dense />
            {s.fast_rotating && (
              <span title={`The device keeps about ${s.retention_horizon_seconds === null ? "an unknown amount" : `${(s.retention_horizon_seconds / 3600).toFixed(1)} h`} of this log, less than the collection interval allows for; gaps are likely.`}>
                <StatusChip tone="warn" label="fast rotating" dense />
              </span>
            )}
          </Box>
        ))}
      </Box>
      {sources.filter((s) => s.source_key === source).map((s) => (
        <Typography key={s.source_key} variant="caption" color="text.secondary">
          {s.last_run_at ? <>Last run <Ts at={s.last_run_at} relative seconds={false} /></> : "Never run"}
          {s.pending_bytes > 0 ? ` · ${formatBytes(s.pending_bytes)} still waiting on the device` : ""}
          {s.retention_horizon_seconds !== null ? ` · device keeps about ${(s.retention_horizon_seconds / 3600).toFixed(1)} h` : ""}
        </Typography>
      ))}

      <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap", alignItems: "flex-end" }}>
        <TextField select size="small" label="Day" value={day ?? ""} SelectProps={{ native: true }} sx={{ minWidth: 150 }}
          onChange={(e) => { setDay(e.target.value || null); setChunkId(null); }} disabled={days.length === 0}>
          {days.length === 0 ? <option value="">No chunks</option> : days.map((d) => <option key={d} value={d}>{d}</option>)}
        </TextField>
        <TextField select size="small" label="Chunk" value={chunkId ?? ""} SelectProps={{ native: true }} sx={{ minWidth: 260 }}
          onChange={(e) => setChunkId(e.target.value || null)} disabled={dayChunks.length === 0}>
          {dayChunks.length === 0 ? <option value="">No chunks</option> : dayChunks.map((c, i) => (
            <option key={c.chunk_id} value={c.chunk_id}>
              {`${i + 1}. ${c.collected_at ? formatTime(c.collected_at, false).slice(11) : "?"} · ${c.lines.toLocaleString("en-US")} lines · ${formatBytes(c.bytes)}${c.outcome ? ` · ${c.outcome}` : ""}${c.pruned ? " · removed" : ""}`}
            </option>
          ))}
        </TextField>
        <TextField size="small" label="Filter lines" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ minWidth: 200, flex: 1 }}
          inputProps={{ "aria-label": "Filter lines" }} />
      </Box>

      {canViewUnmasked && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
          <FormControlLabel
            control={<Switch checked={unmasked} onChange={(e) => setUnmasked(e.target.checked)} inputProps={{ "aria-label": "Show unmasked" }} />}
            label="Show unmasked" />
          <Typography variant="caption" color="text.secondary">
            Unmasked lines show real addresses and names. Every page you load this way is recorded on the audit ledger.
          </Typography>
        </Box>
      )}

      {chunksError && <StatePanel variant="error" title="Chunks unavailable" body="The chunk list could not be read." code={chunksError} />}
      {chunks && chunks.length === 0 && !chunksError && (
        <StatePanel variant="empty" title="Nothing stored for this source" body="No chunk is stored for this source yet, or retention removed them all." />
      )}
      {chunk && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          {text.masked !== null && (text.masked
            ? <StatusChip tone="neutral" label="Masked view" dense />
            : <StatusChip tone="warn" label="Unmasked view · recorded on the audit ledger" dense />)}
          <Typography variant="caption" color="text.secondary">
            {chunk.lines.toLocaleString("en-US")} lines · {formatBytes(chunk.bytes)} · {chunk.redaction_count} secret value{chunk.redaction_count === 1 ? "" : "s"} redacted
            {chunk.first_line_time && chunk.last_line_time ? ` · lines ${chunk.first_line_time} to ${chunk.last_line_time} (device time)` : ""}
            {chunk.flags.includes("LINE_SPLIT") ? " · begins or ends inside a line" : ""}
          </Typography>
        </Box>
      )}
      {chunk?.pruned && <StatePanel variant="empty" title="Removed by retention" body="The bytes of this chunk were removed. Its index row is kept as the record." />}
      {text.error && <StatePanel variant="error" title="Lines unavailable" body="The lines of this chunk could not be read." code={text.error}
        action={chunk ? <M3Button emphasis="outlined" onClick={() => loadPage(chunk, text.lines.length === 0 ? 0 : text.nextOffset, text.lines.length === 0)}>Retry</M3Button> : undefined} />}

      {chunk && !chunk.pruned && !text.error && (
        <Box>
          <Box data-testid="log-lines" role="log" aria-label="Log lines" sx={{
            fontFamily: MONO, fontSize: 12, lineHeight: 1.5, bgcolor: m3.scHigh, borderRadius: "8px", p: 1.25, maxHeight: 460, overflow: "auto",
            whiteSpace: "pre", minHeight: 60,
          }}>
            {text.loading && text.lines.length === 0 ? "Loading…" : shown.length === 0 ? (needle ? "No loaded line matches the filter." : "This chunk holds no lines.") :
              shown.map((line, i) => <div key={i} data-testid="log-line">{line}</div>)}
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, pt: 1, flexWrap: "wrap" }}>
            <Typography variant="caption" color="text.secondary">
              {needle ? `${shown.length} of ${text.lines.length} loaded lines match` : `${text.lines.length.toLocaleString("en-US")} lines loaded`}
              {text.capped ? ` (stopped at ${MAX_LOADED_LINES.toLocaleString("en-US")}; filter, or open another chunk)` : ""}
            </Typography>
            {!text.eof && !text.capped && (
              <M3Button emphasis="outlined" disabled={text.loading} onClick={() => loadPage(chunk, text.nextOffset, false)}>
                {text.loading ? "Loading…" : "Load more"}
              </M3Button>
            )}
            {text.eof && chunkIndex >= 0 && chunkIndex < dayChunks.length - 1 && (
              <M3Button emphasis="text" onClick={() => setChunkId(dayChunks[chunkIndex + 1].chunk_id)}>Next chunk</M3Button>
            )}
            {text.eof && <Typography variant="caption" color="text.secondary">End of chunk</Typography>}
            <Typography variant="caption" color="text.secondary">Pages are at most {formatBytes(maxPageBytes)}.</Typography>
          </Box>
        </Box>
      )}
    </Box>
  );
}
