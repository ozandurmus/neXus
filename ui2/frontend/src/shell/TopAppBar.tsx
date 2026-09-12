import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";

/**
 * The canvas's top app bar: product name, a search affordance and a
 * notification glyph. The canvas also shows a run-status chip and an
 * operator initial badge, but both name a specific collection run and a
 * specific signed-in operator -- values this build has neither collected
 * nor authenticated, so showing them here would be fabricated certainty.
 */
export function TopAppBar() {
  return (
    <Box sx={{ height: 64, flex: "none", display: "flex", alignItems: "center", gap: 2, px: 3, pl: 1 }}>
      <Typography variant="h3">SecurityExpert</Typography>
      <Box sx={{ flex: 1, maxWidth: 520, height: 48, display: "flex", alignItems: "center", gap: 1.5,
                 px: 2, borderRadius: "24px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, fontSize: 15 }}>
        <Icon name="search" size={20} />
        Search devices, settings, evidence
      </Box>
      <Box sx={{ ml: "auto", display: "flex", alignItems: "center", color: m3.onSurfaceVar }}>
        <Icon name="bell" size={20} />
      </Box>
    </Box>
  );
}
