import { urlParam } from "../shell/urlParams";
import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
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
  updateBackupPolicy,
  setBackupBaseline,
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
import { ScreenHeader, MetricGrid, MetricCard, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { Ts, VendorBadge } from "../shell/States";
import { utcHourToLocal } from "../shell/time";

/** " (= 05:00 GMT+3)" for a daily cron with a fixed minute and hour; "" when the expression is not that simple. */
function cronLocal(cron: string | null | undefined): string {
  const m = /^(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+\*$/.exec((cron ?? "").trim());
  return m ? ` (= ${utcHourToLocal(Number(m[2]), Number(m[1]))})` : "";
}
import Tooltip from "@mui/material/Tooltip";
import { MONO, m3 } from "../theme/m3Theme";

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

  // V44: the policy dialog edits the real, audited row; fields are seeded from the last read.
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [dailyCron, setDailyCron] = useState("0 2 * * *");
  const [retentionDays, setRetentionDays] = useState(14);
  const [snapshotDepth, setSnapshotDepth] = useState(4);
  const [policyBusy, setPolicyBusy] = useState(false);
  const [policyError, setPolicyError] = useState<string | null>(null);
  // V44: per-device baseline artefact ids and the History dialog.
  const [baselines, setBaselines] = useState<Record<string, string>>({});
  const [historyFor, setHistoryFor] = useState<BackupDeviceItem | null>(null);
  const [history, setHistory] = useState<BackupArtefact[] | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [baselineBusy, setBaselineBusy] = useState<string | null>(null);

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
      setBaselines(response.baselines ?? {});
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
      const loaded = await getBackupPolicy();
      setPolicy(loaded);
      setScheduleEnabled(Boolean(loaded.schedule_enabled));
      setDailyCron(loaded.daily_backup_cron ?? "0 2 * * *");
      setRetentionDays(loaded.backup_retention_days ?? 14);
      setSnapshotDepth(loaded.snapshot_retention_depth ?? 4);
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

  const handleBackupNow = async (device: BackupDeviceItem, type: "standard" | "snapshot" | "mds_export") => {
    if (type === "mds_export" && !window.confirm(
      "MDS export runs mds_backup for the whole Multi-Domain Server (every domain).\n\n"
      + "While it runs, the MDS database is locked: no SmartConsole changes can be saved or published in any domain "
      + "until it completes (Check Point). It normally runs at night.\n\nStart it now?")) {
      return;
    }
    setTriggeringId(device.deviceId);
    setLoading(true);
    const label = type === "snapshot" ? "Snapshot" : type === "mds_export" ? "MDS export" : "Backup";
    try {
      // Through the API client: the CSRF token and the mapped /devices/{id}/backup/collect route.
      // The raw fetch to the /api/v2/backups/{id}/run alias went out without a token to a route
      // outside the action map, so every click was refused 403 (Product Owner, 2026-09-22).
      await collectDeviceBackup(device.deviceId, `Operator manual trigger for ${type} via console`, type === "standard" ? "backup" : type);
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
      const body = (error as { body?: { code?: string; reason?: string } }).body;
      setExportError(
        status === 403
          ? "Refused by the service: your session is not allowed to download a backup."
          : body?.reason
            ? `Download refused: ${body.reason}`
            : `Download refused${status ? ` (HTTP ${status}${body?.code ? `, ${body.code}` : ""})` : ""}.`,
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
      const baseline = baselines[device.deviceId];
      if (baseline && history.some((a) => a.artefact_id === baseline)) {
        setCompareOtherId(baseline);
      } else if (history.length > 0) {
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

  // V44: the policy is a real, audited row now; the dialog used to close and report a
  // change it never made, then (honestly) refuse. Save writes it and re-reads it.
  const handleSavePolicy = async () => {
    setPolicyBusy(true);
    setPolicyError(null);
    try {
      const saved = await updateBackupPolicy({
        schedule_enabled: scheduleEnabled,
        daily_backup_cron: dailyCron.trim(),
        backup_retention_days: retentionDays,
        snapshot_retention_depth: snapshotDepth,
      });
      setPolicy(saved);
      setPolicyOpen(false);
      setSuccessMessage(saved.schedule_enabled
        ? `Policy saved: fleet backup scheduled at "${saved.daily_backup_cron}" (UTC), ${saved.backup_retention_days}-day retention.`
        : `Policy saved: schedule off, ${saved.backup_retention_days}-day retention.`);
      setTimeout(() => setSuccessMessage(null), 8000);
    } catch (error) {
      const body = (error as { body?: { reason?: string } }).body;
      const status = (error as { status?: number }).status;
      setPolicyError(body?.reason ?? (status === 403 ? "Refused by the service: your session may not change the backup policy." : "The policy could not be saved."));
    } finally {
      setPolicyBusy(false);
    }
  };

  const openHistory = async (device: BackupDeviceItem) => {
    setHistoryFor(device);
    setHistory(null);
    setHistoryError(null);
    try {
      const response = await listDeviceBackups(device.deviceId);
      setHistory([...response.backups].sort((a, b) => b.collected_at.localeCompare(a.collected_at)));
      if (response.baseline_artefact_id) {
        setBaselines((prev) => ({ ...prev, [device.deviceId]: response.baseline_artefact_id as string }));
      }
    } catch (error) {
      setHistoryError(error instanceof Error ? error.message : "The device's backup history could not be read.");
    }
  };

  const handleSetBaseline = async (device: BackupDeviceItem, artefactId: string | null) => {
    setBaselineBusy(artefactId ?? "clear");
    setHistoryError(null);
    try {
      const result = await setBackupBaseline(device.deviceId, artefactId);
      setBaselines((prev) => {
        const next = { ...prev };
        if (result.baseline_artefact_id) next[device.deviceId] = result.baseline_artefact_id;
        else delete next[device.deviceId];
        return next;
      });
    } catch (error) {
      const status = (error as { status?: number }).status;
      setHistoryError(status === 403 ? "Refused by the service: your session may not set a baseline." : `Baseline change refused${status ? ` (HTTP ${status})` : ""}.`);
    } finally {
      setBaselineBusy(null);
    }
  };

  /** A row for one artefact of a device, so the per-artefact dialogs (Contents, Download) work from History too. */
  const asItem = (device: BackupDeviceItem, artefact: BackupArtefact): BackupDeviceItem => ({
    ...device,
    artefactId: artefact.artefact_id,
    sizeBytes: artefact.size_bytes,
    lastBackupTime: artefact.collected_at,
    validationLevel: artefact.validation_level || "UNKNOWN",
  });

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Backups"
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

      {/* Metric Cards: a figure or the word for why there is none (UI review 2026-09-23) */}
      <MetricGrid>
        <MetricCard title="Devices with a stored backup" value={listLoaded && !listError ? devices.length : null}
          state={listLoaded && !listError ? "ok" : "unknown"} reason={listError ?? undefined}
          note={listError ? "Store unreadable" : "Counted from the artefact store, not from inventory"} />
        <Card sx={{ p: 2.25, borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none", display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar }}>Scheduled fleet backup</Typography>
          {!policy ? (
            <Typography sx={{ fontSize: 22, lineHeight: "40px", fontWeight: 650, color: m3.neutralInk }}>UNKNOWN</Typography>
          ) : policy.schedule_enabled ? (
            <Typography sx={{ fontFamily: MONO, fontSize: 24, lineHeight: "40px", fontWeight: 600, color: m3.onSurface }}>{policy.daily_backup_cron}</Typography>
          ) : (
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, minHeight: 40 }}>
              <StatusChip tone="warn" label="Not scheduled" />
            </Box>
          )}
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            {policy
              ? policy.schedule_enabled
                ? <>Cron in UTC{cronLocal(policy.daily_backup_cron)} · last run {policy.last_scheduled_run_at ? <Ts at={policy.last_scheduled_run_at} seconds={false} /> : "never"}</>
                : "No backup runs on its own. Enable the schedule under Retention & Policies."
              : "Policy unavailable"}
          </Typography>
        </Card>
        <MetricCard title="Retention policy" value={policy ? `${policy.backup_retention_days} days` : null} state={policy ? "ok" : "unknown"}
          note={policy ? `Snapshot depth: ${policy.snapshot_retention_depth} retained` : "Policy unavailable"} />
        {/* Zero deviations found and zero comparisons run look identical and mean opposite things. */}
        <MetricCard title="Active major deviations"
          value={deviations && deviations.total_deviations_checked > 0 ? deviations.active_major_deviations.length : null}
          state={deviations && deviations.total_deviations_checked > 0 ? "ok" : "unknown"}
          reason="No comparison has run, so no deviation can be claimed or ruled out"
          note={deviations ? `${deviations.total_deviations_checked} comparison${deviations.total_deviations_checked === 1 ? "" : "s"} run` : "Deviation state unavailable"} />
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

      {/* One table per device (UI review 2026-09-23): target switch, latest archive, validation, deviation, every action. */}
      <BackupFleetTable
        version={targetsVersion}
        onTargetChanged={() => setTargetsVersion((v) => v + 1)}
        fleet={devices}
        fleetLoaded={listLoaded}
        fleetError={listError}
        baselines={baselines}
        busyDeviceId={loading ? triggeringId : null}
        onBackupNow={(d, type) => void handleBackupNow(d, type)}
        onHistory={(d) => void openHistory(d)}
        onContents={(d) => void openContents(d)}
        onCompare={(d) => void openCompare(d)}
        onDownload={(d) => { setExportError(null); setSelectedDeviceExport(d); }}
      />

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
                      {baselines[selectedDeviceDiff.deviceId] === a.artefact_id ? "★ baseline · " : ""}
                      <Ts at={a.collected_at} /> · {formatBytes(a.size_bytes)} · {a.artefact_id.slice(0, 8)}
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
                  <Ts at={compareResult.left.collected_at} /> ({formatBytes(compareResult.left.size_bytes)}) →{" "}
                  <Ts at={compareResult.right.collected_at} /> ({formatBytes(compareResult.right.size_bytes)})
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
              <Ts at={selectedDeviceExport.lastBackupTime} />
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

      {/* V44 History dialog: every backup of one device, baseline, per-artefact actions */}
      {historyFor && (
        <Dialog open={true} onClose={() => setHistoryFor(null)} maxWidth="md" fullWidth>
          <DialogTitle sx={{ fontWeight: 700 }}>Backup history: {historyFor.name}</DialogTitle>
          <DialogContent dividers>
            {historyError && <Alert severity="error" sx={{ mb: 1 }}>{historyError}</Alert>}
            {!history && !historyError && <CircularProgress size={20} />}
            {history && history.length === 0 && <Alert severity="info">No backup of this device is on record.</Alert>}
            {history && history.length > 0 && (
              <TableContainer sx={{ maxHeight: 460 }}>
                <Table size="small" stickyHeader>
                  <TableHead>
                    <TableRow>
                      <TableCell>Collected</TableCell>
                      <TableCell align="right">Size</TableCell>
                      <TableCell>Validation</TableCell>
                      <TableCell>Deviation</TableCell>
                      <TableCell>Baseline</TableCell>
                      <TableCell align="right">Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {history.map((a) => {
                      const isBaseline = baselines[historyFor.deviceId] === a.artefact_id;
                      return (
                        <TableRow key={a.artefact_id} hover selected={isBaseline}>
                          <TableCell sx={{ whiteSpace: "nowrap" }}><Ts at={a.collected_at} /></TableCell>
                          <TableCell align="right">{formatBytes(a.size_bytes)}</TableCell>
                          <TableCell>{a.validation_level || "UNKNOWN"}</TableCell>
                          <TableCell>{a.deviation_state ? a.deviation_state.toUpperCase() : "NOT EVALUATED"}</TableCell>
                          <TableCell>
                            {isBaseline ? (
                              <Chip size="small" label="★ baseline" sx={{ bgcolor: m3.primaryContainer, color: m3.onPrimaryContainer, fontWeight: 700 }} />
                            ) : (
                              <M3Button emphasis="text" disabled={baselineBusy !== null} onClick={() => void handleSetBaseline(historyFor, a.artefact_id)}>
                                Set as baseline
                              </M3Button>
                            )}
                          </TableCell>
                          <TableCell align="right">
                            <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                              <M3Button emphasis="text" onClick={() => void openContents(asItem(historyFor, a))}>Contents</M3Button>
                              <M3Button
                                emphasis="text"
                                onClick={() => {
                                  setExportError(null);
                                  setSelectedDeviceExport(asItem(historyFor, a));
                                }}
                              >
                                Download
                              </M3Button>
                            </Stack>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </DialogContent>
          <DialogActions>
            {baselines[historyFor.deviceId] && (
              <M3Button emphasis="text" disabled={baselineBusy !== null} onClick={() => void handleSetBaseline(historyFor, null)}>
                Clear baseline
              </M3Button>
            )}
            <M3Button emphasis="filled" onClick={() => setHistoryFor(null)}>
              Close
            </M3Button>
          </DialogActions>
        </Dialog>
      )}

      {/* V44 Retention & schedule policy: the real, audited row */}
      <Dialog open={policyOpen} onClose={() => !policyBusy && setPolicyOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ fontWeight: 700 }}>Backup Schedule & Retention</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            <Stack direction="row" spacing={1.5} alignItems="center">
              <Switch checked={scheduleEnabled} onChange={(e) => setScheduleEnabled(e.target.checked)} disabled={policyBusy} />
              <Typography variant="body2">
                {scheduleEnabled ? "Scheduled fleet backup is ON: every backup target runs on the cron below." : "Scheduled fleet backup is OFF: backups run only from this screen."}
              </Typography>
            </Stack>
            <TextField
              label="Fleet backup schedule (cron, UTC)"
              value={dailyCron}
              onChange={(e) => setDailyCron(e.target.value)}
              helperText={`Five fields: minute hour day month weekday, in UTC (the server's schedule clock). 0 2 * * * = 02:00 UTC = ${utcHourToLocal(2)} every day.${cronLocal(dailyCron)}`}
              disabled={policyBusy}
              fullWidth
            />
            <TextField
              label="Backup retention (days)"
              type="number"
              value={retentionDays}
              onChange={(e) => setRetentionDays(Number(e.target.value))}
              helperText="Backups older than this are pruned with append-only ledger tombstones; a device's last backup is never pruned."
              disabled={policyBusy}
              fullWidth
            />
            <TextField
              label="Snapshot retention depth"
              type="number"
              value={snapshotDepth}
              onChange={(e) => setSnapshotDepth(Number(e.target.value))}
              helperText="Gaia snapshots kept per gateway."
              disabled={policyBusy}
              fullWidth
            />
            {policyError && <Alert severity="error">{policyError}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <M3Button emphasis="text" disabled={policyBusy} onClick={() => setPolicyOpen(false)}>
            Cancel
          </M3Button>
          <M3Button emphasis="filled" disabled={policyBusy} onClick={() => void handleSavePolicy()}>
            {policyBusy ? <CircularProgress size={16} /> : "Save Policy"}
          </M3Button>
        </DialogActions>
      </Dialog>
    </ScreenRoot>
  );
}


/** The deviation word: CHANGED is a signal (attention), FIRST / UNCHANGED neutral, MAJOR a fault, NOT EVALUATED neutral. */
const DEVIATION_MEANING: Record<string, string> = {
  FIRST: "The first archive of this device: nothing earlier to compare with",
  CHANGED: "Contents differ from the previous archive of this device",
  UNCHANGED: "Contents match the previous archive of this device",
  "MAJOR DEVIATION": "A semantic comparison found a major deviation",
  "NOT EVALUATED": "No comparison has run for this archive",
};

function DeviationChip({ state }: { readonly state: string }) {
  const tone = state === "MAJOR DEVIATION" ? "bad" : state === "CHANGED" ? "attn" : "neutral";
  return (
    <Tooltip title={DEVIATION_MEANING[state] ?? state}>
      <Box component="span"><StatusChip tone={tone} label={state} dense /></Box>
    </Tooltip>
  );
}

/** The validation level is an evidence grade on the V1-V4 ladder, not a pass mark: shown as recorded, neutral. */
function ValidationChip({ level }: { readonly level: string }) {
  return (
    <Tooltip title={`Validation level ${level} as recorded on the V1–V4 ladder (evidence grade, not a pass mark)`}>
      <Box component="span"><StatusChip tone="neutral" label={level} dense /></Box>
    </Tooltip>
  );
}

type Show = "all" | "targets" | "without_archive";

/**
 * Backups (Product Owner, 2026-09-22; merged per the UI review 2026-09-23): the devices the product may back up and
 * what the store holds for each, in one table. Every enrolled gateway is listed; the switch is an audited devices
 * UPDATE; a device appears with its newest archive, or "No archive". Nothing is inferred from inventory: the archive
 * columns come from the artefact store only. The five per-device actions (Backup Now, History, Contents, Compare,
 * Download, plus Snapshot Now on Check Point) are all kept.
 */
function BackupFleetTable({ version, onTargetChanged, fleet, fleetLoaded, fleetError, baselines, busyDeviceId,
  onBackupNow, onHistory, onContents, onCompare, onDownload }: {
  readonly version: number;
  readonly onTargetChanged: () => void;
  readonly fleet: readonly BackupDeviceItem[];
  readonly fleetLoaded: boolean;
  readonly fleetError: string | null;
  readonly baselines: Record<string, string>;
  readonly busyDeviceId: string | null;
  readonly onBackupNow: (d: BackupDeviceItem, type: "standard" | "snapshot" | "mds_export") => void;
  readonly onHistory: (d: BackupDeviceItem) => void;
  readonly onContents: (d: BackupDeviceItem) => void;
  readonly onCompare: (d: BackupDeviceItem) => void;
  readonly onDownload: (d: BackupDeviceItem) => void;
}) {
  const [devices, setDevices] = useState<DeviceSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // `q` carries a filter from other screens (the cluster context strip); `artefact=none` from the Overview tile.
  const [search, setSearch] = useState(() => urlParam("q") ?? "");
  const [show, setShow] = useState<Show>(() => (urlParam("artefact") === "none" ? "without_archive" : "all"));

  useEffect(() => {
    let cancelled = false;
    listDevices()
      .then((r) => {
        if (!cancelled) {
          setDevices(r.devices.filter((d) => d.enrollment_state === "ENROLLED" && (d.role === undefined || d.role === "gateway" || d.role === "firewall"
            // a Check Point management server (MDS) takes the Gaia backup too
            || (d.role === "management_server" && d.vendor_hint === "check_point"))));
          setError(null);
        }
      })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "Devices could not be read."); });
    return () => { cancelled = true; };
  }, [version]);

  const toggle = async (device: DeviceSummary, enabled: boolean) => {
    setBusyId(device.device_id);
    try {
      await setBackupTarget(device.device_id, enabled);
      setDevices((prev) => prev?.map((d) => (d.device_id === device.device_id ? { ...d, backup_target: enabled } : d)) ?? prev);
      onTargetChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "The backup target could not be changed.");
    } finally {
      setBusyId(null);
    }
  };

  const byId = new Map(fleet.map((f) => [f.deviceId, f] as const));
  // Every enrolled gateway, plus any device the store holds an archive for that is not in that list.
  const summaries = devices ?? [];
  const known = new Set(summaries.map((d) => d.device_id));
  const rows: Array<{ summary: DeviceSummary | null; item: BackupDeviceItem | null; id: string }> = [
    ...summaries.map((d) => ({ summary: d, item: byId.get(d.device_id) ?? null, id: d.device_id })),
    ...fleet.filter((f) => !known.has(f.deviceId)).map((f) => ({ summary: null, item: f, id: f.deviceId })),
  ];
  const targetCount = summaries.filter((d) => d.backup_target).length;
  const withoutArchive = summaries.filter((d) => d.backup_target && !byId.has(d.device_id)).length;
  const q = search.trim().toLowerCase();
  const shown = rows.filter(({ summary, item }) => {
    if (show === "targets" && !summary?.backup_target) return false;
    if (show === "without_archive" && (!summary?.backup_target || item !== null)) return false;
    if (!q) return true;
    return [summary?.hostname, summary?.model, summary?.cluster_member_ref, summary?.software_version, item?.name, summary?.device_id ?? item?.deviceId]
      .some((f) => (f ?? "").toLowerCase().includes(q));
  }).sort((a, b) => (a.summary?.hostname ?? a.item?.name ?? "").localeCompare(b.summary?.hostname ?? b.item?.name ?? ""));

  const chip = (value: Show, label: string, count: number) => (
    <Chip key={value} clickable size="small" label={`${label} · ${count}`} onClick={() => setShow(value)}
      sx={{ bgcolor: show === value ? m3.primaryContainer : m3.scLow, color: show === value ? m3.onPrimaryContainer : m3.onSurface,
            fontWeight: show === value ? 600 : 400, border: `1px solid ${m3.outlineVar}` }} />
  );

  return (
    <Card sx={{ borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none", overflow: "hidden" }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1.5, p: 2, pb: 1.5 }}>
        <Box>
          <Typography sx={{ fontSize: 16, fontWeight: 600, color: m3.onSurface }}>Backup fleet · {targetCount} targets</Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            Only devices switched on as a backup target are ever backed up; each change is audited. Archive columns come from the artefact store.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Show:</Typography>
          {chip("all", "All enrolled", summaries.length)}
          {chip("targets", "Targets", targetCount)}
          {chip("without_archive", "Targets without archive", withoutArchive)}
          <TextField size="small" placeholder="Filter devices, clusters, models…" value={search} onChange={(e) => setSearch(e.target.value)}
            inputProps={{ "aria-label": "Filter the backup fleet" }} />
        </Stack>
      </Box>
      {(error || fleetError) && <Alert severity="warning" sx={{ mx: 2, mb: 1.5 }}>{error ?? fleetError}</Alert>}
      {devices === null && !error ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, px: 2, pb: 2 }}>Reading the devices…</Typography>
      ) : shown.length === 0 ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, px: 2, pb: 2 }}>
          {rows.length === 0
            ? (fleetLoaded ? "No enrolled gateway and no stored archive yet. Nothing here is inferred from inventory." : "Reading the store…")
            : "No device matches the filter."}
        </Typography>
      ) : (
        <TableContainer sx={{ maxHeight: 640 }}>
          <Table size="small" stickyHeader sx={{ "& td, & th": { px: 1 }, "& td:first-of-type, & th:first-of-type": { pl: 2 }, "& td:last-of-type, & th:last-of-type": { pr: 2 } }}>
            <TableHead>
              <TableRow>
                <TableCell>Device</TableCell>
                <TableCell>Model · version</TableCell>
                <TableCell>Cluster</TableCell>
                <TableCell align="center">Target</TableCell>
                <TableCell>Latest archive</TableCell>
                <TableCell align="right">Size</TableCell>
                <TableCell>Validation</TableCell>
                <TableCell>Deviation</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {shown.map(({ summary, item, id }) => {
                const name = summary?.hostname ?? item?.name ?? id.slice(0, 8);
                const vendor = summary?.vendor_hint ?? item?.vendor;
                const busy = busyDeviceId === id;
                return (
                  <TableRow key={id} hover>
                    <TableCell>
                      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                        <VendorBadge vendor={vendor} size={24} />
                        <Typography sx={{ fontFamily: MONO, fontSize: 12.5, fontWeight: 600, whiteSpace: "nowrap" }}>{name}</Typography>
                      </Box>
                    </TableCell>
                    <TableCell sx={{ color: m3.onSurfaceVar, fontSize: 12, lineHeight: 1.3 }}>
                      {summary ? <><Box component="span" sx={{ whiteSpace: "nowrap" }}>{(summary.model ?? "UNKNOWN").replace(/^Check Point\s+/, "")}</Box><br />{summary.software_version ?? ""}</> : "UNKNOWN"}
                    </TableCell>
                    <TableCell sx={{ fontFamily: MONO, fontSize: 12, color: m3.onSurfaceVar, whiteSpace: "nowrap" }}>{summary?.cluster_member_ref ?? "standalone"}</TableCell>
                    <TableCell align="center">
                      {summary ? (
                        <Switch size="small" checked={Boolean(summary.backup_target)} disabled={busyId === id}
                          onChange={(e) => void toggle(summary, e.target.checked)}
                          inputProps={{ "aria-label": `Backup target ${name}` }} />
                      ) : <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>not enrolled</Typography>}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12.5, whiteSpace: "nowrap" }}>
                      {item ? <Ts at={item.lastBackupTime} relative />
                        : summary?.backup_target ? <StatusChip tone="warn" label="No archive" dense />
                        : <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>No archive</Typography>}
                    </TableCell>
                    <TableCell align="right" sx={{ fontSize: 12.5, whiteSpace: "nowrap" }}>{item ? formatBytes(item.sizeBytes) : ""}</TableCell>
                    <TableCell>{item ? <ValidationChip level={item.validationLevel} /> : null}</TableCell>
                    <TableCell>{item ? <DeviationChip state={item.deviationState} /> : null}</TableCell>
                    <TableCell align="right">
                      {/* Compact actions: all six stay visible in the row (the full-size buttons clipped Compare and Download). */}
                      <Stack direction="row" spacing={0} justifyContent="flex-end" alignItems="center" sx={{ whiteSpace: "nowrap",
                        "& .MuiButton-root": { minWidth: 0, px: 0.9, py: 0.25, fontSize: 12.5, textTransform: "none", borderRadius: "6px" } }}>
                        {item || summary?.backup_target ? (
                          <Tooltip title="Take a configuration and state backup of this device now (needs it switched on as a backup target)"><span><Button size="small" variant="contained" disableElevation disabled={busy || !summary?.backup_target}
                            sx={{ bgcolor: m3.secondaryContainer, color: m3.onSecondaryContainer, "&:hover": { bgcolor: m3.secondaryContainer }, mr: 0.5 }}
                            onClick={() => onBackupNow(item ?? { deviceId: id, name, ip: "", vendor: vendor ?? "unknown", role: "", lastBackupTime: "", backupType: "standard", validationLevel: "UNKNOWN", deviationState: "NOT EVALUATED", sizeBytes: 0, artefactId: "" }, "standard")}>
                            {busy ? <CircularProgress size={12} /> : "Backup Now"}
                          </Button></span></Tooltip>
                        ) : null}
                        {vendor === "check_point" && summary?.role === "management_server" && summary?.backup_target ? (
                          <Tooltip title="Multi-Domain Server export: one mds_backup of the whole server and every domain. Locks the MDS database while it runs; scheduled at night."><span><Button size="small" disabled={busy}
                            onClick={() => onBackupNow(item ?? { deviceId: id, name, ip: "", vendor: vendor ?? "unknown", role: "management_server", lastBackupTime: "", backupType: "standard", validationLevel: "UNKNOWN", deviationState: "NOT EVALUATED", sizeBytes: 0, artefactId: "" }, "mds_export")}>MDS export</Button></span></Tooltip>
                        ) : null}
                        {vendor === "check_point" && item ? (
                          <Tooltip title="Check Point only: a full Gaia OS snapshot, in addition to the configuration backup. Palo Alto has no equivalent here."><span><Button size="small" disabled={busy} onClick={() => onBackupNow(item, "snapshot")}>Snapshot</Button></span></Tooltip>
                        ) : null}
                        <Tooltip title={baselines[id] ? "Every backup of this device; ★ = a baseline archive is set for comparison" : "Every backup of this device"}><span><Button size="small" disabled={!item} onClick={() => item && onHistory(item)}>History{baselines[id] ? " ★" : ""}</Button></span></Tooltip>
                        <Button size="small" disabled={!item?.artefactId} onClick={() => item && onContents(item)}>Contents</Button>
                        <Button size="small" disabled={!item?.artefactId} onClick={() => item && onCompare(item)}>Compare</Button>
                        <Button size="small" disabled={!item?.artefactId} onClick={() => item && onDownload(item)}>Download</Button>
                      </Stack>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Card>
  );
}
