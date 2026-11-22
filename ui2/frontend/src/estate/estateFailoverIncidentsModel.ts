import type { FailoverIncident } from "../auth/adminApi";
import type { BadgeState } from "./estateModel";

/** Rows shown before the list is capped; the rest are counted in "+N more". */
export const FAILOVER_INCIDENT_CAP = 10;

export interface IncidentStatus {
  readonly state: BadgeState;
  readonly word: string;
}

/**
 * Status of one incident as read from the standard endpoint. Only an explicit boolean
 * decides the word; anything else (missing field, string, null) is UNKNOWN, never OK.
 */
export function incidentStatusOf(incident: Pick<FailoverIncident, "incidentActive"> | null | undefined): IncidentStatus {
  const active = incident?.incidentActive;
  if (active === true) return { state: "act", word: "Incident open" };
  if (active === false) return { state: "watch", word: "Unresolved send" };
  return { state: "unk", word: "UNKNOWN" };
}

export interface FailoverIncidentRow {
  readonly key: string;
  readonly label: string;
  readonly status: IncidentStatus;
  readonly openedAt: string | null;
  readonly blocksEstate: boolean;
}

export interface FailoverIncidentsModel {
  /** Every incident in the successful read (not only the shown rows). */
  readonly total: number;
  readonly openCount: number;
  readonly unresolvedCount: number;
  readonly unknownCount: number;
  readonly rows: readonly FailoverIncidentRow[];
  /** Incidents not shown because of the cap. */
  readonly moreCount: number;
}

const RANK: Readonly<Record<BadgeState, number>> = { act: 0, unk: 1, watch: 2, ok: 3, na: 4 };

function openedMs(iso: string | null): number {
  if (!iso) return Number.NEGATIVE_INFINITY;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? Number.NEGATIVE_INFINITY : ms;
}

/**
 * Groups and orders incidents: open first, then UNKNOWN, then unresolved sends; newest
 * first inside each group. `labelOf` resolves the Estate unit label (never a raw host name).
 */
export function buildFailoverIncidentsModel(
  incidents: readonly FailoverIncident[],
  labelOf: (incident: FailoverIncident) => string,
): FailoverIncidentsModel {
  const items = incidents.map((incident, index) => {
    const status = incidentStatusOf(incident);
    const openedAt = typeof incident.openedAt === "string" && incident.openedAt.trim() ? incident.openedAt : null;
    return {
      key: `${incident.incidentRef ?? "incident"}:${index}`,
      label: labelOf(incident),
      status,
      openedAt,
      blocksEstate: incident.blocksEstate === true,
    };
  });
  items.sort((a, b) => RANK[a.status.state] - RANK[b.status.state] || openedMs(b.openedAt) - openedMs(a.openedAt));
  const rows = items.slice(0, FAILOVER_INCIDENT_CAP);
  return {
    total: items.length,
    openCount: items.filter((i) => i.status.state === "act").length,
    unresolvedCount: items.filter((i) => i.status.state === "watch").length,
    unknownCount: items.filter((i) => i.status.state === "unk").length,
    rows,
    moreCount: Math.max(0, items.length - rows.length),
  };
}
