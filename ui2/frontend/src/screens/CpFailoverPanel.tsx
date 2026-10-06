import { useCallback, useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import Step from "@mui/material/Step";
import StepLabel from "@mui/material/StepLabel";
import Stepper from "@mui/material/Stepper";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { relativeAge } from "../shell/time";
import { approveCpFailover, getCpFailoverRun, listCpFailoverApprovals, listCpFailoverRuns, listCpFailoverUnits,
  secondApproveCpFailover, startCpFailover, runCpFailoverReadiness,
  type CpFailoverApproval, type CpFailoverRunDetail, type CpFailoverState, type CpFailoverUnit } from "../auth/adminApi";
import { ReadinessCard, ReadinessChecksTable } from "./ReadinessChecksTable";
const labels = ["Checking readiness", "Failing over", "Role change confirmed", "Checking traffic and cluster after switch",
  "Returning former active to standby", "Verifying final cluster health", "Failover verified"];
const activeStates: CpFailoverState[] = ["PLANNED", "PRECHECK", "FAILING_OVER", "SWITCHED", "POSTCHECK", "RETURNING"];
export function runStep(state: CpFailoverState, step: string): number {
  const current = state === "STOPPED" ? step : state;
  if (current === "FINAL_POSTCHECK" || current === "POST_RETURN" || current === "POSTCHECK" && ["FINAL_POSTCHECK", "POST_RETURN", "final"].includes(step)) return 5;
  return ({ PLANNED: 0, PRECHECK: 0, FAILING_OVER: 1, SWITCHED: 2, POSTCHECK: 3, RETURNING: 4, DONE: 6 } as Record<string, number>)[current] ?? 0;
}
const errorText = (error: unknown) => (error as { body?: { code?: string } })?.body?.code ?? "Request failed";
type Draft = { requestId: string; from: string; until: string; reason: string; confirmedRevision?: number };
export function FailoverRun({ run, unit }: { run: CpFailoverRunDetail; unit: CpFailoverUnit }) {
  const stopped = run.state === "STOPPED", step = runStep(run.state, run.step);
  const failed = run.checks.filter(c => c.blocking && ["FAIL", "UNKNOWN"].includes(c.status));
  const roles = Object.entries(run.lastConfirmedRoles ?? {});
  return <Stack spacing={1.5} aria-label="Failover run">
    <Typography role="status">{stopped ? run.mutationPossible === false ? "Stopped before failover"
      : run.mutationPossible === true ? "Stopped after mutation — intervention required"
      : "Stopped — mutation status unknown; intervention required" : run.state === "PLANNED" ? "Queued" : labels[step]}</Typography>
    <Typography variant="caption">Run: {run.runId} · Phase: {run.step}</Typography>
    <Stepper activeStep={step} alternativeLabel>{labels.map((label, index) => <Step key={label} completed={run.state === "DONE" || index < step}>
      <StepLabel error={stopped && index === step}>{label}</StepLabel></Step>)}</Stepper>
    {(["pre", "post", "final"] as const).map(phase => <Box key={phase}>
      <Typography variant="subtitle2">{phase === "pre" ? "Pre-checks" : phase === "post" ? "Post-switch checks" : "Final post-checks"}</Typography>
      {run.checks.some(c => c.phase === phase) ? <ReadinessChecksTable checks={run.checks.filter(c => c.phase === phase)} members={unit.members} />
        : <Typography variant="body2">Not evaluated</Typography>}
      {phase !== "pre" && <Typography variant="body2">Session continuity: not evaluated</Typography>}
    </Box>)}
    <Typography variant="body2">Last confirmed roles: {roles.length ? roles.map(([id, role]) =>
      `${unit.members?.find(m => m.device_id === id)?.hostname ?? "Member"}: ${role}`).join(" · ") : "Not evaluated"}</Typography>
    {stopped && <><Typography>Failed/unknown check: {failed.map(c => `${c.title} · ${c.status}`).join("; ") || run.failedCheck || "Not evaluated"}</Typography>
      <Typography>Refusal: {run.message || "Unknown"} · Incident reference: {run.incidentRef ?? "Not recorded"}</Typography></>}
    {run.state === "DONE" && <Typography>Evidence coverage: {run.checks.filter(c => c.blocking && c.status === "PASS").length} / {run.checks.filter(c => c.blocking).length} recorded required results passed. Final post-checks: {run.checks.some(c => c.phase === "final") ? "Recorded" : "Not evaluated"}.</Typography>}
  </Stack>;
}
function UnitPanel({ unit, expanded, onExpand, onRefreshUnits }: { unit: CpFailoverUnit; expanded: boolean; onExpand: () => void; onRefreshUnits: () => void }) {
  const label = unit.maskedName ?? unit.virtual_system ?? unit.cluster_member_ref;
  const storageKey = `failover-request:${unit.vendor ?? "check_point"}:${unit.unitId}`;
  const [draft, setDraft] = useState<Draft | null>(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey) ?? "null") as Draft | null; } catch { return null; }
  });
  const [approvals, setApprovals] = useState<CpFailoverApproval[]>([]);
  const [run, setRun] = useState<CpFailoverRunDetail | null>(null);
  const [readinessRun, setReadinessRun] = useState<CpFailoverRunDetail | null>(null);
  const [dialog, setDialog] = useState(false), [until, setUntil] = useState("");
  const [reason, setReason] = useState("Manual failover verification"), [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false), [loaded, setLoaded] = useState(false);
  const lock = useRef(false), attempted = useRef<string | null>(null);
  const lastReadiness = useRef<string | null>(null);
  const remember = (value: Draft) => { sessionStorage.setItem(storageKey, JSON.stringify(value)); setDraft(value); };
  const refresh = useCallback(async () => {
    try {
      const [windows, runs] = await Promise.all([listCpFailoverApprovals(unit), listCpFailoverRuns(unit)]);
      const failover = runs.find(item => item.kind !== "READINESS" && activeStates.includes(item.state)) ?? runs.find(item => item.kind !== "READINESS");
      const readiness = runs.find(item => item.kind === "READINESS");
      const [detail, checks] = await Promise.all([failover ? getCpFailoverRun(failover.runId, unit.vendor) : null,
        readiness ? getCpFailoverRun(readiness.runId, unit.vendor) : null]);
      setApprovals(windows); setRun(detail); setReadinessRun(checks); setLoaded(true);
      if (checks && !activeStates.includes(checks.state) && lastReadiness.current !== checks.runId) {
        lastReadiness.current = checks.runId; onRefreshUnits();
      }
    } catch (e) { setLoaded(false); setError(errorText(e)); }
  }, [unit.unitId, unit.clusterId, unit.vendor, onRefreshUnits]);
  useEffect(() => { if (expanded) void refresh(); }, [expanded, refresh]);
  const pending = approvals.find(a => !a.revokedAt && Date.parse(a.windowUntil) > Date.now() && a.policy === "OPERATION_ADMIN_TWO_PERSON" && !a.approved);
  const bound = approvals.find(a => a.requestId === draft?.requestId && !a.revokedAt && Date.parse(a.windowUntil) > Date.now());
  const active = Boolean(run && activeStates.includes(run.state));
  const readinessActive = Boolean(readinessRun && activeStates.includes(readinessRun.state));
  useEffect(() => {
    if (!expanded || (!active && !pending && !readinessActive)) return;
    const timer = window.setInterval(() => { void refresh(); }, 3000);
    return () => window.clearInterval(timer);
  }, [expanded, active, pending, readinessActive, refresh]);
  const act = async (action: () => Promise<unknown>) => {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError(null);
    try { await action(); setDialog(false); await refresh(); }
    catch (e) { setError(errorText(e)); }
    finally { lock.current = false; setBusy(false); }
  };
  const start = async (approval: CpFailoverApproval) => {
    if (!approval.requestId || !approval.revision || !approval.executionNonce) throw new Error("Missing request binding");
    const result = await startCpFailover(unit, { requestId: approval.requestId, revision: approval.revision, executionNonce: approval.executionNonce, warningConfirmed: true });
    setRun(await getCpFailoverRun(result.runId, unit.vendor));
  };
  // The initiator's one warning confirmation survives refresh; second approval adds no new confirmation.
  useEffect(() => {
    if (expanded && !unit.masked && unit.canStart && loaded && bound?.approved && bound.canStart && draft?.confirmedRevision === bound.revision
      && (!run || run.approvalId !== bound.approvalId) && !active && attempted.current !== bound.requestId) {
      attempted.current = bound.requestId!; void act(() => start(bound));
    }
  }, [expanded, bound, draft, loaded, active, run, unit.masked, unit.canStart]);
  const idle = loaded && !busy && !active && !(readinessRun && activeStates.includes(readinessRun.state));
  const canOperate = unit.masked !== true && unit.canStart;
  const confirm = () => act(async () => {
    const request = draft ?? { requestId: crypto.randomUUID(), from: new Date().toISOString(), until: new Date(until).toISOString(), reason: reason.trim() };
    remember(request);
    const approval = bound ?? await approveCpFailover(unit, request.from, request.until, request.reason, request.requestId);
    if (!approval.requestId || !approval.revision) throw new Error("Missing request revision");
    remember({ ...request, confirmedRevision: approval.revision });
    if (approval.approved) { attempted.current = approval.requestId; await start(approval); }
  });
  return <Card sx={{ p: 2, ml: unit.unitId === unit.clusterId ? 0 : 3, bgcolor: m3.scLow }}>
    <M3Button emphasis="text" onClick={onExpand}>{unit.unitId === unit.clusterId ? label : `Virtual System · ${label}`}</M3Button>
    <Typography variant="body2">{unit.vendor === "palo_alto" ? "Palo Alto" : "Check Point"} · Mode: {unit.mode ?? "UNKNOWN"} · Readiness age: {unit.readinessObservedAt ? relativeAge(unit.readinessObservedAt, new Date()) : "Not evaluated"}</Typography>
    {unit.refusalReason && <Typography color="error">{unit.refusalReason}</Typography>}
    {expanded && <Stack spacing={2} sx={{ mt: 1 }}>
      {pending && <Typography role="status">Awaiting second approval</Typography>}
      {canOperate && <M3Button emphasis="filled" disabled={!idle || Boolean(unit.refusalReason) || Boolean(pending)} onClick={() => {
        if (draft?.confirmedRevision && bound?.approved && (!run || run.approvalId !== bound.approvalId)) { void act(() => start(bound)); return; }
        if (draft && (run?.approvalId === draft.requestId || Date.parse(draft.until) <= Date.now()
          || approvals.some(a => a.requestId === draft.requestId && a.revokedAt))) {
          sessionStorage.removeItem(storageKey); setDraft(null);
        }
        setDialog(true);
      }}>Start failover</M3Button>}
      {unit.masked !== true && unit.canApprove && pending?.canApprove && <M3Button emphasis="outlined" disabled={!idle}
        onClick={() => void act(() => secondApproveCpFailover(unit, pending.requestId!, pending.revision!))}>Approve request revision {pending.revision}</M3Button>}
      <ReadinessCard checks={readinessRun?.checks.filter(c => c.phase === "pre") ?? []} members={unit.members} cluster={label}
        vendor={unit.vendor} status={readinessRun?.outcome} stopCode={readinessRun?.message}
        observedAt={readinessRun?.checks.map(c => c.observedAt).sort().at(-1)} masked={unit.masked === true}
        running={Boolean(readinessRun && activeStates.includes(readinessRun.state))} disabled={!idle} canRun={canOperate}
        onRun={() => void act(() => runCpFailoverReadiness(unit))} />
      {run && <FailoverRun run={run} unit={unit} />}
      {error && <Typography role="alert" color="error">{error}</Typography>}
    </Stack>}
    <Dialog open={dialog} onClose={() => { if (!busy) setDialog(false); }}><DialogTitle>Start manual failover</DialogTitle>
      <DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
        <Typography>Target: {label}</Typography>
        <Typography>Intended role swap: current active becomes DOWN/suspended; current standby/passive becomes active. The former active is then returned to standby/passive after successful checks.</Typography>
        <Typography>Traffic may be interrupted. The run may stop with one member DOWN. Intervention may be required.</Typography>
        <Typography>OK / Start confirms this request revision and the complete workflow. No later confirmation is required.</Typography>
        <TextField label="Window ends" type="datetime-local" InputLabelProps={{ shrink: true }} value={draft?.until.slice(0,16) ?? until} disabled={Boolean(draft)} onChange={e => setUntil(e.target.value)} />
        <TextField label="Reason" value={draft?.reason ?? reason} disabled={Boolean(draft)} onChange={e => setReason(e.target.value)} />
        {error && <Typography role="alert" color="error">{error}</Typography>}
      </Stack></DialogContent>
      <DialogActions><M3Button emphasis="text" disabled={busy} onClick={() => setDialog(false)}>Cancel</M3Button>
        <M3Button emphasis="filled" disabled={busy || !reason.trim() || (!draft && (!until || Date.parse(until) <= Date.now()))} onClick={() => void confirm()}>OK / Start</M3Button></DialogActions>
    </Dialog>
  </Card>;
}
export function CpFailoverPanel({ memberDeviceId, vendor = "check_point", initialUnitId }: { memberDeviceId: string; vendor?: "check_point" | "palo_alto"; initialUnitId?: string | null }) {
  const [units, setUnits] = useState<CpFailoverUnit[]>([]), [selected, setSelected] = useState<string | null>(initialUnitId ?? null);
  const [error, setError] = useState<string | null>(null);
  const refreshUnits = useCallback(() => {
    void listCpFailoverUnits(memberDeviceId,vendor).then(value => setUnits(value.map(unit => ({ ...unit, members: unit.maskedMembers?.map(member => ({ ...member, hostname: member.maskedLabel })) ?? unit.members }))))
      .catch(e => setError(errorText(e)));
  }, [memberDeviceId,vendor]);
  useEffect(() => {
    let disposed = false;
    setUnits([]); setError(null); setSelected(initialUnitId ?? null);
    listCpFailoverUnits(memberDeviceId, vendor).then(value => { if (!disposed) setUnits(value.map(unit => ({ ...unit, members: unit.maskedMembers?.map(member => ({ ...member, hostname: member.maskedLabel })) ?? unit.members }))); }).catch(e => { if (!disposed) setError(errorText(e)); });
    return () => { disposed = true; };
  }, [memberDeviceId, vendor, initialUnitId]);
  return <Stack spacing={1} aria-label={`${vendor === "palo_alto" ? "Palo Alto" : "Check Point"} failover`}>
    {error && <Typography role="alert" color="error">{error}</Typography>}
    {!error && units.length === 0 && <Typography>No eligible failover units</Typography>}
    {units.map(unit => <UnitPanel key={`${vendor}:${unit.unitId}`} unit={unit} expanded={selected === unit.unitId} onRefreshUnits={refreshUnits} onExpand={() => setSelected(selected === unit.unitId ? null : unit.unitId)} />)}
  </Stack>;
}
