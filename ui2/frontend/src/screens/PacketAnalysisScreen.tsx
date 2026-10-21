import { useCallback, useEffect, useRef, useState } from "react";
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
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  analyzePacketCase,
  createPacketCase,
  deletePacketCase,
  getPacketCase,
  getPacketReport,
  listPacketCases,
  packetCaptureDownloadHref,
  uploadPacketCapture,
  type PacketCase,
  type PacketCaseDetail,
  type PacketCaseList,
  type PacketReport,
  type PacketAnalysisWindow,
  type PacketRunSummary,
} from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { ROLE_REFUSAL } from "../shell/actionAccess";
import { statusWord } from "../shell/presenters";
import type { Tone } from "../shell/tone";
import { m3, MONO } from "../theme/m3Theme";
import { PacketCaseWorkspace } from "./packetanalysis/PacketCaseWorkspace";
import { PacketHostTelemetry } from "./packetanalysis/PacketHostTelemetry";
import { packetBytes, packetError } from "./packetanalysis/packetFormat";

export { packetBytes, packetError };

const STATE_TONE: Record<string, Tone> = {
  OPEN: "neutral", QUEUED: "attn", ANALYZING: "attn", ANALYZED: "ok", FAILED: "bad",
  REQUESTED: "attn", RUNNING: "attn", COMPLETED: "ok",
};
const ACTIVE = new Set(["QUEUED", "ANALYZING"]);

/** What a failed run's closed reason code means for the user (the code itself stays visible). */
export function packetFailureText(reason: string | null | undefined): string | null {
  switch (reason) {
    case "ANALYSIS_MEMORY_LIMIT":
      return "The analysis needs more memory than its budget allows. Narrow the analysis window (start and end time) or analyse fewer or smaller captures, then run it again.";
    case "ANALYSIS_WINDOW_REQUIRED":
      return "The capture range is larger than the analysis budget and no bounded window covers at least two capture points. Choose an analysis window (start and end time) that covers the incident, then run it again.";
    default:
      return null;
  }
}

/** The window the budget guard chose, as one line; null when the whole range was analysed. */
export function packetWindowText(window: PacketAnalysisWindow | undefined): string | null {
  if (!window || window.mode !== "guarded" || window.start == null || window.end == null) return null;
  const seconds = window.seconds ?? window.end - window.start;
  return `Analysed window: ${new Date(window.start * 1000).toISOString().replace(/\.\d+Z$/, "Z")} + ${seconds} s, reason: budget (more than ${window.max_packets_per_capture ?? "the budgeted"} packets per capture in the full range).`;
}
const day = (iso: string) => (iso && iso.length >= 10 ? iso.slice(0, 10) : "UNKNOWN");
const time = (iso: string | null) => (iso ? iso.replace("T", " ").replace(/\.\d+Z$/, "Z") : "—");

export function PacketAnalysisScreen() {
  const [list, setList] = useState<PacketCaseList | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<PacketCaseDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [zeroing, setZeroing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<{ name: string; sent: number; total: number } | null>(null);
  const [report, setReport] = useState<PacketReport | null>(null);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);

  const loadList = useCallback(() => {
    listPacketCases().then(data => { if (mounted.current) { setList(data); setError(null); } })
      .catch(err => { if (mounted.current) setError(packetError(err, "Cases could not be loaded.")); });
  }, []);
  const loadDetail = useCallback((caseId: string) => {
    getPacketCase(caseId).then(data => { if (mounted.current) setDetail(data); })
      .catch(err => { if (mounted.current) { setDetail(null); setError(packetError(err, "The case could not be loaded.")); } });
  }, []);
  useEffect(loadList, [loadList]);
  useEffect(() => { setReport(null); if (selected) loadDetail(selected); else setDetail(null); }, [selected, loadDetail]);
  // A queued or running analysis is followed until it ends.
  useEffect(() => {
    if (!selected || !detail || !ACTIVE.has(detail.state)) return;
    const timer = setInterval(() => { loadDetail(selected); loadList(); }, 5000);
    return () => clearInterval(timer);
  }, [selected, detail, loadDetail, loadList]);

  const permissions = detail?.permissions ?? list?.permissions;
  const limits = list?.limits;

  const create = async () => {
    setBusy(true); setNotice(null); setError(null);
    try {
      const created = await createPacketCase(title.trim(), zeroing);
      if (!mounted.current) return;
      setTitle(""); setZeroing(false); setSelected(created.case_id); loadList();
    } catch (err) { if (mounted.current) setError(packetError(err, "The case could not be created.")); }
    finally { if (mounted.current) setBusy(false); }
  };
  const upload = async (file: File | undefined) => {
    if (!file || !selected) return;
    setNotice(null); setError(null);
    if (limits && file.size > limits.max_file_bytes) {
      setError(`A capture may be at most ${packetBytes(limits.max_file_bytes)}.`);
      return;
    }
    setBusy(true); setProgress({ name: file.name, sent: 0, total: file.size });
    try {
      await uploadPacketCapture(selected, file, (sent, total) => { if (mounted.current) setProgress({ name: file.name, sent, total }); });
      if (!mounted.current) return;
      setNotice("Capture uploaded."); loadDetail(selected); loadList();
    } catch (err) { if (mounted.current) setError(packetError(err, "The upload was refused.")); }
    finally { if (mounted.current) { setBusy(false); setProgress(null); } }
  };
  const analyze = async () => {
    if (!selected) return;
    setBusy(true); setNotice(null); setError(null);
    try { await analyzePacketCase(selected); if (mounted.current) { setNotice("Analysis queued."); loadDetail(selected); loadList(); } }
    catch (err) { if (mounted.current) setError(packetError(err, "The analysis could not be requested.")); }
    finally { if (mounted.current) setBusy(false); }
  };
  const remove = async () => {
    if (!selected) return;
    setBusy(true); setNotice(null); setError(null);
    try { await deletePacketCase(selected); if (mounted.current) { setSelected(null); setNotice("Case deleted."); loadList(); } }
    catch (err) { if (mounted.current) setError(packetError(err, "The case could not be deleted.")); }
    finally { if (mounted.current) setBusy(false); }
  };
  const showReport = async (jobId: string) => {
    setError(null);
    try { const data = await getPacketReport(jobId); if (mounted.current) setReport(data); }
    catch (err) { if (mounted.current) setError(packetError(err, "The result could not be loaded.")); }
  };

  return (
    <ScreenRoot screenType="tool">
      {/* Tool page (UI review 2026-10-18 §3, roadmap 3.3): the contract in two sentences -- what it does, what it never does. */}
      <ScreenHeader title="Packet Analysis" subtitle={packetContract(limits?.retention_days ?? 7)} />
      {error && <Alert severity="error">{error}</Alert>}
      {notice && <Alert severity="success">{notice}</Alert>}
      {!list && !error && <Typography role="status">Loading cases…</Typography>}

      {/* One input region: the case form, or the same form locked with its refusal reason inline; the store limits beneath it. */}
      {permissions && (
        <Card component="section" aria-label="New case" sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}>
          {permissions.run ? <>
            <Typography variant="h6">New case</Typography>
            <Stack direction="row" spacing={2} alignItems="center" sx={{ flexWrap: "wrap", gap: 1, mt: 1 }}>
              <TextField label="Case title" size="small" value={title} inputProps={{ maxLength: 120 }}
                onChange={e => setTitle(e.target.value)} disabled={busy} />
              <FormControlLabel label="Payload zeroing"
                control={<Checkbox checked={zeroing} onChange={e => setZeroing(e.target.checked)} disabled={busy} />} />
              <M3Button emphasis="filled" disabled={busy || !title.trim()} onClick={() => void create()}>Create case</M3Button>
            </Stack>
          </> : <Stack direction="row" spacing={2} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
            <M3Button emphasis="filled" icon="plus" disabled>New case</M3Button>
            <Typography role="status" variant="body2" sx={{ color: m3.onSurfaceVar }}>{ROLE_REFUSAL}</Typography>
          </Stack>}
          {limits && <Typography variant="caption" sx={{ display: "block", mt: 1.5, color: m3.onSurfaceVar }}>
            Store: {packetBytes(limits.used_bytes)} of {packetBytes(limits.max_store_bytes)} used · at most {packetBytes(limits.max_file_bytes)} per capture
          </Typography>}
        </Card>
      )}

      {list && <CaseTable cases={list.cases} selected={selected} onSelect={setSelected} />}

      {detail && (
        <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Case detail">
          <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
            <Typography variant="h6">{detail.title}</Typography>
            <StatusChip tone={STATE_TONE[detail.state] ?? "neutral"} label={statusWord(detail.state)} />
            <Typography variant="body2">Expires {day(detail.expires_at)}</Typography>
            {detail.payload_zeroing && <StatusChip tone="neutral" label="Payload zeroing requested" dense />}
          </Stack>
          <Typography variant="subtitle2" sx={{ mt: 2 }}>Captures</Typography>
          <TableContainer><Table size="small" aria-label="Captures"><TableHead><TableRow>
            <TableCell>Name</TableCell><TableCell>Format</TableCell><TableCell>Size</TableCell><TableCell>Uploaded</TableCell>
            {permissions?.raw_view && <TableCell>Raw</TableCell>}
          </TableRow></TableHead><TableBody>
            {detail.captures.map(c => <TableRow key={c.capture_id}>
              <TableCell>{c.display_name}{c.sha256 && <Typography variant="caption" display="block" sx={{ fontFamily: MONO }}>sha256 {c.sha256.slice(0, 16)}…</Typography>}</TableCell>
              <TableCell>{c.format}</TableCell><TableCell>{packetBytes(c.size_bytes)}</TableCell><TableCell>{time(c.uploaded_at)}</TableCell>
              {permissions?.raw_view && <TableCell><a href={packetCaptureDownloadHref(c.capture_id)}>Download</a></TableCell>}
            </TableRow>)}
            {!detail.captures.length && <TableRow><TableCell colSpan={5}>No capture uploaded yet</TableCell></TableRow>}
          </TableBody></Table></TableContainer>

          {permissions?.run && (
            <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 2, flexWrap: "wrap", gap: 1 }}>
              <M3Button emphasis="tonal" disabled={busy || ACTIVE.has(detail.state)}
                onClick={() => document.getElementById("packet-capture-file")?.click()}>Upload capture</M3Button>
              <input id="packet-capture-file" aria-label="Capture file" type="file" accept=".pcap,.pcapng,.cap" hidden
                onChange={e => { void upload(e.target.files?.[0]); e.target.value = ""; }} />
              <M3Button emphasis="filled" disabled={busy || ACTIVE.has(detail.state) || !detail.captures.length}
                onClick={() => void analyze()}>Analyze</M3Button>
              <M3Button emphasis="outlined" disabled={busy || ACTIVE.has(detail.state)} onClick={() => void remove()}>Delete case</M3Button>
            </Stack>
          )}
          {progress && <Box role="status" aria-label="Upload progress" sx={{ mt: 1 }}>
            <Typography variant="body2">Uploading {progress.name}: {packetBytes(progress.sent)} of {packetBytes(progress.total)}</Typography>
            <LinearProgress variant="determinate" value={progress.total ? Math.min(100, (100 * progress.sent) / progress.total) : 0} />
          </Box>}

          <PacketHostTelemetry caseId={detail.case_id} items={detail.telemetry ?? []} permissions={permissions}
            maxBytes={limits?.max_telemetry_bytes} onChanged={() => loadDetail(detail.case_id)} onError={setError} onNotice={setNotice} />

          <Typography variant="subtitle2" sx={{ mt: 2 }}>Analyses</Typography>
          <TableContainer><Table size="small" aria-label="Analyses"><TableHead><TableRow>
            <TableCell>State</TableCell><TableCell>Requested</TableCell><TableCell>Finished</TableCell><TableCell>Verdict</TableCell><TableCell />
          </TableRow></TableHead><TableBody>
            {detail.runs.map(r => <TableRow key={r.job_id}>
              <TableCell><StatusChip tone={STATE_TONE[r.state] ?? "neutral"} label={statusWord(r.state)} dense />{r.failure_reason && <Typography variant="caption" display="block">{r.failure_reason}</Typography>}{packetFailureText(r.failure_reason) && <Typography variant="caption" display="block" color="text.secondary">{packetFailureText(r.failure_reason)}</Typography>}</TableCell>
              <TableCell>{time(r.requested_at)}</TableCell><TableCell>{time(r.finished_at)}</TableCell>
              <TableCell>{r.summary?.verdict ?? "—"}{packetWindowText(r.summary?.analysis_window) && <Typography variant="caption" display="block" color="text.secondary">{packetWindowText(r.summary?.analysis_window)}</Typography>}</TableCell>
              <TableCell>{r.state === "COMPLETED" && <M3Button emphasis="text" onClick={() => void showReport(r.job_id)}>View result</M3Button>}</TableCell>
            </TableRow>)}
            {!detail.runs.length && <TableRow><TableCell colSpan={5}>Not analysed yet</TableCell></TableRow>}
          </TableBody></Table></TableContainer>
        </Card>
      )}

      {detail && !ACTIVE.has(detail.state) && (
        <PacketCaseWorkspace
          key={`${detail.case_id}-${detail.latest_job_id ?? detail.state}`}
          caseId={detail.case_id}
          permissions={permissions}
          onError={setError}
          onNotice={setNotice}
        />
      )}

      {report && <ResultView report={report} />}
    </ScreenRoot>
  );
}

/** The tool's contract under the title (review §3 "Tool page"): sentence one what it does, sentence two what it never does. */
export function packetContract(retentionDays: number): string {
  return "Analyses uploaded pcap or pcapng captures offline and reports what the frames prove, stage by stage; results are evidence-led: "
    + "a stage that cannot be established is reported as unknown, with the reason. "
    + `It never reads a device or live traffic and never changes anything; captures are deleted ${retentionDays} days after the case is created.`;
}

/** The empty state: what a case is and the three steps, in two sentences, instead of a dead table row (UI review 2026-10-18). */
export const PACKET_WORKFLOW_SENTENCES = [
  "A case holds packet captures (pcap or pcapng) and optional host telemetry for one investigation; create a case, upload a capture, then run Analyze.",
  "Each stage the capture cannot establish is reported as UNKNOWN with the reason, and the capture is deleted after the retention period.",
] as const;

function CaseTable({ cases, selected, onSelect }: { readonly cases: readonly PacketCase[]; readonly selected: string | null; readonly onSelect: (id: string) => void }) {
  if (cases.length === 0) {
    return (
      <Card component="section" aria-label="How Packet Analysis works" sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}>
        <Typography variant="subtitle2">No case yet</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.5, maxWidth: 760 }}>{PACKET_WORKFLOW_SENTENCES.join(" ")}</Typography>
      </Card>
    );
  }
  return (
    <TableContainer><Table size="small" aria-label="Packet analysis cases"><TableHead><TableRow>
      <TableCell>Case</TableCell><TableCell>State</TableCell><TableCell>Captures</TableCell><TableCell>Size</TableCell>
      <TableCell>Created</TableCell><TableCell>Expires</TableCell>
    </TableRow></TableHead><TableBody>
      {cases.map(c => <TableRow key={c.case_id} hover selected={c.case_id === selected} onClick={() => onSelect(c.case_id)} sx={{ cursor: "pointer" }}>
        <TableCell><Box component="button" type="button" onClick={() => onSelect(c.case_id)}
          sx={{ all: "unset", cursor: "pointer", color: m3.primary, fontWeight: 500 }}>{c.title}</Box></TableCell>
        <TableCell><StatusChip tone={STATE_TONE[c.state] ?? "neutral"} label={statusWord(c.state)} dense /></TableCell>
        <TableCell>{c.capture_count}</TableCell><TableCell>{packetBytes(c.total_bytes)}</TableCell>
        <TableCell>{day(c.created_at)}</TableCell><TableCell>{day(c.expires_at)}</TableCell>
      </TableRow>)}
    </TableBody></Table></TableContainer>
  );
}

/** Verdict and per-stage unknown reasons first, then the document itself as pretty JSON. */
export function ResultView({ report }: { readonly report: PacketReport }) {
  const summary: PacketRunSummary | null | undefined = report.projection === "full"
    ? (report.report as PacketRunSummary | undefined) : report.summary;
  const document = report.projection === "full" ? report.report : report.summary;
  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Analysis result">
      <Stack direction="row" spacing={1} alignItems="center">
        <Typography variant="h6">Result</Typography>
        <StatusChip tone={summary?.verdict === "unknown" || !summary?.verdict ? "neutral" : "ok"} label={`Verdict: ${summary?.verdict ?? "UNKNOWN"}`} />
        <StatusChip tone={report.projection === "masked" ? "mem" : "neutral"} label={report.projection === "masked" ? "Masked summary" : "Full report"} dense />
      </Stack>
      {summary?.reason && <Typography variant="body2" sx={{ mt: 1 }}>{summary.reason}</Typography>}
      <Table size="small" aria-label="Stages" sx={{ mt: 1 }}><TableHead><TableRow>
        <TableCell>Stage</TableCell><TableCell>Verdict</TableCell><TableCell>Reason</TableCell>
      </TableRow></TableHead><TableBody>
        {(summary?.stages ?? []).map(s => <TableRow key={s.stage}><TableCell>{s.stage}</TableCell><TableCell>{s.verdict}</TableCell><TableCell>{s.reason ?? "—"}</TableCell></TableRow>)}
      </TableBody></Table>
      <Box component="pre" aria-label="Result JSON" sx={{ mt: 2, p: 1.5, bgcolor: m3.scLow, borderRadius: 1, overflow: "auto", maxHeight: 480, fontFamily: MONO, fontSize: 12 }}>
        {JSON.stringify(document ?? null, null, 2)}
      </Box>
    </Card>
  );
}
