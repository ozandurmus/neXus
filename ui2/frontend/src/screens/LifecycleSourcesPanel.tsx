import { useState } from "react";
import { Alert, Box, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { getLifecycleCatalogSources, uploadLifecycleVendorPage } from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button } from "../shell/M3Widgets";
import { lifecycleError } from "./LifecycleCatalogPanel";

const ageColor = (state: string) => state === "RED" ? "error.main" : state === "AMBER" ? "warning.main" : "text.primary";
const SOURCE_NAMES: Record<string, string> = { cisco_eol: "Cisco end-of-life bulletins", paloalto_eol: "Palo Alto end-of-life pages", checkpoint_lifecycle: "Check Point life cycle page" };
export const lifecycleSourceName = (key: string) => SOURCE_NAMES[key] ?? key;

/**
 * Where the lifecycle catalog's vendor dates come from (LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 6): the online source
 * with its age, the vendor pages an operator may save and upload (for hosts neXus does not reach), the upload history and
 * the entries that need review. Upload is shown only to a session that may change the catalog.
 */
export function LifecycleSourcesPanel({ canManage, onChanged }: { canManage: boolean; onChanged: () => void }) {
  const sources = useFetchOnMount(getLifecycleCatalogSources, lifecycleError);
  const [form, setForm] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (sources.error) return <Alert severity="error">{sources.error}<M3Button emphasis="tonal" onClick={sources.refresh}>Retry</M3Button></Alert>;
  if (!sources.data) return <Typography>Loading lifecycle sources…</Typography>;
  const data = sources.data;
  const chosen = data.page_forms.find(f => f.page_form === form);
  return <Stack spacing={1.5} aria-label="Lifecycle sources">
    <Typography variant="subtitle1">Vendor sources</Typography>
    <Typography variant="body2">Vendor end-of-sale and end-of-support dates are read from the vendors' own publications. An online source must have
      loaded within {data.feeds[0]?.fresh_days ?? 30} days and an uploaded page within {data.hand_loaded_fresh_days} days; after that its devices show
      NOT_EVALUATED until it is loaded again. A date the vendor gives only as a month is shown as that month. A row neXus could not read safely is
      NEEDS_REVIEW and gives no date.</Typography>
    <TableContainer><Table size="small" aria-label="Online lifecycle sources">
      <TableHead><TableRow>{["Source", "Last good load", "Age", "Last run", "Entries", "Need review"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
      <TableBody>{data.feeds.map(f => <TableRow key={f.source}>
        <TableCell>{lifecycleSourceName(f.source)} (online)</TableCell>
        <TableCell>{f.last_good_at ?? "Never loaded"}</TableCell>
        <TableCell><Typography variant="body2" component="span" sx={{ color: ageColor(f.age_state) }}>{f.age_days == null ? "UNKNOWN" : `${f.age_days} days`}</Typography></TableCell>
        <TableCell>{f.last_run_outcome ? `${f.last_run_outcome}${f.last_run_reason ? ` (${f.last_run_reason})` : ""}` : "None"}</TableCell>
        <TableCell>{f.entries}</TableCell><TableCell>{f.entries_needing_review}</TableCell>
      </TableRow>)}</TableBody>
    </Table></TableContainer>
    <Typography variant="body2">Fortinet publishes its product life cycle only inside the FortiCloud support portal: enter FortiGate and FortiOS
      dates in the catalog below (row or CSV, for example FortiGate-600E or FortiOS 7.4).</Typography>
    {canManage && <Stack spacing={1}>
      <Typography variant="subtitle2">Upload a saved vendor page</Typography>
      <Typography variant="caption">Open the vendor page in a browser, save it as HTML, and upload the file here. neXus reads only the lifecycle tables;
        the file itself is not kept (its hash is). An upload replaces the entries of the previous upload of the same page.</Typography>
      {error && <Alert severity="error">{error}</Alert>}{message && <Alert severity="success">{message}</Alert>}
      <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
        <TextField select label="Vendor page" size="small" value={form} sx={{ minWidth: 320 }} disabled={busy} onChange={e => setForm(e.target.value)}>
          {data.page_forms.map(f => <MenuItem key={f.page_form} value={f.page_form}>{f.label}</MenuItem>)}</TextField>
        <Box component="label">Choose file <input aria-label="Upload vendor lifecycle page" type="file" accept=".html,.htm,text/html" disabled={busy || !form} onChange={async e => {
          const file = e.target.files?.[0]; e.target.value = "";
          if (!file || !form) return;
          if (file.size > 8 * 1024 * 1024) { setError("The file exceeds 8 MiB"); return; }
          setBusy(true); setError(null); setMessage(null);
          try {
            const r = await uploadLifecycleVendorPage(form, await file.text());
            setMessage(`Read ${r.parsed} entries${r.needs_review ? `, ${r.needs_review} need review` : ""}${r.skipped ? `, ${r.skipped} rows not used` : ""}`);
            sources.refresh(); onChanged();
          } catch (err) { setError(lifecycleError(err)); }
          finally { setBusy(false); }
        }} /></Box>
      </Stack>
      {chosen && <Typography variant="caption">Page: {chosen.page_url}</Typography>}
    </Stack>}
    {data.uploads.length > 0 && <TableContainer><Table size="small" aria-label="Lifecycle page uploads">
      <TableHead><TableRow>{["Page", "Uploaded", "Age", "Entries", "Need review", "Rows not used", "By"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
      <TableBody>{data.uploads.map(u => <TableRow key={u.upload_id}>
        <TableCell>{data.page_forms.find(f => f.page_form === u.page_form)?.label ?? u.page_form}</TableCell><TableCell>{u.uploaded_at ?? "UNKNOWN"}</TableCell>
        <TableCell><Typography variant="body2" component="span" sx={{ color: ageColor(u.age_state) }}>{u.age_days == null ? "UNKNOWN" : `${u.age_days} days`}</Typography></TableCell>
        <TableCell>{u.entries_parsed}</TableCell><TableCell>{u.entries_needing_review}</TableCell><TableCell>{u.rows_skipped}</TableCell><TableCell>{u.uploaded_by}</TableCell>
      </TableRow>)}</TableBody>
    </Table></TableContainer>}
    {data.needs_review.length > 0 && <>
      <Typography variant="subtitle2">Entries that need review ({data.needs_review.length})</Typography>
      <Typography variant="caption">The vendor page gave a date neXus will not guess (for example a two-digit year) or two different dates for one product.
        Check the vendor page and enter the date as a catalog row; an operator row is used before a vendor entry.</Typography>
      <TableContainer><Table size="small" aria-label="Lifecycle entries that need review">
        <TableHead><TableRow>{["Vendor", "Kind", "Product", "Reason", "Source"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
        <TableBody>{data.needs_review.map(r => <TableRow key={r.entry_id}>
          <TableCell>{r.vendor}</TableCell><TableCell>{r.kind}</TableCell><TableCell>{r.product}</TableCell><TableCell>{r.parse_note ?? "UNKNOWN"}</TableCell>
          <TableCell>{lifecycleSourceName(r.source)}{r.origin === "OPERATOR_PAGE" ? " (uploaded page)" : " (online)"}</TableCell>
        </TableRow>)}</TableBody>
      </Table></TableContainer></>}
  </Stack>;
}
