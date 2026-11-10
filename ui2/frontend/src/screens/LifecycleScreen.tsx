import { Fragment, useEffect, useState } from "react";
import { Alert, Box, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { getLifecycle, getDeviceLifecycle, type LifecycleDevice, type LifecycleLicense } from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button, M3Tabs } from "../shell/M3Widgets";
import { downloadText, toCsv } from "./DeviceShared";
import { LifecycleCatalogPanel, lifecycleError } from "./LifecycleCatalogPanel";
import { LifecycleVulnerabilitiesPanel, VulnerabilityCell, VulnerabilityDetail, severityWord, advisorySourceName, vulnerabilityRank, vulnerabilitySummary } from "./LifecycleVulnerabilitiesPanel";
import { m3 } from "../theme/m3Theme";

const cards = [["devices", "Devices"], ["past_end_of_support", "Past end of support"], ["within_180_days", "Within 180 days"], ["within_365_days", "Within 365 days"], ["no_lifecycle_data", "No lifecycle data"], ["licenses_within_60_days", "Licenses within 60 days"], ["license_problems", "License problems"], ["live_patch_not_active", "Live Patch not active"], ["live_patch_missing", "No Live Patch package"]];
const headings = ["Device / cluster", "Vendor", "Model", "Version", "Vulnerabilities", "Hardware end of support", "Software end of support", "Next milestone", "Nearest license expiry", "Risk", "Match basis", "Licenses", "Live Patch"];
/** Positions in {@link lifecycleCells} that the table draws as more than their text. Vulnerabilities sits beside the version it judges. */
const COL = { device: 0, vulnerabilities: 4, hardware: 5, software: 6, basis: 10, licenses: 11, livePatch: 12 } as const;
const shown = (value: string | null | undefined) => value || "UNKNOWN";
/** Rows shown before "+N more" (post-release review 2026-10-18, 1.7): the page was 22,000 px as one table. */
export const LIFECYCLE_PAGE = 50;
/** Compact license state of the table: "Problem", else the nearest expiry, else Collected / Unknown / Not collected. */
export function licensesCell(d: LifecycleDevice): string {
  if (d.license_problem) return "Problem";
  if (d.nearest_license?.expiry) return d.nearest_license.expiry;
  return d.license_status === "COLLECTED" ? "Collected" : d.license_status === "UNKNOWN" ? "Unknown" : "Not collected";
}
/** "take N · A armed" for a Check Point device; an em dash for other vendors. The not-active part is {@link notActive}. */
export function livePatchCell(d: LifecycleDevice): string {
  const p = d.live_patch;
  if (!p) return "—";
  return `${livePatchBase(d)}${p.ready > 0 ? ` · ${p.ready} not active` : ""}`;
}
/** The package take and the armed count. "0 armed" is only said when the patch list was actually read. */
function livePatchBase(d: LifecycleDevice): string {
  const p = d.live_patch;
  if (!p) return "—";
  if (p.missing) return "No Live Patch package";
  if (p.read_outcome === "COLLECTED") return `take ${p.package_take ?? "UNKNOWN"} · ${p.armed} armed`;
  return p.package_take ? `take ${p.package_take} · patches not read` : "Not collected";
}
const notActive = (d: LifecycleDevice) => (d.live_patch?.ready ?? 0) > 0;
export function lifecycleCells(d: LifecycleDevice): string[] {
  return [shown(d.hostname) + (d.cluster_member_ref ? ` · ${d.cluster_member_ref}` : ""), d.vendor, shown(d.model), shown(d.software_version), vulnerabilitySummary(d.vulnerabilities),
    shown(d.hardware.catalog?.end_of_support), shown(d.software.catalog?.end_of_support),
    d.next_milestone ? `${d.next_milestone.name}: ${d.next_milestone.date} (${d.next_milestone.days_remaining} days)` : "UNKNOWN",
    d.nearest_license?.expiry ?? "UNKNOWN", d.risk,
    [d.hardware.basis ?? d.hardware.reason, d.software.basis ?? d.software.reason].filter(Boolean).join(" · "),
    licensesCell(d), livePatchCell(d)];
}
const CATEGORIES: [string, string][] = [["LICENSE", "Licenses"], ["SUBSCRIPTION", "Subscriptions"], ["SUPPORT_CONTRACT", "Support contracts"], ["SMART_LICENSING", "Smart Licensing"]];
/** One license row as text: label, member, expiry or Permanent or the state word, days remaining. */
export function licenseLine(l: LifecycleLicense): string {
  const when = l.status === "PERMANENT" ? "Permanent" : l.expiry ?? (l.label || l.status);
  const days = l.expiry && l.days_remaining != null ? (l.days_remaining < 0 ? `expired ${-l.days_remaining} days ago` : `${l.days_remaining} days remaining`) : null;
  const word = l.status === "PERMANENT" || l.status === "DATED" || l.status === when ? null : l.status;
  const earlier = l.earlier_entries ? `${l.earlier_entries} earlier ${l.earlier_entries === 1 ? "entry" : "entries"}` : null;
  return [l.name ?? "License", l.member, when, word, days, earlier].filter(Boolean).join(" · ");
}
/** A ready patch waits for its process: with none running there is nothing to patch; with one running it is not active yet. */
const patchStatus = (x: { status: string; idle?: boolean }) => x.status !== "ready" ? x.status : x.idle ? "installed, process not running" : "installed, not active";
const readsText = (d: LifecycleDevice) => (d.license_reads ?? []).map(r => `${r.kind}: ${r.outcome}`).join("; ");
/** The vulnerability columns of the export, after the support milestones and before the license columns: the state, the counts and what they were compared against. */
const vulnerabilityCsvHeadings = ["Vulnerability state", "Vulnerability reason", "Advisories affecting", "Affecting and exploited", "Affecting and critical or high",
  "Advisories not judged", "Advisories fixed", "Worst severity", "Advisory source", "Advisories compared", "Oldest advisory", "Newest advisory", "Advisory source age (days)"];
function vulnerabilityCsvCells(d: LifecycleDevice): (string | number | null | undefined)[] {
  const v = d.vulnerabilities;
  if (!v) return vulnerabilityCsvHeadings.map(() => undefined);
  // Counts are only numbers for an evaluated device: a device that was not judged exports blanks, never zeros.
  const n = (value: number | undefined) => (v.state === "EVALUATED" ? value ?? 0 : undefined);
  const b = v.compared;
  return [v.state, v.reason, n(v.affected), n(v.affected_exploited), n(v.affected_critical_high), n(v.not_evaluable), n(v.fixed),
    v.state === "EVALUATED" && v.worst_severity ? severityWord(v.worst_severity) : undefined,
    b ? advisorySourceName(b.source) : undefined, b?.advisories, b?.oldest, b?.newest, b?.age_days];
}
export function lifecycleCsv(devices: LifecycleDevice[]) {
  return toCsv([[...headings, "Hardware end of sale", "Hardware end of engineering", "Software end of sale", "Software end of engineering", ...vulnerabilityCsvHeadings, "License details", "License collection", "Support contract collection",
    "License problem", "License collection by kind", "Live Patch package take", "Live Patch armed", "Live Patch ready", "Live Patch jumbofix"],
    ...devices.map(d => [...lifecycleCells(d), d.hardware.catalog?.end_of_sale, d.hardware.catalog?.end_of_engineering,
      d.software.catalog?.end_of_sale, d.software.catalog?.end_of_engineering, ...vulnerabilityCsvCells(d),
      d.licenses.map(l => [l.name, l.kind, l.member, l.expiry ?? l.label, l.status].filter(Boolean).join(" · ")).join("; "),
      d.license_status, d.support_contract_status,
      d.license_problem ? "yes" : "no", readsText(d), d.live_patch?.package_take, d.live_patch?.armed, d.live_patch?.ready, d.live_patch?.jumbofix])]);
}

/** The expanded device: licenses by category, the read outcomes when collection is UNKNOWN, and the Live Patch list. */
export function LifecycleDetail({ d }: { d: LifecycleDevice }) {
  const groups = CATEGORIES.map(([key, title]) => [title, d.licenses.filter(l => (l.category ?? "LICENSE") === key)] as const).filter(([, rows]) => rows.length > 0);
  const p = d.live_patch;
  return <Stack spacing={1.5} aria-label={`Details of ${shown(d.hostname)}`} role="group">
    {groups.map(([title, rows]) => <Box key={title}>
      <Typography variant="subtitle2">{title}</Typography>
      {rows.map((l, i) => <Typography key={i} variant="caption" display="block">{licenseLine(l)}
        {l.evidence === "MANAGER_REPORTED" && <Typography component="span" variant="caption" color="text.secondary">{" · reported by manager"}</Typography>}</Typography>)}
    </Box>)}
    {d.license_problem && <Typography variant="caption" color="error">License problems: {(d.license_problems ?? []).map(x => `${x.label} (${x.status})`).join(", ")}</Typography>}
    {d.license_status === "NOT_COLLECTED" && <Typography variant="caption">License data not collected for this device</Typography>}
    {d.license_status === "UNKNOWN" && <Box><Typography variant="caption" display="block">License collection status UNKNOWN</Typography>
      {(d.license_reads ?? []).map((r, i) => <Typography key={i} variant="caption" display="block">{`Read ${r.kind}: ${r.outcome}${r.reason ? ` (${r.reason})` : ""}${r.observed_at ? ` · ${r.observed_at}` : ""}`}</Typography>)}</Box>}
    {d.support_contract_status === "NOT_COLLECTED" && <Typography variant="caption">Support contract not collected for this device</Typography>}
    {d.vulnerabilities && <VulnerabilityDetail v={d.vulnerabilities} />}
    {p && <Box>
      <Typography variant="subtitle2">Live Patch</Typography>
      <Typography variant="caption" display="block">{`Package take ${p.package_take ?? "UNKNOWN"} · Jumbo ${p.jumbo ?? "UNKNOWN"} · read ${p.read_outcome ?? "not collected"}${p.observed_at ? ` · ${p.observed_at}` : ""}`}</Typography>
      <Typography variant="caption" display="block">{p.missing ? "The patch list was read and is empty, and the device names no Live Patch package."
        : `${p.armed} armed · ${p.ready} installed, not active · ${p.idle ?? 0} installed, process not running · ${p.jumbofix} jumbofix · ${p.other} other`}</Typography>
      {p.items.length > 0 && <Table size="small" aria-label="Live Patch patches"><TableHead><TableRow>{["Patch", "Status", "Mode", "Processes", "Installed", "References"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead><TableBody>
        {p.items.map(x => <TableRow key={x.patch}><TableCell>{x.patch}</TableCell><TableCell sx={x.status === "ready" && !x.idle ? { color: "warning.main" } : undefined}>{patchStatus(x)}</TableCell>
          <TableCell>{x.mode ?? "—"}</TableCell><TableCell>{x.running ?? "?"} of {x.total ?? "?"}</TableCell><TableCell>{x.installed_at ?? "—"}</TableCell><TableCell>{x.refs.join(", ") || "—"}</TableCell></TableRow>)}
      </TableBody></Table>}
    </Box>}
  </Stack>;
}

/** The Vulnerability state filter: the label and the test of a device. */
const vulnerabilityFilters: Record<string, [string, (d: LifecycleDevice) => boolean]> = {
  all: ["Any vulnerability state", () => true],
  exploited: ["Exploited advisory", d => vulnerabilityRank(d.vulnerabilities) === 0],
  affected: ["Affected", d => vulnerabilityRank(d.vulnerabilities) < 3],
  not_evaluated: ["Not evaluated", d => d.vulnerabilities?.state === "NOT_EVALUATED" || d.vulnerabilities?.state === "VERSION_UNKNOWN"],
  not_covered: ["No advisory source", d => d.vulnerabilities?.state === "NOT_COVERED"],
};

/**
 * The four vulnerability counters, first on the page: what needs action soonest. A counter the backend did not send
 * reads UNKNOWN, never 0; a zero is neutral, never green, and is said with how many devices were evaluated; red is kept
 * for devices under an exploited advisory and for a source older than 30 days. Devices without an advisory source are
 * in none of the counters, so their number is written beside "not evaluated".
 */
function VulnerabilityCards({ summary, devices }: { summary: Record<string, number | null>; devices: LifecycleDevice[] }) {
  const figure = (key: string) => (typeof summary[key] === "number" ? summary[key] as number : null);
  const text = (key: string) => String(figure(key) ?? "UNKNOWN");
  const reported = devices.some(d => d.vulnerabilities);
  const evaluated = reported ? `of ${devices.filter(d => d.vulnerabilities?.state === "EVALUATED").length} evaluated` : undefined;
  const uncovered = devices.filter(d => d.vulnerabilities?.state === "NOT_COVERED").length;
  const age = figure("vuln_oldest_source_age_days");
  const cards: [key: string, label: string, value: string, color: string | undefined, note: string | undefined][] = [
    ["vuln_devices_exploited", "Devices with an exploited advisory", text("vuln_devices_exploited"), (figure("vuln_devices_exploited") ?? 0) > 0 ? m3.criticalInk : undefined, evaluated],
    ["vuln_devices_affected", "Devices affected by an advisory", text("vuln_devices_affected"), (figure("vuln_devices_affected") ?? 0) > 0 ? m3.warningInk : undefined, evaluated],
    ["vuln_devices_not_evaluated", "Devices not evaluated", text("vuln_devices_not_evaluated"), undefined, uncovered ? `${uncovered} more without an advisory source` : undefined],
    ["vuln_oldest_source_age_days", "Oldest advisory source load", age === null ? "UNKNOWN" : age <= 0 ? "today" : `${age} d ago`,
      age === null ? undefined : age > 30 ? m3.criticalInk : age > 7 ? m3.warningInk : undefined, undefined],
  ];
  return <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 1 }} role="group" aria-label="Vulnerability summary">
    {cards.map(([key, label, value, color, note]) => <Box key={key} data-vulnerability-card={key} sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2 }}>
      <Typography variant="h6" sx={{ color: value === "UNKNOWN" ? "text.secondary" : color }}>{value}</Typography>
      <Typography variant="caption" display="block">{label}</Typography>
      {note && <Typography variant="caption" display="block" color="text.secondary">{note}</Typography>}</Box>)}
  </Stack>;
}

export function LifecycleScreen() {
  const fleet = useFetchOnMount(getLifecycle, lifecycleError);
  const [search, setSearch] = useState("");
  const [vendor, setVendor] = useState("all");
  const [risk, setRisk] = useState("all");
  const [missing, setMissing] = useState("all");
  const [vulnerability, setVulnerability] = useState("all");
  const rows = fleet.data?.devices ?? [];
  // Devices affected by an exploited advisory come first, then the other affected devices; the rest keep the server's order.
  const filtered = rows.filter(d => (vendor === "all" || d.vendor === vendor) && (risk === "all" || d.risk === risk)
    && (missing === "all" || d.status === "NO_LIFECYCLE_DATA") && vulnerabilityFilters[vulnerability]?.[1](d) !== false
    && lifecycleCells(d).join(" ").toLowerCase().includes(search.toLowerCase()))
    .map((d, i) => [d, i] as const).sort((a, b) => vulnerabilityRank(a[0].vulnerabilities) - vulnerabilityRank(b[0].vulnerabilities) || a[1] - b[1]).map(([d]) => d);
  // Paged at 50 with "+N more" (1.7): every row stays reachable, the CSV export still takes the whole filtered set.
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (id: string) => setOpen(prev => { const next = new Set(prev); if (!next.delete(id)) next.add(id); return next; });
  const [limit, setLimit] = useState(LIFECYCLE_PAGE);
  useEffect(() => { setLimit(LIFECYCLE_PAGE); }, [search, vendor, risk, missing, vulnerability]);
  const visible = filtered.slice(0, limit);
  const remaining = filtered.length - visible.length;
  const panel = <Stack spacing={2}>
    {fleet.error && <Alert severity="error">{fleet.error}<M3Button emphasis="tonal" onClick={fleet.refresh}>Retry</M3Button></Alert>}
    {!fleet.data && !fleet.error && <Typography>Loading lifecycle…</Typography>}
    {fleet.data && <>
      <Typography variant="caption">Stored inventory · as of {fleet.data.as_of} (UTC). Each cluster member is a separate evidence entity. Support windows overlap; the 60-day count is devices with at least one license, subscription or contract expiry in the next 60 days (all vendors); license problems are devices with an expired, evaluation-expired, unregistered or out-of-compliance item.</Typography>
      <VulnerabilityCards summary={fleet.data.summary} devices={rows} />
      <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 1 }}>{cards.map(([key, label]) => <Box key={key} sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2 }}><Typography variant="h6">{fleet.data?.summary[key] ?? 0}</Typography><Typography variant="caption">{label}</Typography></Box>)}</Stack>
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
        <TextField label="Search lifecycle" size="small" value={search} onChange={e => setSearch(e.target.value)} />
        <TextField label="Vendor" select size="small" value={vendor} onChange={e => setVendor(e.target.value)}><MenuItem value="all">All vendors</MenuItem>{[...new Set(rows.map(d => d.vendor))].sort().map(v => <MenuItem key={v} value={v}>{v}</MenuItem>)}</TextField>
        <TextField label="Risk" select size="small" value={risk} onChange={e => setRisk(e.target.value)}>{["all", "EXPIRED", "HIGH", "MEDIUM", "LOW", "UNKNOWN"].map(r => <MenuItem key={r} value={r}>{r === "all" ? "All risks" : r}</MenuItem>)}</TextField>
        <TextField label="Lifecycle data" select size="small" value={missing} onChange={e => setMissing(e.target.value)}><MenuItem value="all">All devices</MenuItem><MenuItem value="missing">No lifecycle data</MenuItem></TextField>
        <TextField label="Vulnerability state" select size="small" value={vulnerability} sx={{ minWidth: 170 }} onChange={e => setVulnerability(e.target.value)}>{Object.entries(vulnerabilityFilters).map(([key, [label]]) => <MenuItem key={key} value={key}>{label}</MenuItem>)}</TextField>
        <M3Button emphasis="tonal" disabled={!filtered.length} onClick={() => downloadText("lifecycle.csv", lifecycleCsv(filtered))}>Export lifecycle CSV</M3Button>
        <M3Button emphasis="tonal" onClick={fleet.refresh}>Refresh</M3Button>
      </Stack>
      <Typography variant="caption">Risk uses the earliest known hardware/software end of support: EXPIRED before today, HIGH under 180 days, MEDIUM under 365 days. LOW requires both support dates; missing dates remain UNKNOWN. End of sale and engineering are separate milestones.</Typography>
      <Typography variant="caption">Vulnerabilities compares each device's software version with the vendor's security advisories. Affected: an advisory names this version (by version alone; exposure depends on configuration). Exploited: the advisory is listed in the CISA Known Exploited Vulnerabilities catalog. "None affected" is only said with what was compared; a device that was not evaluated is not known to be safe. Advisory sources turn amber after 7 days and red after 30, when their devices stop being evaluated.</Typography>
      <TableContainer><Table size="small" aria-label="Fleet lifecycle"><TableHead><TableRow>{headings.map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead><TableBody>
        {visible.map(d => <Fragment key={d.device_id}><TableRow>{lifecycleCells(d).map((cell, i) => <TableCell key={i}>
          {i === COL.licenses ? <Typography variant="body2" component="span" color={d.license_problem ? "error" : "text.primary"}>{cell}</Typography>
            : i === COL.vulnerabilities ? <VulnerabilityCell v={d.vulnerabilities} />
            : i === COL.livePatch ? (d.live_patch ? <>{d.live_patch.missing ? <Typography variant="body2" component="span" sx={{ color: "warning.main" }}>{livePatchBase(d)}</Typography> : livePatchBase(d)}{notActive(d) && <Typography variant="body2" component="span" sx={{ color: "warning.main" }}>{` · ${d.live_patch.ready} not active`}</Typography>}</> : cell)
            : i === COL.device ? <><a href={`?screen=inventory&device_id=${encodeURIComponent(d.device_id)}`}>{shown(d.hostname)}</a>{d.cluster_member_ref && <Typography variant="caption" display="block">{d.cluster_member_ref}</Typography>}
            <M3Button emphasis="text" aria-expanded={open.has(d.device_id)} onClick={() => toggle(d.device_id)}>{open.has(d.device_id) ? "Hide details" : "Details"}</M3Button></> : cell}
          {(i === COL.hardware || i === COL.software) && <Typography variant="caption" display="block">
            End of sale: {shown((i === COL.hardware ? d.hardware : d.software).catalog?.end_of_sale)}
            {" · "}End of engineering: {shown((i === COL.hardware ? d.hardware : d.software).catalog?.end_of_engineering)}
          </Typography>}
          {i === COL.basis && d.status === "NO_LIFECYCLE_DATA" && <Typography variant="caption" display="block">NO_LIFECYCLE_DATA</Typography>}
        </TableCell>)}</TableRow>
        {open.has(d.device_id) && <TableRow><TableCell colSpan={headings.length}><LifecycleDetail d={d} /></TableCell></TableRow>}</Fragment>)}
        {!filtered.length && <TableRow><TableCell colSpan={headings.length}>No devices match these filters</TableCell></TableRow>}
      </TableBody></Table></TableContainer>
      {filtered.length > 0 && <Stack direction="row" spacing={2} alignItems="center">
        <Typography variant="caption" data-lifecycle-page>{`1–${visible.length} of ${filtered.length}${remaining > 0 ? ` · +${remaining} more` : ""}`}</Typography>
        {remaining > 0 && <M3Button emphasis="tonal" onClick={() => setLimit(n => n + LIFECYCLE_PAGE)}>Show next {Math.min(LIFECYCLE_PAGE, remaining)}</M3Button>}
        {remaining > 0 && <M3Button emphasis="text" onClick={() => setLimit(filtered.length)}>Show all {filtered.length}</M3Button>}
      </Stack>}
    </>}
  </Stack>;
  return <Box sx={{ px: 3, pb: 3 }}><M3Tabs ariaLabel="Lifecycle view" tabs={[{ label: "Fleet", panel: panel }, { label: "Catalog", panel: <LifecycleCatalogPanel onChanged={fleet.refresh} /> }, { label: "Vulnerabilities", panel: <LifecycleVulnerabilitiesPanel /> }]} /></Box>;
}

/** The one lifecycle sentence of a device: its risk word, then the support dates and licence expiry that explain it. */
export function lifecycleSentence(data: LifecycleDevice | null | undefined, error: string | null): string {
  return `${error ? "Unavailable" : data?.risk ?? "UNKNOWN"}`
    + `${data?.status === "NO_LIFECYCLE_DATA" ? " (no lifecycle data imported)" : ""}`
    + `${data?.hardware?.catalog?.end_of_support ? ` · Hardware support: ${data.hardware.catalog.end_of_support}` : ""}`
    + `${data?.software?.catalog?.end_of_support ? ` · Software support: ${data.software.catalog.end_of_support}` : ""}`
    + `${data?.nearest_license?.expiry ? ` · License expiry: ${data.nearest_license.expiry}` : ""}`
    + `${error ? ` · ${error}` : ""}`;
}

/** The loose line, for a device whose screen has no Platform identity grid to hold the cell (no Configuration tab). */
export function DeviceLifecycleLine({ deviceId }: { deviceId: string }) {
  const data = useFetchOnMount(() => getDeviceLifecycle(deviceId), lifecycleError);
  return <Typography variant="body2" color="text.secondary">Lifecycle: {lifecycleSentence(data.data, data.error)}</Typography>;
}

/** The same sentence as a cell of the Platform identity grid (UI review 2026-10-18, F-9). */
export function LifecycleCell({ deviceId }: { deviceId: string }) {
  const data = useFetchOnMount(() => getDeviceLifecycle(deviceId), lifecycleError);
  return <>{data.data === null && data.error === null ? "Loading…" : lifecycleSentence(data.data, data.error)}</>;
}
