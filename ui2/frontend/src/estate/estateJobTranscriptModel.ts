import type { JobTranscriptEntry } from "../auth/adminApi";
import { plural } from "../shell/plural";
import type { ScriptRunRead } from "./estateSystemModel";

/** Most transcript lines the Estate drawer draws at once; the rest are counted as "+N more", never dropped silently. */
export const TRANSCRIPT_LINE_CAP = 100;

/** Transcript lines in order: ascending by `seq` (the server's own order), without touching the input array. */
export function orderTranscriptLines(entries: readonly JobTranscriptEntry[]): JobTranscriptEntry[] {
  return [...entries].sort((a, b) => a.seq - b.seq);
}

/** The first `cap` lines and how many lines were left out of the drawer. */
export function capTranscriptLines(
  lines: readonly JobTranscriptEntry[],
  cap: number = TRANSCRIPT_LINE_CAP,
): { readonly shown: readonly JobTranscriptEntry[]; readonly hidden: number } {
  const hidden = Math.max(0, lines.length - cap);
  return { shown: lines.slice(0, cap), hidden };
}

/** "N lines": the count says what it counts, and singular only for exactly one line. */
export function transcriptLineCountWording(count: number): string {
  return plural(count, "line", "lines");
}

/** "+N more lines not shown" when the cap left lines out; null when every line is shown. */
export function transcriptMoreLabel(hidden: number): string | null {
  return hidden > 0 ? `+${hidden} more lines not shown` : null;
}

/** Shown when the read succeeded and the transcript holds no lines. Never "OK": an empty transcript is not a pass. */
export const TRANSCRIPT_EMPTY_WORDING = "No transcript lines";

/** Shown when the read failed. The state is UNKNOWN, never OK. */
export function transcriptFailedWording(restricted: boolean, code: string): string {
  return restricted
    ? "Transcript restricted for this account (UNKNOWN: not read)."
    : `Transcript could not be read (UNKNOWN; ${code}).`;
}

/** The state or level word of one line: its kind as the server names it; an absent kind is UNKNOWN. */
export function transcriptStateWord(entry: JobTranscriptEntry): string {
  const kind = entry.kind.trim();
  return kind.length > 0 ? kind.toUpperCase() : "UNKNOWN";
}

export interface TranscriptJobRow {
  readonly scriptName: string;
  readonly jobId: string;
  readonly requestedAt: string;
}

/**
 * One transcript-able job per script: the script's newest run in the list as read. Scripts whose runs were not
 * read, or that have no run, contribute no row; a script with no run has no job to open.
 */
export function latestTranscriptJobRows(runs: readonly ScriptRunRead[]): TranscriptJobRow[] {
  const rows: TranscriptJobRow[] = [];
  for (const row of runs) {
    if (row.read.state !== "ok") continue;
    let best: TranscriptJobRow | null = null;
    let bestTime = Number.NEGATIVE_INFINITY;
    for (const run of row.read.value) {
      const t = Date.parse(run.requested_at);
      const time = Number.isNaN(t) ? Number.NEGATIVE_INFINITY : t;
      if (best === null || time > bestTime) {
        best = { scriptName: row.script.name, jobId: run.job_id, requestedAt: run.requested_at };
        bestTime = time;
      }
    }
    if (best !== null && best.jobId.length > 0) rows.push(best);
  }
  return rows;
}

/** "+N more jobs" for job rows past the card's list cap; null when none are left out. */
export function moreRowsLabel(hidden: number): string | null {
  return hidden > 0 ? `+${hidden} more jobs (open the standard view for the rest)` : null;
}
