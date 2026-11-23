import type {
  ApiError,
  ScriptKind,
  ScriptListResponse,
  ScriptNotifyMode,
  ScriptRunSummary,
  ScriptSummary,
} from "../auth/adminApi";
import { actorLabel } from "../shell/presenters";
import { roleTokenLabel } from "../shell/roleLabel";

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

/** True when the notify choice sends the run-end mail, so "mail on failure only" applies (V264). */
export function notifySendsMail(mode: string | null | undefined): boolean {
  return mode === "smtp" || mode === "both";
}

const NOTIFICATION_CODE_LABELS: Readonly<Record<string, string>> = {
  syslog_not_configured: "syslog is not configured in Settings > Notifications",
  smtp_not_configured: "the SMTP relay is not configured in Settings > Notifications",
  no_recipients: "no mail recipients are configured in Settings > Notifications",
  failure_only: "mail on failure only, and the run succeeded",
  send_failed: "the send failed",
};

export interface RunNotificationView {
  readonly tone: "bad" | "warn" | "ok" | "neutral" | "unknown";
  readonly word: string;
  readonly title: string;
}

function channelPart(channel: string, state: string, code: string | null): string | null {
  if (state === "NOT_REQUESTED") return null;
  const reason = code ? `: ${NOTIFICATION_CODE_LABELS[code] ?? code}` : "";
  if (state === "SENT") return `${channel} sent`;
  if (state === "FAILED") return `${channel} failed${reason}`;
  if (state === "SKIPPED") return `${channel} skipped${reason}`;
  return `${channel} ${state || "UNKNOWN"}`;
}

/**
 * The run-end notification outcome of one run (V264), for the run history row. No record is never shown as sent: a
 * finished run without one says so ("Not recorded"); an unknown state is UNKNOWN.
 */
export function describeRunNotification(run: ScriptRunSummary): RunNotificationView {
  const n = run.notification;
  if (!n) {
    if (!run.finished_at) {
      return { tone: "neutral", word: "—", title: "The run has not finished; notifications are sent at run end." };
    }
    return {
      tone: "unknown",
      word: "Not recorded",
      title: "No notification outcome is recorded for this run (not processed yet, or the run finished before run notifications existed).",
    };
  }
  const parts = [channelPart("Syslog", n.syslog, n.syslog_code), channelPart("Mail", n.smtp, n.smtp_code)].filter(
    (p): p is string => p !== null,
  );
  const states = [n.syslog, n.smtp];
  const known = states.every((st) => ["SENT", "FAILED", "SKIPPED", "NOT_REQUESTED"].includes(st));
  const recordedAt = `Recorded ${n.notified_at}.`;
  if (!known) {
    return { tone: "unknown", word: "UNKNOWN", title: `${parts.join(" · ")}. ${recordedAt}` };
  }
  if (parts.length === 0) {
    return { tone: "neutral", word: "Not requested", title: `The script asks for no notification. ${recordedAt}` };
  }
  const failed = states.includes("FAILED");
  const skippedForAProblem =
    (n.syslog === "SKIPPED") || (n.smtp === "SKIPPED" && n.smtp_code !== "failure_only");
  const tone = failed ? "bad" : skippedForAProblem ? "warn" : "ok";
  const word = parts.map((p) => p.split(":")[0]).join(" · ");
  return { tone, word, title: `${parts.join(" · ")}. ${recordedAt}` };
}

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

export function describeApiError(err: unknown): string {
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

export function formatRunAs(runAs: string): string {
  const label = roleTokenLabel(runAs);
  const actor = actorLabel(runAs);
  if (runAs.startsWith("USR-")) return actor.label;
  return label !== runAs ? `${label} (${runAs})` : actor.label;
}
