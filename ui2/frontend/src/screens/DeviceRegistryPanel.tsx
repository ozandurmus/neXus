import { useState } from "react";

import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { CapabilityMenu, M3Button, StatusChip } from "../shell/M3Widgets";
import { ChangeCredentialsDialog } from "../shell/ChangeCredentialsDialog";
import { RestrictedPanel, StatePanel, Ts, VendorBadge, isRestricted } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { deleteDevice, listDevices, type ApiError, type BackupDisposition, type DeviceSummary } from "../auth/adminApi";
import { deviceNameLabel, enrollmentStateLabel, enrollmentStateTone, missingHostnameReason } from "../shell/deviceCopy";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

function requiredBackupDisposition(err: unknown): number | null {
  const apiErr = err as Partial<ApiError>;
  const count = apiErr.body?.backup_artefact_count;
  return apiErr.status === 409 && apiErr.body?.error === "BACKUP_DISPOSITION_REQUIRED" && typeof count === "number"
    ? count
    : null;
}

/** The device/hostname cell: the plain name, or "UNKNOWN hostname" with a chip naming why (review §3). */
function HostnameCell({ device }: { readonly device: DeviceSummary }) {
  const hostname = device.hostname?.trim();
  if (hostname) {
    return (
      <Typography variant="body2" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontWeight: 500 }}>
        {hostname}
      </Typography>
    );
  }
  const reason = missingHostnameReason(device.enrollment_state, Boolean(device.inventory_collected_at || device.latest_job_state));
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, minWidth: 0 }}>
      <Typography variant="body2" sx={{ color: m3.neutralInk, fontWeight: 600, whiteSpace: "nowrap" }}>UNKNOWN hostname</Typography>
      <StatusChip tone="neutral" label={reason} dense />
    </Box>
  );
}

/**
 * The Administration screen's "Device management" tab, backed by a real
 * `GET /devices` fetch. Lighter than `InventoryScreen`'s own listing --
 * a count plus a simple row per device, no cluster grouping -- since the
 * full inventory view is the canonical place to browse the registry.
 */
export function DeviceManagementPane() {
  const { data: devices, error, rawError, refresh } = useFetchOnMount(
    () => listDevices().then((result) => result.devices ?? []),
    describeApiError,
  );
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<{ deviceId: string; artefactCount: number } | null>(null);

  const onDelete = async (deviceId: string) => {
    try {
      await deleteDevice(deviceId);
      setDeleteError(null);
      refresh();
    } catch (err) {
      const artefactCount = requiredBackupDisposition(err);
      if (artefactCount !== null) {
        setPendingDelete({ deviceId, artefactCount });
        return;
      }
      setDeleteError(describeApiError(err));
    }
  };

  const onBulkDelete = async (deviceIds: string[]): Promise<boolean> => {
    if (deviceIds.length === 0) return false;
    try {
      for (const id of deviceIds) {
        try {
          await deleteDevice(id);
        } catch (err) {
          const artefactCount = requiredBackupDisposition(err);
          if (artefactCount !== null) {
            setPendingDelete({ deviceId: id, artefactCount });
            refresh();
            return false;
          }
          throw err;
        }
      }
      setDeleteError(null);
      refresh();
      return true;
    } catch (err) {
      setDeleteError(describeApiError(err));
      refresh();
      return false;
    }
  };

  const confirmBackupDisposition = async (disposition: BackupDisposition) => {
    if (!pendingDelete) return;
    try {
      await deleteDevice(pendingDelete.deviceId, disposition);
      setPendingDelete(null);
      setDeleteError(null);
      refresh();
    } catch (err) {
      setDeleteError(describeApiError(err));
    }
  };

  if (error && devices === null) {
    return isRestricted(rawError)
      ? <RestrictedPanel area="Device registry" />
      : <StatePanel variant="error" title="Device registry unavailable" body="The registry could not be read." code={error}
          action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />;
  }

  const total = devices?.length ?? 0;
  const enrolledCount = devices?.filter((d) => d.enrollment_state === "ENROLLED").length ?? 0;
  // Enrolled devices whose most recent collection job failed -- the evidence the store has for "degraded". Note
  // this denominator is narrower than the Devices screen's "Failed N of 105" chip, which counts every device
  // (drafts included) with `latest_job_state === "FAILED""; here only an already-ENROLLED device counts, plus a
  // device the store itself marked DEGRADED/UNREACHABLE. The two figures answer different questions and can
  // legitimately differ -- see the final report for the exact predicates.
  const degradedCount = devices?.filter((d) => d.enrollment_state === "DEGRADED" || d.enrollment_state === "UNREACHABLE"
    || (d.enrollment_state === "ENROLLED" && d.latest_job_state === "FAILED")).length ?? 0;
  const backupTargetCount = devices?.filter((d) => d.backup_target).length ?? 0;
  const draftCount = devices?.filter((d) => d.enrollment_state === "DRAFT").length ?? 0;

  return (
    <>
      <Dialog open={pendingDelete !== null} onClose={() => setPendingDelete(null)} aria-labelledby="backup-disposition-title">
        <DialogTitle id="backup-disposition-title">Backup artefacts found</DialogTitle>
        <DialogContent>
          <Typography>
            This device has {pendingDelete?.artefactCount ?? 0} backup artefact{pendingDelete?.artefactCount === 1 ? "" : "s"}.
            Choose whether to keep or remove them before deleting the device.
          </Typography>
        </DialogContent>
        <DialogActions>
          <M3Button emphasis="text" onClick={() => setPendingDelete(null)}>Cancel</M3Button>
          <M3Button emphasis="outlined" onClick={() => confirmBackupDisposition("KEEP")}>Keep artefacts</M3Button>
          <M3Button emphasis="filled" onClick={() => confirmBackupDisposition("REMOVE")}>Remove artefacts</M3Button>
        </DialogActions>
      </Dialog>
      <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) 320px", gap: 2 }}>
      <DeviceRegistryCard
        devices={devices}
        deleteError={deleteError}
        onDelete={onDelete}
        onBulkDelete={onBulkDelete}
        onChanged={refresh}
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
              <Typography variant="body2">Last collection failed (enrolled)</Typography>
              <StatusChip tone={degradedCount === 0 ? "neutral" : "warn"} label={`${degradedCount} device${degradedCount === 1 ? "" : "s"}`} dense />
            </Box>
            <Box sx={{ display: "flex", justifyContent: "space-between" }}>
              <Typography variant="body2">Draft · not collected</Typography>
              <StatusChip tone="neutral" label={`${draftCount} device${draftCount === 1 ? "" : "s"}`} dense />
            </Box>
          </Stack>
        </EmptyPanel>
        <EmptyPanel
          title="Collection scope"
          body={`Inventory and configuration are read from every enrolled device (Bulk Collect, Collect All). Backups run only for the ${backupTargetCount} device${backupTargetCount === 1 ? "" : "s"} switched on as backup targets on the Backups screen.`}
        />
      </Stack>
      </Box>
    </>
  );
}

/** Confirmation dialog for both the row menu's single Delete and the bulk bar's Delete -- names the device(s). */
function ConfirmDeleteDialog({
  names,
  busy,
  onCancel,
  onConfirm,
}: {
  readonly names: readonly string[];
  readonly busy: boolean;
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
}) {
  const shown = names.slice(0, 5);
  const more = names.length - shown.length;
  return (
    <Dialog open onClose={onCancel} aria-labelledby="confirm-delete-title">
      <DialogTitle id="confirm-delete-title">
        Delete {names.length === 1 ? "this device" : `${names.length} devices`}?
      </DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 1 }}>
          {shown.join(", ")}{more > 0 ? `, and ${more} more` : ""}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          This deletes the device{names.length === 1 ? "" : "s"} and all of its collected records. This cannot be undone.
        </Typography>
      </DialogContent>
      <DialogActions>
        <M3Button emphasis="text" onClick={onCancel} disabled={busy}>Cancel</M3Button>
        <M3Button emphasis="filled" onClick={onConfirm} disabled={busy}>{busy ? "Deleting…" : "Delete"}</M3Button>
      </DialogActions>
    </Dialog>
  );
}

function DeviceRegistryCard({
  devices,
  deleteError,
  onDelete,
  onBulkDelete,
  onChanged,
  total,
}: {
  readonly devices: DeviceSummary[] | null;
  readonly deleteError: string | null;
  readonly onDelete: (deviceId: string) => void;
  readonly onBulkDelete: (deviceIds: string[]) => Promise<boolean>;
  readonly onChanged: () => void;
  readonly total: number;
}) {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [isDeleting, setIsDeleting] = useState(false);
  // Ids pending a named confirmation dialog -- either one row's Delete (from its overflow menu) or the bulk
  // bar's Delete for the current selection. The capability and its API call are unchanged; only the click a
  // stray tap can no longer trigger moved off the row (review "shows a filled Delete button on every row").
  const [confirmIds, setConfirmIds] = useState<string[] | null>(null);
  const [credentialsFor, setCredentialsFor] = useState<DeviceSummary | null>(null);

  if (devices === null) {
    return <StatePanel variant="empty" title="Device registry" body="Loading…" />;
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

  const confirmNames = (confirmIds ?? []).map((id) => deviceNameLabel(devices.find((d) => d.device_id === id)?.hostname));

  const handleConfirmedDelete = async () => {
    if (!confirmIds) return;
    setIsDeleting(true);
    try {
      if (confirmIds.length === 1) {
        onDelete(confirmIds[0]);
        setConfirmIds(null);
      } else if (await onBulkDelete(confirmIds)) {
        setSelectedIds(new Set());
        setConfirmIds(null);
      }
    } finally {
      setIsDeleting(false);
    }
  };

  return (
    <EmptyPanel
      title={`Device registry · ${total} ${total === 1 ? "entry" : "entries"}`}
      body={total === 0 ? "No device has been enrolled yet." : `${total} device${total === 1 ? "" : "s"} in the registry.`}
    >
      {credentialsFor && (
        <ChangeCredentialsDialog device={credentialsFor} onClose={() => setCredentialsFor(null)} onChanged={onChanged} />
      )}
      {confirmIds && (
        <ConfirmDeleteDialog
          names={confirmNames}
          busy={isDeleting}
          onCancel={() => setConfirmIds(null)}
          onConfirm={() => void handleConfirmedDelete()}
        />
      )}
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
          <M3Button emphasis="tonal" onClick={() => setConfirmIds(Array.from(selectedIds))}>
            Delete selected ({selectedIds.size})
          </M3Button>
        </Box>
      )}
      {total > 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, mt: 1 }}>
          <Box sx={{ display: "grid", gridTemplateColumns: "36px 64px minmax(0, 1fr) 140px 230px 40px", px: 2, py: 0.5, color: "text.secondary", fontSize: "0.75rem", fontWeight: 600, alignItems: "center" }}>
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
            <Box>VENDOR</Box>
            <Box>DEVICE / HOSTNAME</Box>
            <Box>ENROLMENT STATE</Box>
            <Box>LAST COLLECTION</Box>
            <Box sx={{ textAlign: "right" }}>&nbsp;</Box>
          </Box>
          <Stack spacing={1} sx={{ maxHeight: "calc(100vh - 340px)", overflowY: "auto", pr: 0.5 }}>
            {devices.map((device) => (
              <Box
                key={device.device_id}
                sx={{
                  display: "grid",
                  gridTemplateColumns: "36px 64px minmax(0, 1fr) 140px 230px 40px",
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
                <VendorBadge vendor={device.vendor_hint} size={24} />
                <HostnameCell device={device} />
                <Box>
                  <StatusChip tone={enrollmentStateTone(device.enrollment_state)} label={enrollmentStateLabel(device.enrollment_state)} dense />
                </Box>
                <Ts at={device.inventory_collected_at} relative />
                <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
                  <CapabilityMenu
                    ariaLabel={`${deviceNameLabel(device.hostname)} actions`}
                    items={[
                      { label: "Change credentials…", onSelect: () => setCredentialsFor(device) },
                      { label: "Delete", destructive: true, dividerBefore: true, onSelect: () => setConfirmIds([device.device_id]) },
                    ]}
                  />
                </Box>
              </Box>
            ))}
          </Stack>
        </Box>
      )}
    </EmptyPanel>
  );
}

/**
 * Administration › Registry › Inventory exclusions. There is no exclusion capability or store yet in this
 * build -- this stays an empty state -- but its copy read "no device has been excluded yet, because no device
 * has been enrolled yet" even once the registry held enrolled devices (review §3 copy bug). The second clause
 * is only ever true when the registry is actually empty; this component reads the real device count (the same
 * `GET /devices` the registry tab uses) so the copy states the reason that is actually true.
 */
export function InventoryExclusionsPanel() {
  const { data: devices, error, rawError, refresh } = useFetchOnMount(
    () => listDevices().then((result) => result.devices ?? []),
    describeApiError,
  );

  if (error && devices === null) {
    return isRestricted(rawError)
      ? <RestrictedPanel area="Inventory exclusions" />
      : <StatePanel variant="error" title="Inventory exclusions unavailable" body="The device registry could not be read." code={error}
          action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />;
  }
  if (devices === null) return <StatePanel variant="empty" title="Inventory exclusions" body="Loading…" />;

  const enrolledCount = devices.filter((d) => d.enrollment_state !== "DRAFT").length;
  const body = enrolledCount > 0
    ? "Excluding a device removes it from inventory collection while it stays enrolled for configuration evidence. No device has been excluded yet."
    : "Excluding a device removes it from inventory collection while it stays enrolled for configuration evidence. No device has been excluded yet, because no device has been enrolled yet.";
  return <EmptyPanel title="No device excluded" body={body} />;
}
