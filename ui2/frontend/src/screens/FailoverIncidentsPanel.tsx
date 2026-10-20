import { useCallback, useEffect, useRef, useState } from "react";
import Card from "@mui/material/Card";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { listFailoverIncidents, releaseFailoverIncident, type FailoverIncident, type FailoverIncidents } from "../auth/adminApi";

export const RELEASE_REASON_MAX = 500;
const errorCode = (error: unknown) => (error as { body?: { code?: string } })?.body?.code ?? "Request failed";

/**
 * Open failover incidents and estate-wide unresolved sends (PO decision 2026-10-20 item 1). Every reader sees
 * them; only a security_admin session is offered "Release incident", with a mandatory reason. The actor is the
 * server-side session, so the dialog sends the reason and nothing else.
 */
export function FailoverIncidentsPanel({ clusterId, onReleased }: { clusterId?: string; onReleased?: () => void }) {
  const [data, setData] = useState<FailoverIncidents | null>(null);
  const [target, setTarget] = useState<FailoverIncident | null>(null);
  const [reason, setReason] = useState(""), [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const refresh = useCallback(async () => {
    try {
      const value = await listFailoverIncidents();
      setData({ canRelease: value?.canRelease === true, roleNote: value?.roleNote ?? null,
        incidents: Array.isArray(value?.incidents) ? value.incidents : [] });
    } catch { setData(null); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  // A unit is blocked by its own incidents and by any send that fences the whole estate.
  const shown = (data?.incidents ?? []).filter(i => !clusterId || i.clusterId === clusterId || i.blocksEstate);
  if (shown.length === 0) return null;
  const release = async () => {
    if (!target || lock.current) return;
    lock.current = true; setBusy(true); setError(null);
    try {
      await releaseFailoverIncident(target.incidentRef, reason.trim());
      setTarget(null); setReason(""); await refresh(); onReleased?.();
    } catch (e) { setError(errorCode(e)); }
    finally { lock.current = false; setBusy(false); }
  };
  return <Stack spacing={1} aria-label="Failover incidents">
    {shown.map(incident => <Card key={incident.incidentRef} sx={{ p: 2, bgcolor: m3.scLow }} role="group"
      aria-label={`Failover incident ${incident.incidentRef}`}>
      <Typography variant="subtitle2" color="error">
        Failover incident · {incident.maskedName ?? incident.cluster_member_ref}
      </Typography>
      <Typography variant="body2">
        {incident.incidentActive ? "Incident open: no failover on this unit or its members until it is released."
          : "Unresolved send: the outcome of a device write was never observed."}
        {incident.blocksEstate ? ` Blocks every failover in the estate (${incident.unresolvedSends} unresolved send${incident.unresolvedSends === 1 ? "" : "s"}).` : ""}
      </Typography>
      <Typography variant="caption">Reason: {incident.reason} · Opened: {incident.openedAt} · Run: {incident.incidentRef}</Typography>
      {data?.canRelease
        ? <Stack direction="row" sx={{ mt: 1 }}><M3Button emphasis="outlined" disabled={busy}
            onClick={() => { setTarget(incident); setReason(""); setError(null); }}>Release incident</M3Button></Stack>
        : <Typography variant="body2" sx={{ mt: 1 }}>{data?.roleNote ?? "Only a security_admin can release a failover incident."}</Typography>}
    </Card>)}
    <Dialog open={target !== null} onClose={() => { if (!busy) setTarget(null); }}>
      <DialogTitle>Release failover incident</DialogTitle>
      <DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
        <Typography>Unit: {target?.maskedName ?? target?.cluster_member_ref} · Run: {target?.incidentRef}</Typography>
        <Typography>Release only after the cluster state was verified out of band. neXus sends nothing to a device;
          an unobserved write is recorded as outcome unknown, never as confirmed. Your account and the reason are recorded on the incident.</Typography>
        <TextField label="Reason" required multiline minRows={2} value={reason}
          inputProps={{ maxLength: RELEASE_REASON_MAX }} onChange={e => setReason(e.target.value)} />
        {error && <Typography role="alert" color="error">{error}</Typography>}
      </Stack></DialogContent>
      <DialogActions>
        <M3Button emphasis="text" disabled={busy} onClick={() => setTarget(null)}>Cancel</M3Button>
        <M3Button emphasis="filled" disabled={busy || !reason.trim()} onClick={() => void release()}>Release</M3Button>
      </DialogActions>
    </Dialog>
  </Stack>;
}
