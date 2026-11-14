import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { TopAppBar } from "../src/shell/TopAppBar";
import {
  PLATFORM_STATUS_HREF, PlatformHealthBadge, canOpenPlatformStatus, cronRunOf, platformHealth, podRoles, type PlatformHealth,
} from "../src/shell/PlatformHealthIndicator";
import type { PodsView, PodStatusView } from "../src/auth/adminApi";

/**
 * neXus' own health in the top bar (PO 2026-11-13 amendment of navigation decisions F1/F2): one state from the existing
 * `GET /api/v2/system/pods` read, quiet when OK, visible to every signed-in user, a link to Administration > Platform
 * status for a session that may open it. Synthetic pod names only.
 */

const pod = (name: string, over: Partial<PodStatusView> = {}): PodStatusView => ({
  name, component: name.replace(/-\d+$/, ""), phase: "Running", state: "Running", ready: "1/1", restarts: 0, started_at: "2026-11-13T06:00:00Z",
  image_digest: null, cpu_millicores: null, cpu_limit_millicores: null, memory_bytes: null, memory_limit_bytes: null, ...over,
});
const view = (pods: PodStatusView[], over: Partial<PodsView> = {}): PodsView => ({ available: true, read_at: "2026-11-13T09:00:00Z", pods, ...over });
const HEALTHY = view([pod("svc-a-0"), pod("svc-b-0"), pod("db-0"), pod("nightly-check-1", { phase: "Succeeded", state: "Succeeded", ready: "0/1" })]);

describe("the one state", () => {
  it("is OK when every long-running pod is running and ready; a finished one-off pod does not count", () => {
    expect(platformHealth({ pods: HEALTHY })).toEqual({ state: "ok", reason: "All 3 service pods running and ready." });
  });

  it("is degraded when a pod is not running or not fully ready, naming the first", () => {
    const one = platformHealth({ pods: view([pod("svc-a-0"), pod("svc-b-0", { state: "CrashLoopBackOff", ready: "0/1" })]) });
    expect(one).toEqual({ state: "degraded", reason: "1 service pod not ready: svc-b-0 (CrashLoopBackOff)." });
    const two = platformHealth({ pods: view([pod("svc-a-0", { ready: "1/2" }), pod("svc-b-0", { state: "Pending", ready: "0/1" })]) });
    expect(two).toEqual({ state: "degraded", reason: "2 service pods not ready: svc-a-0 (Running) and 1 other." });
  });

  it("is degraded when work is quarantined, counting each owner once", () => {
    const health = platformHealth({ pods: view([pod("svc-a-0")], {
      quarantined_by_role: [{ owner: "worker", quarantined_permits: 2 }, { owner: "worker", quarantined_permits: 2 }, { owner: "policy", quarantined_permits: 0 }],
    }) });
    expect(health).toEqual({ state: "degraded", reason: "2 permits or task leases quarantined; collection may be blocked." });
  });

  it("is degraded, with the server's reason, when the pod view cannot be read; and when no pod is reported", () => {
    expect(platformHealth({ pods: view([], { available: false, reason: "the Kubernetes API could not be read" }) }))
      .toEqual({ state: "degraded", reason: "Service status cannot be read: the Kubernetes API could not be read." });
    expect(platformHealth({ pods: view([]) })?.state).toBe("degraded");
  });

  it("is down when the read fails, and nothing at all when the session may not read it", () => {
    expect(platformHealth({ error: { status: 503, body: {} } })).toEqual({ state: "down", reason: "neXus could not read its own status (status 503)." });
    expect(platformHealth({ error: new TypeError("Failed to fetch") })).toEqual({ state: "down", reason: "neXus did not answer its status read." });
    expect(platformHealth({ error: { status: 401, body: {} } })).toBeNull();
    expect(platformHealth({ error: { status: 403, body: {} } })).toBeNull();
  });
});

// Health light semantics (2026-11-14): what runs a pod decides whether it can turn the light. Synthetic names; the minute
// numbers are real schedule times (29910180 min = 2026-11-13T23:00Z, 29908740 = 2026-11-12T23:00Z, 29910330 = 2026-11-14T01:30Z).
const service = (name: string, over: Partial<PodStatusView> = {}) =>
  pod(name, { owner_kind: "ReplicaSet", owner_name: `${name}-rs`, created_at: "2026-11-13T18:00:00Z", ...over });
const jobPod = (job: string, createdAt: string, phase: "Succeeded" | "Failed" | "Running") =>
  pod(`${job}-pod`, { owner_kind: "Job", owner_name: job, created_at: createdAt, phase, state: phase, ready: phase === "Running" ? "1/1" : "0/1" });
const SERVICES = [service("svc-a"), service("svc-b"), pod("db-0", { owner_kind: "StatefulSet", owner_name: "db", created_at: "2026-11-09T22:00:00Z" })];

describe("what counts against the light", () => {
  it("ignores an old failed scheduled run when the latest run of that CronJob succeeded", () => {
    const pods = [...SERVICES, jobPod("nightly-check-29908740", "2026-11-12T23:00:00Z", "Failed"), jobPod("nightly-check-29910180", "2026-11-13T23:00:00Z", "Succeeded")];
    expect(platformHealth({ pods: view(pods) })).toEqual({ state: "ok", reason: "All 3 service pods running and ready." });
  });

  it("is degraded when the latest scheduled run of a CronJob failed, naming the CronJob and the run", () => {
    const pods = [...SERVICES, jobPod("nightly-check-29908740", "2026-11-12T23:00:00Z", "Succeeded"), jobPod("nightly-check-29910180", "2026-11-13T23:00:01Z", "Failed"),
      jobPod("feed-fetch-29910330", "2026-11-14T01:30:00Z", "Succeeded")];
    expect(platformHealth({ pods: view(pods) })).toEqual({ state: "degraded", reason: "The latest scheduled run of nightly-check failed (nightly-check-29910180)." });
  });

  it("is not degraded while the latest scheduled run is still running", () => {
    const pods = [...SERVICES, jobPod("nightly-check-29908740", "2026-11-12T23:00:00Z", "Failed"), jobPod("nightly-check-29910180", "2026-11-13T23:00:00Z", "Running")];
    expect(platformHealth({ pods: view(pods) })?.state).toBe("ok");
  });

  it("never degrades for a one-off Job, failed or not, even one started by hand from a CronJob", () => {
    const pods = [...SERVICES, jobPod("nightly-check-clean-1110", "2026-11-09T23:01:51Z", "Failed"), jobPod("feed-first3-0825", "2026-11-13T08:25:08Z", "Failed"),
      jobPod("deploy-check", "2026-11-13T18:14:48Z", "Failed"), pod("probe", { owner_kind: null, owner_name: null, phase: "Failed", state: "Error", ready: "0/1" })];
    expect(platformHealth({ pods: view(pods) })).toEqual({ state: "ok", reason: "All 3 service pods running and ready." });
    const roles = podRoles(pods);
    expect(pods.slice(3).map((p) => roles.get(p.name)?.kind)).toEqual(["one-off", "one-off", "one-off", "one-off"]);
  });

  it("is degraded when a Deployment's or StatefulSet's pod is not ready or is waiting to restart", () => {
    expect(platformHealth({ pods: view([service("svc-a"), service("svc-b", { state: "CrashLoopBackOff", ready: "0/1", restarts: 7 })]) }))
      .toEqual({ state: "degraded", reason: "1 service pod not ready: svc-b (CrashLoopBackOff)." });
    expect(platformHealth({ pods: view([service("svc-a"), pod("db-0", { owner_kind: "StatefulSet", owner_name: "db", ready: "0/1" })]) }))
      .toEqual({ state: "degraded", reason: "1 service pod not ready: db-0 (Running)." });
    // A service's not-ready pod outranks a failed scheduled run in the one line.
    expect(platformHealth({ pods: view([service("svc-a", { state: "Pending", ready: "0/1" }), jobPod("nightly-check-29910180", "2026-11-13T23:00:00Z", "Failed")]) })?.reason)
      .toBe("1 service pod not ready: svc-a (Pending).");
  });

  it("does not count an evicted pod its ReplicaSet already replaced", () => {
    const pods = [...SERVICES, service("svc-a-old", { phase: "Failed", state: "Failed", ready: "0/1" })];
    expect(platformHealth({ pods: view(pods) })?.state).toBe("ok");
  });

  it("takes a Job for a scheduled run only when its name's minute matches when the pod was created", () => {
    expect(cronRunOf("nightly-check-29910180", "2026-11-13T23:00:04Z")).toEqual({ cronJob: "nightly-check", job: "nightly-check-29910180", scheduledAt: 29910180 * 60_000 });
    expect(cronRunOf("nightly-check-29910180", "2026-11-12T10:00:00Z")).toBeNull(); // created before its schedule
    expect(cronRunOf("nightly-check-29910180", "2026-11-15T23:00:01Z")).toBeNull(); // more than a day after
    expect(cronRunOf("report-12345678", "2026-11-13T23:00:00Z")).toBeNull(); // digits that are not this pod's schedule
    expect(cronRunOf("nightly-check-clean-1110", "2026-11-09T23:01:51Z")).toBeNull();
    expect(cronRunOf("nightly-check-29910180", null)).toBeNull();
  });
});

const badge = (health: PlatformHealth, linkable = true) =>
  render(<ThemeProvider theme={m3Theme}><PlatformHealthBadge health={health} linkable={linkable} /></ThemeProvider>);

describe("the indicator", () => {
  it("is quiet when OK: the product name and a dot, no word, no fill", () => {
    badge({ state: "ok", reason: "All 3 service pods running and ready." });
    const el = document.querySelector("[data-platform-health]") as HTMLElement;
    expect(el).toHaveAttribute("data-platform-health", "ok");
    expect(el).toHaveTextContent(/^neXus$/);
    expect(el).toHaveAccessibleName("neXus status: OK. All 3 service pods running and ready.");
    expect(el).toHaveStyle({ backgroundColor: "rgba(0, 0, 0, 0)" });
  });

  it.each<[PlatformHealth["state"], string]>([["degraded", "neXus degraded"], ["down", "neXus down"]])("says %s in words when it is not OK", (state, words) => {
    badge({ state, reason: "Synthetic reason." });
    const el = document.querySelector("[data-platform-health]") as HTMLElement;
    expect(el).toHaveTextContent(words);
    expect(el).toHaveAccessibleName(`neXus status: ${state}. Synthetic reason.`);
  });

  it("links to Administration > Platform status only where the session may open it", () => {
    const { unmount } = badge({ state: "degraded", reason: "Synthetic reason." }, true);
    expect(screen.getByRole("link")).toHaveAttribute("href", "?screen=administration&tab=platform-status");
    unmount();
    badge({ state: "degraded", reason: "Synthetic reason." }, false);
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByRole("status")).toHaveAccessibleName("neXus status: degraded. Synthetic reason.");
  });

  it("follows the menu's own checks for the Platform status entry: every signed-in session may open it, no session may not", () => {
    expect(PLATFORM_STATUS_HREF).toBe("?screen=administration&tab=platform-status");
    expect(canOpenPlatformStatus({ permissions: ["Devices"], canReadAdministration: false })).toBe(true);
    expect(canOpenPlatformStatus({ permissions: [], canReadAdministration: false })).toBe(true);
    expect(canOpenPlatformStatus(null)).toBe(false);
  });
});

describe("in the top app bar", () => {
  afterEach(() => vi.unstubAllGlobals());

  const session: SessionInfo = { displayName: "Synthetic viewer", roleTokens: ["role:viewer"], permissions: ["Devices"], canReadAdministration: false, onSignOut: () => {} };
  const stub = (pods: { status: number; body: unknown }) => {
    const fetch = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      const body = url === "/api/v2/system/pods" ? pods : { status: 200, body: {} };
      return Promise.resolve(new Response(JSON.stringify(body.body), { status: body.status }));
    });
    vi.stubGlobal("fetch", fetch);
    return fetch;
  };
  const bar = (withSession: boolean) => render(<ThemeProvider theme={m3Theme}>
    {withSession ? <SessionContext.Provider value={session}><TopAppBar /></SessionContext.Provider> : <TopAppBar />}
  </ThemeProvider>);

  it("shows every signed-in user the state, linked to Platform status", async () => {
    stub({ status: 200, body: HEALTHY });
    bar(true);
    const link = await screen.findByRole("link", { name: /^neXus status: OK\./ });
    expect(link).toHaveAttribute("href", "?screen=administration&tab=platform-status");
  });

  it("shows down when the read fails", async () => {
    stub({ status: 502, body: {} });
    bar(true);
    expect(await screen.findByRole("link", { name: /^neXus status: down\./ })).toHaveTextContent("neXus down");
  });

  it("shows nothing when the server refuses the read, and nothing without a session", async () => {
    const fetch = stub({ status: 403, body: { code: "ACTION_REFUSED" } });
    bar(true);
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url) === "/api/v2/system/pods")).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(document.querySelector("[data-platform-health]")).toBeNull();
  });

  it("makes no status read and draws nothing without a session (previews, the sign-in screen)", async () => {
    const fetch = stub({ status: 200, body: HEALTHY });
    bar(false);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(document.querySelector("[data-platform-health]")).toBeNull();
    expect(fetch.mock.calls.some(([url]) => String(url) === "/api/v2/system/pods")).toBe(false);
  });
});

describe("a page opened in the background", () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  it("reads as soon as it is shown", async () => {
    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    const fetch = vi.fn((input: RequestInfo | URL) => Promise.resolve(new Response(JSON.stringify(String(input) === "/api/v2/system/pods" ? HEALTHY : {}), { status: 200 })));
    vi.stubGlobal("fetch", fetch);
    render(<ThemeProvider theme={m3Theme}><SessionContext.Provider value={{ displayName: "Synthetic viewer", roleTokens: [], permissions: [], onSignOut: () => {} }}>
      <TopAppBar /></SessionContext.Provider></ThemeProvider>);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(fetch.mock.calls.some(([url]) => String(url) === "/api/v2/system/pods")).toBe(false);
    visibility.mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    expect(await screen.findByRole("link", { name: /^neXus status: OK\./ })).toBeInTheDocument();
  });
});
