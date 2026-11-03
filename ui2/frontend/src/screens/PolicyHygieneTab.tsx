import { useEffect, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { downloadPolicyHygiene, getPolicyHygiene, getPolicyRule, type PolicyHygienePage, type PolicyRuleDetail } from "../auth/adminApi";
import { RuleFields, unknownReasonGroup, unknownReasons, type ReasonGroup } from "./PolicyRuleViewer";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";

const classes = ["shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown"];
/** The `unknown` class is the analysis saying it could not decide, so its chip says so in words; the request key stays `unknown`. */
const classLabel = (cls: string) => cls === "unknown" ? "not provable (UNKNOWN)" : cls;

export interface HygieneCoverage { unknown: number | null; proven: number | null; denominator: number | null; sentence: string; /** Where the denominator came from, for the hover. */ basis: string }
/**
 * `Analysed: 26 proven · 845 not provable (of 871 rules)`. The counts are policy-wide (the chips show them whatever the
 * filter); the denominator is the catalog's recorded rule count, else the unfiltered rules total the caller passed. With
 * neither, the proven count is UNKNOWN -- it is never derived from a filtered page total.
 */
export function hygieneCoverage(counts: Record<string, number> | undefined, ruleCount: number | undefined): HygieneCoverage {
  const unknown = typeof counts?.unknown === "number" ? counts.unknown : null;
  const denominator = typeof ruleCount === "number" && Number.isFinite(ruleCount) ? ruleCount : null;
  if (unknown === null) return { unknown, proven: null, denominator, sentence: "Analysed: UNKNOWN (the not-provable count was not returned)", basis: "The hygiene read returned no `unknown` count." };
  if (denominator === null) return { unknown, proven: null, denominator, sentence: `Analysed: proven count UNKNOWN · ${unknown.toLocaleString("en-US")} not provable (rule total not recorded)`, basis: "The stored snapshot carries no rule count, so the proven share cannot be computed." };
  const proven = Math.max(0, denominator - unknown);
  return { unknown, proven, denominator, sentence: `Analysed: ${proven.toLocaleString("en-US")} proven · ${unknown.toLocaleString("en-US")} not provable (of ${denominator.toLocaleString("en-US")} rules)`,
    basis: `Proven = the policy's ${denominator.toLocaleString("en-US")} rules minus the ${unknown.toLocaleString("en-US")} the analysis could not decide.` };
}
export interface ReasonBreakdownRow { group: ReasonGroup; rules: number; /** The distinct raw reasons behind the group, for the hover. */ reasons: string[] }
/** A row's not-provable reasons: its shadow reason plus the `unknown` findings the table lists for it (the row's own findings, as rendered). */
const rowReasons = (row: PolicyHygienePage["rows"][number]) => unknownReasons({ ...row.hygiene, findings: row.findings });
/** The not-provable reasons of the rows given, grouped in plain words and counted per rule (a rule with two reasons in one group counts once). */
export function hygieneReasonBreakdown(rows: PolicyHygienePage["rows"]): ReasonBreakdownRow[] {
  const byGroup = new Map<string, ReasonBreakdownRow>();
  for (const row of rows) {
    const seen = new Set<string>();
    for (const reason of rowReasons(row)) {
      const group = unknownReasonGroup(reason);
      const entry = byGroup.get(group.key) ?? { group, rules: 0, reasons: [] };
      if (!seen.has(group.key)) { entry.rules++; seen.add(group.key); }
      if (!entry.reasons.includes(reason)) entry.reasons.push(reason);
      byGroup.set(group.key, entry);
    }
  }
  return [...byGroup.values()].sort((a, b) => b.rules - a.rules);
}

export function PolicyHygieneTab({ policy, openRule, openObject, initialClass = "", ruleCount }: { policy: string; openRule: (id: string, days: number) => void; openObject: (id: string) => void; initialClass?: string; /** The policy's rule count from the catalog or the unfiltered rules read; the coverage denominator. */ ruleCount?: number }) {
  const [data, setData] = useState<PolicyHygienePage | null>(null), [error, setError] = useState("");
  const [findingClass, setClass] = useState(classes.includes(initialClass) ? initialClass : ""), [severity, setSeverity] = useState(""), [days, setDays] = useState(90), [page, setPage] = useState(0);
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
  const coverage = data ? hygieneCoverage(data.counts, ruleCount) : null;
  const breakdown = data ? hygieneReasonBreakdown(data.rows) : [];
  const unknownOnPage = data ? data.rows.filter(row => rowReasons(row).length > 0).length : 0;
  const pageCovers = data ? (data.total <= data.pageSize && page === 0 ? `all ${data.total.toLocaleString("en-US")} matching rules` : `the ${data.rows.length} rules on this page (of ${data.total.toLocaleString("en-US")} matching; other pages are not summed)`) : "";
  return <Box role="tabpanel" aria-label="Hygiene">
    <Typography variant="body2">Stored-snapshot analysis. A rule is <b>proven</b> only when the stored evidence decides it; everything else is UNKNOWN (not provable), never guessed. Counts are rules per class.</Typography>
    {/* Coverage first: how much of the policy the analysis could decide, then why it could not decide the rest, in plain words. */}
    {(coverage || error) && <Box role="region" aria-label="Analysis coverage" data-budget-reached={data?.budgetReached ? "true" : undefined} sx={{ my: 2, p: 1.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, bgcolor: m3.scLowest }}>
      <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
        <Typography variant="body2" title={coverage?.basis} sx={{ fontWeight: 600 }}>{coverage ? coverage.sentence : "Analysed: UNKNOWN (the hygiene read failed)"}</Typography>
        {data?.budgetReached && <Chip size="small" color="warning" label="Analysis limit reached" title="The analysis stopped at its budget; the rules after that point were not compared and count as not provable." />}
      </Stack>
      {data?.budgetReached && <Typography role="status" variant="caption" display="block" sx={{ color: m3.warningInk }}>UNKNOWN because the analysis budget was reached. Remaining rules were not fully analyzed; their not-provable count is a limit of the analysis, not a finding.</Typography>}
      {data && breakdown.length > 0 && <>
        <Typography variant="caption" display="block" sx={{ mt: 1, color: m3.onSurfaceVar }}>Why not provable · from {pageCovers}{unknownOnPage ? ` · ${unknownOnPage} of them not provable` : ""}{findingClass !== "unknown" && data.total > data.pageSize ? ". Filter by \"not provable\" to page through every such rule." : ""}</Typography>
        <Box component="ul" aria-label="Not provable reasons" sx={{ m: 0, mt: 0.5, p: 0, listStyle: "none", display: "grid", gap: 0.5, gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))" }}>
          {breakdown.map(row => <Box component="li" key={row.group.key} data-reason-group={row.group.key} title={row.reasons.join("\n")} sx={{ fontSize: 12, display: "flex", gap: 1, alignItems: "baseline", minWidth: 0 }}>
            <Box component="span" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700, minWidth: 36, textAlign: "right" }}>{row.rules.toLocaleString("en-US")}</Box>
            <Box sx={{ minWidth: 0 }}><Box component="span" sx={{ fontWeight: 600 }}>{row.group.label}</Box>
              <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, lineHeight: 1.25 }}>{row.group.explain}</Typography></Box>
          </Box>)}
        </Box>
      </>}
      {data && breakdown.length === 0 && (coverage?.unknown ?? 0) > 0 && <Typography variant="caption" display="block" sx={{ mt: 1, color: m3.onSurfaceVar }}>
        No not-provable rule is on this page; filter by "not provable" to see why the {coverage!.unknown!.toLocaleString("en-US")} could not be decided.</Typography>}
    </Box>}
    <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 2 }}>
      {/* Loading… while the read is in flight, then the count; UNKNOWN when the read failed. Never a dash. */}
      {classes.map(cls => <Chip key={cls} label={`${classLabel(cls)}: ${data ? data.counts[cls] ?? "UNKNOWN" : error ? "UNKNOWN" : "Loading…"}`} onClick={() => { setClass(cls); setPage(0); }}
        title={cls === "unknown" ? "Rules the analysis could not decide: an evidence limit, not a finding. Click to list them with their reasons." : undefined} />)}
    </Stack>
    <Stack direction="row" gap={2} flexWrap="wrap" sx={{ mb: 2 }}>
      <TextField select size="small" label="Finding class" value={findingClass} onChange={e => { setClass(e.target.value); setPage(0); }} sx={{ minWidth: 170 }}>
        <MenuItem value="">All classes</MenuItem>{classes.map(cls => <MenuItem key={cls} value={cls}>{classLabel(cls)}</MenuItem>)}
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
    {data && data.total === 0 && <Typography role="status">No hygiene findings match these filters.</Typography>}
    {data && data.total > 0 && <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Policy hygiene findings">
      <TableHead><TableRow>{["Rule", "Findings", "Counter evidence", "Shadow comparison"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
      <TableBody>{data.rows.map(row => <TableRow key={row.ruleId}>
        <TableCell><Button onClick={() => openRule(row.ruleId, days)}>{row.number} · {row.name || "Unnamed rule"}</Button></TableCell>
        <TableCell>{row.findings.map((f, i) => <Typography key={i} variant="body2" sx={{ color: f.severity === "HIGH" ? m3.error : undefined }}>
          {f.findingClass === "unknown" ? "UNKNOWN because" : `${f.severity} · ${f.findingClass}:`} {f.evidence}</Typography>)}
          {row.findings.some(f => f.findingClass === "broad") && <Typography variant="caption">Why this level: {row.hygiene.permissiveness.reasons.join("; ")} · Score {row.hygiene.permissiveness.score ?? "UNKNOWN"}</Typography>}
        </TableCell>
        <TableCell><Typography variant="caption">{row.hygiene.counterWindow}<br />Source: {row.hygiene.hitSource} · Collected: {formatStamp(row.hygiene.hitsCollectedAt)}</Typography></TableCell>
        <TableCell>{row.hygiene.coveringRuleId ? <Button onClick={() => setPair({ covered: row.ruleId, covering: row.hygiene.coveringRuleId! })}>Compare rules</Button>
          : row.hygiene.shadowStatus === "UNKNOWN" ? <>
            <Typography variant="body2">UNKNOWN because {row.hygiene.shadowReason ?? "reason not recorded"}</Typography>
            <Typography variant="caption" data-reason-group={unknownReasonGroup(row.hygiene.shadowReason).key} sx={{ color: m3.onSurfaceVar }}>{unknownReasonGroup(row.hygiene.shadowReason).label}</Typography>
          </> : row.hygiene.shadowStatus === "NOT_SHADOWED" ? "Not shadowed (proven)" : "No full shadow proven"}</TableCell>
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
