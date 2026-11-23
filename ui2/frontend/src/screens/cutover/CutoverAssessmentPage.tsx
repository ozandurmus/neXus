import { useEffect, useState, type ReactNode } from "react";
import { Alert, Box, Button, Link, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography } from "@mui/material";

import {
  deleteCutoverAssessment, getCutoverAssessment, getCutoverFlows, getCutoverSegment, listDevices,
  type CutoverAssessmentView, type CutoverFlowItem, type CutoverFlowPage, type CutoverMissingRuleItem, type DeviceSummary,
} from "../../auth/adminApi";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { StatusPill } from "../../shell/StatusPill";
import { formatStamp } from "../../shell/time";
import { urlParam, writeUrlParams } from "../../shell/urlParams";
import { m3 } from "../../theme/m3Theme";
import { cutoverFailureText } from "./cutoverErrors";
import {
  RESULT_TABS, STALE_REASON, coverageText, decisionOf, directionOf, flagText, readinessOf, resultTabOf, ruleText, targetSourceLabel, type ResultTab,
} from "./cutoverWording";

const PAGE = 100;
const number = (value: number | null | undefined) => (typeof value === "number" && Number.isFinite(value) ? value.toLocaleString("en-US") : "UNKNOWN");
const TRACE_HREF = "?screen=trace&mode=path";

/**
 * One stored assessment (brief, screen 3). The verdict line is the server's headline, its readiness chip and the
 * target label; the coverage list sits directly under it, never collapsed and never empty. The tabs each ask the server
 * for their own decision, so no count is recomputed here.
 */
export function CutoverAssessmentPage({ assessmentId, back }: { readonly assessmentId: string; readonly back: () => void }) {
  const [view, setView] = useState<CutoverAssessmentView | null>(null), [failure, setFailure] = useState<unknown>(null);
  const [canManage, setCanManage] = useState(false), [devices, setDevices] = useState<Map<string, DeviceSummary>>(new Map());
  const [tab, setTab] = useState<ResultTab>(() => resultTabOf(urlParam("tab")));
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  useEffect(() => {
    let active = true; setFailure(null); setView(null);
    getCutoverAssessment(assessmentId).then((found) => {
      if (!active) return;
      setView(found);
      // Delete is offered by the segment's own answer (canManage); the assessment read carries no grant.
      getCutoverSegment(found.segmentId).then((s) => { if (active) setCanManage(Boolean(s.canManage)); }).catch(() => { if (active) setCanManage(false); });
    }).catch((e) => { if (active) setFailure(e); });
    listDevices().then((r) => { if (active) setDevices(new Map((r.devices ?? []).map((d) => [d.device_id, d]))); }).catch(() => undefined);
    return () => { active = false; };
  }, [assessmentId]);
  const deviceName = (id: string | null | undefined) => (id ? devices.get(id)?.hostname || id : "UNKNOWN");
  const remove = async () => {
    if (!window.confirm("Delete this stored assessment and its flows? This cannot be undone.")) return;
    setBusy(true); setError("");
    try { await deleteCutoverAssessment(assessmentId); back(); }
    catch (e) { setError(cutoverFailureText(e, "The assessment could not be deleted.")); }
    finally { setBusy(false); }
  };

  if (failure) return <Box><Button onClick={back}>Back to the segment</Button>{isRestricted(failure) ? <RestrictedPanel area="Cutover impact" />
    : <StatePanel variant="error" title="The assessment could not be read" body={cutoverFailureText(failure, "The assessment could not be loaded.")} />}</Box>;
  if (!view) return <StatePanel variant="empty" title="Reading the assessment…" announce="status" />;
  const readiness = readinessOf(view.readiness);
  const counts = view.counts ?? {};
  const coverage = view.coverage ?? [];
  const override = view.targetSource === "OPERATOR_OVERRIDE";
  return <Box role="region" aria-label="Cutover assessment" data-readiness={view.readiness}>
    <Button onClick={back}>Back to the segment</Button>
    {view.stale && <Alert severity="warning" data-fact="stale" sx={{ my: 1 }}>
      STALE: {(view.staleReasons ?? []).map((r) => STALE_REASON[r] ?? r).join("; ") || "the evidence changed since this assessment"}. Run a new assessment before relying on it.
    </Alert>}

    <Box data-fact="verdict" sx={{ my: 1 }}>
      <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
        <StatusPill tone={readiness.tone} word={readiness.word} fact="readiness" />
        <Typography component="span" data-fact="target-source" sx={{ fontWeight: 600, ...(override ? { color: m3.onErrorContainer } : {}) }}>{targetSourceLabel(view.targetSource)}</Typography>
        {view.status === "INCOMPLETE" && <StatusPill tone="warn" word="Incomplete" fact="incomplete" />}
      </Stack>
      <Typography variant="h6" component="h2" data-fact="headline" sx={{ mt: 1 }}>{view.headline || "UNKNOWN"}</Typography>
    </Box>

    <Box component="section" aria-label="Coverage" data-fact="coverage" sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 1, p: 1.5, my: 1 }}>
      <Typography variant="subtitle2" component="h3">What this verdict covers, and what it does not</Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
        {coverage.length === 0 ? <li data-coverage="NONE">The server returned no coverage list: read this verdict as incomplete.</li>
          : coverage.map((item, i) => <li key={`${item.code}:${i}`} data-coverage={item.code}>{coverageText(item)}</li>)}
      </Box>
    </Box>

    <Stack component="dl" direction="row" flexWrap="wrap" gap={3} aria-label="Counts" sx={{ my: 2 }}>
      <Fact label="Distinct flows" value={number(counts.tuples)} />
      <Fact label="Logged connections" value={number(counts.flows)} />
      <Fact label="Would drop" value={number(counts.WOULD_DROP)} />
      <Fact label="Not decided" value={number(counts.NOT_DECIDED)} />
      <Fact label="Not evaluable" value={number(counts.NOT_EVALUABLE)} />
      <Fact label="Would allow" value={number(counts.WOULD_ALLOW)} />
      <Fact label="Already dropped today" value={number(counts.alreadyDroppedTuples)} />
      <Fact label="Missing-rule groups" value={number(counts.missingRuleGroups)} />
    </Stack>
    <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Flows already dropped today are shown but are not counted as blockers.</Typography>

    <Box component="details" sx={{ my: 1.5 }} data-fact="provenance">
      <Box component="summary" sx={{ cursor: "pointer" }}>Evidence used</Box>
      <Typography variant="body2">Made {formatStamp(view.createdAt)}{view.actor ? ` by ${view.actor}` : ""} · engine {view.engineVersion || "UNKNOWN"} · segment revision {view.segmentRevision}</Typography>
      {override ? <Typography variant="body2">Target firewall {deviceName(view.targetDeviceId)}{view.targetVendor ? ` (${view.targetVendor})` : ""}
          {view.targetCollectedAt ? ` · policy collected ${formatStamp(view.targetCollectedAt)}` : " · no stored policy evaluated"}
          {view.segmentZone || view.peerZone ? ` · zones ${view.segmentZone || "UNKNOWN"} / ${view.peerZone || "UNKNOWN"}` : ""}</Typography>
        : <Typography variant="body2">Paths computed on topology snapshot {view.topologySnapshotId || "UNKNOWN"}</Typography>}
      {view.oldPolicyId && <Typography variant="body2">Old-path policy collected {formatStamp(view.oldCollectedAt)}{view.oldDeviceId ? ` · firewall ${deviceName(view.oldDeviceId)}` : ""}</Typography>}
      {(view.uploads ?? []).length > 0 && <Table size="small" aria-label="Uploaded files" sx={{ mt: 1 }}>
        <TableHead><TableRow><TableCell>File</TableCell><TableCell align="right">Rows</TableCell><TableCell align="right">Invalid</TableCell><TableCell align="right">In scope</TableCell><TableCell>Period</TableCell></TableRow></TableHead>
        <TableBody>{(view.uploads ?? []).map((u) => <TableRow key={u.index}>
          <TableCell>{u.index + 1}</TableCell><TableCell align="right">{number(u.rowCount)}</TableCell><TableCell align="right">{number(u.invalidRows)}</TableCell>
          <TableCell align="right">{number(u.inScopeFlows)}</TableCell><TableCell>{u.periodStart || "UNKNOWN"} – {u.periodEnd || "UNKNOWN"}</TableCell>
        </TableRow>)}</TableBody>
      </Table>}
    </Box>

    {error && <Alert severity="error">{error}</Alert>}
    {canManage && <Button color="error" disabled={busy} onClick={() => void remove()}>Delete stored assessment</Button>}

    <Tabs value={tab.slug} aria-label="Assessment results" variant="scrollable" sx={{ mt: 2, borderBottom: `1px solid ${m3.outlineVar}` }}
      onChange={(_e, slug: string) => { const next = resultTabOf(slug); setTab(next); writeUrlParams({ tab: next.slug }); }}>
      {RESULT_TABS.map((t) => <Tab key={t.slug} value={t.slug} label={`${t.label} · ${number(t.kind === "MISSING_RULE" ? counts.missingRuleGroups : counts[t.decision as keyof typeof counts])}`} />)}
    </Tabs>
    <ResultList key={tab.slug} assessmentId={assessmentId} tab={tab} deviceName={deviceName} />
  </Box>;
}

function Fact({ label, value }: { readonly label: string; readonly value: ReactNode }) {
  return <Box sx={{ minWidth: 130 }}>
    <Typography component="dt" variant="caption" sx={{ display: "block", color: m3.onSurfaceVar }}>{label}</Typography>
    <Typography component="dd" sx={{ m: 0, fontVariantNumeric: "tabular-nums" }}>{value}</Typography>
  </Box>;
}

function ResultList({ assessmentId, tab, deviceName }: { readonly assessmentId: string; readonly tab: ResultTab; readonly deviceName: (id: string) => string }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<CutoverFlowPage<CutoverFlowItem | CutoverMissingRuleItem> | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true; setData(null); setError("");
    const request = tab.kind === "MISSING_RULE" ? getCutoverFlows(assessmentId, "MISSING_RULE", null, page) : getCutoverFlows(assessmentId, "FLOW", tab.decision, page);
    request.then((r) => { if (active) setData(r); }).catch((e) => { if (active) setError(cutoverFailureText(e, "The flows could not be loaded.")); });
    return () => { active = false; };
  }, [assessmentId, tab, page]);
  if (error) return <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>;
  if (!data) return <Typography role="status" sx={{ mt: 1 }}>Reading the flows…</Typography>;
  const pager = data.total > PAGE && <Stack direction="row" gap={1} alignItems="center" sx={{ mt: 1 }}>
    <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
    <Typography variant="body2">{number(page * PAGE + 1)}–{number(Math.min((page + 1) * PAGE, data.total))} of {number(data.total)}</Typography>
    <Button disabled={(page + 1) * PAGE >= data.total} onClick={() => setPage((p) => p + 1)}>Next</Button>
  </Stack>;
  // Q7 retention: an expired assessment never reads as "no flows".
  if (data.flowsExpired && data.items.length === 0) return <Alert severity="info" sx={{ mt: 1.5 }} data-fact="flows-expired">
    Flow rows were removed after {number(data.retentionDays)} days (retention); the verdict and counts above are kept.</Alert>;
  if (data.items.length === 0) return <Typography sx={{ mt: 1.5 }} data-fact="no-rows">{tab.kind === "MISSING_RULE" ? "No missing-rule group in this assessment." : `No flow is ${tab.label.toLowerCase()} in this assessment.`}</Typography>;
  if (tab.kind === "MISSING_RULE") return <Box sx={{ mt: 1 }}>
    <Typography variant="body2" sx={{ my: 1 }}>Flows the target would drop or leaves undecided while the old path allows them (or has no old-path reference), grouped by destination and service: candidates for rules to add, to review, not to apply as they are.</Typography>
    <MissingRules items={data.items as CutoverMissingRuleItem[]} />{pager}
  </Box>;
  const flows = data.items as CutoverFlowItem[];
  // Not decided is read by its reason; the grouping is of this page only, in the server's order.
  const groups = tab.decision === "NOT_DECIDED" ? groupBy(flows, (f) => f.decisionReason || "UNKNOWN") : [["", flows] as const];
  return <Box sx={{ mt: 1 }}>
    {groups.map(([reason, items]) => <Box key={reason} data-reason={reason || undefined}>
      {reason && <Typography variant="subtitle2" sx={{ mt: 1.5 }}>Reason: {reason} · {items.length} on this page</Typography>}
      <FlowTable flows={items} deviceName={deviceName} />
    </Box>)}
    {pager}
  </Box>;
}

function groupBy<T>(items: readonly T[], key: (item: T) => string): (readonly [string, T[]])[] {
  const out = new Map<string, T[]>();
  for (const item of items) { const k = key(item); out.set(k, [...(out.get(k) ?? []), item]); }
  return [...out.entries()];
}

function FlowTable({ flows, deviceName }: { readonly flows: readonly CutoverFlowItem[]; readonly deviceName: (id: string) => string }) {
  return <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Flows">
    <TableHead><TableRow>
      <TableCell>Direction</TableCell><TableCell>Source</TableCell><TableCell>Destination</TableCell><TableCell>Service</TableCell>
      <TableCell align="right">Connections</TableCell><TableCell>Decision</TableCell><TableCell>Why</TableCell>
    </TableRow></TableHead>
    <TableBody>{flows.map((f, i) => <FlowRow key={i} flow={f} deviceName={deviceName} />)}</TableBody>
  </Table></Box>;
}

function FlowRow({ flow, deviceName }: { readonly flow: CutoverFlowItem; readonly deviceName: (id: string) => string }) {
  const decision = decisionOf(flow.decision);
  const flags = flow.flags ?? [];
  const dropped = flags.includes("ALREADY_DROPPED_TODAY");
  return <TableRow data-decision={flow.decision} data-flags={flags.join(" ") || undefined} sx={dropped ? { opacity: 0.6 } : undefined}>
    <TableCell>{directionOf(flow.direction)}</TableCell>
    <TableCell sx={{ fontFamily: "monospace" }}>{flow.flowSource}</TableCell>
    <TableCell sx={{ fontFamily: "monospace" }}>{flow.flowDestination}</TableCell>
    <TableCell>{flow.protocol}/{flow.port}</TableCell>
    <TableCell align="right" sx={{ fontVariantNumeric: "tabular-nums" }}>{number(flow.flowCount)}</TableCell>
    <TableCell><StatusPill dense tone={dropped ? "neutral" : decision.tone} word={decision.word} /></TableCell>
    <TableCell>
      <Stack gap={0.5}>
        {flow.decidingRule && <Typography variant="body2">Rule {ruleText(flow.decidingRule)}{flow.decidingRule.sectionName ? ` · ${flow.decidingRule.sectionName}` : ""}</Typography>}
        {flow.decisionReason && <Typography variant="body2">{flow.decisionReason}</Typography>}
        {flags.map((flag) => <Typography key={flag} variant="body2" data-flag={flag} sx={flag === "ALREADY_DROPPED_TODAY" ? { color: m3.onSurfaceVar } : undefined}>
          {flagText(flag, flow.conditionalRule, flow.droppedConstraints)}</Typography>)}
        {flow.oldPathRule && <Typography variant="body2">Old-path rule {ruleText(flow.oldPathRule)}</Typography>}
        {(flow.pathFirewalls ?? []).length > 0 && <Box data-fact="path-firewalls">
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Firewalls on the computed path:</Typography>
          <Box component="ol" sx={{ m: 0, pl: 2.5 }}>{(flow.pathFirewalls ?? []).map((hop, i) => <li key={`${hop.deviceId}:${i}`}>
            {deviceName(hop.deviceId)}{hop.vendor ? ` (${hop.vendor})` : ""} · {hop.hopVerdict || "UNKNOWN"}{hop.decidingRule ? ` · rule ${ruleText(hop.decidingRule)}` : ""}
          </li>)}</Box>
        </Box>}
        {flow.targetSource === "PATH" && <Link variant="body2" href={TRACE_HREF} title={`Trace ${flow.flowSource} → ${flow.flowDestination} ${flow.protocol}/${flow.port}`}>
          Open Trace for this tuple ({flow.flowSource} → {flow.flowDestination} {flow.protocol}/{flow.port})</Link>}
      </Stack>
    </TableCell>
  </TableRow>;
}

function MissingRules({ items }: { readonly items: readonly CutoverMissingRuleItem[] }) {
  return <Box sx={{ overflowX: "auto" }}><Table size="small" aria-label="Missing rules">
    <TableHead><TableRow>
      <TableCell>Direction</TableCell><TableCell>Sources</TableCell><TableCell>Destination</TableCell><TableCell>Service</TableCell>
      <TableCell align="right">Connections</TableCell><TableCell>Decisions</TableCell><TableCell>Old-path rule</TableCell>
    </TableRow></TableHead>
    <TableBody>{items.map((m, i) => <TableRow key={i}>
      <TableCell>{directionOf(m.direction)}</TableCell>
      <TableCell sx={{ fontFamily: "monospace" }}>{m.flowSources ? m.flowSources.join(", ") : m.sourceRange ? `${m.sourceRange} (${number(m.sourceCount)} sources)` : number(m.sourceCount)}</TableCell>
      <TableCell sx={{ fontFamily: "monospace" }}>{m.flowDestination}</TableCell>
      <TableCell>{m.protocol}/{m.port}</TableCell>
      <TableCell align="right">{number(m.flowCount)}</TableCell>
      <TableCell>{(m.decisionCounts ?? []).map((d) => `${decisionOf(d.decision).word} ${number(d.count)}`).join(" · ")}</TableCell>
      <TableCell>{ruleText(m.oldPathRule) || "—"}</TableCell>
    </TableRow>)}</TableBody>
  </Table></Box>;
}

