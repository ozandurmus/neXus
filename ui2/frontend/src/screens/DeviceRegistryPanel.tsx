import { useState } from "react";

import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { CapabilityMenu, M3Button, StatusChip, ToggleRow } from "../shell/M3Widgets";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { deleteDevice, listDevices, type ApiError, type DeviceSummary } from "../auth/adminApi";
import { deviceNameLabel, enrollmentStateLabel, enrollmentStateTone } from "../shell/deviceCopy";

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
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const onDelete = async (deviceId: string) => {
    if (!window.confirm("Delete this device and all of its collected records? This cannot be undone.")) return;
    try {
      await deleteDevice(deviceId);
      setDeleteError(null);
      refresh();
    } catch (err) {
      setDeleteError(describeApiError(err));
    }
  };

  const onBulkDelete = async (deviceIds: string[]) => {
    if (deviceIds.length === 0) return;
    if (!window.confirm(`Delete ${deviceIds.length} selected device(s) and all of their collected records? This cannot be undone.`)) return;
    try {
      for (const id of deviceIds) {
        await deleteDevice(id);
      }
      setDeleteError(null);
      refresh();
    } catch (err) {
      setDeleteError(describeApiError(err));
      refresh();
    }
  };

  const total = devices?.length ?? 0;
  const enrolledCount = devices?.filter((d) => d.enrollment_state === "ENROLLED").length ?? 0;
  const degradedCount = devices?.filter((d) => d.enrollment_state === "DEGRADED" || d.enrollment_state === "UNREACHABLE").length ?? 0;
  const draftCount = devices?.filter((d) => d.enrollment_state === "DRAFT").length ?? 0;

  return (
    <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) 320px", gap: 2 }}>
      <DeviceRegistryCard
        devices={devices}
        error={error}
        deleteError={deleteError}
        onDelete={onDelete}
        onBulkDelete={onBulkDelete}
        onRetry={refresh}
        total={total}
      />
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
  deleteError,
  onDelete,
  onBulkDelete,
  onRetry,
  total,
}: {
  readonly devices: DeviceSummary[] | null;
  readonly error: string | null;
  readonly deleteError: string | null;
  readonly onDelete: (deviceId: string) => void;
  readonly onBulkDelete: (deviceIds: string[]) => Promise<void>;
  readonly onRetry: () => void;
  readonly total: number;
}) {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [isDeleting, setIsDeleting] = useState(false);

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

  const isAllSelected = total > 0 && selectedIds.size === total;
  const isSomeSelected = selectedIds.size > 0 && selectedIds.size < total;

  const handleToggleAll = () => {
    if (!devices) return;
    if (selectedIds.size === devices.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(devices.map((d) => d.device_id)));
    }
  };

  const handleToggleRow = (deviceId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(deviceId)) next.delete(deviceId);
      else next.add(deviceId);
      return next;
    });
  };

  const selectAllDraft = () => {
    if (!devices) return;
    const drafts = devices.filter((d) => d.enrollment_state === "DRAFT").map((d) => d.device_id);
    setSelectedIds(new Set(drafts));
  };

  const handleBulkDelete = async () => {
    setIsDeleting(true);
    try {
      await onBulkDelete(Array.from(selectedIds));
      setSelectedIds(new Set());
    } finally {
      setIsDeleting(false);
    }
  };

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
      {deleteError && (
        <Box role="alert" sx={{ mt: 1, px: 1.5, py: 1, border: "1px solid", borderColor: "error.main", borderRadius: 1.5 }}>
          <Typography color="error">Delete failed: {deleteError}</Typography>
        </Box>
      )}
      {selectedIds.size > 0 && (
        <Box
          sx={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            p: 1.25,
            bgcolor: "action.hover",
            borderRadius: 1.5,
            flexWrap: "wrap",
            gap: 1,
            mt: 1,
          }}
        >
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {selectedIds.size} of {total} selected
            </Typography>
            <M3Button emphasis="text" onClick={() => setSelectedIds(new Set())}>
              Clear selection
            </M3Button>
            {devices.some((d) => d.enrollment_state === "DRAFT") && (
              <M3Button emphasis="outlined" onClick={selectAllDraft}>
                Select all draft
              </M3Button>
            )}
          </Stack>
          <Box sx={{ "& button": { bgcolor: "error.main", color: "error.contrastText", "&:hover": { bgcolor: "error.dark" } } }}>
            <M3Button
              emphasis="filled"
              onClick={handleBulkDelete}
              disabled={isDeleting}
            >
              {isDeleting ? "Deleting..." : `Delete selected (${selectedIds.size})`}
            </M3Button>
          </Box>
        </Box>
      )}
      {total > 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, mt: 1 }}>
          <Box sx={{ display: "grid", gridTemplateColumns: "36px 1fr 200px 90px", px: 2, py: 0.5, color: "text.secondary", fontSize: "0.75rem", fontWeight: 600, alignItems: "center" }}>
            <Box>
              <Checkbox
                checked={isAllSelected}
                indeterminate={isSomeSelected}
                onChange={handleToggleAll}
                size="small"
                sx={{ p: 0 }}
                inputProps={{ "aria-label": "Select all devices" }}
              />
            </Box>
            <Box>DEVICE / HOSTNAME</Box>
            <Box sx={{ textAlign: "center" }}>STATUS</Box>
            <Box sx={{ textAlign: "right" }}>ACTIONS</Box>
          </Box>
          <Stack spacing={1} sx={{ maxHeight: "calc(100vh - 340px)", overflowY: "auto", pr: 0.5 }}>
            {devices.map((device) => (
              <Box
                key={device.device_id}
                sx={{
                  display: "grid",
                  gridTemplateColumns: "36px 1fr 200px 90px",
                  alignItems: "center",
                  gap: 1,
                  px: 2,
                  py: 1.25,
                  border: "1px solid",
                  borderColor: selectedIds.has(device.device_id) ? "primary.main" : "divider",
                  borderRadius: 1.5,
                  bgcolor: selectedIds.has(device.device_id) ? "action.selected" : "background.paper",
                  "&:hover": { borderColor: "primary.main" },
                }}
              >
                <Box>
                  <Checkbox
                    checked={selectedIds.has(device.device_id)}
                    onChange={() => handleToggleRow(device.device_id)}
                    size="small"
                    sx={{ p: 0 }}
                    inputProps={{ "aria-label": `Select ${deviceNameLabel(device.hostname)}` }}
                  />
                </Box>
                <Typography variant="body2" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontWeight: 500 }}>
                  {deviceNameLabel(device.hostname)}
                </Typography>
                <Box sx={{ display: "flex", justifyContent: "center" }}>
                  <StatusChip tone={enrollmentStateTone(device.enrollment_state)} label={enrollmentStateLabel(device.enrollment_state)} dense />
                </Box>
                <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
                  <M3Button emphasis="outlined" onClick={() => onDelete(device.device_id)}>Delete</M3Button>
                </Box>
              </Box>
            ))}
          </Stack>
        </Box>
      )}
    </EmptyPanel>
  );
}
