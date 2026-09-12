import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { INVENTORY, INVENTORY_FILTERS, type InventoryRow } from "./previewData";

const KIND_COLOUR: Record<InventoryRow["kind"], string> = {
  CP: m3.cp, MDS: m3.cp, VSX: m3.vsx, PAN: m3.pan,
};

const LIVENESS: Record<InventoryRow["liveness"], { bg: string; fg: string }> = {
  "Live": { bg: m3.successContainer, fg: m3.onSuccessContainer },
  "Stale": { bg: m3.warningContainer, fg: m3.onWarningContainer },
  "No live data": { bg: m3.scHigh, fg: m3.onSurfaceVar },
};

export function InventoryPreview() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>Network inventory</Typography>
      <Typography variant="body2" sx={{ mb: 3 }}>
        42 of 46 logical views live · 4 shown from last-known-good · collected 06:40–06:58 UTC
      </Typography>

      <Stack direction="row" spacing={1} sx={{ mb: 2, flexWrap: "wrap", gap: 1 }}>
        {INVENTORY_FILTERS.map((f, i) => (
          <Chip key={f.label} size="small" label={`${f.label} ${f.n}`}
                sx={{ bgcolor: i === 0 ? m3.secondaryContainer : m3.scHigh,
                      color: i === 0 ? m3.onSecondaryContainer : m3.onSurfaceVar,
                      borderRadius: "8px", height: 26, fontSize: 12, fontWeight: 500 }} />
        ))}
      </Stack>

      <Stack spacing={1.25}>
        {INVENTORY.map((r) => (
          <Card key={r.name} sx={{ bgcolor: m3.scLow, borderRadius: "16px", px: 2.5, py: 2,
                                   boxShadow: "none", display: "flex", alignItems: "center", gap: 2 }}>
            <Chip size="small" label={r.kind}
                  sx={{ bgcolor: m3.scHighest, color: KIND_COLOUR[r.kind], borderRadius: "8px",
                        height: 26, minWidth: 46, fontSize: 12, fontWeight: 700 }} />
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 13.5, fontWeight: 500 }}>
                {r.name}
              </Typography>
              <Typography variant="body2">{r.detail}</Typography>
            </Box>
            <Typography variant="body2" sx={{ textAlign: "right", minWidth: 150 }}>{r.note}</Typography>
            <Chip size="small" label={r.liveness}
                  sx={{ bgcolor: LIVENESS[r.liveness].bg, color: LIVENESS[r.liveness].fg,
                        borderRadius: "8px", height: 26, fontSize: 12, fontWeight: 500, minWidth: 96 }} />
          </Card>
        ))}
      </Stack>
    </Box>
  );
}
