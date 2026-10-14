import { useEffect, useRef, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { downloadPolicyChanges, getPolicyChanges, type PolicyChangeFilters, type PolicyChangesPage, type PolicyMetadata, type PolicyTarget } from "../auth/adminApi";
import { m3 } from "../theme/m3Theme";

const fields = ["name", "number", "enabled", "source", "destination", "service", "application", "action", "log", "comment", "extras", "container", "section", "parentRuleId"];
const initialFilters: PolicyChangeFilters = { from: "", to: "", policy: "", device: "", actor: "", changeType: "", field: "", q: "" };
export function PolicyChangesTab({ policies, devices, openRule }: { policies: PolicyMetadata[]; devices: PolicyTarget[]; openRule: (policy: string, rule: string) => void }) {
  const [filters, setFilters] = useState(initialFilters), [draft, setDraft] = useState(initialFilters);
  const [data, setData] = useState<PolicyChangesPage | null>(null), [error, setError] = useState("");
  const [cursors, setCursors] = useState([""]), [revision, setRevision] = useState(0), [exporting, setExporting] = useState(false);
  const range = useRef<{ from: string; to: string } | null>(null);
  const cursor = cursors[cursors.length - 1];
  useEffect(() => {
    let active = true; setData(null); setError("");
    getPolicyChanges({ ...filters, ...range.current }, cursor).then(result => {
      if (!Array.isArray(result.rows) || !result.summary) throw new Error("Invalid changes response");
      if (active) { range.current = { from: result.rangeStart, to: result.rangeEnd }; setData(result); }
    }).catch(() => { if (active) setError("Changes could not be loaded."); });
    return () => { active = false; };
  }, [filters, cursor, revision]);
  const update = (field: keyof PolicyChangeFilters, value: string) => setDraft(previous => ({ ...previous, [field]: value }));
  const exportCsv = async () => {
    setExporting(true); setError("");
    try {
      const blob = await downloadPolicyChanges({ ...filters, from: data?.rangeStart ?? filters.from, to: data?.rangeEnd ?? filters.to });
      const url = URL.createObjectURL(blob), link = document.createElement("a");
      link.href = url; link.download = "policy-changes.csv"; link.click(); URL.revokeObjectURL(url);
    } catch { setError("Changes CSV could not be exported."); }
    finally { setExporting(false); }
  };
  return <Box role="tabpanel" aria-label="Changes">
    <Typography variant="body2">Estate-wide stored rule revisions. Default period: last 30 days. Missing actors are UNKNOWN; collection observations do not prove the change time.</Typography>
    <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 2 }}>
      {[["Changes in period", data?.summary.total], ["Distinct actors", data?.summary.actors], ["Distinct rules", data?.summary.rules],
        ...["added", "modified", "removed"].map(type => [type, data?.summary.counts[type]])].map(([label, value]) =>
        <Box key={label} sx={{ bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, px: 2, py: 1 }}>
          <Typography variant="caption">{label}</Typography><Typography variant="h6">{value ?? "—"}</Typography>
        </Box>)}
    </Stack>
    <Box component="form" onSubmit={event => { event.preventDefault(); range.current = null; setFilters({ ...draft }); setCursors([""]); }}>
      <Stack direction="row" gap={1} flexWrap="wrap" sx={{ mb: 2 }}>
        {(["from", "to"] as const).map(field => <TextField key={field} size="small" label={field === "from" ? "From (UTC)" : "Until (UTC, exclusive)"} type="datetime-local"
          InputLabelProps={{ shrink: true }} value={draft[field].replace(/Z$/, "")} onChange={e => update(field, e.target.value ? new Date(`${e.target.value}Z`).toISOString() : "")} />)}
        <TextField select label="Policy" size="small" value={draft.policy} sx={{ minWidth: 160 }} onChange={e => update("policy", e.target.value)}>
          <MenuItem value="">All policies</MenuItem>{policies.map(p => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
        </TextField>
        <TextField select label="Device" size="small" value={draft.device} sx={{ minWidth: 160 }} onChange={e => update("device", e.target.value)}>
          <MenuItem value="">All devices</MenuItem>{devices.map(d => <MenuItem key={d.deviceId} value={d.deviceId}>{d.name}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Actor" value={draft.actor} inputProps={{ maxLength: 256 }} onChange={e => update("actor", e.target.value)} />
        <TextField select label="Change type" size="small" value={draft.changeType} sx={{ minWidth: 140 }} onChange={e => update("changeType", e.target.value)}>
          <MenuItem value="">All types</MenuItem>{["added", "modified", "removed"].map(t => <MenuItem key={t} value={t}>{t}</MenuItem>)}
        </TextField>
        <TextField select label="Changed field" size="small" value={draft.field} sx={{ minWidth: 140 }} onChange={e => update("field", e.target.value)}>
          <MenuItem value="">All fields</MenuItem>{fields.map(f => <MenuItem key={f} value={f}>{f}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Rule name or number" value={draft.q} inputProps={{ maxLength: 1000 }} onChange={e => update("q", e.target.value)} />
        <Button type="submit">Apply change filters</Button><Button disabled={!data || exporting} onClick={() => void exportCsv()}>{exporting ? "Exporting…" : "Export changes CSV"}</Button>
      </Stack>
    </Box>
    {error && <Box role="alert">{error}<Button onClick={() => setRevision(n => n + 1)}>Retry changes</Button></Box>}
    {!data && !error && <Typography role="status">Loading changes…</Typography>}
    {data && <Typography variant="caption">{data.rangeStart} — {data.rangeEnd} · {data.summary.total} matching changes</Typography>}
    {data && data.rows.length === 0 && <Typography role="status">No rule changes match these filters.</Typography>}
    {!!data?.rows.length && <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Policy changes">
      <TableHead><TableRow>{["When", "Policy / device", "Rule", "Change", "Actor", "Before / after"].map(label => <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead>
      <TableBody>{data.rows.map(row => <TableRow key={row.revision}>
        <TableCell>{row.changedOn}{row.observedAtCollection && <Typography variant="caption" display="block">Observed at collection</Typography>}</TableCell>
        <TableCell>{row.policyName}<Typography variant="caption" display="block">{row.targets.map(t => t.name).join(", ") || "Unassigned"}</Typography></TableCell>
        <TableCell><Button onClick={() => openRule(row.policyId, row.ruleId)}>{row.number ?? "?"} · {row.name || "Unnamed rule"}</Button>
          {row.currentSnapshotLabel && <Typography variant="caption" display="block">Current snapshot label; historical label unavailable</Typography>}
          {row.changeType === "removed" && <Typography variant="caption" display="block">Removed; current rule details may be unavailable</Typography>}</TableCell>
        <TableCell><Chip size="small" label={row.changeType} /></TableCell><TableCell>{row.changedBy || "UNKNOWN (observed by collection)"}</TableCell>
        <TableCell><Box component="details"><Box component="summary" sx={{ cursor: "pointer" }}>{row.changedFields.join(", ") || "No field details"}</Box>
          {row.changes.map((change, index) => <Stack key={index} gap={0.5} sx={{ my: 1 }}><Typography variant="caption">{change.field}</Typography>
            <Chip size="small" variant="outlined" label={`Before: ${JSON.stringify(change.before) ?? "UNKNOWN"}`} sx={{ maxWidth: 480, height: "auto", "& .MuiChip-label": { whiteSpace: "normal", overflowWrap: "anywhere" } }} />
            <Chip size="small" label={`After: ${JSON.stringify(change.after) ?? "UNKNOWN"}`} sx={{ maxWidth: 480, height: "auto", "& .MuiChip-label": { whiteSpace: "normal", overflowWrap: "anywhere" } }} />
          </Stack>)}</Box></TableCell>
      </TableRow>)}</TableBody>
    </Table></Box>}
    {data && <Stack direction="row" alignItems="center"><Button disabled={cursors.length === 1} onClick={() => setCursors(previous => previous.slice(0, -1))}>Previous changes page</Button>
      <Typography variant="caption">Page {cursors.length}</Typography><Button disabled={!data.nextCursor} onClick={() => setCursors(previous => [...previous, data.nextCursor])}>Next changes page</Button></Stack>}
  </Box>;
}
