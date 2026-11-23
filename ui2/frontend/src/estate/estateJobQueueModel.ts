/**
 * Pure mapping for the Estate System "Job queue" section (read-only; standard-view parity with the Operations job counts,
 * which come from the same job statistics read).
 *
 * Rules: every figure names what it counts and its window; a field the API did not return is "not reported", never 0;
 * a failed or refused read is never a zero count and never OK; each figure is stamped with the age of this read.
 */
import type { JobStatsView } from "../auth/adminApi";
import type { BadgeState } from "./estateModel";
import { BADGE_WORD_DEFAULT } from "./estateModel";
import { plural } from "../shell/plural";
import type { TimedRead } from "./estateSystemModel";

export const JOB_QUEUE_NOT_REPORTED = "not reported";
export const JOB_QUEUE_FAILED_TEXT = "Job queue could not be read";
export const JOB_QUEUE_RESTRICTED_TEXT = "Restricted for this account";

export type JobQueueFigureId = "running" | "failed" | "completed" | "outcome_unknown";

export interface JobQueueFigure {
  readonly id: JobQueueFigureId;
  /** What the figure counts, with its window words. */
  readonly label: string;
  /** null when the API did not report the field (rendered as "not reported", never 0). */
  readonly value: string | null;
}

export type JobQueueSection =
  | { readonly kind: "unreadable"; readonly text: string }
  | {
      readonly kind: "ok";
      readonly badge: BadgeState;
      readonly word: string;
      readonly figures: readonly JobQueueFigure[];
    };

/** A finite number as the API returned it; anything else (absent, null, NaN, a string) is "not reported". */
function count(n: unknown): number | null {
  return typeof n === "number" && Number.isFinite(n) ? n : null;
}

/** The four figures in screen order. A missing field gives a null value, never 0. */
export function mapJobQueueFigures(stats: JobStatsView): readonly JobQueueFigure[] {
  const running = count(stats.running);
  const failed = count(stats.failed_24h);
  const total = count(stats.total_24h);
  const completed = count(stats.completed_24h);
  const outcomeUnknown = count(stats.outcome_unknown_24h);
  return [
    { id: "running", label: "Running now (jobs in flight at the read)", value: running === null ? null : plural(running, "job", "jobs") },
    {
      id: "failed",
      label: "Failed in the last 24 h, out of all jobs in the last 24 h",
      value: failed === null || total === null ? null : `${failed} of ${total} jobs`,
    },
    { id: "completed", label: "Completed in the last 24 h", value: completed === null ? null : plural(completed, "job", "jobs") },
    {
      id: "outcome_unknown",
      label: "Outcome unknown in the last 24 h",
      value: outcomeUnknown === null ? null : plural(outcomeUnknown, "job", "jobs"),
    },
  ];
}

/**
 * The section for one job-statistics read. A failed read is unreadable (not a zero count); a refused read says so.
 * The badge is never OK while a figure is not reported: an unreported figure is UNKNOWN, not healthy.
 */
export function mapJobQueue(jobs: TimedRead<JobStatsView>): JobQueueSection {
  const kind = jobs.read.state;
  if (kind === "restricted") return { kind: "unreadable", text: JOB_QUEUE_RESTRICTED_TEXT };
  if (kind !== "ok") return { kind: "unreadable", text: JOB_QUEUE_FAILED_TEXT };

  const stats: unknown = jobs.read.value;
  // A payload that is not an object (absent, null, an array, a scalar) is unreadable, never a throw or a zero count.
  if (!stats || typeof stats !== "object" || Array.isArray(stats)) return { kind: "unreadable", text: JOB_QUEUE_FAILED_TEXT };
  const figures = mapJobQueueFigures(stats as JobStatsView);
  const anyMissing = figures.some((f) => f.value === null);
  const typed = stats as JobStatsView;
  const flagged = (count(typed.failed_24h) ?? 0) > 0 || (count(typed.outcome_unknown_24h) ?? 0) > 0;
  const badge: BadgeState = anyMissing ? "unk" : flagged ? "watch" : "ok";
  return { kind: "ok", badge, word: BADGE_WORD_DEFAULT[badge], figures };
}
