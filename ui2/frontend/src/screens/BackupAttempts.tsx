import { useEffect, useMemo, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { listJobs, listLatestJobsByDevice, type JobEventView, type JobPageView } from "../auth/adminApi";
import { PaginationFooter } from "../shell/DataTable";
import { Stamp } from "../shell/PresentedText";
import { m3 } from "../theme/m3Theme";
import { attemptWords } from "./backupWords";
import { JobTranscriptDrawer } from "./JobTranscriptDrawer";

const FULL_PAGE = 25;
const WORD_INK = { ok: m3.onSurfaceVar, bad: m3.criticalInk, warn: m3.warningInk, neutral: m3.onSurfaceVar } as const;

/** One attempt on one line: the stamp (age only when `compact`), the status words, and the transcript entry point. */
export function AttemptLine({ job, compact = false, archiveComplete }: {
  readonly job: JobEventView;
  readonly compact?: boolean;
  readonly archiveComplete?: boolean;
}) {
  const words = attemptWords(job.state, job.terminal_reason, { archiveComplete });
  return (
    <Stack direction="row" spacing={1} alignItems="center" flexWrap={compact ? "nowrap" : "wrap"} useFlexGap sx={compact ? { whiteSpace: "nowrap" } : undefined}>
      {/* One timestamp component (R15): the fleet cell shows the age with the exact stamp on hover, the device tab the full stamp. */}
      <Stamp at={job.submitted_at} compact={compact} />
      <Typography variant="body2" title={words.detail ?? undefined} sx={{ color: WORD_INK[words.tone], fontWeight: words.tone === "bad" ? 600 : 400 }}>
        {words.word}{words.inline ? ` · ${words.inline}` : ""}
      </Typography>
      {words.link && (
        <Typography component="a" href={words.link.href} variant="body2" sx={{ color: m3.primary, textDecoration: "underline", whiteSpace: "nowrap" }}>
          {words.link.label}
        </Typography>
      )}
      {words.detail && !compact && <Box component="details" sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
        <Box component="summary" sx={{ cursor: "pointer" }}>Details</Box>
        <Box sx={{ fontFamily: "monospace", fontSize: 12, wordBreak: "break-word", maxWidth: 560 }}>{words.detail}</Box>
      </Box>}
      <JobTranscriptDrawer jobId={job.job_id} jobType={job.job_type} submittedAt={job.submitted_at} hasTranscript={job.has_transcript} />
    </Stack>
  );
}

/** A device's latest backup attempt as the fleet table holds it: not read yet, the read failed, none on record, or the job. */
export type LatestAttempt = "loading" | "error" | "none" | JobEventView;

/** The fleet table's LATEST ATTEMPT cell (one compact line), from the value {@link useLatestBackupAttempts} holds. */
export function LatestAttemptCell({ latest, archiveComplete }: { readonly latest: LatestAttempt; readonly archiveComplete?: boolean }) {
  if (latest === "error") return <Alert severity="warning">Backup attempts could not be loaded.</Alert>;
  if (latest === "loading") return <Typography variant="body2">Loading backup attempts…</Typography>;
  if (latest === "none") return <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>No attempt</Typography>;
  return <AttemptLine job={latest} compact archiveComplete={archiveComplete} />;
}

/** The time of a latest attempt for ordering the fleet table; unknown until read, and when there is none. */
export function latestAttemptTime(latest: LatestAttempt | undefined): string | null {
  return latest && typeof latest === "object" ? latest.submitted_at : null;
}

type AttemptsBatch =
  | { readonly version: number; readonly state: "loading" }
  | { readonly version: number; readonly state: "error" }
  | { readonly version: number; readonly state: "ok"; readonly byDevice: ReadonlyMap<string, JobEventView>; readonly complete: boolean };

/**
 * The latest backup attempt of every device in the fleet table, read in a single batched server request rather than
 * one /api/v2/jobs call per device (Product Owner, 2026-11-04: a header click orders every row, so the table has
 * to know every row's attempt, not the fifty in view; 2026-11-14: one batched read for all devices). A new `version`
 * (a backup was requested) reads them again.
 */
export function useLatestBackupAttempts(deviceIds: readonly string[], version = 0): ReadonlyMap<string, LatestAttempt> {
  const [batch, setBatch] = useState<AttemptsBatch>({ version, state: "loading" });

  useEffect(() => {
    let active = true;
    setBatch({ version, state: "loading" });
    listLatestJobsByDevice({ job_type: "backup" })
      .then((value) => {
        if (!active) return;
        const byDevice = new Map<string, JobEventView>();
        for (const item of value.items ?? []) {
          if (item.target_device_id && !byDevice.has(item.target_device_id)) {
            byDevice.set(item.target_device_id, item);
          }
        }
        // the server bounds the read; a full page may have left devices out, and an absent device is then unknown, not "none"
        const complete = (value.items?.length ?? 0) < (value.page_size ?? Number.POSITIVE_INFINITY);
        setBatch({ version, state: "ok", byDevice, complete });
      })
      .catch(() => {
        if (active) setBatch({ version, state: "error" });
      });
    return () => { active = false; };
  }, [version]);

  const idsKey = deviceIds.join("\n");
  return useMemo(() => {
    if (batch.version !== version || batch.state === "loading") return new Map();
    const ids = idsKey ? idsKey.split("\n") : [];
    const map = new Map<string, LatestAttempt>();
    for (const id of ids) {
      map.set(id, batch.state === "error" ? "error" : (batch.byDevice.get(id) ?? (batch.complete ? "none" : "error")));
    }
    return map;
  }, [batch, version, idsKey]);
}

/**
 * Attempt identities and transcript affordances come from the same role-gated response as Jobs.
 *
 * `latestOnly` is one compact line for the newest attempt (the fleet table now reads its cells through
 * {@link useLatestBackupAttempts}). `collapseTo` is the device Backup tab: only the newest N attempts until the operator
 * chooses "Show all" (UI review 2026-10-18, F-16).
 */
export function BackupAttempts({ deviceId, latestOnly = false, version = 0, collapseTo, quietWhenEmpty = false, archiveComplete }: {
  readonly deviceId: string;
  readonly latestOnly?: boolean;
  readonly version?: number;
  readonly collapseTo?: number;
  /** Render nothing when there is no attempt: the caller already shows the one empty state (FortiManager, 3.3). */
  readonly quietWhenEmpty?: boolean;
  /** Whether the stored archive for this device is complete per the recorded manifest. */
  readonly archiveComplete?: boolean;
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
    {result.items.map((job) => <Box key={job.job_id}><AttemptLine job={job} compact={latestOnly} archiveComplete={archiveComplete} /></Box>)}
    {!latestOnly && collapsed && result.total > pageSize && (
      <Box><Button size="small" onClick={() => setExpanded(true)}>Show all {result.total} attempts</Button></Box>
    )}
    {!latestOnly && !collapsed && collapseTo !== undefined && (
      <Box><Button size="small" onClick={() => { setExpanded(false); setPage(1); }}>Show only the latest {collapseTo}</Button></Box>
    )}
    {/* The one pagination footer (post-release review 2026-10-20, N15), as the Backups fleet table has it. */}
    {!latestOnly && !collapsed && result.total > pageSize && <Box role="navigation" aria-label="Backup attempts pages" sx={{ display: "flex" }}>
      <PaginationFooter offset={(page - 1) * pageSize} size={pageSize} total={result.total} noun="attempts" onPage={(offset) => setPage(Math.floor(offset / pageSize) + 1)} />
    </Box>}
  </Stack>;
}
