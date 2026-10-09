import { useState } from "react";
import { Alert, Box, Button, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { getLifecycleCatalog, saveLifecycleCatalog, importLifecycleCatalog, deleteLifecycleCatalog, type ApiError, type LifecycleCatalogInput } from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button } from "../shell/M3Widgets";

const vendors = ["CHECKPOINT", "PALOALTO", "FORTINET", "BLUECOAT", "INFOBLOX", "RADWARE", "CISCO_ASA", "PULSE_SECURE"];
const empty = (): LifecycleCatalogInput => ({ vendor: "CHECKPOINT", kind: "HARDWARE", product: "", end_of_sale: null, end_of_support: null, end_of_engineering: null, note: "" });
const dateFields = ["end_of_sale", "end_of_support", "end_of_engineering"] as const;
export function lifecycleError(error: unknown): string {
  const body = (error as Partial<ApiError>)?.body;
  if (Array.isArray(body?.errors)) return body.errors.map((e: { row: number; reason: string }) => `Row ${e.row}: ${e.reason}`).join("; ");
  return typeof body?.reason === "string" ? body.reason : typeof body?.error === "string" ? body.error : "Lifecycle request failed";
}

export function LifecycleCatalogPanel({ onChanged }: { onChanged: () => void }) {
  const catalog = useFetchOnMount(getLifecycleCatalog, lifecycleError);
  const [entry, setEntry] = useState(empty);
  const [editing, setEditing] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function mutate(work: () => Promise<unknown>) {
    setBusy(true); setError(null); setMessage(null);
    try { await work(); catalog.refresh(); onChanged(); setMessage("Catalog updated"); }
    catch (e) { setError(lifecycleError(e)); }
    finally { setBusy(false); }
  }
  if (catalog.error) return <Alert severity="error">{catalog.error}<M3Button emphasis="tonal" onClick={catalog.refresh}>Retry</M3Button></Alert>;
  if (!catalog.data) return <Typography>Loading catalog…</Typography>;
  return <Stack spacing={2}>
    <Typography variant="body2">The catalog starts empty. Enter vendor-published milestones and their source; blank dates remain unknown. Imports replace the same vendor, kind and product atomically. An invalid file changes nothing.</Typography>
    {error && <Alert severity="error">{error}</Alert>}{message && <Alert severity="success">{message}</Alert>}
    {catalog.data.can_manage && <>
      <Stack direction="row" spacing={2} alignItems="center">
        <Box component="label">Import CSV <input aria-label="Import lifecycle CSV" type="file" accept=".csv,text/csv" disabled={busy} onChange={async e => {
          const file = e.target.files?.[0]; e.target.value = "";
          if (!file) return;
          if (file.size > 1_000_000) { setError("CSV exceeds 1 MB"); return; }
          await mutate(async () => importLifecycleCatalog(await file.text()));
        }} /></Box>
      </Stack>
      <Typography variant="caption">Columns: vendor,kind,product,end_of_sale,end_of_support,end_of_engineering,note. Dates: YYYY-MM-DD.</Typography>
      <Box component="form" onSubmit={e => { e.preventDefault(); void mutate(async () => {
        await saveLifecycleCatalog(entry, editing); setEntry(empty()); setEditing(undefined);
      }); }}>
        <Stack spacing={1.5}>
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            <TextField select label="Vendor" size="small" value={entry.vendor} disabled={busy} onChange={e => setEntry({ ...entry, vendor: e.target.value })}>{vendors.map(v => <MenuItem key={v} value={v}>{v}</MenuItem>)}</TextField>
            <TextField select label="Kind" size="small" value={entry.kind} disabled={busy} onChange={e => setEntry({ ...entry, kind: e.target.value as LifecycleCatalogInput["kind"] })}>{["HARDWARE", "SOFTWARE"].map(k => <MenuItem key={k} value={k}>{k}</MenuItem>)}</TextField>
            <TextField label="Product" size="small" value={entry.product} required inputProps={{ maxLength: 200 }} disabled={busy} onChange={e => setEntry({ ...entry, product: e.target.value })} />
            {dateFields.map(field => <TextField key={field} label={field.replaceAll("_", " ")} type="date" size="small" InputLabelProps={{ shrink: true }} value={entry[field] ?? ""} disabled={busy} onChange={e => setEntry({ ...entry, [field]: e.target.value || null })} />)}
          </Stack>
          <TextField label="Source note or URL" size="small" value={entry.note ?? ""} inputProps={{ maxLength: 2000 }} disabled={busy} onChange={e => setEntry({ ...entry, note: e.target.value })} />
          <Stack direction="row" spacing={1}><Button variant="contained" type="submit" disabled={busy || !entry.product.trim()}>{editing ? "Save changes" : "Add row"}</Button>{editing && <M3Button emphasis="tonal" disabled={busy} onClick={() => { setEditing(undefined); setEntry(empty()); }}>Cancel edit</M3Button>}</Stack>
        </Stack>
      </Box>
    </>}
    {!catalog.data.can_manage && <Typography variant="caption">Catalog is read-only for this session.</Typography>}
    {catalog.data.entries.length === 0 ? <Typography>No lifecycle catalog data</Typography> : <TableContainer><Table size="small" aria-label="Lifecycle catalog">
      <TableHead><TableRow>{["Vendor", "Kind", "Product", "End of sale", "End of support", "End of engineering", "Source", "Provenance", "Actions"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
      <TableBody>{catalog.data.entries.map(e => <TableRow key={e.catalog_id}>
        <TableCell>{e.vendor}</TableCell><TableCell>{e.kind}</TableCell><TableCell>{e.product}</TableCell>{dateFields.map(f => <TableCell key={f}>{e[f] ?? "UNKNOWN"}</TableCell>)}<TableCell>{e.source}</TableCell><TableCell>{e.note}<Typography variant="caption" display="block">{e.imported_at} · {e.imported_by}</Typography></TableCell>
        <TableCell>{catalog.data?.can_manage && <Stack direction="row" spacing={1}><M3Button emphasis="tonal" disabled={busy} onClick={() => { setEntry(e); setEditing(e.catalog_id); setError(null); }}>Edit</M3Button><M3Button emphasis="tonal" disabled={busy} onClick={() => void mutate(() => deleteLifecycleCatalog(e.catalog_id))}>Delete</M3Button></Stack>}</TableCell>
      </TableRow>)}</TableBody>
    </Table></TableContainer>}
  </Stack>;
}
