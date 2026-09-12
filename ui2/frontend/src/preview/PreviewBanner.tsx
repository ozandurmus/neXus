import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";

/**
 * Every preview screen carries this. A mockup that does not say it is a mockup
 * becomes a screenshot someone later reads as a status report.
 */
export function PreviewBanner() {
  return (
    <Box sx={{ bgcolor: m3.warningContainer, color: m3.onWarningContainer,
               px: 4, py: 1.25, display: "flex", gap: 1.5, alignItems: "baseline" }}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: "0.5px" }}>
        DESIGN PREVIEW
      </Typography>
      <Typography variant="body2" sx={{ color: m3.onWarningContainer }}>
        Synthetic values from the design canvas and the repository's test fixtures.
        No device has been contacted and nothing here was collected.
      </Typography>
    </Box>
  );
}
