import { useState, useEffect, useCallback } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import Chip from "@mui/material/Chip";
import TextField from "@mui/material/TextField";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";

import { EmptyPanel } from "../shell/ScreenLayout";
import { listDevices, listJobs, type JobEventView, type ApiError } from "../auth/adminApi";

function formatDuration(ms?: number): string {
  if (ms === undefined || ms === null) return "-";
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  const mins = Math.floor(ms / 60000);
  const secs = Math.floor((ms % 60000) / 1000);
  return `${mins}m ${secs}s`;
}

function stateColor(state: string): "default" | "primary" | "secondary" | "error" | "info" | "success" | "warning" {
  switch (state) {
    case "COMPLETED":
      return "success";
    case "FAILED":
      return "error";
    case "EXECUTING":
      return "info";
    case "CLAIMED":
    case "REQUESTED":
      return "warning";
    default:
      return "default";
  }
}

export function JobLogsPanel() {
  const [jobs, setJobs] = useState<JobEventView[] | null>(null);
  const [deviceNames, setDeviceNames] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [expandedJobId, setExpandedJobId] = useState<string | null>(null);

  const fetchJobs = useCallback(() => {
    listJobs()
      .then((data) => {
        setJobs(data);
        setError(null);
      })
      .catch((err: ApiError) => {
        setError(typeof err.body?.error === "string" ? err.body.error : `Request failed (status ${err.status})`);
      });
  }, []);

  const fetchDeviceNames = useCallback(() => {
    listDevices()
      .then(({ devices }) => {
        // A device with no recorded hostname contributes no entry, so its rows
        // read as unknown rather than carrying an empty name beside the identifier.
        setDeviceNames(Object.fromEntries(
          (devices ?? [])
            .filter((device): device is typeof device & { hostname: string } => Boolean(device.hostname))
            .map((device) => [device.device_id, device.hostname]),
        ));
      })
      // The identifier is the record; a name we could not read stays unknown
      // rather than blanking the log or failing the panel.
      .catch(() => setDeviceNames({}));
  }, []);

  useEffect(() => {
    fetchJobs();
    fetchDeviceNames();
    if (!autoRefresh) return;
    const interval = setInterval(fetchJobs, 4000);
    return () => clearInterval(interval);
  }, [fetchJobs, fetchDeviceNames, autoRefresh]);

  if (error && !jobs) return <EmptyPanel title="Job logs unavailable" body={error} />;
  if (jobs === null) return <EmptyPanel title="Job logs" body="Loading…" />;

  const filtered = jobs.filter((j) => {
    const q = filter.toLowerCase();
    return (
      j.job_id.toLowerCase().includes(q) ||
      j.target_device_id.toLowerCase().includes(q) ||
      j.job_type.toLowerCase().includes(q) ||
      j.state.toLowerCase().includes(q) ||
      (j.terminal_reason && j.terminal_reason.toLowerCase().includes(q))
    );
  });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between">
        <TextField
          size="small"
          placeholder="Filter by device, state, or reason..."
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          sx={{ minWidth: 320 }}
        />
        <Stack direction="row" spacing={1} alignItems="center">
          <Button
            size="small"
            variant={autoRefresh ? "contained" : "outlined"}
            onClick={() => setAutoRefresh((v) => !v)}
            sx={{ textTransform: "none" }}
          >
            {autoRefresh ? "Live polling: ON" : "Live polling: OFF"}
          </Button>
          <Button size="small" variant="outlined" onClick={fetchJobs} sx={{ textTransform: "none" }}>
            Refresh
          </Button>
        </Stack>
      </Stack>

      {filtered.length === 0 ? (
        <EmptyPanel title="No jobs matching filter" body="No background jobs matched your criteria." />
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Job logs">
            <TableHead>
              <TableRow sx={{ bgcolor: "action.hover" }}>
                <TableCell sx={{ fontWeight: 600 }}>Job ID</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Target Device</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Type</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>State</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Duration</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Terminal Reason / Error</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Submitted At</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map((job) => {
                const isExpanded = expandedJobId === job.job_id;
                return (
                  <TableRow
                    key={job.job_id}
                    hover
                    onClick={() => setExpandedJobId(isExpanded ? null : job.job_id)}
                    sx={{ cursor: "pointer" }}
                  >
                    <TableCell sx={{ fontFamily: "monospace", fontSize: "0.8rem" }}>
                      {job.job_id.slice(0, 8)}...
                    </TableCell>
                    <TableCell sx={{ fontWeight: 500 }}>
                      {deviceNames[job.target_device_id] ?? "Unknown device"} · {job.target_device_id}
                    </TableCell>
                    <TableCell sx={{ fontSize: "0.8rem" }}>{job.job_type}</TableCell>
                    <TableCell>
                      <Chip label={job.state} size="small" color={stateColor(job.state)} variant="outlined" />
                    </TableCell>
                    <TableCell sx={{ fontWeight: 600, color: (job.duration_ms || 0) > 20000 ? "error.main" : "text.primary" }}>
                      {formatDuration(job.duration_ms)}
                    </TableCell>
                    <TableCell
                      sx={{
                        maxWidth: 360,
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: isExpanded ? "normal" : "nowrap",
                        color: job.state === "FAILED" ? "error.main" : "text.secondary",
                        fontSize: "0.8rem",
                      }}
                    >
                      {job.terminal_reason || "-"}
                    </TableCell>
                    <TableCell sx={{ fontSize: "0.75rem", color: "text.secondary", whiteSpace: "nowrap" }}>
                      {job.submitted_at ? new Date(job.submitted_at).toLocaleTimeString() : "-"}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}
