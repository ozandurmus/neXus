import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { PathResultView } from "../screens/pathanalysis/PathResultView";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { TYPE } from "../theme/tokens";
import { PATH_DENIED, PATH_PARTIAL } from "./pathPreviewData";

/** Synthetic: the lattice cases of PATH_ANALYSIS_DESIGN.md §5 on pseudonymised units and documentation addresses only. */
export function PathAnalysisPreview() {
  const now = new Date("2026-11-04T08:30:00Z");
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Path analysis" subtitle="Can A reach B? · synthetic preview of the verdict lattice" />
      <Stack spacing={1}>
        <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Equal-cost branches, an UNKNOWN hop, a conditional hop, an asymmetric return</Typography>
        <PathResultView result={PATH_PARTIAL} now={now} />
      </Stack>
      <Stack spacing={1}>
        <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Denied at a named rule</Typography>
        <PathResultView result={PATH_DENIED} now={now} />
      </Stack>
    </ScreenRoot>
  );
}
