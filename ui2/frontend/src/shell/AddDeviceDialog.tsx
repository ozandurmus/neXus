import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";

import { m3 } from "../theme/m3Theme";
import { CreateCredentialDialog } from "../screens/CredentialsPanel";
import { M3Button, StatusChip } from "./M3Widgets";
import { useFetchOnMount } from "./useFetchOnMount";
import {
  addDeviceSingle,
  getDevice,
  getDiscoveryRun,
  importDiscoveryCandidates,
  listCredentials,
  requestInventoryCollect,
  startDiscoveryRun,
  type ApiError,
  type CredentialView,
  type DeviceDetail,
  type DeviceRole,
  type DiscoveryCandidate,
  type DiscoveryImportResult,
  type DiscoveryRunView,
  type Vendor,
  setDeviceSecret,
} from "../auth/adminApi";
import { enrollmentStateLabel, isTerminalJobState, jobPhaseLabel, peerFollowMessage } from "./deviceCopy";

const POLL_INTERVAL_MS = 1750;
const DISCOVERY_FAILURE_COPY: Record<string, string> = {
  TRUST_ENTRY_MISSING: "SSH trust: MISSING. A security administrator must authorize the independently verified key.",
  TRUST_MISMATCH: "SSH trust: MISMATCH. Discovery is refused until explicit re-enrollment.",
  AUTH_FAILED: "SSH authentication failed.",
  CONNECT_TIMEOUT: "SSH connection timed out before trust could be verified.",
};

const VENDOR_LABEL: Record<Vendor, string> = {
  check_point: "Check Point",
  palo_alto: "Palo Alto",
  infoblox: "Infoblox",
  radware: "Radware",
};

/** V64: vendors reached over HTTPS -- an appliance role, a password credential; Radware also an export passphrase. */
const HTTPS_VENDORS: ReadonlySet<Vendor> = new Set<Vendor>(["infoblox", "radware"]);

function formatValidationReason(reason: string): string {
  switch (reason) {
    case "address_ref_invalid":
      return `${reason} (Invalid IP address or hostname. Check for whitespace or unsupported characters)`;
    case "credential_reference_not_found":
      return `${reason} (Please select a valid credential from the list)`;
    case "vendor_hint_invalid":
      return `${reason} (Selected vendor is invalid)`;
    case "role_invalid":
      return `${reason} (Selected role is invalid)`;
    case "unsupported_transport_kind":
      return `${reason} (Transport kind is not supported)`;
    case "DEVICE_NOT_DRAFT":
      return `${reason} (This device is already enrolled and active in the system)`;
    case "DEVICE_NOT_FOUND":
      return `${reason} (Device not found)`;
    case "ADDRESS_UNRESOLVABLE":
      return `${reason} (Address could not be resolved)`;
    default:
      return reason;
  }
}

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  const reasonCode = typeof apiErr.body?.reason_code === "string" ? (apiErr.body.reason_code as string) : undefined;
  if (serverError === "ACTION_REFUSED") return "You do not have permission to create credentials.";
  if (serverError === "VALIDATION_FAILED" && reasonCode) {
    return `Validation failed: ${formatValidationReason(reasonCode)}`;
  }
  if (reasonCode) return `Validation failed: ${formatValidationReason(reasonCode)}`;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

/**
 * The `M3Components` enrollment dialog, wired to the real `/devices/*`
 * contract (NXS-LOCAL-0157) and, for the "management server (discovery)"
 * mode, the `/discovery/runs*` contract (14F section 3): same three fields,
 * a run poll, a grouped candidate table, and an import step into the
 * existing device list.
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

type Phase = "form" | "submitting" | "confirming" | "collecting" | "terminal";
type DiscoveryPhase = "form" | "starting" | "polling" | "failed" | "candidates" | "importing" | "done";

/**
 * "Import from manager" (Administration): the same dialog opened in its management-server discovery mode -- the
 * button used to have no handler at all (review 2026-09-23).
 */
export function ImportFromManagerTrigger() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <M3Button emphasis="outlined" onClick={() => setOpen(true)}>
        Import from manager
      </M3Button>
      {open && <AddDeviceDialogContent initialMode="discovery" onClose={() => setOpen(false)} />}
    </>
  );
}

function AddDeviceDialogContent({ onClose, initialMode = "single" }: { readonly onClose: () => void; readonly initialMode?: "single" | "discovery" }) {
  const [mode, setMode] = useState<"single" | "discovery">(initialMode);
  const [address, setAddress] = useState("");
  const [role, setRole] = useState<DeviceRole>("gateway");
  const [vendor, setVendor] = useState<Vendor>("check_point");
  const [credentialId, setCredentialId] = useState("");
  const [createdCredential, setCreatedCredential] = useState<CredentialView | null>(null);
  const [createCredentialOpen, setCreateCredentialOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>("form");
  const [validationReason, setValidationReason] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [activeJobId, setActiveJobId] = useState<string | null>(null);
  const [detail, setDetail] = useState<DeviceDetail | null>(null);

  const [trustFormOpen, setTrustFormOpen] = useState(false);
  const [trustAlgorithm, setTrustAlgorithm] = useState("ssh-rsa");
  const [trustFingerprint, setTrustFingerprint] = useState("");
  const [trustObservedAt, setTrustObservedAt] = useState("");
  const [trustVerified, setTrustVerified] = useState(false);
  const [trustReEnroll, setTrustReEnroll] = useState(false);
  const [trustBusy, setTrustBusy] = useState(false);
  const [trustRelationship, setTrustRelationship] = useState<string | null>(null);
  const [discoveryPhase, setDiscoveryPhase] = useState<DiscoveryPhase>("form");
  const [runId, setRunId] = useState<string | null>(null);
  const [run, setRun] = useState<DiscoveryRunView | null>(null);
  const [selectedCandidateIds, setSelectedCandidateIds] = useState<Set<string>>(new Set());
  const [importCredentialId, setImportCredentialId] = useState("");
  const [importResults, setImportResults] = useState<Record<string, DiscoveryImportResult>>({});

  const {
    data: credentialsData,
    error: credentialsError,
  } = useFetchOnMount(
    () => listCredentials().then((result) => result.credentials ?? []),
    describeApiError,
  );
  const credentials = createdCredential && !(credentialsData ?? []).some((credential) => credential.credential_id === createdCredential.credential_id)
    ? [...(credentialsData ?? []), createdCredential]
    : credentialsData ?? [];
  // A credential is not tied to a vendor (PO, 2026-09-24); only an SSH private key is limited to Check Point, the one
  // vendor reached with a key -- Palo Alto and the HTTPS vendors take a username and password.
  const eligibleCredentials = credentials.filter((c) => vendor === "check_point" || c.kind !== "ssh_private_key");
  const [passphraseCredentialId, setPassphraseCredentialId] = useState("");

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
    if ((phase !== "confirming" && phase !== "collecting") || !deviceId || !activeJobId) return undefined;
    let cancelled = false;
    let requestingInventory = false;

    const tick = () => {
      getDevice(deviceId)
        .then((result) => {
          if (cancelled) return;
          setDetail(result);
          if (result.job?.job_id !== activeJobId || !isTerminalJobState(result.job.state) || requestingInventory) {
            return;
          }
          if (phase === "confirming" && result.enrollment_state === "ENROLLED" && result.job.state === "COMPLETED") {
            requestingInventory = true;
            requestInventoryCollect(deviceId)
              .then(({ job_id }) => {
                if (cancelled) return;
                setActiveJobId(job_id);
                setPhase("collecting");
              })
              .catch((err) => {
                if (cancelled) return;
                setSubmitError(describeApiError(err));
                setPhase("terminal");
              });
            return;
          }
          if (phase === "collecting" || result.job.outcome !== "SUCCESS") {
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
  }, [phase, deviceId, activeJobId]);

  // 14F section 3: poll GET /discovery/runs/{run_id} until FINISHED/FAILED.
  useEffect(() => {
    if (discoveryPhase !== "polling" || !runId) return undefined;
    let cancelled = false;

    const tick = () => {
      getDiscoveryRun(runId)
        .then((result) => {
          if (cancelled) return;
          setRun(result);
          if (result.state === "FINISHED") {
            setDiscoveryPhase("candidates");
          } else if (result.state === "FAILED") {
            setDiscoveryPhase("failed");
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
  }, [discoveryPhase, runId]);

  useEffect(() => {
    setTrustRelationship(null);
    setTrustVerified(false);
    setTrustFingerprint("");
  }, [address, vendor, trustAlgorithm]);

  const authorizeSshTrust = async () => {
    setTrustBusy(true);
    setTrustRelationship(null);
    try {
      const status = await fetch("/session/status", { credentials: "same-origin" });
      const session = await status.json() as { csrf_token?: string };
      if (!status.ok || !session.csrf_token) throw new Error();
      const response = await fetch(`/discovery/ssh-trust/${trustReEnroll ? "re-enroll" : "enroll"}`, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-CSRF-Token": session.csrf_token },
        body: JSON.stringify({ management_address: address, management_port: 22,
          key_algorithm: trustAlgorithm, fingerprint_sha256: trustFingerprint,
          observed_at: new Date(trustObservedAt).toISOString(), independently_verified: trustVerified }),
      });
      const body = await response.json() as { relationship?: string };
      setTrustRelationship(response.ok && body.relationship === "MATCH" ? "MATCH"
        : response.status === 409 && body.relationship === "MISMATCH" ? "MISMATCH" : "NOT_EVALUABLE");
    } catch {
      setTrustRelationship("NOT_EVALUABLE");
    } finally {
      setTrustFingerprint("");
      setTrustVerified(false);
      setTrustBusy(false);
    }
  };

  const handleSubmit = async () => {
    setSubmitError(null);
    setValidationReason(null);
    const trimmedAddress = address.trim();
    if (mode === "single") {
      setPhase("submitting");
      try {
        const result = await addDeviceSingle(trimmedAddress, HTTPS_VENDORS.has(vendor) ? "appliance" : role, vendor, credentialId);
        if (vendor === "radware" && passphraseCredentialId) {
          await setDeviceSecret(result.device_id, "export_passphrase", passphraseCredentialId);
        }
        setDeviceId(result.device_id);
        setActiveJobId(result.job_id);
        setPhase("confirming");
      } catch (err) {
        const apiErr = err as ApiError;
        if (apiErr.status === 422) {
          const reasonCode =
            typeof apiErr.body?.reason_code === "string" ? (apiErr.body.reason_code as string) : "VALIDATION_FAILED";
          setValidationReason(reasonCode);
          setPhase("form");
          return;
        }
        setSubmitError(describeApiError(apiErr));
        setPhase("form");
      }
      return;
    }

    setDiscoveryPhase("starting");
    try {
      const result = await startDiscoveryRun(trimmedAddress, vendor, credentialId);
      setRunId(result.run_id);
      setImportCredentialId(credentialId);
      setDiscoveryPhase("polling");
    } catch (err) {
      const apiErr = err as ApiError;
      if (apiErr.status === 422) {
        const reasonCode =
          typeof apiErr.body?.reason_code === "string" ? (apiErr.body.reason_code as string) : "VALIDATION_FAILED";
        setValidationReason(reasonCode);
        setDiscoveryPhase("form");
        return;
      }
      setSubmitError(describeApiError(apiErr));
      setDiscoveryPhase("form");
    }
  };

  const rootCandidates = useMemo(() => (run?.candidates ?? []).filter((c) => !c.parent_candidate_id), [run]);
  const childrenOf = (candidateId: string) =>
    (run?.candidates ?? []).filter((c) => c.parent_candidate_id === candidateId);

  // Selection rule: a candidate whose registry_state is already_imported or
  // conflicting is never selectable (backend RD-5 projection, AC-2).
  const isSelectable = (candidate: DiscoveryCandidate) => candidate.importable && candidate.registry_state === "new";

  const toggleCandidate = (candidate: DiscoveryCandidate) => {
    setSelectedCandidateIds((prev) => {
      const next = new Set(prev);
      const children = childrenOf(candidate.candidate_id);
      if (!candidate.importable && children.length > 0) {
        // SB-9/RD-1: selecting a cluster/host parent that is not itself
        // importable selects (or clears) every importable, not-yet-imported
        // child instead -- already-imported/conflicting members are skipped.
        const childIds = children.filter(isSelectable).map((c) => c.candidate_id);
        const allSelected = childIds.length > 0 && childIds.every((id) => next.has(id));
        childIds.forEach((id) => (allSelected ? next.delete(id) : next.add(id)));
        return next;
      }
      if (isSelectable(candidate)) {
        if (next.has(candidate.candidate_id)) next.delete(candidate.candidate_id);
        else next.add(candidate.candidate_id);
      }
      return next;
    });
  };

  const isGroupSelected = (candidate: DiscoveryCandidate) => {
    const children = childrenOf(candidate.candidate_id);
    if (!candidate.importable && children.length > 0) {
      const childIds = children.filter(isSelectable).map((c) => c.candidate_id);
      return childIds.length > 0 && childIds.every((id) => selectedCandidateIds.has(id));
    }
    return selectedCandidateIds.has(candidate.candidate_id);
  };

  const selectableCandidates = useMemo(() => {
    return (run?.candidates ?? []).filter((c) => c.importable && c.registry_state === "new");
  }, [run]);
  const selectableCount = selectableCandidates.length;
  const selectedCount = useMemo(() => {
    return selectableCandidates.filter((c) => selectedCandidateIds.has(c.candidate_id)).length;
  }, [selectableCandidates, selectedCandidateIds]);

  const handleToggleAll = () => {
    if (selectableCount === 0) return;
    if (selectedCount === selectableCount) {
      setSelectedCandidateIds(new Set());
    } else {
      setSelectedCandidateIds(new Set(selectableCandidates.map((c) => c.candidate_id)));
    }
  };

  const handleSelectAll = () => {
    if (selectableCount === 0) return;
    setSelectedCandidateIds(new Set(selectableCandidates.map((c) => c.candidate_id)));
  };

  const handleClearSelection = () => {
    setSelectedCandidateIds(new Set());
  };

  const totalCandidateCount = run?.candidates.length ?? 0;
  const alreadyAddedCount = (run?.candidates ?? []).filter((c) => c.registry_state === "already_imported").length;
  const conflictingCount = (run?.candidates ?? []).filter((c) => c.registry_state === "conflicting").length;
  const candidateSummaryLine =
    `${totalCandidateCount} candidates found, ${alreadyAddedCount} already added` +
    (conflictingCount > 0 ? `, ${conflictingCount} conflicting` : "");

  const handleImport = async () => {
    if (!runId) return;
    setDiscoveryPhase("importing");
    setSubmitError(null);
    try {
      const result = await importDiscoveryCandidates(runId, Array.from(selectedCandidateIds), importCredentialId);
      const byId: Record<string, DiscoveryImportResult> = {};
      result.results.forEach((r) => (byId[r.candidate_id] = r));
      setImportResults(byId);
      setDiscoveryPhase("done");
    } catch (err) {
      setSubmitError(describeApiError(err));
      setDiscoveryPhase("candidates");
    }
  };

  const canSubmit = !trustBusy && (
    mode === "single"
      ? address.trim().length > 0 && credentialId.length > 0 && phase === "form"
      : address.trim().length > 0 && credentialId.length > 0 && discoveryPhase === "form");
  const success = detail !== null && detail.enrollment_state === "ENROLLED";
  const mismatchOpen = detail !== null && detail.identity_mismatch_state === "OPEN";
  const peerMessage = detail !== null ? peerFollowMessage(detail) : null;

  const dialogBusy =
    trustBusy || phase === "submitting" ||
    phase === "confirming" ||
    phase === "collecting" ||
    discoveryPhase === "starting" ||
    discoveryPhase === "polling" ||
    discoveryPhase === "importing";

  const credentialSelectorFragment = (
    <>
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
              ? `No stored credential allows ${VENDOR_LABEL[vendor]}.`
              : undefined
        }
      >
        {eligibleCredentials.map((c: CredentialView) => (
          <MenuItem key={c.credential_id} value={c.credential_reference_id}>
            {c.display_name}
          </MenuItem>
        ))}
      </TextField>
      {eligibleCredentials.length === 0 && (
        <Button onClick={() => setCreateCredentialOpen(true)}>Create credential</Button>
      )}
    </>
  );

  const sshTrustAuthorization = vendor === "check_point" && <>
    <Button onClick={() => setTrustFormOpen(!trustFormOpen)} disabled={trustBusy}>SSH trust authorization</Button>
    {trustFormOpen && <Stack spacing={1}>
      <Typography variant="body2">Security administrator only. Independently verify the observed key through an out-of-band source before authorization. Observation alone does not authorize trust.</Typography>
      <TextField label="SSH host-key algorithm" select value={trustAlgorithm} disabled={trustBusy}
        onChange={(e) => setTrustAlgorithm(e.target.value)}>
        {["ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"].map((algorithm) =>
          <MenuItem key={algorithm} value={algorithm}>{algorithm}</MenuItem>)}
      </TextField>
      <TextField label="Independently verified SHA-256 key (lowercase hex)" type="password" autoComplete="off"
        value={trustFingerprint} disabled={trustBusy} onChange={(e) => setTrustFingerprint(e.target.value)} />
      <TextField label="Key observed at" type="datetime-local" InputLabelProps={{ shrink: true }}
        value={trustObservedAt} disabled={trustBusy} onChange={(e) => setTrustObservedAt(e.target.value)} />
      <label><Checkbox checked={trustVerified} disabled={trustBusy} onChange={(e) => setTrustVerified(e.target.checked)} />I independently verified the observed key</label>
      <label><Checkbox checked={trustReEnroll} disabled={trustBusy} onChange={(e) => setTrustReEnroll(e.target.checked)} />Explicitly re-enroll and supersede the existing authorization</label>
      <Button onClick={authorizeSshTrust} disabled={trustBusy || !address.trim() || !trustVerified
        || !/^[0-9a-f]{64}$/.test(trustFingerprint) || !trustObservedAt}>
        {trustReEnroll ? "Re-enroll SSH trust" : "Authorize SSH trust"}
      </Button>
      {trustRelationship && <Typography role="status">SSH trust: {trustRelationship}</Typography>}
    </Stack>}
  </>;

  return (
    <Dialog open onClose={dialogBusy ? undefined : onClose} PaperProps={{ sx: { borderRadius: "28px", width: mode === "discovery" && discoveryPhase !== "form" ? 860 : 460, maxWidth: "95vw" } }}>
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
          disabled={trustBusy || phase !== "form" || discoveryPhase !== "form"}
        >
          <ToggleButton value="single" sx={{ textTransform: "none" }}>
            Single device
          </ToggleButton>
          <ToggleButton value="discovery" sx={{ textTransform: "none" }}>
            Management server (discovery)
          </ToggleButton>
        </ToggleButtonGroup>

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
            {!HTTPS_VENDORS.has(vendor) && <TextField
              label="Role"
              select
              size="small"
              fullWidth
              value={role}
              onChange={(e) => setRole(e.target.value as DeviceRole)}
            >
              <MenuItem value="gateway">Security device</MenuItem>
              <MenuItem value="management_server">Management server</MenuItem>
            </TextField>}
            {role === "management_server" && (
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                A management server can be added and confirmed, but collection from it is refused until its read sets are gated.
              </Typography>
            )}
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
              <MenuItem value="infoblox">Infoblox Grid Manager (HTTPS)</MenuItem>
              <MenuItem value="radware">Radware DefensePro (HTTPS)</MenuItem>
            </TextField>
            {credentialSelectorFragment}
            {vendor === "radware" && (
              <TextField label="Export passphrase credential" select size="small" fullWidth value={passphraseCredentialId}
                onChange={(e) => setPassphraseCredentialId(e.target.value)}
                helperText="The backup includes the private keys, encrypted with this credential's password (IncludePKeys=on). Without it the backup is refused, never taken without keys.">
                <MenuItem value="">None yet</MenuItem>
                {credentials.filter((c) => c.kind !== "ssh_private_key").map((c) => (
                  <MenuItem key={c.credential_reference_id} value={c.credential_reference_id}>{c.display_name}</MenuItem>
                ))}
              </TextField>
            )}
            {sshTrustAuthorization}
            {validationReason && (
              <Typography variant="body2" color="error">
                Validation failed: {formatValidationReason(validationReason)}
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

        {mode === "single" && (phase === "submitting" || phase === "confirming" || phase === "collecting") && (
          <Stack spacing={1.5} alignItems="center" sx={{ py: 2 }}>
            <Typography variant="body1">
              {phase === "submitting" ? "Starting credential check…"
                : phase === "confirming" ? `Checking credentials: ${jobPhaseLabel(detail?.job?.state ?? "REQUESTED")}`
                  : `Collecting inventory: ${jobPhaseLabel(detail?.job?.state ?? "REQUESTED")}`}
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
                {submitError ?? detail.job?.terminal_reason ?? "Enrollment did not complete."}
              </Typography>
            )}
          </Stack>
        )}

        {mode === "discovery" && discoveryPhase === "form" && (
          <Stack spacing={1.5}>
            <TextField
              label="Management server address"
              placeholder="Hostname or IP"
              size="small"
              fullWidth
              value={address}
              disabled={trustBusy}
              onChange={(e) => setAddress(e.target.value)}
              autoFocus
            />
            <TextField
              label="Vendor"
              select
              size="small"
              fullWidth
              value={vendor}
              disabled={trustBusy}
              onChange={(e) => setVendor(e.target.value as Vendor)}
            >
              <MenuItem value="check_point">Check Point (multi-domain server)</MenuItem>
              <MenuItem value="palo_alto">Palo Alto (Panorama)</MenuItem>
            </TextField>
            {credentialSelectorFragment}
            {sshTrustAuthorization}
            {validationReason && (
              <Typography variant="body2" color="error">
                Validation failed: {formatValidationReason(validationReason)}
              </Typography>
            )}
            {submitError && (
              <Typography variant="body2" color="error">
                {submitError}
              </Typography>
            )}
          </Stack>
        )}

        {mode === "discovery" && (discoveryPhase === "starting" || discoveryPhase === "polling") && (
          <Stack spacing={1.5} alignItems="center" sx={{ py: 2 }}>
            <Typography variant="body1">
              {discoveryPhase === "starting" ? "Starting discovery…" : "Discovering…"}
            </Typography>
            {vendor === "palo_alto" && <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{address}</Typography>}
          </Stack>
        )}

        {mode === "discovery" && discoveryPhase === "failed" && (
          <Typography variant="body2" color="error">
            {Object.keys(run?.outcome_summary ?? {}).map((key) => DISCOVERY_FAILURE_COPY[key]).find(Boolean)
              ?? "Discovery did not complete."}
          </Typography>
        )}

        {mode === "discovery" && (discoveryPhase === "candidates" || discoveryPhase === "importing" || discoveryPhase === "done") && run && (
          <Stack spacing={1.5}>
            {submitError && (
              <Typography variant="body2" color="error">
                {submitError}
              </Typography>
            )}
            {discoveryPhase !== "done" && (
              <TextField
                label="Import credential"
                select
                size="small"
                fullWidth
                value={importCredentialId}
                onChange={(e) => setImportCredentialId(e.target.value)}
              >
                {eligibleCredentials.map((c: CredentialView) => (
                  <MenuItem key={c.credential_id} value={c.credential_reference_id}>
                    {c.display_name}
                  </MenuItem>
                ))}
              </TextField>
            )}
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                {candidateSummaryLine}
              </Typography>
              {discoveryPhase !== "done" && selectableCount > 0 && (
                <Stack direction="row" spacing={1}>
                  <Button
                    size="small"
                    variant="outlined"
                    onClick={handleSelectAll}
                    disabled={selectedCount === selectableCount || discoveryPhase === "importing"}
                    sx={{ textTransform: "none", fontSize: "0.8rem", py: 0.25, px: 1 }}
                  >
                    Select all ({selectableCount})
                  </Button>
                  <Button
                    size="small"
                    variant="text"
                    onClick={handleClearSelection}
                    disabled={selectedCandidateIds.size === 0 || discoveryPhase === "importing"}
                    sx={{ textTransform: "none", fontSize: "0.8rem", py: 0.25, px: 1 }}
                  >
                    Clear selection
                  </Button>
                </Stack>
              )}
            </Box>
            <TableContainer
              sx={{
                maxHeight: 440,
                overflow: "auto",
                border: (theme) => `1px solid ${theme.palette.divider}`,
                borderRadius: 1,
              }}
            >
              <Table size="small" stickyHeader sx={{ tableLayout: "fixed" }}>
                <TableHead>
                  <TableRow>
                    <TableCell padding="checkbox" sx={{ width: 48, minWidth: 48, maxWidth: 48 }}>
                      <Checkbox
                        size="small"
                        checked={selectableCount > 0 && selectedCount === selectableCount}
                        indeterminate={selectedCount > 0 && selectedCount < selectableCount}
                        disabled={selectableCount === 0 || discoveryPhase === "importing" || discoveryPhase === "done"}
                        onChange={handleToggleAll}
                        inputProps={{ "aria-label": "Select all candidates" }}
                      />
                    </TableCell>
                    <TableCell sx={{ minWidth: 220 }}>Name</TableCell>
                    <TableCell sx={{ minWidth: 160 }}>Kind</TableCell>
                    <TableCell sx={{ minWidth: 140 }}>Address</TableCell>
                    {discoveryPhase === "done" && <TableCell sx={{ minWidth: 100 }}>Outcome</TableCell>}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rootCandidates.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={discoveryPhase === "done" ? 5 : 4} align="center" sx={{ py: 4, color: m3.onSurfaceVar }}>
                        No candidates found.
                      </TableCell>
                    </TableRow>
                  )}

                  {rootCandidates.map((root) => (
                    <CandidateRows
                      key={root.candidate_id}
                      candidate={root}
                      depth={0}
                      childrenOf={childrenOf}
                      selected={selectedCandidateIds}
                      isGroupSelected={isGroupSelected}
                      onToggle={toggleCandidate}
                      importResults={importResults}
                      done={discoveryPhase === "done"}
                      disabled={discoveryPhase === "importing"}
                    />
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            {discoveryPhase === "done" && (
              <Typography variant="body2">
                Imported devices now appear in the device list.
              </Typography>
            )}
          </Stack>
        )}
      </DialogContent>
      {createCredentialOpen && (
        <CreateCredentialDialog
          initialVendor={vendor === "check_point" ? "check_point" : "palo_alto" /* HTTPS vendors use a password credential, like PAN */}
          onClose={() => setCreateCredentialOpen(false)}
          onCreated={(credential) => {
            setCreatedCredential(credential);
            setCredentialId(credential.credential_reference_id);
            setCreateCredentialOpen(false);
          }}
        />
      )}
      <DialogActions sx={{ px: 3, pb: 3 }}>
        <Button
          onClick={onClose}
          disabled={dialogBusy}
          sx={{ textTransform: "none", color: m3.primary }}
        >
          {phase === "terminal" || discoveryPhase === "done" ? "Close" : "Cancel"}
        </Button>
        {mode === "single" && phase !== "terminal" && (
          <M3Button emphasis="filled" onClick={handleSubmit} disabled={!canSubmit}>
            Enrol
          </M3Button>
        )}
        {mode === "discovery" && discoveryPhase === "form" && (
          <M3Button emphasis="filled" onClick={handleSubmit} disabled={!canSubmit}>
            Start discovery
          </M3Button>
        )}
        {mode === "discovery" && discoveryPhase === "candidates" && (
          <M3Button emphasis="filled" onClick={handleImport} disabled={selectedCandidateIds.size === 0}>
            {selectedCandidateIds.size > 0 ? `Import (${selectedCandidateIds.size})` : "Import"}
          </M3Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

function CandidateRows({
  candidate,
  depth,
  childrenOf,
  selected,
  isGroupSelected,
  onToggle,
  importResults,
  done,
  disabled,
}: {
  readonly candidate: DiscoveryCandidate;
  readonly depth: number;
  readonly childrenOf: (candidateId: string) => DiscoveryCandidate[];
  readonly selected: Set<string>;
  readonly isGroupSelected: (candidate: DiscoveryCandidate) => boolean;
  readonly onToggle: (candidate: DiscoveryCandidate) => void;
  readonly importResults: Record<string, DiscoveryImportResult>;
  readonly done: boolean;
  readonly disabled: boolean;
}) {
  const children = childrenOf(candidate.candidate_id);
  const isGroup = !candidate.importable && children.length > 0;
  const checked = isGroup ? isGroupSelected(candidate) : selected.has(candidate.candidate_id);
  // Selection rule (AC-2): an already-imported or conflicting candidate is
  // greyed out and cannot be selected -- rendered, never hidden (DI-3).
  const notSelectableState =
    candidate.importable && candidate.registry_state !== "new" ? candidate.registry_state : null;
  const checkable = (candidate.importable && candidate.registry_state === "new") || isGroup;
  const result = importResults[candidate.candidate_id];

  const rawName = candidate.display_name?.trim();
  const displayName = rawName && rawName.length > 0
    ? rawName
    : (candidate.own_address || candidate.management_address || "Unnamed");

  return (
    <>
      <TableRow sx={notSelectableState ? { opacity: 0.6 } : undefined}>
        <TableCell padding="checkbox" sx={{ width: 48, minWidth: 48, maxWidth: 48 }}>
          {checkable && (
            <Checkbox
              size="small"
              checked={checked}
              disabled={disabled || done}
              onChange={() => onToggle(candidate)}
            />
          )}
        </TableCell>
        <TableCell sx={{ pl: depth > 0 ? depth * 2.5 + 2 : 2 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            {depth > 0 && (
              <Typography component="span" variant="body2" sx={{ color: m3.onSurfaceVar, userSelect: "none", mr: 0.5 }}>
                └─
              </Typography>
            )}
            <Typography variant="body2" sx={{ fontWeight: isGroup ? 600 : 400 }}>
              {displayName}
            </Typography>
          </Box>
          {notSelectableState && (
            <Stack spacing={0.25} sx={{ mt: 0.5 }}>
              <StatusChip
                tone={notSelectableState === "already_imported" ? "ok" : "warn"}
                label={notSelectableState === "already_imported" ? "Already added" : "Conflicting"}
                dense
              />
              {candidate.existing_device_id && (
                <Typography
                  variant="body2"
                  sx={{ color: m3.onSurfaceVar }}
                  title={`Existing device: ${candidate.existing_device_id}`}
                >
                  Existing device: {candidate.existing_device_id}
                </Typography>
              )}
            </Stack>
          )}
        </TableCell>
        <TableCell>
          {candidate.kind}
          {!candidate.importable && !isGroup && (
            <Typography component="span" variant="body2" sx={{ color: m3.onSurfaceVar, ml: 1 }}>
              (not importable)
            </Typography>
          )}
        </TableCell>
        <TableCell>{candidate.own_address ?? candidate.management_address ?? "—"}</TableCell>
        {done && (
          <TableCell>
            {result ? (
              <StatusChip
                tone={result.outcome === "new" ? "ok" : result.outcome === "refused" ? "bad" : "warn"}
                label={result.outcome}
                dense
              />
            ) : (
              "—"
            )}
          </TableCell>
        )}
      </TableRow>
      {children.map((child) => (
        <CandidateRows
          key={child.candidate_id}
          candidate={child}
          depth={depth + 1}
          childrenOf={childrenOf}
          selected={selected}
          isGroupSelected={isGroupSelected}
          onToggle={onToggle}
          importResults={importResults}
          done={done}
          disabled={disabled}
        />
      ))}
    </>
  );
}
