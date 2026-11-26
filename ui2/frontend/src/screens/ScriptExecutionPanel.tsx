import { useCallback, useEffect, useMemo, useState, type ChangeEvent } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
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
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  addScriptVersion,
  disableScript,
  enableScript,
  getScript,
  listCredentials,
  listScripts,
  runScriptNow,
  updateScriptSettings,
  type CredentialView,
  type ScriptKind,
  type ScriptListResponse,
  type ScriptNotifyMode,
  type ScriptRunSummary,
  type ScriptSummary,
} from "../auth/adminApi";
import { useSession } from "../auth/SessionContext";
import { PRODUCT_NAME } from "../brand/product";
import { bytesTitle, formatBytes } from "../shell/bytes";
import { M3Button } from "../shell/M3Widgets";
import { actorLabel } from "../shell/presenters";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { PillRow, StatusPill } from "../shell/StatusPill";
import { Timestamp } from "../shell/Timestamp";
import { m3, MONO } from "../theme/m3Theme";
import { LABEL_UPPER, RADIUS, TYPE } from "../theme/tokens";
import { CreateScriptDialog, CredentialRefsPicker, KindRefusalAlert } from "./ScriptCreateDialog";
import {
  BINARY_SCRIPT_KINDS,
  DEFAULT_RUN_AS_OPTIONS,
  SCRIPT_KINDS,
  SCRIPT_KIND_LABELS,
  SCRIPT_NOTIFY_OPTIONS,
  describeApiError,
  describeRunNotification,
  extractAliasWarnings,
  formatRunAs,
  inferScriptKindFromExtension,
  notifySendsMail,
  parseEgressInput,
  readFileAsBase64,
  resolveRunAsOptions,
  resolveScheduleZone,
  validateFiveFieldCronShape,
} from "./ScriptFormHelpers";
import { ScriptRunsSection } from "./ScriptRunsSection";

export {
  SCRIPT_KINDS,
  extractAliasWarnings,
  hasRunCapability,
  inferScriptKindFromExtension,
  parseEgressInput,
  resolveRunAsOptions,
  resolveScheduleZone,
} from "./ScriptFormHelpers";

function notifyLabel(mode: string | null | undefined, mailOnFailureOnly?: boolean): string {
  const found = SCRIPT_NOTIFY_OPTIONS.find(([val]) => val === mode);
  const label = found ? found[1] : mode || "None";
  return mailOnFailureOnly && notifySendsMail(mode) ? `${label}; mail on failure only` : label;
}

function ScriptWarningsCard({
  script,
  title,
  testId,
  onDismiss,
}: {
  readonly script: ScriptSummary;
  readonly title: string;
  readonly testId: string;
  readonly onDismiss?: () => void;
}) {
  const secretCount = script.secret_findings?.count ?? 0;
  const findings = script.secret_findings?.findings ?? [];
  const lineNumbers = script.secret_findings?.line_numbers ?? [];
  const deviceTargets = script.egress_device_targets ?? [];
  const serverWarnings = script.warnings ?? [];
  const aliasWarnings = extractAliasWarnings(serverWarnings);
  const aliasSet = new Set(aliasWarnings);
  const otherServerWarnings = serverWarnings.filter((w) => !aliasSet.has(w));

  if (secretCount === 0 && deviceTargets.length === 0 && serverWarnings.length === 0) {
    return null;
  }

  return (
    <Card
      role="status"
      data-testid={testId}
      sx={{
        p: 2,
        bgcolor: m3.warningContainer,
        color: m3.onWarningContainer,
        boxShadow: "none",
        border: `1px solid ${m3.outlineVar}`,
        borderRadius: `${RADIUS.card}px`,
      }}
    >
      <Stack spacing={1.25}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" gap={1}>
          <Typography sx={{ ...TYPE.section, color: m3.onWarningContainer }}>{title}</Typography>
          {onDismiss ? (
            <M3Button emphasis="text" onClick={onDismiss}>
              Dismiss
            </M3Button>
          ) : null}
        </Stack>

        {secretCount > 0 ? (
          <Box data-testid={`${testId}-secrets`}>
            <Typography sx={{ ...TYPE.body, fontWeight: 600 }}>
              Secret-pattern findings ({secretCount} {secretCount === 1 ? "line" : "lines"} — matched values are never stored or shown):
            </Typography>
            <Stack component="ul" spacing={0.25} sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
              {findings.length > 0
                ? findings.map((f, idx) => (
                    <Typography component="li" key={`${f.line}-${f.pattern_class}-${idx}`} sx={{ ...TYPE.mono }}>
                      Line {f.line}: {f.pattern_class}
                    </Typography>
                  ))
                : lineNumbers.map((ln) => (
                    <Typography component="li" key={ln} sx={{ ...TYPE.mono }}>
                      Line {ln}
                    </Typography>
                  ))}
            </Stack>
            <Typography sx={{ ...TYPE.body, mt: 0.5 }} data-testid={`${testId}-cred-recommendation`}>
              Credential-store recommendation: declare credential-store references (<code>credential_refs</code>) instead of embedding inline credentials in script code. The uploaded script body is stored encrypted at rest and never returned.
            </Typography>
          </Box>
        ) : null}

        {deviceTargets.length > 0 ? (
          <Box data-testid={`${testId}-device-egress`}>
            <Typography sx={{ ...TYPE.body, fontWeight: 600 }}>
              Enrolled device egress ({deviceTargets.length}): direct path outside {PRODUCT_NAME} command gates
            </Typography>
            <Typography sx={{ ...TYPE.body, mt: 0.25 }}>
              Declared egress matches enrolled device management address(es) ({deviceTargets.join(", ")}). Network traffic on this egress is a direct path outside {PRODUCT_NAME} command gates and will be recorded on each run.
            </Typography>
          </Box>
        ) : null}

        {aliasWarnings.length > 0 ? (
          <Box data-testid={`${testId}-alias-warnings`}>
            <Typography sx={{ ...TYPE.body, fontWeight: 600 }}>
              Credential environment alias collision ({aliasWarnings.length}):
            </Typography>
            <Stack component="ul" spacing={0.25} sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
              {aliasWarnings.map((w, idx) => (
                <Typography component="li" key={idx} sx={{ ...TYPE.mono }}>
                  {w}
                </Typography>
              ))}
            </Stack>
          </Box>
        ) : null}

        {otherServerWarnings.length > 0 ? (
          <Stack spacing={0.5} data-testid={`${testId}-server-messages`}>
            {otherServerWarnings.map((w, idx) => (
              <Typography key={idx} sx={{ ...TYPE.label, opacity: 0.92 }}>
                {w}
              </Typography>
            ))}
          </Stack>
        ) : null}
      </Stack>
    </Card>
  );
}

function RunnerUnavailableBanner({ testId }: { readonly testId: string }) {
  return (
    <Card
      data-testid={testId}
      aria-disabled="true"
      sx={{
        p: 2,
        bgcolor: m3.scLow,
        boxShadow: "none",
        border: `1px solid ${m3.outlineVar}`,
        borderRadius: `${RADIUS.card}px`,
      }}
    >
      <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1.5}>
        <Box>
          <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
            Script runner unavailable
          </Typography>
          <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>
            No active runner broker heartbeat is present — Run now is disabled until the script runner is online. Stored run history remains readable in each script&apos;s detail view.
          </Typography>
        </Box>
        <M3Button emphasis="outlined" disabled>
          Run now
        </M3Button>
      </Stack>
    </Card>
  );
}

function UploadVersionDialog({
  script,
  onClose,
  onUploaded,
  credentials,
  credentialsError,
}: {
  readonly script: ScriptSummary;
  readonly onClose: () => void;
  readonly onUploaded: (updated: ScriptSummary) => void;
  readonly credentials: readonly CredentialView[] | null;
  readonly credentialsError: string | null;
}) {
  const isBinaryKind = (BINARY_SCRIPT_KINDS as readonly string[]).includes(script.kind);
  const [sourceMode, setSourceMode] = useState<"editor" | "upload">(isBinaryKind ? "upload" : "editor");
  const [fileName, setFileName] = useState("");
  const [codeBody, setCodeBody] = useState("");
  const [uploadedFileName, setUploadedFileName] = useState("");
  const [uploadedSize, setUploadedSize] = useState<number | null>(null);
  const [uploadedBase64, setUploadedBase64] = useState<string | null>(null);
  const [credentialRefs, setCredentialRefs] = useState<string[]>([...script.credential_refs]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const effectiveFileName = sourceMode === "upload" ? uploadedFileName : fileName;
  const extInference = useMemo(() => inferScriptKindFromExtension(effectiveFileName), [effectiveFileName]);
  const kindMismatch = Boolean(extInference.kind && extInference.kind !== script.kind);
  const extensionRefused =
    extInference.isBatOrCmd || extInference.isPs1 || Boolean(extInference.unsupportedExtension);

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setError(null);
    setUploadedFileName(file.name);
    setUploadedSize(file.size);
    const inf = inferScriptKindFromExtension(file.name);
    if (inf.isBatOrCmd || inf.isPs1 || inf.unsupportedExtension) {
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
    setError(null);
    onClose();
  };

  const hasBody = sourceMode === "upload" ? Boolean(uploadedBase64) : codeBody.length > 0;
  const canSubmit = !busy && !extensionRefused && !kindMismatch && hasBody;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await addScriptVersion(script.script_id, {
        file_name: effectiveFileName.trim() || undefined,
        kind: script.kind,
        body: sourceMode === "editor" ? codeBody : undefined,
        content_base64: sourceMode === "upload" ? (uploadedBase64 ?? undefined) : undefined,
        credential_refs: credentialRefs,
      });
      setCodeBody("");
      setUploadedBase64(null);
      setUploadedFileName("");
      setUploadedSize(null);
      onUploaded(updated);
    } catch (submitErr) {
      setError(describeApiError(submitErr));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={() => !busy && resetAndClose()} fullWidth maxWidth="md">
      <DialogTitle>
        Upload new version · {script.name} (v{script.current_version + 1})
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info" data-testid="write-only-version-notice">
            <strong>Write-only script body:</strong> Stored versions cannot be viewed or edited in place. This upload creates immutable version v{script.current_version + 1}, encrypted at rest, and the submitted text is never shown again after save.
          </Alert>

          {!isBinaryKind ? (
            <RadioGroup
              row
              aria-label="Version input method"
              value={sourceMode}
              onChange={(e) => {
                setSourceMode(e.target.value === "upload" ? "upload" : "editor");
                setError(null);
              }}
            >
              <FormControlLabel value="editor" control={<Radio size="small" />} label="Code editor" />
              <FormControlLabel value="upload" control={<Radio size="small" />} label="File upload" />
            </RadioGroup>
          ) : (
            <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>
              Binary script kind (<code>{script.kind}</code>) requires file upload.
            </Typography>
          )}

          {extensionRefused ? (
            <KindRefusalAlert inference={extInference} />
          ) : kindMismatch ? (
            <Alert severity="error">
              Selected file extension kind (<code>{extInference.kind}</code>) does not match script kind (<code>{script.kind}</code>).
            </Alert>
          ) : null}

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
                  Choose version file (.{script.kind})
                  <input
                    type="file"
                    hidden
                    aria-label="Version script file"
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
            <>
              <TextField
                size="small"
                label="File name (optional)"
                placeholder={`${script.name}.${script.kind}`}
                value={fileName}
                onChange={(e) => setFileName(e.target.value)}
              />
              <TextField
                label="New version code"
                multiline
                minRows={8}
                fullWidth
                value={codeBody}
                onChange={(e) => setCodeBody(e.target.value)}
                InputProps={{ sx: { fontFamily: MONO, fontSize: TYPE.mono.fontSize } }}
                helperText="Sent once, scanned for secret patterns (warning only), encrypted at rest, and never shown again."
              />
            </>
          )}

          <CredentialRefsPicker
            credentials={credentials}
            credentialsError={credentialsError}
            selectedRefs={credentialRefs}
            onChange={setCredentialRefs}
            disabled={busy}
          />

          {error ? <Alert severity="error">{error}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <M3Button emphasis="text" disabled={busy} onClick={resetAndClose}>
          Cancel
        </M3Button>
        <M3Button emphasis="filled" disabled={!canSubmit} onClick={() => void handleSubmit()}>
          {busy ? "Uploading…" : `Save v${script.current_version + 1}`}
        </M3Button>
      </DialogActions>
    </Dialog>
  );
}

function ScriptSettingsAndDetailCard({
  script,
  canEdit,
  canRun,
  runnerAvailable,
  canReadOutput,
  isMasked,
  runAsOptions,
  scheduleZone,
  credentials,
  credentialsError,
  externalRun,
  onUpdated,
  onOpenUploadVersion,
  onClose,
}: {
  readonly script: ScriptSummary;
  readonly canEdit: boolean;
  readonly canRun: boolean;
  readonly runnerAvailable: boolean;
  readonly canReadOutput: boolean;
  readonly isMasked: boolean;
  readonly runAsOptions: readonly string[];
  readonly scheduleZone: string;
  readonly credentials: readonly CredentialView[] | null;
  readonly credentialsError: string | null;
  readonly externalRun: ScriptRunSummary | null;
  readonly onUpdated: (updated: ScriptSummary, showPostSaveBanner: boolean) => void;
  readonly onOpenUploadVersion: () => void;
  readonly onClose: () => void;
}) {
  const defaultRunAs = runAsOptions[0] ?? DEFAULT_RUN_AS_OPTIONS[0];
  const [schedule, setSchedule] = useState(script.schedule ?? "");
  const [enabled, setEnabled] = useState(script.enabled);
  const [timeoutSeconds, setTimeoutSeconds] = useState(String(script.timeout_s));
  const [egressText, setEgressText] = useState(script.egress.join("\n"));
  const [notify, setNotify] = useState<ScriptNotifyMode>(
    (SCRIPT_NOTIFY_OPTIONS.some(([m]) => m === script.notify) ? script.notify : "none") as ScriptNotifyMode,
  );
  const [mailOnFailureOnly, setMailOnFailureOnly] = useState(Boolean(script.notify_mail_on_failure_only));
  const [runAs, setRunAs] = useState(
    runAsOptions.includes(script.run_as) ? script.run_as : defaultRunAs,
  );
  const [credentialRefs, setCredentialRefs] = useState<string[]>([...script.credential_refs]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedNote, setSavedNote] = useState<string | null>(null);

  useEffect(() => {
    setSchedule(script.schedule ?? "");
    setEnabled(script.enabled);
    setTimeoutSeconds(String(script.timeout_s));
    setEgressText(script.egress.join("\n"));
    setNotify((SCRIPT_NOTIFY_OPTIONS.some(([m]) => m === script.notify) ? script.notify : "none") as ScriptNotifyMode);
    setMailOnFailureOnly(Boolean(script.notify_mail_on_failure_only));
    setRunAs(runAsOptions.includes(script.run_as) ? script.run_as : defaultRunAs);
    setCredentialRefs([...script.credential_refs]);
    setError(null);
  }, [script, runAsOptions, defaultRunAs]);

  const effectiveRunAs = runAsOptions.includes(runAs) ? runAs : defaultRunAs;
  const parsedTimeout = Number(timeoutSeconds);
  const timeoutValid = Number.isInteger(parsedTimeout) && parsedTimeout >= 1 && parsedTimeout <= 3600;
  const scheduleError = validateFiveFieldCronShape(schedule);
  const scheduleChanged = (schedule.trim() || null) !== (script.schedule ?? null);
  const credentialRefsChanged =
    credentialRefs.length !== script.credential_refs.length ||
    credentialRefs.some((refId, idx) => refId !== script.credential_refs[idx]);

  const handleSaveSettings = async () => {
    if (!canEdit || busy || !timeoutValid || scheduleError !== null) return;
    setBusy(true);
    setError(null);
    setSavedNote(null);
    const prevVersion = script.current_version;
    try {
      const trimmedSchedule = schedule.trim();
      const updated = await updateScriptSettings(script.script_id, {
        schedule: trimmedSchedule || undefined,
        clear_schedule: trimmedSchedule === "",
        enabled,
        timeout_s: parsedTimeout,
        egress: parseEgressInput(egressText),
        credential_refs: credentialRefs,
        notify,
        notify_mail_on_failure_only: notifySendsMail(notify) && mailOnFailureOnly,
        run_as: effectiveRunAs,
      });
      setSavedNote(
        updated.current_version > prevVersion
          ? `Settings saved — recorded new version v${updated.current_version} for updated credential references.`
          : "Settings saved.",
      );
      onUpdated(updated, true);
    } catch (saveErr) {
      setError(describeApiError(saveErr));
    } finally {
      setBusy(false);
    }
  };

  const handleToggleEnabled = async (nextEnabled: boolean) => {
    if (!canEdit || busy) return;
    setBusy(true);
    setError(null);
    setSavedNote(null);
    try {
      const updated = nextEnabled
        ? await enableScript(script.script_id)
        : await disableScript(script.script_id);
      setSavedNote(nextEnabled ? "Script enabled." : "Script disabled.");
      onUpdated(updated, false);
    } catch (toggleErr) {
      setError(describeApiError(toggleErr));
    } finally {
      setBusy(false);
    }
  };

  const versions = script.versions ?? [];

  return (
    <Card
      data-testid="script-detail-panel"
      sx={{
        p: 2,
        bgcolor: m3.scLowest,
        boxShadow: "none",
        border: `1px solid ${m3.outlineVar}`,
        borderRadius: `${RADIUS.card}px`,
      }}
    >
      <Stack spacing={2}>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" flexWrap="wrap" gap={1}>
          <Box>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography sx={{ ...TYPE.section, fontFamily: MONO, color: m3.onSurface }}>
                {script.name}
              </Typography>
              <StatusPill tone="neutral" word={script.kind} dense />
              <StatusPill
                tone={script.enabled ? "ok" : "neutral"}
                word={script.enabled ? "Enabled" : "Disabled"}
                dense
              />
              <Typography sx={{ ...TYPE.mono, color: m3.onSurfaceVar }}>
                v{script.current_version} · <span title={bytesTitle(script.size_bytes)}>{formatBytes(script.size_bytes)}</span>
              </Typography>
            </Stack>
            <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar, mt: 0.5 }}>
              Created by {actorLabel(script.created_by_actor_fingerprint).label} · Updated <Timestamp at={script.updated_at} />
              {script.sha256 ? ` · SHA-256 ${script.sha256.slice(0, 12)}…` : ""}
            </Typography>
          </Box>
          <Stack direction="row" spacing={1} alignItems="center">
            {canEdit ? (
              <>
                <M3Button
                  emphasis="outlined"
                  disabled={busy}
                  onClick={() => void handleToggleEnabled(!script.enabled)}
                >
                  {script.enabled ? "Disable" : "Enable"}
                </M3Button>
                <M3Button emphasis="tonal" disabled={busy} onClick={onOpenUploadVersion}>
                  Upload new version
                </M3Button>
              </>
            ) : null}
            <M3Button emphasis="text" onClick={onClose}>
              Close
            </M3Button>
          </Stack>
        </Stack>

        <Box
          data-testid="script-detail-write-only-note"
          sx={{
            p: 1.5,
            borderRadius: `${RADIUS.pill}px`,
            bgcolor: m3.scLow,
            border: `1px solid ${m3.outlineVar}`,
          }}
        >
          <Typography sx={{ ...TYPE.body, color: m3.onSurface }}>
            <strong>Write-only encrypted storage:</strong> Stored script bodies are envelope-encrypted at rest and never returned by any API or CLI route. No code viewer exists for stored versions; upload a new version to update script logic.
          </Typography>
        </Box>

        <ScriptWarningsCard
          script={script}
          title="Active script warnings"
          testId="script-detail-warnings"
        />

        <Box
          data-testid="script-next-five-fires"
          sx={{
            p: 1.5,
            borderRadius: `${RADIUS.pill}px`,
            bgcolor: m3.scLow,
            border: `1px solid ${m3.outlineVar}`,
          }}
        >
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.75 }}>
            <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>
              Next five fires (server preview)
            </Typography>
            <Chip
              size="small"
              data-testid="script-schedule-zone"
              label={scheduleZone}
              title={`Schedule evaluated in ${scheduleZone}`}
              sx={{ fontFamily: MONO }}
            />
          </Stack>
          {script.next_five_fires.length > 0 ? (
            <Stack component="ol" spacing={0.5} sx={{ m: 0, pl: 2.5 }}>
              {script.next_five_fires.map((iso) => (
                <Typography component="li" key={iso} sx={{ ...TYPE.mono, color: m3.onSurface }}>
                  <Timestamp at={iso} />
                </Typography>
              ))}
            </Stack>
          ) : (
            <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>
              No scheduled fires (no cron schedule configured).
            </Typography>
          )}
          {canEdit && scheduleChanged ? (
            <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar, mt: 0.75 }}>
              Save settings to compute updated fire times on the server ({scheduleZone}).
            </Typography>
          ) : null}
        </Box>

        <ScriptRunsSection
          script={script}
          canRun={canRun}
          runnerAvailable={runnerAvailable}
          canReadOutput={canReadOutput}
          isMasked={isMasked}
          externalRun={externalRun}
        />

        {canEdit ? (
          <Stack spacing={2} data-testid="script-settings-editor">
            <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>Settings</Typography>

            <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} alignItems="flex-start">
              <TextField
                size="small"
                label="Schedule (five-field cron)"
                fullWidth
                placeholder="0 2 * * *"
                value={schedule}
                onChange={(e) => setSchedule(e.target.value)}
                error={scheduleError !== null}
                InputProps={{ sx: { fontFamily: MONO } }}
                helperText={
                  scheduleError ??
                  `minute hour day-of-month month day-of-week in ${scheduleZone} (leave blank to clear)`
                }
              />
              {schedule ? (
                <M3Button emphasis="text" disabled={busy} onClick={() => setSchedule("")}>
                  Clear schedule
                </M3Button>
              ) : null}
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
                helperText="Role granted to the per-run CLI session (allowlisted roles only; Security Admin is forbidden)"
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
              value={egressText}
              onChange={(e) => setEgressText(e.target.value)}
              InputProps={{ sx: { fontFamily: MONO } }}
              helperText="Comma- or newline-separated host:port entries (empty = none)"
            />

            <CredentialRefsPicker
              credentials={credentials}
              credentialsError={credentialsError}
              selectedRefs={credentialRefs}
              onChange={setCredentialRefs}
              disabled={busy}
              versionNote="Changing credential references here creates a new version with the existing encrypted script body."
            />

            {credentialRefsChanged ? (
              <Alert severity="info" data-testid="credential-refs-version-note">
                Changing <code>credential_refs</code> records new immutable version <strong>v{script.current_version + 1}</strong> with the existing encrypted script body and the updated references.
              </Alert>
            ) : null}

            <FormControlLabel
              control={<Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} disabled={busy} />}
              label="Enabled"
            />

            {error ? <Alert severity="error">{error}</Alert> : null}
            {savedNote ? <Alert severity="success">{savedNote}</Alert> : null}

            <Stack direction="row" spacing={1.5}>
              <M3Button
                emphasis="filled"
                disabled={busy || !timeoutValid || scheduleError !== null || !effectiveRunAs}
                onClick={() => void handleSaveSettings()}
              >
                {busy ? "Saving…" : "Save settings"}
              </M3Button>
            </Stack>
          </Stack>
        ) : (
          <Stack spacing={1.25} data-testid="script-settings-readonly">
            <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
              Settings (read-only — Security Admin required to edit)
            </Typography>
            <Box
              sx={{
                display: "grid",
                gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" },
                gap: 1.5,
              }}
            >
              <Box>
                <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Schedule ({scheduleZone})</Typography>
                <Typography sx={{ ...TYPE.mono }}>{script.schedule ?? "Unscheduled"}</Typography>
              </Box>
              <Box>
                <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Timeout</Typography>
                <Typography sx={{ ...TYPE.mono }}>{script.timeout_s} s</Typography>
              </Box>
              <Box>
                <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Notify</Typography>
                <Typography sx={{ ...TYPE.body }}>{notifyLabel(script.notify, script.notify_mail_on_failure_only)}</Typography>
              </Box>
              <Box>
                <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Run as</Typography>
                <Typography sx={{ ...TYPE.mono }}>{formatRunAs(script.run_as)}</Typography>
              </Box>
              <Box>
                <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Declared egress</Typography>
                {script.egress.length > 0 ? (
                  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
                    {script.egress.map((entry) => (
                      <Chip key={entry} size="small" label={entry} sx={{ fontFamily: MONO }} />
                    ))}
                  </Stack>
                ) : (
                  <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>None</Typography>
                )}
              </Box>
              <Box>
                <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Credential references</Typography>
                {script.credential_refs.length > 0 ? (
                  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
                    {script.credential_refs.map((refId) => (
                      <Chip key={refId} size="small" label={refId} sx={{ fontFamily: MONO }} />
                    ))}
                  </Stack>
                ) : (
                  <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>None</Typography>
                )}
              </Box>
            </Box>
          </Stack>
        )}

        {versions.length > 0 ? (
          <Box>
            <Typography sx={{ ...TYPE.section, color: m3.onSurface, mb: 1 }}>
              Version history (metadata only — bodies are write-only)
            </Typography>
            <Box sx={{ overflowX: "auto", border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px` }}>
              <Table size="small" aria-label="Script versions table">
                <TableHead>
                  <TableRow>
                    <TableCell>Version</TableCell>
                    <TableCell>SHA-256</TableCell>
                    <TableCell>Size</TableCell>
                    <TableCell>Secret findings</TableCell>
                    <TableCell>Credential refs</TableCell>
                    <TableCell>Uploaded by</TableCell>
                    <TableCell>Uploaded at</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {versions.map((ver) => (
                    <TableRow key={ver.version}>
                      <TableCell sx={{ ...TYPE.mono, fontWeight: 600 }}>v{ver.version}</TableCell>
                      <TableCell sx={{ ...TYPE.mono }} title={ver.sha256}>
                        {ver.sha256.slice(0, 12)}…
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono }} title={bytesTitle(ver.size_bytes)}>
                        {formatBytes(ver.size_bytes)}
                      </TableCell>
                      <TableCell>
                        {ver.secret_findings.count > 0 ? (
                          <StatusPill
                            tone="warn"
                            word="Secret findings"
                            count={ver.secret_findings.count}
                            title={`Lines: ${ver.secret_findings.line_numbers.join(", ")}`}
                            dense
                          />
                        ) : (
                          <StatusPill tone="neutral" word="None" dense />
                        )}
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono }}>
                        {ver.credential_refs.length > 0 ? ver.credential_refs.join(", ") : "—"}
                      </TableCell>
                      <TableCell>{actorLabel(ver.created_by).label}</TableCell>
                      <TableCell>
                        <Timestamp at={ver.created_at} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          </Box>
        ) : null}
      </Stack>
    </Card>
  );
}

/**
 * The list's "Last run" cell: the outcome token of the latest FINISHED run and the age of that evidence. Only
 * SUCCESS may look positive; FAILURE and TIMED_OUT are bad; a script that never finished a run, and any token this
 * screen does not know, stay neutral (an unknown token is written as received, never as OK).
 */
function LastRunPill({ outcome, at }: { readonly outcome: string | null | undefined; readonly at: string | null | undefined }) {
  if (!outcome) {
    return <StatusPill tone="neutral" word="never run" dense fact="last-run-never" />;
  }
  const tone = outcome === "SUCCESS" ? "ok" : outcome === "FAILURE" || outcome === "TIMED_OUT" ? "bad" : "neutral";
  return <StatusPill tone={tone} word={outcome} age={at ?? undefined} title={at ? `Finished ${at}` : undefined} dense fact="last-run-outcome" />;
}

export function ScriptExecutionPanel() {
  const session = useSession();
  const [data, setData] = useState<ScriptListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [kindFilter, setKindFilter] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedDetail, setSelectedDetail] = useState<ScriptSummary | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [uploadVersionFor, setUploadVersionFor] = useState<ScriptSummary | null>(null);
  const [postSaveScript, setPostSaveScript] = useState<ScriptSummary | null>(null);
  const [rowBusyId, setRowBusyId] = useState<string | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);
  const [externalRun, setExternalRun] = useState<ScriptRunSummary | null>(null);

  const isMasked = data?.masked === true;
  const canEdit =
    !isMasked &&
    (typeof selectedDetail?.can_write === "boolean"
      ? selectedDetail.can_write
      : typeof data?.can_write === "boolean"
        ? data.can_write
        : Boolean(session?.canReadAdministration));
  const canRun =
    !isMasked &&
    (typeof selectedDetail?.can_run === "boolean"
      ? selectedDetail.can_run
      : data?.can_run === true);
  const runnerAvailable =
    typeof selectedDetail?.runner_available === "boolean"
      ? selectedDetail.runner_available
      : data?.runner_available === true;
  const canReadOutput =
    !isMasked &&
    (typeof selectedDetail?.can_read_output === "boolean"
      ? selectedDetail.can_read_output
      : data?.can_read_output === true);

  const runAsOptions = resolveRunAsOptions(data, selectedDetail);
  const scheduleZone = resolveScheduleZone(data, selectedDetail);

  const [credentials, setCredentials] = useState<readonly CredentialView[] | null>(null);
  const [credentialsError, setCredentialsError] = useState<string | null>(null);

  const loadList = useCallback(() => {
    setLoading(true);
    setError(null);
    return listScripts()
      .then((res) => {
        setData(res);
        setLoading(false);
        return res;
      })
      .catch((err: unknown) => {
        setError(err);
        setLoading(false);
        return null;
      });
  }, []);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  useEffect(() => {
    if (!canEdit) {
      setCredentials(null);
      setCredentialsError(null);
      return;
    }
    let cancelled = false;
    listCredentials()
      .then((res) => {
        if (!cancelled) {
          setCredentials(res.credentials ?? []);
          setCredentialsError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setCredentials([]);
          setCredentialsError(describeApiError(err));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [canEdit]);

  const openScriptDetail = useCallback(async (summary: ScriptSummary) => {
    setSelectedId(summary.script_id);
    setSelectedDetail(summary);
    try {
      const full = await getScript(summary.script_id);
      setSelectedDetail(full);
    } catch {
      // Keep list summary when detail fetch fails transiently.
    }
  }, []);

  const handleRowToggleEnabled = async (row: ScriptSummary) => {
    if (!canEdit || rowBusyId) return;
    setRowBusyId(row.script_id);
    setRowError(null);
    try {
      const updated = row.enabled
        ? await disableScript(row.script_id)
        : await enableScript(row.script_id);
      if (selectedId === updated.script_id) {
        setSelectedDetail(updated);
      }
      await loadList();
    } catch (err) {
      setRowError(describeApiError(err));
    } finally {
      setRowBusyId(null);
    }
  };

  const handleRowRunNow = async (row: ScriptSummary) => {
    const rowCanRun = !isMasked && (typeof row.can_run === "boolean" ? row.can_run : canRun);
    const rowRunnerAvailable =
      typeof row.runner_available === "boolean" ? row.runner_available : runnerAvailable;
    if (!rowCanRun || !rowRunnerAvailable || rowBusyId) return;
    setRowBusyId(row.script_id);
    setRowError(null);
    try {
      const submitted = await runScriptNow(row.script_id);
      setExternalRun(submitted);
      await openScriptDetail(row);
    } catch (err) {
      setRowError(describeApiError(err));
    } finally {
      setRowBusyId(null);
    }
  };

  if (error && isRestricted(error)) {
    return <RestrictedPanel area="Script execution" />;
  }

  const scripts = data?.scripts ?? [];
  const enabledCount = scripts.filter((s) => s.enabled).length;
  const disabledCount = scripts.length - enabledCount;
  const warningScriptsCount = scripts.filter(
    (s) =>
      (s.secret_findings?.count ?? 0) > 0 ||
      (s.egress_device_targets?.length ?? 0) > 0 ||
      (s.warnings?.length ?? 0) > 0,
  ).length;

  const qLower = searchQuery.trim().toLowerCase();
  const filteredScripts = scripts.filter((row) => {
    if (kindFilter && row.kind !== kindFilter) return false;
    if (!qLower) return true;
    return (
      row.name.toLowerCase().includes(qLower) ||
      row.kind.toLowerCase().includes(qLower) ||
      (row.schedule ?? "").toLowerCase().includes(qLower)
    );
  });

  const showRowActions = canEdit || canRun;

  return (
    <Stack spacing={2} data-testid="script-execution-panel">
      <Card
        sx={{
          p: 2,
          bgcolor: m3.scLowest,
          boxShadow: "none",
          border: `1px solid ${m3.outlineVar}`,
          borderRadius: `${RADIUS.card}px`,
        }}
      >
        <Stack spacing={1.5}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1}>
            <Box>
              <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
                Script execution
              </Typography>
              <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>
                Operator-supplied scripts stored encrypted at rest (write-only; editing uploads a new version), executed in isolated per-run pods with five-field cron schedules ({scheduleZone}), declared egress, and versioned credential-store references.
              </Typography>
            </Box>
            {canEdit ? (
              <M3Button emphasis="filled" onClick={() => setCreateOpen(true)}>
                New script
              </M3Button>
            ) : null}
          </Stack>

          {!canEdit && !loading && !error ? (
            <Box
              data-testid="script-exec-readonly-note"
              sx={{
                p: 1.5,
                borderRadius: `${RADIUS.pill}px`,
                bgcolor: m3.scLow,
                border: `1px solid ${m3.outlineVar}`,
              }}
            >
              <Typography sx={{ ...TYPE.body, color: m3.onSurface }}>
                <strong>{canRun ? "Edit controls gated (can_write required):" : "Read-only view:"}</strong> Only a Security Admin can create scripts, upload new versions, or change script settings.
                {canRun ? " Operator execution and audited run output downloads remain available." : ""}
                {isMasked ? " Identity, egress, and run output values are shown in masked projection." : ""}
              </Typography>
            </Box>
          ) : null}

          <Stack
            direction="row"
            spacing={1}
            useFlexGap
            flexWrap="wrap"
            alignItems="center"
            data-testid="script-exec-counts"
          >
            {[
              { key: "total", word: "Scripts", tone: "neutral" as const, count: scripts.length },
              { key: "enabled", word: "Enabled", tone: "ok" as const, count: enabledCount },
              { key: "disabled", word: "Disabled", tone: "neutral" as const, count: disabledCount },
              {
                key: "warnings",
                word: "With warnings",
                tone: warningScriptsCount > 0 ? ("warn" as const) : ("neutral" as const),
                count: warningScriptsCount,
              },
            ].map((item) => (
              <Box
                key={item.key}
                sx={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 0.75,
                  px: 1,
                  py: 0.5,
                  borderRadius: `${RADIUS.pill}px`,
                  bgcolor: m3.scLow,
                  border: `1px solid ${m3.outlineVar}`,
                }}
              >
                <StatusPill tone={item.tone} word={item.word} dense />
                <Typography sx={{ ...TYPE.mono, fontWeight: 600, color: m3.onSurface }}>
                  {item.count}
                </Typography>
              </Box>
            ))}
          </Stack>

          <Stack direction="row" spacing={1.5} useFlexGap flexWrap="wrap" alignItems="center">
            <TextField
              select
              size="small"
              label="Kind"
              value={kindFilter}
              onChange={(e) => setKindFilter(e.target.value)}
              inputProps={{ "aria-label": "Kind filter" }}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">All kinds</MenuItem>
              {SCRIPT_KINDS.map((k) => (
                <MenuItem key={k} value={k}>
                  {k}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              size="small"
              label="Search"
              placeholder="Filter script name, kind, or schedule…"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              inputProps={{ "aria-label": "Search scripts" }}
              sx={{ minWidth: 260, flexGrow: 1 }}
            />
          </Stack>
        </Stack>
      </Card>

      {rowError ? <Alert severity="error">{rowError}</Alert> : null}

      {postSaveScript ? (
        <Stack spacing={1} data-testid="script-post-save-banner">
          <Alert severity="success" onClose={() => setPostSaveScript(null)}>
            Saved <strong>{postSaveScript.name}</strong> (v{postSaveScript.current_version}). Stored script bodies are write-only and never shown again.
          </Alert>
          <ScriptWarningsCard
            script={postSaveScript}
            title={`Warnings for ${postSaveScript.name} (v${postSaveScript.current_version})`}
            testId="script-post-save-warnings"
            onDismiss={() => setPostSaveScript(null)}
          />
        </Stack>
      ) : null}

      {error ? (
        <StatePanel
          variant="error"
          title="Could not load scripts."
          body={describeApiError(error)}
          action={
            <M3Button emphasis="outlined" onClick={() => void loadList()}>
              Retry
            </M3Button>
          }
        />
      ) : loading && !data ? (
        <StatePanel variant="empty" title="Loading scripts…" />
      ) : scripts.length === 0 ? (
        <StatePanel
          variant="empty"
          title="No scripts registered yet"
          body="Upload or write an operator script (.sh, .bash, .py, .jar, .js, .mjs, .pl, .rb, or .bin) with an optional cron schedule, declared egress, and credential-store references."
          action={
            canEdit ? (
              <M3Button emphasis="filled" onClick={() => setCreateOpen(true)}>
                New script
              </M3Button>
            ) : undefined
          }
        />
      ) : filteredScripts.length === 0 ? (
        <StatePanel variant="empty" title="No scripts match the current filters." />
      ) : (
        <Card
          sx={{
            p: 0,
            bgcolor: m3.scLowest,
            boxShadow: "none",
            border: `1px solid ${m3.outlineVar}`,
            borderRadius: `${RADIUS.card}px`,
            overflowX: "auto",
          }}
        >
          <Table size="small" aria-label="Scripts table">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Kind</TableCell>
                <TableCell>Current version</TableCell>
                <TableCell>Schedule</TableCell>
                <TableCell>Enabled</TableCell>
                <TableCell>Last run</TableCell>
                <TableCell>Next fires</TableCell>
                <TableCell>Warnings</TableCell>
                {showRowActions ? <TableCell align="right">Actions</TableCell> : null}
              </TableRow>
            </TableHead>
            <TableBody>
              {filteredScripts.map((row) => {
                const secretCount = row.secret_findings?.count ?? 0;
                const deviceEgressCount = row.egress_device_targets?.length ?? 0;
                const aliasWarnings = extractAliasWarnings(row.warnings);
                const otherWarningCount =
                  secretCount === 0 && deviceEgressCount === 0 && aliasWarnings.length === 0
                    ? (row.warnings?.length ?? 0)
                    : 0;
                const hasAnyBadgeWarning =
                  secretCount > 0 || deviceEgressCount > 0 || aliasWarnings.length > 0 || otherWarningCount > 0;
                const isSelected = selectedId === row.script_id;
                const rowCanRun = !isMasked && (typeof row.can_run === "boolean" ? row.can_run : canRun);
                const rowRunnerAvailable =
                  typeof row.runner_available === "boolean" ? row.runner_available : runnerAvailable;

                return (
                  <TableRow
                    key={row.script_id}
                    hover
                    selected={isSelected}
                    data-script-id={row.script_id}
                  >
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      <Box
                        component="button"
                        type="button"
                        onClick={() => void openScriptDetail(row)}
                        sx={{
                          all: "unset",
                          cursor: "pointer",
                          ...TYPE.mono,
                          fontWeight: 600,
                          color: m3.primary,
                          "&:hover": { textDecoration: "underline" },
                          "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 },
                        }}
                      >
                        {row.name}
                      </Box>
                    </TableCell>
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      <StatusPill
                        tone="neutral"
                        word={row.kind}
                        title={SCRIPT_KIND_LABELS[row.kind as ScriptKind] ?? row.kind}
                        dense
                      />
                    </TableCell>
                    <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>
                      v{row.current_version}
                    </TableCell>
                    <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>
                      {row.schedule ?? "Unscheduled"}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      <StatusPill
                        tone={row.enabled ? "ok" : "neutral"}
                        word={row.enabled ? "Enabled" : "Disabled"}
                        dense
                      />
                    </TableCell>
                    <TableCell sx={{ whiteSpace: "nowrap" }} data-testid={`last-run-${row.script_id}`}>
                      <LastRunPill outcome={row.last_run_outcome} at={row.last_run_at} />
                    </TableCell>
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      {row.next_five_fires.length > 0 ? (
                        <Stack direction="row" spacing={0.75} alignItems="center">
                          <Timestamp at={row.next_five_fires[0]} />
                          {row.next_five_fires.length > 1 ? (
                            <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>
                              (+{row.next_five_fires.length - 1})
                            </Typography>
                          ) : null}
                        </Stack>
                      ) : (
                        "—"
                      )}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      {!hasAnyBadgeWarning ? (
                        <StatusPill tone="neutral" word="None" dense />
                      ) : (
                        <PillRow testId={`warnings-badge-${row.script_id}`}>
                          {secretCount > 0 ? (
                            <StatusPill
                              tone="warn"
                              word="Secret findings"
                              count={secretCount}
                              title={`Lines: ${(row.secret_findings?.line_numbers ?? []).join(", ")}`}
                              dense
                            />
                          ) : null}
                          {deviceEgressCount > 0 ? (
                            <StatusPill
                              tone="warn"
                              word="Device egress"
                              count={deviceEgressCount}
                              title={`Enrolled device target(s): ${row.egress_device_targets.join(", ")}`}
                              dense
                            />
                          ) : null}
                          {aliasWarnings.length > 0 ? (
                            <StatusPill
                              tone="warn"
                              word="Alias collision"
                              count={aliasWarnings.length}
                              title={aliasWarnings.join(" ")}
                              dense
                            />
                          ) : null}
                          {otherWarningCount > 0 ? (
                            <StatusPill
                              tone="warn"
                              word="Warnings"
                              count={otherWarningCount}
                              title={(row.warnings ?? []).join(" ")}
                              dense
                            />
                          ) : null}
                        </PillRow>
                      )}
                    </TableCell>
                    {showRowActions ? (
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                          {rowCanRun ? (
                            <M3Button
                              emphasis="text"
                              disabled={!rowRunnerAvailable || rowBusyId === row.script_id}
                              onClick={() => void handleRowRunNow(row)}
                            >
                              Run now
                            </M3Button>
                          ) : null}
                          {canEdit ? (
                            <>
                              <M3Button
                                emphasis="text"
                                disabled={rowBusyId === row.script_id}
                                onClick={() => void handleRowToggleEnabled(row)}
                              >
                                {row.enabled ? "Disable" : "Enable"}
                              </M3Button>
                              <M3Button
                                emphasis="text"
                                disabled={rowBusyId === row.script_id}
                                onClick={() => setUploadVersionFor(row)}
                              >
                                New version
                              </M3Button>
                              <M3Button
                                emphasis="text"
                                onClick={() => void openScriptDetail(row)}
                              >
                                Settings
                              </M3Button>
                            </>
                          ) : null}
                        </Stack>
                      </TableCell>
                    ) : null}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Card>
      )}

      {selectedDetail ? (
        <ScriptSettingsAndDetailCard
          script={selectedDetail}
          canEdit={canEdit}
          canRun={canRun}
          runnerAvailable={runnerAvailable}
          canReadOutput={canReadOutput}
          isMasked={isMasked}
          runAsOptions={runAsOptions}
          scheduleZone={scheduleZone}
          credentials={credentials}
          credentialsError={credentialsError}
          externalRun={externalRun}
          onUpdated={(updated, showBanner) => {
            setSelectedDetail(updated);
            if (showBanner) setPostSaveScript(updated);
            void loadList();
          }}
          onOpenUploadVersion={() => setUploadVersionFor(selectedDetail)}
          onClose={() => {
            setSelectedId(null);
            setSelectedDetail(null);
          }}
        />
      ) : null}

      {!runnerAvailable && !loading && !error ? (
        <RunnerUnavailableBanner testId="script-runner-disabled-section" />
      ) : null}

      {canEdit && createOpen ? (
        <CreateScriptDialog
          open={createOpen}
          onClose={() => setCreateOpen(false)}
          onCreated={(created) => {
            setCreateOpen(false);
            setPostSaveScript(created);
            setSelectedId(created.script_id);
            setSelectedDetail(created);
            void loadList();
          }}
          credentials={credentials}
          credentialsError={credentialsError}
          runAsOptions={runAsOptions}
          scheduleZone={scheduleZone}
        />
      ) : null}

      {canEdit && uploadVersionFor ? (
        <UploadVersionDialog
          script={uploadVersionFor}
          onClose={() => setUploadVersionFor(null)}
          onUploaded={(updated) => {
            setUploadVersionFor(null);
            setPostSaveScript(updated);
            setSelectedId(updated.script_id);
            setSelectedDetail(updated);
            void loadList();
          }}
          credentials={credentials}
          credentialsError={credentialsError}
        />
      ) : null}
    </Stack>
  );
}
