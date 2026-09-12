import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

/** M3Inventory with nothing enrolled. */
export function InventoryScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Network inventory"
        subtitle="0 logical views live · nothing collected yet"
        actions={
          <>
            <M3Button emphasis="outlined" icon="download">Export inventory</M3Button>
            <M3Button emphasis="filled" icon="plus" href="?screen=administration">Add device</M3Button>
          </>
        }
      />
      <ListDetail
        list={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            <Box sx={{ height: 48, display: "flex", alignItems: "center", gap: 1.5, px: 2,
                       borderRadius: "24px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, fontSize: 14 }}>
              <Icon name="search" size={20} />
              Subnet, device, serial or IP
            </Box>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              <StatusChip tone="neutral" label="All 0" />
              <StatusChip tone="neutral" label="Check Point 0" />
              <StatusChip tone="neutral" label="Palo Alto 0" />
              <StatusChip tone="neutral" label="Stale 0" />
            </Stack>
            <EmptyPanel title="No devices" body="Nothing is enrolled yet." />
          </Box>
        }
        detail={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            <M3Tabs
              ariaLabel="Device detail"
              tabs={["Interfaces", "Routing", "Cluster members", "Identity & provenance"]}
            />
            <EmptyPanel title="No device selected" body="Enrol a device from Administration to see its interfaces, routing, cluster membership and identity evidence here." />
            <Typography variant="body2">
              Interface and routing evidence is read over SSH or HTTPS. Values are observed, never written.
            </Typography>
          </Box>
        }
      />
    </ScreenRoot>
  );
}
