import type { LogCoverageSummary, LogFinding, LogFindingChipData, LogFindingSeverity } from "../auth/adminApi";
import type { Tone } from "../shell/tone";

/**
 * The reading of the log detection engine's answers (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 7.4 and 9).
 * Pure functions, no fetch: every sentence says what it counts, UNKNOWN is never worded as healthy, and a finding is
 * shown with its coverage and its history label.
 */

/** The state colour of a finding severity; INFO is quiet, UNKNOWN is the neutral unknown tone. */
export function severityTone(severity: LogFindingSeverity): Tone {
  switch (severity) {
    case "CRITICAL": return "bad";
    case "ATTENTION": return "warn";
    case "INFO": return "neutral";
    default: return "unknown";
  }
}

const COVERAGE_WORDS: Record<string, string> = {
  EVALUATED: "evaluated",
  BASELINE_LEARNING: "evaluated, baseline still learning (7 to 13 days)",
  NOT_EVALUABLE: "evaluated by the hardware rules only (under 7 days of history)",
  GAP: "UNKNOWN: a collection gap overlaps the window",
  NOT_COLLECTED: "UNKNOWN: the window was not collected",
  MASKING_UNAVAILABLE: "UNKNOWN: masking was unavailable, nothing was processed",
  UNPARSED_HIGH: "UNKNOWN: too many lines could not be read",
  UNSUPPORTED: "UNKNOWN: this source is not analysed yet",
};

export function coverageWord(state: string): string {
  return COVERAGE_WORDS[state] ?? `UNKNOWN: ${state}`;
}

/** True for the states in which the window was evaluated; the others are UNKNOWN to the reader. */
export function coverageEvaluated(state: string): boolean {
  return state === "EVALUATED" || state === "BASELINE_LEARNING" || state === "NOT_EVALUABLE";
}

/** "3 of 4 recorded windows evaluated"; never a bare "OK". */
export function coverageLine(c: LogCoverageSummary): string {
  if (c.windows_total === 0) return "UNKNOWN: no window has been evaluated yet";
  const unknown = c.windows_total - c.windows_evaluated;
  return unknown === 0
    ? `${c.windows_evaluated} of ${c.windows_total} recorded windows evaluated`
    : `${c.windows_evaluated} of ${c.windows_total} recorded windows evaluated; ${unknown} UNKNOWN`;
}

/** The chip's label: the worst open severity with its count, or the coverage word when there is nothing to count. */
export function chipLabel(chip: LogFindingChipData): string {
  if (chip.state === "FINDINGS" && chip.severity) return `${chip.severity} ${chip.count}`;
  return chip.state === "NO_FINDINGS" ? "NO FINDINGS" : "UNKNOWN";
}

export function chipTone(chip: LogFindingChipData): Tone {
  if (chip.state === "FINDINGS" && chip.severity) return severityTone(chip.severity);
  // A device without findings is green only when every recorded window was evaluated (the server decided that).
  return chip.state === "NO_FINDINGS" ? "ok" : "unknown";
}

/** "hw.edac.ce on CPU_SrcID#1_MC#0_Chan#2_DIMM#0": the marker and, when it has one, its evidence key. */
export function findingTitle(f: LogFinding): string {
  return f.key_token ? `${f.marker_id} on ${f.key_token}` : f.marker_id;
}

/** "45 lines on 2026-11-24, 00:30 to 04:54 (device-local)". */
export function findingCounts(f: LogFinding): string {
  const time = (t: string) => t.slice(11, 16);
  return `${f.counts.lines} lines on ${f.window_day}, ${time(f.window_start)} to ${time(f.window_end)} (device-local)`;
}

const LABEL_WORDS: Record<string, string> = {
  HISTORY_UNDER_7_DAYS: "history under 7 days",
  CHRONIC: "chronic on this device",
  FLEET_BACKGROUND: "fleet background",
  PROVISIONAL_CAP: "capped to ATTENTION: meaning not yet cited",
};

export function labelWord(label: string): string {
  return LABEL_WORDS[label] ?? label;
}

/** The shadow banner: findings are stored but shown on this admin screen only. */
export function shadowNote(shadow: boolean, until: string | null): string | null {
  if (!shadow) return null;
  return until
    ? `Shadow period until ${until.slice(0, 10)}: findings are stored and shown here only; the devices list does not show them yet.`
    : "Shadow period: findings are stored and shown here only; the devices list does not show them yet.";
}

/** Findings of ATTENTION or above are counted; INFO is listed. */
export function countedFindings(findings: readonly LogFinding[]): readonly LogFinding[] {
  return findings.filter((f) => f.severity === "CRITICAL" || f.severity === "ATTENTION");
}
