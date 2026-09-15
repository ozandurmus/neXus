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
  startDiscoveryRun,
  type ApiError,
  type CredentialView,
  type DeviceDetail,
  type DeviceRole,
  type DiscoveryCandidate,
  type DiscoveryImportResult,
  type DiscoveryRunView,
  type Vendor,
} from "../auth/adminApi";
import { enrollmentStateLabel, isTerminalJobState, jobPhaseLabel, peerFollowMessage } from "./deviceCopy";

const POLL_INTERVAL_MS = 1750;

const VENDOR_LABEL: Record<Vendor, string> = {
  check_point: "Check Point",
  palo_alto: "Palo Alto",
};

const FAILURE_REASON_PREFIX = "failure_reason_class:";

// Closed allowlist (DiscoveryJobExecutor safe classes): the only reason
// codes this dialog will ever render. Anything else -- an unknown key, a
// malformed key, or more than one reason key in the same summary -- falls
// through to the generic UNKNOWN explanation rather than echoing the raw
// API text.
const DISCOVERY_FAILURE_EXPLANATIONS: Record<string, string> = {
  UNREACHABLE: "The management server did not respond on the network within the timeout.",
  REFUSED: "The management server refused the connection. This class does not yet distinguish an authentication failure from a trust failure.",
  UNKNOWN_FAILURE: "Discovery failed for a reason that is not yet classified.",
  UNSUPPORTED_VENDOR: "The management server's vendor or product is not supported by discovery.",
  CANDIDATE_PERSIST_FAILED: "Discovery completed but its candidate results could not be saved.",
};

const UNKNOWN_DISCOVERY_FAILURE_EXPLANATION = "Discovery did not complete for an unclassified reason.";

function discoveryFailureReason(outcomeSummary: Record<string, number> | undefined | null): {
  readonly code: string;
  readonly explanation: string;
} {
  const reasonKeys = Object.keys(outcomeSummary ?? {}).filter((key) => key.startsWith(FAILURE_REASON_PREFIX));
  if (reasonKeys.length === 1) {
    const candidate = reasonKeys[0].slice(FAILURE_REASON_PREFIX.length);
    const explanation = DISCOVERY_FAILURE_EXPLANATIONS[candidate];
    if (explanation) return { code: candidate, explanation };
  }
  return { code: "UNKNOWN", explanation: UNKNOWN_DISCOVERY_FAILURE_EXPLANATION };
}

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError === "ACTION_REFUSED") return "You do not have permission to create credentials.";
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

type Phase = "form" | "submitting" | "polling" | "terminal";
type DiscoveryPhase = "form" | "starting" | "polling" | "failed" | "candidates" | "importing" | "done";

function AddDeviceDialogContent({ onClose }: { readonly onClose: () => void }) {
  const [mode, setMode] = useState<"single" | "discovery">("single");
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
  const [detail, setDetail] = useState<DeviceDetail | null>(null);

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

  const handleSubmit = async () => {
    setSubmitError(null);
    setValidationReason(null);
    if (mode === "single") {
      setPhase("submitting");
      try {
        const result = await addDeviceSingle(address, role, vendor, credentialId);
        setDeviceId(result.device_id);
        setPhase("polling");
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
      const result = await startDiscoveryRun(address, vendor, credentialId);
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

  const canSubmit =
    mode === "single"
      ? address.trim().length > 0 && credentialId.length > 0 && phase === "form"
      : address.trim().length > 0 && credentialId.length > 0 && discoveryPhase === "form";
  const success = detail !== null && detail.enrollment_state === "ENROLLED";
  const mismatchOpen = detail !== null && detail.identity_mismatch_state === "OPEN";
  const peerMessage = detail !== null ? peerFollowMessage(detail) : null;

  const dialogBusy =
    phase === "submitting" ||
    phase === "polling" ||
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

  return (
    <Dialog open onClose={dialogBusy ? undefined : onClose} PaperProps={{ sx: { borderRadius: "28px", width: mode === "discovery" && discoveryPhase !== "form" ? 640 : 460 } }}>
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
          disabled={phase !== "form" || discoveryPhase !== "form"}
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
            <TextField
              label="Role"
              select
              size="small"
              fullWidth
              value={role}
              onChange={(e) => setRole(e.target.value as DeviceRole)}
            >
              <MenuItem value="gateway">Firewall</MenuItem>
              <MenuItem value="management_server">Management server</MenuItem>
            </TextField>
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
            </TextField>
            {credentialSelectorFragment}
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

        {mode === "discovery" && discoveryPhase === "form" && (
          <Stack spacing={1.5}>
            <TextField
              label="Management server address"
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
              <MenuItem value="check_point">Check Point (multi-domain server)</MenuItem>
              <MenuItem value="palo_alto">Palo Alto (Panorama)</MenuItem>
            </TextField>
            {credentialSelectorFragment}
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
          </Stack>
        )}

        {mode === "discovery" && (discoveryPhase === "starting" || discoveryPhase === "polling") && (
          <Stack spacing={1.5} alignItems="center" sx={{ py: 2 }}>
            <Typography variant="body1">
              {discoveryPhase === "starting" ? "Starting discovery…" : "Discovering…"}
            </Typography>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              {address}
            </Typography>
          </Stack>
        )}

        {mode === "discovery" && discoveryPhase === "failed" && (
          <Stack spacing={0.5}>
            <Typography variant="body2" color="error">
              Discovery did not complete.
            </Typography>
            {(() => {
              const { code, explanation } = discoveryFailureReason(run?.outcome_summary);
              return (
                <Stack direction="row" spacing={1} alignItems="center">
                  <StatusChip tone="bad" label={code} dense />
                  <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                    {explanation}
                  </Typography>
                </Stack>
              );
            })()}
          </Stack>
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
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              {candidateSummaryLine}
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox" />
                  <TableCell>Name</TableCell>
                  <TableCell>Kind</TableCell>
                  <TableCell>Address</TableCell>
                  {discoveryPhase === "done" && <TableCell>Outcome</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
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
          initialVendor={vendor}
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
            Import
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

  return (
    <>
      <TableRow sx={notSelectableState ? { opacity: 0.6 } : undefined}>
        <TableCell padding="checkbox">
          {checkable && (
            <Checkbox
              size="small"
              checked={checked}
              disabled={disabled || done}
              onChange={() => onToggle(candidate)}
            />
          )}
        </TableCell>
        <TableCell sx={{ pl: depth > 0 ? 3 + depth * 2 : undefined }}>
          {candidate.display_name ?? "Unnamed"}
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
