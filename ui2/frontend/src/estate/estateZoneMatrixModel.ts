import {
  matrixOf,
  referenceStatusWord,
  severityWord,
  statementWord,
  type MatrixCellView,
  type ZoneVersionDetail,
} from "../screens/zonematrix/zoneMatrixApi";
import { relativeAge } from "../shell/time";
import type { BadgeState } from "./estateModel";

/** Source zones (grid rows) shown before the "+N more" footer. */
export const ZONE_MATRIX_ROW_CAP = 12;

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
}

export interface ZoneGridRow {
  readonly zoneId: string;
  readonly name: string;
  readonly cells: readonly ZoneGridCell[];
}

export interface ZoneMatrixGridModel {
  /** `Version 3 · 2 d ago`, or `Draft · UNKNOWN` when the version carries no time. The age is the version's, not "now". */
  readonly versionLabel: string;
  /** The instant the version was published (or created); feeds the evidence stamp. */
  readonly evidenceAt: string | null;
  readonly defaultLine: string;
  readonly columns: readonly ZoneGridColumn[];
  readonly rows: readonly ZoneGridRow[];
  readonly shownRows: number;
  readonly totalRows: number;
}

/** DENY_ALL is the restrictive case (ok), ALLOW_ANY the loosest (act), ALLOW_LISTED in between (watch). */
const STATEMENT_STATE: Readonly<Record<string, BadgeState>> = {
  DENY_ALL: "ok",
  ALLOW_LISTED: "watch",
  ALLOW_ANY: "act",
};

const pairKey = (source: string, destination: string): string => JSON.stringify([source, destination]);

export function zoneGridCellOf(cell: MatrixCellView | undefined, key: string): ZoneGridCell {
  if (!cell) {
    return { key, state: "unk", word: "UNKNOWN", severity: "UNKNOWN", reference: "UNKNOWN", known: false };
  }
  const state = STATEMENT_STATE[cell.statement];
  if (!state) {
    return { key, state: "unk", word: "UNKNOWN", severity: severityWord(cell.severity), reference: referenceStatusWord(cell.reference_status), known: false };
  }
  return {
    key,
    state,
    word: statementWord(cell.statement),
    severity: severityWord(cell.severity),
    reference: referenceStatusWord(cell.reference_status),
    known: true,
  };
}

export function zoneMatrixGridModel(version: ZoneVersionDetail, now: Date = new Date()): ZoneMatrixGridModel {
  const matrix = matrixOf(version);
  const columns: ZoneGridColumn[] = version.zones.map((z) => ({ zoneId: z.zone_id, name: z.name }));
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

  const evidenceAt = version.published_at ?? version.created_at ?? null;
  const base = version.number === null ? "Draft" : `Version ${version.number}`;
  const age = evidenceAt ? relativeAge(evidenceAt, now) : "UNKNOWN";

  return {
    versionLabel: `${base} · ${age}`,
    evidenceAt,
    defaultLine: `Pairs with no explicit cell: default ${statementWord(matrix.default_statement)}, severity ${severityWord(matrix.default_severity)}`,
    columns,
    rows: allRows.slice(0, ZONE_MATRIX_ROW_CAP),
    shownRows: Math.min(allRows.length, ZONE_MATRIX_ROW_CAP),
    totalRows: allRows.length,
  };
}
