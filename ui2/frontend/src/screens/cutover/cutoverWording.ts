import type { CutoverCoverageItem, CutoverRule } from "../../auth/adminApi";
import type { StateRole } from "../../theme/tokens";

/**
 * The fixed words of the Cutover impact screen (CUTOVER_IMPACT_UI_BRIEF_2026_11_14.md, ACI-1 and ACI-6). The browser
 * computes no verdict: it names the server's codes. No state is green and no label says the cutover is fine; the best
 * verdict is a neutral chip with the coverage list right under it.
 */

/** Words no label, chip or sentence of this screen may carry (ACI-1). Tests scan the rendered text for them. */
export const FORBIDDEN_WORDS = ["safe", "ready", "pass", "passed", "healthy", "symmetric"] as const;
export const FORBIDDEN_PATTERN = new RegExp(`\\b(${FORBIDDEN_WORDS.join("|")})\\b`, "i");

export const READINESS: Readonly<Record<string, { readonly tone: StateRole; readonly word: string }>> = {
  BLOCKERS_FOUND: { tone: "bad", word: "Blockers found" },
  NOT_DECIDED: { tone: "warn", word: "Not decided" },
  // The best verdict is neutral, never "ok": it speaks only of the flows that were evaluated.
  NO_BLOCKERS_IN_EVALUATED_FLOWS: { tone: "neutral", word: "No blockers in evaluated flows" },
  NO_EVIDENCE: { tone: "unknown", word: "No evidence" },
};
export const readinessOf = (code: string | null | undefined) => READINESS[code ?? ""] ?? { tone: "unknown" as StateRole, word: "UNKNOWN" };

export const DECISIONS = ["WOULD_DROP", "NOT_DECIDED", "NOT_EVALUABLE", "WOULD_ALLOW"] as const;
export const DECISION: Readonly<Record<string, { readonly tone: StateRole; readonly word: string }>> = {
  WOULD_DROP: { tone: "bad", word: "Would drop" },
  NOT_DECIDED: { tone: "warn", word: "Not decided" },
  NOT_EVALUABLE: { tone: "unknown", word: "Not evaluable" },
  WOULD_ALLOW: { tone: "neutral", word: "Would allow" },
};
export const decisionOf = (code: string | null | undefined) => DECISION[code ?? ""] ?? { tone: "unknown" as StateRole, word: "UNKNOWN" };

export const TARGET_SOURCE: Readonly<Record<string, string>> = {
  PATH: "Target: computed path",
  OPERATOR_OVERRIDE: "Target: OPERATOR OVERRIDE",
};
export const targetSourceLabel = (source: string | null | undefined) => TARGET_SOURCE[source ?? ""] ?? "Target: UNKNOWN";

export const DIRECTION: Readonly<Record<string, string>> = { SEGMENT_TO_PEER: "Segment → peer", PEER_TO_SEGMENT: "Peer → segment" };
export const directionOf = (code: string | null | undefined) => DIRECTION[code ?? ""] ?? (code || "UNKNOWN");

export const STALE_REASON: Readonly<Record<string, string>> = {
  SEGMENT_CHANGED: "the segment was edited after this assessment",
  TARGET_POLICY_CHANGED: "a newer snapshot of the target policy is stored",
  OLD_POLICY_CHANGED: "a newer snapshot of the old-path policy is stored",
  POLICY_NOT_FOUND: "a policy this assessment cites is no longer stored",
  TOPOLOGY_CHANGED: "the topology the paths were computed on is no longer the one held",
};

const count = (item: CutoverCoverageItem) => (typeof item.count === "number" ? item.count.toLocaleString("en-US") : "UNKNOWN");
const stamp = (value: string | undefined) => (value && value.trim() ? value : "UNKNOWN");

/** One sentence per coverage code; an unknown code is shown as the code itself, never dropped. */
export function coverageText(item: CutoverCoverageItem): string {
  switch (item.code) {
    case "LOG_PERIOD": return `Log period ${stamp(item.periodStart)} – ${stamp(item.periodEnd)}. Only flows logged in this period are assessed.`;
    case "LOGGING_COMPLETENESS_UNKNOWN": return "Whether every flow of the period was logged is unknown.";
    case "UNLOGGED_FLOWS_INVISIBLE": return "A flow that was never logged cannot be assessed and is not counted anywhere.";
    case "INSTALL_STATE_NOT_VERIFIED": return "The stored target policy is taken as the installed one; the install state is not verified.";
    case "NAT_NOT_EVALUATED": return "The target policy holds NAT rules. NAT is not evaluated, so a translated flow may match a different rule.";
    case "TARGET_NOT_EVALUABLE": return "No evaluable policy is stored for the target firewall (policy not collected for its vendor yet): every flow is not evaluable.";
    case "TARGET_SNAPSHOT_INCOMPLETE": return "The stored target policy snapshot is incomplete: some of its parts failed to collect.";
    case "ZONE_PAIR_MISSING": return "Target without both zones or interfaces (Palo Alto, Cisco ASA, FortiGate): every flow stays not decided.";
    case "IPV6_FLOWS_SET_ASIDE": return `${count(item)} IPv6 rows set aside; this step assesses IPv4 only.`;
    case "INVALID_ROWS": return `${count(item)} rows could not be read.`;
    case "OUT_OF_SCOPE_FLOWS": return `${count(item)} rows fall outside this segment's blocks and peers and were not assessed.`;
    case "NON_TCP_UDP_FLOWS": return `${count(item)} flows are neither TCP nor UDP and stay not decided.`;
    case "APPLICATIONS_NOT_EVALUATED": return `${count(item)} flows meet a rule with an application constraint, which is not evaluated.`;
    case "ASSESSMENT_INCOMPLETE": return `The assessment reached its time limit: ${count(item)} flows were not decided.`;
    case "ACTION_VALUES_UNRECOGNISED": return `${count(item)} rows carry an action value not read as a drop (read as a drop: drop, deny, reject, block).`;
    case "TARGET_FROM_OPERATOR_OVERRIDE": return "The target firewall was named by the operator (override); it was not computed from the path.";
    case "TARGET_FROM_ROUTED_PATH": return "Each flow's target firewall comes from its computed path over the held topology.";
    case "FABRIC_REDIRECT_NOT_MODELLED": return "The fabric redirect (contracts, service graph / PBR) is not modelled yet: paths are today's routed paths, not the redirected ones.";
    default: return item.code || "UNKNOWN";
  }
}

export const ruleText = (rule: CutoverRule | null | undefined) => {
  if (!rule) return "";
  const number = rule.number === null || rule.number === undefined || rule.number === "" ? "" : `#${rule.number} `;
  return `${number}${rule.name || rule.ruleId || "Unnamed rule"}${rule.action ? ` (${rule.action})` : ""}`;
};

/** The flags of one flow, in words. CONDITIONAL_RULE carries its rule and the dropped constraints. */
export function flagText(flag: string, conditional?: CutoverRule | null, constraints?: readonly string[]): string {
  switch (flag) {
    case "ALREADY_DROPPED_TODAY": return "Already dropped today: the log records this flow as dropped on the current path.";
    case "CONDITIONAL_RULE": return `Rule ${ruleText(conditional) || "UNKNOWN"} could match only if ${constraints && constraints.length ? constraints.join(", ") : "its unevaluated constraints"} hold; not decided.`;
    case "OLD_PATH_ALLOWED": return "Old path: allowed.";
    case "OLD_PATH_DROPPED": return "Old path: dropped.";
    case "OLD_PATH_NOT_DECIDED": return "Old path: not decided.";
    default: return flag;
  }
}

/** The five result tabs, in the brief's order; each maps to a server query, never to a browser filter. */
export const RESULT_TABS = [
  { slug: "drop", label: "Would drop", kind: "FLOW", decision: "WOULD_DROP" },
  { slug: "open", label: "Not decided", kind: "FLOW", decision: "NOT_DECIDED" },
  { slug: "missing", label: "Missing rules", kind: "MISSING_RULE", decision: null },
  { slug: "allow", label: "Would allow", kind: "FLOW", decision: "WOULD_ALLOW" },
  { slug: "unevaluable", label: "Not evaluable", kind: "FLOW", decision: "NOT_EVALUABLE" },
] as const;
export type ResultTab = typeof RESULT_TABS[number];
export const resultTabOf = (slug: string | null) => RESULT_TABS.find((t) => t.slug === slug) ?? RESULT_TABS[0];
