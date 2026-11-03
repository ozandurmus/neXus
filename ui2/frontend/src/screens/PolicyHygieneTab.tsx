import { useEffect, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { downloadPolicyHygiene, getPolicyHygiene, getPolicyRule, type PolicyHygieneCoverage, type PolicyHygienePage, type PolicyRuleDetail } from "../auth/adminApi";
import { RuleFields, policyFailureText, unknownReasonGroup, unknownReasons, type ReasonGroup } from "./PolicyRuleViewer";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";

/** Proven classes first, the coverage view ("not provable") last: it is where the analysis could not decide, not a finding. */
const classes = ["conflict", "shadowed", "broad", "unused", "expired", "disabled", "unknown"];
/** The `unknown` class is the analysis saying it could not decide, so its chip says so in words; the request key stays `unknown`. */
const classLabel = (cls: string) => cls === "unknown" ? "not provable (UNKNOWN)" : cls;
const n = (value: number) => value.toLocaleString("en-US");

export interface HygieneCoverage {
  /** Shadow analysis: rules the comparison decided (NOT_SHADOWED, REDUNDANT, CONFLICT) against those it could not. */
  shadow: { proven: number; unknown: number; rules: number } | null;
  /** Hit counters: rules whose counters are stored against those with none. Independent of the shadow question. */
  hits: { collected: number; missing: number; rules: number } | null;
  sentence: string; hitsSentence: string; /** Where the numbers come from, for the hover. */ basis: string;
}
/**
 * `Shadow analysis: 3,049 proven · 102,661 not provable (of 105,710 rules)` and, separately, `Hit counters: 71,200
 * stored · 34,510 not collected`. Both come from the service's policy-wide coverage, never from a page: missing hit
 * counters make the usage finding unprovable, not the shadow result, so a policy with every shadow decided and no
 * counters shows every rule proven on the first line and none on the second. Without the coverage (an older service)
 * both are UNKNOWN; nothing is derived from the per-class chip counts, whose `unknown` mixes the two questions.
 */
export function hygieneCoverage(coverage: PolicyHygieneCoverage | undefined): HygieneCoverage {
  const whole = (value: unknown) => typeof value === "number" && Number.isInteger(value) && value >= 0;
  if (!coverage || !["rules", "shadowProven", "shadowUnknown", "hitsCollected", "hitsMissing"].every(key => whole(coverage[key as keyof PolicyHygieneCoverage])))
    return { shadow: null, hits: null, sentence: "Shadow analysis: coverage UNKNOWN (not returned by the service)", hitsSentence: "Hit counters: coverage UNKNOWN", basis: "The hygiene read carried no policy-wide coverage; the chips still count rules per class." };
  const shadow = { proven: coverage.shadowProven, unknown: coverage.shadowUnknown, rules: coverage.rules };
  const hits = { collected: coverage.hitsCollected, missing: coverage.hitsMissing, rules: coverage.rules };
  return { shadow, hits,
    sentence: `Shadow analysis: ${n(shadow.proven)} proven · ${n(shadow.unknown)} not provable (of ${n(shadow.rules)} rules)`,
    hitsSentence: `Hit counters: ${n(hits.collected)} stored · ${n(hits.missing)} not collected`,
    basis: `Counted over all ${n(coverage.rules)} rules of the stored policy. Proven = the comparison decided shadowed, conflicting or clean; not provable = it could not decide. Hit counters are a separate question: without them only the usage finding is UNKNOWN.` };
}
export interface ReasonBreakdownRow { group: ReasonGroup; rules: number; /** The distinct raw reasons behind the group, for the hover. */ reasons: string[] }
/** A row's not-provable reasons: its shadow reason plus the `unknown` findings the table lists for it (the row's own findings, as rendered). */
const rowReasons = (row: PolicyHygienePage["rows"][number]) => unknownReasons({ ...row.hygiene, findings: row.findings });
/** The reason as the engineer reads it: the `Shadowing: …` evidence wrapper and the closing stop removed. */
const plainReason = (evidence: string) => evidence.replace(/^Shadowing: /, "").replace(/\.$/, "");
/**
 * The not-provable reasons of the whole policy, grouped in plain words. From the service's policy-wide counts when it
 * sent them (each is one evidence string with the number of rules carrying it; a rule with two reasons in one group is
 * then counted once per reason -- an upper bound the hover makes explicit); else from the rows given, counted per rule.
 */
export function hygieneReasonBreakdown(rows: PolicyHygienePage["rows"], policyWide?: PolicyHygienePage["unknownReasons"]): ReasonBreakdownRow[] {
  const byGroup = new Map<string, ReasonBreakdownRow>();
  if (policyWide) {
    for (const item of policyWide) {
      if (typeof item?.evidence !== "string" || typeof item.rules !== "number") continue;
      const reason = plainReason(item.evidence), group = unknownReasonGroup(reason);
      const entry = byGroup.get(group.key) ?? { group, rules: 0, reasons: [] };
      entry.rules += item.rules;
      if (!entry.reasons.includes(reason)) entry.reasons.push(reason);
      byGroup.set(group.key, entry);
    }
    return [...byGroup.values()].sort((a, b) => b.rules - a.rules);
  }
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

export function PolicyHygieneTab({ policy, openRule, openObject, initialClass = "" }: { policy: string; openRule: (id: string, days: number) => void; openObject: (id: string) => void; initialClass?: string }) {
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
    }).catch(failure => { if (active) setError(policyFailureText(failure, "Hygiene could not be loaded.")); });
    return () => { active = false; };
  }, [policy, page, findingClass, severity, days, revision, validDays]);
  const exportCsv = async () => {
    setExporting(true); setError("");
    try {
      const blob = await downloadPolicyHygiene(policy, findingClass, severity, days);
      const url = URL.createObjectURL(blob), a = document.createElement("a");
      a.href = url; a.download = "policy-hygiene.csv"; a.click(); URL.revokeObjectURL(url);
    } catch (failure) { setError(policyFailureText(failure, "Hygiene CSV could not be exported.")); }
    finally { setExporting(false); }
  };
  if (!policy) return <Typography role="status">Select a stored policy to view hygiene.</Typography>;
  const coverage = data ? hygieneCoverage(data.coverage) : null;
  const policyWide = data ? Array.isArray(data.unknownReasons) : false;
  const breakdown = data ? hygieneReasonBreakdown(data.rows, policyWide ? data.unknownReasons : undefined) : [];
  const unknownOnPage = data ? data.rows.filter(row => rowReasons(row).length > 0).length : 0;
  const pageCovers = data ? (data.total <= data.pageSize && page === 0 ? `all ${n(data.total)} matching rules` : `the ${data.rows.length} rules on this page (of ${n(data.total)} matching; other pages are not summed)`) : "";
  const unknownTotal = typeof data?.counts.unknown === "number" ? data.counts.unknown : 0;
  return <Box role="tabpanel" aria-label="Hygiene">
    <Typography variant="body2">Stored-snapshot analysis. A rule is <b>proven</b> only when the stored evidence decides it; everything else is UNKNOWN (not provable), never guessed. Counts are rules per class. The table lists proven findings; rules whose only result is not provable are under "not provable (UNKNOWN)".</Typography>
    {/* Coverage first: how much of the policy the analysis could decide, hit counters as their own question, then why it could not decide the rest, in plain words. */}
    {(coverage || error) && <Box role="region" aria-label="Analysis coverage" data-budget-reached={data?.budgetReached ? "true" : undefined} sx={{ my: 2, p: 1.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, bgcolor: m3.scLowest }}>
      <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
        <Typography variant="body2" title={coverage?.basis} sx={{ fontWeight: 600 }}>{coverage ? coverage.sentence : "Shadow analysis: coverage UNKNOWN (the hygiene read failed)"}</Typography>
        {data?.budgetReached && <Chip size="small" color="warning" label="Analysis limit reached" title="The analysis stopped at its budget; the rules after that point were not compared and count as not provable." />}
      </Stack>
      {coverage && <Typography variant="body2" title={coverage.basis} data-hits-coverage={coverage.hits ? "true" : "unknown"} sx={{ color: coverage.hits?.missing ? m3.warningInk : m3.onSurfaceVar }}>
        {coverage.hitsSentence}{coverage.hits?.missing ? " · a full refresh (incl. hit counts) collects them; the usage finding is UNKNOWN without them" : ""}</Typography>}
      {data?.budgetReached && <Typography role="status" variant="caption" display="block" sx={{ color: m3.warningInk }}>UNKNOWN because the analysis budget was reached. Remaining rules were not fully analyzed; their not-provable count is a limit of the analysis, not a finding.</Typography>}
      {data && breakdown.length > 0 && <>
        <Typography variant="caption" display="block" sx={{ mt: 1, color: m3.onSurfaceVar }}>Why not provable · {policyWide
          ? `across the whole policy (rules per reason; a rule with two reasons in one group is counted for each)`
          : `from ${pageCovers}${unknownOnPage ? ` · ${unknownOnPage} of them not provable` : ""}${findingClass !== "unknown" && data.total > data.pageSize ? ". Filter by \"not provable\" to page through every such rule." : ""}`}</Typography>
        <Box component="ul" aria-label="Not provable reasons" sx={{ m: 0, mt: 0.5, p: 0, listStyle: "none", display: "grid", gap: 0.5, gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))" }}>
          {breakdown.map(row => <Box component="li" key={row.group.key} data-reason-group={row.group.key} title={row.reasons.join("\n")} sx={{ fontSize: 12, display: "flex", gap: 1, alignItems: "baseline", minWidth: 0 }}>
            <Box component="span" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700, minWidth: 36, textAlign: "right" }}>{n(row.rules)}</Box>
            <Box sx={{ minWidth: 0 }}><Box component="span" sx={{ fontWeight: 600 }}>{row.group.label}</Box>
              <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, lineHeight: 1.25 }}>{row.group.explain}</Typography></Box>
          </Box>)}
        </Box>
      </>}
      {data && breakdown.length === 0 && unknownTotal > 0 && <Typography variant="caption" display="block" sx={{ mt: 1, color: m3.onSurfaceVar }}>
        No not-provable rule is on this page; filter by "not provable" to see why the {n(unknownTotal)} could not be decided.</Typography>}
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
        {/* The shadow reason is the Shadow comparison column's own sentence; the `Shadowing: …` finding would print it twice. */}
        <TableCell>{row.findings.filter(f => !(f.findingClass === "unknown" && f.evidence.startsWith("Shadowing: "))).map((f, i) => <Typography key={i} variant="body2" sx={{ color: f.severity === "HIGH" ? m3.error : undefined }}>
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
  const [rules, setRules] = useState<PolicyRuleDetail[] | null>(null), [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    Promise.all([getPolicyRule(policy, pair.covering, days), getPolicyRule(policy, pair.covered, days)])
      .then(result => { if (active) setRules(result); }).catch(failure => { if (active) setError(policyFailureText(failure, "Rule comparison could not be loaded.")); });
    return () => { active = false; };
  }, [policy, pair.covered, pair.covering, days]);
  if (error) return <Typography role="alert">{error}</Typography>;
  if (!rules) return <Typography role="status">Loading rule comparison…</Typography>;
  return <Box aria-label="Shadow comparison" sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2, mt: 2 }}>
    {rules.map((detail, i) => <Box key={i} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 2 }}>
      <Typography variant="h6">{i === 0 ? "Covering rule" : "Shadowed rule"}</Typography>
      <Button onClick={() => openRule(detail.rule.id, days)}>{detail.rule.name || "Unnamed rule"}</Button>
      <RuleFields rule={detail.rule} objects={new Map(detail.objects.map(o => [o.id, o]))} openObject={openObject} />
    </Box>)}
  </Box>;
}
