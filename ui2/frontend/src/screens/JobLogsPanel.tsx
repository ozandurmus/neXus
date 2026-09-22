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
import Pagination from "@mui/material/Pagination";
import { StatePanel, RestrictedPanel, isRestricted, Ts } from "../shell/States";
import { formatDuration } from "../shell/time";
import { MONO, m3 } from "../theme/m3Theme";
import {
  downloadJobsCsv,
  jobFacets,
  listDevices,
  listJobs,
  type ApiError,
  type JobEventView,
  type JobFacetsView,
  type JobQueryParams,
} from "../auth/adminApi";

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

/** A datetime-local input value (local time, no zone) to the ISO instant the API expects; empty stays empty. */
export function localInputToIso(value: string): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

const PAGE_SIZES = [25, 50, 100, 200] as const;

/**
 * Jobs screen (Product Owner P0, 2026-09-22): the whole history, not the last
 * 50; filters on the server (state, type, device, time window, free text);
 * numbered pages; CSV export of the current filter. Live polling refreshes
 * the page being looked at and never resets the filters.
 */
/** Local "datetime-local" input value for a moment {@code hours} ago. */
function hoursAgoLocal(hours: number): string {
  const d = new Date(Date.now() - hours * 3600 * 1000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function JobLogsPanel({
  initialState = "",
  initialJobType = "",
  initialSinceHours,
  initialText = "",
  emptyTitle,
  emptyBody,
}: {
  readonly initialState?: string;
  readonly initialJobType?: string;
  readonly initialSinceHours?: number;
  readonly initialText?: string;
  /** Overrides the generic empty-result message while the filter is unchanged from this panel's own default
      (e.g. the Queue tab's "Queue empty · 0 requested · 0 claimed · 0 executing", review §3) -- never shown
      once the viewer narrows the filter further, since the breakdown would no longer be provably zero. */
  readonly emptyTitle?: string;
  readonly emptyBody?: string;
} = {}) {
  const [page, setPage] = useState<{ items: readonly JobEventView[]; total: number } | null>(null);
  const [deviceNames, setDeviceNames] = useState<Record<string, string>>({});
  const [facets, setFacets] = useState<JobFacetsView>({ states: [], job_types: [] });
  const [error, setError] = useState<ApiError | string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [expandedJobId, setExpandedJobId] = useState<string | null>(null);
  const [exportBusy, setExportBusy] = useState(false);

  const [state, setState] = useState(initialState);
  const [jobType, setJobType] = useState(initialJobType);
  const [deviceId, setDeviceId] = useState("");
  const [sinceLocal, setSinceLocal] = useState(initialSinceHours ? hoursAgoLocal(initialSinceHours) : "");
  const [untilLocal, setUntilLocal] = useState("");
  const [text, setText] = useState(initialText);
  const [pageNumber, setPageNumber] = useState(1);
  const [pageSize, setPageSize] = useState<number>(50);

  const params: JobQueryParams = {
    state: state || undefined,
    job_type: jobType || undefined,
    device_id: deviceId || undefined,
    since: localInputToIso(sinceLocal),
    until: localInputToIso(untilLocal),
    q: text.trim() || undefined,
    page: pageNumber,
    page_size: pageSize,
  };
  const paramsKey = JSON.stringify(params);

  const fetchJobs = useCallback(() => {
    const current = JSON.parse(paramsKey) as JobQueryParams;
    listJobs(current)
      .then((data) => {
        setPage({ items: data.items ?? [], total: data.total ?? 0 });
        setError(null);
      })
      .catch((err: ApiError) => setError(err));
  }, [paramsKey]);

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
    fetchDeviceNames();
    jobFacets().then(setFacets).catch(() => setFacets({ states: [], job_types: [] }));
  }, [fetchDeviceNames]);

  useEffect(() => {
    fetchJobs();
    if (!autoRefresh) return;
    const interval = setInterval(fetchJobs, 4000);
    return () => clearInterval(interval);
  }, [fetchJobs, autoRefresh]);

  const resetToFirstPage = () => setPageNumber(1);

  const handleExport = async () => {
    setExportBusy(true);
    try {
      const { blob, fileName } = await downloadJobsCsv(params);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setExportBusy(false);
    }
  };

  /** A human sentence for an error/restriction StatePanel; the raw status stays in the mono code line. */
  function errorMessage(err: ApiError | string): string {
    if (typeof err === "string") return err;
    return typeof err.body?.error === "string" ? err.body.error : "The job log read failed.";
  }
  function errorCode(err: ApiError | string): string | undefined {
    return typeof err === "string" ? undefined : err.status ? `HTTP ${err.status}` : undefined;
  }

  if (error && !page) {
    return isRestricted(error)
      ? <RestrictedPanel area="Job logs" />
      : <StatePanel variant="error" title="Job logs unavailable" body={errorMessage(error)} code={errorCode(error)}
          action={<Button size="small" variant="outlined" onClick={fetchJobs} sx={{ textTransform: "none" }}>Retry</Button>} />;
  }
  if (page === null) return <StatePanel variant="empty" title="Job logs" body="Loading…" />;

  const pageCount = Math.max(1, Math.ceil(page.total / pageSize));
  const deviceOptions = Object.entries(deviceNames).sort((a, b) => a[1].localeCompare(b[1]));
  const isDefaultFilter = state === initialState && !jobType && !deviceId && !sinceLocal && !untilLocal && !text.trim();

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
        <TextField
          select
          size="small"
          label="State"
          value={state}
          onChange={(e) => { setState(e.target.value); resetToFirstPage(); }}
          SelectProps={{ native: true }}
          InputLabelProps={{ shrink: true }}
          sx={{ minWidth: 150 }}
        >
          <option value="">Any</option>
          {initialState.includes(",") && <option value={initialState}>{initialState.replaceAll(",", " | ")}</option>}
          {facets.states.map((s) => <option key={s} value={s}>{s}</option>)}
        </TextField>
        <TextField
          select
          size="small"
          label="Type"
          value={jobType}
          onChange={(e) => { setJobType(e.target.value); resetToFirstPage(); }}
          SelectProps={{ native: true }}
          InputLabelProps={{ shrink: true }}
          sx={{ minWidth: 220 }}
        >
          <option value="">Any</option>
          {initialJobType && !facets.job_types.includes(initialJobType) && <option value={initialJobType}>any *_{initialJobType}</option>}
          {facets.job_types.map((t) => <option key={t} value={t}>{t}</option>)}
        </TextField>
        <TextField
          select
          size="small"
          label="Device"
          value={deviceId}
          onChange={(e) => { setDeviceId(e.target.value); resetToFirstPage(); }}
          SelectProps={{ native: true }}
          InputLabelProps={{ shrink: true }}
          sx={{ minWidth: 220 }}
        >
          <option value="">Any</option>
          {deviceOptions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </TextField>
        <TextField
          size="small"
          type="datetime-local"
          label="From"
          value={sinceLocal}
          onChange={(e) => { setSinceLocal(e.target.value); resetToFirstPage(); }}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          size="small"
          type="datetime-local"
          label="To"
          value={untilLocal}
          onChange={(e) => { setUntilLocal(e.target.value); resetToFirstPage(); }}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          size="small"
          label="Search"
          placeholder="job id, device id or reason…"
          value={text}
          onChange={(e) => { setText(e.target.value); resetToFirstPage(); }}
          InputLabelProps={{ shrink: true }}
          sx={{ minWidth: 260 }}
        />
        <Box sx={{ flexGrow: 1 }} />
        {/* One order everywhere this panel is used -- Jobs, History and Admin Job Logs (review §3/§4):
            Refresh, Export CSV, then Live polling. */}
        <Button size="small" variant="outlined" onClick={fetchJobs} sx={{ textTransform: "none" }}>
          Refresh
        </Button>
        <Button size="small" variant="outlined" disabled={exportBusy} onClick={() => void handleExport()} sx={{ textTransform: "none" }}>
          {exportBusy ? "Exporting…" : "Export CSV"}
        </Button>
        <Button
          size="small"
          variant={autoRefresh ? "contained" : "outlined"}
          onClick={() => setAutoRefresh((v) => !v)}
          sx={{ textTransform: "none" }}
        >
          {autoRefresh ? "Live polling: ON" : "Live polling: OFF"}
        </Button>
      </Stack>

      {error && (
        isRestricted(error)
          ? <RestrictedPanel area="Job logs" />
          : <StatePanel variant="error" title="Job logs read failed" body={errorMessage(error)} code={errorCode(error)}
              action={<Button size="small" variant="outlined" onClick={fetchJobs} sx={{ textTransform: "none" }}>Retry</Button>} />
      )}

      {page.items.length === 0 ? (
        emptyTitle && isDefaultFilter
          ? <StatePanel variant="empty" title={emptyTitle} body={emptyBody} />
          : <StatePanel variant="empty" title="No jobs match this filter" body="No background job matched the current filter." />
      ) : (
        <>
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
                {page.items.map((job) => {
                  const isExpanded = expandedJobId === job.job_id;
                  // The API's terminal_reason repeats the terminal state on a clean run (review §3); a
                  // completed job has nothing to explain, so the column reads "--" there and keeps the
                  // reason only where it is one -- a failure, rejection or unknown outcome.
                  const reasonText = job.state === "COMPLETED" ? "—" : (job.terminal_reason || "—");
                  return (
                    <TableRow
                      key={job.job_id}
                      hover
                      onClick={() => setExpandedJobId(isExpanded ? null : job.job_id)}
                      sx={{ cursor: "pointer" }}
                    >
                      <TableCell sx={{ fontFamily: MONO, fontSize: "0.8rem" }}>
                        {isExpanded ? job.job_id : `${job.job_id.slice(0, 8)}...`}
                      </TableCell>
                      <TableCell sx={{ fontWeight: 500 }}>
                        {job.device_name ?? deviceNames[job.target_device_id] ?? "Unknown device"} · {job.target_device_id}
                      </TableCell>
                      <TableCell sx={{ fontSize: "0.8rem" }}>{job.job_type}</TableCell>
                      <TableCell>
                        <Chip label={job.state} size="small" color={stateColor(job.state)} variant="outlined" />
                      </TableCell>
                      {/* Duration is neutral -- never red; red is reserved for the state word, shown in the
                          State column above (review §3 "Duration is red on every completed job"). */}
                      <TableCell sx={{ fontFamily: MONO, fontVariantNumeric: "tabular-nums", color: m3.onSurface }}>
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
                        {reasonText}
                      </TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        <Ts at={job.submitted_at} />
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>

          <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" color="text.secondary">
              {`${(pageNumber - 1) * pageSize + 1}–${Math.min(pageNumber * pageSize, page.total)} of ${page.total} jobs`}
            </Typography>
            <Stack direction="row" spacing={2} alignItems="center">
              <TextField
                select
                size="small"
                label="Per page"
                value={pageSize}
                onChange={(e) => { setPageSize(Number(e.target.value)); resetToFirstPage(); }}
                SelectProps={{ native: true }}
                InputLabelProps={{ shrink: true }}
                sx={{ width: 110 }}
              >
                {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
              </TextField>
              <Pagination
                count={pageCount}
                page={Math.min(pageNumber, pageCount)}
                onChange={(_e, value) => setPageNumber(value)}
                color="primary"
                showFirstButton
                showLastButton
                size="small"
              />
            </Stack>
          </Stack>
        </>
      )}
    </Box>
  );
}
