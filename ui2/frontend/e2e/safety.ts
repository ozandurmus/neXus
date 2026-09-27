import type { BrowserContext } from "@playwright/test";

export function isAiview(body: unknown): boolean {
  if (!body || typeof body !== "object") return false;
  const status = body as Record<string, unknown>;
  return status.authenticated !== false && status.must_change_password !== true
    && Array.isArray(status.role_tokens) && status.role_tokens.includes("role:replay_viewer");
}

export function readAllowed(method: string, url: string, origin: string, resourceType = "fetch"): boolean {
  // src/auth/ only reads /session/status. No session-maintenance POST exists.
  // index.html also loads external icon styles/fonts; these are passive GETs, not API calls.
  return (method === "GET" || method === "HEAD")
    && (new URL(url).origin === origin || resourceType === "stylesheet" || resourceType === "font");
}

export function hasPrivateAddress(text: string): boolean {
  return [...text.matchAll(/\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b/g)].some((match) => {
    const [a, b, c, d] = match.slice(1).map(Number);
    return [a, b, c, d].every((n) => n <= 255)
      && (a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168));
  });
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
