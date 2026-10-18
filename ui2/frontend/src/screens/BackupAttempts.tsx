import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Pagination from "@mui/material/Pagination";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { listJobs, type JobPageView } from "../auth/adminApi";
import { Stamp } from "../shell/PresentedText";
import { m3 } from "../theme/m3Theme";
import { attemptWords } from "./backupWords";
import { JobTranscriptDrawer } from "./JobTranscriptDrawer";

const FULL_PAGE = 25;
const WORD_INK = { ok: m3.onSurfaceVar, bad: m3.criticalInk, warn: m3.onSurface, neutral: m3.onSurfaceVar } as const;

/**
 * Attempt identities and transcript affordances come from the same role-gated response as Jobs.
 *
 * `latestOnly` is the fleet table's LATEST ATTEMPT cell (one compact line). `collapseTo` is the device Backup tab: only
 * the newest N attempts until the operator chooses "Show all" (UI review 2026-10-18, F-16).
 */
export function BackupAttempts({ deviceId, latestOnly = false, version = 0, collapseTo, quietWhenEmpty = false }: {
  readonly deviceId: string;
  readonly latestOnly?: boolean;
  readonly version?: number;
  readonly collapseTo?: number;
  /** Render nothing when there is no attempt: the caller already shows the one empty state (FortiManager, 3.3). */
  readonly quietWhenEmpty?: boolean;
}) {
  const [page, setPage] = useState(1);
  const [expanded, setExpanded] = useState(false);
  const [result, setResult] = useState<JobPageView | null>(null);
  const [error, setError] = useState(false);
  const collapsed = !latestOnly && collapseTo !== undefined && !expanded;
  const pageSize = latestOnly ? 1 : collapsed ? collapseTo : FULL_PAGE;

  useEffect(() => {
    let active = true;
    setResult(null);
    setError(false);
    listJobs({ device_id: deviceId, job_type: "backup", page: collapsed ? 1 : page, page_size: pageSize })
      .then((value) => { if (active) setResult(value); })
      .catch(() => { if (active) setError(true); });
    return () => { active = false; };
  }, [deviceId, page, pageSize, collapsed, version]);

  if (error) return <Alert severity="warning">Backup attempts could not be loaded.</Alert>;
  if (!result) return <Typography variant="body2">Loading backup attempts…</Typography>;
  if (!result.items?.length) {
    if (quietWhenEmpty) return null;
    return latestOnly ? <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>No attempt</Typography> : <Typography variant="body2">No backup attempts on record.</Typography>;
  }

  return <Stack spacing={latestOnly ? 0 : 1}>
    {result.items.map((job) => {
      const words = attemptWords(job.state, job.terminal_reason);
      return <Box key={job.job_id}>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap={latestOnly ? "nowrap" : "wrap"} useFlexGap sx={latestOnly ? { whiteSpace: "nowrap" } : undefined}>
          {/* One timestamp component (R15): the fleet cell shows the age with the exact stamp on hover, the device tab the full stamp. */}
          <Stamp at={job.submitted_at} compact={latestOnly} />
          <Typography variant="body2" title={words.detail ?? undefined} sx={{ color: WORD_INK[words.tone], fontWeight: words.tone === "bad" ? 600 : 400 }}>
            {words.word}{words.inline ? ` · ${words.inline}` : ""}
          </Typography>
          {words.detail && !latestOnly && <Box component="details" sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
            <Box component="summary" sx={{ cursor: "pointer" }}>Details</Box>
            <Box sx={{ fontFamily: "monospace", fontSize: 12, wordBreak: "break-word", maxWidth: 560 }}>{words.detail}</Box>
          </Box>}
          <JobTranscriptDrawer jobId={job.job_id} hasTranscript={job.has_transcript} />
        </Stack>
      </Box>;
    })}
    {!latestOnly && collapsed && result.total > pageSize && (
      <Box><Button size="small" onClick={() => setExpanded(true)}>Show all {result.total} attempts</Button></Box>
    )}
    {!latestOnly && !collapsed && collapseTo !== undefined && (
      <Box><Button size="small" onClick={() => { setExpanded(false); setPage(1); }}>Show only the latest {collapseTo}</Button></Box>
    )}
    {!latestOnly && !collapsed && result.total > pageSize && <Pagination
      count={Math.ceil(result.total / pageSize)} page={page} onChange={(_, value) => setPage(value)}
      aria-label="Backup attempts pages" size="small" />}
  </Stack>;
}
