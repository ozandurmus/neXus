import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import Box from "@mui/material/Box";
import FormControlLabel from "@mui/material/FormControlLabel";
import IconButton from "@mui/material/IconButton";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import {
  getSystemLogChunks, getSystemLogCrashFiles, getSystemLogGaps, getSystemLogHcp, getSystemLogSources, getSystemLogSpikeData,
  getSystemLogText, listJobs, type ApiError, type SystemLogChunk, type SystemLogCrashFiles, type SystemLogGap, type SystemLogHcp,
  type SystemLogSource, type SystemLogSources, type SystemLogSpikeData,
} from "../auth/adminApi";
import { EmptyPanel } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { usePreferenceFlag } from "../shell/preference";
import { RestrictedPanel, StatePanel, Ts, Unknown, isRestricted } from "../shell/States";
import { useJobWindow } from "../shell/JobWindow";
import type { Tone } from "../shell/tone";
import { formatTime, relativeAge } from "../shell/time";
import { KpiCard } from "../shell/KpiCard";
import { lineDeviceTime, lineSeverity, windowWords, type LineSeverity } from "./logLineTime";
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

const SIDE_ID = { gaps: "logs-gaps", crash: "logs-crash", hcp: "logs-hcp", spike: "logs-spike" } as const;

/** The Logs view tabs, by position: the line viewer first, the collection health panels beside it. */
const VIEW = { lines: 0, health: 1 } as const;

function jumpTo(id: string): void {
  document.getElementById(id)?.scrollIntoView?.({ behavior: "smooth", block: "start" });
}

/** A phrase that is also the way to the panel it talks about: it opens Collection health and scrolls to that panel. */
function JumpLink({ target, onJump, children }: { readonly target: string; readonly onJump: (id: string) => void; readonly children: React.ReactNode }) {
  return (
    <Box component="button" type="button" onClick={() => onJump(target)}
      sx={{ all: "unset", cursor: "pointer", color: "inherit", textDecoration: "underline", textDecorationColor: m3.outline, textUnderlineOffset: 3,
        "&:hover": { color: m3.primary }, "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 } }}>
      {children}
    </Box>
  );
}

/** A small text control that opens or hides something on the same card (`Run details`, `Wrap`). */
function TextToggle({ pressed, label, onClick, expands = false, children }: {
  readonly pressed: boolean; readonly label?: string; readonly onClick: () => void;
  /** True when the control discloses content below it (aria-expanded) rather than switching a mode (aria-pressed). */
  readonly expands?: boolean; readonly children: React.ReactNode;
}) {
  const state = expands ? { "aria-expanded": pressed } : { "aria-pressed": pressed };
  return (
    <Box component="button" type="button" aria-label={label} onClick={onClick} {...state}
      sx={{ all: "unset", cursor: "pointer", display: "inline-flex", alignItems: "center", gap: 0.5, fontSize: 13, fontWeight: 500, lineHeight: "20px",
        px: 1, py: "3px", borderRadius: "8px", color: pressed && !expands ? m3.onSecondaryContainer : m3.primary,
        bgcolor: pressed && !expands ? m3.secondaryContainer : "transparent", border: `1px solid ${pressed && !expands ? m3.secondaryContainer : "transparent"}`,
        whiteSpace: "nowrap", "&:hover": { bgcolor: pressed && !expands ? m3.secondaryContainer : m3.scHigh },
        "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 1 } }}>
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
  // The viewer's state lives here, above the tabs, so a look at Collection health and back costs no reload and loses no filter.
  const vm = useLogViewer(deviceId, lineSources);
  const [view, setView] = useState<number>(VIEW.lines);
  const [jumpReq, setJumpReq] = useState<{ readonly id: string; readonly seq: number } | null>(null);
  const jump = useCallback((id: string) => {
    setView(VIEW.health);
    setJumpReq((prev) => ({ id, seq: (prev?.seq ?? 0) + 1 }));
  }, []);
  useEffect(() => {
    if (jumpReq && view === VIEW.health) jumpTo(jumpReq.id);
  }, [jumpReq, view]);
  const levels = useMemo(() => countLevels(vm.text.lines), [vm.text.lines]);
  const [detailsOpen, setDetailsOpen] = useState(false);

  return (
    // One layout for Check Point and Palo Alto (the source set is the only difference). The lines are the content
    // (PO 2026-10-20): one summary line, one attention sentence, then the viewer on its own tab with the room; the
    // gaps, crash files, HCP reports and other collection sit on the Collection health tab and never squeeze it.
    <Stack spacing={1.5} data-testid="device-logs" sx={{ containerType: "inline-size", minWidth: 0, flex: 1, minHeight: 0 }}>
      <LogFacts data={data} latest={latest} crash={crash} levels={levels} loaded={vm.text.lines.length} loading={vm.text.loading} />
      <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: 1.5, rowGap: 0.5 }}>
        <Box sx={{ flex: "1 1 320px", minWidth: 0 }}><AttentionLine gaps={gaps} crash={crash} onJump={jump} /></Box>
        <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 0.5 }}>
          <TextToggle pressed={detailsOpen} expands onClick={() => setDetailsOpen((v) => !v)}>Run details <Icon name={detailsOpen ? "chevron-left" : "chevron-right"} size={14} /></TextToggle>
          <M3Button emphasis="text" onClick={onRefresh}>Refresh</M3Button>
        </Box>
      </Box>
      {detailsOpen && <RunDetails data={data} />}
      <M3Tabs ariaLabel="Logs view" value={view} onChange={setView} tabs={[
        { label: "Lines", panel: emptyState ?? <LogViewer vm={vm} sources={lineSources} canViewUnmasked={data.can_view_unmasked} maxPageBytes={data.max_page_bytes} gaps={gaps.data?.gaps ?? []} onJump={jump} /> },
        { label: "Collection health", panel: <CollectionHealth deviceId={deviceId} data={data} gaps={gaps} crash={crash} hcp={hcp} otherSources={otherSources} /> },
      ]} />
    </Stack>
  );
}

/** The gaps, crash files, HCP reports, Spike data and whole-file sources: two columns when the pane is wide, one otherwise. */
function CollectionHealth({ deviceId, data, gaps, crash, hcp, otherSources }: {
  readonly deviceId: string; readonly data: SystemLogSources;
  readonly gaps: { readonly data: { readonly gaps: readonly SystemLogGap[]; readonly truncated: boolean } | null; readonly error: string | null };
  readonly crash: { readonly data: SystemLogCrashFiles | null; readonly error: string | null };
  readonly hcp: { readonly data: SystemLogHcp | null; readonly error: string | null };
  readonly otherSources: readonly SystemLogSource[];
}) {
  return (
    <Box data-testid="collection-health" sx={{ display: "grid", gap: 2, gridTemplateColumns: "minmax(0, 1fr)", alignItems: "start",
      "@container (min-width: 900px)": { gridTemplateColumns: "repeat(2, minmax(0, 1fr))" } }}>
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
      {data.sources.some((s) => s.source_key === "cp_spike_data") && <SpikeDataPanel deviceId={deviceId} />}
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
    </Box>
  );
}

const SPIKE_STATE_LABELS: Record<string, string> = {
  COLLECTED: "Collected", NOTHING_TO_FETCH: "Nothing to fetch", TOO_LARGE: "Too large, not fetched",
  NAME_REJECTED: "Unexpected name, not listed", ABSENT: "Gone before it was read",
};

/**
 * Spike Detective data (section 8.3): the directories collected since monitoring began, by name class, device time and
 * size. Directories present before monitoring are counted only; the archives stay encrypted and are not served here.
 */
function SpikeDataPanel({ deviceId }: { readonly deviceId: string }) {
  const spike = useFetchOnMount<SystemLogSpikeData>(() => getSystemLogSpikeData(deviceId), describe);
  const baseline = spike.data?.counts.BASELINE ?? 0;
  return (
    <SidePanel id={SIDE_ID.spike} title="Spike Detective data">
      {spike.error ? <Typography variant="body2" color="text.secondary">Spike Detective data unavailable ({spike.error}).</Typography>
        : !spike.data ? <Typography variant="body2">Loading…</Typography>
        : spike.data.directories.length === 0 ? <Typography variant="body2" color="text.secondary">No new Spike Detective directory has been collected.</Typography>
        : <Stack spacing={1}>{spike.data.directories.map((d) => (
            <Box key={d.directory_id} sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <StatusChip tone={d.state === "TOO_LARGE" || d.state === "NAME_REJECTED" ? "warn" : "neutral"}
                  label={SPIKE_STATE_LABELS[d.state] ?? d.state} dense />
                {d.pruned && <StatusChip tone="neutral" label="Removed by retention" dense />}
                <Typography variant="caption" color="text.secondary">
                  <Ts at={epochIso(d.mtime_epoch)} seconds={false} /> · {d.fetched_files} of {d.member_count} files · {formatBytes(d.fetched_bytes)}
                </Typography>
              </Box>
              {d.member_classes.length > 0 && (
                <Typography variant="caption" color="text.secondary" sx={{ fontFamily: MONO, overflowWrap: "anywhere" }}>
                  {d.member_classes.map((c) => `${c.class}${c.count > 1 ? ` ×${c.count}` : ""} ${formatBytes(c.bytes)}${c.fetched > 0 ? " (stored)" : ""}`).join(" · ")}
                </Typography>
              )}
            </Box>
          ))}</Stack>}
      <Typography variant="caption" color="text.secondary">
        {baseline > 0 ? `${baseline} director${baseline === 1 ? "y was" : "ies were"} present before monitoring and ${baseline === 1 ? "is" : "are"} not fetched. ` : ""}
        Names are shown as classes. Archives are kept encrypted{spike.data?.retention_days ? ` for ${spike.data.retention_days} days` : ""} and are not served from this screen.
      </Typography>
    </SidePanel>
  );
}

function SidePanel({ title, id, children }: { readonly title: string; readonly id?: string; readonly children: React.ReactNode }) {
  return (
    <Box id={id} sx={{ bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2, display: "flex", flexDirection: "column", gap: 1.25, scrollMarginTop: 16, minWidth: 0 }}>
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
 * Gaps and the crash check as one neutral sentence, each part a link to its panel on Collection health (UI review
 * 2026-10-18, F-8). Grey on purpose: a first collection or a quiet crash directory is not an alarm. Only a NEW crash or
 * dump file adds a red word, because that is a finding about the device.
 */
function AttentionLine({ gaps, crash, onJump }: {
  readonly gaps: { readonly data: { readonly gaps: readonly SystemLogGap[] } | null; readonly error: string | null };
  readonly crash: { readonly data: SystemLogCrashFiles | null; readonly error: string | null };
  readonly onJump: (id: string) => void;
}) {
  const kinds = new Map<string, SystemLogGap[]>();
  for (const g of gaps.data?.gaps ?? []) kinds.set(g.kind, [...(kinds.get(g.kind) ?? []), g]);
  const newCrash = crash.data?.counts?.NEW ?? 0;
  const parts: React.ReactNode[] = [];
  if (gaps.error) parts.push(<span key="g">Gaps unavailable</span>);
  else if (!gaps.data) parts.push(<span key="g">Gaps loading…</span>);
  else if (kinds.size === 0) parts.push(<JumpLink key="g" target={SIDE_ID.gaps} onJump={onJump}>No gaps recorded</JumpLink>);
  else {
    for (const [kind, list] of kinds) {
      const where = list.map((g) => `${sourceLabel(g.source_key)} ${clock(g.recorded_at)}`).join(", ");
      parts.push(<JumpLink key={kind} target={SIDE_ID.gaps} onJump={onJump}>{gapKindLabel(kind)}{list.length > 1 ? ` ×${list.length}` : ""} ({where})</JumpLink>);
    }
  }
  if (crash.error) parts.push(<span key="c">Crash and dump check unavailable</span>);
  else if (!crash.data) parts.push(<span key="c">Crash and dump check loading…</span>);
  else if (newCrash === 0) parts.push(<JumpLink key="c" target={SIDE_ID.crash} onJump={onJump}>No new crash or dump files</JumpLink>);
  return (
    <Box role="region" aria-label="Attention" sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: 1, rowGap: 0.5, color: m3.onSurfaceVar, fontSize: 13.5, px: 0.5 }}>
      {parts.map((part, i) => (
        <Box component="span" key={i} sx={{ display: "inline-flex", alignItems: "center", columnGap: 1 }}>
          {i > 0 && <Box component="span" aria-hidden sx={{ color: m3.outline }}>·</Box>}
          {part}
        </Box>
      ))}
      {newCrash > 0 && (
        <Box component="span" sx={{ display: "inline-flex", alignItems: "center", columnGap: 1 }}>
          {parts.length > 0 && <Box component="span" aria-hidden sx={{ color: m3.outline }}>·</Box>}
          <JumpLink target={SIDE_ID.crash} onJump={onJump}><StatusChip tone="bad" label={`${newCrash} new crash or dump file${newCrash === 1 ? "" : "s"}`} dense /></JumpLink>
        </Box>
      )}
    </Box>
  );
}

/** How many loaded lines write an error-class or a warning-class level token of their own (never inferred). */
export interface LevelCounts { readonly error: number; readonly warning: number }

export function countLevels(lines: readonly string[]): LevelCounts {
  let error = 0;
  let warning = 0;
  for (const line of lines) {
    const tone = lineSeverity(line)?.tone;
    if (tone === "bad") error++;
    else if (tone === "warn") warning++;
  }
  return { error, warning };
}

/** `06:13` of an instant in the display zone, for a KPI figure; the full stamp goes on hover. */
function clockFigure(at: string | null | undefined): string | null {
  return at ? formatTime(at, false).slice(11, 16) : null;
}

/**
 * The four facts of the mockup "Cihaz logları için dört ekran" (2026-10-17), as the product's KPI row and only as far
 * as the stored data supports them (coordinator 2026-10-20): the last collection with its device-time window; the
 * error and warning lines counted from the level tokens the loaded lines themselves write (the previous window is not
 * loaded, so no comparison is claimed); process restarts, UNKNOWN until the contract documents a cpwd restart line
 * shape (vendor semantics law: nothing is counted from a guessed text); and the new crash or dump files, red only
 * when there is one.
 */
function LogFacts({ data, latest, crash, levels, loaded, loading }: {
  readonly data: SystemLogSources; readonly latest: LatestCollection | null;
  readonly crash: { readonly data: SystemLogCrashFiles | null; readonly error: string | null };
  readonly levels: LevelCounts; readonly loaded: number; readonly loading: boolean;
}) {
  const job = data.last_job;
  const totals = data.sources.reduce((acc, s) => ({ chunks: acc.chunks + s.stored.chunks, bytes: acc.bytes + s.stored.bytes, lines: acc.lines + s.stored.lines }), { chunks: 0, bytes: 0, lines: 0 });
  const window = windowWords(job?.window_from, job?.window_to);
  const stateWord = latest ? jobStateWord(latest.state) : "job state UNKNOWN";
  const stored = `${totals.lines.toLocaleString("en-US")} lines · ${formatBytes(totals.bytes)} in ${totals.chunks} chunks`;
  const newCrash = crash.data?.counts?.NEW ?? null;
  const before = crash.data?.counts?.PRESENT_BEFORE_MONITORING ?? 0;
  return (
    // The product's KPI cards in a row of four that stays four across the object pane (the shared row's 230 px minimum
    // would fold the fourth card under the others beside the device list at 1440 px).
    <Box role="region" aria-label="Log summary" sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))" }}>
      <Box data-testid="last-collection" sx={{ display: "contents" }}>
        <KpiCard label="Last collection" value={clockFigure(job?.at)} unit={job?.at ? relativeAge(job.at) : undefined}
          state={job?.at ? "ok" : "unknown"} reason="No collection time is recorded for the newest run."
          detail={window ? `window ${window} device time` : "window of the newest lines not known"}
          sentence={`${stateWord} · ${stored}`} />
      </Box>
      <KpiCard label="Error / warning lines" value={loaded > 0 ? `${levels.error} / ${levels.warning}` : null} unit={loaded > 0 ? "error / warning" : undefined}
        state={loaded > 0 ? "ok" : "unknown"} reason={loading ? "Lines are still loading." : "No line of this run is loaded yet."}
        detail={loaded > 0 ? `of ${loaded.toLocaleString("en-US")} loaded lines of this run` : undefined}
        sentence="from the lines' own level tokens · no previous window loaded" />
      <KpiCard label="Process restarts" value={null} state="unknown" reason="The contract documents no cpwd restart line shape yet, so nothing is counted from a guessed text."
        sentence="cpwd.elg · line shape not documented; not counted" />
      <KpiCard label="New crash / dump files" value={crash.error ? null : newCrash} state={crash.error || newCrash === null ? "unknown" : "ok"}
        reason={crash.error ? `The crash and dump check could not be read (${crash.error}).` : "The crash and dump check has not answered yet."}
        unit={newCrash !== null && !crash.error ? (newCrash === 1 ? "new file" : "new files") : undefined}
        badge={newCrash !== null && newCrash > 0 && !crash.error ? <StatusChip tone="bad" label="Alarm" dense /> : undefined}
        detail={crash.data ? `${before} present before monitoring` : undefined}
        sentence={crash.data ? `new within ${crash.data.new_window_days} days · by process and size` : "by process and size"} />
    </Box>
  );
}

/** What the run said per source (the outcome counts and a row per source with its state word, stored lines and last run), on demand. */
function RunDetails({ data }: { readonly data: SystemLogSources }) {
  const job = data.last_job;
  const outcomes = job ? Object.entries(job.outcomes) : [];
  return (
    <Box data-testid="run-details" sx={{ display: "flex", flexDirection: "column", gap: 1, bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", px: 2, py: 1.25 }}>
      <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
        <Typography variant="caption" color="text.secondary">Sources in that run</Typography>
        {outcomes.length > 0
          // a count is grey; a source that did not end cleanly keeps its state colour and its word
          ? outcomes.map(([outcome, n]) => (
              <StatusChip key={outcome} tone={outcome === "COLLECTED" || outcome === "NO_NEW_DATA" ? "neutral" : statusTone(outcome)}
                label={`${outcome.replace(/_/g, " ").toLowerCase()} ${n}`} dense />))
          : <Typography variant="body2">UNKNOWN</Typography>}
      </Box>
      <Box component="table" aria-label="Sources" sx={{ borderCollapse: "collapse", fontSize: 13, "& td, & th": { textAlign: "left", py: 0.375, pr: 2, verticalAlign: "middle", whiteSpace: "nowrap" },
        "& th": { color: m3.onSurfaceVar, fontWeight: 500, fontSize: 12 } }}>
        <thead><tr><th>Source</th><th>State</th><th>Stored</th><th>Last run</th><th>Note</th></tr></thead>
        <tbody>
          {data.sources.map((s) => (
            <tr key={s.source_key}>
              <td>{sourceLabel(s.source_key)}</td>
              <td><StatusChip tone={statusTone(s.status)} label={sourceStatusWord(s.status)} dense /></td>
              <td><StatusChip tone="neutral" label={`${s.stored.lines.toLocaleString("en-US")} lines`} dense /> <Box component="span" sx={{ color: m3.onSurfaceVar }}>· {s.stored.chunks} chunks · {formatBytes(s.stored.bytes)}</Box></td>
              <td>{s.last_run_at ? <Ts at={s.last_run_at} relative seconds={false} /> : "never run"}</td>
              <td>
                <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, flexWrap: "wrap", color: m3.onSurfaceVar }}>
                  {s.fast_rotating && (
                    <span title={`The device keeps about ${s.retention_horizon_seconds === null ? "an unknown amount" : `${(s.retention_horizon_seconds / 3600).toFixed(1)} h`} of this log, less than the collection interval allows for; gaps are likely.`}>
                      <StatusChip tone="warn" label="fast rotating" dense />
                    </span>
                  )}
                  {s.pending_bytes > 0 ? `${formatBytes(s.pending_bytes)} still waiting on the device` : ""}
                  {s.retention_horizon_seconds !== null ? `device keeps about ${(s.retention_horizon_seconds / 3600).toFixed(1)} h` : ""}
                </Box>
              </td>
            </tr>
          ))}
        </tbody>
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

export type LevelFilter = "all" | "error" | "warning";

/** Everything the viewer holds: the chosen source, day and run, the filter, the masked/unmasked choice and the loaded page. */
interface LogViewerModel {
  readonly source: string | null; readonly setSource: (key: string) => void;
  readonly chunks: readonly SystemLogChunk[] | null; readonly chunksError: string | null;
  readonly day: string | null; readonly setDay: (day: string | null) => void; readonly days: readonly string[];
  readonly dayChunks: readonly SystemLogChunk[]; readonly chunkId: string | null; readonly setChunkId: (id: string | null) => void;
  readonly chunk: SystemLogChunk | null; readonly chunkIndex: number;
  readonly filter: string; readonly setFilter: (value: string) => void;
  /** Lines to show by the level they write: every line, or only error-class or warning-class ones. */
  readonly level: LevelFilter; readonly setLevel: (next: LevelFilter) => void;
  readonly unmasked: boolean; readonly setUnmasked: (on: boolean) => void;
  readonly text: TextState;
  readonly loadPage: (target: SystemLogChunk, offset: number, replace: boolean) => void;
}

function useLogViewer(deviceId: string, sources: readonly SystemLogSource[]): LogViewerModel {
  const [source, setSource] = useState<string | null>(() => (sources.find((s) => s.stored.chunks > 0) ?? sources[0])?.source_key ?? null);
  const [chunks, setChunks] = useState<readonly SystemLogChunk[] | null>(null);
  const [chunksError, setChunksError] = useState<string | null>(null);
  const [day, setDay] = useState<string | null>(null);
  const [chunkId, setChunkId] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [level, setLevel] = useState<LevelFilter>("all");
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

  return {
    source, setSource, chunks, chunksError, day, setDay, days, dayChunks, chunkId, setChunkId, chunk,
    chunkIndex: dayChunks.findIndex((c) => c.chunk_id === chunkId), filter, setFilter, level, setLevel, unmasked, setUnmasked, text, loadPage,
  };
}

/** The text with every filter match wrapped in a `mark`, case-insensitively; the text itself is never changed. */
function highlight(text: string, needle: string, keyBase: string): React.ReactNode[] {
  if (!needle) return [text];
  const out: React.ReactNode[] = [];
  const lower = text.toLowerCase();
  let from = 0;
  let n = 0;
  for (let at = lower.indexOf(needle, from); at >= 0; at = lower.indexOf(needle, from)) {
    if (at > from) out.push(text.slice(from, at));
    out.push(<Box component="mark" key={`${keyBase}-${n++}`} sx={{ bgcolor: m3.primaryContainer, color: m3.onPrimaryContainer, borderRadius: "2px" }}>{text.slice(at, at + needle.length)}</Box>);
    from = at + needle.length;
  }
  if (from < text.length) out.push(text.slice(from));
  return out;
}

/**
 * One raw line: the device time in a fixed column, then the line, wrapped (default) or on one row that scrolls sideways.
 * Nothing is cut away. A severity the line itself writes gets its colour on that word alone (design language: a state
 * is a word with a colour); a filter match is marked. Neither is ever invented.
 */
function RawLine({ line, needle, wrap }: { readonly line: string; readonly needle: string; readonly wrap: boolean }) {
  const time = lineDeviceTime(line);
  const severity = lineSeverity(line);
  let body: React.ReactNode[];
  if (severity && severity.tone) {
    const end = severity.index + severity.token.length;
    body = [
      ...highlight(line.slice(0, severity.index), needle, "a"),
      <Box component="span" key="sev" data-severity={severity.token} title="Severity as the line writes it"
        sx={{ color: severity.tone === "bad" ? m3.criticalInk : m3.warningInk, fontWeight: 600 }}>{highlight(severity.token, needle, "s")}</Box>,
      ...highlight(line.slice(end), needle, "b"),
    ];
  } else {
    body = highlight(line, needle, "l");
  }
  return (
    <Box data-testid="log-line" data-wrap={wrap ? "on" : "off"}
      sx={{ display: "grid", gridTemplateColumns: wrap ? "150px minmax(0, 1fr)" : "150px max-content", columnGap: 1.5, borderRadius: "4px", px: 0.5,
        width: wrap ? "auto" : "max-content", minWidth: "100%", boxSizing: "border-box", "&:hover": { bgcolor: m3.scHighest } }}>
      <Box component="span" data-testid="log-line-time" title={time ? "Device time of this line" : "No device time on this line"}
        sx={{ color: m3.onSurfaceVar, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{time ?? ""}</Box>
      <Box component="span" sx={wrap ? { whiteSpace: "pre-wrap", overflowWrap: "anywhere" } : { whiteSpace: "pre" }}>{body}</Box>
    </Box>
  );
}

/**
 * A gap of this source recorded by the run that stored this chunk, shown in the stream where it belongs: before the
 * lines for content lost before they were read, after them for a backlog still waiting on the device. A gap whose
 * place in the stream is not known stays on Collection health only.
 */
function GapMarker({ gap, where, onJump }: { readonly gap: SystemLogGap; readonly where: "before" | "after"; readonly onJump: (id: string) => void }) {
  const tone = gapTone(gap.kind);
  return (
    <Box role="note" data-testid="gap-marker" data-where={where}
      sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: 1, rowGap: 0.5, my: 0.5, px: 1, py: 0.5, borderRadius: "6px", fontSize: 12.5, lineHeight: 1.4,
        fontFamily: (t) => t.typography.fontFamily, color: m3.onSurface, bgcolor: m3.scLow,
        border: `1px dashed ${tone === "attn" ? m3.attention : tone === "warn" ? m3.warning : m3.outline}` }}>
      <Box component="span" sx={{ fontWeight: 600 }}>{where === "before" ? "Gap before these lines" : "Backlog after these lines"}</Box>
      <StatusChip tone={tone} label={gapKindLabel(gap.kind)} dense />
      <Box component="span" sx={{ color: m3.onSurfaceVar }}>{gapWords(gap)}</Box>
      <JumpLink target={SIDE_ID.gaps} onJump={onJump}>Collection health</JumpLink>
    </Box>
  );
}

/** The one-line selects of the run header share this look. */
const SELECT_SX = { minWidth: 0, "& .MuiInputBase-root": { fontSize: 13 }, "& .MuiInputLabel-root": { fontSize: 13 } } as const;

function LogViewer({ vm, sources, canViewUnmasked, maxPageBytes, gaps, onJump }: {
  readonly vm: LogViewerModel;
  readonly sources: readonly SystemLogSource[];
  readonly canViewUnmasked: boolean;
  readonly maxPageBytes: number;
  readonly gaps: readonly SystemLogGap[];
  readonly onJump: (id: string) => void;
}) {
  const { source, setSource, chunks, chunksError, day, setDay, days, dayChunks, chunkId, setChunkId, chunk, chunkIndex, filter, setFilter, level, setLevel, unmasked, setUnmasked, text, loadPage } = vm;
  const [wrap, setWrap] = usePreferenceFlag("nx.logs.wrap", true);
  const [fullscreen, setFullscreen] = useState(false);
  const linesRef = useRef<HTMLDivElement>(null);
  // The viewer takes the rest of the viewport: its own top edge on the page decides how much that is.
  const [top, setTop] = useState(0);
  useLayoutEffect(() => {
    const measure = () => {
      const rect = linesRef.current?.getBoundingClientRect();
      if (rect) setTop(Math.max(0, Math.round(rect.top + window.scrollY)));
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [fullscreen, canViewUnmasked, chunk?.chunk_id]);
  useEffect(() => {
    if (!fullscreen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setFullscreen(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [fullscreen]);

  const needle = filter.trim().toLowerCase();
  const levels = useMemo(() => countLevels(text.lines), [text.lines]);
  const levelOf = (line: string): LineSeverity["tone"] => lineSeverity(line)?.tone ?? null;
  const shown = text.lines.filter((l) => (!needle || l.toLowerCase().includes(needle))
    && (level === "all" || (level === "error" ? levelOf(l) === "bad" : levelOf(l) === "warn")));
  const filtered = Boolean(needle) || level !== "all";
  const selected = sources.find((s) => s.source_key === source) ?? null;
  // Gaps recorded by the run that stored this chunk, for this source: their place in the stream is known.
  const chunkGaps = chunk ? gaps.filter((g) => g.source_key === source && g.job_id === chunk.job_id) : [];
  const before = chunkGaps.filter((g) => g.kind !== "BACKLOG");
  const after = chunkGaps.filter((g) => g.kind === "BACKLOG");

  if (sources.length === 0) {
    return <EmptyPanel title="No line sources" body="This device has no source whose lines can be viewed here." />;
  }

  return (
    <Box data-testid="log-viewer" data-fullscreen={fullscreen ? "true" : "false"}
      sx={fullscreen
        ? { position: "fixed", inset: 0, zIndex: 1300, bgcolor: m3.surface, p: 2, display: "flex", flexDirection: "column", gap: 1, minWidth: 0, overflow: "hidden" }
        : { bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 1.5, display: "flex", flexDirection: "column", gap: 1, minWidth: 0 }}>
      {/* The run header (PO 2026-10-20): source, day, run, filter and the masked state on one line that wraps when narrow. */}
      <Box data-testid="run-header" sx={{ display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center" }}>
        <TextField select size="small" label="Source" value={source ?? ""} SelectProps={{ native: true }} sx={{ ...SELECT_SX, minWidth: 170 }}
          onChange={(e) => setSource(e.target.value)}>
          {sources.map((s) => (
            <option key={s.source_key} value={s.source_key}>
              {`${sourceLabel(s.source_key)} · ${sourceStatusWord(s.status)} · ${s.stored.lines.toLocaleString("en-US")} lines`}
            </option>
          ))}
        </TextField>
        {selected && (
          <Box data-testid="source-state" sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
            <StatusChip tone={statusTone(selected.status)} label={sourceStatusWord(selected.status)} dense
              title={selected.last_run_at ? `Last run ${formatTime(selected.last_run_at, false)}${selected.pending_bytes > 0 ? ` · ${formatBytes(selected.pending_bytes)} still waiting on the device` : ""}` : "Never run"} />
            {selected.fast_rotating && (
              <span title={`The device keeps about ${selected.retention_horizon_seconds === null ? "an unknown amount" : `${(selected.retention_horizon_seconds / 3600).toFixed(1)} h`} of this log, less than the collection interval allows for; gaps are likely.`}>
                <StatusChip tone="warn" label="fast rotating" dense />
              </span>
            )}
          </Box>
        )}
        <TextField select size="small" label="Day" value={day ?? ""} SelectProps={{ native: true }} sx={{ ...SELECT_SX, minWidth: 130 }}
          onChange={(e) => { setDay(e.target.value || null); setChunkId(null); }} disabled={days.length === 0}>
          {days.length === 0 ? <option value="">No chunks</option> : days.map((d) => <option key={d} value={d}>{d}</option>)}
        </TextField>
        <TextField select size="small" label="Run" value={chunkId ?? ""} SelectProps={{ native: true }} sx={{ ...SELECT_SX, minWidth: 220 }}
          onChange={(e) => setChunkId(e.target.value || null)} disabled={dayChunks.length === 0}>
          {dayChunks.length === 0 ? <option value="">No chunks</option> : dayChunks.map((c, i) => (
            <option key={c.chunk_id} value={c.chunk_id}>
              {`${i + 1} of ${dayChunks.length} · ${clock(c.collected_at)} · ${c.lines.toLocaleString("en-US")} lines · ${formatBytes(c.bytes)}${c.outcome ? ` · ${c.outcome}` : ""}${c.pruned ? " · removed" : ""}`}
            </option>
          ))}
        </TextField>
        <TextField size="small" label="Filter lines" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ ...SELECT_SX, minWidth: 160, flex: "1 1 160px" }}
          inputProps={{ "aria-label": "Filter lines" }} />
        {(levels.error > 0 || levels.warning > 0) && (
          // Level chips only for the levels the loaded lines write (mockup 2026-10-17: Hepsi / Hata / Uyarı); none are invented.
          <Box role="group" aria-label="Severity" sx={{ display: "inline-flex", alignItems: "center", gap: 0.5 }}>
            <TextToggle pressed={level === "all"} onClick={() => setLevel("all")}>All</TextToggle>
            {levels.error > 0 && <TextToggle pressed={level === "error"} onClick={() => setLevel("error")}>Error <Box component="span" sx={{ color: level === "error" ? "inherit" : m3.criticalInk }}>{levels.error}</Box></TextToggle>}
            {levels.warning > 0 && <TextToggle pressed={level === "warning"} onClick={() => setLevel("warning")}>Warning <Box component="span" sx={{ color: level === "warning" ? "inherit" : m3.warningInk }}>{levels.warning}</Box></TextToggle>}
          </Box>
        )}
        {chunk && text.masked !== null && (text.masked
          ? <StatusChip tone="neutral" label={text.maskRules ? `Masked view · rules ${text.maskRules}` : "Masked view"} dense />
          : <StatusChip tone="warn" label="Unmasked view · recorded on the audit ledger" dense />)}
        <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, ml: "auto" }}>
          <TextToggle pressed={wrap} label="Wrap lines" onClick={() => setWrap(!wrap)}>Wrap</TextToggle>
          <Tooltip title={fullscreen ? "Exit full screen (Esc)" : "Expand the viewer to the whole window"}>
            <IconButton size="small" aria-label={fullscreen ? "Exit full screen" : "Expand viewer"} aria-pressed={fullscreen} onClick={() => setFullscreen((v) => !v)}
              sx={{ color: m3.primary, borderRadius: "8px" }}>
              <Icon name={fullscreen ? "collapse" : "expand"} size={18} />
            </IconButton>
          </Tooltip>
        </Box>
      </Box>
      {chunk && day && (
        <Typography variant="caption" data-testid="picker-sentence" sx={{ color: m3.onSurfaceVar, px: 0.5 }}>
          Day {day} · run {chunkIndex + 1} of {dayChunks.length} · {clock(chunk.collected_at)} · {chunk.lines.toLocaleString("en-US")} lines · {formatBytes(chunk.bytes)}
          {` · ${chunk.redaction_count} secret value${chunk.redaction_count === 1 ? "" : "s"} redacted`}
          {chunk.first_line_time && chunk.last_line_time ? ` · lines ${chunk.first_line_time} to ${chunk.last_line_time} (device time)` : ""}
          {chunk.flags.includes("LINE_SPLIT") ? " · begins or ends inside a line" : ""}
        </Typography>
      )}

      {canViewUnmasked && (
        // The audit warning stays, on one line with its icon; the switch is still the only way to an unmasked page.
        <Box data-testid="unmasked-control" sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", px: 0.5 }}>
          <FormControlLabel sx={{ mr: 0, "& .MuiFormControlLabel-label": { fontSize: 13 } }}
            control={<Switch size="small" checked={unmasked} onChange={(e) => setUnmasked(e.target.checked)} inputProps={{ "aria-label": "Show unmasked" }} />}
            label="Show unmasked" />
          <Box component="span" aria-hidden sx={{ display: "inline-flex", color: m3.warningInk }}><Icon name="warning" size={16} /></Box>
          <Typography variant="caption" color="text.secondary">
            Unmasked lines show real addresses and names. Every page you load this way is recorded on the audit ledger.
          </Typography>
        </Box>
      )}

      {chunksError && <StatePanel variant="error" title="Chunks unavailable" body="The chunk list could not be read." code={chunksError} />}
      {chunks && chunks.length === 0 && !chunksError && (
        <StatePanel variant="empty" title="Nothing stored for this source" body="No chunk is stored for this source yet, or retention removed them all." />
      )}
      {chunk?.pruned && <StatePanel variant="empty" title="Removed by retention" body="The bytes of this chunk were removed. Its index row is kept as the record." />}
      {text.error && <StatePanel variant="error" title="Lines unavailable" body="The lines of this chunk could not be read." code={text.error}
        action={chunk ? <M3Button emphasis="outlined" onClick={() => loadPage(chunk, text.lines.length === 0 ? 0 : text.nextOffset, text.lines.length === 0)}>Retry</M3Button> : undefined} />}

      {chunk && !chunk.pruned && !text.error && (
        <>
          <Box ref={linesRef} data-testid="log-lines" data-fill={fullscreen ? "screen" : "viewport"} data-wrap={wrap ? "on" : "off"} role="log" aria-label="Log lines" sx={{
            fontFamily: MONO, fontSize: 12.5, lineHeight: 1.6, bgcolor: m3.scHigh, borderRadius: "8px", p: 1.25, overflow: "auto", minWidth: 0,
            ...(fullscreen ? { flex: "1 1 auto", minHeight: 0 } : { height: `calc(100vh - ${top}px - 88px)`, minHeight: 320 }),
          }}>
            {before.map((g) => <GapMarker key={g.gap_id} gap={g} where="before" onJump={onJump} />)}
            {text.loading && text.lines.length === 0 ? "Loading…" : shown.length === 0 ? (filtered ? "No loaded line matches the filter." : "This chunk holds no lines.") :
              shown.map((line, i) => <RawLine key={`${chunk.chunk_id}:${i}`} line={line} needle={needle} wrap={wrap} />)}
            {after.map((g) => <GapMarker key={g.gap_id} gap={g} where="after" onJump={onJump} />)}
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap", px: 0.5 }}>
            <Typography variant="caption" color="text.secondary">
              {filtered ? `${shown.length} of ${text.lines.length} loaded lines match` : `${text.lines.length.toLocaleString("en-US")} lines loaded`}
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
        </>
      )}
    </Box>
  );
}
