import { useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { getFmgDiagnostic, listFmgDiagnosticTargets, diagnosticHistory, runDiagnostic,
  type DiagnosticTarget, type DiagnosticResult, type DiagnosticHistoryRow, type ApiError } from "../auth/adminApi";

const TERMINAL = new Set(["COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN"]);

export function DiagnosticPanel() {
  const [allowed, setAllowed] = useState<boolean | null>(null);
  const [canExecute, setCanExecute] = useState(false);
  const [devices, setDevices] = useState<DiagnosticTarget[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [command, setCommand] = useState("");
  const [result, setResult] = useState<DiagnosticResult | null>(null);
  const [jobId, setJobId] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [running, setRunning] = useState(false);
  const [history, setHistory] = useState<DiagnosticHistoryRow[]>([]);
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const requestId = useRef<string | null>(null);
  const busy = submitting || running;

  useEffect(() => {
    listFmgDiagnosticTargets().then(r => {
      if (!Array.isArray(r.targets)) return setAllowed(false);
      setDevices(r.targets); setAllowed(true); setCanExecute(r.canExecute === true);
    }).catch(() => setAllowed(false));
  }, []);
  useEffect(() => {
    if (!allowed) return;
    let active = true;
    diagnosticHistory(deviceId, page).then(r => { if (active) setHistory(Array.isArray(r.runs) ? r.runs : []); })
      .catch(() => { if (active) setMessage("Could not load execution history."); });
    return () => { active = false; };
  }, [allowed, deviceId, page, refresh]);
  useEffect(() => {
    if (!jobId) return;
    let active = true;
    const poll = () => getFmgDiagnostic(jobId).then(r => {
      if (!active) return;
      setResult(r);
      setRunning(!TERMINAL.has(r.state));
      if (TERMINAL.has(r.state)) {
        clearInterval(timer); setRunning(false); setRefresh(v => v + 1);
      }
    }).catch(() => { if (active) setMessage("Could not read this job; retrying the result request."); });
    const timer = setInterval(poll, 2000);
    poll();
    return () => { active = false; clearInterval(timer); };
  }, [jobId]);

  async function run() {
    if (!canExecute || !deviceId || !command.trim() || busy) return;
    setSubmitting(true); setMessage("");
    requestId.current ??= crypto.randomUUID();
    try {
      const admitted = await runDiagnostic(deviceId, command, requestId.current);
      setResult(null); setRunning(true); setJobId(admitted.job_id); requestId.current = null;
      setRefresh(v => v + 1);
    } catch (error) {
      const code = (error as ApiError).body?.code;
      setMessage(code === "DIAGNOSTIC_UNAVAILABLE" ? "This command is not approved or its transport is not supported for this device."
        : code === "RATE_LIMITED_OR_RUNNING" ? "A command is running or was submitted within the last minute."
        : "Request refused or not confirmed. Retry uses the same request ID.");
    } finally { setSubmitting(false); }
  }
  function openRun(id: string) { if (!busy) { setResult(null); setJobId(id); setMessage(""); } }

  return <Box sx={{ display: "grid", gap: 2, maxWidth: 1000 }}>
    <Typography variant="h6">Debug / Parser</Typography>
    {allowed === false ? <Typography role="status">Super administrator role required.</Typography> : allowed ? <>
      <TextField select SelectProps={{ native: true }} label="Device" value={deviceId} disabled={busy}
        onChange={e => { setDeviceId(e.target.value); setPage(0); requestId.current = null; }}>
        <option value="">Select a device</option>
        {devices.map(d => <option key={d.deviceId} value={d.deviceId}>{d.target}</option>)}
      </TextField>
      <TextField label="Command" value={command} disabled={!deviceId || busy} inputProps={{ maxLength: 512 }}
        onChange={e => { setCommand(e.target.value); requestId.current = null; }} placeholder="Enter a diagnostic read command" />
      <Button variant="contained" onClick={run} disabled={!canExecute || !deviceId || !command.trim() || busy}>Run read</Button>
      {!canExecute && <Typography variant="body2">This session can inspect history and masked output.</Typography>}
    </> : null}
    {message && <Typography role="alert">{message}</Typography>}
    <Box role="status" aria-label="Output" sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
      <Typography variant="subtitle1">Output</Typography>
      {result && <Typography variant="body2">{result.target} · {result.command} · {result.state} · {result.masked ? "Masked" : "Administrator view"}</Typography>}
      {result?.legacySummary && <Typography variant="body2">Legacy summary; original output was not retained.</Typography>}
      {result?.terminalReason && <Typography variant="body2">{result.terminalReason}</Typography>}
      <Box component="pre" sx={{ fontFamily: "monospace", whiteSpace: "pre-wrap", overflowWrap: "anywhere", minHeight: 160 }}>
        {result ? result.output ?? "Output is not available for this job." : submitting ? "Submitting…" : jobId ? "Waiting for output…" : "No command has been run."}
      </Box>
    </Box>
    {allowed && <Box sx={{ overflowX: "auto" }}>
      <Typography variant="h6">Execution history</Typography>
      <table style={{ width: "100%", textAlign: "left" }}>
        <thead><tr><th>Time</th><th>Actor</th><th>Device</th><th>Command</th><th>Result</th><th>Output</th></tr></thead>
        <tbody>{history.map(row => <tr key={row.jobId}>
          <td>{new Date(row.submittedAt).toLocaleString()}</td><td>{row.actor}</td><td>{row.target}</td>
          <td>{row.command}</td><td>{row.state}</td><td><Button disabled={busy} onClick={() => openRun(row.jobId)}>View output</Button></td>
        </tr>)}</tbody>
      </table>
      {history.length === 0 && <Typography>No diagnostic runs on this page.</Typography>}
      <Button disabled={page === 0} onClick={() => setPage(v => v - 1)}>Previous</Button>
      <Button disabled={history.length < 50} onClick={() => setPage(v => v + 1)}>Next</Button>
      <Button onClick={() => setRefresh(v => v + 1)}>Refresh history</Button>
    </Box>}
  </Box>;
}
