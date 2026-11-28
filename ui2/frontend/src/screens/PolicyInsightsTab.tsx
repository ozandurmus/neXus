import { useCallback, useEffect, useRef, useState } from "react";
import { Box, Button, Chip, Drawer, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { getPolicyInsights, responsesAreMasked, type InsightGrade, type InsightType, type PolicyInsight, type PolicyInsightCounts, type PolicyInsightsPage } from "../auth/adminApi";
import { downloadText } from "./DeviceShared";
import { ChangeListPanel, InsightDetail } from "./PolicyInsightDetail";
import { PolicyProposalPanel } from "./PolicyProposalPanel";
import { policyFailureText } from "./PolicyRuleViewer";
import { GRADE_EXPLAIN, TYPE_LABEL, changeItem, changeListCsv, changeListText, insightGradeColor, insightSuggestion, loadChangeList, raiseText, saveChangeList, withChange, withoutChange,
  type ChangeItem, type SuggestedAction } from "./policyInsightActions";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";

export { COVER_ACTION_WORDS, INSTALL_CAVEAT, TYPE_LABEL, TYPE_SUGGESTION, raiseText } from "./policyInsightActions";

/** Policy Insights phases P1, P2a, P2b, P2c-1 and P2c-2a (POLICY_INSIGHTS_DESIGN_2026_11_24.md): the Check Point Insights tab. Read-only, stored evidence only. */
const n = (value: number) => value.toLocaleString("en-US");
export const INSIGHT_TYPES: readonly InsightType[] = ["I1", "I2", "I3", "I4", "I5", "I7", "I8", "I9", "I10", "I11", "I12"];
export const INSIGHT_GRADES: readonly InsightGrade[] = ["PROVEN", "SUPPORTED", "INDICATED", "NOT_ESTABLISHED", "NOT_EVALUABLE"];
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

const MAX_WAITING_ROWS = 10;

function PackageOverview({ counts, complete, collectedAt }: { counts: PolicyInsightCounts; complete: boolean | undefined; collectedAt: string | undefined }) {
  const p = packageNumbers(counts);
  const waitingRules = counts.waitingRules ?? [];
  const notReadable = counts.notReadable ?? counts.notDecidable.reduce((sum, b) => sum + b.rules, 0);
  const waiting = counts.waiting ?? waitingRules.reduce((sum, w) => sum + w.rules, 0);
  const shownWaiting = waitingRules.slice(0, MAX_WAITING_ROWS), restWaiting = waitingRules.slice(MAX_WAITING_ROWS);
  const restWaitingRules = restWaiting.reduce((sum, w) => sum + w.rules, 0);
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
    {(notReadable > 0 || waiting > 0) && <Typography variant="caption" display="block" data-undecided-split sx={{ mt: 1, color: m3.onSurfaceVar }}>
      Undecided split: <b>{n(notReadable)}</b> {notReadable === 1 ? "rule" : "rules"} not readable · <b>{n(waiting)}</b> {waiting === 1 ? "rule" : "rules"} readable, {waiting === 1 ? "waits" : "wait"} behind an earlier rule</Typography>}
    {counts.notDecidable.length > 0 && <Box component="ul" aria-label="Not decidable by reason" sx={{ m: 0, mt: 1, p: 0, listStyle: "none", display: "grid", gap: 0.25 }}>
      {counts.notDecidable.map(b => <Box component="li" key={`${b.shadowReason}:${b.reasonClass}`} data-reason-class={b.reasonClass} sx={{ fontSize: 12 }}>
        <b>{n(b.rules)}</b> {b.rules === 1 ? "rule" : "rules"} not readable · {b.shadowReason} <Box component="span" sx={{ color: m3.onSurfaceVar }}>({reasonClassWords[b.reasonClass] ?? "UNKNOWN"})</Box></Box>)}
    </Box>}
    {shownWaiting.length > 0 && <Box component="ul" aria-label="Readable rules waiting behind an earlier rule" sx={{ m: 0, mt: 1, p: 0, listStyle: "none", display: "grid", gap: 0.25 }}>
      {shownWaiting.map(w => <Box component="li" key={`${w.blockingRuleId ?? "?"}:${w.blockingRuleNumber ?? 0}:${w.shadowReason}`} data-waiting-class={w.reasonClass} sx={{ fontSize: 12 }}>
        <b>{n(w.rules)}</b> {w.rules === 1 ? "rule" : "rules"} readable, {w.rules === 1 ? "waits" : "wait"} behind {w.blockingRuleNumber ? `rule #${w.blockingRuleNumber}` : "an earlier rule"} · {w.shadowReason} <Box component="span" sx={{ color: m3.onSurfaceVar }}>({reasonClassWords[w.reasonClass] ?? "UNKNOWN"})</Box></Box>)}
      {restWaitingRules > 0 && <Box component="li" sx={{ fontSize: 12, color: m3.onSurfaceVar }}>
        +{n(restWaitingRules)} {restWaitingRules === 1 ? "rule" : "rules"} readable, waiting behind {n(restWaiting.length)} other {restWaiting.length === 1 ? "rule" : "rules"}</Box>}
    </Box>}
    <Typography variant="caption" display="block" sx={{ mt: 1, color: m3.onSurfaceVar }}>
      Over {n(counts.rules)} enabled security rules. {n(counts.disabledRules)} disabled and {n(counts.outsideSecurityRules)} NAT rules are left out; the Hygiene tab counts all of them.
      "Up to" is a ceiling: it is known before any log exists. Hit counters: {n(counts.hitsCollected)} stored · {n(counts.hitsMissing)} not collected.</Typography>
  </Box>;
}

/** Which drawer is open: the detail of the highlighted insight, or the package's change list. */
type Panel = null | "detail" | "changes";

export function PolicyInsightsTab({ policy, policyName, openRule, openObject = () => {} }: { policy: string; policyName?: string;
  openRule: (id: string, days: number) => void; openObject?: (id: string) => void }) {
  const [data, setData] = useState<PolicyInsightsPage | null>(null), [error, setError] = useState("");
  const [type, setType] = useState(""), [grade, setGrade] = useState(""), [days, setDays] = useState(90), [page, setPage] = useState(0), [revision, setRevision] = useState(0);
  // The highlighted row outlives the drawer: closing the detail leaves the row marked and focused.
  const [selected, setSelected] = useState<string | null>(null), [panel, setPanel] = useState<Panel>(null);
  // The change list of this package in this browser; `masked` is known only once a response has come back.
  const [changes, setChanges] = useState<ChangeItem[]>([]), [masked, setMasked] = useState<boolean | null>(null);
  const rows = useRef(new Map<string, HTMLTableRowElement>()), changesButton = useRef<HTMLButtonElement | null>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  const validDays = Number.isInteger(days) && days >= 1 && days <= 36500;
  useEffect(() => {
    let active = true; setData(null); setError(""); setSelected(null); setPanel(null);
    if (!policy || !validDays) return;
    getPolicyInsights(policy, page, type, grade, days).then(result => {
      if (typeof result?.supported !== "boolean") throw new Error("Invalid insights response");
      if (result.supported && (!Array.isArray(result.insights) || !result.counts)) throw new Error("Invalid insights response");
      if (active) { setData(result); setMasked(responsesAreMasked()); }
    }).catch(failure => { if (active) setError(policyFailureText(failure, "Insights could not be loaded.")); });
    return () => { active = false; };
  }, [policy, page, type, grade, days, revision, validDays]);
  useEffect(() => { if (policy && masked !== null) setChanges(loadChangeList(policy, masked)); }, [policy, masked]);
  // Focus goes back to where the drawer was opened from, after the drawer has let go of it.
  useEffect(() => { if (panel === null && returnFocus.current) { returnFocus.current.focus(); returnFocus.current = null; } }, [panel]);
  const store = useCallback((next: ChangeItem[]) => { setChanges(next); if (policy && masked !== null) saveChangeList(policy, masked, next); }, [policy, masked]);
  const openDetail = (id: string) => { returnFocus.current = rows.current.get(id) ?? null; setSelected(id); setPanel("detail"); };
  const openChanges = () => { returnFocus.current = changesButton.current; setPanel("changes"); };
  const close = () => setPanel(null);
  const addChange = (insight: PolicyInsight, action: SuggestedAction, snapshotAt: string | undefined) => store(withChange(changes, changeItem(insight, action, snapshotAt ?? data?.collectedAt)));
  const removeChange = (insightId: string) => store(withoutChange(changes, insightId));
  const exportList = (kind: "txt" | "csv") => {
    const at = new Date(), name = `policy-change-list-${at.toISOString().slice(0, 16).replace(/[:T]/g, "-")}Z.${kind}`;
    if (kind === "csv") downloadText(name, changeListCsv(changes));
    else downloadText(name, changeListText(changes, { packageName: policyName, masked: masked === true, exportedAt: at }), "text/plain;charset=utf-8");
  };
  if (!policy) return <Typography role="status">Select a stored policy to view insights.</Typography>;
  return <Box role="tabpanel" aria-label="Insights">
    <Typography variant="body2">Insights read the stored policy and its stored hit counters. Each one carries an evidence grade, never a percentage: <b>PROVEN</b> follows from the policy, <b>SUPPORTED</b> adds dated runtime evidence, <b>INDICATED</b> rests on evidence whose window is unknown, <b>NOT_EVALUABLE</b> could not be read. No device is contacted.</Typography>
    {error && <Box role="alert">{error}<Button onClick={() => setRevision(r => r + 1)}>Retry insights</Button></Box>}
    {!data && !error && validDays && <Typography role="status">Loading insights…</Typography>}
    {data && !data.supported && <Typography role="status" data-insights-supported="false" sx={{ my: 2 }}>
      Insights are available for Check Point packages in this phase. This package is not Check Point, so there is nothing to show; this is not a result of "nothing found".</Typography>}
    {data && data.supported && data.counts && <>
      <PackageOverview counts={data.counts} complete={data.complete} collectedAt={data.collectedAt} />
      {data.unionCover && <Typography variant="caption" display="block" aria-label="Union cover analysis" sx={{ color: m3.onSurfaceVar }}>
        I3 union cover: {n(data.unionCover.evaluated)} rules checked against the earlier rules of their layer · {n(data.unionCover.covered)} covered
        · {n(data.unionCover.notProven)} not proven (work budget){data.unionCover.notEvaluable.length > 0
          ? ` · not evaluated: ${data.unionCover.notEvaluable.map(r => `${n(r.rules)} ${r.unionReason}`).join(", ")}` : ""}</Typography>}
      {data.partialShadow && <Typography variant="caption" display="block" aria-label="Partial shadow analysis" sx={{ color: m3.onSurfaceVar }}>
        I4 partial shadow: {n(data.partialShadow.evaluated)} rules checked against the earlier rules of their layer · {n(data.partialShadow.partial)} partly shadowed, same action
        · {n(data.partialShadow.differentAction)} overlap an earlier rule with another action (counted, no insight) · {n(data.partialShadow.notProven)} not proven (work budget){data.partialShadow.notEvaluable.length > 0
          ? ` · not evaluated: ${data.partialShadow.notEvaluable.map(r => `${n(r.rules)} ${r.partialReason}`).join(", ")}` : ""}</Typography>}
      {data.merge && <Typography variant="caption" display="block" aria-label="Merge analysis" sx={{ color: m3.onSurfaceVar }}>
        I5 merge (adjacent rules): {n(data.merge.evaluated)} adjacent pairs checked · {n(data.merge.candidates)} merge candidates
        · {n(data.merge.alreadyShadowed)} already shadowed · {n(data.merge.differsInSeveralCells)} differ in two or more cells
        · {n(data.merge.differentEffect)} differ in effect or log setting · {n(data.merge.differentInstallOn)} differ in install-on
        · {n(data.merge.anchorBlocked)} blocked by an anchor (counted, no insight)
        · {n(data.merge.notProven)} not proven (work budget){data.merge.notEvaluable.length > 0
          ? ` · not evaluated: ${data.merge.notEvaluable.map(r => `${n(r.rules)} ${r.mergeReason}`).join(", ")}` : ""}</Typography>}
      {data.safeReorder && <Typography variant="caption" display="block" aria-label="Reorder analysis" sx={{ color: m3.onSurfaceVar }}>
        I7 reorder (promote): {n(data.safeReorder.evaluated)} promote moves checked (one per I4 rule) · {n(data.safeReorder.safe)} no behaviour change
        · {n(data.safeReorder.safeInspectionChanges)} verdict-safe, inspection changes · {n(data.safeReorder.unsafeDifferentEffect)} cross a rule with another verdict
        · {n(data.safeReorder.anchorBlocked)} blocked by an anchor · {n(data.safeReorder.crossesSection)} would leave their section (counted, no insight)
        · {n(data.safeReorder.notProven)} not proven (work budget){data.safeReorder.notEvaluable.length > 0
          ? ` · not evaluated: ${data.safeReorder.notEvaluable.map(r => `${n(r.rules)} ${r.reorderReason}`).join(", ")}` : ""}</Typography>}
      <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 2 }} aria-label="Insights by type">
        {INSIGHT_TYPES.map(t => <Chip key={t} label={`${t} ${TYPE_LABEL[t]}: ${data.byType?.[t] ?? 0}`} color={type === t ? "primary" : "default"} onClick={() => { setType(type === t ? "" : t); setPage(0); }} />)}
      </Stack>
      <Stack direction="row" gap={2} flexWrap="wrap" alignItems="flex-start" sx={{ mb: 2 }}>
        <TextField select size="small" label="Insight type" value={type} onChange={e => { setType(e.target.value); setPage(0); }} sx={{ minWidth: 260 }}>
          <MenuItem value="">All types</MenuItem>{INSIGHT_TYPES.map(t => <MenuItem key={t} value={t}>{t} · {TYPE_LABEL[t]}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Grade" value={grade} onChange={e => { setGrade(e.target.value); setPage(0); }} sx={{ minWidth: 190 }}>
          <MenuItem value="">All grades</MenuItem>{INSIGHT_GRADES.map(g => <MenuItem key={g} value={g}>{g} ({data.byGrade?.[g] ?? 0})</MenuItem>)}
        </TextField>
        <TextField size="small" type="number" label="Unused threshold (days)" value={days} error={!validDays} helperText={!validDays ? "Enter 1–36500 whole days" : undefined}
          onChange={e => { setDays(Number(e.target.value)); setPage(0); }} inputProps={{ min: 1, max: 36500 }} />
        <Button ref={changesButton} variant="outlined" onClick={openChanges} sx={{ ml: "auto", height: 40, gap: 1 }}
          aria-label={`Change list, ${changes.length} planned ${changes.length === 1 ? "change" : "changes"}`} title="Planned changes for this package. Nothing is sent to the firewall.">
          Change list<Box component="span" data-change-count sx={{ minWidth: 22, px: 0.75, borderRadius: 999, fontSize: 12, fontWeight: 700, lineHeight: "22px", textAlign: "center",
            bgcolor: changes.length ? m3.primary : m3.scHigh, color: changes.length ? m3.onPrimary : m3.onSurfaceVar }}>{changes.length}</Box></Button>
      </Stack>
      {data.total === 0 && <Typography role="status">No insight matches these filters.</Typography>}
      {(data.total ?? 0) > 0 && <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Policy insights">
        <TableHead><TableRow>{["Type", "Rules / objects", "Suggestion", "Grade", "Raise to", "Change list"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
        <TableBody>{data.insights!.map(insight => { const planned = changes.find(c => c.insightId === insight.insightId); return <TableRow key={insight.insightId} hover selected={selected === insight.insightId}
          tabIndex={0} data-insight-id={insight.insightId} ref={(el: HTMLTableRowElement | null) => { if (el) rows.current.set(insight.insightId, el); else rows.current.delete(insight.insightId); }}
          onClick={() => openDetail(insight.insightId)}
          onKeyDown={e => { if (e.target === e.currentTarget && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); openDetail(insight.insightId); } }}
          sx={{ cursor: "pointer", "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: "-2px" } }}>
          {/* The whole row opens the detail (click, Enter, Space); the button names the action for assistive technology and is not a second tab stop. */}
          <TableCell><Button size="small" tabIndex={-1} aria-label={`Open ${insight.insightType} detail`}>{insight.insightType} · {TYPE_LABEL[insight.insightType] ?? "UNKNOWN"}</Button></TableCell>
          <TableCell>{insight.rules.length > 0
            ? insight.rules.map(r => `#${r.number || "?"} ${r.name}`).join(" · ")
            : `${insight.objectIds.length} ${insight.objectIds.length === 1 ? "object" : "objects"} (${insight.insightScope === "domain" ? "domain-wide" : "package"})`}</TableCell>
          <TableCell>{insightSuggestion(insight)}</TableCell>
          <TableCell><Chip size="small" color={insightGradeColor(insight.insightType, insight.evidenceGrade)} label={insight.evidenceGrade} title={GRADE_EXPLAIN[insight.evidenceGrade]} /></TableCell>
          <TableCell><Typography variant="caption">{raiseText(insight)}</Typography></TableCell>
          <TableCell>{planned ? <Chip size="small" variant="outlined" color="primary" label={planned.label} title={planned.plan} /> : <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>—</Typography>}</TableCell>
        </TableRow>; })}</TableBody>
      </Table></Box>}
      <Stack direction="row" alignItems="center"><Button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous insights page</Button>
        <Typography variant="caption">{n(data.total ?? 0)} matching insights · Page {page + 1}</Typography>
        <Button disabled={(page + 1) * (data.pageSize ?? 200) >= (data.total ?? 0)} onClick={() => setPage(p => p + 1)}>Next insights page</Button></Stack>
      <PolicyProposalPanel policy={policy} days={days} />
      {/* Esc and the backdrop close through onClose; focus is handed back by the effect above, so the drawer does not restore it itself. */}
      <Drawer anchor="right" open={panel !== null} onClose={close} ModalProps={{ disableRestoreFocus: true }}
        PaperProps={{ sx: { width: { xs: "100%", md: 860 }, maxWidth: "100vw", bgcolor: m3.surface } }}>
        <Box role="dialog" aria-modal="true" aria-label={panel === "changes" ? "Change list" : "Insight detail"} sx={{ p: { xs: 2, md: 3 }, overflowWrap: "anywhere" }}>
          {panel === "detail" && selected && <InsightDetail key={selected} policy={policy} insightId={selected} days={days} openRule={openRule} openObject={openObject}
            planned={changes.find(c => c.insightId === selected)} changeCount={changes.length} addChange={addChange} removeChange={removeChange}
            showChanges={() => setPanel("changes")} close={close} />}
          {panel === "changes" && <ChangeListPanel items={changes} remove={removeChange} exportText={() => exportList("txt")} exportCsv={() => exportList("csv")}
            back={selected && returnFocus.current !== changesButton.current ? () => setPanel("detail") : undefined} close={close} />}
        </Box>
      </Drawer>
    </>}
  </Box>;
}
