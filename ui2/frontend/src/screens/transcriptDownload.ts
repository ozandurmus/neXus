import { getJobTranscript, type ApiError, type JobTranscriptEntry } from "../auth/adminApi";

/**
 * The identity of the transcript a download belongs to. Every row and every panel captures its own source value;
 * nothing here is shared or mutated, so a download is always the transcript of the row it was clicked on.
 */
export interface TranscriptSource {
  readonly jobId: string;
  /** The job type as the server names it (`backup`, `cp_gateway_backup`); it becomes the file name prefix. */
  readonly jobType?: string;
  /** The job's submission instant (ISO). The first entry's instant stands in when the caller has none. */
  readonly at?: string;
}

const KNOWN_ERRORS = ["TRANSCRIPT_DECRYPT_FAILED", "TRANSCRIPT_PARSE_FAILED", "TRANSCRIPT_MISSING", "TRANSCRIPT_STORE_UNAVAILABLE"];

/** The stable code a transcript read failure is reported with; never the server's free text. */
export function transcriptErrorCode(failure: ApiError | undefined): string {
  const code = failure?.body?.error;
  if (typeof code === "string" && KNOWN_ERRORS.includes(code)) return code;
  return failure?.status === 403 ? "TRANSCRIPT_FORBIDDEN" : "TRANSCRIPT_LOAD_FAILED";
}

const ISTANBUL = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/Istanbul", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});

/** `YYYYMMDD-HHmm` in Turkey time (Europe/Istanbul), as the PO reads the fleet; `unknown-time` for an unreadable instant. */
export function istanbulStamp(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "unknown-time";
  const parts = Object.fromEntries(ISTANBUL.formatToParts(date).map((part) => [part.type, part.value]));
  return `${parts.year}${parts.month}${parts.day}-${parts.hour}${parts.minute}`;
}

/** `<job-type>-<YYYYMMDD-HHmm>-<short job id>.txt`, e.g. `cp-gateway-backup-20261025-0912-3f9c1a2b.txt`. */
export function transcriptFileName(source: TranscriptSource, entries: readonly JobTranscriptEntry[]): string {
  const type = (source.jobType ?? "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "job";
  const at = source.at ?? entries[0]?.at ?? new Date().toISOString();
  return `${type}-${istanbulStamp(at)}-${source.jobId.slice(0, 8)}.txt`;
}

/**
 * One line per entry, in server order: ISO instant, channel (with the session id when the server names one), kind,
 * text, tab-separated. Device output that spans several lines keeps its line breaks; its continuation lines start
 * with a tab, so `grep -v '^\t'` is the entry list and the output still reads as the device wrote it.
 */
export function transcriptAsText(entries: readonly JobTranscriptEntry[]): string {
  if (entries.length === 0) return "";
  return entries.map((entry) => {
    const channel = entry.sessionId ? `${entry.channel}/${entry.sessionId}` : entry.channel;
    const text = entry.text.replace(/\r\n|\r|\n/g, "\n\t");
    return `${entry.at}\t${channel}\t${entry.kind}\t${text}`;
  }).join("\n") + "\n";
}

/** Saves the given entries as the file named for `source`; returns the file name. */
export function saveTranscriptFile(source: TranscriptSource, entries: readonly JobTranscriptEntry[]): string {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob([transcriptAsText(entries)], { type: "text/plain;charset=utf-8" }));
  link.download = transcriptFileName(source, entries);
  link.click();
  URL.revokeObjectURL(link.href);
  return link.download;
}

/**
 * Reads the transcript the same way the panel does (GET /jobs/{jobId}/transcript, server-side admin gate, replay
 * viewer refused) and saves it. The job id is read from `source` at click time; no state outlives the call.
 */
export async function downloadJobTranscript(source: TranscriptSource): Promise<string> {
  const entries = await getJobTranscript(source.jobId);
  return saveTranscriptFile(source, entries);
}
