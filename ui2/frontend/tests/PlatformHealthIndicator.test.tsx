import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { TopAppBar } from "../src/shell/TopAppBar";
import {
  PLATFORM_STATUS_HREF, PlatformHealthBadge, canOpenPlatformStatus, platformHealth, type PlatformHealth,
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
