// @vitest-environment node
import { afterEach, expect, it, vi } from "vitest";
import { test } from "../e2e/fixtures";

// Run the real fixture handlers without launching a browser or making requests.
vi.mock("@playwright/test", async () => {
  const { expect } = await import("vitest");
  return {
    test: { extend: (fixtures: unknown) => fixtures },
    expect: Object.assign((...args: Parameters<typeof expect>) => expect(...args), {
      poll: (read: () => boolean) => expect(read()),
    }),
  };
});

const fixture = (test as unknown as { safety: [
  (args: { context: unknown }, use: (safety: { expectForbiddenTranscript: (path: string) => void }) => Promise<void>) => Promise<void>,
] }).safety[0];

afterEach(() => { vi.useRealTimers(); vi.unstubAllEnvs(); });

it.each([
  ["aborted fetch ignored", "fetch", "net::ERR_ABORTED", undefined, false, undefined],
  ["failed fetch reported", "fetch", "net::ERR_CONNECTION_REFUSED", undefined, false, "request failed"],
  ["timed out fetch reported", "fetch", "net::ERR_TIMED_OUT", undefined, false, "request failed"],
  ["reset fetch reported", "fetch", "net::ERR_CONNECTION_RESET", undefined, false, "request failed"],
  ["failed image ignored", "image", "net::ERR_CONNECTION_REFUSED", undefined, false, undefined],
  ["404 image ignored", "image", undefined, 404, false, undefined],
  ["404 xhr reported", "xhr", undefined, 404, false, "returned HTTP 404"],
  ["expected transcript 403 allowed", "fetch", undefined, 403, true, undefined],
  ["unexpected transcript 403 reported", "fetch", undefined, 403, false, "returned HTTP 403"],
] as const)("%s", async (_name, resourceType, errorText, status, expectedDenial, violation) => {
  vi.useFakeTimers();
  vi.stubEnv("NEXUS_E2E_BASE_URL", "https://192.0.2.1");
  vi.stubEnv("NEXUS_E2E_MODE", "quick");
  const handlers = new Map<string, (payload: unknown) => void>();
  const context = {
    on: (event: string, handler: (payload: unknown) => void) => handlers.set(event, handler),
    emit: (event: string, payload: unknown) => handlers.get(event)!(payload),
    route: vi.fn(), routeWebSocket: vi.fn(), pages: () => [],
    newPage: async () => ({
      goto: async () => ({ status: () => 200, json: async () => ({ role_tokens: ["role:replay_viewer"] }) }),
      close: async () => {},
    }),
  };
  const path = "/jobs/synthetic-job/transcript";
  const request = {
    resourceType: () => resourceType, method: () => "GET",
    url: () => `https://192.0.2.1${path}?synthetic=value`,
    failure: () => errorText ? { errorText } : null,
  };
  const run = fixture({ context }, async (safety) => {
    if (expectedDenial) safety.expectForbiddenTranscript(path);
    context.emit("request", request);
    if (status === undefined) context.emit("requestfailed", request);
    else {
      context.emit("response", { request: () => request, url: request.url, status: () => status });
      context.emit("requestfinished", request);
    }
    vi.setSystemTime(Date.now() + 500);
  });
  if (violation) await expect(run).rejects.toMatchObject({ actual: [`API GET /jobs/{id}/transcript ${violation}`] });
  else await expect(run).resolves.toBeUndefined();
});
