import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Box, Button, Checkbox, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from "@mui/material";
import { relativeAge, formatTime } from "../shell/time";
import { VendorBadge } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { getPolicyHistory, type PolicyCell, type PolicyMetadata, type PolicyObject, type PolicyPage, type PolicyRevision, type PolicyRule, type PolicySchedule, type PolicySection } from "../auth/adminApi";

export function reportPolicyLoadError(scope: "snapshots" | "rules" | "object" | "history" | "inventory" | "usage", error: unknown) {
  // Record categories only: error messages and API bodies may contain sensitive identities.
  const failure = error as { status?: unknown; body?: { error?: unknown } } | null;
  const code = failure?.body?.error === "TIMEOUT" ? "TIMEOUT"
    : typeof failure?.status === "number" && Number.isInteger(failure.status) && failure.status >= 100 && failure.status <= 599 ? `HTTP_${failure.status}`
    : error instanceof SyntaxError ? "INVALID_JSON"
    : error instanceof Error && ["Empty policy response", "Invalid history response", "Invalid object response"].includes(error.message) ? "INVALID_RESPONSE_SHAPE"
    : error instanceof TypeError ? "NETWORK_OR_TYPE_ERROR" : "CLIENT_ERROR";
  console.debug("policy-load", scope, code);
  return code;
}

export function scheduleLabel(s: PolicySchedule) {
  const zone = `${s.timezone}${s.timezoneKnown ? "" : " (device timezone unknown)"}`;
  if (s.kind === "unknown") return `Unknown schedule · ${zone}`;
  const date = (value: string) => value.replace(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2}).*$/, (_, year, month, day, hour) =>
    `${Number(day)} ${["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"][Number(month) - 1]} ${year} ${hour}`);
  if (s.kind === "one-time") {
    if (s.windows.length > 1) return `${s.windows.map(w => `${date(w.start)} – ${date(w.end)}`).join("; ")} · ${zone}`;
    return `${s.start ? `From ${date(s.start)} · ` : ""}${s.end ? `Until ${date(s.end)}` : "No end date"} · ${zone}`;
  }
  const dayNames = ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  return `${s.start ? `From ${date(s.start)} · ` : ""}${s.windows.map(w => `${w.months?.length ? `Months ${w.months.join(", ")} · ` : ""}${w.days.join(",") === "1,2,3,4,5" ? "Weekdays" : w.days.length ? w.days.map(d => dayNames[d]).join(", ") : w.monthDays.length ? `Days ${w.monthDays.join(", ")} of month` : "Daily"} ${w.start}–${w.end}`).join("; ")}${s.end ? ` · Until ${date(s.end)}` : ""} · ${zone}`;
}
export function containerLabel(metadata: PolicyMetadata, section: PolicySection, rule: PolicyRule) {
  if (metadata.vendor === "CP") return `${section.parentRuleId ? "Inline layer" : "Access layer"}: ${rule.extras["layer-name"]?.[0] ?? section.name}`;
  if (metadata.vendor === "PAN") return section.name === "Local rules" ? `Local firewall: ${section.source}`
    : `${section.source === "Shared" ? "Shared" : `Device group: ${section.source}`} (${section.name === "Post rules" ? "Post" : "Pre"})`;
  return section.source;
}
export function sectionLabel(metadata: PolicyMetadata, section: PolicySection) {
  if (metadata.vendor !== "PAN" || !["Pre rules", "Post rules"].includes(section.name)) return section.name;
  return `${section.source === "Shared" ? "Shared" : "Device-group"} ${section.name === "Pre rules" ? "pre" : "post"}-rules`;
}
function CellEntries({ cell, objects, openObject, limit = 4 }: { cell: PolicyCell; objects: Map<string, PolicyObject>; openObject: (id: string) => void; limit?: number }) {
  const [expanded, setExpanded] = useState(false);
  return <Box>
    {cell.negated && <Chip size="small" label="NOT" color="warning" />}
    {!cell.refs.length && <Typography variant="caption">—</Typography>}
    {(expanded ? cell.refs : cell.refs.slice(0, limit)).map((id, index) => <Button key={`${id}-${index}`} size="small" onClick={() => openObject(id)}
      title={objects.get(id)?.name ?? "Unresolved object"} sx={{ display: "block", textAlign: "left", textTransform: "none", p: 0, minWidth: 0, maxWidth: "100%", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", fontSize: 12 }}>
      {objects.get(id)?.name ?? "Unresolved object"}</Button>)}
    {cell.refs.length > limit && <Button size="small" aria-expanded={expanded} onClick={() => setExpanded(v => !v)} sx={{ p: 0 }}>
      {expanded ? "▴ Less" : `+${cell.refs.length - limit} ▾`}</Button>}
  </Box>;
}
const fieldLabels: Record<string, string> = { "source-user": "Source user", category: "URL category", from: "From", to: "To", "rule-type": "Type", "install-on": "Install on", "target-selectors": "Targets", vpn: "VPN", "time": "Time", schedule: "Schedule", "tag": "Tags", "layer-name": "Layer", "last-modified": "Last modified", "last-modifier": "Changed by" };
function Extra({ name, values, objects, openObject }: { name: string; values: string[]; objects: Map<string, PolicyObject>; openObject: (id: string) => void }) {
  const [expanded, setExpanded] = useState(false);
  return <Box sx={{ fontSize: 12, overflowWrap: "anywhere" }}><strong>{fieldLabels[name] ?? name}: </strong>
    {(expanded ? values : values.slice(0, 4)).map((v, i) => <span key={`${v}-${i}`}>{i > 0 && ", "}{objects.has(v)
      ? <Button size="small" sx={{ p: 0, minWidth: 0, textTransform: "none", fontSize: 12 }} onClick={() => openObject(v)}>{objects.get(v)?.name}</Button> : v}</span>)}
    {values.length > 4 && <Button size="small" aria-expanded={expanded} onClick={() => setExpanded(v => !v)}>{expanded ? "▴ Less" : `+${values.length - 4} ▾`}</Button>}
  </Box>;
}
export function RuleMetrics({ rule, openRuleId }: { rule: PolicyRule; openRuleId?: (id: string) => void }) {
  const status = rule.timeStatus ?? "unknown";
  const tiles = [
    ["Hits", String(rule.hitCounts?.hits ?? "UNKNOWN"), rule.hitCounts ? `${rule.hitCounts.source} · Collected: ${rule.hitCounts.collectedAt}` : "Hit counts not collected"],
    ["Last hit", relativeAge(rule.hitCounts?.lastHit), rule.hitCounts?.lastHit ? `${formatTime(rule.hitCounts.lastHit)} · ${rule.hitCounts.lastHit}` : "Last hit unknown"],
    ["First hit", formatTime(rule.hitCounts?.firstHit), rule.hitCounts?.firstHit ?? "First hit unknown"],
    ["Last modified", rule.extras["last-modified"]?.[0] ?? "—", "vendor modification time, when collected"],
    ["Time/Schedule status", status === "unknown" ? "—" : status, (rule.schedules ?? []).map(scheduleLabel).join("; ") || (status === "always" ? "Unbounded schedule" : "schedule not collected")],
    ["Permissiveness", rule.permissiveness?.level === "Unknown" ? "—" : rule.permissiveness?.level ?? "—", rule.permissiveness?.reasons.join("; ") || "requires analysis"],
    ["Shadowed", rule.hygiene?.shadowStatus ?? "UNKNOWN", rule.hygiene?.shadowReason ?? "Stored-snapshot analysis"],
    ["Violations", rule.hygiene ? String(rule.hygiene.findings.filter(f => f.findingClass !== "unknown").length) : "UNKNOWN", "Stored hygiene findings; UNKNOWN entries are evidence gaps"],
  ];
  return <Box className="policy-rule-metrics" aria-label="Rule metrics" sx={{ width: 220, maxWidth: "100%", display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))", gap: 1 }}>
    {tiles.map(([name, value, tip]) => <Tooltip key={name} title={tip}><Box sx={{ borderBottom: `1px solid ${m3.outlineVar}`, pb: 1, minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary">{name}</Typography>
      <Typography variant="body2" sx={{ fontWeight: 700, overflowWrap: "anywhere", color: value === "High" || value === "expired" ? m3.error : value === "Medium" ? m3.warning : value === "Low" ? m3.success : undefined }}>{value}</Typography>
      {name === "Permissiveness" && <Typography variant="caption" display="block">Why this level: {tip}{rule.permissiveness?.score !== undefined ? ` · Score ${rule.permissiveness.score}` : ""}</Typography>}
      {name === "Shadowed" && value === "UNKNOWN" && <Typography variant="caption" display="block">UNKNOWN because {rule.hygiene?.shadowReason ?? "analysis not available"}</Typography>}
      {name === "Shadowed" && rule.hygiene?.coveringRuleId && openRuleId && <Button size="small" onClick={() => openRuleId(rule.hygiene!.coveringRuleId!)}>Covering rule</Button>}
      {name === "Violations" && rule.hygiene?.findings.map((f, i) => <Typography key={i} variant="caption" display="block">{f.findingClass === "unknown" ? "UNKNOWN because" : `${f.severity} · ${f.findingClass}:`} {f.evidence}</Typography>)}
    </Box></Tooltip>)}
  </Box>;
}
export function RuleFields({ rule, objects, openObject }: { rule: PolicyRule; objects: Map<string, PolicyObject>; openObject: (id: string) => void }) {
  const fields = [
    ["◎ SOURCE", <><CellEntries cell={rule.source} objects={objects} openObject={openObject} />{rule.extras["source-user"] && <Extra name="source-user" values={rule.extras["source-user"]} objects={objects} openObject={openObject} />}</>],
    ["⚑ DESTINATION", <><CellEntries cell={rule.destination} objects={objects} openObject={openObject} />{rule.extras.category && <Extra name="category" values={rule.extras.category} objects={objects} openObject={openObject} />}</>],
    ["⚙ SERVICE", <><CellEntries cell={rule.service} objects={objects} openObject={openObject} />{!!rule.application.refs.length && <><Typography variant="caption">Application</Typography><CellEntries cell={rule.application} objects={objects} openObject={openObject} /></>}</>],
    ["ACTION", <Tooltip title={rule.action}><Stack alignItems="center"><Typography aria-label={rule.action} sx={{ fontSize: 30, color: actionColor(rule.action) }}>{actionGlyph(rule.action)}</Typography><Typography variant="caption">{rule.action}</Typography></Stack></Tooltip>],
    ["☏ COMMENT", <Typography variant="caption" sx={{ whiteSpace: "pre-wrap" }}>{rule.comment || "—"}</Typography>],
    ["⊞ MISC", <><Typography variant="caption" display="block">UID on device: {rule.uuid || "—"}</Typography><Typography variant="caption" display="block">Log/track: {rule.log || "—"}</Typography>
      {Object.entries(rule.extras).filter(([key]) => !["source-user", "category", "layer-name"].includes(key)).map(([key, values]) => <Extra key={key} name={key} values={values} objects={objects} openObject={openObject} />)}
      {(rule.schedules ?? []).map((s, i) => <Typography key={i} variant="caption" display="block">{scheduleLabel(s)}</Typography>)}</>],
  ];
  return <Box className="policy-rule-fields" sx={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))", gap: 1.5 }}>
    {fields.map(([name, content]) => <Box key={name as string} sx={{ minWidth: 0, overflowWrap: "anywhere" }}><Typography variant="caption" display="block" sx={{ fontWeight: 700 }}>{name}</Typography>{content}</Box>)}
  </Box>;
}

const allowAction = /^(accept|allow)$/i, denyAction = /^(deny|drop|reject|reset-client|reset-server|reset-both)$/i;
function actionColor(action: string) { return allowAction.test(action) ? m3.success : denyAction.test(action) ? m3.error : m3.onSurfaceVar; }
function actionGlyph(action: string) { return allowAction.test(action) ? "✓" : /reject|reset/i.test(action) ? "⊘" : /^(deny|drop)$/i.test(action) ? "✕" : "?"; }

/** Table geometry: rule rows have a fixed height so the window of rendered rows can be computed without measuring. */
export const RULE_ROW_HEIGHT = 40;
const SECTION_ROW_HEIGHT = 36, DETAIL_ESTIMATE = 520, OVERSCAN = 160, VIEWPORT_FALLBACK = 600, INLINE_VALUES = 2;
const hygieneOrder = ["conflict", "shadowed", "broad", "unused", "expired", "disabled", "unknown"];
const hygieneLabels: Record<string, string> = { conflict: "Conflict", shadowed: "Shadowed", broad: "Broad", unused: "Unused", expired: "Expired", disabled: "Disabled", unknown: "Unknown" };
const severityRank: Record<string, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 };
export interface HygieneBadge { key: string; label: string; severity: "HIGH" | "MEDIUM" | "LOW" | "UNKNOWN"; tip: string }
/** Badges come only from the stored hygiene findings and the rule's own enabled/schedule state; nothing is inferred. */
export function hygieneBadges(rule: PolicyRule): HygieneBadge[] {
  const byClass = new Map<string, HygieneBadge>();
  for (const finding of rule.hygiene?.findings ?? []) {
    const known = finding.findingClass === "unknown" ? "UNKNOWN" : finding.severity;
    const current = byClass.get(finding.findingClass);
    if (!current) byClass.set(finding.findingClass, { key: finding.findingClass, label: hygieneLabels[finding.findingClass] ?? finding.findingClass, severity: known, tip: finding.evidence });
    else byClass.set(finding.findingClass, { ...current, severity: (severityRank[known] ?? 0) > (severityRank[current.severity] ?? 0) ? known : current.severity, tip: `${current.tip}; ${finding.evidence}` });
  }
  if (rule.enabled === false && !byClass.has("disabled")) byClass.set("disabled", { key: "disabled", label: "Disabled", severity: "LOW", tip: "Rule is disabled." });
  if (rule.timeStatus === "expired" && !byClass.has("expired")) byClass.set("expired", { key: "expired", label: "Expired", severity: "LOW", tip: "Stored schedule has expired." });
  if (rule.expiring && rule.timeStatus !== "expired") {
    const current = byClass.get("expired");
    byClass.set("expired", { key: "expired", label: "Expiring", severity: current?.severity ?? "LOW", tip: current?.tip ?? "Stored schedule expires within 14 days." });
  }
  return [...byClass.values()].sort((a, b) => (hygieneOrder.indexOf(a.key) + 99) % 99 - (hygieneOrder.indexOf(b.key) + 99) % 99);
}
function Badge({ badge }: { badge: HygieneBadge }) {
  const [bg, fg] = badge.severity === "HIGH" ? [m3.errorContainer, m3.onErrorContainer] : badge.severity === "MEDIUM" ? [m3.warningContainer, m3.onWarningContainer]
    : badge.severity === "LOW" ? [m3.sc, m3.onSurface] : ["transparent", m3.neutralInk];
  return <Box component="span" title={`${badge.severity === "UNKNOWN" ? "UNKNOWN because" : `${badge.severity} ·`} ${badge.tip}`} data-hygiene={badge.key}
    sx={{ display: "inline-flex", alignItems: "center", height: 20, px: 0.75, borderRadius: "6px", fontSize: 11, fontWeight: 600, lineHeight: 1, flex: "none",
      bgcolor: bg, color: fg, border: `1px solid ${badge.severity === "UNKNOWN" ? m3.outline : "transparent"}` }}>{badge.label}</Box>;
}
const muted = { color: m3.onSurfaceVar, fontSize: 12 } as const;
/** First values of a cell inline; the remainder is counted and listed in full in the expanded details. */
function CompactCell({ cell, objects, openObject }: { cell: PolicyCell; objects: Map<string, PolicyObject>; openObject: (id: string) => void }) {
  if (!cell.refs.length) return <Box component="span" sx={muted}>—</Box>;
  const names = cell.refs.map(id => objects.get(id)?.name ?? "Unresolved object");
  const rest = cell.refs.length - INLINE_VALUES;
  return <Box title={`${cell.negated ? "NOT " : ""}${names.slice(0, 50).join(", ")}${names.length > 50 ? ", …" : ""}`} sx={{ display: "flex", alignItems: "center", gap: 0.5, minWidth: 0 }}>
    {cell.negated && <Box component="span" sx={{ flex: "none", px: 0.5, borderRadius: "4px", fontSize: 10, fontWeight: 700, bgcolor: m3.warningContainer, color: m3.onWarningContainer }}>NOT</Box>}
    <Box component="span" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
      {cell.refs.slice(0, INLINE_VALUES).map((id, index) => <span key={`${id}-${index}`}>{index > 0 && ", "}<Box component="button" type="button" onClick={() => openObject(id)}
        sx={{ border: 0, p: 0, m: 0, background: "none", font: "inherit", fontSize: 12, color: m3.primary, cursor: "pointer", "&:hover": { textDecoration: "underline" }, "&:focus-visible": { outline: `2px solid ${m3.primary}` } }}>{names[index]}</Box></span>)}
    </Box>
    {rest > 0 && <Box component="span" sx={{ ...muted, flex: "none" }}>+{rest} more</Box>}
  </Box>;
}
type Column = { key: string; label: string; width: number | string; cell: (rule: PolicyRule) => React.ReactNode };
function MeasuredRow({ id, onHeight, children }: { id: string; onHeight: (id: string, height: number) => void; children: React.ReactNode }) {
  const ref = useRef<HTMLTableRowElement>(null);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const report = () => { const height = Math.ceil(element.getBoundingClientRect().height); if (height > 0) onHeight(id, height); };
    report();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(report); observer.observe(element);
    return () => observer.disconnect();
  }, [id, onHeight]);
  return <TableRow ref={ref} data-policy-rule-detail>{children}</TableRow>;
}
/**
 * The rulebase as a compact table: one fixed-height row per rule, section groups as collapsible header rows, and an
 * inline detail panel per expanded row holding everything the rule carries. Only rows inside the scroll window render.
 */
export function PolicyRuleTable({ sections, collapsed, scrollTop, viewportHeight = 0, stickyTop = 0, toggle, objects, metadata, openObject, selected, onSelect, openRuleId, headerSelect }: {
  sections: PolicyPage["sections"]; collapsed: Set<string>; scrollTop: number; viewportHeight?: number; stickyTop?: number; toggle: (id: string) => void;
  objects: Map<string, PolicyObject>; metadata: PolicyMetadata; openObject: (id: string) => void;
  selected: Set<string>; onSelect: (id: string) => void; openRuleId?: (id: string) => void; headerSelect?: React.ReactNode;
}) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [heights, setHeights] = useState<Record<string, number>>({});
  const onHeight = useCallback((id: string, height: number) => setHeights(current => current[id] === height ? current : { ...current, [id]: height }), []);
  const toggleRule = (key: string) => setExpanded(current => { const next = new Set(current); next.has(key) ? next.delete(key) : next.add(key); return next; });
  const showApplication = metadata.vendor === "PAN" || sections.some(section => section.rules.some(rule => rule.application.refs.length > 0));
  const cell = (key: "source" | "destination" | "service" | "application") => (rule: PolicyRule) => <CompactCell cell={rule[key]} objects={objects} openObject={openObject} />;
  const columns: Column[] = [
    { key: "name", label: "Name", width: showApplication ? "14%" : "16%", cell: rule => <Box component="span" title={rule.name || undefined} sx={{ fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", display: "block" }}>{rule.name || rule.uuid.slice(0, 8) || "Unnamed rule"}</Box> },
    { key: "source", label: "Source", width: showApplication ? "13%" : "15%", cell: cell("source") },
    { key: "destination", label: "Destination", width: showApplication ? "12%" : "14%", cell: cell("destination") },
    { key: "service", label: "Service", width: showApplication ? "10%" : "12%", cell: cell("service") },
    ...(showApplication ? [{ key: "application", label: "Application", width: "8%", cell: cell("application") }] : []),
    { key: "action", label: "Action", width: "7%", cell: rule => <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, color: actionColor(rule.action), fontWeight: 600 }}>
      <Box component="span" aria-hidden sx={{ fontSize: 15 }}>{actionGlyph(rule.action)}</Box><Box component="span" sx={{ overflow: "hidden", textOverflow: "ellipsis" }}>{rule.action || "UNKNOWN"}</Box></Box> },
    { key: "hits", label: "Hits", width: showApplication ? "7%" : "8%", cell: rule => <Box component="span" title={rule.hitCounts ? `${rule.hitCounts.source} · Collected: ${rule.hitCounts.collectedAt}` : "Hit counts not collected"}
      sx={rule.hitCounts?.hits == null ? muted : { fontVariantNumeric: "tabular-nums" }}>{rule.hitCounts?.hits == null ? "UNKNOWN" : rule.hitCounts.hits.toLocaleString("en-US")}</Box> },
    { key: "lastHit", label: "Last hit", width: "7%", cell: rule => <Box component="span" title={rule.hitCounts?.lastHit ? `${formatTime(rule.hitCounts.lastHit)} · ${rule.hitCounts.lastHit}` : "Last hit unknown"}
      sx={rule.hitCounts?.lastHit ? undefined : muted}>{relativeAge(rule.hitCounts?.lastHit)}</Box> },
    { key: "permissiveness", label: "Permissiveness", width: "9%", cell: rule => {
      const level = rule.permissiveness?.level;
      const known = level === "High" || level === "Medium" || level === "Low";
      return <Box component="span" title={rule.permissiveness?.reasons.join("; ") || "requires analysis"} sx={known ? { fontWeight: 700, color: level === "High" ? m3.error : level === "Medium" ? m3.warningInk : m3.goodInk } : muted}>{known ? level : "UNKNOWN"}</Box>;
    } },
    { key: "hygiene", label: "Hygiene", width: "13%", cell: rule => {
      const badges = hygieneBadges(rule);
      return <Box title={badges.map(badge => badge.label).join(", ") || undefined} sx={{ display: "flex", gap: 0.5, alignItems: "center", overflow: "hidden" }}>
        {badges.map(badge => <Badge key={badge.key} badge={badge} />)}
        {!rule.hygiene && <Box component="span" title="Hygiene analysis is not available for this rule" sx={muted}>{badges.length ? "· analysis UNKNOWN" : "UNKNOWN"}</Box>}
        {rule.hygiene && !badges.length && <Box component="span" sx={muted}>No findings</Box>}
      </Box>;
    } },
  ];
  const span = columns.length + 2;
  const viewport = viewportHeight > 0 ? viewportHeight : VIEWPORT_FALLBACK;
  const start = Math.max(0, scrollTop - OVERSCAN), end = scrollTop + viewport + OVERSCAN;
  let offset = 0, top = 0, bottom = 0;
  const rows: React.ReactNode[] = [];
  const append = (render: () => React.ReactNode, size: number) => {
    if (offset + size <= start) top += size;
    else if (offset >= end) bottom += size;
    else rows.push(render());
    offset += size;
  };
  const keyDown = (event: React.KeyboardEvent<HTMLTableRowElement>, key: string) => {
    if (event.target !== event.currentTarget) return;
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); toggleRule(key); return; }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    const all = [...(event.currentTarget.closest("tbody")?.querySelectorAll<HTMLElement>("[data-policy-rule]") ?? [])];
    const next = all[all.indexOf(event.currentTarget) + (event.key === "ArrowDown" ? 1 : -1)];
    if (next) { event.preventDefault(); next.focus(); }
  };
  const emptySections = sections.filter(section => section.total === 0).length;
  if (emptySections) append(() => <TableRow key="empty-sections" sx={{ height: SECTION_ROW_HEIGHT }}><TableCell colSpan={span} sx={{ py: 0, color: m3.onSurfaceVar, fontSize: 12 }}>{emptySections} empty sections</TableCell></TableRow>, SECTION_ROW_HEIGHT);
  for (const section of sections.filter(section => section.total !== 0)) {
    append(() => <TableRow key={section.id} data-policy-section sx={{ height: SECTION_ROW_HEIGHT }}>
      <TableCell colSpan={span} sx={{ py: 0, bgcolor: m3.sc, borderBottom: `1px solid ${m3.outlineVar}` }}>
        <Stack direction="row" alignItems="center" gap={1}>
          <Button size="small" aria-expanded={!collapsed.has(section.id)} onClick={() => toggle(section.id)} sx={{ textTransform: "none", fontWeight: 700 }}>{collapsed.has(section.id) ? "▸" : "▾"} {sectionLabel(metadata, section)} · {section.total}</Button>
          {section.parentRuleId && <Typography variant="caption">Inline layer · conditional on parent rule</Typography>}
        </Stack>
      </TableCell>
    </TableRow>, SECTION_ROW_HEIGHT);
    if (collapsed.has(section.id)) continue;
    for (const rule of section.rules) {
      const key = `${section.id}:${rule.id}`, open = expanded.has(key), checked = selected.has(rule.id);
      append(() => <TableRow key={key} data-policy-rule hover tabIndex={0} aria-expanded={open} aria-label={`Rule ${rule.number}: ${rule.name || "Unnamed rule"}`}
        onClick={event => { if (!(event.target as HTMLElement).closest("button, a, input, label")) toggleRule(key); }} onKeyDown={event => keyDown(event, key)}
        sx={{ height: RULE_ROW_HEIGHT, cursor: "pointer", opacity: rule.enabled === false ? 0.6 : 1, bgcolor: checked ? m3.primaryContainer : open ? m3.scLow : undefined,
          "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: -2 }, "& > td": { py: 0, px: 1, fontSize: 12, whiteSpace: "nowrap", overflow: "hidden", borderBottom: open ? 0 : `1px solid ${m3.outlineVar}` }, "& > td:first-of-type": { px: 0.5 } }}>
        <TableCell padding="checkbox" sx={{ boxShadow: rule.timeStatus === "expired" ? `inset 3px 0 0 ${m3.error}` : undefined }}>
          <Checkbox size="small" checked={checked} inputProps={{ "aria-label": `Select rule ${rule.number}` }} onChange={() => onSelect(rule.id)} />
        </TableCell>
        <TableCell data-column="number" sx={{ fontVariantNumeric: "tabular-nums" }}><Box component="span" aria-hidden sx={{ color: m3.onSurfaceVar, mr: 0.5 }}>{open ? "▾" : "▸"}</Box>{rule.number || "—"}</TableCell>
        {columns.map(column => <TableCell key={column.key} data-column={column.key}>{column.cell(rule)}</TableCell>)}
      </TableRow>, RULE_ROW_HEIGHT);
      if (open) append(() => <MeasuredRow key={`${key}:detail`} id={key} onHeight={onHeight}>
        <TableCell colSpan={span} sx={{ p: 0, bgcolor: m3.scLow, borderBottom: `1px solid ${m3.outlineVar}` }}>
          <Box role="region" aria-label={`Rule ${rule.number} details`} sx={{ p: 2, borderLeft: `3px solid ${rule.timeStatus === "expired" ? m3.error : m3.primary}`, whiteSpace: "normal", overflowWrap: "anywhere" }}>
            <RuleDetailPanel inline rule={rule} section={section} metadata={metadata} objects={objects} openObject={openObject} openRuleId={openRuleId} />
          </Box>
        </TableCell>
      </MeasuredRow>, heights[key] ?? DETAIL_ESTIMATE);
    }
  }
  const spacer = (height: number, name: string) => <TableRow key={name} aria-hidden><TableCell colSpan={span} sx={{ height, p: 0, border: 0 }} /></TableRow>;
  return <Table stickyHeader size="small" aria-label="Policy rules" sx={{ tableLayout: "fixed", width: "100%" }}>
    <colgroup><col style={{ width: 44 }} /><col style={{ width: 60 }} />{columns.map(column => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
    <TableHead><TableRow sx={{ "& > th": { top: stickyTop, bgcolor: m3.scLow, color: m3.onSurfaceVar, fontSize: 11, fontWeight: 700, letterSpacing: "0.02em", py: 0.75, px: 1,
      whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", zIndex: 3, borderBottom: `1px solid ${m3.outlineVar}` }, "& > th:first-of-type": { px: 0.5, textOverflow: "clip" } }}>
      <TableCell padding="checkbox">{headerSelect}</TableCell><TableCell>#</TableCell>
      {columns.map(column => <TableCell key={column.key} title={column.label}>{column.label}</TableCell>)}
    </TableRow></TableHead>
    <TableBody>{top > 0 && spacer(top, "spacer-top")}{rows}{bottom > 0 && spacer(bottom, "spacer-bottom")}</TableBody>
  </Table>;
}
function valueText(value: unknown): string {
  if (value == null) return "—";
  if (Array.isArray(value)) return value.map(valueText).join(", ");
  if (typeof value === "object") {
    const object = value as Record<string, unknown>;
    if (typeof object.name === "string") return object.name;
    return Object.entries(object).map(([k, v]) => `${k}: ${valueText(v)}`).join("; ");
  }
  return String(value);
}
export function RuleHistory({ policy, rule = "" }: { policy: string; rule?: string }) {
  const [revisions, setRevisions] = useState<PolicyRevision[]>([]), [page, setPage] = useState(0), [status, setStatus] = useState("Loading history…");
  const [errorCode, setErrorCode] = useState("");
  useEffect(() => {
    let active = true; setStatus("Loading history…"); setErrorCode("");
    getPolicyHistory(policy, rule, page).then(result => {
      if (!Array.isArray(result.revisions)) throw new Error("Invalid history response");
      if (active) { setRevisions(result.revisions); setStatus(result.revisions.length ? "" : "No recorded changes."); }
    }).catch(error => { if (active) { setErrorCode(reportPolicyLoadError("history", error)); setStatus("Rule history could not be loaded."); } });
    return () => { active = false; };
  }, [policy, rule, page]);
  return <Box><Typography variant="caption">History starts with stored collections. Incomplete collections and ambiguous repeated identities are excluded.</Typography>
    {status && <Typography role="status" data-error-code={errorCode || undefined}>{status}</Typography>}
    <Table size="small" aria-label="Rule revisions"><TableHead><TableRow>{["Revision", "Changed on", "Collected on", "Changed by"].map(s => <TableCell key={s}>{s}</TableCell>)}</TableRow></TableHead>
      <TableBody>{revisions.map(r => <TableRow key={r.revision}><TableCell><Box component="details"><Box component="summary" sx={{ cursor: "pointer" }}>#{r.revision} · {r.changeType}{r.identityFallback ? " · name fallback" : ""}</Box>
        <Box component="dl">{r.changes.map((change, i) => <Box key={i}><Typography component="dt" variant="caption" fontWeight={700}>{change.field === "number" ? "Moved position" : change.field}</Typography>
          <Typography component="dd" variant="caption" sx={{ m: 0, overflowWrap: "anywhere" }}>{valueText(change.before)} → {valueText(change.after)}</Typography></Box>)}</Box>
      </Box></TableCell><TableCell>{r.changedOn ?? "—"}</TableCell><TableCell>{r.collectedAt}</TableCell><TableCell>{r.changedBy ?? "—"}</TableCell></TableRow>)}</TableBody>
    </Table><Stack direction="row"><Button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous revisions</Button><Button disabled={revisions.length < 200} onClick={() => setPage(p => p + 1)}>Next revisions</Button></Stack>
  </Box>;
}
export function RuleDetailPanel({ rule, section, metadata, objects, openObject, openRuleId, inline = false }: { rule: PolicyRule; section: PolicySection; metadata: PolicyMetadata; objects: Map<string, PolicyObject>; openObject: (id: string) => void; openRuleId?: (id: string) => void; inline?: boolean }) {
  const [tab, setTab] = useState("Overview");
  return <Box><Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
      <Typography variant={inline ? "subtitle1" : "h6"} sx={{ fontWeight: 700 }}>{inline ? `#${rule.number || "—"} · ` : ""}{rule.name || "Unnamed rule"}</Typography>
      <VendorBadge vendor={metadata.vendor === "PAN" ? "palo_alto" : metadata.vendor === "CP" ? "check_point" : metadata.vendor} size={24} />
      {rule.identityFallback && <Chip size="small" color="warning" label="Name identity fallback" />}
      {rule.enabled === false && <Chip size="small" label="Disabled" />}
      {rule.timeStatus === "expired" && <Chip size="small" color="error" label="Expired" />}
      {rule.expiring && <Chip size="small" color="warning" label="Expiring within 14 days" />}
    </Stack>
    <Box sx={{ display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", sm: "130px minmax(0, 1fr)" }, gap: 2, mt: inline ? 1 : 2 }}>
      <Stack component="nav" aria-label="Rule detail navigation" alignItems="stretch">
        {["Overview", "Rule history", "Objects", "Shadowing", "Violations"].map(label => <Button key={label} aria-pressed={tab === label} onClick={() => setTab(label)} sx={{ justifyContent: "flex-start", textTransform: "none" }}>{label}</Button>)}
      </Stack><Box sx={{ minWidth: 0 }}>
        {tab === "Overview" && <><Typography variant="body2" sx={{ mb: 2 }}>{metadata.sourceName} · {containerLabel(metadata, section, rule)} · Section: {sectionLabel(metadata, section)} · Domain: {metadata.containerName} · Tags: {rule.extras.tag?.join(", ") || "—"}</Typography>
          <Box sx={{ display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "minmax(0, 1fr) 220px" }, gap: 2, alignItems: "start" }}>
            <RuleFields rule={rule} objects={objects} openObject={openObject} /><RuleMetrics rule={rule} openRuleId={openRuleId} />
          </Box>
          {rule.hitCounts && <Box sx={{ my: 2, overflowX: "auto" }}><Typography variant="body2">Hits: {rule.hitCounts.hits ?? "UNKNOWN"} · First hit: {rule.hitCounts.firstHit ?? "—"} · Source: {rule.hitCounts.source} · Collected: {rule.hitCounts.collectedAt}</Typography>
            {!!rule.hitCounts.firewalls.length && <Table size="small" aria-label="Per-firewall hit counts"><TableHead><TableRow>
              {["Firewall", "Context", "Hits", "First hit", "Last hit", "Created", "Modified", "Collected"].map(label => <TableCell key={label}>{label}</TableCell>)}
            </TableRow></TableHead><TableBody>{rule.hitCounts.firewalls.map(member => <TableRow key={`${member.deviceId}:${member.context}`}>
              <TableCell>{metadata.targets.find(target => target.deviceId === member.deviceId)?.name ?? "Unmatched firewall"}</TableCell><TableCell>{member.context}</TableCell>
              <TableCell>{member.hits ?? "UNKNOWN"}</TableCell><TableCell>{member.firstHit ?? "—"}</TableCell><TableCell>{member.lastHit ?? "—"}</TableCell>
              <TableCell>{member.createdAt ?? "—"}</TableCell><TableCell>{member.modifiedAt ?? "—"}</TableCell><TableCell>{member.collectedAt}</TableCell>
            </TableRow>)}</TableBody></Table>}
          </Box>}
          <Typography variant="caption" display="block" sx={{ mt: 1 }}>UUID: {rule.uuid || "—"}</Typography><Typography variant="caption" display="block">Enabled: {rule.enabled === null ? "UNKNOWN" : String(rule.enabled)}</Typography></>}
        {(tab === "Shadowing" || tab === "Violations") && <RuleMetrics rule={rule} openRuleId={openRuleId} />}
        {tab === "Rule history" && <RuleHistory policy={metadata.id} rule={rule.id} />}
        {tab === "Objects" && <Stack alignItems="flex-start">{[...new Set([rule.source, rule.destination, rule.service, rule.application].flatMap(c => c.refs).concat(rule.extras.time ?? [], rule.extras.schedule ?? []))].map(id =>
          <Button key={id} onClick={() => openObject(id)}>{objects.get(id)?.name ?? "Unresolved object"}</Button>)}</Stack>}
      </Box>
    </Box>
  </Box>;
}
