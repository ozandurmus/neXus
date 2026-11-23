import type { PolicyChangeRow, PolicyChangesPage } from "../auth/adminApi";
import type { SourceRead } from "../screens/overview/attentionRules";
import { plural } from "../shell/plural";

export interface PolicyRuleRevisionSummaryView {
  readonly state: "ok" | "restricted" | "unknown";
  readonly distinctRulesText: string;
  readonly breakdownText: string;
  readonly truncated: boolean;
  readonly shownRowsCount: number;
}

function isFiniteCount(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

export function summarisePolicyRuleRevisions(
  policyRead: SourceRead<PolicyChangesPage>,
): PolicyRuleRevisionSummaryView {
  if (policyRead.state !== "ok" || !policyRead.value?.summary) {
    return {
      state: policyRead.state === "restricted" ? "restricted" : "unknown",
      distinctRulesText: "UNKNOWN",
      breakdownText: "UNKNOWN",
      truncated: false,
      shownRowsCount: 0,
    };
  }

  const summary = policyRead.value.summary;
  const distinctRulesText = isFiniteCount(summary.rules)
    ? plural(summary.rules, "distinct rule", "distinct rules")
    : "UNKNOWN";

  const rawCounts = summary.counts;
  const counts =
    rawCounts && typeof rawCounts === "object" ? (rawCounts as Record<string, unknown>) : null;

  let breakdownText = "UNKNOWN";
  if (counts !== null) {
    const keys = ["added", "modified", "removed"] as const;
    const hasAnyKey = keys.some((k) => counts[k] !== undefined);
    const totalIsZero = isFiniteCount(summary.total) && summary.total === 0;
    const allPresentAreFinite = keys.every(
      (k) => counts[k] === undefined || isFiniteCount(counts[k]),
    );
    if ((hasAnyKey || totalIsZero) && allPresentAreFinite) {
      const added = isFiniteCount(counts.added) ? counts.added : 0;
      const modified = isFiniteCount(counts.modified) ? counts.modified : 0;
      const removed = isFiniteCount(counts.removed) ? counts.removed : 0;
      breakdownText = `${plural(added, "added rule", "added rules")} · ${plural(modified, "modified rule", "modified rules")} · ${plural(removed, "removed rule", "removed rules")}`;
    }
  }

  const truncated =
    typeof policyRead.value.nextCursor === "string" &&
    policyRead.value.nextCursor.trim().length > 0;
  const shownRowsCount = Array.isArray(policyRead.value.rows)
    ? policyRead.value.rows.length
    : 0;

  return {
    state: "ok",
    distinctRulesText,
    breakdownText,
    truncated,
    shownRowsCount,
  };
}

function rowMatchesChangeSet(
  changeSetId: string,
  row: PolicyChangeRow,
): boolean {
  const time = row.collectedAt ?? row.changedOn;
  const actorRaw = row.changedBy || "UNKNOWN";
  const initCap = Boolean(row.initialCapture);

  const candidateTuples = [
    JSON.stringify([row.policyId, time, actorRaw]),
    JSON.stringify([row.policyId, time, "AMBIGUOUS"]),
    JSON.stringify([row.policyId, time, actorRaw, initCap]),
    JSON.stringify([row.policyId, time, "AMBIGUOUS", initCap]),
  ];

  for (const tuple of candidateTuples) {
    if (changeSetId === tuple || changeSetId === `pol:${tuple}`) {
      return true;
    }
  }

  const stripped = changeSetId.startsWith("pol:") ? changeSetId.slice(4) : changeSetId;
  if (stripped.startsWith("[")) {
    try {
      const parsed: unknown = JSON.parse(stripped);
      if (Array.isArray(parsed) && parsed.length >= 2) {
        const [pId, t, , init] = parsed;
        if (
          pId === row.policyId &&
          t === time &&
          (init === undefined || Boolean(init) === initCap)
        ) {
          return true;
        }
      }
    } catch {
      // Ignore malformed JSON set ids and fall through.
    }
  }

  return false;
}

export function ambiguousRevisionsForSet(
  changeSet: {
    readonly id: string;
    readonly category: string;
    readonly actorIsAmbiguous: boolean;
  },
  rows: readonly PolicyChangeRow[],
): readonly string[] {
  if (
    !changeSet ||
    changeSet.category !== "rule" ||
    !changeSet.actorIsAmbiguous ||
    !Array.isArray(rows)
  ) {
    return [];
  }

  const seen = new Set<string>();
  const out: string[] = [];

  for (const row of rows) {
    if (!row || row.actorState !== "AMBIGUOUS") continue;
    const rev = typeof row.revision === "string" ? row.revision.trim() : "";
    if (!rev || seen.has(rev)) continue;
    if (!rowMatchesChangeSet(changeSet.id, row)) continue;
    seen.add(rev);
    out.push(rev);
    if (out.length >= 5) break;
  }

  return out;
}
