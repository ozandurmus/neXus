import type { PolicyHygieneSummary, PolicyHygieneSummaryRow, PolicyMetadata, PolicyRule } from "../auth/adminApi";

/** Rows shown before the list says "+N more". The browser never renders an unbounded list. */
export const POLICY_BROWSER_RULE_CAP = 50;
export const POLICY_BROWSER_OBJECT_CAP = 50;
export const POLICY_BROWSER_CONTAINER_CAP = 25;

export interface CappedList<T> {
  readonly shown: readonly T[];
  readonly hidden: number;
  /** "+N more" when anything was cut, otherwise null. */
  readonly moreLabel: string | null;
}

/** Keep the first `cap` items and say how many were left out. Counts stay honest: `total` is what the source reported. */
export function capList<T>(items: readonly T[], cap: number): CappedList<T> {
  const safeCap = Number.isFinite(cap) && cap >= 0 ? Math.floor(cap) : 0;
  const shown = items.slice(0, safeCap);
  const hidden = Math.max(0, items.length - shown.length);
  return { shown, hidden, moreLabel: hidden > 0 ? `+${hidden} more` : null };
}

/** The unit picker: one entry per stored policy container, labelled with what the operator will see in the standard view. */
export interface PolicyUnitOption {
  readonly id: string;
  readonly label: string;
  readonly ruleCount: number | null;
  readonly collectedAt: string;
}

export function policyUnitOptions(policies: readonly PolicyMetadata[]): PolicyUnitOption[] {
  return policies
    .map((p) => ({
      id: p.id,
      label: `${p.name} · ${p.containerName} · ${p.sourceName}`,
      ruleCount: typeof p.ruleCount === "number" ? p.ruleCount : null,
      collectedAt: p.collectedAt,
    }))
    .sort((a, b) => a.label.localeCompare(b.label));
}

/** Rules of one loaded policy page, flattened across sections, in their stored order. */
export function flattenRules(sections: readonly { readonly rules: readonly PolicyRule[] }[]): PolicyRule[] {
  return sections.flatMap((s) => (Array.isArray(s.rules) ? s.rules : []));
}

/** Local, case-insensitive match on a rule's number, name, action and comment (used for the shown page only). */
export function ruleMatches(rule: PolicyRule, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [String(rule.number), rule.name, rule.action, rule.comment].some((v) => (v ?? "").toLowerCase().includes(q));
}

export type HygieneRowView =
  | { readonly kind: "ready"; readonly key: string; readonly label: string; readonly rules: number; readonly counts: readonly { readonly finding: string; readonly rules: number }[]; readonly snapshotAt: string }
  | { readonly kind: "pending"; readonly key: string; readonly label: string; readonly snapshotAt: string };

export interface HygieneSummaryView {
  /** "UNKNOWN" when the summary could not be read at all; never shown as zero. */
  readonly state: "ok" | "unknown";
  readonly rows: readonly HygieneRowView[];
  readonly hiddenRows: number;
  readonly moreLabel: string | null;
  /** Counts are summed over READY containers only; PENDING containers are excluded and named in `pendingLine`. */
  readonly readyContainers: number;
  readonly pendingContainers: number;
  readonly totals: readonly { readonly finding: string; readonly rules: number }[];
  readonly pendingLine: string | null;
  readonly countsLine: string;
}

function rowLabel(r: PolicyHygieneSummaryRow): string {
  return `${r.containerName} · ${r.sourceName} (${r.vendor})`;
}

/**
 * Turn the fleet hygiene summary into view rows. A PENDING container has no counts, so it renders as PENDING and never as
 * zeros; totals come from READY containers only. An unreadable summary (null/undefined/non-array) is UNKNOWN, not OK.
 */
export function hygieneSummaryView(summary: PolicyHygieneSummary | null | undefined, cap = POLICY_BROWSER_CONTAINER_CAP): HygieneSummaryView {
  if (!summary || !Array.isArray(summary.containers)) {
    return { state: "unknown", rows: [], hiddenRows: 0, moreLabel: null, readyContainers: 0, pendingContainers: 0, totals: [], pendingLine: null,
      countsLine: "Hygiene summary UNKNOWN: the fleet summary could not be read." };
  }
  const all: HygieneRowView[] = summary.containers.map((r): HygieneRowView => {
    const key = `${r.sourceId}:${r.containerId}`;
    if (r.state !== "READY" || typeof r.rules !== "number") {
      return { kind: "pending", key, label: rowLabel(r), snapshotAt: r.snapshotAt };
    }
    return {
      kind: "ready",
      key,
      label: rowLabel(r),
      rules: r.rules,
      counts: Object.entries(r.counts ?? {}).map(([finding, rules]) => ({ finding, rules })),
      snapshotAt: r.snapshotAt,
    };
  });
  const ready = all.filter((r): r is Extract<HygieneRowView, { kind: "ready" }> => r.kind === "ready");
  const pending = all.length - ready.length;
  const sums = new Map<string, number>();
  for (const r of ready) for (const c of r.counts) sums.set(c.finding, (sums.get(c.finding) ?? 0) + c.rules);
  const totals = [...sums.entries()].sort((a, b) => b[1] - a[1]).map(([finding, rules]) => ({ finding, rules }));
  const capped = capList(all, cap);
  const countsLine = `Totals count rules per finding class over ${ready.length} READY ${ready.length === 1 ? "container" : "containers"} (window ${summary.days} days).`;
  return {
    state: "ok",
    rows: capped.shown,
    hiddenRows: capped.hidden,
    moreLabel: capped.moreLabel,
    readyContainers: ready.length,
    pendingContainers: pending,
    totals,
    pendingLine: pending > 0 ? `${pending} ${pending === 1 ? "container" : "containers"} PENDING: no counts yet, not counted as zero.` : null,
    countsLine,
  };
}
