import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";

export function BackupPreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>Backups & Recovery</Typography>
      <Typography variant="body2" sx={{ mb: 3 }}>
        3 devices protected · 400 GiB dedicated recovery vault · 30-day retention
      </Typography>

      <Box sx={{ display: "grid", gap: 2, mb: 3, gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))" }}>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, border: `1px solid ${m3.outlineVar}` }}>
          <Typography sx={{ fontSize: 14, fontWeight: 500, color: m3.onSurfaceVar }}>Vault Storage</Typography>
          <Typography variant="h3" sx={{ my: 1, fontWeight: 700, color: m3.primary }}>400 GiB</Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Dedicated persistent volume</Typography>
        </Card>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, border: `1px solid ${m3.outlineVar}` }}>
          <Typography sx={{ fontSize: 14, fontWeight: 500, color: m3.onSurfaceVar }}>Fleet Protection</Typography>
          <Typography variant="h3" sx={{ my: 1, fontWeight: 700, color: m3.success }}>100%</Typography>
          <Typography variant="caption" sx={{ color: m3.success }}>All active firewalls backed up</Typography>
        </Card>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, border: `1px solid ${m3.outlineVar}` }}>
          <Typography sx={{ fontSize: 14, fontWeight: 500, color: m3.onSurfaceVar }}>Retention Policy</Typography>
          <Typography variant="h3" sx={{ my: 1, fontWeight: 700, color: m3.onSurface }}>30 Days</Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Snapshots depth: 2 retained</Typography>
        </Card>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, border: `1px solid ${m3.outlineVar}` }}>
          <Typography sx={{ fontSize: 14, fontWeight: 500, color: m3.onSurfaceVar }}>Deviations</Typography>
          <Typography variant="h3" sx={{ my: 1, fontWeight: 700, color: m3.success }}>0 Active</Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>No major policy shifts</Typography>
        </Card>
      </Box>

      <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, border: `1px solid ${m3.outlineVar}` }}>
        <Typography variant="h4" sx={{ mb: 1.5 }}>Protected Fleet</Typography>
        <Stack spacing={1.5}>
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", p: 1.5, bgcolor: m3.surface, borderRadius: "8px" }}>
            <Box>
              <Typography sx={{ fontWeight: 600 }}>FW-TANGO-04 (PA-5410)</Typography>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>192.0.2.22 · Palo Alto Networks · Today at 02:00 UTC</Typography>
            </Box>
            <Chip size="small" label="UNCHANGED" sx={{ bgcolor: m3.successContainer, color: m3.onSuccessContainer, fontWeight: 700 }} />
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", p: 1.5, bgcolor: m3.surface, borderRadius: "8px" }}>
            <Box>
              <Typography sx={{ fontWeight: 600 }}>FW-BRAVO-02-M1 (Gaia R81.20)</Typography>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>192.0.2.21 · Check Point Gateway · Today at 02:00 UTC</Typography>
            </Box>
            <Chip size="small" label="UNCHANGED" sx={{ bgcolor: m3.successContainer, color: m3.onSuccessContainer, fontWeight: 700 }} />
          </Box>
        </Stack>
      </Card>
    </Box>
  );
}
