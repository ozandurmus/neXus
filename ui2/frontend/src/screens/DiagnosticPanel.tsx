import { JobButton } from "../shell/JobWindow";
import { useEffect, useRef, useState } from "react";
import { Alert, Box, Button, Chip, Divider, List, ListItemButton, ListItemText, ListSubheader,
  Paper, Stack, Step, StepLabel, Stepper, Table, TableBody, TableCell, TableContainer, TableHead,
  TablePagination, TableRow, TextField, Typography } from "@mui/material";
import { getFmgDiagnostic, listFmgDiagnosticTargets, diagnosticHistory, runDiagnostic,
  type DiagnosticTarget, type DiagnosticResult, type DiagnosticHistoryRow, type ApiError } from "../auth/adminApi";

const TERMINAL = new Set(["COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN", "RECONCILED"]);
const statusLabel = (state: string) => state === "REQUESTED" ? "Queued"
  : state === "CLAIMED" || state === "EXECUTING" ? "Running" : state === "COMPLETED" ? "Done"
  : state === "CANCELLED" ? "Cancelled" : state === "OUTCOME_UNKNOWN" || state === "RECONCILED" ? "Outcome unknown" : "Failed";
const duration = (ms?: number | null) => ms == null ? "—" : `${(ms / 1000).toFixed(1)} s`;
const time = (value?: string | null) => value ? new Date(value).toLocaleString() : "Not started";
const failureReason = (result: DiagnosticResult) => {
  const reasons: Record<string, string> = {
    DIAGNOSTIC_READ_FAILED: "The device read failed.", DIAGNOSTIC_TARGET_UNAVAILABLE: "The device is unavailable for this read.",
    PRIOR_ATTEMPT_MAY_HAVE_SENT_COMMAND: "A previous attempt may have sent the command. It was not repeated.",
    PRE_CONTACT_STATE_UNKNOWN: "The connection state could not be confirmed. The command was not repeated.",
    DIAGNOSTIC_DISPATCH_UNKNOWN: "The command outcome could not be confirmed. The command was not repeated.",
    SAFE_RESULT_WRITE_FAILED: "The output could not be saved.", ATTEMPT_OUTCOME_WRITE_FAILED: "The result could not be recorded.",
  };
  if (result.state === "CANCELLED") return "This run was cancelled.";
  return reasons[result.terminalReason ?? ""] ?? (result.terminalReason
    ? result.terminalReason.toLowerCase().replaceAll("_", " ").replace(/^./, c => c.toUpperCase()) + "."
    : "This run did not complete successfully. Review the output or contact an administrator.");
};
function StatusChip({ state }: { state: string }) {
  return <Chip size="small" label={statusLabel(state)} color={state === "COMPLETED" ? "success"
    : TERMINAL.has(state) ? "error" : "info"} variant="outlined" />;
}

export function DiagnosticPanel() {
  const [allowed, setAllowed] = useState<boolean | null>(null);
  const [canExecute, setCanExecute] = useState(false);
  const [devices, setDevices] = useState<DiagnosticTarget[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [search, setSearch] = useState("");
  const [vendor, setVendor] = useState("");
  const [gateId, setGateId] = useState("");
  const [parameter, setParameter] = useState("");
  const [result, setResult] = useState<DiagnosticResult | null>(null);
  const [jobId, setJobId] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [running, setRunning] = useState(false);
  const [history, setHistory] = useState<DiagnosticHistoryRow[]>([]);
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [copied, setCopied] = useState(false);
  const requestId = useRef<string | null>(null);
  const busy = submitting || running;
  const device = devices.find(d => d.deviceId === deviceId);
  const selected = device?.commands.find(c => c.gate_id === gateId);
  const needsParameter = selected?.command_template.includes("<") ?? false;
  const needsVs = selected?.command_template.includes("<VSID>") ?? false;
  const validParameter = !needsParameter || (needsVs ? device?.virtualSystems?.includes(parameter)
    : /^[A-Za-z0-9_.-]{1,31}$/.test(parameter));
  const visibleDevices = devices.filter(d => (!vendor || d.vendor === vendor)
    && `${d.target} ${d.vendor}`.toLowerCase().includes(search.trim().toLowerCase()))
    .sort((a, b) => (a.cluster ?? "Unclustered").localeCompare(b.cluster ?? "Unclustered") || a.target.localeCompare(b.target));
  const groups = [...new Set(visibleDevices.map(d => d.cluster ?? "Unclustered"))];
  const failed = result && TERMINAL.has(result.state) && result.state !== "COMPLETED";
  const queued = result?.state === "REQUESTED";
  const output = result?.output;

  useEffect(() => {
    let active = true;
    listFmgDiagnosticTargets().then(r => {
      if (!active) return;
      if (!Array.isArray(r.targets)) return setAllowed(false);
      setDevices(r.targets); setAllowed(true); setCanExecute(r.canExecute === true);
    }).catch(() => { if (active) setAllowed(false); });
    return () => { active = false; };
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

  function selectDevice(id: string, vs = "") {
    setDeviceId(id); setGateId(""); setParameter(vs); setPage(0); setHistory([]); requestId.current = null;
    if (!busy) { setResult(null); setJobId(""); }
    if (vs) setGateId(devices.find(d => d.deviceId === id)?.commands.find(c => c.command_template.includes("<VSID>"))?.gate_id ?? "");
  }
  async function run() {
    if (!canExecute || !deviceId || !selected?.runnable || !validParameter || busy) return;
    setSubmitting(true); setMessage(""); setCopied(false);
    requestId.current ??= crypto.randomUUID();
    try {
      const admitted = await runDiagnostic(deviceId, gateId, needsParameter ? parameter : undefined, requestId.current);
      setResult(null); setRunning(true); setJobId(admitted.job_id); requestId.current = null;
      setRefresh(v => v + 1);
    } catch (error) {
      const code = (error as ApiError).body?.code;
      setMessage(code === "OUTSIDE_JOB_WINDOW" ? String((error as ApiError).body?.message ?? "Device jobs are outside the scheduled window.")
        : code === "DIAGNOSTIC_UNAVAILABLE" ? "This read is unavailable for this device."
        : code === "RATE_LIMITED_OR_RUNNING" ? "A command is running or was submitted within the last minute."
        : "Request refused or not confirmed. Retry uses the same request ID.");
    } finally { setSubmitting(false); }
  }
  function openRun(id: string) {
    if (!busy && id !== jobId) { setResult(null); setJobId(id); setMessage(""); setCopied(false); }
  }
  async function copyOutput() {
    try { await navigator.clipboard.writeText(output ?? ""); setCopied(true); }
    catch { setMessage("Copy was unavailable. Select the output text or download it instead."); }
  }
  function downloadOutput() {
    const url = URL.createObjectURL(new Blob([output ?? ""], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a"); link.href = url; link.download = "diagnostic-output.txt";
    link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return <Box sx={{ display: "grid", gap: 2, minWidth: 0 }}>
    <Box><Typography variant="h5">Diagnostics</Typography>
      <Typography color="text.secondary">Run an approved read command on one device and read its output. Only commands neXus already uses are listed.</Typography></Box>
    {allowed === null && <Typography role="status">Loading devices and approved commands…</Typography>}
    {allowed === false && <Alert severity="error">Diagnostics could not be loaded or access is unavailable.</Alert>}
    {message && <Alert severity="warning" onClose={() => setMessage("")}>{message}</Alert>}
    {allowed && <>
      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "320px minmax(0, 1fr)" }, gap: 2, alignItems: "start" }}>
        <Paper variant="outlined" sx={{ p: 2, minWidth: 0 }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
            <Typography variant="h6">Devices</Typography><Chip size="small" label={`${visibleDevices.length} / ${devices.length}`} /></Stack>
          <TextField fullWidth size="small" label="Search devices" placeholder="Name or vendor" value={search} onChange={e => setSearch(e.target.value)} />
          <Stack direction="row" useFlexGap flexWrap="wrap" gap={0.75} sx={{ my: 1.5 }}>
            {["", ...[...new Set(devices.map(d => d.vendor))].sort()].map(v => <Chip key={v} label={v || "All vendors"} size="small"
              color={vendor === v ? "primary" : "default"} aria-pressed={vendor === v} onClick={() => setVendor(v)} />)}
          </Stack>
          <Button size="small" disabled={!deviceId || busy} onClick={() => selectDevice("")}>All devices history</Button>
          <List dense aria-label="Devices" sx={{ maxHeight: { xs: 240, md: 640 }, overflow: "auto", mx: -1 }}>
            {groups.map(group => <li key={group}><ul style={{ padding: 0, listStyle: "none" }}>
              <ListSubheader sx={{ bgcolor: "background.paper" }}>{group}</ListSubheader>
              {visibleDevices.filter(d => (d.cluster ?? "Unclustered") === group).map(d => <Box key={d.deviceId} component="li">
                <ListItemButton selected={deviceId === d.deviceId} disabled={busy} aria-label={d.target} aria-pressed={deviceId === d.deviceId}
                  onClick={() => selectDevice(d.deviceId)} sx={{ borderRadius: 1 }}>
                  <ListItemText primary={d.target} secondary={d.vendor} primaryTypographyProps={{ sx: { overflowWrap: "anywhere" } }} />
                </ListItemButton>
                {d.virtualSystems?.map(vs => <ListItemButton key={vs} disabled={busy} sx={{ pl: 4, borderRadius: 1 }}
                  aria-label={`${d.target} VS ${vs}`} selected={deviceId === d.deviceId && parameter === vs}
                  onClick={() => selectDevice(d.deviceId, vs)}><ListItemText primary={`VS ${vs}`} /></ListItemButton>)}
              </Box>)}
            </ul></li>)}
          </List>
          {visibleDevices.length === 0 && <Typography color="text.secondary">No matching devices.</Typography>}
        </Paper>
        <Stack spacing={2} sx={{ minWidth: 0 }}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h6">{device ? `${device.target} · Approved reads` : "Select a device"}</Typography>
            {!canExecute && <Alert severity="info" sx={{ mt: 1 }}>This session can inspect history and masked output.</Alert>}
            {device && <>
              <List aria-label="Approved commands" sx={{ maxHeight: 320, overflow: "auto" }}>
                {[...device.commands].sort((a, b) => Number(a.command_template.includes("<VSID>")) - Number(b.command_template.includes("<VSID>"))
                  || (a.description ?? a.command_template).localeCompare(b.description ?? b.command_template)).map(c =>
                  <ListItemButton key={c.gate_id} selected={gateId === c.gate_id} disabled={busy} aria-pressed={gateId === c.gate_id}
                    onClick={() => { setGateId(c.gate_id); setParameter(""); requestId.current = null; }} sx={{ borderRadius: 1, mb: 0.5 }}>
                    <ListItemText primary={c.description ?? c.command_template} secondary={c.command_template}
                      secondaryTypographyProps={{ sx: { fontFamily: "monospace", overflowWrap: "anywhere", mt: 0.5 } }} />
                  </ListItemButton>)}
              </List>
              {device.commands.length === 0 && <Typography>No approved reads are available for this device.</Typography>}
              <Divider sx={{ mb: 2 }} />
              <Stack direction={{ xs: "column", sm: "row" }} spacing={2} alignItems={{ sm: "flex-start" }}>
                {needsVs ? <TextField select SelectProps={{ native: true }} label="Virtual system" value={parameter} disabled={busy}
                  sx={{ minWidth: 210 }} helperText={device.virtualSystems?.length ? "Select an inventoried virtual system" : "No virtual systems in stored inventory"}
                  onChange={e => { setParameter(e.target.value); requestId.current = null; }}>
                  <option value="">Select a virtual system</option>
                  {device.virtualSystems?.map(vs => <option key={vs} value={vs}>VS {vs}</option>)}
                </TextField> : needsParameter ? <TextField label="Parameter" value={parameter} disabled={busy} inputProps={{ maxLength: 31 }}
                  onChange={e => { setParameter(e.target.value); requestId.current = null; }} error={parameter.length > 0 && !validParameter}
                  helperText="One token: letters, digits, underscore, period or hyphen" /> : null}
                {canExecute && selected?.runnable && <JobButton variant="contained" onClick={run} disabled={!validParameter || busy} sx={{ whiteSpace: "nowrap" }}>
                  {submitting ? "Submitting…" : "Run read"}</JobButton>}
              </Stack>
            </>}
          </Paper>
          <Paper variant="outlined" sx={{ p: 2, minWidth: 0 }} aria-label="Output">
            <Stack direction="row" useFlexGap flexWrap="wrap" gap={1} alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="h6" sx={{ flexGrow: 1 }}>Output</Typography>
              {result && <Chip size="small" label={result.masked ? "Masked" : "Administrator view"} />}
              <Button size="small" disabled={output == null} onClick={copyOutput}>{copied ? "Copied" : "Copy"}</Button>
              <Button size="small" disabled={output == null} onClick={downloadOutput}>Download .txt</Button>
            </Stack>
            {result && <>
              <Typography variant="body2" sx={{ overflowWrap: "anywhere" }}>{result.target} · {result.description ?? result.command}</Typography>
              <Stepper activeStep={queued ? 0 : TERMINAL.has(result.state) ? 2 : 1} sx={{ my: 2 }} alternativeLabel>
                <Step><StepLabel>{queued && result.queuePosition != null ? `Queued · Position ${result.queuePosition}` : "Queued"}</StepLabel></Step>
                <Step><StepLabel>Running</StepLabel></Step>
                <Step completed={result.state === "COMPLETED"}><StepLabel error={!!failed}>{failed ? statusLabel(result.state) : "Done"}</StepLabel></Step>
              </Stepper>
              <Typography variant="caption" color="text.secondary">Started: {time(result.startedAt)} · Duration: {duration(result.durationMs)}</Typography>
              {failed && <Alert severity="error" sx={{ mt: 1 }}>{failureReason(result)}</Alert>}
              {result.legacySummary && <Alert severity="info" sx={{ mt: 1 }}>Legacy summary; original output was not retained.</Alert>}
            </>}
            {output != null ? <Box sx={{ mt: 2, maxHeight: 480, overflow: "auto", bgcolor: "action.hover", borderRadius: 1, py: 1 }}>
              <Box component="pre" sx={{ m: 0, minWidth: "max-content", fontFamily: "monospace", fontSize: 13 }}>
                {output.split("\n").map((line, i) => <Box component="span" key={i} sx={{ display: "flex" }}>
                  <Box component="span" aria-hidden="true" sx={{ userSelect: "none", color: "text.secondary", textAlign: "right", minWidth: "4em", pr: 2, mr: 2, borderRight: "1px solid", borderColor: "divider" }}>{i + 1}</Box>
                  <span>{line}{"\n"}</span>
                </Box>)}
              </Box>
            </Box> : <Typography role="status" color="text.secondary" sx={{ py: 4 }}>
              {queued ? `Waiting for a worker${result.queuePosition != null ? ` · Queue position ${result.queuePosition}` : ""}.`
                : result && !TERMINAL.has(result.state) ? "Command is running. Output will appear when it finishes."
                : result ? "No output was retained for this run." : submitting ? "Submitting…" : jobId ? "Waiting for job status…" : "No command has been run."}
            </Typography>}
          </Paper>
        </Stack>
      </Box>
      <Paper variant="outlined" sx={{ minWidth: 0 }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ p: 2 }}>
          <Box><Typography variant="h6">Execution history</Typography><Typography variant="body2" color="text.secondary">{device?.target ?? "All devices"}</Typography></Box>
          <Button onClick={() => setRefresh(v => v + 1)}>Refresh history</Button>
        </Stack>
        <TableContainer><Table size="small" aria-label="Execution history">
          <TableHead><TableRow>{["Time", "Actor", ...(!deviceId ? ["Device"] : []), "Command", "Status", "Duration", "Output"].map(label => <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead>
          <TableBody>{history.filter(row => !deviceId || row.targetDeviceId === deviceId).map(row => <TableRow key={row.jobId} hover>
            <TableCell sx={{ whiteSpace: "nowrap" }}>{time(row.submittedAt)}</TableCell><TableCell>{row.actor}</TableCell>
            {!deviceId && <TableCell>{row.target}</TableCell>}<TableCell sx={{ maxWidth: 400, overflowWrap: "anywhere" }}>{row.description ?? row.command}</TableCell>
            <TableCell><StatusChip state={row.state} /></TableCell><TableCell sx={{ whiteSpace: "nowrap" }}>{duration(row.durationMs)}</TableCell>
            <TableCell><Button disabled={busy} onClick={() => openRun(row.jobId)}>View</Button></TableCell>
          </TableRow>)}</TableBody>
        </Table></TableContainer>
        {history.length === 0 && <Typography color="text.secondary" sx={{ p: 2 }}>No diagnostic runs on this page.</Typography>}
        <TablePagination component="div" count={-1} page={page} rowsPerPage={50} rowsPerPageOptions={[50]}
          onPageChange={(_, value) => { setPage(value); setHistory([]); }} nextIconButtonProps={{ disabled: history.length < 50 }} />
      </Paper>
    </>}
  </Box>;
}
