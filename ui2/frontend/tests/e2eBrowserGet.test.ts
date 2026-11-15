// @vitest-environment node
import { afterEach, expect, it, vi } from "vitest";
import type { Page } from "@playwright/test";
import { browserGet } from "../e2e/fixtures";

vi.mock("@playwright/test", async () => {
  const { expect } = await import("vitest");
  return { test: { extend: (fixtures: unknown) => fixtures }, expect };
});

afterEach(() => { vi.unstubAllGlobals(); });

// The page runs the evaluated function in this process against a stubbed fetch: no browser, no request.
const page = { evaluate: async (run: (path: string) => unknown, path: string) => run(path) } as unknown as Page;

function answer(status: number) {
  const response = {
    status,
    json: vi.fn(async () => ({ synthetic: true })),
    arrayBuffer: vi.fn(async () => new ArrayBuffer(0)),
    body: { cancel: vi.fn(async () => {}) },
  };
  vi.stubGlobal("fetch", vi.fn(async () => response));
  return response;
}

it("drains a denied transcript so Chromium settles a no-store refusal, and never returns it", async () => {
  // An unread Cache-Control: no-store body stays "loading" in Chromium: the 2026-11-15 preview stall.
  const response = answer(403);
  await expect(browserGet(page, "/jobs/synthetic-job/transcript")).resolves.toEqual({ status: 403, body: null });
  expect(response.arrayBuffer).toHaveBeenCalledOnce();
  expect(response.json).not.toHaveBeenCalled();
  expect(response.body.cancel).not.toHaveBeenCalled();
});

it("cancels an unexpected transcript answer unread", async () => {
  const response = answer(200);
  await expect(browserGet(page, "/jobs/synthetic-job/transcript")).resolves.toEqual({ status: 200, body: null });
  expect(response.body.cancel).toHaveBeenCalledOnce();
  expect(response.arrayBuffer).not.toHaveBeenCalled();
  expect(response.json).not.toHaveBeenCalled();
});

it("reads every other path as JSON", async () => {
  const response = answer(200);
  await expect(browserGet(page, "/devices")).resolves.toEqual({ status: 200, body: { synthetic: true } });
  expect(response.json).toHaveBeenCalledOnce();
});
