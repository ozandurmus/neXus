import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import {
  createCredential,
  deleteCredential,
  listCredentials,
  replaceCredentialSecret,
  type ApiError,
  type CredentialView,
} from "../auth/adminApi";

const KIND_LABEL: Record<CredentialView["kind"], string> = {
  ssh_password: "SSH password",
  ssh_private_key: "SSH private key",
  api_password: "API password",
};

function vendorLabel(view: CredentialView): string {
  const vendors: string[] = [];
  if (view.allows_check_point) vendors.push("Check Point");
  if (view.allows_palo_alto) vendors.push("Palo Alto");
  return vendors.join(", ");
}

/**
 * 2026-09-14 PO decision record section 3 (CS-1..CS-5): the operator's own
 * view of the credential store. Lists exactly the fields the server returns
 * and nothing it forbids -- the secret field is write-only, and nothing on
 * this screen ever displays it back. Mirrors {@code LocalIdentitiesPanel}
 * exactly: this component never decides what to render based on a role
 * token (AG-J3, {@code NoRoleConditionalRenderingInFrontendTest}).
 */
export function CredentialsPanel() {
  const [createOpen, setCreateOpen] = useState(false);
  const [replaceSecretFor, setReplaceSecretFor] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<Record<string, string>>({});

  const {
    data,
    error: fetchError,
    refresh,
  } = useFetchOnMount(
    () => listCredentials().then((result) => result.credentials ?? []),
    (err) => describeError(err as ApiError),
  );
  const credentials = data;
  const error = fetchError;

  if (error) {
    return (
      <EmptyPanel title="Credentials unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={refresh}>
            Retry
          </M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  if (credentials === null) {
    return <EmptyPanel title="Credentials" body="Loading…" />;
  }

  const handleDelete = (credentialId: string) => {
    setDeleteError((prev) => ({ ...prev, [credentialId]: "" }));
    deleteCredential(credentialId)
      .then(refresh)
      .catch((err: ApiError) => setDeleteError((prev) => ({ ...prev, [credentialId]: describeError(err) })));
  };

  if (credentials.length === 0) {
    return (
      <EmptyPanel
        title="No credential stored"
        body="Credentials are stored outside the repository, encrypted at rest, and never written into a report or a support bundle."
      >
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="filled" onClick={() => setCreateOpen(true)}>
            Add credential
          </M3Button>
        </Box>
        {createOpen && <CreateCredentialDialog onClose={() => setCreateOpen(false)} onCreated={refresh} />}
      </EmptyPanel>
    );
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, minHeight: 0 }}>
      <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
        <M3Button emphasis="filled" onClick={() => setCreateOpen(true)}>
          Add credential
        </M3Button>
      </Box>
      <Stack spacing={1.5}>
        {credentials.map((credential) => (
          <Box
            key={credential.credential_id}
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
              <Typography variant="body1">{credential.display_name}</Typography>
              <Typography variant="caption" color="text.secondary">
                {KIND_LABEL[credential.kind]} · {credential.username} · secret set {credential.secret_set_at}
              </Typography>
              {deleteError[credential.credential_id] && (
                <Typography variant="caption" color="error" sx={{ display: "block" }}>
                  {deleteError[credential.credential_id]}
                </Typography>
              )}
            </Box>
            <Stack direction="row" spacing={1} alignItems="center">
              <StatusChip tone="neutral" label={vendorLabel(credential)} dense />
              <Button size="small" onClick={() => setReplaceSecretFor(credential.credential_id)}>
                Replace secret
              </Button>
              <Button size="small" color="error" onClick={() => handleDelete(credential.credential_id)}>
                Delete
              </Button>
            </Stack>
          </Box>
        ))}
      </Stack>
      {createOpen && <CreateCredentialDialog onClose={() => setCreateOpen(false)} onCreated={refresh} />}
      {replaceSecretFor && (
        <ReplaceSecretDialog
          credentialId={replaceSecretFor}
          onClose={() => setReplaceSecretFor(null)}
          onDone={refresh}
        />
      )}
    </Box>
  );
}

function describeError(err: ApiError): string {
  const serverError = typeof err.body?.error === "string" ? (err.body.error as string) : undefined;
  // CS-4: shown plainly, exactly as the server states it -- never re-interpreted or softened.
  if (serverError === "CREDENTIAL_IN_USE") {
    return "This credential is in use by a device and cannot be deleted.";
  }
  return serverError ?? `request failed (status ${err.status})`;
}

function CreateCredentialDialog({ onClose, onCreated }: { readonly onClose: () => void; readonly onCreated: () => void }) {
  const [displayName, setDisplayName] = useState("");
  const [kind, setKind] = useState<CredentialView["kind"]>("ssh_password");
  const [username, setUsername] = useState("");
  const [allowsCheckPoint, setAllowsCheckPoint] = useState(false);
  const [allowsPaloAlto, setAllowsPaloAlto] = useState(false);
  const [secret, setSecret] = useState("");
  const [passphrase, setPassphrase] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <Dialog open onClose={onClose}>
      <DialogTitle>Add credential</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1, minWidth: 360 }}>
          <TextField label="Display name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoFocus />
          <TextField label="Kind" select value={kind} onChange={(e) => setKind(e.target.value as CredentialView["kind"])}>
            <MenuItem value="ssh_password">SSH password</MenuItem>
            <MenuItem value="ssh_private_key">SSH private key</MenuItem>
            <MenuItem value="api_password">API password</MenuItem>
          </TextField>
          <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} />
          <Stack direction="row" spacing={1}>
            <FormControlLabel
              control={<Checkbox checked={allowsCheckPoint} onChange={(e) => setAllowsCheckPoint(e.target.checked)} />}
              label="Check Point"
            />
            <FormControlLabel
              control={<Checkbox checked={allowsPaloAlto} onChange={(e) => setAllowsPaloAlto(e.target.checked)} />}
              label="Palo Alto"
            />
          </Stack>
          <TextField
            label={kind === "ssh_private_key" ? "Private key (PEM)" : "Secret"}
            type={kind === "ssh_private_key" ? "text" : "password"}
            multiline={kind === "ssh_private_key"}
            minRows={kind === "ssh_private_key" ? 4 : undefined}
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
          />
          {kind === "ssh_private_key" && (
            <TextField
              label="Passphrase (optional)"
              type="password"
              value={passphrase}
              onChange={(e) => setPassphrase(e.target.value)}
            />
          )}
          {error && <Typography color="error">{error}</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!allowsCheckPoint && !allowsPaloAlto}
          onClick={() =>
            createCredential(displayName, kind, username, allowsCheckPoint, allowsPaloAlto, secret, passphrase)
              .then(() => {
                onCreated();
                onClose();
              })
              .catch((err: ApiError) => setError(describeError(err)))
          }
        >
          Add
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ReplaceSecretDialog({
  credentialId,
  onClose,
  onDone,
}: {
  readonly credentialId: string;
  readonly onClose: () => void;
  readonly onDone: () => void;
}) {
  const [secret, setSecret] = useState("");
  const [passphrase, setPassphrase] = useState("");
  const [error, setError] = useState<string | null>(null);

  return (
    <Dialog open onClose={onClose}>
      <DialogTitle>Replace secret</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1, minWidth: 320 }}>
          <TextField label="New secret" type="password" value={secret} onChange={(e) => setSecret(e.target.value)} autoFocus />
          <TextField
            label="Passphrase (optional)"
            type="password"
            value={passphrase}
            onChange={(e) => setPassphrase(e.target.value)}
          />
          {error && <Typography color="error">{error}</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() =>
            replaceCredentialSecret(credentialId, secret, passphrase)
              .then(() => {
                onDone();
                onClose();
              })
              .catch((err: ApiError) => setError(describeError(err)))
          }
        >
          Replace secret
        </Button>
      </DialogActions>
    </Dialog>
  );
}
