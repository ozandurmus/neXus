import { useState } from "react";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { listCredentials, retryDeviceConfirm, setDeviceCredential, setDeviceSecret, type CredentialView, type DeviceSummary } from "../auth/adminApi";
import { useFetchOnMount } from "./useFetchOnMount";
import { deviceNameLabel } from "./deviceCopy";
import { m3 } from "../theme/m3Theme";

/**
 * Change a device's credentials after it was added (PO, 2026-09-24: "user değiştirme imkanım olmalı"): the login
 * credential, and for Radware the export passphrase. References only, never values. A device still waiting for its
 * first confirm is checked again with the new credential.
 */
export function ChangeCredentialsDialog({ device, onClose, onChanged }: {
  readonly device: DeviceSummary;
  readonly onClose: () => void;
  readonly onChanged: () => void;
}) {
  const { data: credentials, error: listError } = useFetchOnMount(
    () => listCredentials().then((r) => r.credentials ?? []),
    (e) => String((e as { message?: string }).message ?? e),
  );
  const [login, setLogin] = useState("");
  const [passphrase, setPassphrase] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isRadware = device.vendor_hint === "radware";
  const isDraft = device.enrollment_state === "DRAFT";
  // Only Check Point is reached with an SSH key; every other vendor takes a username and password.
  const eligible = (credentials ?? []).filter((c: CredentialView) => device.vendor_hint === "check_point" || c.kind !== "ssh_private_key");

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      if (login) await setDeviceCredential(device.device_id, login);
      if (isRadware && passphrase) await setDeviceSecret(device.device_id, "export_passphrase", passphrase);
      if (login && isDraft) await retryDeviceConfirm(device.device_id);
      onChanged();
      onClose();
    } catch (e) {
      const body = (e as { body?: { error?: string } }).body;
      setError(body?.error ?? String((e as { message?: string }).message ?? e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={() => !busy && onClose()} fullWidth maxWidth="xs">
      <DialogTitle>Change credentials · {deviceNameLabel(device.hostname)}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField label="Login credential" select size="small" fullWidth value={login} onChange={(e) => setLogin(e.target.value)}
            helperText={listError ?? "Leave empty to keep the current one."}>
            {eligible.map((c) => <MenuItem key={c.credential_id} value={c.credential_reference_id}>{c.display_name}</MenuItem>)}
          </TextField>
          {isRadware && (
            <TextField label="Export passphrase credential" select size="small" fullWidth value={passphrase}
              onChange={(e) => setPassphrase(e.target.value)} helperText="Encrypts the private keys in the backup. Leave empty to keep the current one.">
              {eligible.map((c) => <MenuItem key={c.credential_id} value={c.credential_reference_id}>{c.display_name}</MenuItem>)}
            </TextField>
          )}
          {isDraft && login && (
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              This device is not confirmed yet: saving checks it again with the new credential.
            </Typography>
          )}
          {error && <Typography variant="body2" color="error">{error}</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>Cancel</Button>
        <Button variant="contained" onClick={() => void save()} disabled={busy || (!login && !passphrase)}>
          {busy ? "Saving…" : "Save"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
