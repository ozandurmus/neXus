import { useEffect, useRef, useState } from "react";
import { Alert, Box, Button, Checkbox, CircularProgress, FormControlLabel, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { deleteOfflineAnalysis, getOfflineAnalysis, listOfflineAnalyses, uploadOfflineAnalysis, type OfflineAnalysisResult, type OfflineAnalysisSummary, type OfflineRule } from "../auth/adminApi";
import { csvText, detectMapping, fields, parsePreview, required, type CsvField } from "./offlineCsv";
import { RuleUsagePicker, type RuleChoice } from "./RuleUsagePicker";
import { NarrowingSuggestion, RuleUsageResult, ruleUsageCsv } from "./RuleUsageResult";
import { PLANE_ADMIN, ROLE_LOCK_LABEL, useRoleAllows } from "../shell/actionAccess";
import { plural } from "../shell/plural";
import { SnapshotFreshnessChip, isPolicyBusy, policyFailureText, POLICY_BUSY_MESSAGE } from "./PolicyRuleViewer";
import { formatStamp } from "../shell/time";

const labels: Record<CsvField, string> = { source: "Source IP", destination: "Destination IP", port: "Destination port", protocol: "Protocol",
  time: "Time", action: "Action", sourceZone: "Source zone", destinationZone: "Destination zone", rule: "SIEM rule ID/name", count: "Count" };
export function offlineResultsCsv(result: OfflineAnalysisResult): string {
  const rows: string[][] = [["Field", "Value"]];
  const visit = (value: unknown, path: string) => {
    if (value !== null && typeof value === "object") Object.entries(value).forEach(([key, child]) => visit(child, path ? `${path}.${key}` : key));
    else rows.push([path, value == null ? "" : String(value)]);
  };
  visit(result, "");
  return csvText(rows);
}
/** The two analyses of Rule object usage: one rule's objects, or every rule of the policy (the former Offline analysis). */
export type RuleUsageMode = "rule" | "policy";
/** What the server said about the rule of a One-rule upload (ROU-1): refused by name, never resolved by a guess. */
export function ruleRefusalText(code: string | undefined): string | null {
  if (code?.startsWith("RULE_NOT_FOUND")) return "The rule was not found in this policy snapshot. Choose it from the list or check the pasted rule UID / id. Nothing was analysed.";
  if (code?.startsWith("RULE_AMBIGUOUS")) return "That rule UID / id matches more than one rule of this snapshot. Choose the rule from the list. Nothing was analysed.";
  return null;
}
/**
 * The upload, the column mapping, the stored analyses and their result, for one stored policy. `mode` is what the next
 * upload asks for; a stored analysis is shown in the view of the mode it was made in, whatever the switch says.
 */
export function PolicyOfflineTab({ policy, openRule, mode = "policy" }: { policy: string; openRule: (id: string) => void; mode?: RuleUsageMode }) {
  const [rule, setRule] = useState<RuleChoice | null>(null);
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
      .catch(failure => { if (active) setError(policyFailureText(failure, "Stored analyses could not be loaded.")); }).finally(() => { if (active) setLoading(false); });
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
    catch (failure) { if (mounted.current && resultRequest.current === request) setError(policyFailureText(failure, "Analysis could not be loaded.")); }
  };
  const submit = async () => {
    if (!file || !confirmed || inFlight.current || (mode === "rule" && !rule)) return;
    inFlight.current = true; setBusy(true); setError("");
    try {
      const columns: Record<string, string> = Object.fromEntries(Object.entries(mapping).filter(([, value]) => value));
      // One-rule mode names its rule inside the same mapping document; whole-policy mode sends the columns alone, as before.
      const data = await uploadOfflineAnalysis(policy, file, mode === "rule" && rule ? { ...columns, ruleId: rule.ruleId } : columns);
      if (mounted.current) { setFile(null); setPreview(null); setConfirmed(false); setRefresh(r => r + 1); await show(data.id); }
    } catch (failure) {
      if (mounted.current) {
        const code = (failure as { status?: number }).status;
        const refusal = code === 400 ? ruleRefusalText((failure as { body?: { error?: string } }).body?.error) : null;
        // The bulkhead's wait is not a rejected CSV: the file and mapping are fine, the server is busy.
        setError(refusal ?? (isPolicyBusy(failure) ? POLICY_BUSY_MESSAGE : code === 403 ? "Administrator permission is required." : code === 429 ? "Another analysis is running. Retry shortly."
          : code === 0 ? "The request timed out. Refresh the stored list before retrying; the server may have completed the analysis."
          : `Upload rejected. ${(failure as { body?: { error?: string } }).body?.error ?? "Check the CSV and column mapping."}`));
      }
    } finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  };
  const remove = async () => {
    if (!result || inFlight.current) return;
    inFlight.current = true; setBusy(true); setError("");
    try { await deleteOfflineAnalysis(result.id); if (mounted.current) { setResult(null); setRefresh(r => r + 1); } }
    catch (failure) { if (mounted.current) setError(policyFailureText(failure, "Analysis could not be deleted.")); }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  };
  const exportCsv = () => {
    if (!result) return;
    const oneRule = result.mode === "ONE_RULE";
    const url = URL.createObjectURL(new Blob([oneRule ? ruleUsageCsv(result) : offlineResultsCsv(result)], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a"); link.href = url; link.download = oneRule ? "rule-object-usage.csv" : "policy-log-analysis.csv"; link.click(); URL.revokeObjectURL(url);
  };
  const validMapping = required.every(f => mapping[f]) && new Set(Object.values(mapping).filter(Boolean)).size === Object.values(mapping).filter(Boolean).length;
  // Before a policy is chosen the server has not said who may upload; the session's plane list already says a role
  // without Admin never can (post-release review 2026-10-18, 2.5), so the locked dropzone is there from the first look.
  const roleMayUpload = useRoleAllows(PLANE_ADMIN);
  const lockedReason = !policy ? (roleMayUpload ? "Select a stored policy first" : ROLE_LOCK_LABEL) : ROLE_LOCK_LABEL;
  const storedCount = policy ? analyses.length : 0;
  const rules = result?.rules.filter(rule => filter === "all" || filter === "zero" && rule.flowCount === 0 || filter === "members" && (rule.unseenMembers.length > 0 || rule.memberStatus === "UNKNOWN") || filter === "narrowing" && rule.narrowing.memberStatus) ?? [];
  const shownMode: RuleUsageMode | null = result ? (result.mode === "ONE_RULE" ? "rule" : "policy") : null;
  return <Box role="region" aria-label={mode === "rule" ? "One rule analysis" : "Whole policy analysis"} data-usage-mode={mode}>
    <Typography variant="body2">{mode === "rule"
      ? "Upload an operator-exported SIEM CSV for one firewall and period. Its rows are attributed to the chosen rule of this stored policy snapshot, and each of the rule's objects is reported as used or not seen. No live ingestion or device changes. Files and raw rows are never saved."
      : "Analyze an operator-exported SIEM CSV for one firewall and period against this stored policy snapshot. No live ingestion or device changes. Files and raw rows are never saved."}</Typography>
    {loading && <Typography role="status">Loading stored analyses…</Typography>}
    {error && <Alert severity="error">{error}</Alert>}
    {/* A role that cannot upload still sees where the upload goes, locked and saying why (UI review 2026-10-18, P0-4B);
        the same zone before a policy is chosen, so the tab never opens on an empty form. */}
    {!loading && (!policy || !canManage) && <Box role="group" aria-label="SIEM CSV upload" aria-disabled="true"
      sx={{ my: 2, p: 2.5, border: "2px dashed", borderColor: "divider", borderRadius: 2, textAlign: "center", color: "text.disabled", bgcolor: "action.disabledBackground" }}>
      <Typography>Drop a SIEM CSV here · {lockedReason}</Typography>
      <Typography variant="caption" display="block">
        {!policy ? `${plural(storedCount, "stored analysis", "stored analyses")} · select a stored policy to list its analyses.`
          : analyses.length === 0 ? "No stored analysis for this policy." : `${plural(analyses.length, "stored analysis", "stored analyses")} for this policy; choose one below to read it.`}
      </Typography>
    </Box>}
    {canManage && <Box sx={{ my: 2 }}>
      {mode === "rule" && <RuleUsagePicker policy={policy} value={rule} onChange={setRule} disabled={busy} />}
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
        <Button disabled={busy || !confirmed || !validMapping || (mode === "rule" && !rule)} onClick={() => void submit()}>Upload and analyze</Button>
        {mode === "rule" && !rule && <Typography variant="caption" display="block">Choose the rule first: a One rule analysis is made for exactly one rule.</Typography>}
      </>}
    </Box>}
    {busy && <Stack direction="row" gap={1} role="status"><CircularProgress size={20} /><Typography>Processing… Large CSV samples may take several minutes.</Typography></Stack>}
    <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center" sx={{ my: 2 }}>
      <TextField select label="Stored analysis" sx={{ minWidth: 280 }} value={analyses.some(a => a.id === result?.id) ? result!.id : ""} disabled={busy} onChange={e => void show(e.target.value)}>
        <MenuItem value="" disabled>Select analysis</MenuItem>{analyses.map(a => <MenuItem key={a.id} value={a.id}>{a.fileName} · {a.createdAt} · {a.rowCount} rows{a.mode ? ` · ${a.mode === "ONE_RULE" ? "One rule" : "Whole policy"}` : ""}</MenuItem>)}
      </TextField>
      <Button disabled={page === 0 || busy} onClick={() => setPage(p => p - 1)}>Previous analyses</Button>
      <Button disabled={analyses.length < 50 || busy} onClick={() => setPage(p => p + 1)}>Next analyses</Button>
      <Button disabled={busy} onClick={() => setRefresh(r => r + 1)}>Refresh list</Button>
    </Stack>
    {result && shownMode !== mode && <Typography variant="body2" role="note" sx={{ mb: 1 }}>
      This stored analysis is a {shownMode === "rule" ? "One rule" : "Whole policy"} analysis and is shown as one.</Typography>}
    {result && shownMode === "rule" && <RuleUsageResult result={result} openRule={openRule} onExport={exportCsv} onDelete={canManage ? () => void remove() : undefined} busy={busy} />}
    {result && shownMode === "policy" && <>
      <Typography variant="h6">Sample summary</Typography>
      <Typography>{result.rowCount} rows · {result.invalidRows} invalid rows · {result.flowCount} valid sample flows · {result.attributedFlows} attributed flows</Typography>
      {/* The stored snapshot the sample was matched against: its age bounds how far the attribution can be trusted. */}
      <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center" data-evaluated-snapshot="true">
        <Typography>Evaluated against snapshot collected {result.collectedAt ? formatStamp(result.collectedAt) : "UNKNOWN"}</Typography>
        <SnapshotFreshnessChip at={result.collectedAt} now={new Date()} />
      </Stack>
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
    <NarrowingSuggestion rule={rule} />
  </Box>;
}
