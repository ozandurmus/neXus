import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";
import { evidenceIsStale, staleSentence, type FleetFreshness } from "./freshness";
import { relativeAge } from "./time";

/**
 * The stale-evidence banner (UI review 2026-11-05, B1 / Argon R-01): shown on the Overview and on Compliance when fewer
 * than 80 % of the active devices were read in the last 24 h, so figures built on old reads are never mistaken for live
 * posture. Renders nothing when the data is fresh enough, or when the freshness could not be read (`null`): the screen
 * says UNKNOWN in its own place, this banner makes no claim it can not back.
 */
export function StaleEvidenceBanner({ freshness, newestAt, what = "The figures below" }: {
  readonly freshness: FleetFreshness | null;
  /** The newest read in the fleet, for "newest read 20 min ago". */
  readonly newestAt?: string | null;
  /** The subject of the second sentence ("Compliance results", "The figures below"). */
  readonly what?: string;
}) {
  if (!freshness || !evidenceIsStale(freshness)) return null;
  return (
    <Box role="status" aria-label="Data freshness warning" data-stale-evidence-banner
      sx={{ display: "flex", gap: 1.25, alignItems: "flex-start", px: 2, py: 1.25, borderRadius: "10px",
            bgcolor: m3.warningContainer, color: m3.onWarningContainer, border: `1px solid ${m3.warning}` }}>
      <Box sx={{ mt: "2px", flex: "none" }}><Icon name="info" size={18} /></Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 700, color: "inherit" }}>
          Data is out of date: {staleSentence(freshness)}.
        </Typography>
        <Typography sx={{ fontSize: 13, color: "inherit" }}>
          {what} reflect the last stored reads{newestAt ? ` (newest read ${relativeAge(newestAt)})` : ""}, not the devices as they are now.{" "}
          <Link href="?screen=inventory&inventory_age=stale" underline="always" sx={{ color: "inherit", fontWeight: 600 }}>Devices not read in 24 h →</Link>
        </Typography>
      </Box>
    </Box>
  );
}
