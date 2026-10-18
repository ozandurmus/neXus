import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";

import { StatusChip } from "../shell/M3Widgets";
import type { Tone } from "../shell/tone";

/**
 * The words the backup screens use for a stored archive's validation level, its byte-digest deviation state and a
 * backup attempt's outcome -- one vocabulary for the fleet table, the History dialog and a device's Backup tab, so the
 * same fact never reads two ways (UI review 2026-10-18: `V1` and grey `changed` on one screen against `Checked` and
 * amber `Changed since last` on the other; `COMPLETED: COMPLETED`; `CANCELLED: cancelled:`).
 */

export function backupDisplayWord(value: string): string {
  return ({
    V1: "Checked", FIRST: "First backup", CHANGED: "Changed since last", UNCHANGED: "Unchanged", "MAJOR DEVIATION": "Major change", "NOT EVALUATED": "Not evaluated",
  } as Record<string, string>)[value] ?? value;
}

const DEVIATION_MEANING: Record<string, string> = {
  FIRST: "The first archive of this device: nothing earlier to compare with",
  CHANGED: "The stored bytes differ from the previous archive of this device (a digest comparison, not a content analysis)",
  UNCHANGED: "Contents match the previous archive of this device",
  "MAJOR DEVIATION": "A semantic comparison found a major deviation",
  "NOT EVALUATED": "No comparison has run for this archive",
};

/** Normalises a stored deviation value the way the fleet rows do (`changed` becomes `CHANGED`, nothing becomes `NOT EVALUATED`). */
export function deviationState(value: string | null | undefined): string {
  return value ? value.toUpperCase() : "NOT EVALUATED";
}

/**
 * The deviation word. A nightly byte change is routine, so `CHANGED` stays neutral grey (`Bytes changed`) until a
 * comparison has actually run; only then is it an attention word. A major deviation is a fault whatever the screen
 * knows, because it only exists once a semantic comparison found it.
 */
export function DeviationChip({ state, comparisonRun = false }: { readonly state: string; readonly comparisonRun?: boolean }) {
  const tone: Tone = state === "MAJOR DEVIATION" ? "bad" : state === "CHANGED" && comparisonRun ? "attn" : "neutral";
  const label = state === "CHANGED" && !comparisonRun ? "Bytes changed" : backupDisplayWord(state);
  return (
    <Tooltip title={DEVIATION_MEANING[state] ?? state}>
      <Box component="span"><StatusChip tone={tone} label={label} dense /></Box>
    </Tooltip>
  );
}

/** The validation level remains an evidence grade; the UI uses a plain display word and a neutral tone. */
export function ValidationChip({ level, withLevel = false }: { readonly level: string; readonly withLevel?: boolean }) {
  const word = backupDisplayWord(level);
  const label = withLevel && word !== level ? `${word} (${level})` : word;
  return (
    <Tooltip title={`Backup check: ${label}`}>
      <Box component="span"><StatusChip tone="neutral" label={label} dense /></Box>
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Backup attempts
// ---------------------------------------------------------------------------------------------------------------

export interface AttemptWords {
  /** One status word: `Completed`, `Failed`, `Cancelled`, `Host key mismatch`, `Partial`. */
  readonly word: string;
  readonly tone: "ok" | "bad" | "warn" | "neutral";
  /** A short reason shown beside the word (generic failures only). */
  readonly inline: string | null;
  /** Everything else, kept one click away. */
  readonly detail: string | null;
}

const STATE_WORD: Record<string, string> = {
  COMPLETED: "Completed", FAILED: "Failed", CANCELLED: "Cancelled", REJECTED: "Rejected", OUTCOME_UNKNOWN: "Outcome unknown",
  EXECUTING: "Running", CLAIMED: "Claimed", REQUESTED: "Requested", SUBMITTED: "Submitted",
};
const INLINE_REASON_MAX = 60;

function stateWord(state: string): string {
  return STATE_WORD[state] ?? state.charAt(0) + state.slice(1).toLowerCase().replace(/_/g, " ");
}

/** The terminal reason without an echo of the state (`COMPLETED`, `cancelled:`), or null when nothing is left. */
function cleanReason(state: string, reason: string | null | undefined): string | null {
  let text = (reason ?? "").trim();
  const echo = new RegExp(`^(?:${state}|${stateWord(state)})\\b[\\s:.-]*`, "i");
  text = text.replace(echo, "").replace(/[\s:]+$/, "").trim();
  return text.length > 0 ? text : null;
}

/**
 * One status word per attempt instead of `COMPLETED: COMPLETED` / `CANCELLED: cancelled:`; the long machine lines
 * (`connect_failed: host_key_mismatch ...`, `partial: stored without ...`) shrink to a word with the full line behind a
 * disclosure. Nothing is dropped: the detail text is the original reason.
 */
export function attemptWords(state: string, reason: string | null | undefined): AttemptWords {
  const cleaned = cleanReason(state, reason);
  if (cleaned && /host_key_mismatch/i.test(cleaned)) return { word: "Host key mismatch", tone: "bad", inline: null, detail: cleaned };
  if (cleaned && /^partial\b/i.test(cleaned)) {
    return { word: "Partial", tone: "warn", inline: null, detail: cleaned.replace(/^partial[\s:.-]*/i, "").trim() || cleaned };
  }
  const tone = state === "COMPLETED" ? "ok" : state === "FAILED" || state === "REJECTED" ? "bad" : "neutral";
  if (!cleaned) return { word: stateWord(state), tone, inline: null, detail: null };
  // A successful run's reason (`completed in 8038ms`) is a note, not a status: it stays behind the disclosure.
  if (state === "COMPLETED" || cleaned.length > INLINE_REASON_MAX) return { word: stateWord(state), tone, inline: null, detail: cleaned };
  return { word: stateWord(state), tone, inline: cleaned, detail: null };
}
