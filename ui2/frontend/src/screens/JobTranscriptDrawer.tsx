import { useEffect, useMemo, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Drawer from "@mui/material/Drawer";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";
import { getJobTranscript, type ApiError, type JobTranscriptEntry } from "../auth/adminApi";
import { downloadJobTranscript, saveTranscriptFile, transcriptErrorCode, type TranscriptSource } from "./transcriptDownload";

export { transcriptAsText } from "./transcriptDownload";

/**
 * The transcript actions of one job: `Transcript` opens the right-hand panel, `Download` saves the same transcript as
 * a text file without opening it, and the panel header offers the same Download for what it shows. Each instance
 * belongs to one row: the job id, type and submission instant are captured as props and read at click time, so a
 * download is always this row's transcript, whatever panel was opened or closed before (PO 2026-10-25).
 */
export function JobTranscriptDrawer({ jobId, jobType, submittedAt, hasTranscript, title = "Backup transcript" }: {
  readonly jobId: string;
  /** The job type as the server names it; the download's file name prefix. */
  readonly jobType?: string;
  /** The job's submission instant (ISO); the download's file name stamp, in Turkey time. */
  readonly submittedAt?: string;
  readonly hasTranscript: boolean;
  readonly title?: string;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [entries, setEntries] = useState<JobTranscriptEntry[] | null>(null);
  const [error, setError] = useState("");
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState("");
  const source = useMemo<TranscriptSource>(() => ({ jobId, jobType, at: submittedAt }), [jobId, jobType, submittedAt]);

  useEffect(() => {
    if (!open) return;
    let active = true;
    setEntries(null);
    setError("");
    getJobTranscript(jobId).then((value) => { if (active) setEntries(value); })
      .catch((failure: ApiError | undefined) => { if (active) setError(`Transcript could not be loaded (${transcriptErrorCode(failure)}).`); });
    return () => { active = false; };
  }, [open, jobId]);

  const visible = useMemo(() => (entries ?? []).filter((entry) =>
    `${entry.at} ${entry.channel} ${entry.kind} ${entry.text}`.toLowerCase().includes(query.toLowerCase())), [entries, query]);
  if (!hasTranscript) return null;

  const downloadFresh = async () => {
    setDownloading(true);
    setDownloadError("");
    try {
      await downloadJobTranscript(source);
    } catch (failure) {
      setDownloadError(`Transcript could not be downloaded (${transcriptErrorCode(failure as ApiError | undefined)}).`);
    } finally {
      setDownloading(false);
    }
  };

  return <>
    <Button size="small" onClick={(event) => { event.stopPropagation(); setOpen(true); }}>Transcript</Button>
    <Button size="small" disabled={downloading} title="Save this job's transcript as a text file"
      onClick={(event) => { event.stopPropagation(); void downloadFresh(); }}>{downloading ? "Downloading…" : "Download"}</Button>
    {downloadError && <Typography component="span" variant="caption" role="alert" sx={{ color: m3.criticalInk }}>{downloadError}</Typography>}
    <Drawer anchor="right" open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { width: "min(100vw, 900px)", height: "100vh", bgcolor: m3.scLowest, color: m3.onSurface } }}>
      <Stack sx={{ height: "100%", p: 2, gap: 1.5 }}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>{title}</Typography>
          <TextField size="small" label="Search transcript" value={query} onChange={(e) => setQuery(e.target.value)} />
          <Button disabled={!entries} title="Save this transcript as a text file" onClick={() => { if (entries) saveTranscriptFile(source, entries); }}>Download</Button>
          <Button onClick={() => setOpen(false)}>Close</Button>
        </Stack>
        <Box sx={{ overflow: "auto", flex: 1, fontFamily: "monospace", fontSize: 13, whiteSpace: "pre-wrap" }}>
          {error ? <Alert severity="error">{error}</Alert> : (entries === null ? "Loading transcript…" : entries.length === 0 ? "No steps were recorded (bug)" : visible.length === 0 ? "No matching steps." : visible.map((entry) => <Box key={entry.seq} sx={{ borderBottom: `1px solid ${m3.outlineVar}`, py: 1 }}>
            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
              <span>{entry.at} (+{entry.elapsedMs} ms)</span><Chip size="small" label={entry.channel.toUpperCase()} />
              <strong style={{ color: entry.kind === "command" || entry.kind === "request" ? m3.primary : "inherit" }}>{entry.kind}</strong>
            </Stack>
            {entry.text}
          </Box>))}
        </Box>
      </Stack>
    </Drawer>
  </>;
}
