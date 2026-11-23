import type { JobEventView } from "../auth/adminApi";

/** Rows the dossier shows; anything beyond is counted in the "+N more" footer, never listed. */
export const DOSSIER_JOBS_CAP = 5;
export const DOSSIER_JOBS_NOT_EVALUABLE_TEXT = "Not evaluable for a cluster unit";
export const DOSSIER_JOBS_FAILED_TEXT = "Recent jobs could not be read";
export const DOSSIER_JOBS_TOTAL_UNKNOWN_TEXT = "Total jobs UNKNOWN; more jobs may exist beyond the listed rows";
export const DOSSIER_JOBS_STANDARD_HREF = "?screen=operations&tab=jobs&standard=1";

export function dossierJobsStandardHref(): string {
  return DOSSIER_JOBS_STANDARD_HREF;
}

/** One read of `listJobs({ device_id, page_size })`: either the page or a failure. */
export type DossierJobsRead =
  | { readonly ok: true; readonly items: readonly JobEventView[]; readonly total?: number }
  | { readonly ok: false };

export interface DossierJobRow {
  /** Job type as the API reports it, or "UNKNOWN" when missing/blank. Transcript content is never read here. */
  readonly jobType: string;
  /** State word as the API reports it, or "UNKNOWN" when missing/blank. */
  readonly state: string;
  /** Age source: the job's own submission record, not this read. */
  readonly submittedAt: string | null;
}

export type DossierJobsModel =
  | { readonly status: "not_evaluable" }
  | { readonly status: "failed" }
  | {
      readonly status: "ok";
      readonly rows: readonly DossierJobRow[];
      /** Jobs the page reports beyond the rows shown; 0 when the whole page is listed; null when total is unknown. */
      readonly remainder: number | null;
    };

function nonBlankOrUnknown(value: unknown): string {
  const trimmed = typeof value === "string" ? value.trim() : "";
  return trimmed !== "" ? trimmed : "UNKNOWN";
}

/**
 * Maps one read to what the dossier block prints. A cluster unit has no single device identifier, so it is not
 * evaluable and no call is made. A read that failed, or whose payload lacks its item list, is FAILED, never an
 * empty list and never OK.
 */
export function buildDossierJobs(read: DossierJobsRead | null, clusterUnit: boolean): DossierJobsModel {
  if (clusterUnit) return { status: "not_evaluable" };
  if (read === null || !read.ok || !Array.isArray(read.items)) return { status: "failed" };
  const shown = read.items.slice(0, DOSSIER_JOBS_CAP);
  const remainder =
    typeof read.total === "number" && Number.isFinite(read.total) && read.total >= shown.length
      ? read.total - shown.length
      : null;
  const rows: DossierJobRow[] = shown.map((job) => ({
    jobType: nonBlankOrUnknown(job.job_type),
    state: nonBlankOrUnknown(job.state),
    submittedAt: typeof job.submitted_at === "string" && job.submitted_at.trim() !== "" ? job.submitted_at : null,
  }));
  return { status: "ok", rows, remainder };
}
