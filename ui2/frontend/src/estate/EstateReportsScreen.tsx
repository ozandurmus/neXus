import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import {
  getReportCatalogue,
  listReportRuns,
  reportDownloadHref,
  type ReportCatalogue,
  type ReportRunView,
} from "../auth/adminApi";
import { reportError } from "../screens/ReportsScreen";
import { formatStamp } from "../shell/time";
import { EstateCard, EvidenceStamp, MoreFooter } from "./EstatePrimitives";
import {
  buildRunRows,
  buildTemplateRows,
  capList,
  summariseRuns,
  standardReportsHref,
  type RunRow,
  type TemplateRow,
} from "./estateReportsModel";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

type Load =
  | { readonly state: "loading" }
  | { readonly state: "failed"; readonly message: string }
  /** `readAt`: the instant both reads settled; the list responses carry no evidence time, so the counts are stamped with it. */
  | { readonly state: "ok"; readonly catalogue: ReportCatalogue; readonly runs: readonly ReportRunView[]; readonly readAt: Date };

function useReportsData(): Load {
  const [load, setLoad] = useState<Load>({ state: "loading" });
  useEffect(() => {
    let active = true;
    Promise.all([getReportCatalogue(), listReportRuns()]).then(
      ([catalogue, history]) => { if (active) setLoad({ state: "ok", catalogue, runs: history.runs, readAt: new Date() }); },
      (e: unknown) => { if (active) setLoad({ state: "failed", message: reportError(e) }); },
    );
    return () => { active = false; };
  }, []);
  return load;
}

const toneColour = (tone: RunRow["tone"]) => (tone === "ok" ? ev.tOk : tone === "act" ? ev.tAct : ev.tWatch);
const cellSx = { px: "10px", py: "8px", borderBottom: `1px solid ${ev.line}`, fontSize: 13, verticalAlign: "top" } as const;
const headSx = { ...cellSx, fontSize: 11, letterSpacing: ".06em", textTransform: "uppercase", color: ev.ink3, fontWeight: 700 } as const;

function TemplateCard({ row }: { readonly row: TemplateRow }) {
  return (
    <Box data-report-template={row.templateId} sx={{ border: `1px solid ${ev.line}`, borderRadius: "10px", p: "12px 14px", bgcolor: ev.surface2 }}>
      <Box sx={{ fontFamily: ESTATE_MONO, fontSize: 12, color: ev.ink3 }}>{row.templateId} · {row.audience}</Box>
      <Box sx={{ fontWeight: 650, fontSize: 15, mt: 0.25 }}>{row.title}</Box>
      <Box sx={{ color: ev.ink2, fontSize: 13, mt: 0.5 }}>{row.question}</Box>
      <Box sx={{ fontSize: 12, mt: 0.75, color: row.status === "REFUSED" ? ev.act : ev.ink2 }}>
        {row.statusText} · formats {row.formats.join(", ") || "UNKNOWN"}
      </Box>
      {row.status === "AVAILABLE" && (
        <Box component="a" href={standardReportsHref()} sx={{ display: "inline-block", mt: 1, fontSize: 13, color: ev.accent }}>
          Generate in standard Reports
        </Box>
      )}
    </Box>
  );
}

function HistoryTable({ rows, retentionDays }: { readonly rows: readonly RunRow[]; readonly retentionDays: number }) {
  const capped = capList(rows);
  return (
    <Box>
      <Box sx={{ fontSize: 12, color: ev.ink3, mb: 1 }}>Generated files are kept for {retentionDays} days. Newest first.</Box>
      <Box component="table" sx={{ width: "100%", borderCollapse: "collapse", fontFamily: ESTATE_SANS }}>
        <thead>
          <tr>
            {["Requested", "Report", "Format", "State", "Evidence age", "Expires", "File"].map(h => <Box component="th" key={h} sx={{ ...headSx, textAlign: "left" }}>{h}</Box>)}
          </tr>
        </thead>
        <tbody>
          {capped.shown.map(r => (
            <tr key={r.runId} data-estate-report-run={r.runId}>
              <Box component="td" sx={cellSx}>{formatStamp(r.requestedAt)}</Box>
              <Box component="td" sx={cellSx}>{r.templateId} · {r.title}</Box>
              <Box component="td" sx={cellSx}>{r.format} · {r.projection}</Box>
              <Box component="td" sx={{ ...cellSx, color: toneColour(r.tone), fontWeight: 650 }}>
                {r.state}{r.errorCode ? ` · ${r.errorCode}` : ""}
              </Box>
              <Box component="td" sx={cellSx} title={r.evidenceAt ?? "No plane evidence"}>
                {r.evidenceAgeLabel} <Box component="span" sx={{ color: ev.ink3 }}>(evidence)</Box>
              </Box>
              <Box component="td" sx={cellSx}>{formatStamp(r.expiresAt)}</Box>
              <Box component="td" sx={cellSx}>
                {r.downloadable ? <Box component="a" href={reportDownloadHref(r.runId)} sx={{ color: ev.accent }}>Download</Box>
                  : r.state === "SUCCEEDED" ? "Expired" : "No file"}
              </Box>
            </tr>
          ))}
          {rows.length === 0 && <tr><Box component="td" colSpan={7} sx={cellSx}>No report has been generated yet.</Box></tr>}
        </tbody>
      </Box>
      {capped.more > 0 && (
        <Box sx={{ fontSize: 12, color: ev.ink3, mt: 1 }}>
          <MoreFooter shown={capped.shown.length} total={rows.length} noun="runs" />
          <Box sx={{ display: "block" }}>not shown; the standard Reports screen lists all of them.</Box>
        </Box>
      )}
    </Box>
  );
}

export function EstateReportsScreen() {
  const load = useReportsData();
  const [now] = useState(() => new Date());
  const view = useMemo(() => {
    if (load.state !== "ok") return null;
    return {
      templates: buildTemplateRows(load.catalogue),
      runs: buildRunRows(load.runs, now),
      summary: summariseRuns(load.runs),
      retention: load.catalogue.retention_days,
      readAt: load.readAt,
    };
  }, [load, now]);

  return (
    <Box data-testid="estate-reports" sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Box>
        <Box sx={{ fontSize: 11, letterSpacing: ".09em", textTransform: "uppercase", fontWeight: 650, color: ev.ink3 }}>Reports</Box>
        <Box component="h1" sx={{ m: 0, fontSize: 26, lineHeight: 1.2, fontWeight: 650 }}>Evidence packs, templates and history</Box>
        <Box sx={{ color: ev.ink2, mt: 0.5, maxWidth: 780 }}>
          Read-only. Reports are built from stored evidence only; no device is contacted here. Generate a pack on the standard Reports screen.
        </Box>
      </Box>

      {load.state === "loading" && <Box sx={{ color: ev.ink3 }}>Reading the report catalogue and history…</Box>}
      {load.state === "failed" && <Box role="alert" sx={{ color: ev.act }}>The reports could not be read: {load.message}</Box>}

      {view && (
        <>
          <EstateCard sx={{ p: 2.5 }}>
            <Box component="h2" sx={{ m: 0, fontSize: 16, fontWeight: 650 }}>Templates</Box>
            <Box sx={{ fontSize: 12, color: ev.ink3, mb: 1.5 }}>
              {view.templates.length} templates listed: {view.templates.filter(t => t.status === "AVAILABLE").length} available to this session,
              {" "}{view.templates.filter(t => t.status === "REFUSED").length} refused for missing read access.{" "}
              <EvidenceStamp at={view.readAt.toISOString()} source="report catalogue read" />
            </Box>
            <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 1.5 }}>
              {view.templates.map(t => <TemplateCard key={t.templateId} row={t} />)}
            </Box>
          </EstateCard>

          <EstateCard sx={{ p: 2.5 }}>
            <Box component="h2" sx={{ m: 0, fontSize: 16, fontWeight: 650 }}>History</Box>
            <Box sx={{ fontSize: 12, color: ev.ink3, mb: 1.5 }}>
              {view.summary.sentence}{" "}
              <EvidenceStamp at={view.readAt.toISOString()} source="report history read" />
            </Box>
            <HistoryTable rows={view.runs} retentionDays={view.retention} />
          </EstateCard>
        </>
      )}
    </Box>
  );
}
