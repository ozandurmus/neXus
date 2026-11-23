import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import { getReportRun, reportDownloadHref, type ReportRunView } from "../auth/adminApi";
import { reportError } from "../screens/ReportsScreen";
import { formatStamp } from "../shell/time";
import { EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import {
  REPORT_SECTION_CAP,
  capList,
  newestEvidenceAt,
  runDocument,
  runStateBadge,
  runStateLabel,
  sectionCountLabel,
  standardReportsHref,
  type DetailSection,
} from "./estateReportsModel";
import { ESTATE_SANS, ev } from "./estateTokens";
import { plural } from "../shell/plural";

/**
 * Read-only detail of one report run (Estate Reports). Calls `getReportRun` once for the run id and shows the
 * run's state (shape + word), request time, evidence age (stamp) and document sections as text. It never generates,
 * previews or deletes. A generated run stores a downloadable file; inline sections are read only if present.
 */

type Load =
  | { readonly state: "loading" }
  | { readonly state: "failed"; readonly message: string }
  | { readonly state: "ok"; readonly run: ReportRunView };

function SectionBlock({ section }: { readonly section: DetailSection }) {
  return (
    <Box data-estate-report-section={section.id} sx={{ borderTop: `1px solid ${ev.line}`, pt: 1, mt: 1 }}>
      <Box sx={{ fontWeight: 650, fontSize: 14 }}>{section.title}</Box>
      {section.notes.map((n, i) => <Box key={i} sx={{ fontSize: 13, color: ev.ink2, mt: 0.25 }}>{n}</Box>)}
    </Box>
  );
}

export function EstateReportRunDetail({ runId }: { readonly runId: string }) {
  const [load, setLoad] = useState<Load>({ state: "loading" });
  const [now] = useState(() => new Date());

  useEffect(() => {
    let active = true;
    getReportRun(runId).then(
      (run) => { if (active) setLoad({ state: "ok", run }); },
      (e: unknown) => { if (active) setLoad({ state: "failed", message: reportError(e) }); },
    );
    return () => { active = false; };
  }, [runId]);

  const view = useMemo(() => {
    if (load.state !== "ok") return null;
    const doc = runDocument(load.run);
    return {
      run: load.run,
      badge: runStateBadge(load.run.state),
      state: runStateLabel(load.run.state),
      evidenceAt: newestEvidenceAt(load.run),
      planesDated: (load.run.data_as_of?.planes ?? []).filter(p => p.newest !== null).length,
      doc,
      sections: doc.available ? capList(doc.sections, REPORT_SECTION_CAP) : null,
    };
  }, [load]);

  return (
    <Box data-testid="estate-report-run-detail" data-run-id={runId} sx={{ border: `1px solid ${ev.line}`, borderRadius: "10px", p: 2, fontFamily: ESTATE_SANS, bgcolor: ev.surface2 }}>
      <Box sx={{ fontSize: 11, letterSpacing: ".06em", textTransform: "uppercase", color: ev.ink3, fontWeight: 700 }}>Run detail · read-only</Box>

      {load.state === "loading" && <Box sx={{ color: ev.ink3, mt: 1 }}>Reading the run…</Box>}
      {load.state === "failed" && (
        <Box role="alert" data-run-state="FAILED_READ" sx={{ color: ev.act, mt: 1, fontSize: 13, display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          State: <StateBadge state="unk" word="READ FAILED" dense /> The run could not be read: {load.message}
        </Box>
      )}

      {view && (
        <Box sx={{ mt: 1 }}>
          <Box sx={{ fontWeight: 650, fontSize: 15 }}>{view.run.template_id} · {view.run.title}</Box>
          <Box sx={{ fontSize: 13, mt: 0.5, display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            State: <StateBadge state={view.badge} word={view.state} dense />
            {view.run.error_code ? <Box component="span">{` · ${view.run.error_code}`}</Box> : null}
          </Box>
          <Box sx={{ fontSize: 13, color: ev.ink2 }}>Requested {formatStamp(view.run.requested_at)} · finished {formatStamp(view.run.finished_at)}</Box>
          <Box sx={{ fontSize: 13, color: ev.ink2, display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            Evidence age <EvidenceStamp at={view.evidenceAt} source="newest stored evidence" now={now} />
          </Box>
          <Box sx={{ fontSize: 12, color: ev.ink3 }}>
            Format {view.run.format} · projection {view.run.projection} · {view.planesDated} data {plural(view.planesDated, "plane", "planes")} with a dated evidence stamp
          </Box>

          {!view.doc.available && view.doc.malformed && (
            <Box data-document="unavailable" sx={{ fontSize: 13, mt: 1, display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
              <StateBadge state="unk" word="UNKNOWN" dense /> Document could not be read (UNKNOWN)
            </Box>
          )}
          {!view.doc.available && !view.doc.malformed && (
            <Box data-document="metadata-only" sx={{ fontSize: 13, color: ev.ink2, mt: 1 }}>
              Inline sections are not stored on the run record. A generated run keeps its output as a downloadable file (see Download).
            </Box>
          )}

          {view.sections && (
            <Box data-document="available" sx={{ mt: 1 }}>
              <Box sx={{ fontSize: 12, color: ev.ink3 }}>
                Document: {sectionCountLabel(view.sections.shown.length + view.sections.more)}
              </Box>
              {view.sections.shown.map(s => <SectionBlock key={s.id} section={s} />)}
              <MoreFooter shown={view.sections.shown.length} total={view.sections.shown.length + view.sections.more} noun="sections" />
            </Box>
          )}

          <Box sx={{ display: "flex", gap: 2, mt: 1.5, fontSize: 13 }}>
            {view.run.downloadable
              ? <Box component="a" href={reportDownloadHref(runId)} sx={{ color: ev.accent }}>Download</Box>
              : <Box sx={{ color: ev.ink3 }}>No file to download</Box>}
            <Box component="a" href={standardReportsHref()} sx={{ color: ev.accent }}>Open in standard view</Box>
          </Box>
        </Box>
      )}
    </Box>
  );
}
