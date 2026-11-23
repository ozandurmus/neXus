import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import {
  downloadScriptRunOutput,
  getScriptRun,
  listScriptRuns,
  runScriptNow,
  type ScriptRunSummary,
  type ScriptSummary,
} from "../auth/adminApi";
import { formatBytes } from "../shell/bytes";
import { M3Button } from "../shell/M3Widgets";
import { actorLabel } from "../shell/presenters";
import { StatusPill } from "../shell/StatusPill";
import type { Tone } from "../shell/tone";
import { Timestamp } from "../shell/Timestamp";
import { m3, MONO } from "../theme/m3Theme";
import { LABEL_UPPER, RADIUS, TYPE } from "../theme/tokens";
import { describeApiError, describeRunNotification, formatRunAs } from "./ScriptFormHelpers";

function formatDurationMs(durationMs: number | null | undefined): string {
  if (durationMs === null || durationMs === undefined) return "—";
  if (durationMs < 1000) return `${durationMs} ms`;
  return `${(durationMs / 1000).toFixed(1)} s`;
}

function runStatusPillProps(run: ScriptRunSummary): { readonly tone: Tone; readonly word: string } {
  const outcome = run.outcome?.toUpperCase() ?? null;
  const state = run.state.toUpperCase();
  if (outcome === "SUCCESS" || (state === "COMPLETED" && !outcome)) {
    return { tone: "ok", word: outcome ?? state };
  }
  if (
    outcome === "FAILURE" ||
    outcome === "TIMED_OUT" ||
    state === "FAILED" ||
    state === "REJECTED"
  ) {
    return { tone: "bad", word: outcome ?? state };
  }
  if (state === "OUTCOME_UNKNOWN" || state === "CANCELLED") {
    return { tone: "warn", word: outcome ? `${state} · ${outcome}` : state };
  }
  return { tone: "neutral", word: outcome ? `${state} · ${outcome}` : state };
}

function triggerBrowserTextDownload(content: string, fileName: string): void {
  if (typeof URL.createObjectURL !== "function") return;
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => {
    if (typeof URL.revokeObjectURL === "function") {
      URL.revokeObjectURL(url);
    }
  }, 1000);
}

export function ScriptRunsSection({
  script,
  canRun,
  runnerAvailable,
  canReadOutput,
  isMasked,
  externalRun,
}: {
  readonly script: ScriptSummary;
  readonly canRun: boolean;
  readonly runnerAvailable: boolean;
  readonly canReadOutput: boolean;
  readonly isMasked: boolean;
  readonly externalRun: ScriptRunSummary | null;
}) {
  const [runs, setRuns] = useState<readonly ScriptRunSummary[]>([]);
  const [runsLoading, setRunsLoading] = useState(true);
  const [runsError, setRunsError] = useState<string | null>(null);
  const [runBusy, setRunBusy] = useState(false);
  const [runActionNote, setRunActionNote] = useState<string | null>(null);
  const [runActionError, setRunActionError] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedRunDetail, setSelectedRunDetail] = useState<ScriptRunSummary | null>(null);
  const [runDetailLoading, setRunDetailLoading] = useState(false);
  const [runDetailError, setRunDetailError] = useState<string | null>(null);
  const [downloadBusy, setDownloadBusy] = useState(false);
  const [downloadNote, setDownloadNote] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const loadRuns = useCallback(async () => {
    setRunsLoading(true);
    setRunsError(null);
    try {
      const response = await listScriptRuns(script.script_id);
      setRuns(response.runs);
    } catch (err) {
      setRunsError(describeApiError(err));
    } finally {
      setRunsLoading(false);
    }
  }, [script.script_id]);

  useEffect(() => {
    setSelectedRunId(null);
    setSelectedRunDetail(null);
    setRunActionNote(null);
    setRunActionError(null);
    setDownloadNote(null);
    setDownloadError(null);
    void loadRuns();
  }, [loadRuns]);

  const inspectRun = useCallback(async (runId: string, fallback?: ScriptRunSummary) => {
    setSelectedRunId(runId);
    if (fallback) setSelectedRunDetail(fallback);
    setRunDetailLoading(true);
    setRunDetailError(null);
    setDownloadNote(null);
    setDownloadError(null);
    try {
      const detail = await getScriptRun(runId);
      setSelectedRunDetail(detail);
    } catch (err) {
      setRunDetailError(describeApiError(err));
    } finally {
      setRunDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    if (externalRun && externalRun.script_id === script.script_id) {
      setRunActionNote(`Submitted manual run ${externalRun.run_id} (v${externalRun.version}).`);
      void loadRuns();
      void inspectRun(externalRun.run_id, externalRun);
    }
  }, [externalRun, script.script_id, loadRuns, inspectRun]);

  const canRunNow = !isMasked && canRun && runnerAvailable;

  const handleRunNow = async () => {
    if (!canRunNow || runBusy) return;
    setRunBusy(true);
    setRunActionError(null);
    setRunActionNote(null);
    try {
      const submitted = await runScriptNow(script.script_id);
      setRunActionNote(`Submitted manual run ${submitted.run_id} (v${submitted.version}).`);
      await loadRuns();
      await inspectRun(submitted.run_id, submitted);
    } catch (err) {
      setRunActionError(describeApiError(err));
    } finally {
      setRunBusy(false);
    }
  };

  const handleDownloadOutput = async (run: ScriptRunSummary) => {
    if (!canReadOutput || !run.has_output || downloadBusy) return;
    setDownloadBusy(true);
    setDownloadError(null);
    setDownloadNote(null);
    try {
      const text = await downloadScriptRunOutput(run.run_id);
      const fileName = `script-run-${run.run_id}.log`;
      triggerBrowserTextDownload(text, fileName);
      setDownloadNote(`Downloaded ${fileName} (${formatBytes(new Blob([text]).size)}) — recorded in the server audit log.`);
    } catch (err) {
      setDownloadError(describeApiError(err));
    } finally {
      setDownloadBusy(false);
    }
  };

  return (
    <Box
      data-testid="script-run-history"
      sx={{
        p: 2,
        borderRadius: `${RADIUS.card}px`,
        bgcolor: m3.scLow,
        border: `1px solid ${m3.outlineVar}`,
      }}
    >
      <Stack spacing={1.75}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1.5}>
          <Box>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
                Run history and manual execution
              </Typography>
              <StatusPill
                tone={runnerAvailable ? "ok" : "neutral"}
                word={runnerAvailable ? "Runner online" : "Runner offline"}
                dense
              />
            </Stack>
            <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar, mt: 0.25 }}>
              {canRunNow
                ? `Submits an immediate manual script_run job for v${script.current_version} in an isolated execution pod.`
                : !runnerAvailable
                  ? "Run now is disabled because the script runner broker has no active heartbeat."
                  : "Run now requires Operator role or above (read-only execution status for this account)."}
            </Typography>
          </Box>
          <Stack direction="row" spacing={1} alignItems="center">
            <M3Button emphasis="outlined" disabled={runsLoading} onClick={() => void loadRuns()}>
              Refresh runs
            </M3Button>
            <M3Button
              emphasis="filled"
              disabled={!canRunNow || runBusy}
              onClick={() => void handleRunNow()}
            >
              {runBusy ? "Starting…" : "Run now"}
            </M3Button>
          </Stack>
        </Stack>

        {runActionError ? <Alert severity="error">{runActionError}</Alert> : null}
        {runActionNote ? <Alert severity="success">{runActionNote}</Alert> : null}

        {runsError ? (
          <Alert
            severity="error"
            action={
              <M3Button emphasis="text" onClick={() => void loadRuns()}>
                Retry
              </M3Button>
            }
          >
            Could not load run history: {runsError}
          </Alert>
        ) : runsLoading && runs.length === 0 ? (
          <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>Loading run history…</Typography>
        ) : runs.length === 0 ? (
          <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }} data-testid="script-runs-empty">
            No runs recorded for this script yet.
          </Typography>
        ) : (
          <Box sx={{ overflowX: "auto", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px` }}>
            <Table size="small" aria-label="Script runs table">
              <TableHead>
                <TableRow>
                  <TableCell>Run</TableCell>
                  <TableCell>Version</TableCell>
                  <TableCell>Trigger</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Exit</TableCell>
                  <TableCell>Duration</TableCell>
                  <TableCell>Notified</TableCell>
                  <TableCell>Requested by</TableCell>
                  <TableCell>Requested at</TableCell>
                  <TableCell align="right">Details</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {runs.map((run) => {
                  const pill = runStatusPillProps(run);
                  const notified = describeRunNotification(run);
                  const isSelected = selectedRunId === run.run_id;
                  return (
                    <TableRow key={run.run_id} hover selected={isSelected} data-run-id={run.run_id}>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        <Box
                          component="button"
                          type="button"
                          onClick={() => void inspectRun(run.run_id, run)}
                          sx={{
                            all: "unset",
                            cursor: "pointer",
                            ...TYPE.mono,
                            fontWeight: 600,
                            color: m3.primary,
                            "&:hover": { textDecoration: "underline" },
                            "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 },
                          }}
                        >
                          {run.run_id}
                        </Box>
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>v{run.version}</TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>{run.trigger_kind}</TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        <StatusPill tone={pill.tone} word={pill.word} dense />
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>
                        {run.exit_code !== null && run.exit_code !== undefined ? run.exit_code : "—"}
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>
                        {formatDurationMs(run.duration_ms)}
                      </TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }} data-testid="script-run-notified">
                        <StatusPill tone={notified.tone} word={notified.word} title={notified.title} dense />
                      </TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>{actorLabel(run.requested_by).label}</TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        <Timestamp at={run.requested_at} />
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        <M3Button emphasis="text" onClick={() => void inspectRun(run.run_id, run)}>
                          Inspect run
                        </M3Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </Box>
        )}

        {selectedRunId ? (
          <Card
            data-testid="script-run-detail"
            sx={{
              p: 2,
              bgcolor: m3.scLowest,
              boxShadow: "none",
              border: `1px solid ${m3.outlineVar}`,
              borderRadius: `${RADIUS.card}px`,
            }}
          >
            {runDetailError ? (
              <Alert severity="error">Could not load run {selectedRunId}: {runDetailError}</Alert>
            ) : !selectedRunDetail ? (
              <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>Loading run details…</Typography>
            ) : (
              <Stack spacing={1.5}>
                <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1}>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography sx={{ ...TYPE.section, fontFamily: MONO, color: m3.onSurface }}>
                      Run {selectedRunDetail.run_id}
                    </Typography>
                    <StatusPill
                      tone={runStatusPillProps(selectedRunDetail).tone}
                      word={runStatusPillProps(selectedRunDetail).word}
                      dense
                    />
                    <Typography sx={{ ...TYPE.mono, color: m3.onSurfaceVar }}>
                      v{selectedRunDetail.version} · {selectedRunDetail.trigger_kind}
                    </Typography>
                  </Stack>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <M3Button
                      emphasis="text"
                      disabled={runDetailLoading}
                      onClick={() => void inspectRun(selectedRunDetail.run_id, selectedRunDetail)}
                    >
                      Refresh run
                    </M3Button>
                    <M3Button
                      emphasis="text"
                      onClick={() => {
                        setSelectedRunId(null);
                        setSelectedRunDetail(null);
                      }}
                    >
                      Close run
                    </M3Button>
                  </Stack>
                </Stack>

                <Box
                  sx={{
                    display: "grid",
                    gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0, 1fr))" },
                    gap: 1.25,
                  }}
                >
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Run as</Typography>
                    <Typography sx={{ ...TYPE.mono }}>{formatRunAs(selectedRunDetail.run_as)}</Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Requested by</Typography>
                    <Typography sx={{ ...TYPE.body }}>{actorLabel(selectedRunDetail.requested_by).label}</Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Exit / Duration</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      Exit {selectedRunDetail.exit_code !== null ? selectedRunDetail.exit_code : "—"} ·{" "}
                      {formatDurationMs(selectedRunDetail.duration_ms)}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Captured streams</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      stdout {formatBytes(selectedRunDetail.stdout_bytes)}
                      {selectedRunDetail.stdout_truncated ? " (truncated)" : ""} · stderr{" "}
                      {formatBytes(selectedRunDetail.stderr_bytes)}
                      {selectedRunDetail.stderr_truncated ? " (truncated)" : ""}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Declared egress</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      {selectedRunDetail.egress.length > 0 ? selectedRunDetail.egress.join(", ") : "None"}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Credential refs injected</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      {selectedRunDetail.credential_refs_used.length > 0
                        ? selectedRunDetail.credential_refs_used.join(", ")
                        : "None"}
                    </Typography>
                  </Box>
                </Box>

                {selectedRunDetail.failure_reason ? (
                  <Alert severity="warning" data-testid="script-run-failure-reason">
                    Failure reason: {selectedRunDetail.failure_reason}
                  </Alert>
                ) : null}

                {canReadOutput && !isMasked && selectedRunDetail.output_tail ? (
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar, mb: 0.5 }}>
                      Captured output tail (masked, up to 4 KiB)
                    </Typography>
                    <Box
                      component="pre"
                      data-testid="script-run-output-tail"
                      sx={{
                        m: 0,
                        p: 1.5,
                        maxHeight: 220,
                        overflow: "auto",
                        borderRadius: `${RADIUS.card}px`,
                        bgcolor: m3.scLow,
                        border: `1px solid ${m3.outlineVar}`,
                        fontFamily: MONO,
                        fontSize: TYPE.mono.fontSize,
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                      }}
                    >
                      {selectedRunDetail.output_tail}
                    </Box>
                  </Box>
                ) : null}

                <Box
                  data-testid="script-run-output-download-section"
                  sx={{
                    p: 1.5,
                    borderRadius: `${RADIUS.pill}px`,
                    bgcolor: m3.scLow,
                    border: `1px solid ${m3.outlineVar}`,
                  }}
                >
                  {canReadOutput ? (
                    <Stack spacing={1}>
                      <Stack
                        direction="row"
                        justifyContent="space-between"
                        alignItems="center"
                        flexWrap="wrap"
                        gap={1}
                      >
                        <Typography sx={{ ...TYPE.body, color: m3.onSurface }} data-testid="script-output-audit-notice">
                          <strong>Audited output download:</strong> Full run output downloads require Operator or above and are audited server-side (one audit entry recorded per download before the first byte).
                        </Typography>
                        {selectedRunDetail.has_output ? (
                          <M3Button
                            emphasis="outlined"
                            disabled={downloadBusy}
                            onClick={() => void handleDownloadOutput(selectedRunDetail)}
                          >
                            {downloadBusy ? "Downloading…" : "Download run output"}
                          </M3Button>
                        ) : (
                          <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>
                            No stored output artefact for this run.
                          </Typography>
                        )}
                      </Stack>
                      {downloadError ? <Alert severity="error">{downloadError}</Alert> : null}
                      {downloadNote ? <Alert severity="success">{downloadNote}</Alert> : null}
                    </Stack>
                  ) : (
                    <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }} data-testid="script-output-gated-note">
                      Full output download requires Operator role or above (<code>can_read_output</code>; downloads are audited server-side).
                      {isMasked ? " Output download is never available in masked inspection mode." : ""}
                    </Typography>
                  )}
                </Box>
              </Stack>
            )}
          </Card>
        ) : null}
      </Stack>
    </Box>
  );
}
