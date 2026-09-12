import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { AddDeviceDialogTrigger } from "../shell/AddDeviceDialog";
import { CapabilityMenu, M3Button, M3Tabs, StatusChip, ToggleRow } from "../shell/M3Widgets";

/** M3Administration with an empty registry. Enrollment is the one place a device enters the product. */
export function AdministrationScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Administration"
        subtitle="Device registry, collection scope and delivery plan. Enrollment is the one place a device enters the product."
        actions={
          <>
            <M3Button emphasis="outlined">Import from manager</M3Button>
            <AddDeviceDialogTrigger />
          </>
        }
      />
      <M3Tabs
        ariaLabel="Administration sections"
        tabs={["Device management", "Inventory exclusions", "Credentials", "Project plan"]}
      />
      <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) 320px", gap: 2 }}>
        <EmptyPanel title="Device registry · 0 entries" body="No device has been enrolled yet.">
          <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
            <CapabilityMenu
              ariaLabel="Device registry capabilities"
              items={[
                { label: "Export evidence bundle" },
                { label: "Compare with a revision" },
                { label: "Assign compliance framework" },
                { label: "Exclude from inventory" },
                { label: "Collect now", disabledReason: "console only", dividerBefore: true },
              ]}
            />
          </Box>
        </EmptyPanel>
        <Stack spacing={2}>
          <EmptyPanel title="Enrollment" body="Enrolling a device grants read collection only.">
            <Stack spacing={1}>
              <Box sx={{ display: "flex", justifyContent: "space-between" }}>
                <Typography variant="body2">Enrolled</Typography>
                <StatusChip tone="ok" label="0 devices" dense />
              </Box>
              <Box sx={{ display: "flex", justifyContent: "space-between" }}>
                <Typography variant="body2">Degraded or unreachable</Typography>
                <StatusChip tone="warn" label="0 devices" dense />
              </Box>
              <Box sx={{ display: "flex", justifyContent: "space-between" }}>
                <Typography variant="body2">Draft · not collected</Typography>
                <StatusChip tone="neutral" label="0 devices" dense />
              </Box>
            </Stack>
          </EmptyPanel>
          <EmptyPanel title="Collection scope" body="No collection scope is configured yet.">
            <Stack spacing={1.25}>
              <ToggleRow label="Inventory collection" checked={false} />
              <ToggleRow label="Configuration collection" checked={false} />
              <ToggleRow
                label="Backup creation · class 1"
                checked={false}
                helperText="Backup creation is a controlled recovery write. It stays off until a device is inside the pilot allowlist."
              />
            </Stack>
          </EmptyPanel>
        </Stack>
      </Box>
    </ScreenRoot>
  );
}
