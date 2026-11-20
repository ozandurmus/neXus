import { useCallback, useEffect, useMemo, useState, type ChangeEvent } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
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
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  addScriptVersion,
  createScript,
  disableScript,
  downloadScriptRunOutput,
  enableScript,
  getScript,
  getScriptRun,
  listCredentials,
  listScriptRuns,
  listScripts,
  runScriptNow,
  updateScriptSettings,
  type ApiError,
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
import { roleTokenLabel } from "../shell/roleLabel";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { PillRow, StatusPill } from "../shell/StatusPill";
import type { Tone } from "../shell/tone";
import { Timestamp } from "../shell/Timestamp";
import { m3, MONO } from "../theme/m3Theme";
import { LABEL_UPPER, RADIUS, TYPE } from "../theme/tokens";

export const SCRIPT_KINDS: readonly ScriptKind[] = [
  "sh",
  "bash",
  "py",
  "jar",
  "js",
  "mjs",
  "pl",
  "rb",
  "bin",
] as const;

export const TEXT_SCRIPT_KINDS: readonly ScriptKind[] = [
  "sh",
  "bash",
  "py",
  "js",
  "mjs",
  "pl",
  "rb",
] as const;

export const BINARY_SCRIPT_KINDS: readonly ScriptKind[] = ["jar", "bin"] as const;

export const SCRIPT_KIND_LABELS: Readonly<Record<ScriptKind, string>> = {
  sh: "POSIX shell (.sh)",
  bash: "Bash 5 (.bash)",
  py: "Python 3 (.py)",
  jar: "Java archive (.jar)",
  js: "Node.js (.js)",
  mjs: "Node.js ES module (.mjs)",
  pl: "Perl 5 (.pl)",
  rb: "Ruby 3 (.rb)",
  bin: "Static Linux binary (.bin)",
};

export const SCRIPT_NOTIFY_OPTIONS: readonly [ScriptNotifyMode, string][] = [
  ["none", "None"],
  ["syslog", "Syslog"],
  ["smtp", "SMTP mail relay"],
  ["both", "Both (syslog and SMTP)"],
] as const;

export const DEFAULT_RUN_AS_OPTIONS: readonly string[] = ["operator", "viewer", "replay_viewer"].map(
  (name) => `role:${name}`,
);

export const DEFAULT_SCHEDULE_ZONE = "Europe/Istanbul";

export const PS1_REFUSAL_REASON =
  "PowerShell (.ps1) cannot run yet: PowerShell 7 (pwsh) is not installed in the script execution image (no pinned offline package is available to the image build).";

export const BAT_CMD_REFUSAL_REASON =
  "Windows batch files (.bat / .cmd) cannot run on the Linux execution image; use a supported Linux interpreter (.sh, .bash, .py, .js, .mjs, .pl, .rb, .jar, or .bin).";

export interface ExtensionKindInference {
  readonly kind: ScriptKind | null;
  readonly isBatOrCmd: boolean;
  readonly isPs1: boolean;
  readonly unsupportedExtension: string | null;
}

export function inferScriptKindFromExtension(fileNameOrName: string): ExtensionKindInference {
  const trimmed = fileNameOrName.trim().replace(/\\/g, "/");
  const base = trimmed.includes("/") ? trimmed.slice(trimmed.lastIndexOf("/") + 1) : trimmed;
  const dot = base.lastIndexOf(".");
  if (dot <= 0 || dot === base.length - 1) {
    return { kind: null, isBatOrCmd: false, isPs1: false, unsupportedExtension: null };
  }
  const ext = base.slice(dot + 1).toLowerCase();
  if (ext === "bat" || ext === "cmd") {
    return { kind: null, isBatOrCmd: true, isPs1: false, unsupportedExtension: ext };
  }
  if (ext === "ps1") {
    return { kind: null, isBatOrCmd: false, isPs1: true, unsupportedExtension: "ps1" };
  }
  if ((SCRIPT_KINDS as readonly string[]).includes(ext)) {
    return { kind: ext as ScriptKind, isBatOrCmd: false, isPs1: false, unsupportedExtension: null };
  }
  return { kind: null, isBatOrCmd: false, isPs1: false, unsupportedExtension: ext };
}

export function stemFromFileName(fileName: string): string {
  const trimmed = fileName.trim().replace(/\\/g, "/");
  const base = trimmed.includes("/") ? trimmed.slice(trimmed.lastIndexOf("/") + 1) : trimmed;
  const dot = base.lastIndexOf(".");
  const raw = dot > 0 ? base.slice(0, dot) : base;
  return raw.replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
}

export function parseEgressInput(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of raw.split(/[\n,]+/)) {
    const trimmed = part.trim();
    if (trimmed && !seen.has(trimmed)) {
      seen.add(trimmed);
      out.push(trimmed);
    }
  }
  return out;
}

export function hasRunCapability(
  list: ScriptListResponse | null,
  detail?: ScriptSummary | null,
): boolean {
  const canRun =
    typeof detail?.can_run === "boolean"
      ? detail.can_run
      : list?.can_run === true;
  const runnerAvailable =
    typeof detail?.runner_available === "boolean"
      ? detail.runner_available
      : list?.runner_available === true;
  return canRun && runnerAvailable && list?.masked !== true;
}

export function resolveRunAsOptions(
  list: ScriptListResponse | null,
  detail?: ScriptSummary | null,
): readonly string[] {
  if (detail?.run_as_options && detail.run_as_options.length > 0) {
    return detail.run_as_options;
  }
  if (list?.run_as_options && list.run_as_options.length > 0) {
    return list.run_as_options;
  }
  return DEFAULT_RUN_AS_OPTIONS;
}

export function resolveScheduleZone(
  list: ScriptListResponse | null,
  detail?: ScriptSummary | null,
): string {
  return detail?.schedule_zone || list?.schedule_zone || DEFAULT_SCHEDULE_ZONE;
}

export function extractAliasWarnings(warnings: readonly string[] | undefined): string[] {
  if (!warnings || warnings.length === 0) return [];
  return warnings.filter((w) => /NEXUS_CRED_|same variable name|alias/i.test(w));
}

export function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("Could not read the selected file."));
    reader.onload = () => {
      const buffer = reader.result;
      if (!(buffer instanceof ArrayBuffer)) {
        reject(new Error("Could not read file bytes."));
        return;
      }
      const bytes = new Uint8Array(buffer);
      const chunkSize = 0x8000;
      const chunks: string[] = [];
      for (let i = 0; i < bytes.length; i += chunkSize) {
        chunks.push(String.fromCharCode(...bytes.subarray(i, i + chunkSize)));
      }
      resolve(btoa(chunks.join("")));
    };
    reader.readAsArrayBuffer(file);
  });
}

function describeApiError(err: unknown): string {
  const apiErr = err as ApiError | undefined;
  const body = apiErr?.body;
  if (body) {
    if (typeof body.message === "string" && body.message.trim()) return body.message;
    if (typeof body.reason === "string" && body.reason.trim()) return body.reason;
    if (typeof body.error === "string" && body.error.trim()) return body.error;
  }
  if (err instanceof Error && err.message) return err.message;
  return apiErr?.status ? `Request failed (status ${apiErr.status}).` : "Request failed.";
}

function notifyLabel(mode: string | null | undefined): string {
  const found = SCRIPT_NOTIFY_OPTIONS.find(([val]) => val === mode);
  return found ? found[1] : mode || "None";
}

function formatRunAs(runAs: string): string {
  const label = roleTokenLabel(runAs);
  const actor = actorLabel(runAs);
  if (runAs.startsWith("USR-")) return actor.label;
  return label !== runAs ? `${label} (${runAs})` : actor.label;
}

function formatDurationMs(durationMs: number | null | undefined): string {
  if (durationMs === null || durationMs === undefined) return "—";
  if (durationMs < 1000) return `${durationMs} ms`;
  return `${(durationMs / 1000).toFixed(1)} s`;
}

function runStatusPillProps(run: ScriptRunSummary): { readonly tone: Tone; readonly word: string } {
  const outcome = run.outcome?.toUpperCase() ?? null;
  const state = run.state.toUpperCase();
  if (outcome === "SUCCESS" || (state === "COMPLETED" && !outcome)) {
    return { tone: "ok", word: outcome ?? state };
  }
  if (
    outcome === "FAILURE" ||
    outcome === "TIMED_OUT" ||
    state === "FAILED" ||
    state === "REJECTED"
  ) {
    return { tone: "bad", word: outcome ?? state };
  }
  if (state === "OUTCOME_UNKNOWN" || state === "CANCELLED") {
    return { tone: "warn", word: outcome ? `${state} · ${outcome}` : state };
  }
  return { tone: "neutral", word: outcome ? `${state} · ${outcome}` : state };
}

function triggerBrowserTextDownload(content: string, fileName: string): void {
  if (typeof URL.createObjectURL !== "function") return;
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => {
    if (typeof URL.revokeObjectURL === "function") {
      URL.revokeObjectURL(url);
    }
  }, 1000);
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

function CredentialRefsPicker({
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

function KindRefusalAlert({ inference }: { readonly inference: ExtensionKindInference }) {
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

function CreateScriptDialog({
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
    setError(null);
    onClose();
  };

  const hasBody = sourceMode === "upload" ? Boolean(uploadedBase64) : codeBody.length > 0;
  const effectiveName = name.trim() || stemFromFileName(effectiveFileName);
  const parsedTimeout = Number(timeoutSeconds);
  const timeoutValid = Number.isInteger(parsedTimeout) && parsedTimeout >= 1 && parsedTimeout <= 3600;
  const canSubmit =
    !busy &&
    !extensionRefused &&
    resolvedKind !== null &&
    Boolean(effectiveName) &&
    hasBody &&
    timeoutValid &&
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
        run_as: effectiveRunAs,
      });
      // Immediately clear script body from browser memory after save (write-only contract).
      setCodeBody("");
      setUploadedBase64(null);
      setUploadedFileName("");
      setUploadedSize(null);
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
              helperText={`minute hour day-of-month month day-of-week in ${scheduleZone} (leave blank for unscheduled)`}
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

function ScriptRunsSection({
  script,
  canRun,
  runnerAvailable,
  canReadOutput,
  isMasked,
  externalRun,
}: {
  readonly script: ScriptSummary;
  readonly canRun: boolean;
  readonly runnerAvailable: boolean;
  readonly canReadOutput: boolean;
  readonly isMasked: boolean;
  readonly externalRun: ScriptRunSummary | null;
}) {
  const [runs, setRuns] = useState<readonly ScriptRunSummary[]>([]);
  const [runsLoading, setRunsLoading] = useState(true);
  const [runsError, setRunsError] = useState<string | null>(null);
  const [runBusy, setRunBusy] = useState(false);
  const [runActionNote, setRunActionNote] = useState<string | null>(null);
  const [runActionError, setRunActionError] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedRunDetail, setSelectedRunDetail] = useState<ScriptRunSummary | null>(null);
  const [runDetailLoading, setRunDetailLoading] = useState(false);
  const [runDetailError, setRunDetailError] = useState<string | null>(null);
  const [downloadBusy, setDownloadBusy] = useState(false);
  const [downloadNote, setDownloadNote] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const loadRuns = useCallback(async () => {
    setRunsLoading(true);
    setRunsError(null);
    try {
      const response = await listScriptRuns(script.script_id);
      setRuns(response.runs);
    } catch (err) {
      setRunsError(describeApiError(err));
    } finally {
      setRunsLoading(false);
    }
  }, [script.script_id]);

  useEffect(() => {
    setSelectedRunId(null);
    setSelectedRunDetail(null);
    setRunActionNote(null);
    setRunActionError(null);
    setDownloadNote(null);
    setDownloadError(null);
    void loadRuns();
  }, [loadRuns]);

  const inspectRun = useCallback(async (runId: string, fallback?: ScriptRunSummary) => {
    setSelectedRunId(runId);
    if (fallback) setSelectedRunDetail(fallback);
    setRunDetailLoading(true);
    setRunDetailError(null);
    setDownloadNote(null);
    setDownloadError(null);
    try {
      const detail = await getScriptRun(runId);
      setSelectedRunDetail(detail);
    } catch (err) {
      setRunDetailError(describeApiError(err));
    } finally {
      setRunDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    if (externalRun && externalRun.script_id === script.script_id) {
      setRunActionNote(`Submitted manual run ${externalRun.run_id} (v${externalRun.version}).`);
      void loadRuns();
      void inspectRun(externalRun.run_id, externalRun);
    }
  }, [externalRun, script.script_id, loadRuns, inspectRun]);

  const canRunNow = !isMasked && canRun && runnerAvailable;

  const handleRunNow = async () => {
    if (!canRunNow || runBusy) return;
    setRunBusy(true);
    setRunActionError(null);
    setRunActionNote(null);
    try {
      const submitted = await runScriptNow(script.script_id);
      setRunActionNote(`Submitted manual run ${submitted.run_id} (v${submitted.version}).`);
      await loadRuns();
      await inspectRun(submitted.run_id, submitted);
    } catch (err) {
      setRunActionError(describeApiError(err));
    } finally {
      setRunBusy(false);
    }
  };

  const handleDownloadOutput = async (run: ScriptRunSummary) => {
    if (!canReadOutput || !run.has_output || downloadBusy) return;
    setDownloadBusy(true);
    setDownloadError(null);
    setDownloadNote(null);
    try {
      const text = await downloadScriptRunOutput(run.run_id);
      const fileName = `script-run-${run.run_id}.log`;
      triggerBrowserTextDownload(text, fileName);
      setDownloadNote(`Downloaded ${fileName} (${formatBytes(new Blob([text]).size)}) — recorded in the server audit log.`);
    } catch (err) {
      setDownloadError(describeApiError(err));
    } finally {
      setDownloadBusy(false);
    }
  };

  return (
    <Box
      data-testid="script-run-history"
      sx={{
        p: 2,
        borderRadius: `${RADIUS.card}px`,
        bgcolor: m3.scLow,
        border: `1px solid ${m3.outlineVar}`,
      }}
    >
      <Stack spacing={1.75}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1.5}>
          <Box>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
                Run history and manual execution
              </Typography>
              <StatusPill
                tone={runnerAvailable ? "ok" : "neutral"}
                word={runnerAvailable ? "Runner online" : "Runner offline"}
                dense
              />
            </Stack>
            <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar, mt: 0.25 }}>
              {canRunNow
                ? `Submits an immediate manual script_run job for v${script.current_version} in an isolated execution pod.`
                : !runnerAvailable
                  ? "Run now is disabled because the script runner broker has no active heartbeat."
                  : "Run now requires Operator role or above (read-only execution status for this account)."}
            </Typography>
          </Box>
          <Stack direction="row" spacing={1} alignItems="center">
            <M3Button emphasis="outlined" disabled={runsLoading} onClick={() => void loadRuns()}>
              Refresh runs
            </M3Button>
            <M3Button
              emphasis="filled"
              disabled={!canRunNow || runBusy}
              onClick={() => void handleRunNow()}
            >
              {runBusy ? "Starting…" : "Run now"}
            </M3Button>
          </Stack>
        </Stack>

        {runActionError ? <Alert severity="error">{runActionError}</Alert> : null}
        {runActionNote ? <Alert severity="success">{runActionNote}</Alert> : null}

        {runsError ? (
          <Alert
            severity="error"
            action={
              <M3Button emphasis="text" onClick={() => void loadRuns()}>
                Retry
              </M3Button>
            }
          >
            Could not load run history: {runsError}
          </Alert>
        ) : runsLoading && runs.length === 0 ? (
          <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>Loading run history…</Typography>
        ) : runs.length === 0 ? (
          <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }} data-testid="script-runs-empty">
            No runs recorded for this script yet.
          </Typography>
        ) : (
          <Box sx={{ overflowX: "auto", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px` }}>
            <Table size="small" aria-label="Script runs table">
              <TableHead>
                <TableRow>
                  <TableCell>Run</TableCell>
                  <TableCell>Version</TableCell>
                  <TableCell>Trigger</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Exit</TableCell>
                  <TableCell>Duration</TableCell>
                  <TableCell>Requested by</TableCell>
                  <TableCell>Requested at</TableCell>
                  <TableCell align="right">Details</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {runs.map((run) => {
                  const pill = runStatusPillProps(run);
                  const isSelected = selectedRunId === run.run_id;
                  return (
                    <TableRow key={run.run_id} hover selected={isSelected} data-run-id={run.run_id}>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        <Box
                          component="button"
                          type="button"
                          onClick={() => void inspectRun(run.run_id, run)}
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
                          {run.run_id}
                        </Box>
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>v{run.version}</TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>{run.trigger_kind}</TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        <StatusPill tone={pill.tone} word={pill.word} dense />
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>
                        {run.exit_code !== null && run.exit_code !== undefined ? run.exit_code : "—"}
                      </TableCell>
                      <TableCell sx={{ ...TYPE.mono, whiteSpace: "nowrap" }}>
                        {formatDurationMs(run.duration_ms)}
                      </TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>{actorLabel(run.requested_by).label}</TableCell>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        <Timestamp at={run.requested_at} />
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        <M3Button emphasis="text" onClick={() => void inspectRun(run.run_id, run)}>
                          Inspect run
                        </M3Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </Box>
        )}

        {selectedRunId ? (
          <Card
            data-testid="script-run-detail"
            sx={{
              p: 2,
              bgcolor: m3.scLowest,
              boxShadow: "none",
              border: `1px solid ${m3.outlineVar}`,
              borderRadius: `${RADIUS.card}px`,
            }}
          >
            {runDetailError ? (
              <Alert severity="error">Could not load run {selectedRunId}: {runDetailError}</Alert>
            ) : !selectedRunDetail ? (
              <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>Loading run details…</Typography>
            ) : (
              <Stack spacing={1.5}>
                <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1}>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography sx={{ ...TYPE.section, fontFamily: MONO, color: m3.onSurface }}>
                      Run {selectedRunDetail.run_id}
                    </Typography>
                    <StatusPill
                      tone={runStatusPillProps(selectedRunDetail).tone}
                      word={runStatusPillProps(selectedRunDetail).word}
                      dense
                    />
                    <Typography sx={{ ...TYPE.mono, color: m3.onSurfaceVar }}>
                      v{selectedRunDetail.version} · {selectedRunDetail.trigger_kind}
                    </Typography>
                  </Stack>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <M3Button
                      emphasis="text"
                      disabled={runDetailLoading}
                      onClick={() => void inspectRun(selectedRunDetail.run_id, selectedRunDetail)}
                    >
                      Refresh run
                    </M3Button>
                    <M3Button
                      emphasis="text"
                      onClick={() => {
                        setSelectedRunId(null);
                        setSelectedRunDetail(null);
                      }}
                    >
                      Close run
                    </M3Button>
                  </Stack>
                </Stack>

                <Box
                  sx={{
                    display: "grid",
                    gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0, 1fr))" },
                    gap: 1.25,
                  }}
                >
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Run as</Typography>
                    <Typography sx={{ ...TYPE.mono }}>{formatRunAs(selectedRunDetail.run_as)}</Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Requested by</Typography>
                    <Typography sx={{ ...TYPE.body }}>{actorLabel(selectedRunDetail.requested_by).label}</Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Exit / Duration</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      Exit {selectedRunDetail.exit_code !== null ? selectedRunDetail.exit_code : "—"} ·{" "}
                      {formatDurationMs(selectedRunDetail.duration_ms)}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Captured streams</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      stdout {formatBytes(selectedRunDetail.stdout_bytes)}
                      {selectedRunDetail.stdout_truncated ? " (truncated)" : ""} · stderr{" "}
                      {formatBytes(selectedRunDetail.stderr_bytes)}
                      {selectedRunDetail.stderr_truncated ? " (truncated)" : ""}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Declared egress</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      {selectedRunDetail.egress.length > 0 ? selectedRunDetail.egress.join(", ") : "None"}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar }}>Credential refs injected</Typography>
                    <Typography sx={{ ...TYPE.mono }}>
                      {selectedRunDetail.credential_refs_used.length > 0
                        ? selectedRunDetail.credential_refs_used.join(", ")
                        : "None"}
                    </Typography>
                  </Box>
                </Box>

                {selectedRunDetail.failure_reason ? (
                  <Alert severity="warning" data-testid="script-run-failure-reason">
                    Failure reason: {selectedRunDetail.failure_reason}
                  </Alert>
                ) : null}

                {canReadOutput && !isMasked && selectedRunDetail.output_tail ? (
                  <Box>
                    <Typography sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar, mb: 0.5 }}>
                      Captured output tail (masked, up to 4 KiB)
                    </Typography>
                    <Box
                      component="pre"
                      data-testid="script-run-output-tail"
                      sx={{
                        m: 0,
                        p: 1.5,
                        maxHeight: 220,
                        overflow: "auto",
                        borderRadius: `${RADIUS.card}px`,
                        bgcolor: m3.scLow,
                        border: `1px solid ${m3.outlineVar}`,
                        fontFamily: MONO,
                        fontSize: TYPE.mono.fontSize,
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                      }}
                    >
                      {selectedRunDetail.output_tail}
                    </Box>
                  </Box>
                ) : null}

                <Box
                  data-testid="script-run-output-download-section"
                  sx={{
                    p: 1.5,
                    borderRadius: `${RADIUS.pill}px`,
                    bgcolor: m3.scLow,
                    border: `1px solid ${m3.outlineVar}`,
                  }}
                >
                  {canReadOutput ? (
                    <Stack spacing={1}>
                      <Stack
                        direction="row"
                        justifyContent="space-between"
                        alignItems="center"
                        flexWrap="wrap"
                        gap={1}
                      >
                        <Typography sx={{ ...TYPE.body, color: m3.onSurface }} data-testid="script-output-audit-notice">
                          <strong>Audited output download:</strong> Full run output downloads require Operator or above and are audited server-side (one audit entry recorded per download before the first byte).
                        </Typography>
                        {selectedRunDetail.has_output ? (
                          <M3Button
                            emphasis="outlined"
                            disabled={downloadBusy}
                            onClick={() => void handleDownloadOutput(selectedRunDetail)}
                          >
                            {downloadBusy ? "Downloading…" : "Download run output"}
                          </M3Button>
                        ) : (
                          <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>
                            No stored output artefact for this run.
                          </Typography>
                        )}
                      </Stack>
                      {downloadError ? <Alert severity="error">{downloadError}</Alert> : null}
                      {downloadNote ? <Alert severity="success">{downloadNote}</Alert> : null}
                    </Stack>
                  ) : (
                    <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }} data-testid="script-output-gated-note">
                      Full output download requires Operator role or above (<code>can_read_output</code>; downloads are audited server-side).
                      {isMasked ? " Output download is never available in masked inspection mode." : ""}
                    </Typography>
                  )}
                </Box>
              </Stack>
            )}
          </Card>
        ) : null}
      </Stack>
    </Box>
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
    setRunAs(runAsOptions.includes(script.run_as) ? script.run_as : defaultRunAs);
    setCredentialRefs([...script.credential_refs]);
    setError(null);
  }, [script, runAsOptions, defaultRunAs]);

  const effectiveRunAs = runAsOptions.includes(runAs) ? runAs : defaultRunAs;
  const parsedTimeout = Number(timeoutSeconds);
  const timeoutValid = Number.isInteger(parsedTimeout) && parsedTimeout >= 1 && parsedTimeout <= 3600;
  const scheduleChanged = (schedule.trim() || null) !== (script.schedule ?? null);
  const credentialRefsChanged =
    credentialRefs.length !== script.credential_refs.length ||
    credentialRefs.some((refId, idx) => refId !== script.credential_refs[idx]);

  const handleSaveSettings = async () => {
    if (!canEdit || busy || !timeoutValid) return;
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
                InputProps={{ sx: { fontFamily: MONO } }}
                helperText={`minute hour day-of-month month day-of-week in ${scheduleZone} (leave blank to clear)`}
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
                disabled={busy || !timeoutValid || !effectiveRunAs}
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
                <Typography sx={{ ...TYPE.body }}>{notifyLabel(script.notify)}</Typography>
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
