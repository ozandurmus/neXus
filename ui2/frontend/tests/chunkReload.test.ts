import { expect, it, vi } from "vitest";
import { isStaleChunkError, reloadOnceForStaleChunk } from "../src/shell/chunkReload";

const store = () => { const m = new Map<string, string>(); return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v); } }; };
const stale = new TypeError("Failed to fetch dynamically imported module: /assets/RuleUsageScreen-abc.js");

it("recognises a chunk removed by a deploy and nothing else", () => {
  expect(isStaleChunkError(stale)).toBe(true);
  expect(isStaleChunkError(new TypeError("Cannot read properties of undefined"))).toBe(false);
  expect(isStaleChunkError("Failed to fetch dynamically imported module")).toBe(false);
});

it("reloads once, not again within a minute, and again after it", () => {
  const s = store(); const reload = vi.fn();
  expect(reloadOnceForStaleChunk(stale, 1_000_000, s, reload)).toBe(true);
  expect(reloadOnceForStaleChunk(stale, 1_030_000, s, reload)).toBe(false);
  expect(reloadOnceForStaleChunk(stale, 1_070_000, s, reload)).toBe(true);
  expect(reload).toHaveBeenCalledTimes(2);
  expect(reloadOnceForStaleChunk(new Error("other"), 2_000_000, s, reload)).toBe(false);
});
