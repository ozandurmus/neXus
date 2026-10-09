import { useEffect, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { downloadPolicyHygiene, getPolicyHygiene, getPolicyRule, type PolicyHygienePage, type PolicyRuleDetail } from "../auth/adminApi";
import { RuleFields } from "./PolicyRuleViewer";
import { m3 } from "../theme/m3Theme";

const classes = ["shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown"];
export function PolicyHygieneTab({ policy, openRule, openObject }: { policy: string; openRule: (id: string, days: number) => void; openObject: (id: string) => void }) {
  const [data, setData] = useState<PolicyHygienePage | null>(null), [error, setError] = useState("");
  const [findingClass, setClass] = useState(""), [severity, setSeverity] = useState(""), [days, setDays] = useState(90), [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0), [exporting, setExporting] = useState(false);
  const [pair, setPair] = useState<{ covered: string; covering: string } | null>(null);
  const validDays = Number.isInteger(days) && days >= 1 && days <= 36500;
  useEffect(() => {
    let active = true; setData(null); setError(""); setPair(null);
    if (!policy || !validDays) return;
    getPolicyHygiene(policy, page, findingClass, severity, days).then(result => {
      if (!Array.isArray(result.rows) || !result.counts) throw new Error("Invalid hygiene response");
      if (active) setData(result);
    }).catch(() => { if (active) setError("Hygiene could not be loaded."); });
    return () => { active = false; };
  }, [policy, page, findingClass, severity, days, revision, validDays]);
  const exportCsv = async () => {
    setExporting(true); setError("");
    try {
      const blob = await downloadPolicyHygiene(policy, findingClass, severity, days);
      const url = URL.createObjectURL(blob), a = document.createElement("a");
      a.href = url; a.download = "policy-hygiene.csv"; a.click(); URL.revokeObjectURL(url);
    } catch { setError("Hygiene CSV could not be exported."); }
    finally { setExporting(false); }
  };
  if (!policy) return <Typography role="status">Select a stored policy to view hygiene.</Typography>;
  return <Box role="tabpanel" aria-label="Hygiene">
    <Typography variant="body2">Stored-snapshot analysis. Counts are rules per class; UNKNOWN means evidence is insufficient.</Typography>
    <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 2 }}>
      {classes.map(cls => <Chip key={cls} label={`${cls}: ${data?.counts[cls] ?? "—"}`} onClick={() => { setClass(cls); setPage(0); }} />)}
    </Stack>
    <Stack direction="row" gap={2} flexWrap="wrap" sx={{ mb: 2 }}>
      <TextField select size="small" label="Finding class" value={findingClass} onChange={e => { setClass(e.target.value); setPage(0); }} sx={{ minWidth: 170 }}>
        <MenuItem value="">All classes</MenuItem>{classes.map(cls => <MenuItem key={cls} value={cls}>{cls}</MenuItem>)}
      </TextField>
      <TextField select size="small" label="Severity" value={severity} onChange={e => { setSeverity(e.target.value); setPage(0); }} sx={{ minWidth: 140 }}>
        <MenuItem value="">All severities</MenuItem>{["HIGH", "MEDIUM", "LOW"].map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
      </TextField>
      <TextField size="small" type="number" label="Unused threshold (days)" value={days} error={!validDays} helperText={!validDays ? "Enter 1–36500 whole days" : undefined}
        onChange={e => { setDays(Number(e.target.value)); setPage(0); }} inputProps={{ min: 1, max: 36500 }} />
      <Button disabled={!data || exporting || !validDays} onClick={() => void exportCsv()}>{exporting ? "Exporting…" : "Export hygiene CSV"}</Button>
    </Stack>
    {error && <Box role="alert">{error}<Button onClick={() => setRevision(n => n + 1)}>Retry hygiene</Button></Box>}
    {!data && !error && validDays && <Typography role="status">Loading hygiene…</Typography>}
    {data?.budgetReached && <Typography role="status" color="warning.main">UNKNOWN because the analysis budget was reached. Remaining rules were not fully analyzed.</Typography>}
    {data && data.total === 0 && <Typography role="status">No hygiene findings match these filters.</Typography>}
    {data && data.total > 0 && <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Policy hygiene findings">
      <TableHead><TableRow>{["Rule", "Findings", "Counter evidence", "Shadow comparison"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
      <TableBody>{data.rows.map(row => <TableRow key={row.ruleId}>
        <TableCell><Button onClick={() => openRule(row.ruleId, days)}>{row.number} · {row.name || "Unnamed rule"}</Button></TableCell>
        <TableCell>{row.findings.map((f, i) => <Typography key={i} variant="body2" sx={{ color: f.severity === "HIGH" ? m3.error : undefined }}>
          {f.findingClass === "unknown" ? "UNKNOWN because" : `${f.severity} · ${f.findingClass}:`} {f.evidence}</Typography>)}
          {row.findings.some(f => f.findingClass === "broad") && <Typography variant="caption">Why this level: {row.hygiene.permissiveness.reasons.join("; ")} · Score {row.hygiene.permissiveness.score ?? "UNKNOWN"}</Typography>}
        </TableCell>
        <TableCell><Typography variant="caption">{row.hygiene.counterWindow}<br />Source: {row.hygiene.hitSource} · Collected: {row.hygiene.hitsCollectedAt ?? "UNKNOWN"}</Typography></TableCell>
        <TableCell>{row.hygiene.coveringRuleId ? <Button onClick={() => setPair({ covered: row.ruleId, covering: row.hygiene.coveringRuleId! })}>Compare rules</Button>
          : row.hygiene.shadowStatus === "UNKNOWN" ? `UNKNOWN because ${row.hygiene.shadowReason}` : "No full shadow proven"}</TableCell>
      </TableRow>)}</TableBody>
    </Table></Box>}
    {data && <Stack direction="row" alignItems="center"><Button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous hygiene page</Button>
      <Typography variant="caption">{data.total} matching rules · Page {page + 1}</Typography>
      <Button disabled={(page + 1) * data.pageSize >= data.total} onClick={() => setPage(p => p + 1)}>Next hygiene page</Button></Stack>}
    {pair && <ShadowComparison key={`${policy}:${pair.covered}:${pair.covering}`} policy={policy} pair={pair} days={days} openRule={openRule} openObject={openObject} />}
  </Box>;
}
function ShadowComparison({ policy, pair, days, openRule, openObject }: { policy: string; pair: { covered: string; covering: string }; days: number; openRule: (id: string, days: number) => void; openObject: (id: string) => void }) {
  const [rules, setRules] = useState<PolicyRuleDetail[] | null>(null), [error, setError] = useState(false);
  useEffect(() => {
    let active = true;
    Promise.all([getPolicyRule(policy, pair.covering, days), getPolicyRule(policy, pair.covered, days)])
      .then(result => { if (active) setRules(result); }).catch(() => { if (active) setError(true); });
    return () => { active = false; };
  }, [policy, pair.covered, pair.covering, days]);
  if (error) return <Typography role="alert">Rule comparison could not be loaded.</Typography>;
  if (!rules) return <Typography role="status">Loading rule comparison…</Typography>;
  return <Box aria-label="Shadow comparison" sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2, mt: 2 }}>
    {rules.map((detail, i) => <Box key={i} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 2 }}>
      <Typography variant="h6">{i === 0 ? "Covering rule" : "Shadowed rule"}</Typography>
      <Button onClick={() => openRule(detail.rule.id, days)}>{detail.rule.name || "Unnamed rule"}</Button>
      <RuleFields rule={detail.rule} objects={new Map(detail.objects.map(o => [o.id, o]))} openObject={openObject} />
    </Box>)}
  </Box>;
}
