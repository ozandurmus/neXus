import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import { getReportRun, reportDownloadHref, type ReportRunView } from "../auth/adminApi";
import { reportError } from "../screens/ReportsScreen";
import { formatStamp } from "../shell/time";
import { MoreFooter } from "./EstatePrimitives";
import {
  REPORT_SECTION_CAP,
  capList,
  runDocument,
  runEvidenceAgeLabel,
  runStateLabel,
  runStateTone,
  sectionCountLabel,
  standardReportsHref,
  type DetailSection,
  type RunTone,
} from "./estateReportsModel";
import { ESTATE_SANS, ev } from "./estateTokens";

/**
 * Read-only detail of one report run (Estate Reports). Calls `getReportRun` once for the run id and shows the
 * run's state, request time, evidence age and document sections as text. It never generates, previews or deletes.
 */

type Load =
  | { readonly state: "loading" }
  | { readonly state: "failed"; readonly message: string }
  | { readonly state: "ok"; readonly run: ReportRunView };

const toneColour = (tone: RunTone) => (tone === "ok" ? ev.tOk : tone === "act" ? ev.tAct : ev.tWatch);

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
      tone: runStateTone(load.run.state),
      state: runStateLabel(load.run.state),
      ageLabel: runEvidenceAgeLabel(load.run, now),
      doc,
      sections: doc.available ? capList(doc.sections, REPORT_SECTION_CAP) : null,
    };
  }, [load, now]);

  return (
    <Box data-testid="estate-report-run-detail" data-run-id={runId} sx={{ border: `1px solid ${ev.line}`, borderRadius: "10px", p: 2, fontFamily: ESTATE_SANS, bgcolor: ev.surface2 }}>
      <Box sx={{ fontSize: 11, letterSpacing: ".06em", textTransform: "uppercase", color: ev.ink3, fontWeight: 700 }}>Run detail · read-only</Box>

      {load.state === "loading" && <Box sx={{ color: ev.ink3, mt: 1 }}>Reading the run…</Box>}
      {load.state === "failed" && (
        <Box role="alert" data-run-state="FAILED_READ" sx={{ color: ev.act, mt: 1 }}>
          State: READ FAILED. The run could not be read: {load.message}
        </Box>
      )}

      {view && (
        <Box sx={{ mt: 1 }}>
          <Box sx={{ fontWeight: 650, fontSize: 15 }}>{view.run.template_id} · {view.run.title}</Box>
          <Box sx={{ fontSize: 13, mt: 0.5 }}>
            State: <Box component="span" data-run-state={view.state} sx={{ color: toneColour(view.tone), fontWeight: 650 }}>{view.state}</Box>
            {view.run.error_code ? ` · ${view.run.error_code}` : ""}
          </Box>
          <Box sx={{ fontSize: 13, color: ev.ink2 }}>Requested {formatStamp(view.run.requested_at)} · finished {formatStamp(view.run.finished_at)}</Box>
          <Box sx={{ fontSize: 13, color: ev.ink2 }}>Evidence age: {view.ageLabel} (age of the newest stored evidence this run was built from)</Box>

          {!view.doc.available && <Box data-document="unavailable" sx={{ color: ev.tWatch, fontSize: 13, mt: 1 }}>Document not available</Box>}

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
