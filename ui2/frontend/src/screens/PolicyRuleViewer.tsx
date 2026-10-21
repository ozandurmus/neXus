import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Box, Button, Checkbox, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from "@mui/material";
import { relativeAge, formatTime, formatStamp } from "../shell/time";
import { VendorBadge } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { UNKNOWN_HATCH } from "../theme/tokens";
import { getPolicyHistory, type PolicyCell, type PolicyMetadata, type PolicyObject, type PolicyPage, type PolicyRevision, type PolicyRule, type PolicySchedule, type PolicySection } from "../auth/adminApi";
import { statusWord } from "../shell/presenters";
import { firewallLabels, firewallSerialTitle, withFirewallNames } from "./policyFirewalls";

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
/** Palo Alto sections other than plain security rules: the mapper's fixed names for NAT and overridden default rules. */
const PAN_KINDS: Record<string, { phase: "Pre" | "Post"; kind: string }> = {
  "Pre rules": { phase: "Pre", kind: "" }, "Post rules": { phase: "Post", kind: "" },
  "Pre NAT rules": { phase: "Pre", kind: "NAT" }, "Post NAT rules": { phase: "Post", kind: "NAT" },
  "Pre default rules": { phase: "Pre", kind: "default" }, "Post default rules": { phase: "Post", kind: "default" },
};
export function containerLabel(metadata: PolicyMetadata, section: PolicySection, rule: PolicyRule) {
  if (metadata.vendor === "CP") return `${section.parentRuleId ? "Inline layer" : "Access layer"}: ${rule.extras["layer-name"]?.[0] ?? section.name}`;
  if (metadata.vendor === "PAN") {
    if (section.name === "Local rules") return `Local firewall: ${section.source}`;
    const kind = PAN_KINDS[section.name] ?? { phase: "Pre", kind: "" };
    return `${section.source === "Shared" ? "Shared" : `Device group: ${section.source}`} (${kind.phase}${kind.kind ? ` ${kind.kind}` : ""})`;
  }
  return section.source;
}
export function sectionLabel(metadata: PolicyMetadata, section: PolicySection) {
  const kind = PAN_KINDS[section.name];
  if (metadata.vendor !== "PAN" || !kind) return section.name;
  return `${section.source === "Shared" ? "Shared" : "Device-group"} ${kind.phase.toLowerCase()}-${kind.kind ? `${kind.kind} ` : ""}rules`;
}

/* ---------------------------------------------------------------------------------------------------------------------
 * Words. Every presentation decision that a test can pin lives in a pure function: the action word, the usage words
 * (hits / last hit), the permissiveness words and the object kind. Nothing here infers evidence; UNKNOWN stays UNKNOWN.
 * ------------------------------------------------------------------------------------------------------------------ */
const allowAction = /^(accept|allow)$/i, denyAction = /^(deny|drop|reject|reset-client|reset-server|reset-both|block)$/i;
const natAction = /^(source-nat|destination-nat|source-and-destination-nat|no-nat)$/i;
export type ActionTone = "allow" | "deny" | "nat" | "other";
export function actionTone(action: string): ActionTone { return allowAction.test(action) ? "allow" : denyAction.test(action) ? "deny" : natAction.test(action) ? "nat" : "other"; }
/** The vendor's own action word, readable: `allow` → `Allow`, `reset-client` → `Reset client`, `source-nat` → `Source NAT`. */
export function actionWord(action: string) {
  if (!action) return "UNKNOWN";
  const spaced = action.replace(/-/g, " ").replace(/\bnat\b/i, "NAT");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}
function actionColor(action: string) { const tone = actionTone(action); return tone === "allow" ? m3.goodInk : tone === "deny" ? m3.error : m3.onSurfaceVar; }
function actionGlyph(action: string) { return allowAction.test(action) ? "✓" : /reject|reset/i.test(action) ? "⊘" : /^(deny|drop|block)$/i.test(action) ? "✕" : natAction.test(action) ? "⇄" : "?"; }

export type UsageState = "unknown" | "not-collected" | "never" | "partial" | "stale" | "recent";
export interface UsageWords { hits: string; lastHit: string; detail: string; state: UsageState }
/** The usage words of one rule. `hitCounts` missing is UNKNOWN; `null` is "Not collected"; 0 hits is "Never"; a count without a time is partial. */
export function usageWords(rule: Pick<PolicyRule, "hitCounts">, now: Date = new Date(), staleDays = 90): UsageWords {
  const counts = rule.hitCounts;
  if (counts === undefined) return { hits: "UNKNOWN", lastHit: "UNKNOWN", detail: "Hit evidence is missing from this snapshot.", state: "unknown" };
  if (counts === null) return { hits: "Not collected", lastHit: "Not collected", detail: "No hit counts are stored for this rule. A full refresh (incl. hit counts) collects them.", state: "not-collected" };
  const provenance = `${counts.source === "mds" ? "Management server counters" : "Firewall counters"} · collected ${formatStamp(counts.collectedAt)}`;
  if (counts.hits === 0 && !counts.lastHit) return { hits: "0", lastHit: "Never", detail: `Never matched within the counter window. ${provenance}`, state: "never" };
  const hits = counts.hits == null ? "UNKNOWN" : counts.hits.toLocaleString("en-US");
  if (!counts.lastHit) return { hits, lastHit: "UNKNOWN", detail: `${counts.hits == null ? "Hit count not reported by the source" : "Last-hit time not reported by the source"}. ${provenance}`, state: "partial" };
  const last = Date.parse(counts.lastHit);
  if (!Number.isFinite(last)) return { hits, lastHit: "UNKNOWN", detail: `Last-hit time unreadable (${counts.lastHit}). ${provenance}`, state: "partial" };
  const days = (now.getTime() - last) / 86_400_000;
  return { hits, lastHit: relativeAge(counts.lastHit, now), detail: `Last hit ${formatStamp(counts.lastHit)} · ${counts.lastHit}. ${provenance}`, state: days > staleDays ? "stale" : "recent" };
}

export type PermissivenessLevel = "High" | "Medium" | "Low" | "Unknown";
export interface PermissivenessWords { level: PermissivenessLevel; word: string; reasons: string[]; known: boolean }
/** Level word plus its specific reasons. Unknown always carries a reason; no level is ever a dash. */
export function permissivenessWords(rule: Pick<PolicyRule, "permissiveness">): PermissivenessWords {
  const p = rule.permissiveness;
  if (!p) return { level: "Unknown", word: "UNKNOWN", reasons: ["Not assessed in this snapshot"], known: false };
  const known = p.level === "High" || p.level === "Medium" || p.level === "Low";
  const reasons = p.reasons.length ? p.reasons : known ? ["No broad element found"] : ["Requires analysis"];
  return { level: known ? p.level : "Unknown", word: known ? p.level : "UNKNOWN", reasons, known };
}
function permissivenessColor(level: PermissivenessLevel) { return level === "High" ? m3.error : level === "Medium" ? m3.warningInk : level === "Low" ? m3.goodInk : m3.neutralInk; }

export type ObjectKind = "any" | "host" | "network" | "range" | "group" | "service" | "application" | "zone" | "user" | "time" | "other" | "unresolved";
export const OBJECT_KIND_WORDS: Record<ObjectKind, string> = { any: "Any", host: "Host", network: "Network", range: "Address range", group: "Group", service: "Service", application: "Application", zone: "Zone", user: "User", time: "Time object", other: "Object", unresolved: "Unresolved object" };
/** The chip kind of a stored object, from its vendor type and, for a plain address, the shape of its first value. */
export function objectKind(object: PolicyObject | undefined): ObjectKind {
  if (!object || object.type === "unresolved") return "unresolved";
  const type = object.type.toLowerCase();
  if (type === "any") return "any";
  if (type.includes("group")) return "group";
  if (type.startsWith("service") || /^(tcp|udp|icmp|port)/.test(type)) return "service";
  if (type.startsWith("application")) return "application";
  if (type.includes("zone")) return "zone";
  if (type === "user" || type.endsWith("-user") || type === "access-role") return "user";
  if (type === "time" || type === "schedule") return "time";
  if (type.endsWith("range") || type === "range") return "range";
  if (type === "network" || type === "subnet") return "network";
  if (["host", "simple-gateway", "dns-domain", "fqdn", "gateway"].includes(type)) return "host";
  if (type === "address" || type === "ip-address") {
    const value = object.values?.[0] ?? "";
    if (/\/\d{1,3}$/.test(value) && !/\/(32|128)$/.test(value)) return "network";
    if (/^[0-9a-f.:]+-[0-9a-f.:]+$/i.test(value)) return "range";
    return "host";
  }
  return "other";
}
/** Rule-documentation fields a source may carry in `extras` (owner, ticket, expiry); nothing is inferred when absent. */
export function documentationEntries(extras: Record<string, string[]>) {
  return Object.entries(extras).filter(([key]) => /owner|ticket|expir|request|approver|business|custom-field/i.test(key));
}

/* ---------------------------------------------------------------------------------------------------------------------
 * Chips. Objects are named chips with a kind glyph; the resolved value sits in the hover title, the full object in the
 * drawer. The styling stays local so it folds into the shared pill/table components when those land.
 * ------------------------------------------------------------------------------------------------------------------ */
function ObjectGlyph({ kind }: { kind: ObjectKind }) {
  const common = { width: 11, height: 11, viewBox: "0 0 12 12", fill: "none", stroke: "currentColor", strokeWidth: 1.3, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true, style: { flex: "none" } };
  switch (kind) {
    case "any": return <svg {...common}><circle cx="6" cy="6" r="4.5" /><path d="M1.5 6h9M6 1.5c-2 2.5-2 6.5 0 9M6 1.5c2 2.5 2 6.5 0 9" /></svg>;
    case "host": return <svg {...common}><rect x="1.5" y="2" width="9" height="6" rx="1" /><path d="M4 10.5h4M6 8v2.5" /></svg>;
    case "network": return <svg {...common}><circle cx="6" cy="2.5" r="1.5" /><circle cx="2.5" cy="9.5" r="1.5" /><circle cx="9.5" cy="9.5" r="1.5" /><path d="M6 4v2M6 6L3.3 8.3M6 6l2.7 2.3" /></svg>;
    case "range": return <svg {...common}><path d="M1.5 6h9M3.5 4L1.5 6l2 2M8.5 4l2 2-2 2" /></svg>;
    case "group": return <svg {...common}><rect x="1.5" y="3.5" width="6.5" height="6.5" rx="1" /><path d="M4 3.5V2.5a1 1 0 0 1 1-1h4.5a1 1 0 0 1 1 1V7a1 1 0 0 1-1 1H8" /></svg>;
    case "service": return <svg {...common}><rect x="2" y="5" width="8" height="5" rx="1" /><path d="M4.5 5V2M7.5 5V2" /></svg>;
    case "application": return <svg {...common}><rect x="1.5" y="1.5" width="9" height="9" rx="1.2" /><path d="M1.5 4.5h9" /></svg>;
    case "zone": return <svg {...common}><path d="M6 1.5l4 2v3c0 2.2-1.7 3.7-4 4.5-2.3-.8-4-2.3-4-4.5v-3z" /></svg>;
    case "user": return <svg {...common}><circle cx="6" cy="4" r="2.2" /><path d="M1.8 10.5c.6-2.1 2.3-3 4.2-3s3.6.9 4.2 3" /></svg>;
    case "time": return <svg {...common}><circle cx="6" cy="6" r="4.5" /><path d="M6 3.5V6l1.8 1.2" /></svg>;
    case "unresolved": return <svg {...common}><circle cx="6" cy="6" r="4.5" strokeDasharray="2 1.5" /><path d="M6 8.5v.01M4.8 4.8a1.3 1.3 0 1 1 1.9 1.2c-.5.3-.7.6-.7 1" /></svg>;
    default: return <svg {...common}><circle cx="6" cy="6" r="3.5" /></svg>;
  }
}
const chipBase = { display: "inline-flex", alignItems: "center", gap: "4px", height: 20, px: "6px", borderRadius: "6px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, color: m3.onSurface,
  font: "inherit", fontSize: 11.5, lineHeight: 1, maxWidth: "100%", minWidth: 0, whiteSpace: "nowrap", cursor: "pointer", textAlign: "left",
  "&:hover": { borderColor: m3.primary, color: m3.primary }, "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 1 } } as const;
const chipLabel = { overflow: "hidden", textOverflow: "ellipsis", minWidth: 0 } as const;
/**
 * A chip that may take two lines (post-release review 2026-10-20, N4): the name wraps inside the cell and is clamped
 * after the second line, so `GRP-DELTA-ENGINEERING-HOSTS` is read whole where the row has the room (one chip in the
 * cell) instead of being cut at eight characters. The full name stays on hover either way.
 */
const chipTwoLine = { height: "auto", minHeight: 20, py: "3px", whiteSpace: "normal", alignItems: "flex-start", lineHeight: 1.15 } as const;
const chipLabelTwoLine = { ...chipLabel, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflowWrap: "anywhere" } as const;
function objectTitle(object: PolicyObject | undefined, kind: ObjectKind) {
  const members = object?.members?.length ?? 0, values = object?.values ?? [];
  const resolved = values.length ? ` · ${values.slice(0, 8).join(", ")}${values.length > 8 ? ` +${values.length - 8} more` : ""}` : kind === "group" && !members ? " · members in the object view" : "";
  return `${OBJECT_KIND_WORDS[kind]}${members ? ` · ${members} members` : ""}${resolved}${object && object.status !== "RESOLVED" ? ` · ${statusWord(object.status)}` : ""} · Open object`;
}
export function ObjectChip({ id, objects, openObject, maxWidth, lines = 1 }: { id: string; objects: Map<string, PolicyObject>; openObject: (id: string) => void; maxWidth?: number; lines?: 1 | 2 }) {
  const object = objects.get(id), kind = objectKind(object);
  const members = object?.members?.length ?? 0;
  const name = object?.name ?? "Unresolved object";
  return <Box component="button" type="button" data-object-kind={kind} title={`${name} · ${objectTitle(object, kind)}`} onClick={() => openObject(id)}
    sx={{ ...chipBase, ...(lines === 2 ? chipTwoLine : {}), maxWidth: maxWidth ?? "100%", ...(kind === "any" ? { borderStyle: "dashed", fontStyle: "italic", color: m3.onSurfaceVar } : {}), ...(kind === "unresolved" ? { borderStyle: "dashed", color: m3.neutralInk } : {}) }}>
    <Box component="span" sx={{ color: kind === "any" ? m3.onSurfaceVar : m3.primary, display: "inline-flex", ...(lines === 2 ? { mt: "1px" } : {}) }}><ObjectGlyph kind={kind} /></Box>
    <Box component="span" sx={lines === 2 ? chipLabelTwoLine : chipLabel}>{name}</Box>
    {members > 0 && <Box component="span" sx={{ color: m3.onSurfaceVar, fontVariantNumeric: "tabular-nums" }}>{members}</Box>}
  </Box>;
}
function NotTag() { return <Box component="span" title="Negated: everything except these objects" sx={{ flex: "none", px: 0.5, height: 18, display: "inline-flex", alignItems: "center", borderRadius: "4px", fontSize: 10, fontWeight: 700, bgcolor: m3.warningContainer, color: m3.onWarningContainer }}>NOT</Box>; }
const muted = { color: m3.onSurfaceVar, fontSize: 12 } as const;
const INLINE_CHIPS = 2;
/**
 * The row form of a cell: two lines, one chip each, the second line ending in `+N` (which opens the rule's details where
 * every member is listed). Fixed lines keep `+N` visible whatever the names' length; chips shrink with an ellipsis.
 */
function CompactCell({ cell, objects, openObject, onMore }: { cell: PolicyCell; objects: Map<string, PolicyObject>; openObject: (id: string) => void; onMore?: () => void }) {
  if (!cell.refs.length) return <Box component="span" sx={muted} title="No objects in this field">—</Box>;
  const names = cell.refs.map(id => objects.get(id)?.name ?? "Unresolved object");
  const rest = cell.refs.length - INLINE_CHIPS;
  const line = { display: "flex", alignItems: "center", gap: "4px", minWidth: 0 } as const;
  // One chip in the cell: it may use the row's second line for its name (N4); two chips: one line each, `+N` on the second.
  const lines = cell.refs.length === 1 ? 2 : 1;
  return <Box title={`${cell.negated ? "NOT " : ""}${names.slice(0, 50).join(", ")}${names.length > 50 ? ", …" : ""}`} sx={{ display: "grid", gap: "3px", minWidth: 0 }}>
    <Box sx={{ ...line, alignItems: lines === 2 ? "flex-start" : "center" }}>{cell.negated && <NotTag />}<ObjectChip id={cell.refs[0]} objects={objects} openObject={openObject} lines={lines} /></Box>
    {cell.refs.length > 1 && <Box sx={line}>
      <ObjectChip id={cell.refs[1]} objects={objects} openObject={openObject} />
      {rest > 0 && <Box component="button" type="button" aria-label={`${rest} more objects`} title={`${rest} more · open the rule details`} onClick={onMore}
        sx={{ ...chipBase, flex: "none", border: `1px solid transparent`, bgcolor: m3.sc, color: m3.onSurfaceVar, fontWeight: 600, px: "5px" }}>+{rest}</Box>}
    </Box>}
  </Box>;
}
/** The comfortable form: every chip, expanding past the first twelve. */
export function CellEntries({ cell, objects, openObject, limit = 12 }: { cell: PolicyCell; objects: Map<string, PolicyObject>; openObject: (id: string) => void; limit?: number }) {
  const [expanded, setExpanded] = useState(false);
  return <Box sx={{ display: "flex", flexWrap: "wrap", gap: "4px", alignItems: "center" }}>
    {cell.negated && <NotTag />}
    {!cell.refs.length && <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>—</Typography>}
    {(expanded ? cell.refs : cell.refs.slice(0, limit)).map((id, index) => <ObjectChip key={`${id}-${index}`} id={id} objects={objects} openObject={openObject} maxWidth={240} />)}
    {cell.refs.length > limit && <Button size="small" aria-expanded={expanded} onClick={() => setExpanded(v => !v)} sx={{ p: 0, minWidth: 0, fontSize: 12 }}>
      {expanded ? "▴ Less" : `+${cell.refs.length - limit} ▾`}</Button>}
  </Box>;
}
const fieldLabels: Record<string, string> = { "source-user": "Source user", category: "URL category", from: "From zone", to: "To zone", "rule-type": "Type", "install-on": "Install on", "target-selectors": "Targets", vpn: "VPN", "time": "Time", schedule: "Schedule", "tag": "Tags", "layer-name": "Layer", "last-modified": "Last modified", "last-modifier": "Changed by",
  "log-setting": "Log forwarding", "profile-setting/group": "Security profile group",
  "nat-type": "NAT type", "to-interface": "To interface", "source-translation-type": "Source translation", "translated-source": "Translated source",
  "source-translation-interface": "Translation interface", "source-translation-interface-ip": "Interface IP", "source-translation-interface-floating-ip": "Interface floating IP",
  "source-translation-bi-directional": "Bi-directional", "source-translation-fallback-type": "Fallback type", "translated-source-fallback": "Fallback translated source",
  "source-translation-fallback-interface": "Fallback interface", "source-translation-fallback-ip": "Fallback IP", "source-translation-fallback-floating-ip": "Fallback floating IP",
  "translated-destination": "Translated destination", "translated-destination-port": "Translated port", "destination-dns-rewrite": "DNS rewrite",
  "translated-destination-dynamic": "Dynamic translated destination", "translated-destination-dynamic-port": "Dynamic translated port",
  "destination-dynamic-distribution": "Distribution", "translated-service": "Translated service", "auto-generated": "Auto-generated" };
function Extra({ name, values, objects, openObject, title }: { name: string; values: string[]; objects: Map<string, PolicyObject>; openObject: (id: string) => void; title?: string }) {
  const [expanded, setExpanded] = useState(false);
  return <Box component="li" title={title} sx={{ fontSize: 12, overflowWrap: "anywhere", display: "flex", gap: 1, alignItems: "baseline", flexWrap: "wrap" }}>
    <Box component="span" sx={{ color: m3.onSurfaceVar, minWidth: 120 }}>{fieldLabels[name] ?? name}</Box>
    <Box component="span" sx={{ display: "inline-flex", flexWrap: "wrap", gap: "4px", alignItems: "center" }}>
      {(expanded ? values : values.slice(0, 6)).map((v, i) => objects.has(v)
        ? <ObjectChip key={`${v}-${i}`} id={v} objects={objects} openObject={openObject} maxWidth={240} />
        : <Box key={`${v}-${i}`} component="span">{i > 0 && !objects.has(values[i - 1]) ? ", " : ""}{v}</Box>)}
      {values.length > 6 && <Button size="small" aria-expanded={expanded} onClick={() => setExpanded(v => !v)} sx={{ p: 0, minWidth: 0, fontSize: 12 }}>{expanded ? "▴ Less" : `+${values.length - 6} ▾`}</Button>}
    </Box>
  </Box>;
}

/* ---------------------------------------------------------------------------------------------------------------------
 * Detail: the rule sentence (FROM → TO · SERVICES / APPLICATIONS ⇒ ACTION), then evidence cards.
 * ------------------------------------------------------------------------------------------------------------------ */
const cardSx = { border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 1.5, minWidth: 0, bgcolor: m3.scLowest } as const;
const cardTitle = { fontSize: 10.5, fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase", color: m3.onSurfaceVar, display: "block", mb: 0.75 } as const;
function ActionWord({ action, size = 14 }: { action: string; size?: number }) {
  const tone = actionTone(action);
  return <Box component="span" data-action-tone={tone} sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, color: actionColor(action), fontWeight: 700, fontSize: size, whiteSpace: "nowrap" }}>
    <Box component="span" aria-hidden sx={{ fontSize: size + 2, lineHeight: 1 }}>{actionGlyph(action)}</Box>
    <Box component="span" sx={{ overflow: "hidden", textOverflow: "ellipsis" }}>{actionWord(action)}</Box>
  </Box>;
}
/** The rule as a sentence plus its comment and properties; also used by the shadow comparison. */
export function RuleFields({ rule, objects, openObject }: { rule: PolicyRule; objects: Map<string, PolicyObject>; openObject: (id: string) => void }) {
  const part = (label: string, content: React.ReactNode) => <Box sx={{ minWidth: 0 }}><Typography component="span" sx={cardTitle}>{label}</Typography>{content}</Box>;
  const documentation = documentationEntries(rule.extras);
  const hidden = new Set(["layer-name", ...documentation.map(([key]) => key)]);
  return <Box className="policy-rule-fields" sx={{ display: "grid", gap: 1.5 }}>
    <Box role="group" aria-label="Rule sentence" sx={{ ...cardSx, display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "minmax(0, 1fr) 20px minmax(0, 1fr) minmax(0, 1fr) auto" }, gap: 1.5, alignItems: "start" }}>
      {part("From", <><CellEntries cell={rule.source} objects={objects} openObject={openObject} />{rule.extras["source-user"] && <Box component="ul" sx={{ listStyle: "none", m: 0, mt: 0.5, p: 0 }}><Extra name="source-user" values={rule.extras["source-user"]} objects={objects} openObject={openObject} /></Box>}</>)}
      <Box aria-hidden sx={{ display: { xs: "none", md: "block" }, color: m3.onSurfaceVar, fontSize: 18, lineHeight: 1, pt: 2.5, textAlign: "center" }}>→</Box>
      {part("To", <><CellEntries cell={rule.destination} objects={objects} openObject={openObject} />{rule.extras.category && <Box component="ul" sx={{ listStyle: "none", m: 0, mt: 0.5, p: 0 }}><Extra name="category" values={rule.extras.category} objects={objects} openObject={openObject} /></Box>}</>)}
      {part(rule.application.refs.length ? "Services · Applications" : "Services", <><CellEntries cell={rule.service} objects={objects} openObject={openObject} />
        {!!rule.application.refs.length && <Box sx={{ mt: 0.75 }}><CellEntries cell={rule.application} objects={objects} openObject={openObject} /></Box>}</>)}
      {part("Action", <ActionWord action={rule.action} size={15} />)}
    </Box>
    <Box sx={{ display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "minmax(0, 1fr) minmax(0, 1fr)" }, gap: 1.5 }}>
      <Box sx={cardSx}><Typography component="span" sx={cardTitle}>Comment</Typography>
        <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", color: rule.comment ? m3.onSurface : m3.onSurfaceVar }}>{rule.comment || "No comment"}</Typography>
        <Typography component="span" sx={{ ...cardTitle, mt: 1.5 }}>Documentation</Typography>
        {documentation.length ? <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 0.5 }}>{documentation.map(([key, values]) => <Extra key={key} name={key} values={values} objects={objects} openObject={openObject} />)}</Box>
          : <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Owner, ticket and expiry are not recorded by this source in the stored snapshot.</Typography>}
      </Box>
      <Box sx={cardSx}><Typography component="span" sx={cardTitle}>Properties</Typography>
        <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 0.5 }}>
          <Box component="li" sx={{ fontSize: 12, display: "flex", gap: 1 }}><Box component="span" sx={{ color: m3.onSurfaceVar, minWidth: 120 }}>UID on device</Box><Box component="span" sx={{ overflowWrap: "anywhere" }}>{rule.uuid || "—"}</Box></Box>
          <Box component="li" sx={{ fontSize: 12, display: "flex", gap: 1 }}><Box component="span" sx={{ color: m3.onSurfaceVar, minWidth: 120 }}>Log/track</Box><Box component="span">{rule.log || "—"}</Box></Box>
          {Object.entries(rule.extras).filter(([key]) => !["source-user", "category"].includes(key) && !hidden.has(key)).map(([key, values]) => key === "target-selectors"
            ? <Extra key={key} name={key} values={firewallLabels(values, rule.targetDevices)} title={firewallSerialTitle(rule.targetDevices)} objects={objects} openObject={openObject} />
            : <Extra key={key} name={key} values={values} objects={objects} openObject={openObject} />)}
          {(rule.schedules ?? []).map((s, i) => <Box component="li" key={i} sx={{ fontSize: 12, display: "flex", gap: 1 }}><Box component="span" sx={{ color: m3.onSurfaceVar, minWidth: 120 }}>Schedule</Box><Box component="span">{scheduleLabel(s)}</Box></Box>)}
        </Box>
      </Box>
    </Box>
  </Box>;
}
function ReasonChips({ reasons, tone }: { reasons: string[]; tone: "neutral" | "warn" | "bad" }) {
  const [bg, fg] = tone === "bad" ? [m3.errorContainer, m3.onErrorContainer] : tone === "warn" ? [m3.warningContainer, m3.onWarningContainer] : [m3.sc, m3.onSurfaceVar];
  return <Box component="ul" aria-label="Reasons" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexWrap: "wrap", gap: "4px" }}>
    {reasons.map((reason, i) => <Box component="li" key={i} sx={{ display: "inline-flex", alignItems: "center", height: 20, px: 0.75, borderRadius: "6px", fontSize: 11, fontWeight: 600, bgcolor: bg, color: fg }}>{reason}</Box>)}
  </Box>;
}
/** The evidence cards: usage, permissiveness, hygiene, schedule. Each UNKNOWN says why. */
export function RuleMetrics({ rule, openRuleId, openHygiene, now = new Date() }: { rule: PolicyRule; openRuleId?: (id: string) => void; openHygiene?: (findingClass: string) => void; now?: Date }) {
  const usage = usageWords(rule, now), permissive = permissivenessWords(rule);
  const status = rule.timeStatus ?? "unknown";
  const findings = rule.hygiene?.findings ?? [];
  const violations = findings.filter(f => f.findingClass !== "unknown");
  const big = { fontWeight: 700, fontSize: 15, lineHeight: 1.2, overflowWrap: "anywhere" } as const;
  const usageColor = usage.state === "stale" ? m3.warningInk : usage.state === "never" ? m3.neutralInk : usage.state === "recent" ? m3.onSurface : m3.neutralInk;
  return <Box className="policy-rule-metrics" aria-label="Rule metrics" sx={{ display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", sm: "repeat(2, minmax(0, 1fr))" }, gap: 1.5, alignContent: "start" }}>
    <Tooltip title={usage.detail}><Box sx={cardSx} data-usage-state={usage.state}>
      <Typography component="span" sx={cardTitle}>Usage</Typography>
      <Stack direction="row" gap={2} flexWrap="wrap">
        <Box><Typography variant="caption" color="text.secondary">Hits</Typography><Typography sx={{ ...big, fontVariantNumeric: "tabular-nums", color: usage.hits === "UNKNOWN" || usage.hits === "Not collected" ? m3.neutralInk : undefined }}>{usage.hits}</Typography></Box>
        <Box><Typography variant="caption" color="text.secondary">Last hit</Typography><Typography sx={{ ...big, color: usageColor }}>{usage.lastHit}</Typography></Box>
        <Box><Typography variant="caption" color="text.secondary">First hit</Typography><Typography sx={{ ...big, color: rule.hitCounts?.firstHit ? undefined : m3.neutralInk }}>{rule.hitCounts?.firstHit ? formatStamp(rule.hitCounts.firstHit) : usage.state === "not-collected" ? "Not collected" : "UNKNOWN"}</Typography></Box>
      </Stack>
      <Typography variant="caption" display="block" sx={{ mt: 0.75, color: m3.onSurfaceVar }}>{usage.detail}</Typography>
    </Box></Tooltip>
    <Tooltip title={permissive.reasons.join("; ")}><Box sx={cardSx} data-permissiveness={permissive.level}>
      <Typography component="span" sx={cardTitle}>Permissiveness</Typography>
      <Typography sx={{ ...big, color: permissivenessColor(permissive.level) }}>{permissive.word}</Typography>
      <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, my: 0.5 }}>{permissive.known ? "Why this level" : "UNKNOWN because"}{rule.permissiveness?.score !== undefined ? ` · score ${rule.permissiveness.score}` : ""}</Typography>
      <ReasonChips reasons={permissive.reasons} tone={permissive.level === "High" ? "bad" : permissive.level === "Medium" ? "warn" : "neutral"} />
    </Box></Tooltip>
    <Tooltip title={rule.hygiene?.shadowReason ?? "Stored-snapshot analysis"}><Box sx={cardSx}>
      <Typography component="span" sx={cardTitle}>Hygiene</Typography>
      <Stack direction="row" gap={2} flexWrap="wrap" alignItems="flex-start">
        <Box><Typography variant="caption" color="text.secondary">Shadowed</Typography><Typography sx={{ ...big, color: rule.hygiene?.shadowStatus === "CONFLICT" ? m3.error : rule.hygiene?.shadowStatus === "REDUNDANT" ? m3.warningInk : rule.hygiene?.shadowStatus === "NOT_SHADOWED" ? m3.goodInk : m3.neutralInk }}>{rule.hygiene?.shadowStatus ?? "UNKNOWN"}</Typography>
          {(!rule.hygiene || rule.hygiene.shadowStatus === "UNKNOWN") && <Typography variant="caption" display="block">UNKNOWN because {rule.hygiene?.shadowReason ?? "analysis not available"}</Typography>}
          {rule.hygiene?.coveringRuleId && openRuleId && <Button size="small" sx={{ p: 0, minWidth: 0 }} onClick={() => openRuleId(rule.hygiene!.coveringRuleId!)}>Covering rule</Button>}</Box>
        <Box><Typography variant="caption" color="text.secondary">Violations</Typography><Typography sx={{ ...big, color: rule.hygiene ? (violations.length ? m3.warningInk : m3.goodInk) : m3.neutralInk }}>{rule.hygiene ? String(violations.length) : "UNKNOWN"}</Typography></Box>
      </Stack>
      {findings.map((f, i) => <Typography key={i} variant="caption" display="block" sx={{ mt: 0.5 }}>{f.findingClass === "unknown" ? "UNKNOWN because" : `${f.severity} · ${f.findingClass}:`} {f.evidence}</Typography>)}
      {!rule.hygiene && <Typography variant="caption" display="block" sx={{ mt: 0.5 }}>Stored hygiene findings; UNKNOWN entries are evidence gaps</Typography>}
      {openHygiene && <Button size="small" sx={{ mt: 0.5, p: 0, minWidth: 0 }} onClick={() => openHygiene(violations[0]?.findingClass ?? "")}>Open in Hygiene</Button>}
    </Box></Tooltip>
    <Tooltip title={(rule.schedules ?? []).map(scheduleLabel).join("; ") || (status === "always" ? "Unbounded schedule" : "schedule not collected")}><Box sx={cardSx}>
      <Typography component="span" sx={cardTitle}>Time / schedule</Typography>
      <Typography sx={{ ...big, color: status === "expired" ? m3.error : status === "unknown" ? m3.neutralInk : undefined }}>{status === "unknown" ? "UNKNOWN" : status}{rule.expiring && status !== "expired" ? " · expiring" : ""}</Typography>
      <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, mt: 0.5 }}>{(rule.schedules ?? []).map(scheduleLabel).join("; ") || (status === "always" ? "Unbounded schedule" : "Schedule not collected")}</Typography>
      <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>Last modified: {rule.extras["last-modified"]?.[0] ?? "not recorded"}{rule.extras["last-modifier"]?.[0] ? ` · by ${rule.extras["last-modifier"][0]}` : ""}</Typography>
    </Box></Tooltip>
  </Box>;
}

/* ---------------------------------------------------------------------------------------------------------------------
 * Table geometry: rule rows have a fixed height so the window of rendered rows can be computed without measuring.
 * ------------------------------------------------------------------------------------------------------------------ */
export const RULE_ROW_HEIGHT = 56;
const SECTION_ROW_HEIGHT = 36, DETAIL_ESTIMATE = 560, OVERSCAN = 160, VIEWPORT_FALLBACK = 600;
const hygieneOrder = ["conflict", "shadowed", "broad", "unused", "expired", "disabled", "unknown"];
/** The `unknown` class is not a finding but the analysis saying it could not decide: the flag names its subject (N5). */
const hygieneLabels: Record<string, string> = { conflict: "Conflict", shadowed: "Shadowed", broad: "Broad", unused: "Unused", expired: "Expired", disabled: "Disabled", unknown: "Hygiene UNKNOWN" };
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
function Badge({ badge, onOpen }: { badge: HygieneBadge; onOpen?: (findingClass: string) => void }) {
  const [bg, fg] = badge.severity === "HIGH" ? [m3.errorContainer, m3.onErrorContainer] : badge.severity === "MEDIUM" ? [m3.warningContainer, m3.onWarningContainer]
    : badge.severity === "LOW" ? [m3.sc, m3.onSurface] : [m3.scLow, m3.neutralInk];
  const unknown = badge.severity === "UNKNOWN";
  const title = `${unknown ? "UNKNOWN because" : `${badge.severity} ·`} ${badge.tip}${onOpen ? " · Open in Hygiene" : ""}`;
  return <Box component={onOpen ? "button" : "span"} type={onOpen ? "button" : undefined} onClick={onOpen ? () => onOpen(badge.key) : undefined} title={title} data-hygiene={badge.key}
    sx={{ display: "inline-flex", alignItems: "center", height: 20, px: 0.75, borderRadius: "6px", fontSize: 11, fontWeight: 600, lineHeight: 1, flex: "none", font: "inherit",
      bgcolor: bg, color: fg, border: `1px solid ${unknown ? m3.outline : "transparent"}`, cursor: onOpen ? "pointer" : undefined,
      // The product's UNKNOWN hatch (design language, status pill): a flag that makes no claim is not drawn like a finding.
      ...(unknown ? { backgroundImage: UNKNOWN_HATCH } : {}),
      "&:hover": onOpen ? { borderColor: m3.primary } : undefined, "&:focus-visible": { outline: `2px solid ${m3.primary}` } }}>{badge.label}</Box>;
}
function StateTag({ label, title }: { label: string; title?: string }) {
  return <Box component="span" title={title} sx={{ display: "inline-flex", alignItems: "center", height: 16, px: 0.5, borderRadius: "4px", fontSize: 10, fontWeight: 700, letterSpacing: "0.04em", textTransform: "uppercase", bgcolor: m3.scHigh, color: m3.onSurfaceVar, flex: "none" }}>{label}</Box>;
}
type Column = { key: string; label: string; /** The header word when the full label cannot fit the column; the label stays the accessible name. */ short?: string; width: number | string; cell: (rule: PolicyRule, api: { open: () => void }) => React.ReactNode };
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
const line2 = { fontSize: 11, color: m3.onSurfaceVar, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", display: "block" } as const;
/** A second line that may wrap once more (a reason sentence, N4): two lines, clamped, never cut mid-sentence on one line. */
const line2Wrap = { ...line2, whiteSpace: "normal", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", lineHeight: 1.2 } as const;
/** The token the masked view (AIView) writes in place of a value it withholds (`PolicyPrivacy`); never a real comment. */
const WITHHELD = "Withheld in AIView";
/**
 * The rulebase as a table of rules: one fixed-height, two-line row per rule (number + name as the anchor, FROM → TO ·
 * SERVICES · ACTION as the reading order, objects as named chips), section groups as collapsible header rows, and an
 * inline detail panel per expanded row holding everything the rule carries. Only rows inside the scroll window render.
 */
export function PolicyRuleTable({ sections, collapsed, scrollTop, viewportHeight = 0, stickyTop = 0, toggle, objects, metadata, openObject, selected, onSelect, openRuleId, openHygiene, headerSelect, now = new Date() }: {
  sections: PolicyPage["sections"]; collapsed: Set<string>; scrollTop: number; viewportHeight?: number; stickyTop?: number; toggle: (id: string) => void;
  objects: Map<string, PolicyObject>; metadata: PolicyMetadata; openObject: (id: string) => void;
  selected: Set<string>; onSelect: (id: string) => void; openRuleId?: (id: string) => void; openHygiene?: (findingClass: string) => void; headerSelect?: React.ReactNode; now?: Date;
}) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [showEmpty, setShowEmpty] = useState(false);
  const [heights, setHeights] = useState<Record<string, number>>({});
  const onHeight = useCallback((id: string, height: number) => setHeights(current => current[id] === height ? current : { ...current, [id]: height }), []);
  const toggleRule = (key: string) => setExpanded(current => { const next = new Set(current); next.has(key) ? next.delete(key) : next.add(key); return next; });
  const showApplication = metadata.vendor === "PAN" || sections.some(section => section.rules.some(rule => rule.application.refs.length > 0));
  const cell = (key: "source" | "destination" | "service" | "application") => (rule: PolicyRule, api: { open: () => void }) => <CompactCell cell={rule[key]} objects={objects} openObject={openObject} onMore={api.open} />;
  // Widths at 1440 (N4): the object columns get the room, the action and the number give it up; a short header word where
  // the full one cannot fit (`Apps`, `Permissive`), the full word as the column's accessible name and on hover.
  const columns: Column[] = [
    { key: "name", label: "Rule", width: showApplication ? "12%" : "14%", cell: rule => <Box sx={{ minWidth: 0 }}>
      <Box component="span" title={rule.name || undefined} sx={{ fontWeight: 600, fontSize: 12.5, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", display: "block", textDecoration: rule.enabled === false ? "line-through" : undefined, textDecorationColor: m3.outline }}>{rule.name || rule.uuid.slice(0, 8) || "Unnamed rule"}</Box>
      <Box sx={{ ...line2, display: "flex", alignItems: "center", gap: 0.5 }}>
        {rule.enabled === false && <StateTag label="Disabled" title="Rule is disabled on the source" />}
        {rule.enabled === null && <StateTag label="Enabled UNKNOWN" title="The source did not report whether the rule is enabled" />}
        {rule.identityFallback && <StateTag label="Name identity" title="Identified by name only; the source did not keep a stable identifier" />}
        {rule.comment === WITHHELD
          ? <StateTag label="Comment withheld" title="The masked view (AIView) withholds rule comments; the comment exists on the source." />
          : <Box component="span" title={rule.comment || undefined} sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontStyle: rule.comment ? undefined : "italic" }}>{rule.comment || "No comment"}</Box>}
      </Box>
    </Box> },
    { key: "source", label: "Source", width: showApplication ? "14%" : "16%", cell: cell("source") },
    { key: "destination", label: "Destination", width: showApplication ? "14%" : "16%", cell: cell("destination") },
    { key: "service", label: "Service", width: showApplication ? "10%" : "12%", cell: cell("service") },
    ...(showApplication ? [{ key: "application", label: "Application", short: "Apps", width: "9%", cell: cell("application") } as Column] : []),
    { key: "action", label: "Action", width: "6%", cell: rule => <ActionWord action={rule.action} size={12.5} /> },
    { key: "usage", label: "Usage", width: showApplication ? "11%" : "12%", cell: rule => {
      const usage = usageWords(rule, now);
      const tone = usage.state === "stale" ? m3.warningInk : usage.state === "recent" ? m3.onSurface : m3.neutralInk;
      return <Box title={usage.detail} data-usage-state={usage.state} sx={{ minWidth: 0 }}>
        <Box component="span" data-field="hits" sx={{ display: "block", fontSize: 12.5, fontWeight: usage.state === "unknown" || usage.state === "not-collected" ? 600 : 500, fontVariantNumeric: "tabular-nums", color: usage.state === "unknown" || usage.state === "not-collected" ? m3.neutralInk : undefined, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {usage.hits}{usage.state === "never" || usage.state === "recent" || usage.state === "stale" || (usage.state === "partial" && usage.hits !== "UNKNOWN") ? " hits" : ""}</Box>
        <Box component="span" data-field="lastHit" sx={{ ...line2, color: tone, fontWeight: usage.state === "never" ? 600 : 400 }}>
          {usage.state === "recent" || usage.state === "stale" ? `last ${usage.lastHit}` : usage.state === "never" ? "Never hit" : usage.state === "partial" ? "last hit UNKNOWN" : usage.lastHit}</Box>
      </Box>;
    } },
    { key: "permissiveness", label: "Permissiveness", short: "Permissive", width: "12%", cell: rule => {
      const words = permissivenessWords(rule);
      return <Box title={`${words.word}: ${words.reasons.join("; ")}`} data-permissiveness={words.level} sx={{ minWidth: 0 }}>
        <Box component="span" sx={{ display: "block", fontWeight: 700, fontSize: 12.5, color: permissivenessColor(words.level) }}>{words.word}</Box>
        <Box component="span" sx={line2Wrap}>{words.reasons.join(" · ")}</Box>
      </Box>;
    } },
    { key: "hygiene", label: "Flags", width: "12%", cell: rule => {
      const badges = hygieneBadges(rule).filter(badge => badge.key !== "disabled" || rule.enabled !== false);
      return <Box title={badges.map(badge => badge.label).join(", ") || undefined} sx={{ display: "flex", flexWrap: "wrap", gap: "4px", alignItems: "center", alignContent: "flex-start", maxHeight: 44, overflow: "hidden" }}>
        {badges.map(badge => <Badge key={badge.key} badge={badge} onOpen={openHygiene} />)}
        {!rule.hygiene && <Box component="span" title="Hygiene analysis is not available for this rule" sx={{ ...muted, fontSize: 11, fontWeight: 600, color: m3.neutralInk, whiteSpace: "normal", lineHeight: 1.2 }}>{badges.length ? "analysis UNKNOWN" : "Hygiene UNKNOWN"}</Box>}
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
  const emptySections = sections.filter(section => section.total === 0);
  if (emptySections.length) append(() => <TableRow key="empty-sections" data-policy-empty-sections sx={{ height: SECTION_ROW_HEIGHT }}>
    <TableCell colSpan={span} sx={{ py: 0, bgcolor: m3.sc, borderBottom: `1px solid ${m3.outlineVar}` }}>
      <Stack direction="row" alignItems="center" gap={1}>
        <Button size="small" aria-expanded={showEmpty} onClick={() => setShowEmpty(v => !v)} sx={{ textTransform: "none", fontWeight: 600, color: m3.onSurfaceVar, fontSize: 12, gap: 0.75 }}>
          <Box component="span" aria-hidden sx={{ fontSize: 11 }}>{showEmpty ? "▾" : "▸"}</Box><Box component="span">{emptySections.length === 1 ? "1 empty section" : `${emptySections.length} empty sections`}</Box>
        </Button>
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{showEmpty ? "Sections with no rule in this policy" : "Show"}</Typography>
      </Stack>
    </TableCell>
  </TableRow>, SECTION_ROW_HEIGHT);
  if (showEmpty) for (const section of emptySections) append(() => <TableRow key={section.id} data-policy-section data-empty-section sx={{ height: SECTION_ROW_HEIGHT }}>
    <TableCell colSpan={span} sx={{ py: 0, pl: 4, color: m3.onSurfaceVar, fontSize: 12.5, borderBottom: `1px solid ${m3.outlineVar}` }}>{sectionLabel(metadata, section)} · 0 rules</TableCell>
  </TableRow>, SECTION_ROW_HEIGHT);
  for (const section of sections.filter(section => section.total !== 0)) {
    append(() => <TableRow key={section.id} data-policy-section sx={{ height: SECTION_ROW_HEIGHT }}>
      <TableCell colSpan={span} sx={{ py: 0, bgcolor: m3.sc, borderBottom: `1px solid ${m3.outlineVar}`, borderTop: `1px solid ${m3.outlineVar}` }}>
        <Stack direction="row" alignItems="center" gap={1}>
          <Button size="small" aria-expanded={!collapsed.has(section.id)} onClick={() => toggle(section.id)} sx={{ textTransform: "none", fontWeight: 700, color: m3.onSurface, fontSize: 12.5, gap: 0.75 }}>
            <Box component="span" aria-hidden sx={{ color: m3.onSurfaceVar, fontSize: 11 }}>{collapsed.has(section.id) ? "▸" : "▾"}</Box>{sectionLabel(metadata, section)}
            <Box component="span" sx={{ ...muted, fontWeight: 600, bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", px: 0.75, fontSize: 11, lineHeight: "16px" }}>{section.total}</Box>
          </Button>
          {section.parentRuleId && <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Inline layer · conditional on its parent rule</Typography>}
        </Stack>
      </TableCell>
    </TableRow>, SECTION_ROW_HEIGHT);
    if (collapsed.has(section.id)) continue;
    for (const rule of section.rules) {
      const key = `${section.id}:${rule.id}`, open = expanded.has(key), checked = selected.has(rule.id), disabled = rule.enabled === false;
      const api = { open: () => { if (!open) toggleRule(key); } };
      append(() => <TableRow key={key} data-policy-rule hover tabIndex={0} aria-expanded={open} aria-label={`Rule ${rule.number}: ${rule.name || "Unnamed rule"}`}
        onClick={event => { if (!(event.target as HTMLElement).closest("button, a, input, label")) toggleRule(key); }} onKeyDown={event => keyDown(event, key)}
        sx={{ height: RULE_ROW_HEIGHT, cursor: "pointer", bgcolor: checked ? m3.primaryContainer : open ? m3.scLow : undefined,
          "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: -2 },
          "& > td": { py: 0, px: 1, fontSize: 12, whiteSpace: "nowrap", overflow: "hidden", verticalAlign: "middle", borderBottom: open ? 0 : `1px solid ${m3.outlineVar}`, opacity: disabled ? 0.55 : 1 },
          "& > td:first-of-type": { px: 0.5, opacity: 1 } }}>
        <TableCell padding="checkbox" sx={{ boxShadow: rule.timeStatus === "expired" ? `inset 3px 0 0 ${m3.error}` : disabled ? `inset 3px 0 0 ${m3.outlineVar}` : undefined }}>
          <Checkbox size="small" checked={checked} inputProps={{ "aria-label": `Select rule ${rule.number}` }} onChange={() => onSelect(rule.id)} />
        </TableCell>
        <TableCell data-column="number" sx={{ fontVariantNumeric: "tabular-nums", color: m3.onSurfaceVar }}>
          <Box component="span" aria-hidden sx={{ color: m3.outline, mr: 0.5, fontSize: 10 }}>{open ? "▾" : "▸"}</Box>
          <Box component="span" sx={{ fontWeight: 700, color: m3.onSurface }}>{rule.number || "—"}</Box>
        </TableCell>
        {columns.map(column => <TableCell key={column.key} data-column={column.key} sx={column.key === "destination" ? { position: "relative", pl: "18px !important", "&::before": { content: '"→"', position: "absolute", left: 4, top: "50%", transform: "translateY(-50%)", color: m3.outline, fontSize: 12 } } : undefined}>{column.cell(rule, api)}</TableCell>)}
      </TableRow>, RULE_ROW_HEIGHT);
      if (open) append(() => <MeasuredRow key={`${key}:detail`} id={key} onHeight={onHeight}>
        <TableCell colSpan={span} sx={{ p: 0, bgcolor: m3.scLow, borderBottom: `1px solid ${m3.outlineVar}` }}>
          <Box role="region" aria-label={`Rule ${rule.number} details`} sx={{ p: 2, borderLeft: `3px solid ${rule.timeStatus === "expired" ? m3.error : m3.primary}`, whiteSpace: "normal", overflowWrap: "anywhere" }}>
            <RuleDetailPanel inline rule={rule} section={section} metadata={metadata} objects={objects} openObject={openObject} openRuleId={openRuleId} openHygiene={openHygiene} now={now} />
          </Box>
        </TableCell>
      </MeasuredRow>, heights[key] ?? DETAIL_ESTIMATE);
    }
  }
  const spacer = (height: number, name: string) => <TableRow key={name} aria-hidden><TableCell colSpan={span} sx={{ height, p: 0, border: 0 }} /></TableRow>;
  return <Table stickyHeader size="small" aria-label="Policy rules" sx={{ tableLayout: "fixed", width: "100%" }}>
    <colgroup><col style={{ width: 40 }} /><col style={{ width: 46 }} />{columns.map(column => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
    <TableHead><TableRow sx={{ "& > th": { top: stickyTop, bgcolor: m3.scLow, color: m3.onSurfaceVar, fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", py: 0.75, px: 1,
      whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", zIndex: 3, borderBottom: `1px solid ${m3.outlineVar}` }, "& > th:first-of-type": { px: 0.5, textOverflow: "clip" } }}>
      <TableCell padding="checkbox">{headerSelect}</TableCell><TableCell>#</TableCell>
      {columns.map(column => <TableCell key={column.key} title={column.label} aria-label={column.short ? column.label : undefined} sx={column.key === "destination" ? { pl: "18px !important" } : undefined}>{column.short ?? column.label}</TableCell>)}
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
          <Typography component="dd" variant="caption" sx={{ m: 0, overflowWrap: "anywhere" }}>{valueText(withFirewallNames(change.before, r.targetDevices))} → {valueText(withFirewallNames(change.after, r.targetDevices))}</Typography></Box>)}</Box>
      </Box></TableCell><TableCell>{r.changedOn ?? "—"}</TableCell><TableCell>{r.collectedAt}</TableCell><TableCell>{r.changedBy ?? "—"}</TableCell></TableRow>)}</TableBody>
    </Table><Stack direction="row"><Button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous revisions</Button><Button disabled={revisions.length < 200} onClick={() => setPage(p => p + 1)}>Next revisions</Button></Stack>
  </Box>;
}
export function RuleDetailPanel({ rule, section, metadata, objects, openObject, openRuleId, openHygiene, inline = false, now = new Date() }: { rule: PolicyRule; section: PolicySection; metadata: PolicyMetadata; objects: Map<string, PolicyObject>; openObject: (id: string) => void; openRuleId?: (id: string) => void; openHygiene?: (findingClass: string) => void; inline?: boolean; now?: Date }) {
  const [tab, setTab] = useState("Overview");
  const cells: [string, PolicyCell][] = [["Source", rule.source], ["Destination", rule.destination], ["Service", rule.service], ["Application", rule.application]];
  const extraRefs = [...new Set((rule.extras.time ?? []).concat(rule.extras.schedule ?? []))];
  return <Box><Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
      <Typography variant={inline ? "subtitle1" : "h6"} sx={{ fontWeight: 700 }}><Box component="span" sx={{ color: m3.onSurfaceVar, fontWeight: 600, mr: 1 }}>#{rule.number || "—"}</Box>{rule.name || "Unnamed rule"}</Typography>
      <VendorBadge vendor={metadata.vendor === "PAN" ? "palo_alto" : metadata.vendor === "CP" ? "check_point" : metadata.vendor} size={24} />
      <ActionWord action={rule.action} size={13} />
      {rule.identityFallback && <Chip size="small" color="warning" label="Name identity fallback" />}
      {rule.enabled === false && <Chip size="small" label="Disabled" />}
      {rule.timeStatus === "expired" && <Chip size="small" color="error" label="Expired" />}
      {rule.expiring && <Chip size="small" color="warning" label="Expiring within 14 days" />}
    </Stack>
    <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, mt: 0.5 }}>{metadata.sourceName} · {containerLabel(metadata, section, rule)} · Section: {sectionLabel(metadata, section)} · Domain: {metadata.containerName} · Tags: {rule.extras.tag?.join(", ") || "—"}</Typography>
    <Box sx={{ display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", sm: "130px minmax(0, 1fr)" }, gap: 2, mt: inline ? 1.5 : 2 }}>
      <Stack component="nav" aria-label="Rule detail navigation" alignItems="stretch">
        {["Overview", "Rule history", "Objects", "Shadowing", "Violations"].map(label => <Button key={label} aria-pressed={tab === label} onClick={() => setTab(label)} sx={{ justifyContent: "flex-start", textTransform: "none", bgcolor: tab === label ? m3.scHigh : undefined }}>{label}</Button>)}
      </Stack><Box sx={{ minWidth: 0 }}>
        {tab === "Overview" && <Box sx={{ display: "grid", gap: 1.5 }}>
          <RuleFields rule={rule} objects={objects} openObject={openObject} />
          <RuleMetrics rule={rule} openRuleId={openRuleId} openHygiene={openHygiene} now={now} />
          {!!rule.hitCounts?.firewalls.length && <Box sx={{ overflowX: "auto" }}><Typography variant="body2">Hits: {rule.hitCounts.hits ?? "UNKNOWN"} · First hit: {rule.hitCounts.firstHit ?? "—"} · Source: {rule.hitCounts.source} · Collected: {rule.hitCounts.collectedAt}</Typography>
            {<Table size="small" aria-label="Per-firewall hit counts"><TableHead><TableRow>
              {["Firewall", "Context", "Hits", "First hit", "Last hit", "Created", "Modified", "Collected"].map(label => <TableCell key={label}>{label}</TableCell>)}
            </TableRow></TableHead><TableBody>{rule.hitCounts.firewalls.map(member => <TableRow key={`${member.deviceId}:${member.context}`}>
              <TableCell>{metadata.targets.find(target => target.deviceId === member.deviceId)?.name ?? "Unmatched firewall"}</TableCell><TableCell>{member.context}</TableCell>
              <TableCell>{member.hits ?? "UNKNOWN"}</TableCell><TableCell>{member.firstHit ?? "—"}</TableCell><TableCell>{member.lastHit ?? "—"}</TableCell>
              <TableCell>{member.createdAt ?? "—"}</TableCell><TableCell>{member.modifiedAt ?? "—"}</TableCell><TableCell>{member.collectedAt}</TableCell>
            </TableRow>)}</TableBody></Table>}
          </Box>}
          <Box><Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>UUID: {rule.uuid || "—"}</Typography><Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>Enabled: {rule.enabled === null ? "UNKNOWN" : String(rule.enabled)}</Typography></Box>
        </Box>}
        {(tab === "Shadowing" || tab === "Violations") && <RuleMetrics rule={rule} openRuleId={openRuleId} openHygiene={openHygiene} now={now} />}
        {tab === "Rule history" && <RuleHistory policy={metadata.id} rule={rule.id} />}
        {tab === "Objects" && <Box sx={{ display: "grid", gap: 1.5 }}>
          {cells.filter(([, cell]) => cell.refs.length).map(([label, cell]) => <Box key={label}><Typography component="span" sx={cardTitle}>{label}{cell.negated ? " · negated" : ""}</Typography>
            <Stack alignItems="flex-start" gap={0.5}>{[...new Set(cell.refs)].map(id => <ObjectChip key={id} id={id} objects={objects} openObject={openObject} maxWidth={360} />)}</Stack></Box>)}
          {!!extraRefs.length && <Box><Typography component="span" sx={cardTitle}>Time objects</Typography><Stack alignItems="flex-start" gap={0.5}>{extraRefs.map(id => <ObjectChip key={id} id={id} objects={objects} openObject={openObject} maxWidth={360} />)}</Stack></Box>}
        </Box>}
      </Box>
    </Box>
  </Box>;
}
