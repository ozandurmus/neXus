import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { CONFIG_DEVICES, CONFIG_SETTINGS, type AlignmentState } from "./previewData";

const TONE: Record<string, { bg: string; fg: string }> = {
  neutral: { bg: m3.scHigh, fg: m3.onSurfaceVar },
  ok: { bg: m3.successContainer, fg: m3.onSuccessContainer },
  warn: { bg: m3.warningContainer, fg: m3.onWarningContainer },
  bad: { bg: m3.errorContainer, fg: m3.onErrorContainer },
};

const STATE_TONE: Record<AlignmentState, keyof typeof TONE> = {
  "Aligned": "ok",
  "Member-specific": "neutral",
  "Local override": "neutral",
  "Difference observed": "warn",
  "Effective drift": "bad",
  "Out of sync": "bad",
};

export function ConfigurationPreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>Configuration</Typography>
      <Typography variant="body2" sx={{ mb: 3 }}>
        Expected intent from the management plane against effective device state · 48 settings classified across 42 devices
      </Typography>
      <Box sx={{ display: "grid", gridTemplateColumns: "312px minmax(0, 1fr)", gap: 2 }}>
        <Stack spacing={0.5}>
          {CONFIG_DEVICES.map((d) => (
            <Card key={d.name} sx={{ bgcolor: m3.scLow, borderRadius: "16px", px: 2, py: 1.25, boxShadow: "none" }}>
              <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 13.5, fontWeight: 500 }}>{d.name}</Typography>
              <Typography variant="body2">{d.detail}</Typography>
              <Chip size="small" label={d.state} sx={{ mt: 0.5, bgcolor: TONE[STATE_TONE[d.state]].bg,
                    color: TONE[STATE_TONE[d.state]].fg, borderRadius: "8px", height: 22, fontSize: 11 }} />
            </Card>
          ))}
        </Stack>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
          <Typography variant="h4" sx={{ mb: 1 }}>fw-ist-core-CLS · Alignment</Typography>
          <Table size="small">
            <TableHead>
              <TableRow>
                {["Setting", "Expected", "Effective", "State"].map((h) => (
                  <TableCell key={h} sx={{ fontSize: 12, fontWeight: 500, color: m3.onSurfaceVar, borderColor: m3.outlineVar }}>{h}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {CONFIG_SETTINGS.map((r) => (
                <TableRow key={r.setting}>
                  <TableCell sx={{ fontSize: 12.5, borderColor: m3.outlineVar }}>{r.setting}</TableCell>
                  <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5, borderColor: m3.outlineVar }}>{r.expected}</TableCell>
                  <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5, borderColor: m3.outlineVar }}>{r.effective}</TableCell>
                  <TableCell sx={{ borderColor: m3.outlineVar }}>
                    <Chip size="small" label={r.state} sx={{ bgcolor: TONE[STATE_TONE[r.state]].bg,
                          color: TONE[STATE_TONE[r.state]].fg, borderRadius: "8px", height: 26, fontSize: 12 }} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      </Box>
    </Box>
  );
}
