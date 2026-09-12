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
import { ALIGNMENT, ALIGNMENT_TOTALS, POSTURE, type AlignmentState } from "./previewData";

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

export function OverviewPreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Stack direction="row" spacing={2} alignItems="baseline" sx={{ mb: 0.5 }}>
        <Typography variant="h2">Operational posture</Typography>
        <Chip size="small" label="run-20260908-0640"
              sx={{ bgcolor: m3.scHigh, color: m3.onSurfaceVar, borderRadius: "8px",
                    height: 26, fontSize: 12, fontFamily: "ui-monospace, monospace" }} />
      </Stack>
      <Typography variant="body2" sx={{ mb: 3 }}>
        42 devices · 3 management planes · evidence collected 06:40–06:58 UTC
      </Typography>

      <Box sx={{ display: "grid", gap: 2, mb: 3,
                 gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))" }}>
        {POSTURE.map((c) => (
          <Card key={c.title} sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5,
                                    boxShadow: "none", display: "flex", flexDirection: "column", gap: 1.5 }}>
            <Typography sx={{ fontSize: 14, fontWeight: 500 }}>{c.title}</Typography>
            <Typography variant="h1">{c.value}</Typography>
            <Chip size="small" label={c.sub}
                  sx={{ alignSelf: "flex-start", bgcolor: TONE[c.tone].bg, color: TONE[c.tone].fg,
                        borderRadius: "8px", height: 26, fontSize: 12, fontWeight: 500 }} />
          </Card>
        ))}
      </Box>

      <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
        <Typography variant="h4" sx={{ mb: 0.5 }}>Configuration alignment</Typography>
        <Typography variant="body2" sx={{ mb: 2 }}>
          48 settings classified · expected from CMA intent, effective from device evidence
        </Typography>
        <Stack direction="row" spacing={1} sx={{ mb: 2, flexWrap: "wrap", gap: 1 }}>
          {ALIGNMENT_TOTALS.map((t) => (
            <Chip key={t.label} size="small" label={`${t.label} ${t.n}`}
                  sx={{ bgcolor: m3.scHigh, color: m3.onSurfaceVar, borderRadius: "8px",
                        height: 26, fontSize: 12, fontWeight: 500 }} />
          ))}
        </Stack>
        <Table size="small">
          <TableHead>
            <TableRow>
              {["Device", "Setting", "Expected", "Effective", "State"].map((h) => (
                <TableCell key={h} sx={{ fontSize: 12, fontWeight: 500, color: m3.onSurfaceVar,
                                         borderColor: m3.outlineVar }}>{h}</TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {ALIGNMENT.map((r) => (
              <TableRow key={`${r.device}-${r.setting}`}>
                <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5, borderColor: m3.outlineVar }}>{r.device}</TableCell>
                <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5, borderColor: m3.outlineVar }}>{r.setting}</TableCell>
                <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5, borderColor: m3.outlineVar }}>{r.expected}</TableCell>
                <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5, borderColor: m3.outlineVar }}>{r.effective}</TableCell>
                <TableCell sx={{ borderColor: m3.outlineVar }}>
                  <Chip size="small" label={r.state}
                        sx={{ bgcolor: TONE[STATE_TONE[r.state]].bg, color: TONE[STATE_TONE[r.state]].fg,
                              borderRadius: "8px", height: 26, fontSize: 12, fontWeight: 500 }} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Card>
    </Box>
  );
}
