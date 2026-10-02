import { useEffect, useState } from "react";
import useMediaQuery from "@mui/material/useMediaQuery";
import { Box, Button, Checkbox, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from "@mui/material";
import { VendorBadge } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { getPolicyHistory, type PolicyCell, type PolicyMetadata, type PolicyObject, type PolicyPage, type PolicyRevision, type PolicyRule, type PolicySchedule, type PolicySection } from "../auth/adminApi";

export function reportPolicyLoadError(scope: "snapshots" | "rules" | "object" | "history", error: unknown) {
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
  return `${s.windows.map(w => `${w.months?.length ? `Months ${w.months.join(", ")} · ` : ""}${w.days.join(",") === "1,2,3,4,5" ? "Weekdays" : w.days.length ? w.days.map(d => dayNames[d]).join(", ") : w.monthDays.length ? `Days ${w.monthDays.join(", ")} of month` : "Daily"} ${w.start}–${w.end}`).join("; ")}${s.end ? ` · Until ${date(s.end)}` : ""} · ${zone}`;
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
function CellEntries({ cell, objects, openObject }: { cell: PolicyCell; objects: Map<string, PolicyObject>; openObject: (id: string) => void }) {
  const [expanded, setExpanded] = useState(false);
  return <Box>
    {cell.negated && <Chip size="small" label="NOT" color="warning" />}
    {!cell.refs.length && <Typography variant="caption">—</Typography>}
    {(expanded ? cell.refs : cell.refs.slice(0, 4)).map((id, index) => <Button key={`${id}-${index}`} size="small" onClick={() => openObject(id)}
      title={objects.get(id)?.name ?? "Unresolved object"} sx={{ display: "block", textAlign: "left", textTransform: "none", p: 0, minWidth: 0, maxWidth: "100%", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", fontSize: 12 }}>
      {objects.get(id)?.name ?? "Unresolved object"}</Button>)}
    {cell.refs.length > 4 && <Button size="small" aria-expanded={expanded} onClick={() => setExpanded(v => !v)} sx={{ p: 0 }}>
      {expanded ? "▴ Less" : `+${cell.refs.length - 4} ▾`}</Button>}
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
export function RuleMetrics({ rule }: { rule: PolicyRule }) {
  const status = rule.timeStatus ?? "unknown";
  const tiles = [
    ["Last hit", "—", "hit counts not collected yet"],
    ["Last modified", rule.extras["last-modified"]?.[0] ?? "—", "vendor modification time, when collected"],
    ["Time/Schedule status", status === "unknown" ? "—" : status, (rule.schedules ?? []).map(scheduleLabel).join("; ") || (status === "always" ? "Unbounded schedule" : "schedule not collected")],
    ["Permissiveness", rule.permissiveness?.level === "Unknown" ? "—" : rule.permissiveness?.level ?? "—", rule.permissiveness?.reasons.join("; ") || "requires analysis"],
    ["Shadowed", "—", "requires analysis"], ["Violations", "—", "requires analysis"],
  ];
  return <Box className="policy-rule-metrics" aria-label="Rule metrics" sx={{ width: 220, maxWidth: "100%", display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))", gap: 1 }}>
    {tiles.map(([name, value, tip]) => <Tooltip key={name} title={tip}><Box sx={{ borderBottom: `1px solid ${m3.outlineVar}`, pb: 1, minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary">{name}</Typography>
      <Typography variant="body2" sx={{ fontWeight: 700, overflowWrap: "anywhere", color: value === "High" || value === "expired" ? m3.error : value === "Medium" ? m3.warning : value === "Low" ? m3.success : undefined }}>{value}</Typography>
    </Box></Tooltip>)}
  </Box>;
}
export function RuleFields({ rule, objects, openObject, compact = false }: { rule: PolicyRule; objects: Map<string, PolicyObject>; openObject: (id: string) => void; compact?: boolean }) {
  const fields = [
    ["◎ SOURCE", <><CellEntries cell={rule.source} objects={objects} openObject={openObject} />{rule.extras["source-user"] && <Extra name="source-user" values={rule.extras["source-user"]} objects={objects} openObject={openObject} />}</>],
    ["⚑ DESTINATION", <><CellEntries cell={rule.destination} objects={objects} openObject={openObject} />{rule.extras.category && <Extra name="category" values={rule.extras.category} objects={objects} openObject={openObject} />}</>],
    ["⚙ SERVICE", <><CellEntries cell={rule.service} objects={objects} openObject={openObject} />{!!rule.application.refs.length && <><Typography variant="caption">Application</Typography><CellEntries cell={rule.application} objects={objects} openObject={openObject} /></>}</>],
    ["ACTION", <Tooltip title={rule.action}><Stack alignItems="center"><Typography aria-label={rule.action} sx={{ fontSize: 30, color: /^(accept|allow)$/i.test(rule.action) ? m3.success : /^(deny|drop|reject|reset-client|reset-server|reset-both)$/i.test(rule.action) ? m3.error : m3.onSurfaceVar }}>{/^(accept|allow)$/i.test(rule.action) ? "✓" : /reject|reset/i.test(rule.action) ? "⊘" : /^(deny|drop)$/i.test(rule.action) ? "✕" : "?"}</Typography><Typography variant="caption">{rule.action}</Typography></Stack></Tooltip>],
    ["☏ COMMENT", <Typography variant="caption" sx={{ whiteSpace: "pre-wrap" }}>{rule.comment || "—"}</Typography>],
    ["⊞ MISC", <><Typography variant="caption" display="block">UID on device: {rule.uuid || "—"}</Typography><Typography variant="caption" display="block">Log/track: {rule.log || "—"}</Typography>
      {Object.entries(rule.extras).filter(([key]) => !["source-user", "category", "layer-name"].includes(key)).map(([key, values]) => <Extra key={key} name={key} values={values} objects={objects} openObject={openObject} />)}
      {(rule.schedules ?? []).map((s, i) => <Typography key={i} variant="caption" display="block">{scheduleLabel(s)}</Typography>)}</>],
  ];
  return <Box className={compact ? "policy-rule-fields compact" : "policy-rule-fields"} sx={{ display: "grid", gridTemplateColumns: compact ? "repeat(3, minmax(90px, 1fr)) 60px minmax(90px, 1fr)" : "repeat(2, minmax(0, 1fr))", gap: 1.5,
    '@media (max-width: 1399px)': compact ? { gridTemplateColumns: "repeat(3, minmax(90px, 1fr)) 60px", '& > .policy-rule-misc': { gridColumn: "1 / -1" } } : {},
    '@media (max-width: 700px)': { gridTemplateColumns: "repeat(2, minmax(0, 1fr))" } }}>
    {(compact ? fields.slice(0, 4) : fields).map(([name, content]) => <Box key={name as string} sx={{ minWidth: 0, overflowWrap: "anywhere" }}><Typography variant="caption" display="block" sx={{ fontWeight: 700 }}>{name}</Typography>{content}</Box>)}
    {compact && <Box className="policy-rule-misc" sx={{ minWidth: 0, overflowWrap: "anywhere" }}>{fields.slice(4).map(([name, content]) =>
      <Box component="details" key={name as string}><Box component="summary" sx={{ cursor: "pointer", fontSize: 12, fontWeight: 700 }}>{name}</Box>{content}</Box>)}</Box>}
  </Box>;
}
export function VirtualPolicyCards({ sections, collapsed, scrollTop, toggle, objects, metadata, openObject, openRule, selected, onSelect }: {
  sections: PolicyPage["sections"]; collapsed: Set<string>; scrollTop: number; toggle: (id: string) => void;
  objects: Map<string, PolicyObject>; metadata: PolicyMetadata; openObject: (id: string) => void; openRule: (rule: PolicyRule) => void;
  selected: Set<string>; onSelect: (id: string) => void;
}) {
  const narrow = useMediaQuery("(max-width: 1399px)");
  const headerHeight = 44, cardHeight = narrow ? 430 : 280, start = Math.max(0, scrollTop - cardHeight * 2), end = start + 1800;
  let offset = 0, top = 0, bottom = 0;
  const rows: React.ReactNode[] = [];
  const append = (render: () => React.ReactNode, size: number) => {
    if (offset + size <= start) top += size;
    else if (offset >= end) bottom += size;
    else rows.push(render());
    offset += size;
  };
  const emptySections = sections.filter(section => section.total === 0).length;
  if (emptySections) append(() => <Typography key="empty-sections" variant="caption" sx={{ height: headerHeight, display: "flex", alignItems: "center", color: m3.onSurfaceVar }}>{emptySections} empty sections</Typography>, headerHeight);
  for (const section of sections.filter(section => section.total !== 0)) {
    append(() => <Stack key={section.id} direction="row" alignItems="center" sx={{ height: headerHeight }}>
      <Button size="small" aria-expanded={!collapsed.has(section.id)} onClick={() => toggle(section.id)}>{collapsed.has(section.id) ? "▸" : "▾"} {sectionLabel(metadata, section)} · {section.total}</Button>
      {section.parentRuleId && <Typography variant="caption">Inline layer · conditional on parent rule</Typography>}
    </Stack>, headerHeight);
    if (collapsed.has(section.id)) continue;
    for (const rule of section.rules) append(() => <Box component="article" key={`${section.id}:${rule.id}`} data-policy-rule aria-label={`Rule ${rule.number}: ${rule.name || "Unnamed rule"}`}
      sx={{ height: cardHeight, boxSizing: "border-box", py: 0.5 }}>
      <Box className={`policy-rule-card ${narrow ? "stacked" : "wide"}`} sx={{ height: "100%", boxSizing: "border-box", display: "grid", gridTemplateColumns: narrow ? "36px minmax(0, 1fr)" : "36px minmax(0, 1fr) 220px", gap: 1.5, p: 1.5, border: `1px solid ${m3.outlineVar}`, borderLeft: `3px solid ${rule.timeStatus === "expired" ? m3.error : m3.primary}`, borderRadius: 2, bgcolor: m3.scLowest, opacity: rule.enabled === false ? 0.5 : 1,
        '& > .policy-rule-metrics': { gridColumn: narrow ? "2" : undefined }, overflowY: "auto", overflowX: "hidden" }}>
        <Stack alignItems="center"><Checkbox size="small" checked={selected.has(rule.id)} inputProps={{ "aria-label": `Select rule ${rule.number}` }} onChange={() => onSelect(rule.id)} /><Typography>{rule.number || "—"}</Typography></Stack>
        <Box sx={{ minWidth: 0 }}><Stack direction="row" gap={1} alignItems="center" sx={{ pb: 0.5, borderBottom: `1px solid ${m3.outlineVar}`, flexWrap: "wrap", maxHeight: 62, overflow: "auto" }}>
          <Button size="small" onClick={() => openRule(rule)} sx={{ textTransform: "none", p: 0, fontWeight: 700 }}>{rule.name || rule.uuid.slice(0, 8) || "Unnamed rule"}</Button>
          <VendorBadge vendor={metadata.vendor === "PAN" ? "palo_alto" : metadata.vendor === "CP" ? "check_point" : metadata.vendor} />
          <Typography variant="caption">{metadata.sourceName}</Typography><Typography variant="caption">{containerLabel(metadata, section, rule)}</Typography><Typography variant="caption">Section: {sectionLabel(metadata, section)}</Typography>
          {rule.extras.tag && <Typography variant="caption">Tags: {rule.extras.tag.join(", ")}</Typography>}
          {rule.identityFallback && <Chip size="small" color="warning" label="Name identity fallback" />}
          {rule.enabled === false && <Chip size="small" label="Disabled" />}
          {rule.timeStatus === "expired" && <Chip size="small" color="error" label="Expired" />}
          {rule.expiring && <Chip size="small" color="warning" label="Expiring within 14 days" />}
        </Stack><Box sx={{ pt: 1, minWidth: 0 }}><RuleFields compact rule={rule} objects={objects} openObject={openObject} /></Box></Box>
        <RuleMetrics rule={rule} />
      </Box>
    </Box>, cardHeight);
  }
  return <>{top > 0 && <Box aria-hidden sx={{ height: top }} />}{rows}{bottom > 0 && <Box aria-hidden sx={{ height: bottom }} />}</>;
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
export function RuleDetailPanel({ rule, section, metadata, objects, openObject }: { rule: PolicyRule; section: PolicySection; metadata: PolicyMetadata; objects: Map<string, PolicyObject>; openObject: (id: string) => void }) {
  const [tab, setTab] = useState("Overview");
  return <Box><Typography variant="h6">{rule.name || "Unnamed rule"}</Typography>
    <Box sx={{ display: "grid", gridTemplateColumns: "130px minmax(0, 1fr)", gap: 2, mt: 2 }}>
      <Stack component="nav" aria-label="Rule detail navigation" alignItems="stretch">
        {["Overview", "Rule history", "Objects"].map(label => <Button key={label} aria-pressed={tab === label} onClick={() => setTab(label)} sx={{ justifyContent: "flex-start", textTransform: "none" }}>{label}</Button>)}
        {["Shadowing", "Violations"].map(label => <Tooltip key={label} title="coming later"><span><Button disabled>{label}</Button></span></Tooltip>)}
      </Stack><Box sx={{ minWidth: 0 }}>
        {tab === "Overview" && <><RuleMetrics rule={rule} /><Typography variant="body2" sx={{ my: 2 }}>{metadata.sourceName} · {containerLabel(metadata, section, rule)} · Domain: {metadata.containerName} · Tags: {rule.extras.tag?.join(", ") || "—"}</Typography>
          <Typography variant="caption" display="block">UUID: {rule.uuid || "—"}</Typography><Typography variant="caption" display="block">Enabled: {rule.enabled === null ? "UNKNOWN" : String(rule.enabled)}</Typography>
          <RuleFields rule={rule} objects={objects} openObject={openObject} /></>}
        {tab === "Rule history" && <RuleHistory policy={metadata.id} rule={rule.id} />}
        {tab === "Objects" && <Stack alignItems="flex-start">{[...new Set([rule.source, rule.destination, rule.service, rule.application].flatMap(c => c.refs).concat(rule.extras.time ?? [], rule.extras.schedule ?? []))].map(id =>
          <Button key={id} onClick={() => openObject(id)}>{objects.get(id)?.name ?? "Unresolved object"}</Button>)}</Stack>}
      </Box>
    </Box>
  </Box>;
}
