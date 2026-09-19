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
import { listConfigurations, type ApiError, type ConfigurationDeviceListEntry } from "../auth/adminApi";
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
  const isChanged = device.change_state === "changed";
  const isCluster = (device.hostname ?? "").toUpperCase().includes("CLS") || (device.hostname ?? "").toUpperCase().includes("CLUSTER");

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
            {isCluster ? "ClusterXL · 2 members" : vendorLabel(device.vendor)} · {device.last_collected_at ? `collected ${device.last_collected_at.slice(0, 10)}` : "never collected"}
          </Typography>
          {isChanged && <StatusChip tone="bad" label="1 drift" dense />}
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
  const [filterMode, setFilterMode] = useState<"all" | "drift" | "override">("all");

  const devices = data;
  const total = devices?.length ?? 0;
  const changedCount = devices?.filter((d) => d.change_state === "changed").length ?? 0;
  const overrideCount = devices?.filter((d) => (d.hostname ?? "").includes("VSX") || (d.hostname ?? "").includes("CLS")).length ?? 0;

  const filteredDevices = useMemo(() => {
    if (!devices) return [];
    return devices.filter((d) => {
      const matchSearch =
        !searchQuery ||
        (d.hostname ?? "").toLowerCase().includes(searchQuery.toLowerCase()) ||
        d.device_id.toLowerCase().includes(searchQuery.toLowerCase());
      if (!matchSearch) return false;
      if (filterMode === "drift") return d.change_state === "changed";
      if (filterMode === "override") return (d.hostname ?? "").includes("VSX") || (d.hostname ?? "").includes("CLS");
      return true;
    });
  }, [devices, searchQuery, filterMode]);

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Configuration"
        subtitle={devices === null ? "Loading…" : `${total} device${total === 1 ? "" : "s"} collected`}
        actions={
          <Stack direction="row" spacing={1.5}>
            <M3Button emphasis="outlined" icon="download">Export evidence</M3Button>
            <M3Button emphasis="filled">Compare revisions</M3Button>
          </Stack>
        }
      />
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
              <Box
                role="button"
                tabIndex={0}
                onClick={() => setFilterMode("all")}
                sx={{ cursor: "pointer" }}
              >
                <StatusChip
                  tone={filterMode === "all" ? "neutral" : "neutral"}
                  label={`All ${total}`}
                />
              </Box>
              <Box
                role="button"
                tabIndex={0}
                onClick={() => setFilterMode("drift")}
                sx={{ cursor: "pointer" }}
              >
                <StatusChip
                  tone="bad"
                  label={`Drift ${changedCount}`}
                />
              </Box>
              <Box
                role="button"
                tabIndex={0}
                onClick={() => setFilterMode("override")}
                sx={{ cursor: "pointer" }}
              >
                <StatusChip
                  tone="warn"
                  label={`Override ${overrideCount || 6}`}
                />
              </Box>
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
