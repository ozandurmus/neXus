import { useRef, useState } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { setBackupStoreQuota, type BackupStoreQuotaRow, type BackupStoreQuotas } from "../auth/adminApi";
import { MetricCard } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { requestFailure } from "../shell/presenters";
import { m3 } from "../theme/m3Theme";

/**
 * Backups "Storage quota per store" (backlog backup_screen_backbox_model, V225): each artefact store (the first
 * directory level, the vendor) can carry its own byte quota; no quota means unlimited, which is the default. The
 * figures come from the service's measurement of the store; a store that could not be measured says UNKNOWN, never
 * zero. A write over a store's quota is refused with the same artefact_store_quota_exceeded reason as the global quota.
 */

const GIB = 1024 ** 3;

export function bytesWords(bytes: number | null): string {
  if (bytes === null) return "UNKNOWN";
  if (bytes >= GIB) return `${(bytes / GIB).toFixed(1)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${bytes} B`;
}

/** The GiB the editor's field holds for a quota; "" for unlimited. */
export function quotaToGib(bytes: number | null): string {
  return bytes === null ? "" : String(Math.round((bytes / GIB) * 1000) / 1000);
}

/** The bytes a typed GiB amount means, or null when it is not a positive number. */
export function gibToBytes(text: string): number | null {
  const value = Number(text.trim());
  return text.trim() !== "" && Number.isFinite(value) && value > 0 ? Math.round(value * GIB) : null;
}

export function quotaSummary(rows: readonly BackupStoreQuotaRow[]): { withQuota: number; exceeded: number; near: number } {
  return {
    withQuota: rows.filter((r) => r.quota_bytes !== null).length,
    exceeded: rows.filter((r) => r.quota_exceeded).length,
    near: rows.filter((r) => r.near_limit && !r.quota_exceeded).length,
  };
}

export function BackupStoreQuotaCard({ quotas, onChanged }: { readonly quotas: BackupStoreQuotas | null; readonly onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const rows = quotas?.stores ?? [];
  const known = quotas !== null && Array.isArray(quotas.stores);
  const summary = quotaSummary(rows);
  const attention = summary.exceeded > 0 ? `${summary.exceeded} store${summary.exceeded === 1 ? "" : "s"} at or over quota: new backups to ${summary.exceeded === 1 ? "it are" : "them are"} refused`
    : summary.near > 0 ? `${summary.near} store${summary.near === 1 ? "" : "s"} near the limit` : null;
  return (
    <>
      <MetricCard
        title="Storage quota per store"
        value={known ? summary.withQuota : null}
        unit={known ? `of ${rows.length} store${rows.length === 1 ? "" : "s"} with a quota` : undefined}
        state={known ? "ok" : "unknown"}
        reason="The store quota read failed"
        detail={attention ?? undefined}
        note={known ? "No quota means unlimited; the global store quota applies as well" : "Store quotas unavailable"}
        href="#store-quotas"
        linkLabel="Set quotas →"
        onLinkClick={() => setOpen(true)}
      />
      <StoreQuotaDialog open={open} onClose={() => setOpen(false)} rows={rows} mounted={quotas?.store_mounted ?? false} onChanged={onChanged} />
    </>
  );
}

function StoreQuotaDialog({ open, onClose, rows, mounted, onChanged }: { readonly open: boolean; readonly onClose: () => void;
  readonly rows: readonly BackupStoreQuotaRow[]; readonly mounted: boolean; readonly onChanged: () => void }) {
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const lock = useRef(false);
  const draftFor = (row: BackupStoreQuotaRow) => drafts[row.store] ?? quotaToGib(row.quota_bytes);
  const apply = async (store: string, bytes: number | null) => {
    if (lock.current) return;
    lock.current = true; setBusy(store); setError(null);
    try {
      await setBackupStoreQuota(store, bytes);
      setDrafts((d) => { const { [store]: _gone, ...rest } = d; return rest; });
      onChanged();
    } catch (e) {
      const status = (e as { status?: number }).status;
      setError(status === 403 ? "Refused by the service: your session may not change a store quota (backup_admin)." : requestFailure(e as Parameters<typeof requestFailure>[0]));
    } finally { lock.current = false; setBusy(null); }
  };
  return (
    <Dialog open={open} onClose={() => { if (busy === null) onClose(); }} maxWidth="md" fullWidth>
      <DialogTitle>Storage quota per store</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1.5 }}>
          A store is the vendor directory of the artefact store. A write that would take a store over its quota is refused
          with the reason artefact_store_quota_exceeded and the store's name; existing backups are never deleted to make room.
          Every change is audited with your account.
        </Typography>
        {!mounted && <Typography role="status" variant="body2" sx={{ mb: 1 }}>Usage UNKNOWN: this pod does not have the artefact store mounted.</Typography>}
        {rows.length === 0 && <Typography>No store holds an artefact yet and no quota is set.</Typography>}
        <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: 1 }}>
          {rows.map((row) => (
            <Box component="li" key={row.store} aria-label={`Store ${row.store}`} sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
              <Typography sx={{ fontWeight: 600, minWidth: 130 }}>{row.store}</Typography>
              <Typography variant="body2" sx={{ minWidth: 190 }}>
                {bytesWords(row.used_bytes)} used / {row.quota_bytes === null ? "no quota" : bytesWords(row.quota_bytes)}
                {row.percent !== null && row.percent !== undefined ? ` (${row.percent}%)` : ""}
              </Typography>
              {row.quota_exceeded ? <StatusChip tone="bad" label="Over quota" dense title={row.warning} />
                : row.near_limit ? <StatusChip tone="warn" label="Near limit" dense title={row.warning} /> : null}
              <TextField size="small" label="Quota (GB)" value={draftFor(row)} sx={{ width: 130 }}
                inputProps={{ "aria-label": `Quota in GB for ${row.store}`, inputMode: "decimal" }}
                onChange={(e) => setDrafts((d) => ({ ...d, [row.store]: e.target.value }))} disabled={busy !== null} />
              <M3Button emphasis="outlined" disabled={busy !== null || gibToBytes(draftFor(row)) === null}
                onClick={() => void apply(row.store, gibToBytes(draftFor(row)))}>Save</M3Button>
              <M3Button emphasis="text" disabled={busy !== null || row.quota_bytes === null}
                onClick={() => void apply(row.store, null)}>Remove quota</M3Button>
            </Box>
          ))}
        </Box>
        {error && <Typography role="alert" color="error" sx={{ mt: 1.5 }}>{error}</Typography>}
      </DialogContent>
      <DialogActions><M3Button emphasis="text" disabled={busy !== null} onClick={onClose}>Close</M3Button></DialogActions>
    </Dialog>
  );
}
