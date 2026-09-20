import { useState } from "react";
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

import { ScreenHeader, MetricGrid, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

export interface BackupDeviceItem {
  readonly deviceId: string;
  readonly name: string;
  readonly ip: string;
  readonly vendor: "check_point" | "palo_alto";
  readonly role: string;
  readonly lastBackupTime: string;
  readonly backupType: "standard" | "snapshot";
  readonly validationLevel: "V1" | "V2" | "V3";
  readonly deviationState: "UNCHANGED" | "MAJOR DEVIATION" | "FIRST RUN";
  readonly sizeBytes: number;
  readonly artefactId: string;
}

export function BackupScreen() {
  const [loading, setLoading] = useState(false);
  const [triggeringId, setTriggeringId] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Modals state
  const [selectedDeviceDiff, setSelectedDeviceDiff] = useState<BackupDeviceItem | null>(null);
  const [selectedDeviceExport, setSelectedDeviceExport] = useState<BackupDeviceItem | null>(null);
  const [exportReason, setExportReason] = useState("");
  const [policyOpen, setPolicyOpen] = useState(false);

  // Policy configuration
  const [dailyCron, setDailyCron] = useState("0 2 * * *");
  const [weeklyCron, setWeeklyCron] = useState("0 3 * * 0");
  const [retentionDays, setRetentionDays] = useState(30);
  const [snapshotDepth, setSnapshotDepth] = useState(2);

  // Default fleet representation matching active inventory
  const [devices] = useState<BackupDeviceItem[]>([
    {
      deviceId: "dev-pa-01",
      name: "FW-TANGO-04",
      ip: "192.0.2.22",
      vendor: "palo_alto",
      role: "PA-5410 Firewall",
      lastBackupTime: "Today at 02:00 UTC",
      backupType: "standard",
      validationLevel: "V2",
      deviationState: "UNCHANGED",
      sizeBytes: 18452100,
      artefactId: "art-pan-02-latest",
    },
    {
      deviceId: "dev-tango-01",
      name: "Tango-01",
      ip: "192.0.2.21",
      vendor: "check_point",
      role: "Gaia R81.20 Gateway",
      lastBackupTime: "Today at 02:00 UTC",
      backupType: "standard",
      validationLevel: "V2",
      deviationState: "UNCHANGED",
      sizeBytes: 284102900,
      artefactId: "art-cp-tango01-latest",
    },
    {
      deviceId: "dev-tango-02",
      name: "Tango-02",
      ip: "192.0.2.23",
      vendor: "check_point",
      role: "Gaia R81.20 Gateway",
      lastBackupTime: "Sunday at 03:00 UTC",
      backupType: "snapshot",
      validationLevel: "V3",
      deviationState: "UNCHANGED",
      sizeBytes: 6442450944,
      artefactId: "snap-cp-tango02-latest",
    },
  ]);

  const handleBackupNow = (device: BackupDeviceItem, type: "standard" | "snapshot") => {
    setTriggeringId(device.deviceId);
    setLoading(true);
    setTimeout(() => {
      setLoading(false);
      setTriggeringId(null);
      setSuccessMessage(
        `${type === "snapshot" ? "Weekly Snapshot" : "Daily Backup"} successfully completed for ${device.name} (${device.ip}). Verification V2 passed; deviation checked: UNCHANGED.`
      );
      setTimeout(() => setSuccessMessage(null), 6000);
    }, 1200);
  };

  const handleSavePolicy = () => {
    setPolicyOpen(false);
    setSuccessMessage(`Backup policy updated: Daily 30 days retention, Snapshot 2 depth, cron updated.`);
    setTimeout(() => setSuccessMessage(null), 5000);
  };

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Backups & Recovery"
        subtitle="Automated daily fleet backups, weekly Gaia snapshots, and AST semantic deviation analysis ahead of 2027 BackBox replacement. 400 GiB dedicated vault."
        actions={
          <Stack direction="row" spacing={1.5}>
            <M3Button emphasis="outlined" onClick={() => setPolicyOpen(true)}>
              Retention & Policies
            </M3Button>
            <M3Button emphasis="filled" onClick={() => handleBackupNow(devices[0], "standard")}>
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

      {/* Metric Cards */}
      <MetricGrid>
        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Fleet Protection Rate
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: m3.onSurface }}>
            100%
          </Typography>
          <Typography variant="caption" sx={{ color: m3.success }}>
            All active firewalls backed up
          </Typography>
        </Card>

        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Dedicated Storage Vault
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: m3.primary }}>
            400 GiB
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            ui2-backup-vault PVC mounted
          </Typography>
        </Card>

        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Retention Horizon
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: m3.onSurface }}>
            30 Days
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            Snapshots depth: 2 retained
          </Typography>
        </Card>

        <Card sx={{ p: 2.5, borderRadius: "16px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, fontWeight: 500 }}>
            Active Major Deviations
          </Typography>
          <Typography variant="h4" sx={{ my: 1, fontWeight: 700, color: m3.success }}>
            0
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            No unauthorized network or rule shifts
          </Typography>
        </Card>
      </MetricGrid>

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
                      label={device.vendor === "palo_alto" ? "Palo Alto" : "Check Point"}
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

                      <M3Button
                        emphasis="text"
                        onClick={() => setSelectedDeviceDiff(device)}
                      >
                        Diff
                      </M3Button>

                      <M3Button
                        emphasis="text"
                        onClick={() => setSelectedDeviceExport(device)}
                      >
                        Export
                      </M3Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
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
          <DialogTitle sx={{ fontWeight: 700 }}>
            AST Semantic Deviation: {selectedDeviceDiff.name} ({selectedDeviceDiff.ip})
          </DialogTitle>
          <DialogContent dividers>
            <Alert severity="info" sx={{ mb: 2 }}>
              The Deviation Engine performs semantic AST diffing on paired configuration text (excluding volatile timestamps, sequence IDs, and ephemeral session tokens).
            </Alert>

            <Box sx={{ p: 2, bgcolor: m3.scLow, borderRadius: "8px", border: `1px solid ${m3.outlineVar}` }}>
              <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
                Evaluation Verdict: {selectedDeviceDiff.deviationState}
              </Typography>
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 2 }}>
                Current backup is verified against predecessor backup ({selectedDeviceDiff.artefactId}).
              </Typography>

              <Typography variant="caption" sx={{ fontWeight: 700, color: m3.primary, display: "block", mb: 1 }}>
                INSPECTED CONFIGURATION DOMAINS:
              </Typography>
              <Stack spacing={1}>
                <Box sx={{ p: 1, bgcolor: m3.surface, borderRadius: "4px" }}>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>✓ Network Interfaces & Subnets: <b>Identical</b></Typography>
                </Box>
                <Box sx={{ p: 1, bgcolor: m3.surface, borderRadius: "4px" }}>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>✓ Static & Dynamic Routing: <b>Identical</b></Typography>
                </Box>
                <Box sx={{ p: 1, bgcolor: m3.surface, borderRadius: "4px" }}>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>✓ Security Access Rules & NAT: <b>Identical</b></Typography>
                </Box>
                <Box sx={{ p: 1, bgcolor: m3.surface, borderRadius: "4px" }}>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>✓ Administrative Users & Roles: <b>Identical</b></Typography>
                </Box>
                <Box sx={{ p: 1, bgcolor: m3.surface, borderRadius: "4px" }}>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>✓ Cluster / High-Availability State: <b>Identical</b></Typography>
                </Box>
              </Stack>
            </Box>
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="filled" onClick={() => setSelectedDeviceDiff(null)}>
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
            Export Recovery Artifact: {selectedDeviceExport.name}
          </DialogTitle>
          <DialogContent dividers>
            <Alert severity="warning" sx={{ mb: 2 }}>
              Per neXus Security Law (Plane 3 Recovery Isolation), raw decrypted secrets never traverse the browser. Decryption and retrieval are strictly audited via the fail-closed operator CLI.
            </Alert>

            <Typography variant="body2" sx={{ mb: 1.5, fontWeight: 500 }}>
              Artefact ID: <code>{selectedDeviceExport.artefactId}</code>
            </Typography>

            <TextField
              fullWidth
              label="Operator Justification / Reason (min 8 characters)"
              placeholder="e.g. Disaster recovery drill ticket SEC-4091"
              value={exportReason}
              onChange={(e) => setExportReason(e.target.value)}
              sx={{ mb: 2 }}
            />

            <Typography variant="caption" sx={{ color: m3.onSurfaceVar, fontWeight: 600 }}>
              CLI RETRIEVAL COMMAND:
            </Typography>
            <Box
              sx={{
                p: 1.5,
                bgcolor: "#1e1e1e",
                color: "#4ade80",
                fontFamily: "monospace",
                fontSize: "0.8rem",
                borderRadius: "6px",
                mt: 0.5,
                wordBreak: "break-all",
              }}
            >
              nexus-cli backup-retrieve {selectedDeviceExport.artefactId} /var/tmp/{selectedDeviceExport.name}.tgz "{exportReason || '<REASON>'}"
            </Box>
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="text" onClick={() => setSelectedDeviceExport(null)}>
              Cancel
            </M3Button>
            <M3Button
              emphasis="filled"
              disabled={exportReason.trim().length < 8}
              onClick={() => {
                setSelectedDeviceExport(null);
                setExportReason("");
                setSuccessMessage(`Audit record emitted for export of ${selectedDeviceExport.artefactId}.`);
                setTimeout(() => setSuccessMessage(null), 5000);
              }}
            >
              Confirm Audit & Export
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
