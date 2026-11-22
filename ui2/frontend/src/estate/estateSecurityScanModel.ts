import type { SecurityScanSummaryView } from "../auth/adminApi";
import { ageBucketOf, type AgeBucket, type BadgeState } from "./estateModel";
import type { PlatformReadState } from "./estatePlatformFindings";

/**
 * Pure model for the Brief's read-only "Daily security scan" card (standard-view parity with SecurityScanCard).
 *
 * Counts and dates only: finding titles, rule names, package names and file paths are never read into this model.
 * A missing, unreadable or unreadable-by-role scan is UNKNOWN, never OK. OK is shown only when the summary
 * explicitly carries `passed: true` and the scan is not stale.
 */

/** Severities the summary can carry, in display order. */
export const SCAN_SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO", "UNKNOWN"] as const;

export type ScanSeverity = (typeof SCAN_SEVERITY_ORDER)[number];

export type ScanCardKind = "populated" | "no-scan" | "unreadable" | "failed" | "restricted";

export interface ScanCardStatus {
  readonly kind: ScanCardKind;
  readonly badge: BadgeState;
  readonly word: string;
  readonly headline: string;
  /** The scan date (the age of the scan evidence); null when the summary gave none. */
  readonly at: string | null;
}

export interface ScanSeverityCount {
  readonly severity: ScanSeverity;
  /**
   * Open findings for this severity: `new` + `existing` summed over the tools. Accepted and fixed are not open.
   * For UNKNOWN, the open findings under severities this brief does not recognise; null (shown as "n/a") when
   * any count in the summary is unreadable, so the figure cannot be stated.
   */
  readonly open: number | null;
}

/** Age bucket of the scan evidence; "never" when the summary has no scan date. */
export function scanAgeBucket(view: SecurityScanSummaryView, now: Date): AgeBucket {
  return ageBucketOf(view.generated_at ?? null, now);
}

/**
 * Open counts per severity, summed over the tools in the summary. Returns null when the summary carries no
 * counts at all (the card then says the counts are UNKNOWN). Severities the summary does not carry are omitted.
 *
 * Only finite numeric `new` and `existing` values are summed. A severity key this brief does not recognise, or a
 * cell whose `new`/`existing` is missing or non-numeric, is never dropped: it makes a trailing UNKNOWN entry.
 * That entry is "n/a" when any cell is unreadable, because its figure cannot be stated.
 */
export function openSeverityCounts(view: SecurityScanSummaryView): ScanSeverityCount[] | null {
  if (!view.counts) return null;
  const known: readonly string[] = SCAN_SEVERITY_ORDER.filter((s) => s !== "UNKNOWN");
  const totals = new Map<string, number>();
  let unrecognisedSeen = false;
  let unrecognisedOpen = 0;
  let unreadable = false;
  for (const tool of Object.values(view.counts)) {
    if (tool === null || typeof tool !== "object") {
      unreadable = true;
      continue;
    }
    for (const [severity, cell] of Object.entries(tool)) {
      if (cell === null || typeof cell !== "object") {
        unreadable = true;
        if (!known.includes(severity)) unrecognisedSeen = true;
        continue;
      }
      const newOpen = Number.isFinite(cell.new) ? cell.new : 0;
      const existingOpen = Number.isFinite(cell.existing) ? cell.existing : 0;
      if (!Number.isFinite(cell.new) || !Number.isFinite(cell.existing)) unreadable = true;
      if (known.includes(severity)) {
        totals.set(severity, (totals.get(severity) ?? 0) + newOpen + existingOpen);
      } else {
        unrecognisedSeen = true;
        unrecognisedOpen += newOpen + existingOpen;
      }
    }
  }
  const counts: ScanSeverityCount[] = SCAN_SEVERITY_ORDER.filter((s) => totals.has(s)).map((severity) => ({
    severity,
    open: totals.get(severity) ?? 0,
  }));
  if (unrecognisedSeen || unreadable) {
    counts.push({ severity: "UNKNOWN", open: unreadable ? null : unrecognisedOpen });
  }
  return counts;
}

/**
 * The count line, e.g. "HIGH 2 · MEDIUM 5"; an unreadable UNKNOWN entry reads "UNKNOWN n/a". Every entry names its
 * severity; the caption says what the figure counts. An empty list means the summary carries counts with no open
 * findings in them.
 */
export function severityCountLine(counts: readonly ScanSeverityCount[]): string {
  if (counts.length === 0) return "no open findings in the summary";
  return counts.map((c) => `${c.severity} ${c.open ?? "n/a"}`).join(" · ");
}

export const SCAN_COUNT_CAPTION = "open findings per severity (new + existing; accepted and fixed not counted)";

/** Status of the card for one read. Loading is handled by the component and is not a status here. */
export function scanCardStatus(
  read: Exclude<PlatformReadState<SecurityScanSummaryView>, { state: "loading" }>,
  now: Date,
): ScanCardStatus {
  if (read.state === "failed") {
    return {
      kind: "failed", badge: "unk", word: "UNKNOWN", at: null,
      headline: "The daily scan summary could not be read; not counted as fine.",
    };
  }
  if (read.state === "restricted") {
    return {
      kind: "restricted", badge: "unk", word: "UNKNOWN", at: null,
      headline: "The daily scan summary is not available to this role; not counted as fine.",
    };
  }
  const view = read.value;
  if (!view.available) {
    if (view.reason === "SUMMARY_UNREADABLE") {
      return {
        kind: "unreadable", badge: "unk", word: "UNKNOWN", at: null,
        headline: "The daily scan wrote a summary this brief cannot read; not counted as fine.",
      };
    }
    return {
      kind: "no-scan", badge: "unk", word: "No scan", at: null,
      headline: "No daily scan has been recorded yet (the scan runs at 02:30); not counted as fine.",
    };
  }
  const at = view.generated_at ?? null;
  if (!at) {
    return {
      kind: "populated", badge: "unk", word: "UNKNOWN", at: null,
      headline: "The scan date is not stated in the summary; the scan age is UNKNOWN.",
    };
  }
  const stale = view.stale === true || scanAgeBucket(view, now) === "stale";
  if (stale) {
    const hours = view.age_hours === undefined ? "UNKNOWN" : `${view.age_hours} h`;
    return {
      kind: "populated", badge: "watch", word: "Stale", at,
      headline: `The last daily scan finished ${hours} ago; it is expected every ${view.stale_after_hours} h.`,
    };
  }
  if (view.passed === true) {
    return { kind: "populated", badge: "ok", word: "Passed", at, headline: "The last daily scan passed." };
  }
  if (view.passed === false) {
    return { kind: "populated", badge: "act", word: "Failed", at, headline: "The last daily scan did not pass." };
  }
  return {
    kind: "populated", badge: "unk", word: "UNKNOWN", at,
    headline: "The summary does not state whether the last daily scan passed.",
  };
}
