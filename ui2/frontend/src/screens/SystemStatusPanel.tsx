import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { M3Button, StatusChip } from "../shell/M3Widgets";
import { Ts } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { getSystemPods, getSystemStorage, type PodsView, type StorageView } from "../auth/adminApi";

export function formatBytes(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = value;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

function age(startedAt: string | null): string {
  if (!startedAt) return "—";
  const s = Math.max(0, (Date.now() - new Date(startedAt).getTime()) / 1000);
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  if (s < 86400) return `${Math.floor(s / 3600)} h`;
  return `${Math.floor(s / 86400)} d`;
}

function Usage({ used, limit, format }: { readonly used: number | null; readonly limit: number | null; readonly format: (n: number) => string }) {
  if (used === null) return <Typography variant="body2" color="text.secondary">—</Typography>;
  const pct = limit ? Math.min(100, Math.round((100 * used) / limit)) : null;
  return (
    <Box sx={{ minWidth: 150 }}>
      <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: 12 }}>
        {format(used)}{limit ? ` / ${format(limit)}` : ""}{pct !== null ? ` · ${pct}%` : ""}
      </Typography>
      {pct !== null && <LinearProgress variant="determinate" value={pct} color={pct >= 85 ? "error" : pct >= 65 ? "warning" : "primary"} sx={{ height: 4, borderRadius: 2 }} />}
    </Box>
  );
}

/** Administration › System: the product's own pods (a read-only top) and how much space its evidence takes. */
export function SystemStatusPanel() {
  const [pods, setPods] = useState<PodsView | null>(null);
  const [storage, setStorage] = useState<StorageView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    Promise.all([getSystemPods(), getSystemStorage()])
      .then(([p, s]) => { setPods(p); setStorage(s); setError(null); })
      .catch((e) => setError(e instanceof Error ? e.message : `System status could not be read (status ${(e as { status?: number }).status ?? "?"})`));
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, [load]);

  if (error) return <Alert severity="error">{error}</Alert>;
  if (!pods || !storage) return <Typography sx={{ p: 3 }}>Reading the system status…</Typography>;

  const backupStored = storage.backups.reduce((s, r) => s + r.stored_bytes, 0);
  const backupCount = storage.backups.reduce((s, r) => s + r.artefacts, 0);
  const vol = storage.artefact_volume;

  return (
    <Stack spacing={2}>
      <Card sx={{ p: 2, borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1 }}>
          <Box>
            <Typography variant="overline" sx={{ color: m3.onSurfaceVar }}>Service view · refreshed every 15 s</Typography>
            <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Pods</Typography>
          </Box>
          <M3Button emphasis="text" onClick={load}>Refresh</M3Button>
        </Box>
        {!pods.available && <Alert severity="warning">{pods.reason}</Alert>}
        {pods.metrics_note && <Alert severity="info" sx={{ mb: 1 }}>{pods.metrics_note}</Alert>}
        {pods.available && (
          <Table size="small">
            <TableHead>
              <TableRow>
                {["POD", "STATE", "READY", "RESTARTS", "AGE", "CPU", "MEMORY", "IMAGE"].map((h) => <TableCell key={h} sx={{ fontSize: 11, letterSpacing: "0.06em" }}>{h}</TableCell>)}
              </TableRow>
            </TableHead>
            <TableBody>
              {pods.pods.map((p) => (
                <TableRow key={p.name} hover>
                  <TableCell sx={{ fontWeight: 600, fontSize: 12.5 }}>{p.name}</TableCell>
                  <TableCell><StatusChip tone={p.state === "Running" ? "ok" : p.state === "Succeeded" ? "neutral" : "bad"} label={p.state} dense /></TableCell>
                  <TableCell>{p.ready}</TableCell>
                  <TableCell>{p.restarts > 0 ? <StatusChip tone="warn" label={String(p.restarts)} dense /> : "0"}</TableCell>
                  <TableCell>{age(p.started_at)}</TableCell>
                  <TableCell><Usage used={p.cpu_millicores} limit={p.cpu_limit_millicores} format={(n) => `${n}m`} /></TableCell>
                  <TableCell><Usage used={p.memory_bytes} limit={p.memory_limit_bytes} format={formatBytes} /></TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 11, color: m3.onSurfaceVar }}>{p.image_digest ?? "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>

      <Card sx={{ p: 2, borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
        <Typography variant="overline" sx={{ color: m3.onSurfaceVar }}>Storage</Typography>
        <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5, mb: 1.5 }}>What the evidence takes</Typography>
        <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fill, minmax(210px, 1fr))", mb: 2 }}>
          {[
            ["Backups (encrypted)", formatBytes(backupStored), `${backupCount} archives`],
            ["Configuration evidence", formatBytes(storage.configuration.stored_bytes), `${storage.configuration.artefacts} reads · ${formatBytes(storage.configuration.text_in_database_bytes)} text in the database`],
            ["Database", formatBytes(storage.database_bytes), "PostgreSQL, all tables"],
            ["Artefact volume free", formatBytes(vol.usable_bytes), vol.total_bytes ? `of ${formatBytes(vol.total_bytes)} · ${vol.note ?? ""}` : vol.note ?? ""],
          ].map(([label, value, note]) => (
            <Box key={label} sx={{ p: 1.5, borderRadius: "12px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em" }}>{label.toUpperCase()}</Typography>
              <Typography variant="h5" sx={{ fontWeight: 600 }}>{value}</Typography>
              <Typography variant="caption" color="text.secondary">{note}</Typography>
            </Box>
          ))}
        </Box>
        <Table size="small">
          <TableHead>
            <TableRow>{["VENDOR", "KIND", "ARCHIVES", "DEVICES", "STORED", "ORIGINAL", "OLDEST", "NEWEST"].map((h) => <TableCell key={h} sx={{ fontSize: 11, letterSpacing: "0.06em" }}>{h}</TableCell>)}</TableRow>
          </TableHead>
          <TableBody>
            {storage.backups.map((b) => (
              <TableRow key={`${b.vendor}-${b.class}`}>
                <TableCell>{b.vendor === "check_point" ? "Check Point" : b.vendor === "palo_alto" ? "Palo Alto" : b.vendor}</TableCell>
                <TableCell>{b.class}</TableCell>
                <TableCell>{b.artefacts}</TableCell>
                <TableCell>{b.devices}</TableCell>
                <TableCell sx={{ fontFamily: "monospace" }}>{formatBytes(b.stored_bytes)}</TableCell>
                <TableCell sx={{ fontFamily: "monospace" }}>{formatBytes(b.original_bytes)}</TableCell>
                <TableCell><Ts at={b.oldest} /></TableCell>
                <TableCell><Ts at={b.newest} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {storage.top_devices.length > 0 && (
          <>
            <Typography variant="subtitle2" sx={{ mt: 2, mb: 0.5 }}>Largest backup footprint</Typography>
            <Table size="small">
              <TableBody>
                {storage.top_devices.map((d) => (
                  <TableRow key={d.device_id}>
                    <TableCell sx={{ fontWeight: 600, fontSize: 12.5 }}>{d.hostname ?? d.device_id}</TableCell>
                    <TableCell>{d.artefacts} archives</TableCell>
                    <TableCell sx={{ fontFamily: "monospace" }}>{formatBytes(d.stored_bytes)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </Card>
    </Stack>
  );
}
