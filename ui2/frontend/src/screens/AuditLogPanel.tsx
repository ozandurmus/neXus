import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listAuditEvents, type ApiError } from "../auth/adminApi";

function describeError(err: ApiError): string {
  return typeof err.body?.error === "string" ? err.body.error : `request failed (status ${err.status})`;
}

/** The server returns only the bounded summary; no audit snapshot is rendered here. */
export function AuditLogPanel() {
  const { data, error, rawError, refresh } = useFetchOnMount(
    () => listAuditEvents().then((result) => result.events ?? []),
    (err) => describeError(err as ApiError),
  );

  if (error && data === null) {
    return isRestricted(rawError)
      ? <RestrictedPanel area="Audit log" role="Security Admin" />
      : <StatePanel variant="error" title="Audit log unavailable" body="Audit events could not be read." code={error}
          action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />;
  }
  if (data === null) return <StatePanel variant="empty" title="Audit log" body="Loading…" />;
  if (data.length === 0) return <EmptyPanel title="No audit events" body="No audited management action has been recorded." />;

  return (
    <Table size="small" aria-label="Audit log">
      <TableHead>
        <TableRow>
          <TableCell>ID</TableCell><TableCell>Occurred</TableCell><TableCell>Actor</TableCell><TableCell>Action</TableCell>
          <TableCell>Outcome</TableCell><TableCell>Target</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {data.map((event) => (
          <TableRow key={event.id}>
            <TableCell>{event.id}</TableCell><TableCell><Ts at={event.occurred_at} /></TableCell><TableCell>{event.actor}</TableCell>
            <TableCell>{event.action}</TableCell><TableCell>{event.outcome}</TableCell><TableCell>{event.target}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
