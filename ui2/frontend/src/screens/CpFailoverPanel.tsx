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
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";
import { PRODUCT_NAME } from "../brand/product";
import { m3 } from "../theme/m3Theme";
import { formatStamp, relativeAge } from "../shell/time";
import { clusterModeWord, readinessReasonLabel, requestFailure, statusWord } from "../shell/presenters";
import { approveCpFailover, approveCpFailoverResume, getCpFailoverRun, listCpFailoverApprovals, listCpFailoverRuns, listCpFailoverUnits,
  listFailoverIncidents, resumeCpFailoverReturn, secondApproveCpFailover, startCpFailover, runCpFailoverReadiness,
  type CpFailoverApproval, type CpFailoverResumeState, type CpFailoverRunDetail, type CpFailoverState, type CpFailoverUnit } from "../auth/adminApi";
import { ReadinessCard, ReadinessChecksTable } from "./ReadinessChecksTable";
import { FailoverIncidentsPanel } from "./FailoverIncidentsPanel";
import { PanConfigSyncAction } from "./PanConfigSyncAction";
import { failoverExecutionDisabled, failoverVendorLabel } from "./HaReadinessList";
const labels = ["Checking readiness", "Failing over", "Role change confirmed", "Checking traffic and cluster after switch",
  "Returning former active to standby", "Verifying final cluster health", "Failover verified"];
const activeStates: CpFailoverState[] = ["PLANNED", "PRECHECK", "FAILING_OVER", "SWITCHED", "POSTCHECK", "RETURNING"];
export function runStep(state: CpFailoverState, step: string): number {
  const current = state === "STOPPED" ? step : state;
  if (current === "FINAL_POSTCHECK" || current === "POST_RETURN" || current === "POSTCHECK" && ["FINAL_POSTCHECK", "POST_RETURN", "final"].includes(step)) return 5;
  return ({ PLANNED: 0, PRECHECK: 0, FAILING_OVER: 1, SWITCHED: 2, POSTCHECK: 3, RETURNING: 4, DONE: 6 } as Record<string, number>)[current] ?? 0;
}
/** The server's reason for a refused request: its `code`, else its `error` (ACTION_MAPPING_REQUIRED, ACTION_REFUSED ...
 * bodies carry only `error`), with the HTTP status; never a bare "Request failed". */
export const errorText = (error: unknown) => {
  const failure = error as { status?: number; body?: { code?: string; error?: string; message?: string } } | null | undefined;
  const body = failure?.body;
  if (body?.code === "BUSINESS_HOURS_CONFIRM_REQUIRED") return body.message ?? "Not started: business hours were not confirmed.";
  if (body?.code === "OUTSIDE_JOB_WINDOW") return body.message ?? "Device jobs are outside the scheduled window.";
  if (body?.code === "DIRECTORY_UNREACHABLE_AT_START") return body.message ?? "Directory unreachable (DIRECTORY_UNREACHABLE_AT_START): directory group membership could not be refreshed. Retry when the directory is reachable.";
  // A VSX chassis refusal (mode, sequence, per-VS readiness) is read in words first; the code and HTTP status stay beside it.
  const code = body?.code ?? body?.error;
  if (code && CHASSIS_CODE.test(code)) return `${readinessReasonLabel(code)} (${requestFailure(failure)})`;
  return requestFailure(failure);
};
const CHASSIS_CODE = /^(VSX_|VIRTUAL_SYSTEM_|SEQUENCE_|VS_RUN_|CHASSIS_RUN_)/;
export const isDirectoryUnreachable = (error: unknown) =>
  (error as { body?: { code?: string } } | null | undefined)?.body?.code === "DIRECTORY_UNREACHABLE_AT_START";
type Draft = { requestId: string; from: string; until: string; reason: string; confirmedRevision?: number };
/** The one free-text field of a failover or resume request (PO 2026-11-23): a work record, not an approval form. */
export const WORK_LOG_NOTE_LABEL = "Work log note";
export const WORK_LOG_NOTE_MAX = 500;
/** How long a request stays startable. A single administrator's request always uses the default; nothing is typed. */
export const VALIDITY_MINUTES = [30, 60, 120] as const;
export const DEFAULT_VALIDITY_MINUTES = 60;
/**
 * The readiness line of the confirm dialog. "Ready" needs a finished readiness run whose outcome is READY and a unit
 * the server does not refuse; a missing, running or unrecognised result is Unknown and is never worded as ready. A
 * server refusal is shown as the server sent it.
 */
export type ReadinessVerdict = { tone: "ready" | "warning" | "blocked" | "unknown"; text: string; detail?: string };
export function failoverReadinessVerdict(unit: CpFailoverUnit, readiness: CpFailoverRunDetail | null): ReadinessVerdict {
  const refusal = unit.refusalReason || unit.chassisStartRefusal || unit.failoverRefusal;
  if (refusal) return { tone: "blocked", text: `Refused: ${refusal}` };
  if (!readiness) return { tone: "unknown", text: "Unknown: no readiness result on record" };
  if (activeStates.includes(readiness.state)) return { tone: "unknown", text: "Unknown: pre-checks are still running" };
  const pre = readiness.checks.filter(c => c.phase === "pre");
  if (readiness.outcome === "READY") {
    const warned = [...new Set(pre.filter(c => (c.status ?? c.result) === "WARN").map(c => c.title))];
    if (!warned.length) return { tone: "ready", text: "Ready" };
    const listed = warned.slice(0, 3).join("; "), more = warned.length - 3;
    return { tone: "warning", text: `Ready with ${warned.length} ${warned.length === 1 ? "warning" : "warnings"}`,
      detail: more > 0 ? `${listed}; +${more} more` : listed };
  }
  if (readiness.outcome === "NOT_READY") {
    const blockers = new Set(pre.filter(c => c.blocking && ["FAIL", "UNKNOWN"].includes(c.status ?? c.result)).map(c => c.checkNo)).size;
    return { tone: "blocked", text: `Not ready${blockers ? ` · ${blockers} ${blockers === 1 ? "blocker" : "blockers"}` : ""}`,
      detail: readiness.message ?? undefined };
  }
  return { tone: "unknown", text: "Unknown: the last readiness run recorded no verdict" };
}
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
        ? <ReadinessChecksTable checks={run.checks.filter(c => (group.phases as ReadonlyArray<string>).includes(c.phase))} members={unit.members} vendor={unit.vendor} />
        : <Typography variant="body2">Not evaluated</Typography>}
      {group.key !== "pre" && <Typography variant="body2">Session continuity: not evaluated</Typography>}
    </Box>)}
    <Typography variant="body2">Last confirmed roles: {roles.length ? roles.map(([id, role]) =>
      `${unit.members?.find(m => m.device_id === id)?.hostname ?? "Member"}: ${role}`).join(" · ") : "Not evaluated"}</Typography>
    {stopped && <><Typography>Failed/unknown check: {failed.map(c => `${c.title} · ${statusWord(c.status)}`).join("; ") || run.failedCheck || "Not evaluated"}</Typography>
      <Typography>Refusal: {run.message ? <span title={run.message}>{readinessReasonLabel(run.message)}</span> : "Unknown"} · Incident reference: {run.incidentRef ?? "Not recorded"}</Typography></>}
    {run.state === "DONE" && <Typography>Evidence coverage: {run.checks.filter(c => c.blocking && c.status === "PASS").length} / {run.checks.filter(c => c.blocking).length} recorded required results passed. Final post-checks: {run.checks.some(c => FINAL_PHASES.includes(c.phase)) ? "Recorded" : "Not evaluated"}.</Typography>}
  </Stack>;
}
/** "Resume return step" (docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md §6): stepper of the resumed return. */
const resumeLabels = ["Reading cluster state", "Returning former active to standby", "Verifying final cluster health", "Return verified"];
export const resumeActiveStates: CpFailoverResumeState[] = ["QUEUED", "READING_STATE", "RETURNING", "POST_RETURN"];
/**
 * Poll while a resume runs or awaits its second approval; never once it has ended (DONE, STOPPED, EXPIRED). The server
 * turns a PENDING_APPROVAL request past its 30 minutes into EXPIRED on every read, so polling a stale request ends.
 */
export const resumePolls = (state?: CpFailoverResumeState) =>
  Boolean(state && (resumeActiveStates.includes(state) || state === "PENDING_APPROVAL"));
export function resumeStep(state: CpFailoverResumeState, step?: string): number {
  const current = state === "STOPPED" ? step : state;
  return ({ QUEUED: 0, READING_STATE: 0, RETURNING: 1, POST_RETURN: 2, DONE: 3 } as Record<string, number>)[current ?? ""] ?? 0;
}
/** The server's refusal and stop codes in plain words; an unknown code is shown as sent. */
const resumeWords: Record<string, string> = {
  RESUME_RUN_NOT_ELIGIBLE: "Resume is not available: this is not a stopped Check Point ClusterXL failover run.",
  RESUME_DOWN_NOT_CONFIRMED: `Resume is not available: ${PRODUCT_NAME} did not confirm that this run took the member down.`,
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
  TRUST_NOT_EVALUABLE: "Resume is not available: a member's host-key trust could not be checked.",
  ENDPOINT_MISSING: "Resume is not available: a member has no management endpoint.",
  REASON_REQUIRED: "A work log note is required.",
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
  INITIATOR_NOT_AUTHORIZED_AT_START: "Stopped before contact: the requesting administrator no longer holds the role or is disabled. Nothing was sent.",
  APPROVER_NOT_AUTHORIZED_AT_START: "Stopped before contact: the approving administrator no longer holds the role or is disabled. Nothing was sent.",
  APPROVER_IS_INITIATOR_AT_START: "Stopped before contact: the approver and the requester are the same person. Nothing was sent.",
  DIRECTORY_AUTHORITY_STALE_AT_START: "Stopped before contact: directory group membership was not refreshed in time. Nothing was sent.",
  DIRECTORY_UNREACHABLE_AT_START: "The directory could not be reached to refresh group membership. Nothing was sent; retry when the directory is reachable.",
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
        : resume.state === "EXPIRED" ? resumeText("RESUME_REQUEST_EXPIRED")
        : resume.state === "DONE" ? "Return verified" : resume.state === "STOPPED" ? `Resume stopped: ${resumeText(resume.message ?? resume.outcome)}`
        : resumeLabels[step]}</Typography>
      {resume.state !== "PENDING_APPROVAL" && resume.state !== "EXPIRED" && <Stepper activeStep={step} alternativeLabel>{resumeLabels.map((text, index) =>
        <Step key={text} completed={resume.state === "DONE" || index < step}><StepLabel error={resume.state === "STOPPED" && index === step}>{text}</StepLabel></Step>)}</Stepper>}
      {resume.state === "DONE" && <Typography variant="body2">The incident stays open until a security_admin releases it.</Typography>}
      {(resume.state === "STOPPED" || resume.state === "EXPIRED") && <Typography variant="body2">Nothing more is sent. The incident stays open; recovery continues out of band.</Typography>}
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
        <Typography>{PRODUCT_NAME} will first read the cluster state on both members. Only if {peer} is Active, {formerActive} is Down and the cluster is in Active Up mode, it sends clusterXL_admin up once to {formerActive}. Then it waits up to 60 s for it to rejoin as Standby and repeats the post-checks. Traffic stays on {peer}.</Typography>
        <Typography>Nothing is retried. If anything does not match, {PRODUCT_NAME} stops and the incident stays open.</Typography>
        {dialog === "request" && <TextField label={WORK_LOG_NOTE_LABEL} required autoFocus value={reason} placeholder="e.g. change CHG-123 return the former active"
          helperText="Recorded with the resume." onChange={e => setReason(e.target.value)} inputProps={{ maxLength: WORK_LOG_NOTE_MAX }} />}
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
/**
 * `singleAdmin` chooses the dialog's wording only (PO 2026-11-23): true = this session starts alone (ADMIN_SINGLE), false =
 * a second administrator approves (OPERATION_ADMIN_TWO_PERSON), null = not known. The server names the policy in its
 * answer to the request and that answer alone decides whether the run starts or waits.
 */
function UnitPanel({ unit, expanded, singleAdmin, onExpand, onOpen, onRefreshUnits }: { unit: CpFailoverUnit; expanded: boolean;
  singleAdmin: boolean | null | undefined; onExpand: () => void; onOpen: () => void; onRefreshUnits: () => void }) {
  const label = unit.maskedName ?? unit.virtual_system ?? unit.cluster_member_ref;
  const storageKey = `failover-request:${unit.vendor ?? "check_point"}:${unit.unitId}`;
  const [draft, setDraft] = useState<Draft | null>(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey) ?? "null") as Draft | null; } catch { return null; }
  });
  const [approvals, setApprovals] = useState<CpFailoverApproval[]>([]);
  const [run, setRun] = useState<CpFailoverRunDetail | null>(null);
  const [readinessRun, setReadinessRun] = useState<CpFailoverRunDetail | null>(null);
  const [dialog, setDialog] = useState(false), [minutes, setMinutes] = useState<number>(DEFAULT_VALIDITY_MINUTES);
  const [note, setNote] = useState(""), [error, setError] = useState<string | null>(null);
  // Set by the row's failover button: the dialog opens once this unit's requests and runs are read (and the policy hint answered).
  const [armed, setArmed] = useState(false);
  const [canRetry, setCanRetry] = useState(false);
  const [busy, setBusy] = useState(false), [loaded, setLoaded] = useState(false);
  const lock = useRef(false), attempted = useRef<string | null>(null);
  const lastAction = useRef<(() => Promise<unknown>) | null>(null);
  const approvedDraft = useRef<CpFailoverApproval | null>(null);
  const lastReadiness = useRef<string | null>(null);
  const shown = useRef(unit.readinessObservedAt);
  shown.current = unit.readinessObservedAt;
  const remember = (value: Draft) => { sessionStorage.setItem(storageKey, JSON.stringify(value)); setDraft(value); };
  const executionDisabled = failoverExecutionDisabled(unit.vendor);
  const refresh = useCallback(async () => {
    try {
      const [windows, runs] = await Promise.all([
        executionDisabled ? Promise.resolve([] as CpFailoverApproval[]) : listCpFailoverApprovals(unit),
        listCpFailoverRuns(unit),
      ]);
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
    } catch (e) { setLoaded(false); setArmed(false); setError(errorText(e)); }
  }, [unit.unitId, unit.clusterId, unit.vendor, onRefreshUnits]);
  useEffect(() => { if (expanded) void refresh(); }, [expanded, refresh]);
  const pending = approvals.find(a => !a.revokedAt && Date.parse(a.windowUntil) > Date.now() && a.policy === "OPERATION_ADMIN_TWO_PERSON" && !a.approved);
  const bound = approvals.find(a => a.requestId === draft?.requestId && !a.revokedAt && Date.parse(a.windowUntil) > Date.now());
  const active = Boolean(run && activeStates.includes(run.state));
  const resuming = resumePolls(run?.resume?.state);
  const readinessActive = Boolean(readinessRun && activeStates.includes(readinessRun.state));
  useEffect(() => {
    if (!expanded || (!active && !pending && !readinessActive && !resuming)) return;
    const timer = window.setInterval(() => { void refresh(); }, 3000);
    return () => window.clearInterval(timer);
  }, [expanded, active, pending, readinessActive, resuming, refresh]);
  const act = async (action: () => Promise<unknown>) => {
    if (lock.current) return;
    lastAction.current = action;
    lock.current = true; setBusy(true); setError(null); setCanRetry(false);
    try { await action(); setDialog(false); await refresh(); }
    catch (e) { setError(errorText(e)); setCanRetry(isDirectoryUnreachable(e)); }
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
  const canOperate = unit.masked !== true && unit.canStart && !executionDisabled;
  const canRunChecks = unit.masked !== true && (unit.canRunReadiness ?? unit.canStart);
  // A VSX chassis is failed over as its mode allows (contract §17): one chassis run in VSX HA mode, every virtual system in
  // turn on VSLS; with the mode unknown the server offers nothing (failoverRefusal).
  const failoverOffered = canOperate && !unit.failoverRefusal;
  const allVirtualSystems = unit.unitKind === "VSX_CHASSIS" && unit.chassisAction === "FAILOVER_ALL_VIRTUAL_SYSTEMS";
  const startLabel = allVirtualSystems ? "Fail over all virtual systems" : unit.unitKind === "VSX_CHASSIS" ? "Fail over the chassis" : "Start failover";
  const sequence = unit.sequence;
  const single = singleAdmin === true;
  const verdict = failoverReadinessVerdict(unit, readinessRun);
  const readinessAt = readinessRun?.checks.filter(c => c.phase === "pre").map(c => c.observedAt).sort().at(-1) ?? unit.readinessObservedAt;
  const refused = Boolean(unit.refusalReason) || Boolean(unit.chassisStartRefusal) || Boolean(unit.failoverRefusal);
  const noteText = draft?.reason ?? note;
  const openDialog = () => {
    if (draft?.confirmedRevision && bound?.approved && (!run || run.approvalId !== bound.approvalId)) { void act(() => start(bound)); return; }
    if (draft && (run?.approvalId === draft.requestId || Date.parse(draft.until) <= Date.now()
      || approvals.some(a => a.requestId === draft.requestId && a.revokedAt))) {
      sessionStorage.removeItem(storageKey); setDraft(null);
    }
    setNote(""); setMinutes(DEFAULT_VALIDITY_MINUTES); setError(null); setCanRetry(false); setDialog(true);
  };
  // One click from the row: a collapsed unit is opened and read first, then the dialog follows without a second click.
  const requestFailover = () => {
    if (!expanded) { setLoaded(false); onOpen(); }
    setArmed(true);
  };
  useEffect(() => {
    if (!armed || !expanded || !loaded || singleAdmin === undefined) return;
    setArmed(false);
    // A run or a request found while reading is shown in the opened unit instead of a second request.
    if (!active && !readinessActive && !pending) openDialog();
  }, [armed, expanded, loaded, singleAdmin, active, readinessActive, pending]);
  const confirm = () => act(async () => {
    const now = Date.now();
    const request = draft ?? { requestId: crypto.randomUUID(), from: new Date(now).toISOString(),
      until: new Date(now + (single ? DEFAULT_VALIDITY_MINUTES : minutes) * 60_000).toISOString(), reason: note.trim() };
    remember(request);
    const currentBound = bound ?? (approvedDraft.current?.requestId === request.requestId ? approvedDraft.current : undefined);
    const approval = currentBound ?? await approveCpFailover(unit, request.from, request.until, request.reason, request.requestId);
    if (!approval.requestId || !approval.revision) throw new Error("Missing request revision");
    approvedDraft.current = approval;
    // The dialog said a second administrator must agree and the server needed none: nothing starts on words the reader
    // was not given. The request stands on the server, but the reader's confirmation is NOT recorded: a later click on the
    // row must show the dialog again instead of starting on the strength of this one. Confirming again in this dialog starts it.
    const unconfirmed = approval.approved && singleAdmin === false && !currentBound;
    remember(unconfirmed ? request : { ...request, confirmedRevision: approval.revision });
    if (unconfirmed) throw new Error("No second administrator was needed for this request, so it is already approved on the server. The failover has not been started. Confirm again to start it now.");
    if (approval.approved) {
      attempted.current = approval.requestId;
      await start(approval);
    }
  });
  return <Card data-unit-kind={unit.unitKind ?? (unit.unitId === unit.clusterId ? "CLUSTER" : "VIRTUAL_SYSTEM")} sx={{ p: 2, ml: unit.unitId === unit.clusterId ? 0 : 3, bgcolor: m3.scLow }}>
    <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1} useFlexGap sx={{ flexWrap: "wrap" }}>
      <M3Button emphasis="text" onClick={onExpand}>{unit.unitKind === "VSX_CHASSIS" ? `VSX chassis · ${label}` : unit.unitId === unit.clusterId ? label : `Virtual System · ${label}`}</M3Button>
      {/* The action sits on the row: no expand step first. Collapsed, what the server already says on the unit decides it;
          opened, a running run, running pre-checks or a waiting request disable it as before. */}
      {failoverOffered && <M3Button deviceJob emphasis="filled" disabled={busy || armed || refused || sequence?.state === "RUNNING"
        || (expanded && (!idle || Boolean(pending)))} onClick={requestFailover}>{startLabel}</M3Button>}
    </Stack>
    <Typography variant="body2">{failoverVendorLabel(unit.vendor)} · Mode: <span title={unit.mode ?? undefined}>{clusterModeWord(unit.mode)}</span>{!unit.vendor || unit.vendor === "check_point" ? ` · Recovery mode: ${recoveryLabel(unit.recoveryMode)}` : ""} · Readiness age: {unit.readinessObservedAt ? relativeAge(unit.readinessObservedAt, new Date()) : "Not evaluated"}</Typography>
    {unit.refusalReason && <Typography color="error">{unit.refusalReason}</Typography>}
    {executionDisabled
      ? <Typography variant="body2" data-failover-execution-disabled title={unit.failoverRefusal ?? "FAILOVER_VENDOR_NOT_AVAILABLE"}>
          Failover execution not enabled for {failoverVendorLabel(unit.vendor)}</Typography>
      : unit.failoverRefusal && <Typography variant="body2" data-failover-refusal title={unit.failoverRefusal}>
          {readinessReasonLabel(unit.failoverRefusal)}: its pre-checks run here; each Virtual System below is failed over on its own.</Typography>}
    {unit.unitKind === "VSX_CHASSIS" && unit.chassisAction && <Typography variant="body2" data-chassis-action={unit.chassisAction}>
      {allVirtualSystems ? "VSLS chassis: every virtual system is failed over in turn, each with its own checks and return."
        : "VSX HA mode: the chassis fails over in VS0; every virtual system must follow it."}
      {unit.chassisStartRefusal && <Box component="span" data-chassis-start-refusal title={unit.chassisStartRefusal}> Not now: {readinessReasonLabel(unit.chassisStartRefusal)}.</Box>}</Typography>}
    {sequence && <Typography variant="body2" role="status" data-sequence-state={sequence.state}>
      All virtual systems: {sequence.state === "RUNNING" ? `virtual system ${sequence.started} of ${sequence.total} running`
        : `${readinessReasonLabel(sequence.outcome ?? sequence.state)}${sequence.failedVsId ? ` (VSID ${sequence.failedVsId})` : ""}`}</Typography>}
    {expanded && <Stack spacing={2} sx={{ mt: 1 }}>
      <FailoverIncidentsPanel clusterId={unit.clusterId} onReleased={() => { onRefreshUnits(); void refresh(); }} />
      {pending && <Typography role="status">Awaiting second approval</Typography>}
      {unit.masked !== true && unit.canApprove && !unit.failoverRefusal && pending?.canApprove && <M3Button emphasis="outlined" disabled={!idle}
        onClick={() => void act(() => secondApproveCpFailover(unit, pending.requestId!, pending.revision!))}>Approve request revision {pending.revision}</M3Button>}
      <ReadinessCard checks={readinessRun?.checks.filter(c => c.phase === "pre") ?? []} members={unit.members} cluster={label}
        vendor={unit.vendor} status={readinessRun?.outcome} stopCode={readinessRun?.message}
        observedAt={readinessRun?.checks.map(c => c.observedAt).sort().at(-1)} masked={unit.masked === true}
        running={Boolean(readinessRun && activeStates.includes(readinessRun.state))} disabled={!idle} canRun={canRunChecks}
        onRun={() => void act(() => runCpFailoverReadiness(unit))}
        rowAction={checkNo => checkNo === 4 && unit.vendor === "palo_alto"
          ? <PanConfigSyncAction unit={unit} onDone={() => { onRefreshUnits(); void refresh(); }} /> : null} />
      {run && <FailoverRun run={run} unit={unit} />}
      {run && <ResumeReturn run={run} unit={unit} onChanged={() => { onRefreshUnits(); void refresh(); }} />}
      {error && <Stack direction="row" spacing={1} alignItems="center"><Typography role="alert" color="error">{error}</Typography>
        {canRetry && !dialog && <M3Button emphasis="outlined" disabled={busy} onClick={() => { if (lastAction.current) void act(lastAction.current); }}>Retry</M3Button>}</Stack>}
    </Stack>}
    <Dialog open={dialog} onClose={() => { if (!busy) setDialog(false); }}>
      <DialogTitle>{startLabel}</DialogTitle>
      <DialogContent><Stack spacing={2} sx={{ pt: 1 }} data-failover-policy={single ? "single" : singleAdmin === false ? "two-person" : "unknown"}>
        <Typography>Target: {label}</Typography>
        <Box data-readiness-verdict={verdict.tone}>
          <Typography sx={{ fontWeight: 600, color: verdict.tone === "ready" ? m3.onSurface : verdict.tone === "blocked" ? m3.error : m3.onSurfaceVar }}>
            Readiness: {verdict.text}{verdict.tone !== "ready" && verdict.detail ? ` · ${verdict.detail}` : ""}</Typography>
          <Typography variant="caption">Pre-checks read: {readinessAt ? `${relativeAge(readinessAt, new Date())} (${formatStamp(readinessAt)})` : "not evaluated"}. The run repeats every pre-check before it switches.</Typography>
        </Box>
        {allVirtualSystems && <Typography data-sequence-warning>Every virtual system of this chassis is failed over one after another, in VSID order, each in its own run with its own pre-checks, switch, post-checks and return. The sequence stops at the first virtual system that does not succeed.</Typography>}
        {unit.unitKind === "VSX_CHASSIS" && !allVirtualSystems && <Typography>The chassis fails over in VS0. Afterwards every virtual system must be active on the new active member; if one is not, the run stops and offers the return step.</Typography>}
        <Typography>Intended role swap: current active becomes DOWN/suspended; current standby/passive becomes active. The former active is then returned to standby/passive after successful checks.</Typography>
        <Typography>Traffic may be interrupted. The run may stop with one member DOWN. Intervention may be required.</Typography>
        <Typography data-failover-flow>{single ? "The failover starts as soon as you confirm. Nothing more is asked."
          : singleAdmin === false ? "A second administrator must agree before anything is sent. The failover then starts from this page; nothing more is asked of you."
          : "The failover starts as soon as you confirm, unless your role needs a second administrator to agree first. Nothing more is asked of you."}</Typography>
        <TextField label={WORK_LOG_NOTE_LABEL} required autoFocus value={noteText} disabled={Boolean(draft)} placeholder="e.g. change CHG-123 planned failover"
          helperText="Recorded with the run." inputProps={{ maxLength: WORK_LOG_NOTE_MAX }} onChange={e => setNote(e.target.value)}
          onKeyDown={e => { if (e.key === "Enter" && !busy && !refused && noteText.trim()) { e.preventDefault(); void confirm(); } }} />
        {draft ? <Typography variant="caption" data-failover-validity>This request was already made. Valid until {formatStamp(draft.until)}.</Typography>
          : single ? <Typography variant="caption" data-failover-validity>Valid for {DEFAULT_VALIDITY_MINUTES} minutes.</Typography>
          : <Box><Typography variant="caption" id={`validity-${unit.unitId}`}>Request valid for</Typography>
            <RadioGroup row aria-labelledby={`validity-${unit.unitId}`} value={String(minutes)} onChange={e => setMinutes(Number(e.target.value))}>
              {VALIDITY_MINUTES.map(value => <FormControlLabel key={value} value={String(value)} control={<Radio size="small" />} label={`${value} minutes`} />)}
            </RadioGroup></Box>}
        {error && <Stack direction="row" spacing={1} alignItems="center"><Typography role="alert" color="error">{error}</Typography>
          {canRetry && <M3Button emphasis="outlined" disabled={busy} onClick={() => { if (lastAction.current) void act(lastAction.current); }}>Retry</M3Button>}</Stack>}
      </Stack></DialogContent>
      <DialogActions><M3Button emphasis="text" disabled={busy} onClick={() => setDialog(false)}>Cancel</M3Button>
        <M3Button deviceJob emphasis="filled" disabled={busy || refused || !noteText.trim()} onClick={() => void confirm()}>{single ? "Fail over now" : "Request failover"}</M3Button></DialogActions>
    </Dialog>
  </Card>;
}
/** The cluster (or VSX chassis) unit first, then its Virtual Systems in the server's order. */
export function orderUnits(units: CpFailoverUnit[]): CpFailoverUnit[] {
  const base = (unit: CpFailoverUnit) => unit.unitKind === "VSX_CHASSIS" || unit.unitId === unit.clusterId ? 0 : 1;
  return units.map((unit, index) => ({ unit, index })).sort((a, b) => base(a.unit) - base(b.unit) || a.index - b.index).map(entry => entry.unit);
}
export function CpFailoverPanel({ memberDeviceId, vendor = "check_point", initialUnitId }: { memberDeviceId: string; vendor?: "check_point" | "palo_alto" | "cisco_asa" | "fortinet"; initialUnitId?: string | null }) {
  const [units, setUnits] = useState<CpFailoverUnit[]>([]), [selected, setSelected] = useState<string | null>(initialUnitId ?? null);
  const [error, setError] = useState<string | null>(null);
  // undefined = not asked or not answered yet; null = the read failed (the dialog then words both outcomes).
  const [singleAdmin, setSingleAdmin] = useState<boolean | null | undefined>(undefined);
  const refreshUnits = useCallback(() => {
    void listCpFailoverUnits(memberDeviceId,vendor).then(value => setUnits(orderUnits(value).map(unit => ({ ...unit, members: unit.maskedMembers?.map(member => ({ ...member, hostname: member.maskedLabel })) ?? unit.members }))))
      .catch(e => setError(errorText(e)));
  }, [memberDeviceId,vendor]);
  useEffect(() => {
    let disposed = false;
    setUnits([]); setError(null); setSelected(initialUnitId ?? null);
    listCpFailoverUnits(memberDeviceId, vendor).then(value => { if (!disposed) setUnits(orderUnits(value).map(unit => ({ ...unit, members: unit.maskedMembers?.map(member => ({ ...member, hostname: member.maskedLabel })) ?? unit.members }))); }).catch(e => { if (!disposed) setError(errorText(e)); });
    return () => { disposed = true; };
  }, [memberDeviceId, vendor, initialUnitId]);
  // Which dialog to word (PO 2026-11-23). The unit list does not name the session's approval policy, so the page reads the
  // one fact the server already publishes that follows the same rule: `canRelease` on the incidents read is true exactly
  // for the role that approves and starts alone. It words the dialog and nothing else; no role token is read here, and the
  // policy in the server's answer to the request decides what happens.
  const startable = units.some(unit => unit.canStart && unit.masked !== true && !failoverExecutionDisabled(unit.vendor));
  useEffect(() => {
    if (!startable) return;
    let disposed = false;
    (async () => listFailoverIncidents())().then(value => { if (!disposed) setSingleAdmin(value?.canRelease === true); })
      .catch(() => { if (!disposed) setSingleAdmin(null); });
    return () => { disposed = true; };
  }, [startable]);
  return <Stack spacing={1} aria-label={`${failoverVendorLabel(vendor)} failover`}>
    {error && <Typography role="alert" color="error">{error}</Typography>}
    {!error && units.length === 0 && <Typography>No eligible failover units</Typography>}
    {units.map(unit => <UnitPanel key={`${vendor}:${unit.unitId}`} unit={unit} expanded={selected === unit.unitId} singleAdmin={singleAdmin} onRefreshUnits={refreshUnits}
      onOpen={() => setSelected(unit.unitId)} onExpand={() => setSelected(selected === unit.unitId ? null : unit.unitId)} />)}
  </Stack>;
}
