import Box from "@mui/material/Box";
import Divider from "@mui/material/Divider";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import type { Tone } from "../shell/tone";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import {
  getAuditEntry,
  type ApiError,
  type AuditChangeStateView,
  type AuditEntryDetailView,
  type AuditFieldProjectionView,
} from "../auth/adminApi";

/**
 * Detail side of the Audit screen
 * (`UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §3.2, §4.2). Mirrors
 * `DeviceInventoryPanels`'s own "fetch on selection" shape: mounted fresh
 * (by `key`) each time a different row is selected.
 */

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

type DetailFetchState = { readonly kind: "ok"; readonly detail: AuditEntryDetailView } | { readonly kind: "not_found" };

function fetchDetail(auditId: number): Promise<DetailFetchState> {
  return getAuditEntry(auditId)
    .then((detail): DetailFetchState => ({ kind: "ok", detail }))
    .catch((err: ApiError) => {
      // §4.2: an id out of scope is indistinguishable from one that never
      // existed -- both render the same "not found" state, never a
      // different message for the two cases.
      if (err.status === 404) {
        return { kind: "not_found" } as const;
      }
      throw err;
    });
}

const PROJECTION_TONE: Record<AuditFieldProjectionView["state"], Tone> = {
  PRESENT: "ok",
  NULL: "neutral",
  REDACTED: "warn",
  ABSENT: "neutral",
  UNCLASSIFIED: "bad",
};

const PROJECTION_LABEL: Record<AuditFieldProjectionView["state"], string> = {
  PRESENT: "value",
  NULL: "null",
  REDACTED: "redacted",
  ABSENT: "not present",
  UNCLASSIFIED: "unclassified",
};

const CHANGE_TONE: Record<AuditChangeStateView, Tone> = {
  CHANGED: "attn",
  UNCHANGED: "neutral",
  NOT_EVALUABLE: "neutral",
};

/**
 * Contract §3.2's five payload shapes, rendered so no two are visually
 * interchangeable: distinct chip, distinct body copy, and only `PRESENT`
 * ever shows a value pulled from the row itself.
 */
function ProjectionValue({ projection }: { readonly projection: AuditFieldProjectionView }) {
  switch (projection.state) {
    case "PRESENT":
      return (
        <Typography variant="body2" sx={{ fontFamily: "ui-monospace, monospace", wordBreak: "break-word" }}>
          {typeof projection.value === "string" ? projection.value : JSON.stringify(projection.value)}
        </Typography>
      );
    case "NULL":
      return (
        <Typography variant="body2" sx={{ fontStyle: "italic" }} color="text.secondary">
          null
        </Typography>
      );
    case "REDACTED":
      return (
        <Stack spacing={0.25}>
          <Typography variant="body2" sx={{ fontStyle: "italic" }}>
            Tier {projection.tier}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {projection.reason}
          </Typography>
        </Stack>
      );
    case "ABSENT":
      return (
        <Typography variant="body2" sx={{ fontStyle: "italic" }} color="text.secondary">
          not present in this snapshot
        </Typography>
      );
    case "UNCLASSIFIED":
      return (
        <Typography variant="body2" color="error">
          {projection.table_name}.{projection.column_name} is not yet classified for display
        </Typography>
      );
  }
}

function FieldProjectionTable({
  title,
  fields,
  changeStates,
}: {
  readonly title: string;
  readonly fields: Record<string, AuditFieldProjectionView>;
  readonly changeStates?: Record<string, AuditChangeStateView>;
}) {
  const keys = Object.keys(fields);
  if (keys.length === 0) {
    return (
      <Box>
        <Typography variant="h4" sx={{ mb: 1 }}>{title}</Typography>
        <Typography variant="body2" color="text.secondary">No snapshot on this side of the row.</Typography>
      </Box>
    );
  }
  return (
    <Box>
      <Typography variant="h4" sx={{ mb: 1 }}>{title}</Typography>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Column</TableCell>
            <TableCell>State</TableCell>
            <TableCell>Value</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {keys.map((key) => {
            const projection = fields[key];
            const changeState = changeStates?.[key];
            return (
              <TableRow key={key}>
                <TableCell sx={{ verticalAlign: "top" }}>
                  <Stack spacing={0.5}>
                    <Typography variant="body2" sx={{ fontFamily: "ui-monospace, monospace" }}>{key}</Typography>
                    {changeState && <StatusChip tone={CHANGE_TONE[changeState]} label={changeState} dense />}
                  </Stack>
                </TableCell>
                <TableCell sx={{ verticalAlign: "top" }}>
                  <StatusChip tone={PROJECTION_TONE[projection.state]} label={PROJECTION_LABEL[projection.state]} dense />
                </TableCell>
                <TableCell sx={{ verticalAlign: "top" }}>
                  <ProjectionValue projection={projection} />
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </Box>
  );
}

function MetadataRow({ label, value, monospace = false }: { readonly label: string; readonly value: string; readonly monospace?: boolean }) {
  return (
    <Box sx={{ display: "flex", gap: 1.5 }}>
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 140, flex: "none" }}>{label}</Typography>
      <Typography variant="body2" sx={{ fontFamily: monospace ? "ui-monospace, monospace" : undefined, wordBreak: "break-word" }}>
        {value}
      </Typography>
    </Box>
  );
}

export function AuditDetailPanel({ auditId }: { readonly auditId: number }) {
  const { data, error, refresh } = useFetchOnMount(() => fetchDetail(auditId), describeApiError);

  if (error) {
    return (
      <EmptyPanel title="Audit entry unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }
  if (data === null) {
    return <EmptyPanel title="Audit entry" body="Loading…" />;
  }
  if (data.kind === "not_found") {
    return (
      <EmptyPanel
        title="Not found"
        body="This audit entry does not exist, or is outside what your role can read. Those two cases look the same on purpose."
      />
    );
  }

  const detail = data.detail;
  return (
    <Stack spacing={2.5} sx={{ minHeight: 0, overflow: "auto" }}>
      <Box sx={{ bgcolor: m3.scLowest, borderRadius: "16px", p: 2.5, boxShadow: m3.e1 }}>
        <Stack spacing={1}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 0.5 }}>
            <Typography variant="h3">Audit entry {detail.audit_id}</Typography>
            <StatusChip tone="neutral" label={detail.operation} />
          </Box>
          <MetadataRow label="Occurred at" value={detail.occurred_at} />
          <MetadataRow label="Table" value={detail.table_name} />
          <MetadataRow label="Row" value={detail.row_pk} monospace />
          <MetadataRow label="Actor fingerprint" value={detail.actor_fingerprint} monospace />
          <MetadataRow label="Action" value={detail.action_id} monospace />
          <MetadataRow label="Correlation run" value={detail.correlation_run_id ?? "—"} monospace />
        </Stack>
      </Box>

      <Divider />

      <Stack spacing={2.5}>
        <FieldProjectionTable title="Before" fields={detail.before_fields} changeStates={detail.change_states} />
        <FieldProjectionTable title="After" fields={detail.after_fields} changeStates={detail.change_states} />
      </Stack>
    </Stack>
  );
}
