import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  cancelSupportBundle, cleanupSupportBundleWorkDir, deleteSupportBundle, getSupportBundleCatalogue,
  getSupportBundleUploadDestinations, listDevices, listSupportBundleUploads, listSupportBundles, requestSupportBundle,
  requestSupportBundleDownloadTicket, startSupportBundleUpload, type DeviceSummary, type SupportBundleKindRow,
  type SupportBundleRequest, type SupportBundleUpload, type SupportBundleUploadDestination,
} from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { StatePanel } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import { formatStamp } from "../shell/time";

/**
 * Support bundles (docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md, FROZEN 2026-11-15), under Tools. An
 * administrator picks a device and the items a vendor's support team usually asks for, runs them now or at a chosen time,
 * and later downloads the result, or sends it to a vendor support case. The server decides every check; the screen only shows its
 * answer. A bundle cannot be masked (the vendor's tools need it intact); it is deleted after a confirmed download, or after 14
 * days with a warning first. An upload credential is typed per upload, held by the service for the transfer only, and never shown again.
 */
const KIND_LABELS: Record<string, string> = {
  cpdata_collector_present: "cpdata_collector installed? (read only, never run)",
  cp_gaia_extras: "Gaia details (versions, assets, HA state, CPU, disk)",
  cp_hcp_latest: "Latest HCP health report",
  cp_hcp_run: "Run HCP now (replaces the device's own latest HCP report)",
  cp_cpinfo: "cpinfo (high load, can take up to two hours)",
  cpdata_collector_run: "cpdata_collector run (optional, only if installed)",
  pan_tsf: "Tech support file (management plane, can take up to 30 minutes)",
  asa_crashinfo: "Crash information (show crashinfo)",
  asa_show_tech: "Technical support report (show tech-support, long)",
  fgt_crashlog: "Crash log (diagnose debug crashlog read)",
  fgt_tac_report: "TAC report (execute tac report, long)",
};
/** Vendors with at least one collectable item (design 5.2). */
const BUNDLE_VENDORS = new Set(["check_point", "palo_alto", "cisco_asa", "fortinet"]);
/** A management server is collected for Palo Alto (Panorama) and Check Point (SMS and MDS, heavy items only); a FortiManager is not. */
export const bundleDevice = (d: { vendor_hint: string; role?: string }): boolean =>
  BUNDLE_VENDORS.has(d.vendor_hint) && (d.role !== "management_server" || d.vendor_hint === "palo_alto" || d.vendor_hint === "check_point");
/** PO 2026-11-15 (Q5): HCP and cpinfo are included for an MDS, with a clear warning and no block. */
export const MDS_WARNING = "This is a Check Point management server or Multi-Domain Server. HCP and cpinfo are heavy on it, and a "
  + "Multi-Domain Server has crashed under a heavy backup before. They are not blocked. Run them outside business hours and watch the server.";
const LOAD_NOTE: Record<string, string> = { none: "no device load", low: "light", medium: "medium load", high: "high load", unknown: "load not measured" };
/** Times neXus itself works on devices (design 5.7): a hint when the picked hour collides, never a refusal. */
export function collisionHint(local: string): string | null {
  const hour = Number(local.slice(11, 13));
  if (hour === 2) return "02:00-02:59: scheduled backups and the MDS export run. 00:00 or 04:00 is quieter.";
  if (hour === 3) return "About 03:00: the device's own HCP run. 00:00 or 04:00 is quieter.";
  if (hour === 6) return "06:00: the system-log HCP copy runs. 00:00 or 04:00 is quieter.";
  return null;
}
const sizeText = (bytes: number | null) => (bytes === null ? "" : bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`);

export function SupportBundlesScreen() {
  const [rows, setRows] = useState<readonly SupportBundleRequest[] | null>(null);
  const [kinds, setKinds] = useState<readonly SupportBundleKindRow[]>([]);
  const [devices, setDevices] = useState<readonly DeviceSummary[]>([]);
  const [error, setError] = useState("");
  const reload = useCallback(() => {
    listSupportBundles().then((r) => { setRows(r.support_bundles); setError(""); })
      .catch(() => { setRows([]); setError("The support bundle list could not be read."); });
  }, []);
  useEffect(() => {
    reload();
    getSupportBundleCatalogue().then((r) => setKinds(r.kinds)).catch(() => undefined);
    listDevices().then((r) => setDevices(r.devices)).catch(() => undefined);
  }, [reload]);
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Support bundles"
        subtitle="Collect the files a vendor's support team usually asks for, then download them or send them to a vendor case. Nothing leaves neXus unless you start an upload." />
      {error && <Alert severity="error" data-fact="bundle-error">{error}</Alert>}
      <NewRequest kinds={kinds} devices={devices} onDone={reload} />
      <Requests rows={rows} onChange={reload} />
    </ScreenRoot>
  );
}

function NewRequest({ kinds, devices, onDone }: {
  readonly kinds: readonly SupportBundleKindRow[]; readonly devices: readonly DeviceSummary[]; readonly onDone: () => void;
}) {
  const [deviceId, setDeviceId] = useState("");
  const [ticked, setTicked] = useState<ReadonlySet<string> | null>(null);
  const [when, setWhen] = useState("");
  const [caseRef, setCaseRef] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false), [message, setMessage] = useState(""), [failure, setFailure] = useState("");
  const device = devices.find((d) => d.device_id === deviceId);
  const vendor = device?.vendor_hint ?? "";
  const cpManagement = vendor === "check_point" && device?.role === "management_server";
  const forVendor = kinds.filter((k) => k.vendor === vendor && (!cpManagement || k.check_point_management));
  const selected = ticked ?? new Set(forVendor.filter((k) => k.enabled && k.default_ticked).map((k) => k.kind));
  const toggle = (kind: string) => { const next = new Set(selected); if (next.has(kind)) next.delete(kind); else next.add(kind); setTicked(next); };
  const hint = when ? collisionHint(when) : null;
  const submit = async () => {
    setBusy(true); setFailure(""); setMessage("");
    try {
      const accepted = await requestSupportBundle(deviceId, {
        kinds: [...selected], reason: reason.trim(),
        ...(when ? { run_at: new Date(when).toISOString() } : {}), ...(caseRef.trim() ? { case_ref: caseRef.trim() } : {}),
      });
      setMessage((when ? "Scheduled. It starts at the chosen time." : "Started. It appears in the list below.")
        + (accepted?.mds_warning ? ` ${accepted.warning ?? MDS_WARNING}` : ""));
      setReason(""); onDone();
    } catch (e) {
      const body = (e as { body?: { reason?: string; error?: string } }).body;
      setFailure(body?.reason ?? body?.error ?? "The request was not accepted.");
    } finally { setBusy(false); }
  };
  return <Box component="form" aria-label="New support bundle" onSubmit={(e) => { e.preventDefault(); void submit(); }}
    sx={{ display: "flex", flexDirection: "column", gap: 2, maxWidth: 760, my: 2 }}>
    <Typography variant="subtitle1" component="h2">New support bundle</Typography>
    {failure && <Alert severity="error" data-fact="request-error">{failure}</Alert>}
    {message && <Alert severity="success" data-fact="request-ok">{message}</Alert>}
    <TextField select label="Device" value={deviceId} onChange={(e) => { setDeviceId(e.target.value); setTicked(null); }} disabled={busy}>
      {devices.filter(bundleDevice).map((d) => <MenuItem key={d.device_id} value={d.device_id}>{d.hostname}</MenuItem>)}
    </TextField>
    {cpManagement && <Alert severity="warning" data-fact="mds-warning">{MDS_WARNING}</Alert>}
    {device && !BUNDLE_VENDORS.has(vendor) && <Alert severity="info">Support bundles for this vendor are not built yet.</Alert>}
    {forVendor.length > 0 && <Box role="group" aria-label="Items">
      {forVendor.map((k) => <Box key={k.kind}><FormControlLabel disabled={!k.enabled || busy}
        control={<Checkbox checked={selected.has(k.kind) && k.enabled} onChange={() => toggle(k.kind)} />}
        label={`${KIND_LABELS[k.kind] ?? k.kind} (${LOAD_NOTE[k.load] ?? k.load})${k.enabled ? "" : " - not available yet"}`} /></Box>)}
    </Box>}
    <TextField label="Run at (empty: now)" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} disabled={busy}
      InputLabelProps={{ shrink: true }} helperText="Any time in the next 7 days. Inside business hours (08:00-20:00) you are asked to confirm." />
    {hint && <Alert severity="info" data-fact="collision-hint">{hint}</Alert>}
    <TextField label="Vendor case reference (optional)" value={caseRef} onChange={(e) => setCaseRef(e.target.value)} disabled={busy}
      inputProps={{ maxLength: 40 }} />
    <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} required disabled={busy}
      inputProps={{ maxLength: 500 }} helperText="At least 8 characters. Kept in the audit trail." />
    <Box><Button type="submit" variant="contained" disabled={busy || !deviceId || selected.size === 0 || reason.trim().length < 8}>Collect</Button></Box>
  </Box>;
}

function Requests({ rows, onChange }: { readonly rows: readonly SupportBundleRequest[] | null; readonly onChange: () => void }) {
  const [downloading, setDownloading] = useState<SupportBundleRequest | null>(null);
  const [afterDownload, setAfterDownload] = useState<SupportBundleRequest | null>(null);
  const [deleting, setDeleting] = useState<SupportBundleRequest | null>(null);
  const [uploading, setUploading] = useState<SupportBundleRequest | null>(null);
  const [cleaning, setCleaning] = useState<SupportBundleRequest | null>(null);
  if (rows === null) return <StatePanel variant="empty" title="Reading the support bundles…" announce="status" />;
  if (rows.length === 0) return <StatePanel variant="empty" title="No support bundle yet" body="Collect one with the form above." />;
  return <Box sx={{ overflowX: "auto" }}>
    <Table size="small" aria-label="Support bundles">
      <TableHead><TableRow><TableCell>Device</TableCell><TableCell>State</TableCell><TableCell>Items</TableCell><TableCell>Requested</TableCell><TableCell>Expires</TableCell><TableCell>Actions</TableCell></TableRow></TableHead>
      <TableBody>{rows.map((r) => <TableRow key={r.id} data-bundle={r.id}>
        <TableCell>{r.device_pseudonym}</TableCell>
        <TableCell><StatusPill tone={r.state === "COMPLETED" ? "ok" : r.state === "FAILED" ? "bad" : "neutral"} word={r.state} /></TableCell>
        <TableCell>{r.items.map((i) => <Box key={`${i.kind}-${i.ordinal}`} component="div" data-item={i.kind}>
          {KIND_LABELS[i.kind] ?? i.kind}: {i.state}{i.outcome_code && i.outcome_code !== "COMPLETED" ? ` (${i.outcome_code})` : ""}
          {i.size_bytes !== null ? ` ${sizeText(i.size_bytes)}` : ""}{i.digest_prefix ? ` ${i.digest_prefix}` : ""}</Box>)}</TableCell>
        <TableCell>{formatStamp(r.requested_at)}
          {r.work_dir_pending && <Typography variant="caption" component="div" color="warning.main" data-fact="work-dir-pending">
            A work directory from this run is still on the device{r.cleanup_state === "REQUESTED" ? " (removal requested)" : r.cleanup_state === "FAILED" ? ` (last removal failed: ${r.cleanup_outcome ?? "unknown"})` : ""}.</Typography>}</TableCell>
        <TableCell>{formatStamp(r.expires_at)}{r.expiry_warning && <Typography variant="caption" color="warning.main" component="div" data-fact="expiry-warning">
          Will be deleted at this time{r.downloaded_at ? "" : " and has not been downloaded"}.</Typography>}</TableCell>
        <TableCell><Stack direction="row" gap={1}>
          {(r.state === "COMPLETED" || r.state === "PARTIAL") && <Button size="small" onClick={() => setDownloading(r)}>Download</Button>}
          {(r.state === "COMPLETED" || r.state === "PARTIAL") && <Button size="small" onClick={() => setUploading(r)}>Upload</Button>}
          {r.work_dir_pending && r.cleanup_state !== "REQUESTED" && !["QUEUED", "RUNNING"].includes(r.state)
            && <Button size="small" color="warning" onClick={() => setCleaning(r)}>Remove leftover work directory</Button>}
          {r.state === "QUEUED" && <Button size="small" onClick={() => { void cancelSupportBundle(r.id, "Cancelled from the support bundles screen").then(onChange); }}>Cancel</Button>}
          {["COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "EXPIRED"].includes(r.state) && <Button size="small" color="warning" onClick={() => setDeleting(r)}>Delete</Button>}
        </Stack></TableCell>
      </TableRow>)}</TableBody>
    </Table>
    {downloading && <DownloadDialog request={downloading} onClose={() => setDownloading(null)}
      onStarted={() => { setAfterDownload(downloading); setDownloading(null); onChange(); }} />}
    {uploading && <UploadDialog request={uploading} onClose={() => setUploading(null)} />}
    {cleaning && <CleanupDialog request={cleaning} onClose={() => setCleaning(null)} onDone={() => { setCleaning(null); onChange(); }} />}
    {afterDownload && <DeleteDialog request={afterDownload} afterDownload onClose={() => setAfterDownload(null)} onDone={() => { setAfterDownload(null); onChange(); }} />}
    {deleting && <DeleteDialog request={deleting} onClose={() => setDeleting(null)} onDone={() => { setDeleting(null); onChange(); }} />}
  </Box>;
}

function DownloadDialog({ request, onClose, onStarted }: {
  readonly request: SupportBundleRequest; readonly onClose: () => void; readonly onStarted: () => void;
}) {
  const [reason, setReason] = useState(""), [sanitized, setSanitized] = useState(false), [failure, setFailure] = useState(""), [busy, setBusy] = useState(false);
  const go = async () => {
    setBusy(true); setFailure("");
    try {
      const { href } = await requestSupportBundleDownloadTicket(request.id, reason.trim(), sanitized);
      window.location.assign(href);
      onStarted();
    } catch (e) {
      const body = (e as { body?: { reason?: string } }).body;
      setFailure(body?.reason ?? "The download could not be started."); setBusy(false);
    }
  };
  return <Dialog open onClose={onClose} aria-labelledby="sb-dl-title">
    <DialogTitle id="sb-dl-title">Download support bundle</DialogTitle>
    <DialogContent>
      <Alert severity="warning" sx={{ mb: 2 }}>The bundle carries configuration, names, addresses and may carry secrets. It cannot be masked. The copy is yours to protect.</Alert>
      {failure && <Alert severity="error" data-fact="download-error" sx={{ mb: 2 }}>{failure}</Alert>}
      <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required disabled={busy} helperText="At least 8 characters. Audited." />
      <FormControlLabel control={<Checkbox checked={sanitized} onChange={(e) => setSanitized(e.target.checked)} />} label="Sanitized text-only export (binary items are left out)" />
    </DialogContent>
    <DialogActions><Button onClick={onClose} disabled={busy}>Cancel</Button>
      <Button variant="contained" onClick={() => void go()} disabled={busy || reason.trim().length < 8}>Download</Button></DialogActions>
  </Dialog>;
}

/** PO 2026-11-15 (Q2): once downloaded, the bundle is deleted after a confirmation. Also the early delete. */
function DeleteDialog({ request, afterDownload, onClose, onDone }: {
  readonly request: SupportBundleRequest; readonly afterDownload?: boolean; readonly onClose: () => void; readonly onDone: () => void;
}) {
  const [failure, setFailure] = useState(""), [busy, setBusy] = useState(false);
  const go = async () => {
    setBusy(true);
    try { await deleteSupportBundle(request.id, afterDownload ? "Deleted after download from the support bundles screen" : "Deleted from the support bundles screen"); onDone(); }
    catch (e) { setFailure(((e as { body?: { reason?: string } }).body?.reason) ?? "The bundle could not be deleted."); setBusy(false); }
  };
  return <Dialog open onClose={onClose} aria-labelledby="sb-del-title">
    <DialogTitle id="sb-del-title">{afterDownload ? "Delete the bundle now?" : "Delete this support bundle?"}</DialogTitle>
    <DialogContent>
      {failure && <Alert severity="error" data-fact="delete-error">{failure}</Alert>}
      <Typography>{afterDownload ? "Your download has started. Delete the stored copy now? This cannot be undone."
        : "The stored copy is removed. This cannot be undone."}</Typography>
    </DialogContent>
    <DialogActions><Button onClick={onClose} disabled={busy}>{afterDownload ? "Keep it" : "Cancel"}</Button>
      <Button color="warning" variant="contained" onClick={() => void go()} disabled={busy}>Delete</Button></DialogActions>
  </Dialog>;
}

/** PO 2026-11-16: the admin-confirmed removal of a work directory a run left on the device (the one approved remove command, the recorded name only). */
function CleanupDialog({ request, onClose, onDone }: {
  readonly request: SupportBundleRequest; readonly onClose: () => void; readonly onDone: () => void;
}) {
  const [reason, setReason] = useState(""), [confirmed, setConfirmed] = useState(false), [failure, setFailure] = useState(""), [busy, setBusy] = useState(false);
  const go = async () => {
    setBusy(true); setFailure("");
    try { await cleanupSupportBundleWorkDir(request.id, reason.trim()); onDone(); }
    catch (e) { setFailure(((e as { body?: { reason?: string } }).body?.reason) ?? "The removal was not accepted."); setBusy(false); }
  };
  return <Dialog open onClose={onClose} aria-labelledby="sb-clean-title">
    <DialogTitle id="sb-clean-title">Remove the leftover work directory</DialogTitle>
    <DialogContent>
      <Alert severity="warning" sx={{ mb: 2 }}>neXus connects to {request.device_pseudonym} and removes the one work directory (and its archive) that this run
        created and recorded. It is the same removal the run itself performs; nothing else on the device is touched.</Alert>
      {failure && <Alert severity="error" data-fact="cleanup-error" sx={{ mb: 2 }}>{failure}</Alert>}
      <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required disabled={busy} helperText="At least 8 characters. Audited." />
      <FormControlLabel control={<Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />}
        label="I confirm that the leftover work directory of this run may be removed from the device" />
    </DialogContent>
    <DialogActions><Button onClick={onClose} disabled={busy}>Cancel</Button>
      <Button color="warning" variant="contained" onClick={() => void go()} disabled={busy || !confirmed || reason.trim().length < 8}>Remove</Button></DialogActions>
  </Dialog>;
}

/** What each destination asks for, in the vendor's own words (design S8 research; the credential differs per vendor). */
const UPLOAD_FIELDS: Record<string, { readonly user: string; readonly secret: string; readonly help: string }> = {
  cisco_cxd_https: { user: "Case (SR) number", secret: "Upload token", help: "Generate the token in Support Case Manager: open the case, Attachments, Generate Token." },
  cisco_cxd_sftp: { user: "Case (SR) number", secret: "Upload token", help: "Generate the token in Support Case Manager: open the case, Attachments, Generate Token." },
  cisco_cxd_scp: { user: "Case (SR) number", secret: "Upload token", help: "Generate the token in Support Case Manager: open the case, Attachments, Generate Token." },
  fortinet_sftp: { user: "Ticket SFTP user (U_...)", secret: "Ticket SFTP password", help: "Shown in the ticket under SFTP Info. The Fortinet engineer must enable SFTP for the ticket; the password changes each time it is shown." },
  paloalto_scp: { user: "Case number (with leading zeros)", secret: "Case contact email", help: "The email address the case was opened under is the SCP password." },
  generic_sftp: { user: "User", secret: "Password", help: "Any SFTP server you are allowed to send this to. The host and port are audited." },
};
const EGRESS_NOTE = "The upload starts from the neXus host. If the connection fails, the bank firewall may need an opening for the destination.";
const uploadSize = (u: SupportBundleUpload) => u.bytes_total ? `${sizeText(u.bytes_sent)} of ${sizeText(u.bytes_total)}` : sizeText(u.bytes_sent);

function UploadDialog({ request, onClose }: { readonly request: SupportBundleRequest; readonly onClose: () => void }) {
  const [destinations, setDestinations] = useState<readonly SupportBundleUploadDestination[]>([]);
  const [manualOnly, setManualOnly] = useState<readonly string[]>([]);
  const [uploads, setUploads] = useState<readonly SupportBundleUpload[]>([]);
  const [destination, setDestination] = useState("");
  const [user, setUser] = useState(""), [secret, setSecret] = useState(""), [host, setHost] = useState(""), [port, setPort] = useState("22");
  const [caseRef, setCaseRef] = useState(request.case_ref ?? ""), [reason, setReason] = useState("");
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set(request.items.filter((i) => i.size_bytes !== null).map((i) => i.kind)));
  const [confirmed, setConfirmed] = useState(false), [busy, setBusy] = useState(false), [failure, setFailure] = useState("");
  const manual = manualOnly.includes(request.vendor);
  const options = destinations.filter((d) => d.generic || (d.vendor === request.vendor));
  const dest = destinations.find((d) => d.id === destination);
  const fields = UPLOAD_FIELDS[destination];
  const stored = request.items.filter((i) => i.size_bytes !== null);
  const refresh = useCallback(() => listSupportBundleUploads(request.id).then((r) => setUploads(r.uploads)).catch(() => undefined), [request.id]);
  useEffect(() => {
    getSupportBundleUploadDestinations().then((r) => { setDestinations(r.destinations); setManualOnly(r.manual_only_vendors); }).catch(() => setFailure("The upload destinations could not be read."));
    void refresh();
  }, [refresh]);
  const running = uploads.some((u) => u.state === "RUNNING");
  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setInterval(() => { void refresh(); }, 2000);
    return () => window.clearInterval(timer);
  }, [running, refresh]);
  const go = async () => {
    setBusy(true); setFailure("");
    try {
      await startSupportBundleUpload(request.id, {
        destination, user: user.trim(), password: secret, reason: reason.trim(), item_kinds: [...picked],
        ...(caseRef.trim() ? { case_ref: caseRef.trim() } : {}),
        ...(dest?.generic ? { host: host.trim(), port: Number(port) } : {}),
      });
      setSecret(""); setConfirmed(false);   // the credential is gone from the screen as soon as it is sent
      await refresh();
    } catch (e) {
      setFailure(((e as { body?: { reason?: string } }).body?.reason) ?? "The upload was not accepted.");
    } finally { setSecret(""); setBusy(false); }
  };
  const target = dest ? (dest.generic ? host.trim() || "the server you typed" : `${dest.host}:${dest.port}`) : "the destination";
  const ready = !!dest && user.trim() !== "" && secret !== "" && reason.trim().length >= 8 && picked.size > 0 && confirmed && !running
    && (!dest.generic || (host.trim() !== "" && Number(port) >= 1 && Number(port) <= 65535));
  return <Dialog open onClose={onClose} aria-labelledby="sb-up-title" fullWidth maxWidth="sm">
    <DialogTitle id="sb-up-title">Send to a vendor case</DialogTitle>
    <DialogContent>
      <Alert severity="warning" sx={{ mb: 2 }}>The bundle carries configuration, names, addresses and may carry secrets, and it cannot be masked. Sending it
        puts bank data on a server outside the bank. You may do this only where the bank's rules allow it.</Alert>
      {manual && <Alert severity="info" sx={{ mb: 2 }} data-fact="manual-upload">Manual upload: Check Point has no scriptable upload route yet. Download the
        bundle and attach it to the case on the vendor portal. You may still send it to a SFTP server of your own below.</Alert>}
      {failure && <Alert severity="error" data-fact="upload-error" sx={{ mb: 2 }}>{failure}</Alert>}
      <Stack gap={2}>
        <TextField select label="Destination" value={destination} onChange={(e) => { setDestination(e.target.value); setUser(""); setSecret(""); }} disabled={busy || running}>
          {options.map((d) => <MenuItem key={d.id} value={d.id}>{d.label}{d.host ? ` (${d.host}:${d.port})` : ""}</MenuItem>)}
        </TextField>
        {fields && <Typography variant="body2" color="text.secondary">{fields.help}</Typography>}
        {dest?.generic && <Stack direction="row" gap={2}>
          <TextField label="Host" value={host} onChange={(e) => setHost(e.target.value)} fullWidth disabled={busy || running} helperText="A host name or an IP address" />
          <TextField label="Port" value={port} onChange={(e) => setPort(e.target.value)} disabled={busy || running} sx={{ width: 120 }} />
        </Stack>}
        {fields && <TextField label={fields.user} value={user} onChange={(e) => setUser(e.target.value)} disabled={busy || running} autoComplete="off" />}
        {fields && <TextField label={fields.secret} type="password" value={secret} onChange={(e) => setSecret(e.target.value)} disabled={busy || running}
          autoComplete="new-password" helperText="Used for this upload only. neXus does not store it, log it or show it again." />}
        <Box role="group" aria-label="Items to send">
          {stored.map((i) => <Box key={`${i.kind}-${i.ordinal}`}><FormControlLabel
            control={<Checkbox checked={picked.has(i.kind)} disabled={busy || running} onChange={() => {
              const next = new Set(picked); if (next.has(i.kind)) next.delete(i.kind); else next.add(i.kind); setPicked(next);
            }} />} label={`${KIND_LABELS[i.kind] ?? i.kind} (${sizeText(i.size_bytes)})`} /></Box>)}
        </Box>
        <TextField label="Vendor case reference (optional)" value={caseRef} onChange={(e) => setCaseRef(e.target.value)} disabled={busy || running} inputProps={{ maxLength: 40 }} />
        <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} required disabled={busy || running} inputProps={{ maxLength: 500 }}
          helperText="At least 8 characters. Audited." />
        <Alert severity="info" data-fact="egress-note">{EGRESS_NOTE}</Alert>
        <FormControlLabel control={<Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} disabled={busy || running} />}
          label={`I confirm that this bundle may be sent to ${target}`} />
      </Stack>
      {uploads.length > 0 && <Box sx={{ mt: 3 }}>
        <Typography variant="subtitle2" component="h3">Uploads of this bundle</Typography>
        {uploads.map((u) => <Box key={u.id} data-upload={u.id} sx={{ mt: 1 }}>
          <Typography variant="body2">
            <StatusPill tone={u.state === "COMPLETED" ? "ok" : u.state === "RUNNING" ? "neutral" : "bad"} word={u.state} /> {u.destination_host}:{u.destination_port}
            {" "}{uploadSize(u)}{u.attempts > 1 ? `, ${u.attempts} attempts` : ""}
          </Typography>
          {u.state === "COMPLETED" && <Typography variant="caption" component="div">Remote answer: {u.remote_status}. SHA-256 {u.sha256?.slice(0, 16)}</Typography>}
          {u.error_code && <Typography variant="caption" color="error" component="div" data-fact="upload-failed">{u.error_code}: {u.error_detail}</Typography>}
          {u.host_key_changed && <Typography variant="caption" color="warning.main" component="div" data-fact="host-key-changed">
            Warning: the destination's host key differs from one seen before. The upload was not blocked.</Typography>}
        </Box>)}
      </Box>}
    </DialogContent>
    <DialogActions><Button onClick={onClose}>Close</Button>
      <Button variant="contained" onClick={() => void go()} disabled={!ready || busy}>Send</Button></DialogActions>
  </Dialog>;
}
