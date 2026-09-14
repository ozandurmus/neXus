import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";

import { m3 } from "../theme/m3Theme";
import { M3Button, StatusChip } from "./M3Widgets";
import { useFetchOnMount } from "./useFetchOnMount";
import {
  addDeviceSingle,
  getDevice,
  listCredentials,
  type ApiError,
  type CredentialView,
  type DeviceDetail,
  type Vendor,
} from "../auth/adminApi";
import { enrollmentStateLabel, isTerminalJobState, jobPhaseLabel, peerFollowMessage } from "./deviceCopy";

const POLL_INTERVAL_MS = 1750;

const VENDOR_LABEL: Record<Vendor, string> = {
  check_point: "Check Point",
  palo_alto: "Palo Alto",
};

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

/**
 * The `M3Components` enrollment dialog, wired to the real `/devices/*`
 * contract (NXS-LOCAL-0157). "Single device" is the only mode that submits;
 * "Management server (discovery)" is shown -- per the canvas's own "a
 * capability that exists but cannot run here is shown and explained, never a
 * bare greyed control" rule -- disabled, because no backend path exists for
 * it yet in this movement.
 */
export function AddDeviceDialogTrigger() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <M3Button emphasis="filled" icon="plus" onClick={() => setOpen(true)}>
        Add device
      </M3Button>
      {open && <AddDeviceDialogContent onClose={() => setOpen(false)} />}
    </>
  );
}

type Phase = "form" | "submitting" | "polling" | "terminal";

function AddDeviceDialogContent({ onClose }: { readonly onClose: () => void }) {
  const [mode, setMode] = useState<"single" | "discovery">("single");
  const [address, setAddress] = useState("");
  const [vendor, setVendor] = useState<Vendor>("check_point");
  const [credentialId, setCredentialId] = useState("");
  const [phase, setPhase] = useState<Phase>("form");
  const [validationReason, setValidationReason] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [detail, setDetail] = useState<DeviceDetail | null>(null);

  const {
    data: credentialsData,
    error: credentialsError,
  } = useFetchOnMount(
    () => listCredentials().then((result) => result.credentials ?? []),
    describeApiError,
  );
  const credentials = credentialsData ?? [];
  const eligibleCredentials = credentials.filter((c) =>
    vendor === "check_point" ? c.allows_check_point : c.allows_palo_alto,
  );

  // Keep the credential selection valid as the vendor (and therefore the
  // eligible list) changes; never leave a stale id from another vendor selected.
  useEffect(() => {
    if (eligibleCredentials.length === 0) {
      if (credentialId !== "") setCredentialId("");
      return;
    }
    if (!eligibleCredentials.some((c) => c.credential_reference_id === credentialId)) {
      setCredentialId(eligibleCredentials[0].credential_reference_id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vendor, credentials.length]);

  // Poll GET /devices/{device_id} on a cancellable interval while a job is
  // in flight; cleared on unmount, dialog close (which unmounts this
  // component) or reaching a terminal state.
  useEffect(() => {
    if (phase !== "polling" || !deviceId) return undefined;
    let cancelled = false;

    const tick = () => {
      getDevice(deviceId)
        .then((result) => {
          if (cancelled) return;
          setDetail(result);
          const jobTerminal = result.job !== null && isTerminalJobState(result.job.state);
          if (result.enrollment_state === "ENROLLED" || jobTerminal) {
            setPhase("terminal");
          }
        })
        .catch(() => {
          // Transient poll failure: keep polling rather than abandoning the flow.
        });
    };

    tick();
    const intervalId = setInterval(tick, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
  }, [phase, deviceId]);

  const handleSubmit = async () => {
    setSubmitError(null);
    setValidationReason(null);
    setPhase("submitting");
    try {
      const result = await addDeviceSingle(address, vendor, credentialId);
      setDeviceId(result.device_id);
      setPhase("polling");
    } catch (err) {
      const apiErr = err as ApiError;
      if (apiErr.status === 422) {
        const reasonCode = typeof apiErr.body?.reason_code === "string" ? (apiErr.body.reason_code as string) : "VALIDATION_FAILED";
        setValidationReason(reasonCode);
        setPhase("form");
        return;
      }
      setSubmitError(describeApiError(apiErr));
      setPhase("form");
    }
  };

  const canSubmit = mode === "single" && address.trim().length > 0 && credentialId.length > 0 && phase === "form";
  const success = detail !== null && detail.enrollment_state === "ENROLLED";
  const mismatchOpen = detail !== null && detail.identity_mismatch_state === "OPEN";
  const peerMessage = detail !== null ? peerFollowMessage(detail) : null;

  return (
    <Dialog open onClose={phase === "submitting" || phase === "polling" ? undefined : onClose} PaperProps={{ sx: { borderRadius: "28px", width: 460 } }}>
      <DialogContent sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2 }}>
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="h3">Add device</Typography>
          <Typography variant="body1" sx={{ color: m3.onSurfaceVar }}>
            Enrolling a device grants read collection only. Backup creation stays off until the
            device enters the pilot allowlist.
          </Typography>
        </Box>

        <ToggleButtonGroup
          exclusive
          value={mode}
          onChange={(_e, next) => next && setMode(next)}
          size="small"
          fullWidth
          disabled={phase !== "form"}
        >
          <ToggleButton value="single" sx={{ textTransform: "none" }}>
            Single device
          </ToggleButton>
          <ToggleButton value="discovery" disabled sx={{ textTransform: "none" }}>
            Management server (discovery) · coming in a later release
          </ToggleButton>
        </ToggleButtonGroup>

        {mode === "discovery" && (
          <Typography variant="body2">
            Management server discovery is coming in a later release. Use Single device to enrol one device now.
          </Typography>
        )}

        {mode === "single" && phase === "form" && (
          <Stack spacing={1.5}>
            <TextField
              label="Address"
              placeholder="Hostname or IP"
              size="small"
              fullWidth
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              autoFocus
            />
            <TextField
              label="Vendor"
              select
              size="small"
              fullWidth
              value={vendor}
              onChange={(e) => setVendor(e.target.value as Vendor)}
            >
              <MenuItem value="check_point">Check Point</MenuItem>
              <MenuItem value="palo_alto">Palo Alto</MenuItem>
            </TextField>
            <TextField
              label="Credential"
              select
              size="small"
              fullWidth
              value={credentialId}
              onChange={(e) => setCredentialId(e.target.value)}
              disabled={eligibleCredentials.length === 0}
              helperText={
                credentialsError
                  ? credentialsError
                  : eligibleCredentials.length === 0
                    ? `No stored credential allows ${VENDOR_LABEL[vendor]}. Add one from the Credentials tab first.`
                    : undefined
              }
            >
              {eligibleCredentials.map((c: CredentialView) => (
                <MenuItem key={c.credential_id} value={c.credential_reference_id}>
                  {c.display_name}
                </MenuItem>
              ))}
            </TextField>
            {validationReason && (
              <Typography variant="body2" color="error">
                Validation failed: {validationReason}
              </Typography>
            )}
            {submitError && (
              <Typography variant="body2" color="error">
                {submitError}
              </Typography>
            )}
            <Typography variant="body2">
              Credentials are stored outside the repository.
            </Typography>
          </Stack>
        )}

        {mode === "single" && (phase === "submitting" || phase === "polling") && (
          <Stack spacing={1.5} alignItems="center" sx={{ py: 2 }}>
            <Typography variant="body1">
              {phase === "submitting" ? "Connecting…" : jobPhaseLabel(detail?.job?.state ?? "REQUESTED")}
            </Typography>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              {address}
            </Typography>
          </Stack>
        )}

        {mode === "single" && phase === "terminal" && detail && (
          <Stack spacing={1.5}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <StatusChip tone={success ? "ok" : "bad"} label={enrollmentStateLabel(detail.enrollment_state)} dense />
              {mismatchOpen && <StatusChip tone="warn" label="Identity mismatch open" dense />}
            </Box>
            {success && detail.facts && (
              <Stack spacing={0.5}>
                <Typography variant="body2">Hostname: {detail.facts.hostname ?? "Unknown"}</Typography>
                <Typography variant="body2">Model: {detail.facts.model ?? "Unknown"}</Typography>
                <Typography variant="body2">Software version: {detail.facts.software_version ?? "Unknown"}</Typography>
                <Typography variant="body2">HA role: {detail.facts.ha_role ?? "Unknown"}</Typography>
              </Stack>
            )}
            {success && peerMessage && <Typography variant="body2">{peerMessage}</Typography>}
            {!success && (
              <Typography variant="body2" color="error">
                {detail.job?.terminal_reason ?? "Enrollment did not complete."}
              </Typography>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 3 }}>
        <Button
          onClick={onClose}
          disabled={phase === "submitting" || phase === "polling"}
          sx={{ textTransform: "none", color: m3.primary }}
        >
          {phase === "terminal" ? "Close" : "Cancel"}
        </Button>
        {phase !== "terminal" && (
          <M3Button emphasis="filled" onClick={handleSubmit} disabled={!canSubmit}>
            Enrol
          </M3Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
