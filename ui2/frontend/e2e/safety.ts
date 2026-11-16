import type { BrowserContext } from "@playwright/test";
import { createHash } from "node:crypto";

export function isAiview(body: unknown): boolean {
  if (!body || typeof body !== "object") return false;
  const status = body as Record<string, unknown>;
  return status.authenticated !== false && status.must_change_password !== true
    && Array.isArray(status.role_tokens) && status.role_tokens.includes("role:replay_viewer");
}

export function readAllowed(method: string, url: string, origin: string, _resourceType = "fetch"): boolean {
  // src/auth/ only reads /session/status. No session-maintenance POST exists.
  // Fonts and stylesheets are self-hosted; no cross-origin requests are allowed.
  return (method === "GET" || method === "HEAD") && new URL(url).origin === origin;
}

export function hasCanaryToken(text: string, digests: ReadonlySet<string>): boolean {
  return [...text.matchAll(/\b[A-Za-z0-9][A-Za-z0-9._:-]*\b/g)].some(([token]) =>
    digests.has(createHash("sha256").update(token).digest("hex")));
}

export const loadErrorText = /something went wrong|uncaught|unhandled (?:api|error)|failed to fetch|networkerror|(?:api|request|read|load) (?:failed|error)|could not be (?:read|loaded)|(?:inventory|overview|workspace|job logs|device registry) unavailable/i;

export async function installReadOnlyGuard(context: BrowserContext, origin: string, report: (message: string) => void) {
  await context.routeWebSocket("**/*", (socket) => {
    report("WebSocket blocked: not a read-only HTTP request");
    socket.close();
  });
  await context.route("**/*", async (route) => {
    const request = route.request();
    if (!readAllowed(request.method(), request.url(), origin, request.resourceType())) {
      report("Read-only guard aborted a non-GET/HEAD or cross-origin request (URL/body withheld)");
      await route.abort("blockedbyclient");
      return;
    }
    if (new URL(request.url()).pathname === "/session/status") {
      try {
        const response = await route.fetch({ maxRedirects: 0 });
        if (response.status() !== 200 || !isAiview(await response.json())) throw new Error();
        await route.fulfill({ response });
      } catch {
        report("Session is no longer an active aiview session");
        await route.abort("blockedbyclient");
      }
      return;
    }
    await route.continue();
  });
}

// Informational read-only notices are not error banners; unknown alert styles fail closed.
export function isErrorAlert(classes: string): boolean {
  return !/\bMuiAlert-(?:standard|outlined|filled)(?:Info|Success)\b/.test(classes);
}

/** An alert as the page renders it: its classes and whether it is an operational alert the status screen lists. */
export type RenderedAlert = { className: string; statusAlert: string | null };

/**
 * Alerts that fail the screen check. The Platform status screen exists to show operational alerts from the status API
 * (disk headroom, artefact quota, quarantined permits, unavailable pod data), so those carry data-status-alert="operational"
 * and are allowed when they are warnings. An error-severity status alert is allowed only when it is also marked: a
 * load-failure alert, an error boundary or any unmarked alert still fails. Informational alerts never fail.
 */
export function failingAlerts(alerts: readonly RenderedAlert[]): RenderedAlert[] {
  return alerts.filter(alert => isErrorAlert(alert.className) && alert.statusAlert !== "operational");
}
