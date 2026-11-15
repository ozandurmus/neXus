import { useState } from "react";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import FormLabel from "@mui/material/FormLabel";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { reconcileJob, type JobReconcileOutcome } from "../auth/adminApi";
import { EVIDENCE_MAX, RECONCILE_OUTCOMES, reconcileRefusalWords } from "../jobs/jobReconcile";
import { m3 } from "../theme/m3Theme";

/**
 * Records the decision that closes a job whose outcome is unknown: which of the three outcomes it was, and the evidence
 * the person relied on. This records a decision only; neXus does not contact the device and does not run the job again.
 * A refusal (404, 409, 400) is shown in the server's own reason, in words, and the dialog stays open.
 */
export function JobReconcileDialog({ jobId, onClose, onReconciled, onStale }: {
  readonly jobId: string;
  readonly onClose: () => void;
  /** The server recorded the decision: the caller refreshes the job. */
  readonly onReconciled: () => void;
  /** The server says the job is gone or no longer unknown (404, 409): the caller may refresh while the reason stays shown. */
  readonly onStale?: () => void;
}) {
  const [outcome, setOutcome] = useState<JobReconcileOutcome | "">("");
  const [evidence, setEvidence] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const valid = outcome !== "" && evidence.trim().length > 0 && evidence.length <= EVIDENCE_MAX;

  const confirm = async () => {
    if (outcome === "" || !valid) return;
    setBusy(true);
    setError(null);
    try {
      await reconcileJob(jobId, outcome, evidence.trim());
      onReconciled();
      onClose();
    } catch (failure) {
      setError(reconcileRefusalWords(failure as { status?: number; body?: Record<string, unknown> }));
      const status = (failure as { status?: number }).status;
      if (status === 404 || status === 409) onStale?.();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={() => !busy && onClose()} fullWidth maxWidth="sm" aria-labelledby="job-reconcile-title">
      <DialogTitle id="job-reconcile-title">Reconcile job · outcome unknown</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            neXus could not tell whether this job's command took effect. Check the device yourself, then record what you found.
            This records your decision only: it does not contact the device and does not run the job again.
          </Typography>
          <FormControl>
            <FormLabel id="job-reconcile-outcome-label" sx={{ fontSize: "0.8125rem" }}>What happened on the device?</FormLabel>
            <RadioGroup aria-labelledby="job-reconcile-outcome-label" value={outcome}
              onChange={(event) => setOutcome(event.target.value as JobReconcileOutcome)}>
              {RECONCILE_OUTCOMES.map((option) => (
                <FormControlLabel key={option.value} value={option.value} control={<Radio size="small" />} disabled={busy}
                  label={<span><strong>{option.label}</strong> <span style={{ color: m3.onSurfaceVar }}>· {option.hint}</span></span>} />
              ))}
            </RadioGroup>
          </FormControl>
          <TextField label="Evidence" required multiline minRows={3} size="small" fullWidth value={evidence} disabled={busy}
            onChange={(event) => setEvidence(event.target.value)}
            inputProps={{ maxLength: EVIDENCE_MAX }}
            helperText={
              <span style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
                <span>What you checked and where (no passwords or other secrets).</span>
                <span data-evidence-counter>{evidence.length} / {EVIDENCE_MAX}</span>
              </span>
            } />
          {error && <Typography variant="body2" color="error" role="alert">{error}</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy} sx={{ textTransform: "none" }}>Cancel</Button>
        <Button variant="contained" onClick={() => void confirm()} disabled={busy || !valid} sx={{ textTransform: "none" }}>
          {busy ? "Recording…" : "Confirm"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
