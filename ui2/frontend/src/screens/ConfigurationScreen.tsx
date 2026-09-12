import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

/** M3Configuration with no expected intent and no effective evidence yet. */
export function ConfigurationScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Configuration"
        subtitle="Expected intent from the management plane against effective device state · 0 settings classified"
        actions={
          <>
            <M3Button emphasis="outlined" icon="download">Export evidence</M3Button>
            <M3Button emphasis="filled">Compare revisions</M3Button>
          </>
        }
      />
      <ListDetail
        list={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            <Box sx={{ height: 48, display: "flex", alignItems: "center", gap: 1.5, px: 2,
                       borderRadius: "24px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, fontSize: 14 }}>
              <Icon name="search" size={20} />
              Device, serial or model
            </Box>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              <StatusChip tone="neutral" label="All 0" />
              <StatusChip tone="bad" label="Drift 0" />
              <StatusChip tone="warn" label="Override 0" />
            </Stack>
            <EmptyPanel title="No devices" body="Nothing is enrolled yet." />
          </Box>
        }
        detail={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            <M3Tabs
              ariaLabel="Configuration detail"
              tabs={["Overview", "Current state", "Alignment", "Policy & objects", "History", "Evidence", "Backup"]}
              initial={2}
            />
            <EmptyPanel title="No alignment evidence" body="Alignment needs both an intent snapshot and a device read; neither exists yet." />
          </Box>
        }
      />
    </ScreenRoot>
  );
}
