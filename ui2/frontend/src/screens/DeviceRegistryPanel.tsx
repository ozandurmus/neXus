import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { CapabilityMenu, M3Button, StatusChip, ToggleRow } from "../shell/M3Widgets";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listDevices, type ApiError, type DeviceSummary } from "../auth/adminApi";
import { enrollmentStateLabel, enrollmentStateTone } from "../shell/deviceCopy";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

/**
 * The Administration screen's "Device management" tab, backed by a real
 * `GET /devices` fetch. Lighter than `InventoryScreen`'s own listing --
 * a count plus a simple row per device, no cluster grouping -- since the
 * full inventory view is the canonical place to browse the registry.
 */
export function DeviceManagementPane() {
  const { data: devices, error, refresh } = useFetchOnMount(
    () => listDevices().then((result) => result.devices ?? []),
    describeApiError,
  );

  const total = devices?.length ?? 0;
  const enrolledCount = devices?.filter((d) => d.enrollment_state === "ENROLLED").length ?? 0;
  const degradedCount = devices?.filter((d) => d.enrollment_state === "DEGRADED" || d.enrollment_state === "UNREACHABLE").length ?? 0;
  const draftCount = devices?.filter((d) => d.enrollment_state === "DRAFT").length ?? 0;

  return (
    <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) 320px", gap: 2 }}>
      <DeviceRegistryCard devices={devices} error={error} onRetry={refresh} total={total} />
      <Stack spacing={2}>
        <EmptyPanel title="Enrollment" body="Enrolling a device grants read collection only.">
          <Stack spacing={1}>
            <Box sx={{ display: "flex", justifyContent: "space-between" }}>
              <Typography variant="body2">Enrolled</Typography>
              <StatusChip tone="ok" label={`${enrolledCount} device${enrolledCount === 1 ? "" : "s"}`} dense />
            </Box>
            <Box sx={{ display: "flex", justifyContent: "space-between" }}>
              <Typography variant="body2">Degraded or unreachable</Typography>
              <StatusChip tone="warn" label={`${degradedCount} device${degradedCount === 1 ? "" : "s"}`} dense />
            </Box>
            <Box sx={{ display: "flex", justifyContent: "space-between" }}>
              <Typography variant="body2">Draft · not collected</Typography>
              <StatusChip tone="neutral" label={`${draftCount} device${draftCount === 1 ? "" : "s"}`} dense />
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
  );
}

function DeviceRegistryCard({
  devices,
  error,
  onRetry,
  total,
}: {
  readonly devices: DeviceSummary[] | null;
  readonly error: string | null;
  readonly onRetry: () => void;
  readonly total: number;
}) {
  if (error) {
    return (
      <EmptyPanel title="Device registry unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={onRetry}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  if (devices === null) {
    return <EmptyPanel title="Device registry" body="Loading…" />;
  }

  return (
    <EmptyPanel
      title={`Device registry · ${total} ${total === 1 ? "entry" : "entries"}`}
      body={total === 0 ? "No device has been enrolled yet." : `${total} device${total === 1 ? "" : "s"} in the registry.`}
    >
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
      {total > 0 && (
        <Stack spacing={1}>
          {devices.map((device) => (
            <Box
              key={device.device_id}
              sx={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                px: 1.5,
                py: 1,
                border: "1px solid",
                borderColor: "divider",
                borderRadius: 1.5,
              }}
            >
              <Typography variant="body2" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {device.hostname ?? device.device_id}
              </Typography>
              <StatusChip tone={enrollmentStateTone(device.enrollment_state)} label={enrollmentStateLabel(device.enrollment_state)} dense />
            </Box>
          ))}
        </Stack>
      )}
    </EmptyPanel>
  );
}
