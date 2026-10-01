import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import {
  createCredential,
  deleteCredential,
  listCredentials,
  replaceCredentialSecret,
  type ApiError,
  type CredentialView,
  type SnmpSettings,
} from "../auth/adminApi";

const KIND_LABEL: Record<CredentialView["kind"], string> = {
  ssh_password: "SSH password",
  ssh_private_key: "SSH private key",
  api_password: "API password",
  snmp_v1_v2c: "SNMP v1/v2c",
  snmp_v3: "SNMP v3",
};

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
  const [replaceSecretFor, setReplaceSecretFor] = useState<CredentialView | null>(null);
  const [deleteError, setDeleteError] = useState<Record<string, string>>({});

  const {
    data,
    error: fetchError,
    rawError,
    refresh,
  } = useFetchOnMount(
    () => listCredentials().then((result) => result.credentials ?? []),
    (err) => describeError(err as ApiError),
  );
  const credentials = data;
  const error = fetchError;

  if (error && credentials === null) {
    return isRestricted(rawError)
      ? <RestrictedPanel area="Credentials" role="Security Admin" />
      : <StatePanel variant="error" title="Credentials unavailable" body="The credential store could not be read." code={error}
          action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />;
  }

  if (credentials === null) {
    return <StatePanel variant="empty" title="Credentials" body="Loading…" />;
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
        {createOpen && <CreateCredentialDialog onClose={() => setCreateOpen(false)} onCreated={() => refresh()} />}
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
              <Typography variant="caption" color="text.secondary" component="div">
                {KIND_LABEL[credential.kind]} · {credential.username} · secret set <Ts at={credential.secret_set_at} />
                {credential.community && ` · community: ${credential.community}`}
                {credential.snmp && ` · ${credential.snmp.securityLevel} · auth secret: ${credential.auth_secret} · privacy secret: ${credential.priv_secret}`}
              </Typography>
              {deleteError[credential.credential_id] && (
                <Typography variant="caption" color="error" sx={{ display: "block" }}>
                  {deleteError[credential.credential_id]}
                </Typography>
              )}
            </Box>
            <Stack direction="row" spacing={1} alignItems="center">
              <Button size="small" onClick={() => setReplaceSecretFor(credential)}>
                {credential.kind.startsWith("snmp_") ? "Edit" : "Replace secret"}
              </Button>
              <Button size="small" color="error" onClick={() => handleDelete(credential.credential_id)}>
                Delete
              </Button>
            </Stack>
          </Box>
        ))}
      </Stack>
      {createOpen && <CreateCredentialDialog onClose={() => setCreateOpen(false)} onCreated={() => refresh()} />}
      {replaceSecretFor?.kind.startsWith("snmp_") ? (
        <CreateCredentialDialog credential={replaceSecretFor} onClose={() => setReplaceSecretFor(null)} onCreated={refresh} />
      ) : replaceSecretFor && (
        <ReplaceSecretDialog
          credentialId={replaceSecretFor.credential_id}
          kind={replaceSecretFor.kind}
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
  if (serverError === "ACTION_REFUSED") {
    return "You do not have permission to create credentials.";
  }
  return serverError ?? `request failed (status ${err.status})`;
}

export function CreateCredentialDialog({ onClose, onCreated, onActionRefused, credential }: {
  readonly credential?: CredentialView;
  readonly onClose: () => void;
  readonly onCreated: (credential: CredentialView) => void;
  readonly onActionRefused?: () => void;
  /** Kept for callers; a credential is no longer tied to a vendor (PO, 2026-09-24). */
  readonly initialVendor?: string;
}) {
  const [displayName, setDisplayName] = useState(credential?.display_name ?? "");
  const [kind, setKind] = useState<CredentialView["kind"]>(credential?.kind ?? "ssh_password");
  const [username, setUsername] = useState(credential?.username ?? "");
  const [secret, setSecret] = useState("");
  const [passphrase, setPassphrase] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [level, setLevel] = useState<SnmpSettings["securityLevel"]>(credential?.snmp?.securityLevel ?? "authPriv");
  const [authProtocol, setAuthProtocol] = useState(credential?.snmp?.authProtocol ?? "SHA-256");
  const [privProtocol, setPrivProtocol] = useState(credential?.snmp?.privProtocol ?? "AES-128");
  const v3 = kind === "snmp_v3";
  const community = kind === "snmp_v1_v2c";
  const needsAuth = v3 && level !== "noAuthNoPriv";
  const needsPrivacy = v3 && level === "authPriv";
  const snmp: SnmpSettings | undefined = v3 ? {
    securityLevel: level, authProtocol: needsAuth ? authProtocol : null, privProtocol: needsPrivacy ? privProtocol : null,
  } : undefined;
  const submit = () => {
    const primary = v3 && !needsAuth ? "" : secret;
    const secondary = needsPrivacy || kind === "ssh_private_key" ? passphrase : "";
    return credential
      ? replaceCredentialSecret(credential.credential_id, primary, secondary, snmp, v3 ? username : undefined)
      : createCredential(displayName, kind, community ? "" : username, false, false, primary, secondary, snmp);
  };

  return (
    <Dialog open onClose={onClose}>
      <DialogTitle>{credential ? "Edit SNMP credential" : "Add credential"}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1, minWidth: 360 }}>
          <TextField label="Display name" disabled={!!credential} value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoFocus />
          <TextField label="Kind" select disabled={!!credential} value={kind} onChange={(e) => { setKind(e.target.value as CredentialView["kind"]); setSecret(""); setPassphrase(""); }}>
            <MenuItem value="ssh_password">SSH password</MenuItem>
            <MenuItem value="ssh_private_key">SSH private key</MenuItem>
            <MenuItem value="api_password">API password</MenuItem>
            <MenuItem value="snmp_v1_v2c">SNMP v1/v2c</MenuItem>
            <MenuItem value="snmp_v3">SNMP v3</MenuItem>
          </TextField>
          {!community && <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} />}
          {community && <Typography color="warning.main">Community sent in clear text.</Typography>}
          {v3 && <>
            <TextField select label="Security level" value={level} onChange={(e) => {
              setLevel(e.target.value as SnmpSettings["securityLevel"]); setSecret(""); setPassphrase("");
            }}>
              {["noAuthNoPriv", "authNoPriv", "authPriv"].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}
            </TextField>
            {level !== "authPriv" && <Typography color="warning.main">No privacy, data exposed.</Typography>}
            {needsAuth && <TextField select label="Authentication protocol" value={authProtocol} onChange={(e) => setAuthProtocol(e.target.value)}>
              {["SHA-256", "SHA-384", "SHA-512", "SHA-224", "SHA1", "MD5"].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}
            </TextField>}
            {needsPrivacy && <TextField select label="Privacy protocol" value={privProtocol} onChange={(e) => setPrivProtocol(e.target.value)}>
              {["AES-128", "AES-192", "AES-256", "DES"].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}
            </TextField>}
            {((needsAuth && authProtocol === "MD5") || (needsPrivacy && privProtocol === "DES")) &&
              <Typography color="warning.main">MD5/DES are weak.</Typography>}
            {credential && <Typography variant="caption">Enter new secrets for the selected security level. Stored values are never displayed.</Typography>}
          </>}
          {(!v3 || needsAuth) &&
          <TextField
            label={community ? "Community" : v3 ? "Authentication secret" : kind === "ssh_private_key" ? "Private key (PEM)" : "Secret"}
            type={kind === "ssh_private_key" ? "text" : "password"}
            multiline={kind === "ssh_private_key"}
            minRows={kind === "ssh_private_key" ? 4 : undefined}
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
          />}
          {(kind === "ssh_private_key" || needsPrivacy) && (
            <TextField
              label={v3 ? "Privacy secret" : "Passphrase (optional)"}
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
          disabled={!displayName.trim() || (!community && !username.trim()) || ((!v3 || needsAuth) && !secret) || (needsPrivacy && !passphrase)}
          onClick={() =>
            submit()
              .then((credential) => {
                onCreated(credential);
                onClose();
              })
              .catch((err: ApiError) => {
                setError(describeError(err));
                if (err.body?.error === "ACTION_REFUSED") onActionRefused?.();
              })
          }
        >
          {credential ? "Save" : "Add"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ReplaceSecretDialog({
  credentialId,
  kind,
  onClose,
  onDone,
}: {
  readonly credentialId: string;
  readonly kind: CredentialView["kind"];
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
