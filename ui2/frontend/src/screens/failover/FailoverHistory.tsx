import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { getCpFailoverRun, type CpFailoverRun, type CpFailoverRunDetail, type CpFailoverUnit } from "../../auth/adminApi";
import { jobOutcomeWord, readinessReasonLabel, requestFailure, statusWord } from "../../shell/presenters";
import { m3 } from "../../theme/m3Theme";
import { formatStamp } from "../../shell/time";
import { FailoverRunView } from "./FailoverRunView";

const PAGE = 10;
/** The run's end in words: the outcome when the server recorded one, else its state; a stop code is shown as its label. */
export function runOutcomeWord(run: CpFailoverRun): string {
  const word = jobOutcomeWord(run.outcome);
  if (word) return word;
  if (run.message && run.state === "STOPPED") return readinessReasonLabel(run.message);
  return statusWord(run.state);
}

/**
 * Earlier runs of one unit (REDESIGN section 4): newest first, ten rows, "Show all". Pre-check (READINESS) runs are behind a
 * toggle. "Open run" reads that run's detail and shows it under the row; it changes nothing.
 */
export function FailoverHistory({ runs, unit }: { runs: readonly CpFailoverRun[]; unit: CpFailoverUnit }) {
  const [showPreChecks, setShowPreChecks] = useState(false), [showAll, setShowAll] = useState(false);
  const [opened, setOpened] = useState<{ runId: string; detail?: CpFailoverRunDetail; error?: string } | null>(null);
  const failoverRuns = runs.filter(run => run.kind !== "READINESS");
  const preCheckRuns = runs.length - failoverRuns.length;
  const listed = (showPreChecks ? runs : failoverRuns), shown = showAll ? listed : listed.slice(0, PAGE);
  const open = async (runId: string) => {
    if (opened?.runId === runId) { setOpened(null); return; }
    setOpened({ runId });
    try { setOpened({ runId, detail: await getCpFailoverRun(runId, unit.vendor) }); }
    catch (e) { setOpened({ runId, error: requestFailure(e as Parameters<typeof requestFailure>[0]) }); }
  };
  return <Box component="section" aria-label="History" data-failover-history>
    <Typography variant="subtitle2">History</Typography>
    {listed.length === 0 && <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>No earlier failover runs of this unit.</Typography>}
    {shown.map(run => <Box key={run.runId} data-history-run={run.runId} sx={{ py: 0.5, borderTop: `1px solid ${m3.outlineVar}` }}>
      <Box sx={{ display: "flex", gap: 1.5, alignItems: "baseline", flexWrap: "wrap" }}>
        <Typography variant="body2" component="time">{run.scheduledFor ? formatStamp(run.scheduledFor) : "Time not recorded"}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{run.kind === "READINESS" ? "Pre-check run" : "Failover"}</Typography>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{runOutcomeWord(run)}</Typography>
        <Button size="small" aria-expanded={opened?.runId === run.runId} onClick={() => void open(run.runId)} sx={{ textTransform: "none", ml: "auto" }}>Open run</Button>
      </Box>
      {opened?.runId === run.runId && <Box sx={{ mt: 1 }}>
        {opened.error ? <Typography role="alert" color="error">{opened.error}</Typography>
          : opened.detail ? <FailoverRunView run={opened.detail} unit={unit} resumeActions={false} /> : <Typography variant="body2">Reading the run…</Typography>}
      </Box>}
    </Box>)}
    <Box sx={{ display: "flex", gap: 1 }}>
      {listed.length > PAGE && !showAll && <Button size="small" onClick={() => setShowAll(true)} sx={{ textTransform: "none" }}>Show all ({listed.length})</Button>}
      {preCheckRuns > 0 && <Button size="small" aria-pressed={showPreChecks} onClick={() => setShowPreChecks(value => !value)} sx={{ textTransform: "none" }}>
        {showPreChecks ? "Hide pre-check runs" : `Show pre-check runs (${preCheckRuns})`}</Button>}
    </Box>
  </Box>;
}
