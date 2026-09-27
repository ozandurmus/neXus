import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Drawer from "@mui/material/Drawer";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { getJobTranscript, type JobTranscriptEntry } from "../auth/adminApi";

export function transcriptAsText(entries: readonly JobTranscriptEntry[]): string {
  return entries.map((entry) => `${entry.at} (+${entry.elapsedMs} ms) [${entry.channel.toUpperCase()}] ${entry.kind}\n${entry.text}`).join("\n\n");
}

export function JobTranscriptDrawer({ jobId, hasTranscript }: { readonly jobId: string; readonly hasTranscript: boolean }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [entries, setEntries] = useState<JobTranscriptEntry[] | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    let active = true;
    getJobTranscript(jobId).then((value) => { if (active) setEntries(value); })
      .catch(() => { if (active) setError("Transcript could not be loaded."); });
    return () => { active = false; };
  }, [open, jobId]);

  const visible = useMemo(() => (entries ?? []).filter((entry) =>
    `${entry.at} ${entry.channel} ${entry.kind} ${entry.text}`.toLowerCase().includes(query.toLowerCase())), [entries, query]);
  if (!hasTranscript) return null;

  return <>
    <Button size="small" onClick={(event) => { event.stopPropagation(); setOpen(true); }}>Transcript</Button>
    <Drawer anchor="right" open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { width: "min(100vw, 900px)", height: "100vh", bgcolor: "#101418", color: "#e6edf3" } }}>
      <Stack sx={{ height: "100%", p: 2, gap: 1.5 }}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>Backup transcript</Typography>
          <TextField size="small" label="Search transcript" value={query} onChange={(e) => setQuery(e.target.value)} />
          <Button disabled={!entries} onClick={() => {
            const link = document.createElement("a");
            link.href = URL.createObjectURL(new Blob([transcriptAsText(entries ?? [])], { type: "text/plain;charset=utf-8" }));
            link.download = `backup-transcript-${jobId}.txt`; link.click(); URL.revokeObjectURL(link.href);
          }}>Download (.txt)</Button>
          <Button onClick={() => setOpen(false)}>Close</Button>
        </Stack>
        <Box sx={{ overflow: "auto", flex: 1, fontFamily: "monospace", fontSize: 13, whiteSpace: "pre-wrap" }}>
          {error || (entries === null ? "Loading transcript…" : visible.map((entry) => <Box key={entry.seq} sx={{ borderBottom: "1px solid #30363d", py: 1 }}>
            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
              <span>{entry.at} (+{entry.elapsedMs} ms)</span><Chip size="small" label={entry.channel.toUpperCase()} />
              <strong style={{ color: entry.kind === "command" || entry.kind === "request" ? "#79c0ff" : "inherit" }}>{entry.kind}</strong>
            </Stack>
            {entry.text}
          </Box>))}
        </Box>
      </Stack>
    </Drawer>
  </>;
}
