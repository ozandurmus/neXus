import { BECAME_UNDECIDED, flagWords } from "./zoneMatrixSummaryUi";
import type { SummaryFinding, SummaryPolicy } from "./zoneMatrixApi";

/**
 * Pure client-side option builders and finding predicate for the Violations tab (contract 7.1, 7.2; ZM-34, ZM-35).
 * Every filter narrows only the findings returned by the stored-summary route; nothing here alters request parameters,
 * headline figures, per-cell counts or the resolved-by-version-change list.
 */
export const ALL_ZONES_FILTER = "ALL";
export const UNKNOWN_VENDOR_VALUE = "__VENDOR_UNKNOWN__";
export const NO_REASON_VALUE = "__NO_REASON__";

export type FirstObservedRange =
  | "LAST_24_HOURS"
  | "LAST_7_DAYS"
  | "OLDER_THAN_7_DAYS"
  | "NOT_RECORDED";

export type PolicyStalenessChoice = "CURRENT" | "STALE" | "UNKNOWN";

export interface ViolationFilterOption {
  readonly value: string;
  readonly label: string;
}

export const FIRST_OBSERVED_OPTIONS: readonly { readonly value: FirstObservedRange; readonly label: string }[] = [
  { value: "LAST_24_HOURS", label: "Last 24 hours" },
  { value: "LAST_7_DAYS", label: "Last 7 days" },
  { value: "OLDER_THAN_7_DAYS", label: "Older than 7 days" },
  { value: "NOT_RECORDED", label: "Not recorded" },
];

export const STALENESS_OPTIONS: readonly { readonly value: PolicyStalenessChoice; readonly label: string }[] = [
  { value: "CURRENT", label: "Current" },
  { value: "STALE", label: "Stale" },
  { value: "UNKNOWN", label: "Unknown" },
];

/**
 * Typed client-side filter state for `matchesViolationFilters`. Every field is optional; an absent or empty value
 * (or `ALL` on a zone field) applies no narrowing.
 */
export interface ViolationFilters {
  readonly sourceZoneId?: string;
  readonly destinationZoneId?: string;
  readonly severity?: string;
  readonly vendor?: string;
  readonly policySourceId?: string;
  readonly deviceId?: string;
  readonly flag?: string;
  readonly reason?: string;
  readonly firstObserved?: FirstObservedRange | "";
  readonly lifecycle?: string;
  readonly staleness?: PolicyStalenessChoice | "";
}

const DAY_MS = 24 * 60 * 60 * 1000;
const SEVEN_DAYS_MS = 7 * DAY_MS;
const ISO_INSTANT_RE = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|([+-])(\d{2}):?(\d{2}))$/;
const MACHINE_REASON_TOKEN_RE = /^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/;

function compareExact(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function compareSorted(a: string, b: string): number {
  return a.localeCompare(b) || compareExact(a, b);
}

/**
 * Maps `policies[].latest_complete_run.run_id` to `policies[].vendor`. A finding whose `run_id` is absent from this
 * map has vendor UNKNOWN (never guessed from policy or source names).
 */
export function buildPolicyVendorByRunId(policies: readonly SummaryPolicy[]): ReadonlyMap<string, string> {
  const byRunId = new Map<string, string>();
  for (const policy of policies) {
    const runId = policy.latest_complete_run?.run_id;
    if (runId && policy.vendor) {
      byRunId.set(runId, policy.vendor);
    }
  }
  return byRunId;
}

/**
 * Distinct sorted vendor options from `policies[].vendor`, plus `"Vendor unknown"` when at least one returned finding
 * has no joinable policy row by `run_id`.
 */
export function buildVendorOptions(
  policies: readonly SummaryPolicy[],
  findings: readonly SummaryFinding[] = [],
  policyVendorByRunId: ReadonlyMap<string, string> = buildPolicyVendorByRunId(policies),
): readonly ViolationFilterOption[] {
  const distinct = new Set<string>();
  for (const policy of policies) {
    if (policy.vendor) {
      distinct.add(policy.vendor);
    }
  }
  const options: ViolationFilterOption[] = [...distinct]
    .sort(compareSorted)
    .map((vendor) => ({ value: vendor, label: vendor }));
  const hasUnknownVendorFinding = findings.some((f) => !f.run_id || !policyVendorByRunId.has(f.run_id));
  if (hasUnknownVendorFinding) {
    options.push({ value: UNKNOWN_VENDOR_VALUE, label: "Vendor unknown" });
  }
  return options;
}

/**
 * Distinct sorted policy-source options from `findings[].policy_source_id`, labelled with the matching
 * `policies[].source_name` when present, else the opaque id verbatim (no case folding, no trimming).
 */
export function buildPolicySourceOptions(
  findings: readonly SummaryFinding[],
  policies: readonly SummaryPolicy[],
): readonly ViolationFilterOption[] {
  const sourceNameById = new Map<string, string>();
  for (const policy of policies) {
    if (policy.source_name && policy.policy_id && !sourceNameById.has(policy.policy_id)) {
      sourceNameById.set(policy.policy_id, policy.source_name);
    }
  }
  for (const policy of policies) {
    if (policy.source_name && policy.source_id && !sourceNameById.has(policy.source_id)) {
      sourceNameById.set(policy.source_id, policy.source_name);
    }
  }
  const distinctIds = new Set<string>();
  for (const finding of findings) {
    if (finding.policy_source_id !== "") {
      distinctIds.add(finding.policy_source_id);
    }
  }
  return [...distinctIds]
    .map((id) => ({ value: id, label: sourceNameById.get(id) ?? id }))
    .sort((a, b) => compareSorted(a.label, b.label) || compareExact(a.value, b.value));
}

/**
 * Distinct sorted target device ids across the returned findings, kept verbatim as sent by the server.
 */
export function buildDeviceOptions(findings: readonly SummaryFinding[]): readonly ViolationFilterOption[] {
  const distinct = new Set<string>();
  for (const finding of findings) {
    for (const deviceId of finding.target_device_ids) {
      if (deviceId !== "") {
        distinct.add(deviceId);
      }
    }
  }
  return [...distinct]
    .sort(compareSorted)
    .map((deviceId) => ({ value: deviceId, label: deviceId }));
}

/**
 * Formats an `UPPER_SNAKE_CASE` reason token through `flagWords` while keeping `UNKNOWN` and prose reason codes
 * (`"IPv6 not evaluated"`, `"NAT translation unresolved"`, `"unresolved object"`) verbatim.
 */
export function reasonLabel(code: string): string {
  if (code === "UNKNOWN") return "UNKNOWN";
  if (MACHINE_REASON_TOKEN_RE.test(code)) return flagWords(code);
  return code;
}

/**
 * Distinct sorted non-null reason options from the returned findings.
 */
export function buildReasonOptions(findings: readonly SummaryFinding[]): readonly ViolationFilterOption[] {
  const distinct = new Set<string>();
  for (const finding of findings) {
    if (finding.reason !== null && finding.reason !== "") {
      distinct.add(finding.reason);
    }
  }
  return [...distinct]
    .map((code) => ({ value: code, label: reasonLabel(code) }))
    .sort((a, b) => compareSorted(a.label, b.label) || compareExact(a.value, b.value));
}

/**
 * Parses an ISO-8601 instant string with explicit zone (`Z` or numeric offset) into epoch milliseconds, returning
 * `null` when absent, malformed or calendar-invalid so unparsable dates fail closed into `"Not recorded"`.
 */
export function parseObservedInstantMs(value: string | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const trimmed = value.trim();
  const match = ISO_INSTANT_RE.exec(trimmed);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = match[6] !== undefined ? Number(match[6]) : 0;
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
    return null;
  }
  const calCheck = new Date(Date.UTC(year, month - 1, day));
  if (calCheck.getUTCFullYear() !== year || calCheck.getUTCMonth() !== month - 1 || calCheck.getUTCDate() !== day) {
    return null;
  }
  if (match[7] !== undefined) {
    const offH = Number(match[8]);
    const offM = Number(match[9]);
    if (offH > 23 || offM > 59) return null;
  }
  const ms = Date.parse(trimmed);
  return Number.isFinite(ms) ? ms : null;
}

/**
 * Evaluates a finding's `first_observed_at` against a fixed range relative to `nowMs`. Null or unparsable timestamps
 * match only `"NOT_RECORDED"` and are never placed into a time range.
 */
export function matchesFirstObserved(
  firstObservedAt: string | null | undefined,
  choice: FirstObservedRange,
  nowMs: number,
): boolean {
  const observedMs = parseObservedInstantMs(firstObservedAt);
  if (choice === "NOT_RECORDED") {
    return observedMs === null;
  }
  if (observedMs === null || !Number.isFinite(nowMs)) {
    return false;
  }
  const ageMs = nowMs - observedMs;
  if (ageMs < 0) {
    return false;
  }
  switch (choice) {
    case "LAST_24_HOURS":
      return ageMs <= DAY_MS;
    case "LAST_7_DAYS":
      return ageMs <= SEVEN_DAYS_MS;
    case "OLDER_THAN_7_DAYS":
      return ageMs > SEVEN_DAYS_MS;
  }
}

/**
 * Normalises `policy_staleness`: any value other than `"CURRENT"` or `"STALE"` is `"UNKNOWN"`, never `"CURRENT"`.
 */
export function normalizePolicyStaleness(value: string | null | undefined): PolicyStalenessChoice {
  if (value === "CURRENT" || value === "STALE") return value;
  return "UNKNOWN";
}

/**
 * Pure AND predicate over all client-side Violations tab filters. Empty / omitted fields perform no narrowing.
 */
export function matchesViolationFilters(
  finding: SummaryFinding,
  policyVendorByRunId: ReadonlyMap<string, string>,
  filters: ViolationFilters,
  nowMs: number,
): boolean {
  const src = !filters.sourceZoneId || filters.sourceZoneId === ALL_ZONES_FILTER ? ALL_ZONES_FILTER : filters.sourceZoneId;
  const dst = !filters.destinationZoneId || filters.destinationZoneId === ALL_ZONES_FILTER ? ALL_ZONES_FILTER : filters.destinationZoneId;
  if (src !== ALL_ZONES_FILTER || dst !== ALL_ZONES_FILTER) {
    const matchesPair = finding.violated_pairs.some(
      (p) => (src === ALL_ZONES_FILTER || p.source_zone_id === src) && (dst === ALL_ZONES_FILTER || p.destination_zone_id === dst),
    );
    if (!matchesPair) return false;
  }
  if (
    filters.severity
    && finding.highest_severity !== filters.severity
    && !finding.violated_pairs.some((p) => p.severity === filters.severity)
  ) {
    return false;
  }
  if (filters.vendor) {
    const joinedVendor = finding.run_id ? policyVendorByRunId.get(finding.run_id) : undefined;
    if (filters.vendor === UNKNOWN_VENDOR_VALUE) {
      if (joinedVendor !== undefined) return false;
    } else if (joinedVendor !== filters.vendor) {
      return false;
    }
  }
  if (filters.policySourceId && finding.policy_source_id !== filters.policySourceId) {
    return false;
  }
  if (filters.deviceId && !finding.target_device_ids.includes(filters.deviceId)) {
    return false;
  }
  if (filters.flag && !finding.flags.includes(filters.flag)) {
    return false;
  }
  if (filters.reason) {
    if (filters.reason === NO_REASON_VALUE) {
      if (finding.reason !== null && finding.reason !== "") return false;
    } else if (finding.reason !== filters.reason) {
      return false;
    }
  }
  if (filters.firstObserved && !matchesFirstObserved(finding.first_observed_at, filters.firstObserved, nowMs)) {
    return false;
  }
  if (filters.lifecycle && (filters.lifecycle === "undecided") !== finding.flags.includes(BECAME_UNDECIDED)) {
    return false;
  }
  if (filters.staleness && normalizePolicyStaleness(finding.policy_staleness) !== filters.staleness) {
    return false;
  }
  return true;
}
