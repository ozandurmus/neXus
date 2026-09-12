import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { OPERATIONS_JOBS, OPERATIONS_READINESS } from "./previewData";

const STATUS_TONE: Record<string, { bg: string; fg: string }> = {
  ok: { bg: m3.successContainer, fg: m3.onSuccessContainer },
  warn: { bg: m3.warningContainer, fg: m3.onWarningContainer },
  bad: { bg: m3.errorContainer, fg: m3.onErrorContainer },
  unknown: { bg: m3.scHigh, fg: m3.onSurfaceVar },
};

export function OperationsPreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>Operations</Typography>
      <Typography variant="body2" sx={{ mb: 3 }}>
        What is running against the fleet, and what the fleet is ready for. Readiness is observed; no
        failover action exists in this build.
      </Typography>

      <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1.6fr) minmax(0, 1fr)", gap: 2 }}>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
          <Typography variant="h4" sx={{ mb: 1.5 }}>Recent jobs</Typography>
          <Stack spacing={1}>
            {OPERATIONS_JOBS.map((j, i) => (
              <Box key={`${j.job}-${i}`} sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
                <Typography sx={{ fontSize: 13.5, fontWeight: 500, minWidth: 200 }}>{j.job}</Typography>
                <Typography variant="body2" sx={{ fontFamily: "ui-monospace, monospace" }}>{j.target}</Typography>
                <Chip size="small" label={j.finished} sx={{ ml: "auto", bgcolor: STATUS_TONE[j.status].bg,
                      color: STATUS_TONE[j.status].fg, borderRadius: "8px", height: 24, fontSize: 11 }} />
              </Box>
            ))}
          </Stack>
        </Card>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
          <Typography variant="h4" sx={{ mb: 1.5 }}>HA readiness</Typography>
          <Stack spacing={1}>
            {OPERATIONS_READINESS.map((r) => (
              <Box key={r.cluster} sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 13 }}>{r.cluster}</Typography>
                <Chip size="small" label={r.note} sx={{ bgcolor: STATUS_TONE[r.state].bg,
                      color: STATUS_TONE[r.state].fg, borderRadius: "8px", height: 24, fontSize: 11 }} />
              </Box>
            ))}
          </Stack>
          <Typography variant="body2" sx={{ mt: 1.5 }}>Readiness is observed. No class 2 action exists in this build.</Typography>
        </Card>
      </Box>
    </Box>
  );
}
