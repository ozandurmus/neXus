import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { getFmgDiagnosticPorts, getFmgDiagnostic, listFmgDiagnosticTargets, previewFmgDiagnostic, runFmgDiagnostic,
  type DiagnosticTarget, type DiagnosticPreview, type DiagnosticResult } from "../auth/adminApi";

const TERMINAL = new Set(["COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN"]);

/** Typed text is matched locally against a gated command; the browser submits only target and port. */
export function DiagnosticPanel() {
  const [allowed, setAllowed] = useState<boolean | null>(null);
  const [devices, setDevices] = useState<DiagnosticTarget[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [ports, setPorts] = useState<string[]>([]);
  const [command, setCommand] = useState("");
  const [preview, setPreview] = useState<DiagnosticPreview | null>(null);
  const [result, setResult] = useState<DiagnosticResult | null>(null);
  const [jobId, setJobId] = useState("");
  const [message, setMessage] = useState("");
  const typedPort = /^diagnose fmnetwork interface detail ([A-Za-z0-9_.-]{1,31})$/.exec(command)?.[1];

  useEffect(() => {
    listFmgDiagnosticTargets().then(r => {
      if (!Array.isArray(r.targets)) return setAllowed(false);
      setDevices(r.targets); setAllowed(true);
    })
      .catch(() => setAllowed(false));
  }, []);
  useEffect(() => {
    setPorts([]); setCommand(""); setPreview(null); setResult(null); setJobId("");
    if (!deviceId) return;
    getFmgDiagnosticPorts(deviceId).then(r => setPorts(r.ports)).catch(() => setPorts([]));
  }, [deviceId]);
  useEffect(() => {
    setPreview(null); setMessage(""); setResult(null); setJobId("");
    if (deviceId && typedPort && ports.includes(typedPort)) previewFmgDiagnostic(deviceId, typedPort).then(setPreview)
      .catch(() => setMessage("Diagnostic unavailable for this target or port. Refresh its inventory first."));
  }, [deviceId, command, ports]);
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
    if (!preview || command !== preview.command) return;
    setMessage(""); setResult(null);
    try {
      const admitted = await runFmgDiagnostic(deviceId, preview.port, crypto.randomUUID());
      setJobId(admitted.job_id);
    } catch { setMessage("Diagnostic request refused. Check role, target, gate, or one-minute limit."); }
  };

  return <Box sx={{ display: "grid", gap: 2, maxWidth: 700 }}>
    <Typography variant="h6">Debug / Parser</Typography>
    <Typography variant="body2">Type a gated read command. The device response is reduced to safe fields.</Typography>
    {allowed === false ? <Typography role="status">Super administrator role required.</Typography> : allowed ? <>
      <TextField select SelectProps={{ native: true }} label="Device" value={deviceId} onChange={e => setDeviceId(e.target.value)}>
        <option value="">Select a FortiManager</option>
        {devices.map(d => <option key={d.deviceId} value={d.deviceId}>{d.target}</option>)}
      </TextField>
      <TextField label="Command" value={command} onChange={e => setCommand(e.target.value)} disabled={!deviceId}
        placeholder="diagnose fmnetwork interface detail port5" helperText="FortiManager interface detail; the port must exist in current inventory." />
      {command && (!typedPort || !ports.includes(typedPort)) && <Typography role="alert">Command or port is not gated for this device.</Typography>}
      {preview && <Box sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
        <Typography>Target: {preview.target} · Port: {preview.port}</Typography>
        <Typography sx={{ fontFamily: "monospace" }}>Command: {preview.command}</Typography>
        <Typography variant="body2">Read only · Gate V{preview.gateRevision} · {preview.timeoutSeconds}s timeout · no retry · {preview.frequency}</Typography>
        <Typography variant="body2">Result: Status presence/token, bounded line count, masked shape ID. No raw response.</Typography>
        <Button variant="contained" onClick={run} disabled={command !== preview.command || (!!jobId && (!result || !TERMINAL.has(result.state)))}>Run read</Button>
      </Box>}
      {result && <Box role="status">
        <Typography>Job: {result.state}</Typography>
        <Typography sx={{ fontFamily: "monospace", whiteSpace: "pre-wrap" }}>Masked output:{"\n"}{result.maskedOutput ?? "[UNAVAILABLE]"}</Typography>
        <Typography variant="body2">Status: {result.statusPresent ? result.statusToken : "ABSENT"} · Lines: {result.lineCount ?? "—"} · Shape: {result.shapeId ?? "—"}</Typography>
        <Typography>Physical link: UNKNOWN until vendor semantics are proven.</Typography>
      </Box>}
      {message && <Typography role="alert">{message}</Typography>}
    </> : null}
  </Box>;
}
