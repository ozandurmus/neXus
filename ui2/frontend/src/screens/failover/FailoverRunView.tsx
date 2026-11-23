import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Step from "@mui/material/Step";
import StepLabel from "@mui/material/StepLabel";
import Stepper from "@mui/material/Stepper";
import Typography from "@mui/material/Typography";
import type { CpFailoverRunDetail, CpFailoverUnit } from "../../auth/adminApi";
import { m3 } from "../../theme/m3Theme";
import { readinessReasonLabel, statusWord } from "../../shell/presenters";
import { formatStamp } from "../../shell/time";
import { ChecksByConsequence } from "./ChecksByConsequence";
import { FINAL_PHASES, runStep } from "./failoverShared";
import { groupChecks } from "./failoverRowModel";
import { ResumeReturn } from "./ResumeReturn";

/** The six steps of a failover run, in plain words (the worker's last two steps are one for the reader: the final checks). */
export const RUN_STEPS = ["Pre-checks", "Switch", "Roles confirmed", "Post-checks", "Return", "Final checks"] as const;
const ACTIVE_SENTENCE = ["Failing over - running the pre-checks", "Failing over - switching", "Failing over - roles confirmed, checking the cluster",
  "Failing over - checking traffic and cluster after the switch", "Failing over - returning the former active to standby", "Failing over - verifying final cluster health"];
const phaseGroups = [
  { key: "pre", title: "Pre-checks", phases: ["pre"] },
  { key: "post", title: "Post-switch checks", phases: ["post"] },
  { key: "final", title: "Final post-checks", phases: FINAL_PHASES },
] as const;

/** The outcome sentence from the run's state and what it says about a device write (the same three cases as before). */
export function runOutcomeSentence(run: CpFailoverRunDetail): string {
  if (run.state === "DONE") return "Failover verified";
  if (run.state === "STOPPED") return run.mutationPossible === false ? "Stopped before anything was sent"
    : run.mutationPossible === true ? "Stopped after the switch - action needed"
    : "Stopped - it is not known whether anything was sent; action needed";
  if (run.state === "PLANNED") return "Queued";
  return ACTIVE_SENTENCE[Math.min(runStep(run.state, run.step), ACTIVE_SENTENCE.length - 1)];
}

const memberName = (unit: CpFailoverUnit, id: string) => unit.members?.find(m => m.device_id === id)?.hostname ?? "Member";

/**
 * One failover run (REDESIGN section 6): the outcome sentence first, six steps, the facts, then - when stopped - the server's
 * reason, the failed check, the incident and the next action the server offers. Groups of checks only when they have rows.
 */
export function FailoverRunView({ run, unit, onChanged, resumeActions = true }: { run: CpFailoverRunDetail; unit: CpFailoverUnit; onChanged?: () => void;
  /** False where the run is only read (the history), so a run is never acted on from two places at once. */
  resumeActions?: boolean }) {
  const stopped = run.state === "STOPPED", done = run.state === "DONE";
  const step = runStep(run.state, run.step);
  const failed = run.checks.filter(c => c.blocking && ["FAIL", "UNKNOWN"].includes(c.status));
  // One entry per failing check, naming every member whose row failed.
  const failedText = [...new Set(failed.map(c => c.checkNo))].map(no => {
    const rows = failed.filter(c => c.checkNo === no);
    return `${rows[0].title} · ${statusWord(rows[0].status)}${rows.some(c => c.hostname) ? ` · ${rows.map(c => c.hostname).filter(Boolean).join(", ")}` : ""}`;
  }).join("; ") || run.failedCheck || "Not evaluated";
  const roles = Object.entries(run.lastConfirmedRoles ?? {});
  const rolesText = roles.map(([id, role]) => `${memberName(unit, id)} ${role.toLowerCase()}`).join(", ");
  const required = run.checks.filter(c => c.blocking);
  const passedRequired = required.filter(c => c.status === "PASS").length;
  const resume = run.resume;
  // The server says the run cannot be resumed because nothing was switched: there is nothing to offer, so nothing is drawn.
  const showResume = resumeActions && Boolean(resume) && !(resume?.refusal === "RESUME_RUN_NOT_ELIGIBLE" && run.mutationPossible === false);
  const resumeOffered = Boolean(resume?.canResume || resume?.state);
  return <Stack spacing={1.5} aria-label="Failover run">
    <Typography role="status" variant="h6" sx={{ fontWeight: 600, color: stopped ? m3.onErrorContainer : undefined }}>{runOutcomeSentence(run)}</Typography>
    <Stepper activeStep={done ? RUN_STEPS.length : Math.min(step, RUN_STEPS.length - 1)} alternativeLabel>{RUN_STEPS.map((label, index) =>
      <Step key={label} completed={done || index < step}><StepLabel error={stopped && index === Math.min(step, RUN_STEPS.length - 1)}>{label}</StepLabel></Step>)}</Stepper>
    <Typography variant="body2" data-run-facts>
      Started {formatStamp(run.scheduledFor)}
      {roles.length > 0 && ` · roles last confirmed: ${rolesText}`}
    </Typography>
    {done && <Box data-run-outcome="verified" sx={{ p: 1.5, borderRadius: 1.5, bgcolor: m3.successContainer, color: m3.onSuccessContainer }}>
      <Typography sx={{ fontWeight: 600 }}>Failover verified{rolesText ? ` · roles now ${rolesText}` : ""}</Typography>
      <Typography variant="body2">{passedRequired} of {required.length} required checks passed · session continuity not evaluated</Typography>
    </Box>}
    {!done && <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Session continuity: not evaluated</Typography>}
    {stopped && <Stack spacing={0.5} data-run-stopped>
      {run.mutationPossible === false && <Typography>Nothing was sent to a device.</Typography>}
      <Typography>Reason: {run.message ? <><span title={run.message}>{readinessReasonLabel(run.message)}</span>{readinessReasonLabel(run.message) !== run.message && <span> · {run.message}</span>}</> : "Unknown"}</Typography>
      <Typography>Failed or unknown check: {failedText}</Typography>
      {run.mutationPossible !== false && <Typography>Incident: {run.incidentRef ?? "Not recorded"}</Typography>}
      {(run.mutationPossible === false || !resumeOffered) && <Typography variant="body2">Nothing more is sent.</Typography>}
    </Stack>}
    {phaseGroups.map(group => {
      const checks = run.checks.filter(c => (group.phases as ReadonlyArray<string>).includes(c.phase));
      if (checks.length === 0) return null;
      const grouped = groupChecks(checks, unit.members ?? [], unit.vendor);
      const bad = grouped.blocking.length, unknown = grouped.unknown.length;
      return <Box component="details" key={group.key} data-run-group={group.key} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 1.5, px: 1.5, py: 0.75 }}>
        <Box component="summary" sx={{ cursor: "pointer" }}><Box component="span" sx={{ fontWeight: 600 }}>{group.title}</Box>
          {` · ${grouped.passed.length} passed, ${bad} failed${unknown ? `, ${unknown} unknown` : ""}`}</Box>
        <Box sx={{ mt: 1 }}><ChecksByConsequence checks={checks} members={unit.members ?? []} vendor={unit.vendor} /></Box>
      </Box>;
    })}
    {showResume && <ResumeReturn run={run} unit={unit} onChanged={onChanged ?? (() => {})} />}
    <Box component="details" sx={{ color: m3.onSurfaceVar }}>
      <Box component="summary" sx={{ cursor: "pointer", fontSize: 12 }}>Technical details</Box>
      <Typography variant="caption" component="div">Run: {run.runId} · Phase: {run.step}</Typography>
    </Box>
  </Stack>;
}
