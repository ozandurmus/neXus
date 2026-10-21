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
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { relativeAge } from "../shell/time";
import { approveCpFailover, approveCpFailoverResume, getCpFailoverRun, listCpFailoverApprovals, listCpFailoverRuns, listCpFailoverUnits,
  resumeCpFailoverReturn, secondApproveCpFailover, startCpFailover, runCpFailoverReadiness,
  type CpFailoverApproval, type CpFailoverResumeState, type CpFailoverRunDetail, type CpFailoverState, type CpFailoverUnit } from "../auth/adminApi";
import { ReadinessCard, ReadinessChecksTable } from "./ReadinessChecksTable";
import { FailoverIncidentsPanel } from "./FailoverIncidentsPanel";
const labels = ["Checking readiness", "Failing over", "Role change confirmed", "Checking traffic and cluster after switch",
  "Returning former active to standby", "Verifying final cluster health", "Failover verified"];
const activeStates: CpFailoverState[] = ["PLANNED", "PRECHECK", "FAILING_OVER", "SWITCHED", "POSTCHECK", "RETURNING"];
export function runStep(state: CpFailoverState, step: string): number {
  const current = state === "STOPPED" ? step : state;
  if (current === "FINAL_POSTCHECK" || current === "POST_RETURN" || current === "POSTCHECK" && ["FINAL_POSTCHECK", "POST_RETURN", "final"].includes(step)) return 5;
  return ({ PLANNED: 0, PRECHECK: 0, FAILING_OVER: 1, SWITCHED: 2, POSTCHECK: 3, RETURNING: 4, DONE: 6 } as Record<string, number>)[current] ?? 0;
}
const errorText = (error: unknown) => {
  const body = (error as { body?: { code?: string; message?: string } })?.body;
  if (body?.code === "BUSINESS_HOURS_CONFIRM_REQUIRED") return body.message ?? "Not started: business hours were not confirmed.";
  return body?.code === "OUTSIDE_JOB_WINDOW" ? body.message ?? "Device jobs are outside the scheduled window." : body?.code ?? "Request failed";
};
type Draft = { requestId: string; from: string; until: string; reason: string; confirmedRevision?: number };
/** Workers write "post_return" after the return step; "final" is the older name. Both are the final post-checks. */
const FINAL_PHASES: ReadonlyArray<string> = ["post_return", "final"];
const phaseGroups = [
  { key: "pre", title: "Pre-checks", phases: ["pre"] },
  { key: "post", title: "Post-switch checks", phases: ["post"] },
  { key: "final", title: "Final post-checks", phases: FINAL_PHASES },
] as const;
const recoveryLabel = (mode?: string) => mode === "ACTIVE_UP" ? "Active Up" : mode === "PRIMARY_UP" ? "Primary Up" : "Unknown";
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
    {phaseGroups.map(group => <Box key={group.key}>
      <Typography variant="subtitle2">{group.title}</Typography>
      {run.checks.some(c => (group.phases as ReadonlyArray<string>).includes(c.phase))
        ? <ReadinessChecksTable checks={run.checks.filter(c => (group.phases as ReadonlyArray<string>).includes(c.phase))} members={unit.members} />
        : <Typography variant="body2">Not evaluated</Typography>}
      {group.key !== "pre" && <Typography variant="body2">Session continuity: not evaluated</Typography>}
    </Box>)}
    <Typography variant="body2">Last confirmed roles: {roles.length ? roles.map(([id, role]) =>
      `${unit.members?.find(m => m.device_id === id)?.hostname ?? "Member"}: ${role}`).join(" · ") : "Not evaluated"}</Typography>
    {stopped && <><Typography>Failed/unknown check: {failed.map(c => `${c.title} · ${c.status}`).join("; ") || run.failedCheck || "Not evaluated"}</Typography>
      <Typography>Refusal: {run.message || "Unknown"} · Incident reference: {run.incidentRef ?? "Not recorded"}</Typography></>}
    {run.state === "DONE" && <Typography>Evidence coverage: {run.checks.filter(c => c.blocking && c.status === "PASS").length} / {run.checks.filter(c => c.blocking).length} recorded required results passed. Final post-checks: {run.checks.some(c => FINAL_PHASES.includes(c.phase)) ? "Recorded" : "Not evaluated"}.</Typography>}
  </Stack>;
}
/** "Resume return step" (docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md §6): stepper of the resumed return. */
const resumeLabels = ["Reading cluster state", "Returning former active to standby", "Verifying final cluster health", "Return verified"];
export const resumeActiveStates: CpFailoverResumeState[] = ["QUEUED", "READING_STATE", "RETURNING", "POST_RETURN"];
export function resumeStep(state: CpFailoverResumeState, step?: string): number {
  const current = state === "STOPPED" ? step : state;
  return ({ QUEUED: 0, READING_STATE: 0, RETURNING: 1, POST_RETURN: 2, DONE: 3 } as Record<string, number>)[current ?? ""] ?? 0;
}
/** The server's refusal and stop codes in plain words; an unknown code is shown as sent. */
const resumeWords: Record<string, string> = {
  RESUME_RUN_NOT_ELIGIBLE: "Resume is not available: this is not a stopped Check Point ClusterXL failover run.",
  RESUME_DOWN_NOT_CONFIRMED: "Resume is not available: neXus did not confirm that this run took the member down.",
  RESUME_RETURN_ALREADY_ATTEMPTED: "Resume is not available: this run's return command may already have been sent.",
  RESUME_ALREADY_USED: "Resume is not available: the return step of this run was already resumed once.",
  INCIDENT_NOT_OPEN: "Resume is not available: this run has no open incident.",
  INCIDENT_MISMATCH: "Resume refused: the incident changed since this page was loaded. Reload and check again.",
  OPEN_INCIDENT: "Resume is not available: another incident covers this unit or one of its members.",
  FLEET_MUTATION_ACTIVE: "Resume is not available: another failover or resume is in progress.",
  UNRESOLVED_DISPATCH: "Resume is not available: another run has a device write whose outcome is unresolved.",
  RUN_ALREADY_ACTIVE: "Resume is not available: another run is in progress on this unit.",
  MEMBERSHIP_CHANGED: "Resume is not available: the cluster members or their recorded identities changed since the run.",
  FAILOVER_MUTATION_DISABLED: "Resume is not available: failover writes are switched off.",
  TRUSTED_HOST_KEY_REQUIRED: "Resume is not available: a member's host key is not trusted.",
  REASON_REQUIRED: "A reason is required.",
  BUSINESS_HOURS_ACK_REQUIRED: "Business hours: tick the acknowledgement to continue.",
  RESUME_REQUEST_EXPIRED: "The resume request expired (30 minutes after it was made). Nothing was sent.",
  SELF_APPROVAL: "A second, different administrator must approve this resume.",
  WRONG_ROLE: "Only a security_admin or operation_admin can resume the return step.",
  RESUME_PEER_NOT_ACTIVE: "Stopped before the write: the peer was not Active on both members. Nothing was sent.",
  RESUME_MEMBER_NOT_DOWN: "Stopped before the write: the former active is no longer down. Verify it out of band, then release the incident.",
  RETURN_PREEMPTION_POSSIBLE: "Stopped before the write: the cluster is not in Active Up mode. Nothing was sent.",
  RESUME_STATE_UNKNOWN: "Stopped before the write: the cluster state was not recognised. Nothing was sent.",
  RESUME_BASELINE_UNAVAILABLE: "Stopped before the write: the run's pre-check baseline is incomplete. Nothing was sent.",
  RETURN_TIMEOUT: "The former active did not rejoin as Standby within 60 s.",
  UNEXPECTED_ROLES: "Unexpected roles after the return command (possible preemption).",
  OUTCOME_UNCERTAIN: "The return command's outcome is uncertain. It is not resent.",
  OUTCOME_UNKNOWN: "The return command's outcome is uncertain. It is not resent.",
  RETURN_VERIFIED: "Return verified.",
};
export const resumeText = (code?: string | null) => code ? resumeWords[code] ?? code : "";
export function ResumeReturn({ run, unit, onChanged }: { run: CpFailoverRunDetail; unit: CpFailoverUnit; onChanged: () => void }) {
  const resume = run.resume;
  const [dialog, setDialog] = useState<"request" | "approve" | null>(null);
  const [reason, setReason] = useState(""), [ack, setAck] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null);
  const lock = useRef(false);
  if (!resume || run.state !== "STOPPED" || run.kind === "READINESS") return null;
  const label = unit.maskedName ?? unit.virtual_system ?? unit.cluster_member_ref;
  const formerActive = unit.members?.find(m => m.device_id === resume.formerActiveRef)?.hostname ?? "the former active";
  const peer = unit.members?.find(m => m.device_id !== resume.formerActiveRef)?.hostname ?? "the peer";
  const operable = unit.masked !== true;
  const needsAck = resume.businessHoursInside;
  const submit = async () => {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError(null);
    try {
      if (dialog === "approve") await approveCpFailoverResume(run.runId, resume.resumeId ?? "", needsAck && ack);
      else await resumeCpFailoverReturn(run.runId, run.incidentRef ?? "", reason.trim(), needsAck && ack);
      setDialog(null); onChanged();
    } catch (e) {
      const code = (e as { body?: { code?: string } })?.body?.code;
      setError(code ? resumeText(code) : "Request failed");
    } finally { lock.current = false; setBusy(false); }
  };
  const step = resume.state ? resumeStep(resume.state, resume.step) : 0;
  return <Stack spacing={1} aria-label="Resume return step">
    <Typography variant="subtitle2">Resume return step</Typography>
    {resume.state ? <>
      <Typography role="status">{resume.state === "PENDING_APPROVAL" ? "Resume awaiting second approval"
        : resume.state === "DONE" ? "Return verified" : resume.state === "STOPPED" ? `Resume stopped: ${resumeText(resume.message ?? resume.outcome)}`
        : resumeLabels[step]}</Typography>
      {resume.state !== "PENDING_APPROVAL" && <Stepper activeStep={step} alternativeLabel>{resumeLabels.map((text, index) =>
        <Step key={text} completed={resume.state === "DONE" || index < step}><StepLabel error={resume.state === "STOPPED" && index === step}>{text}</StepLabel></Step>)}</Stepper>}
      {resume.state === "DONE" && <Typography variant="body2">The incident stays open until a security_admin releases it.</Typography>}
      {resume.state === "STOPPED" && <Typography variant="body2">Nothing more is sent. The incident stays open; recovery continues out of band.</Typography>}
      {operable && resume.canApprove && resume.state === "PENDING_APPROVAL" && <M3Button deviceJob emphasis="filled" disabled={busy}
        onClick={() => { setAck(false); setError(null); setDialog("approve"); }}>Approve resume</M3Button>}
    </> : operable && resume.canResume
      ? <M3Button deviceJob emphasis="filled" disabled={busy} onClick={() => { setReason(""); setAck(false); setError(null); setDialog("request"); }}>Resume return step</M3Button>
      : <Typography variant="body2">{resume.refusal ? resumeText(resume.refusal) : resumeText("WRONG_ROLE")}</Typography>}
    {error && !dialog && <Typography role="alert" color="error">{error}</Typography>}
    <Dialog open={dialog !== null} onClose={() => { if (!busy) setDialog(null); }}>
      <DialogTitle>Resume the return step of this failover run?</DialogTitle>
      <DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
        <Typography>Run {run.runId} on {label} stopped after clusterXL_admin down ({run.message ?? run.outcome ?? "unknown"}). {formerActive} is down by admin and {peer} carries the traffic.</Typography>
        <Typography>neXus will first read the cluster state on both members. Only if {peer} is Active, {formerActive} is Down and the cluster is in Active Up mode, it sends clusterXL_admin up once to {formerActive}. Then it waits up to 60 s for it to rejoin as Standby and repeats the post-checks. Traffic stays on {peer}.</Typography>
        <Typography>Nothing is retried. If anything does not match, neXus stops and the incident stays open.</Typography>
        {dialog === "request" && <TextField label="Reason" value={reason} onChange={e => setReason(e.target.value)} inputProps={{ maxLength: 500 }} />}
        {needsAck && <FormControlLabel control={<Checkbox checked={ack} onChange={e => setAck(e.target.checked)} />}
          label={`I acknowledge this runs during business hours (${resume.businessHours} Europe/Istanbul).`} />}
        {error && <Typography role="alert" color="error">{error}</Typography>}
      </Stack></DialogContent>
      <DialogActions><M3Button emphasis="text" disabled={busy} onClick={() => setDialog(null)}>Cancel</M3Button>
        <M3Button deviceJob emphasis="filled" disabled={busy || (dialog === "request" && !reason.trim()) || (needsAck && !ack)}
          onClick={() => void submit()}>{dialog === "approve" ? "Approve resume" : "Resume return step"}</M3Button></DialogActions>
    </Dialog>
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
  const shown = useRef(unit.readinessObservedAt);
  shown.current = unit.readinessObservedAt;
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
        // The unit list was loaded with the latest finished readiness: reload it only when this run is newer than
        // what the list shows (a run that finished while we watched), not for the run already on screen at mount.
        const firstSight = lastReadiness.current === null;
        lastReadiness.current = checks.runId;
        const seen = checks.checks.map(c => c.observedAt).sort().at(-1);
        if (!firstSight || (seen && (!shown.current || Date.parse(seen) > Date.parse(shown.current)))) onRefreshUnits();
      }
    } catch (e) { setLoaded(false); setError(errorText(e)); }
  }, [unit.unitId, unit.clusterId, unit.vendor, onRefreshUnits]);
  useEffect(() => { if (expanded) void refresh(); }, [expanded, refresh]);
  const pending = approvals.find(a => !a.revokedAt && Date.parse(a.windowUntil) > Date.now() && a.policy === "OPERATION_ADMIN_TWO_PERSON" && !a.approved);
  const bound = approvals.find(a => a.requestId === draft?.requestId && !a.revokedAt && Date.parse(a.windowUntil) > Date.now());
  const active = Boolean(run && activeStates.includes(run.state));
  const resuming = Boolean(run?.resume?.state && [...resumeActiveStates, "PENDING_APPROVAL"].includes(run.resume.state));
  const readinessActive = Boolean(readinessRun && activeStates.includes(readinessRun.state));
  useEffect(() => {
    if (!expanded || (!active && !pending && !readinessActive && !resuming)) return;
    const timer = window.setInterval(() => { void refresh(); }, 3000);
    return () => window.clearInterval(timer);
  }, [expanded, active, pending, readinessActive, resuming, refresh]);
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
    <Typography variant="body2">{unit.vendor === "palo_alto" ? "Palo Alto" : "Check Point"} · Mode: {unit.mode ?? "UNKNOWN"}{unit.vendor === "palo_alto" ? "" : ` · Recovery mode: ${recoveryLabel(unit.recoveryMode)}`} · Readiness age: {unit.readinessObservedAt ? relativeAge(unit.readinessObservedAt, new Date()) : "Not evaluated"}</Typography>
    {unit.refusalReason && <Typography color="error">{unit.refusalReason}</Typography>}
    {expanded && <Stack spacing={2} sx={{ mt: 1 }}>
      <FailoverIncidentsPanel clusterId={unit.clusterId} onReleased={() => { onRefreshUnits(); void refresh(); }} />
      {pending && <Typography role="status">Awaiting second approval</Typography>}
      {canOperate && <M3Button deviceJob emphasis="filled" disabled={!idle || Boolean(unit.refusalReason) || Boolean(pending)} onClick={() => {
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
      {run && <ResumeReturn run={run} unit={unit} onChanged={() => { onRefreshUnits(); void refresh(); }} />}
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
        <M3Button deviceJob emphasis="filled" disabled={busy || !reason.trim() || (!draft && (!until || Date.parse(until) <= Date.now()))} onClick={() => void confirm()}>OK / Start</M3Button></DialogActions>
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
