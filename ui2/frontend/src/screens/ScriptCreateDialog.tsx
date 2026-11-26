import { useEffect, useMemo, useState, type ChangeEvent } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  createScript,
  type CredentialView,
  type ScriptKind,
  type ScriptNotifyMode,
  type ScriptSummary,
} from "../auth/adminApi";
import { formatBytes } from "../shell/bytes";
import { M3Button } from "../shell/M3Widgets";
import { m3, MONO } from "../theme/m3Theme";
import { LABEL_UPPER, RADIUS, TYPE } from "../theme/tokens";
import {
  BAT_CMD_REFUSAL_REASON,
  BINARY_SCRIPT_KINDS,
  DEFAULT_RUN_AS_OPTIONS,
  PS1_REFUSAL_REASON,
  SCRIPT_KINDS,
  SCRIPT_KIND_LABELS,
  SCRIPT_NOTIFY_OPTIONS,
  TEXT_SCRIPT_KINDS,
  describeApiError,
  formatRunAs,
  inferScriptKindFromExtension,
  notifySendsMail,
  parseEgressInput,
  readFileAsBase64,
  stemFromFileName,
  validateFiveFieldCronShape,
  type ExtensionKindInference,
} from "./ScriptFormHelpers";

export function CredentialRefsPicker({
  credentials,
  credentialsError,
  selectedRefs,
  onChange,
  disabled = false,
  versionNote,
}: {
  readonly credentials: readonly CredentialView[] | null;
  readonly credentialsError: string | null;
  readonly selectedRefs: readonly string[];
  readonly onChange: (next: string[]) => void;
  readonly disabled?: boolean;
  readonly versionNote?: string;
}) {
  const knownRefIds = useMemo(
    () => new Set((credentials ?? []).map((c) => c.credential_reference_id)),
    [credentials],
  );
  const extraRefs = selectedRefs.filter((r) => !knownRefIds.has(r));

  const toggle = (refId: string) => {
    if (selectedRefs.includes(refId)) {
      onChange(selectedRefs.filter((item) => item !== refId));
    } else {
      onChange([...selectedRefs, refId]);
    }
  };

  return (
    <Box data-testid="credential-refs-picker">
      <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar, mb: 0.5 }}>
        Declared credential-store references (credential_refs)
      </Typography>
      <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar, mb: 1 }}>
        Only the version&apos;s declared references are injected into the isolated execution pod at run time and masked from captured output. Secret values are never accepted or returned here.
        {versionNote ? ` ${versionNote}` : ""}
      </Typography>
      {credentialsError ? (
        <Alert severity="warning" sx={{ mb: 1 }}>
          Could not load credential references: {credentialsError}
        </Alert>
      ) : null}
      {credentials === null && !credentialsError ? (
        <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>Loading credential store references…</Typography>
      ) : credentials && credentials.length === 0 ? (
        <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>
          No credentials stored yet (manage credentials under Administration › Device registry › Credentials).
        </Typography>
      ) : (
        <Stack spacing={0.5}>
          {(credentials ?? []).map((cred) => {
            const checked = selectedRefs.includes(cred.credential_reference_id);
            return (
              <FormControlLabel
                key={cred.credential_reference_id}
                control={
                  <Checkbox
                    size="small"
                    checked={checked}
                    disabled={disabled}
                    onChange={() => toggle(cred.credential_reference_id)}
                  />
                }
                label={
                  <Box component="span">
                    <Box component="span" sx={{ ...TYPE.body, fontWeight: 500 }}>
                      {cred.display_name}
                    </Box>{" "}
                    <Box component="span" sx={{ ...TYPE.mono, color: m3.onSurfaceVar }}>
                      ({cred.credential_reference_id} · {cred.kind}
                      {cred.username ? ` · ${cred.username}` : ""})
                    </Box>
                  </Box>
                }
              />
            );
          })}
        </Stack>
      )}
      {extraRefs.length > 0 ? (
        <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
          {extraRefs.map((refId) => (
            <Chip
              key={refId}
              size="small"
              label={refId}
              onDelete={disabled ? undefined : () => toggle(refId)}
              sx={{ fontFamily: MONO }}
            />
          ))}
        </Stack>
      ) : null}
    </Box>
  );
}

export function KindRefusalAlert({ inference }: { readonly inference: ExtensionKindInference }) {
  if (inference.isBatOrCmd) {
    return (
      <Alert severity="error" data-testid="bat-cmd-refusal">
        {BAT_CMD_REFUSAL_REASON}
      </Alert>
    );
  }
  if (inference.isPs1) {
    return (
      <Alert severity="error" data-testid="ps1-refusal">
        {PS1_REFUSAL_REASON}
      </Alert>
    );
  }
  if (inference.unsupportedExtension) {
    return (
      <Alert severity="error">
        Unsupported script file extension <code>.{inference.unsupportedExtension}</code>. Supported extensions:{" "}
        {SCRIPT_KINDS.map((k) => `.${k}`).join(", ")}.
      </Alert>
    );
  }
  return null;
}

export function CreateScriptDialog({
  open,
  onClose,
  onCreated,
  credentials,
  credentialsError,
  runAsOptions,
  scheduleZone,
}: {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onCreated: (created: ScriptSummary) => void;
  readonly credentials: readonly CredentialView[] | null;
  readonly credentialsError: string | null;
  readonly runAsOptions: readonly string[];
  readonly scheduleZone: string;
}) {
  const defaultRunAs = runAsOptions[0] ?? DEFAULT_RUN_AS_OPTIONS[0];
  const [sourceMode, setSourceMode] = useState<"editor" | "upload">("editor");
  const [name, setName] = useState("");
  const [fileName, setFileName] = useState("");
  const [explicitKind, setExplicitKind] = useState<ScriptKind>("sh");
  const [codeBody, setCodeBody] = useState("");
  const [uploadedFileName, setUploadedFileName] = useState("");
  const [uploadedSize, setUploadedSize] = useState<number | null>(null);
  const [uploadedBase64, setUploadedBase64] = useState<string | null>(null);
  const [schedule, setSchedule] = useState("");
  const [enabled, setEnabled] = useState(false);
  const [timeoutSeconds, setTimeoutSeconds] = useState("300");
  const [egressText, setEgressText] = useState("");
  const [notify, setNotify] = useState<ScriptNotifyMode>("none");
  const [mailOnFailureOnly, setMailOnFailureOnly] = useState(false);
  const [runAs, setRunAs] = useState<string>(defaultRunAs);
  const [credentialRefs, setCredentialRefs] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!runAsOptions.includes(runAs)) {
      setRunAs(defaultRunAs);
    }
  }, [runAsOptions, runAs, defaultRunAs]);

  const effectiveRunAs = runAsOptions.includes(runAs) ? runAs : defaultRunAs;
  const effectiveFileName = sourceMode === "upload" ? uploadedFileName : fileName;
  const extInference = useMemo(() => {
    const fromFile = inferScriptKindFromExtension(effectiveFileName);
    if (fromFile.isBatOrCmd || fromFile.isPs1 || fromFile.kind || fromFile.unsupportedExtension) return fromFile;
    return inferScriptKindFromExtension(name);
  }, [effectiveFileName, name]);

  const extensionRefused =
    extInference.isBatOrCmd || extInference.isPs1 || Boolean(extInference.unsupportedExtension);
  const resolvedKind: ScriptKind | null = extInference.kind ?? (extensionRefused ? null : explicitKind);

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setError(null);
    setUploadedFileName(file.name);
    setUploadedSize(file.size);
    const inferred = inferScriptKindFromExtension(file.name);
    if (inferred.kind) {
      setExplicitKind(inferred.kind);
    }
    if (!name.trim()) {
      const stem = stemFromFileName(file.name);
      if (stem) setName(stem);
    }
    if (inferred.isBatOrCmd || inferred.isPs1 || inferred.unsupportedExtension) {
      setUploadedBase64(null);
      return;
    }
    try {
      const b64 = await readFileAsBase64(file);
      setUploadedBase64(b64);
    } catch (readErr) {
      setUploadedBase64(null);
      setError(describeApiError(readErr));
    }
  };

  const resetAndClose = () => {
    setCodeBody("");
    setUploadedBase64(null);
    setUploadedFileName("");
    setUploadedSize(null);
    setMailOnFailureOnly(false);
    setError(null);
    onClose();
  };

  const hasBody = sourceMode === "upload" ? Boolean(uploadedBase64) : codeBody.length > 0;
  const effectiveName = name.trim() || stemFromFileName(effectiveFileName);
  const parsedTimeout = Number(timeoutSeconds);
  const timeoutValid = Number.isInteger(parsedTimeout) && parsedTimeout >= 1 && parsedTimeout <= 3600;
  const scheduleError = validateFiveFieldCronShape(schedule);
  const canSubmit =
    !busy &&
    !extensionRefused &&
    resolvedKind !== null &&
    Boolean(effectiveName) &&
    hasBody &&
    timeoutValid &&
    scheduleError === null &&
    Boolean(effectiveRunAs);

  const handleSubmit = async () => {
    if (!canSubmit || !resolvedKind) return;
    setBusy(true);
    setError(null);
    try {
      const created = await createScript({
        name: effectiveName,
        file_name: effectiveFileName.trim() || undefined,
        kind: resolvedKind,
        body: sourceMode === "editor" ? codeBody : undefined,
        content_base64: sourceMode === "upload" ? (uploadedBase64 ?? undefined) : undefined,
        schedule: schedule.trim() || undefined,
        enabled,
        timeout_s: parsedTimeout,
        egress: parseEgressInput(egressText),
        credential_refs: credentialRefs,
        notify,
        notify_mail_on_failure_only: notifySendsMail(notify) && mailOnFailureOnly,
        run_as: effectiveRunAs,
      });
      // Immediately clear script body from browser memory after save (write-only contract).
      setCodeBody("");
      setUploadedBase64(null);
      setUploadedFileName("");
      setUploadedSize(null);
      setMailOnFailureOnly(false);
      onCreated(created);
    } catch (submitErr) {
      setError(describeApiError(submitErr));
    } finally {
      setBusy(false);
    }
  };

  const allowedEditorKinds = sourceMode === "editor" ? TEXT_SCRIPT_KINDS : SCRIPT_KINDS;

  return (
    <Dialog open={open} onClose={() => !busy && resetAndClose()} fullWidth maxWidth="md">
      <DialogTitle>New script</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info" data-testid="write-only-script-notice">
            <strong>Write-only script body:</strong> The script text or file is sent once, encrypted at rest, and never shown again after save — nobody can read a stored script body back. Editing a script always uploads a new version.
          </Alert>

          <RadioGroup
            row
            aria-label="Script input method"
            value={sourceMode}
            onChange={(e) => {
              const next = e.target.value === "upload" ? "upload" : "editor";
              setSourceMode(next);
              if (next === "editor" && (BINARY_SCRIPT_KINDS as readonly string[]).includes(explicitKind)) {
                setExplicitKind("sh");
              }
              setError(null);
            }}
          >
            <FormControlLabel value="editor" control={<Radio size="small" />} label="Code editor" />
            <FormControlLabel value="upload" control={<Radio size="small" />} label="File upload" />
          </RadioGroup>

          <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
            <TextField
              size="small"
              label="Script name"
              required
              fullWidth
              value={name}
              onChange={(e) => setName(e.target.value)}
              helperText="Letters, digits, '.', '_' or '-' (1–128 chars)"
            />
            {sourceMode === "editor" ? (
              <TextField
                size="small"
                label="File name (optional, infers kind)"
                fullWidth
                placeholder="nightly-report.py"
                value={fileName}
                onChange={(e) => {
                  const val = e.target.value;
                  setFileName(val);
                  const inf = inferScriptKindFromExtension(val);
                  if (inf.kind && (TEXT_SCRIPT_KINDS as readonly string[]).includes(inf.kind)) {
                    setExplicitKind(inf.kind);
                  }
                }}
                helperText="Extension sets script kind automatically"
              />
            ) : null}
            <TextField
              select
              size="small"
              label="Kind"
              value={extInference.kind ?? explicitKind}
              disabled={Boolean(extInference.kind) || extensionRefused}
              onChange={(e) => setExplicitKind(e.target.value as ScriptKind)}
              sx={{ minWidth: 220 }}
              helperText={extInference.kind ? `Inferred from .${extInference.kind} extension` : "Runtime interpreter"}
            >
              {allowedEditorKinds.map((k) => (
                <MenuItem key={k} value={k}>
                  {SCRIPT_KIND_LABELS[k]}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <KindRefusalAlert inference={extInference} />

          {sourceMode === "upload" ? (
            <Box
              sx={{
                p: 2,
                borderRadius: `${RADIUS.card}px`,
                border: `1px dashed ${m3.outline}`,
                bgcolor: m3.scLow,
              }}
            >
              <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap">
                <Button variant="outlined" component="label" size="small">
                  Choose script file
                  <input
                    type="file"
                    hidden
                    aria-label="Script file"
                    onChange={(e) => void handleFileChange(e)}
                  />
                </Button>
                <Typography sx={{ ...TYPE.mono, color: m3.onSurface }}>
                  {uploadedFileName
                    ? `${uploadedFileName}${uploadedSize !== null ? ` (${formatBytes(uploadedSize)})` : ""}`
                    : "No file selected"}
                </Typography>
              </Stack>
            </Box>
          ) : (
            <TextField
              label="Script code"
              multiline
              minRows={8}
              fullWidth
              value={codeBody}
              onChange={(e) => setCodeBody(e.target.value)}
              placeholder="#!/usr/bin/env bash"
              InputProps={{ sx: { fontFamily: MONO, fontSize: TYPE.mono.fontSize } }}
              helperText="Sent once over HTTPS, scanned for secret patterns (warning only), encrypted at rest, and never displayed again."
            />
          )}

          <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
            <TextField
              size="small"
              label="Schedule (five-field cron)"
              fullWidth
              placeholder="0 2 * * *"
              value={schedule}
              onChange={(e) => setSchedule(e.target.value)}
              error={scheduleError !== null}
              helperText={
                scheduleError ??
                `minute hour day-of-month month day-of-week in ${scheduleZone} (leave blank for unscheduled)`
              }
              InputProps={{ sx: { fontFamily: MONO } }}
            />
            <TextField
              size="small"
              type="number"
              label="Timeout (seconds)"
              value={timeoutSeconds}
              onChange={(e) => setTimeoutSeconds(e.target.value)}
              error={!timeoutValid}
              helperText="1–3600 seconds"
              sx={{ minWidth: 180 }}
            />
          </Stack>

          <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
            <TextField
              select
              size="small"
              label="Notify"
              value={notify}
              onChange={(e) => setNotify(e.target.value as ScriptNotifyMode)}
              sx={{ minWidth: 220 }}
            >
              {SCRIPT_NOTIFY_OPTIONS.map(([val, label]) => (
                <MenuItem key={val} value={val}>
                  {label}
                </MenuItem>
              ))}
            </TextField>
            <FormControlLabel
              sx={{ minWidth: 220 }}
              control={
                <Switch
                  checked={notifySendsMail(notify) && mailOnFailureOnly}
                  onChange={(e) => setMailOnFailureOnly(e.target.checked)}
                  disabled={busy || !notifySendsMail(notify)}
                  inputProps={{ "aria-label": "Mail on failure only" }}
                />
              }
              label={
                <Typography sx={{ ...TYPE.body }} title="The syslog line is sent on every run end; only the mail is narrowed.">
                  {notifySendsMail(notify) ? "Mail on failure only" : "Mail on failure only (needs SMTP or Both)"}
                </Typography>
              }
            />
            <TextField
              select
              size="small"
              label="Run as"
              fullWidth
              value={effectiveRunAs}
              onChange={(e) => setRunAs(e.target.value)}
              helperText="Role granted to the per-run CLI session (allowlisted roles only; Security Admin is never permitted)"
            >
              {runAsOptions.map((opt) => (
                <MenuItem key={opt} value={opt}>
                  {formatRunAs(opt)}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <TextField
            size="small"
            label="Egress (host:port list)"
            multiline
            minRows={2}
            fullWidth
            placeholder="192.0.2.10:443, 203.0.113.50:8443"
            value={egressText}
            onChange={(e) => setEgressText(e.target.value)}
            InputProps={{ sx: { fontFamily: MONO } }}
            helperText="Comma- or newline-separated host:port endpoints reachable from the execution pod (empty = none)"
          />

          <CredentialRefsPicker
            credentials={credentials}
            credentialsError={credentialsError}
            selectedRefs={credentialRefs}
            onChange={setCredentialRefs}
            disabled={busy}
          />

          <FormControlLabel
            control={<Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />}
            label="Enable scheduled execution immediately"
          />

          {error ? <Alert severity="error">{error}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <M3Button emphasis="text" disabled={busy} onClick={resetAndClose}>
          Cancel
        </M3Button>
        <M3Button emphasis="filled" disabled={!canSubmit} onClick={() => void handleSubmit()}>
          {busy ? "Saving…" : "Save script"}
        </M3Button>
      </DialogActions>
    </Dialog>
  );
}
