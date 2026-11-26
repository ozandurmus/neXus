import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { StatePanel, Ts } from "../../shell/States";
import type { Tone } from "../../shell/tone";
import { parseTraceTuple, traceTupleHref } from "../../shell/traceTupleLink";
import { useFetchOnMount } from "../../shell/useFetchOnMount";
import { m3 } from "../../theme/m3Theme";
import { RADIUS } from "../../theme/tokens";
import type { ZoneTabProps } from "./ZoneMatrixScreen";
import { Hash, Hint, SectionTitle, mono, panelSx } from "./zoneMatrixUi";
import {
  evaluateZoneMatrixPolicy, listZoneMatrixPolicies, severityWord, statementWord, zoneErrorLines,
  type EvaluationOutcome, type EvaluationRuleRow, type EvaluationWitness, type EvaluationZoneGap,
  type ZoneMatrixEvaluationResponse, type ZoneMatrixNotEvaluatedItem, type ZoneMatrixPoliciesResponse,
} from "./zoneMatrixApi";

const PAGE_SIZE = 50;
const WITNESS_AUDIT_NOTICE = "This view is recorded in the audit log";

function readOutcomeCount(
  counts: Readonly<Record<string, number>> | undefined,
  key: EvaluationOutcome,
): number | null {
  const value = counts?.[key];
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
}

/**
 * Contract 7.2 (ZM-7, ZM-26): an evaluation is proven exact only when it actually evaluated with COMPLETE coverage,
 * no uncollected-scope coverage items, the server marks the headline exact, explicit non-missing outcome counts are
 * present, and there are zero collection failures, undecided rules, or incomplete or non-evaluated policies.
 */
export function evaluationProven(result: ZoneMatrixEvaluationResponse): boolean {
  return (
    result.evaluated === true &&
    result.coverage === "COMPLETE" &&
    Array.isArray(result.coverage_items) &&
    result.coverage_items.length === 0 &&
    result.headline.exact === true &&
    result.failure_count === 0 &&
    readOutcomeCount(result.outcome_counts, "VIOLATION") !== null &&
    readOutcomeCount(result.outcome_counts, "NOT_EVALUABLE") === 0 &&
    readOutcomeCount(result.outcome_counts, "UNKNOWN") === 0 &&
    result.headline.not_evaluable_rules === 0 &&
    result.headline.unknown_rules === 0 &&
    result.headline.incomplete_policies === 0 &&
    result.headline.not_evaluated_policies === 0
  );
}

/**
 * Contract 7.2 (ZM-7, ZM-26): fail-closed headline sentence. Never displays a bare `"0 ..."` or an unprefixed
 * exact positive count when `proven` is false, even if the server response claimed `headline.exact: true`.
 */
export function evaluationHeadlineText(
  result: ZoneMatrixEvaluationResponse,
  proven: boolean = evaluationProven(result),
): string {
  const raw = (result.headline.text ?? "").trim();
  if (proven) return raw;
  if (!raw || /^0\b/.test(raw)) return "no proven violations in evaluated rules";
  if (/^[1-9]\d*\b/.test(raw)) return `at least ${raw}`;
  return raw;
}

/**
 * Contract 7.1 fail-closed "Open in Trace" deep link for one evaluation witness tuple, or `null` for ranges, CIDRs,
 * IPv6 addresses, port ranges, non-TCP/UDP protocols, `any`, or `UNKNOWN`.
 */
export function evaluationWitnessTraceHref(witness: EvaluationWitness): string | null {
  const parts = witness.service.split("/");
  if (parts.length !== 2) return null;
  const [protocol, port] = parts;
  const href = traceTupleHref({
    src: witness.source_range,
    dst: witness.destination_range,
    protocol,
    port,
  });
  return parseTraceTuple(href.slice(href.indexOf("#"))) ? href : null;
}

const OUTCOME_DISPLAY: Readonly<Record<EvaluationOutcome, { readonly label: string; readonly tone: Tone }>> = {
  VIOLATION: { label: "Violation", tone: "bad" },
  COMPLIANT: { label: "Allowed by matrix", tone: "ok" },
  NOT_EVALUABLE: { label: "Not evaluable", tone: "warn" },
  UNKNOWN: { label: "Outcome unknown", tone: "unknown" },
};

const SEVERITY_TONE: Readonly<Record<string, Tone>> = {
  CRITICAL: "bad",
  HIGH: "warn",
  MEDIUM: "attn",
  LOW: "neutral",
};

/**
 * Zone matrix delivery step 3 (docs/design/ZONE_MATRIX_CONTRACT.md sections 5, 7.1-7.2 and 10.1 item 3): on-demand
 * deterministic evaluation of stored Check Point and Palo Alto policies against a published zone-matrix version, plus
 * explicit listing of non-evaluated ASA and FortiGate sources.
 */
export function EvaluateTab({ data, notify }: ZoneTabProps) {
  const publishedVersions = useMemo(
    () => data.versions.filter((v) => v.state === "ACTIVE" || v.state === "RETIRED"),
    [data.versions],
  );
  const defaultVersionId = data.active?.version_id ?? (publishedVersions[0]?.version_id ?? "");

  const [selectedPolicyId, setSelectedPolicyId] = useState("");
  const [selectedVersionId, setSelectedVersionId] = useState(defaultVersionId);
  const [includeWitnesses, setIncludeWitnesses] = useState(false);
  const [evaluating, setEvaluating] = useState(false);
  const [result, setResult] = useState<ZoneMatrixEvaluationResponse | null>(null);
  const [outcomeFilter, setOutcomeFilter] = useState<string>("VIOLATION");
  const [severityFilter, setSeverityFilter] = useState<string>("");
  const [searchText, setSearchText] = useState("");
  const [pageOffset, setPageOffset] = useState(0);
  const [selectedRuleKey, setSelectedRuleKey] = useState<string | null>(null);

  const { data: catalogue, error: catalogueError, refresh: refreshCatalogue } = useFetchOnMount<ZoneMatrixPoliciesResponse>(
    () => listZoneMatrixPolicies(),
    (err) => zoneErrorLines(err).join(" "),
  );

  const policies = catalogue?.policies ?? [];
  const notEvaluated = catalogue?.not_evaluated ?? [];
  const canEvaluate = Boolean(catalogue?.can?.evaluate ?? data.draft.can.evaluate);
  const canViewBlocks = Boolean(catalogue?.can?.view_blocks ?? data.draft.can.view_blocks);

  useEffect(() => {
    if (!selectedVersionId && defaultVersionId) setSelectedVersionId(defaultVersionId);
  }, [defaultVersionId, selectedVersionId]);

  useEffect(() => {
    if (!selectedPolicyId && policies.length > 0) setSelectedPolicyId(policies[0].id);
  }, [policies, selectedPolicyId]);

  const runEvaluation = async () => {
    if (!selectedPolicyId || !selectedVersionId) return;
    notify(null);
    setEvaluating(true);
    try {
      const evaluated = await evaluateZoneMatrixPolicy(selectedPolicyId, selectedVersionId, includeWitnesses && canViewBlocks);
      setResult(evaluated);
      setPageOffset(0);
      setSelectedRuleKey(null);
      if (readOutcomeCount(evaluated.outcome_counts, "VIOLATION") === 0 && evaluated.rules.length > 0) {
        setOutcomeFilter("ALL");
      } else {
        setOutcomeFilter("VIOLATION");
      }
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
    } finally {
      setEvaluating(false);
    }
  };

  const filteredRules = useMemo(() => {
    if (!result) return [];
    const needle = searchText.trim().toLowerCase();
    return result.rules.filter((row) => {
      if (outcomeFilter !== "ALL" && row.outcome !== outcomeFilter) return false;
      if (severityFilter && row.highest_severity !== severityFilter) return false;
      if (!needle) return true;
      const haystack = [
        row.rule_name,
        String(row.rule_number),
        row.section_name,
        row.reason ?? "",
        ...row.touched_pair_names,
        ...row.flags,
      ].join(" ").toLowerCase();
      return haystack.includes(needle);
    });
  }, [result, outcomeFilter, severityFilter, searchText]);

  const pagedRules = useMemo(
    () => filteredRules.slice(pageOffset, pageOffset + PAGE_SIZE),
    [filteredRules, pageOffset],
  );

  const selectedRule = useMemo(
    () => (result && selectedRuleKey ? result.rules.find((r) => r.rule_id === selectedRuleKey) ?? null : null),
    [result, selectedRuleKey],
  );

  const ruleColumns: readonly DataColumn<EvaluationRuleRow>[] = useMemo(() => [
    {
      key: "number",
      header: "#",
      width: 64,
      mono: true,
      align: "right",
      render: (r) => r.rule_number > 0 ? String(r.rule_number) : "—",
    },
    {
      key: "section",
      header: "Section / layer",
      width: 170,
      render: (r) => r.inline_parent_path.length > 0 ? `${r.section_name} (inline)` : r.section_name,
    },
    {
      key: "rule",
      header: "Rule",
      render: (r) => r.rule_name || r.rule_uid,
    },
    {
      key: "outcome",
      header: "Outcome",
      width: 155,
      render: (r) => {
        const spec = OUTCOME_DISPLAY[r.outcome] ?? { label: r.outcome, tone: "unknown" as Tone };
        return <StatusChip tone={spec.tone} label={spec.label} />;
      },
    },
    {
      key: "severity",
      header: "Severity",
      width: 110,
      render: (r) => r.highest_severity
        ? <StatusChip tone={SEVERITY_TONE[r.highest_severity] ?? "neutral"} label={severityWord(r.highest_severity)} />
        : "—",
    },
    {
      key: "pairs",
      header: "Zone pairs / reason",
      width: 260,
      wrap: true,
      render: (r) => {
        if (r.outcome === "VIOLATION") {
          return r.violated_cells.map((c) => `${c.source_name} -> ${c.destination_name} (${c.nat_view})`).join("; ");
        }
        return r.reason ?? (r.touched_pair_names.join("; ") || "—");
      },
    },
    {
      key: "flags",
      header: "Flags",
      width: 190,
      mono: true,
      render: (r) => r.flags.length > 0 ? r.flags.join(", ") : "—",
    },
  ], []);

  const notEvalColumns: readonly DataColumn<ZoneMatrixNotEvaluatedItem>[] = useMemo(() => [
    { key: "vendor", header: "Vendor", width: 90, mono: true, render: (r) => r.vendor },
    { key: "name", header: "Source / policy", render: (r) => r.name },
    { key: "reason", header: "Evaluation state", width: 280, render: (r) => <StatusChip tone="unknown" label={r.reason} /> },
  ], []);

  const gapColumns: readonly DataColumn<EvaluationZoneGap>[] = useMemo(() => [
    { key: "number", header: "#", width: 64, mono: true, align: "right", render: (g) => String(g.rule_number) },
    { key: "section", header: "Section", width: 170, render: (g) => g.section_name },
    { key: "rule", header: "Rule", render: (g) => g.rule_name },
    { key: "count", header: "Private ranges", width: 130, mono: true, align: "right", render: (g) => String(g.range_count) },
    {
      key: "ranges",
      header: "Unassigned RFC 1918 / ULA ranges",
      width: 300,
      mono: true,
      render: (g) => g.unassigned_private_ranges ? g.unassigned_private_ranges.join(", ") : "Withheld unless witness view is requested",
    },
  ], []);

  if (publishedVersions.length === 0) {
    return (
      <StatePanel variant="empty" title="No published zone matrix version"
        body="Publish a zone matrix version before evaluating stored policies. Draft versions are never evaluated." />
    );
  }

  if (catalogue === null) {
    return catalogueError
      ? <StatePanel variant="error" title="Stored policies could not be read" body={catalogueError}
          action={<M3Button emphasis="outlined" onClick={() => refreshCatalogue()}>Retry</M3Button>} />
      : <StatePanel variant="empty" title="Reading stored policies…" announce="status" />;
  }

  return (
    <Stack gap={2} sx={{ pt: 1.5 }}>
      <Box sx={panelSx}>
        <SectionTitle note="Evaluation reads stored management snapshots only and never contacts a network device. Statements and outcomes reflect configured policy intent, not observed traffic.">
          Evaluate stored policy
        </SectionTitle>

        {!canEvaluate ? (
          <Hint>This account can inspect published versions and policy catalogues; starting an on-demand evaluation requires security or compliance administration.</Hint>
        ) : null}

        <Stack direction="row" flexWrap="wrap" gap={1.5} alignItems="center">
          <TextField select size="small" label="Stored policy" value={selectedPolicyId}
            disabled={policies.length === 0}
            onChange={(e) => setSelectedPolicyId(e.target.value)} sx={{ minWidth: 300 }}>
            {policies.length === 0 ? <MenuItem value="">No stored Check Point or Palo Alto policies</MenuItem> : null}
            {policies.map((p) => (
              <MenuItem key={p.id} value={p.id}>
                {p.vendor} · {p.name}{p.source_name ? ` (${p.source_name})` : ""} · {p.rule_count} rules
              </MenuItem>
            ))}
          </TextField>

          <TextField select size="small" label="Published version" value={selectedVersionId}
            onChange={(e) => setSelectedVersionId(e.target.value)} sx={{ minWidth: 210 }}>
            {publishedVersions.map((v) => (
              <MenuItem key={v.version_id} value={v.version_id}>
                Version {v.number ?? "?"} ({v.state === "ACTIVE" ? "Active" : "Retired"})
              </MenuItem>
            ))}
          </TextField>

          {canEvaluate && canViewBlocks ? (
            <FormControlLabel
              control={
                <Checkbox size="small" checked={includeWitnesses}
                  onChange={(e) => setIncludeWitnesses(e.target.checked)} />
              }
              label={<Typography variant="body2">Include witness addresses (recorded in the audit log)</Typography>}
            />
          ) : null}

          {canEvaluate ? (
            <M3Button emphasis="filled" disabled={evaluating || !selectedPolicyId || !selectedVersionId}
              onClick={() => { void runEvaluation(); }}>
              {evaluating ? "Evaluating…" : "Evaluate policy"}
            </M3Button>
          ) : null}
        </Stack>
      </Box>

      {result ? (() => {
        const proven = evaluationProven(result);
        const violationCount = readOutcomeCount(result.outcome_counts, "VIOLATION");
        const compliantCount = readOutcomeCount(result.outcome_counts, "COMPLIANT");
        const notEvaluableCount = readOutcomeCount(result.outcome_counts, "NOT_EVALUABLE");
        const unknownCount = readOutcomeCount(result.outcome_counts, "UNKNOWN");
        const hasViolations = result.headline.distinct_violating_rules > 0 || (violationCount !== null && violationCount > 0);
        const headlineTone: Tone = hasViolations
          ? "bad"
          : !result.evaluated
            ? "unknown"
            : proven
              ? "ok"
              : "warn";
        const headlineText = evaluationHeadlineText(result, proven);
        const violationsLabel = violationCount === null
          ? "Violations: UNKNOWN"
          : proven
            ? `Violations: ${violationCount}`
            : `Violations: at least ${violationCount}`;
        const violationsFilterLabel = violationCount === null
          ? "Violations (UNKNOWN)"
          : proven
            ? `Violations (${violationCount})`
            : `Violations (at least ${violationCount})`;
        const allowWitnessView = Boolean(canViewBlocks && result.can.view_blocks && result.witnesses_included);
        const policyHref = result.policy_id && selectedRule?.rule_id
          ? `?screen=policy&policy_id=${encodeURIComponent(result.policy_id)}&rule=${encodeURIComponent(selectedRule.rule_id)}&standard=1`
          : null;
        const hasAnyWitness = Boolean(
          allowWitnessView && selectedRule?.violated_cells.some((cell) => cell.witness !== null),
        );

        return (
        <>
          <Box data-testid="evaluation-headline" data-proven={proven ? "yes" : "no"} sx={panelSx}>
            <Stack direction="row" flexWrap="wrap" gap={1} alignItems="center">
              <StatusChip
                tone={headlineTone}
                label={result.coverage === "COMPLETE" ? "Coverage complete" : "Coverage incomplete"}
              />
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                {result.vendor} · {result.policy_name} · Version {result.version_number} (<Hash value={result.content_sha256} />)
                {result.collected_at ? <> · Snapshot collected <Ts at={result.collected_at} /></> : null}
                {" "}· Engine <Box component="span" sx={mono}>{result.engine_version}</Box> ({result.duration_ms} ms)
              </Typography>
            </Stack>
            <Typography variant="body1" sx={{ fontWeight: 600 }}>{headlineText}</Typography>
            {result.headline.outside_headline_text ? (
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{result.headline.outside_headline_text}</Typography>
            ) : null}

            <Stack direction="row" flexWrap="wrap" gap={1.5} data-testid="evaluation-counts">
              <StatusChip tone={violationCount === null ? "unknown" : violationCount > 0 ? "bad" : "neutral"}
                label={violationsLabel} />
              <StatusChip tone={compliantCount === null ? "unknown" : "neutral"}
                label={`Allowed by matrix: ${compliantCount === null ? "UNKNOWN" : compliantCount}`} />
              <StatusChip tone={notEvaluableCount === null ? "unknown" : notEvaluableCount > 0 ? "warn" : "neutral"}
                label={`Not evaluable: ${notEvaluableCount === null ? "UNKNOWN" : notEvaluableCount}`} />
              <StatusChip tone={unknownCount === null || unknownCount > 0 ? "unknown" : "neutral"}
                label={`Outcome unknown: ${unknownCount === null ? "UNKNOWN" : unknownCount}`} />
              <StatusChip tone="neutral"
                label={`Not applicable: ${result.not_applicable.total} (disabled ${result.not_applicable.disabled}, deny ${result.not_applicable.deny_type}, non-security ${result.not_applicable.outside_security_rulebase}, inline-decided ${result.not_applicable.apply_layer_decided_by_inline}, schedule ended ${result.not_applicable.schedule_ended})`} />
            </Stack>

            {result.coverage_items.length > 0 ? (
              <Box data-testid="coverage-items" sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>Coverage notes</Typography>
                {result.coverage_items.map((item) => (
                  <Typography key={item.code} variant="body2" sx={{ color: m3.onSurfaceVar }}>
                    <Box component="span" sx={mono}>{item.code}</Box>: {item.description}
                  </Typography>
                ))}
              </Box>
            ) : null}
          </Box>

          <Box sx={panelSx}>
            <SectionTitle note="Click a row to inspect violated zone pairs, disallowed services, undecided sub-matches and witness tuples.">
              Evaluated rules
            </SectionTitle>
            <Stack direction="row" flexWrap="wrap" gap={1.5} alignItems="center">
              <TextField select size="small" label="Outcome filter" value={outcomeFilter}
                onChange={(e) => { setPageOffset(0); setOutcomeFilter(e.target.value); }} sx={{ minWidth: 185 }}>
                <MenuItem value="ALL">All outcomes ({result.rules.length})</MenuItem>
                <MenuItem value="VIOLATION">{violationsFilterLabel}</MenuItem>
                <MenuItem value="NOT_EVALUABLE">Not evaluable ({notEvaluableCount === null ? "UNKNOWN" : notEvaluableCount})</MenuItem>
                <MenuItem value="UNKNOWN">Outcome unknown ({unknownCount === null ? "UNKNOWN" : unknownCount})</MenuItem>
                <MenuItem value="COMPLIANT">Allowed by matrix ({compliantCount === null ? "UNKNOWN" : compliantCount})</MenuItem>
              </TextField>

              <TextField select size="small" label="Severity filter" value={severityFilter}
                onChange={(e) => { setPageOffset(0); setSeverityFilter(e.target.value); }} sx={{ minWidth: 165 }}>
                <MenuItem value="">All severities</MenuItem>
                <MenuItem value="CRITICAL">Critical</MenuItem>
                <MenuItem value="HIGH">High</MenuItem>
                <MenuItem value="MEDIUM">Medium</MenuItem>
                <MenuItem value="LOW">Low</MenuItem>
              </TextField>

              <TextField size="small" label="Filter rules" value={searchText}
                onChange={(e) => { setPageOffset(0); setSearchText(e.target.value); }} sx={{ minWidth: 220 }} />
            </Stack>

            <DataTable
              ariaLabel="Evaluated policy rules"
              columns={ruleColumns}
              rows={pagedRules}
              rowKey={(r) => r.rule_id}
              selectedKey={selectedRuleKey}
              onRowClick={(r) => setSelectedRuleKey(r.rule_id === selectedRuleKey ? null : r.rule_id)}
              page={{
                offset: pageOffset,
                size: PAGE_SIZE,
                total: filteredRules.length,
                onPage: setPageOffset,
                noun: "rules",
              }}
              emptyText="No evaluated rules match the current filter."
            />

            {selectedRule ? (
              <Box data-testid="rule-finding-detail" sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, p: 1.5, bgcolor: m3.scLow }}>
                <Stack direction="row" flexWrap="wrap" justifyContent="space-between" alignItems="center" gap={1}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    Rule #{selectedRule.rule_number} · {selectedRule.rule_name || selectedRule.rule_uid}
                  </Typography>
                  {policyHref ? (
                    <Box
                      component="a"
                      href={policyHref}
                      data-testid="evaluate-open-policy-link"
                      sx={{ fontSize: "0.875rem", fontWeight: 500 }}
                    >
                      Open in Policy
                    </Box>
                  ) : null}
                </Stack>
                <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                  Rule UID: <Box component="span" sx={mono}>{selectedRule.rule_uid}</Box>
                  {selectedRule.covering_rule_id ? <> · Covered by earlier deny rule <Box component="span" sx={mono}>{selectedRule.covering_rule_id}</Box></> : null}
                </Typography>
                {hasAnyWitness ? (
                  <Typography variant="body2" data-testid="evaluate-witness-audit-notice" sx={{ mt: 1, fontWeight: 600 }}>
                    {WITNESS_AUDIT_NOTICE}
                  </Typography>
                ) : null}
                {selectedRule.violated_cells.map((cell, idx) => {
                  const witness = allowWitnessView ? cell.witness : null;
                  const traceHref = witness ? evaluationWitnessTraceHref(witness) : null;
                  return (
                    <Box key={`${cell.source}-${cell.destination}-${cell.nat_view}-${idx}`} sx={{ mt: 1 }}>
                      <Typography variant="body2">
                        <strong>{cell.source_name} {"->"} {cell.destination_name}</strong> ({cell.nat_view}) · Matrix statement: {statementWord(cell.statement)} · Severity: {severityWord(cell.severity)} · Disallowed services: <Box component="span" sx={mono}>{cell.not_allowed_services.join(", ")}</Box>
                      </Typography>
                      {witness ? (
                        <Box sx={{ mt: 0.25 }}>
                          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                            Witness: <Box component="span" sx={mono}>{witness.source_range} {"->"} {witness.destination_range}</Box> on <Box component="span" sx={mono}>{witness.service}</Box>
                          </Typography>
                          {traceHref ? (
                            <Box
                              component="a"
                              href={traceHref}
                              data-testid="evaluate-witness-trace-link"
                              sx={{ display: "inline-block", fontSize: "0.875rem", fontWeight: 500 }}
                            >
                              Open in Trace
                            </Box>
                          ) : null}
                        </Box>
                      ) : null}
                    </Box>
                  );
                })}
                {selectedRule.undecided_parts.length > 0 ? (
                  <Typography variant="body2" sx={{ mt: 1, color: m3.onSurfaceVar }}>
                    Undecided sub-matches: {selectedRule.undecided_parts.join("; ")}
                  </Typography>
                ) : null}
              </Box>
            ) : null}
          </Box>

          {result.zone_definition_gaps.length > 0 ? (
            <Box data-testid="zone-definition-gaps" sx={panelSx}>
              <SectionTitle note="Explicit rule or NAT private ranges (RFC 1918 or IPv6 ULA) that currently fall in UNASSIGNED.">
                Zone definition gaps ({result.zone_definition_gaps.length})
              </SectionTitle>
              <DataTable
                ariaLabel="Zone definition gaps"
                columns={gapColumns}
                rows={result.zone_definition_gaps}
                rowKey={(g) => g.rule_id}
                emptyText="No unassigned private address ranges referenced."
              />
            </Box>
          ) : null}
        </>
        );
      })() : null}

      {notEvaluated.length > 0 ? (
        <Box data-testid="not-evaluated-sources" sx={panelSx}>
          <SectionTitle note="Cisco ASA and FortiGate sources are listed explicitly and are not evaluated in this step.">
            Sources not evaluated ({notEvaluated.length})
          </SectionTitle>
          <DataTable
            ariaLabel="Sources not evaluated"
            columns={notEvalColumns}
            rows={notEvaluated}
            rowKey={(r) => r.id}
            emptyText="No non-evaluated sources."
          />
        </Box>
      ) : null}
    </Stack>
  );
}
