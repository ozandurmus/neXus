import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import Step from "@mui/material/Step";
import StepLabel from "@mui/material/StepLabel";
import Stepper from "@mui/material/Stepper";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { approveCpFailover, getCpFailoverRun, listCpFailoverApprovals, listCpFailoverRuns, listCpFailoverUnits, revokeCpFailover, startCpFailover,
  runCpFailoverReadiness, type CpFailoverApproval, type CpFailoverRunDetail, type CpFailoverState, type CpFailoverUnit } from "../auth/adminApi";

import { ReadinessChecksTable } from "./ReadinessChecksTable";

const labels = ["Preparing", "Failing over", "Switched", "Checking", "No problems found"];
const cpChecks: Record<number, string> = { 1: "Cluster state", 2: "Cluster IP table", 3: "Cluster interfaces", 5: "ARP", 6: "Connections", 8: "Traffic rate", 9: "State synchronization", 10: "Installed policy parity", 11: "Critical devices", 12: "Bond interfaces", 13: "Last failover", 14: "Routing" };
const panChecks: Record<number, string> = { 1: "HA mode and roles", 2: "Peer relationship", 3: "HA links", 4: "Configuration sync", 5: "Session synchronization", 6: "Sessions carried", 7: "Version parity" };
const activeStates: CpFailoverState[] = ["PLANNED", "PRECHECK", "FAILING_OVER", "SWITCHED", "POSTCHECK", "RETURNING"];

export function runStep(state: CpFailoverState, step: string): number {
  const current = state === "STOPPED" ? step : state;
  if (state === "STOPPED" && current === "PLANNED") return 0;
  return ({ PLANNED: -1, PRECHECK: 0, FAILING_OVER: 1, SWITCHED: 2, POSTCHECK: 3, RETURNING: 3, DONE: 4 } as Record<string, number>)[current] ?? -1;
}

const local = (value: string) => new Date(value).toLocaleString();
const localInput = (time: number) => {
  const date = new Date(time);
  return new Date(time - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
};
const errorText = (error: unknown) => {
  const body = (error as { body?: { code?: string } })?.body;
  return body?.code ?? "Request failed";
};

function UnitPanel({ unit, expanded, onExpand }: { unit: CpFailoverUnit; expanded: boolean; onExpand: () => void }) {
  const label = unit.virtual_system ?? unit.cluster_member_ref;
  const checks = unit.vendor === "palo_alto" ? panChecks : cpChecks;
  const [approvals, setApprovals] = useState<CpFailoverApproval[]>([]);
  const [run, setRun] = useState<CpFailoverRunDetail | null>(null);
  const [readinessRun, setReadinessRun] = useState<CpFailoverRunDetail | null>(null);
  const [dialog, setDialog] = useState<"approve" | "schedule" | null>(null);
  const [from, setFrom] = useState("");
  const [until, setUntil] = useState("");
  const [reason, setReason] = useState("");
  const [scheduled, setScheduled] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [windows, runs] = await Promise.all([listCpFailoverApprovals(unit), listCpFailoverRuns(unit)]);
      setApprovals(windows);
      const failover = runs.find(item => item.kind !== "READINESS");
      const readiness = runs.find(item => item.kind === "READINESS");
      const [failoverDetail, readinessDetail] = await Promise.all([
        failover ? getCpFailoverRun(failover.runId, unit.vendor) : null,
        readiness ? getCpFailoverRun(readiness.runId, unit.vendor) : null,
      ]);
      setRun(failoverDetail);
      setReadinessRun(readinessDetail);
      setError(null);
    } catch (e) { setError(errorText(e)); }
  }, [unit]);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    if ((!run || !activeStates.includes(run.state)) && (!readinessRun || !activeStates.includes(readinessRun.state))) return;
    const timer = window.setInterval(() => { void refresh(); }, 3000);
    return () => window.clearInterval(timer);
  }, [run?.state, readinessRun?.state, refresh]);

  const now = Date.now();
  const windowNow = approvals.find(a => !a.revokedAt && Date.parse(a.windowFrom) <= now && now < Date.parse(a.windowUntil));
  const scheduleWindow = windowNow ?? approvals.filter(a => !a.revokedAt && Date.parse(a.windowUntil) > now)
    .sort((a, b) => a.windowFrom.localeCompare(b.windowFrom))[0];
  const idle = !busy && (!run || !activeStates.includes(run.state)) && (!readinessRun || !activeStates.includes(readinessRun.state));
  const canStart = unit.canStart && Boolean(windowNow) && idle;
  const canSchedule = unit.canSchedule && Boolean(scheduleWindow) && idle;
  const act = async (action: () => Promise<unknown>) => {
    setBusy(true); setError(null);
    try { await action(); setDialog(null); await refresh(); }
    catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  };
  const step = run ? runStep(run.state, run.step) : -1;
  const stopped = run?.state === "STOPPED";
  const checkRows = [...new Set((run?.checks ?? []).map(c => c.checkNo))].sort((a, b) => a - b);
  const phaseStatus = (no: number, phase: "pre" | "post") => {
    const statuses = run?.checks.filter(c => c.checkNo === no && c.phase === phase).map(c => c.status) ?? [];
    return statuses.includes("FAIL") ? "FAIL" : statuses.includes("UNKNOWN") ? "UNKNOWN" : statuses.includes("WARN") ? "WARN" : statuses.length ? "PASS" : "—";
  };
  return <Card sx={{ p: 2, ml: unit.unitId === unit.clusterId ? 0 : 3, bgcolor: m3.scLow }}>
    <M3Button emphasis="text" onClick={onExpand}>{unit.unitId === unit.clusterId ? label : `Virtual System · ${label}`}</M3Button>
    {expanded && <Stack spacing={2} sx={{ mt: 1 }}>
      <Box>
        <Typography variant="subtitle1">Approval window</Typography>
        {approvals.length ? approvals.map(a => <Box key={a.approvalId} sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap", mb: 0.5 }}>
          <Typography variant="body2">{label} · {local(a.windowFrom)} – {local(a.windowUntil)} · {a.reason} · Approved by {a.approvedBy}</Typography>
          <StatusChip label={a.revokedAt ? "Revoked" : Date.parse(a.windowUntil) <= now ? "Expired" : "Approved"} tone={a.revokedAt ? "neutral" : "ok"} dense />
          {unit.canApprove && !a.revokedAt && <M3Button emphasis="text" onClick={() => void act(() => revokeCpFailover(a.approvalId, unit.vendor))}>Revoke</M3Button>}
        </Box>) : <Typography variant="body2">No approval window</Typography>}
        {unit.canApprove && <M3Button emphasis="outlined" onClick={() => setDialog("approve")}>Approve a window</M3Button>}
      </Box>
      <Box>
        <Typography variant="subtitle1">Failover</Typography>
        <Typography variant="body2">{windowNow ? `Approved until ${local(windowNow.windowUntil)}` : scheduleWindow ? `Approved from ${local(scheduleWindow.windowFrom)}` : "No approval — failover not possible"}</Typography>
        {unit.canStart && <M3Button emphasis="outlined" disabled={!idle} onClick={() => void act(() => runCpFailoverReadiness(unit))}>Run pre-checks</M3Button>}
        {unit.canStart && <M3Button emphasis="filled" disabled={!canStart} onClick={() => void act(() => startCpFailover(unit, null))}>Failover now</M3Button>}
        {unit.canSchedule && <M3Button emphasis="outlined" disabled={!canSchedule} onClick={() => setDialog("schedule")}>Schedule</M3Button>}
      </Box>
      {readinessRun && <Box aria-label="Readiness checks">
        <Typography variant="subtitle1">Readiness: {activeStates.includes(readinessRun.state) ? "Running" : readinessRun.outcome ?? "UNKNOWN"}</Typography>
        <Typography variant="body2">{readinessRun.checks.length
          ? `Observed ${Math.max(0, Math.floor((now - Math.max(...readinessRun.checks.map(check => Date.parse(check.observedAt)))) / 60_000))} min ago`
          : "No observations yet"}</Typography>
        <ReadinessChecksTable checks={readinessRun.checks.filter(check => check.phase === "pre")} />
      </Box>}
      {run && <Box aria-label="Failover run">
        <Stepper activeStep={Math.max(step, 0)} alternativeLabel>
          {labels.map((label, index) => <Step key={label} completed={run.state === "DONE" || index < step}>
            <StepLabel error={stopped && index === step} icon={index === step && run.state !== "PLANNED" && !stopped && activeStates.includes(run.state) ? <CircularProgress size={24} /> : undefined}>
              {stopped && index === step ? run.failedCheck ? `Stopped: ${checks[Number(run.failedCheck)] ?? "check unavailable"}` : "Stopped" : label}
            </StepLabel>
          </Step>)}
        </Stepper>
        <Table size="small" aria-label="Failover checks"><TableHead><TableRow><TableCell>Check</TableCell><TableCell>Before</TableCell><TableCell>After</TableCell><TableCell>Result</TableCell></TableRow></TableHead>
          <TableBody>{checkRows.map(no => {
            const pre = phaseStatus(no, "pre");
            const post = phaseStatus(no, "post");
            return <TableRow key={no}><TableCell>{checks[no] ?? "Check"}</TableCell><TableCell>{pre}</TableCell><TableCell>{post}</TableCell><TableCell>{post === "—" ? pre : post}</TableCell></TableRow>;
          })}</TableBody>
        </Table>
      </Box>}
      {error && <Typography color="error">{error}</Typography>}
    </Stack>}
    <Dialog open={dialog !== null} onClose={() => setDialog(null)}><DialogTitle>{dialog === "approve" ? "Approve a window" : "Schedule failover"}</DialogTitle>
      <DialogContent><Stack spacing={2} sx={{ pt: 1, minWidth: 300 }}>
        {dialog === "approve" ? <>
          <Typography>{label}</Typography>
          <TextField label="From" type="datetime-local" InputLabelProps={{ shrink: true }} value={from} onChange={e => setFrom(e.target.value)} />
          <TextField label="Until" type="datetime-local" InputLabelProps={{ shrink: true }} value={until} onChange={e => setUntil(e.target.value)} />
          <TextField label="Reason" value={reason} onChange={e => setReason(e.target.value)} />
        </> : <TextField label="Scheduled time" type="datetime-local" InputLabelProps={{ shrink: true }} value={scheduled} onChange={e => setScheduled(e.target.value)} inputProps={{ min: scheduleWindow ? localInput(Math.max(now, Date.parse(scheduleWindow.windowFrom))) : undefined, max: scheduleWindow ? localInput(Date.parse(scheduleWindow.windowUntil)) : undefined }} />}
        {error && <Typography color="error">{error}</Typography>}
      </Stack></DialogContent>
      <DialogActions><M3Button emphasis="text" onClick={() => setDialog(null)}>Cancel</M3Button>
        <M3Button emphasis="filled" disabled={busy || (dialog === "approve" ? !from || !until || !reason.trim() || Date.parse(from) >= Date.parse(until) : !scheduleWindow || !scheduled || Date.parse(scheduled) < Math.max(now, Date.parse(scheduleWindow.windowFrom)) || Date.parse(scheduled) >= Date.parse(scheduleWindow.windowUntil))}
          onClick={() => void act(() => dialog === "approve" ? approveCpFailover(unit, new Date(from).toISOString(), new Date(until).toISOString(), reason.trim()) : startCpFailover(unit, new Date(scheduled).toISOString()))}>Confirm</M3Button>
      </DialogActions>
    </Dialog>
  </Card>;
}

export function CpFailoverPanel({ memberDeviceId, vendor = "check_point" }: { memberDeviceId: string; vendor?: "check_point" | "palo_alto" }) {
  const [units, setUnits] = useState<CpFailoverUnit[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { listCpFailoverUnits(memberDeviceId, vendor).then(setUnits).catch(e => setError(errorText(e))); }, [memberDeviceId, vendor]);
  return <Stack spacing={1} aria-label={`${vendor === "palo_alto" ? "Palo Alto" : "Check Point"} failover`}>
    {error && <Typography color="error">{error}</Typography>}
    {!error && units.length === 0 && <Typography>No eligible failover units</Typography>}
    {units.map(unit => <UnitPanel key={unit.unitId} unit={unit} expanded={selected === unit.unitId} onExpand={() => setSelected(selected === unit.unitId ? null : unit.unitId)} />)}
  </Stack>;
}
