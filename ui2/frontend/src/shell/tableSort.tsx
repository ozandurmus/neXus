import { useCallback, useMemo, useState, type ReactNode } from "react";
import TableCell, { type TableCellProps } from "@mui/material/TableCell";
import TableSortLabel from "@mui/material/TableSortLabel";

/**
 * Column sorting for the one data table (design language 2026-10-19, component 4), shared by every list the browser
 * holds whole (Product Owner, 2026-11-04: "when I click a column header it only reorders the 50 rows on the page").
 *
 * The contract, in one place so every table behaves the same way:
 * - A header click cycles ascending, then descending, then back to the table's default order. A column may name
 *   `firstDirection: "desc"` when largest-first is the useful first step (archive size).
 * - The WHOLE filtered list is ordered before it is cut into pages; the caller resets to page 1 on a change.
 * - Text compares with numeric collation (`FW-TANGO-2` before `FW-TANGO-10`), numbers as numbers, times
 *   chronologically.
 * - An empty or unknown value (`null`, `undefined`, `""`, `NaN`, an unparseable time) sorts last in both directions.
 * - Ties keep the default order (the sort is stable), so a table's own default order shows through.
 *
 * A table that already pages on the server keeps its headers plain: ordering one page of a server list would say
 * something the list does not mean. That is a server-side sort, not this hook.
 */
export type SortDirection = "asc" | "desc";

export interface TableSort {
  readonly key: string;
  readonly direction: SortDirection;
}

/** What a cell orders by: the text, number or ISO-8601 time the cell actually renders. */
export type SortValue = string | number | null | undefined;

export type SortKind = "text" | "number" | "time";

export interface SortColumn<T> {
  readonly key: string;
  readonly value: (row: T) => SortValue;
  /** How the value compares (default `text`). */
  readonly kind?: SortKind;
  /** The direction the first click takes (default ascending). */
  readonly firstDirection?: SortDirection;
}

const collator = new Intl.Collator("en", { numeric: true, sensitivity: "base" });

/** `null` for a value that carries no order; otherwise the comparable form of the value for its kind. */
function normalise(value: SortValue, kind: SortKind): string | number | null {
  if (value === null || value === undefined) return null;
  if (kind === "number") {
    const n = typeof value === "number" ? value : value.trim() === "" ? Number.NaN : Number(value);
    return Number.isFinite(n) ? n : null;
  }
  if (kind === "time") {
    if (typeof value === "number") return Number.isFinite(value) ? value : null;
    if (value.trim() === "") return null;
    const t = Date.parse(value);
    return Number.isNaN(t) ? null : t;
  }
  const s = typeof value === "number" ? (Number.isFinite(value) ? String(value) : "") : value;
  return s.trim() === "" ? null : s;
}

/**
 * Orders two values of one kind. An unknown value sorts after a known one whichever way the column points; two
 * unknowns tie, so they keep the default order among themselves.
 */
export function compareSortValues(a: SortValue, b: SortValue, kind: SortKind = "text", direction: SortDirection = "asc"): number {
  const x = normalise(a, kind);
  const y = normalise(b, kind);
  if (x === null && y === null) return 0;
  if (x === null) return 1;
  if (y === null) return -1;
  const base = typeof x === "number" && typeof y === "number" ? (x < y ? -1 : x > y ? 1 : 0) : collator.compare(String(x), String(y));
  return direction === "desc" ? 0 - base : base; // `0 - base`, not `-base`: a tie stays +0
}

/** The next state after a click on `key`: ascending (or the column's first direction), then the other way, then off. */
export function nextTableSort(current: TableSort | null, key: string, firstDirection: SortDirection = "asc"): TableSort | null {
  if (current === null || current.key !== key) return { key, direction: firstDirection };
  if (current.direction === firstDirection) return { key, direction: firstDirection === "asc" ? "desc" : "asc" };
  return null;
}

/** A stable copy of `rows` in `sort` order over the column that owns `sort.key`; the rows as given when there is no sort. */
export function sortRows<T>(rows: readonly T[], columns: ReadonlyArray<SortColumn<T>>, sort: TableSort | null): T[] {
  if (sort === null) return [...rows];
  const column = columns.find((c) => c.key === sort.key);
  if (!column) return [...rows];
  const kind = column.kind ?? "text";
  // the value once per row, then a stable sort over the pairs (Array.prototype.sort is stable)
  return rows
    .map((row, index) => ({ row, index, value: column.value(row) }))
    .sort((a, b) => compareSortValues(a.value, b.value, kind, sort.direction) || a.index - b.index)
    .map((p) => p.row);
}

export interface TableSortState<T> {
  /** The active sort, or null for the table's default order. */
  readonly sort: TableSort | null;
  readonly setSort: (sort: TableSort | null) => void;
  /** A header click. */
  readonly toggle: (key: string) => void;
  /** The whole filtered list in order; slice the page from the result. */
  readonly apply: (rows: readonly T[]) => T[];
  /** The header cell's props for `key`: `aria-sort` through MUI's `sortDirection`, and the label's state. */
  readonly header: (key: string) => SortHeaderState;
}

export interface SortHeaderState {
  readonly active: boolean;
  /** The active direction, or the one the first click would take. */
  readonly direction: SortDirection;
  /** The direction the first click takes, so a header can say whether the next click flips or clears. */
  readonly firstDirection: SortDirection;
  /** MUI `TableCell.sortDirection`: sets `aria-sort` when active, nothing when not. */
  readonly sortDirection: false | SortDirection;
  readonly onClick: () => void;
}

/**
 * The sort state of one table. `onChange` runs on every change (the caller resets its page there). `columns` should be
 * a stable reference (a module constant or a `useMemo`), as every header reads it.
 */
export function useTableSort<T>(columns: ReadonlyArray<SortColumn<T>>, options: { readonly onChange?: () => void; readonly initial?: TableSort | null } = {}): TableSortState<T> {
  const { onChange, initial = null } = options;
  const [sort, setSortState] = useState<TableSort | null>(initial);
  const setSort = useCallback((next: TableSort | null) => { setSortState(next); onChange?.(); }, [onChange]);
  const toggle = useCallback((key: string) => {
    const column = columns.find((c) => c.key === key);
    setSortState((current) => nextTableSort(current, key, column?.firstDirection ?? "asc"));
    onChange?.();
  }, [columns, onChange]);
  const apply = useCallback((rows: readonly T[]) => sortRows(rows, columns, sort), [columns, sort]);
  const header = useCallback((key: string): SortHeaderState => {
    const column = columns.find((c) => c.key === key);
    const first = column?.firstDirection ?? "asc";
    const active = sort !== null && sort.key === key;
    return { active, direction: active ? sort.direction : first, firstDirection: first, sortDirection: active ? sort.direction : false, onClick: () => toggle(key) };
  }, [columns, sort, toggle]);
  return useMemo(() => ({ sort, setSort, toggle, apply, header }), [sort, setSort, toggle, apply, header]);
}

export interface ServerTableSortState {
  readonly sort: TableSort | null;
  readonly setSort: (sort: TableSort | null) => void;
  readonly toggle: (key: string, firstDirection?: SortDirection) => void;
  readonly header: (key: string, firstDirection?: SortDirection) => SortHeaderState;
}

/**
 * Sort state for a server-paged table: the same `asc -> desc -> default` header cycle and `SortHeaderCell` props,
 * while the query endpoint applies the order across all pages before slicing.
 */
export function useServerTableSort(options: { readonly onChange?: () => void; readonly initial?: TableSort | null } = {}): ServerTableSortState {
  const { onChange, initial = null } = options;
  const [sort, setSortState] = useState<TableSort | null>(initial);
  const setSort = useCallback((next: TableSort | null) => { setSortState(next); onChange?.(); }, [onChange]);
  const toggle = useCallback((key: string, firstDirection: SortDirection = "asc") => {
    setSortState((current) => nextTableSort(current, key, firstDirection));
    onChange?.();
  }, [onChange]);
  const header = useCallback((key: string, firstDirection: SortDirection = "asc"): SortHeaderState => {
    const active = sort !== null && sort.key === key;
    return {
      active,
      direction: active ? sort.direction : firstDirection,
      firstDirection,
      sortDirection: active ? sort.direction : false,
      onClick: () => toggle(key, firstDirection),
    };
  }, [sort, toggle]);
  return useMemo(() => ({ sort, setSort, toggle, header }), [sort, setSort, toggle, header]);
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export interface SortHeaderCellProps extends Omit<TableCellProps, "sortDirection"> {
  readonly state: SortHeaderState;
  /** The header text; also the accessible name of the sort button. */
  readonly label: string;
  /** Words for the two directions in the hover text (default A to Z / Z to A; a size column says largest first). */
  readonly words?: { readonly asc: string; readonly desc: string };
  readonly children?: ReactNode;
}

/**
 * One sortable header cell: MUI's `TableCell.sortDirection` (which writes `aria-sort`) around a `TableSortLabel`,
 * with hover text that says what the click will do.
 */
export function SortHeaderCell({ state, label, words = { asc: "A to Z", desc: "Z to A" }, children, ...cell }: SortHeaderCellProps) {
  const word = (d: SortDirection) => (d === "asc" ? words.asc : words.desc);
  const sortTitle = !state.active
    ? `Sort by ${label.toLowerCase()}, ${word(state.direction)}`
    : state.direction === state.firstDirection
      ? `${capitalise(word(state.direction))} · click for ${word(state.direction === "asc" ? "desc" : "asc")}`
      : `${capitalise(word(state.direction))} · click for the default order`;
  return (
    <TableCell {...cell} sortDirection={state.sortDirection}>
      <TableSortLabel
        active={state.active}
        direction={state.direction}
        onClick={state.onClick}
        title={sortTitle}
        aria-label={cell["aria-label"]}
      >
        {children ?? label}
      </TableSortLabel>
    </TableCell>
  );
}
