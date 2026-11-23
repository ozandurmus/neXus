import { JobButton, jobWindowUsable, useJobWindow } from "../shell/JobWindow";
import { ROLE_LOCK_LABEL } from "../shell/actionAccess";
import { DISABLED_STYLE } from "../shell/disabledStyle";
import { Presented } from "../shell/PresentedText";
import { clusterModeWord, jobOutcomeWord, readinessReasonLabel, statusWord } from "../shell/presenters";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import { Fragment, useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
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
import type { CpFailoverSummary, DeviceSummary, FailoverIncident } from "../auth/adminApi";
import { Icon, type IconName } from "../shell/Icon";
import { StatePanel, VendorBadge, vendorDisplayName } from "../shell/States";
import { formatStamp, relativeAge } from "../shell/time";
import { FRESH_UNDER_MS, isPastFresh } from "../shell/freshness";
import { urlParam } from "../shell/urlParams";
import { m3 } from "../theme/m3Theme";
import { isActiveRole, isStandbyRole, observedRoles, primaryReportsPeer, primaryRolesConfirmed, readinessStopReason } from "./ReadinessChecksTable";
import { configSyncSettlingText, panConfigSyncPhase } from "./PanConfigSyncAction";
import { HaPairLine } from "./HaPairLine";
import { PRODUCT_NAME } from "../brand/product";
import { readinessWarningChecks, readyWord } from "./readinessWarnings";
import { INCIDENTS_UNREADABLE, incidentsUnreadableRow, STATE_ORDER, SEGMENTS, chassisRollup, ALL_VIRTUAL_SYSTEMS_TOOLTIP, allVirtualSystemsLabel, chassisVs0Offered, failoverButtonLabel, failoverExecutionDisabled, failoverVendorLabel, inSegment, reasonLine, rowState,
  segmentCounts, segmentFromReadiness, type RowState, type Segment, type Status } from "./failover/failoverRowModel";
export { failoverExecutionDisabled, failoverVendorLabel };

type Cluster = { ref: string; title: string; members: DeviceSummary[] };
export type ReadinessProgress = Record<string, "Queued" | "Running" | "Completed" | "Failed" | "Not run">;
const statuses: Record<Status, { label: string; icon: IconName; bg: string; ink: string; order: number }> = {
  READY: { label: "Ready", icon: "check-circle", bg: m3.successContainer, ink: m3.onSuccessContainer, order: 2 },
  NOT_READY: { label: "Not ready", icon: "x-circle", bg: m3.errorContainer, ink: m3.onErrorContainer, order: 0 },
  UNKNOWN: { label: statusWord("UNKNOWN"), icon: "info", bg: m3.scHigh, ink: m3.onSurfaceVar, order: 1 },
  UNSUPPORTED: { label: "Not supported", icon: "lock", bg: m3.scLow, ink: m3.onSurfaceVar, order: 3 },
};

/**
 * A readiness evaluation this old is flagged as stale: the edge of "fresh" on the product's one freshness scale
 * (`shell/freshness.ts`, UI review 2026-11-05 B1). Presentation only: the status value itself is never changed by age.
 */
export const READINESS_STALE_AFTER_MS = FRESH_UNDER_MS;

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
    const vendor = cluster.members[0]?.vendor_hint ?? base?.vendor ?? units[0]?.vendor;
    const hasReadinessUnit = units.some(unit => unit.readinessAvailable === true
      || (unit.readinessAvailable !== false && (!unit.vendor || unit.vendor === vendor)));
    // Cisco ASA is evaluated (readiness only) when the server says readinessAvailable; otherwise it is listed only.
    const asaReadiness = vendor === "cisco_asa" && hasReadinessUnit;
    // FortiGate likewise: evaluated for readiness only when the server says readinessAvailable.
    const fgtReadiness = vendor === "fortinet" && hasReadinessUnit;
    const unsupported = Boolean(vendor && !["check_point", "palo_alto"].includes(vendor) && !asaReadiness && !fgtReadiness);
    const status: Status = unsupported ? "UNSUPPORTED" : base?.readiness?.status ?? "UNKNOWN";
    return { cluster, units, base, vendor, status };
  });
}

export const CLUSTER_COUNT_NOTE = "Counts enrolled clusters: each cluster counts once";

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
  const staleAts = evaluatedAts.filter(at => isPastFresh(at, now));
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

/** The lead blocking check in words (its own summary) when the stored reason is only the generic "pre-check failed". */
function leadCheckReason(row: CpFailoverSummary): string | null {
  const checks = row.readiness?.checks ?? [];
  const lead = checks.find(c => c.blocking && c.result === "FAIL") ?? checks.find(c => c.blocking && c.result === "UNKNOWN");
  return lead?.summary ? `${lead.title}: ${lead.summary}` : null;
}

function primaryReason(row?: CpFailoverSummary) {
  if (!row) return "INSUFFICIENT_EVIDENCE";
  const sync = panConfigSyncPhase(row);
  if (sync.phase === "SETTLING") return configSyncSettlingText(sync.requestedAt);
  const checks = row.readiness?.checks ?? [];
  const blocking = checks.find(c => c.blocking && c.result === "FAIL") ?? checks.find(c => c.blocking && c.result === "UNKNOWN");
  if (sync.phase === "STILL_FAILS" && (!blocking || blocking.checkNo === 4)) return "Configuration sync still fails";
  const lead = leadCheckReason(row);
  if (lead && (!row.refusalReason || row.refusalReason === "CHECK_NOT_READY" || row.refusalReason === "READINESS_NOT_READY"
      || readinessReasonLabel(row.refusalReason) === row.refusalReason)) return lead;
  return row?.refusalReason || readinessStopReason(row?.readiness?.stopCode) || (row?.readiness?.status === "NOT_READY" ? row.readiness.failedCheck || "Blocking conditions observed"
    : row?.readiness?.status === "READY" ? "No blocking conditions observed" : "Readiness has not been established");
}

/** The primary reason in words: the reason label, and a bare outcome token (`READY`) as the outcome word (`Ready`). */
export function primaryReasonWord(row?: CpFailoverSummary) {
  const label = readinessReasonLabel(primaryReason(row));
  return jobOutcomeWord(label) ?? label;
}


/** A readiness run that did not finish, shown on the row it belongs to. `cancelled`: the business-hours question was declined (not a failure). */
export type ReadinessError = { unitId: string | null; text: string; cancelled: boolean };

type RoleMember = DeviceSummary & { hostname: string | null; ha_role: string | null };
type Item = ReturnType<typeof haReadinessItems>[number];

/** The members of one row with the labels the server sent (masked for aiview) and the best known HA role. */
function roleMembersOf(item: Item, row: CpFailoverSummary | undefined, nested: boolean): RoleMember[] {
  const raw = nested ? row?.members ?? [] : item.cluster.members.length ? item.cluster.members : row?.members ?? [];
  return raw.map(m => {
    const rowMember = row?.members?.find(rm => rm.device_id === m.device_id);
    const maskedMember = (row as { maskedMembers?: Array<{ device_id: string; maskedLabel: string; ha_role?: string | null }> } | undefined)
      ?.maskedMembers?.find(mm => mm.device_id === m.device_id);
    const haPairMember = row?.haView?.members?.find(hm => hm.device_id === m.device_id);
    const haRole = (m as { ha_role?: string | null }).ha_role ?? rowMember?.ha_role ?? maskedMember?.ha_role
      ?? (haPairMember && haPairMember.role !== "unknown" ? haPairMember.role : null);
    return { ...m, hostname: maskedMember?.maskedLabel ?? (m as { hostname?: string | null }).hostname ?? rowMember?.hostname ?? null, ha_role: haRole } as RoleMember;
  });
}

const PILL: Record<"ready" | "warn" | "bad" | "neutral", { bg: string; ink: string }> = {
  ready: { bg: m3.successContainer, ink: m3.onSuccessContainer }, warn: { bg: m3.warningContainer, ink: m3.onWarningContainer }, bad: { bg: m3.errorContainer, ink: m3.onErrorContainer },
  neutral: { bg: m3.scHigh, ink: m3.onSurfaceVar },
};
/** The pill for a state (REDESIGN 3.1). Filled green always means "can be failed over here"; readiness-only pairs are outlined. */
function pillOf(state: RowState, status: Status, warnings = 0): { label: string; icon: IconName; tone: keyof typeof PILL; outline: boolean } {
  switch (state) {
    case "INCIDENT": return { label: "Incident open", icon: "warning", tone: "bad", outline: false };
    // A blocking check that warns keeps the unit Ready, but the pill never says "Ready" without naming the warning (PO 2026-11-23).
    case "CAN_FAIL_OVER": return warnings > 0 ? { label: readyWord(warnings), icon: "warning", tone: "warn", outline: false }
      : { label: "Ready", icon: "check-circle", tone: "ready", outline: false };
    case "BLOCKED": return status === "NOT_READY" ? { label: "Not ready", icon: "x-circle", tone: "bad", outline: false }
      : { label: "Refused", icon: "x-circle", tone: "bad", outline: false };
    case "READINESS_ONLY": return { label: status === "READY" ? readyWord(warnings, statuses[status].label) : statuses[status].label,
      icon: status === "READY" && warnings > 0 ? "warning" : statuses[status].icon, outline: true,
      tone: status === "READY" ? warnings > 0 ? "warn" : "ready" : status === "NOT_READY" ? "bad" : "neutral" };
    case "UNSUPPORTED": return { label: statuses.UNSUPPORTED.label, icon: "lock", tone: "neutral", outline: false };
    default: return { label: statuses.UNKNOWN.label, icon: "info", tone: "neutral", outline: false };
  }
}

/** Presentation only: status, permissions and masked names remain server-owned. */
export function HaReadinessList({ clusters, rows, running, busy, progress, error, incidents = [], summaryFailed = false, incidentsFailed = false, settled = true, onOpen, onRun, onBulkRun, onFailover }: {
  clusters: readonly Cluster[]; rows: Record<string, CpFailoverSummary[]>; running: string | null;
  busy: boolean; progress: ReadinessProgress; error: ReadinessError | string | null;
  incidents?: readonly FailoverIncident[]; summaryFailed?: boolean; /** The incidents read failed: no row is shown Ready. */ incidentsFailed?: boolean;
  /**
   * The summary and the incidents have both answered (or failed). Until then the list is not drawn: with the reads open every
   * row is Unknown, and the segment it opens on would change under the reader (and under a script holding a row) once they land.
   */
  settled?: boolean;
  onOpen: (ref: string, unitId: string | null) => void; onRun: (row: CpFailoverSummary) => void;
  onBulkRun: (rows: CpFailoverSummary[]) => void;
  /** The row's failover button: opens the detail with the failover dialog. */
  onFailover?: (ref: string, unitId: string) => void;
  /** Kept for callers that reload the summary after a server-offered row action; the PAN sync action now lives in the detail. */
  onChanged?: () => void;
}) {
  // `?readiness=NOT_READY` (the Overview's "needs attention" line) opens the list on its segment.
  const [choice, setChoice] = useState<Segment | null>(() => segmentFromReadiness(urlParam("readiness")));
  const [search, setSearch] = useState("");
  const [vendorFilter, setVendorFilter] = useState("");
  const [showUnsupported, setShowUnsupported] = useState(() => urlParam("readiness") === "UNSUPPORTED");
  const [openVs, setOpenVs] = useState<Set<string>>(new Set());
  const [confirmRun, setConfirmRun] = useState(false);
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  const items = haReadinessItems(clusters, rows);
  const entries = items.map(item => ({ item, state: rowState(item, item.base, incidents, summaryFailed, incidentsFailed) }));
  const counts = segmentCounts(entries);
  // Until the reader chooses, the list opens on "Can fail over now" when something can be failed over, else on everything. The
  // opening segment is fixed once, when the reads have settled: a later re-check or reload never moves the reader to another segment.
  const opening = useRef<Segment | null>(null);
  if (settled && opening.current === null) opening.current = counts.can > 0 ? "can" : "all";
  const segment: Segment = choice ?? opening.current ?? "all";
  const query = search.trim().toLowerCase();
  const visible = entries.filter(({ item, state }) => inSegment(state, segment) && (!vendorFilter || item.vendor === vendorFilter)
      && [item.cluster.title, ...item.cluster.members.map(m => m.hostname ?? ""), ...item.units.map(unit => unit.virtual_system ?? "")]
        .some(name => name.toLowerCase().includes(query)))
    .sort((a, b) => (segment === "all" ? STATE_ORDER.indexOf(a.state) - STATE_ORDER.indexOf(b.state) : 0) || a.item.cluster.title.localeCompare(b.item.cluster.title));
  const { evaluatedAts, staleAts, mostlyStale, newestStale } = readinessFreshness(items, now);
  const supported = visible.filter(entry => entry.state !== "UNSUPPORTED");
  const unsupported = visible.filter(entry => entry.state === "UNSUPPORTED");
  const selectable = items.filter(item => item.status !== "UNSUPPORTED").flatMap(item => item.units).filter(canRunReadiness);
  // A row action only when it can run now, for this role and inside the window (post-release review 2026-10-18, R6):
  // forty identical locked buttons say nothing the header button does not already say with its "Next window" suffix.
  const jobWindow = useJobWindow();
  const hasActions = selectable.length > 0 && jobWindowUsable(jobWindow);
  // "Re-check shown" targets the clusters in view (the base unit of each, not their virtual systems), so narrowing the
  // filters narrows the run. The server decides who may start one (`canRunReadiness`); a role it refuses sees the button
  // locked with the reason instead of no button at all.
  const runnableVisible = supported.map(entry => entry.item.base).filter((row): row is CpFailoverSummary => canRunReadiness(row));
  const mayRun = selectable.length > 0;
  const runCheck = () => {
    if (runnableVisible.length === 0 || busy) return;
    if (runnableVisible.length > 1) setConfirmRun(true); else onBulkRun(runnableVisible);
  };
  const runCheckButton = (testId: string) => mayRun
    ? <JobButton size="small" variant="outlined" data-testid={testId} disabled={busy || runnableVisible.length === 0} onClick={runCheck}
        sx={{ textTransform: "none", whiteSpace: "nowrap" }}>Re-check shown ({runnableVisible.length})</JobButton>
    : items.length > 0 && <Button size="small" variant="outlined" disabled data-testid={testId} sx={[DISABLED_STYLE, { textTransform: "none", whiteSpace: "nowrap" }]}>
        Re-check shown · {ROLE_LOCK_LABEL}</Button>;
  const runError = typeof error === "string" ? { unitId: null, text: error, cancelled: false } : error;
  const toggleVs = (ref: string) => setOpenVs(current => { const next = new Set(current); if (next.has(ref)) next.delete(ref); else next.add(ref); return next; });
  const renderRow = (item: Item, row: CpFailoverSummary | undefined, nested: boolean) => {
    const { cluster, vendor } = item;
    const title = nested ? `Virtual System ${row?.virtual_system ?? "Unknown"}` : cluster.title;
    const key = JSON.stringify([cluster.ref, nested ? row?.unitId : null]);
    const status: Status = nested ? row?.readiness?.status ?? "UNKNOWN" : item.status;
    const state = rowState(item, row, incidents, summaryFailed, incidentsFailed);
    const unreadable = incidentsUnreadableRow(state, status, summaryFailed, incidentsFailed);
    const pill = pillOf(state, status, readinessWarningChecks(row?.readiness?.checks).length);
    const roleMembers = roleMembersOf(item, row, nested);
    const checks = row?.readiness?.checks ?? [];
    const observed = observedRoles(checks, roleMembers, row?.readiness?.observedAt, vendor);
    // A single-address FortiGate has one enrolled device and no inventory role: check 1 PASS (one primary, one secondary, the
    // enrolled unit is the primary) gives primary = active and secondary = standby, as the primary reports.
    const fgtRoles = !nested && primaryReportsPeer(vendor, roleMembers) && primaryRolesConfirmed(checks);
    const fromReadiness = !fgtRoles && roleMembers.length > 0 && roleMembers.every(m => observed.has(m.device_id));
    const readinessRolesAt = [...observed.values()].map(v => v.at).filter((t): t is string => Boolean(t)).sort().at(-1) ?? row?.readiness?.observedAt;
    const inventoryRolesAt = nested ? row?.readiness?.observedAt : cluster.members.map(m => m.inventory_collected_at ?? "").filter(Boolean).sort().at(-1);
    const rolesAt = (fromReadiness || fgtRoles) ? (readinessRolesAt ?? inventoryRolesAt) : inventoryRolesAt;
    const roleOf = (m: { device_id: string; ha_role?: string | null }) => (fromReadiness ? observed.get(m.device_id)?.role : m.ha_role)?.toLowerCase() ?? "";
    const hasInventoryRoles = !fromReadiness && !fgtRoles && roleMembers.some(m => isActiveRole(m.ha_role) || isStandbyRole(m.ha_role));
    const roleNames = (match: (role: string) => boolean, activeSide: boolean) => fgtRoles
      ? (activeSide ? roleMembers[0].hostname ?? "Unknown member" : "Secondary (as the primary reports)")
      : roleMembers.filter(m => match(roleOf(m))).map(m => m.hostname ?? "Unknown member").join(", ") || "Not observed";
    const at = row?.readiness?.observedAt;
    const children = item.units.filter(unit => unit.unitId !== unit.clusterId);
    const reason = summaryFailed ? { headline: "Readiness could not be read", more: 0, code: undefined, where: undefined, title: undefined, warning: undefined }
      : unreadable ? { headline: `Readiness is Ready, but ${INCIDENTS_UNREADABLE}`, more: 0, code: undefined, where: undefined, title: undefined, warning: undefined }
      : reasonLine(row, roleMembers);
    const isChassis = !nested && row?.unitKind === "VSX_CHASSIS";
    const rowError = runError && row && runError.unitId === row.unitId ? runError : null;
    const rowBusy = Boolean(row && running === row.unitId);
    const canRun = Boolean(row && canRunReadiness(row)) && hasActions;
    const open = () => onOpen(cluster.ref, row?.unitId ?? null);
    const vendorSentence = `${PRODUCT_NAME} does not execute failover for ${failoverVendorLabel(vendor)}. The checks describe the pair's state; fail over on the device.`;
    const action = (() => {
      if (state === "CAN_FAIL_OVER" && row && !row.masked) {
        // PO 2026-11-23: on a chassis, VS0 is its own failover unit ("Fail over VS0", HA or VSLS mode) and "Fail over all virtual
        // systems" is a separate, secondary action. Both open the chassis detail, where the server re-checks everything.
        const vs0 = !isChassis || chassisVs0Offered(row);
        const label = failoverButtonLabel(row);
        const allLabel = allVirtualSystemsLabel(children.length);
        return <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 0.5 }}>
          {vs0 && <JobButton size="small" variant="contained" data-failover-action aria-label={`${label} ${title}`} disabled={busy || rowBusy || !onFailover}
            onClick={() => onFailover?.(cluster.ref, row.unitId)} sx={{ textTransform: "none", whiteSpace: "nowrap" }}>{label}</JobButton>}
          {isChassis && row.sequenceAction && <JobButton size="small" variant="outlined" data-sequence-action={row.sequenceAction} title={ALL_VIRTUAL_SYSTEMS_TOOLTIP}
            aria-label={`${allLabel} ${title}`} disabled={busy || rowBusy || !onFailover}
            onClick={() => onFailover?.(cluster.ref, row.unitId)} sx={{ textTransform: "none", whiteSpace: "nowrap" }}>{allLabel}</JobButton>}
        </Box>;
      }
      if (state === "READINESS_ONLY") return <Typography variant="caption" data-failover-execution-disabled
        title={`${vendorSentence} (${row?.failoverRefusal ?? "FAILOVER_VENDOR_NOT_AVAILABLE"})`} sx={{ color: m3.onSurfaceVar, display: "inline-flex", alignItems: "center", gap: 0.5 }}>
        <Icon name="lock" size={14} />Readiness only</Typography>;
      if (state === "BLOCKED" || state === "INCIDENT") return <Button size="small" onClick={open} aria-label={`${state === "INCIDENT" ? "Open incident" : "See blockers"} ${title}`}
        sx={{ textTransform: "none", whiteSpace: "nowrap" }}>{state === "INCIDENT" ? "Open incident" : "See blockers"}</Button>;
      if (state === "UNKNOWN" && row && canRun && !unreadable) return <JobButton size="small" variant="outlined" aria-label={`Run pre-checks ${title}`} aria-busy={rowBusy} disabled={busy}
        onClick={() => onRun(row)} sx={{ textTransform: "none", whiteSpace: "nowrap" }}>Run pre-checks</JobButton>;
      return null;
    })();
    return <Fragment key={key}>
      <TableRow data-readiness-row={key} data-row-state={state} aria-label={title} tabIndex={0} onClick={open}
        onKeyDown={event => { if (event.target === event.currentTarget && event.key === "Enter") { event.preventDefault(); open(); } }}
        sx={{ minHeight: 48, cursor: "pointer", bgcolor: nested ? m3.scLow : m3.scLowest, '&:hover': { bgcolor: m3.sc }, '&:focus-visible': { outline: `2px solid ${m3.primary}`, outlineOffset: -2 }, '& > td, & > th': { py: 0.75, whiteSpace: "normal", overflowWrap: "anywhere", verticalAlign: "top" } }}>
        <TableCell><Chip size="small" icon={<Icon name={pill.icon} size={16} />} label={pill.label} variant={pill.outline ? "outlined" : "filled"}
          data-pill-outline={pill.outline ? "true" : undefined}
          sx={{ minWidth: "min-content", bgcolor: pill.outline ? "transparent" : PILL[pill.tone].bg, color: PILL[pill.tone].ink,
            ...(pill.outline ? { borderColor: PILL[pill.tone].ink } : {}), '& .MuiChip-label': { overflow: "visible", textOverflow: "clip" }, '& .MuiChip-icon': { color: "inherit" } }} /></TableCell>
        <TableCell component="th" scope="row" sx={{ pl: nested ? 4 : 2 }}>
          {!nested && children.length > 0 && <IconButton size="small" aria-label={`Virtual systems in ${cluster.title}`} aria-expanded={openVs.has(cluster.ref)}
            onClick={event => { event.stopPropagation(); toggleVs(cluster.ref); }}><span aria-hidden>{openVs.has(cluster.ref) ? "▾" : "▸"}</span></IconButton>}
          <Box component="span" sx={{ fontWeight: 600, mr: 1 }}>{title}</Box><VendorBadge vendor={vendor} />
          {isChassis && <Typography variant="caption" component="div" data-vsx-chassis sx={{ color: m3.onSurfaceVar }}
            title="The chassis is its own unit: its status and pre-checks are the chassis step; each Virtual System below has its own">
            VSX chassis{row?.chassisMode === "VSLS" ? " · VSLS" : row?.chassisMode === "HA" ? " · HA mode" : ""} · {chassisRollup(item.units)}</Typography>}
          {!isChassis && row?.mode && row.mode !== "UNKNOWN" && <Typography variant="caption" component="div" title={`Mode: ${row.mode}`}
            sx={{ color: m3.onSurfaceVar }}>{clusterModeLine(row.mode)}</Typography>}
        </TableCell>
        <TableCell title={`Roles observed: ${rolesAt ? formatStamp(rolesAt) : "Not observed"}`}>
          Active: {roleNames(isActiveRole, true)} <span aria-hidden>→</span> Standby: {roleNames(isStandbyRole, false)}
          {(fromReadiness || fgtRoles) && readinessRolesAt && <Typography component="span" variant="caption" data-role-source="readiness"
            sx={{ color: m3.onSurfaceVar }}> ({relativeAge(readinessRolesAt, now)})</Typography>}
          {hasInventoryRoles && <Typography component="span" variant="caption" data-role-source="inventory"
            sx={{ color: m3.onSurfaceVar }}> (inventory)</Typography>}
        </TableCell>
        <TableCell data-reason-line>
          {rowError && <Typography variant="caption" component="div" role={rowError.cancelled ? "status" : "alert"} data-run-notice={rowError.cancelled ? "neutral" : "error"}
            sx={{ color: rowError.cancelled ? m3.onSurfaceVar : m3.criticalInk, mb: 0.25 }}>{rowError.cancelled ? `Pre-checks not started. ${rowError.text}` : rowError.text}</Typography>}
          <Box component="span">
            {reason.title && <Box component="span" sx={{ fontWeight: 600 }}>{reason.title} – </Box>}{reason.headline}
            {reason.where && <Box component="span" data-reason-where={reason.where.kind} sx={{ color: reason.where.kind === "unidentified" ? m3.onSurfaceVar : undefined }}> – {reason.where.text}</Box>}
            {reason.warning && <Box component="span" sx={{ color: m3.onSurfaceVar }}> · 1 warning: {reason.warning}</Box>}
            {reason.more > 0 && <Chip size="small" label={`+${reason.more} more`} sx={{ ml: 0.75, height: 20, fontSize: 11 }} />}
            {reason.code && <Typography component="span" variant="caption" sx={{ color: m3.onSurfaceVar }}> · {reason.code}</Typography>}
          </Box>
        </TableCell>
        <TableCell onClick={event => event.stopPropagation()}>
          {at ? <>
            <Box component="time" dateTime={at} title={`${formatStamp(at)} · ${at}`}>{relativeAge(at, now)}</Box>
            {isPastFresh(at, now) && <Tooltip title="Evaluated more than 24 h ago; the status may no longer hold. The failover repeats every pre-check before it switches.">
              <Chip size="small" label="Stale" data-stale-evaluation sx={{ ml: 0.75, height: 20, fontSize: 11, bgcolor: m3.warningContainer, color: m3.onWarningContainer }} />
            </Tooltip>}
          </> : "Not evaluated"}
          {rowBusy && <Typography component="span" variant="caption" role="status" sx={{ ml: 0.75 }}><CircularProgress size={12} aria-label="Running pre-checks" /> checking</Typography>}
          {row && canRun && state !== "UNKNOWN" && <Tooltip title="Re-check this unit"><span><JobButton size="small" aria-label={`Re-check ${title}`} aria-busy={rowBusy} disabled={busy}
            onClick={() => onRun(row)} sx={{ color: m3.primary, minWidth: 0, ml: 0.5 }}><Icon name="operations" size={16} /></JobButton></span></Tooltip>}
          {row && progress[row.unitId] && <Typography component="span" role="status" variant="caption" sx={{ ml: 0.75 }}>{progress[row.unitId]}</Typography>}
        </TableCell>
        <TableCell onClick={event => event.stopPropagation()}>{action}</TableCell>
      </TableRow>
      {!nested && openVs.has(cluster.ref) && children.map(unit => renderRow(item, unit, true))}
    </Fragment>;
  };
  const segmentButton = (id: Segment, label: string) => {
    const n = counts[id];
    return <Tooltip key={id} title={`${CLUSTER_COUNT_NOTE}.`}>
      <Button size="small" aria-label={`${label}: ${n} clusters`} aria-pressed={segment === id} onClick={() => setChoice(id)}
        sx={{ textTransform: "none", px: 1.5, borderRadius: 2, bgcolor: segment === id ? m3.secondaryContainer : "transparent",
          color: segment === id ? m3.onSecondaryContainer : m3.onSurfaceVar, border: `1px solid ${segment === id ? m3.primary : m3.outlineVar}` }}>
        {label} · {n}</Button>
    </Tooltip>;
  };
  return <Box className="ha-readiness-full-width" sx={{ width: "100%", minWidth: 0 }}>
    <Typography variant="subtitle1" sx={{ mb: 1 }}>{clusters.length} clusters enrolled</Typography>
    {summaryFailed && <Box role="status" data-readiness-summary-failed sx={{ mb: 1.5, px: 1.5, py: 1, borderRadius: 1, bgcolor: m3.warningContainer, color: m3.onWarningContainer, fontSize: 14 }}>
      Readiness could not be read. Statuses below are unknown, not bad.</Box>}
    {/* A notice, not an Alert: the status values are unchanged and nothing failed to load. */}
    {mostlyStale && <Box role="status" data-readiness-stale-notice sx={{ mb: 1.5, px: 1.5, py: 1, borderRadius: 1,
      bgcolor: m3.warningContainer, color: m3.onWarningContainer, fontSize: 14 }}>
      {staleAts.length === evaluatedAts.length
        ? `Readiness was last evaluated ${relativeAge(newestStale, now)}; a failover repeats every pre-check, but run a re-check before relying on Ready.`
        : `${staleAts.length} of ${evaluatedAts.length} readiness evaluations are older than 24 h (the newest of them ${relativeAge(newestStale, now)}); a failover repeats every pre-check, but run a re-check before relying on Ready.`}
      <Box component="span" sx={{ ml: 1.5, display: "inline-block" }}>{runCheckButton("stale-run-readiness")}</Box>
    </Box>}
    {!settled ? <StatePanel variant="empty" title="Reading readiness and open incidents…" /> : <>
    <Box role="group" aria-label="Readiness segments" sx={{ display: "flex", flexWrap: "wrap", gap: 1, alignItems: "center", mb: 1.5 }}>
      {SEGMENTS.map(({ id, label }) => segmentButton(id, label))}
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
      <DialogTitle id="run-readiness-title">Re-check {runnableVisible.length} clusters?</DialogTitle>
      <DialogContent>
        <Typography variant="body2">
          A read-only pre-check, one cluster at a time, inside the job window. Each result replaces the previous one for that cluster; nothing is changed on a device.
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => setConfirmRun(false)}>Cancel</Button>
        <Button variant="contained" onClick={() => { setConfirmRun(false); onBulkRun(runnableVisible); }}>Re-check</Button>
      </DialogActions>
    </Dialog>
    {runError && runError.unitId === null && <Box sx={{ mb: 1 }}><StatePanel variant={runError.cancelled ? "empty" : "error"} title={runError.text} /></Box>}
    <TableContainer sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 2 }}>
      <Table size="small" aria-label="HA clusters" sx={{ width: "100%", tableLayout: "fixed" }}>
        <TableHead><TableRow>
          <TableCell sx={{ width: 140 }}>Status</TableCell><TableCell sx={{ width: "22%" }}>Cluster</TableCell>
          <TableCell sx={{ width: "22%" }}>Roles</TableCell>
          <TableCell>Reason</TableCell>
          <TableCell sx={{ width: 150 }}>Checked</TableCell><TableCell sx={{ width: 150 }}>Action</TableCell>
        </TableRow></TableHead>
        <TableBody>{supported.map(({ item }) => renderRow(item, item.base, false))}</TableBody>
      </Table>
    </TableContainer>
    {unsupported.length > 0 && <Box component="details" open={showUnsupported} onToggle={e => setShowUnsupported(e.currentTarget.open)} sx={{ mt: 1.5, border: `1px solid ${m3.outlineVar}`, borderRadius: 2, p: 1.5, bgcolor: m3.scLow }}>
      <Box component="summary" sx={{ cursor: "pointer", color: m3.onSurfaceVar, fontWeight: 600 }}>Not supported · {unsupported.length} clusters</Box>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, my: 1 }}>Readiness pre-checks are not supported for these vendors; enrolled clusters are shown for inventory context.</Typography>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0 }}>{unsupported.map(({ item: { cluster, vendor, units } }) => {
        const listedOnly = vendor === "cisco_asa" || units.some(unit => unit.readinessAvailable === false);
        const pair = units.find(unit => unit.haView);
        return <Box component="li" key={cluster.ref} sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap", py: 0.75 }}><Typography variant="body2">{cluster.title}</Typography><VendorBadge vendor={vendor} />{listedOnly && <Typography variant="body2">{vendorDisplayName(vendor)}</Typography>}{listedOnly
          ? <><Typography variant="caption" title="FAILOVER_VENDOR_NOT_AVAILABLE">Readiness not available yet</Typography>{pair && <HaPairLine row={pair} />}</>
          : <Typography variant="caption" title="UNSUPPORTED_MODE"><Presented label={readinessReasonLabel("UNSUPPORTED_MODE")} raw="UNSUPPORTED_MODE" /> · Readiness: not evaluated</Typography>}</Box>;
      })}</Box>
    </Box>}
    {visible.length === 0 && (segment === "can" && !query && !vendorFilter
      ? <StatePanel variant="empty" title="No cluster can be failed over right now"
          body={<>{counts.blocked} blocked, {counts.unknown} unknown.{" "}
            <Button size="small" onClick={() => setChoice("blocked")}>Show blocked</Button><Button size="small" onClick={() => setChoice("unknown")}>Show unknown</Button></>} />
      : <StatePanel variant="empty" title="No clusters match these filters" />)}
    </>}
  </Box>;
}

function clusterModeLine(mode: string) {
  const word = clusterModeWord(mode);
  return `Mode: ${word}`;
}
