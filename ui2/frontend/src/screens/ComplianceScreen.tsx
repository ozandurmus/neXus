import Stack from "@mui/material/Stack";

import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";

/** M3Compliance with no framework assigned and no control evidence yet. */
export function ComplianceScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Compliance"
        subtitle="No framework assigned · nothing assessed yet"
        actions={
          <>
            <M3Button emphasis="outlined" icon="download">Export report</M3Button>
            <M3Button emphasis="filled">Assign framework</M3Button>
          </>
        }
      />
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1, px: 0.5 }}>
        <StatusChip tone="neutral" label="CIS Benchmarks · not assigned" />
        <StatusChip tone="neutral" label="ISO/IEC 27001 · not assigned" />
        <StatusChip tone="neutral" label="Internal baseline 2026 · not assigned" />
      </Stack>
      <MetricGrid>
        <MetricCard title="Control coverage" note="no framework assigned" />
        <MetricCard title="Controls covered" note="nothing assessed" />
        <MetricCard title="Subjects assessed" note="no devices enrolled" />
        <MetricCard title="Open findings" note="nothing to find" />
      </MetricGrid>
      <EmptyPanel
        title="No compliance evidence yet"
        body="Assigning a framework and enrolling devices are both required before a control can be assessed."
      />
    </ScreenRoot>
  );
}
