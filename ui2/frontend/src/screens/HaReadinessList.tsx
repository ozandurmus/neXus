import { JobButton, jobWindowUsable, useJobWindow } from "../shell/JobWindow";
import { ROLE_LOCK_LABEL } from "../shell/actionAccess";
import { DISABLED_STYLE } from "../shell/disabledStyle";
import { Presented } from "../shell/PresentedText";
import { clusterModeWord, jobOutcomeWord, readinessReasonLabel, statusWord } from "../shell/presenters";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import { Fragment, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import IconButton from "@mui/material/IconButton";
import MenuItem from "@mui/material/MenuItem";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import type { CpFailoverSummary, DeviceSummary } from "../auth/adminApi";
import { Icon, type IconName } from "../shell/Icon";
import { StatePanel, VendorBadge, vendorDisplayName } from "../shell/States";
import { formatStamp, relativeAge } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import { ReadinessChecksTable, readinessStopReason } from "./ReadinessChecksTable";

type Cluster = { ref: string; title: string; members: DeviceSummary[] };
type Status = "READY" | "NOT_READY" | "UNKNOWN" | "UNSUPPORTED";
export type ReadinessProgress = Record<string, "Queued" | "Running" | "Completed" | "Failed" | "Not run">;
const statuses: Record<Status, { label: string; icon: IconName; bg: string; ink: string; order: number }> = {
  READY: { label: "Ready", icon: "check-circle", bg: m3.successContainer, ink: m3.onSuccessContainer, order: 2 },
  NOT_READY: { label: "Not ready", icon: "x-circle", bg: m3.errorContainer, ink: m3.onErrorContainer, order: 0 },
  UNKNOWN: { label: statusWord("UNKNOWN"), icon: "info", bg: m3.scHigh, ink: m3.onSurfaceVar, order: 1 },
  UNSUPPORTED: { label: "Not supported", icon: "lock", bg: m3.scLow, ink: m3.onSurfaceVar, order: 3 },
};

/**
 * A readiness evaluation older than this is flagged as stale. The repo defines no readiness freshness
 * constant elsewhere; the Overview's 24 h job window is the closest existing convention. Presentation only:
 * the status value itself is never changed by age.
 */
export const READINESS_STALE_AFTER_MS = 24 * 3600_000;

function evaluationAgeMs(at: string | null | undefined, now: Date): number | null {
  if (!at) return null;
  const t = new Date(at).getTime();
  return Number.isNaN(t) ? null : Math.max(0, now.getTime() - t);
}

/** Shared by the list and KPI: virtual systems never count as extra clusters. */
export function haReadinessItems(clusters: readonly Cluster[], rows: Record<string, CpFailoverSummary[]>) {
  return clusters.map(cluster => {
    const units = rows[cluster.ref] ?? [];
    const base = units.find(row => row.unitId === row.clusterId);
    const vendor = cluster.members[0]?.vendor_hint;
    const unsupported = Boolean(vendor && !["check_point", "palo_alto"].includes(vendor));
    const status: Status = unsupported ? "UNSUPPORTED" : base?.readiness?.status ?? "UNKNOWN";
    return { cluster, units, base, vendor, status };
  });
}

/**
 * Every failover unit behind the shared items, for the list's chips: a plain cluster, a VSX chassis and each of its
 * Virtual Systems count once each (the chassis and every VS are evaluated separately; PO 2026-10-23). A cluster without a
 * unit counts once with the cluster's own word. The Overview KPI keeps counting clusters through `readinessCounts`.
 */
export function readinessUnits(items: ReturnType<typeof haReadinessItems>) {
  return items.flatMap(item => [{ item, status: item.status },
    ...(item.status === "UNSUPPORTED" ? [] : item.units.filter(unit => unit.unitId !== unit.clusterId)
      .map(unit => ({ item, status: (unit.readiness?.status ?? "UNKNOWN") as Status })))]);
}
export const UNIT_COUNT_NOTE = "Counts failover units: each cluster, each VSX chassis and each of its Virtual Systems count separately";

/** The enrolled clusters from the device list (cluster reference -> members); the grouping the Operations screen and the Overview share. */
export function groupClusters(devices: readonly DeviceSummary[]): Cluster[] {
  const byRef = new Map<string, DeviceSummary[]>();
  for (const d of devices) if (d.cluster_member_ref) byRef.set(d.cluster_member_ref, [...(byRef.get(d.cluster_member_ref) ?? []), d]);
  return [...byRef.entries()].map(([ref, members]) => ({ ref, title: ref, members }));
}

/** Counts per readiness word over the shared items; every cluster lands in exactly one, so the parts add up to the whole. */
export function readinessCounts(items: ReadonlyArray<{ status: Status }>) {
  const counts = { total: items.length, ready: 0, notReady: 0, unknown: 0, unsupported: 0 };
  for (const item of items) {
    if (item.status === "READY") counts.ready++;
    else if (item.status === "NOT_READY") counts.notReady++;
    else if (item.status === "UNSUPPORTED") counts.unsupported++;
    else counts.unknown++;
  }
  return counts;
}

/**
 * How old the evaluations are (presentation only; a status is never changed by age). `mostlyStale` is the list's own
 * rule for its warning: more than half of the evaluated clusters were last evaluated over 24 h ago.
 */
export function readinessFreshness(items: ReturnType<typeof haReadinessItems>, now: Date) {
  const evaluatedAts = items.filter(item => item.status !== "UNSUPPORTED")
    .map(item => item.base?.readiness?.observedAt)
    .filter((at): at is string => evaluationAgeMs(at, now) !== null);
  const staleAts = evaluatedAts.filter(at => evaluationAgeMs(at, now)! > READINESS_STALE_AFTER_MS);
  return { evaluatedAts, staleAts, mostlyStale: staleAts.length > 0 && staleAts.length * 2 > evaluatedAts.length, newestStale: staleAts.slice().sort().at(-1) };
}

/**
 * Whether this session may start a readiness run for the unit. The server decides (`canRunReadiness` is computed from the
 * caller's role): readiness is read-only and the aiview persona may start it (AGENTS.md, PO decision 2026-10-04), so the
 * masked flag no longer hides the control. Failover execution, approvals and schedules are not offered here at all.
 */
export function canRunReadiness(row?: CpFailoverSummary) {
  return row?.canRunReadiness === true;
}

/** The one-line rule that tells the three non-ready words apart (UI review 2026-10-18, F-12). */
export const READINESS_RULE = "Not ready: a pre-check found a blocking condition · UNKNOWN: readiness could not be established (no result, or not enough evidence) · neither is Ready.";

function primaryReason(row?: CpFailoverSummary) {
  if (!row) return "INSUFFICIENT_EVIDENCE";
  return row?.refusalReason || readinessStopReason(row?.readiness?.stopCode) || (row?.readiness?.status === "NOT_READY" ? row.readiness.failedCheck || "Blocking conditions observed"
    : row?.readiness?.status === "READY" ? "No blocking conditions observed" : "Readiness has not been established");
}

/** The primary reason in words: the reason label, and a bare outcome token (`READY`) as the outcome word (`Ready`). */
export function primaryReasonWord(row?: CpFailoverSummary) {
  const label = readinessReasonLabel(primaryReason(row));
  return jobOutcomeWord(label) ?? label;
}

/** Presentation only: status, permissions and masked names remain server-owned. */
export function HaReadinessList({ clusters, rows, running, busy, progress, error, onOpen, onRun, onBulkRun }: {
  clusters: readonly Cluster[]; rows: Record<string, CpFailoverSummary[]>; running: string | null;
  busy: boolean; progress: ReadinessProgress; error: string | null;
  onOpen: (ref: string, unitId: string | null) => void; onRun: (row: CpFailoverSummary) => void;
  onBulkRun: (rows: CpFailoverSummary[]) => void;
}) {
  const [filter, setFilter] = useState<Status | null>(null);
  const [search, setSearch] = useState("");
  const [vendorFilter, setVendorFilter] = useState("");
  const [showUnsupported, setShowUnsupported] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [openVs, setOpenVs] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirmRun, setConfirmRun] = useState(false);
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  const items = haReadinessItems(clusters, rows);
  const unitsByStatus = readinessUnits(items);
  const query = search.trim().toLowerCase();
  // A chip filters the clusters that hold a unit with its word (the chassis or any of its Virtual Systems).
  const visible = items.filter(item => (!filter || unitsByStatus.some(unit => unit.item === item && unit.status === filter)))
    .filter(({ cluster, units, vendor }) => (!vendorFilter || vendor === vendorFilter)
    && [cluster.title, ...cluster.members.map(m => m.hostname ?? ""), ...units.map(row => row.virtual_system ?? "")]
      .some(name => name.toLowerCase().includes(query)))
    .sort((a, b) => statuses[a.status].order - statuses[b.status].order || a.cluster.title.localeCompare(b.cluster.title));
  const { evaluatedAts, staleAts, mostlyStale, newestStale } = readinessFreshness(items, now);
  const supported = visible.filter(item => item.status !== "UNSUPPORTED");
  const unsupported = visible.filter(item => item.status === "UNSUPPORTED");
  const selectable = items.filter(item => item.status !== "UNSUPPORTED").flatMap(item => item.units).filter(canRunReadiness);
  // A row action only when it can run now, for this role and inside the window (post-release review 2026-10-18, R6):
  // forty identical locked buttons say nothing the header button does not already say with its "Next window" suffix.
  const jobWindow = useJobWindow();
  const hasActions = selectable.length > 0 && jobWindowUsable(jobWindow);
  // "Run readiness check" targets the clusters in view (the base unit of each, not their virtual systems), so narrowing
  // the filters narrows the run. The server decides who may start one (`canRunReadiness`); a role it refuses sees the
  // button locked with the reason instead of no button at all.
  const runnableVisible = supported.map(item => item.base).filter((row): row is CpFailoverSummary => canRunReadiness(row));
  const mayRun = selectable.length > 0;
  const runCheck = () => {
    if (runnableVisible.length === 0 || busy) return;
    if (runnableVisible.length > 1) setConfirmRun(true); else onBulkRun(runnableVisible);
  };
  const runCheckButton = (testId: string) => mayRun
    ? <JobButton size="small" variant="outlined" data-testid={testId} disabled={busy || runnableVisible.length === 0} onClick={runCheck}
        sx={{ textTransform: "none", whiteSpace: "nowrap" }}>Run readiness check{runnableVisible.length > 1 ? ` (${runnableVisible.length} clusters)` : ""}</JobButton>
    : items.length > 0 && <Button size="small" variant="outlined" disabled data-testid={testId} sx={[DISABLED_STYLE, { textTransform: "none", whiteSpace: "nowrap" }]}>
        Run readiness check · {ROLE_LOCK_LABEL}</Button>;
  const selectedRows = selectable.filter(row => selected.has(row.unitId));
  const visibleSelectable = supported.flatMap(item => item.units.filter(row => row.unitId === row.clusterId || openVs.has(item.cluster.ref))).filter(canRunReadiness);
  const allVisibleSelected = visibleSelectable.length > 0 && visibleSelectable.every(row => selected.has(row.unitId));
  const toggle = (set: Set<string>, id: string) => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  };
  const toggleSelection = (row?: CpFailoverSummary) => {
    if (row && canRunReadiness(row) && !busy) setSelected(current => toggle(current, row.unitId));
  };
  const renderRow = (item: typeof items[number], row?: CpFailoverSummary, nested = false) => {
    const { cluster, vendor } = item;
    const title = nested ? `Virtual System ${row?.virtual_system ?? "Unknown"}` : cluster.title;
    const key = JSON.stringify([cluster.ref, nested ? row?.unitId : null]);
    const status = nested ? row?.readiness?.status ?? "UNKNOWN" : item.status;
    const s = statuses[status];
    const rolesAt = nested ? row?.readiness?.observedAt : cluster.members.map(m => m.inventory_collected_at ?? "").sort().at(-1);
    const roleMembers = nested ? row?.members ?? [] : cluster.members;
    const roleNames = (roles: string[]) => roleMembers.filter(m => roles.includes((m.ha_role ?? "").toLowerCase())).map(m => m.hostname ?? "Unknown member").join(", ") || "Not observed";
    const at = row?.readiness?.observedAt;
    const children = item.units.filter(unit => unit.unitId !== unit.clusterId);
    const isExpanded = expanded.has(key);
    return <Fragment key={key}>
      <TableRow data-readiness-row={key} aria-label={title} aria-expanded={isExpanded} aria-controls={isExpanded ? `checks-${encodeURIComponent(key)}` : undefined}
        tabIndex={0} onClick={() => setExpanded(current => toggle(current, key))}
        onKeyDown={event => {
          if (event.target !== event.currentTarget) return;
          if (event.key === "Enter") { event.preventDefault(); setExpanded(current => toggle(current, key)); }
          if (event.key === " ") { event.preventDefault(); toggleSelection(row); }
        }}
        sx={{ height: 48, cursor: "pointer", bgcolor: nested ? m3.scLow : m3.scLowest, '&:hover': { bgcolor: m3.sc }, '&:focus-visible': { outline: `2px solid ${m3.primary}`, outlineOffset: -2 }, '& > td, & > th': { py: 0.5, whiteSpace: "normal", overflowWrap: "anywhere" } }}>
        <TableCell padding="checkbox" onClick={event => event.stopPropagation()}>
          <Checkbox size="small" inputProps={{ "aria-label": `Select ${title}` }} checked={Boolean(row && selected.has(row.unitId) && canRunReadiness(row))}
            disabled={!canRunReadiness(row) || busy} onChange={() => toggleSelection(row)} />
        </TableCell>
        <TableCell><Chip size="small" icon={<Icon name={s.icon} size={16} />} label={s.label} sx={{ minWidth: "min-content", bgcolor: s.bg, color: s.ink, '& .MuiChip-label': { overflow: "visible", textOverflow: "clip" }, '& .MuiChip-icon': { color: "inherit" } }} /></TableCell>
        <TableCell component="th" scope="row" sx={{ pl: nested ? 4 : 2 }}>
          <IconButton size="small" aria-label={`Checks for ${title}`} aria-expanded={isExpanded}
            aria-controls={isExpanded ? `checks-${encodeURIComponent(key)}` : undefined}
            onClick={event => { event.stopPropagation(); setExpanded(current => toggle(current, key)); }}>
            <span aria-hidden>{isExpanded ? "▾" : "▸"}</span>
          </IconButton>
          {!nested && children.length > 0 && <IconButton size="small" aria-label={`Virtual systems in ${cluster.title}`} aria-expanded={openVs.has(cluster.ref)}
            onClick={event => { event.stopPropagation(); setOpenVs(current => toggle(current, cluster.ref)); }}><span aria-hidden>{openVs.has(cluster.ref) ? "▾" : "▸"}</span></IconButton>}
          <Box component="span" sx={{ fontWeight: 600, mr: 1 }}>{title}</Box><VendorBadge vendor={vendor} />
          {/* The mode line only when it adds information: "Mode: UNKNOWN" next to a known role says nothing. */}
          {!nested && row?.unitKind === "VSX_CHASSIS" && <Typography variant="caption" component="div" data-vsx-chassis
            title="The chassis is its own unit: its status, pre-checks and selection are the chassis step; each Virtual System below has its own">VSX chassis</Typography>}
          {row?.mode && row.mode !== "UNKNOWN" && <Typography variant="caption" component="div" title={`Mode: ${row.mode}`}
            sx={{ whiteSpace: "nowrap", overflowWrap: "normal", overflow: "hidden", textOverflow: "ellipsis" }}>Mode: {clusterModeWord(row.mode)}</Typography>}
        </TableCell>
        <TableCell title={`Roles observed: ${rolesAt ? formatStamp(rolesAt) : "Not observed"}`}>
          Active: {roleNames(["active"])} <span aria-hidden>↔</span> Standby: {roleNames(["standby", "passive"])}
        </TableCell>
        {/* The label map on the reason too (post-release review 2026-10-20, L1): a bare outcome token (`READY`) reads `Ready`, as
            the pill does; a check name or a sentence passes through as before. */}
        <TableCell><Presented label={primaryReasonWord(row)} raw={primaryReason(row)} /></TableCell>
        <TableCell>{at ? <>
          <Box component="time" dateTime={at} title={`${formatStamp(at)} · ${at}`}>{relativeAge(at, now)}</Box>
          {(evaluationAgeMs(at, now) ?? 0) > READINESS_STALE_AFTER_MS && <Tooltip title="Evaluated more than 24 h ago; the status may no longer hold">
            <Chip size="small" label="Stale" data-stale-evaluation sx={{ ml: 0.75, height: 20, fontSize: 11, bgcolor: m3.warningContainer, color: m3.onWarningContainer }} />
          </Tooltip>}
        </> : "Not evaluated"}</TableCell>
        {hasActions && <TableCell onClick={event => event.stopPropagation()}>
          {row && canRunReadiness(row) && <Tooltip title="Run pre-checks"><span><JobButton size="small" aria-label="Run pre-checks" aria-busy={running === row.unitId} disabled={busy}
            onClick={() => onRun(row)} sx={{ color: m3.primary }}>
            {running === row.unitId ? <CircularProgress size={18} aria-label="Running pre-checks" /> : <Icon name="operations" size={18} />}
          </JobButton></span></Tooltip>}
          {row && progress[row.unitId] && <Typography component="span" role="status" variant="caption">{progress[row.unitId]}</Typography>}
        </TableCell>}
      </TableRow>
      {isExpanded && <TableRow><TableCell colSpan={hasActions ? 7 : 6} sx={{ bgcolor: m3.scLow, p: 1.5 }}>
        <Box id={`checks-${encodeURIComponent(key)}`} role="region" aria-label={`Checks for ${title}`}>
          {row?.readiness?.checks.length ? <ReadinessChecksTable checks={row.readiness.checks} members={row.members?.length ? row.members : cluster.members} compact />
            : <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>No observations yet</Typography>}
          <Button size="small" onClick={() => onOpen(cluster.ref, row?.unitId ?? null)}>Open full detail</Button>
        </Box>
      </TableCell></TableRow>}
      {!nested && openVs.has(cluster.ref) && children.map(unit => renderRow(item, unit, true))}
    </Fragment>;
  };
  return <Box className="ha-readiness-full-width" sx={{ width: "100%", minWidth: 0 }}>
    <Typography variant="subtitle1" sx={{ mb: 1 }}>{clusters.length} clusters enrolled</Typography>
    {/* A notice, not an Alert: the status values are unchanged and nothing failed to load. */}
    {mostlyStale && <Box role="status" data-readiness-stale-notice sx={{ mb: 1.5, px: 1.5, py: 1, borderRadius: 1,
      bgcolor: m3.warningContainer, color: m3.onWarningContainer, fontSize: 14 }}>
      {staleAts.length === evaluatedAts.length
        ? `Readiness was last evaluated ${relativeAge(newestStale, now)}; run a readiness check before relying on Ready.`
        : `${staleAts.length} of ${evaluatedAts.length} readiness evaluations are older than 24 h (the newest of them ${relativeAge(newestStale, now)}); run a readiness check before relying on Ready.`}
      <Box component="span" sx={{ ml: 1.5, display: "inline-block" }}>{runCheckButton("stale-run-readiness")}</Box>
    </Box>}
    {/* One legend line (R6): the four status counts and the rule that tells the words apart, side by side. */}
    <Box sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", columnGap: 2, rowGap: 1, mb: 1.5 }}>
    <Box aria-label="Readiness summary" sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
      {(Object.keys(statuses) as Status[]).map(status => {
        const s = statuses[status];
        const matches = unitsByStatus.filter(unit => unit.status === status);
        const vendors = new Map<string, number>();
        matches.forEach(({ item }) => { const name = vendorDisplayName(item.vendor); vendors.set(name, (vendors.get(name) ?? 0) + 1); });
        return <Tooltip key={status} title={`${UNIT_COUNT_NOTE}. ${[...vendors].map(([name, count]) => `${name} ${count}`).join(" · ") || "No units"}`}>
          <Button size="small" aria-label={`${s.label}: ${matches.length} units`} aria-pressed={filter === status}
            onClick={() => setFilter(filter === status ? null : status)}
            // Zero is neutral, never green (or red): a status word with no cluster behind it carries no status colour.
            sx={{ bgcolor: matches.length === 0 ? m3.scHigh : s.bg, color: matches.length === 0 ? m3.onSurfaceVar : s.ink,
              border: `1px solid ${filter === status ? s.ink : 'transparent'}`, px: 1.5, borderRadius: 2 }}>
            <Icon name={s.icon} size={16} /><Box component="span" sx={{ ml: 0.75 }}>{s.label} · {matches.length}</Box>
          </Button>
        </Tooltip>;
      })}
    </Box>
    <Typography variant="caption" data-readiness-rule sx={{ color: m3.onSurfaceVar }}>{READINESS_RULE}</Typography>
    </Box>
    <Box role="toolbar" aria-label="Readiness filters" sx={{ display: "flex", gap: 1.5, mb: 1.5, flexWrap: "wrap", alignItems: "center" }}>
      <TextField label="Search cluster or member" size="small" value={search} onChange={e => setSearch(e.target.value)} sx={{ flex: "1 1 280px" }} />
      <TextField select label="Vendor" size="small" value={vendorFilter} onChange={e => setVendorFilter(e.target.value)} sx={{ minWidth: 190 }}>
        <MenuItem value="">All vendors</MenuItem>
        {[...new Set(items.map(item => item.vendor).filter(Boolean))].map(vendor => <MenuItem key={vendor} value={vendor}>{vendorDisplayName(vendor)}</MenuItem>)}
      </TextField>
      <Box sx={{ ml: "auto" }}>{runCheckButton("toolbar-run-readiness")}</Box>
    </Box>
    <Dialog open={confirmRun} onClose={() => setConfirmRun(false)} aria-labelledby="run-readiness-title">
      <DialogTitle id="run-readiness-title">Run readiness check on {runnableVisible.length} clusters?</DialogTitle>
      <DialogContent>
        <Typography variant="body2">
          A read-only pre-check, one cluster at a time, inside the job window. Each result replaces the previous one for that cluster; nothing is changed on a device.
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => setConfirmRun(false)}>Cancel</Button>
        <Button variant="contained" onClick={() => { setConfirmRun(false); onBulkRun(runnableVisible); }}>Run readiness check</Button>
      </DialogActions>
    </Dialog>
    {selectedRows.length > 0 && <Box role="toolbar" aria-label="Bulk readiness actions" sx={{ position: "sticky", top: 0, zIndex: 2, display: "flex", gap: 1, alignItems: "center", p: 1, mb: 1, bgcolor: m3.primaryContainer, borderRadius: 2 }}>
      <Typography variant="body2" sx={{ fontWeight: 600, px: 1 }}>{selectedRows.length} of {selectable.length} selected</Typography>
      <JobButton disabled={busy} onClick={() => onBulkRun(selectedRows)}>Run pre-checks ({selectedRows.length})</JobButton>
      <Button disabled={busy} onClick={() => setSelected(new Set())}>Clear selection</Button>
    </Box>}
    {error && <Box sx={{ mb: 1 }}><StatePanel variant="error" title={error} /></Box>}
    <TableContainer sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2 }}>
      <Table size="small" aria-label="HA clusters" sx={{ width: "100%", tableLayout: "fixed" }}>
        <TableHead><TableRow>
          <TableCell padding="checkbox"><Checkbox size="small" inputProps={{ "aria-label": "Select all visible" }} checked={allVisibleSelected}
            indeterminate={!allVisibleSelected && visibleSelectable.some(row => selected.has(row.unitId))} disabled={busy || visibleSelectable.length === 0}
            onChange={() => setSelected(current => {
              const next = new Set(current);
              visibleSelectable.forEach(row => { if (allVisibleSelected) next.delete(row.unitId); else next.add(row.unitId); });
              return next;
            })} /></TableCell>
          <TableCell sx={{ width: 140 }}>Status</TableCell><TableCell sx={{ width: "22%" }}>Cluster</TableCell>
          <TableCell sx={{ width: "24%" }}>Active ↔ Standby members</TableCell><TableCell>Primary reason</TableCell>
          <TableCell sx={{ width: 145 }}>Last evaluated</TableCell>{hasActions && <TableCell sx={{ width: 105 }}>Actions</TableCell>}
        </TableRow></TableHead>
        <TableBody>{supported.map(item => renderRow(item, item.base))}</TableBody>
      </Table>
    </TableContainer>
    {unsupported.length > 0 && <Box component="details" open={showUnsupported || filter === "UNSUPPORTED"} onToggle={e => setShowUnsupported(e.currentTarget.open)} sx={{ mt: 1.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 1.5, bgcolor: m3.scLow }}>
      <Box component="summary" sx={{ cursor: "pointer", color: m3.onSurfaceVar, fontWeight: 600 }}>Not supported · {unsupported.length} clusters</Box>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, my: 1 }}>Readiness pre-checks are not supported for these vendors; enrolled clusters are shown for inventory context.</Typography>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0 }}>{unsupported.map(({ cluster, vendor }) => <Box component="li" key={cluster.ref} sx={{ display: "flex", gap: 1, alignItems: "center", py: 0.75 }}><Typography variant="body2">{cluster.title}</Typography><VendorBadge vendor={vendor} /><Typography variant="caption" title="UNSUPPORTED_MODE"><Presented label={readinessReasonLabel("UNSUPPORTED_MODE")} raw="UNSUPPORTED_MODE" /> · Readiness: not evaluated</Typography></Box>)}</Box>
    </Box>}
    {visible.length === 0 && <StatePanel variant="empty" title="No clusters match these filters" />}
  </Box>;
}
