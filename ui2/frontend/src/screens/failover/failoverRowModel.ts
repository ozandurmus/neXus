/**
 * The HA & failover list and detail, as pure derivations (HA & failover screen redesign, REDESIGN 3.1 / 3.3 / 4).
 * No React, no I/O. Every word here is derived from fields the server already sends; nothing here decides who may
 * start, approve or run anything (the server's `canStart`, `canApprove`, `refusalReason` ... are rendered as sent).
 */
import type { CpFailoverSummary, DeviceSummary, FailoverIncident, ReadinessCheck, ReadinessMember } from "../../auth/adminApi";
import { readinessReasonLabel } from "../../shell/presenters";
import { formatStamp } from "../../shell/time";
import { configSyncSettlingText, panConfigSyncPhase } from "../PanConfigSyncAction";
import { SECONDARY_NOT_ACTIVE, TRAFFIC_ARP_CHECK, checkRows, isActiveRole, isStandbyRole, observedRoles, primaryReportsPeer,
  primaryRolesConfirmed, readinessStopReason } from "../ReadinessChecksTable";

export type Status = "READY" | "NOT_READY" | "UNKNOWN" | "UNSUPPORTED";
type Cluster = { ref: string; title: string; members: DeviceSummary[] };
/** The shape `haReadinessItems` returns; only what the row state needs. */
export type ListItem = { cluster: Cluster; units: CpFailoverSummary[]; base?: CpFailoverSummary; vendor?: string; status: Status };

/**
 * The display state of one list row. `RUNNING` and `AWAITING` are part of the vocabulary but are not derived in this
 * version: the summary's `lastRun*` fields are read from every run of the unit including READINESS runs, so a running
 * pre-check would be shown as a running failover (REDESIGN open point 4), and a pending two-person request is not on the
 * summary row. They stay out until the server carries them (REDESIGN section 10, A1).
 */
export type RowState = "INCIDENT" | "RUNNING" | "AWAITING" | "READINESS_ONLY" | "CAN_FAIL_OVER" | "BLOCKED" | "UNKNOWN" | "UNSUPPORTED";

/** Failover execution is not offered for these vendors (readiness only). Re-exported by `HaReadinessList` under the same names. */
export function failoverVendorLabel(vendor?: string): string {
  return vendor === "cisco_asa" ? "Cisco ASA" : vendor === "fortinet" ? "FortiGate" : vendor === "palo_alto" ? "Palo Alto" : "Check Point";
}
export function failoverExecutionDisabled(vendor?: string): boolean {
  return vendor === "cisco_asa" || vendor === "fortinet";
}

const isNested = (row?: { unitId: string; clusterId: string }) => Boolean(row && row.unitId !== row.clusterId);

/** The reason a row that would otherwise be Ready shows Unknown when the open incidents could not be read. */
export const INCIDENTS_UNREADABLE = "incidents could not be read";

/**
 * First match wins (REDESIGN 3.1). A chassis is judged by its own row; its virtual systems never decide it.
 * `incidentsFailed`: the incidents read failed, so "no incident" is not known: a row that would be `CAN_FAIL_OVER` is `UNKNOWN`
 * (never Ready, no Fail over action). Rows already blocked, refused or readiness-only stay as they are; they offer no failover.
 */
export function rowState(item: Pick<ListItem, "cluster" | "vendor" | "status">, row: CpFailoverSummary | undefined,
    incidents: readonly FailoverIncident[], summaryFailed: boolean, incidentsFailed = false): RowState {
  const executable = !failoverExecutionDisabled(item.vendor);
  // An estate-wide unresolved send fences every failover that can be executed; a readiness-only pair is not fenced by it.
  if (incidents.some(i => (executable && i.blocksEstate) || i.cluster_member_ref === item.cluster.ref || (row && i.clusterId === row.clusterId)))
    return "INCIDENT";
  if (summaryFailed) return "UNKNOWN";
  const status: Status = isNested(row) ? row?.readiness?.status ?? "UNKNOWN" : item.status;
  if (status === "UNSUPPORTED") return "UNSUPPORTED";
  if (!executable) return "READINESS_ONLY";
  if (status === "READY") {
    if (row?.refusalReason) return "BLOCKED";
    // PO 2026-11-23: VS0 and "Fail over all virtual systems" are independent chassis actions; the row is blocked only when neither is offered.
    if (row?.unitKind === "VSX_CHASSIS") { if (!chassisVs0Offered(row) && !row.sequenceAction) return "BLOCKED"; }
    else if (row?.failoverRefusal) return "BLOCKED";
    return incidentsFailed ? "UNKNOWN" : "CAN_FAIL_OVER";
  }
  return status === "NOT_READY" ? "BLOCKED" : "UNKNOWN";
}
/** True for an Unknown row whose only reason is the unreadable incidents (its readiness itself is Ready). */
export function incidentsUnreadableRow(state: RowState, status: Status, summaryFailed: boolean, incidentsFailed: boolean): boolean {
  return incidentsFailed && !summaryFailed && state === "UNKNOWN" && status === "READY";
}

/** The order of the "All" segment (REDESIGN 3.1). */
export const STATE_ORDER: readonly RowState[] = ["INCIDENT", "RUNNING", "AWAITING", "BLOCKED", "UNKNOWN", "CAN_FAIL_OVER", "READINESS_ONLY", "UNSUPPORTED"];

export type Segment = "can" | "blocked" | "unknown" | "readiness-only" | "all";
export const SEGMENTS: ReadonlyArray<{ id: Segment; label: string }> = [
  { id: "can", label: "Can fail over now" }, { id: "blocked", label: "Blocked" }, { id: "unknown", label: "Unknown" },
  { id: "readiness-only", label: "Readiness only" }, { id: "all", label: "All" }];
/** An open incident is a reason the cluster is blocked, so it is listed under Blocked. */
export function inSegment(state: RowState, segment: Segment): boolean {
  switch (segment) {
    case "can": return state === "CAN_FAIL_OVER";
    case "blocked": return state === "BLOCKED" || state === "INCIDENT";
    case "unknown": return state === "UNKNOWN";
    case "readiness-only": return state === "READINESS_ONLY";
    default: return true;
  }
}
/** Counts per segment over the top-level rows; a chassis counts by its own state only. `all` is every row. */
export function segmentCounts(rows: ReadonlyArray<{ state: RowState }>): Record<Segment, number> {
  const counts: Record<Segment, number> = { can: 0, blocked: 0, unknown: 0, "readiness-only": 0, all: rows.length };
  for (const { state } of rows) for (const s of ["can", "blocked", "unknown", "readiness-only"] as const) if (inSegment(state, s)) counts[s]++;
  return counts;
}
/** `?readiness=` from the Overview to the segment it opens on. */
export function segmentFromReadiness(value: string | null | undefined): Segment | null {
  return value === "NOT_READY" ? "blocked" : value === "READY" ? "can" : value === "UNKNOWN" ? "unknown" : null;
}

/** The optional chassis action (PO 2026-11-23): writes are fenced one at a time fleet-wide, so the VS runs follow each other. */
export const ALL_VIRTUAL_SYSTEMS_TOOLTIP = "Fails over every virtual system of this chassis one after another, in VSID order, each in its own run; "
  + "never in parallel, because only one failover write runs at a time across the fleet. VS0 is not part of it.";
/** The chassis's own run (VS0, HA or VSLS mode): offered when the server names an action and gives no refusal. */
export const chassisVs0Offered = (row: Pick<CpFailoverSummary, "chassisAction" | "failoverRefusal">) => Boolean(row.chassisAction) && !row.failoverRefusal;
/** The label of the row's failover button (REDESIGN section 7); a chassis row's button is its VS0 run, the nested row of a chassis is one virtual system. */
export function failoverButtonLabel(row: Pick<CpFailoverSummary, "unitKind" | "unitId" | "clusterId"> | undefined): string {
  if (row?.unitKind === "VSX_CHASSIS") return "Fail over VS0";
  return isNested(row) ? "Fail over VS" : "Fail over";
}
/** The chassis's secondary action: every virtual system in turn, with the count it covers. */
export const allVirtualSystemsLabel = (virtualSystems: number) => `Fail over all VS (${virtualSystems})`;
/** "5 virtual systems: 4 ready, 1 not ready" - each says what it counts; unknown ones are never folded into ready. */
export function chassisRollup(units: readonly CpFailoverSummary[]): string {
  const vs = units.filter(unit => isNested(unit));
  const ready = vs.filter(unit => unit.readiness?.status === "READY").length;
  const notReady = vs.filter(unit => unit.readiness?.status === "NOT_READY").length;
  const unknown = vs.length - ready - notReady;
  return `${vs.length} virtual ${vs.length === 1 ? "system" : "systems"}: ${ready} ready, ${notReady} not ready${unknown ? `, ${unknown} unknown` : ""}`;
}

// ---- the reason line ---------------------------------------------------------------------------------------------

const ISO_INSTANT = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})/g;
const COUNT_UNAVAILABLE = "Count unavailable, Count unavailable, below tolerance";
export const SESSION_CONTINUITY_NOT_MEASURED = "Not evaluated - session continuity is not measured";
const escapeRegex = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const readable = (text: string) => text.replace(ISO_INSTANT, instant => formatStamp(instant));

/**
 * The server's summary in two parts: the headline (up to the first `;`, else the first ` (`, a leading repeat of the check
 * title removed, machine timestamps in display time) and the rest. The one known catch-all text is rewritten in words.
 */
export function splitSummary(title: string, summary: string | null | undefined): { headline: string; detail: string } {
  const text = (summary ?? "").trim();
  if (text === COUNT_UNAVAILABLE) return { headline: SESSION_CONTINUITY_NOT_MEASURED, detail: "" };
  let cut = text.indexOf(";");
  if (cut < 0) cut = text.indexOf(" (");
  let headline = cut > 0 ? text.slice(0, cut).trim() : text;
  const detail = cut > 0 ? text.slice(cut + 1).trim() : "";
  const repeated = new RegExp(`^${escapeRegex(title.trim())}\\s*[:\\u2013\\u2014-]?\\s*`, "i").exec(headline);
  if (title.trim() && repeated && headline.length > repeated[0].length) headline = headline.slice(repeated[0].length);
  return { headline: readable(headline), detail: readable(detail) };
}

export type ReasonLine = {
  title?: string; headline: string; where?: { text: string; kind: "member" | "both" | "unidentified" };
  /** Other blocking checks beyond the lead one. */
  more: number; code?: string; /** READY rows: the first warning, shown beside the passed count. */ warning?: string;
};
export const MEMBER_NOT_IDENTIFIED = "member not identified by this check";

const FAILING = ["FAIL", "UNKNOWN"];
/** The lead blocking check: the first FAIL, else the first UNKNOWN (the order the list always used). */
function leadCheck(checks: readonly ReadinessCheck[]) {
  return checks.find(c => c.blocking && c.result === "FAIL") ?? checks.find(c => c.blocking && c.result === "UNKNOWN");
}
const memberOf = (check: ReadinessCheck, members: readonly ReadinessMember[]) =>
  check.device_id ? members.find(m => m.device_id === check.device_id)
    : check.member === "Member 1" ? members[0] : check.member === "Member 2" ? members[1] : undefined;
const roleWord = (role: string | null | undefined) => isActiveRole(role) ? "active" : isStandbyRole(role) ? "standby" : (role ?? "").toLowerCase();

function whereOf(lead: ReadinessCheck, checks: readonly ReadinessCheck[], members: readonly ReadinessMember[], vendor: string | undefined,
    observedAt: string | null | undefined): ReasonLine["where"] {
  // A single-address FortiGate is read through one device: its rows say nothing about which member differs.
  if (primaryReportsPeer(vendor, [...members])) return undefined;
  const rows = checks.filter(c => c.checkNo === lead.checkNo);
  const unidentified = { text: MEMBER_NOT_IDENTIFIED, kind: "unidentified" as const };
  const named = (check: ReadinessCheck) => {
    const member = memberOf(check, members);
    if (!member) return unidentified;
    const role = roleWord(observedRoles([...checks], [...members], observedAt).get(member.device_id)?.role ?? member.ha_role);
    return { text: `on ${member.hostname ?? "Unknown member"}${role ? ` (${role})` : ""}`, kind: "member" as const };
  };
  // Palo Alto config sync: the row itself says which member has it off.
  const synced = rows.filter(r => r.memberSync !== undefined);
  if (synced.length > 0) {
    const off = synced.filter(r => r.memberSync === "off");
    if (off.length === 1) return named(off[0]);
    if (off.length > 1) return { text: "on both members", kind: "both" };
  }
  if (rows.length < 2) return unidentified;
  const failing = rows.filter(r => r.result === lead.result);
  if (failing.length === 1) return named(failing[0]);
  if (failing.length >= 2) return new Set(failing.map(r => (r.summary ?? "").trim())).size === 1 ? unidentified : { text: "on both members", kind: "both" };
  return unidentified;
}

function leadLine(lead: ReadinessCheck, row: CpFailoverSummary, members: readonly ReadinessMember[]): ReasonLine {
  const checks = row.readiness?.checks ?? [];
  const others = new Set(checks.filter(c => c.blocking && FAILING.includes(c.result) && c.checkNo !== lead.checkNo).map(c => c.checkNo));
  return { title: lead.title, headline: splitSummary(lead.title, lead.summary).headline || "Blocking condition observed",
    where: whereOf(lead, checks, members, row.vendor, row.readiness?.observedAt), more: others.size };
}
const GENERIC_REFUSALS = ["CHECK_NOT_READY", "READINESS_NOT_READY"];

/** The one line that answers "what is wrong, on which member" (REDESIGN 3.3). `members` are the row's role members (masked labels). */
export function reasonLine(row: CpFailoverSummary | undefined, members: readonly ReadinessMember[] = []): ReasonLine {
  const withCode = (code: string): ReasonLine => ({ headline: readinessReasonLabel(code), more: 0, code });
  if (!row) return { headline: "Never checked", more: 0 };
  if (!row.readiness) return row.refusalReason ? withCode(row.refusalReason) : { headline: "Never checked", more: 0 };
  const sync = panConfigSyncPhase(row);
  if (sync.phase === "SETTLING") return { headline: configSyncSettlingText(sync.requestedAt), more: 0 };
  const checks = row.readiness.checks ?? [];
  const lead = leadCheck(checks);
  if (sync.phase === "STILL_FAILS" && (!lead || lead.checkNo === 4)) return { headline: "Configuration sync still fails", more: 0 };
  const refusal = row.refusalReason || (failoverExecutionDisabled(row.vendor) ? null : row.failoverRefusal) || null;
  const generic = !row.refusalReason || GENERIC_REFUSALS.includes(row.refusalReason) || readinessReasonLabel(row.refusalReason) === row.refusalReason;
  if (lead && row.readiness.status !== "READY" && generic) return leadLine(lead, row, members);
  if (refusal) return withCode(refusal);
  const stop = readinessStopReason(row.readiness.stopCode);
  if (row.readiness.status === "READY") {
    const blockingNos = [...new Set(checks.filter(c => c.blocking).map(c => c.checkNo))];
    const passed = blockingNos.filter(no => !checks.some(c => c.checkNo === no && (FAILING.includes(c.result) || c.result === "NOT_EVALUATED"))).length;
    // A Ready row read "9 of 10 blocking checks passed" when one check was not evaluated, as if one had failed (PO 2026-11-24).
    const notEvaluated = blockingNos.filter(no => checks.some(c => c.checkNo === no && c.result === "NOT_EVALUATED")
      && !checks.some(c => c.checkNo === no && FAILING.includes(c.result))).length;
    const warn = checks.find(c => c.result === "WARN");
    return { headline: blockingNos.length ? `${passed} of ${blockingNos.length} blocking checks passed${notEvaluated ? ` · ${notEvaluated} not evaluated` : ""}` : "No blocking conditions observed",
      more: 0, ...(warn ? { warning: warn.title } : {}) };
  }
  if (stop) return { headline: `Pre-checks could not finish: ${stop}`, more: 0, code: row.readiness.stopCode ?? undefined };
  if (row.readiness.status === "NOT_READY") return { headline: row.readiness.failedCheck || "Blocking conditions observed", more: 0 };
  return { headline: "Readiness has not been established", more: 0 };
}

// ---- checks by consequence ---------------------------------------------------------------------------------------

export type CheckCell = { memberLabel: string; role: string; headline: string; detail: string; failed: boolean };
export type CheckEntry = { no: number; title: string; cells: [CheckCell, CheckCell]; /** Both members report the same text: one merged cell. */ same: boolean; perMember: boolean };
export type CheckGroups = { blocking: CheckEntry[]; unknown: CheckEntry[]; warnings: CheckEntry[]; passed: CheckEntry[]; info: CheckEntry[] };

/** The two member columns, active first: the readiness roles when they name every member, else the inventory roles (as the table). */
export function memberColumns(checks: ReadinessCheck[], members: ReadinessMember[], vendor?: string, observedAt?: string | null) {
  const peer = primaryReportsPeer(vendor, members);
  const singleObserverFgt = !peer && vendor === "fortinet" && members.length === 2
    && new Set(checks.map(c => c.device_id ?? c.member).filter(Boolean)).size === 1;
  const observed = observedRoles(checks, members, observedAt, vendor);
  const fromReadiness = members.length > 0 && members.every(member => observed.has(member.device_id));
  const roleOf = (member?: ReadinessMember) => member ? (fromReadiness ? observed.get(member.device_id)!.role : member.ha_role?.toLowerCase()) : undefined;
  const ordered = [...members].sort((a, b) => Number(!isActiveRole(roleOf(a))) - Number(!isActiveRole(roleOf(b))));
  const memberOrder = [0, 1].map(index => members.length ? members.indexOf(ordered[index]) : index);
  const confirmed = peer && primaryRolesConfirmed(checks);
  const columns = peer
    ? [0, 1].map(index => ({ label: index === 0 ? members[0].hostname ?? "Primary" : "Secondary",
        role: `${confirmed ? (index === 0 ? "active" : "standby") : "role unknown"} (as the primary reports)` }))
    : [0, 1].map(index => ({ label: ordered[index]?.hostname ?? `Member ${index + 1}`, role: roleOf(ordered[index]) ?? "role unknown" }));
  return { columns, memberOrder, peerFromPrimary: peer || singleObserverFgt };
}

/**
 * Every check number lands in exactly one group, so the five counts add up to the number of checks:
 * blocking (a blocking check with a FAIL), warnings (any WARN, not failed), passed (blocking, every member PASS),
 * unknown (blocking, neither failed nor passed, or WARN on one member and not established on the other: never a pass), information (the rest, not blocking).
 */
export function groupChecks(checks: ReadinessCheck[], members: ReadinessMember[] = [], vendor?: string, observedAt?: string | null): CheckGroups {
  const { columns, memberOrder, peerFromPrimary } = memberColumns(checks, members, vendor, observedAt);
  const groups: CheckGroups = { blocking: [], unknown: [], warnings: [], passed: [], info: [] };
  for (const row of checkRows(checks, members, peerFromPrimary)) {
    const cells = [0, 1].map(index => {
      const source = peerFromPrimary ? row.members[0] : row.members[memberOrder[index]];
      const text = peerFromPrimary && index === 1 && row.no === TRAFFIC_ARP_CHECK ? SECONDARY_NOT_ACTIVE : source?.summary ?? "Not collected";
      const failed = row.blocking && (row.perMember ? source?.memberSync === "off"
        : !(peerFromPrimary && index === 1 && row.no === TRAFFIC_ARP_CHECK) && source?.result === "FAIL");
      return { text, cell: { memberLabel: columns[index].label, role: columns[index].role, ...splitSummary(row.title, text), failed } };
    });
    const entry: CheckEntry = { no: row.no, title: row.title, cells: [cells[0].cell, cells[1].cell],
      same: !row.perMember && cells[0].text === cells[1].text, perMember: row.perMember };
    const warned = row.members.some(member => member?.result === "WARN");
    // A blocking check that is not established on one member (UNKNOWN / NOT_EVALUATED) is never softened by the other member's WARN.
    const unestablished = row.blocking && row.members.some(member => member?.result === "UNKNOWN" || member?.result === "NOT_EVALUATED");
    if (row.failed) groups.blocking.push(entry);
    else if (warned && !unestablished) groups.warnings.push(entry);
    else if (warned) groups.unknown.push(entry);
    else if (row.blocking) (row.passed ? groups.passed : groups.unknown).push(entry);
    else groups.info.push(entry);
  }
  return groups;
}
