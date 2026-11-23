import type { DeviceInventory, GridMemberView, GridSummaryView } from "../auth/adminApi";
import type { SourceRead } from "../screens/overview/attentionRules";
import type { BadgeState } from "./estateModel";
import { plural } from "../shell/plural";

/**
 * Pure model for the Estate dossier "Grid members" block (Infoblox Grid Manager units).
 *
 * Evidence law: the member list and summary come from the unit's stored inventory read, the same read the dossier
 * already holds. A failed or still-loading read draws nothing here. A null field is UNKNOWN, never OK and never zero.
 * Member addresses and serials are never rendered; the member name is the masked `virtual_system` as the API returns it.
 */

/** Member rows printed before the "+N more" footer. */
export const GRID_MEMBER_ROW_CAP = 6;

/** The standard device view. Read-only Estate links only ever point here, with `standard=1`. */
export function gridStandardHref(deviceId: string): string {
  return `/devices/${encodeURIComponent(deviceId)}?standard=1`;
}

export const UNKNOWN_WORD = "UNKNOWN";

export interface GridSummaryLine {
  /** What the number counts, spelled out in the label. */
  readonly label: string;
  /** The count as shown (`≥3` when the vendor reports a lower bound), or UNKNOWN. */
  readonly value: string;
}

export interface GridMemberRow {
  readonly name: string;
  readonly role: "Grid Master" | "Master candidate" | "Member";
  /** HA status word as reported, `HA off` when HA is disabled, or UNKNOWN. */
  readonly ha: string;
  /** Node status word as reported, or UNKNOWN. */
  readonly node: string;
  /** Resource percentages as `NN%`, or UNKNOWN per field. */
  readonly disk: string;
  readonly memory: string;
  readonly cpu: string;
  readonly db: string;
  /** Services as `DNS WORKING` style pairs; an empty list means none was reported. */
  readonly services: readonly string[];
}

export type GridBlockModel =
  | { readonly drawn: false }
  | {
      readonly drawn: true;
      readonly badge: BadgeState;
      readonly badgeWord: string;
      readonly headline: string;
      /** True when the read ran but carried no member or count: shown as "No grid member evidence", never as zero. */
      readonly noEvidence: boolean;
      /**
       * False when the member list was not collected (`grid_members` absent) though the summary carries counts. The
       * member counts are then UNKNOWN and are not printed; the summary lines still are. True when the list was collected.
       */
      readonly membersKnown: boolean;
      readonly memberCount: number;
      readonly gridMasterCount: number;
      readonly masterCandidateCount: number;
      readonly haEnabledCount: number;
      readonly summaryLines: readonly GridSummaryLine[];
      readonly rows: readonly GridMemberRow[];
      readonly hiddenCount: number;
      /** The inventory collected-at time: the evidence age for this block. Null when never collected. */
      readonly evidenceAt: string | null;
      readonly evidenceSource: "inventory";
    };

/** Not-collected wording shared with the standard view's empty state. */
export const GRID_NOT_COLLECTED_BODY =
  "The member list is read during Grid Manager inventory; none has been recorded yet. Not collected is not the same as zero members.";

function pct(value: number | null | undefined): string {
  return typeof value === "number" && Number.isFinite(value) ? `${value}%` : UNKNOWN_WORD;
}

function word(value: string | null | undefined): string {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : UNKNOWN_WORD;
}

function rowOf(member: GridMemberView): GridMemberRow {
  const role: GridMemberRow["role"] = member.grid_master
    ? "Grid Master"
    : member.master_candidate
      ? "Master candidate"
      : "Member";
  const ha = member.ha_enabled === true ? word(member.ha_status) : member.ha_enabled === false ? "HA off" : UNKNOWN_WORD;
  return {
    name: word(member.virtual_system),
    role,
    ha,
    node: word(member.node_status),
    disk: pct(member.disk_percent),
    memory: pct(member.memory_percent),
    cpu: pct(member.cpu_percent),
    db: pct(member.db_percent),
    services: (member.services ?? []).map((s) => `${word(s.service)} ${word(s.status)}`),
  };
}

function countOf(value: number | null, atLeast: boolean, one: string, many: string): string {
  if (typeof value !== "number" || !Number.isFinite(value)) return UNKNOWN_WORD;
  return `${atLeast ? "≥" : ""}${plural(value, one, many)}`;
}

/** Summary lines in the standard view's order; each label says what the number counts. Null counts are UNKNOWN. */
export function summaryLinesOf(summary: GridSummaryView | null | undefined): readonly GridSummaryLine[] {
  if (!summary) return [];
  return [
    {
      label: "DNS views (count of views)",
      value: countOf(summary.dns_views, summary.dns_views_at_least, "view", "views"),
    },
    {
      label: "Authoritative DNS zones (count of zones)",
      value: countOf(summary.auth_zones, summary.auth_zones_at_least, "zone", "zones"),
    },
    {
      label: "DHCP networks (count of networks)",
      value: countOf(summary.dhcp_networks, summary.dhcp_networks_at_least, "network", "networks"),
    },
    {
      label: "DHCP ranges (count of ranges)",
      value: countOf(summary.dhcp_ranges, summary.dhcp_ranges_at_least, "range", "ranges"),
    },
  ];
}

/**
 * Block model for one unit. Not drawn while the inventory read is loading or failed, or when the read carries neither
 * members nor a summary (the standard view's own "No grid member evidence" condition). When the summary object exists
 * but holds no members and no counts, the block is drawn with the not-collected wording, never as zero members.
 */
export function buildGridBlockModel(inventory: SourceRead<DeviceInventory> | "loading" | null | undefined): GridBlockModel {
  if (!inventory || inventory === "loading" || inventory.state !== "ok") return { drawn: false };
  const inv = inventory.value;
  const membersCollected = Array.isArray(inv.grid_members);
  const members = membersCollected ? inv.grid_members : [];
  const summary = inv.grid_summary ?? null;
  if (members.length === 0 && summary === null) return { drawn: false };

  const summaryLines = summaryLinesOf(summary);
  const summaryHasCount = summaryLines.some((l) => l.value !== UNKNOWN_WORD);
  const noEvidence = members.length === 0 && !summaryHasCount;
  const rows = members.map(rowOf);
  const gridMasterCount = members.filter((m) => m.grid_master).length;
  const masterCandidateCount = members.filter((m) => m.master_candidate && !m.grid_master).length;
  const haEnabledCount = members.filter((m) => m.ha_enabled === true).length;

  if (noEvidence) {
    return {
      drawn: true,
      badge: "unk",
      badgeWord: UNKNOWN_WORD,
      headline: "No grid member evidence",
      noEvidence: true,
      membersKnown: membersCollected,
      memberCount: 0,
      gridMasterCount: 0,
      masterCandidateCount: 0,
      haEnabledCount: 0,
      summaryLines,
      rows: [],
      hiddenCount: 0,
      evidenceAt: inv.collected_at ?? null,
      evidenceSource: "inventory",
    };
  }

  if (!membersCollected) {
    // Summary counts exist but the member list was never collected: UNKNOWN, never a zero member count.
    return {
      drawn: true,
      badge: "unk",
      badgeWord: UNKNOWN_WORD,
      headline: "Grid member list not collected (UNKNOWN)",
      noEvidence: false,
      membersKnown: false,
      memberCount: 0,
      gridMasterCount: 0,
      masterCandidateCount: 0,
      haEnabledCount: 0,
      summaryLines,
      rows: [],
      hiddenCount: 0,
      evidenceAt: inv.collected_at ?? null,
      evidenceSource: "inventory",
    };
  }

  return {
    drawn: true,
    badge: "na",
    badgeWord: "STORED",
    headline: `${plural(members.length, "grid member")} listed in the stored inventory`,
    noEvidence: false,
    membersKnown: true,
    memberCount: members.length,
    gridMasterCount,
    masterCandidateCount,
    haEnabledCount,
    summaryLines,
    rows: rows.slice(0, GRID_MEMBER_ROW_CAP),
    hiddenCount: Math.max(0, rows.length - GRID_MEMBER_ROW_CAP),
    evidenceAt: inv.collected_at ?? null,
    evidenceSource: "inventory",
  };
}
