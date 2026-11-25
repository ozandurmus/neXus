import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Box, Button, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { getPolicyInsight, type InsightRuleRef, type InsightType, type PolicyInsight, type PolicyInsightDetail, type PolicyObject, type PolicyRule } from "../auth/adminApi";
import { CellEntries, ObjectChip, actionWord, policyFailureText, scheduleLabel } from "./PolicyRuleViewer";
import { CHANGE_LIST_NOTICE, COVER_ACTION_WORDS, GRADE_EXPLAIN, INSTALL_CAVEAT, TYPE_LABEL, coverReasons, gradeColor, insightHeadline, raiseText, suggestedActions,
  type ChangeItem, type SuggestedAction } from "./policyInsightActions";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";

/**
 * Policy Insights detail panel (PO 2026-11-25): what the insight says in one sentence, the involved rules side by side
 * with the cells the verdict rests on, and the suggested changes. A suggested change is only ever added to the
 * package's change list; nothing here contacts a device.
 */
type Cover = "holds" | "differs";
const SHADOW_COVER: Readonly<Record<string, Cover>> = { position: "holds", source: "holds", destination: "holds", service: "holds", installOn: "holds", action: "holds" };
/** The comparison rows the insight's verdict rests on, by type. I10 rests on counters, which have their own block. */
function coverFields(insight: PolicyInsight): Readonly<Record<string, Cover>> {
  switch (insight.insightType) {
    case "I1": return SHADOW_COVER;
    case "I2": return { ...SHADOW_COVER, action: "differs" };
    case "I3": {
      if (insight.evidenceGrade === "NOT_EVALUABLE") return {};
      const coverAction = String(insight.counter?.coverAction ?? "");
      return coverAction === "DIFFERENT" || coverAction === "INLINE_LAYER" ? { ...SHADOW_COVER, action: "differs" } : SHADOW_COVER;
    }
    case "I4": return insight.evidenceGrade === "NOT_EVALUABLE" ? {} : SHADOW_COVER;
    case "I8": return { enabled: "holds" };
    case "I9": return { schedule: "holds" };
    default: return {};
  }
}
const ROLE_TAG: Readonly<Record<InsightType, string>> = {
  I1: "Never reached", I2: "Never reached", I3: "Never reached", I4: "Partly shadowed",
  I8: "Disabled", I9: "Time-limited", I10: "No recent hits", I11: "", I12: "",
};
const sectionTitle = { fontSize: 10.5, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: m3.onSurfaceVar, display: "block", mt: 2.5, mb: 0.75 } as const;
const plain = (text: string) => <Typography variant="body2" component="span">{text}</Typography>;

const roleRank = (role: InsightRuleRef["insightRole"]) => role === "covering" || role === "overlapping" ? 0 : 1;
const roleLabel = (insight: PolicyInsight, role: InsightRuleRef["insightRole"]) => {
  if (role === "covering") return "Decides first";
  if (role === "overlapping") return "Overlapping earlier rule";
  if (insight.evidenceGrade === "NOT_EVALUABLE" && (insight.insightType === "I3" || insight.insightType === "I4")) return "Not proven";
  return ROLE_TAG[insight.insightType] || "Rule";
};

function RuleComparison({ insight, detail, days, openRule, openObject }: { insight: PolicyInsight; detail: PolicyInsightDetail; days: number;
  openRule: (id: string, days: number) => void; openObject: (id: string) => void }) {
  const objects = useMemo(() => new Map<string, PolicyObject>((detail.ruleObjects ?? []).map(o => [o.id, o])), [detail.ruleObjects]);
  // Covering / overlapping rules come first, in policy order, followed by the subject rule.
  const columns = [...insight.rules].sort((a, b) => roleRank(a.insightRole) - roleRank(b.insightRole))
    .map(ref => ({ ref, rule: detail.rules.find(r => r.id === ref.ruleId) }));
  const cover = coverFields(insight);
  const refs = (values: string[] | undefined) => !values?.length ? plain("Not stored")
    : <Box sx={{ display: "flex", flexWrap: "wrap", gap: "4px" }}>{values.map((v, i) => objects.has(v)
      ? <ObjectChip key={`${v}-${i}`} id={v} objects={objects} openObject={openObject} maxWidth={220} /> : <Box key={`${v}-${i}`} component="span" sx={{ fontSize: 12 }}>{v}</Box>)}</Box>;
  const rows: { key: string; label: string; render: (rule: PolicyRule) => ReactNode }[] = [
    { key: "position", label: "Position", render: r => plain(r.number > 0 ? `#${r.number}` : "UNKNOWN") },
    { key: "name", label: "Name", render: r => plain(r.name || "Unnamed rule") },
    { key: "source", label: "Source", render: r => <CellEntries cell={r.source} objects={objects} openObject={openObject} /> },
    { key: "destination", label: "Destination", render: r => <CellEntries cell={r.destination} objects={objects} openObject={openObject} /> },
    { key: "service", label: "Service / application", render: r => <><CellEntries cell={r.service} objects={objects} openObject={openObject} />
      {r.application.refs.length > 0 && <Box sx={{ mt: 0.5 }}><CellEntries cell={r.application} objects={objects} openObject={openObject} /></Box>}</> },
    { key: "action", label: "Action", render: r => plain(actionWord(r.action)) },
    { key: "log", label: "Track / log", render: r => plain(r.log || "UNKNOWN") },
    { key: "enabled", label: "Enabled", render: r => plain(r.enabled === false ? "Disabled" : r.enabled === true ? "Enabled" : "UNKNOWN") },
    ...(insight.insightType === "I9" ? [{ key: "schedule", label: "Schedule", render: (r: PolicyRule) => r.schedules?.length
      ? <>{r.schedules.map((s, i) => <Typography key={i} variant="body2">{scheduleLabel(s)}</Typography>)}</> : plain(`Time status: ${r.timeStatus ?? "unknown"}`) }] : []),
    { key: "installOn", label: "Install on", render: r => refs(r.extras?.["install-on"]) },
    { key: "comment", label: "Comments", render: r => plain(r.comment || "—") },
  ];
  const fill = (kind: Cover | undefined) => kind === "holds" ? m3.primaryContainer : kind === "differs" ? m3.warningContainer : undefined;
  const reasons = coverReasons(insight, detail);
  const moreOverlapping = insight.insightType === "I4" ? Number(insight.counter?.moreOverlapping ?? 0) : 0;
  return <>
    <Typography component="h3" sx={sectionTitle}>{columns.length > 1 ? "Rules side by side" : "Rule"}</Typography>
    <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Rule comparison" sx={{ tableLayout: "fixed", minWidth: columns.length > 1 ? Math.max(560, 132 + columns.length * 210) : 0 }}>
      <TableHead><TableRow>
        <TableCell sx={{ width: 132 }} />
        {columns.map(({ ref, rule }) => {
          const earlier = ref.insightRole === "covering" || ref.insightRole === "overlapping";
          return <TableCell key={ref.ruleId} data-role={ref.insightRole} sx={{ verticalAlign: "bottom" }}>
            <Chip size="small" data-role-tag={ref.insightRole} label={roleLabel(insight, ref.insightRole)}
              sx={{ fontWeight: 700, bgcolor: earlier ? m3.successContainer : m3.warningContainer, color: earlier ? m3.onSuccessContainer : m3.onWarningContainer }} />
            <Button size="small" onClick={() => openRule(ref.ruleId, days)} title="Open this rule" sx={{ display: "block", textAlign: "left", px: 0, textTransform: "none", overflowWrap: "anywhere" }}>
              #{(rule?.number ?? ref.number) || "?"} · {(rule?.name ?? ref.name) || "Unnamed rule"}</Button>
          </TableCell>;
        })}
      </TableRow></TableHead>
      <TableBody>{rows.map(row => <TableRow key={row.key}>
        <TableCell component="th" scope="row" sx={{ color: m3.onSurfaceVar, fontSize: 12, verticalAlign: "top" }}>{row.label}</TableCell>
        {columns.map(({ ref, rule }) => <TableCell key={ref.ruleId} data-field={row.key} data-role={ref.insightRole} data-cover={cover[row.key]}
          sx={{ verticalAlign: "top", overflowWrap: "anywhere", bgcolor: fill(cover[row.key]) }}>{rule ? row.render(rule) : plain("UNKNOWN")}</TableCell>)}
      </TableRow>)}</TableBody>
    </Table></Box>
    {moreOverlapping > 0 && <Typography variant="caption" display="block" sx={{ mt: 0.5, color: m3.onSurfaceVar }}>
      Showing {columns.filter(c => c.ref.insightRole === "overlapping").length} of {String(insight.counter?.overlappingRules ?? "?")} overlapping earlier rules (+{moreOverlapping} more not listed).</Typography>}
    {Object.keys(cover).length > 0 && <Typography variant="caption" display="block" sx={{ mt: 0.75, color: m3.onSurfaceVar }}>
      <Box component="span" aria-hidden sx={{ display: "inline-block", width: 10, height: 10, mr: 0.5, borderRadius: "2px", bgcolor: m3.primaryContainer, border: `1px solid ${m3.outlineVar}` }} />
      Highlighted cells are the ones this insight rests on.</Typography>}
    {reasons.length > 0 && <Box component="ul" aria-label="Why the cover holds" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
      {reasons.map(reason => <li key={reason}><Typography variant="body2">{reason}</Typography></li>)}</Box>}
  </>;
}

function ActionCard({ action, insight, chosen, add }: { action: SuggestedAction; insight: PolicyInsight; chosen: boolean; add: () => void }) {
  const proven = insight.evidenceGrade === "PROVEN";
  return <Box component="li" data-action={action.kind} data-strong={action.strong} sx={{ border: `1px solid ${chosen ? m3.primary : m3.outlineVar}`, borderRadius: 2, p: 1.25, bgcolor: m3.scLowest }}>
    <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
      <Button size="small" variant={action.strong && proven ? "contained" : "outlined"} color={action.strong && !proven ? "warning" : "primary"} aria-pressed={chosen} onClick={add}
        title="Adds this change to the change list. Nothing is sent to the firewall.">{chosen ? "✓ " : ""}{action.label}</Button>
      <Typography variant="caption" data-action-effect sx={{ color: m3.onSurfaceVar }}>{action.effect}</Typography>
      {chosen && <Typography variant="caption" sx={{ color: m3.primary, fontWeight: 700 }}>In the change list</Typography>}
    </Stack>
    <Typography variant="body2" sx={{ mt: 0.75 }}>{action.plan}</Typography>
    {action.caveat && <Typography variant="caption" display="block" data-action-caveat sx={{ mt: 0.5, color: m3.warningInk }}>{action.caveat}</Typography>}
    {action.warning && <Typography variant="caption" display="block" data-action-warning sx={{ mt: 0.5, color: m3.warningInk, fontWeight: 600 }}>⚠ {action.warning}</Typography>}
  </Box>;
}

export interface InsightDetailProps {
  policy: string; insightId: string; days: number;
  openRule: (id: string, days: number) => void; openObject: (id: string) => void;
  /** The planned change of this insight, if any; choosing another action replaces it. */
  planned: ChangeItem | undefined; changeCount: number;
  addChange: (insight: PolicyInsight, action: SuggestedAction, snapshotAt: string | undefined) => void; removeChange: (insightId: string) => void;
  showChanges: () => void; close: () => void;
}
export function InsightDetail({ policy, insightId, days, openRule, openObject, planned, changeCount, addChange, removeChange, showChanges, close }: InsightDetailProps) {
  const [detail, setDetail] = useState<PolicyInsightDetail | null>(null), [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    getPolicyInsight(policy, insightId, days).then(result => {
      if (!result?.insight || !Array.isArray(result.rules) || !Array.isArray(result.objects)) throw new Error("Invalid insight response");
      if (active) setDetail(result);
    }).catch(failure => { if (active) setError(policyFailureText(failure, "Insight detail could not be loaded.")); });
    return () => { active = false; };
  }, [policy, insightId, days]);
  const header = (title: string, chip?: ReactNode) => <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
    <Typography component="h2" variant="h6" sx={{ mr: "auto" }}>{title}</Typography>{chip}
    <Button size="small" variant="outlined" onClick={showChanges} aria-label={`Change list, ${changeCount} planned ${changeCount === 1 ? "change" : "changes"}`}>Change list · {changeCount}</Button>
    <Button size="small" onClick={close}>Close detail</Button>
  </Stack>;
  if (error) return <>{header("Insight")}<Typography role="alert" sx={{ mt: 2 }}>{error}</Typography></>;
  if (!detail) return <>{header("Insight")}<Typography role="status" sx={{ mt: 2 }}>Loading insight detail…</Typography></>;
  const insight = detail.insight, counter = insight.counter ?? {};
  const actions = suggestedActions(insight, detail);
  return <>
    {header(`${insight.insightType} · ${TYPE_LABEL[insight.insightType] ?? "UNKNOWN"}`,
      <Chip size="small" color={gradeColor(insight.evidenceGrade)} label={insight.evidenceGrade} title={GRADE_EXPLAIN[insight.evidenceGrade]} />)}
    <Typography data-insight-headline sx={{ mt: 1.5, fontSize: 15, fontWeight: 600, lineHeight: 1.45 }}>{insightHeadline(insight, detail)}</Typography>
    <Typography variant="caption" display="block" sx={{ mt: 0.5, color: m3.onSurfaceVar }}>{insight.evidenceGrade}: {GRADE_EXPLAIN[insight.evidenceGrade] ?? "UNKNOWN"} Raise to: {raiseText(insight)}.</Typography>
    {insight.insightCaveats.includes("INSTALL_STATE_NOT_VERIFIED") && <Typography role="note" variant="caption" display="block" sx={{ mt: 0.5, color: m3.warningInk }}>{INSTALL_CAVEAT}</Typography>}

    {insight.rules.length > 0 && <RuleComparison insight={insight} detail={detail} days={days} openRule={openRule} openObject={openObject} />}
    {detail.objects.length > 0 && <>
      <Typography component="h3" sx={sectionTitle}>Objects (domain-wide)</Typography>
      <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Objects involved">
        <TableHead><TableRow>{["Name", "Type", "Values", "Used by rules", "Used by groups"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
        <TableBody>{detail.objects.map(o => <TableRow key={o.uid}>
          <TableCell>{o.name}</TableCell><TableCell>{o.type}</TableCell><TableCell sx={{ overflowWrap: "anywhere" }}>{o.values?.length ? o.values.join(", ") : "—"}</TableCell>
          <TableCell>{o.ruleCount ?? 0}</TableCell><TableCell>{o.groupCount ?? 0}</TableCell></TableRow>)}</TableBody>
      </Table></Box>
    </>}

    <Typography component="h3" sx={sectionTitle}>Suggested actions</Typography>
    <Box component="ul" aria-label="Suggested actions" sx={{ m: 0, p: 0, listStyle: "none", display: "grid", gap: 1 }}>
      {actions.map(action => <ActionCard key={action.kind} action={action} insight={insight} chosen={planned?.kind === action.kind}
        add={() => addChange(insight, action, detail.collectedAt)} />)}
    </Box>
    <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap" sx={{ mt: 1 }}>
      <Typography variant="caption" data-change-notice sx={{ color: m3.onSurfaceVar }}>Each button adds the change to this package's change list. {CHANGE_LIST_NOTICE}</Typography>
      {planned && <Button size="small" onClick={() => removeChange(insight.insightId)}>Remove from list</Button>}
    </Stack>

    <Typography component="h3" sx={sectionTitle}>Evidence</Typography>
    <Box component="ul" aria-label="Insight evidence" sx={{ m: 0, pl: 2.5 }}>
      {insight.evidence.map(e => <li key={e}><Typography variant="body2">{e}</Typography></li>)}
      {insight.evidence.length === 0 && <li><Typography variant="body2">Derived from the stored object inventory and where-used counts.</Typography></li>}
    </Box>
    {insight.insightType === "I10" && <Box aria-label="Counter evidence" sx={{ mt: 1 }}>
      <Typography variant="body2">Hits: {counter.hits ?? "UNKNOWN"} · First hit: {formatStamp(counter.firstHit as string | null)} · Last hit: {formatStamp(counter.lastHit as string | null)}</Typography>
      <Typography variant="body2">Source: {String(counter.hitSource ?? "UNKNOWN")} · Collected: {formatStamp(counter.hitsCollectedAt as string | null)}</Typography>
      <Typography variant="body2">{String(counter.counterWindow ?? "Counter window unknown.")}{counter.windowKnown === true ? ` Measured window: ${counter.windowDays} days.` : " No measured window; hit counters carry no window start."}</Typography>
    </Box>}
    {insight.insightType === "I3" && <Box aria-label="Union cover evidence" sx={{ mt: 1 }}>
      {typeof counter.coveringRules === "number" && <Typography variant="body2">Covered together by {counter.coveringRules} earlier rules of the same layer. {COVER_ACTION_WORDS[String(counter.coverAction)] ?? "Action relation UNKNOWN."}</Typography>}
      {counter.unionReason !== undefined && <Typography variant="body2">Not proven: {String(counter.unionReason)}. The rule may or may not be covered; nothing is claimed.</Typography>}
    </Box>}
    {insight.insightType === "I4" && <Box aria-label="Partial shadow evidence" sx={{ mt: 1 }}>
      {typeof counter.overlappingRules === "number" && <Typography variant="body2">Part of this rule's match is decided by earlier rules with the same action; its zero or low counter can be explained by them.
        {" "}Overlapping earlier rules: {counter.overlappingRules}{Number(counter.moreOverlapping) > 0 ? ` (+${counter.moreOverlapping} more not listed)` : ""}.</Typography>}
      {counter.partialReason !== undefined && <Typography variant="body2">Not proven: {String(counter.partialReason)}. The rule may or may not overlap earlier rules; nothing is claimed.</Typography>}
    </Box>}
  </>;
}

/** The package's planned changes in plain words. The list lives in this browser only; export is a file the user carries into their change window. */
export function ChangeListPanel({ items, remove, exportText, exportCsv, back, close }: { items: readonly ChangeItem[]; remove: (insightId: string) => void;
  exportText: () => void; exportCsv: () => void; back?: () => void; close: () => void }) {
  return <>
    <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
      <Typography component="h2" variant="h6" sx={{ mr: "auto" }}>Change list · {items.length}</Typography>
      {back && <Button size="small" onClick={back}>Back to insight</Button>}
      <Button size="small" onClick={close}>Close change list</Button>
    </Stack>
    <Typography role="note" data-change-notice sx={{ mt: 1.5, p: 1.25, borderRadius: 2, bgcolor: m3.primaryContainer, color: m3.onPrimaryContainer, fontWeight: 600, fontSize: 14 }}>{CHANGE_LIST_NOTICE}</Typography>
    <Typography variant="caption" display="block" sx={{ mt: 0.75, color: m3.onSurfaceVar }}>
      This list is kept in this browser for this package. Rule numbers are positions in the snapshot named on each change; check them against the current policy.</Typography>
    <Stack direction="row" gap={1} flexWrap="wrap" sx={{ mt: 1.5 }}>
      <Button size="small" variant="contained" disabled={items.length === 0} onClick={exportText}>Export change list (text)</Button>
      <Button size="small" variant="outlined" disabled={items.length === 0} onClick={exportCsv}>Export change list (CSV)</Button>
    </Stack>
    {items.length === 0 && <Typography role="status" sx={{ mt: 2 }}>No planned changes. Open an insight and choose a suggested action.</Typography>}
    <Box component="ol" aria-label="Planned changes" sx={{ m: 0, mt: 1.5, p: 0, listStyle: "none", display: "grid", gap: 1 }}>
      {items.map((item, index) => <Box component="li" key={item.insightId} data-change={item.kind} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 1.25, bgcolor: m3.scLowest }}>
        <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
          <Typography variant="subtitle2" sx={{ mr: "auto" }}>{index + 1}. {item.label}</Typography>
          <Chip size="small" label={`${item.insightType} · ${item.evidenceGrade}`} color={gradeColor(item.evidenceGrade)} />
          <Button size="small" aria-label={`Remove from list: ${item.label}, change ${index + 1}`} onClick={() => remove(item.insightId)}>Remove from list</Button>
        </Stack>
        <Typography variant="body2" sx={{ mt: 0.5 }}>{item.plan}</Typography>
        <Typography variant="caption" display="block" sx={{ mt: 0.5, color: m3.onSurfaceVar }}>{item.effect} · Snapshot collected {formatStamp(item.snapshotAt)}</Typography>
        {item.caveat && <Typography variant="caption" display="block" sx={{ mt: 0.25, color: m3.warningInk }}>{item.caveat}</Typography>}
        {item.warning && <Typography variant="caption" display="block" sx={{ mt: 0.25, color: m3.warningInk, fontWeight: 600 }}>⚠ {item.warning}</Typography>}
      </Box>)}
    </Box>
  </>;
}
