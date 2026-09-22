import { useState, useMemo } from "react";
import Box from "@mui/material/Box";
import InputBase from "@mui/material/InputBase";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listConfigurations, requestBulkConfigurationCollect, type ApiError, type ConfigurationDeviceListEntry } from "../auth/adminApi";
import { DeviceConfigurationPanels } from "./ConfigurationPanels";
import { VendorAvatar } from "./InventoryPanels";

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

function changeStateTone(state: string | null): "ok" | "warn" | "neutral" | "bad" {
  if (state === "changed") return "bad";
  if (state === "unchanged") return "ok";
  return "neutral";
}

function changeStateLabel(state: string | null): string {
  if (state === null) return "Not collected";
  if (state === "first_run") return "First run";
  if (state === "changed") return "Changed";
  return "Aligned";
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
        alignItems: "flex-start",
        gap: 1.25,
        p: 1.25,
        bgcolor: selected ? "#f0f5ff" : m3.scLowest,
        border: "1px solid",
        borderColor: selected ? m3.primary : m3.outlineVar,
        borderRadius: "12px",
        cursor: "pointer",
        transition: "all 0.15s ease-in-out",
        "&:hover": {
          borderColor: m3.primary,
          boxShadow: "0 2px 6px rgba(0,0,0,0.06)",
        },
      }}
    >
      <VendorAvatar vendorHint={device.vendor} hostname={device.hostname} />
      <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {device.hostname ?? device.device_id}
          </Typography>
          <StatusChip tone={changeStateTone(device.change_state)} label={changeStateLabel(device.change_state)} dense />
        </Box>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: 11 }}>
            {vendorLabel(device.vendor)} · {device.last_collected_at ? `collected ${device.last_collected_at.slice(0, 10)}` : "never collected"}
          </Typography>
        </Box>
      </Box>
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
  const [searchQuery, setSearchQuery] = useState("");
  const [filterMode, setFilterMode] = useState<"all" | "changed" | "first_run" | "uncollected">("all");
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<{ enrolled_devices: number; admitted: number; refused: number } | null>(null);

  const handleBulkCollect = async () => {
    setBulkBusy(true);
    setBulkResult(null);
    try {
      const res = await requestBulkConfigurationCollect();
      setBulkResult(res);
      refresh();
    } catch (err) {
      console.error("Bulk configuration collect failed:", err);
    } finally {
      setBulkBusy(false);
    }
  };

  const devices = data;
  const total = devices?.length ?? 0;
  const collectedCount = devices?.filter((d) => d.change_state !== null).length ?? 0;
  const changedCount = devices?.filter((d) => d.change_state === "changed").length ?? 0;
  const firstRunCount = devices?.filter((d) => d.change_state === "first_run").length ?? 0;
  const uncollectedCount = total - collectedCount;

  const filteredDevices = useMemo(() => {
    if (!devices) return [];
    return devices.filter((d) => {
      const matchSearch =
        !searchQuery ||
        (d.hostname ?? "").toLowerCase().includes(searchQuery.toLowerCase()) ||
        d.device_id.toLowerCase().includes(searchQuery.toLowerCase());
      if (!matchSearch) return false;
      if (filterMode === "changed") return d.change_state === "changed";
      if (filterMode === "first_run") return d.change_state === "first_run";
      if (filterMode === "uncollected") return d.change_state === null;
      return true;
    });
  }, [devices, searchQuery, filterMode]);

  const selectedDevice = devices?.find((d) => d.device_id === selectedDeviceId) ?? null;

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Configuration"
        subtitle={devices === null ? "Loading…" : `${collectedCount} device${collectedCount === 1 ? "" : "s"} collected${uncollectedCount > 0 ? ` · ${uncollectedCount} not collected` : ""}`}
        actions={
          <Stack direction="row" spacing={1.5}>
            <M3Button emphasis="outlined" icon="operations" disabled={bulkBusy} onClick={handleBulkCollect}>
              {bulkBusy ? "Starting..." : "Collect All"}
            </M3Button>
          </Stack>
        }
      />
      {bulkResult && (
        <Box sx={{ px: 3, pb: 2 }}>
          <Typography variant="body2" sx={{ color: m3.primary }}>
            Bulk configuration collect admitted {bulkResult.admitted} of {bulkResult.enrolled_devices} enrolled devices.
            {bulkResult.refused > 0 && ` (${bulkResult.refused} refused.)`} View Administration &gt; Job Logs for progress.
          </Typography>
        </Box>
      )}
      <ListDetail
        list={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            <Box sx={{ height: 48, display: "flex", alignItems: "center", gap: 1.5, px: 2,
                       borderRadius: "24px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, fontSize: 14 }}>
              <Icon name="search" size={20} />
              <InputBase
                placeholder="Device, serial or model"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                sx={{ flex: 1, fontSize: 14, color: "inherit" }}
              />
            </Box>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              {([
                ["all", `All ${total}`, "neutral"],
                ["changed", `Changed ${changedCount}`, changedCount > 0 ? "bad" : "neutral"],
                ["first_run", `First run ${firstRunCount}`, "neutral"],
                ["uncollected", `Not collected ${uncollectedCount}`, uncollectedCount > 0 ? "warn" : "neutral"],
              ] as const).map(([mode, label, tone]) => (
                <Box key={mode} role="button" tabIndex={0} onClick={() => setFilterMode(mode)} sx={{ cursor: "pointer", opacity: filterMode === mode ? 1 : 0.7 }}>
                  <StatusChip tone={tone} label={label} />
                </Box>
              ))}
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
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1.25, maxHeight: "calc(100vh - 240px)", overflowY: "auto", pr: 0.5 }}>
                {filteredDevices.map((device) => (
                  <DeviceRow
                    key={device.device_id}
                    device={device}
                    selected={device.device_id === selectedDeviceId}
                    onSelect={(d) => setSelectedDeviceId(d.device_id)}
                  />
                ))}
              </Box>
            )}
          </Box>
        }
        detail={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            {selectedDeviceId ? (
              <DeviceConfigurationPanels key={selectedDeviceId} deviceId={selectedDeviceId} hostname={selectedDevice?.hostname} vendorHint={selectedDevice?.vendor} />
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
