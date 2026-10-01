import { test as base, expect, type Page, type Request } from "@playwright/test";
import { baseURL } from "./settings.mjs";
import { hasCanaryToken, isErrorAlert, isAiview, loadErrorText, installReadOnlyGuard } from "./safety";
import { fullMode, latencyViolation, pathTemplate } from "./latency";
import { requestViolation } from "./requestPath";
import { existsSync, readFileSync } from "node:fs";

const canaryFile = process.env.NEXUS_E2E_CANARY_FILE;
const canaryDigests = canaryFile && existsSync(canaryFile)
  ? new Set(readFileSync(canaryFile, "utf8").trim().split(/\r?\n/)) : null;
if (canaryDigests && [...canaryDigests].some((digest) => !/^[0-9a-f]{64}$/.test(digest))) {
  throw new Error("Invalid E2E canary digest file");
}
if (!canaryDigests) console.info("E2E canary privacy check skipped: digest file is absent");

type Safety = { apiTimings: readonly { path: string; elapsedMs: number }[]; checkpoint: () => Promise<void>; expectForbiddenTranscript: (path: string) => void };

export async function visibleSafety(page: Page) {
  const text = await page.locator("body").innerText();
  // Assert booleans, never actual text: failures must not print a leaked identity.
  if (canaryDigests) expect(hasCanaryToken(text, canaryDigests), "Visible text matches a privacy canary (value withheld)").toBe(false);
  expect(loadErrorText.test(text), "Visible unhandled API/load error text (content withheld)").toBe(false);
  const alerts = await page.getByRole("alert").evaluateAll(elements => elements.map(element => element.className));
  expect(alerts.some(isErrorAlert), "A screen exposes an error/warning alert").toBe(false);
  expect(await page.getByRole("button", { name: /transcript/i }).count(), "Transcript button must be absent under aiview").toBe(0);
}

export const test = base.extend<{ safety: Safety }>({
  // APIRequestContext bypasses context.route. Tests use browser GETs instead.
  request: async ({}) => {
    throw new Error("Use browserGet: the request fixture bypasses the read-only route guard.");
  },
  safety: [async ({ context }, use) => {
    const violations: string[] = [];
    const pending = new Set<Request>();
    const started = new Map<Request, number>();
    const apiTimings: { path: string; elapsedMs: number }[] = [];
    const forbiddenTranscripts = new Set<string>();
    const enforceLatency = fullMode();
    let changedAt = Date.now();
    const relevant = (request: Request) => ["fetch", "xhr", "document"].includes(request.resourceType());
    context.on("request", (request) => {
      if (new URL(request.url()).pathname.startsWith("/api/")) started.set(request, Date.now());
      if (relevant(request)) { pending.add(request); changedAt = Date.now(); } });
    context.on("requestfinished", (request) => {
      const start = started.get(request);
      if (start !== undefined) {
        const elapsedMs = Date.now() - start;
        apiTimings.push({ path: pathTemplate(request.url()), elapsedMs });
        const violation = enforceLatency ? latencyViolation(request.url(), elapsedMs) : null;
        if (violation) violations.push(violation);
      }
      started.delete(request);
      if (pending.delete(request)) changedAt = Date.now();
    });
    context.on("requestfailed", (request) => {
      started.delete(request);
      if (pending.delete(request)) changedAt = Date.now();
      if (relevant(request) && request.failure()?.errorText !== "net::ERR_ABORTED") {
        violations.push(requestViolation(request.method(), request.url()));
      }
    });
    context.on("response", (response) => {
      const expectedDenial = response.status() === 403 && response.request().method() === "GET"
        && forbiddenTranscripts.delete(new URL(response.url()).pathname);
      if (relevant(response.request()) && response.status() >= 400 && !expectedDenial) violations.push(requestViolation(response.request().method(), response.url(), response.status()));
    });
    context.on("page", (page) => page.on("pageerror", () => violations.push("Uncaught page error (content withheld)")));
    await installReadOnlyGuard(context, baseURL(), (message) => violations.push(message));
    // Recheck before EACH test, including filtered runs and sessions that expired after global setup.
    const probe = await context.newPage();
    try {
      const response = await probe.goto(`${baseURL()}/session/status`);
      if (!response || response.status() !== 200 || !isAiview(await response.json())) throw new Error();
    } catch {
      throw new Error("E2E refused: active role:replay_viewer session required before opening a screen.");
    } finally {
      await probe.close();
    }
    const checkpoint = async () => {
      // Do not pass on a heading rendered before its API data. Wait for the current read batch to settle.
      await expect.poll(() => pending.size === 0 && Date.now() - changedAt >= 400,
        { message: "Screen reads did not finish", timeout: 35_000 }).toBe(true);
      expect(violations, "Read-only/session/API/page safety checks").toEqual([]);
      for (const page of context.pages()) if (!page.isClosed()) await visibleSafety(page);
    };
    const expectForbiddenTranscript = (path: string) => {
      if (!/^\/jobs\/[^/]+\/transcript$/.test(path)) throw new Error("Only the transcript denial probe may expect HTTP 403");
      forbiddenTranscripts.add(path);
    };
    try { await use({ checkpoint, expectForbiddenTranscript, apiTimings }); }
    finally { await checkpoint(); }
  }, { auto: true }],
});

export { expect };

export async function visit(page: Page, query: string, heading: string) {
  try { await page.goto(`/?${query}`); }
  catch { throw new Error("Screen navigation failed (URL withheld)"); }
  // A screen may repeat its title in a sub-heading (Inventory: page title and the device list both say "Devices").
  await expect(page.getByRole("heading", { name: heading, exact: true }).first()).toBeVisible();
}

export async function browserGet<T>(page: Page, path: string): Promise<{ status: number; body: T }> {
  try {
    return await page.evaluate(async (path) => {
      const response = await fetch(path, { method: "GET", credentials: "include", redirect: "error" });
      // A denied transcript is never read; neither is an unexpected successful transcript.
      const body = path.endsWith("/transcript") ? null : await response.json();
      return { status: response.status, body };
    }, path);
  } catch { throw new Error("Guarded API read failed (URL/body withheld)"); }
}
