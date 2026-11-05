import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { PathResultView } from "../screens/pathanalysis/PathResultView";
import type { PathVerification } from "../screens/pathanalysis/usePathValidation";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { TYPE } from "../theme/tokens";
import { PATH_ALLOWED, PATH_DENIED, PATH_PARTIAL, PATH_TOPOLOGY_PREVIEW, PATH_VALIDATION_COMPLETE } from "./pathPreviewData";

/** A finished verification for the preview: no request leaves the page. */
const VERIFIED: PathVerification = {
  state: { phase: "complete", branchId: "b1", validationId: PATH_VALIDATION_COMPLETE.validation_id, validation: PATH_VALIDATION_COMPLETE, failure: null, polls: 3, lastPolledAt: "2026-11-04T08:06:00Z" },
  start: () => undefined, checkAgain: () => undefined, reset: () => undefined,
};

/** Synthetic: the lattice cases of PATH_ANALYSIS_DESIGN.md §5 on pseudonymised units and documentation addresses only. */
export function PathAnalysisPreview() {
  const now = new Date("2026-11-04T08:30:00Z");
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Path analysis" subtitle="Can A reach B? · synthetic preview of the verdict lattice" />
      <Stack spacing={1}>
        <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Verified on the devices: the route agrees on both units, the Palo Alto NAT match disagrees</Typography>
        <PathResultView result={PATH_ALLOWED} verification={VERIFIED} defaultView="diagram" topology={PATH_TOPOLOGY_PREVIEW} now={now} />
      </Stack>
      <Stack spacing={1}>
        <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Equal-cost branches, an UNKNOWN hop, a conditional hop, an asymmetric return</Typography>
        <PathResultView result={PATH_PARTIAL} defaultView="diagram" topology={PATH_TOPOLOGY_PREVIEW} now={now} />
      </Stack>
      <Stack spacing={1}>
        <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Denied at a named rule</Typography>
        <PathResultView result={PATH_DENIED} topology={PATH_TOPOLOGY_PREVIEW} now={now} />
      </Stack>
    </ScreenRoot>
  );
}
