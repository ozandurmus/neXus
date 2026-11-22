import type { SystemLogCrashFiles, SystemLogGap, SystemLogSources } from "../auth/adminApi";
import type { BadgeState } from "./estateModel";

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

export interface DeviceLogsSummary {
  /** `collected`: sources read and at least one chunk stored; `no_logs_yet`: sources read with zero chunks; `not_collected`: the device is outside collection; `unknown`: a read failed. */
  readonly state: "collected" | "no_logs_yet" | "not_collected" | "unknown";
  readonly badge: BadgeState;
  readonly badgeWord: string;
  readonly headline: string;
  /** Null when the figure's read failed: rendered as UNKNOWN. */
  readonly sourceCount: number | null;
  readonly chunkCount: number | null;
  readonly newestChunkAt: string | null;
  readonly gapCount: number | null;
  readonly gapKinds: readonly GapKindCount[];
  readonly gapsTruncated: boolean;
  readonly crashFileCount: number | null;
  readonly crashFilesTruncated: boolean;
  /** Evidence time per figure: the newest item it counts, else the last collection job time (the read's own evidence). */
  readonly sourcesEvidenceAt: string | null;
  readonly gapsEvidenceAt: string | null;
  readonly crashEvidenceAt: string | null;
  readonly failedFigures: readonly LogsFigure[];
}

function timeOf(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

function newestIso(values: readonly (string | null | undefined)[]): string | null {
  let best: { t: number; iso: string } | null = null;
  for (const iso of values) {
    const t = timeOf(iso);
    if (t !== null && (best === null || t > best.t)) best = { t, iso: iso as string };
  }
  return best?.iso ?? null;
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
  const chunkCount = sourcesRead ? sourcesRead.sources.reduce((n, s) => n + s.stored.chunks, 0) : null;
  const newestChunkAt = sourcesRead ? newestIso(sourcesRead.sources.map((s) => s.stored.newest_chunk_at)) : null;

  // Gaps.
  const gaps = gapsRead?.gaps ?? [];
  const gapCount = gapsRead ? gaps.length : null;
  const gapKinds = gapsRead ? groupGapKinds(gaps) : [];
  const gapsEvidenceAt = gapsRead ? newestIso([...gaps.map((g) => g.recorded_at), lastJobAt]) : null;

  // Crash files: a removed file is not a present one.
  const presentCrash = crashRead ? crashRead.files.filter((f) => f.state !== "REMOVED") : [];
  const crashFileCount = crashRead ? presentCrash.length : null;
  const crashEvidenceAt = crashRead
    ? newestIso([...presentCrash.map((f) => f.first_seen_at), lastJobAt])
    : null;

  const base = {
    sourceCount,
    chunkCount,
    newestChunkAt,
    gapCount,
    gapKinds,
    gapsTruncated: gapsRead?.truncated ?? false,
    crashFileCount,
    crashFilesTruncated: crashRead?.truncated ?? false,
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

  const attention = (gapCount ?? 0) > 0 || (crashFileCount ?? 0) > 0 || failedFigures.length > 0;
  return {
    ...base,
    state: failedFigures.length > 0 ? "unknown" : "collected",
    badge: failedFigures.length > 0 ? "unk" : attention ? "watch" : "ok",
    badgeWord: failedFigures.length > 0 ? "UNKNOWN" : attention ? "Review" : "OK",
    headline: `${sourceCount} ${sourceCount === 1 ? "source" : "sources"} · ${chunkCount} ${chunkCount === 1 ? "chunk" : "chunks"} collected`,
  };
}

/** The standard view's Device > Logs tab for one device (the full reader, lines included). */
export function standardDeviceLogsHref(deviceId: string): string {
  const params = new URLSearchParams({ screen: "inventory", device_id: deviceId, tab: "logs", standard: "1" });
  return `?${params.toString()}`;
}
