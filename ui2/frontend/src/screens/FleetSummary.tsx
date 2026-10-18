import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { getOverview, type DeviceSummary, type OverviewView } from "../auth/adminApi";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { m3 } from "../theme/m3Theme";
import { EstateDetails } from "./OverviewScreen";

/**
 * The Devices screen's landing (UI review 2026-10-18, P1-1): where the empty "Select a device or cluster" pane was, the
 * estate inventory summary -- policy installed, hardware models and software versions per vendor, inventory evidence age.
 * It is the Overview's "Estate details" itself ({@link EstateDetails}, same stored-evidence read), so the two screens can not
 * disagree and every slice keeps its link to the filtered device list. {@code countLabel} is the screen's one count sentence.
 */
export function FleetSummary({ countLabel, fleet }: { readonly countLabel: string;
  /** The Devices screen's own device list: the other vendors' version slices are read from it, no second request (1.5). */
  readonly fleet?: readonly DeviceSummary[] }) {
  const overview = useFetchOnMount<OverviewView>(getOverview, (err) => {
    const e = err as { status?: number; message?: string };
    return e?.message ?? `status ${e?.status ?? "unknown"}`;
  });
  return (
    <Stack spacing={2} data-testid="fleet-summary">
      <Box>
        <Typography variant="h4">Fleet summary</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 760, mt: 0.5 }}>
          {countLabel}. Choose one on the left to see its interfaces, routing, cluster members, configuration, backup,
          logs and identity. Interface evidence is read over SSH or HTTPS; values are observed, never written.
        </Typography>
      </Box>
      {overview.data && overview.data.platform && overview.data.nexus
        ? <EstateDetails data={overview.data} onDevicesScreen fleet={fleet} />
        : overview.error
          ? isRestricted(overview.rawError)
            ? <RestrictedPanel area="Estate summary" />
            // A finding about this one panel, not a failed screen: announced as a status, never as an alert.
            : <StatePanel variant="empty" title="Estate summary is not shown" body="The stored-evidence summary did not return data. Devices below are not affected; reload to try again." code={overview.error} />
          : overview.data
            ? <StatePanel variant="empty" title="Estate summary is not shown" body="The stored-evidence summary came back without its platform and neXus figures. Devices below are not affected." />
            : <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Reading the estate summary…</Typography>}
    </Stack>
  );
}
