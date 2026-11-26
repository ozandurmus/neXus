import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { isRestricted } from "../../shell/States";
import { vendorLabel } from "../../shell/presenters";
import { m3 } from "../../theme/m3Theme";
import { RADIUS } from "../../theme/tokens";
import { Hint } from "./zoneMatrixUi";
import {
  ThresholdNote, ageText, stalenessReasonWords, type ZoneSummaryHandle,
} from "./zoneMatrixSummaryUi";
import type { ZoneMatrixSummaryResponse } from "./zoneMatrixApi";

/** Runtime check that the summary payload carries the fleet coverage fields (fail closed to UNKNOWN on partial mocks). */
export function isUsableCoverageSummary(data: ZoneMatrixSummaryResponse | null): data is ZoneMatrixSummaryResponse {
  return data !== null
    && typeof data === "object"
    && typeof data.state === "string"
    && Array.isArray(data.policies)
    && Array.isArray(data.not_evaluated)
    && Boolean(data.staleness_threshold)
    && typeof data.staleness_threshold.seconds === "number";
}

/**
 * True only when the summary is READY, at least one covered policy exists, every covered policy is CURRENT and COMPLETE
 * with a completed run and a known snapshot age, nothing is in `not_evaluated`, and the fleet headline has no stale,
 * incomplete, older-version, not-evaluated or undecided counts (ZONE_MATRIX_CONTRACT.md 7.1, 7.2, ZM-26, ZM-35).
 */
export function isCoverageClean(data: ZoneMatrixSummaryResponse | null): boolean {
  if (!isUsableCoverageSummary(data)) return false;
  if (data.state !== "READY" || data.policies.length === 0 || data.not_evaluated.length > 0) return false;
  const h = data.headline;
  if (!h || !h.exact || !h.staleness_headline?.exact) return false;
  if (h.stale_policies > 0 || h.incomplete_policies > 0 || h.older_version_policies > 0 || h.not_evaluated_policies > 0) {
    return false;
  }
  if (h.not_evaluable_findings > 0 || h.unknown_findings > 0 || h.became_undecided_findings > 0) {
    return false;
  }
  return data.policies.every((p) =>
    p.staleness?.verdict === "CURRENT"
    && p.coverage === "COMPLETE"
    && p.has_complete_run
    && p.staleness.snapshot_age_known
    && p.staleness.snapshot_age_seconds !== null
    && p.staleness.snapshot_age_seconds >= 0);
}

export interface DerivedCoverageSummary {
  readonly totalPolicies: number;
  readonly cleanPolicies: number;
  readonly stalePolicies: number;
  readonly incompletePolicies: number;
  readonly olderVersionPolicies: number;
  readonly notEvaluatedPolicies: number;
  readonly notEvaluableFindings: number;
  readonly unknownFindings: number;
  readonly oldestEvaluatedAgeSeconds: number | null;
  readonly newestEvaluatedAgeSeconds: number | null;
  readonly unknownAgePolicies: number;
  readonly clean: boolean;
}

/**
 * Fleet coverage counts derived solely from `policies`, `not_evaluated`, `active_version` and `headline` — never from
 * `findings` or `total_findings`, which `GET /api/v2/zone-matrix/summary` narrows when the Violations tab filters by
 * `policy_id`, `outcome` or `limit` (`ZoneMatrixSummaryView.project`).
 */
export function deriveCoverageSummary(data: ZoneMatrixSummaryResponse): DerivedCoverageSummary {
  const totalPolicies = data.policies.length;
  const cleanFromPolicies = data.policies.filter(
    (p) => p.staleness?.verdict === "CURRENT" && p.coverage === "COMPLETE" && p.has_complete_run,
  ).length;
  const staleFromPolicies = data.policies.filter((p) => p.staleness?.verdict !== "CURRENT").length;
  const incompleteFromPolicies = data.policies.filter((p) => p.coverage !== "COMPLETE" || !p.has_complete_run).length;
  const olderFromPolicies = data.policies.filter(
    (p) => p.has_complete_run
      && p.latest_complete_run !== null
      && data.active_version !== null
      && p.latest_complete_run.version_id !== data.active_version.version_id,
  ).length;

  const h = data.headline;
  const stalePolicies = Math.max(staleFromPolicies, h?.stale_policies ?? 0);
  const incompletePolicies = Math.max(incompleteFromPolicies, h?.incomplete_policies ?? 0);
  const olderVersionPolicies = Math.max(olderFromPolicies, h?.older_version_policies ?? 0);
  const notEvaluatedPolicies = Math.max(data.not_evaluated.length, h?.not_evaluated_policies ?? 0);
  const cleanPolicies = Math.min(cleanFromPolicies, Math.max(0, totalPolicies - Math.max(stalePolicies, incompletePolicies)));

  const evaluatedKnownAges = data.policies
    .filter((p) => p.has_complete_run
      && p.staleness?.snapshot_age_known
      && p.staleness.snapshot_age_seconds !== null
      && p.staleness.snapshot_age_seconds >= 0)
    .map((p) => p.staleness.snapshot_age_seconds as number);
  const unknownAgePolicies = data.policies.filter(
    (p) => !p.staleness?.snapshot_age_known || p.staleness.snapshot_age_seconds === null || p.staleness.snapshot_age_seconds < 0,
  ).length;

  return {
    totalPolicies,
    cleanPolicies,
    stalePolicies,
    incompletePolicies,
    olderVersionPolicies,
    notEvaluatedPolicies,
    notEvaluableFindings: h?.not_evaluable_findings ?? 0,
    unknownFindings: h?.unknown_findings ?? 0,
    oldestEvaluatedAgeSeconds: evaluatedKnownAges.length > 0 ? Math.max(...evaluatedKnownAges) : null,
    newestEvaluatedAgeSeconds: evaluatedKnownAges.length > 0 ? Math.min(...evaluatedKnownAges) : null,
    unknownAgePolicies,
    clean: isCoverageClean(data),
  };
}

function unknownCoverageReason(summary: ZoneSummaryHandle): string {
  if (summary.error !== null) {
    return isRestricted(summary.rawError)
      ? "this account is not permitted to read the stored summary"
      : "the stored summary could not be read";
  }
  const { data } = summary;
  if (data === null) return "reading the stored summary…";
  if (!isUsableCoverageSummary(data)) return "stored summary unavailable";
  if (data.state === "NO_ACTIVE_VERSION") return data.state_text || "no active zone matrix version";
  if (data.state === "NO_EVALUABLE_POLICY" || data.policies.length === 0) {
    return data.state_text || "no stored Check Point or Palo Alto policy";
  }
  if (data.state !== "READY") return data.state_text || "no policy has a complete evaluation run yet";
  return "no fleet headline available";
}

function tallyStrings(values: readonly string[]): readonly { readonly text: string; readonly count: number }[] {
  const counts = new Map<string, number>();
  for (const v of values) {
    if (v) counts.set(v, (counts.get(v) ?? 0) + 1);
  }
  return [...counts.entries()].map(([text, count]) => ({ text, count }));
}

function tallyNotEvaluatedByVendor(data: ZoneMatrixSummaryResponse): readonly {
  readonly vendor: string;
  readonly reason: string;
  readonly count: number;
}[] {
  const grouped = new Map<string, { vendor: string; reason: string; count: number }>();
  for (const item of data.not_evaluated) {
    const vendor = vendorLabel(item.vendor);
    const reason = item.reason || "reason UNKNOWN";
    const key = `${vendor}\u0000${reason}`;
    const existing = grouped.get(key);
    if (existing) existing.count += 1;
    else grouped.set(key, { vendor, reason, count: 1 });
  }
  return [...grouped.values()];
}

function stalenessHeadlineText(data: ZoneMatrixSummaryResponse, clean: boolean): string | null {
  const raw = data.headline?.staleness_headline?.text?.trim();
  if (!raw) return null;
  if (!clean && /^0\b/.test(raw)) return "at least 0 (coverage or evaluation not complete)";
  return raw;
}

/**
 * Compact, always-visible coverage strip rendered above all six Zone matrix tabs (ZONE_MATRIX_CONTRACT.md 7.1, 7.2,
 * ZM-26, ZM-30, ZM-35, Amendment 2026-11-23). Prints counts, ages and reasons only — never policy or source names.
 */
export function CoverageStrip({
  summary,
  onOpenCoverage,
}: {
  readonly summary: ZoneSummaryHandle;
  readonly onOpenCoverage: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const { data } = summary;
  const usable = summary.error === null && isUsableCoverageSummary(data) ? data : null;
  const readyWithPolicies = usable !== null
    && usable.state === "READY"
    && usable.policies.length > 0
    && usable.headline !== null;

  const derived = usable !== null ? deriveCoverageSummary(usable) : null;
  const canExpand = usable !== null;

  if (!readyWithPolicies || derived === null || usable === null) {
    const extraNotEvaluated = usable !== null
      ? Math.max(usable.not_evaluated.length, usable.headline?.not_evaluated_policies ?? 0)
      : 0;
    const partialPolicies = usable !== null && usable.policies.length > 0 && derived !== null
      ? ` · ${derived.cleanPolicies} of ${derived.totalPolicies} policies CURRENT and COMPLETE`
      : "";
    return (
      <Box
        role="region"
        aria-label="Zone matrix coverage"
        data-testid="zone-matrix-coverage-strip"
        data-clean="no"
        data-coverage-state="unknown"
        sx={{
          border: `1px solid ${m3.outlineVar}`,
          borderRadius: `${RADIUS.card}px`,
          bgcolor: m3.scLow,
          px: 2,
          py: 1,
          display: "flex",
          flexDirection: "column",
          gap: 1,
        }}
      >
        <Stack direction="row" gap={1.25} alignItems="center" flexWrap="wrap">
          <StatusChip dense tone="unknown" label="UNKNOWN" />
          <Typography variant="body2" data-testid="coverage-strip-line" sx={{ flex: 1, minWidth: 220 }}>
            Zone matrix coverage not known: {unknownCoverageReason(summary)}
            {partialPolicies}
            {extraNotEvaluated > 0 ? ` · ${extraNotEvaluated} not evaluated` : ""}
          </Typography>
          {canExpand ? (
            <M3Button
              emphasis="text"
              onClick={() => setExpanded((prev) => !prev)}
            >
              {expanded ? "Hide coverage details" : "Coverage details"}
            </M3Button>
          ) : null}
          <M3Button emphasis="text" onClick={onOpenCoverage}>Open Coverage</M3Button>
        </Stack>
        {expanded && usable !== null ? (
          <CoverageStripDetail data={usable} derived={deriveCoverageSummary(usable)} />
        ) : null}
      </Box>
    );
  }

  const issueSegments: string[] = [];
  if (derived.stalePolicies > 0) issueSegments.push(`${derived.stalePolicies} STALE`);
  if (derived.incompletePolicies > 0) issueSegments.push(`${derived.incompletePolicies} INCOMPLETE`);
  if (derived.olderVersionPolicies > 0) issueSegments.push(`${derived.olderVersionPolicies} older-version`);
  if (derived.notEvaluatedPolicies > 0) issueSegments.push(`${derived.notEvaluatedPolicies} not evaluated`);
  if (derived.notEvaluableFindings > 0) issueSegments.push(`${derived.notEvaluableFindings} NOT_EVALUABLE`);
  if (derived.unknownFindings > 0) issueSegments.push(`${derived.unknownFindings} UNKNOWN`);

  const oldestAgeLabel = derived.oldestEvaluatedAgeSeconds !== null
    ? `${ageText(derived.oldestEvaluatedAgeSeconds)}${derived.unknownAgePolicies > 0 ? ` (${derived.unknownAgePolicies} snapshot age UNKNOWN)` : ""}`
    : "UNKNOWN";

  const lowerBoundNote = !derived.clean && usable.headline !== null
    ? ` · violation counts are at least ${usable.headline.distinct_violating_rules}`
    : "";

  return (
    <Box
      role="region"
      aria-label="Zone matrix coverage"
      data-testid="zone-matrix-coverage-strip"
      data-clean={derived.clean ? "yes" : "no"}
      data-coverage-state={derived.clean ? "clean" : "partial"}
      sx={{
        border: `1px solid ${m3.outlineVar}`,
        borderRadius: `${RADIUS.card}px`,
        bgcolor: m3.scLow,
        px: 2,
        py: 1,
        display: "flex",
        flexDirection: "column",
        gap: 1,
      }}
    >
      <Stack direction="row" gap={1.25} alignItems="center" flexWrap="wrap">
        <StatusChip
          dense
          tone={derived.clean ? "ok" : "warn"}
          label={derived.clean ? "CURRENT and COMPLETE" : "Coverage incomplete or stale"}
        />
        <Typography variant="body2" data-testid="coverage-strip-line" sx={{ flex: 1, minWidth: 240 }}>
          <strong>{derived.cleanPolicies} of {derived.totalPolicies} policies CURRENT and COMPLETE</strong>
          {issueSegments.length > 0 ? ` · ${issueSegments.join(" · ")}` : ""}
          {lowerBoundNote}
          {" · "}Oldest evaluated snapshot age: <span data-testid="coverage-strip-oldest-age">{oldestAgeLabel}</span>
        </Typography>
        <M3Button
          emphasis="text"
          onClick={() => setExpanded((prev) => !prev)}
        >
          {expanded ? "Hide coverage details" : "Coverage details"}
        </M3Button>
        <M3Button emphasis="text" onClick={onOpenCoverage}>Open Coverage</M3Button>
      </Stack>
      {expanded ? <CoverageStripDetail data={usable} derived={derived} /> : null}
    </Box>
  );
}

function CoverageStripDetail({
  data,
  derived,
}: {
  readonly data: ZoneMatrixSummaryResponse;
  readonly derived: DerivedCoverageSummary;
}) {
  const staleReasonCounts = tallyStrings(
    data.policies.flatMap((p) => (p.staleness?.reasons ?? []).map((r) => stalenessReasonWords(r))),
  );
  const coverageItemCounts = tallyStrings(
    data.policies.flatMap((p) => p.coverage_items ?? []),
  );
  const notEvaluatedGroups = tallyNotEvaluatedByVendor(data);
  const stalenessLine = stalenessHeadlineText(data, derived.clean);
  const ageSpanNote = derived.oldestEvaluatedAgeSeconds !== null
    && derived.newestEvaluatedAgeSeconds !== null
    && derived.oldestEvaluatedAgeSeconds !== derived.newestEvaluatedAgeSeconds
    ? ` Evaluated snapshot ages range from ${ageText(derived.newestEvaluatedAgeSeconds)} to ${ageText(derived.oldestEvaluatedAgeSeconds)}.`
    : "";

  return (
    <Box
      data-testid="coverage-strip-detail"
      sx={{
        borderTop: `1px solid ${m3.outlineVar}`,
        pt: 1,
        display: "flex",
        flexDirection: "column",
        gap: 0.5,
      }}
    >
      <ThresholdNote
        threshold={data.staleness_threshold}
        id="coverage-strip-staleness-threshold"
        testId="coverage-strip-threshold"
      />
      <Hint>
        Every age is the age of the evidence (when the evaluated policy snapshot was collected).{ageSpanNote}
      </Hint>
      {data.state_text ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          Summary state: {data.state_text}
          {stalenessLine ? ` · ${stalenessLine}` : ""}
        </Typography>
      ) : null}
      {staleReasonCounts.length > 0 ? (
        <Typography variant="body2" data-testid="coverage-strip-stale-reasons">
          Staleness reasons: {staleReasonCounts.map((r) => `${r.text} (${r.count})`).join("; ")}.
        </Typography>
      ) : null}
      {coverageItemCounts.length > 0 ? (
        <Typography variant="body2" data-testid="coverage-strip-items">
          Coverage items: {coverageItemCounts.map((c) => `${c.text} (${c.count})`).join("; ")}.
        </Typography>
      ) : null}
      {notEvaluatedGroups.length > 0 ? (
        <Typography variant="body2" data-testid="coverage-strip-not-evaluated">
          Not evaluated by vendor:{" "}
          {notEvaluatedGroups.map((g) => `${g.vendor} (${g.count}: ${g.reason})`).join("; ")}.
        </Typography>
      ) : null}
    </Box>
  );
}
