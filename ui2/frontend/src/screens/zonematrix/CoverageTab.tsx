import { useCallback, useMemo, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { StatePanel, Ts, Unknown } from "../../shell/States";
import { m3 } from "../../theme/m3Theme";
import { Hint, SectionTitle, panelSx } from "./zoneMatrixUi";
import { stalenessHeadlineText } from "./ViolationsTab";
import {
  SummaryReadFailure, ThresholdNote, ageText, flagWords, stalenessReasonWords, summaryGate, useEnsureSummary,
  type ZoneSummaryHandle,
} from "./zoneMatrixSummaryUi";
import {
  startZoneMatrixRun, zoneErrorLines,
  type SummaryNotEvaluated, type SummaryPolicy, type SummaryRun,
} from "./zoneMatrixApi";

function RunCell({ run, label }: { readonly run: SummaryRun | null; readonly label: string }) {
  if (run === null) return <Box>{label}: <Unknown reason="No such run is stored" /></Box>;
  return (
    <Box data-testid="run-cell">
      {label}: {flagWords(run.state)} · version {run.version_number}
      {run.finished_at ? <> · finished <Ts at={run.finished_at} /></> : run.state === "RUNNING" ? " · running" : <> · finish time <Unknown /></>}
      {run.failure_reason ? <Box sx={{ color: m3.onSurfaceVar }}>{run.failure_reason}</Box> : null}
    </Box>
  );
}

/**
 * Zone matrix delivery step 4, Coverage tab (contract 7.1, 7.2, 5.9; ZM-26, ZM-34, ZM-35): per policy whether the stored
 * evaluation is current and complete, what it covers and what failed, and which policies are not evaluated at all.
 */
export function CoverageTab({ summary }: { readonly summary: ZoneSummaryHandle }) {
  useEnsureSummary(summary);
  const { data } = summary;
  const [inFlightPolicies, setInFlightPolicies] = useState<ReadonlySet<string>>(() => new Set());
  const [submissionNotice, setSubmissionNotice] = useState<string | null>(null);
  const [submissionError, setSubmissionError] = useState<readonly string[] | null>(null);

  const canTriggerRun = Boolean(data?.can?.evaluate) && data !== null && !data.masked && data.active_version !== null;

  const triggerRun = useCallback(async (policyId: string) => {
    setSubmissionNotice(null);
    setSubmissionError(null);
    setInFlightPolicies((prev) => {
      const next = new Set(prev);
      next.add(policyId);
      return next;
    });
    try {
      const res = await startZoneMatrixRun(policyId);
      setSubmissionNotice(
        res.already_running
          ? `Run ${res.run_id} is already running (${res.state}). Stored findings update only after the run finishes COMPLETE.`
          : `Run ${res.run_id} queued (${res.state}). Stored findings update only after the run finishes COMPLETE.`,
      );
      summary.reload();
    } catch (err: unknown) {
      setSubmissionError(zoneErrorLines(err));
    } finally {
      setInFlightPolicies((prev) => {
        const next = new Set(prev);
        next.delete(policyId);
        return next;
      });
    }
  }, [summary]);

  const columns: readonly DataColumn<SummaryPolicy>[] = useMemo(() => [
    {
      key: "policy", header: "Policy", width: 220, wrap: true,
      render: (p) => <Box><b>{p.policy_name}</b><Box sx={{ color: m3.onSurfaceVar }}>{p.vendor}{p.source_name ? ` · ${p.source_name}` : ""} · {p.rule_count} rules</Box></Box>,
    },
    {
      key: "staleness", header: "Evaluation currency", width: 280, wrap: true,
      render: (p) => (
        <Box data-testid="policy-staleness">
          <StatusChip tone={p.staleness.verdict === "CURRENT" ? "ok" : p.staleness.verdict === "STALE" ? "warn" : "unknown"}
            label={p.staleness.verdict === "CURRENT" || p.staleness.verdict === "STALE" ? p.staleness.verdict : "UNKNOWN"} />
          {p.staleness.reasons.map((r) => <Box key={r} data-testid="stale-reason">{stalenessReasonWords(r)}</Box>)}
        </Box>
      ),
    },
    {
      key: "age", header: "Snapshot age", width: 130,
      render: (p) => p.staleness.snapshot_age_known && p.staleness.snapshot_age_seconds !== null
        ? <span data-testid="snapshot-age">{ageText(p.staleness.snapshot_age_seconds)}</span>
        : <span data-testid="snapshot-age"><Unknown reason="The snapshot time is missing, unreadable or in the future" /></span>,
    },
    {
      key: "coverage", header: "Coverage", width: 320, wrap: true,
      render: (p) => (
        <Box data-testid="policy-coverage">
          <StatusChip tone={p.coverage === "COMPLETE" ? "ok" : p.coverage === "INCOMPLETE" ? "warn" : "unknown"}
            label={p.coverage === "COMPLETE" || p.coverage === "INCOMPLETE" ? p.coverage : "UNKNOWN"} />
          {p.coverage_items.length > 0
            ? <Box component="ul" sx={{ m: 0, pl: 2 }}>{p.coverage_items.map((c, i) => <li key={i}>{c}</li>)}</Box>
            : p.has_complete_run ? <Box sx={{ color: m3.onSurfaceVar }}>No coverage item is listed.</Box>
            : <Box sx={{ color: m3.onSurfaceVar }}>No complete run: coverage is not known.</Box>}
        </Box>
      ),
    },
    {
      key: "failures", header: "Collection failures", width: 130,
      render: (p) => p.collection_failures === null
        ? <span data-testid="collection-failures"><Unknown reason="The policy has no complete run, so the number of collection failures is not known" /></span>
        : <span data-testid="collection-failures">{p.collection_failures}</span>,
    },
    {
      key: "runs", header: "Runs", width: 330, wrap: true,
      render: (p) => {
        const busy = inFlightPolicies.has(p.policy_id) || p.latest_run?.state === "RUNNING";
        return (
          <Box>
            <RunCell run={p.latest_run} label="Latest run" />
            <RunCell run={p.latest_complete_run} label="Latest complete run" />
            {canTriggerRun ? (
              <Box
                sx={{ mt: 0.75 }}
                ref={(node: HTMLDivElement | null) => {
                  const btn = node?.querySelector("button");
                  if (btn) btn.setAttribute("data-testid", `run-policy-${p.policy_id}`);
                }}
              >
                <M3Button
                  emphasis="outlined"
                  disabled={busy}
                  onClick={() => { void triggerRun(p.policy_id); }}
                >
                  {busy ? "Running…" : "Run evaluation"}
                </M3Button>
              </Box>
            ) : null}
          </Box>
        );
      },
    },
  ], [canTriggerRun, inFlightPolicies, triggerRun]);

  const notEvaluatedColumns: readonly DataColumn<SummaryNotEvaluated>[] = useMemo(() => [
    { key: "vendor", header: "Vendor", width: 110, mono: true, render: (r) => r.vendor },
    { key: "name", header: "Source / policy", wrap: true, render: (r) => r.source_name ? `${r.name} (${r.source_name})` : r.name },
    { key: "reason", header: "Why it is not evaluated", width: 360, wrap: true, render: (r) => <StatusChip tone="unknown" label={r.reason} /> },
  ], []);

  const gate = summaryGate(summary, "Zone matrix coverage");
  if (gate !== null || data === null) return <Box sx={{ pt: 1.5 }}>{gate}</Box>;

  return (
    <Stack gap={2} sx={{ pt: 1.5 }} aria-busy={summary.loading}>
      <SummaryReadFailure summary={summary} />
      <Typography variant="body2" data-testid="summary-state-text" sx={{ color: m3.onSurfaceVar }}>{data.state_text}</Typography>
      <ThresholdNote threshold={data.staleness_threshold} />
      {data.headline ? <Typography variant="body2" data-testid="staleness-headline">{stalenessHeadlineText(data, data.headline)}</Typography> : null}
      {data.masked ? <Hint>Names are shown as the server masked them.</Hint> : null}

      <Box sx={panelSx}>
        <SectionTitle note="Every stored Check Point and Palo Alto policy. A policy that is stale or incomplete is never counted as clean.">
          Policies evaluated
        </SectionTitle>
        {submissionNotice !== null ? (
          <Alert severity="info" data-testid="zone-run-submission-notice">
            {submissionNotice}
          </Alert>
        ) : null}
        {submissionError !== null ? (
          <Alert severity="error" data-testid="zone-run-submission-error">
            {submissionError.map((line, index) => (
              <Typography key={index} variant="body2">{line}</Typography>
            ))}
          </Alert>
        ) : null}
        <DataTable
          ariaLabel="Policy coverage"
          columns={columns}
          rows={data.policies}
          rowKey={(p) => p.policy_id}
          emptyText="No stored Check Point or Palo Alto policy is listed. This is not a statement that nothing needs evaluating."
        />
      </Box>

      <Box sx={panelSx}>
        <SectionTitle note="Sources the zone matrix cannot evaluate. They are outside every count above, never counted as compliant.">
          Policies not evaluated ({data.not_evaluated.length})
        </SectionTitle>
        {data.not_evaluated.length === 0 && data.policies.length === 0 && data.state === "NO_ACTIVE_VERSION" ? (
          <StatePanel variant="empty" title={data.state_text} />
        ) : (
          <DataTable
            ariaLabel="Policies not evaluated"
            columns={notEvaluatedColumns}
            rows={data.not_evaluated}
            rowKey={(r) => r.id}
            emptyText="The response lists no policy that is not evaluated."
          />
        )}
      </Box>
    </Stack>
  );
}
