import { call } from "../../auth/adminApi";

/**
 * The Overview's read of the stored zone matrix summary (docs/design/ZONE_MATRIX_CONTRACT.md section 7.2, delivery step 5).
 *
 * The source of truth for the body is `ZoneMatrixSummaryView` in ui2/service (`zonematrix` package): this file types only
 * the fields the attention row uses and checks them at runtime, so a body that lacks one becomes an UNKNOWN row instead of
 * a crash or a zero. The headline sentence, its "at least N" form and its `exact` flag are the server's; nothing here
 * recomputes them. Another module (`screens/zonematrix/zoneMatrixApi.ts`) carries the typed client of the Zone matrix
 * screen; this one is deliberately separate.
 */

/** The part of `headline` the row reads (counts are whole numbers of what the field names). */
export interface ZoneMatrixAttentionHeadline {
  readonly version_number: number;
  readonly distinct_violating_rules: number;
  readonly violated_zone_pairs: number;
  readonly critical_rules: number;
  readonly outside_headline_ordered_layer: number;
  readonly not_evaluable_findings: number;
  readonly unknown_findings: number;
  readonly became_undecided_findings: number;
  readonly stale_policies: number;
  readonly incomplete_policies: number;
  readonly older_version_policies: number;
  readonly not_evaluated_policies: number;
  readonly resolved_by_version_change_last_7_days: number;
  /** `true` only when every covered policy is CURRENT and COMPLETE and nothing is undecided: the count is then not a lower bound. */
  readonly exact: boolean;
  /** The server's headline sentence ("N rules violate the zone matrix (vK) across ..." or "no proven violations in evaluated rules"). */
  readonly text: string;
  readonly outside_headline_text: string | null;
}

export interface ZoneMatrixAttentionPolicy {
  /** `CURRENT` or `STALE`; anything else is read as not current. */
  readonly staleness_verdict: string;
  readonly collected_at: string | null;
}

export interface ZoneMatrixAttentionThreshold {
  readonly seconds: number;
  readonly hours: number | null;
  readonly interim: boolean;
}

export interface ZoneMatrixAttentionSummary {
  /** `NO_ACTIVE_VERSION`, `NO_EVALUABLE_POLICY`, `NO_COMPLETE_RUN` or `READY`. */
  readonly state: string;
  readonly state_text: string | null;
  /** `null` exactly when there is no active version. */
  readonly headline: ZoneMatrixAttentionHeadline | null;
  readonly staleness_threshold: ZoneMatrixAttentionThreshold | null;
  readonly policies: readonly ZoneMatrixAttentionPolicy[];
}

const HEADLINE_COUNTS = [
  "version_number", "distinct_violating_rules", "violated_zone_pairs", "critical_rules", "outside_headline_ordered_layer",
  "not_evaluable_findings", "unknown_findings", "became_undecided_findings", "stale_policies", "incomplete_policies",
  "older_version_policies", "not_evaluated_policies", "resolved_by_version_change_last_7_days",
] as const;

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const isCount = (v: unknown): v is number => typeof v === "number" && Number.isInteger(v) && v >= 0;

/**
 * Reads a summary body into what the row needs, or `null` when the body is not usable: no state, a state with active
 * version but no headline, a count that is missing or not a whole number, a missing `exact` flag or headline text.
 * `null` means UNKNOWN to the caller; a field is never defaulted to zero.
 */
export function parseZoneMatrixAttention(body: unknown): ZoneMatrixAttentionSummary | null {
  if (!isObject(body) || typeof body.state !== "string" || !Array.isArray(body.policies)) return null;
  const policies: ZoneMatrixAttentionPolicy[] = [];
  for (const p of body.policies) {
    if (!isObject(p) || !isObject(p.staleness) || typeof p.staleness.verdict !== "string") return null;
    policies.push({ staleness_verdict: p.staleness.verdict, collected_at: typeof p.collected_at === "string" ? p.collected_at : null });
  }
  const t = body.staleness_threshold;
  const threshold: ZoneMatrixAttentionThreshold | null = isObject(t) && typeof t.seconds === "number" && Number.isFinite(t.seconds)
    ? { seconds: t.seconds, hours: typeof t.hours === "number" && Number.isFinite(t.hours) ? t.hours : null, interim: t.interim === true }
    : null;
  const stateText = typeof body.state_text === "string" ? body.state_text : null;
  const h = body.headline;
  if (body.state === "NO_ACTIVE_VERSION") {
    // No active version: no headline and no row (section 7.2). A headline sent anyway is not trusted for this state.
    return h === null || h === undefined ? { state: body.state, state_text: stateText, headline: null, staleness_threshold: threshold, policies } : null;
  }
  if (!isObject(h) || typeof h.exact !== "boolean" || typeof h.text !== "string" || h.text.trim() === "") return null;
  const counts: Record<string, number> = {};
  for (const k of HEADLINE_COUNTS) {
    const v = h[k];
    if (!isCount(v)) return null;
    counts[k] = v;
  }
  const headline = { ...counts, exact: h.exact, text: h.text,
    outside_headline_text: typeof h.outside_headline_text === "string" && h.outside_headline_text.trim() !== "" ? h.outside_headline_text : null,
  } as unknown as ZoneMatrixAttentionHeadline;
  return { state: body.state, state_text: stateText, headline, staleness_threshold: threshold, policies };
}

/**
 * `GET /api/v2/zone-matrix/summary?limit=1`: the row needs the headline and the policy coverage, not the findings (the route
 * refuses a limit below 1, so 1 is the smallest). Callers issue this only for a session that holds the Policy plane.
 */
export const getZoneMatrixSummaryForOverview = () => call<unknown>("/api/v2/zone-matrix/summary?limit=1", "GET");
