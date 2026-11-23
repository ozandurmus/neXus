import type { PolicyHygieneFinding, PolicyHygienePage } from "../auth/adminApi";
import {
  hygieneCoverage,
  hygieneReasonBreakdown,
  type HygieneCoverage,
  type ReasonBreakdownRow,
} from "../screens/PolicyHygieneTab";

/** Not-provable reason groups shown before "+N more reason groups". */
export const POLICY_HYGIENE_REASON_CAP = 6;

/** Finding rows shown before "+N more matching rules". */
export const POLICY_HYGIENE_ROW_CAP = 10;

/** Default unused-rule threshold in days (matches PolicyHygieneTab). */
export const POLICY_HYGIENE_DEFAULT_DAYS = 90;

/** Standard-view link for full interactive policy hygiene (filters, CSV export, rule comparison). */
export const POLICY_HYGIENE_STANDARD_HREF = "?screen=policy&tab=hygiene&standard=1";

/** The 7 hygiene classes in the order shown by PolicyHygieneTab (proven classes first, not-provable coverage last). */
export const HYGIENE_CLASSES = [
  "conflict",
  "shadowed",
  "broad",
  "unused",
  "expired",
  "disabled",
  "unknown",
] as const;

export type HygieneClassKey = (typeof HYGIENE_CLASSES)[number];

export function hygieneClassLabel(cls: string): string {
  return cls === "unknown" ? "not provable (UNKNOWN)" : cls;
}

export interface HygieneClassCountView {
  readonly key: HygieneClassKey;
  readonly label: string;
  readonly rules: number | null;
  readonly countText: string;
}

export interface HygieneFindingRowView {
  readonly ruleId: string;
  readonly number: number;
  readonly name: string;
  readonly findingsSummary: string;
  readonly shadowStatus: "UNKNOWN" | "NOT_SHADOWED" | "REDUNDANT" | "CONFLICT";
  readonly shadowText: string;
}

export type PolicyHygieneView =
  | {
      readonly state: "unknown";
      readonly message: string;
    }
  | {
      readonly state: "ok";
      readonly coverage: HygieneCoverage;
      readonly coverageUnknown: boolean;
      readonly budgetReached: boolean;
      readonly classCounts: readonly HygieneClassCountView[];
      readonly shownReasons: readonly ReasonBreakdownRow[];
      readonly totalReasons: number;
      readonly shownRows: readonly HygieneFindingRowView[];
      /** Matching-rule total as reported by the service; null when missing or inconsistent (UNKNOWN). */
      readonly totalRows: number | null;
    };

export function isValidPolicyHygienePage(value: unknown): value is PolicyHygienePage {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<PolicyHygienePage>;
  if (!Array.isArray(candidate.rows)) return false;
  if (!candidate.counts || typeof candidate.counts !== "object" || Array.isArray(candidate.counts)) return false;
  return true;
}

export function buildHygieneClassCounts(
  counts: Record<string, number> | null | undefined,
): HygieneClassCountView[] {
  return HYGIENE_CLASSES.map((key) => {
    const raw = counts?.[key];
    const valid = typeof raw === "number" && Number.isFinite(raw) && raw >= 0;
    const rules = valid ? raw : null;
    return {
      key,
      label: hygieneClassLabel(key),
      rules,
      countText: rules !== null ? `${rules} rules` : "UNKNOWN",
    };
  });
}

function summarizeFindings(findings: readonly PolicyHygieneFinding[] | undefined): string {
  if (!Array.isArray(findings) || findings.length === 0) {
    return "No class findings";
  }
  const visible = findings.filter(
    (f) => !(f.findingClass === "unknown" && typeof f.evidence === "string" && f.evidence.startsWith("Shadowing: ")),
  );
  const source = visible.length > 0 ? visible : findings;
  return source
    .map((f) =>
      f.findingClass === "unknown"
        ? `UNKNOWN because ${f.evidence}`
        : `${f.severity} · ${f.findingClass}: ${f.evidence}`,
    )
    .join(" · ");
}

function formatShadowStatus(row: PolicyHygienePage["rows"][number]): {
  readonly shadowStatus: "UNKNOWN" | "NOT_SHADOWED" | "REDUNDANT" | "CONFLICT";
  readonly shadowText: string;
} {
  const status = row.hygiene?.shadowStatus;
  if (status === "CONFLICT") {
    return { shadowStatus: "CONFLICT", shadowText: "Shadow: CONFLICT" };
  }
  if (status === "REDUNDANT") {
    return { shadowStatus: "REDUNDANT", shadowText: "Shadow: REDUNDANT" };
  }
  if (status === "NOT_SHADOWED") {
    return { shadowStatus: "NOT_SHADOWED", shadowText: "Not shadowed (proven)" };
  }
  const reason = row.hygiene?.shadowReason?.trim() || "reason not recorded";
  return { shadowStatus: "UNKNOWN", shadowText: `UNKNOWN because ${reason}` };
}

export function buildPolicyHygieneView(
  data: PolicyHygienePage | null | undefined,
  reasonCap = POLICY_HYGIENE_REASON_CAP,
  rowCap = POLICY_HYGIENE_ROW_CAP,
): PolicyHygieneView {
  if (!isValidPolicyHygienePage(data)) {
    return {
      state: "unknown",
      message: "Hygiene UNKNOWN: this policy's hygiene analysis could not be read.",
    };
  }

  const coverage = hygieneCoverage(data.coverage);
  const coverageUnknown = coverage.shadow === null || coverage.hits === null;
  const classCounts = buildHygieneClassCounts(data.counts);
  const breakdown = hygieneReasonBreakdown(
    data.rows,
    Array.isArray(data.unknownReasons) ? data.unknownReasons : undefined,
  );
  const safeReasonCap = Number.isFinite(reasonCap) && reasonCap >= 0 ? Math.floor(reasonCap) : 0;
  const shownReasons = breakdown.slice(0, safeReasonCap);

  const safeRowCap = Number.isFinite(rowCap) && rowCap >= 0 ? Math.floor(rowCap) : 0;
  const shownRows: HygieneFindingRowView[] = data.rows.slice(0, safeRowCap).map((row) => {
    const shadow = formatShadowStatus(row);
    return {
      ruleId: row.ruleId || String(row.number),
      number: row.number,
      name: row.name || "Unnamed rule",
      findingsSummary: summarizeFindings(row.findings),
      shadowStatus: shadow.shadowStatus,
      shadowText: shadow.shadowText,
    };
  });
  // The total is trusted only when the service reported it and it can hold every returned row; otherwise UNKNOWN.
  const totalRows =
    typeof data.total === "number" && Number.isFinite(data.total) && data.total >= data.rows.length
      ? data.total
      : null;

  return {
    state: "ok",
    coverage,
    coverageUnknown,
    budgetReached: Boolean(data.budgetReached),
    classCounts,
    shownReasons,
    totalReasons: breakdown.length,
    shownRows,
    totalRows,
  };
}
