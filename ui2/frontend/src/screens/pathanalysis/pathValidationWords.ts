import type { ApiError, PathBranch, PathHop, PathResult, PathValidation, PathValidationHop, PathQueryRequest } from "../../auth/adminApi";
import { PATH_VALIDATION_MAX_HOPS } from "../../auth/adminApi";
import type { Tone } from "../../shell/tone";

/**
 * Stage A6 (PATH_ANALYSIS_DESIGN.md §7) in the words a firewall engineer reads. A verification asks each firewall's own
 * read-only matcher (a route lookup, a policy match) whether it agrees with the hop neXus computed; what comes back and
 * is shown is MATCH / MISMATCH / NOT_EVALUABLE with a reason code -- never the device's answer, which the worker parses
 * in memory and discards. NOT_EVALUABLE is never a mismatch. A code this table does not know is shown as it is.
 */

/** The one-line explanation beside the action. */
export const VERIFY_EXPLANATION =
  "Asks each firewall's own read-only matcher (route lookup, policy match) whether it agrees with the hop neXus computed; only match or mismatch per hop is stored, never the device's answer.";

export const UDP_NOTICE =
  "UDP: the approved Palo Alto policy-match command is TCP only, so a Palo Alto hop's rule and NAT are NOT_EVALUABLE for this query; its route is still asked.";

export const RESULT_WORD: Readonly<Record<string, string>> = {
  MATCH: "Device MATCH",
  MISMATCH: "Device MISMATCH",
  NOT_EVALUABLE: "Device NOT_EVALUABLE",
  PENDING: "Device pending",
};

export function resultWord(result: string | null | undefined): string {
  if (!result) return "Device NOT_EVALUABLE";
  return RESULT_WORD[result] ?? `Device ${result}`;
}

export function resultTone(result: string | null | undefined): Tone {
  switch (result) {
    case "MATCH": return "ok";
    case "MISMATCH": return "bad";
    case "PENDING": return "neutral";
    default: return "unknown";
  }
}

/** The overall word of a verification: agreement, disagreement, nothing decided, or still waiting. */
export const OVERALL_WORD: Readonly<Record<string, string>> = {
  MATCH: "Devices agree",
  MISMATCH: "A device disagrees",
  NOT_EVALUABLE: "Not evaluable",
  PENDING: "Waiting for the devices",
};

export const CHECK_LABEL: Readonly<Record<string, string>> = {
  CP_ROUTE: "Route (Check Point)",
  PAN_FIB: "Route (FIB)",
  PAN_SECURITY: "Security rule",
  PAN_NAT: "NAT rule",
};

export function checkLabel(check: string): string {
  return CHECK_LABEL[check] ?? check;
}

const WORDS: Readonly<Record<string, string>> = {
  // ---- planned but not asked (the result does not decide it, or the approved command cannot target it)
  VENDOR_UNSUPPORTED: "This unit's vendor has no approved matcher; nothing was sent.",
  ACTIVE_MEMBER_UNKNOWN: "Which cluster member is active is not known, so no device was asked.",
  INVENTORY_UNAVAILABLE: "No stored inventory for this device, so the matcher's values could not be checked against it; nothing was sent.",
  CONTEXT_NOT_IN_INVENTORY: "This hop's context is not in the device's stored inventory; nothing was sent.",
  PLAN_INVALID: "No check could be planned from the computed hop; nothing was sent.",
  CONTEXT_UNSUPPORTED: "Check Point: this context is not a VSID the approved command can target.",
  ROUTING_TABLE_NOT_MAIN: "Check Point: only the main routing table can be asked.",
  DESTINATION_INVALID: "The hop's destination is not a plain IPv4 host the matcher takes.",
  NO_ROUTE_NOT_COMPARABLE: "Palo Alto: the FIB answer for \"no route\" is not established, so a hop without a route is not compared.",
  ENGINE_ROUTE_UNKNOWN: "neXus did not decide the route here, so there is nothing to compare.",
  ENGINE_INTERFACE_UNKNOWN: "neXus did not decide the egress interface here, so there is nothing to compare.",
  ENGINE_ROUTE_KIND_NOT_COMPARABLE: "The computed route kind has no comparable device answer.",
  VSYS_TARGET_NOT_APPROVED: "Palo Alto: only a firewall's own vsys1 is an approved target; this vsys was not validated.",
  VIRTUAL_ROUTER_UNKNOWN: "Palo Alto: this hop's virtual router is not in the stored inventory; the route was not asked.",
  PROTOCOL_NOT_APPROVED: "UDP: the approved policy-match command is TCP only, so the device's rule and NAT match were not asked.",
  POLICY_STALE: "The stored policy is stale: a rule comparison would be against a policy the device may no longer run, so it was not asked.",
  TUPLE_INVALID: "The packet's addresses or port are not plain values the matcher takes.",
  ZONE_UNKNOWN: "The ingress or egress zone is not in the stored inventory; the policy match needs both.",
  ENGINE_RULE_UNKNOWN: "neXus did not decide the rule here, so there is nothing to compare.",
  ENGINE_NAT_UNKNOWN: "neXus did not decide NAT here, so there is nothing to compare.",
  NAT_ZONE_NOT_IN_RESULT: "The pre-NAT egress zone the NAT match needs is not in the computed result.",
  // ---- admission (no job reached the device)
  ADMISSION_REFUSED: "The device job was not admitted; nothing was sent.",
  ADMISSION_FAILED: "Admission stopped before this hop's job (for example the window closed); nothing was sent.",
  JOB_NOT_BOUND: "A job was admitted but could not be bound to this hop, so its outcome is not recorded here.",
  CAPABILITY_NOT_EXECUTION_ELIGIBLE: "Path validation is not an eligible capability for this device; nothing was sent.",
  CAPABILITY_UNKNOWN: "Path validation is not a known capability for this device; nothing was sent.",
  DEVICE_NOT_ELIGIBLE: "This device is not eligible for a read job (not enrolled as a gateway of this vendor, or reads not permitted).",
  DEVICE_DISABLED: "This device is disabled; nothing was sent.",
  DEVICE_NOT_FOUND: "This device is not enrolled; nothing was sent.",
  IDEMPOTENCY_KEY_CONFLICT: "A job with the same request key already exists; this one was not admitted.",
  JOB_ENDED_WITHOUT_OUTCOME: "The job ended (cancelled, expired or reconciled) without recording an outcome.",
  // ---- the worker's own checks before contact
  HOP_NOT_PENDING: "The hop was no longer pending when the job ran; nothing was sent.",
  PLAN_UNREADABLE: "The stored plan could not be read by the worker; nothing was sent.",
  PLAN_DEVICE_MISMATCH: "The plan named a different device than the job; nothing was sent.",
  PLAN_CHECKS_INVALID: "The plan's checks were not valid for this vendor; nothing was sent.",
  VSID_INVALID: "The VSID failed the worker's own check; nothing was sent.",
  FIB_PARAMETER_INVALID: "A route-lookup value failed the worker's own check; nothing was sent.",
  POLICY_PARAMETER_INVALID: "A policy-match value failed the worker's own check; nothing was sent.",
  TRANSPORT_NOT_ELIGIBLE: "No eligible transport is enrolled for this device (SSH for Check Point, the XML API for Palo Alto).",
  PLATFORM_NOT_SUPPORTED: "Check Point Gaia Embedded: the route command is not approved for this platform.",
  VIRTUAL_ROUTER_NOT_IN_INVENTORY: "Palo Alto: the virtual router was not in the stored inventory when the job ran.",
  ZONE_NOT_IN_INVENTORY: "Palo Alto: a zone was not in the stored inventory when the job ran.",
  GATE_UNAVAILABLE: "The approved command's gate is not signed off or not available; nothing was sent.",
  // ---- contact
  CONNECTION_FAILED: "The device could not be reached or did not authenticate.",
  AUTH_UNAVAILABLE: "Palo Alto: no API key could be obtained.",
  API_UNAVAILABLE: "The device's API did not answer successfully.",
  OUTPUT_UNAVAILABLE: "The command gave no complete output.",
  ANSWER_TOO_LARGE: "The answer exceeded the bounded size and was not parsed.",
  ANSWER_NOT_RECOGNISED: "The device's answer was not in a shape this product recognises; it was not compared.",
  DESTINATION_IS_LOCAL: "The device says the destination is its own address; a local route is not compared.",
  ECMP_DEVICE_CHOICE: "Equal-cost routes: the device shows its one hashed choice, so a difference from this branch is expected, not a mismatch.",
  DEFAULT_RULE_NOT_REPORTED: "Palo Alto reports no rule name for a default-rule match; the empty answer is not a mismatch.",
  // ---- agreement
  NO_ROUTE_BOTH: "Neither has a route for the destination.",
  DISCARD_BOTH: "Both discard the destination.",
  CONNECTED_SAME_INTERFACE: "Connected on the same interface.",
  SAME_INTERFACE_NEXT_HOP_NOT_COMPARED: "Same egress interface; the next hop was not compared.",
  SAME_INTERFACE_AND_NEXT_HOP: "Same egress interface and next hop.",
  SAME_FIRST_MATCH_RULE: "The device's first-match security rule is the computed one.",
  NO_NAT_RULE_BOTH: "Neither matches a NAT rule.",
  SAME_NAT_RULE: "The device's NAT rule is the computed one.",
  // ---- disagreement
  KERNEL_HAS_ROUTE: "neXus found no route; the device has one.",
  KERNEL_NOT_DISCARD: "neXus computed a discard route; the device forwards.",
  FIB_NOT_DISCARD: "neXus computed a discard route; the device forwards.",
  DEVICE_DISCARDS: "neXus computed forwarding; the device discards.",
  EGRESS_INTERFACE_DIFFERS: "The device forwards on a different interface.",
  DEVICE_ROUTES_VIA_GATEWAY: "neXus computed a connected route; the device forwards via a gateway.",
  NEXT_HOP_DIFFERS: "Same interface, different next hop.",
  DEVICE_MATCHED_NO_RULE: "The device matches no security rule where neXus matched one.",
  FIRST_MATCH_RULE_DIFFERS: "The device's first-match security rule differs from the computed one.",
  DEVICE_MATCHED_NAT_RULE: "The device matches a NAT rule where neXus computed none.",
  DEVICE_MATCHED_NO_NAT_RULE: "The device matches no NAT rule where neXus computed one.",
  NAT_RULE_DIFFERS: "The device's NAT rule differs from the computed one.",
};

/** The one-line meaning of a validation reason code; a code the table does not know is written as it is. */
export function explainValidation(code: string): string {
  return WORDS[code] ?? code;
}

export function hasValidationExplanation(code: string): boolean {
  return code in WORDS;
}

/** The query body a verification needs: the computed result's own query, exactly as the server returned it. */
export function requestFromResult(result: PathResult): PathQueryRequest {
  const q = result.query;
  const request: { -readonly [K in keyof PathQueryRequest]: PathQueryRequest[K] } = {
    src: q.src, dst: q.dst, protocol: q.protocol === "udp" ? "udp" : "tcp", port: q.port,
  };
  if (q.ingress_node_id) request.ingress_node_id = q.ingress_node_id;
  if (q.ingress_node_id && q.ingress_interface_name) request.ingress_interface_name = q.ingress_interface_name;
  if (q.edge_mode) request.edge_mode = true;
  return request;
}

/** The branch the server verified: the one asked for, else the one its hops name, else the first. */
export function verifiedBranchId(validation: PathValidation | null, requested: string | undefined, result: PathResult): string | undefined {
  return requested ?? validation?.hops[0]?.branch_id ?? result.branches[0]?.branch_id;
}

export interface ValidationCounts {
  readonly total: number;
  readonly match: number;
  readonly mismatch: number;
  readonly notEvaluable: number;
  readonly pending: number;
  /** Hops among the NOT_EVALUABLE ones that no device was asked about (no check ran). */
  readonly notContacted: number;
  readonly beyondCap: number;
}

export function countValidation(validation: PathValidation): ValidationCounts {
  const hops = validation.hops;
  const of = (result: string) => hops.filter((h) => h.result === result).length;
  return {
    total: hops.length,
    match: of("MATCH"),
    mismatch: of("MISMATCH"),
    notEvaluable: of("NOT_EVALUABLE"),
    pending: of("PENDING"),
    notContacted: hops.filter((h) => h.result === "NOT_EVALUABLE" && h.checks.length === 0).length,
    beyondCap: validation.hops_not_validated,
  };
}

const hopsWord = (n: number) => `${n} hop${n === 1 ? "" : "s"}`;

/** `3 of 4 hops confirmed by the devices; 1 could not be evaluated. 2 hops beyond the 4-hop cap were not asked.` */
export function summarise(validation: PathValidation): string {
  const c = countValidation(validation);
  const cap = c.beyondCap > 0 ? ` ${hopsWord(c.beyondCap)} beyond the ${PATH_VALIDATION_MAX_HOPS}-hop cap of one request ${c.beyondCap === 1 ? "was" : "were"} not asked.` : "";
  if (c.total === 0) return `No unit hop of this branch could be asked.${cap}`;
  if (c.pending > 0) {
    return `Waiting for the devices: ${c.total - c.pending} of ${hopsWord(c.total)} answered.${cap}`;
  }
  const parts: string[] = [];
  if (c.mismatch > 0) parts.push(`${c.mismatch} disagree${c.mismatch === 1 ? "s" : ""} with the computed path`);
  if (c.notEvaluable > 0) {
    parts.push(c.notContacted === c.notEvaluable
      ? `${c.notEvaluable} could not be evaluated (no device was asked)`
      : `${c.notEvaluable} could not be evaluated`);
  }
  return `${c.match} of ${hopsWord(c.total)} confirmed by the devices${parts.length ? `; ${parts.join("; ")}` : ""}.${cap}`;
}

export type ValidationHopView =
  | { readonly kind: "validated"; readonly hop: PathValidationHop }
  | { readonly kind: "beyond_cap" }
  | { readonly kind: "none" };

/**
 * What the verification says about one hop of the result: its recorded state, "beyond the cap" for a unit hop of the
 * verified branch that was not planned, nothing for a cloud node or a hop of another branch.
 */
export function hopValidation(validation: PathValidation | null, branchId: string | undefined, branch: PathBranch, hop: PathHop): ValidationHopView {
  if (!validation || branch.branch_id !== branchId || hop.node_kind === "CLOUD") return { kind: "none" };
  const found = validation.hops.find((h) => h.branch_id === branch.branch_id && h.hop_index === hop.hop_index);
  if (found) return { kind: "validated", hop: found };
  const last = Math.max(0, ...validation.hops.map((h) => h.hop_index));
  return validation.hops_not_validated > 0 && hop.hop_index > last ? { kind: "beyond_cap" } : { kind: "none" };
}

export type ValidationFailureKind =
  | "restricted" | "window" | "not_confirmed" | "not_built" | "refused" | "branch_gone" | "rate_limited" | "busy" | "audit"
  | "invalid" | "record_gone" | "offline" | "error";

export interface ValidationFailure {
  readonly kind: ValidationFailureKind;
  readonly code: string | null;
  readonly status: number;
  /** The server's own sentence, when it wrote one (the job window writes the next window start). */
  readonly message: string | null;
  readonly nextWindowStart: string | null;
}

/** The server's refusal of a verification, classified by status and code; nothing about the inputs is echoed back. */
export function classifyValidationFailure(error: unknown): ValidationFailure {
  const failure = (error ?? {}) as Partial<ApiError>;
  const status = typeof failure.status === "number" ? failure.status : -1;
  const body = failure.body ?? {};
  const code = typeof body.code === "string" ? body.code : typeof body.error === "string" ? body.error : null;
  const message = typeof body.message === "string" ? body.message : null;
  const nextWindowStart = typeof body.next_window_start === "string" ? body.next_window_start : null;
  const at = (kind: ValidationFailureKind, c: string | null = code): ValidationFailure => ({ kind, code: c, status, message, nextWindowStart });
  if (status === 0) return at("offline", code ?? "TIMEOUT");
  if (status === 403) return at("restricted");
  if (status === 409 && code === "OUTSIDE_JOB_WINDOW") return at("window");
  if (status === 409 && code === "BUSINESS_HOURS_CONFIRM_REQUIRED") return at("not_confirmed");
  if (status === 409 && code === "TOPOLOGY_NOT_BUILT") return at("not_built");
  if (status === 422 && code === "PSEUDONYM_REFUSED") return at("refused");
  if (status === 404 && code === "BRANCH_NOT_FOUND") return at("branch_gone");
  if (status === 404) return at("record_gone", code ?? "NOT_FOUND");
  if (status === 429) return at("rate_limited", code ?? "PATH_VALIDATION_RATE_LIMIT");
  if (status === 503 && code === "POLICY_BUSY") return at("busy");
  if (status === 503 && code === "AUDIT_UNAVAILABLE") return at("audit");
  if (status === 400) return at("invalid", code ?? "INVALID_REQUEST");
  return at("error");
}

/** `12:00` in the product's job-window zone, for the next window start the server names. */
export function windowTime(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso.replace(/\[.*\]$/, ""));
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Istanbul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(date);
}
