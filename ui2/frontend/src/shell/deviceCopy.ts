import type { Tone } from "./tone";
import type { DeviceDetail } from "../auth/adminApi";

/**
 * Shared copy/tone conventions for `enrollment_state` and job-state
 * progress, so `AddDeviceDialog`, `InventoryScreen` and the Administration
 * device registry panel all describe the same backend values the same way.
 *
 * This build deliberately avoids liveness/reachability vocabulary
 * ("online"/"offline"/"reachable"/"up"/"down") anywhere in derived copy --
 * every label below is either the `enrollment_state` value verbatim or a
 * plain rewording of that exact value, never an inferred judgement about
 * whether a device is currently "live".
 */

const ENROLLMENT_STATE_LABEL: Record<string, string> = {
  DRAFT: "Registered, not confirmed",
  ENROLLED: "Enrolled",
  UNREACHABLE: "Unreachable",
  DEGRADED: "Degraded",
};

/** Falls back to the raw value verbatim for a state this build does not know yet. */
export function enrollmentStateLabel(state: string): string {
  return ENROLLMENT_STATE_LABEL[state] ?? state;
}

const ENROLLMENT_STATE_TONE: Record<string, Tone> = {
  DRAFT: "neutral",
  ENROLLED: "ok",
  UNREACHABLE: "warn",
  DEGRADED: "warn",
};

export function enrollmentStateTone(state: string): Tone {
  return ENROLLMENT_STATE_TONE[state] ?? "neutral";
}

const TERMINAL_JOB_STATES = new Set([
  "COMPLETED",
  "FAILED",
  "REJECTED",
  "CANCELLED",
  "OUTCOME_UNKNOWN",
  "RECONCILED",
]);

export function isTerminalJobState(state: string): boolean {
  return TERMINAL_JOB_STATES.has(state);
}

const JOB_PHASE_LABEL: Record<string, string> = {
  REQUESTED: "Connecting…",
  CLAIMED: "Connecting…",
  EXECUTING: "Reading identity… / Checking peer…",
  COMPLETED: "Done",
  FAILED: "Failed",
  REJECTED: "Rejected",
  CANCELLED: "Cancelled",
  OUTCOME_UNKNOWN: "Outcome unknown",
  RECONCILED: "Reconciled",
};

/** An unrecognized/future job state renders as generic progress rather than crashing. */
export function jobPhaseLabel(state: string): string {
  return JOB_PHASE_LABEL[state] ?? "Working…";
}

export function peerFollowMessage(detail: Pick<DeviceDetail, "peer_follow_outcome" | "peer_follow_reason">): string | null {
  if (detail.peer_follow_outcome === "CORROBORATED") return "Corroborated with peer";
  if (detail.peer_follow_outcome === "NOT_CONFIRMED") {
    return detail.peer_follow_reason ? `Peer not confirmed: ${detail.peer_follow_reason}` : "Peer not confirmed";
  }
  return null;
}
