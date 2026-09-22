import React, { useState } from "react";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TablePagination from "@mui/material/TablePagination";
import Paper from "@mui/material/Paper";
import TableContainer from "@mui/material/TableContainer";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listAuditEvents, type ApiError } from "../auth/adminApi";

function describeError(err: ApiError): string {
  return typeof err.body?.error === "string" ? err.body.error : `request failed (status ${err.status})`;
}

export function AuditLogsPanel() {
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(10);

  const { data, error, rawError, refresh } = useFetchOnMount(
    () => listAuditEvents().then((result) => result.events ?? []),
    (err) => describeError(err as ApiError),
  );

  const handleChangePage = (event: unknown, newPage: number) => {
    setPage(newPage);
  };

  const handleChangeRowsPerPage = (event: React.ChangeEvent<HTMLInputElement>) => {
    setRowsPerPage(parseInt(event.target.value, 10));
    setPage(0);
  };

  if (error && data === null) {
    return isRestricted(rawError)
      ? <RestrictedPanel area="Audit Logs" role="Security Admin" />
      : <StatePanel variant="error" title="Audit logs unavailable" body="Audit events could not be read." code={error}
          action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />;
  }
  if (data === null) return <StatePanel variant="empty" title="Audit logs" body="Loading…" />;
  if (data.length === 0) return <EmptyPanel title="No audit events" body="No audited management action has been recorded." />;

  const displayedRows = data.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage);

  return (
    <Paper sx={{ width: '100%', overflow: 'hidden' }}>
      <TableContainer sx={{ maxHeight: 600 }}>
        <Table stickyHeader size="small" aria-label="Audit logs table">
          <TableHead>
            <TableRow>
              <TableCell>Timestamp</TableCell>
              <TableCell>Actor</TableCell>
              <TableCell>Action</TableCell>
              <TableCell>Target</TableCell>
              <TableCell>Reason / Outcome</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {displayedRows.map((event) => (
              <TableRow key={event.id} hover>
                <TableCell><Ts at={event.occurred_at} /></TableCell>
                <TableCell>{event.actor}</TableCell>
                <TableCell>{event.action}</TableCell>
                <TableCell>{event.target}</TableCell>
                <TableCell>{event.outcome}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination
        rowsPerPageOptions={[10, 25, 50]}
        component="div"
        count={data.length}
        rowsPerPage={rowsPerPage}
        page={page}
        onPageChange={handleChangePage}
        onRowsPerPageChange={handleChangeRowsPerPage}
      />
    </Paper>
  );
}
