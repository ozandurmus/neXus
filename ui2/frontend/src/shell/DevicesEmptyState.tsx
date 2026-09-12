import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";

const cardSx = {
  bgcolor: m3.scLow,
  borderRadius: "16px",
  p: 2.5,
  boxShadow: "none",
  display: "flex",
  flexDirection: "column",
  gap: 1.75,
} as const;

/**
 * The Devices destination with nothing in it.
 *
 * The counts are read from the shell's own empty state, not invented: this
 * build has no device, no job and no collected evidence, and the screen says
 * so rather than showing a plausible-looking dashboard. `AGENTS.md` forbids
 * fabricated certainty, and a seeded demo row in a product's first screen is
 * exactly that.
 */
export function DevicesEmptyState() {
  return (
    <Box sx={{ p: 4, flex: 1, minWidth: 0 }}>
      <Stack direction="row" alignItems="baseline" spacing={2} sx={{ mb: 3 }}>
        <Typography variant="h2">Devices</Typography>
        <Chip
          label="0 enrolled"
          size="small"
          sx={{ bgcolor: m3.scHigh, color: m3.onSurfaceVar, borderRadius: "8px",
                height: 26, fontSize: 12, fontWeight: 500, letterSpacing: "0.3px" }}
        />
      </Stack>

      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))", mb: 3 }}>
        {[
          { k: "Devices", v: "0", note: "none enrolled" },
          { k: "Collections", v: "0", note: "nothing collected" },
          { k: "Open findings", v: "0", note: "no evidence yet" },
        ].map((c) => (
          <Card key={c.k} sx={cardSx}>
            <Typography sx={{ fontSize: 14, fontWeight: 500, letterSpacing: "0.1px" }}>{c.k}</Typography>
            <Typography variant="h1" sx={{ color: m3.onSurface }}>{c.v}</Typography>
            <Typography variant="body2">{c.note}</Typography>
          </Card>
        ))}
      </Box>

      <Card sx={{ ...cardSx, bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
        <Typography variant="h4">No devices yet</Typography>
        <Typography variant="body2" sx={{ maxWidth: 640 }}>
          The database is empty and nothing has been collected. This build is the shell:
          the service boots, connects to its database and applies its migrations. Adding a
          device, discovery and collection are the next steps, and no device is contacted
          until the Product Owner's collection direction is given.
        </Typography>
        <Stack direction="row" spacing={1}>
          {[
            { label: "Check Point", colour: m3.cp },
            { label: "VSX", colour: m3.vsx },
            { label: "Palo Alto", colour: m3.pan },
          ].map((v) => (
            <Chip key={v.label} label={v.label} size="small"
                  sx={{ bgcolor: m3.scHigh, color: v.colour, borderRadius: "8px",
                        height: 26, fontSize: 12, fontWeight: 500 }} />
          ))}
        </Stack>
      </Card>
    </Box>
  );
}
