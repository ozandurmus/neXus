import { useState } from "react";

import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import InputBase from "@mui/material/InputBase";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { PaginationFooter } from "../shell/DataTable";
import { CapabilityMenu, M3Button, StatusChip } from "../shell/M3Widgets";
import { ChangeCredentialsDialog } from "../shell/ChangeCredentialsDialog";
import { RestrictedPanel, StatePanel, VendorBadge, isRestricted } from "../shell/States";
import { Stamp } from "../shell/PresentedText";
import { Icon } from "../shell/Icon";
import { vendorLabel } from "../shell/presenters";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { deleteDevice, listDevices, type ApiError, type BackupDisposition, type DeviceSummary } from "../auth/adminApi";
import { deviceNameLabel, enrollmentStateLabel, enrollmentStateTone, missingHostnameReason, onboardingChip } from "../shell/deviceCopy";

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

/**
 * The registry filters behind the Enrollment card (UI review 2026-10-18, F-22). Each count in the card is a filter;
 * the predicates are the ones the counts use, so a number and the list it opens always agree.
 */
export type RegistryFilter = "ALL" | "ENROLLED" | "LAST_FAILED" | "DRAFT";

/** Enrolled devices whose most recent collection failed, or that the store itself marked degraded or unreachable. */
const lastCollectionFailed = (d: DeviceSummary) => d.enrollment_state === "DEGRADED" || d.enrollment_state === "UNREACHABLE"
  || (d.enrollment_state === "ENROLLED" && d.latest_job_state === "FAILED");

export function registryMatches(d: DeviceSummary, filter: RegistryFilter, search: string): boolean {
  if (filter === "ENROLLED" && d.enrollment_state !== "ENROLLED") return false;
  if (filter === "LAST_FAILED" && !lastCollectionFailed(d)) return false;
  if (filter === "DRAFT" && d.enrollment_state !== "DRAFT") return false;
  const needle = search.trim().toLowerCase();
  if (!needle) return true;
  return [d.hostname ?? "", d.vendor_hint, vendorLabel(d.vendor_hint), d.enrollment_state, enrollmentStateLabel(d.enrollment_state), d.model ?? ""]
    .some((text) => text.toLowerCase().includes(needle));
}

const PAGE_SIZES = [25, 50, 100] as const;

/** The device/hostname cell: the plain name, or "UNKNOWN hostname" with a chip naming why (review §3). */
function HostnameCell({ device }: { readonly device: DeviceSummary }) {
  const hostname = device.hostname?.trim();
  if (hostname) {
    return (
      <Typography variant="body2" title={hostname} sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontWeight: 500 }}>
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
  const [filter, setFilter] = useState<RegistryFilter>("ALL");
  const [search, setSearch] = useState("");
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
  const degradedCount = devices?.filter(lastCollectionFailed).length ?? 0;
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
      <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) 260px", gap: 2 }}>
      <DeviceRegistryCard
        devices={devices}
        deleteError={deleteError}
        onDelete={onDelete}
        onBulkDelete={onBulkDelete}
        onChanged={refresh}
        total={total}
        filter={filter}
        search={search}
        onSearch={setSearch}
        onFilter={setFilter}
      />
      <Stack spacing={2}>
        <EmptyPanel title="Enrollment" body="Enrolling a device grants read collection only. Each count filters the registry.">
          <Stack spacing={0.5} role="group" aria-label="Registry filters">
            {([
              ["ENROLLED", "Enrolled", enrolledCount, "ok"],
              ["LAST_FAILED", "Last collection failed (enrolled)", degradedCount, "warn"],
              ["DRAFT", "Draft · not collected", draftCount, "neutral"],
            ] as const).map(([key, label, count, tone]) => (
              <Box key={key} component="button" type="button" aria-pressed={filter === key} onClick={() => setFilter(filter === key ? "ALL" : key)}
                sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1, width: "100%", textAlign: "left", cursor: "pointer",
                  font: "inherit", color: "inherit", px: 1, py: 0.75, borderRadius: "8px", border: "1px solid",
                  borderColor: filter === key ? m3.primary : "transparent", bgcolor: filter === key ? m3.secondaryContainer : "transparent",
                  "&:hover": { bgcolor: filter === key ? m3.secondaryContainer : m3.scLow } }}>
                <Typography variant="body2" component="span">{label}</Typography>
                {/* Zero is neutral, never green: an empty count carries no status colour. */}
                <StatusChip tone={count === 0 ? "neutral" : tone} label={`${count} device${count === 1 ? "" : "s"}`} dense />
              </Box>
            ))}
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
  filter,
  search,
  onSearch,
  onFilter,
}: {
  readonly devices: DeviceSummary[] | null;
  readonly deleteError: string | null;
  readonly onDelete: (deviceId: string) => void;
  readonly onBulkDelete: (deviceIds: string[]) => Promise<boolean>;
  readonly onChanged: () => void;
  readonly total: number;
  readonly filter: RegistryFilter;
  readonly search: string;
  readonly onSearch: (value: string) => void;
  readonly onFilter: (filter: RegistryFilter) => void;
}) {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [isDeleting, setIsDeleting] = useState(false);
  // Ids pending a named confirmation dialog -- either one row's Delete (from its overflow menu) or the bulk
  // bar's Delete for the current selection. The capability and its API call are unchanged; only the click a
  // stray tap can no longer trigger moved off the row (review "shows a filled Delete button on every row").
  const [confirmIds, setConfirmIds] = useState<string[] | null>(null);
  const [credentialsFor, setCredentialsFor] = useState<DeviceSummary | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState<number>(50);

  if (devices === null) {
    return <StatePanel variant="empty" title="Device registry" body="Loading…" />;
  }

  // The list the filters leave, then the page of it in view. "Select all" selects what the filters leave, not the whole
  // registry, so a bulk Delete never reaches a row the reader cannot see.
  const matching = devices.filter((d) => registryMatches(d, filter, search));
  const lastPage = Math.max(0, Math.ceil(matching.length / pageSize) - 1);
  const shownPage = Math.min(page, lastPage);
  const pageRows = matching.slice(shownPage * pageSize, shownPage * pageSize + pageSize);
  const matchingSelected = matching.filter((d) => selectedIds.has(d.device_id)).length;
  const isAllSelected = matching.length > 0 && matchingSelected === matching.length;
  const isSomeSelected = matchingSelected > 0 && matchingSelected < matching.length;

  const handleToggleAll = () => {
    if (isAllSelected) {
      setSelectedIds((prev) => { const next = new Set(prev); matching.forEach((d) => next.delete(d.device_id)); return next; });
    } else {
      setSelectedIds((prev) => { const next = new Set(prev); matching.forEach((d) => next.add(d.device_id)); return next; });
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
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
          <Box sx={{ flex: "1 1 260px", display: "flex", alignItems: "center", gap: 1, px: 1.5, py: 0.5, bgcolor: m3.scLow, border: "1px solid",
            borderColor: m3.outlineVar, borderRadius: "10px" }}>
            <Icon name="search" size={18} />
            <InputBase fullWidth placeholder="Search by hostname, vendor or state" value={search} onChange={(e) => { onSearch(e.target.value); setPage(0); }}
              inputProps={{ "aria-label": "Search registry" }} sx={{ fontSize: 14 }} />
          </Box>
          {(filter !== "ALL" || search.trim()) && (
            <M3Button emphasis="text" onClick={() => { onFilter("ALL"); onSearch(""); setPage(0); }}>Clear filters</M3Button>
          )}
          <Typography variant="body2" color="text.secondary" role="status">
            {matching.length === total ? `${total} of ${total}` : `${matching.length} of ${total} match`}
          </Typography>
        </Stack>
      )}
      {total > 0 && (
        <TableContainer sx={{ mt: 1, border: "1px solid", borderColor: m3.outlineVar, borderRadius: "10px" }}>
          <Table size="small" aria-label="Device registry" sx={{ tableLayout: "fixed", "& td, & th": { px: 1 } }}>
            <TableHead>
              <TableRow sx={{ "& th": { color: "text.secondary", fontSize: "0.75rem", fontWeight: 600, whiteSpace: "nowrap" } }}>
                <TableCell padding="checkbox" sx={{ width: 44 }}>
                  <Checkbox
                    checked={isAllSelected}
                    indeterminate={isSomeSelected}
                    onChange={handleToggleAll}
                    size="small"
                    inputProps={{ "aria-label": "Select all devices" }}
                  />
                </TableCell>
                <TableCell sx={{ width: 130 }}>VENDOR</TableCell>
                <TableCell>DEVICE / HOSTNAME</TableCell>
                <TableCell sx={{ width: 150 }}>ENROLLMENT STATE</TableCell>
                <TableCell sx={{ width: 170 }}>LAST COLLECTION</TableCell>
                <TableCell sx={{ width: 48 }} align="right">&nbsp;</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {pageRows.map((device) => (
                <TableRow key={device.device_id} hover selected={selectedIds.has(device.device_id)}>
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={selectedIds.has(device.device_id)}
                      onChange={() => handleToggleRow(device.device_id)}
                      size="small"
                      inputProps={{ "aria-label": `Select ${deviceNameLabel(device.hostname)}` }}
                    />
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={1} alignItems="center">
                      <VendorBadge vendor={device.vendor_hint} size={24} />
                      <Typography variant="body2" title={device.vendor_hint} sx={{ whiteSpace: "nowrap" }}>{vendorLabel(device.vendor_hint)}</Typography>
                    </Stack>
                  </TableCell>
                  <TableCell><HostnameCell device={device} /></TableCell>
                  <TableCell>
                    {(() => {
                      const chip = onboardingChip(device.onboarding);
                      return chip
                        ? <span title={chip.title}><StatusChip tone={chip.tone} label={chip.label} dense /></span>
                        : <StatusChip tone={enrollmentStateTone(device.enrollment_state)} label={enrollmentStateLabel(device.enrollment_state)} dense />;
                    })()}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}><Stamp at={device.inventory_collected_at} stacked /></TableCell>
                  <TableCell align="right">
                    <CapabilityMenu
                      ariaLabel={`${deviceNameLabel(device.hostname)} actions`}
                      items={[
                        { label: "Change credentials…", onSelect: () => setCredentialsFor(device) },
                        { label: "Delete", destructive: true, dividerBefore: true, onSelect: () => setConfirmIds([device.device_id]) },
                      ]}
                    />
                  </TableCell>
                </TableRow>
              ))}
              {pageRows.length === 0 && (
                <TableRow><TableCell colSpan={6}><Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>No device matches these filters.</Typography></TableCell></TableRow>
              )}
            </TableBody>
          </Table>
          <Box sx={{ display: "flex", px: 1, py: 0.5, borderTop: "1px solid", borderColor: m3.outlineVar }}>
            <PaginationFooter offset={shownPage * pageSize} size={pageSize} total={matching.length} onPage={(offset) => setPage(Math.floor(offset / pageSize))}
              sizes={PAGE_SIZES} onSize={(size) => { setPageSize(size); setPage(0); }} />
          </Box>
        </TableContainer>
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
