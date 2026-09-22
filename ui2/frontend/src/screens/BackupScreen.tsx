import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Dialog from "@mui/material/Dialog";
import DialogTitle from "@mui/material/DialogTitle";
import DialogContent from "@mui/material/DialogContent";
import DialogActions from "@mui/material/DialogActions";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import Alert from "@mui/material/Alert";
import CircularProgress from "@mui/material/CircularProgress";

import Switch from "@mui/material/Switch";
import {
  getBackupDeviations,
  getBackupPolicy,
  collectDeviceBackup,
  compareBackups,
  downloadBackupArtefact,
  listBackupEntries,
  listDeviceBackups,
  relistBackupContents,
  listDevices,
  listFleetBackups,
  requestFleetBackup,
  setBackupTarget,
  type DeviceSummary,
  type BackupArchiveListing,
  type BackupArtefact,
  type BackupCompareResult,
  type BackupDeviations,
  type BackupPolicy,
} from "../auth/adminApi";
import { ScreenHeader, MetricGrid, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "unknown size";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export interface BackupDeviceItem {
  readonly deviceId: string;
  readonly name: string;
  readonly ip: string;
  readonly vendor: string;
  readonly role: string;
  readonly lastBackupTime: string;
  readonly backupType: "standard" | "snapshot";
  readonly validationLevel: string;
  readonly deviationState: string;
  readonly sizeBytes: number;
  readonly artefactId: string;
}

/**
 * Folds the artefact rows down to one row per device, keeping the newest artefact.
 *
 * Every displayed field comes from the store. Where the store says nothing -- a device
 * whose vendor or address this view was never given, a deviation never evaluated -- the
 * row says UNKNOWN rather than filling the gap in. A validation level is shown exactly
 * as recorded, never normalised upward: the whole point of the V1-V4 ladder is that
 * "verified" on its own is not a claim anyone may make.
 */
export function toFleetRows(artefacts: readonly BackupArtefact[], nameByDeviceId: ReadonlyMap<string, string> = new Map()): BackupDeviceItem[] {
  const newestByDevice = new Map<string, BackupArtefact>();
  for (const artefact of artefacts) {
    const seen = newestByDevice.get(artefact.device_id);
    if (!seen || artefact.collected_at > seen.collected_at) {
      newestByDevice.set(artefact.device_id, artefact);
    }
  }

  return [...newestByDevice.values()]
    .sort((a, b) => b.collected_at.localeCompare(a.collected_at))
    .map((artefact) => ({
      deviceId: artefact.device_id,
      name: nameByDeviceId.get(artefact.device_id) ?? artefact.device_id,
      ip: "",
      // Product Owner, 2026-09-22: every row read "Check Point" -- the vendor was a constant here.
      // It comes from the artefact manifest now; "unknown" when the API did not carry it.
      vendor: artefact.vendor ?? "unknown",
      role: "",
      lastBackupTime: artefact.collected_at,
      backupType: "standard" as const,
      validationLevel: artefact.validation_level || "UNKNOWN",
      deviationState: artefact.deviation_state ? artefact.deviation_state.toUpperCase() : "NOT EVALUATED",
      sizeBytes: artefact.size_bytes,
      artefactId: artefact.artefact_id,
    }));
}

export function BackupScreen() {
  const [loading, setLoading] = useState(false);
  const [triggeringId, setTriggeringId] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Modals state
  const [selectedDeviceDiff, setSelectedDeviceDiff] = useState<BackupDeviceItem | null>(null);
  const [selectedDeviceExport, setSelectedDeviceExport] = useState<BackupDeviceItem | null>(null);
  const [exportReason, setExportReason] = useState("");
  const [exportBusy, setExportBusy] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  // V41 archive contents and compare.
  const [contentsFor, setContentsFor] = useState<BackupDeviceItem | null>(null);
  const [contents, setContents] = useState<BackupArchiveListing | null>(null);
  const [contentsFilter, setContentsFilter] = useState("");
  const [contentsError, setContentsError] = useState<string | null>(null);
  const [compareHistory, setCompareHistory] = useState<BackupArtefact[]>([]);
  const [compareOtherId, setCompareOtherId] = useState<string>("");
  const [compareResult, setCompareResult] = useState<BackupCompareResult | null>(null);
  const [compareError, setCompareError] = useState<string | null>(null);
  const [compareBusy, setCompareBusy] = useState(false);
  const [policyOpen, setPolicyOpen] = useState(false);

  // Policy configuration (PO requirements: 14 days standard retention, depth 4 snapshots, 400 GB vault)
  const [dailyCron, setDailyCron] = useState("0 2 * * *");
  const [weeklyCron, setWeeklyCron] = useState("0 3 * * 0");
  const [retentionDays, setRetentionDays] = useState(14);
  const [snapshotDepth, setSnapshotDepth] = useState(4);

  // The fleet list is read from the artefact store. It used to be a hardcoded array,
  // which meant the screen showed three devices with passing validation badges whether
  // or not a single backup existed. An operator reading "V2" for a device that has no
  // backup is the most dangerous error this screen can make, so an empty store now
  // renders as empty and a failed read renders as a failed read.
  const [devices, setDevices] = useState<BackupDeviceItem[]>([]);
  const [listError, setListError] = useState<string | null>(null);
  const [listLoaded, setListLoaded] = useState(false);

  const [policy, setPolicy] = useState<BackupPolicy | null>(null);
  const [deviations, setDeviations] = useState<BackupDeviations | null>(null);
  const [fleetOpen, setFleetOpen] = useState(false);
  const [fleetReason, setFleetReason] = useState("");
  const [fleetBusy, setFleetBusy] = useState(false);
  const [fleetError, setFleetError] = useState<string | null>(null);
  const [targetsVersion, setTargetsVersion] = useState(0);

  const handleFleetBackup = async () => {
    setFleetBusy(true);
    setFleetError(null);
    try {
      const outcome = await requestFleetBackup(fleetReason.trim());
      setSuccessMessage(
        `Fleet backup: ${outcome.admitted} of ${outcome.targets} backup target${outcome.targets === 1 ? "" : "s"} admitted` +
          (outcome.refused > 0 ? ` (${outcome.refused} refused -- see Administration > Job Logs).` : "."),
      );
      setFleetOpen(false);
      setFleetReason("");
      void loadFleet();
    } catch (error) {
      setFleetError(error instanceof Error ? error.message : "Fleet backup could not be requested.");
    } finally {
      setFleetBusy(false);
    }
  };

  const loadFleet = useCallback(async () => {
    try {
      const [response, names] = await Promise.all([
        listFleetBackups(),
        listDevices().then((r) => new Map(r.devices.map((d) => [d.device_id, d.hostname ?? d.device_id] as const))).catch(() => new Map<string, string>()),
      ]);
      setDevices(toFleetRows(response.backups ?? [], names));
      setListError(null);
    } catch (error) {
      setDevices([]);
      setListError(error instanceof Error ? error.message : "The backup store could not be read.");
    } finally {
      setListLoaded(true);
    }

    // Policy and deviation counts are read separately and are allowed to be absent.
    // A card with nothing behind it says so; it does not fall back to a figure.
    try {
      setPolicy(await getBackupPolicy());
    } catch {
      setPolicy(null);
    }
    try {
      setDeviations(await getBackupDeviations());
    } catch {
      setDeviations(null);
    }
  }, []);

  useEffect(() => {
    void loadFleet();
  }, [loadFleet]);

  const handleBackupNow = async (device: BackupDeviceItem, type: "standard" | "snapshot") => {
    setTriggeringId(device.deviceId);
    setLoading(true);
    const label = type === "snapshot" ? "Snapshot" : "Backup";
    try {
      // Through the API client: the CSRF token and the mapped /devices/{id}/backup/collect route.
      // The raw fetch to the /api/v2/backups/{id}/run alias went out without a token to a route
      // outside the action map, so every click was refused 403 (Product Owner, 2026-09-22).
      await collectDeviceBackup(device.deviceId, `Operator manual trigger for ${type} via console`, type === "snapshot" ? "snapshot" : "backup");
      setSuccessMessage(`${label} requested for ${device.name}. Validation level will appear once the run is recorded.`);
      await loadFleet();
    } catch (error) {
      setListError(`${label} request for ${device.name} was refused: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setLoading(false);
      setTriggeringId(null);
    }
  };

  // PO decision record 2026-09-22: the archive is downloaded here, role-gated by the service + reason,
  // audited by the service before the first byte. The dialog used to print a CLI command it
  // never ran and then report "audit record emitted" without touching the service.
  const handleDownload = async (device: BackupDeviceItem) => {
    setExportBusy(true);
    setExportError(null);
    try {
      const { blob, fileName } = await downloadBackupArtefact(device.artefactId, exportReason.trim());
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      setSelectedDeviceExport(null);
      setExportReason("");
      setSuccessMessage(`Downloaded ${fileName} (${formatBytes(blob.size)}); the retrieval is on the audit log.`);
      setTimeout(() => setSuccessMessage(null), 8000);
    } catch (error) {
      const status = (error as { status?: number }).status;
      const code = (error as { body?: { code?: string } }).body?.code;
      setExportError(
        status === 403
          ? "Refused by the service: your session is not allowed to download a backup."
          : `Download refused${status ? ` (HTTP ${status}${code ? `, ${code}` : ""})` : ""}.`,
      );
    } finally {
      setExportBusy(false);
    }
  };

  const [relistBusy, setRelistBusy] = useState(false);
  const relistNow = async (device: BackupDeviceItem) => {
    setRelistBusy(true);
    setContentsError(null);
    try {
      await relistBackupContents(device.artefactId);
      setContents(await listBackupEntries(device.artefactId));
    } catch (error) {
      const status = (error as { status?: number }).status;
      setContentsError(status === 403 ? "Refused by the service: your session may not list archive contents." : `Listing refused${status ? ` (HTTP ${status})` : ""}.`);
    } finally {
      setRelistBusy(false);
    }
  };

  const openContents = async (device: BackupDeviceItem) => {
    setContentsFor(device);
    setContents(null);
    setContentsError(null);
    setContentsFilter("");
    try {
      setContents(await listBackupEntries(device.artefactId));
    } catch (error) {
      setContentsError(error instanceof Error ? error.message : "The listing could not be read.");
    }
  };

  // The Diff dialog used to print five "Identical" domains from a constant, whatever the store held.
  // Compare now joins two listings of the same device on the service (names, sizes, digests).
  const openCompare = async (device: BackupDeviceItem) => {
    setSelectedDeviceDiff(device);
    setCompareResult(null);
    setCompareError(null);
    setCompareHistory([]);
    setCompareOtherId("");
    try {
      const history = (await listDeviceBackups(device.deviceId)).backups
        .filter((a) => a.artefact_id !== device.artefactId)
        .sort((a, b) => b.collected_at.localeCompare(a.collected_at));
      setCompareHistory(history);
      if (history.length > 0) {
        setCompareOtherId(history[0].artefact_id);
      }
    } catch (error) {
      setCompareError(error instanceof Error ? error.message : "The device's backup history could not be read.");
    }
  };

  const runCompare = async (device: BackupDeviceItem, otherId: string) => {
    setCompareBusy(true);
    setCompareError(null);
    setCompareResult(null);
    try {
      // left = older (the one chosen), right = the current row's artefact: "what changed since".
      setCompareResult(await compareBackups(otherId, device.artefactId));
    } catch (error) {
      const body = (error as { body?: { error?: string; reason?: string } }).body;
      setCompareError(
        body?.error === "NOT_LISTED"
          ? `One side has no content listing${body.reason ? ` (${body.reason})` : ""}; nothing to compare.`
          : body?.reason ?? (error instanceof Error ? error.message : "The compare was refused."),
      );
    } finally {
      setCompareBusy(false);
    }
  };

  // The service answers 405 POLICY_IMMUTABLE to any policy write: retention and vault
  // policy are changed through approved cluster configuration, not over HTTP. The dialog
  // used to close and report the policy updated without ever calling the service, so an
  // operator could believe they had changed a retention window that never moved.
  const handleSavePolicy = () => {
    setPolicyOpen(false);
    setListError(
      "Backup retention and vault policy are immutable over HTTP and were not changed. "
      + "They are updated through approved cluster configuration."
    );
  };

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Backups & Recovery"
        subtitle="Fleet backups, Gaia snapshots, and AST semantic deviation analysis. Every figure on this screen is read from the artefact store or the backup policy; nothing is inferred from inventory."
        actions={
          <Stack direction="row" spacing={1.5}>
            <M3Button emphasis="outlined" onClick={() => setPolicyOpen(true)}>
              Retention & Policies
            </M3Button>
            <M3Button emphasis="filled" onClick={() => setFleetOpen(true)}>
              Run Fleet Backup
            </M3Button>
          </Stack>
        }
      />

      {successMessage && (
        <Box sx={{ mb: 2 }}>
          <Alert severity="success" onClose={() => setSuccessMessage(null)}>
            {successMessage}
          </Alert>
        </Box>
      )}

      {listError && (
        <Box sx={{ mb: 2 }}>
          <Alert severity="warning" onClose={() => setListError(null)}>
            {listError}
          </Alert>
        </Box>
      )}

      {/* Metric Cards */}
      <MetricGrid>
        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Devices With A Stored Backup
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: m3.onSurface }}>
            {listLoaded && !listError ? devices.length : "—"}
          </Typography>
          {/* This was a hardcoded "100% / All active firewalls backed up". A protection
              rate needs a denominator this screen is not given, and a device with no
              backup is exactly the one an operator must not be reassured about, so the
              card counts what the store holds instead of rating what it does not know. */}
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            {listError ? "Store unreadable" : "Counted from the artefact store, not from inventory"}
          </Typography>
        </Card>

        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Configured Vault Capacity
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: m3.primary }}>
            {policy ? policy.storage_capacity : "—"}
          </Typography>
          {/* The card used to read "400 GiB / ui2-backup-vault PVC mounted", naming a
              volume that does not exist. This is the capacity the policy declares, which
              is not the same claim as space actually reserved: the store currently shares
              a volume with the evidence plane and no quota enforces either figure. */}
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            {policy ? "Declared by policy; not a measured reservation" : "Policy unavailable"}
          </Typography>
        </Card>

        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Retention Horizon
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: m3.onSurface }}>
            {policy ? `${policy.backup_retention_days} Days` : "—"}
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            {policy ? `Snapshots depth: ${policy.snapshot_retention_depth} retained` : "Policy unavailable"}
          </Typography>
        </Card>

        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Active Major Deviations
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: deviations ? m3.onSurface : m3.onSurfaceVar }}>
            {deviations ? deviations.active_major_deviations.length : "—"}
          </Typography>
          {/* A zero here used to be printed unconditionally with the caption "No
              unauthorized network or rule shifts". Zero deviations found and zero
              comparisons run look identical on the face of it and mean opposite things,
              so the caption now says which one this is. */}
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            {deviations
              ? `${deviations.total_deviations_checked} comparison${deviations.total_deviations_checked === 1 ? "" : "s"} run`
              : "Deviation state unavailable"}
          </Typography>
        </Card>
      </MetricGrid>

      <Dialog open={fleetOpen} onClose={() => (fleetBusy ? undefined : setFleetOpen(false))} fullWidth maxWidth="sm">
        <DialogTitle>Run Fleet Backup</DialogTitle>
        <DialogContent>
          <Stack spacing={1.5} sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              One backup is admitted per enrolled device in the backup targets below; nothing outside that list is touched.
              A backup needs an operator justification (BK-12).
            </Typography>
            <TextField
              label="Reason"
              placeholder="e.g. Weekly scheduled backup before change window"
              value={fleetReason}
              onChange={(e) => setFleetReason(e.target.value)}
              fullWidth
              size="small"
            />
            {fleetError && <Alert severity="error">{fleetError}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <M3Button emphasis="text" onClick={() => setFleetOpen(false)} disabled={fleetBusy}>Cancel</M3Button>
          <M3Button emphasis="filled" onClick={handleFleetBackup} disabled={fleetBusy || fleetReason.trim().length < 8}>
            {fleetBusy ? "Requesting…" : "Run"}
          </M3Button>
        </DialogActions>
      </Dialog>

      {/* Backup targets: which devices the product may back up at all. */}
      <Box sx={{ mt: 3 }}>
        <BackupTargetsSection version={targetsVersion} onChanged={() => setTargetsVersion((v) => v + 1)} />
      </Box>

      {/* Fleet Backups Table */}
      <Box sx={{ mt: 3 }}>
        <TableContainer
          component={Card}
          sx={{
            borderRadius: "16px",
            border: `1px solid ${m3.outlineVar}`,
            bgcolor: m3.scLow,
            boxShadow: "none",
          }}
        >
          <Table>
            <TableHead sx={{ bgcolor: m3.sc }}>
              <TableRow>
                <TableCell sx={{ fontWeight: 600, color: m3.onSurfaceVar }}>Device Name</TableCell>
                <TableCell sx={{ fontWeight: 600, color: m3.onSurfaceVar }}>IP & Role</TableCell>
                <TableCell sx={{ fontWeight: 600, color: m3.onSurfaceVar }}>Vendor</TableCell>
                <TableCell sx={{ fontWeight: 600, color: m3.onSurfaceVar }}>Last Backup</TableCell>
                <TableCell sx={{ fontWeight: 600, color: m3.onSurfaceVar }}>Validation</TableCell>
                <TableCell sx={{ fontWeight: 600, color: m3.onSurfaceVar }}>Deviation Status</TableCell>
                <TableCell align="right" sx={{ fontWeight: 600, color: m3.onSurfaceVar }}>Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {devices.map((device) => (
                <TableRow key={device.deviceId} hover>
                  <TableCell>
                    <Typography variant="subtitle2" sx={{ fontWeight: 600, color: m3.onSurface }}>
                      {device.name}
                    </Typography>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      {device.backupType === "snapshot" ? "Full OS Snapshot" : "Daily Config/State Backup"}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontFamily: "monospace", color: m3.onSurface }}>
                      {device.ip}
                    </Typography>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      {device.role}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={device.vendor === "palo_alto" ? "Palo Alto" : device.vendor === "check_point" ? "Check Point" : "Unknown vendor"}
                      sx={{
                        bgcolor: device.vendor === "palo_alto" ? "#e0f2fe" : "#fce7f3",
                        color: device.vendor === "palo_alto" ? "#0369a1" : "#be185d",
                        fontWeight: 600,
                      }}
                    />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" sx={{ color: m3.onSurface }}>
                      {device.lastBackupTime}
                    </Typography>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      {(device.sizeBytes / (1024 * 1024)).toFixed(1)} MB
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={device.validationLevel}
                      sx={{
                        bgcolor: m3.successContainer,
                        color: m3.onSuccessContainer,
                        fontWeight: 700,
                        fontSize: "0.75rem",
                      }}
                    />
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={device.deviationState}
                      sx={{
                        bgcolor:
                          device.deviationState === "UNCHANGED"
                            ? m3.successContainer
                            : device.deviationState === "MAJOR DEVIATION"
                            ? m3.errorContainer
                            : m3.primaryContainer,
                        color:
                          device.deviationState === "UNCHANGED"
                            ? m3.onSuccessContainer
                            : device.deviationState === "MAJOR DEVIATION"
                            ? m3.onErrorContainer
                            : m3.onPrimaryContainer,
                        fontWeight: 700,
                        fontSize: "0.75rem",
                      }}
                    />
                  </TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} justifyContent="flex-end">
                      <M3Button
                        emphasis="tonal"
                        disabled={loading && triggeringId === device.deviceId}
                        onClick={() => handleBackupNow(device, "standard")}
                      >
                        {loading && triggeringId === device.deviceId ? (
                          <CircularProgress size={16} />
                        ) : (
                          "Backup Now"
                        )}
                      </M3Button>

                      {device.vendor === "check_point" && (
                        <M3Button
                          emphasis="outlined"
                          disabled={loading && triggeringId === device.deviceId}
                          onClick={() => handleBackupNow(device, "snapshot")}
                        >
                          Snapshot Now
                        </M3Button>
                      )}

                      <M3Button emphasis="text" disabled={!device.artefactId} onClick={() => void openContents(device)}>
                        Contents
                      </M3Button>

                      <M3Button emphasis="text" disabled={!device.artefactId} onClick={() => void openCompare(device)}>
                        Compare
                      </M3Button>

                      <M3Button
                        emphasis="text"
                        disabled={!device.artefactId}
                        onClick={() => {
                          setExportError(null);
                          setSelectedDeviceExport(device);
                        }}
                      >
                        Download
                      </M3Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
              {listLoaded && devices.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7}>
                    <Typography variant="body2" sx={{ color: m3.onSurfaceVar, py: 2 }}>
                      {listError
                        ? "The backup store could not be read, so no fleet state is shown."
                        : "No backup artefact has been recorded yet. Nothing here is inferred from inventory: a device appears once it has a stored backup."}
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      {/* Deviation Diff Modal */}
      {selectedDeviceDiff && (
        <Dialog
          open={true}
          onClose={() => setSelectedDeviceDiff(null)}
          maxWidth="md"
          fullWidth
        >
          <DialogTitle sx={{ fontWeight: 700 }}>Compare backups: {selectedDeviceDiff.name}</DialogTitle>
          <DialogContent dividers>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1.5 }}>
              Compares the archive listings (member names, sizes and digests) of two backups of this device. It
              says which files changed, never what changed inside them.
            </Typography>
            {compareError && (
              <Alert severity="warning" sx={{ mb: 2 }}>
                {compareError}
              </Alert>
            )}
            {compareHistory.length === 0 && !compareError && (
              <Alert severity="info">This device has only one backup on record; there is nothing to compare it with yet.</Alert>
            )}
            {compareHistory.length > 0 && (
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
                <TextField
                  select
                  size="small"
                  label="Compare current with"
                  value={compareOtherId}
                  onChange={(e) => setCompareOtherId(e.target.value)}
                  SelectProps={{ native: true }}
                  sx={{ minWidth: 320 }}
                >
                  {compareHistory.map((a) => (
                    <option key={a.artefact_id} value={a.artefact_id}>
                      {a.collected_at} · {formatBytes(a.size_bytes)} · {a.artefact_id.slice(0, 8)}
                    </option>
                  ))}
                </TextField>
                <M3Button
                  emphasis="filled"
                  disabled={compareBusy || !compareOtherId}
                  onClick={() => void runCompare(selectedDeviceDiff, compareOtherId)}
                >
                  {compareBusy ? <CircularProgress size={16} /> : "Compare"}
                </M3Button>
              </Stack>
            )}
            {compareResult && (
              <Box sx={{ p: 2, bgcolor: m3.scLow, borderRadius: "8px", border: `1px solid ${m3.outlineVar}` }}>
                <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>
                  {compareResult.identical
                    ? "Identical listings: every member present in both with the same digest."
                    : `${compareResult.changed.length} changed · ${compareResult.added.length} added · ${compareResult.removed.length} removed · ${compareResult.unchanged} unchanged`}
                </Typography>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block", mb: 1 }}>
                  {compareResult.left.collected_at} ({formatBytes(compareResult.left.size_bytes)}) →{" "}
                  {compareResult.right.collected_at} ({formatBytes(compareResult.right.size_bytes)})
                </Typography>
                {(
                  [
                    ["Changed", compareResult.changed.map((c) => `${c.path} (${formatBytes(c.left_bytes)} → ${formatBytes(c.right_bytes)})`)],
                    ["Added", compareResult.added],
                    ["Removed", compareResult.removed],
                  ] as const
                ).map(([label, rows]) =>
                  rows.length === 0 ? null : (
                    <Box key={label} sx={{ mb: 1 }}>
                      <Typography variant="caption" sx={{ fontWeight: 700, color: m3.primary }}>
                        {label.toUpperCase()} ({rows.length})
                      </Typography>
                      <Box
                        component="pre"
                        sx={{ m: 0, p: 1, bgcolor: m3.surface, borderRadius: "4px", fontSize: "0.75rem", maxHeight: 220, overflow: "auto" }}
                      >
                        {rows.slice(0, 500).join("\n")}
                        {rows.length > 500 ? `\n… ${rows.length - 500} more` : ""}
                      </Box>
                    </Box>
                  ),
                )}
              </Box>
            )}
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="filled" onClick={() => setSelectedDeviceDiff(null)}>
              Close
            </M3Button>
          </DialogActions>
        </Dialog>
      )}

      {/* V41 archive contents */}
      {contentsFor && (
        <Dialog open={true} onClose={() => setContentsFor(null)} maxWidth="md" fullWidth>
          <DialogTitle sx={{ fontWeight: 700 }}>Archive contents: {contentsFor.name}</DialogTitle>
          <DialogContent dividers>
            {contentsError && <Alert severity="error">{contentsError}</Alert>}
            {!contents && !contentsError && <CircularProgress size={20} />}
            {contents && contents.listing_state !== "LISTED" && (
              <Alert
                severity="info"
                action={
                  <M3Button emphasis="tonal" disabled={relistBusy} onClick={() => void relistNow(contentsFor)}>
                    {relistBusy ? <CircularProgress size={16} /> : "List now"}
                  </M3Button>
                }
              >
                {contents.listing_state === "FAILED"
                  ? `The archive could not be listed${contents.reason ? `: ${contents.reason}` : ""}.`
                  : "This backup was stored before content listing existed; it has no listing."}
              </Alert>
            )}
            {contents && contents.listing_state === "LISTED" && (
              <>
                <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1 }}>
                  <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                    {contents.entry_count} members{contents.truncated ? " (first 20 000 shown)" : ""} · listed {contents.listed_at}
                  </Typography>
                  <TextField
                    size="small"
                    label="Filter by path"
                    value={contentsFilter}
                    onChange={(e) => setContentsFilter(e.target.value)}
                    sx={{ ml: "auto", minWidth: 260 }}
                  />
                </Stack>
                <TableContainer sx={{ maxHeight: 440 }}>
                  <Table size="small" stickyHeader>
                    <TableHead>
                      <TableRow>
                        <TableCell>Path</TableCell>
                        <TableCell>Type</TableCell>
                        <TableCell align="right">Size</TableCell>
                        <TableCell>Digest</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {contents.entries
                        .filter((e) => !contentsFilter || e.path.toLowerCase().includes(contentsFilter.toLowerCase()))
                        .slice(0, 2000)
                        .map((e) => (
                          <TableRow key={e.path}>
                            <TableCell sx={{ fontFamily: "monospace", fontSize: "0.75rem", wordBreak: "break-all" }}>{e.path}</TableCell>
                            <TableCell>{e.type}</TableCell>
                            <TableCell align="right">{e.type === "file" ? formatBytes(e.bytes) : ""}</TableCell>
                            <TableCell sx={{ fontFamily: "monospace", fontSize: "0.75rem" }}>{e.digest_prefix ?? ""}</TableCell>
                          </TableRow>
                        ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              </>
            )}
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="filled" onClick={() => setContentsFor(null)}>
              Close
            </M3Button>
          </DialogActions>
        </Dialog>
      )}

      {/* Export / Retrieval Command Modal */}
      {selectedDeviceExport && (
        <Dialog
          open={true}
          onClose={() => setSelectedDeviceExport(null)}
          maxWidth="sm"
          fullWidth
        >
          <DialogTitle sx={{ fontWeight: 700 }}>
            Download backup: {selectedDeviceExport.name}
          </DialogTitle>
          <DialogContent dividers>
            <Alert severity="warning" sx={{ mb: 2 }}>
              The archive is decrypted by the service and downloaded as-is. A Gaia backup carries the
              system's own account database and other secret-bearing files: the copy is yours to protect
              from here on. The service decides who may download; every download is audited with your reason.
            </Alert>

            <Typography variant="body2" sx={{ mb: 0.5, fontWeight: 500 }}>
              Artefact ID: <code>{selectedDeviceExport.artefactId}</code>
            </Typography>
            <Typography variant="body2" sx={{ mb: 1.5, color: m3.onSurfaceVar }}>
              {selectedDeviceExport.vendor} · {formatBytes(selectedDeviceExport.sizeBytes)} · collected{" "}
              {selectedDeviceExport.lastBackupTime}
            </Typography>

            <TextField
              fullWidth
              label="Operator Justification / Reason (min 8 characters)"
              placeholder="e.g. Disaster recovery drill ticket SEC-4091"
              value={exportReason}
              onChange={(e) => setExportReason(e.target.value)}
              disabled={exportBusy}
              sx={{ mb: 1 }}
            />
            {exportError && (
              <Alert severity="error" sx={{ mt: 1 }}>
                {exportError}
              </Alert>
            )}
          </DialogContent>
          <DialogActions>
            <M3Button
              emphasis="text"
              disabled={exportBusy}
              onClick={() => {
                setSelectedDeviceExport(null);
                setExportError(null);
              }}
            >
              Cancel
            </M3Button>
            <M3Button
              emphasis="filled"
              disabled={exportBusy || exportReason.trim().length < 8}
              onClick={() => void handleDownload(selectedDeviceExport)}
            >
              {exportBusy ? <CircularProgress size={16} /> : "Download"}
            </M3Button>
          </DialogActions>
        </Dialog>
      )}

      {/* Retention Policy Modal */}
      <Dialog open={policyOpen} onClose={() => setPolicyOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ fontWeight: 700 }}>Backup & Snapshot Policies</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            <TextField
              label="Daily Backup Schedule (Cron)"
              value={dailyCron}
              onChange={(e) => setDailyCron(e.target.value)}
              helperText="Default: 0 2 * * * (02:00 UTC Daily)"
              fullWidth
            />
            <TextField
              label="Weekly Snapshot Schedule (Cron)"
              value={weeklyCron}
              onChange={(e) => setWeeklyCron(e.target.value)}
              helperText="Default: 0 3 * * 0 (03:00 UTC Sunday)"
              fullWidth
            />
            <TextField
              label="Daily Backup Retention Period (Days)"
              type="number"
              value={retentionDays}
              onChange={(e) => setRetentionDays(Number(e.target.value))}
              helperText="Backups older than this horizon are pruned with append-only ledger tombstones (Approved: 30 days)"
              fullWidth
            />
            <TextField
              label="Weekly Snapshot Retention Depth"
              type="number"
              value={snapshotDepth}
              onChange={(e) => setSnapshotDepth(Number(e.target.value))}
              helperText="Number of full OS snapshots kept per gateway (Approved: 2 snapshots)"
              fullWidth
            />
            <Box sx={{ p: 1.5, bgcolor: m3.scLow, borderRadius: "8px" }}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                Storage Allocation: 400 GiB Dedicated Persistent Volume
              </Typography>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                Allocated to accommodate 1-month daily backups and 2-depth Gaia snapshots without disk exhaustion.
              </Typography>
            </Box>
          </Stack>
        </DialogContent>
        <DialogActions>
          <M3Button emphasis="text" onClick={() => setPolicyOpen(false)}>
            Cancel
          </M3Button>
          <M3Button emphasis="filled" onClick={handleSavePolicy}>
            Save Policy
          </M3Button>
        </DialogActions>
      </Dialog>
    </ScreenRoot>
  );
}


/**
 * Backups > Backup targets (Product Owner, 2026-09-22): the devices the product is allowed to
 * back up -- singly or by "Run Fleet Backup" -- chosen here, never by hand in the database. Every
 * enrolled gateway is listed; the switch is an audited devices UPDATE.
 */
function BackupTargetsSection({ version, onChanged }: { readonly version: number; readonly onChanged: () => void }) {
  const [devices, setDevices] = useState<DeviceSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [targetsOnly, setTargetsOnly] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listDevices()
      .then((r) => {
        if (!cancelled) {
          setDevices(r.devices.filter((d) => d.enrollment_state === "ENROLLED" && (d.role === undefined || d.role === "gateway" || d.role === "firewall")));
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Devices could not be read.");
      });
    return () => {
      cancelled = true;
    };
  }, [version]);

  const toggle = async (device: DeviceSummary, enabled: boolean) => {
    setBusyId(device.device_id);
    try {
      await setBackupTarget(device.device_id, enabled);
      setDevices((prev) => prev?.map((d) => (d.device_id === device.device_id ? { ...d, backup_target: enabled } : d)) ?? prev);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "The backup target could not be changed.");
    } finally {
      setBusyId(null);
    }
  };

  const targetCount = devices?.filter((d) => d.backup_target).length ?? 0;
  const shown = (devices ?? []).filter((d) => {
    if (targetsOnly && !d.backup_target) return false;
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (d.hostname ?? "").toLowerCase().includes(q) || (d.model ?? "").toLowerCase().includes(q) || d.device_id.toLowerCase().includes(q);
  });

  return (
    <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, boxShadow: "none" }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1.5, mb: 1.5 }}>
        <Box>
          <Typography variant="h6" sx={{ fontWeight: 700, color: m3.onSurface }}>
            Backup targets · {targetCount}
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            Only devices switched on here are ever backed up. Enrolled gateways are listed; each change is audited.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center">
          <TextField size="small" placeholder="Filter devices…" value={search} onChange={(e) => setSearch(e.target.value)} />
          <M3Button emphasis={targetsOnly ? "filled" : "outlined"} onClick={() => setTargetsOnly(!targetsOnly)}>
            {targetsOnly ? "✓ Targets only" : "All enrolled"}
          </M3Button>
        </Stack>
      </Box>
      {error && <Alert severity="warning" sx={{ mb: 1.5 }}>{error}</Alert>}
      {devices === null ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Loading devices…</Typography>
      ) : shown.length === 0 ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          {devices.length === 0 ? "No enrolled gateway to choose from yet." : "No device matches the filter."}
        </Typography>
      ) : (
        <TableContainer sx={{ maxHeight: 360 }}>
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow>
                <TableCell sx={{ fontWeight: 600 }}>Device</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Vendor</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Model · Version</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Cluster</TableCell>
                <TableCell sx={{ fontWeight: 600 }} align="right">Backup target</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {shown.map((d) => (
                <TableRow key={d.device_id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{d.hostname ?? d.device_id}</TableCell>
                  <TableCell>{d.vendor_hint === "palo_alto" ? "Palo Alto" : "Check Point"}</TableCell>
                  <TableCell sx={{ color: m3.onSurfaceVar }}>{d.model ?? "—"}{d.software_version ? ` · ${d.software_version}` : ""}</TableCell>
                  <TableCell sx={{ color: m3.onSurfaceVar }}>{d.cluster_member_ref ?? "—"}</TableCell>
                  <TableCell align="right">
                    <Switch
                      size="small"
                      checked={Boolean(d.backup_target)}
                      disabled={busyId === d.device_id}
                      onChange={(e) => void toggle(d, e.target.checked)}
                      inputProps={{ "aria-label": `Backup target ${d.hostname ?? d.device_id}` }}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Card>
  );
}
