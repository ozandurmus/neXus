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
import { formatTime, relativeAge, DISPLAY_TZ_LABEL } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import { ReadinessChecksTable } from "./ReadinessChecksTable";

type Cluster = { ref: string; title: string; members: DeviceSummary[] };
type Status = "READY" | "NOT_READY" | "UNKNOWN" | "UNSUPPORTED";
export type ReadinessProgress = Record<string, "Queued" | "Running" | "Completed" | "Failed" | "Not run">;
const statuses: Record<Status, { label: string; icon: IconName; bg: string; ink: string; order: number }> = {
  READY: { label: "Ready", icon: "check-circle", bg: m3.successContainer, ink: m3.onSuccessContainer, order: 2 },
  NOT_READY: { label: "Not ready", icon: "x-circle", bg: m3.errorContainer, ink: m3.onErrorContainer, order: 0 },
  UNKNOWN: { label: "Unknown", icon: "info", bg: m3.scHigh, ink: m3.onSurfaceVar, order: 1 },
  UNSUPPORTED: { label: "Not supported", icon: "lock", bg: m3.scLow, ink: m3.onSurfaceVar, order: 3 },
};

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

/** The API's masked flag identifies replay-viewer responses; this view offers inspection only. */
export function canRunReadiness(row?: CpFailoverSummary) {
  return row?.canRunReadiness === true && row.masked !== true;
}

function primaryReason(row?: CpFailoverSummary) {
  return row?.readiness?.status === "NOT_READY" ? row.readiness.failedCheck || "Blocking conditions observed"
    : row?.readiness?.status === "READY" ? "No blocking conditions observed" : "Readiness has not been established";
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
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  const items = haReadinessItems(clusters, rows);
  const query = search.trim().toLowerCase();
  const visible = items.filter(({ cluster, units, vendor, status }) => (!filter || status === filter)
    && (!vendorFilter || vendor === vendorFilter)
    && [cluster.title, ...cluster.members.map(m => m.hostname ?? ""), ...units.map(row => row.virtual_system ?? "")]
      .some(name => name.toLowerCase().includes(query)))
    .sort((a, b) => statuses[a.status].order - statuses[b.status].order || a.cluster.title.localeCompare(b.cluster.title));
  const supported = visible.filter(item => item.status !== "UNSUPPORTED");
  const unsupported = visible.filter(item => item.status === "UNSUPPORTED");
  const selectable = items.filter(item => item.status !== "UNSUPPORTED").flatMap(item => item.units).filter(canRunReadiness);
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
    const key = JSON.stringify([cluster.ref, row?.unitId ?? null]);
    const status = nested ? row?.readiness?.status ?? "UNKNOWN" : item.status;
    const s = statuses[status];
    const rolesAt = nested ? row?.readiness?.observedAt : cluster.members.map(m => m.inventory_collected_at ?? "").sort().at(-1);
    const roleMembers = nested ? row?.members ?? [] : cluster.members;
    const roleNames = (roles: string[]) => roleMembers.filter(m => roles.includes((m.ha_role ?? "").toLowerCase())).map(m => m.hostname ?? "Unknown member").join(", ") || "Not observed";
    const at = row?.readiness?.observedAt;
    const children = item.units.filter(unit => unit.unitId !== unit.clusterId);
    const isExpanded = expanded.has(key);
    return <Fragment key={key}>
      <TableRow aria-label={title} aria-expanded={isExpanded} aria-controls={isExpanded ? `checks-${encodeURIComponent(key)}` : undefined}
        tabIndex={0} onClick={() => setExpanded(current => toggle(current, key))}
        onKeyDown={event => {
          if (event.target !== event.currentTarget) return;
          if (event.key === "Enter") { event.preventDefault(); setExpanded(current => toggle(current, key)); }
          if (event.key === " ") { event.preventDefault(); toggleSelection(row); }
        }}
        sx={{ height: 48, cursor: "pointer", bgcolor: nested ? m3.scLow : m3.scLowest, '&:hover': { bgcolor: m3.sc }, '&:focus-visible': { outline: `2px solid ${m3.primary}`, outlineOffset: -2 }, '& > td, & > th': { py: 0.5, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" } }}>
        <TableCell padding="checkbox" onClick={event => event.stopPropagation()}>
          <Checkbox size="small" inputProps={{ "aria-label": `Select ${title}` }} checked={Boolean(row && selected.has(row.unitId) && canRunReadiness(row))}
            disabled={!canRunReadiness(row) || busy} onChange={() => toggleSelection(row)} />
        </TableCell>
        <TableCell><Chip size="small" icon={<Icon name={s.icon} size={16} />} label={s.label} sx={{ bgcolor: s.bg, color: s.ink, '& .MuiChip-icon': { color: "inherit" } }} /></TableCell>
        <TableCell component="th" scope="row" sx={{ pl: nested ? 4 : 2 }}>
          {!nested && children.length > 0 && <IconButton size="small" aria-label={`Virtual systems in ${cluster.title}`} aria-expanded={openVs.has(cluster.ref)}
            onClick={event => { event.stopPropagation(); setOpenVs(current => toggle(current, cluster.ref)); }}><span aria-hidden>{openVs.has(cluster.ref) ? "▾" : "▸"}</span></IconButton>}
          <Box component="span" sx={{ fontWeight: 600, mr: 1 }}>{title}</Box><VendorBadge vendor={vendor} />
        </TableCell>
        <TableCell title={`Roles observed: ${rolesAt ? `${formatTime(rolesAt)} ${DISPLAY_TZ_LABEL}` : "Not observed"}`}>
          Active: {roleNames(["active"])} <span aria-hidden>↔</span> Standby: {roleNames(["standby", "passive"])}
        </TableCell>
        <TableCell title={primaryReason(row)}>{primaryReason(row)}</TableCell>
        <TableCell>{at ? <Box component="time" dateTime={at} title={`${formatTime(at)} ${DISPLAY_TZ_LABEL} · ${at}`}>{relativeAge(at, now)}</Box> : "Not evaluated"}</TableCell>
        <TableCell onClick={event => event.stopPropagation()}>
          {row && canRunReadiness(row) && <Tooltip title="Run pre-checks"><span><IconButton size="small" aria-label="Run pre-checks" aria-busy={running === row.unitId} disabled={busy}
            onClick={() => onRun(row)} sx={{ color: m3.primary }}>
            {running === row.unitId ? <CircularProgress size={18} aria-label="Running pre-checks" /> : <Icon name="operations" size={18} />}
          </IconButton></span></Tooltip>}
          {row && progress[row.unitId] && <Typography component="span" role="status" variant="caption">{progress[row.unitId]}</Typography>}
        </TableCell>
      </TableRow>
      {isExpanded && <TableRow><TableCell colSpan={7} sx={{ bgcolor: m3.scLow, p: 1.5 }}>
        <Box id={`checks-${encodeURIComponent(key)}`} role="region" aria-label={`Checks for ${title}`}>
          {row?.readiness?.checks.length ? <ReadinessChecksTable checks={row.readiness.checks} members={row.members} compact />
            : <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>No observations yet</Typography>}
          <Button size="small" onClick={() => onOpen(cluster.ref, row?.unitId ?? null)}>Open full detail</Button>
        </Box>
      </TableCell></TableRow>}
      {!nested && openVs.has(cluster.ref) && children.map(unit => renderRow(item, unit, true))}
    </Fragment>;
  };
  return <Box className="ha-readiness-full-width" sx={{ width: "100%", minWidth: 0 }}>
    <Typography variant="subtitle1" sx={{ mb: 1 }}>{clusters.length} clusters enrolled</Typography>
    <Box aria-label="Readiness summary" sx={{ display: "flex", flexWrap: "wrap", gap: 1, mb: 1.5 }}>
      {(Object.keys(statuses) as Status[]).map(status => {
        const s = statuses[status];
        const matches = items.filter(item => item.status === status);
        const vendors = new Map<string, number>();
        matches.forEach(item => { const name = vendorDisplayName(item.vendor); vendors.set(name, (vendors.get(name) ?? 0) + 1); });
        return <Tooltip key={status} title={[...vendors].map(([name, count]) => `${name} ${count}`).join(" · ") || "No clusters"}>
          <Button size="small" aria-label={`${s.label}: ${matches.length} clusters`} aria-pressed={filter === status}
            onClick={() => setFilter(filter === status ? null : status)}
            sx={{ bgcolor: s.bg, color: s.ink, border: `1px solid ${filter === status ? s.ink : 'transparent'}`, px: 1.5, borderRadius: 2 }}>
            <Icon name={s.icon} size={16} /><Box component="span" sx={{ ml: 0.75 }}>{s.label} · {matches.length}</Box>
          </Button>
        </Tooltip>;
      })}
    </Box>
    <Box role="toolbar" aria-label="Readiness filters" sx={{ display: "flex", gap: 1.5, mb: 1.5, flexWrap: "wrap" }}>
      <TextField label="Search cluster or member" size="small" value={search} onChange={e => setSearch(e.target.value)} sx={{ flex: "1 1 280px" }} />
      <TextField select label="Vendor" size="small" value={vendorFilter} onChange={e => setVendorFilter(e.target.value)} sx={{ minWidth: 190 }}>
        <MenuItem value="">All vendors</MenuItem>
        {[...new Set(items.map(item => item.vendor).filter(Boolean))].map(vendor => <MenuItem key={vendor} value={vendor}>{vendorDisplayName(vendor)}</MenuItem>)}
      </TextField>
    </Box>
    {selectedRows.length > 0 && <Box role="toolbar" aria-label="Bulk readiness actions" sx={{ position: "sticky", top: 0, zIndex: 2, display: "flex", gap: 1, p: 1, mb: 1, bgcolor: m3.primaryContainer, borderRadius: 2 }}>
      <Button disabled={busy} onClick={() => onBulkRun(selectedRows)}>Run pre-checks ({selectedRows.length})</Button>
      <Button disabled={busy} onClick={() => setSelected(new Set())}>Clear selection</Button>
    </Box>}
    {error && <Box sx={{ mb: 1 }}><StatePanel variant="error" title={error} /></Box>}
    <TableContainer sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2 }}>
      <Table size="small" aria-label="HA clusters" sx={{ width: "100%", minWidth: 1100, tableLayout: "fixed" }}>
        <TableHead><TableRow>
          <TableCell padding="checkbox"><Checkbox size="small" inputProps={{ "aria-label": "Select all visible" }} checked={allVisibleSelected}
            indeterminate={!allVisibleSelected && visibleSelectable.some(row => selected.has(row.unitId))} disabled={busy || visibleSelectable.length === 0}
            onChange={() => setSelected(current => {
              const next = new Set(current);
              visibleSelectable.forEach(row => { if (allVisibleSelected) next.delete(row.unitId); else next.add(row.unitId); });
              return next;
            })} /></TableCell>
          <TableCell sx={{ width: 105 }}>Status</TableCell><TableCell sx={{ width: "22%" }}>Cluster</TableCell>
          <TableCell sx={{ width: "28%" }}>Active ↔ Standby members</TableCell><TableCell>Primary reason</TableCell>
          <TableCell sx={{ width: 105 }}>Last evaluated</TableCell><TableCell sx={{ width: 105 }}>Actions</TableCell>
        </TableRow></TableHead>
        <TableBody>{supported.map(item => renderRow(item, item.base))}</TableBody>
      </Table>
    </TableContainer>
    {unsupported.length > 0 && <Box component="details" open={showUnsupported || filter === "UNSUPPORTED"} onToggle={e => setShowUnsupported(e.currentTarget.open)} sx={{ mt: 1.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 1.5, bgcolor: m3.scLow }}>
      <Box component="summary" sx={{ cursor: "pointer", color: m3.onSurfaceVar, fontWeight: 600 }}>Not supported · {unsupported.length} clusters</Box>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, my: 1 }}>Readiness pre-checks are not supported for these vendors; enrolled clusters are shown for inventory context.</Typography>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0 }}>{unsupported.map(({ cluster, vendor }) => <Box component="li" key={cluster.ref} sx={{ display: "flex", gap: 1, alignItems: "center", py: 0.75 }}><Typography variant="body2">{cluster.title}</Typography><VendorBadge vendor={vendor} /></Box>)}</Box>
    </Box>}
    {visible.length === 0 && <StatePanel variant="empty" title="No clusters match these filters" />}
  </Box>;
}
