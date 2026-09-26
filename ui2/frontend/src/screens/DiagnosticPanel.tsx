import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { getFmgDiagnosticPorts, getFmgDiagnostic, listFmgDiagnosticTargets, previewFmgDiagnostic, runFmgDiagnostic,
  type DiagnosticTarget, type DiagnosticPreview, type DiagnosticResult } from "../auth/adminApi";

const TERMINAL = new Set(["COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN"]);

/** The browser selects a fixed template and inventoried port; it never supplies a command string. */
export function DiagnosticPanel() {
  const [allowed, setAllowed] = useState(false);
  const [devices, setDevices] = useState<DiagnosticTarget[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [ports, setPorts] = useState<string[]>([]);
  const [port, setPort] = useState("");
  const [preview, setPreview] = useState<DiagnosticPreview | null>(null);
  const [result, setResult] = useState<DiagnosticResult | null>(null);
  const [jobId, setJobId] = useState("");
  const [message, setMessage] = useState("");

  useEffect(() => {
    fetch("/session/status", { credentials: "include" }).then(r => r.json())
      .then(s => setAllowed(Array.isArray(s.role_tokens) && s.role_tokens.includes("role:security_admin")))
      .catch(() => setAllowed(false));
  }, []);
  useEffect(() => {
    if (allowed) listFmgDiagnosticTargets().then(r => setDevices(r.targets)).catch(() => setDevices([]));
  }, [allowed]);
  useEffect(() => {
    setPorts([]); setPort(""); setPreview(null); setResult(null); setJobId("");
    if (!deviceId) return;
    getFmgDiagnosticPorts(deviceId).then(r => setPorts(r.ports)).catch(() => setPorts([]));
  }, [deviceId]);
  useEffect(() => {
    setPreview(null); setMessage(""); setResult(null); setJobId("");
    if (deviceId && port) previewFmgDiagnostic(deviceId, port).then(setPreview)
      .catch(() => setMessage("Diagnostic unavailable for this target or port. Refresh its inventory first."));
  }, [deviceId, port]);
  useEffect(() => {
    if (!jobId) return;
    let active = true;
    const poll = () => getFmgDiagnostic(jobId).then(r => {
      if (!active) return;
      setResult(r);
      if (TERMINAL.has(r.state)) clearInterval(timer);
    }).catch(() => { if (active) setMessage("Could not read the job result."); });
    const timer = setInterval(poll, 2000);
    poll();
    return () => { active = false; clearInterval(timer); };
  }, [jobId]);

  const run = async () => {
    if (!preview) return;
    setMessage(""); setResult(null);
    try {
      const admitted = await runFmgDiagnostic(deviceId, port, crypto.randomUUID());
      setJobId(admitted.job_id);
    } catch { setMessage("Diagnostic request refused. Check role, target, gate, or one-minute limit."); }
  };

  return <Box sx={{ display: "grid", gap: 2, maxWidth: 700 }}>
    <Typography variant="h6">Approved diagnostics</Typography>
    <Typography variant="body2">Only signed-off read templates appear here. Every execution is a recorded neXus job.</Typography>
    {!allowed ? <Typography role="status">Super administrator role required.</Typography> : <>
      <TextField select SelectProps={{ native: true }} label="Device" value={deviceId} onChange={e => setDeviceId(e.target.value)}>
        <option value="">Select a FortiManager</option>
        {devices.map(d => <option key={d.deviceId} value={d.deviceId}>{d.target}</option>)}
      </TextField>
      <TextField select SelectProps={{ native: true }} label="Inventoried physical port" value={port} onChange={e => setPort(e.target.value)} disabled={!deviceId}>
        <option value="">Select a port</option>
        {ports.map(p => <option key={p} value={p}>{p}</option>)}
      </TextField>
      {preview && <Box sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
        <Typography>Target: {preview.target} · Port: {preview.port}</Typography>
        <Typography sx={{ fontFamily: "monospace" }}>Command: {preview.command}</Typography>
        <Typography variant="body2">Read only · Gate V{preview.gateRevision} · {preview.timeoutSeconds}s timeout · no retry · {preview.frequency}</Typography>
        <Typography variant="body2">Result: Status presence/token, bounded line count, masked shape ID. No raw response.</Typography>
        <Button variant="contained" onClick={run} disabled={!!jobId && (!result || !TERMINAL.has(result.state))}>Run approved read</Button>
      </Box>}
      {result && <Box role="status">
        <Typography>Job: {result.state}</Typography>
        <Typography>Status field: {result.statusPresent ? result.statusToken : "ABSENT"} · Lines: {result.lineCount ?? "—"} · Shape: {result.shapeId ?? "—"}</Typography>
        <Typography>Physical link: UNKNOWN until vendor semantics are proven.</Typography>
      </Box>}
      {message && <Typography role="alert">{message}</Typography>}
    </>}
  </Box>;
}
