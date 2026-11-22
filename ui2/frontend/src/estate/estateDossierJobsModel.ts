import type { JobEventView } from "../auth/adminApi";

/** Rows the dossier shows; anything beyond is counted in the "+N more" footer, never listed. */
export const DOSSIER_JOBS_CAP = 5;
export const DOSSIER_JOBS_NOT_EVALUABLE_TEXT = "Not evaluable for a cluster unit";
export const DOSSIER_JOBS_FAILED_TEXT = "Recent jobs could not be read";

/** One read of `listJobs({ device_id, page_size })`: either the page or a failure. */
export type DossierJobsRead =
  | { readonly ok: true; readonly items: readonly JobEventView[]; readonly total?: number }
  | { readonly ok: false };

export interface DossierJobRow {
  /** Job type as the API reports it. Transcript content is never read here. */
  readonly jobType: string;
  /** State word as the API reports it. */
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
      /** Jobs the page reports beyond the rows shown; 0 when the whole page is listed. */
      readonly remainder: number;
    };

/**
 * Maps one read to what the dossier block prints. A cluster unit has no single device identifier, so it is not
 * evaluable and no call is made. A read that failed, or whose payload lacks its item list, is FAILED, never an
 * empty list and never OK.
 */
export function buildDossierJobs(read: DossierJobsRead | null, clusterUnit: boolean): DossierJobsModel {
  if (clusterUnit) return { status: "not_evaluable" };
  if (read === null || !read.ok || !Array.isArray(read.items)) return { status: "failed" };
  const shown = read.items.slice(0, DOSSIER_JOBS_CAP);
  const total = typeof read.total === "number" && Number.isFinite(read.total) ? read.total : read.items.length;
  const remainder = Math.max(0, total - shown.length);
  const rows: DossierJobRow[] = shown.map((job) => ({
    jobType: String(job.job_type ?? ""),
    state: String(job.state ?? ""),
    submittedAt: typeof job.submitted_at === "string" && job.submitted_at !== "" ? job.submitted_at : null,
  }));
  return { status: "ok", rows, remainder };
}
