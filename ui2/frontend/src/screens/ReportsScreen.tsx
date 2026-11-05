import { useEffect, useRef, useState } from "react";
import { Alert, Box, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import {
  generateReport, getReportCatalogue, getReportRun, listReportRuns, previewReport, reportDownloadHref,
  type ApiError, type ReportCatalogue, type ReportDocumentView, type ReportFormat, type ReportProjection,
  type ReportRunView, type ReportTemplateCard,
} from "../auth/adminApi";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { formatStamp } from "../shell/time";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { m3 } from "../theme/m3Theme";
import { FRAMEWORK_LABELS, ReportDocumentPanel, planeLine, toneOf } from "./ReportDocumentPanel";

export { ReportDocumentPanel };

/**
 * Reports R1 (docs/design/REPORTS_R1_CONTRACT.md "UI"): Catalogue, Run & preview, History. The server builds, masks
 * and stamps every report; this screen renders what it receives and offers RAW only when the server says the session
 * holds it (`permissions.raw_view`), never from a role of its own.
 */

export const REPORT_FORMATS: readonly ReportFormat[] = ["PDF", "XLSX", "CSV"];

export function reportError(err: unknown): string {
  const e = err as Partial<ApiError> | undefined;
  const body = e?.body as Record<string, unknown> | undefined;
  if (body && typeof body.reason === "string") return body.reason;
  if (e?.status === 403) return "This session may not read reports.";
  return "The report service could not be reached.";
}



function today(): string {
  return new Date().toISOString().slice(0, 10);
}
function daysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);
}

export function ReportsScreen() {
  const catalogue = useFetchOnMount(getReportCatalogue, reportError);
  const [tab, setTab] = useState(0);
  const [template, setTemplate] = useState("RPT-01");
  const [historyKey, setHistoryKey] = useState(0);
  const masked = catalogue.data?.permissions.masked ?? true;
  const open = (id: string) => { setTemplate(id); setTab(1); };
  return (
    <ScreenRoot screenType="summary">
      <ScreenHeader title="Reports"
        subtitle="Predefined reports built from stored evidence only: no device is contacted. Every report and section states its data age and coverage, and UNKNOWN is shown as its own category."
        actions={catalogue.data ? <StatusChip tone={masked ? "neutral" : "warn"}
          label={masked ? "Masked projection" : catalogue.data.permissions.raw_view ? "RAW available" : "Masked projection"} /> : undefined} />
      <M3Tabs ariaLabel="Reports sections" value={tab} onChange={setTab} tabs={[
        { label: "Catalogue", panel: <CataloguePanel catalogue={catalogue.data} error={catalogue.error} onRetry={catalogue.refresh} onOpen={open} /> },
        { label: "Run & preview", panel: <RunPanel catalogue={catalogue.data} error={catalogue.error} template={template} onTemplate={setTemplate}
          onGenerated={() => setHistoryKey(k => k + 1)} /> },
        { label: "History", panel: <HistoryPanel key={historyKey} /> },
      ]} />
    </ScreenRoot>
  );
}

function CataloguePanel({ catalogue, error, onRetry, onOpen }: {
  readonly catalogue: ReportCatalogue | null; readonly error: string | null; readonly onRetry: () => void; readonly onOpen: (id: string) => void;
}) {
  if (error) return <Alert severity="error" action={<M3Button emphasis="text" onClick={onRetry}>Retry</M3Button>}>
    The report catalogue could not be read: {error}</Alert>;
  if (!catalogue) return <Typography>Reading the report catalogue…</Typography>;
  return (
    <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))", gap: 2 }}>
      {catalogue.templates.map(t => <CatalogueCard key={t.template_id} card={t} onOpen={onOpen} />)}
    </Box>
  );
}

function CatalogueCard({ card, onOpen }: { readonly card: ReportTemplateCard; readonly onOpen: (id: string) => void }) {
  const refused = card.missing_reads.length > 0;
  return (
    <Box data-report-card={card.template_id} sx={{ p: 2, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, display: "flex", flexDirection: "column", gap: 1 }}>
      <Typography variant="overline">{card.template_id} · {card.audience}</Typography>
      <Typography variant="h6">{card.title}</Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, flex: 1 }}>{card.question}</Typography>
      <Typography variant="caption">On screen · {card.formats.join(" · ")}</Typography>
      {refused && <Typography variant="caption" sx={{ color: m3.error }}>Needs read access: {card.missing_reads.join(", ")}</Typography>}
      <Box><M3Button emphasis="tonal" disabled={refused} onClick={() => onOpen(card.template_id)}>Run & preview</M3Button></Box>
    </Box>
  );
}

function RunPanel({ catalogue, error, template, onTemplate, onGenerated }: {
  readonly catalogue: ReportCatalogue | null; readonly error: string | null; readonly template: string;
  readonly onTemplate: (id: string) => void; readonly onGenerated: () => void;
}) {
  const [from, setFrom] = useState(daysAgo(6));
  const [to, setTo] = useState(today());
  const [framework, setFramework] = useState("ALL");
  const [projection, setProjection] = useState<ReportProjection>("MASKED");
  const [doc, setDoc] = useState<ReportDocumentView | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [run, setRun] = useState<ReportRunView | null>(null);
  const poll = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (poll.current) clearTimeout(poll.current); }, []);
  if (error) return <Alert severity="error">The report catalogue could not be read: {error}</Alert>;
  if (!catalogue) return <Typography>Reading the report catalogue…</Typography>;
  const card = catalogue.templates.find(t => t.template_id === template) ?? catalogue.templates[0];
  if (!card) return <Typography>No report is available.</Typography>;
  const rawAllowed = catalogue.permissions.raw_view;
  const request = (format?: ReportFormat) => ({
    template_id: card.template_id, from, to, projection: rawAllowed ? projection : "MASKED" as ReportProjection,
    ...(card.framework_parameter ? { framework } : {}), ...(format ? { format } : {}),
  });
  const runPreview = async () => {
    setBusy(true); setFailure(null); setMessage(null);
    try { setDoc(await previewReport(request())); } catch (e) { setFailure(reportError(e)); setDoc(null); } finally { setBusy(false); }
  };
  const follow = (runId: string, attempts: number) => {
    poll.current = setTimeout(async () => {
      try {
        const next = await getReportRun(runId);
        setRun(next);
        if ((next.state === "QUEUED" || next.state === "RUNNING") && attempts > 0) follow(runId, attempts - 1);
        else onGenerated();
      } catch (e) { setFailure(reportError(e)); }
    }, 1500);
  };
  const runGenerate = async (format: ReportFormat) => {
    setBusy(true); setFailure(null); setMessage(null);
    try {
      const queued = await generateReport(request(format));
      setRun(queued);
      setMessage(`${card.template_id} ${format} requested; it stays available for ${catalogue.retention_days} days under History.`);
      if (queued.state === "QUEUED" || queued.state === "RUNNING") follow(queued.run_id, 40); else onGenerated();
    } catch (e) { setFailure(reportError(e)); } finally { setBusy(false); }
  };
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1, alignItems: "center" }}>
        <TextField label="Report" select size="small" value={card.template_id} onChange={e => { onTemplate(e.target.value); setDoc(null); }}
          sx={{ minWidth: 320 }}>
          {catalogue.templates.map(t => <MenuItem key={t.template_id} value={t.template_id}>{t.template_id} · {t.title}</MenuItem>)}
        </TextField>
        <TextField label="From" type="date" size="small" value={from} onChange={e => setFrom(e.target.value)} InputLabelProps={{ shrink: true }} />
        <TextField label="To" type="date" size="small" value={to} onChange={e => setTo(e.target.value)} InputLabelProps={{ shrink: true }} />
        {card.framework_parameter && <TextField label="Framework" select size="small" value={framework} onChange={e => setFramework(e.target.value)}>
          {["ALL", ...catalogue.frameworks].map(f => <MenuItem key={f} value={f}>{FRAMEWORK_LABELS[f] ?? f}</MenuItem>)}
        </TextField>}
        {rawAllowed
          ? <TextField label="Projection" select size="small" value={projection} onChange={e => setProjection(e.target.value as ReportProjection)}>
              <MenuItem value="MASKED">Masked (for sharing)</MenuItem>
              <MenuItem value="RAW">RAW (restricted)</MenuItem>
            </TextField>
          : <Typography variant="caption" data-projection-locked>Projection: masked</Typography>}
      </Stack>
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
        <M3Button emphasis="filled" disabled={busy || card.missing_reads.length > 0} onClick={runPreview}>Preview</M3Button>
        {REPORT_FORMATS.map(f => <M3Button key={f} emphasis="tonal" disabled={busy || card.missing_reads.length > 0}
          onClick={() => runGenerate(f)}>Generate {f}</M3Button>)}
      </Stack>
      {card.missing_reads.length > 0 && <Alert severity="warning">This report needs read access to: {card.missing_reads.join(", ")}</Alert>}
      {failure && <Alert severity="error">{failure}</Alert>}
      {message && <Alert severity="info">{message}</Alert>}
      {run && <RunLine run={run} />}
      {busy && !doc && <Typography>Building the report…</Typography>}
      {doc && <ReportDocumentPanel doc={doc} />}
    </Stack>
  );
}

function RunLine({ run }: { readonly run: ReportRunView }) {
  return (
    <Stack direction="row" spacing={1} alignItems="center" data-report-run={run.run_id}>
      <StatusChip tone={toneOf(run.state)} label={run.state} dense />
      <Typography variant="body2">{run.template_id} · {run.format} · {run.projection}{run.error_code ? ` · ${run.error_code}` : ""}</Typography>
      {run.downloadable && <a href={reportDownloadHref(run.run_id)}>Download</a>}
    </Stack>
  );
}

function HistoryPanel() {
  const runs = useFetchOnMount(listReportRuns, reportError);
  if (runs.error) return <Alert severity="error" action={<M3Button emphasis="text" onClick={runs.refresh}>Retry</M3Button>}>
    The report history could not be read: {runs.error}</Alert>;
  if (!runs.data) return <Typography>Reading the report history…</Typography>;
  const rows = runs.data.runs;
  const headings = ["Requested", "Report", "Format", "Projection", "State", "Data as of", "Expires", "File"];
  return (
    <Stack spacing={1}>
      <Stack direction="row" spacing={1} alignItems="center">
        <Typography variant="caption">Generated files are kept for 30 days. {runs.data.permissions.raw_view ? "" : "Only masked reports are listed for this session."}</Typography>
        <M3Button emphasis="text" onClick={runs.refresh}>Refresh</M3Button>
      </Stack>
      <TableContainer><Table size="small" aria-label="Report history"><TableHead><TableRow>
        {headings.map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead><TableBody>
        {rows.map(r => <TableRow key={r.run_id} data-history-run={r.run_id}>
          <TableCell>{formatStamp(r.requested_at)}</TableCell>
          <TableCell>{r.template_id} · {r.title}<Typography variant="caption" display="block">
            {Object.entries(r.parameters).map(([k, v]) => `${k} ${FRAMEWORK_LABELS[v] ?? v}`).join(" · ")}</Typography></TableCell>
          <TableCell>{r.format}</TableCell>
          <TableCell>{r.projection}</TableCell>
          <TableCell><StatusChip tone={toneOf(r.state)} label={r.state} dense />{r.error_code && <Typography variant="caption" display="block">{r.error_code}</Typography>}</TableCell>
          <TableCell>{(r.data_as_of.planes ?? []).length === 0 ? "UNKNOWN"
            : (r.data_as_of.planes ?? []).map(p => <Typography key={p.plane} variant="caption" display="block">{planeLine(p)}</Typography>)}</TableCell>
          <TableCell>{formatStamp(r.expires_at)}</TableCell>
          <TableCell>{r.downloadable ? <a href={reportDownloadHref(r.run_id)}>Download</a>
            : r.state === "SUCCEEDED" ? "Expired" : "No file"}</TableCell>
        </TableRow>)}
        {rows.length === 0 && <TableRow><TableCell colSpan={headings.length}>No report has been generated yet.</TableCell></TableRow>}
      </TableBody></Table></TableContainer>
    </Stack>
  );
}

