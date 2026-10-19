import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  getSystemLogChunks, getSystemLogCrashFiles, getSystemLogGaps, getSystemLogHcp, getSystemLogSources, getSystemLogText, listJobs,
  type ApiError, type SystemLogChunk, type SystemLogCrashFiles, type SystemLogGap, type SystemLogHcp, type SystemLogSource,
  type SystemLogSources,
} from "../auth/adminApi";
import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, Unknown, isRestricted } from "../shell/States";
import { useJobWindow } from "../shell/JobWindow";
import type { Tone } from "../shell/tone";
import { formatTime, relativeAge } from "../shell/time";
import { lineDeviceTime, windowWords } from "./logLineTime";
import { jobStateWord } from "../shell/presenters";
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

/** A source status in sentence case (R7): `GAP` becomes "Gap", `TOO_LARGE` "Too large"; `OK` and `UNKNOWN` stay the words they are. */
export function sourceStatusWord(status: string): string {
  const value = (status ?? "").trim();
  if (!value) return "UNKNOWN";
  if (value === "OK" || value === "UNKNOWN") return value;
  const words = value.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
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

/**
 * A gap kind in the words an operator reads (UI review 2026-10-18, F-8): "Sequence gap suspected", and
 * "First collection: older content not fetched" for the normal first run rather than the jargon "initial baseline".
 * Local to this panel; the product-wide label map belongs to the presenter lane (TODO: D2's presenter helper).
 */
export function gapKindLabel(kind: string): string {
  switch (kind) {
    case "SEQ_GAP_SUSPECTED": return "Sequence gap suspected";
    case "INITIAL_BASELINE": return "First collection: older content not fetched";
    case "ROTATED_AWAY": return "Rotated away on the device";
    case "TRUNCATED": return "File truncated on the device";
    case "ANCHOR_MISMATCH": return "Saved position no longer matched";
    case "BACKLOG": return "Backlog on the device";
    case "COLLECTION_FAILED": return "Collection failed";
    case "PAGE_STALL": return "Paging stalled";
    case "RETENTION_SHORTENED_BY_CAP": return "Retention shortened by the storage cap";
    default: return kind.replace(/_/g, " ").toLowerCase().replace(/^./, (c) => c.toUpperCase());
  }
}

/** Grey for what is informational, amber for a suspected gap or a backlog, red-tinted only for bytes known to be lost. */
export function gapTone(kind: string): Tone {
  switch (kind) {
    case "INITIAL_BASELINE": return "neutral";
    case "SEQ_GAP_SUSPECTED": case "BACKLOG": return "warn";
    default: return "attn";
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

/** The newest system-log collection job of the device as the Jobs list records it (state, when, why it ended). */
export interface LatestCollection {
  readonly state: string;
  readonly at: string;
  readonly reason: string | null;
}

const COLLECT_JOB_TYPES: Readonly<Record<string, readonly string[]>> = {
  check_point: ["cp_system_log_collect"], palo_alto: ["pan_system_log_collect"],
};

/** Read from the same role-gated Jobs list as every other screen; a failed read is "unknown", never an error banner. */
async function latestCollection(deviceId: string, vendorHint: string | null | undefined): Promise<LatestCollection | null> {
  const types = COLLECT_JOB_TYPES[vendorHint ?? ""] ?? ["cp_system_log_collect", "pan_system_log_collect"];
  const pages = await Promise.all(types.map((type) => listJobs({ device_id: deviceId, job_type: type, page: 1, page_size: 1 }).catch(() => null)));
  const newest = pages.flatMap((page) => page?.items ?? []).sort((a, b) => b.submitted_at.localeCompare(a.submitted_at))[0];
  return newest ? { state: newest.state, at: newest.finished_at ?? newest.submitted_at, reason: newest.terminal_reason ?? null } : null;
}

/** A source whose newest run did not end cleanly: nothing could be read from it. */
const FAILED_SOURCE_STATUSES: ReadonlySet<string> = new Set(["UNKNOWN", "STORAGE_REFUSED"]);
const FAILED_JOB_STATES: ReadonlySet<string> = new Set(["FAILED", "REJECTED", "OUTCOME_UNKNOWN", "CANCELLED"]);

export type CollectionSubState =
  /** No run on record for this device and no collection job either. */
  | { readonly kind: "none" }
  /** A run (or a completed job) is on record and stored no line. `unrecorded`: the job completed but left no source row for this device. */
  | { readonly kind: "zero"; readonly unrecorded: boolean }
  | { readonly kind: "failed"; readonly reason: string }
  | { readonly kind: "stored" };

/** `partial:CONNECTION_FAILED` and the like, without the `partial:` echo. */
function jobReason(reason: string | null | undefined): string | null {
  const text = (reason ?? "").replace(/^partial:\s*/i, "").trim();
  return text.length > 0 ? text : null;
}

/**
 * The Logs tab's three empty sub-states (UI review 2026-10-18, F-6): no collection yet, collected with 0 lines, and
 * collection failed with its reason; anything else has lines to show. The decision uses only what the read routes and
 * the Jobs list already say. A completed collection job for THIS device with no stored run row is shown as
 * "collected, nothing recorded" rather than "No collection yet", so a run that happened is never denied.
 */
export function classifyCollection(data: SystemLogSources, latest: LatestCollection | null): CollectionSubState {
  const failedJob = latest !== null && FAILED_JOB_STATES.has(latest.state);
  if (data.collection_state === "NO_COLLECTION_YET") {
    if (latest === null) return { kind: "none" };
    if (failedJob) return { kind: "failed", reason: jobReason(latest.reason) ?? latest.state.toLowerCase().replace(/_/g, " ") };
    return latest.state === "COMPLETED" ? { kind: "zero", unrecorded: true } : { kind: "none" };
  }
  const lines = data.sources.reduce((n, source) => n + source.stored.lines, 0);
  if (lines > 0) return { kind: "stored" };
  const everySourceFailed = data.sources.length > 0 && data.sources.every((source) => FAILED_SOURCE_STATUSES.has(source.status));
  if (everySourceFailed || failedJob) {
    return { kind: "failed", reason: jobReason(latest?.reason) ?? (everySourceFailed ? "no source could be read" : "the collection job did not complete") };
  }
  return { kind: "zero", unrecorded: false };
}

/** `Next window: 12:00` as the title tail `next window 12:00`; null when the window is open or unknown. */
function nextWindowWords(label: string | null): string | null {
  const time = label?.match(/(\d{2}:\d{2})/)?.[1];
  return time ? `next window ${time}` : null;
}

function CollectionStateCard({ state, data, latest, onRefresh }: {
  readonly state: Exclude<CollectionSubState, { kind: "stored" }>;
  readonly data: SystemLogSources;
  readonly latest: LatestCollection | null;
  readonly onRefresh: () => void;
}) {
  const window = useJobWindow();
  const refresh = <M3Button emphasis="outlined" onClick={onRefresh}>Refresh</M3Button>;
  const lastAt = data.last_job?.at ?? latest?.at ?? null;
  const zone = "Times are Europe/Istanbul (GMT+3).";
  if (state.kind === "none") {
    const next = nextWindowWords(window.label);
    return (
      <StatePanel variant="empty" title={`No collection yet${next ? ` · ${next}` : ""}`}
        body="No system log has been collected from this device yet. Collection runs in the shared job windows (00:00, 06:00, 12:00 and 18:00 Europe/Istanbul); this tab fills in after the first run."
        action={refresh} />
    );
  }
  if (state.kind === "failed") {
    return (
      // A failed collection is a finding about the device, not a failed screen: announced as a status, never as an alert.
      <StatePanel variant="error" announce="status" title={`Collection failed · ${state.reason}`}
        body={<>Last collection {lastAt ? <Ts at={lastAt} relative /> : "time UNKNOWN"}{latest ? ` · ${jobStateWord(latest.state)}` : ""}: nothing was stored from it. The next window retries. {zone}</>}
        action={refresh} />
    );
  }
  return (
    <StatePanel variant="empty" title="Collected · 0 lines"
      body={state.unrecorded
        ? <>A collection job completed {latest ? <Ts at={latest.at} relative /> : null} but left no source record under this device, so no line is stored for it. If the job names a similar device (a cluster and its member carry different names), open that one. {zone}</>
        : <>Last collection {lastAt ? <Ts at={lastAt} relative /> : "time UNKNOWN"}{latest ? ` · ${jobStateWord(latest.state)}` : ""}: the device had no new log lines to read, so none is stored. The run is recorded; sources and gaps are below. {zone}</>}
      action={refresh} />
  );
}

export function DeviceLogsPanel({ deviceId, vendorHint }: { readonly deviceId: string; readonly vendorHint?: string | null }) {
  const sources = useFetchOnMount<SystemLogSources>(() => getSystemLogSources(deviceId), describe);
  // Wrapped so "no job on record" (null) is told apart from "not read yet" (no data).
  const latestJob = useFetchOnMount<{ readonly job: LatestCollection | null }>(() => latestCollection(deviceId, vendorHint).then((job) => ({ job })), describe);
  const refreshAll = () => { sources.refresh(); latestJob.refresh(); };

  if (sources.error) {
    if (isRestricted(sources.rawError)) return <RestrictedPanel area="System logs" />;
    return (
      <StatePanel variant="error" title="Logs unavailable" body="The log summary could not be read." code={sources.error}
        action={<M3Button emphasis="outlined" onClick={sources.refresh}>Retry</M3Button>} />
    );
  }
  // A device the collection does not cover is an answer of the read (200 with a state), not a refusal.
  if (sources.data?.collection_state === "DISABLED_ON_MANAGEMENT") {
    return (
      <StatePanel variant="empty" title="Collection disabled for management servers"
        body="System logs are not collected from management servers (the setting that allows it is off). Gateways managed by this server are collected on their own."
        code="SYSTEM_LOG_DISABLED_ON_MANAGEMENT" />
    );
  }
  if (sources.data?.collection_state === "VENDOR_UNSUPPORTED") {
    return <StatePanel variant="empty" title="Logs are not collected for this device" body="System-log collection covers Check Point and Palo Alto gateways." code="SYSTEM_LOG_VENDOR_UNSUPPORTED" />;
  }
  // The latest job read has no say until it has landed or failed (a failed read is unknown, not an error banner).
  if (!sources.data || (latestJob.data === null && latestJob.error === null)) return <EmptyPanel title="Logs" body="Loading…" />;
  const latest = latestJob.data?.job ?? null;
  const sub = classifyCollection(sources.data, latest);
  if (sub.kind !== "stored" && sources.data.collection_state === "NO_COLLECTION_YET") {
    return <CollectionStateCard state={sub} data={sources.data} latest={latest} onRefresh={refreshAll} />;
  }
  return <LogsBody key={deviceId} deviceId={deviceId} data={sources.data} latest={latest} onRefresh={refreshAll}
    emptyState={sub.kind === "stored" ? undefined : <CollectionStateCard state={sub} data={sources.data} latest={latest} onRefresh={refreshAll} />} />;
}

const SIDE_ID = { gaps: "logs-gaps", crash: "logs-crash", hcp: "logs-hcp" } as const;

function jumpTo(id: string): void {
  document.getElementById(id)?.scrollIntoView?.({ behavior: "smooth", block: "start" });
}

/** A phrase that is also the way to the panel it talks about. */
function JumpLink({ target, children }: { readonly target: string; readonly children: React.ReactNode }) {
  return (
    <Box component="button" type="button" onClick={() => jumpTo(target)}
      sx={{ all: "unset", cursor: "pointer", color: "inherit", textDecoration: "underline", textDecorationColor: m3.outline, textUnderlineOffset: 3,
        "&:hover": { color: m3.primary }, "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 } }}>
      {children}
    </Box>
  );
}

function LogsBody({ deviceId, data, latest, onRefresh, emptyState }: {
  readonly deviceId: string; readonly data: SystemLogSources; readonly latest: LatestCollection | null; readonly onRefresh: () => void;
  /** Collected but nothing stored, or every source failed: this card takes the place of the line viewer. */
  readonly emptyState?: React.ReactNode;
}) {
  const gaps = useFetchOnMount(() => getSystemLogGaps(deviceId), describe);
  const crash = useFetchOnMount<SystemLogCrashFiles>(() => getSystemLogCrashFiles(deviceId), describe);
  const hcp = useFetchOnMount<SystemLogHcp>(() => getSystemLogHcp(deviceId), describe);
  const otherSources = data.sources.filter((s) => !(LINE_SOURCES as readonly string[]).includes(s.source_key));
  const lineSources = data.sources.filter((s) => (LINE_SOURCES as readonly string[]).includes(s.source_key));

  return (
    // One layout for Check Point and Palo Alto (the source set is the only difference). The side cards sit beside the
    // lines only when the PANE is wide: a container query, not the browser width (UI review 2026-10-18, F-8).
    <Stack spacing={2} data-testid="device-logs" sx={{ containerType: "inline-size", minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 2 }}>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          Logs · system logs collected from the device, stored encrypted and masked on screen by default.
        </Typography>
        <M3Button emphasis="text" onClick={onRefresh}>Refresh</M3Button>
      </Box>
      <SummaryStrip data={data} latest={latest} />
      <AttentionLine gaps={gaps} crash={crash} />
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "minmax(0, 1fr)", alignItems: "start",
        "@container (min-width: 980px)": { gridTemplateColumns: "minmax(0, 2fr) minmax(280px, 1fr)" } }}>
        {emptyState ?? <LogViewer deviceId={deviceId} sources={lineSources} canViewUnmasked={data.can_view_unmasked} maxPageBytes={data.max_page_bytes} />}
        <Stack spacing={2}>
          <SidePanel id={SIDE_ID.gaps} title="Gaps">
            {gaps.error ? <Typography variant="body2" color="text.secondary">Gaps unavailable ({gaps.error}).</Typography>
              : !gaps.data ? <Typography variant="body2">Loading…</Typography>
              : gaps.data.gaps.length === 0 ? <Typography variant="body2" color="text.secondary">No gaps recorded.</Typography>
              : <Stack spacing={1.25}>{gaps.data.gaps.map((g) => <GapRow key={g.gap_id} gap={g} />)}</Stack>}
            {gaps.data?.truncated && <Typography variant="caption" color="text.secondary">Showing the newest gaps only.</Typography>}
          </SidePanel>
          <SidePanel id={SIDE_ID.crash} title="Crash and dump files">
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
          <SidePanel id={SIDE_ID.hcp} title="HCP reports">
            {hcp.error ? <Typography variant="body2" color="text.secondary">HCP reports unavailable ({hcp.error}).</Typography>
              : !hcp.data ? <Typography variant="body2">Loading…</Typography>
              : hcp.data.files.length === 0 ? <Typography variant="body2" color="text.secondary">No HCP report has been collected.</Typography>
              : <Stack spacing={1}>{hcp.data.files.map((f) => (
                  <Box key={f.file_id} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                    <StatusChip tone={f.status === "STORED" ? "neutral" : "bad"} label={f.status === "STORED" ? "Stored" : f.status === "CORRUPT" ? "Unreadable archive" : f.status} dense />
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
                    <StatusChip tone={statusTone(s.status)} label={sourceStatusWord(s.status)} dense />
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

function SidePanel({ title, id, children }: { readonly title: string; readonly id?: string; readonly children: React.ReactNode }) {
  return (
    <Box id={id} sx={{ bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2, display: "flex", flexDirection: "column", gap: 1.25, scrollMarginTop: 16 }}>
      <Typography variant="subtitle2">{title}</Typography>
      {children}
    </Box>
  );
}

function GapRow({ gap }: { readonly gap: SystemLogGap }) {
  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <StatusChip tone={gapTone(gap.kind)} label={gapKindLabel(gap.kind)} dense />
        <Typography variant="caption" color="text.secondary">{sourceLabel(gap.source_key)} · <Ts at={gap.recorded_at} seconds={false} /></Typography>
      </Box>
      <Typography variant="body2">{gapWords(gap)}</Typography>
    </Box>
  );
}

/** `HH:MM` of an instant in the display zone, for a short clause such as "(config 00:15)". */
function clock(at: string | null | undefined): string {
  return at ? formatTime(at, false).slice(11) : "time UNKNOWN";
}

/**
 * Gaps and the crash check as one neutral sentence, each part a link to its panel below (UI review 2026-10-18, F-8).
 * Grey on purpose: a first collection or a quiet crash directory is not an alarm. Only a NEW crash or dump file adds a
 * red word, because that is a finding about the device.
 */
function AttentionLine({ gaps, crash }: {
  readonly gaps: { readonly data: { readonly gaps: readonly SystemLogGap[] } | null; readonly error: string | null };
  readonly crash: { readonly data: SystemLogCrashFiles | null; readonly error: string | null };
}) {
  const kinds = new Map<string, SystemLogGap[]>();
  for (const g of gaps.data?.gaps ?? []) kinds.set(g.kind, [...(kinds.get(g.kind) ?? []), g]);
  const newCrash = crash.data?.counts?.NEW ?? 0;
  const parts: React.ReactNode[] = [];
  if (gaps.error) parts.push(<span key="g">Gaps unavailable</span>);
  else if (!gaps.data) parts.push(<span key="g">Gaps loading…</span>);
  else if (kinds.size === 0) parts.push(<JumpLink key="g" target={SIDE_ID.gaps}>No gaps recorded</JumpLink>);
  else {
    for (const [kind, list] of kinds) {
      const where = list.map((g) => `${sourceLabel(g.source_key)} ${clock(g.recorded_at)}`).join(", ");
      parts.push(<JumpLink key={kind} target={SIDE_ID.gaps}>{gapKindLabel(kind)}{list.length > 1 ? ` ×${list.length}` : ""} ({where})</JumpLink>);
    }
  }
  if (crash.error) parts.push(<span key="c">Crash and dump check unavailable</span>);
  else if (!crash.data) parts.push(<span key="c">Crash and dump check loading…</span>);
  else if (newCrash === 0) parts.push(<JumpLink key="c" target={SIDE_ID.crash}>No new crash or dump files</JumpLink>);
  return (
    <Box role="region" aria-label="Attention" sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: 1, rowGap: 0.5, color: m3.onSurfaceVar, fontSize: 13.5 }}>
      {parts.map((part, i) => (
        <Box component="span" key={i} sx={{ display: "inline-flex", alignItems: "center", columnGap: 1 }}>
          {i > 0 && <Box component="span" aria-hidden sx={{ color: m3.outline }}>·</Box>}
          {part}
        </Box>
      ))}
      {newCrash > 0 && (
        <Box component="span" sx={{ display: "inline-flex", alignItems: "center", columnGap: 1 }}>
          {parts.length > 0 && <Box component="span" aria-hidden sx={{ color: m3.outline }}>·</Box>}
          <JumpLink target={SIDE_ID.crash}><StatusChip tone="bad" label={`${newCrash} new crash or dump file${newCrash === 1 ? "" : "s"}`} dense /></JumpLink>
        </Box>
      )}
    </Box>
  );
}

/**
 * The first thing an operator needs (UI review 2026-10-18, F-8): when the last collection ran and how it ended, the
 * device-time window it covered, and for each source what its newest run said and how many lines are stored. Counts come
 * from the sources route (its per-source stored totals); severity is deliberately absent: no parsed severity exists.
 */
function SummaryStrip({ data, latest }: {
  readonly data: SystemLogSources; readonly latest: LatestCollection | null;
}) {
  const job = data.last_job;
  const totals = data.sources.reduce((acc, s) => ({ chunks: acc.chunks + s.stored.chunks, bytes: acc.bytes + s.stored.bytes, lines: acc.lines + s.stored.lines }), { chunks: 0, bytes: 0, lines: 0 });
  const window = windowWords(job?.window_from, job?.window_to);
  const outcomes = job ? Object.entries(job.outcomes) : [];
  return (
    <Box role="region" aria-label="Log summary" sx={{ display: "flex", flexDirection: "column", gap: 1.25, bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2 }}>
      <Typography variant="body2" data-testid="last-collection" sx={{ display: "flex", flexWrap: "wrap", columnGap: 1, alignItems: "baseline" }}>
        <Box component="span" sx={{ fontWeight: 600 }}>Last collection</Box>
        {job?.at
          ? <><Ts at={job.at} seconds={false} /><Box component="span" sx={{ color: m3.onSurfaceVar }}>· {relativeAge(job.at)}</Box></>
          : <Unknown reason="No collection time is recorded for the newest run." />}
        <Box component="span" sx={{ color: m3.onSurfaceVar }} title={latest?.state}>· {latest ? jobStateWord(latest.state) : "job state UNKNOWN"}</Box>
        <Box component="span" sx={{ color: m3.onSurfaceVar }}>· {window ? `window ${window} device time` : "window of the newest lines not known"}</Box>
      </Typography>
      {/* The per-source chips live once, on the run selector below (R7): the strip keeps the sentence and the counts. */}
      <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", columnGap: 2, rowGap: 0.75 }}>
        <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
          <Typography variant="caption" color="text.secondary">Sources in that run</Typography>
          {outcomes.length > 0
            // a count is grey; a source that did not end cleanly keeps its state colour and its word
            ? outcomes.map(([outcome, n]) => (
                <StatusChip key={outcome} tone={outcome === "COLLECTED" || outcome === "NO_NEW_DATA" ? "neutral" : statusTone(outcome)}
                  label={`${outcome.replace(/_/g, " ").toLowerCase()} ${n}`} dense />))
            : <Typography variant="body2">UNKNOWN</Typography>}
        </Box>
        <Typography variant="caption" color="text.secondary">
          Stored now · {totals.chunks} chunks · {totals.lines.toLocaleString("en-US")} lines · {formatBytes(totals.bytes)}
        </Typography>
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
  /** The masking rule set of the last masked page (`logmask-v3`); null when unknown. */
  readonly maskRules: string | null;
  readonly loading: boolean;
  readonly error: string | null;
  readonly capped: boolean;
}

const EMPTY_TEXT: TextState = { chunkId: null, lines: [], nextOffset: 0, eof: false, masked: null, maskRules: null, loading: false, error: null, capped: false };

function splitLines(text: string): string[] {
  if (text === "") return [];
  const lines = text.split("\n");
  if (lines[lines.length - 1] === "") lines.pop();
  return lines;
}

/** One raw line: the device time in a fixed column, the line itself on one row, the whole line on click (nothing is cut away). */
function RawLine({ line }: { readonly line: string }) {
  const [open, setOpen] = useState(false);
  const time = lineDeviceTime(line);
  return (
    <Box data-testid="log-line" role="button" tabIndex={0} aria-expanded={open} onClick={() => setOpen((v) => !v)}
      onKeyDown={(e: React.KeyboardEvent) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setOpen((v) => !v); } }}
      sx={{ display: "grid", gridTemplateColumns: "150px minmax(0, 1fr)", columnGap: 1.5, cursor: "pointer", borderRadius: "4px", px: 0.5,
        "&:hover": { bgcolor: m3.scHighest }, "&:focus-visible": { outline: `2px solid ${m3.primary}` } }}>
      <Box component="span" data-testid="log-line-time" title={time ? "Device time of this line" : "No device time on this line"}
        sx={{ color: m3.onSurfaceVar, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{time ?? ""}</Box>
      <Box component="span" sx={open ? { whiteSpace: "pre-wrap", overflowWrap: "anywhere" } : { whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{line}</Box>
    </Box>
  );
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
          return { chunkId: target.chunk_id, lines: merged, nextOffset: page.next_offset, eof: page.eof, masked: page.masked, maskRules: page.mask_rules, loading: false, error: null, capped };
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
      {/* The one source chip row (R7): each chip names the source, its status word and how many lines are stored. */}
      <Box role="group" aria-label="Log sources" sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
        {sources.map((s) => (
          <Box key={s.source_key} component="button" type="button" aria-pressed={s.source_key === source}
            aria-label={`${sourceLabel(s.source_key)} · ${sourceStatusWord(s.status)} · ${s.stored.lines.toLocaleString("en-US")} lines`}
            onClick={() => setSource(s.source_key)}
            sx={{
              display: "inline-flex", alignItems: "center", gap: 0.75, cursor: "pointer", font: "inherit", fontSize: 13,
              px: 1.25, py: 0.5, borderRadius: "16px", color: m3.onSurface,
              border: `1px solid ${s.source_key === source ? m3.primary : m3.outline}`,
              bgcolor: s.source_key === source ? m3.secondaryContainer : "transparent",
            }}>
            {sourceLabel(s.source_key)}
            <StatusChip tone={statusTone(s.status)} label={sourceStatusWord(s.status)} dense />
            <StatusChip tone="neutral" label={`${s.stored.lines.toLocaleString("en-US")} lines`} dense />
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

      {/* The pickers read as one sentence: "Day 2026-10-18 · run 2 of 4 · 06:13 · 13 lines · 7.5 KiB" (F-8). */}
      <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap", alignItems: "flex-end" }}>
        <TextField select size="small" label="Day" value={day ?? ""} SelectProps={{ native: true }} sx={{ minWidth: 150 }}
          onChange={(e) => { setDay(e.target.value || null); setChunkId(null); }} disabled={days.length === 0}>
          {days.length === 0 ? <option value="">No chunks</option> : days.map((d) => <option key={d} value={d}>{d}</option>)}
        </TextField>
        <TextField select size="small" label="Run" value={chunkId ?? ""} SelectProps={{ native: true }} sx={{ minWidth: 260 }}
          onChange={(e) => setChunkId(e.target.value || null)} disabled={dayChunks.length === 0}>
          {dayChunks.length === 0 ? <option value="">No chunks</option> : dayChunks.map((c, i) => (
            <option key={c.chunk_id} value={c.chunk_id}>
              {`${i + 1} of ${dayChunks.length} · ${clock(c.collected_at)} · ${c.lines.toLocaleString("en-US")} lines · ${formatBytes(c.bytes)}${c.outcome ? ` · ${c.outcome}` : ""}${c.pruned ? " · removed" : ""}`}
            </option>
          ))}
        </TextField>
        <TextField size="small" label="Filter lines" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ minWidth: 200, flex: 1 }}
          inputProps={{ "aria-label": "Filter lines" }} />
        {chunk && text.masked !== null && (text.masked
          ? <StatusChip tone="neutral" label={text.maskRules ? `Masked view · rules ${text.maskRules}` : "Masked view"} dense />
          : <StatusChip tone="warn" label="Unmasked view · recorded on the audit ledger" dense />)}
      </Box>
      {chunk && day && (
        <Typography variant="body2" data-testid="picker-sentence" sx={{ color: m3.onSurfaceVar }}>
          Day {day} · run {chunkIndex + 1} of {dayChunks.length} · {clock(chunk.collected_at)} · {chunk.lines.toLocaleString("en-US")} lines · {formatBytes(chunk.bytes)}
        </Typography>
      )}

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
        <Typography variant="caption" color="text.secondary">
          {chunk.redaction_count} secret value{chunk.redaction_count === 1 ? "" : "s"} redacted
          {chunk.first_line_time && chunk.last_line_time ? ` · lines ${chunk.first_line_time} to ${chunk.last_line_time} (device time)` : ""}
          {chunk.flags.includes("LINE_SPLIT") ? " · begins or ends inside a line" : ""}
        </Typography>
      )}
      {chunk?.pruned && <StatePanel variant="empty" title="Removed by retention" body="The bytes of this chunk were removed. Its index row is kept as the record." />}
      {text.error && <StatePanel variant="error" title="Lines unavailable" body="The lines of this chunk could not be read." code={text.error}
        action={chunk ? <M3Button emphasis="outlined" onClick={() => loadPage(chunk, text.lines.length === 0 ? 0 : text.nextOffset, text.lines.length === 0)}>Retry</M3Button> : undefined} />}

      {chunk && !chunk.pruned && !text.error && (
        <Box>
          <Box data-testid="log-lines" role="log" aria-label="Log lines" sx={{
            fontFamily: MONO, fontSize: 12, lineHeight: 1.5, bgcolor: m3.scHigh, borderRadius: "8px", p: 1.25, maxHeight: 460, overflow: "auto",
            minHeight: 60,
          }}>
            {text.loading && text.lines.length === 0 ? "Loading…" : shown.length === 0 ? (needle ? "No loaded line matches the filter." : "This chunk holds no lines.") :
              shown.map((line, i) => <RawLine key={`${chunk.chunk_id}:${i}`} line={line} />)}
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
