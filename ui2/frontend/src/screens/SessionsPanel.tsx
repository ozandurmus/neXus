import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { listSessions, revokeSession, type ApiError } from "../auth/adminApi";
import { M3Button } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";

function duration(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600} hours`;
  if (seconds % 60 === 0) return `${seconds / 60} minutes`;
  return `${seconds} seconds`;
}

function describeError(error: ApiError): string {
  return typeof error.body?.error === "string" ? error.body.error : `Request failed (status ${error.status})`;
}

export function SessionsPanel() {
  const { data, error, rawError, refresh } = useFetchOnMount(listSessions, (value) => describeError(value as ApiError));

  if (error && data === null) {
    return isRestricted(rawError)
      ? <RestrictedPanel area="Sessions" role="Security Admin" />
      : <StatePanel variant="error" title="Sessions unavailable" body="Active sessions could not be read." code={error}
          action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />;
  }
  if (data === null) return <StatePanel variant="empty" title="Sessions" body="Loading…" />;

  const counts = data.sessions.reduce<Record<string, number>>((result, session) => {
    result[session.actor_fingerprint] = (result[session.actor_fingerprint] ?? 0) + 1;
    return result;
  }, {});

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Stack direction="row" spacing={3}>
        <Typography>{data.sessions.length} active sessions</Typography>
        <Typography>Idle timeout: {duration(data.idle_timeout_seconds)}</Typography>
        <Typography>Absolute lifetime: {duration(data.absolute_lifetime_seconds)}</Typography>
      </Stack>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small" aria-label="Active sessions">
          <TableHead>
            <TableRow sx={{ bgcolor: "action.hover" }}>
              <TableCell>Identity</TableCell>
              <TableCell>Created</TableCell>
              <TableCell>Last seen</TableCell>
              <TableCell>Idle deadline</TableCell>
              <TableCell>Absolute expiry</TableCell>
              <TableCell align="right">Action</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {data.sessions.map((session) => (
              <TableRow key={session.session_id}>
                <TableCell>
                  {data.identity_labels[session.session_id] ?? "Identity unavailable"} · {counts[session.actor_fingerprint]} active
                </TableCell>
                <TableCell><Ts at={session.created_at} /></TableCell>
                <TableCell><Ts at={session.last_seen_at} /></TableCell>
                <TableCell><Ts at={session.idle_deadline_at} /></TableCell>
                <TableCell><Ts at={session.absolute_expires_at} /></TableCell>
                <TableCell align="right">
                  <Button size="small" color="error" onClick={() => revokeSession(session.session_id).then(refresh)}>
                    Revoke
                  </Button>
                </TableCell>
              </TableRow>
            ))}
            {data.sessions.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} align="center">No active sessions</TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}
