import { useCallback, useState } from "react";

import { EmptyPanel } from "../shell/ScreenLayout";
import { DataTable, sortColumnsOf, type DataColumn } from "../shell/DataTable";
import { useTableSort } from "../shell/tableSort";
import { M3Button } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listAuditEvents, type ApiError, type AuditEventView } from "../auth/adminApi";

function describeError(err: ApiError): string {
  return typeof err.body?.error === "string" ? err.body.error : `request failed (status ${err.status})`;
}

const PAGE_SIZES = [10, 25, 50] as const;

/** Every column orders by the value its cell shows; the whole trail is ordered, then paged (tableSort.tsx). */
const COLUMNS: ReadonlyArray<DataColumn<AuditEventView>> = [
  { key: "at", header: "Timestamp", width: 230, mono: true, render: (e) => <Ts at={e.occurred_at} seconds relative={false} />, title: (e) => e.occurred_at,
    sort: { value: (e) => e.occurred_at, kind: "time", words: { asc: "oldest first", desc: "newest first" } } },
  { key: "actor", header: "Actor", width: 180, render: (e) => e.actor, sort: { value: (e) => e.actor } },
  { key: "action", header: "Action", width: 220, mono: true, render: (e) => e.action, sort: { value: (e) => e.action } },
  { key: "target", header: "Target", width: 220, mono: true, render: (e) => e.target, sort: { value: (e) => e.target } },
  // one line per row: a long refusal sentence is cut with an ellipsis and read whole on hover
  { key: "outcome", header: "Reason / Outcome", render: (e) => e.outcome, sort: { value: (e) => e.outcome } },
];
const SORT_COLUMNS = sortColumnsOf(COLUMNS);

/** The audit trail as the one data table: compact rows, the stamp with seconds (an audit row keeps them), `1–10 of N`. */
export function AuditLogsPanel() {
  const [offset, setOffset] = useState(0);
  const [pageSize, setPageSize] = useState<number>(10);
  const firstPage = useCallback(() => setOffset(0), []);
  const sort = useTableSort(SORT_COLUMNS, { onChange: firstPage });

  const { data, error, rawError, refresh } = useFetchOnMount(
    () => listAuditEvents().then((result) => result.events ?? []),
    (err) => describeError(err as ApiError),
  );

  if (error && data === null) {
    return isRestricted(rawError)
      ? <RestrictedPanel area="Audit log" role="Security Admin" />
      : <StatePanel variant="error" title="Audit logs unavailable" body="Audit events could not be read." code={error}
          action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />;
  }
  if (data === null) return <StatePanel variant="empty" title="Audit logs" body="Loading…" />;
  if (data.length === 0) return <EmptyPanel title="No audit events" body="No audited management action has been recorded." />;

  const safeOffset = Math.min(offset, Math.max(0, Math.floor((data.length - 1) / pageSize) * pageSize));
  // the whole trail in the chosen order, then the page of it
  const rows = sort.apply(data).slice(safeOffset, safeOffset + pageSize);

  return (
    <DataTable<AuditEventView>
      ariaLabel="Audit logs table"
      columns={COLUMNS}
      sort={sort}
      rows={rows}
      rowKey={(e) => String(e.id)}
      maxHeight={600}
      page={{ offset: safeOffset, size: pageSize, total: data.length, onPage: setOffset, sizes: PAGE_SIZES, onSize: (size) => { setPageSize(size); setOffset(0); } }}
    />
  );
}
