// @vitest-environment node
import { afterEach, expect, it, vi } from "vitest";
import type { Request, Response } from "@playwright/test";
import { dropReplacedDocumentReads, openReads, test } from "../e2e/fixtures";

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
  (args: { context: unknown }, use: (safety: { checkpoint: () => Promise<void> }) => Promise<void>) => Promise<void>,
] }).safety[0];

afterEach(() => { vi.useRealTimers(); vi.unstubAllEnvs(); });

const mainFrame = { name: "main" };
const childFrame = { name: "child" };

function fakeRequest(path: string, resourceType: string, frame: object | null, navigation = false) {
  return {
    url: () => `https://192.0.2.1${path}`, method: () => "GET", resourceType: () => resourceType,
    isNavigationRequest: () => navigation, failure: () => null,
    frame: () => { if (!frame) throw new Error("service worker request"); return frame; },
  };
}
const fakeResponse = (request: ReturnType<typeof fakeRequest>, status: number) =>
  ({ request: () => request, url: request.url, status: () => status });

it("drops the reads a frame's replaced document left open, keeps the navigation and other frames", () => {
  const oldRead = fakeRequest("/devices", "fetch", mainFrame);
  const childRead = fakeRequest("/devices/synthetic-id", "fetch", childFrame);
  const workerRead = fakeRequest("/devices/synthetic-id/lifecycle", "fetch", null);
  const navigation = fakeRequest("/", "document", mainFrame, true);
  const pending = new Set([oldRead, childRead, workerRead, navigation] as unknown as Request[]);
  expect(dropReplacedDocumentReads(pending, fakeResponse(navigation, 200) as unknown as Response)).toBe(true);
  expect([...pending]).toEqual([childRead, workerRead, navigation]);
});

it("keeps every read for a redirect and for a response that is not a navigation", () => {
  const oldRead = fakeRequest("/devices", "fetch", mainFrame);
  const navigation = fakeRequest("/", "document", mainFrame, true);
  const pending = new Set([oldRead, navigation] as unknown as Request[]);
  expect(dropReplacedDocumentReads(pending, fakeResponse(navigation, 302) as unknown as Response)).toBe(false);
  expect(dropReplacedDocumentReads(pending, fakeResponse(oldRead, 200) as unknown as Response)).toBe(false);
  expect(pending.size).toBe(2);
});

async function runFixture(steps: (context: { emit: (event: string, payload: unknown) => void }) => void) {
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
  return fixture({ context }, async () => {
    steps(context);
    vi.setSystemTime(Date.now() + 500);
  });
}

it("a read cancelled by the next screen's navigation does not hold the checkpoint", async () => {
  // Playwright reports neither requestfinished nor requestfailed for it: only the navigation's events arrive.
  const run = runFixture((context) => {
    const poll = fakeRequest("/api/v2/synthetic-poll", "fetch", mainFrame);
    const navigation = fakeRequest("/?screen=inventory", "document", mainFrame, true);
    context.emit("request", poll);
    context.emit("request", navigation);
    context.emit("response", fakeResponse(navigation, 200));
    context.emit("requestfinished", navigation);
  });
  await expect(run).resolves.toBeUndefined();
});

it("a read of the current document that never settles still fails the checkpoint", async () => {
  const run = runFixture((context) => {
    const navigation = fakeRequest("/?screen=inventory", "document", mainFrame, true);
    const read = fakeRequest("/devices", "fetch", mainFrame);
    context.emit("request", navigation);
    context.emit("response", fakeResponse(navigation, 200));
    context.emit("requestfinished", navigation);
    context.emit("request", read);
  });
  await expect(run).rejects.toThrow("Screen reads did not finish (open: GET /devices fetch)");
});

it("names open reads without ids, query values or hosts", () => {
  const reads = [fakeRequest("/devices/synthetic-id/inventory?q=synthetic", "fetch", mainFrame),
    fakeRequest("/devices/other-synthetic-id/inventory", "fetch", mainFrame)];
  expect(openReads(reads as unknown as Request[])).toBe("GET /devices/{id}/inventory fetch");
  expect(openReads([])).toBe("none");
});
