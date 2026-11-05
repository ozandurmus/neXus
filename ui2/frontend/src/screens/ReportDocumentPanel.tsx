import { Alert, Box, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import type { ReportDocumentView, ReportPlaneAsOf } from "../auth/adminApi";
import { StatusChip } from "../shell/M3Widgets";
import { formatStamp } from "../shell/time";
import type { Tone } from "../shell/tone";
import { m3 } from "../theme/m3Theme";

/** Shared by the Reports screen and its design preview: the rendering of one projected report document. */

export const FRAMEWORK_LABELS: Readonly<Record<string, string>> = {
  ALL: "All frameworks", CIS: "CIS Benchmark", NIST_800_53: "NIST SP 800-53", PCI_DSS: "PCI-DSS v4.0.1",
  FINANCIAL_BASELINE: "Financial Baseline",
};

export function toneOf(word: string): Tone {
  const w = word.toUpperCase();
  if (["OK", "HEALTHY", "READY", "CURRENT", "SUCCEEDED", "RESOLVED"].includes(w)) return "ok";
  if (["BAD", "AT RISK", "NOT_READY", "FAILED", "NO ARCHIVE", "CRITICAL"].includes(w)) return "bad";
  if (["WARN", "STALE", "OPEN", "HIGH"].includes(w)) return "warn";
  if (w === "UNKNOWN") return "unknown";
  return "neutral";
}

/** "3 of 5 devices · newest … · oldest … · 1 stale": one plane's data age and coverage, never a bare timestamp. */
export function planeLine(p: ReportPlaneAsOf): string {
  return `${p.plane}: ${p.covered} of ${p.total} ${p.unit} with evidence · newest ${formatStamp(p.newest)} · oldest ${formatStamp(p.oldest)}`
    + (p.stale > 0 ? ` · ${p.stale} stale` : "");
}

/** The on-screen report: the same projected document the PDF, XLSX and CSV files are rendered from. */
export function ReportDocumentPanel({ doc }: { readonly doc: ReportDocumentView }) {
  return (
    <Stack spacing={2} data-report-document={doc.template_id}>
      <Box>
        <Stack direction="row" spacing={1} alignItems="center">
          <Typography variant="h5">{doc.template_id} · {doc.title}</Typography>
          <StatusChip tone={doc.projection === "RAW" ? "warn" : "neutral"}
            label={doc.projection === "RAW" ? "RAW - restricted" : "MASKED"} dense />
        </Stack>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{doc.question}</Typography>
        <Typography variant="caption">Generated {formatStamp(doc.generated_at)} · {Object.entries(doc.parameters)
          .map(([k, v]) => `${k} ${FRAMEWORK_LABELS[v] ?? v}`).join(" · ")}</Typography>
      </Box>
      <Box>
        <Typography variant="subtitle2">Data as of</Typography>
        {doc.data_as_of.map(p => <Typography key={p.plane} variant="body2" data-plane={p.plane}>{planeLine(p)}</Typography>)}
      </Box>
      {doc.notices.map((n, i) => <Alert key={i} severity="warning">{n}</Alert>)}
      {doc.sections.map(s => (
        <Box key={s.id} data-report-section={s.id} sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="h6">{s.title}</Typography>
          {s.kpis.length > 0 && <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1 }}>
            {s.kpis.map(k => <Box key={k.label} sx={{ p: 1.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, minWidth: 160 }}>
              <Typography variant="h6">{k.value}</Typography>
              <Typography variant="caption">{k.label}</Typography>
              {k.tone !== "INFO" && <Box><StatusChip tone={toneOf(k.tone)} label={k.tone} dense /></Box>}
            </Box>)}
          </Stack>}
          {s.tables.map(t => (
            <TableContainer key={t.id}>
              <Typography variant="subtitle2">{t.title}</Typography>
              <Table size="small" aria-label={t.title}>
                <TableHead><TableRow>{t.columns.map(c => <TableCell key={c.key}>{c.label}</TableCell>)}</TableRow></TableHead>
                <TableBody>
                  {t.rows.map((row, i) => <TableRow key={i}>{row.map((cell, j) => <TableCell key={j}>
                    {cell.kind === "STATUS" ? <StatusChip tone={toneOf(cell.text)} label={cell.text} dense />
                      : cell.kind === "TIMESTAMP" ? formatStamp(cell.text === "UNKNOWN" ? null : cell.text) : cell.text}
                  </TableCell>)}</TableRow>)}
                  {t.rows.length === 0 && <TableRow><TableCell colSpan={t.columns.length}>{t.empty_text ?? "None."}</TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
          ))}
          {s.notes.map((n, i) => <Typography key={i} variant="caption">{n}</Typography>)}
        </Box>
      ))}
    </Stack>
  );
}
