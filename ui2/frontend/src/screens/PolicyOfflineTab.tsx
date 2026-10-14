import { useEffect, useRef, useState } from "react";
import { Alert, Box, Button, Checkbox, CircularProgress, FormControlLabel, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { deleteOfflineAnalysis, getOfflineAnalysis, listOfflineAnalyses, uploadOfflineAnalysis, type OfflineAnalysisResult, type OfflineAnalysisSummary, type OfflineRule } from "../auth/adminApi";
import { detectMapping, fields, parsePreview, required, type CsvField } from "./offlineCsv";

const labels: Record<CsvField, string> = { source: "Source IP", destination: "Destination IP", port: "Destination port", protocol: "Protocol",
  time: "Time", action: "Action", sourceZone: "Source zone", destinationZone: "Destination zone", rule: "SIEM rule ID/name", count: "Count" };
export function offlineResultsCsv(result: OfflineAnalysisResult): string {
  const rows: string[][] = [["Field", "Value"]];
  const visit = (value: unknown, path: string) => {
    if (value !== null && typeof value === "object") Object.entries(value).forEach(([key, child]) => visit(child, path ? `${path}.${key}` : key));
    else rows.push([path, value == null ? "" : String(value)]);
  };
  visit(result, "");
  return rows.map(row => row.map(cell => `"${(/^[\s]*[=+@-]/.test(cell) ? "'" : "") + cell.replace(/"/g, '""')}"`).join(",")).join("\r\n");
}
export function PolicyOfflineTab({ policy, openRule }: { policy: string; openRule: (id: string) => void }) {
  const [analyses, setAnalyses] = useState<OfflineAnalysisSummary[]>([]), [canManage, setCanManage] = useState(false);
  const [page, setPage] = useState(0), [refresh, setRefresh] = useState(0), [loading, setLoading] = useState(true);
  const [file, setFile] = useState<File | null>(null), [preview, setPreview] = useState<{ headers: string[]; rows: string[][] } | null>(null);
  const [mapping, setMapping] = useState<Partial<Record<CsvField, string>>>({}), [confirmed, setConfirmed] = useState(false);
  const [result, setResult] = useState<OfflineAnalysisResult | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const [filter, setFilter] = useState("all"), [rulePage, setRulePage] = useState(0);
  const generation = useRef(0), resultRequest = useRef(0), inFlight = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; resultRequest.current++; }; }, []);
  useEffect(() => {
    let active = true; setLoading(true); setCanManage(false); setError("");
    if (!policy) { setLoading(false); return; }
    listOfflineAnalyses(policy, page).then(data => { if (active) { setAnalyses(data.analyses); setCanManage(data.canManage); } })
      .catch(() => { if (active) setError("Stored analyses could not be loaded."); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [policy, page, refresh]);
  const selectFile = async (selected?: File) => {
    const request = ++generation.current; setFile(null); setPreview(null); setConfirmed(false); setMapping({}); setError("");
    if (!selected) return;
    if (!selected.name.toLowerCase().endsWith(".csv") || selected.size > 100_000_000 || !selected.size) { setError("Select a nonempty .csv file, maximum 100 MB."); return; }
    try {
      const prefix = selected.slice(0, 21 * 65536); const text = await prefix.text();
      const parsed = parsePreview(text, prefix.size === selected.size);
      if (generation.current === request) { setFile(selected); setPreview(parsed); setMapping(detectMapping(parsed.headers)); }
    } catch { if (generation.current === request) setError("CSV preview failed. Use UTF-8, commas and valid quoting; records must not exceed 65536 characters."); }
  };
  const show = async (id: string) => {
    const request = ++resultRequest.current; setError(""); setResult(null);
    try { const data = await getOfflineAnalysis(id); if (mounted.current && resultRequest.current === request) { setResult(data); setRulePage(0); } }
    catch { if (mounted.current && resultRequest.current === request) setError("Analysis could not be loaded."); }
  };
  const submit = async () => {
    if (!file || !confirmed || inFlight.current) return;
    inFlight.current = true; setBusy(true); setError("");
    try {
      const data = await uploadOfflineAnalysis(policy, file, Object.fromEntries(Object.entries(mapping).filter(([, value]) => value)));
      if (mounted.current) { setFile(null); setPreview(null); setConfirmed(false); setRefresh(r => r + 1); await show(data.id); }
    } catch (failure) {
      if (mounted.current) {
        const code = (failure as { status?: number }).status;
        setError(code === 403 ? "Administrator permission is required." : code === 429 ? "Another analysis is running. Retry shortly."
          : code === 0 ? "The request timed out. Refresh the stored list before retrying; the server may have completed the analysis."
          : `Upload rejected. ${(failure as { body?: { error?: string } }).body?.error ?? "Check the CSV and column mapping."}`);
      }
    } finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  };
  const remove = async () => {
    if (!result || inFlight.current) return;
    inFlight.current = true; setBusy(true); setError("");
    try { await deleteOfflineAnalysis(result.id); if (mounted.current) { setResult(null); setRefresh(r => r + 1); } }
    catch { if (mounted.current) setError("Analysis could not be deleted."); }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  };
  const exportCsv = () => {
    if (!result) return;
    const url = URL.createObjectURL(new Blob([offlineResultsCsv(result)], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a"); link.href = url; link.download = "offline-analysis.csv"; link.click(); URL.revokeObjectURL(url);
  };
  const validMapping = required.every(f => mapping[f]) && new Set(Object.values(mapping).filter(Boolean)).size === Object.values(mapping).filter(Boolean).length;
  const rules = result?.rules.filter(rule => filter === "all" || filter === "zero" && rule.flowCount === 0 || filter === "members" && (rule.unseenMembers.length > 0 || rule.memberStatus === "UNKNOWN") || filter === "narrowing" && rule.narrowing.memberStatus) ?? [];
  return <Box role="tabpanel" aria-label="Offline analysis">
    <Typography variant="h6">Offline analysis</Typography>
    <Typography variant="body2">Analyze an operator-exported SIEM CSV for one firewall and period against this stored policy snapshot. No live ingestion or device changes. Files and raw rows are never saved.</Typography>
    {!policy && <Typography>Select a stored policy first.</Typography>}
    {loading && <Typography role="status">Loading stored analyses…</Typography>}
    {error && <Alert severity="error">{error}</Alert>}
    {canManage && <Box sx={{ my: 2 }}>
      <Button component="label" disabled={busy}>Choose SIEM CSV<input aria-label="SIEM CSV file" type="file" accept=".csv,text/csv" hidden onChange={e => { void selectFile(e.target.files?.[0]); e.target.value = ""; }} /></Button>
      <Typography variant="caption" display="block">UTF-8 CSV, up to 100 MB and 2,000,000 rows. Maximum 256 columns and 65536 characters per record. Count: 1–1,000,000,000. Time: ISO-8601 UTC or epoch seconds/milliseconds. TCP/UDP IPv4 attribution; other protocols and IPv6 remain UNKNOWN. Large samples may require splitting at 500,000 distinct rule/value aggregates.</Typography>
      {preview && <>
        <Typography variant="subtitle1">Confirm column mapping · {file?.name}</Typography>
        <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 2 }}>
          {fields.map(field => <TextField key={field} select label={labels[field]} value={mapping[field] ?? ""} required={required.includes(field)} disabled={busy}
            sx={{ minWidth: 180 }} onChange={e => { setMapping(m => ({ ...m, [field]: e.target.value })); setConfirmed(false); }}>
            <MenuItem value="">{required.includes(field) ? "Select column" : "Not mapped"}</MenuItem>
            {preview.headers.map(header => <MenuItem key={header} value={header}>{header}</MenuItem>)}
          </TextField>)}
        </Stack>
        <Typography variant="caption">Header detection is a suggestion. Action is informational; attribution uses the stored policy. A SIEM rule value must equal the stored ID, UID or unambiguous name.</Typography>
        <Box sx={{ overflow: "auto", maxHeight: 300 }}><Table size="small" aria-label="CSV preview, first 20 rows"><TableHead><TableRow>{preview.headers.map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
          <TableBody>{preview.rows.map((row, i) => <TableRow key={i}>{row.map((value, j) => <TableCell key={j}>{value}</TableCell>)}</TableRow>)}</TableBody></Table></Box>
        <FormControlLabel label="I confirm the mapping and that the sample is for this policy/firewall" control={<Checkbox checked={confirmed} disabled={busy || !validMapping} onChange={e => setConfirmed(e.target.checked)} />} />
        <Button disabled={busy || !confirmed || !validMapping} onClick={() => void submit()}>Upload and analyze</Button>
      </>}
    </Box>}
    {busy && <Stack direction="row" gap={1} role="status"><CircularProgress size={20} /><Typography>Processing… Large CSV samples may take several minutes.</Typography></Stack>}
    <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center" sx={{ my: 2 }}>
      <TextField select label="Stored analysis" sx={{ minWidth: 280 }} value={analyses.some(a => a.id === result?.id) ? result!.id : ""} disabled={busy} onChange={e => void show(e.target.value)}>
        <MenuItem value="" disabled>Select analysis</MenuItem>{analyses.map(a => <MenuItem key={a.id} value={a.id}>{a.fileName} · {a.createdAt} · {a.rowCount} rows</MenuItem>)}
      </TextField>
      <Button disabled={page === 0 || busy} onClick={() => setPage(p => p - 1)}>Previous analyses</Button>
      <Button disabled={analyses.length < 50 || busy} onClick={() => setPage(p => p + 1)}>Next analyses</Button>
      <Button disabled={busy} onClick={() => setRefresh(r => r + 1)}>Refresh list</Button>
    </Stack>
    {result && <>
      <Typography variant="h6">Sample summary</Typography>
      <Typography>{result.rowCount} rows · {result.invalidRows} invalid rows · {result.flowCount} valid sample flows · {result.attributedFlows} attributed flows</Typography>
      <Typography>Period {result.periodStart || "UNKNOWN"} – {result.periodEnd || "UNKNOWN"} · Uploaded {result.createdAt} · {result.uploader}</Typography>
      <Typography variant="body2">{result.attributionNote}</Typography><Typography variant="body2">{result.limitations}</Typography>
      {result.comparisonEnabled && <><Typography>SIEM rule agreement: {result.agreementCount} · Disagreement: {result.disagreementCount} · UNKNOWN: {result.comparisonUnknownCount}</Typography><Typography>{result.comparisonNote}</Typography></>}
      <Alert severity="warning">{result.caution}</Alert>
      <Button onClick={exportCsv}>Export results CSV</Button>{canManage && <Button disabled={busy} color="error" onClick={() => void remove()}>Delete stored analysis</Button>}
      <Typography variant="h6">Parse / UNKNOWN statistics</Typography>
      {[['Parse errors (rows)', result.parseStats], ['UNKNOWN (flows)', result.unknownStats], ['Verdicts (flows)', result.verdictStats]].map(([label, stats]) => <Box key={label as string}>
        <Typography variant="subtitle2">{label as string}</Typography>{(stats as OfflineAnalysisResult['parseStats']).length ? (stats as OfflineAnalysisResult['parseStats']).map(s => <Typography key={s.statReason}>{s.statReason}: {s.count}</Typography>) : <Typography>None</Typography>}
      </Box>)}
      <TextField select label="Rule findings" value={filter} onChange={e => { setFilter(e.target.value); setRulePage(0); }} sx={{ my: 2, minWidth: 260 }}>
        <MenuItem value="all">Rules by traffic</MenuItem><MenuItem value="zero">Zero-traffic rules</MenuItem><MenuItem value="members">Unseen members</MenuItem><MenuItem value="narrowing">Narrowing suggestions</MenuItem>
      </TextField>
      <Typography>{rules.length} rules · sorted by attributed flow count</Typography>
      {rules.slice(rulePage * 50, (rulePage + 1) * 50).map(rule => <RuleResult key={rule.ruleId} rule={rule} openRule={openRule} />)}
      <Button disabled={rulePage === 0} onClick={() => setRulePage(p => p - 1)}>Previous rules</Button><Button disabled={(rulePage + 1) * 50 >= rules.length} onClick={() => setRulePage(p => p + 1)}>Next rules</Button>
    </>}
  </Box>;
}
function RuleResult({ rule, openRule }: { rule: OfflineRule; openRule: (id: string) => void }) {
  return <Box sx={{ border: 1, borderColor: "divider", borderRadius: 1, p: 2, my: 1 }}>
    <Button onClick={() => openRule(rule.ruleId)}>{rule.number} · {rule.name || "Unnamed rule"}</Button>
    <Typography>{rule.sectionName} · {rule.action} · {rule.flowCount} attributed flows</Typography>
    <Typography variant="body2">Distinct sources: {rule.distinctSources} · Destinations: {rule.distinctDestinations} · Services: {rule.distinctServices} · First/last: {rule.firstTime || "UNKNOWN"} / {rule.lastTime || "UNKNOWN"}</Typography>
    {rule.zeroTraffic && <Typography>{rule.severity}: {rule.zeroTraffic} This does not establish an unused rule.</Typography>}
    <Box component="details"><Box component="summary">Top observed values (up to 50)</Box>{rule.topObserved.map((value, i) => <Typography key={i}>{value.observedSource ? `Source ${value.observedSource}` : value.observedDestination ? `Destination ${value.observedDestination}` : value.observedService}: {value.count}</Typography>)}</Box>
    <Typography variant="subtitle2">Unseen members</Typography>
    {rule.memberStatus === "UNKNOWN" ? <Typography>UNKNOWN: {rule.lookupReason}</Typography> : rule.unseenMembers.length ? rule.unseenMembers.map(member => <Typography key={`${member.dimension}:${member.memberId}`}>{member.dimension}: {member.name} — not seen in attributed sample flows</Typography>) : <Typography>No unseen resolved members in attributed sample flows.</Typography>}
    {rule.narrowing.memberStatus && <><Typography variant="subtitle2">Narrowing suggestion</Typography>{rule.narrowing.memberStatus === "UNKNOWN" ? <Typography>UNKNOWN: {rule.narrowing.suggestionNote}</Typography> : <>
      <Table size="small" aria-label={`Narrowing for rule ${rule.number}`}><TableHead><TableRow><TableCell>Observed sources</TableCell><TableCell>Observed destinations</TableCell><TableCell>Observed services</TableCell></TableRow></TableHead><TableBody><TableRow>
        <TableCell>{rule.narrowing.observedSource?.join(", ")}</TableCell><TableCell>{rule.narrowing.observedDestination?.join(", ")}</TableCell><TableCell>{rule.narrowing.observedService?.join(", ")}</TableCell>
      </TableRow></TableBody></Table>
      <Typography>Coverage: {rule.narrowing.coveragePercent}% of {rule.narrowing.coveredFlows} attributed flows. Preserve the existing action, zones, application and placement constraints. Combining independent sets may also admit unobserved combinations.</Typography>
      <Typography>{rule.narrowing.caution}</Typography>
    </>}</>}
  </Box>;
}
