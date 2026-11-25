import { useEffect, useState } from "react";
import { Box, Button, Chip, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { getPolicyInsight, getPolicyInsights, type InsightGrade, type InsightNeeds, type InsightType, type PolicyInsight, type PolicyInsightCounts, type PolicyInsightDetail, type PolicyInsightsPage } from "../auth/adminApi";
import { policyFailureText } from "./PolicyRuleViewer";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";

/** Policy Insights phase P1 (POLICY_INSIGHTS_DESIGN_2026_11_24.md): the Check Point Insights tab. Read-only, stored evidence only. */
const n = (value: number) => value.toLocaleString("en-US");
export const INSIGHT_TYPES: readonly InsightType[] = ["I1", "I2", "I8", "I9", "I10", "I11", "I12"];
export const INSIGHT_GRADES: readonly InsightGrade[] = ["PROVEN", "SUPPORTED", "INDICATED", "NOT_ESTABLISHED", "NOT_EVALUABLE"];
export const INSTALL_CAVEAT = "Proven for the stored management policy, not for what is installed on a gateway (INSTALL_STATE_NOT_VERIFIED).";

export const TYPE_LABEL: Readonly<Record<InsightType, string>> = {
  I1: "Fully shadowed, same action", I2: "Fully shadowed, different action", I8: "Disabled rule", I9: "Expired or expiring schedule",
  I10: "Zero or old hits", I11: "Unused object", I12: "Duplicate objects",
};
/** What the user does, one sentence per type; a grade other than PROVEN adds its own limit below the sentence. */
export const TYPE_SUGGESTION: Readonly<Record<InsightType, string>> = {
  I1: "Remove: an earlier rule already decides every packet this rule matches, with the same action.",
  I2: "Decide which rule is right: an earlier rule with a different action decides every packet this rule matches. Never removed automatically.",
  I8: "Remove after your team's holding period.",
  I9: "Remove or renew the rule.",
  I10: "Review: the stored counters show no recent use. This does not prove the rule is unused.",
  I11: "Remove the object or tighten the group that holds it.",
  I12: "Consolidate: these objects have the same type and the same values.",
};
const GRADE_EXPLAIN: Readonly<Record<InsightGrade, string>> = {
  PROVEN: "Follows from the stored policy by set semantics.",
  SUPPORTED: "Proven on the policy side, plus dated runtime evidence with a known window.",
  INDICATED: "Runtime evidence exists but its window or completeness is unknown, or the analysis ignored a constraint.",
  NOT_ESTABLISHED: "The policy shows a candidate but nothing shows it is safe.",
  NOT_EVALUABLE: "The analysis cannot read this; the reason is named.",
};
const NEEDS_WORDS: Readonly<Record<InsightNeeds, string>> = {
  NONE: "nothing", TRAFFIC_LOGS: "traffic logs", COUNTER_SAMPLES: "hit-counter samples over time", NEW_COLLECTION: "a new collection",
  VENDOR_REPORT: "the management's unused-object report", NOT_RESOLVABLE: "nothing in this design",
};
export const raiseText = (insight: PolicyInsight) => {
  const { evidenceGrade: to, insightNeeds } = insight.raiseTo;
  return insightNeeds === "NONE" || to === insight.evidenceGrade ? "Nothing raises this" : `${to} with ${NEEDS_WORDS[insightNeeds] ?? "UNKNOWN"}`;
};
const gradeColor = (grade: InsightGrade): "success" | "info" | "warning" | "default" =>
  grade === "PROVEN" ? "success" : grade === "SUPPORTED" ? "info" : grade === "INDICATED" ? "warning" : "default";
const reasonClassWords = { LOGS: "traffic logs could decide", COLLECTION: "a new collection could decide", NOT_RESOLVABLE: "nothing in this design can decide" } as const;

export interface PackageNumbers {
  rules: number; decided: number; upToWithLogs: number; notDecidable: number;
  /** Counted shares, shown after the counts: whole percent, never a probability. Null when the package has no enabled rule. */
  decidedShare: number | null; upToShare: number | null;
  sentence: string;
}
/** The section 3.2 numbers as one sentence: counts first, the share second. "up to" because the logs figure is a ceiling known before any log exists. */
export function packageNumbers(counts: PolicyInsightCounts): PackageNumbers {
  const share = (part: number) => counts.rules > 0 ? Math.round((part / counts.rules) * 100) : null;
  const notDecidable = counts.rules - counts.upToWithLogs;
  const pct = (part: number) => { const s = share(part); return s === null ? "" : ` (${s} %)`; };
  return { rules: counts.rules, decided: counts.decided, upToWithLogs: counts.upToWithLogs, notDecidable,
    decidedShare: share(counts.decided), upToShare: share(counts.upToWithLogs),
    sentence: `${n(counts.decided)} of ${n(counts.rules)} rules decided from the policy alone${pct(counts.decided)} · up to ${n(counts.upToWithLogs)} with traffic logs${pct(counts.upToWithLogs)} · ${n(notDecidable)} not decidable by logs` };
}

function PackageOverview({ counts, complete, collectedAt }: { counts: PolicyInsightCounts; complete: boolean | undefined; collectedAt: string | undefined }) {
  const p = packageNumbers(counts);
  const segments = [
    { key: "decided", label: "Decided from the policy alone", value: counts.decided, color: m3.primary },
    { key: "logs", label: "Could be decided with traffic logs (upper bound)", value: counts.logResolvable, color: m3.warningInk },
    { key: "collection", label: "Could be decided with a new collection", value: counts.collectionResolvable, color: m3.outline },
    { key: "none", label: "Not decidable", value: counts.notResolvable, color: m3.error },
  ];
  return <Box role="region" aria-label="Package counts" sx={{ my: 2, p: 1.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, bgcolor: m3.scLowest }}>
    <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
      <Typography variant="body2" sx={{ fontWeight: 600 }}>{p.sentence}</Typography>
      {complete === false && <Chip size="small" color="warning" label="Snapshot incomplete" title="Some parts of this policy were not collected; rules that depend on them cannot be decided." />}
      <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Snapshot collected {formatStamp(collectedAt)}</Typography>
    </Stack>
    {counts.rules > 0 && <Box role="img" aria-label="Rules by what can decide them" sx={{ display: "flex", height: 18, mt: 1, borderRadius: 1, overflow: "hidden", bgcolor: m3.scLow }}>
      {segments.filter(s => s.value > 0).map(s => <Box key={s.key} title={`${s.label}: ${n(s.value)}`} data-segment={s.key} sx={{ width: `${(s.value / counts.rules) * 100}%`, bgcolor: s.color }} />)}
    </Box>}
    <Box component="ul" aria-label="What would raise this" sx={{ m: 0, mt: 1, p: 0, listStyle: "none", display: "grid", gap: 0.25 }}>
      {segments.map(s => <Box component="li" key={s.key} data-count={s.key} sx={{ fontSize: 12, display: "flex", gap: 1 }}>
        <Box component="span" sx={{ fontVariantNumeric: "tabular-nums", fontWeight: 700, minWidth: 48, textAlign: "right" }}>{n(s.value)}</Box><span>{s.label}</span></Box>)}
    </Box>
    {counts.notDecidable.length > 0 && <Box component="ul" aria-label="Not decidable by reason" sx={{ m: 0, mt: 1, p: 0, listStyle: "none", display: "grid", gap: 0.25 }}>
      {counts.notDecidable.map(b => <Box component="li" key={`${b.shadowReason}:${b.reasonClass}`} data-reason-class={b.reasonClass} sx={{ fontSize: 12 }}>
        <b>{n(b.rules)}</b> {b.rules === 1 ? "rule" : "rules"} · {b.shadowReason} <Box component="span" sx={{ color: m3.onSurfaceVar }}>({reasonClassWords[b.reasonClass] ?? "UNKNOWN"})</Box></Box>)}
    </Box>}
    <Typography variant="caption" display="block" sx={{ mt: 1, color: m3.onSurfaceVar }}>
      Over {n(counts.rules)} enabled security rules. {n(counts.disabledRules)} disabled and {n(counts.outsideSecurityRules)} NAT rules are left out; the Hygiene tab counts all of them.
      "Up to" is a ceiling: it is known before any log exists. Hit counters: {n(counts.hitsCollected)} stored · {n(counts.hitsMissing)} not collected.</Typography>
  </Box>;
}

export function PolicyInsightsTab({ policy, openRule }: { policy: string; openRule: (id: string, days: number) => void }) {
  const [data, setData] = useState<PolicyInsightsPage | null>(null), [error, setError] = useState("");
  const [type, setType] = useState(""), [grade, setGrade] = useState(""), [days, setDays] = useState(90), [page, setPage] = useState(0), [revision, setRevision] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const validDays = Number.isInteger(days) && days >= 1 && days <= 36500;
  useEffect(() => {
    let active = true; setData(null); setError(""); setSelected(null);
    if (!policy || !validDays) return;
    getPolicyInsights(policy, page, type, grade, days).then(result => {
      if (typeof result?.supported !== "boolean") throw new Error("Invalid insights response");
      if (result.supported && (!Array.isArray(result.insights) || !result.counts)) throw new Error("Invalid insights response");
      if (active) setData(result);
    }).catch(failure => { if (active) setError(policyFailureText(failure, "Insights could not be loaded.")); });
    return () => { active = false; };
  }, [policy, page, type, grade, days, revision, validDays]);
  if (!policy) return <Typography role="status">Select a stored policy to view insights.</Typography>;
  return <Box role="tabpanel" aria-label="Insights">
    <Typography variant="body2">Insights read the stored policy and its stored hit counters. Each one carries an evidence grade, never a percentage: <b>PROVEN</b> follows from the policy, <b>SUPPORTED</b> adds dated runtime evidence, <b>INDICATED</b> rests on evidence whose window is unknown, <b>NOT_EVALUABLE</b> could not be read. No device is contacted.</Typography>
    {error && <Box role="alert">{error}<Button onClick={() => setRevision(r => r + 1)}>Retry insights</Button></Box>}
    {!data && !error && validDays && <Typography role="status">Loading insights…</Typography>}
    {data && !data.supported && <Typography role="status" data-insights-supported="false" sx={{ my: 2 }}>
      Insights are available for Check Point packages in this phase. This package is not Check Point, so there is nothing to show; this is not a result of "nothing found".</Typography>}
    {data && data.supported && data.counts && <>
      <PackageOverview counts={data.counts} complete={data.complete} collectedAt={data.collectedAt} />
      <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 2 }} aria-label="Insights by type">
        {INSIGHT_TYPES.map(t => <Chip key={t} label={`${t} ${TYPE_LABEL[t]}: ${data.byType?.[t] ?? 0}`} color={type === t ? "primary" : "default"} onClick={() => { setType(type === t ? "" : t); setPage(0); }} />)}
      </Stack>
      <Stack direction="row" gap={2} flexWrap="wrap" sx={{ mb: 2 }}>
        <TextField select size="small" label="Insight type" value={type} onChange={e => { setType(e.target.value); setPage(0); }} sx={{ minWidth: 260 }}>
          <MenuItem value="">All types</MenuItem>{INSIGHT_TYPES.map(t => <MenuItem key={t} value={t}>{t} · {TYPE_LABEL[t]}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Grade" value={grade} onChange={e => { setGrade(e.target.value); setPage(0); }} sx={{ minWidth: 190 }}>
          <MenuItem value="">All grades</MenuItem>{INSIGHT_GRADES.map(g => <MenuItem key={g} value={g}>{g} ({data.byGrade?.[g] ?? 0})</MenuItem>)}
        </TextField>
        <TextField size="small" type="number" label="Unused threshold (days)" value={days} error={!validDays} helperText={!validDays ? "Enter 1–36500 whole days" : undefined}
          onChange={e => { setDays(Number(e.target.value)); setPage(0); }} inputProps={{ min: 1, max: 36500 }} />
      </Stack>
      {data.total === 0 && <Typography role="status">No insight matches these filters.</Typography>}
      {(data.total ?? 0) > 0 && <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Policy insights">
        <TableHead><TableRow>{["Type", "Rules / objects", "Suggestion", "Grade", "Raise to"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
        <TableBody>{data.insights!.map(insight => <TableRow key={insight.insightId} hover selected={selected === insight.insightId}>
          <TableCell><Button size="small" aria-label={`Open ${insight.insightType} detail`} onClick={() => setSelected(insight.insightId)}>{insight.insightType} · {TYPE_LABEL[insight.insightType] ?? "UNKNOWN"}</Button></TableCell>
          <TableCell>{insight.rules.length > 0
            ? insight.rules.map(r => `#${r.number || "?"} ${r.name}`).join(" · ")
            : `${insight.objectIds.length} ${insight.objectIds.length === 1 ? "object" : "objects"} (${insight.insightScope === "domain" ? "domain-wide" : "package"})`}</TableCell>
          <TableCell>{TYPE_SUGGESTION[insight.insightType] ?? "UNKNOWN"}</TableCell>
          <TableCell><Chip size="small" color={gradeColor(insight.evidenceGrade)} label={insight.evidenceGrade} title={GRADE_EXPLAIN[insight.evidenceGrade]} /></TableCell>
          <TableCell><Typography variant="caption">{raiseText(insight)}</Typography></TableCell>
        </TableRow>)}</TableBody>
      </Table></Box>}
      <Stack direction="row" alignItems="center"><Button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous insights page</Button>
        <Typography variant="caption">{n(data.total ?? 0)} matching insights · Page {page + 1}</Typography>
        <Button disabled={(page + 1) * (data.pageSize ?? 200) >= (data.total ?? 0)} onClick={() => setPage(p => p + 1)}>Next insights page</Button></Stack>
      {selected && <InsightDetail key={selected} policy={policy} insightId={selected} days={days} openRule={openRule} close={() => setSelected(null)} />}
    </>}
  </Box>;
}

export function InsightDetail({ policy, insightId, days, openRule, close }: { policy: string; insightId: string; days: number; openRule: (id: string, days: number) => void; close: () => void }) {
  const [detail, setDetail] = useState<PolicyInsightDetail | null>(null), [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    getPolicyInsight(policy, insightId, days).then(result => { if (active) setDetail(result); })
      .catch(failure => { if (active) setError(policyFailureText(failure, "Insight detail could not be loaded.")); });
    return () => { active = false; };
  }, [policy, insightId, days]);
  if (error) return <Typography role="alert">{error}</Typography>;
  if (!detail) return <Typography role="status">Loading insight detail…</Typography>;
  const insight = detail.insight, counter = insight.counter ?? {};
  const role = (id: string) => insight.rules.find(r => r.ruleId === id)?.insightRole;
  return <Box role="region" aria-label="Insight detail" sx={{ mt: 2, p: 2, border: `1px solid ${m3.outlineVar}`, borderRadius: 2 }}>
    <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
      <Typography variant="h6">{insight.insightType} · {TYPE_LABEL[insight.insightType] ?? "UNKNOWN"}</Typography>
      <Chip size="small" color={gradeColor(insight.evidenceGrade)} label={insight.evidenceGrade} title={GRADE_EXPLAIN[insight.evidenceGrade]} />
      <Button size="small" onClick={close}>Close detail</Button>
    </Stack>
    <Typography variant="body2" sx={{ mt: 1 }}>{TYPE_SUGGESTION[insight.insightType] ?? "UNKNOWN"}</Typography>
    {insight.rules.length > 0 && <Box aria-label="Rules involved" sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: insight.rules.length > 1 ? "1fr 1fr" : "1fr" }, gap: 2, mt: 2 }}>
      {detail.rules.map(rule => <Box key={rule.id} data-role={role(rule.id)} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 1.5 }}>
        <Typography variant="subtitle2">{role(rule.id) === "covering" ? "Covering rule" : "Rule"}</Typography>
        <Button onClick={() => openRule(rule.id, days)}>#{rule.number} · {rule.name || "Unnamed rule"}</Button>
        <Typography variant="body2">Action: {rule.action} · Log: {rule.log || "UNKNOWN"} · {rule.enabled === false ? "Disabled" : rule.enabled === true ? "Enabled" : "Enabled state UNKNOWN"}</Typography>
      </Box>)}
    </Box>}
    <Typography variant="subtitle2" sx={{ mt: 2 }}>Evidence</Typography>
    <Box component="ul" aria-label="Insight evidence" sx={{ m: 0, pl: 2 }}>
      {insight.evidence.map(e => <li key={e}><Typography variant="body2">{e}</Typography></li>)}
      {insight.evidence.length === 0 && <li><Typography variant="body2">Derived from the stored object inventory and where-used counts.</Typography></li>}
    </Box>
    {insight.insightType === "I10" && <Box aria-label="Counter evidence" sx={{ mt: 1 }}>
      <Typography variant="body2">Hits: {counter.hits ?? "UNKNOWN"} · First hit: {formatStamp(counter.firstHit as string | null)} · Last hit: {formatStamp(counter.lastHit as string | null)}</Typography>
      <Typography variant="body2">Source: {String(counter.hitSource ?? "UNKNOWN")} · Collected: {formatStamp(counter.hitsCollectedAt as string | null)}</Typography>
      <Typography variant="body2">{String(counter.counterWindow ?? "Counter window unknown.")}{counter.windowKnown === true ? ` Measured window: ${counter.windowDays} days.` : " No measured window; hit counters carry no window start."}</Typography>
    </Box>}
    {detail.objects.length > 0 && <Box aria-label="Objects involved" sx={{ mt: 1 }}>
      <Typography variant="subtitle2">Objects (domain-wide)</Typography>
      <Box component="ul" sx={{ m: 0, pl: 2 }}>{detail.objects.map(o => <li key={o.uid}><Typography variant="body2">{o.name} · {o.type}{o.values?.length ? ` · ${o.values.join(", ")}` : ""} · used by {o.ruleCount ?? 0} rules, {o.groupCount ?? 0} groups</Typography></li>)}</Box>
    </Box>}
    <Typography variant="body2" sx={{ mt: 1 }}>Raise to: {raiseText(insight)}.</Typography>
    {insight.insightCaveats.includes("INSTALL_STATE_NOT_VERIFIED") && <Typography role="note" variant="caption" display="block" sx={{ mt: 1, color: m3.warningInk }}>{INSTALL_CAVEAT}</Typography>}
  </Box>;
}
