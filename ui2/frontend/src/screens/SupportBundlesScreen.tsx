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
  cancelSupportBundle, deleteSupportBundle, getSupportBundleCatalogue, listDevices, listSupportBundles, requestSupportBundle,
  requestSupportBundleDownloadTicket, type DeviceSummary, type SupportBundleKindRow, type SupportBundleRequest,
} from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { StatePanel } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import { formatStamp } from "../shell/time";

/**
 * Support bundles (docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md, FROZEN 2026-11-15), under Tools. An
 * administrator picks a device and the items a vendor's support team usually asks for, runs them now or at a chosen time,
 * and later downloads the result. The server decides every check; the screen only shows its answer. A bundle cannot be
 * masked (the vendor's tools need it intact); it is deleted after a confirmed download, or after 14 days with a warning first.
 */
const KIND_LABELS: Record<string, string> = {
  cpdata_collector_present: "cpdata_collector installed? (read only, never run)",
  cp_gaia_extras: "Gaia details (versions, assets, HA state, CPU, disk)",
  cp_hcp_latest: "Latest HCP health report",
  cp_hcp_run: "Run HCP now (medium load)",
  cp_cpinfo: "cpinfo (high load)",
};
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
        subtitle="Collect the files a vendor's support team usually asks for, and download them later. Nothing is sent to the vendor." />
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
  const forVendor = kinds.filter((k) => k.vendor === vendor);
  const selected = ticked ?? new Set(forVendor.filter((k) => k.enabled && k.default_ticked).map((k) => k.kind));
  const toggle = (kind: string) => { const next = new Set(selected); if (next.has(kind)) next.delete(kind); else next.add(kind); setTicked(next); };
  const hint = when ? collisionHint(when) : null;
  const submit = async () => {
    setBusy(true); setFailure(""); setMessage("");
    try {
      await requestSupportBundle(deviceId, {
        kinds: [...selected], reason: reason.trim(),
        ...(when ? { run_at: new Date(when).toISOString() } : {}), ...(caseRef.trim() ? { case_ref: caseRef.trim() } : {}),
      });
      setMessage(when ? "Scheduled. It starts at the chosen time." : "Started. It appears in the list below.");
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
      {devices.filter((d) => d.vendor_hint === "check_point").map((d) => <MenuItem key={d.device_id} value={d.device_id}>{d.hostname}</MenuItem>)}
    </TextField>
    {device && vendor !== "check_point" && <Alert severity="info">Support bundles for this vendor are not built yet.</Alert>}
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
  if (rows === null) return <StatePanel variant="empty" title="Reading the support bundles…" announce="status" />;
  if (rows.length === 0) return <StatePanel variant="empty" title="No support bundle yet" body="Collect one with the form above." />;
  return <Box sx={{ overflowX: "auto" }}>
    <Table size="small" aria-label="Support bundles">
      <TableHead><TableRow><TableCell>Device</TableCell><TableCell>State</TableCell><TableCell>Items</TableCell><TableCell>Requested</TableCell><TableCell>Expires</TableCell><TableCell>Actions</TableCell></TableRow></TableHead>
      <TableBody>{rows.map((r) => <TableRow key={r.id} data-bundle={r.id}>
        <TableCell>{r.device_pseudonym}</TableCell>
        <TableCell><StatusPill tone={r.state === "COMPLETED" ? "ok" : r.state === "FAILED" ? "bad" : "neutral"} word={r.state} /></TableCell>
        <TableCell>{r.items.map((i) => <Box key={i.kind} component="div" data-item={i.kind}>
          {KIND_LABELS[i.kind] ?? i.kind}: {i.state}{i.outcome_code && i.outcome_code !== "COMPLETED" ? ` (${i.outcome_code})` : ""}
          {i.size_bytes !== null ? ` ${sizeText(i.size_bytes)}` : ""}{i.digest_prefix ? ` ${i.digest_prefix}` : ""}</Box>)}</TableCell>
        <TableCell>{formatStamp(r.requested_at)}</TableCell>
        <TableCell>{formatStamp(r.expires_at)}{r.expiry_warning && <Typography variant="caption" color="warning.main" component="div" data-fact="expiry-warning">
          Will be deleted at this time{r.downloaded_at ? "" : " and has not been downloaded"}.</Typography>}</TableCell>
        <TableCell><Stack direction="row" gap={1}>
          {(r.state === "COMPLETED" || r.state === "PARTIAL") && <Button size="small" onClick={() => setDownloading(r)}>Download</Button>}
          {r.state === "QUEUED" && <Button size="small" onClick={() => { void cancelSupportBundle(r.id, "Cancelled from the support bundles screen").then(onChange); }}>Cancel</Button>}
          {["COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "EXPIRED"].includes(r.state) && <Button size="small" color="warning" onClick={() => setDeleting(r)}>Delete</Button>}
        </Stack></TableCell>
      </TableRow>)}</TableBody>
    </Table>
    {downloading && <DownloadDialog request={downloading} onClose={() => setDownloading(null)}
      onStarted={() => { setAfterDownload(downloading); setDownloading(null); onChange(); }} />}
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
