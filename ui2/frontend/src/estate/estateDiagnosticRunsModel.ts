import type { DiagnosticHistoryRow } from "../auth/adminApi";
import { actorLabel, jobStateWord } from "../shell/presenters";
import { formatDuration } from "../shell/time";
import { parseTimeMs, type BadgeState } from "./estateModel";

/** Diagnostic runs listed before the section prints "+N more diagnostic runs". */
export const DIAGNOSTIC_RUNS_SHOWN = 10;
/** Fixed page size of `DiagnosticService.history` (page 0 only is read here); a full page may hide more runs. */
export const DIAGNOSTIC_HISTORY_PAGE_SIZE = 50;
export const DIAGNOSTIC_HISTORY_EVIDENCE_SOURCE = "diagnostic history read";
export const DIAGNOSTIC_RUNS_EMPTY_TEXT = "No diagnostic runs recorded.";
export const DIAGNOSTIC_RUNS_FAILED_TEXT = "Diagnostic runs could not be read.";
export const DIAGNOSTIC_RUNS_RESTRICTED_TEXT = "Diagnostic history is restricted for this role.";

export interface DiagnosticRunStateView {
  readonly state: BadgeState;
  readonly word: string;
}

/**
 * Maps a diagnostic run state to its Estate badge state and word.
 * An `OUTCOME_UNKNOWN` or unrecognised/blank state is always `unk` / `"UNKNOWN"`, never `OK` or `Fine`.
 */
export function diagnosticRunStateView(rawState: string | null | undefined): DiagnosticRunStateView {
  const normalized = (rawState ?? "").trim().toUpperCase();
  switch (normalized) {
    case "COMPLETED":
      return { state: "ok", word: jobStateWord(normalized) };
    case "CLAIMED":
      return { state: "watch", word: "Running" };
    case "REQUESTED":
    case "EXECUTING":
      return { state: "watch", word: jobStateWord(normalized) };
    case "FAILED":
    case "REJECTED":
      return { state: "act", word: jobStateWord(normalized) };
    case "CANCELLED":
    case "RECONCILED":
      return { state: "na", word: jobStateWord(normalized) };
    case "OUTCOME_UNKNOWN":
    default:
      return { state: "unk", word: "UNKNOWN" };
  }
}

export interface DiagnosticRunRowView {
  readonly key: string;
  readonly jobId: string;
  readonly target: string;
  readonly description: string;
  readonly actor: string;
  readonly badge: DiagnosticRunStateView;
  readonly duration: string;
  readonly submittedAt: string | null;
}

export type DiagnosticRunsRead =
  | { readonly status: "ok"; readonly runs: readonly Partial<DiagnosticHistoryRow>[]; readonly readAt: string }
  | { readonly status: "restricted"; readonly readAt: string }
  | { readonly status: "failed"; readonly readAt: string };

export type DiagnosticRunsModel =
  | {
      readonly status: "restricted";
      readonly badge: DiagnosticRunStateView;
      readonly text: string;
      /** `null` when no evidence was read (restricted or failed): the stamp renders UNKNOWN (a0). */
      readonly evidenceAt: null;
    }
  | {
      readonly status: "failed";
      readonly badge: DiagnosticRunStateView;
      readonly text: string;
      /** `null` when no evidence was read (restricted or failed): the stamp renders UNKNOWN (a0). */
      readonly evidenceAt: null;
    }
  | {
      readonly status: "ok";
      readonly total: number;
      readonly countLine: string;
      /** True when page 0 is full (50 rows): more runs may exist beyond the first page. */
      readonly truncated: boolean;
      readonly evidenceAt: string;
      readonly shown: readonly DiagnosticRunRowView[];
    };

function timeOf(iso: string | null | undefined): number {
  return parseTimeMs(iso) ?? Number.NEGATIVE_INFINITY;
}

function nonBlank(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed !== "" ? trimmed : null;
}

/**
 * Pure mapper for the read-only Estate "Diagnostic runs" section.
 * Orders runs by `submittedAt` newest first and caps at 10 rows. The `ok` evidence is the history read instant
 * (`read.readAt`), never a run's `submittedAt`. `failed` and `restricted` carry `evidenceAt: null` (no evidence).
 * A full first page (50 rows) is flagged `truncated` so the count is not presented as the complete total.
 */
export function buildDiagnosticRunsModel(read: DiagnosticRunsRead): DiagnosticRunsModel {
  if (read.status === "restricted") {
    return {
      status: "restricted",
      badge: { state: "na", word: "Restricted" },
      text: DIAGNOSTIC_RUNS_RESTRICTED_TEXT,
      evidenceAt: null,
    };
  }
  if (read.status === "failed" || !Array.isArray(read.runs)) {
    return {
      status: "failed",
      badge: { state: "unk", word: "UNKNOWN" },
      text: DIAGNOSTIC_RUNS_FAILED_TEXT,
      evidenceAt: null,
    };
  }

  const sorted = read.runs
    .map((row, index) => ({ row: row ?? {}, index }))
    .sort((a, b) => timeOf(b.row.submittedAt) - timeOf(a.row.submittedAt) || a.index - b.index);

  const shown: DiagnosticRunRowView[] = sorted.slice(0, DIAGNOSTIC_RUNS_SHOWN).map(({ row, index }) => {
    const jobId = nonBlank(row.jobId) ?? `diag-run-${index}`;
    const target = nonBlank(row.target) ?? "UNKNOWN";
    const description = nonBlank(row.description) ?? nonBlank(row.command) ?? "UNKNOWN";
    const actor = actorLabel(row.actor).label;
    const badge = diagnosticRunStateView(row.state);
    const duration = formatDuration(typeof row.durationMs === "number" ? row.durationMs : null);
    const submittedAt = nonBlank(row.submittedAt);
    return {
      key: `${jobId}:${index}`,
      jobId,
      target,
      description,
      actor,
      badge,
      duration,
      submittedAt,
    };
  });

  const total = sorted.length;
  const truncated = total >= DIAGNOSTIC_HISTORY_PAGE_SIZE;
  const countLine = truncated
    ? `${total} diagnostic runs on first page (more may exist)`
    : total === 1
      ? "1 diagnostic run listed"
      : `${total} diagnostic runs listed`;
  // The evidence is the history read, not the age of the newest run: each row keeps its own submittedAt.
  return {
    status: "ok",
    total,
    countLine,
    truncated,
    evidenceAt: read.readAt,
    shown,
  };
}
