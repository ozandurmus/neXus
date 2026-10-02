import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Pagination from "@mui/material/Pagination";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { listJobs, type JobPageView } from "../auth/adminApi";
import { Ts } from "../shell/States";
import { JobTranscriptDrawer } from "./JobTranscriptDrawer";

/** Attempt identities and transcript affordances come from the same role-gated response as Jobs. */
export function BackupAttempts({ deviceId, latestOnly = false, version = 0 }: {
  readonly deviceId: string;
  readonly latestOnly?: boolean;
  readonly version?: number;
}) {
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<JobPageView | null>(null);
  const [error, setError] = useState(false);
  const pageSize = latestOnly ? 1 : 25;

  useEffect(() => {
    let active = true;
    setResult(null);
    setError(false);
    listJobs({ device_id: deviceId, job_type: "backup", page, page_size: pageSize })
      .then((value) => { if (active) setResult(value); })
      .catch(() => { if (active) setError(true); });
    return () => { active = false; };
  }, [deviceId, page, pageSize, version]);

  if (error) return <Alert severity="warning">Backup attempts could not be loaded.</Alert>;
  if (!result) return <Typography variant="body2">Loading backup attempts…</Typography>;
  if (!result.items?.length) return latestOnly ? null : <Typography variant="body2">No backup attempts on record.</Typography>;

  return <Stack spacing={1}>
    {result.items.map((job) => <Box key={job.job_id}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        {latestOnly && <Typography variant="caption">Latest attempt</Typography>}
        <Ts at={job.submitted_at} />
        <Typography variant="body2" color={job.state === "FAILED" ? "error" : "text.secondary"}>
          {job.state}{job.terminal_reason ? `: ${job.terminal_reason}` : ""}
        </Typography>
        <JobTranscriptDrawer jobId={job.job_id} hasTranscript={job.has_transcript} />
      </Stack>
    </Box>)}
    {!latestOnly && result.total > pageSize && <Pagination
      count={Math.ceil(result.total / pageSize)} page={page} onChange={(_, value) => setPage(value)}
      aria-label="Backup attempts pages" size="small" />}
  </Stack>;
}
