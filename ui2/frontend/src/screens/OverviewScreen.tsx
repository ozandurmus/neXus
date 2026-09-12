import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";

import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { CapabilityMenu, M3Button, StatusChip } from "../shell/M3Widgets";
import { ALIGNMENT_STATES, ALIGNMENT_STATE_TONE } from "../shell/tone";

/** M3Overview with nothing collected: the database is empty. */
export function OverviewScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Operational posture"
        subtitle="0 devices enrolled · nothing collected yet"
        actions={
          <>
            <M3Button emphasis="outlined" icon="download">Export bundle</M3Button>
            <M3Button emphasis="filled" href="?screen=configuration">Open Configuration</M3Button>
            <CapabilityMenu
              ariaLabel="Overview capabilities"
              items={[
                { label: "Export evidence bundle" },
                { label: "Compare with a revision" },
                { label: "Assign compliance framework" },
                { label: "Collect now", disabledReason: "console only", dividerBefore: true },
              ]}
            />
          </>
        }
      />
      <MetricGrid>
        <MetricCard title="Network inventory" note="none enrolled" />
        <MetricCard title="Configuration" note="no evidence yet" />
        <MetricCard title="Local overrides" note="nothing to compare" />
        <MetricCard title="Effective drift" note="nothing to compare" />
      </MetricGrid>
      <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 2fr) minmax(0, 1fr)", gap: 2, flex: 1, minHeight: 0 }}>
        <EmptyPanel
          title="Configuration alignment"
          body="0 settings classified. Alignment needs both an intent snapshot from the management plane and an
                effective read from the device; neither exists yet."
        >
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            {ALIGNMENT_STATES.map((s) => (
              <StatusChip key={s} tone={ALIGNMENT_STATE_TONE[s]} label={`${s} 0`} />
            ))}
          </Stack>
        </EmptyPanel>
        <Stack spacing={2}>
          <EmptyPanel title="Evidence trust" body="No evidence has been collected, so no trust ratio can be reported yet." />
          <EmptyPanel title="HA readiness" body="No cluster is enrolled yet. Readiness is observed only; no class 2 action exists in this build." />
        </Stack>
      </Box>
    </ScreenRoot>
  );
}
