import type { ApiError, PathBranch, PathHop, PathRule } from "../../auth/adminApi";
import type { Tone } from "../../shell/tone";

/**
 * The path engine's fixed vocabulary (`PathReasons`, `HopPolicyEvaluator`) in the words a firewall engineer reads:
 * one line per code, the code itself kept beside it. Nothing here is a verdict of its own -- a code the table does
 * not know is shown as the code, never dropped (fail closed: an unexplained reason is still a reason).
 */

export const VERDICT_WORD: Readonly<Record<string, string>> = {
  ALLOWED: "Allowed",
  DENIED: "Denied",
  UNKNOWN: "UNKNOWN",
  PARTIAL: "Partial",
  ASYMMETRIC: "Asymmetric",
  ALLOWED_TO_EDGE: "Allowed to the edge",
  CONDITIONAL: "Conditional",
};

export function verdictWord(verdict: string | null | undefined): string {
  if (!verdict) return "UNKNOWN";
  return VERDICT_WORD[verdict] ?? verdict;
}

/** The colour role of a verdict word: UNKNOWN is hatched, a conditional or edge answer is attention, never green. */
export function verdictTone(verdict: string | null | undefined): Tone {
  switch (verdict) {
    case "ALLOWED": return "ok";
    case "DENIED": return "bad";
    case "UNKNOWN": return "unknown";
    case "PARTIAL": return "warn";
    case "ASYMMETRIC": return "warn";
    case "ALLOWED_TO_EDGE": return "attn";
    case "CONDITIONAL": return "attn";
    default: return "neutral";
  }
}

/** What the overall verdict means for the question "can A reach B", in one sentence. */
export const VERDICT_SENTENCE: Readonly<Record<string, string>> = {
  ALLOWED: "Every unit on the path allows this traffic and the return path crosses the same stateful units.",
  DENIED: "A unit on the path blocks this traffic; the blocking hop and every hop before it are decided.",
  UNKNOWN: "The stored evidence does not decide this path. The hops marked UNKNOWN say what is missing; later hops hold only if they resolve.",
  PARTIAL: "Equal-cost routes lead to different answers: whether the traffic passes depends on which branch it takes.",
  ASYMMETRIC: "Return traffic crosses a different set of stateful units than the forward path: an out-of-state drop is likely.",
  ALLOWED_TO_EDGE: "Allowed by every unit up to the edge of the known topology. What happens beyond the edge is not evaluated: this is not a delivery claim.",
};

const WORDS: Readonly<Record<string, string>> = {
  // ---- overall and branch reasons
  ALL_HOPS_ALLOWED: "Every unit on the path allows this traffic.",
  DENIED_AT_HOP: "A unit on the path blocks this traffic; the hop is marked.",
  DENIED_AFTER_UNKNOWN_HOP: "A later unit would block this traffic, but an earlier hop is UNKNOWN, so the block is not claimed as certain.",
  UNKNOWN_HOP: "At least one unit could not be decided from the stored evidence.",
  MIXED_BRANCH_VERDICTS: "Equal-cost routes lead to different answers; the result depends on which branch the traffic takes.",
  ECMP_TRUNCATED: "More equal-cost branches exist than were followed (8 per hop, 16 in total): unfollowed branches may answer differently.",
  ASYMMETRIC_RETURN: "Return traffic crosses a different set of stateful units than the forward path.",
  AMBIGUOUS_INGRESS: "More than one unit could be the first hop for this source. Name the ingress unit to decide it.",
  INGRESS_NOT_FOUND: "No unit in the known topology holds a segment for this source address.",
  INGRESS_HINT_NOT_FOUND: "The ingress unit named in the query is not in the held topology.",
  INGRESS_INTERFACE_NOT_FOUND: "The ingress interface named in the query is not on that unit.",
  INGRESS_INFERRED: "The first hop was inferred from the source address, not given in the query.",
  // ---- terminations
  DELIVERED: "The packet reaches the destination segment.",
  EXITS_KNOWN_TOPOLOGY: "The path leaves the known topology (a cloud or an unmanaged next hop); nothing beyond is evaluated.",
  BLOCKED: "The packet is dropped at the marked hop.",
  NO_ROUTE: "No route for the destination on this unit.",
  ROUTING_LOOP: "The route returns to a unit already on the path: a routing loop.",
  HOP_LIMIT: "The hop limit was reached before the destination.",
  ANALYSIS_BUDGET: "The analysis budget was exhausted before the destination.",
  NEXT_HOP_AMBIGUOUS: "The next hop address is held by more than one unit; which one forwards is not decided.",
  NEXT_HOP_UNREACHABLE: "The next hop address is on no known segment.",
  NEXT_HOP_KIND_UNKNOWN: "The route's next-hop kind is not understood.",
  NEXT_VR_UNRESOLVED: "The route points to another virtual router that could not be resolved.",
  CONNECTED_SEGMENT_UNKNOWN: "The egress interface's connected segment is not known.",
  ENGINE_DATA_ERROR: "The engine met inconsistent stored data at this hop and stopped claiming anything.",
  // ---- hop reasons (routing, binding, policy)
  FIRST_MATCHING_RULE: "Decided by the first matching rule.",
  ALL_ORDERED_LAYERS_ALLOWED: "Every ordered layer allows it.",
  ROUTE_DISCARD: "The forwarding route is a discard (blackhole) route: routing drops the packet before any rule.",
  ROUTE_ACTIVE_UNKNOWN: "The best route's active state is not recorded: whether it forwards is UNKNOWN.",
  ROUTE_SELECTION_AMBIGUOUS: "Several equally specific routes are neither all equal-cost nor separated by a known metric.",
  ROUTE_TABLE_UNPARSEABLE: "A route row could not be parsed; it might be more specific than the one used.",
  CP_ROUTE_AFTER_NAT_UNKNOWN: "Check Point: whether routing sees the pre-NAT or post-NAT destination is not collected; the two lookups differ.",
  NAT_UNKNOWN_TUPLE_UNCHANGED: "NAT was UNKNOWN here, so the packet is traced untranslated.",
  POLICY_BINDING_UNPROVEN: "No stored policy is proven to be this unit's own.",
  POLICY_BINDING_AMBIGUOUS: "More than one stored policy names this unit and context; none is provably its own.",
  POLICY_CONTEXT_UNPROVEN: "This unit's context (a Check Point VSID) has no proven policy context.",
  POLICY_SOURCE_FAILED: "The stored policy for this unit could not be read.",
  POLICY_EVALUATION_FAILED: "The rule evaluation failed on this unit.",
  STALE_OR_OUT_OF_SYNC_POLICY: "The stored policy is stale or out of sync with the gateway; the last-known rule is context only, not a verdict.",
  UNSUPPORTED_NODE_VENDOR: "This unit's vendor is not evaluated by the path engine.",
  ACTIVE_MEMBER_UNPROVEN: "Which cluster member is active is not proven; the cluster is traced as one unit.",
  RELATIONSHIP_INCONSISTENT: "The stored relationships of this unit contradict each other.",
  // ---- rule-engine reasons and caveats (A2)
  CP_ACCESS_NAT_ORDER_UNKNOWN: "Check Point: whether access rules see the pre-NAT or the post-NAT address is not established; the two evaluations disagree, so this hop is UNKNOWN.",
  APP_ID_ASSUMED: "Palo Alto application-default: matched on the application's default ports; the traffic's real App-ID is assumed, not observed.",
  PAN_LOCAL_RULES_NOT_COLLECTED: "Palo Alto: the firewall's own local rules were not collected; a Panorama post-rule decided, and a local rule in between could change it.",
  CP_IMPLIED_RULES_NOT_MODELED: "Check Point implied rules are not modelled.",
  CP_CLEANUP_NOT_COLLECTED: "Check Point: the cleanup rule was not collected; an unmatched packet's fate is not proven.",
  CP_SOURCE_NAT_ACCESS_ON_PRE_NAT: "Check Point: access rules were evaluated on the pre-NAT source.",
  EARLIER_RULE_UNRESOLVED: "An earlier rule could match but could not be resolved.",
  INSTALL_ON_UNPROVEN: "Whether this rule is installed on this gateway is not proven.",
  LOOKUP_UNKNOWN: "The rule lookup could not decide.",
  NAT_UNKNOWN: "A NAT rule could match but could not be resolved: a translation may exist.",
  NAT_FIRST_MATCH: "First matching NAT rule in rulebase order.",
  NAT_NO_RULE_MATCHED: "No NAT rule matches: no translation.",
  TRANSLATED: "Translated.",
  NO_TRANSLATION: "No translation.",
  ORDERED_LAYER_UNKNOWN: "An ordered layer could not be decided.",
  PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED: "Palo Alto: the default rules' override levels were not collected.",
  PAN_DEFAULT_RULE_NOT_COLLECTED: "Palo Alto: no rule matched and the default rules were not collected; the vendor default is not assumed.",
  PAN_DEFAULT_RULE_OVERRIDE: "Palo Alto: decided by a collected default-rule override.",
  PAN_SECURITY_PRE_NAT_PORT: "Palo Alto: security rules were evaluated on the pre-NAT port.",
  PAN_TARGET_UNPROVEN: "Palo Alto: the rule's target selection for this firewall is not proven.",
  POLICY_SYNC_UNKNOWN: "Whether the stored policy matches the gateway is not known.",
  POST_NAT_EGRESS_ZONE_UNKNOWN: "Palo Alto: the post-NAT egress zone could not be determined.",
  SNAPSHOT_INCOMPLETE: "The policy snapshot is incomplete.",
  TARGET_CONTEXT_UNPROVEN: "The policy target's context is not proven to be this unit's.",
  UNSUPPORTED_PROTOCOL: "The protocol is not evaluated by the rule engine.",
  UNSUPPORTED_VENDOR: "The vendor is not evaluated by the rule engine.",
  ZONE_REQUIRED: "A zone is required to evaluate this rule and none is known for the hop.",
  APP_DEFAULT_PORTS_NOT_STORED: "The application's default ports are not stored: application-default cannot be matched.",
  // ---- topology caveats, notes and warnings
  CLUSTER_UNCORROBORATED: "The cluster relationship rests on one member's claim; the peer has not corroborated it.",
  CLUSTER_MEMBERS_DISAGREE: "The cluster members disagree about their relationship.",
  CONFIG_DERIVED_INTERFACES: "This unit's interfaces come from configuration, not from a runtime read.",
  MANAGEMENT_DERIVED_PREFIX: "A virtual address on this unit takes its prefix length from management configuration, not from a runtime read.",
  PBR_PRESENT: "Policy-based routing is present on this unit and is not evaluated; the route shown may not be the one used.",
  HA_ROUTE_DRIFT: "The HA members' routing tables differ.",
  RETURN_PATH_UNPROVEN: "The return walk ended before the source side without showing asymmetry: symmetry is unproven.",
  OUT_OF_STATE_DROP_LIKELY: "A stateful unit sees only one direction of the flow: an out-of-state drop is likely.",
  RETURN_NOT_COMPUTED_AT_EDGE: "The return path is not computed for a path that leaves the known topology.",
  // ---- return statuses
  SYMMETRIC: "Return traffic crosses the same stateful units.",
  ASYMMETRIC: "Return traffic crosses different stateful units.",
  INCOMPLETE: "The return walk did not reach the source side.",
  NOT_COMPUTED: "Not computed.",
};

/** The one-line meaning of a code; a code the table does not know is written as it is. */
export function explain(code: string): string {
  return WORDS[code] ?? code;
}

export function hasExplanation(code: string): boolean {
  return code in WORDS;
}

/** The hops that must resolve favourably before this hop's own evaluation counts: every earlier UNKNOWN hop of the branch. */
export function dependsOn(branch: PathBranch, hop: PathHop): number[] {
  if (hop.verdict !== "CONDITIONAL") return [];
  return branch.hops.filter((h) => h.hop_index < hop.hop_index && h.verdict === "UNKNOWN").map((h) => h.hop_index);
}

/** `#2 · RULE-DENY-01 · Local rules · deny`; an unnamed rule keeps its number. */
export function ruleLabel(rule: PathRule): string {
  const number = rule.rule_number != null ? `#${rule.rule_number}` : null;
  const name = rule.rule_name || (number ? null : "Unnamed rule");
  return [number, name, rule.section_name, rule.action].filter(Boolean).join(" · ");
}

/** A decided hop whose rule is not in the body: the server withheld it (fail-closed rule map, or a masked view). */
export function ruleWithheld(verdict: string, rule: PathRule | null | undefined): boolean {
  return (verdict === "ALLOWED" || verdict === "DENIED") && !rule;
}

export type PathFailureKind = "not_built" | "refused" | "busy" | "rate_limited" | "audit" | "invalid" | "restricted" | "offline" | "error";

export interface PathFailure {
  readonly kind: PathFailureKind;
  readonly code: string | null;
  readonly status: number;
}

/** The server's refusal, classified by status and code; nothing about the inputs is echoed back (the codes never carry a value). */
export function classifyPathFailure(error: unknown): PathFailure {
  const failure = (error ?? {}) as Partial<ApiError>;
  const status = typeof failure.status === "number" ? failure.status : -1;
  const body = failure.body ?? {};
  const code = typeof body.code === "string" ? body.code : typeof body.error === "string" ? body.error : null;
  if (status === 0) return { kind: "offline", code: code ?? "TIMEOUT", status };
  if (status === 403) return { kind: "restricted", code, status };
  if (status === 409 && code === "TOPOLOGY_NOT_BUILT") return { kind: "not_built", code, status };
  if (status === 422 && code === "PSEUDONYM_REFUSED") return { kind: "refused", code, status };
  if (status === 429) return { kind: "rate_limited", code: code ?? "PATH_QUERY_RATE_LIMIT", status };
  if (status === 503 && code === "POLICY_BUSY") return { kind: "busy", code, status };
  if (status === 503 && code === "AUDIT_UNAVAILABLE") return { kind: "audit", code, status };
  if (status === 400) return { kind: "invalid", code: code ?? "INVALID_REQUEST", status };
  return { kind: "error", code, status };
}

const IPV4_HOST = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;

export interface PathFormValues {
  readonly src: string;
  readonly dst: string;
  readonly protocol: "tcp" | "udp";
  readonly port: string;
  readonly ingressNode: string;
  readonly ingressInterface: string;
  readonly edge: boolean;
}

/** What the query is still waiting for, in field order; empty when it can be sent (the same syntax the server checks). */
export function pathFormMissing(values: PathFormValues): string[] {
  const missing: string[] = [];
  if (!values.src.trim()) missing.push("a source");
  else if (!IPV4_HOST.test(values.src.trim())) missing.push("an IPv4 source host (no prefix)");
  if (!values.dst.trim()) missing.push("a destination");
  else if (!IPV4_HOST.test(values.dst.trim())) missing.push("an IPv4 destination host (no prefix)");
  const port = Number(values.port);
  if (!values.port.trim()) missing.push("a port");
  else if (!Number.isInteger(port) || port < 1 || port > 65535) missing.push("a port between 1 and 65535");
  if (values.ingressInterface.trim() && !values.ingressNode.trim()) missing.push("the ingress unit of that interface");
  return missing;
}
