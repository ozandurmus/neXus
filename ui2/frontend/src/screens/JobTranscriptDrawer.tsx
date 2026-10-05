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

export function transcriptAsText(entries: readonly JobTranscriptEntry[]): string {
  return entries.map((entry) => `${entry.at} (+${entry.elapsedMs} ms) [${entry.channel.toUpperCase()}] ${entry.kind}\n${entry.text}`).join("\n\n");
}

export function JobTranscriptDrawer({ jobId, hasTranscript, title = "Backup transcript" }: { readonly jobId: string; readonly hasTranscript: boolean; readonly title?: string }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [entries, setEntries] = useState<JobTranscriptEntry[] | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    let active = true;
    setEntries(null);
    setError("");
    getJobTranscript(jobId).then((value) => { if (active) setEntries(value); })
      .catch((failure: ApiError | undefined) => {
        const code = failure?.body?.error;
        const known = ["TRANSCRIPT_DECRYPT_FAILED", "TRANSCRIPT_PARSE_FAILED", "TRANSCRIPT_MISSING", "TRANSCRIPT_STORE_UNAVAILABLE"];
        if (active) setError(`Transcript could not be loaded (${typeof code === "string" && known.includes(code)
          ? code : failure?.status === 403 ? "TRANSCRIPT_FORBIDDEN" : "TRANSCRIPT_LOAD_FAILED"}).`);
      });
    return () => { active = false; };
  }, [open, jobId]);

  const visible = useMemo(() => (entries ?? []).filter((entry) =>
    `${entry.at} ${entry.channel} ${entry.kind} ${entry.text}`.toLowerCase().includes(query.toLowerCase())), [entries, query]);
  if (!hasTranscript) return null;

  return <>
    <Button size="small" onClick={(event) => { event.stopPropagation(); setOpen(true); }}>Transcript</Button>
    <Drawer anchor="right" open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { width: "min(100vw, 900px)", height: "100vh", bgcolor: m3.scLowest, color: m3.onSurface } }}>
      <Stack sx={{ height: "100%", p: 2, gap: 1.5 }}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>{title}</Typography>
          <TextField size="small" label="Search transcript" value={query} onChange={(e) => setQuery(e.target.value)} />
          <Button disabled={!entries} onClick={() => {
            const link = document.createElement("a");
            link.href = URL.createObjectURL(new Blob([transcriptAsText(entries ?? [])], { type: "text/plain;charset=utf-8" }));
            link.download = `backup-transcript-${jobId}.txt`; link.click(); URL.revokeObjectURL(link.href);
          }}>Download (.txt)</Button>
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
