import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { listSessions, revokeSession, type ApiError, type SessionView } from "../auth/adminApi";
import { DataTable } from "../shell/DataTable";
import { Timestamp } from "../shell/Timestamp";
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
      <DataTable<SessionView>
        ariaLabel="Active sessions"
        columns={[
          { key: "identity", header: "Identity", render: (session) => `${data.identity_labels[session.session_id] ?? "Identity unavailable"} · ${counts[session.actor_fingerprint]} active` },
          // the two past instants as ages (exact on hover), the two deadlines as minute stamps: the identity column keeps the room
          { key: "created", header: "Created", width: 120, mono: true, render: (session) => <Timestamp at={session.created_at} compact />, title: (session) => session.created_at },
          { key: "seen", header: "Last seen", width: 120, mono: true, render: (session) => <Timestamp at={session.last_seen_at} compact />, title: (session) => session.last_seen_at },
          { key: "idle", header: "Idle deadline", width: 200, mono: true, render: (session) => <Ts at={session.idle_deadline_at} relative={false} />, title: (session) => session.idle_deadline_at },
          { key: "expiry", header: "Absolute expiry", width: 200, mono: true, render: (session) => <Ts at={session.absolute_expires_at} relative={false} />, title: (session) => session.absolute_expires_at },
        ]}
        rows={data.sessions}
        rowKey={(session) => session.session_id}
        emptyText="No active sessions"
        actions={(session) => [{ label: "Revoke", destructive: true, onSelect: () => { void revokeSession(session.session_id).then(refresh); } }]}
      />
    </Box>
  );
}
