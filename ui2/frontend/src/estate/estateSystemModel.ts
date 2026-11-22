/**
 * Summary model for the Estate "System" screen (alternative Estate view, design/opus-bold CONCEPT.md: System = enrolment,
 * access, what runs, notifications, platform, audit, delivery). The screen shows four compact sections, each a read of a
 * source the standard screens already call. This file is pure: it turns the reads into lines, counts and a status word.
 *
 * Rules: a failed or unreadable source is UNKNOWN (never OK); a restricted source says so; every count names what it
 * counts; capped lists print "+N more"; every section carries the age of the evidence it was built from.
 */
import type {
  AuditEventView,
  DeviceSummary,
  LocalIdentityView,
  ScheduledJobsView,
  ScriptRunSummary,
  ScriptSummary,
  SessionView,
  SystemHealth,
} from "../auth/adminApi";
import type { SourceRead } from "../screens/overview/attentionRules";
import { enrollmentStateLabel } from "../shell/deviceCopy";
import { formatUtc } from "../shell/time";
import type { BadgeState } from "./estateModel";
import { BADGE_WORD_DEFAULT } from "./estateModel";

export type SystemSectionId = "runs" | "enrolment" | "access" | "platform" | "audit";

/** One source read plus the instant the console read it (the age of the evidence it carries); null when never read. */
export interface TimedRead<T> {
  readonly read: SourceRead<T>;
  readonly readAt: string | null;
}

export interface SystemSummaryInputs {
  readonly jobs: TimedRead<ScheduledJobsView>;
  readonly devices: TimedRead<readonly DeviceSummary[]>;
  readonly health: TimedRead<SystemHealth>;
  readonly audit: TimedRead<readonly AuditEventView[]>;
  readonly identities: TimedRead<readonly LocalIdentityView[]>;
  readonly sessions: TimedRead<readonly SessionView[]>;
  /** Script Execution: the script list and one runs read per script (first SCRIPT_RUNS_READ_CAP scripts). */
  readonly scripts?: ScriptRunsInput;
}

export interface SystemLine {
  readonly label: string;
  readonly value: string;
}

export interface SystemSection {
  readonly id: SystemSectionId;
  readonly title: string;
  readonly badge: BadgeState;
  readonly word: string;
  readonly lines: readonly SystemLine[];
  /** "+N more" when a list was capped; null when everything is shown. */
  readonly more: string | null;
  /** The read instant of the evidence; the screen draws the age from it. */
  readonly evidenceAt: string | null;
  readonly source: string;
  /** The standard screen the section links to (always with `standard=1`). */
  readonly standardHref: string;
  readonly standardLabel: string;
  /** Further standard-screen links the section offers (each with `standard=1`); read-only sections use none. */
  readonly extraLinks?: readonly { readonly href: string; readonly label: string }[];
}

/** One runs read for one script; `read` is the source read of that script's run list. */
export interface ScriptRunRead extends TimedRead<readonly ScriptRunSummary[]> {
  readonly script: ScriptSummary;
}

export interface ScriptRunsInput {
  /** The script list read; its total is what "+N more scripts not read" is counted from. */
  readonly scripts: TimedRead<readonly ScriptSummary[]>;
  /** One entry per script whose runs were read (at most SCRIPT_RUNS_READ_CAP, in list order). */
  readonly runs: readonly ScriptRunRead[];
}

export const SYSTEM_LIST_CAP = 4;
/** How many scripts the System screen reads runs for; the script list response carries no last run. */
export const SCRIPT_RUNS_READ_CAP = 20;
export const AUDIT_PREVIEW_CAP = 5;
/** Device latest-job states that mean a job is in flight now. */
export const IN_FLIGHT_JOB_STATES: readonly string[] = ["EXECUTING", "CLAIMED", "REQUESTED"];

function unreadable(read: SourceRead<unknown>): "restricted" | "unknown" | null {
  if (read.state === "ok") return null;
  return read.state === "restricted" ? "restricted" : "unknown";
}

function unreadableSection(
  id: SystemSectionId,
  title: string,
  kind: "restricted" | "unknown",
  source: string,
  readAt: string | null,
  standardHref: string,
  standardLabel: string,
): SystemSection {
  const line = kind === "restricted"
    ? { label: "Access", value: "Restricted for this account" }
    : { label: "Read", value: "UNKNOWN: the source could not be read" };
  return {
    id, title, badge: kind === "restricted" ? "na" : "unk",
    word: kind === "restricted" ? "Restricted" : BADGE_WORD_DEFAULT.unk,
    lines: [line], more: null, evidenceAt: readAt, source, standardHref, standardLabel,
  };
}

/** Counts per label, largest first, capped; the rest is named in `more` with its own count. */
function capCounts(counts: ReadonlyMap<string, number>, cap: number): { lines: SystemLine[]; more: string | null } {
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  const shown = sorted.slice(0, cap).map(([label, n]) => ({ label, value: countOf(n, "device", "devices") }));
  const rest = sorted.slice(cap);
  const restTotal = rest.reduce((sum, [, n]) => sum + n, 0);
  return { lines: shown, more: rest.length > 0 ? `+${rest.length} more kinds (${restTotal} devices)` : null };
}

/** The standard Script Execution tab: run-now, output and script edit stay there; this section never offers them. */
const SCRIPT_EXECUTION_LINKS: readonly { readonly href: string; readonly label: string }[] = [
  { href: "?screen=operations&tab=script-execution&standard=1", label: "Open script execution (standard)" },
];

/** What runs: which scheduled job families run, and how many devices have a job in flight right now. */
export function summariseRuns(
  jobs: TimedRead<ScheduledJobsView>,
  devices: TimedRead<readonly DeviceSummary[]>,
  scripts?: ScriptRunsInput,
): SystemSection {
  const standardHref = "?screen=administration&tab=scheduled-jobs&standard=1";
  const standardLabel = "Open scheduled jobs (standard)";
  const source = "scheduled jobs list; device latest job state";
  const scriptPart = scriptRunLines(scripts);
  const jobsKind = unreadable(jobs.read);
  if (jobsKind) {
    const base = unreadableSection("runs", "What runs", jobsKind, source, jobs.readAt, standardHref, standardLabel);
    return { ...base, lines: [...base.lines, ...scriptPart.lines], more: scriptPart.more, extraLinks: SCRIPT_EXECUTION_LINKS };
  }
  const view = (jobs.read as { state: "ok"; value: ScheduledJobsView }).value;
  const families = view.families;
  const willRun = families.filter((f) => f.will_run).length;
  const idle = families.filter((f) => f.state === "ON" && !f.will_run).length;
  const off = families.filter((f) => f.state === "OFF").length;
  const locked = families.filter((f) => f.state === "LOCKED").length;
  const nextWindows = families
    .filter((f) => f.state === "ON" && f.next_window_at)
    .map((f) => f.next_window_at as string)
    .sort((a, b) => Date.parse(a) - Date.parse(b));

  const lines: SystemLine[] = [
    { label: "Job families that will run", value: `${willRun} of ${families.length} job families` },
    { label: "Switched on but idle", value: countOf(idle, "job family", "job families") },
    { label: "Switched off", value: countOf(off, "job family", "job families") },
    { label: "Locked by a deployment switch", value: countOf(locked, "job family", "job families") },
    { label: "Next window opens (earliest ON family)", value: nextWindows.length > 0 ? formatUtc(nextWindows[0], false) : "none ON" },
  ];

  const devKind = unreadable(devices.read);
  if (devKind) {
    lines.push({ label: "Devices with a job in flight", value: "UNKNOWN: device list not read" });
  } else {
    const list = (devices.read as { state: "ok"; value: readonly DeviceSummary[] }).value;
    const inFlight = list.filter((d) => IN_FLIGHT_JOB_STATES.includes(d.latest_job_state?.toUpperCase() ?? "")).length;
    lines.push({ label: "Devices with a job in flight (latest job EXECUTING, CLAIMED or REQUESTED)", value: countOf(inFlight, "device", "devices") });
  }

  const deploymentStop = !view.master_enabled;
  return {
    id: "runs", title: "What runs",
    badge: deploymentStop ? "watch" : "na",
    word: deploymentStop ? BADGE_WORD_DEFAULT.watch : "Counted",
    lines: [...lines, ...scriptPart.lines],
    more: scriptPart.more,
    extraLinks: SCRIPT_EXECUTION_LINKS,
    evidenceAt: jobs.readAt,
    source,
    standardHref,
    standardLabel,
  };
}

function countOf(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

function scheduleStateOf(s: ScriptSummary): string {
  if (!s.enabled) return "Off";
  return s.schedule ? "On schedule" : "On, no schedule";
}

/** The newest run by request time; an unparseable time sorts last. */
function latestRunOf(runs: readonly ScriptRunSummary[]): ScriptRunSummary | null {
  let best: ScriptRunSummary | null = null;
  let bestTime = Number.NEGATIVE_INFINITY;
  for (const r of runs) {
    const t = Date.parse(r.requested_at);
    const time = Number.isNaN(t) ? Number.NEGATIVE_INFINITY : t;
    if (best === null || time > bestTime) {
      best = r;
      bestTime = time;
    }
  }
  return best;
}

/**
 * One script row: the script name as the label; the value carries the schedule state, the last run (outcome or
 * state, and its request time) and the run count over the listed runs. A failed read is UNKNOWN, a refusal is
 * Restricted; a script with no listed run says "No runs", never zero-run OK.
 */
export function summariseScriptRunRow(row: ScriptRunRead): SystemLine {
  const label = row.script.name;
  const schedule = scheduleStateOf(row.script);
  const kind = unreadable(row.read);
  if (row.read.state === "skipped") return { label, value: `${schedule} · runs being read` };
  if (kind === "restricted") return { label, value: `${schedule} · Restricted for this account` };
  if (kind === "unknown") return { label, value: `${schedule} · UNKNOWN: runs not read` };
  const runs = (row.read as { state: "ok"; value: readonly ScriptRunSummary[] }).value;
  const last = latestRunOf(runs);
  if (last === null) return { label, value: `${schedule} · No runs (0 in the list as read)` };
  const lastState = last.outcome ?? last.state;
  return {
    label,
    value: `${schedule} · last ${lastState} ${formatUtc(last.requested_at, false)} · ${countOf(runs.length, "run", "runs")} in the list as read`,
  };
}

/**
 * Script runs lines for the What runs card: the script count and the read instant of the script list, one row per
 * script (capped at SYSTEM_LIST_CAP), and "+N more" for capped rows and "+N more scripts not read" for scripts past
 * the runs read cap. An unreadable script list is UNKNOWN (or Restricted), never zero scripts.
 */
export function scriptRunLines(input: ScriptRunsInput | undefined): { lines: SystemLine[]; more: string | null } {
  if (!input) return { lines: [], more: null };
  const scripts = input.scripts;
  const kind = unreadable(scripts.read);
  if (kind) {
    const value = kind === "restricted" ? "Restricted for this account" : "UNKNOWN: script list not read";
    return { lines: [{ label: "Script runs", value }], more: null };
  }
  const total = (scripts.read as { state: "ok"; value: readonly ScriptSummary[] }).value.length;
  const rows = input.runs.slice(0, SYSTEM_LIST_CAP).map(summariseScriptRunRow);
  const cappedRows = input.runs.length - rows.length;
  const notRead = Math.max(0, total - input.runs.length);
  const moreParts = [
    cappedRows > 0 ? `+${cappedRows} more scripts` : null,
    notRead > 0 ? `+${notRead} more scripts not read (runs are read for the first ${SCRIPT_RUNS_READ_CAP} scripts only)` : null,
  ].filter((p): p is string => p !== null);
  return {
    lines: [
      { label: "Scripts in the list (as read)", value: countOf(total, "script", "scripts") },
      { label: "Script list read at", value: scripts.readAt ? formatUtc(scripts.readAt, false) : "not read" },
      ...rows,
    ],
    more: moreParts.length > 0 ? moreParts.join("; ") : null,
  };
}

/**
 * Access: counts of local identities (total, enabled, disabled, must change password) and of sessions as read.
 * Counts only: no identity name, username or session id is read into the section. The list responses carry no
 * observation time, so the stamp is the read's own time (the older of the two reads that were made), never a fake age.
 */
export function summariseAccess(
  identities: TimedRead<readonly LocalIdentityView[]>,
  sessions: TimedRead<readonly SessionView[]>,
): SystemSection {
  const standardHref = "?screen=administration&tab=access&standard=1";
  const standardLabel = "Open access and identity (standard)";
  const source = "local identities list read time; sessions list read time";
  const readTimes = [identities.readAt, sessions.readAt].filter((t): t is string => t !== null && !Number.isNaN(Date.parse(t)));
  const evidenceAt = readTimes.length > 0 ? readTimes.reduce((a, b) => (Date.parse(a) <= Date.parse(b) ? a : b)) : null;
  const idKind = unreadable(identities.read);
  const sessKind = unreadable(sessions.read);
  const refusal = (what: string) => `Your role cannot read ${what}.`;
  const lines: SystemLine[] = [];

  if (idKind) {
    lines.push({
      label: "Local identities",
      value: idKind === "restricted" ? refusal("local identities") : "UNKNOWN: could not be read.",
    });
  } else {
    const list = (identities.read as { state: "ok"; value: readonly LocalIdentityView[] }).value;
    const enabled = list.filter((i) => i.enabled === true).length;
    const mustChange = list.filter((i) => i.must_change_password === true).length;
    lines.push(
      { label: "Local identities in total", value: countOf(list.length, "local identity", "local identities") },
      { label: "Local identities enabled", value: countOf(enabled, "local identity", "local identities") },
      { label: "Local identities disabled", value: countOf(list.length - enabled, "local identity", "local identities") },
      { label: "Local identities that must change their password", value: countOf(mustChange, "local identity", "local identities") },
    );
  }

  if (sessKind) {
    lines.push({
      label: "Sessions",
      value: sessKind === "restricted" ? refusal("sessions") : "UNKNOWN: could not be read.",
    });
  } else {
    const list = (sessions.read as { state: "ok"; value: readonly SessionView[] }).value;
    lines.push({ label: "Sessions (total as read)", value: countOf(list.length, "session", "sessions") });
  }

  return {
    id: "access", title: "Access",
    badge: idKind || sessKind ? "unk" : "na",
    word: idKind || sessKind ? BADGE_WORD_DEFAULT.unk : "Counted",
    lines, more: null, evidenceAt, source, standardHref, standardLabel,
  };
}

/** Enrolment: how many devices the registry holds, counted by enrolment state. Counted, not judged. */
export function summariseEnrolment(devices: TimedRead<readonly DeviceSummary[]>): SystemSection {
  const standardHref = "?screen=administration&tab=registry&standard=1";
  const standardLabel = "Open device registry (standard)";
  const source = "device registry list";
  const kind = unreadable(devices.read);
  if (kind) {
    return unreadableSection("enrolment", "Enrolment", kind, source, devices.readAt, standardHref, standardLabel);
  }
  const list = (devices.read as { state: "ok"; value: readonly DeviceSummary[] }).value;
  const counts = new Map<string, number>();
  for (const d of list) {
    const label = enrollmentStateLabel(d.enrollment_state);
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  const capped = capCounts(counts, SYSTEM_LIST_CAP);
  return {
    id: "enrolment", title: "Enrolment",
    badge: "na", word: "Counted",
    lines: [{ label: "Devices in the registry", value: countOf(list.length, "device", "devices") }, ...capped.lines],
    more: capped.more,
    evidenceAt: devices.readAt,
    source,
    standardHref,
    standardLabel,
  };
}

/** Platform: the stored device-health snapshot. Only a READY snapshot is evaluated; anything else is not OK. */
export function summarisePlatform(health: TimedRead<SystemHealth>): SystemSection {
  const standardHref = "?screen=operations&tab=health&standard=1";
  const standardLabel = "Open device health (standard)";
  const source = "device health snapshot";
  const kind = unreadable(health.read);
  if (kind) {
    return unreadableSection("platform", "Platform", kind, source, health.readAt, standardHref, standardLabel);
  }
  const snap = (health.read as { state: "ok"; value: SystemHealth }).value;
  const computation = snap.computation;
  const evidenceAt = computation.computed_at ?? health.readAt;
  if (computation.state === "FAILED") {
    return {
      id: "platform", title: "Platform", badge: "act", word: BADGE_WORD_DEFAULT.act,
      lines: [{ label: "Snapshot", value: "Computation failed; no figures are evaluated" }],
      more: null, evidenceAt, source, standardHref, standardLabel,
    };
  }
  if (computation.state !== "READY") {
    return {
      id: "platform", title: "Platform", badge: "unk", word: BADGE_WORD_DEFAULT.unk,
      lines: [{ label: "Snapshot", value: `UNKNOWN: ${computation.state} (not evaluated yet)` }],
      more: null, evidenceAt, source, standardHref, standardLabel,
    };
  }
  const k = snap.kpis;
  const figure = (n: number | null | undefined) => (typeof n === "number" ? countOf(n, "device", "devices") : "UNKNOWN");
  const lines: SystemLine[] = [
    { label: "Monitored devices", value: figure(k.monitored?.devices) },
    { label: "Devices with a crash or dump alert", value: figure(k.crash_alerts?.devices) },
    { label: "Devices with CPU over the threshold", value: figure(k.cpu_over_threshold?.devices) },
    { label: "Devices whose collection is not OK", value: figure(k.collection_not_ok?.devices) },
  ];
  const counts = [k.monitored?.devices, k.crash_alerts?.devices, k.cpu_over_threshold?.devices, k.collection_not_ok?.devices];
  const anyUnknown = counts.some((n) => typeof n !== "number");
  const anyFlag = [k.crash_alerts?.devices, k.cpu_over_threshold?.devices, k.collection_not_ok?.devices].some((n) => typeof n === "number" && n > 0);
  const badge: BadgeState = anyUnknown ? "unk" : anyFlag ? "watch" : "ok";
  return {
    id: "platform", title: "Platform", badge, word: BADGE_WORD_DEFAULT[badge],
    lines, more: null, evidenceAt, source, standardHref, standardLabel,
  };
}

function eventTime(e: AuditEventView): number {
  const t = Date.parse(e.occurred_at);
  return Number.isNaN(t) ? Number.NEGATIVE_INFINITY : t;
}

/** Audit: the newest entries first, capped; the total says what the count covers. Counted, not judged. */
export function summariseAudit(audit: TimedRead<readonly AuditEventView[]>): SystemSection {
  const standardHref = "?screen=administration&tab=audit&standard=1";
  const standardLabel = "Open audit log (standard)";
  const source = "audit trail";
  const kind = unreadable(audit.read);
  if (kind) {
    return unreadableSection("audit", "Audit", kind, source, audit.readAt, standardHref, standardLabel);
  }
  const events = (audit.read as { state: "ok"; value: readonly AuditEventView[] }).value;
  const newest = [...events].sort((a, b) => eventTime(b) - eventTime(a));
  const shown = newest.slice(0, AUDIT_PREVIEW_CAP);
  const lines: SystemLine[] = [
    { label: "Events in the trail", value: countOf(events.length, "event", "events") },
    ...shown.map((e) => ({
      label: formatUtc(e.occurred_at, false),
      value: `${e.actor} · ${e.action} · ${e.outcome}`,
    })),
  ];
  const rest = events.length - shown.length;
  return {
    id: "audit", title: "Audit",
    badge: "na", word: "Counted",
    lines,
    more: rest > 0 ? `+${rest} more` : null,
    evidenceAt: audit.readAt,
    source,
    standardHref,
    standardLabel,
  };
}

/** The five System sections in screen order. */
export function buildSystemSummary(inputs: SystemSummaryInputs): readonly SystemSection[] {
  return [
    summariseRuns(inputs.jobs, inputs.devices, inputs.scripts),
    summariseEnrolment(inputs.devices),
    summariseAccess(inputs.identities, inputs.sessions),
    summarisePlatform(inputs.health),
    summariseAudit(inputs.audit),
  ];
}
