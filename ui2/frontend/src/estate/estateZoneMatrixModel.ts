import {
  addressCount,
  matrixOf,
  referenceStatusWord,
  severityWord,
  statementWord,
  zoneRoleLabel,
  type MatrixCellView,
  type ServiceEntryView,
  type ServiceSetView,
  type ZoneVersionDetail,
} from "../screens/zonematrix/zoneMatrixApi";
import { plural } from "../shell/plural";
import { actorLabel } from "../shell/presenters";
import { relativeAge } from "../shell/time";
import type { BadgeState } from "./estateModel";

/** Source zones (grid rows) shown before the "+N more" footer. */
export const ZONE_MATRIX_ROW_CAP = 12;

/** Services listed inside a single ALLOW_LISTED matrix cell before "+N more services". */
export const ZONE_CELL_SERVICES_CAP = 4;

/** Service sets shown in the version summary before the "+N more service sets" footer. */
export const ZONE_SERVICE_SETS_CAP = 10;

/** Entries shown per service set row before "+N more services". */
export const ZONE_SERVICE_SET_ENTRIES_CAP = 6;

/** The standard zone-matrix route; the Estate grid is read-only and links here for every edit. */
export const STANDARD_ZONE_MATRIX_HREF = "?screen=zone_matrix&standard=1";

export interface ZoneGridColumn {
  readonly zoneId: string;
  readonly name: string;
}

export interface ZoneGridCell {
  readonly key: string;
  /** `unk` for a pair with no explicit cell or an unrecognised statement; never `ok` for those. */
  readonly state: BadgeState;
  /** The statement word, or `UNKNOWN`. */
  readonly word: string;
  readonly severity: string;
  readonly reference: string;
  readonly known: boolean;
  readonly servicesLine: string | null;
  readonly confirmedLine: string | null;
}

export interface ZoneGridRow {
  readonly zoneId: string;
  readonly name: string;
  readonly cells: readonly ZoneGridCell[];
}

export interface ZoneSummaryRow {
  readonly zoneId: string;
  readonly name: string;
  readonly roleWord: string;
  readonly blockCount: string;
  readonly ipv4Addresses: string;
  readonly ipv6Addresses: string;
}

export interface ZoneServiceSetRow {
  readonly name: string;
  readonly countWord: string;
  readonly entriesText: string;
  readonly moreEntriesCount: number;
  readonly hasNotEvaluable: boolean;
}

export interface ZoneMatrixGridModel {
  /** `Version 3 · 2 d ago`, or `Draft · UNKNOWN` when the version carries no time. The age is the version's, not "now". */
  readonly versionLabel: string;
  /** The instant the version was published (or created); feeds the evidence stamp. */
  readonly evidenceAt: string | null;
  readonly defaultLine: string;
  readonly confirmationLine: string | null;
  readonly confirmationUnknown: boolean;
  readonly unassignedPolicyLine: string;
  readonly templateOriginLine: string | null;
  readonly columns: readonly ZoneGridColumn[];
  readonly rows: readonly ZoneGridRow[];
  readonly shownRows: number;
  readonly totalRows: number;
  readonly zoneSummaries: readonly ZoneSummaryRow[];
  readonly shownZones: number;
  readonly totalZones: number;
  readonly unassignedLine: string;
  readonly redundantBlocksCount: number | null;
  readonly serviceSets: readonly ZoneServiceSetRow[];
  readonly shownServiceSets: number;
  readonly totalServiceSets: number;
}

/** DENY_ALL is the restrictive case (ok), ALLOW_ANY the loosest (act), ALLOW_LISTED in between (watch). */
const STATEMENT_STATE: Readonly<Record<string, BadgeState>> = {
  DENY_ALL: "ok",
  ALLOW_LISTED: "watch",
  ALLOW_ANY: "act",
};

const pairKey = (source: string, destination: string): string => JSON.stringify([source, destination]);

function formatServiceEntry(s: ServiceEntryView): string {
  if (s.evaluable) return s.entry;
  const reason = s.not_evaluable_reason && s.not_evaluable_reason.trim() ? s.not_evaluable_reason : "UNKNOWN";
  return `${s.entry} (not evaluable: ${reason})`;
}

export function zoneGridCellOf(cell: MatrixCellView | undefined, key: string): ZoneGridCell {
  if (!cell) {
    return {
      key,
      state: "unk",
      word: "UNKNOWN",
      severity: "UNKNOWN",
      reference: "UNKNOWN",
      known: false,
      servicesLine: null,
      confirmedLine: null,
    };
  }
  const state = STATEMENT_STATE[cell.statement];
  if (!state) {
    return {
      key,
      state: "unk",
      word: "UNKNOWN",
      severity: severityWord(cell.severity),
      reference: referenceStatusWord(cell.reference_status),
      known: false,
      servicesLine: null,
      confirmedLine: null,
    };
  }
  const servicesLine =
    cell.statement === "ALLOW_LISTED"
      ? !Array.isArray(cell.services)
        ? "Services UNKNOWN"
        : cell.services.length === 0
          ? "0 services (empty list)"
          : (() => {
              const shown = cell.services.slice(0, ZONE_CELL_SERVICES_CAP).map(formatServiceEntry);
              const more = cell.services.length - shown.length;
              return `${plural(cell.services.length, "service", "services")}: ${shown.join(", ")}${more > 0 ? ` (+${more} more services)` : ""}`;
            })()
      : null;
  const confirmedLine =
    cell.reference_status === "CONFIRMED"
      ? cell.confirmed_by && cell.confirmed_by.trim()
        ? `Confirmed by ${actorLabel(cell.confirmed_by).label}`
        : "Confirmed (actor UNKNOWN)"
      : null;
  return {
    key,
    state,
    word: statementWord(cell.statement),
    severity: severityWord(cell.severity),
    reference: referenceStatusWord(cell.reference_status),
    known: true,
    servicesLine,
    confirmedLine,
  };
}

export function zoneMatrixGridModel(version: ZoneVersionDetail, now: Date = new Date()): ZoneMatrixGridModel {
  const matrix = matrixOf(version);
  const zones = Array.isArray(version.zones) ? version.zones : [];
  const columns: ZoneGridColumn[] = zones.map((z) => ({ zoneId: z.zone_id, name: z.name }));
  const byPair = new Map<string, MatrixCellView>();
  for (const cell of matrix.cells) byPair.set(pairKey(cell.source, cell.destination), cell);

  const allRows: ZoneGridRow[] = columns.map((source) => ({
    zoneId: source.zoneId,
    name: source.name,
    cells: columns.map((destination) => {
      const key = pairKey(source.zoneId, destination.zoneId);
      return zoneGridCellOf(byPair.get(key), key);
    }),
  }));

  const zoneSummaries: ZoneSummaryRow[] = zones.slice(0, ZONE_MATRIX_ROW_CAP).map((z) => {
    const rawRole = z.role ? zoneRoleLabel(z.role).trim() : "";
    const roleWord = rawRole.length > 0 ? rawRole : "UNKNOWN";
    const blockCount =
      typeof z.block_count === "number" && Number.isFinite(z.block_count) && z.block_count >= 0
        ? `${z.block_count} ${z.block_count === 1 ? "block" : "blocks"}`
        : "UNKNOWN";
    const ipv4Addresses =
      z.effective_addresses?.IPV4 !== undefined ? addressCount(z.effective_addresses.IPV4) : "UNKNOWN";
    const ipv6Addresses =
      z.effective_addresses?.IPV6 !== undefined ? addressCount(z.effective_addresses.IPV6) : "UNKNOWN";
    return {
      zoneId: z.zone_id,
      name: z.name,
      roleWord,
      blockCount,
      ipv4Addresses,
      ipv6Addresses,
    };
  });

  const unassignedIpv4 = version.unassigned_addresses ? addressCount(version.unassigned_addresses.IPV4) : "UNKNOWN";
  const unassignedIpv6 = version.unassigned_addresses ? addressCount(version.unassigned_addresses.IPV6) : "UNKNOWN";
  const unassignedLine = `Unassigned space: IPv4 ${unassignedIpv4} · IPv6 ${unassignedIpv6}`;

  const redundantBlocksCount =
    typeof version.redundant_blocks === "number" && Number.isFinite(version.redundant_blocks)
      ? version.redundant_blocks
      : Array.isArray(version.redundant_blocks)
        ? version.redundant_blocks.length
        : null;

  const hasConfirmationCounts =
    typeof matrix.cells_confirmed === "number" &&
    Number.isFinite(matrix.cells_confirmed) &&
    typeof matrix.cells_total === "number" &&
    Number.isFinite(matrix.cells_total);
  const referenceCountSuffix =
    typeof matrix.cells_with_reference === "number" &&
    Number.isFinite(matrix.cells_with_reference) &&
    matrix.cells_with_reference > 0
      ? ` (${plural(matrix.cells_with_reference, "cell", "cells")} with reference)`
      : "";
  const confirmationLine = hasConfirmationCounts
    ? `${matrix.cells_confirmed} of ${plural(matrix.cells_total, "cell", "cells")} confirmed by compliance${referenceCountSuffix}`
    : null;
  const confirmationUnknown = !hasConfirmationCounts;

  const unassignedPolicyLine = `UNASSIGNED -> UNASSIGNED: ${
    typeof matrix.unassigned_to_unassigned === "string" && matrix.unassigned_to_unassigned.trim()
      ? matrix.unassigned_to_unassigned
      : "UNKNOWN"
  }`;

  const templateOriginLine = matrix.template_origin?.name
    ? `Template origin: ${matrix.template_origin.name}${matrix.template_origin.revision ? ` (${matrix.template_origin.revision})` : ""}`
    : null;

  const rawServiceSets: readonly ServiceSetView[] = Array.isArray(matrix.service_sets) ? matrix.service_sets : [];
  const serviceSets: ZoneServiceSetRow[] = rawServiceSets.slice(0, ZONE_SERVICE_SETS_CAP).map((s) => {
    const entries = Array.isArray(s.entries) ? s.entries : [];
    const shownEntries = entries.slice(0, ZONE_SERVICE_SET_ENTRIES_CAP).map(formatServiceEntry);
    const moreEntriesCount = Math.max(0, entries.length - shownEntries.length);
    const entriesText = !Array.isArray(s.entries)
      ? "Entries UNKNOWN"
      : entries.length === 0
        ? "0 services (empty list)"
        : `${shownEntries.join(", ")}${moreEntriesCount > 0 ? ` (+${moreEntriesCount} more services)` : ""}`;
    return {
      name: s.name,
      countWord: Array.isArray(s.entries) ? plural(entries.length, "service", "services") : "UNKNOWN",
      entriesText,
      moreEntriesCount,
      hasNotEvaluable: entries.some((e) => !e.evaluable),
    };
  });

  const evidenceAt = version.published_at ?? version.created_at ?? null;
  const base = version.number === null ? "Draft" : `Version ${version.number}`;
  const age = evidenceAt ? relativeAge(evidenceAt, now) : "UNKNOWN";

  return {
    versionLabel: `${base} · ${age}`,
    evidenceAt,
    defaultLine: `Pairs with no explicit cell: default ${statementWord(matrix.default_statement)}, severity ${severityWord(matrix.default_severity)}`,
    confirmationLine,
    confirmationUnknown,
    unassignedPolicyLine,
    templateOriginLine,
    columns,
    rows: allRows.slice(0, ZONE_MATRIX_ROW_CAP),
    shownRows: Math.min(allRows.length, ZONE_MATRIX_ROW_CAP),
    totalRows: allRows.length,
    zoneSummaries,
    shownZones: zoneSummaries.length,
    totalZones: zones.length,
    unassignedLine,
    redundantBlocksCount,
    serviceSets,
    shownServiceSets: serviceSets.length,
    totalServiceSets: rawServiceSets.length,
  };
}
