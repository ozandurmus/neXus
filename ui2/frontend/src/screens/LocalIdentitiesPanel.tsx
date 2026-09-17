import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import MenuItem from "@mui/material/MenuItem";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import {
  createLocalIdentity,
  createRoleBinding,
  disableLocalIdentity,
  enableLocalIdentity,
  listLocalIdentities,
  setLocalIdentityPassword,
  type ApiError,
  type LocalIdentityView,
} from "../auth/adminApi";

/**
 * 13G ({@code PO_DECISION_RECORD_2026_09_13G}) section 3: the operator's own
 * view of local identity administration. Lists exactly the fields the
 * server returns and nothing it forbids; refusals are shown verbatim as the
 * server states them (never re-interpreted into a stronger or weaker
 * claim). Role assignment goes through the existing role-bindings endpoint
 * (LIA-3.4) -- this component never decides what to render based on a role
 * token, it only displays whatever string the operator or the server
 * supplies (AG-J3, {@code NoRoleConditionalRenderingInFrontendTest}).
 */
export function LocalIdentitiesPanel() {
  const [createOpen, setCreateOpen] = useState(false);
  const [passwordDialogFor, setPasswordDialogFor] = useState<string | null>(null);
  const [roleDialogFor, setRoleDialogFor] = useState<string | null>(null);

  const {
    data,
    error: fetchError,
    refresh,
  } = useFetchOnMount(
    () => listLocalIdentities().then((result) => result.identities ?? []),
    (err) => describeError(err as ApiError),
  );
  const identities = data;
  const [mutationError, setMutationError] = useState<string | null>(null);
  const error = fetchError ?? mutationError;

  if (error) {
    return (
      <EmptyPanel title="Local identities unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={refresh}>
            Retry
          </M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  if (identities === null) {
    return <EmptyPanel title="Local identities" body="Loading…" />;
  }

  if (identities.length === 0) {
    return (
      <EmptyPanel title="No local identity yet" body="Create one to administer sign-in and role bindings for it.">
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="filled" onClick={() => setCreateOpen(true)}>
            Create identity
          </M3Button>
        </Box>
        {createOpen && (
          <CreateIdentityDialog onClose={() => setCreateOpen(false)} onCreated={refresh} />
        )}
      </EmptyPanel>
    );
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, minHeight: 0 }}>
      <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
        <M3Button emphasis="filled" onClick={() => setCreateOpen(true)}>
          Create identity
        </M3Button>
      </Box>
      <Stack spacing={1.5}>
        {identities.map((identity) => (
          <Box
            key={identity.local_identity_id}
            sx={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              border: "1px solid",
              borderColor: "divider",
              borderRadius: 2,
              p: 1.5,
              gap: 2,
            }}
          >
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="body1">{identity.local_identity_name}</Typography>
              <Typography variant="caption" color="text.secondary">
                created {identity.created_at} · password set {identity.password_set_at}
                {identity.must_change_password ? " · must change password at next sign-in" : ""}
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} alignItems="center">
              <StatusChip tone={identity.enabled ? "ok" : "neutral"} label={identity.enabled ? "Enabled" : "Disabled"} dense />
              <Button size="small" onClick={() => setPasswordDialogFor(identity.local_identity_id)}>
                Set password
              </Button>
              <Button size="small" onClick={() => setRoleDialogFor(identity.local_identity_id)}>
                Assign role
              </Button>
              {identity.enabled ? (
                <Button
                  size="small"
                  color="error"
                  onClick={() =>
                    disableLocalIdentity(identity.local_identity_id)
                      .then(refresh)
                      .catch((err: ApiError) => setMutationError(describeError(err)))
                  }
                >
                  Disable
                </Button>
              ) : (
                <Button
                  size="small"
                  onClick={() =>
                    enableLocalIdentity(identity.local_identity_id)
                      .then(refresh)
                      .catch((err: ApiError) => setMutationError(describeError(err)))
                  }
                >
                  Enable
                </Button>
              )}
            </Stack>
          </Box>
        ))}
      </Stack>
      {createOpen && <CreateIdentityDialog onClose={() => setCreateOpen(false)} onCreated={refresh} />}
      {passwordDialogFor && (
        <SetPasswordDialog
          localIdentityId={passwordDialogFor}
          onClose={() => setPasswordDialogFor(null)}
          onDone={refresh}
        />
      )}
      {roleDialogFor && (
        <AssignRoleDialog localIdentityId={roleDialogFor} onClose={() => setRoleDialogFor(null)} onDone={refresh} />
      )}
    </Box>
  );
}

function describeError(err: ApiError): string {
  const serverError = typeof err.body?.error === "string" ? (err.body.error as string) : undefined;
  return serverError ?? `request failed (status ${err.status})`;
}

function CreateIdentityDialog({ onClose, onCreated }: { readonly onClose: () => void; readonly onCreated: () => void }) {
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <Dialog open onClose={onClose}>
      <DialogTitle>Create local identity</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1, minWidth: 320 }}>
          <TextField label="Display name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          <TextField
            label="Initial password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {error && <Typography color="error">{error}</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() =>
            createLocalIdentity(name, password)
              .then(() => {
                onCreated();
                onClose();
              })
              .catch((err: ApiError) => setError(describeError(err)))
          }
        >
          Create
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function SetPasswordDialog({
  localIdentityId,
  onClose,
  onDone,
}: {
  readonly localIdentityId: string;
  readonly onClose: () => void;
  readonly onDone: () => void;
}) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <Dialog open onClose={onClose}>
      <DialogTitle>Set password</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1, minWidth: 320 }}>
          <TextField
            label="New password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoFocus
          />
          {error && <Typography color="error">{error}</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() =>
            setLocalIdentityPassword(localIdentityId, password)
              .then(() => {
                onDone();
                onClose();
              })
              .catch((err: ApiError) => setError(describeError(err)))
          }
        >
          Set password
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * LIA-3.4: assigns a role token to this identity through the existing
 * role-bindings endpoint. The identity's own opaque id is the group
 * reference (C3A §7.1's pattern for a local identity); the role token and
 * key id are typed by the operator, never chosen by this component --
 * displayed and submitted as plain strings, never used here to decide what
 * renders.
 */
function AssignRoleDialog({
  localIdentityId,
  onClose,
  onDone,
}: {
  readonly localIdentityId: string;
  readonly onClose: () => void;
  readonly onDone: () => void;
}) {
  const [roleToken, setRoleToken] = useState("");
  const [groupReferenceKeyId, setGroupReferenceKeyId] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <Dialog open onClose={onClose}>
      <DialogTitle>Assign role</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1, minWidth: 320 }}>
          <TextField select label="Role token" value={roleToken} onChange={(e) => setRoleToken(e.target.value)} autoFocus>
            <MenuItem value="role:security_admin">Security Admin</MenuItem>
            <MenuItem value="role:compliance_admin">Compliance Admin</MenuItem>
            <MenuItem value="role:backup_admin">Backup Admin</MenuItem>
            <MenuItem value="role:onboarding_admin">Onboarding Admin</MenuItem>
            <MenuItem value="role:operator">Operator</MenuItem>
            <MenuItem value="role:viewer">Viewer</MenuItem>
          </TextField>
          <TextField
            label="Group reference key id"
            value={groupReferenceKeyId}
            onChange={(e) => setGroupReferenceKeyId(e.target.value)}
          />
          {error && <Typography color="error">{error}</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() =>
            createRoleBinding(roleToken, localIdentityId, groupReferenceKeyId)
              .then(() => {
                onDone();
                onClose();
              })
              .catch((err: ApiError) => setError(describeError(err)))
          }
        >
          Assign
        </Button>
      </DialogActions>
    </Dialog>
  );
}
