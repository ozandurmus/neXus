import { useCallback, useEffect, useRef, useState, type DragEvent } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import {
  getRevertJob,
  getSanitizeJob,
  getSanitizerInfo,
  revertDownloadHref,
  sanitizeDownloadHref,
  uploadForSanitizer,
  type ApiError,
  type SanitizerInfo,
  type SanitizerState,
} from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import type { Tone } from "../shell/tone";
import { m3, MONO } from "../theme/m3Theme";

/** Byte counts as an operator reads them (files run to 2 GB). */
export function sanitizerBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes, unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return unit === 0 ? `${bytes} B` : `${value.toFixed(1)} ${units[unit]}`;
}

/** What each count means, in the operator's words; unknown kinds are shown by their key. */
export const COUNT_LABELS: Record<string, string> = {
  ipv4: "IPv4 addresses", ipv6: "IPv6 addresses", cidr: "Networks (CIDR)", mac: "MAC addresses",
  hostname: "Host names", fqdn: "Domain names (FQDN)", email: "E-mail addresses", url_host: "URL hosts",
  device_name: "Device names (inventory pseudonyms)", dns_name: "DNS names in packets", packets: "Packets",
  payload_bytes_zeroed: "Payload bytes zeroed", frames_without_ip: "Frames without IP (zeroed)",
  embedded_objects_removed: "Embedded objects removed", reverted: "Tokens reverted",
};

const STATE_TONE: Record<SanitizerState, Tone> = { RUNNING: "attn", SUCCEEDED: "ok", FAILED: "bad" };

/**
 * The refusal names the roles (post-release review 2026-10-18, 3.3; the roles are ActionRegistry's DATA_SANITIZE_RUN /
 * DATA_SANITIZE_REVERT: sanitize -- operator, operation_admin, security_admin; revert -- security_admin, and the user who
 * ran the sanitize job). The sentence is the same whichever mode is locked, so a reader learns both at once.
 */
export const SANITIZER_ROLE_REFUSAL = "Your role does not allow this action · sanitize: Operator, Operation admin, Security admin · "
  + "revert: Security admin (Operator and Operation admin on their own jobs)";

/** The tool's contract under the title (review §3 "Tool page"): sentence one what it does, sentence two what it never does. */
export const SANITIZER_CONTRACT = "Replaces addresses, MAC addresses, host and domain names and e-mails in pcap/pcapng, log, CSV, XLSX and JSON files "
  + "with keyed tokens, and reverts a sanitized file that comes back. "
  + "It never keeps a file: the upload is deleted when the job ends and the result after one download; only the encrypted token vault stays inside neXus.";
const ACCEPT = ".pcap,.pcapng,.cap,.log,.txt,.csv,.xlsx,.json,.ndjson";

/** The server's refusal, in its own words (closed codes and reasons; never a file name or value). */
export function sanitizerError(err: unknown, fallback: string): string {
  const failure = err as ApiError | undefined;
  if (failure?.status === 403) {
    const reason = failure.body?.reason;
    return typeof reason === "string" && reason ? reason : "Your role does not allow this action.";
  }
  if (failure?.status === 0) return "The request did not complete. Check the connection and retry.";
  const reason = failure?.body?.reason ?? failure?.body?.error;
  return typeof reason === "string" && reason ? reason : fallback;
}

type Mode = "sanitize" | "revert";
interface Outcome {
  readonly mode: Mode; readonly id: string; readonly state: SanitizerState; readonly fileType: string | null;
  readonly inputBytes: number | null; readonly outputBytes: number | null; readonly counts: Record<string, number>;
  readonly failure: string | null; readonly outputState: string; readonly vaultExpires?: string; readonly vaultEntries?: number;
}

export function SanitizerScreen() {
  const [info, setInfo] = useState<SanitizerInfo | null>(null);
  const [mode, setMode] = useState<Mode>("sanitize");
  const [sourceJob, setSourceJob] = useState("");
  const [allVaults, setAllVaults] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<{ name: string; sent: number; total: number } | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [downloaded, setDownloaded] = useState(false);
  const [dragging, setDragging] = useState(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);

  useEffect(() => {
    getSanitizerInfo().then(data => { if (mounted.current) setInfo(data); })
      .catch(err => { if (mounted.current) setError(sanitizerError(err, "The sanitizer could not be loaded.")); });
  }, []);

  const refresh = useCallback(async (kind: Mode, id: string) => {
    if (kind === "sanitize") {
      const job = await getSanitizeJob(id);
      return { mode: kind, id, state: job.state, fileType: job.file_type, inputBytes: job.input_bytes, outputBytes: job.output_bytes,
        counts: job.counts ?? {}, failure: job.failure_reason, outputState: job.output_state, vaultExpires: job.vault_expires_at,
        vaultEntries: job.vault_entries } satisfies Outcome;
    }
    const job = await getRevertJob(id);
    return { mode: kind, id, state: job.state, fileType: job.file_type, inputBytes: job.input_bytes, outputBytes: job.output_bytes,
      counts: job.counts ?? {}, failure: job.failure_reason, outputState: job.output_state } satisfies Outcome;
  }, []);

  // A running job is followed until it ends.
  useEffect(() => {
    if (!outcome || outcome.state !== "RUNNING") return;
    const timer = setInterval(() => {
      refresh(outcome.mode, outcome.id).then(next => { if (mounted.current) setOutcome(next); })
        .catch(err => { if (mounted.current) setError(sanitizerError(err, "The job could not be read.")); });
    }, 2000);
    return () => clearInterval(timer);
  }, [outcome, refresh]);

  const submit = async (file: File | undefined) => {
    if (!file) return;
    setError(null); setOutcome(null); setDownloaded(false);
    if (info && file.size > info.limits.max_file_bytes) {
      setError(`A file may be at most ${sanitizerBytes(info.limits.max_file_bytes)}.`);
      return;
    }
    const fields: Record<string, string> = {};
    if (mode === "revert" && sourceJob.trim()) fields.source_job_id = sourceJob.trim();
    if (mode === "revert" && !sourceJob.trim() && allVaults) fields.scope = "all";
    setBusy(true); setProgress({ name: file.name, sent: 0, total: file.size });
    try {
      const accepted = await uploadForSanitizer(mode, file, fields, (sent, total) => {
        if (mounted.current) setProgress({ name: file.name, sent, total });
      });
      if (!mounted.current) return;
      const id = mode === "sanitize" ? accepted.job_id : accepted.revert_id;
      if (!id) throw { status: 500, body: { error: "NO_JOB_ID" } } satisfies ApiError;
      setOutcome(await refresh(mode, id));
    } catch (err) {
      if (mounted.current) setError(sanitizerError(err, "The file was refused."));
    } finally {
      if (mounted.current) { setBusy(false); setProgress(null); }
    }
  };

  const onDrop = (event: DragEvent<HTMLElement>) => {
    event.preventDefault();
    setDragging(false);
    if (!busy) void submit(event.dataTransfer.files?.[0]);
  };

  const permissions = info?.permissions;
  const allowed = mode === "sanitize" ? permissions?.run : permissions?.revert;
  const limits = info?.limits;

  return (
    <ScreenRoot>
      {/* Tool page (UI review 2026-10-18 §3, roadmap 3.3): the contract in two sentences -- what it does, what it never does. */}
      <ScreenHeader title="Data Sanitizer" subtitle={SANITIZER_CONTRACT} />
      {error && <Alert severity="error">{error}</Alert>}
      {!info && !error && <Typography role="status">Loading…</Typography>}
      {limits && !limits.vault_available && <Alert severity="warning">
        The mapping vault is not available on this service (no artefact-store key), so files cannot be sanitized or reverted.
      </Alert>}

      {/* One input region: mode, the drop zone (locked with its reason inline), and the limits beneath it. */}
      {permissions && (
        <Card component="section" aria-label="Sanitizer input" sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}>
          <Stack direction="row" spacing={2} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
            <ToggleButtonGroup exclusive size="small" value={mode} aria-label="Sanitizer mode"
              onChange={(_e, value: Mode | null) => { if (value) { setMode(value); setOutcome(null); setError(null); } }}>
              <ToggleButton value="sanitize" disabled={!permissions.run}>Sanitize</ToggleButton>
              <ToggleButton value="revert" disabled={!permissions.revert}>Revert</ToggleButton>
            </ToggleButtonGroup>
            {mode === "revert" && <>
              <TextField size="small" label="Sanitize job id (optional)" value={sourceJob} disabled={busy}
                inputProps={{ maxLength: 36 }} onChange={e => setSourceJob(e.target.value)} sx={{ minWidth: 320 }} />
              {permissions.revert_any_job && <FormControlLabel label="Use every vault, not only my jobs"
                control={<Checkbox checked={allVaults} disabled={busy || !!sourceJob.trim()} onChange={e => setAllVaults(e.target.checked)} />} />}
            </>}
          </Stack>
          <Typography variant="body2" sx={{ mt: 1, color: m3.onSurfaceVar }}>
            {mode === "sanitize"
              ? "Every token is recorded in the vault, so a file that comes back can be reverted here. Payload bytes of captures are zeroed and cannot be restored."
              : "Only tokens found in the vault are replaced — also inside new text around them; anything else stays as it is."}
          </Typography>
          <Box role="button" tabIndex={0} aria-label="Drop a file here or choose one"
            onClick={() => { if (!busy && allowed) document.getElementById("sanitizer-file")?.click(); }}
            onKeyDown={e => { if ((e.key === "Enter" || e.key === " ") && !busy && allowed) document.getElementById("sanitizer-file")?.click(); }}
            onDragOver={e => { e.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={onDrop}
            sx={{ mt: 2, p: 4, textAlign: "center", borderRadius: 2, cursor: busy || !allowed ? "default" : "pointer",
              border: `2px dashed ${dragging ? m3.primary : m3.outlineVar}`, bgcolor: dragging ? m3.scLow : "transparent" }}>
            <Typography role={allowed ? undefined : "status"}>{allowed ? (mode === "sanitize" ? "Drop a file to sanitize, or click to choose one" : "Drop a returned file to revert, or click to choose one")
              : SANITIZER_ROLE_REFUSAL}</Typography>
          </Box>
          <input id="sanitizer-file" aria-label="File" type="file" accept={ACCEPT} hidden
            onChange={e => { void submit(e.target.files?.[0]); e.target.value = ""; }} />
          {limits && <Typography variant="caption" sx={{ display: "block", mt: 1.5, color: m3.onSurfaceVar }}>
            At most {sanitizerBytes(limits.max_file_bytes)} per file · the type is read from the content · uploads are deleted when
            the job ends, results after one download or {limits.output_ttl_minutes} minutes · the token-to-original vault is kept
            inside neXus for {limits.vault_retention_days} days, encrypted
          </Typography>}
          {progress && <Box role="status" aria-label="Upload progress" sx={{ mt: 1 }}>
            <Typography variant="body2">Uploading {progress.name}: {sanitizerBytes(progress.sent)} of {sanitizerBytes(progress.total)}</Typography>
            <LinearProgress variant="determinate" value={progress.total ? Math.min(100, (100 * progress.sent) / progress.total) : 0} />
          </Box>}
        </Card>
      )}

      {outcome && <OutcomeView outcome={outcome} downloaded={downloaded} onDownload={() => setDownloaded(true)} />}
    </ScreenRoot>
  );
}

/** State, sizes, counts per kind and the one-time download -- never a value from the file. */
export function OutcomeView({ outcome, downloaded, onDownload }: {
  readonly outcome: Outcome; readonly downloaded: boolean; readonly onDownload: () => void;
}) {
  const entries = Object.entries(outcome.counts).sort(([a], [b]) => a.localeCompare(b));
  const href = outcome.mode === "sanitize" ? sanitizeDownloadHref(outcome.id) : revertDownloadHref(outcome.id);
  const ready = outcome.state === "SUCCEEDED" && outcome.outputState === "READY" && !downloaded;
  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Sanitizer result">
      <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
        <Typography variant="h6">{outcome.mode === "sanitize" ? "Sanitize job" : "Revert job"}</Typography>
        <StatusChip tone={STATE_TONE[outcome.state]} label={outcome.state} />
        {outcome.fileType && <StatusChip tone="neutral" label={outcome.fileType.toUpperCase()} dense />}
        <Typography variant="caption" sx={{ fontFamily: MONO }}>{outcome.id}</Typography>
      </Stack>
      {outcome.state === "RUNNING" && <Box role="status" aria-label="Processing" sx={{ mt: 1 }}>
        <Typography variant="body2">Processing…</Typography><LinearProgress />
      </Box>}
      {outcome.failure && <Alert severity="error" sx={{ mt: 1 }}>The job failed: {outcome.failure}</Alert>}
      <Typography variant="body2" sx={{ mt: 1 }}>
        In {sanitizerBytes(outcome.inputBytes)} · out {sanitizerBytes(outcome.outputBytes)}
        {outcome.vaultEntries !== undefined && ` · ${outcome.vaultEntries} vault entries, kept until ${(outcome.vaultExpires ?? "").slice(0, 10)}`}
      </Typography>
      <Table size="small" aria-label="Counts per kind" sx={{ mt: 1, maxWidth: 560 }}>
        <TableHead><TableRow><TableCell>Kind</TableCell><TableCell align="right">Replaced</TableCell></TableRow></TableHead>
        <TableBody>
          {entries.map(([kind, n]) => <TableRow key={kind}><TableCell>{COUNT_LABELS[kind] ?? kind}</TableCell><TableCell align="right">{n}</TableCell></TableRow>)}
          {!entries.length && <TableRow><TableCell colSpan={2}>{outcome.state === "RUNNING" ? "—" : "Nothing to replace"}</TableCell></TableRow>}
        </TableBody>
      </Table>
      {outcome.state === "SUCCEEDED" && <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 2 }}>
        {ready
          ? <M3Button emphasis="filled" href={href} onClick={onDownload}>Download</M3Button>
          : <Typography variant="body2">{downloaded || outcome.outputState === "DOWNLOADED" ? "Downloaded — neXus kept no copy of the file."
            : "The result has expired; run the job again."}</Typography>}
        {ready && <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>One download: the file is deleted from neXus afterwards.</Typography>}
      </Stack>}
    </Card>
  );
}
