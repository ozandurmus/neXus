import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";
import Tooltip from "@mui/material/Tooltip";
import { m3 } from "../theme/m3Theme";

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

  if (normalized === "EXECUTING") {
    return (
      <Tooltip title={`In progress: ${type ?? "Job"} is running...`} arrow>
        <Box sx={{ display: "inline-flex", alignItems: "center", justifyContent: "center", width: dimension, height: dimension }}>
          <CircularProgress size={size === "small" ? 13 : 16} thickness={5} sx={{ color: m3.primary }} />
        </Box>
      </Tooltip>
    );
  }

  if (normalized === "COMPLETED" || normalized === "SUCCEEDED") {
    return (
      <Tooltip title={`Completed: ${type ?? "Job"} finished successfully`} arrow>
        <Box
          component="span"
          sx={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            width: dimension,
            height: dimension,
            borderRadius: "50%",
            bgcolor: m3.successContainer,
            color: m3.onSuccessContainer,
            fontSize,
            fontWeight: 700,
            lineHeight: 1,
          }}
        >
          ✓
        </Box>
      </Tooltip>
    );
  }

  if (normalized === "CLAIMED" || normalized === "REQUESTED") {
    return (
      <Tooltip title={`Queued in worker: ${type ?? "Job"} waiting to execute`} arrow>
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
