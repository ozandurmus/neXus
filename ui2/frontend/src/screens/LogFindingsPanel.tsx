import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import {
  acknowledgeLogFinding, getLogCoverage, getLogFindings, type LogCoverageAnswer, type LogFinding, type LogFindingChipData,
  type LogFindingsAnswer,
} from "../auth/adminApi";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { PLANE_OPERATIONS } from "../shell/actionAccess";
import { describeApiError } from "../shell/apiErrorText";
import { StatePanel } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { MONO, m3 } from "../theme/m3Theme";
import { LogFindingChip } from "./LogFindingChip";
import {
  countedFindings, coverageEvaluated, coverageLine, coverageWord, findingCounts, findingTitle, labelWord, severityTone, shadowNote,
} from "./logFindingsModel";
import { sourceLabel } from "./DeviceLogsPanel";

/**
 * Device > Logs > Findings (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 9, P1). Layer A only: severity by rule and the
 * hardware and kernel markers. Read-only apart from the acknowledgement, which the server decides (role, audit).
 *
 *  - Every figure says what it counts; the evidence is pointers (chunk, offset) and counts, never a log line: a line is
 *    read through the masked log viewer.
 *  - Coverage is part of the answer: a device with an unevaluated window is UNKNOWN there, never green.
 *  - While the shadow period runs the findings are shown on this screen only.
 */

/** The chip data this panel's own reads support: the server's chip wording, rebuilt from the two answers. */
export function chipFromAnswers(deviceId: string, findings: LogFindingsAnswer, coverage: LogCoverageAnswer): LogFindingChipData {
  const open = countedFindings(findings.findings.filter((f) => f.review_state === "OPEN"));
  const rank = (s: string) => (s === "CRITICAL" ? 2 : 1);
  const worst = open.reduce<LogFinding | null>((a, f) => (a === null || rank(f.severity) > rank(a.severity) ? f : a), null);
  const allEvaluated = coverage.summary.windows_total > 0 && coverage.summary.windows_evaluated === coverage.summary.windows_total;
  if (worst) {
    return { device_id: deviceId, state: "FINDINGS", severity: worst.severity, count: open.length, text: findingTitle(worst), coverage: coverage.summary };
  }
  return {
    device_id: deviceId,
    state: allEvaluated ? "NO_FINDINGS" : "UNKNOWN",
    severity: null,
    count: 0,
    text: allEvaluated ? "No findings" : "No findings in the evaluated windows",
    coverage: coverage.summary,
  };
}

function FindingCard({ finding, onAcknowledged }: { readonly finding: LogFinding; readonly onAcknowledged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const acknowledge = () => {
    setBusy(true);
    setError(null);
    acknowledgeLogFinding(finding.finding_id).then(onAcknowledged, (err) => setError(describeApiError(err))).finally(() => setBusy(false));
  };
  return (
    <Box data-finding={finding.finding_id} data-severity={finding.severity} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 1.5 }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <StatusChip tone={severityTone(finding.severity)} label={finding.severity} dense title={`Severity by rule ${finding.severity_rule}`} />
        <Typography sx={{ fontFamily: MONO, fontWeight: 650, fontSize: 13.5 }}>{findingTitle(finding)}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{sourceLabel(finding.source_key)}</Typography>
        {finding.review_state !== "OPEN" && <StatusChip tone="neutral" label={finding.review_state} dense />}
      </Stack>
      <Typography variant="body2" sx={{ mt: 0.75 }}>{findingCounts(finding)}</Typography>
      <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar, mt: 0.5 }}>
        {`S ${finding.score.s} (severity weight, ${finding.provenance.toLowerCase()}) · rarity and change arrive with layer B · coverage: ${coverageWord(finding.coverage_state)}`}
      </Typography>
      {finding.labels.length > 0 && (
        <Stack direction="row" spacing={0.75} sx={{ mt: 0.75 }} flexWrap="wrap" useFlexGap>
          {finding.labels.map((l) => <StatusChip key={l} tone="neutral" label={labelWord(l)} dense />)}
        </Stack>
      )}
      <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar, mt: 0.5, fontFamily: MONO }}>
        {finding.evidence_pointers.length === 0
          ? "evidence: none recorded"
          : `evidence: ${finding.evidence_pointers.length} pointer${finding.evidence_pointers.length === 1 ? "" : "s"} (chunk ${finding.evidence_pointers[0].chunk_id}, offset ${finding.evidence_pointers[0].offset}); read the lines in the masked viewer`}
      </Typography>
      {finding.review_state === "OPEN" && finding.severity !== "INFO" && (
        <Box sx={{ mt: 1 }}>
          <M3Button emphasis="outlined" plane={PLANE_OPERATIONS} onClick={acknowledge} disabled={busy}>Acknowledge</M3Button>
          {error && <Typography role="alert" variant="caption" sx={{ ml: 1, color: m3.error }}>{error}</Typography>}
        </Box>
      )}
    </Box>
  );
}

export function LogFindingsPanel({ deviceId }: { readonly deviceId: string }) {
  const findings = useFetchOnMount(() => getLogFindings(deviceId), describeApiError);
  const coverage = useFetchOnMount(() => getLogCoverage(deviceId), describeApiError);

  if (findings.error || coverage.error) {
    // A failed read is UNKNOWN, never "no findings".
    return (
      <Box data-testid="log-findings-panel">
        <StatePanel variant="unknown" title="Log findings: UNKNOWN" body={`The findings or their coverage could not be read (${findings.error ?? coverage.error}).`} />
      </Box>
    );
  }
  if (!findings.data || !coverage.data) {
    return <Typography data-testid="log-findings-panel" variant="body2" sx={{ color: m3.onSurfaceVar }}>Reading log findings…</Typography>;
  }
  const f = findings.data;
  const c = coverage.data;
  const chip = chipFromAnswers(deviceId, f, c);
  const shadow = shadowNote(f.shadow, f.shadow_until);
  return (
    <Stack spacing={1.5} data-testid="log-findings-panel">
      <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 650 }}>Log findings</Typography>
        <LogFindingChip chip={chip} />
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          {`layer A: severity and hardware markers · last ${f.days} days · ${f.time_basis === "DEVICE_LOCAL" ? "device-local times" : f.time_basis}`}
        </Typography>
      </Stack>
      {!f.enabled && (
        <StatePanel variant="unknown" title="Log detection is switched off" body="No chunk is analysed while the switch is off; this device shows no findings because nothing was evaluated, not because it is healthy." />
      )}
      {shadow && <Typography data-testid="log-findings-shadow" variant="body2" sx={{ color: m3.onSurfaceVar }}>{shadow}</Typography>}
      <Box data-testid="log-findings-coverage">
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{`Coverage: ${coverageLine(c.summary)}`}</Typography>
        <Stack direction="row" spacing={0.75} sx={{ mt: 0.5 }} flexWrap="wrap" useFlexGap>
          {c.sources.map((s) => (
            <StatusChip
              key={s.source_key}
              tone={s.windows_evaluated === s.windows_total ? "ok" : "unknown"}
              label={`${sourceLabel(s.source_key)} ${s.windows_evaluated}/${s.windows_total}`}
              dense
              title={Object.entries(s.by_state).map(([k, n]) => `${n} ${k} (${coverageEvaluated(k) ? "evaluated" : "UNKNOWN"})`).join(", ")}
            />
          ))}
        </Stack>
      </Box>
      {f.findings.length === 0 ? (
        <Typography variant="body2" data-testid="log-findings-empty">
          {chip.state === "NO_FINDINGS"
            ? `No findings; ${coverageLine(c.summary)}.`
            : `No findings in the evaluated windows; ${coverageLine(c.summary)}. This is not a statement of health.`}
        </Typography>
      ) : (
        <Stack spacing={1}>
          {f.findings.map((finding) => (
            <FindingCard key={finding.finding_id} finding={finding} onAcknowledged={findings.refresh} />
          ))}
          {f.truncated && <Typography variant="caption">list truncated: narrow with the CLI (log-findings)</Typography>}
        </Stack>
      )}
    </Stack>
  );
}
