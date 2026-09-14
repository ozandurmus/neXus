import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listConfigurations, type ApiError, type ConfigurationDeviceListEntry } from "../auth/adminApi";
import { DeviceConfigurationPanels } from "./ConfigurationPanels";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

const VENDOR_LABEL: Record<string, string> = {
  check_point: "Check Point",
  palo_alto: "Palo Alto",
};

function vendorLabel(vendorHint: string): string {
  return VENDOR_LABEL[vendorHint] ?? vendorHint;
}

function changeStateTone(state: string | null): "ok" | "warn" | "neutral" {
  if (state === "changed") return "warn";
  if (state === "unchanged") return "ok";
  return "neutral";
}

function changeStateLabel(state: string | null): string {
  if (state === null) return "Not collected";
  if (state === "first_run") return "First run";
  if (state === "changed") return "Changed";
  return "Unchanged";
}

function DeviceRow({
  device,
  selected,
  onSelect,
}: {
  readonly device: ConfigurationDeviceListEntry;
  readonly selected: boolean;
  readonly onSelect: (device: ConfigurationDeviceListEntry) => void;
}) {
  return (
    <Box
      role="button"
      tabIndex={0}
      onClick={() => onSelect(device)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") onSelect(device);
      }}
      sx={{
        display: "flex",
        flexDirection: "column",
        gap: 0.25,
        p: 1.25,
        border: "1px solid",
        borderColor: selected ? m3.primary : "divider",
        borderRadius: 2,
        cursor: "pointer",
      }}
    >
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
        <Typography variant="body2" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {device.hostname ?? device.device_id}
        </Typography>
        <StatusChip tone={changeStateTone(device.change_state)} label={changeStateLabel(device.change_state)} dense />
      </Box>
      <Typography variant="caption" color="text.secondary">
        {vendorLabel(device.vendor)} · {device.last_collected_at ? `collected ${device.last_collected_at}` : "never collected"}
      </Typography>
    </Box>
  );
}

/** M3Configuration, backed by a real `GET /configuration` fetch (movement NXS-LOCAL-0165). */
export function ConfigurationScreen() {
  const { data, error, refresh } = useFetchOnMount(
    () => listConfigurations().then((result) => result.devices ?? []),
    describeApiError,
  );
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);

  const devices = data;
  const total = devices?.length ?? 0;
  const changedCount = devices?.filter((d) => d.change_state === "changed").length ?? 0;

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Configuration"
        subtitle={devices === null ? "Loading…" : `${total} device${total === 1 ? "" : "s"} collected`}
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
              <StatusChip tone="neutral" label={`All ${total}`} />
              <StatusChip tone="warn" label={`Changed ${changedCount}`} />
            </Stack>
            {error && (
              <EmptyPanel title="Configuration unavailable" body={error}>
                <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
                  <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
                </Box>
              </EmptyPanel>
            )}
            {!error && devices === null && <EmptyPanel title="Devices" body="Loading…" />}
            {!error && devices !== null && devices.length === 0 && (
              <EmptyPanel title="No devices" body="Nothing is enrolled yet." />
            )}
            {!error && devices !== null && devices.length > 0 && (
              <Stack spacing={1.25}>
                {devices.map((device) => (
                  <DeviceRow
                    key={device.device_id}
                    device={device}
                    selected={device.device_id === selectedDeviceId}
                    onSelect={(d) => setSelectedDeviceId(d.device_id)}
                  />
                ))}
              </Stack>
            )}
          </Box>
        }
        detail={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            {selectedDeviceId ? (
              <DeviceConfigurationPanels key={selectedDeviceId} deviceId={selectedDeviceId} />
            ) : (
              <EmptyPanel
                title="No device selected"
                body="Select a device from the list to see its configuration index, sanitized text and overrides."
              />
            )}
          </Box>
        }
      />
    </ScreenRoot>
  );
}
