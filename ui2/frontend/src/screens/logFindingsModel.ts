import type { LogCoverageSummary, LogFinding, LogFindingChipData, LogFindingChipsAnswer, LogFindingSeverity } from "../auth/adminApi";
import type { Tone } from "../shell/tone";

/**
 * The reading of the log detection engine's answers (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 7.4 and 9).
 * Pure functions, no fetch: every sentence says what it counts, UNKNOWN is never worded as healthy, and a finding is
 * shown with its coverage and its history label.
 */

/** The state colour of a finding severity; INFO is quiet, UNKNOWN is the neutral unknown tone. */
export function severityTone(severity: LogFindingSeverity): Tone {
  switch (severity) {
    case "CRITICAL": return "bad";
    case "ATTENTION": return "warn";
    case "INFO": return "neutral";
    default: return "unknown";
  }
}

const COVERAGE_WORDS: Record<string, string> = {
  EVALUATED: "evaluated",
  BASELINE_LEARNING: "evaluated, baseline still learning (7 to 13 days)",
  NOT_EVALUABLE: "evaluated by the hardware rules only (under 7 days of history)",
  GAP: "UNKNOWN: a collection gap overlaps the window",
  NOT_COLLECTED: "UNKNOWN: the window was not collected",
  MASKING_UNAVAILABLE: "UNKNOWN: masking was unavailable, nothing was processed",
  UNPARSED_HIGH: "UNKNOWN: too many lines could not be read",
  EVALUATION_FAILED: "UNKNOWN: evaluation failed on this window",
  UNSUPPORTED: "UNKNOWN: this source is not analysed yet",
};

export function coverageWord(state: string): string {
  return COVERAGE_WORDS[state] ?? `UNKNOWN: ${state}`;
}

/** True for the states in which the window was evaluated; the others are UNKNOWN to the reader. */
export function coverageEvaluated(state: string): boolean {
  return state === "EVALUATED" || state === "BASELINE_LEARNING" || state === "NOT_EVALUABLE";
}

/** "3 of 4 recorded windows evaluated"; never a bare "OK". */
export function coverageLine(c: LogCoverageSummary): string {
  if (c.windows_total === 0) return "UNKNOWN: no window has been evaluated yet";
  const unknown = c.windows_total - c.windows_evaluated;
  return unknown === 0
    ? `${c.windows_evaluated} of ${c.windows_total} recorded windows evaluated`
    : `${c.windows_evaluated} of ${c.windows_total} recorded windows evaluated; ${unknown} UNKNOWN`;
}

/** The chip's label: the worst open severity with its count, or the coverage word when there is nothing to count. */
export function chipLabel(chip: LogFindingChipData): string {
  if (chip.state === "FINDINGS" && chip.severity) return `${chip.severity} ${chip.count}`;
  return chip.state === "NO_FINDINGS" ? "NO FINDINGS" : "UNKNOWN";
}

export function chipTone(chip: LogFindingChipData): Tone {
  if (chip.state === "FINDINGS" && chip.severity) return severityTone(chip.severity);
  // A device without findings is green only when every recorded window was evaluated (the server decided that).
  return chip.state === "NO_FINDINGS" ? "ok" : "unknown";
}

const LAYER_B_KIND_WORDS: Record<string, string> = {
  NEW_ON_DEVICE: "first seen on this device since learning began",
  FLEET_RARE: "rare across the cohort (at most 5% of cohort devices)",
  RATE_SPIKE: "hourly rate spike above the device's robust baseline",
  SILENCE: "periodic template absent over the observed day",
  BOOT_BURST: "post-boot burst above cohort profile",
};

export function layerBKindWord(kind: string): string {
  return LAYER_B_KIND_WORDS[kind] ?? kind;
}

/** Layer B kinds on a finding, in evaluation order. */
export function layerBKinds(f: LogFinding): readonly string[] {
  if (!f.layer_b && f.layer !== "B") return [];
  return f.layer_b?.kinds && f.layer_b.kinds.length > 0 ? f.layer_b.kinds : [f.kind];
}

/**
 * Layer A: "hw.edac.ce on CPU_SrcID#1_MC#0_Chan#2_DIMM#0" (marker and, when present, its evidence key).
 * Layer B: "FLEET_RARE · RATE_SPIKE (t-0123...)" (triggered statistical kinds with the opaque template id).
 */
export function findingTitle(f: LogFinding): string {
  if (f.layer === "B" || f.layer_b) {
    const kinds = layerBKinds(f).join(" · ");
    const tpl = f.layer_b?.template_id || f.key_token;
    return tpl ? `${kinds} (${tpl})` : kinds;
  }
  return f.key_token ? `${f.marker_id} on ${f.key_token}` : f.marker_id;
}

/** "45 lines on 2026-11-24, 00:30 to 04:54 (device-local)". */
export function findingCounts(f: LogFinding): string {
  const time = (t: string) => t.slice(11, 16);
  return `${f.counts.lines} lines on ${f.window_day}, ${time(f.window_start)} to ${time(f.window_end)} (device-local)`;
}

/**
 * Ordering score line: shows `100 x S x R x C` with S, R and C next to it for Layer B (section 4.4), or S alone for Layer A.
 */
export function scoreLine(f: LogFinding): string {
  if (f.score.score !== null && f.score.r !== null && f.score.c !== null) {
    return `score ${f.score.score} (100 x S x R x C · S ${f.score.s} · R ${f.score.r} · C ${f.score.c}; ordering only, severity by rule) · provenance: ${f.provenance.toLowerCase()} · coverage: ${coverageWord(f.coverage_state)}`;
  }
  return `S ${f.score.s} (severity weight, ${f.provenance.toLowerCase()}) · rarity and change arrive with layer B · coverage: ${coverageWord(f.coverage_state)}`;
}

/** Learning is claimed only when the evaluator labelled it or the usable baseline days sit in the 7 to 13 day window. */
function baselineLearning(f: LogFinding): boolean {
  return f.labels.includes("BASELINE_LEARNING") || (f.counts.baseline_days >= 7 && f.counts.baseline_days < 14);
}

/**
 * Layer B statistical evidence line: baseline median/MAD, z-score, cohort prevalence and first-seen dates.
 * Uncomputed baselines (7 to 13 learning days) say so explicitly rather than printing 0.
 */
export function layerBStatsLine(f: LogFinding): string | null {
  const b = f.layer_b;
  if (!b) return null;
  const baseline = b.baseline_median !== null && b.baseline_mad !== null
    ? `baseline median ${b.baseline_median}/h, MAD ${b.baseline_mad}${f.counts.peak_bucket_lines > 0 ? ` (peak ${f.counts.peak_bucket_lines}/h)` : ""}`
    : baselineLearning(f)
      ? `baseline learning (${f.counts.baseline_days} usable days)`
      : `baseline not evaluable (UNKNOWN; ${f.counts.baseline_days} usable baseline days)`;
  const zPart = b.z !== null ? `z ${b.z}` : "z not evaluable";
  const parts = [baseline, zPart, `prevalence: ${b.prevalence}`];
  if (b.device_first_seen || b.fleet_first_seen) {
    parts.push(`first seen: device ${b.device_first_seen ?? (b.kinds.includes("NEW_ON_DEVICE") ? "new" : "UNKNOWN")}, fleet ${b.fleet_first_seen ?? "UNKNOWN"}`);
  }
  return parts.join(" · ");
}

const LABEL_WORDS: Record<string, string> = {
  HISTORY_UNDER_7_DAYS: "history under 7 days",
  BASELINE_LEARNING: "baseline learning (7 to 13 days)",
  CHRONIC: "chronic on this device",
  FLEET_BACKGROUND: "fleet background",
  PROVISIONAL_CAP: "capped to ATTENTION: meaning not yet cited",
  PROVISIONAL_TEMPLATE: "provisional template (not yet in frozen catalog)",
  CATALOG_BASELINE: "baseline from catalog",
  CATALOG_PREVALENCE: "prevalence from catalog",
  HA_ROLE_UNKNOWN: "HA role unknown (pooled baseline)",
  STATISTICAL_FLOOR: "capped to ATTENTION: statistical finding",
  EXPECTED_WINDOW: "expected window",
  UPGRADE_NOVELTY: "upgrade novelty",
};

export function labelWord(label: string): string {
  return LABEL_WORDS[label] ?? label;
}

/** The shadow banner: findings are stored but shown on this admin screen only. */
export function shadowNote(shadow: boolean, until: string | null): string | null {
  if (!shadow) return null;
  return until
    ? `Shadow period until ${until.slice(0, 10)}: findings are stored and shown here only; the devices list does not show them yet.`
    : "Shadow period: findings are stored and shown here only; the devices list does not show them yet.";
}

/** Findings of ATTENTION or above are counted; INFO is listed. */
export function countedFindings(findings: readonly LogFinding[]): readonly LogFinding[] {
  return findings.filter((f) => f.severity === "CRITICAL" || f.severity === "ATTENTION");
}

/**
 * Normalize a `/api/v2/log-findings/chips` response for the Devices list.
 * - Chips are shown ONLY outside the shadow period (`enabled && !shadow`).
 * - When `truncated` is true (the backend hit its 50,000-finding paging bound), per-device counts are partial and any
 *   device emitted as `NO_FINDINGS` may have unread findings past the cutoff, so it is downgraded to `UNKNOWN` (never
 *   green `NO FINDINGS`).
 */
export function normalizeLogChipsAnswer(ans: LogFindingChipsAnswer | null | undefined): {
  readonly chips: ReadonlyMap<string, LogFindingChipData>;
  readonly truncatedNote: string | null;
} {
  if (!ans || ans.enabled !== true || ans.shadow !== false || !Array.isArray(ans.chips)) {
    return { chips: new Map(), truncatedNote: null };
  }
  const truncated = ans.truncated === true;
  const truncatedNote = truncated
    ? (ans.truncated_note?.trim() || "Open findings read limit reached; per-device counts are partial and devices without listed findings are UNKNOWN.")
    : null;
  const entries = ans.chips.map((c): readonly [string, LogFindingChipData] => {
    if (!truncated) return [c.device_id, c];
    if (c.state === "NO_FINDINGS") {
      return [
        c.device_id,
        {
          ...c,
          state: "UNKNOWN",
          severity: null,
          count: 0,
          text: "UNKNOWN: open findings list truncated; unread findings may exist for this device",
        },
      ];
    }
    if (c.state === "FINDINGS") {
      return [
        c.device_id,
        {
          ...c,
          text: `${c.text} (partial — read limit reached)`,
        },
      ];
    }
    return [c.device_id, c];
  });
  return { chips: new Map(entries), truncatedNote };
}

function severityOrder(severity: LogFindingSeverity | null): number {
  switch (severity) {
    case "CRITICAL": return 3;
    case "ATTENTION": return 2;
    case "INFO": return 1;
    default: return 0;
  }
}

/**
 * Aggregate member log-finding chips for a cluster header row (covering both VSX/multi-VSYS clusters where member rows
 * are not rendered and collapsed non-VSX clusters).
 * - Any member with open findings lifts the cluster to `FINDINGS` with the worst severity and summed open finding count.
 * - A cluster is green `NO_FINDINGS` ONLY when every member in the cluster has a chip with `state === "NO_FINDINGS"`.
 * - Partial coverage or any `UNKNOWN` member yields `UNKNOWN`, never green.
 */
export function clusterLogChip(
  members: ReadonlyArray<{ readonly device_id: string; readonly hostname?: string | null }>,
  logChips?: ReadonlyMap<string, LogFindingChipData>,
): LogFindingChipData | undefined {
  if (!logChips || members.length === 0) return undefined;
  const present = members
    .map((member) => ({ member, chip: logChips.get(member.device_id) }))
    .filter((entry): entry is { readonly member: { readonly device_id: string; readonly hostname?: string | null }; readonly chip: LogFindingChipData } =>
      entry.chip !== undefined,
    );
  if (present.length === 0) return undefined;

  const byState: Record<string, number> = {};
  let windowsTotal = 0;
  let windowsEvaluated = 0;
  for (const { chip } of present) {
    windowsTotal += chip.coverage.windows_total;
    windowsEvaluated += chip.coverage.windows_evaluated;
    for (const [k, v] of Object.entries(chip.coverage.by_state ?? {})) {
      byState[k] = (byState[k] ?? 0) + v;
    }
  }
  const coverage: LogCoverageSummary = {
    windows_total: windowsTotal,
    windows_evaluated: windowsEvaluated,
    by_state: byState,
    counts: present[0].chip.coverage.counts,
  };

  const withFindings = present.filter((e) => e.chip.state === "FINDINGS" && e.chip.severity !== null);
  if (withFindings.length > 0) {
    const worst = withFindings.reduce((best, cur) => {
      const bestRank = severityOrder(best.chip.severity);
      const curRank = severityOrder(cur.chip.severity);
      if (curRank !== bestRank) return curRank > bestRank ? cur : best;
      return cur.chip.count > best.chip.count ? cur : best;
    });
    const totalCount = withFindings.reduce((sum, e) => sum + e.chip.count, 0);
    const worstName = worst.member.hostname ?? worst.member.device_id;
    const text = withFindings.length === 1
      ? `${worstName}: ${worst.chip.text}`
      : `${totalCount} open findings across ${withFindings.length} of ${members.length} members; worst ${worstName}: ${worst.chip.text}`;
    return {
      device_id: worst.member.device_id,
      state: "FINDINGS",
      severity: worst.chip.severity,
      count: totalCount,
      text,
      coverage,
    };
  }

  const allNoFindings = present.length === members.length && present.every((e) => e.chip.state === "NO_FINDINGS");
  if (allNoFindings) {
    return {
      device_id: members[0].device_id,
      state: "NO_FINDINGS",
      severity: null,
      count: 0,
      text: `No findings across ${members.length} ${members.length === 1 ? "member" : "members"}`,
      coverage,
    };
  }

  const missing = members.length - present.length;
  const text = missing > 0
    ? `No findings on ${present.length} of ${members.length} members; ${missing} ${missing === 1 ? "member has" : "members have"} no log coverage`
    : `No findings in evaluated windows across ${members.length} members; at least one member is UNKNOWN`;
  return {
    device_id: members[0].device_id,
    state: "UNKNOWN",
    severity: null,
    count: 0,
    text,
    coverage,
  };
}
