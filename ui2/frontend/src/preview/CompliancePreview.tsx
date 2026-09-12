import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { COMPLIANCE_FAMILIES, COMPLIANCE_FINDINGS } from "./previewData";

const SEVERITY_TONE: Record<string, { bg: string; fg: string }> = {
  info: { bg: m3.scHigh, fg: m3.onSurfaceVar },
  warn: { bg: m3.warningContainer, fg: m3.onWarningContainer },
  bad: { bg: m3.errorContainer, fg: m3.onErrorContainer },
};

export function CompliancePreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>Compliance</Typography>
      <Typography variant="body2" sx={{ mb: 3 }}>2 frameworks assigned · 21 subjects assessed</Typography>

      <Box sx={{ display: "grid", gap: 2, mb: 3, gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))" }}>
        {COMPLIANCE_FAMILIES.map((f) => (
          <Card key={f.framework} sx={{ bgcolor: m3.scLowest, boxShadow: m3.e1, borderRadius: "16px", p: 2.5,
                                        display: "flex", flexDirection: "column", gap: 1.25 }}>
            <Typography sx={{ fontSize: 16, fontWeight: 500 }}>{f.framework}</Typography>
            <Typography variant="h1">{f.covered}</Typography>
            <Typography variant="body2">{f.note}</Typography>
          </Card>
        ))}
      </Box>

      <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
        <Typography variant="h4" sx={{ mb: 1.5 }}>Findings</Typography>
        <Stack spacing={1}>
          {COMPLIANCE_FINDINGS.map((f) => (
            <Box key={f.control} sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
              <Chip size="small" label={f.severity} sx={{ bgcolor: SEVERITY_TONE[f.severity].bg,
                    color: SEVERITY_TONE[f.severity].fg, borderRadius: "8px", height: 24, fontSize: 11, minWidth: 48 }} />
              <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>{f.control}</Typography>
              <Typography variant="body2" sx={{ fontFamily: "ui-monospace, monospace" }}>{f.device}</Typography>
              <Typography variant="body2" sx={{ ml: "auto" }}>{f.note}</Typography>
            </Box>
          ))}
        </Stack>
      </Card>
    </Box>
  );
}
