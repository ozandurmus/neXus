import { Fragment, useState, useEffect, useCallback } from "react";
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
import IconButton from "@mui/material/IconButton";
import Pagination from "@mui/material/Pagination";
import FormControlLabel from "@mui/material/FormControlLabel";
import Switch from "@mui/material/Switch";
import { StatePanel, RestrictedPanel, isRestricted } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import type { Tone } from "../shell/tone";
import { Presented, Stamp } from "../shell/PresentedText";
import { displayInputToIso, formatDuration, isoToDisplayInput, DISPLAY_TZ_LABEL } from "../shell/time";
import { completedReasonOutcome, jobOutcomeWord, jobStateWord, outcomeEchoesState, outcomeLine, readinessReasonLabel } from "../shell/presenters";
import { urlParam, useUrlParamSync, writeUrlParams } from "../shell/urlParams";
import { MONO, m3 } from "../theme/m3Theme";
import { jobTypeLabel } from "../jobs/jobTypeLabel";
import { JobTranscriptDrawer } from "./JobTranscriptDrawer";
import {
  cancelJob,
  downloadJobsCsv,
  getJobStats,
  jobFacets,
  listDevices,
  listJobs,
  type ApiError,
  type JobEventView,
  type JobFacetsView,
  type JobQueryParams,
} from "../auth/adminApi";

/** The state word's colour from the role table: Completed green, Failed red, Running brand, queued or claimed amber, anything else neutral. */
function stateTone(state: string): Tone {
  switch (state) {
    case "COMPLETED":
      return "ok";
    case "FAILED":
      return "bad";
    case "EXECUTING":
      return "brand";
    case "CLAIMED":
    case "REQUESTED":
      return "warn";
    default:
      return "neutral";
  }
}

/** A datetime-local input value, typed in display time (GMT+3), to the ISO instant the API expects; empty stays empty. */
export function localInputToIso(value: string): string | undefined {
  return displayInputToIso(value);
}

const PAGE_SIZES = [25, 50, 100, 200] as const;

export { jobTypeLabel } from "../jobs/jobTypeLabel";

/**
 * The state pills above the job table (UI review 2026-10-18, roadmap 2.4): the old Queue and History tabs as presets
 * of the one table. Each pill is a comma-separated state set the server already accepts; `all` is no state filter.
 * "Other outcome" keeps the sum honest: a job that neither completed nor failed (outcome unknown, rejected, cancelled)
 * is in All and in this pill, never hidden.
 */
export type JobPill = "all" | "active" | "finished" | "failed" | "other";
export const JOB_PILLS: ReadonlyArray<{ readonly id: JobPill; readonly label: string; readonly states: string }> = [
  { id: "all", label: "All", states: "" },
  { id: "active", label: "Queued / Running", states: "REQUESTED,CLAIMED,EXECUTING" },
  { id: "finished", label: "Finished", states: "COMPLETED" },
  { id: "failed", label: "Failed", states: "FAILED" },
  { id: "other", label: "Other outcome", states: "OUTCOME_UNKNOWN,REJECTED,CANCELLED" },
];

/** The pill whose state set is exactly the current filter, if any (the State dropdown can also name a single state). */
export function pillForState(state: string): JobPill | null {
  return JOB_PILLS.find((p) => p.states === state)?.id ?? null;
}

/** A pill's count from the per-state totals; null when the totals have not been read (the pill then shows no number). */
export function pillCount(byState: Readonly<Record<string, number>> | null, pill: JobPill): number | null {
  if (!byState) return null;
  const def = JOB_PILLS.find((p) => p.id === pill)!;
  if (def.id === "all") return Object.values(byState).reduce((n, c) => n + c, 0);
  return def.states.split(",").reduce((n, s) => n + (byState[s] ?? 0), 0);
}

/**
 * The RESULT cell's words: the recorded outcome when it says more than the state, then the reason where there is one,
 * and for a log collection the lines its run stored (`Collected · 1,234 lines`; absent when the run has no chunk row).
 */
export function resultWords(job: Pick<JobEventView, "state" | "outcome" | "terminal_reason" | "lines_stored">): { outcome: string | null; reason: string | null; lines?: string } {
  const lines = typeof job.lines_stored === "number" ? `${job.lines_stored.toLocaleString("en-US")} ${job.lines_stored === 1 ? "line" : "lines"}` : undefined;
  const base = resultOutcomeWords(job);
  return lines === undefined ? base : { ...base, lines };
}

function resultOutcomeWords(job: Pick<JobEventView, "state" | "outcome" | "terminal_reason">): { outcome: string | null; reason: string | null } {
  const recorded = jobOutcomeWord(job.outcome);
  const showOutcome = recorded !== null && !outcomeEchoesState(job.outcome, job.state);
  // The API's terminal_reason repeats the terminal state on a clean run (review §3); a completed job has nothing to
  // explain, so the reason is kept only where it is one -- a failure, rejection or unknown outcome. The exception is a
  // reason that IS the outcome: a readiness check writes READY / NOT_READY / UNKNOWN there and a log collection writes
  // that it collected (post-release review 2026-10-18, 2.4) -- that word goes in the outcome slot, never hidden.
  const completed = job.state === "COMPLETED";
  const fromReason = completed && !showOutcome ? completedReasonOutcome(job.terminal_reason) : null;
  const reason = completed ? null : (job.terminal_reason || null);
  return { outcome: showOutcome ? recorded : fromReason, reason };
}

/**
 * Jobs screen (Product Owner P0, 2026-09-22): the whole history, not the last
 * 50; filters on the server (state, type, device, time window, free text);
 * numbered pages; CSV export of the current filter. Live polling refreshes
 * the page being looked at and never resets the filters.
 */
/** "datetime-local" input value, in display time (GMT+3), for a moment {@code hours} ago. */
function hoursAgoLocal(hours: number): string {
  return isoToDisplayInput(new Date(Date.now() - hours * 3600 * 1000).toISOString());
}

/** Compact list mode (UI review 2026-10-18 §6): 32 px one-line rows, machine values in mono. */
const COMPACT_TABLE = {
  "& th, & td": { height: 32, py: 0.25, px: 1.25, whiteSpace: "nowrap", fontSize: 13, lineHeight: "18px" },
  "& th": { fontWeight: 600 },
} as const;

export function JobLogsPanel({
  initialState = "",
  initialJobType = "",
  initialSinceHours,
  initialText = "",
  emptyTitle,
  emptyBody,
  pillRequest,
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
  /** A pill the owner asks for (a KPI card link, an old `tab=queue` route); `seq` lets the same pill be asked twice. */
  readonly pillRequest?: { readonly pill: JobPill; readonly seq: number } | null;
} = {}) {
  // `?job=<id>` is the addressable job detail (roadmap 3.6): the row is fetched by its id and opened on load.
  const [linkedJob] = useState(() => urlParam("job"));
  const [page, setPage] = useState<{ items: readonly JobEventView[]; total: number; canCancel: boolean } | null>(null);
  const [devices, setDevices] = useState<Record<string, { name: string; vendor: string }>>({});
  const [facets, setFacets] = useState<JobFacetsView>({ states: [], job_types: [] });
  const [byState, setByState] = useState<Readonly<Record<string, number>> | null | "failed">(null);
  const [error, setError] = useState<ApiError | string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [expandedJobId, setExpandedJobId] = useState<string | null>(linkedJob);
  const [cancelling, setCancelling] = useState<string[]>([]);
  const [exportBusy, setExportBusy] = useState(false);

  const [state, setState] = useState(pillRequest ? JOB_PILLS.find((p) => p.id === pillRequest.pill)!.states : initialState);
  const [jobType, setJobType] = useState(initialJobType);
  const [deviceId, setDeviceId] = useState("");
  const [sinceLocal, setSinceLocal] = useState(initialSinceHours ? hoursAgoLocal(initialSinceHours) : "");
  const [untilLocal, setUntilLocal] = useState("");
  const [text, setText] = useState(linkedJob ?? initialText);
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
        setPage({ items: data.items ?? [], total: data.total ?? 0, canCancel: data.can_cancel === true });
        setError(null);
      })
      .catch((err: ApiError) => setError(err));
    // The pill counts come from the same store at the same moment as the page; a failed read says UNKNOWN, never 0.
    getJobStats().then((s) => setByState(s.by_state ?? "failed")).catch(() => setByState("failed"));
  }, [paramsKey]);

  const fetchDeviceNames = useCallback(() => {
    listDevices()
      .then(({ devices }) => {
        // A device with no recorded hostname contributes no entry, so its rows
        // read as unknown rather than carrying an empty name beside the identifier.
        setDevices(Object.fromEntries(
          (devices ?? [])
            .filter((device): device is typeof device & { hostname: string } => Boolean(device.hostname))
            .map((device) => [device.device_id, { name: device.hostname, vendor: device.vendor_hint }]),
        ));
      })
      // The identifier is the record; a name we could not read stays unknown
      // rather than blanking the log or failing the panel.
      .catch(() => setDevices({}));
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

  // The owner asked for a pill after mount (a KPI card, a route): the filter follows; the first request is the initial state.
  useEffect(() => {
    if (!pillRequest || pillRequest.seq === 0) return;
    setState(JOB_PILLS.find((p) => p.id === pillRequest.pill)!.states);
    setPageNumber(1);
  }, [pillRequest?.pill, pillRequest?.seq]);

  // Back / Forward re-open or close the job detail the URL names.
  useUrlParamSync("job", useCallback((value: string | null) => setExpandedJobId(value), []));
  const toggleJob = (id: string) => {
    const next = expandedJobId === id ? null : id;
    setExpandedJobId(next);
    writeUrlParams({ job: next }, next ? "push" : "replace");
  };

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

  const handleCancel = async (id: string) => {
    setCancelling(current => [...current, id]);
    try {
      await cancelJob(id);
      fetchJobs();
    } catch (err) {
      setCancelling(current => current.filter(job => job !== id));
      setError(err as ApiError);
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
  const deviceOptions = Object.entries(devices).sort((a, b) => a[1].name.localeCompare(b[1].name));
  const isDefaultFilter = state === initialState && !jobType && !deviceId && !sinceLocal && !untilLocal && !text.trim();
  const activePill = pillForState(state);
  const counts = byState === "failed" ? null : byState;
  // The column exists only while a row on this page can open a transcript (P1-4B): an empty column says nothing.
  const transcriptColumn = page.items.some((job) => /backup|snapshot|export/i.test(job.job_type) && job.has_transcript);
  const columnCount = 6 + (transcriptColumn ? 1 : 0) + (page.canCancel ? 1 : 0);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Stack role="group" aria-label="Job state" direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        {JOB_PILLS.map((pill) => {
          const selected = activePill === pill.id;
          const count = pillCount(counts, pill.id);
          const countText = byState === "failed" ? "UNKNOWN" : count === null ? null : count.toLocaleString("en-US");
          return <Chip key={pill.id} clickable role="button" aria-pressed={selected} data-pill={pill.id}
            label={countText === null ? pill.label : `${pill.label} · ${countText}`}
            onClick={() => { setState(pill.states); resetToFirstPage(); }}
            sx={{ borderRadius: "8px", height: 28, fontSize: 13, fontWeight: 500,
              bgcolor: selected ? m3.primary : m3.scHigh, color: selected ? m3.onPrimary : m3.onSurface,
              "&:hover": { bgcolor: selected ? m3.primary : m3.scHighest } }} />;
        })}
        {byState !== null && <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>counts: every recorded job</Typography>}
      </Stack>
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
          {state.includes(",") && <option value={state} title={state}>{state.split(",").map(jobStateWord).join(" | ")}</option>}
          {facets.states.map((s) => <option key={s} value={s}>{jobStateWord(s)}</option>)}
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
          {facets.job_types.map((t) => {
            const label = jobTypeLabel(t);
            return <option key={t} value={t}>{label === t ? t : `${label} · ${t}`}</option>;
          })}
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
          {deviceOptions.map(([id, device]) => <option key={id} value={id}>{device.name}</option>)}
        </TextField>
        <TextField
          size="small"
          type="datetime-local"
          label={`From (${DISPLAY_TZ_LABEL})`}
          value={sinceLocal}
          onChange={(e) => { setSinceLocal(e.target.value); resetToFirstPage(); }}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          size="small"
          type="datetime-local"
          label={`To (${DISPLAY_TZ_LABEL})`}
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
        {/* A state, not a call to action: a labelled switch, so it never outranks Refresh and Export CSV. */}
        <FormControlLabel
          control={<Switch size="small" checked={autoRefresh} onChange={(_event, checked) => setAutoRefresh(checked)} />}
          label={<Typography variant="body2">Live polling</Typography>}
          sx={{ m: 0 }}
        />
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
          : activePill === "active" && isDefaultFilter
            ? <StatePanel variant="empty" title="Queue empty" body="0 queued · 0 claimed · 0 running" />
            : <StatePanel variant="empty" title="No jobs match this filter" body="No background job matched the current filter." />
      ) : (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small" aria-label="Job logs" sx={COMPACT_TABLE}>
              <TableHead>
                <TableRow sx={{ bgcolor: "action.hover" }}>
                  <TableCell>Target Device</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell>State</TableCell>
                  <TableCell>Duration</TableCell>
                  <TableCell>Result</TableCell>
                  <TableCell>Submitted At</TableCell>
                  {transcriptColumn && <TableCell>Transcript</TableCell>}
                  {page.canCancel && <TableCell>Actions</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {page.items.map((job) => {
                  const isExpanded = expandedJobId === job.job_id;
                  const vendor = devices[job.target_device_id]?.vendor;
                  const isBackupJob = /backup|snapshot|export/i.test(job.job_type);
                  const result = resultWords(job);
                  return (
                    <Fragment key={job.job_id}>
                    <TableRow
                      hover
                      onClick={() => toggleJob(job.job_id)}
                      sx={{ cursor: "pointer" }}
                      aria-expanded={isExpanded}
                      data-job-id={job.job_id}
                    >
                      <TableCell sx={{ fontWeight: 500 }}>
                        {job.device_name ?? devices[job.target_device_id]?.name ?? "Unknown device"}
                      </TableCell>
                      <TableCell><Presented label={jobTypeLabel(job.job_type, vendor)} raw={job.job_type} /></TableCell>
                      <TableCell>
                        <StatusPill tone={stateTone(job.state)} word={jobStateWord(job.state)} title={job.state} />
                      </TableCell>
                      {/* Duration is neutral -- never red; red is reserved for the state word, shown in the
                          State column above (review §3 "Duration is red on every completed job"). */}
                      <TableCell sx={{ fontFamily: MONO, fontVariantNumeric: "tabular-nums", color: m3.onSurface }}>
                        {formatDuration(job.duration_ms)}
                      </TableCell>
                      {/* The outcome word the record carries (readiness: Ready / Not ready; log collection: Collected / Partial),
                          then the reason where there is one (P1-4B); the raw code is on hover (R8). */}
                      <TableCell
                        data-result
                        sx={{
                          maxWidth: 360,
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: isExpanded ? "normal" : "nowrap",
                          color: job.state === "FAILED" ? "error.main" : "text.secondary",
                        }}
                      >
                        {result.outcome && <Presented label={result.outcome} raw={job.outcome ?? job.terminal_reason} />}
                        {result.outcome && result.reason && " · "}
                        {result.reason && <Presented label={readinessReasonLabel(result.reason)} raw={result.reason} />}
                        {result.lines && (result.outcome || result.reason) && " · "}
                        {result.lines && <span title="Log lines this run stored, from its chunk ledger">{result.lines}</span>}
                        {!result.outcome && !result.reason && !result.lines && "—"}
                      </TableCell>
                      <TableCell sx={{ fontFamily: MONO }}>
                        <Stamp at={job.submitted_at} />
                      </TableCell>
                      {transcriptColumn && <TableCell><JobTranscriptDrawer jobId={job.job_id} hasTranscript={isBackupJob && job.has_transcript} /></TableCell>}
                      {page.canCancel && <TableCell>
                        {["REQUESTED", "CLAIMED", "EXECUTING"].includes(job.state) && <Button size="small"
                          disabled={cancelling.includes(job.job_id)} onClick={event => {
                            event.stopPropagation(); void handleCancel(job.job_id);
                          }}>{cancelling.includes(job.job_id) ? "Cancel requested" : "Cancel"}</Button>}
                      </TableCell>}
                    </TableRow>
                    {isExpanded && <TableRow key={`${job.job_id}-details`} data-job-detail={job.job_id}>
                      <TableCell colSpan={columnCount} sx={{ height: "auto !important", whiteSpace: "normal !important", py: "12px !important" }}>
                        <Typography variant="body2" sx={{ mb: 0.75 }} data-outcome-line>
                          {outcomeLine({ state: job.state, durationMs: job.duration_ms, at: job.finished_at ?? job.submitted_at })}
                          <Box component="span" sx={{ ml: 1, color: m3.onSurfaceVar }}>
                            <Presented label={jobTypeLabel(job.job_type, vendor)} raw={job.job_type} suffix />
                          </Box>
                        </Typography>
                        <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap alignItems="center">
                          {([["Job ID", job.job_id], ["Device ID", job.target_device_id]] as const).map(([label, value]) => (
                            <Stack key={label} direction="row" spacing={0.5} alignItems="center">
                              <Typography variant="caption">{label}: <span style={{ fontFamily: MONO }}>{value}</span></Typography>
                              <IconButton size="small" aria-label={`Copy ${label}`} onClick={(event) => {
                                event.stopPropagation();
                                void navigator.clipboard.writeText(value);
                              }}>⧉</IconButton>
                            </Stack>
                          ))}
                          <Button size="small" sx={{ textTransform: "none" }} onClick={(event) => {
                            event.stopPropagation();
                            void navigator.clipboard.writeText(window.location.href);
                          }}>Copy link to this job</Button>
                        </Stack>
                      </TableCell>
                    </TableRow>}
                    </Fragment>
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
