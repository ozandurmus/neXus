import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { DENSITY, LABEL_UPPER, RADIUS, TYPE } from "../theme/tokens";
import { Icon } from "./Icon";
import { CapabilityMenu, type CapabilityMenuItem } from "./M3Widgets";

/**
 * The one data table (design language 2026-10-19, component 4): compact density -- 32 px one-line rows, 8 px cell
 * gutters, fixed column widths at 1440 so a long value is cut with an ellipsis and never widens the page, mono for
 * machine values (addresses, serials, hashes, versions, timestamps), a `⋮` menu at the end of a row for its actions,
 * and the pagination footer `1–50 of N`. A cell's full value is on hover when it may be cut. A row never grows past
 * one line: anything longer belongs in the detail.
 */
export interface DataColumn<T> {
  readonly key: string;
  readonly header: ReactNode;
  /** Fixed width in px (or a CSS width); the one column without a width takes the rest. */
  readonly width?: number | string;
  /** Machine value: the mono step. */
  readonly mono?: boolean;
  readonly align?: "left" | "right" | "center";
  readonly render: (row: T, index: number) => ReactNode;
  /** The plain text of the cell, on hover, when it may be cut (default: a string result of `render`). */
  readonly title?: (row: T) => string | undefined;
  /** A column that may wrap onto a second line (a reason sentence); every other cell stays one line. */
  readonly wrap?: boolean;
}

export interface DataPage {
  /** Zero-based offset of the first row shown. */
  readonly offset: number;
  readonly size: number;
  /** The total row count; null when the server does not say (then `hasMore` decides the next button). */
  readonly total: number | null;
  readonly onPage: (offset: number) => void;
  /** The noun after the count: `1–50 of 131 targets`. */
  readonly noun?: string;
  /** With an unknown total: whether a next page exists. */
  readonly hasMore?: boolean;
  /** Offered page sizes (a `Per page` select appears when there is more than one). */
  readonly sizes?: readonly number[];
  readonly onSize?: (size: number) => void;
  /** Rows on the page as the caller counted them; only needed with an unknown total. */
  readonly shown?: number;
  /**
   * First- and last-page buttons beside the previous/next pair, for a long server-paged history (Jobs: hundreds of
   * pages); only with a known total. The footer stays the one footer -- the range words and the chevrons do not change.
   */
  readonly jump?: boolean;
}

export interface DataTableProps<T> {
  readonly ariaLabel: string;
  readonly columns: ReadonlyArray<DataColumn<T>>;
  readonly rows: readonly T[];
  readonly rowKey: (row: T, index: number) => string;
  /** The row's `⋮` menu; an empty list draws no menu for that row. */
  readonly actions?: (row: T) => readonly CapabilityMenuItem[];
  readonly page?: DataPage;
  /** The one sentence for no rows (never a dash, never 0 standing in for UNKNOWN). */
  readonly emptyText?: string;
  readonly onRowClick?: (row: T) => void;
  readonly selectedKey?: string | null;
  /** Keep the first column visible while the table scrolls sideways. */
  readonly stickyFirst?: boolean;
  /** Something that belongs in the footer beside the page count (an export action). */
  readonly footer?: ReactNode;
  /** A fixed height with its own scroll, for a list that sits above a detail. */
  readonly maxHeight?: number | string;
  readonly testId?: string;
}

const ONE_LINE = { whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" } as const;

export function DataTable<T>({ ariaLabel, columns, rows, rowKey, actions, page, emptyText = "No rows.", onRowClick, selectedKey, stickyFirst = false, footer, maxHeight, testId }: DataTableProps<T>) {
  const d = DENSITY.compact;
  const hasActions = Boolean(actions);
  return (
    <Box data-testid={testId} data-density="compact" sx={{ display: "flex", flexDirection: "column", minWidth: 0, border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, bgcolor: m3.scLowest, overflow: "hidden" }}>
      <TableContainer sx={{ maxHeight, overflowX: "auto", ...(stickyFirst ? { "& th:first-of-type, & td:first-of-type": { position: "sticky", left: 0, zIndex: 1, bgcolor: m3.scLowest, boxShadow: `1px 0 0 ${m3.outlineVar}` } } : {}) }}>
        <Table size="small" aria-label={ariaLabel} stickyHeader={Boolean(maxHeight)}
          sx={{ tableLayout: "fixed", minWidth: 0, "& th, & td": { px: `${d.gutter}px`, py: `${d.cellY}px`, borderColor: m3.outlineVar },
                "& tbody tr": { height: d.row }, "& thead th": { bgcolor: m3.scLow } }}>
          <colgroup>
            {columns.map((c) => <col key={c.key} style={c.width !== undefined ? { width: typeof c.width === "number" ? `${c.width}px` : c.width } : undefined} />)}
            {hasActions ? <col style={{ width: 44 }} /> : null}
          </colgroup>
          <TableHead>
            <TableRow>
              {columns.map((c) => (
                <TableCell key={c.key} align={c.align} sx={{ ...LABEL_UPPER, ...ONE_LINE, color: m3.onSurfaceVar, height: d.row }}>{c.header}</TableCell>
              ))}
              {hasActions ? <TableCell aria-label="Actions" sx={{ height: d.row }} /> : null}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={columns.length + (hasActions ? 1 : 0)} sx={{ color: m3.onSurfaceVar, fontSize: TYPE.body.fontSize, lineHeight: TYPE.body.lineHeight }}>{emptyText}</TableCell>
              </TableRow>
            ) : rows.map((row, index) => {
              const key = rowKey(row, index);
              const items = actions ? actions(row) : [];
              return (
                <TableRow key={key} hover={Boolean(onRowClick)} selected={selectedKey === key} onClick={onRowClick ? () => onRowClick(row) : undefined}
                  sx={{ cursor: onRowClick ? "pointer" : "default", "&.Mui-selected": { bgcolor: m3.primaryContainer } }}>
                  {columns.map((c) => {
                    const content = c.render(row, index);
                    const title = c.title ? c.title(row) : typeof content === "string" || typeof content === "number" ? String(content) : undefined;
                    return (
                      <TableCell key={c.key} align={c.align} title={title}
                        sx={{ ...(c.wrap ? { whiteSpace: "normal", overflowWrap: "anywhere" } : ONE_LINE), fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight,
                              fontFamily: c.mono ? TYPE.mono.fontFamily : "inherit", fontVariantNumeric: "tabular-nums", color: m3.onSurface }}>
                        {content}
                      </TableCell>
                    );
                  })}
                  {hasActions ? (
                    <TableCell align="right" sx={{ p: 0, pr: 0.5 }} onClick={(e) => e.stopPropagation()}>
                      {items.length > 0 ? <CapabilityMenu ariaLabel={`Actions for row ${key}`} items={items} /> : null}
                    </TableCell>
                  ) : null}
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
      {(page || footer) ? (
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 2, px: `${d.gutter}px`, py: 0.5, borderTop: `1px solid ${m3.outlineVar}`, minHeight: d.row }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>{footer}</Box>
          {page ? <PaginationFooter {...page} /> : null}
        </Box>
      ) : null}
    </Box>
  );
}

/**
 * The one page footer: `1–50 of 131`, with previous and next (named as MUI names them, so every screen's tests ask
 * for the same buttons); a `Per page` select only where the screen offers sizes. With an unknown total it reads
 * `51–100` and `hasMore` decides the next button. Nothing is drawn when there is nothing to page.
 */
export function PaginationFooter({ offset, size, total, onPage, noun, hasMore, sizes, onSize, shown, jump = false }: DataPage) {
  const known = total !== null && total !== undefined;
  const count = known ? total : offset + (shown ?? size);
  const first = count === 0 ? 0 : offset + 1;
  const last = known ? Math.min(offset + size, total) : offset + (shown ?? size);
  const text = known
    ? `${first}\u2013${last} of ${total.toLocaleString("en-US")}${noun ? ` ${noun}` : ""}`
    : `${first}\u2013${last}${noun ? ` ${noun}` : ""}`;
  const nextDisabled = known ? offset + size >= total : !hasMore;
  const lastOffset = known && total > 0 ? Math.floor((total - 1) / size) * size : 0;
  return (
    <Box data-testid="pagination-footer" sx={{ display: "flex", alignItems: "center", gap: 0.5, ml: "auto" }}>
      {sizes && sizes.length > 1 && onSize ? (
        <Box component="label" sx={{ ...TYPE.label, color: m3.onSurfaceVar, display: "inline-flex", alignItems: "center", gap: 0.75, mr: 1 }}>
          Per page
          <Box component="select" value={size} aria-label="Rows per page" onChange={(e: { target: { value: string } }) => onSize(Number(e.target.value))}
            sx={{ height: 24, fontSize: TYPE.label.fontSize, borderRadius: `${RADIUS.pill}px`, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, color: m3.onSurface, px: 0.5 }}>
            {sizes.map((n) => <option key={n} value={n}>{n}</option>)}
          </Box>
        </Box>
      ) : null}
      <Typography component="span" data-page-range sx={{ ...TYPE.label, color: m3.onSurfaceVar, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>{text}</Typography>
      {jump && known ? (
        <IconButton size="small" aria-label="Go to first page" disabled={offset <= 0} onClick={() => onPage(0)} sx={{ color: m3.onSurfaceVar }}>
          <Icon name="first-page" size={16} />
        </IconButton>
      ) : null}
      <IconButton size="small" aria-label="Go to previous page" disabled={offset <= 0} onClick={() => onPage(Math.max(0, offset - size))} sx={{ color: m3.onSurfaceVar }}>
        <Icon name="chevron-left" size={16} />
      </IconButton>
      <IconButton size="small" aria-label="Go to next page" disabled={nextDisabled} onClick={() => onPage(offset + size)} sx={{ color: m3.onSurfaceVar }}>
        <Icon name="chevron-right" size={16} />
      </IconButton>
      {jump && known ? (
        <IconButton size="small" aria-label="Go to last page" disabled={nextDisabled} onClick={() => onPage(lastOffset)} sx={{ color: m3.onSurfaceVar }}>
          <Icon name="last-page" size={16} />
        </IconButton>
      ) : null}
    </Box>
  );
}
