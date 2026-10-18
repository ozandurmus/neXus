import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";
import Tooltip from "@mui/material/Tooltip";
import { m3 } from "../theme/m3Theme";
import { jobTypeLabel } from "../jobs/jobTypeLabel";

/**
 * The device's latest job as one sentence (`Latest job: Inventory collection completed.`), for the hover text of the
 * Live chip. A completed job used to be a green tick beside every device name; with the identity badge bound to a real
 * comparison that tick read as a verification it never was (post-release review 2026-10-18, §3), so the fact moved
 * into words here and the glyph is gone. Null when there is no job to speak of.
 */
export function latestJobSentence(state: string | null | undefined, type: string | null | undefined): string | null {
  if (!state) return null;
  const normalized = state.toUpperCase();
  const label = type ? jobTypeLabel(type) : "Job";
  if (normalized === "COMPLETED" || normalized === "SUCCEEDED") return `Latest job: ${label} completed.`;
  if (normalized === "EXECUTING") return `Latest job: ${label} is running.`;
  if (normalized === "CLAIMED" || normalized === "REQUESTED") return `Latest job: ${label} is queued.`;
  if (normalized === "FAILED" || normalized === "REJECTED") return `Latest job: ${label} failed.`;
  return null;
}

/**
 * A glyph only for a job that still asks for attention: running, queued or failed. A completed job renders nothing
 * (see {@link latestJobSentence}); the row's or header's own state word (`Live`) carries that place's meaning.
 */
export function JobStatusIndicator({
  state,
  type,
  terminalReason,
  size = "small",
}: {
  readonly state?: string | null;
  readonly type?: string | null;
  readonly terminalReason?: string | null;
  readonly size?: "small" | "medium";
}) {
  if (!state) return null;

  const normalized = state.toUpperCase();
  const dimension = size === "small" ? 18 : 22;
  const fontSize = size === "small" ? 11 : 13;
  const label = type ? jobTypeLabel(type) : "Job";

  if (normalized === "EXECUTING") {
    return (
      <Tooltip title={`In progress: ${label} is running...`} arrow>
        <Box sx={{ display: "inline-flex", alignItems: "center", justifyContent: "center", width: dimension, height: dimension }}>
          <CircularProgress size={size === "small" ? 13 : 16} thickness={5} sx={{ color: m3.primary }} />
        </Box>
      </Tooltip>
    );
  }

  if (normalized === "CLAIMED" || normalized === "REQUESTED") {
    return (
      <Tooltip title={`Queued in worker: ${label} waiting to execute`} arrow>
        <Box
          component="span"
          sx={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            width: dimension,
            height: dimension,
            borderRadius: "50%",
            bgcolor: m3.warningContainer,
            color: m3.onWarningContainer,
            fontSize,
            fontWeight: 700,
            lineHeight: 1,
          }}
        >
          ⏱
        </Box>
      </Tooltip>
    );
  }

  if (normalized === "FAILED" || normalized === "REJECTED") {
    const reasonText = terminalReason ? `: ${terminalReason}` : "";
    return (
      <Tooltip title={`Failed${reasonText}`} arrow>
        <Box
          component="span"
          sx={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            width: dimension,
            height: dimension,
            borderRadius: "50%",
            bgcolor: m3.errorContainer,
            color: m3.onErrorContainer,
            fontSize,
            fontWeight: 700,
            lineHeight: 1,
            cursor: "help",
          }}
        >
          ⚠
        </Box>
      </Tooltip>
    );
  }

  return null;
}
