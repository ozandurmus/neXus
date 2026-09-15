import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import {
  listAuditEntries,
  type ApiError,
  type AuditEntrySummaryView,
  type AuditListQuery,
  type AuditOperation,
} from "../auth/adminApi";
import { AuditDetailPanel } from "./AuditPanels";

/**
 * `UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md`: a read-only view over
 * `audit_log`. This screen computes no scope of its own -- `own` versus
 * `all` is a server decision on every request (§5.1) -- and never offers an
 * export, download, print or clipboard-bulk-copy affordance (§7).
 */

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

type ListFetchState =
  | { readonly kind: "ok"; readonly entries: AuditEntrySummaryView[]; readonly nextCursor: string | null }
  | { readonly kind: "refused"; readonly reasonCode: string }
  | { readonly kind: "invalid"; readonly reasonCode: string };

/**
 * `unknown_table_name` / `unknown_operation` / `occurred_to_before_occurred_from`
 * / a `read_own` actor's illegal `actor_fingerprint` filter, and an actor
 * holding neither role token, both currently reach the browser as the same
 * `403 ACTION_REFUSED` envelope from this endpoint. They are not the same
 * situation: one is a filter mistake an operator can fix inline, the other
 * is contract §5.1's "visible but refused" state that must take over the
 * whole screen body. `decision_id` is the one reliable signal that tells
 * them apart -- `GateChain`'s own E4 evaluation always mints one; this
 * endpoint's own post-gate filter-scope rejection (§5.2) never does. This is
 * a real gap against the contract's own §4.1 text ("rejected with 400"),
 * reported here rather than silently worked around by guessing from prose.
 */
function fetchList(query: AuditListQuery): Promise<ListFetchState> {
  return listAuditEntries(query)
    .then((result): ListFetchState => ({ kind: "ok", entries: result.entries, nextCursor: result.next_cursor }))
    .catch((err: ApiError): ListFetchState => {
      const body = err.body as { error?: string; reason_code?: string; decision_id?: number } | undefined;
      if (body?.error === "VALIDATION_FAILED" && typeof body.reason_code === "string") {
        return { kind: "invalid", reasonCode: body.reason_code };
      }
      if (body?.error === "ACTION_REFUSED" && typeof body.reason_code === "string") {
        if (typeof body.decision_id === "number") {
          return { kind: "refused", reasonCode: body.reason_code };
        }
        return { kind: "invalid", reasonCode: body.reason_code };
      }
      throw err;
    });
}

const REFUSAL_MESSAGES: Record<string, string> = {
  actor_not_in_required_group:
    "Your account does not hold a role that can read the audit trail.",
  role_token_unbound:
    "Your session's role token is not currently bound to an active role. Sign in again, or contact an administrator.",
  actor_group_set_stale:
    "Your account's group membership is stale and must be refreshed before the audit trail can be read.",
};

const INVALID_MESSAGES: Record<string, string> = {
  unknown_table_name: "That table name is not one of the audited tables.",
  unknown_operation: "Operation must be one of INSERT, UPDATE or DELETE.",
  occurred_to_before_occurred_from: "The \"to\" date must not be earlier than the \"from\" date.",
  invalid_query_parameter: "One of the filter values could not be parsed.",
  actor_not_in_required_group:
    "The actor filter is only accepted on a security_admin's request; your own rows are already shown without it.",
};

function refusalMessage(reasonCode: string): string {
  return REFUSAL_MESSAGES[reasonCode] ?? `Refused (${reasonCode}).`;
}

function invalidMessage(reasonCode: string): string {
  return INVALID_MESSAGES[reasonCode] ?? `That filter combination was rejected (${reasonCode}).`;
}

const OPERATIONS: readonly AuditOperation[] = ["INSERT", "UPDATE", "DELETE"];

const OPERATION_TONE: Record<string, "neutral"> = { INSERT: "neutral", UPDATE: "neutral", DELETE: "neutral" };

function AuditRow({
  entry,
  selected,
  onSelect,
}: {
  readonly entry: AuditEntrySummaryView;
  readonly selected: boolean;
  readonly onSelect: (entry: AuditEntrySummaryView) => void;
}) {
  return (
    <Box
      role="button"
      tabIndex={0}
      onClick={() => onSelect(entry)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") onSelect(entry);
      }}
      sx={{
        display: "flex",
        flexDirection: "column",
        gap: 0.5,
        p: 1.25,
        border: "1px solid",
        borderColor: selected ? m3.primary : "divider",
        borderRadius: 2,
        cursor: "pointer",
      }}
    >
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
        <Typography variant="body2" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {entry.table_name}
        </Typography>
        <StatusChip tone={OPERATION_TONE[entry.operation] ?? "neutral"} label={entry.operation} dense />
      </Box>
      <Typography variant="caption" color="text.secondary">{entry.occurred_at}</Typography>
      <Typography
        variant="caption"
        sx={{ fontFamily: "ui-monospace, monospace", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
      >
        row {entry.row_pk}
      </Typography>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ fontFamily: "ui-monospace, monospace", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
      >
        actor {entry.actor_fingerprint}
      </Typography>
    </Box>
  );
}

export function AuditScreen() {
  const [tableNameDraft, setTableNameDraft] = useState("");
  const [operationDraft, setOperationDraft] = useState<"" | AuditOperation>("");
  const [occurredFromDraft, setOccurredFromDraft] = useState("");
  const [occurredToDraft, setOccurredToDraft] = useState("");
  const [appliedFilters, setAppliedFilters] = useState<AuditListQuery>({});
  const [selected, setSelected] = useState<AuditEntrySummaryView | null>(null);

  const { data, error, refresh } = useFetchOnMount(() => fetchList(appliedFilters), describeApiError);

  const [extraEntries, setExtraEntries] = useState<AuditEntrySummaryView[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  // A fresh base page (new filters, or a retry) replaces the accumulated
  // "load more" pages rather than appending to a now-stale set.
  useEffect(() => {
    setExtraEntries([]);
    setLoadMoreError(null);
    setCursor(data?.kind === "ok" ? data.nextCursor : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  const applyFilters = () => {
    setSelected(null);
    setAppliedFilters({
      table_name: tableNameDraft.trim() || undefined,
      operation: operationDraft || undefined,
      occurred_from: occurredFromDraft ? new Date(occurredFromDraft).toISOString() : undefined,
      occurred_to: occurredToDraft ? new Date(occurredToDraft).toISOString() : undefined,
    });
    refresh();
  };

  const clearFilters = () => {
    setTableNameDraft("");
    setOperationDraft("");
    setOccurredFromDraft("");
    setOccurredToDraft("");
    setSelected(null);
    setAppliedFilters({});
    refresh();
  };

  const loadMore = () => {
    if (!cursor) return;
    setLoadingMore(true);
    setLoadMoreError(null);
    listAuditEntries({ ...appliedFilters, cursor })
      .then((result) => {
        setExtraEntries((prev) => [...prev, ...result.entries]);
        setCursor(result.next_cursor);
      })
      .catch((err: unknown) => setLoadMoreError(describeApiError(err)))
      .finally(() => setLoadingMore(false));
  };

  const entries = data?.kind === "ok" ? [...data.entries, ...extraEntries] : [];

  let subtitle = "Loading…";
  if (error) subtitle = "Unavailable";
  else if (data?.kind === "refused") subtitle = "Refused";
  else if (data?.kind === "invalid") subtitle = "Filter rejected";
  else if (data?.kind === "ok") subtitle = `${entries.length} row${entries.length === 1 ? "" : "s"} shown`;

  return (
    <ScreenRoot>
      <ScreenHeader title="Audit" subtitle={subtitle} />

      {error && (
        <EmptyPanel title="Audit trail unavailable" body={error}>
          <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
            <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
          </Box>
        </EmptyPanel>
      )}

      {!error && data === null && <EmptyPanel title="Audit" body="Loading…" />}

      {/*
       * Contract §5.1: an actor holding neither role token sees this, and
       * only this -- no filters, no list, no detail pane. The reason is
       * rendered from the server's own reason_code, never invented here.
       */}
      {!error && data?.kind === "refused" && (
        <EmptyPanel title="Audit trail refused" body={refusalMessage(data.reasonCode)} />
      )}

      {!error && data && data.kind !== "refused" && (
        <ListDetail
          list={
            <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
              <Stack spacing={1.25}>
                <TextField
                  label="Table name"
                  size="small"
                  value={tableNameDraft}
                  onChange={(e) => setTableNameDraft(e.target.value)}
                />
                <TextField
                  label="Operation"
                  select
                  size="small"
                  value={operationDraft}
                  onChange={(e) => setOperationDraft(e.target.value as "" | AuditOperation)}
                >
                  <MenuItem value="">Any</MenuItem>
                  {OPERATIONS.map((op) => (
                    <MenuItem key={op} value={op}>{op}</MenuItem>
                  ))}
                </TextField>
                <TextField
                  label="Occurred from"
                  type="datetime-local"
                  size="small"
                  value={occurredFromDraft}
                  onChange={(e) => setOccurredFromDraft(e.target.value)}
                  InputLabelProps={{ shrink: true }}
                />
                <TextField
                  label="Occurred to"
                  type="datetime-local"
                  size="small"
                  value={occurredToDraft}
                  onChange={(e) => setOccurredToDraft(e.target.value)}
                  InputLabelProps={{ shrink: true }}
                />
                <Stack direction="row" spacing={1}>
                  <M3Button emphasis="filled" onClick={applyFilters}>Apply</M3Button>
                  <M3Button emphasis="text" onClick={clearFilters}>Clear</M3Button>
                </Stack>
              </Stack>

              {data.kind === "invalid" && (
                <EmptyPanel title="Filter rejected" body={invalidMessage(data.reasonCode)} />
              )}

              {data.kind === "ok" && entries.length === 0 && (
                <EmptyPanel title="No audit rows" body="Nothing matches the current filters yet." />
              )}

              {data.kind === "ok" && entries.length > 0 && (
                <Stack spacing={1}>
                  {entries.map((entry) => (
                    <AuditRow
                      key={entry.audit_id}
                      entry={entry}
                      selected={selected?.audit_id === entry.audit_id}
                      onSelect={setSelected}
                    />
                  ))}
                </Stack>
              )}

              {data.kind === "ok" && cursor && (
                <Box sx={{ display: "flex", flexDirection: "column", gap: 1, alignItems: "flex-start" }}>
                  <M3Button emphasis="outlined" onClick={loadMore} disabled={loadingMore}>
                    {loadingMore ? "Loading…" : "Load more"}
                  </M3Button>
                  {loadMoreError && <Typography variant="body2" color="error">{loadMoreError}</Typography>}
                </Box>
              )}
            </Box>
          }
          detail={
            <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
              {selected ? (
                <AuditDetailPanel key={selected.audit_id} auditId={selected.audit_id} />
              ) : (
                <EmptyPanel title="No entry selected" body="Select an audit row to see its full field projection." />
              )}
            </Box>
          }
        />
      )}
    </ScreenRoot>
  );
}
