import { useRef, useState } from "react";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import Stack from "@mui/material/Stack";
import Step from "@mui/material/Step";
import StepLabel from "@mui/material/StepLabel";
import Stepper from "@mui/material/Stepper";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { M3Button } from "../../shell/M3Widgets";
import { PRODUCT_NAME } from "../../brand/product";
import { approveCpFailoverResume, resumeCpFailoverReturn, type CpFailoverResumeState, type CpFailoverRunDetail, type CpFailoverUnit } from "../../auth/adminApi";
import { WORK_LOG_NOTE_LABEL, WORK_LOG_NOTE_MAX } from "./failoverShared";

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
