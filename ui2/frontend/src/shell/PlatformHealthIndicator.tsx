import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";
import { m3 } from "../theme/m3Theme";
import { getSystemPods, type PodStatusView, type PodsView } from "../auth/adminApi";
import { NAV_LEAVES, leafLocked, leafShown, type NavSessionGrants } from "./navModel";

/**
 * neXus' own health in the top bar (PO 2026-11-13 amendment of navigation decisions F1/F2). Platform status became an
 * Administration page; it had sat in Operations only so that every signed-in user could see whether neXus itself is
 * well. This indicator keeps that promise on every screen in one word: OK, degraded or down, from the same read the
 * Platform status page makes (`GET /api/v2/system/pods`, open to every signed-in role). No new backend data.
 *
 * It is quiet when OK (a small dot and the product name in the muted text colour) and only takes colour and a word
 * when something is wrong. The tooltip gives one line of reason. For a session that may open the Platform status page
 * the indicator is a link to it; otherwise it is a plain status.
 */

export type PlatformHealthState = "ok" | "degraded" | "down";

export interface PlatformHealth {
  readonly state: PlatformHealthState;
  /** One line, for the tooltip. */
  readonly reason: string;
}

/** What the status read returned: the pod view, or the error it threw (an `ApiError` carries `status`). */
export type PlatformRead = { readonly pods: PodsView } | { readonly error: unknown };

const statusOf = (error: unknown): number | null => {
  const status = (error as { status?: unknown } | null)?.status;
  return typeof status === "number" ? status : null;
};

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** A Job the CronJob controller started on its schedule. */
export interface CronRun { readonly cronJob: string; readonly job: string; readonly scheduledAt: number }

/**
 * Whether a Job is a scheduled run of a CronJob. The pod view does not carry the Job's own owner (that needs a Job read
 * the service account is not granted), so the Kubernetes CronJob controller's naming is used: it names each Job it
 * starts `<cronjob>-<scheduled time in minutes since the epoch>`. The name alone is not trusted: the pod must have been
 * created no earlier than a minute before that scheduled time and within a day after it. A Job started by hand, even
 * one created from a CronJob (`kubectl create job --from=cronjob/...`), carries a chosen name and is a one-off.
 */
export function cronRunOf(job: string | null | undefined, createdAt: string | null | undefined): CronRun | null {
  const match = job ? /^(.+)-(\d{8,10})$/.exec(job) : null;
  const created = createdAt ? Date.parse(createdAt) : NaN;
  if (!match || !Number.isFinite(created)) return null;
  const scheduledAt = Number(match[2]) * 60_000;
  if (created < scheduledAt - 60_000 || created > scheduledAt + 86_400_000) return null;
  return { cronJob: match[1], job: job!, scheduledAt };
}

/** What a pod is, for the health rule and the Platform status page. */
export type PodRole =
  | { readonly kind: "service" }
  | { readonly kind: "cron-run"; readonly run: CronRun; readonly latest: boolean }
  | { readonly kind: "one-off" };

/**
 * Each pod's role, keyed by pod name. A pod whose owner the server did not report (an older server) is a service pod
 * unless it has finished (phase Succeeded or Failed), which is what a service pod never does.
 */
export function podRoles(pods: readonly PodStatusView[]): Map<string, PodRole> {
  const roles = new Map<string, PodRole>();
  const latest = new Map<string, number>();
  const runs = new Map<string, CronRun>();
  for (const p of pods) {
    if (p.owner_kind !== "Job") continue;
    const run = cronRunOf(p.owner_name, p.created_at);
    if (!run) continue;
    runs.set(p.name, run);
    latest.set(run.cronJob, Math.max(latest.get(run.cronJob) ?? -Infinity, run.scheduledAt));
  }
  for (const p of pods) {
    const run = runs.get(p.name);
    if (run) roles.set(p.name, { kind: "cron-run", run, latest: latest.get(run.cronJob) === run.scheduledAt });
    else if (p.owner_kind === undefined) roles.set(p.name, { kind: p.phase === "Succeeded" || p.phase === "Failed" ? "one-off" : "service" });
    else if (p.owner_kind === null || p.owner_kind === "Job") roles.set(p.name, { kind: "one-off" });
    // A ReplicaSet, StatefulSet or DaemonSet keeps its pod running. An owner this rule does not know is counted the same
    // way, so a problem is shown rather than hidden.
    else roles.set(p.name, { kind: "service" });
  }
  return roles;
}

/** The latest scheduled run of each CronJob that failed: every pod of that run failed and none succeeded or is still running. */
export function failedLatestRuns(pods: readonly PodStatusView[], roles = podRoles(pods)): CronRun[] {
  const byJob = new Map<string, { run: CronRun; phases: string[] }>();
  for (const p of pods) {
    const role = roles.get(p.name);
    if (role?.kind !== "cron-run" || !role.latest) continue;
    const entry = byJob.get(role.run.job) ?? { run: role.run, phases: [] };
    entry.phases.push(p.phase);
    byJob.set(role.run.job, entry);
  }
  return [...byJob.values()].filter((e) => e.phases.every((phase) => phase === "Failed")).map((e) => e.run)
    .sort((a, b) => a.cronJob.localeCompare(b.cronJob));
}

const notReady = (p: PodStatusView) => {
  if (p.state !== "Running") return true;
  const [ready, total] = p.ready.split("/").map(Number);
  return !(Number.isFinite(ready) && Number.isFinite(total) && total > 0 && ready === total);
};

/**
 * The one state from one read, or `null` when the session may not read the status at all (401/403): the indicator then
 * shows nothing rather than a false alarm. Rules, in order (health light semantics, 2026-11-14):
 *  - the read failed for any other reason (no answer, timeout, 5xx): down;
 *  - the pod view is unavailable (no service account, the Kubernetes API unreadable): degraded, with the server's reason;
 *  - a service pod (owned by a ReplicaSet, StatefulSet or DaemonSet) is not Running with every container ready, which
 *    covers a pod waiting to restart (CrashLoopBackOff): degraded;
 *  - the latest scheduled run of a CronJob failed: degraded, naming the run. Older runs do not count;
 *  - work is quarantined: degraded;
 *  - otherwise OK. A one-off Job (one not started by a CronJob's schedule) never changes the state; the Platform status
 *    page lists it.
 */
export function platformHealth(read: PlatformRead): PlatformHealth | null {
  if ("error" in read) {
    const status = statusOf(read.error);
    if (status === 401 || status === 403) return null;
    return { state: "down", reason: status === null
      ? "neXus did not answer its status read."
      : `neXus could not read its own status (status ${status}).` };
  }
  const view = read.pods;
  if (!view.available) {
    return { state: "degraded", reason: `Service status cannot be read: ${view.reason ?? "no reason given"}.` };
  }
  const roles = podRoles(view.pods);
  // A finished pod of a service (an evicted pod its controller already replaced) is listed on the page, not counted:
  // the replacement is the pod that must be ready.
  const services = view.pods.filter((p) => roles.get(p.name)?.kind === "service" && p.phase !== "Succeeded" && p.phase !== "Failed");
  const unready = services.filter(notReady);
  const failedRuns = failedLatestRuns(view.pods, roles);
  const quarantined = (view.quarantined_by_role ?? view.modules ?? [])
    .filter((row, index, rows) => rows.findIndex((other) => other.owner === row.owner) === index)
    .reduce((n, row) => n + row.quarantined_permits, 0);
  if (unready.length > 0) {
    const first = unready[0];
    const more = unready.length > 1 ? ` and ${plural(unready.length - 1, "other", "others")}` : "";
    return { state: "degraded", reason: `${plural(unready.length, "service pod", "service pods")} not ready: ${first.name} (${first.state})${more}.` };
  }
  if (failedRuns.length > 0) {
    const first = failedRuns[0];
    const more = failedRuns.length > 1 ? ` and ${plural(failedRuns.length - 1, "other scheduled job", "other scheduled jobs")}` : "";
    return { state: "degraded", reason: `The latest scheduled run of ${first.cronJob} failed (${first.job})${more}.` };
  }
  if (quarantined > 0) {
    return { state: "degraded", reason: `${plural(quarantined, "permit or task lease", "permits or task leases")} quarantined; collection may be blocked.` };
  }
  if (services.length === 0) {
    return { state: "degraded", reason: "No service pod is reported." };
  }
  return { state: "ok", reason: `All ${plural(services.length, "service pod", "service pods")} running and ready.` };
}

const PLATFORM_STATUS_LEAF = NAV_LEAVES.find((leaf) => leaf.id === "admin-platform-status")!;

/** Whether the session may open the Platform status page: the same checks the menu makes for that entry. */
export function canOpenPlatformStatus(session: NavSessionGrants | null | undefined): boolean {
  if (!session) return false;
  return leafShown(PLATFORM_STATUS_LEAF, session.permissions, "") && !leafLocked(PLATFORM_STATUS_LEAF, session);
}

export const PLATFORM_STATUS_HREF = PLATFORM_STATUS_LEAF.href;

const WORDS: Record<PlatformHealthState, string> = { ok: "OK", degraded: "degraded", down: "down" };

/** How often the indicator re-reads the status; the Platform status page itself refreshes every 15 s. */
const REFRESH_MS = 60_000;

/** The presentation alone, so a test can render each state without a server. */
export function PlatformHealthBadge({ health, linkable }: { readonly health: PlatformHealth; readonly linkable: boolean }) {
  const quiet = health.state === "ok";
  const dot = health.state === "ok" ? m3.success : health.state === "degraded" ? m3.warning : m3.error;
  const ink = quiet ? m3.onSurfaceVar : health.state === "degraded" ? m3.warningInk : m3.criticalInk;
  const label = `neXus status: ${WORDS[health.state]}. ${health.reason}`;
  const linkProps = linkable ? { component: "a" as const, href: PLATFORM_STATUS_HREF } : { component: "span" as const, tabIndex: 0 };
  return (
    <Tooltip title={linkable ? `${health.reason} Open Platform status.` : health.reason}>
      <Box {...linkProps} role={linkable ? undefined : "status"} aria-label={label} data-platform-health={health.state}
        sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, flex: "none", height: 28, px: quiet ? 0.75 : 1.25,
              borderRadius: "14px", textDecoration: "none", whiteSpace: "nowrap", fontSize: 12, fontWeight: quiet ? 500 : 600,
              letterSpacing: "0.01em", color: ink,
              bgcolor: quiet ? "transparent" : health.state === "degraded" ? m3.warningContainer : m3.errorContainer,
              cursor: linkable ? "pointer" : "default", outlineOffset: 2,
              "&:hover": linkable ? { bgcolor: quiet ? m3.sc : undefined, textDecoration: "none" } : undefined }}>
        <Box component="span" aria-hidden sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: dot, flex: "none",
          opacity: quiet ? 0.8 : 1 }} />
        <span>{quiet ? "neXus" : `neXus ${WORDS[health.state]}`}</span>
      </Box>
    </Tooltip>
  );
}

/** Reads the status on mount, every minute while the page is visible, and when it is shown again; nothing until the first answer. */
export function PlatformHealthIndicator({ session }: { readonly session: NavSessionGrants }) {
  const [health, setHealth] = useState<PlatformHealth | null>(null);
  useEffect(() => {
    let alive = true;
    const read = () => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      getSystemPods()
        .then((pods) => { if (alive) setHealth(platformHealth({ pods })); })
        .catch((error: unknown) => { if (alive) setHealth(platformHealth({ error })); });
    };
    read();
    const timer = setInterval(read, REFRESH_MS);
    // A page opened in a background tab reads as soon as it is shown, not a minute later.
    const onVisible = () => { if (document.visibilityState === "visible") read(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => { alive = false; clearInterval(timer); document.removeEventListener("visibilitychange", onVisible); };
  }, []);
  if (!health) return null;
  return <PlatformHealthBadge health={health} linkable={canOpenPlatformStatus(session)} />;
}
