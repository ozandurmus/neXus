import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { requestBulkInventoryCollect, listDevices, type ApiError, type DeviceSummary } from "../auth/adminApi";
import { enrollmentStateLabel, enrollmentStateTone } from "../shell/deviceCopy";
import { DeviceInventoryPanels } from "./InventoryPanels";

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

function DeviceRow({
  device,
  indented = false,
  selected = false,
  onSelect,
}: {
  readonly device: DeviceSummary;
  readonly indented?: boolean;
  readonly selected?: boolean;
  readonly onSelect?: (device: DeviceSummary) => void;
}) {
  return (
    <Box
      role={onSelect ? "button" : undefined}
      tabIndex={onSelect ? 0 : undefined}
      onClick={onSelect ? () => onSelect(device) : undefined}
      onKeyDown={
        onSelect
          ? (e) => {
              if (e.key === "Enter" || e.key === " ") onSelect(device);
            }
          : undefined
      }
      sx={{
        display: "flex",
        flexDirection: "column",
        gap: 0.25,
        p: 1.25,
        ml: indented ? 3 : 0,
        border: "1px solid",
        borderColor: selected ? m3.primary : "divider",
        borderRadius: 2,
        cursor: onSelect ? "pointer" : undefined,
      }}
    >
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
        <Typography variant="body2" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {device.hostname ?? device.device_id ?? "Unknown"}
        </Typography>
        <StatusChip tone={enrollmentStateTone(device.enrollment_state)} label={enrollmentStateLabel(device.enrollment_state)} dense />
      </Box>
      <Typography variant="caption" color="text.secondary">
        {vendorLabel(device.vendor_hint)} · {device.model ?? "Unknown model"} · {device.software_version ?? "Unknown version"} ·{" "}
        {device.ha_role ?? "No HA role"}
      </Typography>
    </Box>
  );
}

/**
 * Devices sharing a non-null `cluster_member_ref` render nested under one
 * parent grouping row instead of as flat standalone rows -- the one
 * structurally required piece of this screen. A device whose
 * `cluster_member_ref` is null always renders as a normal standalone row.
 */
function DeviceList({
  devices,
  selectedDeviceId,
  onSelect,
}: {
  readonly devices: readonly DeviceSummary[];
  readonly selectedDeviceId: string | null;
  readonly onSelect: (device: DeviceSummary) => void;
}) {
  const [collapsedRefs, setCollapsedRefs] = useState<ReadonlySet<string>>(new Set());

  const groups = new Map<string, DeviceSummary[]>();
  const standalone: DeviceSummary[] = [];
  for (const device of devices) {
    if (device.cluster_member_ref) {
      const members = groups.get(device.cluster_member_ref) ?? [];
      members.push(device);
      groups.set(device.cluster_member_ref, members);
    } else {
      standalone.push(device);
    }
  }

  const toggle = (ref: string) => {
    setCollapsedRefs((prev) => {
      const next = new Set(prev);
      if (next.has(ref)) next.delete(ref);
      else next.add(ref);
      return next;
    });
  };

  return (
    <Stack spacing={1.25}>
      {[...groups.entries()].map(([ref, members]) => {
        const isCollapsed = collapsedRefs.has(ref);
        return (
          <Box key={ref} sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <Box
              role="button"
              tabIndex={0}
              onClick={() => toggle(ref)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") toggle(ref);
              }}
              sx={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                cursor: "pointer",
                bgcolor: m3.scHigh,
                borderRadius: 2,
                px: 1.5,
                py: 1,
              }}
            >
              <Typography variant="body2">Cluster {ref}</Typography>
              <StatusChip tone="mem" label={`${members.length} member${members.length === 1 ? "" : "s"}`} dense />
            </Box>
            {!isCollapsed && (
              <Stack spacing={1}>
                {members.map((member) => (
                  <DeviceRow
                    key={member.device_id}
                    device={member}
                    indented
                    selected={member.device_id === selectedDeviceId}
                    onSelect={onSelect}
                  />
                ))}
              </Stack>
            )}
          </Box>
        );
      })}
      {standalone.map((device) => (
        <DeviceRow
          key={device.device_id}
          device={device}
          selected={device.device_id === selectedDeviceId}
          onSelect={onSelect}
        />
      ))}
    </Stack>
  );
}

/** M3Inventory, backed by a real `GET /devices` fetch. */
export function InventoryScreen() {
  const { data, error, refresh } = useFetchOnMount(
    () => listDevices().then((result) => result.devices ?? []),
    describeApiError,
  );
  const [selectedDevice, setSelectedDevice] = useState<DeviceSummary | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<{ enrolled_devices: number, admitted: number, refused: number } | null>(null);

  const devices = data;
  const total = devices?.length ?? 0;
  const checkPointCount = devices?.filter((d) => d.vendor_hint === "check_point").length ?? 0;
  const paloAltoCount = devices?.filter((d) => d.vendor_hint === "palo_alto").length ?? 0;
  const draftCount = devices?.filter((d) => d.enrollment_state === "DRAFT").length ?? 0;

  const handleBulkCollect = async () => {
    setBulkBusy(true);
    setBulkResult(null);
    try {
      const result = await requestBulkInventoryCollect();
      setBulkResult(result);
    } catch (err) {
      console.error("Bulk collect failed:", err);
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Network inventory"
        subtitle={devices === null ? "Loading…" : `${total} device${total === 1 ? "" : "s"} enrolled`}
        actions={
          <>
            <M3Button emphasis="outlined" icon="download">Export inventory</M3Button>
            <M3Button emphasis="outlined" icon="operations" disabled={bulkBusy} onClick={handleBulkCollect}>
              {bulkBusy ? "Starting..." : "Bulk Collect"}
            </M3Button>
            <M3Button emphasis="filled" icon="plus" href="?screen=administration">Add device</M3Button>
          </>
        }
      />
      {bulkResult && (
        <Box sx={{ px: 3, pb: 2 }}>
          <Typography variant="body2" sx={{ color: m3.primary }}>
            Bulk collect admitted {bulkResult.admitted} of {bulkResult.enrolled_devices} enrolled devices.
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
              Subnet, device, serial or IP
            </Box>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              <StatusChip tone="neutral" label={`All ${total}`} />
              <StatusChip tone="neutral" label={`Check Point ${checkPointCount}`} />
              <StatusChip tone="neutral" label={`Palo Alto ${paloAltoCount}`} />
              <StatusChip tone="neutral" label={`Draft ${draftCount}`} />
            </Stack>
            {error && (
              <EmptyPanel title="Inventory unavailable" body={error}>
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
              <DeviceList
                devices={devices}
                selectedDeviceId={selectedDevice?.device_id ?? null}
                onSelect={setSelectedDevice}
              />
            )}
          </Box>
        }
        detail={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            {selectedDevice ? (
              <DeviceInventoryPanels key={selectedDevice.device_id} device={selectedDevice} />
            ) : (
              <M3Tabs
                ariaLabel="Device detail"
                tabs={[
                  {
                    label: "Interfaces",
                    panel: (
                      <Stack spacing={1.5}>
                        <EmptyPanel
                          title="No interface evidence"
                          body="Enrol a device from Administration to see its interfaces here; none is enrolled
                                yet."
                        />
                        <Typography variant="body2">
                          Interface evidence is read over SSH or HTTPS. Values are observed, never written.
                        </Typography>
                      </Stack>
                    ),
                  },
                  {
                    label: "Routing",
                    panel: (
                      <EmptyPanel
                        title="No routing evidence"
                        body="Routing tables are read directly from an enrolled device; no device has been
                              enrolled or read yet."
                      />
                    ),
                  },
                  {
                    label: "Cluster members",
                    panel: (
                      <EmptyPanel
                        title="No cluster membership evidence"
                        body="Membership needs an identity-verified read from each peer; none has been collected
                              yet."
                      />
                    ),
                  },
                  {
                    label: "Identity & provenance",
                    panel: (
                      <EmptyPanel
                        title="No identity or provenance evidence"
                        body="Identity requires a direct, verified device read; none has occurred yet."
                      />
                    ),
                  },
                ]}
              />
            )}
          </Box>
        }
      />
    </ScreenRoot>
  );
}
