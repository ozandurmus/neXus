import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import { getJobTranscript, type JobTranscriptEntry } from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import { formatStamp } from "../shell/time";
import { transcriptErrorCode } from "../screens/transcriptDownload";
import { EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import {
  capTranscriptLines,
  orderTranscriptLines,
  TRANSCRIPT_EMPTY_WORDING,
  transcriptFailedWording,
  transcriptLineCountWording,
  transcriptStateWord,
} from "./estateJobTranscriptModel";
import { ESTATE_MONO, ev } from "./estateTokens";

type LoadState =
  | { readonly kind: "loading" }
  | { readonly kind: "failed"; readonly restricted: boolean; readonly code: string; readonly readAt: string }
  | { readonly kind: "ok"; readonly entries: readonly JobTranscriptEntry[]; readonly readAt: string };

/**
 * Read-only transcript drawer for one job of the Estate System screen. It reads the transcript once when it opens and
 * shows the lines in order. It has no cancel, reconcile or retry action: those stay in the standard view, which the
 * drawer links to with `standard=1`.
 */
export function EstateJobTranscriptDrawer({ jobId, scriptName, onClose }: {
  readonly jobId: string;
  /** The script the job belongs to, as the list names it; shown as a caption only. */
  readonly scriptName?: string;
  readonly onClose: () => void;
}) {
  const [state, setState] = useState<LoadState>({ kind: "loading" });

  useEffect(() => {
    let active = true;
    setState({ kind: "loading" });
    getJobTranscript(jobId)
      .then((entries) => { if (active) setState({ kind: "ok", entries, readAt: new Date().toISOString() }); })
      .catch((failure: unknown) => {
        if (!active) return;
        setState({
          kind: "failed",
          restricted: isRestricted(failure),
          code: transcriptErrorCode(failure as Parameters<typeof transcriptErrorCode>[0]),
          readAt: new Date().toISOString(),
        });
      });
    return () => { active = false; };
  }, [jobId]);

  const lines = useMemo(() => (state.kind === "ok" ? orderTranscriptLines(state.entries) : []), [state]);
  const capped = useMemo(() => capTranscriptLines(lines), [lines]);
  const standardHref = `?screen=operations&tab=jobs&q=${encodeURIComponent(jobId)}&standard=1`;

  return (
    <Drawer anchor="right" open onClose={onClose}
      PaperProps={{ sx: { width: "min(100vw, 900px)", height: "100vh", bgcolor: ev.surface, color: ev.ink } }}>
      <Box role="dialog" aria-label="Job transcript" sx={{ height: "100%", p: "20px 22px", display: "flex", flexDirection: "column", gap: "12px" }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
          <Box component="h2" sx={{ m: 0, fontSize: 18, fontWeight: 700 }}>Job transcript</Box>
          <Box component="button" type="button" onClick={onClose}
            sx={{ border: `1px solid ${ev.line}`, bgcolor: ev.surface, color: ev.ink, borderRadius: "999px", px: "12px", py: "4px", fontSize: 12.5, cursor: "pointer", fontFamily: "inherit" }}>
            Close
          </Box>
        </Box>
        <Box sx={{ fontSize: 13, color: ev.ink3 }}>
          {scriptName ? <>{scriptName} · </> : null}
          <Box component="span" sx={{ fontFamily: ESTATE_MONO }}>{jobId}</Box>
        </Box>
        <Box component="a" href={standardHref} sx={{ fontSize: 13, color: ev.accent, fontWeight: 600 }}>
          Open in standard view (cancel and reconcile live there) →
        </Box>

        {state.kind === "loading" ? <Box sx={{ fontSize: 13, color: ev.ink2 }}>Loading transcript…</Box> : null}

        {state.kind === "failed" ? (
          <Box role="alert" sx={{ display: "flex", alignItems: "center", gap: 1, fontSize: 13.5, fontWeight: 600, color: ev.unk }}>
            <StateBadge state="unk" word="UNKNOWN" dense />
            {transcriptFailedWording(state.restricted, state.code)}
          </Box>
        ) : null}

        {state.kind === "ok" && lines.length === 0 ? (
          <Box sx={{ fontSize: 13.5, fontWeight: 600, color: ev.ink2 }}>{TRANSCRIPT_EMPTY_WORDING}</Box>
        ) : null}

        {state.kind === "ok" && lines.length > 0 ? (
          <>
            <Box sx={{ fontSize: 13, color: ev.ink2 }}>
              Showing {transcriptLineCountWording(capped.shown.length)} of {transcriptLineCountWording(lines.length)} as read
            </Box>
            <Box component="ol" aria-label="Transcript lines" sx={{ m: 0, p: 0, listStyle: "none", overflow: "auto", flex: 1, minHeight: 0 }}>
              {capped.shown.map((entry) => (
                <Box component="li" key={entry.seq} sx={{ borderBottom: `1px solid ${ev.line}`, py: "8px", fontSize: 13 }}>
                  <Box sx={{ display: "flex", gap: "10px", flexWrap: "wrap", alignItems: "baseline", mb: "4px", color: ev.ink2 }}>
                    <Box component="time" dateTime={entry.at} sx={{ fontFamily: ESTATE_MONO }}>{formatStamp(entry.at)}</Box>
                    <Box component="span" sx={{ fontWeight: 700, color: ev.ink }}>{transcriptStateWord(entry)}</Box>
                    <Box component="span">{entry.channel}</Box>
                  </Box>
                  <Box sx={{ whiteSpace: "pre-wrap", wordBreak: "break-word", fontFamily: ESTATE_MONO }}>{entry.text}</Box>
                </Box>
              ))}
            </Box>
            {capped.hidden > 0 ? (
              <Box sx={{ fontSize: 12.5, color: ev.ink3 }}>
                <MoreFooter shown={capped.shown.length} total={lines.length} noun="lines not shown" />
              </Box>
            ) : null}
          </>
        ) : null}

        <EvidenceStamp at={state.kind === "ok" ? state.readAt : null} source="job transcript read" />
      </Box>
    </Drawer>
  );
}
