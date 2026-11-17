import type { ApiError, JobReconcileOutcome } from "../auth/adminApi";
import { ROLE_REFUSAL } from "../shell/actionAccess";
import { requestFailure } from "../shell/presenters";

/** The status a job in OUTCOME_UNKNOWN shows to everyone, whatever their role. */
export const RECONCILE_STATUS_WORD = "Outcome unknown - needs reconciliation";
/** Shown instead of the action for a failover run's job: its unknown sends hold the admission fence. */
export const FAILOVER_RELEASE_NOTE =
  "This job belongs to a failover run. It is released through the failover incident flow, not by reconciliation.";
export const EVIDENCE_MAX = 2000;

/** The three legal outcomes, in plain words. The server records the decision; it never contacts the device. */
export const RECONCILE_OUTCOMES: ReadonlyArray<{ readonly value: JobReconcileOutcome; readonly label: string; readonly hint: string }> = [
  { value: "RECONCILED_SUCCESS", label: "It worked", hint: "The command did take effect on the device." },
  { value: "RECONCILED_FAILURE", label: "It did not work", hint: "The command did not take effect on the device." },
  { value: "RECONCILED_STILL_UNKNOWN", label: "Still not known", hint: "I checked and still cannot tell what happened." },
];

/**
 * The job types whose command a failover run sends (the run's own job; a resume run uses the same type). The server
 * refuses these with JOB_BELONGS_TO_FAILOVER_RUN because each has a failover_dispatch_intent row. Mirrors the backend:
 * JooqCpFailoverRepository creates `cp_cluster_failover`, and the Palo Alto run is `pan_cluster_failover`. Readiness
 * checks (including `asa_cluster_failover`, the Cisco ASA and FortiGate readiness-only jobs, `fgt_failover_readiness`) and the HA configuration sync send no
 * failover command and stay reconcilable. An explicit set, never a substring guess: a new job type must be classified.
 */
export const FAILOVER_EXECUTION_JOB_TYPES: ReadonlySet<string> = new Set(["cp_cluster_failover", "pan_cluster_failover"]);

/** Whether the job is a failover run's own job, so the page offers no Reconcile for it. The server is the final judge. */
export function isFailoverRunJob(jobType: string | null | undefined): boolean {
  return FAILOVER_EXECUTION_JOB_TYPES.has((jobType ?? "").trim().toLowerCase());
}

/** Whether the page offers Reconcile for this job: the state is OUTCOME_UNKNOWN and it is not a failover run's job. */
export function isReconcilable(job: { readonly state: string; readonly job_type: string }): boolean {
  return job.state === "OUTCOME_UNKNOWN" && !isFailoverRunJob(job.job_type);
}

const REFUSAL_WORDS: Readonly<Record<string, string>> = {
  JOB_NOT_FOUND: "This job no longer exists.",
  JOB_NOT_OUTCOME_UNKNOWN: "This job is no longer waiting for reconciliation. It may already have been reconciled.",
  JOB_BELONGS_TO_FAILOVER_RUN: FAILOVER_RELEASE_NOTE,
  JOB_RECONCILIATION_INVALID: `The outcome or the evidence was not accepted. Choose one outcome and write evidence of 1 to ${EVIDENCE_MAX} characters.`,
};

/** The server's reason for a refused reconcile request in plain words; an unrecognised refusal keeps its code. */
export function reconcileRefusalWords(failure: ApiError | { status?: number; body?: Record<string, unknown> } | null | undefined): string {
  const body = failure?.body as { error?: string; code?: string } | undefined;
  const code = body?.error ?? body?.code;
  if (code && REFUSAL_WORDS[code]) return REFUSAL_WORDS[code];
  if (failure?.status === 403) return ROLE_REFUSAL;
  return requestFailure(failure as Parameters<typeof requestFailure>[0]);
}
