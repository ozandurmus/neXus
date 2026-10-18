import { Fragment, useEffect, useRef, useState } from "react";
import { Box, Button, Chip, FormControlLabel, MenuItem, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography } from "@mui/material";
import { downloadPolicyChanges, getPolicyChanges, type PolicyChangeFilters, type PolicyChangeRow, type PolicyChangesPage, type PolicyMetadata, type PolicyTarget } from "../auth/adminApi";
import { m3 } from "../theme/m3Theme";
import { Stamp } from "../shell/PresentedText";
import { DISPLAY_TZ_LABEL, displayInputToIso, formatStamp, isoToDisplayInput } from "../shell/time";

const fields = ["name", "number", "enabled", "source", "destination", "service", "application", "action", "log", "comment", "extras", "container", "section", "parentRuleId"];
const initialFilters: PolicyChangeFilters = { from: "", to: "", policy: "", device: "", actor: "", changeType: "", field: "", q: "", includeInitial: false };
const columns = ["When", "Policy / device", "Rule", "Change", "Actor", "Before / after"];

/** One collection of one policy: the rows a single snapshot publication recorded, in page order. */
export interface ChangeGroup { key: string; policyName: string; collectedAt: string; rows: PolicyChangeRow[]; initialCapture: boolean }
export function groupByCollection(rows: PolicyChangeRow[]): ChangeGroup[] {
  const groups = new Map<string, ChangeGroup>();
  for (const row of rows) {
    const collectedAt = row.collectedAt ?? row.changedOn;
    const key = JSON.stringify([row.policyId, collectedAt]);
    const group = groups.get(key) ?? { key, policyName: row.policyName, collectedAt, rows: [], initialCapture: true };
    group.rows.push(row); group.initialCapture = group.initialCapture && row.initialCapture === true;
    groups.set(key, group);
  }
  return [...groups.values()];
}
function groupLabel(group: ChangeGroup) {
  const counts = ["added", "modified", "removed"].map(type => [type, group.rows.filter(row => row.changeType === type).length] as const).filter(([, n]) => n > 0);
  return `${group.rows.length} ${group.rows.length === 1 ? "change" : "changes"}${counts.length ? ` (${counts.map(([type, n]) => `${n} ${type}`).join(", ")})` : ""}`;
}
const renumberedOnly = (row: PolicyChangeRow) => row.changeType === "modified" && row.changedFields.length === 1 && row.changedFields[0] === "number";

export function PolicyChangesTab({ policies, devices, openRule }: { policies: PolicyMetadata[]; devices: PolicyTarget[]; openRule: (policy: string, rule: string) => void }) {
  const [filters, setFilters] = useState(initialFilters), [draft, setDraft] = useState(initialFilters);
  const [data, setData] = useState<PolicyChangesPage | null>(null), [error, setError] = useState("");
  const [cursors, setCursors] = useState([""]), [revision, setRevision] = useState(0), [exporting, setExporting] = useState(false);
  // Explicit open/closed choices per collection; otherwise initial captures start collapsed and real changes open.
  const [toggled, setToggled] = useState<Map<string, boolean>>(new Map());
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
  const includeInitial = (value: boolean) => {
    // Same period, first page: the keyset restarts because the matching set changed.
    setDraft(previous => ({ ...previous, includeInitial: value })); setFilters(previous => ({ ...previous, includeInitial: value })); setCursors([""]);
  };
  const exportCsv = async () => {
    setExporting(true); setError("");
    try {
      const blob = await downloadPolicyChanges({ ...filters, from: data?.rangeStart ?? filters.from, to: data?.rangeEnd ?? filters.to });
      const url = URL.createObjectURL(blob), link = document.createElement("a");
      link.href = url; link.download = "policy-changes.csv"; link.click(); URL.revokeObjectURL(url);
    } catch { setError("Changes CSV could not be exported."); }
    finally { setExporting(false); }
  };
  const hidden = data?.summary.initialCaptures;
  const groups = data ? groupByCollection(data.rows) : [];
  const open = (group: ChangeGroup) => toggled.get(group.key) ?? !group.initialCapture;
  return <Box role="tabpanel" aria-label="Changes">
    <Typography variant="body2">Estate-wide stored rule revisions. Default period: last 30 days. Missing actors are UNKNOWN; collection observations do not prove the change time.
      {" "}A policy's first complete collection records every rule as added; these initial captures are not changes and are left out unless included.</Typography>
    <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 2 }}>
      {/* Loading… while the read is in flight, then the number; UNKNOWN when the read failed. Never a dash. */}
      {[["Changes in period", data?.summary.total], ["Distinct actors", data?.summary.actors], ["Distinct rules", data?.summary.rules],
        ...["added", "modified", "removed"].map(type => [type, data?.summary.counts[type]]),
        ...(hidden === undefined ? [] : [[filters.includeInitial ? "Initial captures (included)" : "Initial captures (not shown)", hidden]])].map(([label, value]) =>
        <Box key={label} sx={{ bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, px: 2, py: 1 }}>
          <Typography variant="caption">{label}</Typography>
          {value !== undefined && value !== null ? <Typography variant="h6">{value}</Typography>
            : <Typography variant="h6" role="status" sx={{ fontSize: 16, color: m3.neutralInk }}>{error ? "UNKNOWN" : "Loading…"}</Typography>}
        </Box>)}
    </Stack>
    <Box component="form" onSubmit={event => { event.preventDefault(); range.current = null; setFilters({ ...draft }); setCursors([""]); }}>
      <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center" sx={{ mb: 2 }}>
        {/* Display time like the rest of the product (UI review 2026-10-18): typed and shown in GMT+3, sent as the UTC instant. */}
        {(["from", "to"] as const).map(field => <TextField key={field} size="small" label={field === "from" ? `From (${DISPLAY_TZ_LABEL})` : `Until (${DISPLAY_TZ_LABEL}, exclusive)`} type="datetime-local"
          InputLabelProps={{ shrink: true }} value={isoToDisplayInput(draft[field])} onChange={e => update(field, displayInputToIso(e.target.value) ?? "")} />)}
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
        <FormControlLabel control={<Switch size="small" checked={filters.includeInitial === true} onChange={e => includeInitial(e.target.checked)} />} label="Include initial captures" />
        <Button type="submit">Apply change filters</Button><Button disabled={!data || exporting} onClick={() => void exportCsv()}>{exporting ? "Exporting…" : "Export changes CSV"}</Button>
      </Stack>
    </Box>
    {error && <Box role="alert">{error}<Button onClick={() => setRevision(n => n + 1)}>Retry changes</Button></Box>}
    {!data && !error && <Typography role="status">Loading changes…</Typography>}
    {data && <Typography variant="caption">
      <Tooltip title={`${data.rangeStart} — ${data.rangeEnd} (UTC reference)`}><span>{formatStamp(data.rangeStart)} — {formatStamp(data.rangeEnd)}</span></Tooltip>
      {" "}· {data.summary.total} matching changes{!filters.includeInitial && !!hidden && ` · ${hidden} initial-capture rows not shown`}</Typography>}
    {data && data.rows.length === 0 && <Typography role="status">No rule changes match these filters.</Typography>}
    {!!data?.rows.length && <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Policy changes">
      <TableHead><TableRow>{columns.map(label => <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead>
      <TableBody>{groups.map(group => <Fragment key={group.key}>
        <TableRow sx={{ bgcolor: m3.scLow }}>
          <TableCell colSpan={columns.length} sx={{ py: 0.5 }}>
            <Stack direction="row" alignItems="center" gap={1} flexWrap="wrap">
              <Button size="small" aria-expanded={open(group)} aria-label={`Collection ${formatStamp(group.collectedAt)} ${group.policyName}`}
                onClick={() => setToggled(previous => new Map(previous).set(group.key, !open(group)))} sx={{ textTransform: "none", minWidth: 0 }}>
                {open(group) ? "▾" : "▸"}
              </Button>
              <Typography variant="body2" component="span">Collection <Stamp at={group.collectedAt} age={false} /> · {group.policyName}</Typography>
              <Typography variant="caption" component="span">{groupLabel(group)}</Typography>
              {group.initialCapture && <Chip size="small" variant="outlined" label="Initial capture" />}
            </Stack>
          </TableCell>
        </TableRow>
        {open(group) && group.rows.map(row => <TableRow key={row.revision}>
          <TableCell><Stamp at={row.changedOn} />{row.observedAtCollection && <Typography variant="caption" display="block">Observed at collection</Typography>}</TableCell>
          <TableCell>{row.policyName}<Typography variant="caption" display="block">{row.targets.map(t => t.name).join(", ") || "Unassigned"}</Typography></TableCell>
          <TableCell><Button onClick={() => openRule(row.policyId, row.ruleId)}>{row.number ?? "?"} · {row.name || "Unnamed rule"}</Button>
            {row.currentSnapshotLabel && <Typography variant="caption" display="block">Current snapshot label; historical label unavailable</Typography>}
            {row.changeType === "removed" && <Typography variant="caption" display="block">Removed; current rule details may be unavailable</Typography>}</TableCell>
          <TableCell><Chip size="small" label={row.changeType} />
            {row.initialCapture && <Typography variant="caption" display="block">Initial capture</Typography>}
            {renumberedOnly(row) && <Typography variant="caption" display="block">Position only</Typography>}</TableCell>
          <TableCell>{row.changedBy || "UNKNOWN (observed by collection)"}</TableCell>
          <TableCell><Box component="details"><Box component="summary" sx={{ cursor: "pointer" }}>{row.changedFields.join(", ") || "No field details"}</Box>
            {row.changes.map((change, index) => <Stack key={index} gap={0.5} sx={{ my: 1 }}><Typography variant="caption">{change.field}</Typography>
              <Chip size="small" variant="outlined" label={`Before: ${JSON.stringify(change.before) ?? "UNKNOWN"}`} sx={{ maxWidth: 480, height: "auto", "& .MuiChip-label": { whiteSpace: "normal", overflowWrap: "anywhere" } }} />
              <Chip size="small" label={`After: ${JSON.stringify(change.after) ?? "UNKNOWN"}`} sx={{ maxWidth: 480, height: "auto", "& .MuiChip-label": { whiteSpace: "normal", overflowWrap: "anywhere" } }} />
            </Stack>)}</Box></TableCell>
        </TableRow>)}
      </Fragment>)}</TableBody>
    </Table></Box>}
    {data && <Stack direction="row" alignItems="center"><Button disabled={cursors.length === 1} onClick={() => setCursors(previous => previous.slice(0, -1))}>Previous changes page</Button>
      <Typography variant="caption">Page {cursors.length}</Typography><Button disabled={!data.nextCursor} onClick={() => setCursors(previous => [...previous, data.nextCursor])}>Next changes page</Button></Stack>}
  </Box>;
}
