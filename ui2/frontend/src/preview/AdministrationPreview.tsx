import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { ADMIN_DEVICES } from "./previewData";

export function AdministrationPreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>Administration</Typography>
      <Typography variant="body2" sx={{ mb: 3 }}>
        Device registry, collection scope and delivery plan. Enrollment is the one place a device enters the product.
      </Typography>

      <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) 320px", gap: 2 }}>
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
          <Typography variant="h4" sx={{ mb: 1.5 }}>Device registry · {ADMIN_DEVICES.length} entries</Typography>
          <Stack spacing={1}>
            {ADMIN_DEVICES.map((d) => (
              <Box key={d.name} sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
                <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 13.5, fontWeight: 500, minWidth: 170 }}>{d.name}</Typography>
                <Typography variant="body2">{d.vendor}</Typography>
                <Typography variant="body2" sx={{ fontFamily: "ui-monospace, monospace" }}>{d.credentialProfile}</Typography>
                <Chip size="small" label={d.status} sx={{ ml: "auto", bgcolor: m3.scHigh, color: m3.onSurfaceVar,
                      borderRadius: "8px", height: 24, fontSize: 11 }} />
              </Box>
            ))}
          </Stack>
        </Card>
        <Stack spacing={2}>
          <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
            <Typography variant="h4" sx={{ mb: 0.5 }}>Enrollment</Typography>
            <Typography variant="body2">Enrolling a device grants read collection only.</Typography>
          </Card>
          <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none" }}>
            <Typography variant="h4" sx={{ mb: 0.5 }}>Collection scope</Typography>
            <Typography variant="body2">Backup creation stays off until a device enters the pilot allowlist.</Typography>
          </Card>
        </Stack>
      </Box>
    </Box>
  );
}
