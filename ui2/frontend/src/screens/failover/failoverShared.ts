import { readinessReasonLabel, requestFailure } from "../../shell/presenters";
import type { CpFailoverOperation, CpFailoverRunDetail, CpFailoverState, CpFailoverUnit } from "../../auth/adminApi";

export const activeStates: CpFailoverState[] = ["PLANNED", "PRECHECK", "FAILING_OVER", "SWITCHED", "POSTCHECK", "RETURNING"];
export function runStep(state: CpFailoverState, step: string): number {
  const current = state === "STOPPED" ? step : state;
  if (current === "FINAL_POSTCHECK" || current === "POST_RETURN" || current === "POSTCHECK" && ["FINAL_POSTCHECK", "POST_RETURN", "final"].includes(step)) return 5;
  return ({ PLANNED: 0, PRECHECK: 0, FAILING_OVER: 1, SWITCHED: 2, POSTCHECK: 3, RETURNING: 4, DONE: 6 } as Record<string, number>)[current] ?? 0;
}
/** The server's reason for a refused request: its `code`, else its `error` (ACTION_MAPPING_REQUIRED, ACTION_REFUSED ...
 * bodies carry only `error`), with the HTTP status; never a bare "Request failed". */
export const errorText = (error: unknown) => {
  const failure = error as { status?: number; body?: { code?: string; error?: string; message?: string } } | null | undefined;
  const body = failure?.body;
  if (body?.code === "BUSINESS_HOURS_CONFIRM_REQUIRED") return body.message ?? "Not started: business hours were not confirmed.";
  if (body?.code === "OUTSIDE_JOB_WINDOW") return body.message ?? "Device jobs are outside the scheduled window.";
  if (body?.code === "DIRECTORY_UNREACHABLE_AT_START") return body.message ?? "Directory unreachable (DIRECTORY_UNREACHABLE_AT_START): directory group membership could not be refreshed. Retry when the directory is reachable.";
  // A VSX chassis refusal (mode, sequence, per-VS readiness) is read in words first; the code and HTTP status stay beside it.
  const code = body?.code ?? body?.error;
  if (code && CHASSIS_CODE.test(code)) return `${readinessReasonLabel(code)} (${requestFailure(failure)})`;
  return requestFailure(failure);
};
const CHASSIS_CODE = /^(VSX_|VIRTUAL_SYSTEM_|SEQUENCE_|VS_RUN_|CHASSIS_RUN_)/;
export const isDirectoryUnreachable = (error: unknown) =>
  (error as { body?: { code?: string } } | null | undefined)?.body?.code === "DIRECTORY_UNREACHABLE_AT_START";
/** `operation`: which chassis action the request is for (absent = the unit's own failover; on a VSX chassis, VS0). */
export type Draft = { requestId: string; from: string; until: string; reason: string; confirmedRevision?: number; operation?: CpFailoverOperation };
/** The one free-text field of a failover or resume request (PO 2026-11-23): a work record, not an approval form. */
export const WORK_LOG_NOTE_LABEL = "Work log note";
export const WORK_LOG_NOTE_MAX = 500;
/** How long a request stays startable. A single administrator's request always uses the default; nothing is typed. */
export const VALIDITY_MINUTES = [30, 60, 120] as const;
export const DEFAULT_VALIDITY_MINUTES = 60;
/**
 * The readiness line of the confirm dialog. "Ready" needs a finished readiness run whose outcome is READY and a unit
 * the server does not refuse; a missing, running or unrecognised result is Unknown and is never worded as ready. A
 * server refusal is shown as the server sent it.
 */
export type ReadinessVerdict = { tone: "ready" | "warning" | "blocked" | "unknown"; text: string; detail?: string };
export function failoverReadinessVerdict(unit: CpFailoverUnit, readiness: CpFailoverRunDetail | null,
  operation: CpFailoverOperation = "FAILOVER"): ReadinessVerdict {
  // "Fail over all virtual systems" is read on the virtual systems' own readiness (the server's sequenceStartRefusal), never VS0's.
  if (operation === "FAILOVER_ALL_VIRTUAL_SYSTEMS") return unit.sequenceStartRefusal
    ? { tone: "blocked", text: `Refused: ${unit.sequenceStartRefusal}` }
    : { tone: "ready", text: "Every virtual system's latest pre-checks are ready", detail: "each run repeats its own pre-checks" };
  const refusal = unit.refusalReason || unit.chassisStartRefusal || unit.failoverRefusal;
  if (refusal) return { tone: "blocked", text: `Refused: ${refusal}` };
  if (!readiness) return { tone: "unknown", text: "Unknown: no readiness result on record" };
  if (activeStates.includes(readiness.state)) return { tone: "unknown", text: "Unknown: pre-checks are still running" };
  const pre = readiness.checks.filter(c => c.phase === "pre");
  if (readiness.outcome === "READY") {
    const warned = [...new Set(pre.filter(c => (c.status ?? c.result) === "WARN").map(c => c.title))];
    if (!warned.length) return { tone: "ready", text: "Ready" };
    const listed = warned.slice(0, 3).join("; "), more = warned.length - 3;
    return { tone: "warning", text: `Ready with ${warned.length} ${warned.length === 1 ? "warning" : "warnings"}`,
      detail: more > 0 ? `${listed}; +${more} more` : listed };
  }
  if (readiness.outcome === "NOT_READY") {
    const blockers = new Set(pre.filter(c => c.blocking && ["FAIL", "UNKNOWN"].includes(c.status ?? c.result)).map(c => c.checkNo)).size;
    return { tone: "blocked", text: `Not ready${blockers ? ` · ${blockers} ${blockers === 1 ? "blocker" : "blockers"}` : ""}`,
      detail: readiness.message ?? undefined };
  }
  return { tone: "unknown", text: "Unknown: the last readiness run recorded no verdict" };
}
/** Workers write "post_return" after the return step; "final" is the older name. Both are the final post-checks. */
export const FINAL_PHASES: ReadonlyArray<string> = ["post_return", "final"];
export const recoveryLabel = (mode?: string) => mode === "ACTIVE_UP" ? "Active Up" : mode === "PRIMARY_UP" ? "Primary Up" : "Unknown";
