import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useSession } from "../auth/SessionContext";
import { acceptSshHostKey, getSshHostKeyView, rejectSshHostKey, setSshTrustMode, type SshHostKeyView } from "../auth/adminApi";
import { M3Button } from "../shell/M3Widgets";

export function SshHostKeyPanel({ deviceId, settings = false }: { deviceId?: string; settings?: boolean }) {
  const session = useSession();
  const canManage = session?.canReadAdministration ?? false;
  const [view, setView] = useState<SshHostKeyView | null>(null);
  const [mode, setMode] = useState<"WARN" | "STRICT">("WARN");
  const [confirmation, setConfirmation] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const refresh = async () => {
    const next = await getSshHostKeyView(deviceId);
    setView(next); setMode(next.mode);
  };
  useEffect(() => {
    let active = true;
    setView(null);
    getSshHostKeyView(deviceId).then(next => { if (active) { setView(next); setMode(next.mode); setError(null); } })
      .catch(() => { if (active) setError("SSH host-key status unavailable."); });
    return () => { active = false; };
  }, [deviceId]);
  const perform = async (action: () => Promise<unknown>) => {
    setBusy(true); setError(null);
    try { await action(); setConfirmation({}); await refresh(); }
    catch { setError("Unable to update SSH trust. Refresh and verify the observation before retrying."); }
    finally { setBusy(false); }
  };
  const observations = view?.observations.filter(o => settings || deviceId || o.status !== "ACCEPTED") ?? [];
  return <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
    {error && <Alert severity="error">{error}</Alert>}
    {settings && view && <Box>
      <Typography variant="h6">SSH host-key trust</Typography>
      <Typography>WARN allows only read jobs after an audited mismatch. Writes and failover execution always refuse. Missing trust is unchanged.</Typography>
      <TextField select label="Trust mode" value={mode} onChange={e => setMode(e.target.value as "WARN" | "STRICT")} disabled={!canManage || busy}>
        <MenuItem value="WARN">WARN</MenuItem><MenuItem value="STRICT">STRICT</MenuItem>
      </TextField>
      {canManage && <M3Button emphasis="outlined" disabled={busy || mode === view.mode} onClick={() => void perform(() => setSshTrustMode(mode))}>Save trust mode</M3Button>}
    </Box>}
    {observations.map(o => <Alert key={o.observation_id} severity={o.status === "ACCEPTED" ? "info" : "warning"}>
      <Typography fontWeight="bold">SSH host key changed for {o.hostname}</Typography>
      <Typography>{o.status}{o.strict_refusal ? " — Strict refusal for this device until resolved." : ""}</Typography>
      {o.ssh_identity_unverified && <Typography>Evidence collected under an unverified identity. Accepting a key does not verify past collections.</Typography>}
      <Typography sx={{ overflowWrap: "anywhere" }}>Presented ({o.key_algorithm}): {o.presented_fingerprint}</Typography>
      <Typography>Expected fingerprint prefix: {o.expected_fingerprint_prefix}</Typography>
      <Typography>First seen: {o.first_seen} · Last seen: {o.last_seen} · Count: {o.seen_count}</Typography>
      <Typography>Management-plane view ({o.management_source}): model {o.model ?? "UNKNOWN"}; version {o.software_version ?? "UNKNOWN"}; serial {o.serial_number ?? "UNKNOWN"}. Last known discovery evidence; not independent verification of this SSH key.</Typography>
      {canManage && o.status !== "ACCEPTED" && <Box sx={{ mt: 1, display: "flex", flexDirection: "column", gap: 1 }}>
        <TextField label="Confirmation" value={confirmation[o.observation_id] ?? ""}
          onChange={e => setConfirmation({ ...confirmation, [o.observation_id]: e.target.value })}
          helperText="Type ACCEPT NEW SSH KEY to attest that you independently verified this fingerprint." />
        <Box><M3Button emphasis="outlined" disabled={busy || confirmation[o.observation_id] !== "ACCEPT NEW SSH KEY"}
          onClick={() => void perform(() => acceptSshHostKey(o.observation_id, confirmation[o.observation_id]))}>Accept new key</M3Button>
        <M3Button emphasis="outlined" disabled={busy || o.status === "REJECTED"} onClick={() => void perform(() => rejectSshHostKey(o.observation_id))}>Reject</M3Button></Box>
      </Box>}
    </Alert>)}
  </Box>;
}
