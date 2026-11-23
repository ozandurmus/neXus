import type {
  CutoverAssessmentView,
  OfflineAnalysisResult,
  PathBranch,
  PathHop,
  PathResult,
  PolicyLookupResult,
} from "../auth/adminApi";
import { coverageText, STALE_REASON, targetSourceLabel } from "../screens/cutover/cutoverWording";
import { verdictWord } from "../screens/pathanalysis/pathWords";
import { NOT_SEEN_WORDING, usageReason } from "../screens/RuleUsageResult";
import {
  severityWord,
  statementWord,
  type ZoneMatrixEvaluationResponse,
} from "../screens/zonematrix/zoneMatrixApi";
import type { BadgeState } from "./estateModel";

/**
 * Pure model of the Estate view's Ask workbench (alternative view, PO 2026-11-19). Ask brings five existing read tools
 * under one input; each is answered by the API the standard screen already calls. Nothing here computes a verdict: it
 * only maps the server's answer to a badge state, a headline and the evidence rows that follow it.
 * The one rule it enforces is the product's: an answer that is UNKNOWN (or an input the tool cannot take) is shown as
 * "cannot be proven today", never as a green or a red.
 */
export const ASK_MODES = ["path", "lookup", "usage", "cutover", "zones"] as const;
export type AskMode = (typeof ASK_MODES)[number];

export const ASK_EVIDENCE_ROW_CAP = 25;

const ASK_STANDARD_HREF: Readonly<Record<AskMode, { readonly href: string; readonly label: string }>> = {
  path: { href: "?screen=trace&mode=path&standard=1", label: "Open the standard Trace screen →" },
  lookup: { href: "?screen=trace&mode=lookup&standard=1", label: "Open the standard Trace screen →" },
  usage: { href: "?screen=rule_usage&standard=1", label: "Open the standard Rule object usage screen →" },
  cutover: { href: "?screen=cutover&standard=1", label: "Open the standard Cutover impact screen →" },
  zones: { href: "?screen=zone_matrix&standard=1", label: "Open the standard Zone matrix screen →" },
};

export function askStandardHref(mode: AskMode): { readonly href: string; readonly label: string } {
  return ASK_STANDARD_HREF[mode];
}

export function isAskMode(value: string | null | undefined): value is AskMode {
  return Boolean(value && (ASK_MODES as readonly string[]).includes(value));
}

export function parseAskMode(value: string | null | undefined): AskMode {
  return isAskMode(value) ? value : "path";
}

export const ASK_MODE_META: Readonly<Record<AskMode, { readonly question: string; readonly hint: string }>> = {
  path: { question: "Can A reach B?", hint: "path hop by hop: route, NAT, deciding rule" },
  lookup: { question: "Which rule decides?", hint: "one flow against one stored policy" },
  usage: { question: "Are this rule’s objects used?", hint: "a stored log-export analysis of one rule" },
  cutover: { question: "What breaks at cutover?", hint: "a segment’s stored cutover assessment" },
  zones: { question: "Zone matrix", hint: "which zones may talk to which, per policy" },
};

export const CANNOT_PROVE = "Cannot be proven today";

export interface FlowQuestion {
  readonly src: string;
  readonly dst: string;
  readonly protocol: "tcp" | "udp";
  readonly port: number;
}

const IPV4 = /^(?:\d{1,3}\.){3}\d{1,3}$/;

function ipv4Ok(value: string): boolean {
  return IPV4.test(value) && value.split(".").every((octet) => Number(octet) <= 255);
}

/**
 * Reads one typed question such as `192.0.2.10 -> 198.51.100.40 tcp/8443` (also "to", "→", a space, `tcp 8443`,
 * `8443/udp`). Returns the missing parts when the text does not hold a full five-tuple; the input is never guessed.
 */
export function parseFlowQuestion(text: string): { readonly flow: FlowQuestion | null; readonly missing: readonly string[] } {
  const tokens = text
    .replace(/[→,]|->|=>/g, " ")
    .split(/\s+/)
    .map((t) => t.trim())
    .filter((t) => t && !/^(to|from|can|reach|on)$/i.test(t));
  const hosts = tokens.filter((t) => ipv4Ok(t));
  let protocol: "tcp" | "udp" | null = null;
  let port: number | null = null;
  for (const token of tokens) {
    const pair = /^(tcp|udp)[/:](\d{1,5})$/i.exec(token) ?? /^(\d{1,5})[/:](tcp|udp)$/i.exec(token);
    if (pair) {
      const [a, b] = [pair[1] ?? "", pair[2] ?? ""];
      protocol = (/^\d/.test(a) ? b : a).toLowerCase() as "tcp" | "udp";
      port = Number(/^\d/.test(a) ? a : b);
    } else if (/^(tcp|udp)$/i.test(token)) {
      protocol = token.toLowerCase() as "tcp" | "udp";
    } else if (/^\d{1,5}$/.test(token) && !ipv4Ok(token)) {
      port = Number(token);
    }
  }
  const missing: string[] = [];
  if (!hosts[0]) missing.push("a source address");
  if (!hosts[1]) missing.push("a destination address");
  if (!protocol) missing.push("tcp or udp");
  if (port === null || port < 1 || port > 65535) missing.push("a port (1–65535)");
  if (missing.length || !protocol || port === null) return { flow: null, missing };
  return { flow: { src: hosts[0] as string, dst: hosts[1] as string, protocol, port }, missing };
}

/** Policy lookup takes the service as one `tcp/443` token. */
export function flowService(flow: FlowQuestion): string {
  return `${flow.protocol}/${flow.port}`;
}

export interface AskEvidenceRow {
  readonly key: string;
  readonly title: string;
  readonly state: BadgeState;
  readonly stateWord: string;
  readonly lines: readonly string[];
  /** Evidence time of this row (stored policy collection), when the answer carries one. */
  readonly at?: string | null;
  readonly source?: string;
}

export interface AskAnswer {
  readonly mode: AskMode;
  readonly state: BadgeState;
  readonly verdictWord: string;
  readonly headline: string;
  readonly detail: string | null;
  /** True when the answer is UNKNOWN / not evaluable: the screen then says "cannot be proven today". */
  readonly unprovable: boolean;
  readonly rows: readonly AskEvidenceRow[];
  readonly evidenceAt: string | null;
  readonly limitations: string | null;
}

function flowBadge(verdict: string | null | undefined): BadgeState {
  switch (verdict) {
    case "ALLOWED": return "ok";
    case "DENIED": return "act";
    case "PARTIAL":
    case "ASYMMETRIC":
    case "ALLOWED_TO_EDGE":
    case "CONDITIONAL": return "watch";
    default: return "unk";
  }
}

function ruleLine(rule: { rule_number?: number | null; rule_name?: string | null; section_name?: string | null; action?: string | null } | null | undefined): string | null {
  if (!rule) return null;
  const num = rule.rule_number === null || rule.rule_number === undefined ? "" : `#${rule.rule_number} `;
  return `Rule ${num}${rule.rule_name || "unnamed"}${rule.section_name ? ` · ${rule.section_name}` : ""}${rule.action ? ` · ${rule.action}` : ""}`;
}

export function pathHopRow(hop: PathHop): AskEvidenceRow {
  const lines: string[] = [];
  if (hop.ingress?.interface_name) lines.push(`Ingress ${hop.ingress.interface_name}${hop.ingress.zone ? ` · ${hop.ingress.zone}` : ""}`);
  if (hop.route) lines.push(`Route ${hop.route.destination}${hop.route.next_hop ? ` via ${hop.route.next_hop}` : ""}${hop.route.interface_name ? `, ${hop.route.interface_name}` : ""}`);
  else lines.push("Route UNKNOWN · no route evidence for this hop");
  if (hop.nat) lines.push(hop.nat.applied ? `NAT applied \u00b7 ${hop.nat.nat_reason}` : `NAT ${hop.nat.nat_status} \u00b7 ${hop.nat.nat_reason}`);
  const rule = ruleLine(hop.security?.deciding_rule);
  if (rule) lines.push(rule);
  else if (hop.security) lines.push(`Rule ${hop.security.security_verdict === "UNKNOWN" ? "UNKNOWN · the stored policy does not decide" : hop.security.security_verdict}`);
  if (hop.policy_stale) lines.push("Stored policy is stale for this unit");
  for (const note of hop.reasons.slice(0, 2)) lines.push(note);
  return {
    key: `hop-${hop.hop_index}`,
    title: `${hop.node_id} · ${hop.vendor}${hop.context ? ` · ${hop.context}` : ""}`,
    state: flowBadge(hop.verdict),
    stateWord: verdictWord(hop.verdict),
    lines,
    at: hop.policy_collected_at,
    source: "stored policy",
  };
}

function primaryBranch(result: PathResult): PathBranch | null {
  return result.branches.find((b) => b.verdict === result.verdict) ?? result.branches[0] ?? null;
}

export function buildPathAnswer(result: PathResult): AskAnswer {
  const branch = primaryBranch(result);
  const unknownHops = result.executive_summary.unknown_hops.length;
  const state = flowBadge(result.verdict);
  const hops = branch?.hops ?? [];
  const detailParts = [
    `${hops.length} unit${hops.length === 1 ? "" : "s"} on the path`,
    unknownHops ? `${unknownHops} hop${unknownHops === 1 ? "" : "s"} partly UNKNOWN` : null,
    result.asymmetric ? "return path is asymmetric" : null,
    result.branches.length > 1 ? `${result.branches.length} equal-cost branches` : null,
  ].filter((p): p is string => Boolean(p));
  const hopTimes = hops.map((h) => h.policy_collected_at).filter((t): t is string => Boolean(t)).sort();
  return {
    mode: "path",
    state,
    verdictWord: `${verdictWord(result.verdict)} by intent`,
    headline: result.executive_summary.headline || result.verdict_reason,
    detail: detailParts.join(" · "),
    unprovable: state === "unk",
    rows: hops.map(pathHopRow),
    evidenceAt: hopTimes[0] ?? result.computed_at,
    limitations: result.limitations || null,
  };
}

export function buildLookupAnswer(result: PolicyLookupResult): AskAnswer {
  const state = flowBadge(result.verdict);
  const rows: AskEvidenceRow[] = [];
  const decide = result.decidingRule;
  if (decide) {
    rows.push({
      key: "deciding",
      title: result.verdict === "UNKNOWN" ? "First proven match after uncertainty (not a verdict)" : "Deciding rule",
      state,
      stateWord: decide.action,
      lines: [`#${decide.number} ${decide.name || "unnamed"}`, `Section ${decide.sectionName || "UNKNOWN"}`],
      at: result.collectedAt,
      source: "stored policy",
    });
  }
  for (const rule of result.undecidableRules) {
    rows.push({
      key: `undecidable-${rule.ruleId}`,
      title: `Rule ${rule.number} could match`,
      state: "unk",
      stateWord: "UNKNOWN",
      lines: [rule.name || "unnamed", rule.lookupReason ?? "could not be decided"],
      at: result.collectedAt,
      source: "stored policy",
    });
  }
  return {
    mode: "lookup",
    state,
    verdictWord: `${verdictWord(result.verdict)} by intent`,
    headline: result.lookupReason,
    detail: decide ? `Decided by rule #${decide.number}` : "No deciding rule was proven",
    unprovable: state === "unk",
    rows,
    evidenceAt: result.collectedAt ?? null,
    limitations: result.limitations || null,
  };
}

export function buildUsageAnswer(analysis: OfflineAnalysisResult): AskAnswer {
  const rule = analysis.rules[0];
  const objects = rule?.objectUsage ?? [];
  const unused = objects.filter((o) => o.state === "NOT_SEEN");
  const undecided = objects.filter((o) => o.state === "UNKNOWN" || o.state === "NOT_AUDITABLE");
  const state: BadgeState = !rule ? "unk" : undecided.length > 0 && unused.length === 0 ? "unk" : unused.length > 0 ? "watch" : "ok";
  const headline = !rule
    ? "The stored analysis holds no rule to answer for."
    : unused.length
      ? `${unused.length} of ${objects.length} objects carried no traffic in the period.`
      : undecided.length
        ? `${undecided.length} of ${objects.length} objects cannot be checked from this export.`
        : `All ${objects.length} objects carried traffic in the period.`;
  const limitations = [analysis.limitations, unused.length > 0 ? NOT_SEEN_WORDING : null].filter(Boolean).join(" ") || null;
  return {
    mode: "usage",
    state,
    verdictWord: state === "ok" ? "All used" : state === "watch" ? "Unused objects" : "UNKNOWN",
    headline,
    detail: `${analysis.flowCount.toLocaleString("en-US")} flows read · ${analysis.invalidRows.toLocaleString("en-US")} invalid rows · ${analysis.periodStart} → ${analysis.periodEnd}`,
    unprovable: state === "unk",
    rows: objects.map((o, i) => {
      const isJudged = o.state === "USED" || o.state === "NOT_SEEN";
      return {
        key: `obj-${i}`,
        title: `${o.dimension} · ${o.name}`,
        state: o.state === "USED" ? "ok" : o.state === "NOT_SEEN" ? "watch" : "unk",
        stateWord:
          o.state === "USED"
            ? "Used"
            : o.state === "NOT_SEEN"
              ? "Not seen"
              : o.state === "NOT_AUDITABLE"
                ? "Not auditable"
                : "UNKNOWN",
        lines: isJudged
          ? [`${o.flowCount.toLocaleString("en-US")} flows`, ...(o.reason ? [o.reason] : [])]
          : [usageReason(o)],
        source: "uploaded log export",
      };
    }),
    evidenceAt: analysis.collectedAt ?? analysis.createdAt ?? null,
    limitations,
  };
}

export function buildCutoverAnswer(view: CutoverAssessmentView): AskAnswer {
  const state: BadgeState =
    view.readiness === "BLOCKERS_FOUND" ? "act"
      : view.readiness === "NO_BLOCKERS_IN_EVALUATED_FLOWS" && view.status === "COMPLETE" ? "ok"
        : view.readiness === "NO_BLOCKERS_IN_EVALUATED_FLOWS" ? "watch" : "unk";
  const counts = view.counts ?? {};
  const coverage = view.coverage ?? [];
  const rows: AskEvidenceRow[] =
    coverage.length === 0
      ? [
          {
            key: "cov-none",
            title: "NONE",
            state: "unk",
            stateWord: "Incomplete",
            lines: ["The server returned no coverage list: read this verdict as incomplete."],
            source: "assessment coverage",
          },
        ]
      : coverage.map((c, i) => ({
          key: `cov-${i}`,
          title: c.code,
          state: "unk",
          stateWord: "Coverage",
          lines: [coverageText(c)],
          source: "assessment coverage",
        }));
  const staleText = view.stale
    ? (view.staleReasons ?? []).map((r) => STALE_REASON[r] ?? r).join("; ") || "assessment is stale"
    : null;
  return {
    mode: "cutover",
    state,
    verdictWord: state === "act" ? "Blockers found" : state === "ok" ? "No blockers" : state === "watch" ? "No blockers, incomplete" : "UNKNOWN",
    headline: view.headline || `Readiness ${view.readiness}`,
    detail: [
      counts.tuples !== undefined ? `${counts.tuples} distinct flows` : null,
      counts.flows !== undefined ? `${counts.flows} logged connections` : null,
      counts.WOULD_DROP !== undefined ? `${counts.WOULD_DROP} would drop` : null,
      counts.NOT_DECIDED !== undefined ? `${counts.NOT_DECIDED} not decided` : null,
      counts.NOT_EVALUABLE !== undefined ? `${counts.NOT_EVALUABLE} not evaluable` : null,
      counts.WOULD_ALLOW !== undefined ? `${counts.WOULD_ALLOW} would allow` : null,
      counts.alreadyDroppedTuples !== undefined ? `${counts.alreadyDroppedTuples} already dropped today` : null,
      counts.missingRuleGroups !== undefined ? `${counts.missingRuleGroups} missing-rule groups` : null,
      view.targetSource ? targetSourceLabel(view.targetSource) : null,
      staleText,
    ].filter(Boolean).join(" · ") || null,
    unprovable: state === "unk",
    rows,
    evidenceAt: view.targetCollectedAt ?? view.createdAt ?? null,
    limitations: null,
  };
}

export function buildZonesAnswer(evaluation: ZoneMatrixEvaluationResponse): AskAnswer {
  const oc = evaluation.outcome_counts ?? {};
  const violations = oc.VIOLATION ?? 0;
  const compliant = oc.COMPLIANT ?? 0;
  const h = evaluation.headline;
  const notEvaluable = oc.NOT_EVALUABLE ?? h.not_evaluable_rules ?? 0;
  const unknown = oc.UNKNOWN ?? h.unknown_rules ?? 0;
  const state: BadgeState = !evaluation.evaluated ? "unk"
    : violations > 0 ? "act"
      : evaluation.coverage === "COMPLETE" ? "ok" : "watch";
  const ruleRows: AskEvidenceRow[] = (evaluation.rules ?? [])
    .filter((r) => r.outcome !== "COMPLIANT")
    .map((r, i) => {
      const header = [r.section_name, r.highest_severity ? severityWord(r.highest_severity) : null]
        .filter(Boolean)
        .join(" · ");
      const cellLines = (r.violated_cells ?? []).map(
        (c) => `${c.source_name} -> ${c.destination_name} (${c.nat_view}): ${statementWord(c.statement)}`,
      );
      const lines: string[] = [
        ...(header ? [header] : []),
        ...cellLines,
        ...(r.reason ? [r.reason] : []),
        ...((r.undecided_parts ?? []).length > 0 ? [r.undecided_parts.join("; ")] : []),
      ];
      return {
        key: `rule-${r.rule_id || r.rule_uid || i}`,
        title: `Rule #${r.rule_number} ${r.rule_name || r.rule_uid}`,
        state: r.outcome === "VIOLATION" ? ("act" as const) : ("unk" as const),
        stateWord:
          r.outcome === "VIOLATION"
            ? "Violation"
            : r.outcome === "NOT_EVALUABLE"
              ? "Not evaluable"
              : "Outcome unknown",
        lines,
        source: "zone matrix",
      };
    });
  const gapRows: AskEvidenceRow[] = (evaluation.zone_definition_gaps ?? []).map((g, i) => ({
    key: `gap-${g.rule_id || i}`,
    title: `Rule #${g.rule_number} ${g.rule_name}`,
    state: "unk" as const,
    stateWord: "Zone gap",
    lines: [`${g.section_name} · ${g.range_count} unassigned private ${g.range_count === 1 ? "range" : "ranges"}`],
    source: "zone matrix",
  }));
  const coverageRows: AskEvidenceRow[] = (evaluation.coverage_items ?? []).map((c) => ({
    key: `cov-${c.code}`,
    title: c.code,
    state: "unk" as const,
    stateWord: "Coverage",
    lines: [c.description],
    source: "zone matrix",
  }));
  const detailParts = [
    `Matrix v${evaluation.version_number}`,
    `${violations} violation${violations === 1 ? "" : "s"}`,
    `${compliant} compliant`,
    `${notEvaluable} not evaluable`,
    `${unknown} UNKNOWN`,
    evaluation.not_applicable?.total !== undefined ? `${evaluation.not_applicable.total} not applicable` : null,
  ].filter((p): p is string => Boolean(p));
  return {
    mode: "zones",
    state,
    verdictWord: !evaluation.evaluated ? "Not evaluated" : violations > 0 ? "Violations" : evaluation.coverage === "COMPLETE" ? "Compliant" : "No violations, incomplete",
    headline: !evaluation.evaluated
      ? evaluation.not_evaluated_reason ?? "This policy could not be evaluated against the zone matrix."
      : `${h.distinct_violating_rules} rule${h.distinct_violating_rules === 1 ? "" : "s"} violate the matrix across ${h.violated_zone_pairs} zone pair${h.violated_zone_pairs === 1 ? "" : "s"}.`,
    detail: detailParts.join(" · "),
    unprovable: state === "unk",
    rows: [...ruleRows, ...gapRows, ...coverageRows],
    evidenceAt: evaluation.collected_at,
    limitations: null,
  };
}
