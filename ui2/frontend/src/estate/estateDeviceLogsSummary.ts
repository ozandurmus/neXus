import type { SystemLogCrashFiles, SystemLogGap, SystemLogSources } from "../auth/adminApi";
import { formatBytes, sourceLabel, sourceStatusWord } from "../screens/DeviceLogsPanel";
import { newestIso, type BadgeState } from "./estateModel";

/** Per-source rows and crash-file rows shown in the dossier before "+N more" (standard view lists the rest). */
export const LOG_SOURCES_CAP = 8;
export const LOG_CRASH_FILES_CAP = 6;

/**
 * Estate unit dossier, "Logs" block (read-only summary). Pure model: it counts and dates what the three system-log
 * read routes already say and never carries log text. The full log reader is the standard view (Device > Logs), which
 * this model links to with `standard=1`; `getSystemLogText` is never called from the Estate view.
 */

/** One read of the three; `ok: false` means the read failed or was refused, which is UNKNOWN for that figure. */
export type LogsReadPart<T> = { readonly ok: true; readonly value: T } | { readonly ok: false };

export interface DeviceLogsReads {
  readonly sources: LogsReadPart<SystemLogSources>;
  readonly gaps: LogsReadPart<{ readonly gaps: readonly SystemLogGap[]; readonly truncated: boolean }>;
  readonly crashFiles: LogsReadPart<SystemLogCrashFiles>;
}

export type LogsFigure = "sources" | "gaps" | "crash_files";

export interface GapKindCount {
  readonly kind: string;
  readonly count: number;
}

/** One source row in the dossier: the label is `sourceLabel(source_key)`, never the raw key. */
export interface DeviceLogSourceSummaryRow {
  readonly label: string;
  readonly statusWord: string;
  readonly badgeState: BadgeState;
  /** Null when the source's stored counts are missing: rendered as UNKNOWN, never 0. */
  readonly chunks: number | null;
  readonly lines: number | null;
  readonly bytesText: string | null;
  /** Age of the evidence: the newest stored chunk, else the source's last run. */
  readonly evidenceAt: string | null;
}

/** One present crash file in the dossier. */
export interface DeviceLogCrashSummaryRow {
  readonly entryId: string;
  readonly processLabel: string;
  readonly directoryLabel: string;
  readonly stateWord: string;
  readonly badgeState: BadgeState;
  readonly sizeText: string;
  readonly firstSeenAt: string | null;
}

export interface DeviceLogsSummary {
  /** `collected`: sources read and at least one chunk stored; `no_logs_yet`: sources read with zero chunks; `not_collected`: the device is outside collection; `unknown`: a read failed. */
  readonly state: "collected" | "no_logs_yet" | "not_collected" | "unknown";
  readonly badge: BadgeState;
  readonly badgeWord: string;
  readonly headline: string;
  /** Null when the figure's read failed: rendered as UNKNOWN. */
  readonly sourceCount: number | null;
  /** Total stored chunks; null when the sources read failed or any source did not report its stored count. */
  readonly chunkCount: number | null;
  /** Total stored lines and bytes across the sources; null when the sources read failed or any source's count is missing. */
  readonly lineCount: number | null;
  readonly byteCount: number | null;
  readonly bytesText: string | null;
  readonly newestChunkAt: string | null;
  readonly gapCount: number | null;
  readonly gapKinds: readonly GapKindCount[];
  readonly gapsTruncated: boolean;
  readonly crashFileCount: number | null;
  /** Present crash files in state NEW (new since monitoring started); null when the listing read failed. */
  readonly newCrashFileCount: number | null;
  readonly crashFilesTruncated: boolean;
  /** Per-source rows, capped at LOG_SOURCES_CAP. */
  readonly sourceRows: readonly DeviceLogSourceSummaryRow[];
  /** Present crash-file rows, capped at LOG_CRASH_FILES_CAP. */
  readonly crashRows: readonly DeviceLogCrashSummaryRow[];
  /** Evidence time per figure: the newest item it counts, else the last collection job time (the read's own evidence). */
  readonly sourcesEvidenceAt: string | null;
  readonly gapsEvidenceAt: string | null;
  readonly crashEvidenceAt: string | null;
  readonly failedFigures: readonly LogsFigure[];
}

/** A stored count the source reported as a finite number, else null (UNKNOWN). */
function finiteCount(n: number | null | undefined): number | null {
  return typeof n === "number" && Number.isFinite(n) ? n : null;
}

/** Sum of per-source counts; null when any source did not report the count (a partial total is not a total). */
function sumCounts(values: readonly (number | null)[]): number | null {
  let total = 0;
  for (const v of values) {
    if (v === null) return null;
    total += v;
  }
  return total;
}

function sourceBadgeState(status: string): BadgeState {
  if (status === "OK") return "ok";
  if (status === "BACKLOG" || status === "TOO_LARGE" || status === "GAP") return "watch";
  if (status === "UNKNOWN" || status === "STORAGE_REFUSED") return "unk";
  return "na";
}

/** Gaps grouped by kind, most frequent first, ties by kind name so the order is stable. */
function groupGapKinds(gaps: readonly SystemLogGap[]): GapKindCount[] {
  const counts = new Map<string, number>();
  for (const gap of gaps) counts.set(gap.kind, (counts.get(gap.kind) ?? 0) + 1);
  return [...counts.entries()]
    .map(([kind, count]) => ({ kind, count }))
    .sort((a, b) => b.count - a.count || a.kind.localeCompare(b.kind));
}

export function buildDeviceLogsSummary(reads: DeviceLogsReads): DeviceLogsSummary {
  const failedFigures: LogsFigure[] = [];
  const sourcesRead = reads.sources.ok ? reads.sources.value : null;
  const gapsRead = reads.gaps.ok ? reads.gaps.value : null;
  const crashRead = reads.crashFiles.ok ? reads.crashFiles.value : null;
  if (!reads.sources.ok) failedFigures.push("sources");
  if (!reads.gaps.ok) failedFigures.push("gaps");
  if (!reads.crashFiles.ok) failedFigures.push("crash_files");

  const lastJobAt = sourcesRead?.last_job?.at ?? null;

  // Sources: the count of collected sources; chunks and the newest chunk time come from the same read.
  const sourceCount = sourcesRead ? sourcesRead.sources.length : null;
  const chunkCount = sourcesRead
    ? sumCounts((sourcesRead.sources ?? []).map((s) => finiteCount(s.stored?.chunks)))
    : null;
  const newestChunkAt = sourcesRead ? newestIso(sourcesRead.sources.map((s) => s.stored?.newest_chunk_at ?? null)) : null;
  const lineCount = sourcesRead
    ? sumCounts((sourcesRead.sources ?? []).map((s) => finiteCount(s.stored?.lines)))
    : null;
  const byteCount = sourcesRead
    ? sumCounts((sourcesRead.sources ?? []).map((s) => finiteCount(s.stored?.bytes)))
    : null;
  const bytesText = byteCount !== null ? formatBytes(byteCount) : null;
  const sourceRows: DeviceLogSourceSummaryRow[] = (sourcesRead?.sources ?? []).slice(0, LOG_SOURCES_CAP).map((s) => {
    const bytes = finiteCount(s.stored?.bytes);
    return {
      label: sourceLabel(s.source_key),
      statusWord: sourceStatusWord(s.status),
      badgeState: sourceBadgeState(s.status),
      chunks: finiteCount(s.stored?.chunks),
      lines: finiteCount(s.stored?.lines),
      bytesText: bytes === null ? null : formatBytes(bytes),
      evidenceAt: s.stored?.newest_chunk_at ?? s.last_run_at ?? null,
    };
  });

  // Gaps.
  const gaps = gapsRead?.gaps ?? [];
  const gapCount = gapsRead ? gaps.length : null;
  const gapKinds = gapsRead ? groupGapKinds(gaps) : [];
  const gapsEvidenceAt = gapsRead ? newestIso([...gaps.map((g) => g.recorded_at), lastJobAt]) : null;

  // Crash files: a removed file is not a present one.
  const presentCrash = crashRead ? crashRead.files.filter((f) => f.state !== "REMOVED") : [];
  const crashFileCount = crashRead ? presentCrash.length : null;
  const newCrashFileCount = crashRead ? presentCrash.filter((f) => f.state === "NEW").length : null;
  const crashEvidenceAt = crashRead
    ? newestIso([...presentCrash.map((f) => f.first_seen_at), lastJobAt])
    : null;
  const crashRows: DeviceLogCrashSummaryRow[] = presentCrash.slice(0, LOG_CRASH_FILES_CAP).map((f) => ({
    entryId: f.entry_id,
    processLabel: !f.process || f.process === "UNKNOWN" ? "UNKNOWN process" : f.process,
    directoryLabel: f.directory === "crash" ? "Kernel crash" : f.directory === "dump_usermode" ? "User-mode dump" : f.directory,
    stateWord: f.state === "NEW" ? "New" : f.state === "PRESENT_BEFORE_MONITORING" ? "Present before monitoring" : "Present",
    badgeState: f.state === "NEW" ? "act" : "na",
    sizeText: formatBytes(f.size),
    firstSeenAt: f.first_seen_at,
  }));

  const base = {
    sourceCount,
    chunkCount,
    lineCount,
    byteCount,
    bytesText,
    newestChunkAt,
    gapCount,
    gapKinds,
    gapsTruncated: gapsRead?.truncated ?? false,
    crashFileCount,
    newCrashFileCount,
    crashFilesTruncated: crashRead?.truncated ?? false,
    sourceRows,
    crashRows,
    sourcesEvidenceAt: newestChunkAt ?? lastJobAt,
    gapsEvidenceAt,
    crashEvidenceAt,
    failedFigures,
  } as const;

  if (!sourcesRead) {
    return {
      ...base,
      state: "unknown",
      badge: "unk",
      badgeWord: "UNKNOWN",
      headline: "Log read failed",
      sourcesEvidenceAt: null,
    };
  }

  if (sourcesRead.collection_state === "DISABLED_ON_MANAGEMENT" || sourcesRead.collection_state === "VENDOR_UNSUPPORTED") {
    return {
      ...base,
      state: "not_collected",
      badge: "na",
      badgeWord: "Not collected",
      headline: sourcesRead.message ?? "Logs are not collected for this device",
    };
  }

  if (sourcesRead.collection_state === "NO_COLLECTION_YET" || chunkCount === 0) {
    return {
      ...base,
      state: "no_logs_yet",
      badge: "watch",
      badgeWord: "Not yet",
      headline: "No logs collected yet",
    };
  }

  const attentionStatus = (sourcesRead?.sources ?? []).some((src) =>
    src.status === "BACKLOG" || src.status === "TOO_LARGE" || src.status === "GAP" || src.status === "STORAGE_REFUSED");
  const attention = attentionStatus || (gapCount ?? 0) > 0 || (crashFileCount ?? 0) > 0 || failedFigures.length > 0;
  // A source without its stored counts makes the chunk, line and byte totals UNKNOWN: the block is not OK.
  const countsUnknown = chunkCount === null;
  const unknown = failedFigures.length > 0 || countsUnknown;
  return {
    ...base,
    state: unknown ? "unknown" : "collected",
    badge: unknown ? "unk" : attention ? "watch" : "ok",
    badgeWord: unknown ? "UNKNOWN" : attention ? "Review" : "OK",
    headline: countsUnknown
      ? `${sourceCount} ${sourceCount === 1 ? "source" : "sources"} · stored counts UNKNOWN for a source`
      : `${sourceCount} ${sourceCount === 1 ? "source" : "sources"} · ${chunkCount} ${chunkCount === 1 ? "chunk" : "chunks"} collected`,
  };
}

/** The standard view's Device > Logs tab for one device (the full reader, lines included). */
export function standardDeviceLogsHref(deviceId: string): string {
  const params = new URLSearchParams({ screen: "inventory", device_id: deviceId, tab: "logs", standard: "1" });
  return `?${params.toString()}`;
}
