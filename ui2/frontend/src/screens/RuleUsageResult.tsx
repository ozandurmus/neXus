import { useMemo, useState, type ReactNode } from "react";
import { Alert, Box, Button, Stack, Table, TableBody, TableCell, TableHead, TableRow, ToggleButton, ToggleButtonGroup, Typography } from "@mui/material";

import type { OfflineAnalysisResult, OfflineRule, RuleObjectDimension, RuleObjectState, RuleObjectUsage } from "../auth/adminApi";
import { StatusPill } from "../shell/StatusPill";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import type { StateRole } from "../theme/tokens";
import { csvText } from "./offlineCsv";
import { SnapshotFreshnessChip } from "./PolicyRuleViewer";

/**
 * The one-rule result of Rule object usage (RULE_OBJECT_USAGE_CONTRACT.md, section 5). The operator's question is "which
 * of this rule's objects carried traffic, and which never did?", so the screen is the rule, what the file covered, and
 * the rule's three cells as trees with one state per object. Everything here is read from the stored aggregates: no
 * value is unmasked, matched or recounted in the browser.
 */

/** The fixed wording of the contract, shown on every result and carried by every exported "not seen" row. */
export const NOT_SEEN_WORDING = "Not seen in the uploaded period. This is not proof that the object is unused: the export covers only what was logged in that period.";

export const RULE_USAGE_DIMENSIONS: readonly { readonly id: RuleObjectDimension; readonly label: string }[] = [
  { id: "source", label: "Source" }, { id: "destination", label: "Destination" }, { id: "service", label: "Service" },
];

const STATE_PILL: Readonly<Record<RuleObjectState, { readonly tone: StateRole; readonly word: string }>> = {
  USED: { tone: "ok", word: "Used" },
  NOT_SEEN: { tone: "warn", word: "Not seen" },
  NOT_AUDITABLE: { tone: "neutral", word: "Not auditable" },
  UNKNOWN: { tone: "unknown", word: "UNKNOWN" },
};
const pillOf = (state: string) => STATE_PILL[state as RuleObjectState] ?? STATE_PILL.UNKNOWN;
/** A state that is a judgement about traffic; the other two say why no judgement was possible. */
const judged = (state: string) => state === "USED" || state === "NOT_SEEN";
/** NOT_AUDITABLE and UNKNOWN always say why (ROU-3); a missing reason is said, never left blank. */
export const usageReason = (entry: RuleObjectUsage) => judged(entry.state) ? "" : (entry.reason?.trim() || "No reason was given by the server.");

const number = (value: number | null | undefined) => typeof value === "number" && Number.isFinite(value) ? value.toLocaleString("en-US") : "UNKNOWN";
const ROW_STEP = 200;

/** The object list as CSV: one line per object or member, in tree order, from the stored aggregates only. */
export function ruleUsageCsv(result: OfflineAnalysisResult): string {
  const rule = result.rules[0];
  const rows: string[][] = [["Rule number", "Rule name", "Period start", "Period end", "Dimension", "Object", "Object id", "Parent id", "Depth", "State", "Flows", "Reason", "Note"]];
  for (const entry of rule?.objectUsage ?? []) {
    rows.push([String(rule.number ?? ""), rule.name ?? "", result.periodStart || "UNKNOWN", result.periodEnd || "UNKNOWN", entry.dimension, entry.name ?? "", entry.objectId ?? "",
      entry.parentId ?? "", String(entry.depth ?? 0), entry.state, judged(entry.state) ? String(entry.flowCount ?? 0) : "", usageReason(entry), entry.state === "NOT_SEEN" ? NOT_SEEN_WORDING : ""]);
  }
  return csvText(rows);
}

interface TreeRow { readonly entry: RuleObjectUsage; readonly index: number; readonly ancestors: readonly number[]; readonly hasChildren: boolean; readonly holdsNotSeen: boolean }

/** The server's list is already in tree order; depth alone gives each row its ancestors, so a repeated object stays unambiguous. */
function treeOf(entries: readonly RuleObjectUsage[]): TreeRow[] {
  const stack: number[] = [];
  const rows = entries.map((entry, index) => {
    const depth = Math.max(0, entry.depth ?? 0);
    while (stack.length > 0 && Math.max(0, entries[stack[stack.length - 1]].depth ?? 0) >= depth) stack.pop();
    const ancestors = [...stack];
    stack.push(index);
    return { entry, index, ancestors, hasChildren: false, holdsNotSeen: entry.state === "NOT_SEEN" };
  });
  for (const row of rows) {
    for (const ancestor of row.ancestors) {
      rows[ancestor] = { ...rows[ancestor], hasChildren: true, holdsNotSeen: rows[ancestor].holdsNotSeen || row.entry.state === "NOT_SEEN" };
    }
  }
  return rows;
}

const distinct = (entries: readonly RuleObjectUsage[], test: (entry: RuleObjectUsage) => boolean) => new Set(entries.filter(test).map((e) => e.objectId)).size;

function Count({ label, value, fact }: { readonly label: string; readonly value: ReactNode; readonly fact: string }) {
  return <Box data-fact={fact} sx={{ minWidth: 150 }}>
    <Typography component="dt" variant="caption" sx={{ display: "block", color: m3.onSurfaceVar }}>{label}</Typography>
    <Typography component="dd" sx={{ m: 0, fontVariantNumeric: "tabular-nums" }}>{value}</Typography>
  </Box>;
}

function DimensionTree({ label, entries, notSeenOnly }: { readonly label: string; readonly entries: readonly RuleObjectUsage[]; readonly notSeenOnly: boolean }) {
  const rows = useMemo(() => treeOf(entries), [entries]);
  const [closed, setClosed] = useState<ReadonlySet<number>>(new Set());
  const [limit, setLimit] = useState(ROW_STEP);
  const notSeen = distinct(entries, (e) => e.state === "NOT_SEEN");
  const visible = rows.filter((row) => (!notSeenOnly || row.holdsNotSeen) && !row.ancestors.some((a) => closed.has(a)));
  const toggle = (index: number) => setClosed((current) => { const next = new Set(current); if (!next.delete(index)) next.add(index); return next; });
  return <Box component="details" open data-dimension={label.toLowerCase()} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 1, my: 1 }}>
    <Box component="summary" sx={{ px: 2, py: 1, cursor: "pointer", fontWeight: 600 }}>
      {label} · {entries.length === 1 ? "1 object" : `${number(entries.length)} objects`} · {number(notSeen)} not seen
    </Box>
    {entries.length === 0 ? <Typography sx={{ px: 2, pb: 1.5 }}>The analysis lists no {label.toLowerCase()} object for this rule.</Typography>
      : visible.length === 0 ? <Typography sx={{ px: 2, pb: 1.5 }}>No {label.toLowerCase()} object is marked not seen.</Typography>
      : <Box sx={{ overflowX: "auto" }}>
        <Table size="small" aria-label={`${label} objects`}>
          <TableHead><TableRow><TableCell>Object</TableCell><TableCell>State</TableCell><TableCell align="right">Flows</TableCell><TableCell>Why</TableCell></TableRow></TableHead>
          <TableBody>
            {visible.slice(0, limit).map(({ entry, index, hasChildren, holdsNotSeen }) => {
              const pill = pillOf(entry.state);
              const open = !closed.has(index);
              // Under "Not seen only" a group that is itself used stays as the path to its unseen member, quietly.
              const context = notSeenOnly && entry.state !== "NOT_SEEN" && holdsNotSeen;
              return <TableRow key={index} data-state={entry.state} data-object={entry.objectId} sx={{ opacity: context ? 0.7 : 1 }}>
                <TableCell sx={{ pl: 2 + Math.min(Math.max(0, entry.depth ?? 0), 12) * 2.5 }}>
                  <Stack direction="row" alignItems="center" gap={0.5}>
                    {hasChildren
                      ? <Box component="button" type="button" aria-expanded={open} aria-label={`${open ? "Collapse" : "Expand"} ${entry.name}`} onClick={() => toggle(index)}
                          sx={{ border: 0, background: "none", cursor: "pointer", width: 20, p: 0, color: "inherit", font: "inherit" }}>{open ? "▾" : "▸"}</Box>
                      : <Box component="span" sx={{ width: 20 }} />}
                    <Box component="span" sx={{ overflowWrap: "anywhere" }}>{entry.name || entry.objectId}</Box>
                  </Stack>
                </TableCell>
                <TableCell><StatusPill dense tone={pill.tone} word={pill.word} /></TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: "tabular-nums" }}>{judged(entry.state) ? number(entry.flowCount ?? 0) : "—"}</TableCell>
                <TableCell>{usageReason(entry)}</TableCell>
              </TableRow>;
            })}
          </TableBody>
        </Table>
        {visible.length > limit && <Button onClick={() => setLimit((n) => n + ROW_STEP)}>Show {Math.min(ROW_STEP, visible.length - limit)} more of {number(visible.length - limit)}</Button>}
      </Box>}
  </Box>;
}

/** Today's narrowing suggestion for one rule; the whole-policy view and the one-rule tightening summary both show it. */
export function NarrowingSuggestion({ rule }: { readonly rule: OfflineRule }) {
  const narrowing = rule.narrowing ?? {};
  if (!narrowing.memberStatus) return null;
  return <><Typography variant="subtitle2">Narrowing suggestion</Typography>{narrowing.memberStatus === "UNKNOWN" ? <Typography>UNKNOWN: {narrowing.suggestionNote}</Typography> : <>
    <Table size="small" aria-label={`Narrowing for rule ${rule.number}`}><TableHead><TableRow><TableCell>Observed sources</TableCell><TableCell>Observed destinations</TableCell><TableCell>Observed services</TableCell></TableRow></TableHead><TableBody><TableRow>
      <TableCell>{narrowing.observedSource?.join(", ")}</TableCell><TableCell>{narrowing.observedDestination?.join(", ")}</TableCell><TableCell>{narrowing.observedService?.join(", ")}</TableCell>
    </TableRow></TableBody></Table>
    <Typography>Coverage: {narrowing.coveragePercent}% of {narrowing.coveredFlows} attributed flows. Preserve the existing action, zones, application and placement constraints. Combining independent sets may also admit unobserved combinations.</Typography>
    <Typography>{narrowing.caution}</Typography>
  </>}</>;
}

export function RuleUsageResult({ result, openRule, onExport, onDelete, busy = false }: {
  readonly result: OfflineAnalysisResult; readonly openRule: (id: string) => void; readonly onExport: () => void;
  /** Present only for a role that may delete the stored analysis. */
  readonly onDelete?: () => void; readonly busy?: boolean;
}) {
  const [show, setShow] = useState<"all" | "notSeen">("all");
  const rule = result.rules[0];
  const usage = rule?.objectUsage ?? [];
  const unknownFlows = (result.unknownStats ?? []).reduce((sum, stat) => sum + (stat.count ?? 0), 0);
  const notJudged = distinct(usage, (e) => !judged(e.state));
  const perDimension = RULE_USAGE_DIMENSIONS.map((d) => ({ ...d, entries: usage.filter((e) => e.dimension === d.id) }));
  const notSeenTotal = distinct(usage, (e) => e.state === "NOT_SEEN");
  if (!rule) {
    return <Alert severity="warning">This stored analysis names rule {result.ruleId || "UNKNOWN"} but holds no result for it. Nothing can be said about its objects.</Alert>;
  }
  return <Box role="region" aria-label="Rule object usage result" data-result-mode="ONE_RULE">
    <Stack direction="row" gap={1} flexWrap="wrap" alignItems="baseline">
      <Typography variant="h6" component="h2">Rule {rule.number} · {rule.name || "Unnamed rule"}</Typography>
      <Button size="small" onClick={() => openRule(rule.ruleId)}>Open rule in Policy</Button>
    </Stack>
    <Typography data-fact="rule-header">Section {rule.sectionName || "UNKNOWN"} · Action {rule.action || "UNKNOWN"}</Typography>
    <Typography data-fact="period">Period covered by the file: {result.periodStart || "UNKNOWN"} – {result.periodEnd || "UNKNOWN"}</Typography>
    {/* The stored snapshot the sample was matched against: its age bounds how far the attribution can be trusted. */}
    <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center" data-evaluated-snapshot="true">
      <Typography>Evaluated against snapshot collected {result.collectedAt ? formatStamp(result.collectedAt) : "UNKNOWN"}</Typography>
      <SnapshotFreshnessChip at={result.collectedAt} now={new Date()} />
    </Stack>
    <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Uploaded {result.createdAt} · {result.uploader} · {result.fileName}</Typography>

    <Stack component="dl" direction="row" flexWrap="wrap" gap={3} aria-label="What the file held" sx={{ my: 2 }}>
      <Count fact="rows-read" label="Rows read" value={number(result.rowCount)} />
      <Count fact="attributed" label="Flows attributed to this rule" value={number(rule.flowCount)} />
      <Count fact="set-aside" label="Rows set aside for other rules" value={number(result.otherRuleRows)} />
      <Count fact="unknown" label="Unknown flows" value={number(unknownFlows)} />
      <Count fact="invalid" label="Invalid rows" value={number(result.invalidRows)} />
    </Stack>
    {(unknownFlows > 0 || result.invalidRows > 0) && <Box component="details" sx={{ mb: 2 }}>
      <Box component="summary" sx={{ cursor: "pointer" }}>Why rows were unknown or invalid</Box>
      {([["Unknown (flows)", result.unknownStats], ["Invalid (rows)", result.parseStats]] as const).map(([label, stats]) => <Box key={label} sx={{ mt: 1 }}>
        <Typography variant="subtitle2">{label}</Typography>
        {(stats ?? []).length ? stats.map((s) => <Typography key={s.statReason}>{s.statReason}: {number(s.count)}</Typography>) : <Typography>None</Typography>}
      </Box>)}
    </Box>}
    {result.comparisonEnabled && <Typography variant="body2">Log rule identity against the lookup engine: {number(result.agreementCount)} agree · {number(result.disagreementCount)} disagree · {number(result.comparisonUnknownCount)} UNKNOWN. {result.comparisonNote}</Typography>}

    <Alert severity="info" data-wording="not-seen" sx={{ my: 2 }}>{NOT_SEEN_WORDING}</Alert>

    <Box component="section" aria-label="Tightening summary" sx={{ my: 2 }}>
      <Typography variant="subtitle1" component="h3">Tightening summary</Typography>
      {usage.length === 0 ? <Typography>This analysis holds no object list for the rule, so no object can be called used or not seen.</Typography>
        : notSeenTotal === 0 ? <Typography>No object of this rule is marked not seen in the uploaded period.</Typography>
        : <Typography data-fact="not-seen-summary">Not seen in the uploaded period: {perDimension.map((d) => `${d.label.toLowerCase()} ${number(distinct(d.entries, (e) => e.state === "NOT_SEEN"))}`).join(" · ")}. These are the candidates to review, not a removal list.</Typography>}
      {notJudged > 0 && <Typography variant="body2" data-fact="not-judged">{notJudged === 1 ? "1 object" : `${number(notJudged)} objects`} could not be judged (not auditable or UNKNOWN); each says why under All, and none is counted as not seen.</Typography>}
      <NarrowingSuggestion rule={rule} />
    </Box>

    <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center" sx={{ mt: 2 }}>
      <ToggleButtonGroup exclusive size="small" value={show} aria-label="Objects shown" onChange={(_e, value: "all" | "notSeen" | null) => { if (value) setShow(value); }}>
        <ToggleButton value="notSeen">Not seen only</ToggleButton>
        <ToggleButton value="all">All</ToggleButton>
      </ToggleButtonGroup>
      <Button onClick={onExport} disabled={usage.length === 0}>Export object list CSV</Button>
      {onDelete && <Button disabled={busy} color="error" onClick={onDelete}>Delete stored analysis</Button>}
    </Stack>
    {perDimension.map((d) => <DimensionTree key={d.id} label={d.label} entries={d.entries} notSeenOnly={show === "notSeen"} />)}
    {(result.limitations || result.caution) && <Typography variant="body2" sx={{ mt: 1, color: m3.onSurfaceVar }}>{[result.limitations, result.caution].filter(Boolean).join(" ")}</Typography>}
  </Box>;
}
